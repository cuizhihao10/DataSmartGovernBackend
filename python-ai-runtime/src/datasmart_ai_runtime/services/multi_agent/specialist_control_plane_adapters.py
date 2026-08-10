"""data-sync 控制面的生产 HTTP 适配器。

本模块是 Specialist Protocol 与 Java ``data-sync`` Controller 之间的边界层。
边界层的核心原则是：Python 只携带已经通过 Specialist 合同校验的身份上下文，
只读取 Java 控制面返回的低敏事实，不把任务配置、SQL、凭据、样本行或模型原文
放进 HTTP 请求、URL、异常和返回对象。

这里故意没有实现 Recovery 的执行器。恢复动作必须继续经过 Java 的审批、幂等键、
outbox 和执行器链路；本模块只提供 ``FailureDiagnosticClient`` 的只读实现。
"""

from __future__ import annotations

import hashlib
import json
import math
import os
import re
import socket
from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass, field, replace
from datetime import datetime, timezone
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlencode, urlsplit
from urllib.request import Request, urlopen

from datasmart_ai_runtime.services.multi_agent.specialists.monitor_agent import (
    TaskKind,
    TaskLifecycleStatus,
    TaskMonitoringClient,
    TaskMonitoringQuery,
    TaskMonitoringSnapshot,
)
from datasmart_ai_runtime.services.multi_agent.specialists.precheck_agent import (
    PrecheckCheckItem,
    PrecheckCheckStatus,
    PrecheckControlPlaneClient,
    PrecheckControlPlaneRequest,
    PrecheckControlPlaneResult,
)
from datasmart_ai_runtime.services.multi_agent.specialists.recovery_agent import (
    FailureDiagnosticClient,
    FailureDiagnosticRequest,
    FailureDiagnosticResult,
)


# Java data-sync 的真实路由。路径参数必须使用 quote 转义，查询参数只允许在
# 各个客户端明确列出的白名单中出现，不能把 context_summary 原样拼到 URL。
PRECHECK_PATH_TEMPLATE = "/sync-tasks/{taskId}/precheck"
DIAGNOSIS_PATH_TEMPLATE = "/sync-tasks/{taskId}/agent-diagnosis"
EXECUTIONS_PATH_TEMPLATE = "/sync-tasks/{taskId}/executions"
LOGS_PATH_TEMPLATE = "/sync-tasks/{taskId}/executions/{executionId}/logs"
OBJECTS_PATH_TEMPLATE = "/sync-tasks/{taskId}/executions/{executionId}/objects"


# 这些 Header 与 platform-common 的 PlatformContextHeaders 保持同名。
SOURCE_SERVICE_HEADER = "X-DataSmart-Source-Service"
INTERNAL_SERVICE_TOKEN_HEADER = "X-DataSmart-Internal-Service-Token"
TRACE_ID_HEADER = "X-DataSmart-Trace-Id"
TENANT_ID_HEADER = "X-DataSmart-Tenant-Id"
PROJECT_ID_HEADER = "X-DataSmart-Project-Id"
ACTOR_ID_HEADER = "X-DataSmart-Actor-Id"
DATA_SCOPE_LEVEL_HEADER = "X-DataSmart-Data-Scope-Level"
AUTHORIZED_PROJECT_IDS_HEADER = "X-DataSmart-Authorized-Project-Ids"
AGENT_ID_HEADER = "X-DataSmart-Agent-Id"
AGENT_SESSION_ID_HEADER = "X-DataSmart-Agent-Session-Id"
AGENT_RUN_ID_HEADER = "X-DataSmart-Agent-Run-Id"
AGENT_DELEGATION_ID_HEADER = "X-DataSmart-Agent-Delegation-Id"


# 这是适配器对外使用的稳定策略说明。它不是下游返回正文的复制品，因而不会把
# Java message、RAG query 或异常栈带回 Python Specialist。
CONTROL_PLANE_PAYLOAD_POLICY = (
    "LOW_SENSITIVE_CONTROL_PLANE_NO_CREDENTIALS_NO_SQL_NO_SAMPLES_NO_RAW_BODY"
)

_SAFE_REFERENCE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:/-]{0,255}$")
_SAFE_CODE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$")
# 凭据通常不是一个单独的关键词，而是 ``password=...``、``Bearer ...`` 或连接串。
# 先整体匹配“键和值”，再交给同一个替换器，避免只遮住 key 却把 secret value 留在摘要里。
_SECRET_TEXT = re.compile(
    r"(?i)(?:"
    r"\b(?:api[_-]?key|authorization|credential|password|passwd|secret|token)\b"
    r"\s*(?:[:=]\s*|\s+)(?:bearer\s+)?(?:\"[^\"]*\"|'[^']*'|[^\s,;]+)|"
    r"\bbearer\s+[^\s,;]+|"
    r"\bjdbc:[^\s,;]+|\b(?:postgresql|mysql|redis)://[^\s,;]+"
    r")"
)
_SQL_TEXT = re.compile(
    r"(?is)\b(?:select|insert|update|delete|drop|alter|truncate|create|merge)\b"
    r".{0,160}\b(?:from|into|table|where|set)\b"
)


class SpecialistControlPlaneAdapterError(RuntimeError):
    """控制面适配器的低敏、可审计异常。

    ``code`` 是上层 Specialist 可以稳定判断的机器码，例如 ``CONTROL_PLANE_TIMEOUT``。
    异常刻意不保存 URL、响应正文、HTTP message、Header 或内部 token；这样即使上层
    将 ``str(error)`` 写入事件，也不会把下游故障正文扩散到模型或用户界面。
    """

    def __init__(self, code: str, *, status_code: int | None = None) -> None:
        """使用稳定错误码创建异常，并可选保留无敏感性的 HTTP 状态码。"""

        safe_code = _safe_code(code) or "CONTROL_PLANE_CLIENT_FAILED"
        self.code = safe_code
        self.status_code = status_code if isinstance(status_code, int) else None
        super().__init__(self.code)


# 这个别名方便调用方按“HTTP 客户端异常”语义捕获，同时保持只有一个真实异常类型。
ControlPlaneHttpClientError = SpecialistControlPlaneAdapterError


@dataclass(frozen=True)
class ControlPlaneHttpClientSettings:
    """三类 data-sync 只读客户端共享的运行配置。

    ``base_url``、超时和分页大小属于基础设施配置，不从模型输入读取。
    ``service_token`` 使用 ``repr=False``，只允许进入受控 Header；它既不进入正文，
    也不进入 URL、证据引用、工具活动或异常。分页上限用于防止一次监控轮询把大量
    execution 日志和对象账本搬进 Python 内存。
    """

    base_url: str
    timeout_seconds: float = 3.0
    service_token: str | None = field(default=None, repr=False)
    source_service: str = "python-ai-runtime"
    agent_id: str | None = None
    trace_id: str | None = None
    max_response_bytes: int = 2_000_000
    executions_page_size: int = 20
    logs_page_size: int = 100
    objects_page_size: int = 100
    fail_closed: bool = True

    def __post_init__(self) -> None:
        """校验 URL、超时、分页和可公开 Header，错误配置直接拒绝启动调用。"""

        base_url = str(self.base_url or "").strip().rstrip("/")
        parsed = urlsplit(base_url)
        if parsed.scheme not in {"http", "https"} or not parsed.netloc:
            raise ValueError("control plane base_url 必须是 http 或 https URL")
        if parsed.username or parsed.password or parsed.query or parsed.fragment:
            raise ValueError("control plane base_url 不能包含凭据、query 或 fragment")
        object.__setattr__(self, "base_url", base_url)

        timeout = _finite_number(self.timeout_seconds)
        if timeout is None or timeout <= 0 or timeout > 300:
            raise ValueError("control plane timeout_seconds 必须是 0 到 300 秒之间的有限数")
        object.__setattr__(self, "timeout_seconds", timeout)

        source_service = _required_reference(self.source_service, "source_service")
        object.__setattr__(self, "source_service", source_service)
        object.__setattr__(self, "agent_id", _optional_reference(self.agent_id, "agent_id"))
        object.__setattr__(self, "trace_id", _optional_reference(self.trace_id, "trace_id"))

        token = str(self.service_token).strip() if self.service_token is not None else ""
        object.__setattr__(self, "service_token", token or None)

        if isinstance(self.max_response_bytes, bool) or not isinstance(self.max_response_bytes, int):
            raise ValueError("max_response_bytes 必须是整数")
        if not 1_024 <= self.max_response_bytes <= 10_000_000:
            raise ValueError("max_response_bytes 超出安全范围")

        for name in ("executions_page_size", "logs_page_size", "objects_page_size"):
            value = getattr(self, name)
            if isinstance(value, bool) or not isinstance(value, int) or not 1 <= value <= 500:
                raise ValueError(f"{name} 必须是 1 到 500 之间的整数")

    @classmethod
    def from_env(
        cls,
        environ: Mapping[str, str] | None = None,
        *,
        base_url: str | None = None,
    ) -> "ControlPlaneHttpClientSettings":
        """从受控环境变量读取配置，不把环境变量内容写入返回错误或日志。

        生产部署可以使用模块专用的 ``DATASMART_DATA_SYNC_BASE_URL``，也可以由
        编排层显式传入 ``base_url``。环境变量只配置连接边界，任务正文仍然只能来自
        Java 控制面，不会因为配置方式改变数据脱敏规则。
        """

        source = environ if environ is not None else os.environ
        resolved_url = (
            base_url
            or source.get("DATASMART_DATA_SYNC_BASE_URL")
            or source.get("DATASMART_DATA_SYNC_CONTROL_PLANE_BASE_URL")
        )
        if not resolved_url:
            raise ValueError("未配置 data-sync control plane base_url")

        def positive_float(name: str, default: float) -> float:
            """读取正浮点配置；空值使用默认值，非法值在启动期直接失败。"""

            raw = source.get(name)
            if raw is None or not raw.strip():
                return default
            value = _finite_number(raw)
            if value is None or value <= 0:
                raise ValueError(f"{name} 必须是正数")
            return value

        def positive_int(name: str, default: int) -> int:
            """读取正整数配置，避免重试和分页上限被零值或负值关闭。"""

            raw = source.get(name)
            if raw is None or not raw.strip():
                return default
            try:
                value = int(raw.strip())
            except (TypeError, ValueError) as exc:
                raise ValueError(f"{name} 必须是正整数") from exc
            if value <= 0:
                raise ValueError(f"{name} 必须是正整数")
            return value

        return cls(
            base_url=resolved_url,
            timeout_seconds=positive_float("DATASMART_DATA_SYNC_TIMEOUT_SECONDS", 3.0),
            service_token=(
                source.get("DATASMART_DATA_SYNC_INTERNAL_SERVICE_TOKEN")
                or source.get("DATASMART_DATA_SYNC_SERVICE_TOKEN")
                or source.get("DATASMART_AGENT_RUNTIME_INTERNAL_SERVICE_TOKEN")
                or None
            ),
            source_service=source.get("DATASMART_DATA_SYNC_SOURCE_SERVICE") or "python-ai-runtime",
            agent_id=source.get("DATASMART_DATA_SYNC_AGENT_ID") or None,
            trace_id=source.get("DATASMART_DATA_SYNC_TRACE_ID") or None,
            executions_page_size=positive_int("DATASMART_DATA_SYNC_EXECUTIONS_PAGE_SIZE", 20),
            logs_page_size=positive_int("DATASMART_DATA_SYNC_LOGS_PAGE_SIZE", 100),
            objects_page_size=positive_int("DATASMART_DATA_SYNC_OBJECTS_PAGE_SIZE", 100),
        )


# 兼容更直白的命名，实际配置类型仍只有一份。
SpecialistControlPlaneHttpSettings = ControlPlaneHttpClientSettings
DataSyncHttpClientSettings = ControlPlaneHttpClientSettings


class _ControlPlaneHttpClientBase:
    """封装三类客户端共用的安全 HTTP 原语。

    子类只实现业务协议映射，不自行处理 HTTP 异常、身份 Header 或响应包络。
    这样可以避免一个客户端忘记脱敏，或者另一个客户端在 401/403 时把 Java 返回
    正文带回 Agent 的分叉行为。
    """

    def __init__(
        self,
        settings: ControlPlaneHttpClientSettings,
        *,
        transport: Callable[..., Any] | None = None,
        urlopen_func: Callable[..., Any] | None = None,
    ) -> None:
        """绑定不可变配置和可注入 transport，禁止同时提供两种传输实现。"""

        if transport is not None and urlopen_func is not None:
            raise ValueError("transport 和 urlopen_func 只能注入一个")
        if not isinstance(settings, ControlPlaneHttpClientSettings):
            raise TypeError("settings 必须是 ControlPlaneHttpClientSettings")
        self._settings = settings
        self._transport = transport or urlopen_func or _default_transport

    def _headers(
        self,
        *,
        tenant_id: Any,
        project_id: Any,
        actor_id: Any,
        delegation_id: Any,
        trace_id: Any,
        session_id: Any = None,
        run_id: Any = None,
        agent_id: Any = None,
    ) -> dict[str, str]:
        """构造可信上下文 Header，并确保 token 只出现在认证 Header。

        tenant/project/actor/delegation 是下游二次授权的最小范围；缺任何一个都
        不能用“默认值”替代。trace/session/run 用于审计关联，全部经过短引用校验，
        防止把 prompt、SQL 或换行注入到 Header。``service_token`` 永远不会被复制到
        请求正文或错误对象。
        """

        # data-sync 缺少显式范围 Header 时会按 actorRole 兜底；空角色可能退化为
        # TENANT。这里把调用上下文收敛到当前项目的最小只读范围，避免让下游猜测。
        # 项目集合只放在可信 Header 中，绝不复制到 body 或 query。
        normalized_project_id = _required_reference(project_id, "project_id")
        values = {
            TENANT_ID_HEADER: _required_reference(tenant_id, "tenant_id"),
            PROJECT_ID_HEADER: normalized_project_id,
            ACTOR_ID_HEADER: _required_reference(actor_id, "actor_id"),
            AGENT_DELEGATION_ID_HEADER: _required_reference(delegation_id, "delegation_id"),
            TRACE_ID_HEADER: _required_reference(trace_id, "trace_id"),
            DATA_SCOPE_LEVEL_HEADER: "PROJECT",
            AUTHORIZED_PROJECT_IDS_HEADER: normalized_project_id,
        }
        resolved_agent_id = agent_id if agent_id is not None else self._settings.agent_id
        if resolved_agent_id is not None:
            values[AGENT_ID_HEADER] = _required_reference(resolved_agent_id, "agent_id")
        if session_id is not None:
            values[AGENT_SESSION_ID_HEADER] = _required_reference(session_id, "session_id")
        if run_id is not None:
            values[AGENT_RUN_ID_HEADER] = _required_reference(run_id, "run_id")
        values[SOURCE_SERVICE_HEADER] = self._settings.source_service
        if self._settings.service_token:
            # 这是唯一允许出现 service token 的位置；不要把它加入 payload 或日志。
            values[INTERNAL_SERVICE_TOKEN_HEADER] = self._settings.service_token
        return {"Accept": "application/json", **values}

    def _url(self, path: str, query: Mapping[str, Any] | None = None) -> str:
        """拼接受控路径和白名单 query，拒绝绝对 URL 与未经转义的路径片段。"""

        if not isinstance(path, str) or not path.startswith("/") or "?" in path or "#" in path:
            raise SpecialistControlPlaneAdapterError("CONTROL_PLANE_PATH_INVALID")
        url = f"{self._settings.base_url}{path}"
        if query:
            pairs: list[tuple[str, str]] = []
            for key, value in query.items():
                if value is None:
                    continue
                if not _SAFE_CODE.fullmatch(str(key)):
                    raise SpecialistControlPlaneAdapterError("CONTROL_PLANE_QUERY_INVALID")
                safe_value = _required_reference(value, f"query_{key}")
                pairs.append((str(key), safe_value))
            if pairs:
                url = f"{url}?{urlencode(pairs)}"
        return url

    def _request_json(
        self,
        *,
        method: str,
        url: str,
        headers: Mapping[str, str],
        timeout_seconds: float | None = None,
        body: bytes | None = None,
    ) -> Mapping[str, Any]:
        """发送一次受控 HTTP 请求并解析成功的 ``PlatformApiResponse``。

        401/403、非 2xx、超时、网络故障、非 JSON、业务 code 非零和 data 类型错误
        全部转换为稳定异常。尤其是错误响应正文不读取、不保存、不拼接到异常，以确保
        下游可能包含凭据或 SQL 时也不会泄漏。
        """

        if method not in {"GET", "POST"}:
            raise SpecialistControlPlaneAdapterError("CONTROL_PLANE_METHOD_INVALID")
        timeout = self._effective_timeout(timeout_seconds)
        request = Request(
            url=url,
            data=body,
            headers={str(key): str(value) for key, value in headers.items() if value is not None},
            method=method,
        )
        try:
            response_or_context = self._transport(request, timeout)
            if hasattr(response_or_context, "__enter__"):
                with response_or_context as response:
                    status_code, response_body = self._read_response(response)
            else:
                status_code, response_body = self._read_response(response_or_context)
        except HTTPError as exc:
            status_code = int(getattr(exc, "code", 0) or 0)
            raise SpecialistControlPlaneAdapterError(
                _status_error_code(status_code), status_code=status_code or None
            ) from None
        except (TimeoutError, socket.timeout):
            raise SpecialistControlPlaneAdapterError("CONTROL_PLANE_TIMEOUT") from None
        except URLError as exc:
            if _is_timeout_reason(getattr(exc, "reason", None)):
                raise SpecialistControlPlaneAdapterError("CONTROL_PLANE_TIMEOUT") from None
            raise SpecialistControlPlaneAdapterError("CONTROL_PLANE_NETWORK_ERROR") from None
        except SpecialistControlPlaneAdapterError:
            raise
        except Exception:
            # transport 可能来自 mTLS、连接池或测试替身；底层 message 一律不外传。
            raise SpecialistControlPlaneAdapterError("CONTROL_PLANE_NETWORK_ERROR") from None

        if status_code == 401:
            raise SpecialistControlPlaneAdapterError("CONTROL_PLANE_UNAUTHORIZED", status_code=401)
        if status_code == 403:
            raise SpecialistControlPlaneAdapterError("CONTROL_PLANE_FORBIDDEN", status_code=403)
        if status_code < 200 or status_code >= 300:
            raise SpecialistControlPlaneAdapterError(
                "CONTROL_PLANE_HTTP_STATUS", status_code=status_code or None
            )
        if len(response_body) > self._settings.max_response_bytes:
            raise SpecialistControlPlaneAdapterError("CONTROL_PLANE_RESPONSE_TOO_LARGE")
        try:
            envelope = json.loads(response_body.decode("utf-8"))
        except (UnicodeDecodeError, TypeError, ValueError):
            raise SpecialistControlPlaneAdapterError("CONTROL_PLANE_RESPONSE_NOT_JSON") from None
        if not isinstance(envelope, Mapping):
            raise SpecialistControlPlaneAdapterError("CONTROL_PLANE_RESPONSE_INVALID")
        if envelope.get("code") != 0:
            raise SpecialistControlPlaneAdapterError("CONTROL_PLANE_PLATFORM_REJECTED")
        data = envelope.get("data")
        if not isinstance(data, Mapping):
            raise SpecialistControlPlaneAdapterError("CONTROL_PLANE_RESPONSE_DATA_INVALID")
        return dict(data)

    def _read_response(self, response: Any) -> tuple[int, bytes]:
        """读取响应状态和有限大小正文；调用方只在成功状态下继续解析正文。"""

        status_value = getattr(response, "status", None)
        if status_value is None:
            get_code = getattr(response, "getcode", None)
            status_value = get_code() if callable(get_code) else 200
        try:
            status_code = int(status_value)
        except (TypeError, ValueError):
            raise SpecialistControlPlaneAdapterError("CONTROL_PLANE_STATUS_INVALID") from None
        if status_code < 200 or status_code >= 300:
            # 对错误响应不调用 read，避免把正文放入任何中间变量或审计路径。
            return status_code, b""
        read = getattr(response, "read", None)
        if not callable(read):
            raise SpecialistControlPlaneAdapterError("CONTROL_PLANE_RESPONSE_INVALID")
        body = read()
        if isinstance(body, str):
            body = body.encode("utf-8")
        if not isinstance(body, bytes):
            raise SpecialistControlPlaneAdapterError("CONTROL_PLANE_RESPONSE_BODY_INVALID")
        if len(body) > self._settings.max_response_bytes:
            raise SpecialistControlPlaneAdapterError("CONTROL_PLANE_RESPONSE_TOO_LARGE")
        return status_code, body

    def _effective_timeout(self, request_timeout_seconds: float | None) -> float:
        """把请求级超时与客户端上限取较小值，防止单次 turn 无限等待。"""

        configured = self._settings.timeout_seconds
        if request_timeout_seconds is None:
            return configured
        candidate = _finite_number(request_timeout_seconds)
        if candidate is None or candidate <= 0:
            raise SpecialistControlPlaneAdapterError("CONTROL_PLANE_TIMEOUT_INVALID")
        return min(configured, candidate)

    def _page_records(self, data: Mapping[str, Any]) -> tuple[Mapping[str, Any], ...]:
        """解析 Java ``PlatformPageResponse.records``，拒绝未知列表结构和非对象记录。"""

        records = data.get("records")
        if not isinstance(records, list):
            raise SpecialistControlPlaneAdapterError("CONTROL_PLANE_PAGE_INVALID")
        normalized: list[Mapping[str, Any]] = []
        for record in records:
            if not isinstance(record, Mapping):
                raise SpecialistControlPlaneAdapterError("CONTROL_PLANE_PAGE_RECORD_INVALID")
            normalized.append(dict(record))
        return tuple(normalized)

    def _validate_scope_record(
        self,
        record: Mapping[str, Any],
        *,
        task_id: Any,
        tenant_id: Any,
        project_id: Any,
        execution_id: Any = None,
    ) -> None:
        """校验每条 execution/log/object 的租户、项目、任务和 execution 归属。

        Java DTO 已经冗余返回这些字段，Python 仍然再次校验是有意的防御性边界：
        只要下游路由、数据库查询或代理出现范围错配，就立即 fail-closed，而不是
        把别的项目记录聚合进当前 Agent 的监控快照。
        """

        expected_values = {
            "tenantId": tenant_id,
            "projectId": project_id,
            "syncTaskId": task_id,
        }
        if execution_id is not None:
            expected_values["executionId"] = execution_id
        for field_name, expected in expected_values.items():
            if field_name not in record or record.get(field_name) is None:
                raise SpecialistControlPlaneAdapterError("CONTROL_PLANE_SCOPE_INVALID")
            actual = _safe_identifier(record.get(field_name), field_name)
            expected_text = _required_reference(expected, field_name)
            if actual != expected_text:
                raise SpecialistControlPlaneAdapterError("CONTROL_PLANE_SCOPE_MISMATCH")

    def _page_request(
        self,
        *,
        path: str,
        headers: Mapping[str, str],
        size: int,
    ) -> tuple[Mapping[str, Any], ...]:
        """按真实 Controller 的 ``current``/``size`` 参数读取有限分页记录。"""

        data = self._request_json(
            method="GET",
            url=self._url(path, {"current": 1, "size": size}),
            headers=headers,
        )
        return self._page_records(data)


class HttpPrecheckControlPlaneClient(_ControlPlaneHttpClientBase):
    """实现 ``PrecheckControlPlaneClient`` 的只读 HTTP 客户端。

    Java 的真实 Controller 是 ``POST /sync-tasks/{taskId}/precheck``，没有请求体，
    因此这里不会把 Protocol 中的 ``configuration`` 转发出去。配置可能含有连接信息、
    SQL 或样本，真实任务定义已经保存在 data-sync，控制面应根据 taskId 和可信 Header
    自己读取并完成权限、预检查和审批事实计算。
    """

    DEFAULT_AGENT_ID = "precheck-specialist-v1"

    def __init__(
        self,
        base_url: str | ControlPlaneHttpClientSettings | None = None,
        *,
        settings: ControlPlaneHttpClientSettings | None = None,
        timeout_seconds: float = 3.0,
        service_token: str | None = None,
        source_service: str = "python-ai-runtime",
        agent_id: str | None = DEFAULT_AGENT_ID,
        trace_id: str | None = None,
        transport: Callable[..., Any] | None = None,
        urlopen_func: Callable[..., Any] | None = None,
    ) -> None:
        """创建预检查客户端，并允许生产 transport 或单元测试替身注入。"""

        resolved = _coerce_settings(
            base_url,
            settings=settings,
            timeout_seconds=timeout_seconds,
            service_token=service_token,
            source_service=source_service,
            agent_id=agent_id,
            trace_id=trace_id,
            default_agent_id=self.DEFAULT_AGENT_ID,
        )
        super().__init__(resolved, transport=transport, urlopen_func=urlopen_func)

    def precheck(
        self,
        request: PrecheckControlPlaneRequest,
        *,
        trace_id: str | None = None,
    ) -> PrecheckControlPlaneResult:
        """调用真实预检查接口，并把 Java DTO 映射为 Specialist 低敏结果。

        ``configuration``、objective 和任何未列入响应白名单的字段都不会进入请求。
        Java DTO 中的 tenant/project/task 会被严格核对，``issueCodes`` 和布尔门禁
        只转换成预检查协议允许的四种状态。
        """

        if not isinstance(request, PrecheckControlPlaneRequest):
            raise SpecialistControlPlaneAdapterError("CONTROL_PLANE_REQUEST_TYPE_INVALID")
        task_id = _required_reference(request.task_id, "task_id")
        self._require_request_scope(request)
        resolved_trace = _resolve_trace(
            self._settings.trace_id,
            trace_id,
            getattr(request, "trace_id", None),
            request.turn_id,
            request.run_id,
        )
        headers = self._headers(
            tenant_id=request.tenant_id,
            project_id=request.project_id,
            actor_id=request.actor_id,
            delegation_id=request.delegation_id,
            trace_id=resolved_trace,
            run_id=request.run_id,
        )
        data = self._request_json(
            method="POST",
            url=self._url(PRECHECK_PATH_TEMPLATE.format(taskId=quote(task_id, safe=""))),
            headers=headers,
            timeout_seconds=_milliseconds_to_seconds(request.timeout_ms),
            body=None,
        )
        self._validate_precheck_scope(data, request)
        return self._map_precheck_result(data, request, task_id)

    def _require_request_scope(self, request: PrecheckControlPlaneRequest) -> None:
        """校验预检查请求的租户、项目、用户和 delegation，缺失时不发 HTTP 请求。"""

        _required_reference(request.tenant_id, "tenant_id")
        _required_reference(request.project_id, "project_id")
        _required_reference(request.actor_id, "actor_id")
        _required_reference(request.delegation_id, "delegation_id")
        _required_reference(request.turn_id, "turn_id")
        _required_reference(request.run_id, "run_id")

    def _validate_precheck_scope(
        self,
        data: Mapping[str, Any],
        request: PrecheckControlPlaneRequest,
    ) -> None:
        """校验 ``SyncTaskExecutionPrecheckResponse`` 的 task/tenant/project 归属。"""

        for field_name, expected in (
            ("taskId", request.task_id),
            ("tenantId", request.tenant_id),
            ("projectId", request.project_id),
        ):
            if field_name not in data or data.get(field_name) is None:
                raise SpecialistControlPlaneAdapterError("CONTROL_PLANE_SCOPE_INVALID")
            if _safe_identifier(data.get(field_name), field_name) != _required_reference(expected, field_name):
                raise SpecialistControlPlaneAdapterError("CONTROL_PLANE_SCOPE_MISMATCH")

    def _map_precheck_result(
        self,
        data: Mapping[str, Any],
        request: PrecheckControlPlaneRequest,
        task_id: str,
    ) -> PrecheckControlPlaneResult:
        """把 Java 预检查 DTO 的白名单字段映射为 ``PrecheckControlPlaneResult``。"""

        status = _normalize_precheck_status(data.get("precheckStatus"))
        can_start = _required_bool(data.get("canStartExecution"), "canStartExecution")
        issue_codes = _safe_code_list(data.get("issueCodes"), "issueCodes")
        recommended_actions = _safe_text_list(data.get("recommendedActions"), "recommendedActions")
        performance_notes = _safe_text_list(data.get("performanceNotes"), "performanceNotes")
        safety_notes = _safe_text_list(data.get("safetyNotes"), "safetyNotes")
        if can_start is False and status == PrecheckCheckStatus.PASSED:
            status = PrecheckCheckStatus.BLOCKED

        check_status = status.value
        checks: list[PrecheckCheckItem] = []
        for issue_code in issue_codes:
            checks.append(
                PrecheckCheckItem(
                    code=issue_code,
                    status=check_status,
                    problem="控制面返回了需要关注的预检查问题。",
                    suggestion=None,
                )
            )
        if not checks:
            checks.append(
                PrecheckCheckItem(
                    code=f"PRECHECK_STATUS_{status.value}",
                    status=check_status,
                    problem="控制面已返回当前任务的预检查状态。",
                    suggestion=None,
                )
            )

        references = (_stable_reference("sync-precheck", request.tenant_id, request.project_id, task_id),)
        return PrecheckControlPlaneResult(
            status=status.value,
            checks=tuple(checks),
            task_id=task_id,
            can_start_execution=can_start,
            issue_codes=issue_codes,
            recommended_actions=recommended_actions,
            configuration_steps=tuple(dict.fromkeys((*performance_notes, *safety_notes))),
            details_references=references,
            evidence_references=references,
            invocation_summary={
                "responseSource": "data-sync-control-plane",
                "httpRequestCount": 1,
                "rawResponseStored": False,
                "payloadPolicy": CONTROL_PLANE_PAYLOAD_POLICY,
            },
            precheck_status=status.value,
        )


class HttpFailureDiagnosticClient(_ControlPlaneHttpClientBase):
    """实现 ``FailureDiagnosticClient`` 的只读诊断 HTTP 客户端。

    客户端只调用 ``GET /sync-tasks/{taskId}/agent-diagnosis``，并只允许把真实 Controller
    支持的 ``executionId`` 放进 query。Recovery Agent 可以消费返回的根因码和低敏计数，
    但本类没有 ``execute``、``retry``、``replay`` 或 ``repair`` 方法，绝不会绕过 Java
    审批与 outbox 执行受控动作。
    """

    DEFAULT_AGENT_ID = "recovery-specialist-v1"

    def __init__(
        self,
        base_url: str | ControlPlaneHttpClientSettings | None = None,
        *,
        settings: ControlPlaneHttpClientSettings | None = None,
        timeout_seconds: float = 3.0,
        service_token: str | None = None,
        source_service: str = "python-ai-runtime",
        agent_id: str | None = DEFAULT_AGENT_ID,
        trace_id: str | None = None,
        transport: Callable[..., Any] | None = None,
        urlopen_func: Callable[..., Any] | None = None,
    ) -> None:
        """创建只读诊断客户端，并绑定不可变配置和注入的 HTTP transport。"""

        resolved = _coerce_settings(
            base_url,
            settings=settings,
            timeout_seconds=timeout_seconds,
            service_token=service_token,
            source_service=source_service,
            agent_id=agent_id,
            trace_id=trace_id,
            default_agent_id=self.DEFAULT_AGENT_ID,
        )
        super().__init__(resolved, transport=transport, urlopen_func=urlopen_func)

    def diagnose(
        self,
        request: FailureDiagnosticRequest,
        *,
        trace_id: str | None = None,
    ) -> FailureDiagnosticResult:
        """读取并裁剪 Java ``SyncExecutionDiagnosisResponse``，不携带 diagnosis 原文。

        taskId 来自结构化诊断上下文，executionId 是唯一允许透传的真实查询参数。
        ``ragQuery``、错误 message、对象名称和其他未列入白名单的字段即使出现在 Java
        响应里，也不会进入 facts、log_summary 或 public_summary。
        """

        if not isinstance(request, FailureDiagnosticRequest):
            raise SpecialistControlPlaneAdapterError("CONTROL_PLANE_REQUEST_TYPE_INVALID")
        context = request.context_summary
        if not isinstance(context, Mapping):
            raise SpecialistControlPlaneAdapterError("CONTROL_PLANE_CONTEXT_INVALID")
        task_id = _context_identifier(context, "taskId", "task_id", "taskReference", "task_reference")
        if task_id is None:
            task_id = _optional_reference(getattr(request, "task_id", None), "task_id")
        if task_id is None:
            raise SpecialistControlPlaneAdapterError("CONTROL_PLANE_TASK_ID_REQUIRED")
        execution_id = _diagnosis_execution_id(context)
        if execution_id is not None:
            execution_id = _required_reference(execution_id, "execution_id")
        self._require_diagnostic_scope(request)
        resolved_trace = _resolve_trace(
            self._settings.trace_id,
            trace_id,
            _context_value(context, "traceId", "trace_id"),
            getattr(request, "trace_id", None),
            request.turn_id,
            request.run_id,
        )
        headers = self._headers(
            tenant_id=request.tenant_id,
            project_id=request.project_id,
            actor_id=request.actor_id,
            delegation_id=request.delegation_id,
            trace_id=resolved_trace,
            session_id=request.session_id,
            run_id=request.run_id,
        )
        query = {"executionId": execution_id} if execution_id is not None else None
        data = self._request_json(
            method="GET",
            url=self._url(
                DIAGNOSIS_PATH_TEMPLATE.format(taskId=quote(task_id, safe="")),
                query,
            ),
            headers=headers,
        )
        response_execution_id = self._validate_diagnosis_scope(
            data,
            task_id,
            execution_id,
            tenant_id=request.tenant_id,
            project_id=request.project_id,
        )
        return self._map_diagnosis_result(data, request, task_id, response_execution_id)

    def _require_diagnostic_scope(self, request: FailureDiagnosticRequest) -> None:
        """校验诊断调用的租户、项目、用户、delegation 和审计 ID。"""

        _required_reference(request.tenant_id, "tenant_id")
        _required_reference(request.project_id, "project_id")
        _required_reference(request.actor_id, "actor_id")
        _required_reference(request.delegation_id, "delegation_id")
        _required_reference(request.turn_id, "turn_id")
        _required_reference(request.session_id, "session_id")
        _required_reference(request.run_id, "run_id")

    def _validate_diagnosis_scope(
        self,
        data: Mapping[str, Any],
        task_id: str,
        expected_execution_id: str | None,
        *,
        tenant_id: Any,
        project_id: Any,
    ) -> str:
        """校验诊断 DTO 的任务归属，并检查可选租户/项目回显，防止跨范围误诊断。

        真实 ``SyncExecutionDiagnosisResponse`` 没有 tenantId/projectId 字段，因此缺少
        这两个回显时不能把正常响应误判为错误；但如果代理或未来版本返回了它们，必须
        与本次可信上下文完全一致，避免跨项目事实进入 Recovery Agent。
        """

        for field_name, expected in (("tenantId", tenant_id), ("projectId", project_id)):
            if field_name in data and data.get(field_name) is not None:
                if _safe_identifier(data.get(field_name), field_name) != _required_reference(expected, field_name):
                    raise SpecialistControlPlaneAdapterError("CONTROL_PLANE_SCOPE_MISMATCH")

        raw_task_id = data.get("taskId")
        if raw_task_id is None or _safe_identifier(raw_task_id, "taskId") != task_id:
            raise SpecialistControlPlaneAdapterError(
                "CONTROL_PLANE_SCOPE_MISMATCH"
                if raw_task_id is not None
                else "CONTROL_PLANE_SCOPE_INVALID"
            )
        raw_execution_id = data.get("executionId")
        if raw_execution_id is None:
            raise SpecialistControlPlaneAdapterError("CONTROL_PLANE_SCOPE_INVALID")
        response_execution_id = _safe_identifier(raw_execution_id, "executionId")
        if expected_execution_id is not None and response_execution_id != expected_execution_id:
            raise SpecialistControlPlaneAdapterError("CONTROL_PLANE_SCOPE_MISMATCH")
        return response_execution_id

    def _map_diagnosis_result(
        self,
        data: Mapping[str, Any],
        request: FailureDiagnosticRequest,
        task_id: str,
        execution_id: str,
    ) -> FailureDiagnosticResult:
        """把诊断 DTO 映射为 Recovery Protocol 允许的低敏失败事实。"""

        errors = _diagnosis_error_summaries(data.get("errors"))
        failed_objects = _diagnosis_failed_object_summaries(data.get("failedObjects"))
        root_causes = _safe_code_list(data.get("rootCauseCodes"), "rootCauseCodes")
        repair_actions = _safe_code_list(data.get("recommendedRepairActions"), "recommendedRepairActions")
        execution_state = _safe_code(data.get("executionState"))
        diagnosis_digest = _safe_public_text(data.get("diagnosisDigest"), 500)
        failure_code = (
            root_causes[0]
            if root_causes
            else (errors[0]["errorCode"] if errors else ("EXECUTION_FAILED" if execution_state == "FAILED" else None))
        )
        public_summary = diagnosis_digest or "data-sync 已返回低敏执行诊断事实。"
        stable_reference = _stable_reference("sync-diagnosis", request.tenant_id, request.project_id, task_id, execution_id)
        facts: dict[str, Any] = {
            "taskId": task_id,
            "executionId": execution_id,
            "taskState": _safe_code(data.get("taskState")),
            "executionState": execution_state,
            "syncMode": _safe_code(data.get("syncMode")),
            "writeStrategy": _safe_code(data.get("writeStrategy")),
            "sourceConnectorType": _safe_code(data.get("sourceConnectorType")),
            "targetConnectorType": _safe_code(data.get("targetConnectorType")),
            "recordsRead": _optional_non_negative_int(data.get("recordsRead")),
            "recordsWritten": _optional_non_negative_int(data.get("recordsWritten")),
            "failedRecordCount": _optional_non_negative_int(data.get("failedRecordCount")),
            "failedObjectCount": _optional_non_negative_int(data.get("failedObjectCount")),
            "retryableDirtySampleCount": _optional_non_negative_int(data.get("retryableDirtySampleCount")),
            "quarantinedDirtySampleCount": _optional_non_negative_int(data.get("quarantinedDirtySampleCount")),
            "errors": tuple(errors),
            "failedObjects": tuple(failed_objects),
            "rootCauseCodes": root_causes,
            "recommendedRepairActions": repair_actions,
            "payloadPolicy": CONTROL_PLANE_PAYLOAD_POLICY,
        }
        facts = {key: value for key, value in facts.items() if value is not None}
        return FailureDiagnosticResult(
            failure_code=failure_code,
            failure_reason=public_summary,
            facts=facts,
            log_references=(stable_reference,),
            evidence_references=tuple(
                dict.fromkeys(
                    (stable_reference,)
                    + tuple(
                        reference
                        for reference in request.evidence_references
                        if _optional_reference(reference, "evidence_reference") is not None
                    )
                )
            ),
            log_summary={
                "executionState": execution_state,
                "errorCount": len(errors),
                "failedObjectCount": len(failed_objects),
                "rootCauseCount": len(root_causes),
                "payloadPolicy": CONTROL_PLANE_PAYLOAD_POLICY,
            },
            public_summary=public_summary,
        )


class HttpTaskMonitoringClient(_ControlPlaneHttpClientBase):
    """实现 ``TaskMonitoringClient`` 的三路只读监控快照客户端。

    一次 ``get_snapshot`` 会依次读取 executions、logs、objects 三个真实分页接口，
    再把记录聚合成 ``TaskMonitoringSnapshot``。返回值不包含原始列表、SQL、对象名、
    行数据或凭据；所有状态、计数、吞吐、心跳和 checkpoint 都只能来自 DTO 字段或由
    DTO 时间/计数做出的可复核计算。
    """

    DEFAULT_AGENT_ID = "monitor-specialist-v1"

    def __init__(
        self,
        base_url: str | ControlPlaneHttpClientSettings | None = None,
        *,
        settings: ControlPlaneHttpClientSettings | None = None,
        timeout_seconds: float = 3.0,
        service_token: str | None = None,
        source_service: str = "python-ai-runtime",
        agent_id: str | None = DEFAULT_AGENT_ID,
        trace_id: str | None = None,
        transport: Callable[..., Any] | None = None,
        urlopen_func: Callable[..., Any] | None = None,
    ) -> None:
        """创建监控客户端，并允许注入连接池、mTLS transport 或测试替身。"""

        resolved = _coerce_settings(
            base_url,
            settings=settings,
            timeout_seconds=timeout_seconds,
            service_token=service_token,
            source_service=source_service,
            agent_id=agent_id,
            trace_id=trace_id,
            default_agent_id=self.DEFAULT_AGENT_ID,
        )
        super().__init__(resolved, transport=transport, urlopen_func=urlopen_func)

    def get_snapshot(
        self,
        query: TaskMonitoringQuery,
        *,
        trace_id: str | None = None,
    ) -> TaskMonitoringSnapshot:
        """读取三类分页事实并组合成一个可供 Durable 轮询的确定性快照。"""

        if not isinstance(query, TaskMonitoringQuery):
            raise SpecialistControlPlaneAdapterError("CONTROL_PLANE_QUERY_TYPE_INVALID")
        task_id = _required_reference(query.task_id, "task_id")
        tenant_id = _required_reference(query.tenant_id, "tenant_id")
        project_id = _required_reference(query.project_id, "project_id")
        actor_id = _required_reference(query.actor_id, "actor_id")
        delegation_id = _required_reference(query.delegation_id, "delegation_id")
        resolved_trace = _resolve_trace(
            self._settings.trace_id,
            trace_id,
            getattr(query, "trace_id", None),
            query.run_id,
            task_id,
        )
        headers = self._headers(
            tenant_id=tenant_id,
            project_id=project_id,
            actor_id=actor_id,
            delegation_id=delegation_id,
            trace_id=resolved_trace,
            run_id=query.run_id,
        )

        executions = self._page_request(
            path=EXECUTIONS_PATH_TEMPLATE.format(taskId=quote(task_id, safe="")),
            headers=headers,
            size=self._settings.executions_page_size,
        )
        for record in executions:
            self._validate_scope_record(
                record,
                task_id=task_id,
                tenant_id=tenant_id,
                project_id=project_id,
            )
        if not executions:
            raise SpecialistControlPlaneAdapterError("CONTROL_PLANE_EXECUTIONS_EMPTY")
        execution = self._select_execution(executions, query.run_id)
        execution_id = _first_identifier(execution, "id", "executionId")
        if execution_id is None:
            raise SpecialistControlPlaneAdapterError("CONTROL_PLANE_EXECUTION_ID_INVALID")

        logs = self._page_request(
            path=LOGS_PATH_TEMPLATE.format(
                taskId=quote(task_id, safe=""),
                executionId=quote(execution_id, safe=""),
            ),
            headers=headers,
            size=self._settings.logs_page_size,
        )
        objects = self._page_request(
            path=OBJECTS_PATH_TEMPLATE.format(
                taskId=quote(task_id, safe=""),
                executionId=quote(execution_id, safe=""),
            ),
            headers=headers,
            size=self._settings.objects_page_size,
        )
        for record in logs:
            self._validate_scope_record(
                record,
                task_id=task_id,
                tenant_id=tenant_id,
                project_id=project_id,
                execution_id=execution_id,
            )
        for record in objects:
            self._validate_scope_record(
                record,
                task_id=task_id,
                tenant_id=tenant_id,
                project_id=project_id,
                execution_id=execution_id,
            )
        return self._build_snapshot(
            query=query,
            executions=executions,
            execution=execution,
            logs=logs,
            objects=objects,
            task_id=task_id,
            tenant_id=tenant_id,
            project_id=project_id,
            actor_id=actor_id,
            delegation_id=delegation_id,
        )

    def _select_execution(
        self,
        executions: Sequence[Mapping[str, Any]],
        run_id: str | None,
    ) -> Mapping[str, Any]:
        """优先选择 run_id 能明确指向的 execution，否则使用后端排序后的最新记录。"""

        if run_id:
            for record in executions:
                candidate = _first_identifier(record, "id", "executionId")
                if candidate == _optional_reference(run_id, "run_id"):
                    return record
        return executions[0]

    def _build_snapshot(
        self,
        *,
        query: TaskMonitoringQuery,
        executions: Sequence[Mapping[str, Any]],
        execution: Mapping[str, Any],
        logs: Sequence[Mapping[str, Any]],
        objects: Sequence[Mapping[str, Any]],
        task_id: str,
        tenant_id: str,
        project_id: str,
        actor_id: str,
        delegation_id: str,
    ) -> TaskMonitoringSnapshot:
        """从真实 execution/log/object DTO 计算监控 Protocol 的聚合字段。"""

        execution_status = _normalize_lifecycle_status(execution.get("executionState"))
        task_kind = query.task_kind or _infer_task_kind(execution, logs)
        if task_kind is None:
            task_kind = TaskKind.LONG_RUNNING
        status = execution_status
        if task_kind == TaskKind.PERIODIC and execution_status in _TERMINAL_STATUSES:
            # 定期任务的某一次 execution 结束后，任务本身仍在等待下一次调度。
            status = TaskLifecycleStatus.SCHEDULED

        captured_at, captured_seconds = _capture_time(execution, logs)
        records_read = _first_count(execution, "recordsRead", "rowsTotal")
        records_written = _first_count(execution, "recordsWritten", "rowsProcessed")
        failed_records = _first_count(execution, "failedRecordCount", "failureCount")
        if records_read is None:
            records_read = _sum_counts(objects, "recordsRead")
        if records_written is None:
            records_written = _sum_counts(objects, "recordsWritten")
        if failed_records is None:
            failed_records = _sum_counts(objects, "failedRecordCount")

        phase = _latest_safe_text(logs, "logStage", "eventType")
        throughput = _first_number_from_logs(logs, "speedRowsPerSecond", "throughputRowsPerSecond", "throughput")
        if throughput is None:
            throughput = _first_number(execution, "speedRowsPerSecond", "throughputRowsPerSecond", "throughput")
        if throughput is None:
            throughput = _derived_throughput(execution, records_written, captured_seconds)
        baseline = _baseline_throughput(logs, throughput)
        latency = _first_number_from_logs(logs, "latencyMs", "averageLatencyMs", "p95LatencyMs")
        if latency is None:
            latency = _first_number(execution, "latencyMs", "averageLatencyMs", "p95LatencyMs")

        heartbeat_at = _first_timestamp(execution, "heartbeatTime", "heartbeatAt", "lastHeartbeatAt")
        heartbeat_present = None
        explicit_heartbeat = _first_present(execution, "heartbeatPresent", "heartbeat_present")
        if explicit_heartbeat is not None:
            heartbeat_present = _required_bool(explicit_heartbeat, "heartbeatPresent")
        elif heartbeat_at is not None:
            heartbeat_present = True
        heartbeat_age = _first_number(execution, "heartbeatAgeSeconds", "heartbeat_age_seconds")
        if heartbeat_age is None and heartbeat_at is not None and captured_seconds is not None:
            heartbeat_age = max(0.0, captured_seconds - _timestamp_seconds(heartbeat_at))

        queue_wait = _first_number(execution, "queueWaitSeconds", "queuedForSeconds")
        if queue_wait is None and execution_status == TaskLifecycleStatus.QUEUED:
            queued_at = _first_timestamp(execution, "queuedAt")
            if queued_at is not None and captured_seconds is not None:
                queue_wait = max(0.0, captured_seconds - _timestamp_seconds(queued_at))

        cdc_lag = _first_number(execution, "cdcLagSeconds", "lagSeconds", "sourceLagSeconds")
        if cdc_lag is None:
            cdc_lag = _first_number_from_logs(logs, "cdcLagSeconds", "lagSeconds", "sourceLagSeconds")

        schedule, last_run_status, last_run_at, next_run_at, schedule_missed, missed_count, last_success_at = (
            _schedule_facts(executions, execution, logs, task_kind)
        )
        exception = _exception_facts(execution, logs, execution_status)
        checkpoint = _checkpoint_facts(execution, logs)
        return TaskMonitoringSnapshot(
            task_id=task_id,
            status=status,
            task_kind=task_kind,
            phase=phase,
            rows_total=records_read,
            rows_processed=records_written,
            success_count=records_written,
            failure_count=failed_records,
            throughput_rows_per_second=throughput,
            baseline_throughput_rows_per_second=baseline,
            latency_ms=latency,
            heartbeat_age_seconds=heartbeat_age,
            heartbeat_present=heartbeat_present,
            heartbeat_at=heartbeat_at,
            queue_wait_seconds=queue_wait,
            cdc_lag_seconds=cdc_lag,
            checkpoint=checkpoint,
            schedule=schedule,
            exception=exception,
            captured_at=captured_at,
            last_run_status=last_run_status,
            last_run_at=last_run_at,
            next_run_at=next_run_at,
            schedule_missed=schedule_missed,
            missed_schedule_count=missed_count,
            last_success_at=last_success_at,
            tenant_id=tenant_id,
            project_id=project_id,
            actor_id=actor_id,
            delegation_id=delegation_id,
        )


# 下面这些别名只改善接入方的可发现性，不创建第二套实现或第二套安全规则。
PrecheckControlPlaneHttpClient = HttpPrecheckControlPlaneClient
DataSyncPrecheckHttpClient = HttpPrecheckControlPlaneClient
RecoveryDiagnosticHttpClient = HttpFailureDiagnosticClient
FailureDiagnosticHttpClient = HttpFailureDiagnosticClient
DataSyncFailureDiagnosticHttpClient = HttpFailureDiagnosticClient
MonitorTaskMonitoringHttpClient = HttpTaskMonitoringClient
DataSyncTaskMonitoringHttpClient = HttpTaskMonitoringClient
TaskMonitoringHttpClient = HttpTaskMonitoringClient


_TERMINAL_STATUSES = frozenset(
    {
        TaskLifecycleStatus.SUCCEEDED,
        TaskLifecycleStatus.FAILED,
        TaskLifecycleStatus.CANCELLED,
    }
)


def _default_transport(request: Request, timeout: float) -> Any:
    """使用标准 urllib 发起请求；生产部署可用 mTLS/连接池替换本函数。"""

    return urlopen(request, timeout=timeout)


def _coerce_settings(
    base_url: str | ControlPlaneHttpClientSettings | None,
    *,
    settings: ControlPlaneHttpClientSettings | None,
    timeout_seconds: float,
    service_token: str | None,
    source_service: str,
    agent_id: str | None,
    trace_id: str | None,
    default_agent_id: str,
) -> ControlPlaneHttpClientSettings:
    """统一处理字符串 URL、Settings 对象和客户端默认 agentId 三种构造方式。"""

    if settings is not None and base_url is not None:
        raise ValueError("base_url 和 settings 只能提供一个")
    if isinstance(base_url, ControlPlaneHttpClientSettings):
        resolved = base_url
    elif settings is not None:
        resolved = settings
    elif base_url is not None:
        resolved = ControlPlaneHttpClientSettings(
            base_url=str(base_url),
            timeout_seconds=timeout_seconds,
            service_token=service_token,
            source_service=source_service,
            agent_id=agent_id,
            trace_id=trace_id,
        )
    else:
        raise ValueError("必须配置 control plane base_url 或 settings")
    if resolved.agent_id is None and default_agent_id:
        resolved = replace(resolved, agent_id=default_agent_id)
    return resolved


def _status_error_code(status_code: int) -> str:
    """把 HTTP 状态码收敛为不含正文的稳定机器码。"""

    if status_code == 401:
        return "CONTROL_PLANE_UNAUTHORIZED"
    if status_code == 403:
        return "CONTROL_PLANE_FORBIDDEN"
    return "CONTROL_PLANE_HTTP_STATUS"


def _is_timeout_reason(reason: Any) -> bool:
    """识别 urllib 包装的 timeout 原因，而不读取其 message。"""

    return isinstance(reason, (TimeoutError, socket.timeout))


def _finite_number(value: Any) -> float | None:
    """解析有限数字并拒绝 bool、NaN 和无穷大。"""

    if isinstance(value, bool):
        return None
    try:
        number = float(value)
    except (TypeError, ValueError, OverflowError):
        return None
    return number if math.isfinite(number) else None


def _required_reference(value: Any, name: str) -> str:
    """校验必须进入 Header/URL 的引用标识，失败只返回稳定范围错误。"""

    text = str(value).strip() if value is not None else ""
    if not _SAFE_REFERENCE.fullmatch(text):
        raise SpecialistControlPlaneAdapterError("CONTROL_PLANE_CONTEXT_INVALID")
    return text


def _optional_reference(value: Any, name: str) -> str | None:
    """把可选审计引用转换为安全文本；无值返回 None，非法值 fail-closed。"""

    if value is None or not str(value).strip():
        return None
    return _required_reference(value, name)


def _safe_identifier(value: Any, name: str) -> str:
    """校验 Java DTO 中的 Long/String 标识，防止响应字段被当成自由文本。"""

    return _required_reference(value, name)


def _safe_code(value: Any) -> str | None:
    """只保留错误码、状态码等短引用，不把任意 message 当作 code。"""

    if value is None or not str(value).strip():
        return None
    text = str(value).strip().upper().replace("-", "_").replace(" ", "_")
    return text if _SAFE_CODE.fullmatch(text) else "UNCLASSIFIED"


def _safe_public_text(value: Any, limit: int) -> str:
    """对有限公开摘要做凭据/连接串/SQL 脱敏并限制长度。"""

    text = str(value).strip() if value is not None else ""
    if not text:
        return ""
    text = _SECRET_TEXT.sub("[REDACTED]", text)
    if _SQL_TEXT.search(text):
        return "[REDACTED_SENSITIVE_TEXT]"
    return text[:limit]


def _safe_text_list(value: Any, name: str) -> tuple[str, ...]:
    """读取 Java 字符串列表并逐项脱敏，拒绝把单个对象或原始字典带回 Agent。"""

    if value is None:
        return ()
    if not isinstance(value, list):
        raise SpecialistControlPlaneAdapterError("CONTROL_PLANE_RESPONSE_FIELD_INVALID")
    result: list[str] = []
    for item in value:
        text = _safe_public_text(item, 400)
        if text and text not in result:
            result.append(text)
    return tuple(result[:32])


def _safe_code_list(value: Any, name: str) -> tuple[str, ...]:
    """读取错误码/动作码白名单列表，不让任意正文成为恢复动作或根因。"""

    if value is None:
        return ()
    if not isinstance(value, list):
        raise SpecialistControlPlaneAdapterError("CONTROL_PLANE_RESPONSE_FIELD_INVALID")
    result: list[str] = []
    for item in value:
        code = _safe_code(item)
        if code and code not in result:
            result.append(code)
    return tuple(result[:32])


def _required_bool(value: Any, name: str) -> bool:
    """严格解析 DTO 的布尔字段，避免把任意非空正文解释为 True。"""

    if isinstance(value, bool):
        return value
    if isinstance(value, str) and value.strip().lower() in {"true", "false"}:
        return value.strip().lower() == "true"
    raise SpecialistControlPlaneAdapterError("CONTROL_PLANE_RESPONSE_FIELD_INVALID")


def _optional_non_negative_int(value: Any) -> int | None:
    """解析非负计数；异常值 fail-closed，避免伪造进度或失败率。"""

    if value is None:
        return None
    if isinstance(value, bool):
        raise SpecialistControlPlaneAdapterError("CONTROL_PLANE_RESPONSE_FIELD_INVALID")
    try:
        parsed = int(value)
        numeric = float(value)
    except (TypeError, ValueError, OverflowError):
        raise SpecialistControlPlaneAdapterError("CONTROL_PLANE_RESPONSE_FIELD_INVALID") from None
    if not math.isfinite(numeric) or numeric != parsed or parsed < 0:
        raise SpecialistControlPlaneAdapterError("CONTROL_PLANE_RESPONSE_FIELD_INVALID")
    return parsed


def _optional_number(value: Any) -> float | None:
    """解析非负吞吐、延迟和 lag 数字，拒绝负数和特殊浮点值。"""

    if value is None:
        return None
    parsed = _finite_number(value)
    if parsed is None or parsed < 0:
        raise SpecialistControlPlaneAdapterError("CONTROL_PLANE_RESPONSE_FIELD_INVALID")
    return parsed


def _milliseconds_to_seconds(value: Any) -> float | None:
    """把预检查请求的 timeout_ms 转成客户端超时；无效预算直接拒绝调用。"""

    if value is None:
        return None
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise SpecialistControlPlaneAdapterError("CONTROL_PLANE_TIMEOUT_INVALID")
    return value / 1_000.0


def _resolve_trace(*values: Any) -> str:
    """按配置、显式 trace、请求审计 ID 的优先级生成安全 trace Header。"""

    for value in values:
        if value is not None and str(value).strip():
            return _required_reference(value, "trace_id")
    raise SpecialistControlPlaneAdapterError("CONTROL_PLANE_TRACE_REQUIRED")


def _first(mapping: Mapping[str, Any], *keys: str) -> Any:
    """按顺序读取第一个存在且非 None 的字段，兼容真实 DTO 的 camelCase 别名。"""

    for key in keys:
        if key in mapping and mapping.get(key) is not None:
            return mapping.get(key)
    return None


def _first_present(mapping: Mapping[str, Any], *keys: str) -> Any:
    """按顺序读取第一个存在字段，即使值为 False 或 0 也不丢失。"""

    for key in keys:
        if key in mapping:
            return mapping.get(key)
    return None


def _context_value(context: Mapping[str, Any], *keys: str) -> Any:
    """从诊断上下文读取结构化字段，不递归扫描未知正文。"""

    value = _first(context, *keys)
    if isinstance(value, Mapping):
        return None
    return value


def _context_identifier(context: Mapping[str, Any], *keys: str) -> str | None:
    """读取并校验诊断 task 引用，允许 taskId 与 taskReference 两种协议别名。"""

    value = _context_value(context, *keys)
    return _optional_reference(value, "task_id")


def _diagnosis_execution_id(context: Mapping[str, Any]) -> str | None:
    """读取真实 Controller 唯一支持的 executionId query 参数。"""

    value = _context_value(context, "executionId", "execution_id")
    if value is None:
        nested = _first(context, "queryParams", "query_params")
        if isinstance(nested, Mapping):
            value = _context_value(nested, "executionId", "execution_id")
    return _optional_reference(value, "execution_id")


def _normalize_precheck_status(value: Any) -> PrecheckCheckStatus:
    """把真实 Java precheckStatus 映射到 Specialist 允许的四种检查状态。"""

    normalized = str(value).strip().upper().replace("-", "_").replace(" ", "_") if value is not None else ""
    aliases = {
        "PASSED": PrecheckCheckStatus.PASSED,
        "PASS": PrecheckCheckStatus.PASSED,
        "SUCCESS": PrecheckCheckStatus.PASSED,
        "SUCCEEDED": PrecheckCheckStatus.PASSED,
        "READY": PrecheckCheckStatus.PASSED,
        "READY_TO_EXECUTE": PrecheckCheckStatus.PASSED,
        "WARNING": PrecheckCheckStatus.WARNING,
        "REQUIRES_APPROVAL": PrecheckCheckStatus.WARNING,
        "FAILED": PrecheckCheckStatus.FAILED,
        "FAIL": PrecheckCheckStatus.FAILED,
        "BLOCKED": PrecheckCheckStatus.BLOCKED,
        "NOT_SUPPORTED_BY_CURRENT_RUNNER": PrecheckCheckStatus.BLOCKED,
        "PENDING_APPROVAL": PrecheckCheckStatus.BLOCKED,
    }
    try:
        return aliases[normalized]
    except KeyError:
        raise SpecialistControlPlaneAdapterError("CONTROL_PLANE_PRECHECK_STATUS_INVALID") from None


def _normalize_lifecycle_status(value: Any) -> TaskLifecycleStatus:
    """把 data-sync execution 状态收敛到 MONITOR 的六种生命周期状态。"""

    normalized = str(value).strip().upper().replace("-", "_").replace(" ", "_") if value is not None else ""
    aliases = {
        "RUNNING": TaskLifecycleStatus.RUNNING,
        "PAUSED": TaskLifecycleStatus.RUNNING,
        "RETRYING": TaskLifecycleStatus.RUNNING,
        "QUEUED": TaskLifecycleStatus.QUEUED,
        "PENDING": TaskLifecycleStatus.QUEUED,
        "WAITING": TaskLifecycleStatus.QUEUED,
        "SCHEDULED": TaskLifecycleStatus.SCHEDULED,
        "SUCCEEDED": TaskLifecycleStatus.SUCCEEDED,
        "SUCCESS": TaskLifecycleStatus.SUCCEEDED,
        "COMPLETED": TaskLifecycleStatus.SUCCEEDED,
        "PARTIALLY_SUCCEEDED": TaskLifecycleStatus.FAILED,
        "FAILED": TaskLifecycleStatus.FAILED,
        "CANCELLED": TaskLifecycleStatus.CANCELLED,
        "CANCELED": TaskLifecycleStatus.CANCELLED,
        "MANUALLY_TERMINATED": TaskLifecycleStatus.CANCELLED,
    }
    try:
        return aliases[normalized]
    except KeyError:
        raise SpecialistControlPlaneAdapterError("CONTROL_PLANE_LIFECYCLE_STATUS_INVALID") from None


def _infer_task_kind(
    execution: Mapping[str, Any],
    logs: Sequence[Mapping[str, Any]],
) -> TaskKind | None:
    """从结构化 syncMode/triggerType 推断任务长期语义，不从 message 猜测。"""

    raw = _first(execution, "taskKind", "taskType", "syncMode", "mode")
    if raw is None:
        raw = _latest_value(logs, "taskKind", "taskType", "syncMode")
    if raw is not None:
        normalized = str(raw).strip().upper().replace("-", "_").replace(" ", "_")
        if normalized in {"CDC", "CDC_STREAMING", "CDC_REALTIME", "REALTIME", "REAL_TIME", "STREAMING"}:
            return TaskKind.CDC_REALTIME
        if normalized in {"SCHEDULED", "SCHEDULED_FULL", "SCHEDULED_BATCH", "CRON", "PERIODIC", "RECURRING"}:
            return TaskKind.PERIODIC
        if normalized:
            return TaskKind.LONG_RUNNING
    trigger = str(_first(execution, "triggerType", "trigger") or "").strip().upper()
    if trigger in {"SCHEDULED", "CRON", "PERIODIC"}:
        return TaskKind.PERIODIC
    return None


def _first_identifier(mapping: Mapping[str, Any], *keys: str) -> str | None:
    """从记录读取第一个安全 ID，非法 ID 直接触发 fail-closed。"""

    value = _first(mapping, *keys)
    return _optional_reference(value, keys[0])


def _first_count(mapping: Mapping[str, Any], *keys: str) -> int | None:
    """读取记录中的第一个非负计数。"""

    value = _first(mapping, *keys)
    return _optional_non_negative_int(value)


def _sum_counts(records: Sequence[Mapping[str, Any]], key: str) -> int | None:
    """聚合对象账本中的记录数；完全缺失时保持未知而不是补零。"""

    values = [_optional_non_negative_int(record.get(key)) for record in records if record.get(key) is not None]
    return sum(values) if values else None


def _first_number(mapping: Mapping[str, Any], *keys: str) -> float | None:
    """读取一个结构化非负数值。"""

    value = _first(mapping, *keys)
    return _optional_number(value)


def _first_number_from_logs(logs: Sequence[Mapping[str, Any]], *keys: str) -> float | None:
    """从最新日志向前读取吞吐、延迟或 lag，避免使用未知正文。"""

    for record in _ordered_records(logs):
        value = _first_number(record, *keys)
        if value is not None:
            return value
    return None


def _baseline_throughput(logs: Sequence[Mapping[str, Any]], current: float | None) -> float | None:
    """用同一 execution 的历史结构化 speed 值计算确定性基线，缺少历史时保持未知。"""

    values: list[float] = []
    for record in logs:
        value = _first_number(record, "speedRowsPerSecond", "throughputRowsPerSecond", "throughput")
        if value is not None and value > 0:
            values.append(value)
    if current is not None and len(values) > 1:
        remaining = values[:-1] if values[-1] == current else values
        if remaining:
            return sum(remaining) / len(remaining)
    return None


def _derived_throughput(
    execution: Mapping[str, Any],
    rows_written: int | None,
    captured_seconds: float | None,
) -> float | None:
    """用 execution 的写入计数和开始时间计算可复核吞吐，不凭空创建速度。"""

    if rows_written is None or captured_seconds is None:
        return None
    started = _first_timestamp(execution, "startedAt")
    if started is None:
        return None
    duration = captured_seconds - _timestamp_seconds(started)
    return rows_written / duration if duration > 0 else None


def _ordered_records(records: Sequence[Mapping[str, Any]]) -> tuple[Mapping[str, Any], ...]:
    """按结构化 eventTime/updateTime 排序，无法解析时间时保持后端顺序。"""

    indexed = list(enumerate(records))
    if not any(_record_time(record) is not None for _, record in indexed):
        return tuple(records)
    indexed.sort(key=lambda item: (_record_time(item[1]) or float("-inf"), item[0]), reverse=True)
    return tuple(record for _, record in indexed)


def _latest_value(records: Sequence[Mapping[str, Any]], *keys: str) -> Any:
    """从按时间排序的结构化记录取最新字段。"""

    for record in _ordered_records(records):
        value = _first(record, *keys)
        if value is not None:
            return value
    return None


def _latest_safe_text(records: Sequence[Mapping[str, Any]], *keys: str) -> str | None:
    """取最新阶段/事件的低敏文本；日志 message 不在这里读取。"""

    value = _latest_value(records, *keys)
    if value is None:
        return None
    return _safe_public_text(value, 120) or None


def _record_time(record: Mapping[str, Any]) -> float | None:
    """读取记录的结构化时间用于排序，不把无效时间当作当前时间。"""

    value = _first(record, "eventTime", "updateTime", "finishedAt", "startedAt", "createTime")
    return _timestamp_seconds(value) if value is not None else None


def _timestamp_seconds(value: Any) -> float:
    """把已通过时间字段白名单的时间转为 Unix 秒；调用方已确保 value 非空。"""

    numeric = _finite_number(value)
    if numeric is not None and (not isinstance(value, str) or re.fullmatch(r"[+-]?\d+(?:\.\d+)?", value.strip())):
        return numeric
    text = str(value).strip()
    normalized = text[:-1] + "+00:00" if text.endswith("Z") else text
    try:
        parsed = datetime.fromisoformat(normalized)
    except (TypeError, ValueError, OverflowError):
        raise SpecialistControlPlaneAdapterError("CONTROL_PLANE_TIMESTAMP_INVALID") from None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.timestamp()


def _optional_timestamp(value: Any) -> str | None:
    """验证并返回低敏 ISO/Unix 时间文本，非法可选字段视为契约错误。"""

    if value is None or not str(value).strip():
        return None
    _timestamp_seconds(value)
    text = str(value).strip()
    return text[:120]


def _first_timestamp(mapping: Mapping[str, Any], *keys: str) -> str | None:
    """读取并验证记录中的第一个时间字段。"""

    return _optional_timestamp(_first(mapping, *keys))


def _capture_time(
    execution: Mapping[str, Any],
    logs: Sequence[Mapping[str, Any]],
) -> tuple[str, float | None]:
    """选择响应中最新的结构化观测时间，缺失时用本次适配器观测时间。"""

    candidates: list[tuple[str, float]] = []
    for record in (execution, *logs):
        for key in ("capturedAt", "eventTime", "updateTime", "finishedAt", "heartbeatTime", "createTime"):
            value = record.get(key)
            if value is None:
                continue
            text = _optional_timestamp(value)
            if text is not None:
                candidates.append((text, _timestamp_seconds(value)))
                break
    if candidates:
        text, seconds = max(candidates, key=lambda item: item[1])
        return text, seconds
    now = datetime.now(timezone.utc)
    return now.isoformat().replace("+00:00", "Z"), now.timestamp()


def _schedule_facts(
    executions: Sequence[Mapping[str, Any]],
    execution: Mapping[str, Any],
    logs: Sequence[Mapping[str, Any]],
    task_kind: TaskKind,
) -> tuple[dict[str, Any] | None, TaskLifecycleStatus | None, str | None, str | None, bool | None, int | None, str | None]:
    """聚合定期任务最近一次、下一次和错过调度事实，缺失字段保持 None。"""

    if task_kind != TaskKind.PERIODIC:
        return None, None, None, None, None, None, None
    selected_status = _normalize_lifecycle_status(execution.get("executionState"))
    last_run_at = _first_timestamp(execution, "finishedAt", "startedAt", "queuedAt")
    next_run_at = _first_timestamp(execution, "nextRunAt", "nextFireTime")
    nested = _first(execution, "schedule", "scheduleFacts")
    if isinstance(nested, Mapping):
        next_run_at = next_run_at or _optional_timestamp(_first(nested, "nextRunAt", "nextFireTime"))
    last_success_at = None
    for item in executions:
        if _normalize_lifecycle_status(item.get("executionState")) == TaskLifecycleStatus.SUCCEEDED:
            candidate = _first_timestamp(item, "finishedAt", "startedAt", "queuedAt")
            if candidate and (last_success_at is None or _timestamp_seconds(candidate) > _timestamp_seconds(last_success_at)):
                last_success_at = candidate
    schedule_missed = _first_present(execution, "scheduleMissed", "missedSchedule")
    if schedule_missed is not None:
        schedule_missed = _required_bool(schedule_missed, "scheduleMissed")
    if schedule_missed is None:
        schedule_missed = any(
            any(token in str(record.get("eventType") or "").upper() for token in ("MISFIRE", "SCHEDULE_MISSED"))
            for record in logs
        )
        if not schedule_missed:
            schedule_missed = None
    missed_count = _optional_non_negative_int(
        _first(execution, "missedScheduleCount", "scheduleMisfireCount")
    )
    schedule: dict[str, Any] = {
        "triggerType": _safe_code(_first(execution, "triggerType", "trigger")) or "SCHEDULED",
        "lastRunStatus": selected_status.value,
    }
    for key, value in (
        ("lastRunAt", last_run_at),
        ("nextRunAt", next_run_at),
        ("scheduleMissed", schedule_missed),
        ("missedScheduleCount", missed_count),
        ("lastSuccessAt", last_success_at),
    ):
        if value is not None:
            schedule[key] = value
    return schedule, selected_status, last_run_at, next_run_at, schedule_missed, missed_count, last_success_at


def _exception_facts(
    execution: Mapping[str, Any],
    logs: Sequence[Mapping[str, Any]],
    status: TaskLifecycleStatus,
) -> dict[str, Any] | None:
    """只保留错误分类码和脱敏短摘要，不暴露原始错误正文。"""

    code = _safe_code(_first(execution, "lastErrorCode", "errorCode"))
    message = _safe_public_text(_first(execution, "errorSummary", "lastErrorMessage"), 240)
    for record in _ordered_records(logs):
        level = str(record.get("logLevel") or "").upper()
        event_status = str(record.get("eventStatus") or "").upper()
        if level == "ERROR" or event_status in {"FAILED", "ERROR"}:
            code = code or _safe_code(record.get("eventType")) or "EXECUTION_ERROR"
            message = message or _safe_public_text(record.get("detailSummary"), 240)
            break
    if not code and status == TaskLifecycleStatus.FAILED:
        code = "EXECUTION_FAILED"
    if not code and not message:
        return None
    return {"code": code or "EXECUTION_ERROR", **({"message": message} if message else {})}


def _checkpoint_facts(
    execution: Mapping[str, Any],
    logs: Sequence[Mapping[str, Any]],
) -> dict[str, Any] | None:
    """把 checkpointRef 或明确的 checkpoint 白名单映射成低敏 checkpoint facts。"""

    raw = _first(execution, "checkpoint", "checkpointFacts")
    if isinstance(raw, Mapping):
        allowed = ("checkpointId", "checkpointRef", "type", "sequence", "offset", "capturedAt", "updatedAt")
        result: dict[str, Any] = {}
        for key in allowed:
            value = raw.get(key)
            if value is None:
                continue
            if key in {"checkpointId", "checkpointRef", "type"}:
                safe_value = _optional_reference(value, key)
            elif key in {"capturedAt", "updatedAt"}:
                safe_value = _optional_timestamp(value)
            else:
                safe_value = _optional_non_negative_int(value)
            if safe_value is not None:
                result[key] = safe_value
        if result:
            return result
    checkpoint_ref = _first(execution, "checkpointRef", "checkpointId")
    if checkpoint_ref is not None:
        return {"checkpointRef": _required_reference(checkpoint_ref, "checkpointRef")}
    # 日志中的 checkpointRef 也是结构化事实；不读取 detailSummary/message。
    value = _latest_value(logs, "checkpointRef", "checkpointId")
    return {"checkpointRef": _required_reference(value, "checkpointRef")} if value is not None else None


def _diagnosis_error_summaries(value: Any) -> tuple[dict[str, Any], ...]:
    """解析诊断 errors 数组，只保留 type/code/count/retryable 四类低敏字段。"""

    if value is None:
        return ()
    if not isinstance(value, list):
        raise SpecialistControlPlaneAdapterError("CONTROL_PLANE_RESPONSE_FIELD_INVALID")
    result: list[dict[str, Any]] = []
    for item in value[:32]:
        if not isinstance(item, Mapping):
            raise SpecialistControlPlaneAdapterError("CONTROL_PLANE_RESPONSE_FIELD_INVALID")
        count = _optional_non_negative_int(item.get("count"))
        retryable = _required_bool(item.get("retryable"), "retryable")
        result.append(
            {
                "errorType": _safe_code(item.get("errorType")) or "UNCLASSIFIED",
                "errorCode": _safe_code(item.get("errorCode")) or "UNCLASSIFIED",
                "count": count if count is not None else 0,
                "retryable": retryable,
            }
        )
    return tuple(result)


def _diagnosis_failed_object_summaries(value: Any) -> tuple[dict[str, Any], ...]:
    """解析失败对象数组的低敏分类，不返回 schema、对象名、分片边界或错误正文。"""

    if value is None:
        return ()
    if not isinstance(value, list):
        raise SpecialistControlPlaneAdapterError("CONTROL_PLANE_RESPONSE_FIELD_INVALID")
    result: list[dict[str, Any]] = []
    for item in value[:32]:
        if not isinstance(item, Mapping):
            raise SpecialistControlPlaneAdapterError("CONTROL_PLANE_RESPONSE_FIELD_INVALID")
        ordinal = _optional_non_negative_int(item.get("objectOrdinal"))
        summary = {
            "objectOrdinal": ordinal,
            "workUnitType": _safe_code(item.get("workUnitType")),
            "errorType": _safe_code(item.get("errorType")),
            "errorCode": _safe_code(item.get("errorCode")),
        }
        result.append({key: value for key, value in summary.items() if value is not None})
    return tuple(result)


def _stable_reference(prefix: str, *parts: Any) -> str:
    """生成不含正文的稳定证据引用，过长时只保留 SHA-256 摘要。"""

    normalized = [str(part).strip() for part in parts]
    candidate = ":".join((prefix, *normalized))
    if _SAFE_REFERENCE.fullmatch(candidate):
        return candidate
    digest = hashlib.sha256("|".join(normalized).encode("utf-8")).hexdigest()
    return f"{prefix}:sha256:{digest}"


__all__ = [
    "ACTOR_ID_HEADER",
    "AUTHORIZED_PROJECT_IDS_HEADER",
    "AGENT_DELEGATION_ID_HEADER",
    "AGENT_ID_HEADER",
    "AGENT_RUN_ID_HEADER",
    "AGENT_SESSION_ID_HEADER",
    "CONTROL_PLANE_PAYLOAD_POLICY",
    "ControlPlaneHttpClientError",
    "ControlPlaneHttpClientSettings",
    "DataSyncFailureDiagnosticHttpClient",
    "DataSyncHttpClientSettings",
    "DataSyncPrecheckHttpClient",
    "DataSyncTaskMonitoringHttpClient",
    "DATA_SCOPE_LEVEL_HEADER",
    "DIAGNOSIS_PATH_TEMPLATE",
    "EXECUTIONS_PATH_TEMPLATE",
    "HttpFailureDiagnosticClient",
    "HttpPrecheckControlPlaneClient",
    "HttpTaskMonitoringClient",
    "INTERNAL_SERVICE_TOKEN_HEADER",
    "LOGS_PATH_TEMPLATE",
    "MonitorTaskMonitoringHttpClient",
    "OBJECTS_PATH_TEMPLATE",
    "PRECHECK_PATH_TEMPLATE",
    "PrecheckControlPlaneHttpClient",
    "RecoveryDiagnosticHttpClient",
    "SOURCE_SERVICE_HEADER",
    "SpecialistControlPlaneAdapterError",
    "SpecialistControlPlaneHttpSettings",
    "TaskMonitoringHttpClient",
    "TENANT_ID_HEADER",
    "PROJECT_ID_HEADER",
    "TRACE_ID_HEADER",
]
