"""Python Runtime 到 Java agent-runtime 的专业 Agent turn 事实客户端。

本模块只负责“把一次专业 Agent turn 的低敏事实登记到 Java 控制面”，不负责保存对话正文、
调用模型或执行工具。这里的边界非常重要：Python 侧可以知道完整的本地执行上下文，但 Java
事实表只需要知道这次 turn 属于谁、处于什么状态、产生了哪些可审计引用以及耗时多久。

客户端采用标准库 :mod:`urllib`，并允许调用方注入 transport。这样生产环境可以替换成带有
mTLS、连接池、重试和服务发现的 HTTP 适配器，单元测试则不需要启动真实 Java 服务。
"""

from __future__ import annotations

import hashlib
import json
import os
import re
from collections.abc import Callable, Mapping
from dataclasses import dataclass, field
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from datasmart_ai_runtime.services.multi_agent.specialist_contracts import (
    SpecialistTurnRequest,
    SpecialistTurnResult,
)


# Java 侧的事实记录是内部控制面接口，不应通过普通浏览器 API 暴露。
DEFAULT_SPECIALIST_TURN_FACT_REGISTRATION_PATH = "/agent-runtime/specialist-turn-facts"

# 这个策略名称会进入本地 receipt/诊断，但不会进入 Java SpecialistTurnFact JSON。
SPECIALIST_TURN_FACT_PAYLOAD_POLICY = (
    "LOW_SENSITIVE_NO_OBJECTIVE_NO_PROMPT_NO_CHAIN_OF_THOUGHT_NO_SQL_"
    "NO_TOOL_ARGUMENTS_NO_STRUCTURED_OUTPUT_NO_CREDENTIALS_NO_SAMPLES_NO_MODEL_OUTPUT"
)

# Java 平台上下文中约定的 Header 名称。服务 token 不能放在 JSON body、URL 或普通 trace 中。
_SOURCE_SERVICE_HEADER = "X-DataSmart-Source-Service"
_INTERNAL_SERVICE_TOKEN_HEADER = "X-DataSmart-Internal-Service-Token"
_TRACE_ID_HEADER = "X-DataSmart-Trace-Id"
_TENANT_ID_HEADER = "X-DataSmart-Tenant-Id"
_APPLICATION_ID_HEADER = "X-DataSmart-Application-Id"
_PROJECT_ID_HEADER = "X-DataSmart-Project-Id"
_ACTOR_ID_HEADER = "X-DataSmart-Actor-Id"
_AGENT_ID_HEADER = "X-DataSmart-Agent-Id"
_AGENT_DELEGATION_ID_HEADER = "X-DataSmart-Agent-Delegation-Id"

# Java SpecialistTurnFact.safeReference 使用同等的字符边界；Python 先过滤，减少无效 HTTP 请求。
_SAFE_REFERENCE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:/-]{0,255}$")

# 摘要不是正文存储位。中英文高风险词都在客户端提前拦截，避免依赖 Java 侧单独兜底。
_FORBIDDEN_TEXT = re.compile(
    r"(?i)(prompt|chain\s*[-_ ]?of\s*[-_ ]?thought|thought\s*process|"
    r"\breasoning\b|\b(sql|select|insert|update|delete|truncate|drop|alter)\b|"
    r"tool\s*[-_ ]?(argument|parameter)s?|credential|password|secret|"
    r"access\s*[-_ ]?token|sample\s*data|raw\s*output|model\s*output|stdout|stderr|"
    r"提示词|思维链|思考过程|模型输出|模型原文|工具参数|工具入参|凭据|密码|密钥|样本数据|原始输出|"
    r"访问令牌|访问token|SQL)",
)


def _urlopen_with_timeout(http_request: Request, timeout_seconds: int) -> Any:
    """以正确的标准库调用方式发送事实登记请求。

    专业 Agent 事实客户端对可注入 transport 约定了 ``(request, timeout)`` 两个位置参数，
    这样测试替身和未来的连接池适配器都可以保持简单。标准库 ``urlopen`` 却把第二个
    位置参数定义成请求体 ``data``，只有使用 ``timeout=`` 关键字才会真正设置超时。
    这个小适配器把两种合同隔离开，避免生产默认路径把超时整数误发成 HTTP body。
    """

    return urlopen(http_request, timeout=max(1, int(timeout_seconds)))


@dataclass(frozen=True)
class JavaSpecialistTurnFactClientSettings:
    """Python 到 Java 专业 Agent turn 事实接口的配置。

    字段级说明：
    - ``enabled``：是否真正发起登记请求。默认关闭，避免本地开发或单元测试误写控制面；关闭时
      客户端仍返回明确的 ``skipped=True`` receipt，而不是假装登记成功。
    - ``base_url``：Java agent-runtime 根地址，只用于拼接 HTTP URL，不会出现在 receipt 的对外摘要中。
    - ``registration_path``：登记接口路径。保留为配置项，是为了兼容直连服务、网关前缀和灰度版本。
    - ``timeout_seconds``：单次同步登记的网络超时时间。事实写回不应无限期阻塞 Python turn。
    - ``service_token``：Java 可信服务守卫使用的内部共享 token。它只进入
      ``X-DataSmart-Internal-Service-Token`` Header，并且通过 ``repr=False`` 防止配置对象意外打印密钥。
    - ``source_service``：Java 用来判断调用方是否在可信服务白名单中的来源服务名。
    - ``fail_closed``：网络、HTTP 或响应契约失败时是否抛出低敏异常。生产审计强一致场景建议开启；
      本地渐进部署可以关闭，让上层收到失败 receipt 并继续使用已有流程。
    """

    enabled: bool = False
    base_url: str = "http://localhost:8091"
    registration_path: str = DEFAULT_SPECIALIST_TURN_FACT_REGISTRATION_PATH
    timeout_seconds: int = 3
    service_token: str | None = field(default=None, repr=False)
    source_service: str = "python-ai-runtime"
    fail_closed: bool = False

    @classmethod
    def from_env(cls, environ: Mapping[str, str] | None = None) -> "JavaSpecialistTurnFactClientSettings":
        """从环境变量构造配置。

        环境变量读取集中在客户端边界，业务 Agent 不需要知道部署方式。优先读取本模块专用配置，
        再回退到项目已经使用的 ``DATASMART_AGENT_RUNTIME_BASE_URL`` 和共享服务 token，保证
        本地 Compose 与后续 Secret 注入都能复用同一套设置。
        """

        source = environ if environ is not None else os.environ
        return cls(
            enabled=_bool_env(
                source.get("DATASMART_AGENT_RUNTIME_SPECIALIST_TURN_FACT_ENABLED")
                or source.get("DATASMART_SPECIALIST_TURN_FACT_ENABLED"),
                default=False,
            ),
            base_url=(
                source.get("DATASMART_AGENT_RUNTIME_SPECIALIST_TURN_FACT_BASE_URL")
                or source.get("DATASMART_AGENT_RUNTIME_BASE_URL")
                or "http://localhost:8091"
            ),
            registration_path=(
                source.get("DATASMART_AGENT_RUNTIME_SPECIALIST_TURN_FACT_PATH")
                or DEFAULT_SPECIALIST_TURN_FACT_REGISTRATION_PATH
            ),
            timeout_seconds=_positive_int(
                source.get("DATASMART_AGENT_RUNTIME_SPECIALIST_TURN_FACT_TIMEOUT_SECONDS"),
                default=3,
            ),
            service_token=(
                source.get("DATASMART_AGENT_RUNTIME_SPECIALIST_TURN_FACT_SERVICE_TOKEN")
                or source.get("DATASMART_AGENT_RUNTIME_INTERNAL_SERVICE_TOKEN")
                or None
            ),
            source_service=(
                source.get("DATASMART_AGENT_RUNTIME_SPECIALIST_TURN_FACT_SOURCE_SERVICE")
                or "python-ai-runtime"
            ),
            fail_closed=_bool_env(
                source.get("DATASMART_AGENT_RUNTIME_SPECIALIST_TURN_FACT_FAIL_CLOSED"),
                default=False,
            ),
        )


class JavaSpecialistTurnFactClientError(RuntimeError):
    """专业 Agent turn 事实客户端的低敏异常。

    异常只保存稳定机器码，不把 URL、HTTP body、Java message、Header、token 或底层异常文本带给
    上层。这样 API 层即使把 ``str(error)`` 写入用户提示，也不会把内部部署信息和敏感正文扩散出去。
    """

    def __init__(self, code: str) -> None:
        """使用稳定错误码初始化异常，禁止调用方把底层异常原文传入。"""

        self.code = _machine_code(code, fallback="SPECIALIST_TURN_FACT_CLIENT_FAILED")
        super().__init__(self.code)


@dataclass(frozen=True)
class SpecialistTurnFactRegistrationReceipt:
    """Java 登记接口返回的结构化低敏 receipt。

    字段级说明：
    - ``attempted``：是否已经真正调用 transport；配置缺失或 disabled 时为 False。
    - ``registered``：Java 是否接受了本次事实。它不表示模型输出成功，只表示控制面写回成功。
    - ``skipped``：客户端被明确关闭时为 True，帮助前端区分“未启用”和“登记失败”。
    - ``duplicate``：Java 按幂等键识别为重复登记时为 True，重复不代表失败。
    - ``status_code``：只保留 HTTP 状态码，不保留响应正文。
    - ``idempotency_key``：本地生成并用于 Java 幂等登记的低敏定位键。
    - ``fact_id``：Java 如果返回低敏事实 ID，则只保留安全引用格式。
    - ``error_code``：失败时的稳定机器码，不包含异常堆栈和上游 message。
    - ``endpoint_configured``：只表示配置是否完整，不返回实际 endpoint。
    """

    attempted: bool
    registered: bool
    skipped: bool
    duplicate: bool
    status_code: int | None
    idempotency_key: str | None
    fact_id: str | None
    error_code: str | None
    endpoint_configured: bool
    message: str
    payload_policy: str = SPECIALIST_TURN_FACT_PAYLOAD_POLICY

    @property
    def posted(self) -> bool:
        """兼容其他 Java receipt 客户端使用的 ``posted`` 语义。"""

        return self.registered

    @property
    def accepted(self) -> bool:
        """提供更贴近 Java ``accepted`` 字段的只读别名。"""

        return self.registered

    def to_summary(self) -> dict[str, Any]:
        """转换成可写入 runtime event 或 API 响应的低敏字典。"""

        return {
            "payloadPolicy": self.payload_policy,
            "attempted": self.attempted,
            "registered": self.registered,
            "posted": self.posted,
            "accepted": self.accepted,
            "skipped": self.skipped,
            "duplicate": self.duplicate,
            "statusCode": self.status_code,
            "idempotencyKey": self.idempotency_key,
            "factId": self.fact_id,
            "errorCode": self.error_code,
            "endpointConfigured": self.endpoint_configured,
            "message": self.message,
        }


class JavaSpecialistTurnFactClient:
    """向 Java agent-runtime 登记专业 Agent turn 低敏事实的 HTTP 客户端。

    本类刻意不接收任意 ``dict`` 作为输入，而是要求现有领域契约中的
    :class:`SpecialistTurnRequest` 和 :class:`SpecialistTurnResult`。这样可以在一个清晰边界内完成
    “允许字段白名单化”，避免调用方把 prompt、SQL、工具参数或模型输出正文顺手塞进远程请求。

    ``transport``/``urlopen_func`` 是可注入的 HTTP 传输函数，签名兼容 ``urlopen(request, timeout)``。
    生产可注入连接池或 mTLS 实现，测试可注入不联网的 fake response。
    """

    def __init__(
        self,
        settings: JavaSpecialistTurnFactClientSettings | None = None,
        *,
        transport: Callable[..., Any] | None = None,
        urlopen_func: Callable[..., Any] | None = None,
    ) -> None:
        """创建客户端并绑定配置与 HTTP transport。

        ``transport`` 是更通用的命名；``urlopen_func`` 保留是为了和仓库已有 Java receipt 客户端
        的测试注入方式保持一致。两者同时传入时直接拒绝，避免测试到底调用哪一个产生歧义。
        """

        if transport is not None and urlopen_func is not None:
            raise ValueError("transport 和 urlopen_func 只能注入一个")
        self._settings = settings or JavaSpecialistTurnFactClientSettings.from_env()
        # ``urllib.request.urlopen`` 的第二个位置参数是 data，而不是 timeout。
        # 如果把内部测试 transport 的 ``(request, timeout)`` 约定直接套到标准库函数上，
        # Python 会把 timeout 整数当作请求体，最终在真正发送 HTTP 请求时抛出
        # ``TypeError: memoryview: a bytes-like object is required``。这里保留可注入
        # transport 的简单二参数合同，同时为标准库默认实现增加一个明确的关键字参数适配层。
        self._transport = transport or urlopen_func or _urlopen_with_timeout

    def __call__(
        self,
        request: SpecialistTurnRequest,
        result: SpecialistTurnResult,
    ) -> SpecialistTurnFactRegistrationReceipt:
        """把客户端实例适配成协调器可直接注入的结果 sink。

        ``SpecialistAgentCoordinator`` 的登记协议只有两个参数：本次受控 turn 请求和专业 Agent
        结果。客户端内部仍然沿用 ``register`` 的低敏白名单、服务 token Header、幂等键以及
        fail-open/fail-closed 配置，因此把 ``client`` 直接作为 ``result_sink=client`` 传入时，
        不会因为增加一个回调适配层而绕过原有安全边界。

        返回值是结构化 receipt。协调器会记录事实登记是否尝试/成功，但不会把 receipt 的失败状态
        改写成专业 Agent 的业务结果；fail-open receipt 由协调器旁路继续，fail-closed 则由
        ``register`` 抛出低敏机器码异常。
        """

        return self.register(request, result)

    def build_payload(
        self,
        request: SpecialistTurnRequest,
        result: SpecialistTurnResult,
    ) -> dict[str, Any]:
        """把请求和结果映射为 Java ``SpecialistTurnFact`` 白名单 payload。

        这里是最核心的安全边界。允许发送的只有身份定位、状态、低敏摘要、模型元数据、引用和耗时；
        方法不会读取 ``request.objective``、``request.context_summary``、``result.structured_output``，
        也不会读取工具活动的 ``public_summary``。即使调用方把敏感正文放进这些字段，也不会进入
        JSON 序列化流程。

        ``tenantId``/``projectId`` 如果是纯数字字符串会转换为 JSON number，以匹配 Java record 的
        ``Long`` 字段；``applicationId`` 则是 V6 事实合同新增的强制应用边界，必须直接来自受信委派
        scope 且是正整数。客户端不会从用户正文、模型输出或 projectId 推断应用，也不会在缺失时回退
        到某个默认应用。
        """

        if not isinstance(request, SpecialistTurnRequest) or not isinstance(result, SpecialistTurnResult):
            raise JavaSpecialistTurnFactClientError("SPECIALIST_TURN_FACT_INPUT_TYPE_INVALID")

        request_turn_id = _required_text(request.turn_id, "turn_id")
        result_turn_id = _required_text(result.turn_id, "result.turn_id")
        if request_turn_id != result_turn_id:
            raise JavaSpecialistTurnFactClientError("SPECIALIST_TURN_FACT_TURN_ID_MISMATCH")

        agent_id = _safe_reference(result.agent_id)
        if not agent_id:
            raise JavaSpecialistTurnFactClientError("SPECIALIST_TURN_FACT_AGENT_ID_INVALID")
        request_role = _enum_text(request.role)
        role = _enum_text(result.role)
        status = _enum_text(result.status)
        if not role or not status:
            raise JavaSpecialistTurnFactClientError("SPECIALIST_TURN_FACT_ROLE_OR_STATUS_INVALID")
        if request_role != role:
            raise JavaSpecialistTurnFactClientError("SPECIALIST_TURN_FACT_ROLE_MISMATCH")

        scope = request.scope
        application_id = _required_positive_scope_id(scope.application_id, "scope.application_id")
        delegation_id = _safe_reference(scope.delegation_id)
        evidence_refs = _merge_references(
            request.evidence_references,
            result.evidence_references,
            _tool_evidence_references(result),
        )

        model_name, model_invocation_id = _model_metadata(result.model_invocation_summary)
        return {
            # Java record 使用 userId 表达被代表的业务用户；这里的值就是请求 scope.actor_id。
            "userId": _required_text(scope.actor_id, "scope.actor_id"),
            "tenantId": _wire_scope_id(scope.tenant_id),
            "applicationId": application_id,
            "projectId": _wire_scope_id(scope.project_id),
            "sessionId": _required_text(request.session_id, "session_id"),
            "runId": _required_text(request.run_id, "run_id"),
            "turnId": request_turn_id,
            "idempotencyKey": self.idempotency_key(request, result),
            "agentId": agent_id,
            "role": role,
            "delegationId": delegation_id,
            "status": status,
            "lowSensitiveSummary": _safe_public_summary(result.public_summary, status),
            "modelInvocationId": model_invocation_id,
            "modelName": model_name,
            "toolActivitySummaryRefs": _tool_activity_summary_refs(result),
            "evidenceRefs": evidence_refs,
            "durationMillis": max(0, _non_negative_int(result.duration_ms)),
        }

    def build_registration_payload(
        self,
        request: SpecialistTurnRequest,
        result: SpecialistTurnResult,
    ) -> dict[str, Any]:
        """提供语义更明确的 payload 构造别名，便于 Java 合同测试直接调用。"""

        return self.build_payload(request, result)

    def idempotency_key(
        self,
        request: SpecialistTurnRequest,
        result: SpecialistTurnResult,
    ) -> str:
        """根据 turn 不可变身份生成稳定幂等键。

        重试同一 turn 必须生成同一个 key，Java 才能把网络重试识别为幂等更新而不是新事实。正常短 ID
        保留可读形式；如果 ID 过长或含不安全字符，则只保留 SHA-256 引用，避免把正文或异常字符放入索引键。
        """

        parts = (
            _required_text(request.session_id, "session_id"),
            _required_text(request.run_id, "run_id"),
            _required_text(result.turn_id, "result.turn_id"),
            _required_text(result.agent_id, "agent_id"),
        )
        candidate = "specialist-turn:" + ":".join(parts)
        if _SAFE_REFERENCE.fullmatch(candidate) and len(candidate) <= 320:
            return candidate
        digest = hashlib.sha256("|".join(parts).encode("utf-8")).hexdigest()
        return f"specialist-turn:sha256:{digest}"

    def register(
        self,
        request: SpecialistTurnRequest,
        result: SpecialistTurnResult,
        *,
        trace_id: str | None = None,
    ) -> SpecialistTurnFactRegistrationReceipt:
        """登记一次专业 Agent turn，并按配置执行 fail-open/fail-closed。

        处理顺序是：
        1. disabled 时不构造网络请求，返回 ``skipped=True``；
        2. 检查 endpoint 和内部 token 配置；
        3. 构造低敏白名单 payload；
        4. 发送 POST 并解析 Java 统一响应；
        5. 网络、HTTP 或响应契约失败时，fail-open 返回失败 receipt，fail-closed 抛出仅含机器码的异常。
        """

        derived_key = self._best_effort_idempotency_key(request, result)
        endpoint_configured = bool(
            str(self._settings.base_url or "").strip() and str(self._settings.registration_path or "").strip()
        )
        if not self._settings.enabled:
            return SpecialistTurnFactRegistrationReceipt(
                attempted=False,
                registered=False,
                skipped=True,
                duplicate=False,
                status_code=None,
                idempotency_key=derived_key,
                fact_id=None,
                error_code=None,
                endpoint_configured=endpoint_configured,
                message="专业 Agent turn 事实客户端未启用，已明确跳过 Java 登记。",
            )

        if not endpoint_configured:
            return self._failure("SPECIALIST_TURN_FACT_ENDPOINT_NOT_CONFIGURED", derived_key, attempted=False)
        if not str(self._settings.service_token or "").strip():
            return self._failure("SPECIALIST_TURN_FACT_SERVICE_TOKEN_NOT_CONFIGURED", derived_key, attempted=False)

        payload = self.build_payload(request, result)
        http_request = self._build_http_request(payload, trace_id=trace_id)
        try:
            status_code, response_body = self._send(http_request)
        except HTTPError:
            return self._failure("SPECIALIST_TURN_FACT_HTTP_ERROR", derived_key, attempted=True)
        except URLError:
            return self._failure("SPECIALIST_TURN_FACT_NETWORK_ERROR", derived_key, attempted=True)
        except TimeoutError:
            return self._failure("SPECIALIST_TURN_FACT_TIMEOUT", derived_key, attempted=True)
        except Exception:  # pragma: no cover - 具体异常由注入 transport 决定
            return self._failure("SPECIALIST_TURN_FACT_HTTP_POST_FAILED", derived_key, attempted=True)

        if status_code < 200 or status_code >= 300:
            return self._failure(
                "SPECIALIST_TURN_FACT_STATUS_NOT_2XX",
                derived_key,
                attempted=True,
                status_code=status_code,
            )
        return self._parse_success(response_body, status_code, derived_key)

    def register_turn_fact(
        self,
        request: SpecialistTurnRequest,
        result: SpecialistTurnResult,
        *,
        trace_id: str | None = None,
    ) -> SpecialistTurnFactRegistrationReceipt:
        """提供动词更明确的登记别名，方便编排器按业务语义调用。"""

        return self.register(request, result, trace_id=trace_id)

    def post_fact(
        self,
        request: SpecialistTurnRequest,
        result: SpecialistTurnResult,
        *,
        trace_id: str | None = None,
    ) -> SpecialistTurnFactRegistrationReceipt:
        """兼容其他 HTTP receipt 客户端的 ``post_fact`` 命名。"""

        return self.register(request, result, trace_id=trace_id)

    def _build_http_request(self, payload: Mapping[str, Any], *, trace_id: str | None) -> Request:
        """构造内部 POST 请求，保证 token 只存在于认证 Header。

        请求正文严格来自 ``build_payload`` 的白名单。Header 里的 token 不会被写入 receipt、异常或
        日志；``trace_id`` 仅用于链路关联，并通过控制字符过滤防止 Header 注入。
        """

        base_url = str(self._settings.base_url or "").rstrip("/")
        path = str(self._settings.registration_path or "").strip()
        headers = {
            "Accept": "application/json",
            "Content-Type": "application/json; charset=utf-8",
            _SOURCE_SERVICE_HEADER: _header_text(self._settings.source_service, maximum=120)
            or "python-ai-runtime",
        }
        _put_header(headers, _TRACE_ID_HEADER, trace_id)
        # Java Controller 会在这些 Header 出现时校验它们与 body 完全一致；发送它们可以把错误范围
        # 尽早暴露在控制面，而不是让一个格式正确但身份错误的 body 进入 Store。
        _put_header(headers, _TENANT_ID_HEADER, payload.get("tenantId"))
        _put_header(headers, _APPLICATION_ID_HEADER, payload.get("applicationId"))
        _put_header(headers, _PROJECT_ID_HEADER, payload.get("projectId"))
        _put_header(headers, _ACTOR_ID_HEADER, payload.get("userId"))
        _put_header(headers, _AGENT_ID_HEADER, payload.get("agentId"))
        _put_header(headers, _AGENT_DELEGATION_ID_HEADER, payload.get("delegationId"))
        # Java SpecialistTurnFactTrustedServiceGuard 读取这个自定义 Header，而非 Authorization。
        _put_header(headers, _INTERNAL_SERVICE_TOKEN_HEADER, self._settings.service_token)
        return Request(
            url=f"{base_url}/{path.lstrip('/')}",
            data=json.dumps(dict(payload), ensure_ascii=False).encode("utf-8"),
            headers=headers,
            method="POST",
        )

    def _send(self, http_request: Request) -> tuple[int, str]:
        """通过注入的 transport 发送请求，并只返回状态码与待解析响应正文。

        现有仓库 fake transport 通常返回上下文管理器；为了方便轻量 adapter，本方法也接受直接返回
        response 对象的实现。正文只在当前方法内解析，失败时绝不进入异常文本或 receipt。
        """

        response_or_context = self._transport(http_request, max(1, int(self._settings.timeout_seconds)))
        if hasattr(response_or_context, "__enter__"):
            with response_or_context as response:
                return _read_http_response(response)
        return _read_http_response(response_or_context)

    def _parse_success(
        self,
        response_body: str,
        status_code: int,
        idempotency_key: str,
    ) -> SpecialistTurnFactRegistrationReceipt:
        """解析 Java ``PlatformApiResponse``，只保留低敏登记结果字段。"""

        try:
            envelope = json.loads(response_body)
        except (UnicodeDecodeError, TypeError, ValueError):
            return self._failure("SPECIALIST_TURN_FACT_RESPONSE_NOT_JSON", idempotency_key, attempted=True)
        if not isinstance(envelope, Mapping) or _response_code(envelope) != 0:
            return self._failure("SPECIALIST_TURN_FACT_PLATFORM_REJECTED", idempotency_key, attempted=True)
        data = envelope.get("data")
        if not isinstance(data, Mapping):
            return self._failure("SPECIALIST_TURN_FACT_RESPONSE_DATA_INVALID", idempotency_key, attempted=True)

        registered = _first_bool(data, "accepted", "registered", "saved", default=True)
        duplicate = bool(data.get("duplicate", False))
        return SpecialistTurnFactRegistrationReceipt(
            attempted=True,
            registered=registered,
            skipped=False,
            duplicate=duplicate,
            status_code=status_code,
            idempotency_key=idempotency_key,
            fact_id=_safe_reference(data.get("factId") or data.get("id")),
            error_code=None if registered else "SPECIALIST_TURN_FACT_NOT_ACCEPTED",
            endpoint_configured=True,
            message=(
                "Java agent-runtime 已登记专业 Agent turn 低敏事实。"
                if registered
                else "Java agent-runtime 未接受专业 Agent turn 事实。"
            ),
        )

    def _failure(
        self,
        error_code: str,
        idempotency_key: str | None,
        *,
        attempted: bool,
        status_code: int | None = None,
    ) -> SpecialistTurnFactRegistrationReceipt:
        """按 fail-closed 配置生成失败结果或抛出低敏异常。"""

        machine_code = _machine_code(error_code, fallback="SPECIALIST_TURN_FACT_CLIENT_FAILED")
        if self._settings.fail_closed:
            raise JavaSpecialistTurnFactClientError(machine_code)
        return SpecialistTurnFactRegistrationReceipt(
            attempted=attempted,
            registered=False,
            skipped=False,
            duplicate=False,
            status_code=status_code,
            idempotency_key=idempotency_key,
            fact_id=None,
            error_code=machine_code,
            endpoint_configured=bool(
                str(self._settings.base_url or "").strip() and str(self._settings.registration_path or "").strip()
            ),
            message="专业 Agent turn 事实登记未完成，已返回低敏失败码。",
        )

    def _best_effort_idempotency_key(
        self,
        request: SpecialistTurnRequest,
        result: SpecialistTurnResult,
    ) -> str | None:
        """在 disabled 或配置错误路径下尽力生成 receipt 定位键，不让输入校验遮蔽主错误。"""

        try:
            return self.idempotency_key(request, result)
        except Exception:
            return None


def _model_metadata(summary: Mapping[str, Any]) -> tuple[str | None, str | None]:
    """从模型摘要白名单中提取模型名和 Provider 调用 ID。

    只读取明确的元数据键以及 ``selections/invocations/calls`` 中的同名键，不递归遍历任意对象，
    从根上避免把 raw output、prompt、structured output 或工具参数正文误当成元数据发送。
    """

    if not isinstance(summary, Mapping):
        return None, None
    model_name = _first_safe_reference(
        summary,
        "actualModelName",
        "selectedModelName",
        "modelName",
        "model_name",
    )
    invocation_id = _first_safe_reference(
        summary,
        "modelInvocationId",
        "providerInvocationId",
        "providerCallId",
        "modelCallId",
        "invocationId",
        "callId",
        "model_invocation_id",
        "call_id",
    )
    for container_name in ("selections", "invocations", "calls"):
        values = summary.get(container_name)
        if not isinstance(values, (list, tuple)):
            continue
        for value in values:
            if not isinstance(value, Mapping):
                continue
            model_name = model_name or _first_safe_reference(
                value,
                "actualModelName",
                "selectedModelName",
                "modelName",
                "model_name",
            )
            invocation_id = invocation_id or _first_safe_reference(
                value,
                "modelInvocationId",
                "providerInvocationId",
                "providerCallId",
                "modelCallId",
                "invocationId",
                "callId",
                "model_invocation_id",
                "call_id",
            )
            if model_name and invocation_id:
                return model_name, invocation_id
    return model_name, invocation_id


def _tool_activity_summary_refs(result: SpecialistTurnResult) -> list[str]:
    """把工具活动压缩成 Java 可保存的引用型摘要。

    Java DTO 只接受 ``toolActivitySummaryRefs``，因此这里不发送工具 publicSummary、参数、返回值或样本；
    每条引用只表达工具编码和最终状态，并保留发生顺序。duration 仍由整次 turn 的 durationMillis 表达。
    """

    references: list[str] = []
    for activity in result.tool_activities:
        tool_name = _safe_reference(getattr(activity, "tool_name", None))
        status = _safe_reference(_enum_text(getattr(activity, "status", None)))
        if not tool_name or not status:
            continue
        reference = f"tool-activity:{tool_name}:{status}"
        if reference not in references:
            references.append(reference)
        if len(references) >= 100:
            break
    return references


def _tool_evidence_references(result: SpecialistTurnResult) -> tuple[Any, ...]:
    """仅读取工具活动的 evidenceReference，不读取工具活动正文。"""

    return tuple(
        getattr(activity, "evidence_reference", None)
        for activity in result.tool_activities
        if getattr(activity, "evidence_reference", None)
    )


def _merge_references(*groups: Any) -> list[str]:
    """合并、去重并过滤证据引用，最多保留 Java 事实表允许的 100 条。"""

    references: list[str] = []
    for group in groups:
        if not isinstance(group, (list, tuple)):
            continue
        for value in group:
            reference = _safe_reference(value)
            if reference and reference not in references:
                references.append(reference)
            if len(references) >= 100:
                return references
    return references


def _safe_public_summary(value: Any, status: str) -> str:
    """保留低敏短摘要，遇到正文信号时使用静态替代语句。

    ``publicSummary`` 是唯一允许出现可读文本的 Java 字段，但它仍然不能成为模型回答正文的归档位。
    因此安全检查失败时不发送原文，而是发送只表达状态的固定句子。
    """

    text = _bounded_text(value, maximum=2048)
    if not text or _FORBIDDEN_TEXT.search(text):
        return f"专业 Agent turn 已结束，状态为 {status}。"
    return text


def _first_safe_reference(mapping: Mapping[str, Any], *keys: str) -> str | None:
    """按优先级从映射中读取第一个合法引用。"""

    for key in keys:
        value = _safe_reference(mapping.get(key))
        if value:
            return value
    return None


def _safe_reference(value: Any) -> str | None:
    """校验低敏 ID/引用，拒绝正文、控制字符和高风险敏感词。"""

    text = _bounded_text(value, maximum=256)
    if not text or _FORBIDDEN_TEXT.search(text) or not _SAFE_REFERENCE.fullmatch(text):
        return None
    return text


def _wire_scope_id(value: Any) -> int | str | None:
    """把租户/项目 ID 转成 Java Long 友好的 number，非法字符串保留为低敏值等待 Java 拒绝。

    保留非法值而不是静默改成 ``None``，是为了让调用方和 Java 控制面看到真实的契约错误；这个值不是
    prompt 或业务正文，且 Java 的 requiredPositive 会 fail-closed。数字字符串则转换为 JSON number。
    """

    text = _bounded_text(value, maximum=128)
    if not text:
        return None
    try:
        return int(text)
    except (TypeError, ValueError):
        return _safe_reference(text)


def _required_positive_scope_id(value: Any, field_name: str) -> int:
    """把受信控制面的必填作用域 ID 规范化为 Java ``Long`` 可接收的正整数。

    ``applicationId`` 是租户与项目之间不可省略的隔离边界。这里先复用 ``_wire_scope_id`` 完成
    控制字符过滤和数字字符串转换，再明确拒绝缺失值、布尔值、非数字引用、零与负数。这样错误会在
    Python 控制面以稳定机器码 fail-closed，而不是发送一个缺少应用 Header 的请求后依赖 Java 400。

    该函数只接收由 Gateway 重建并写入 ``SpecialistDelegationScope`` 的值；调用方不得把用户正文、
    模型输出、任务参数或 projectId 传进来充当 applicationId。
    """

    normalized = _wire_scope_id(value)
    if isinstance(normalized, bool) or not isinstance(normalized, int) or normalized <= 0:
        machine_field = re.sub(r"[^A-Za-z0-9]+", "_", str(field_name)).strip("_").upper()
        raise JavaSpecialistTurnFactClientError(
            f"SPECIALIST_TURN_FACT_{machine_field}_POSITIVE_INTEGER_REQUIRED"
        )
    return normalized


def _required_text(value: Any, field_name: str) -> str:
    """校验低敏必填标识，统一产生不含原始值的错误。"""

    text = _bounded_text(value, maximum=320)
    if not text:
        raise JavaSpecialistTurnFactClientError(f"SPECIALIST_TURN_FACT_{field_name.upper()}_MISSING")
    return text


def _bounded_text(value: Any, *, maximum: int) -> str:
    """提取短文本并拒绝换行控制符，防止 Header/JSON 记录被注入。"""

    if value is None:
        return ""
    text = str(value).strip()
    if not text or len(text) > maximum or "\r" in text or "\n" in text:
        return ""
    return text


def _enum_text(value: Any) -> str:
    """读取 Enum 的 value 并统一为 Java 可接受的短文本。"""

    raw = getattr(value, "value", value)
    return _bounded_text(raw, maximum=160)


def _non_negative_int(value: Any) -> int:
    """把耗时等计数规范化为非负整数。"""

    try:
        return max(0, int(value))
    except (TypeError, ValueError):
        return 0


def _first_bool(mapping: Mapping[str, Any], *keys: str, default: bool) -> bool:
    """按 Java 响应字段优先级读取布尔结果。"""

    for key in keys:
        if key in mapping:
            return bool(mapping[key])
    return default


def _response_code(envelope: Mapping[str, Any]) -> int | None:
    """读取平台统一响应 code，非法值按失败处理。"""

    try:
        return int(envelope.get("code"))
    except (TypeError, ValueError):
        return None


def _read_http_response(response: Any) -> tuple[int, str]:
    """读取 fake/真实 HTTP response 的状态码和 UTF-8 正文。"""

    status_code = int(getattr(response, "status", 200))
    body = response.read()
    if isinstance(body, bytes):
        return status_code, body.decode("utf-8")
    return status_code, str(body)


def _put_header(headers: dict[str, str], name: str, value: Any) -> None:
    """只有安全短文本才写入 Header，避免空值和控制字符进入请求。"""

    text = _header_text(value, maximum=512)
    if text:
        headers[name] = text


def _header_text(value: Any, *, maximum: int) -> str | None:
    """校验 Header 值；token 不走此函数的返回摘要，只在请求构造时使用。"""

    text = _bounded_text(value, maximum=maximum)
    return text or None


def _machine_code(value: Any, *, fallback: str) -> str:
    """把内部错误码规范化为不含正文的机器码。"""

    text = _bounded_text(value, maximum=100).upper()
    if re.fullmatch(r"[A-Z0-9][A-Z0-9_.-]{0,99}", text):
        return text
    return fallback


def _bool_env(value: Any, *, default: bool) -> bool:
    """解析常见布尔环境变量写法。"""

    text = str(value).strip().lower() if value is not None else ""
    if text in {"1", "true", "yes", "on", "enabled"}:
        return True
    if text in {"0", "false", "no", "off", "disabled"}:
        return False
    return default


def _positive_int(value: Any, *, default: int) -> int:
    """解析正整数配置，非法或非正值回退到稳定默认值。"""

    try:
        parsed = int(str(value).strip())
        return parsed if parsed > 0 else default
    except (TypeError, ValueError):
        return default


# 兼容更简短的调用方命名，同时把推荐的 Java 前缀名称保留为文档主入口。
SpecialistTurnFactClientSettings = JavaSpecialistTurnFactClientSettings
SpecialistTurnFactClientError = JavaSpecialistTurnFactClientError
SpecialistTurnFactClient = JavaSpecialistTurnFactClient
SpecialistTurnFactReceipt = SpecialistTurnFactRegistrationReceipt


def specialist_turn_fact_client_settings_from_env(
    environ: Mapping[str, str] | None = None,
) -> JavaSpecialistTurnFactClientSettings:
    """返回环境变量配置工厂，便于应用装配层和单元测试调用。"""

    return JavaSpecialistTurnFactClientSettings.from_env(environ)


__all__ = [
    "DEFAULT_SPECIALIST_TURN_FACT_REGISTRATION_PATH",
    "SPECIALIST_TURN_FACT_PAYLOAD_POLICY",
    "JavaSpecialistTurnFactClientSettings",
    "JavaSpecialistTurnFactClientError",
    "SpecialistTurnFactRegistrationReceipt",
    "JavaSpecialistTurnFactClient",
    "SpecialistTurnFactClientSettings",
    "SpecialistTurnFactClientError",
    "SpecialistTurnFactClient",
    "SpecialistTurnFactReceipt",
    "specialist_turn_fact_client_settings_from_env",
]
