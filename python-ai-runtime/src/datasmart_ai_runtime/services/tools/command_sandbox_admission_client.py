"""Java command sandbox admission 客户端。

本客户端是 Python Runtime 接入 Java Agent Runtime 5.105 sandbox run admission 的桥梁。它的职责非常克制：

1. 把 Python runner 已知的低敏字段组装成 Java `AgentCommandSandboxRunAdmissionRequest`；
2. POST 到 Java 内部准入路由，由 Java Host 复核 lease、安全决策、workspace 引用和资源预算；
3. 只解析 Java 响应中的低敏白名单字段，并把结果交还给 runner；
4. 永远不把 commandLine、workingDirectory、stdout/stderr、payload body、prompt、SQL、URL、真实路径或凭据放进摘要。

为什么这一步要独立成 client：
- runner 的核心职责是“生成回执”，不应该承担网络协议细节；
- admission 是真实/准真实命令执行前的安全门，后续会自然扩展 mTLS、服务账号 token、重试、熔断和服务发现；
- 独立文件能让功能闭环更清晰，也能避免 `controlled_command_worker_runner.py` 再次膨胀超过 500 行。
"""

from __future__ import annotations

import json
from collections.abc import Iterable, Mapping
from dataclasses import dataclass
from typing import Any, Callable
from urllib.request import Request, urlopen

from datasmart_ai_runtime.services.tools.controlled_command_worker_contract import (
    _is_safe_workspace_reference,
    _non_negative_int,
    _required_text,
    _safe_fencing_token,
    _safe_machine_codes,
    _safe_recommended_actions,
    _safe_text,
)


COMMAND_SANDBOX_ADMISSION_SCHEMA_VERSION = "datasmart.python-ai-runtime.command-sandbox-admission-client.v1"
JAVA_COMMAND_SANDBOX_ADMISSION_ROUTE_TEMPLATE = (
    "/internal/agent-runtime/sessions/{session_id}/runs/{run_id}/tool-executions/command-sandbox-run-admissions"
)
COMMAND_SANDBOX_ADMISSION_PAYLOAD_POLICY = (
    "ADMISSION_ONLY_NO_COMMAND_LINE_NO_STDIO_NO_TOOL_ARGUMENTS_NO_PROMPT_NO_SQL_NO_PAYLOAD_BODY"
)


@dataclass(frozen=True)
class JavaCommandSandboxAdmissionResult:
    """Java sandbox admission 的低敏结果。

    字段说明：
    - `attempted` 表示客户端是否真正发起过 HTTP 调用；
    - `accepted` 表示 Java Host 是否允许进入 sandbox 执行区；
    - `skipped` 表示客户端未启用时主动跳过网络调用，生产链路若要求 admission 应把它视为拒绝；
    - `decision/issue_codes/evidence_codes/recommended_actions` 用于 runner 生成失败预检或运维提示；
    - `normalized_timeout_seconds/normalized_output_byte_limit_bytes` 是 Java 裁剪后的执行预算；
    - `process_started/raw_command_accepted` 理论上必须为 False，因为 admission 不是执行接口；
    - `endpoint_configured` 只表示是否配置了 baseUrl，不返回具体 URL，避免泄露内部服务拓扑。
    """

    attempted: bool
    accepted: bool
    skipped: bool
    status_code: int | None
    decision: str
    sandbox_run_id: str | None
    issue_codes: tuple[str, ...]
    evidence_codes: tuple[str, ...]
    recommended_actions: tuple[str, ...]
    normalized_timeout_seconds: int | None
    normalized_output_byte_limit_bytes: int | None
    isolation_mode: str | None
    process_started: bool
    raw_command_accepted: bool
    payload_policy: str
    error_code: str | None
    endpoint_configured: bool
    message: str

    def to_summary(self) -> dict[str, Any]:
        """输出适合测试、诊断和 runtime event 附带的低敏摘要。"""

        return {
            "schemaVersion": COMMAND_SANDBOX_ADMISSION_SCHEMA_VERSION,
            "payloadPolicy": self.payload_policy,
            "attempted": self.attempted,
            "accepted": self.accepted,
            "skipped": self.skipped,
            "statusCode": self.status_code,
            "decision": self.decision,
            "sandboxRunId": self.sandbox_run_id,
            "issueCodes": list(self.issue_codes),
            "evidenceCodes": list(self.evidence_codes),
            "recommendedActions": list(self.recommended_actions),
            "normalizedTimeoutSeconds": self.normalized_timeout_seconds,
            "normalizedOutputByteLimitBytes": self.normalized_output_byte_limit_bytes,
            "isolationMode": self.isolation_mode,
            "processStarted": self.process_started,
            "rawCommandAccepted": self.raw_command_accepted,
            "errorCode": self.error_code,
            "endpointConfigured": self.endpoint_configured,
            "message": self.message,
        }


@dataclass(frozen=True)
class JavaCommandSandboxAdmissionClientSettings:
    """Python -> Java sandbox admission HTTP 客户端配置。

    - `enabled` 默认关闭，避免单元测试或本地学习环境误触发真实 HTTP；
    - `base_url` 仅用于构造内部请求，永远不会出现在 result summary；
    - `timeout_seconds` 限制单次准入调用耗时，防止 worker 长时间卡在控制面；
    - `raise_on_failure` 默认关闭，调用失败会返回 `accepted=false` 的低敏结果，让 runner 写回失败预检；如果未来真实队列
      需要把网络异常交给外层重试框架，也可以显式打开并捕获异常。
    """

    enabled: bool = False
    base_url: str = "http://localhost:8091"
    timeout_seconds: int = 3
    raise_on_failure: bool = False


class CommandSandboxAdmissionClientError(RuntimeError):
    """Java sandbox admission 客户端错误。

    只有在 `raise_on_failure=True` 时抛出。默认返回低敏失败结果，是为了让 runner 能稳定生成 `FAILED_PRECHECK` 回执，
    避免“准入服务暂时不可用”被误解释成“可以继续执行”。
    """


class JavaCommandSandboxAdmissionClient:
    """向 Java agent-runtime 申请 command sandbox run admission 的轻量客户端。"""

    def __init__(
        self,
        settings: JavaCommandSandboxAdmissionClientSettings | None = None,
        *,
        urlopen_func: Callable[..., Any] = urlopen,
    ) -> None:
        self._settings = settings or JavaCommandSandboxAdmissionClientSettings()
        self._urlopen = urlopen_func

    def request_admission(
        self,
        *,
        session_id: str,
        run_id: str,
        request: Any,
        trace_id: str | None = None,
    ) -> JavaCommandSandboxAdmissionResult:
        """向 Java Host 申请沙箱准入。

        调用流程：
        1. 客户端未启用时直接返回 `skipped=true`，由 runner 根据 `require_sandbox_admission` 决定是否 fail-closed；
        2. 启用后只提交 Java DTO 白名单字段，`fencingToken` 仅用于 Java 校验，不会进入摘要；
        3. 响应只解析低敏字段，遇到非 JSON、非 2xx、Java 失败响应或不安全字段时统一视为拒绝；
        4. 如果 Java 响应声称已经启动进程或接收了原始命令正文，客户端会拒绝该响应，防止 admission 与 execution 语义混淆。
        """

        endpoint_configured = bool(self._settings.base_url.strip())
        if not self._settings.enabled:
            return self._disabled(endpoint_configured)
        if not endpoint_configured:
            return self._failure("ENDPOINT_NOT_CONFIGURED", None)

        http_request = self._build_http_request(session_id, run_id, request, trace_id)
        try:
            with self._urlopen(http_request, timeout=self._settings.timeout_seconds) as response:  # noqa: S310
                status_code = int(getattr(response, "status", 200))
                body = response.read().decode("utf-8")
        except Exception as exc:  # pragma: no cover - 真实网络错误由集成环境覆盖
            if self._settings.raise_on_failure:
                raise CommandSandboxAdmissionClientError("申请 Java command sandbox admission 失败") from exc
            return self._failure("HTTP_ADMISSION_FAILED", None)
        if status_code < 200 or status_code >= 300:
            if self._settings.raise_on_failure:
                raise CommandSandboxAdmissionClientError("Java command sandbox admission 返回非 2xx 状态")
            return self._failure("JAVA_ADMISSION_STATUS_NOT_2XX", status_code)
        return self._parse_success(body, status_code)

    def _build_http_request(
        self,
        session_id: str,
        run_id: str,
        request: Any,
        trace_id: str | None,
    ) -> Request:
        """构造 Java admission HTTP 请求。

        注意：这里会把 `fencingToken` 放入内部请求正文，因为 Java 必须据此校验 worker lease；但 URL、token 和完整请求正文
        都不会出现在 `JavaCommandSandboxAdmissionResult.to_summary()` 中。
        """

        route = JAVA_COMMAND_SANDBOX_ADMISSION_ROUTE_TEMPLATE.format(
            session_id=_required_text(session_id, "session_id"),
            run_id=_required_text(run_id, "run_id"),
        )
        body = json.dumps(self._build_payload(request), ensure_ascii=False).encode("utf-8")
        return Request(
            url=f"{self._settings.base_url.rstrip('/')}{route}",
            data=body,
            headers={
                "Accept": "application/json",
                "Content-Type": "application/json; charset=utf-8",
                "X-DataSmart-Trace-Id": trace_id or "",
                "X-DataSmart-Source-Service": "python-ai-runtime",
            },
            method="POST",
        )

    def _build_payload(self, request: Any) -> dict[str, Any]:
        """把 runner 请求转换为 Java admission DTO 白名单。

        本函数故意不读取任何 `commandLine`、`arguments`、`workingDirectory` 或 stdout/stderr 字段；即使未来调用方对象上出现
        这些属性，也不会被序列化到 Java admission 请求中。
        """

        workspace_reference = getattr(request, "workspace_reference", None)
        if workspace_reference and not _is_safe_workspace_reference(str(workspace_reference)):
            raise ValueError("workspace_reference 必须是低敏受控引用，不能是 URL、本机路径、对象正文或凭据")
        return {
            "commandId": _required_text(getattr(request, "command_id", None), "command_id"),
            "executorId": _safe_text(
                getattr(request, "executor_id", None),
                fallback="python-controlled-command-worker",
                max_length=160,
            ),
            "fencingToken": _safe_fencing_token(getattr(request, "fencing_token", None)),
            "workerLeaseVersion": getattr(request, "worker_lease_version", None),
            "workerLeaseExpiresAtMs": getattr(request, "worker_lease_expires_at_ms", None),
            "tenantId": getattr(request, "tenant_id", None),
            "projectId": getattr(request, "project_id", None),
            "actorId": getattr(request, "actor_id", None),
            "commandSafetyDecision": _safe_text(
                getattr(request, "command_safety_decision", None),
                fallback="UNKNOWN",
                max_length=100,
            ),
            "commandSafetyPolicyVersion": _safe_text(
                getattr(request, "command_safety_policy_version", None),
                max_length=160,
            ),
            "commandSafetyIssueCodes": list(
                _safe_machine_codes(tuple(getattr(request, "command_safety_issue_codes", ()) or ()))
            ),
            "requestedIsolationMode": _safe_text(
                getattr(request, "requested_isolation_mode", None),
                fallback="NO_NETWORK_PROCESS_SANDBOX",
                max_length=80,
            ),
            "requestedTimeoutSeconds": _non_negative_int(getattr(request, "normalized_timeout_seconds", 0)),
            "requestedOutputByteLimitBytes": _non_negative_int(
                getattr(request, "normalized_output_byte_limit_bytes", 0)
            ),
            "requestedCpuMillicores": _non_negative_int(getattr(request, "requested_cpu_millicores", 0)),
            "requestedMemoryMb": _non_negative_int(getattr(request, "requested_memory_mb", 0)),
            "workspaceReference": workspace_reference,
            "toolCode": _safe_text(getattr(request, "tool_code", None), fallback="command.run-program", max_length=160),
            "requesterComponent": _safe_text(
                getattr(request, "target_service", None),
                fallback="python-ai-runtime-controlled-worker",
                max_length=120,
            ),
            "idempotencyKey": _safe_text(getattr(request, "idempotency_key", None), max_length=220),
        }

    def _parse_success(self, response_body: str, status_code: int) -> JavaCommandSandboxAdmissionResult:
        """解析 Java `PlatformApiResponse<AgentCommandSandboxRunAdmissionResponse>`。

        解析策略采用白名单而不是透传：即使 Java 未来响应里包含更多内部字段，Python 侧也不会把它们扩散到 summary、日志或
        runtime event 中。
        """

        try:
            payload = json.loads(response_body)
        except json.JSONDecodeError:
            return self._failure("JAVA_ADMISSION_RESPONSE_NOT_JSON", status_code)
        if not isinstance(payload, Mapping) or payload.get("code") != 0:
            return self._failure("JAVA_ADMISSION_RESPONSE_FAILED", status_code)
        data = payload.get("data")
        if not isinstance(data, Mapping):
            return self._failure("JAVA_ADMISSION_RESPONSE_DATA_INVALID", status_code)
        process_started = bool(data.get("processStarted", False))
        raw_command_accepted = bool(data.get("rawCommandAccepted", False))
        if process_started or raw_command_accepted:
            return self._failure("JAVA_ADMISSION_RESPONSE_UNSAFE", status_code)

        issue_codes = _safe_machine_codes(tuple(_list_strings(data.get("issueCodes"))))
        evidence_codes = _safe_machine_codes(tuple(_list_strings(data.get("evidenceCodes"))))
        recommended_actions = _safe_recommended_actions(tuple(_list_strings(data.get("recommendedActions"))))
        return JavaCommandSandboxAdmissionResult(
            attempted=True,
            accepted=bool(data.get("accepted", False)),
            skipped=False,
            status_code=status_code,
            decision=_safe_text(data.get("decision"), fallback="UNKNOWN", max_length=120) or "UNKNOWN",
            sandbox_run_id=_safe_text(data.get("sandboxRunId"), max_length=220),
            issue_codes=issue_codes,
            evidence_codes=evidence_codes,
            recommended_actions=recommended_actions,
            normalized_timeout_seconds=_optional_non_negative_int(data.get("normalizedTimeoutSeconds")),
            normalized_output_byte_limit_bytes=_optional_non_negative_int(
                data.get("normalizedOutputByteLimitBytes")
            ),
            isolation_mode=_safe_text(data.get("isolationMode"), max_length=80),
            process_started=False,
            raw_command_accepted=False,
            payload_policy=_safe_text(
                data.get("payloadPolicy"),
                fallback=COMMAND_SANDBOX_ADMISSION_PAYLOAD_POLICY,
                max_length=180,
            )
            or COMMAND_SANDBOX_ADMISSION_PAYLOAD_POLICY,
            error_code=None,
            endpoint_configured=True,
            message="Java command sandbox admission 已返回低敏准入结果。",
        )

    def _disabled(self, endpoint_configured: bool) -> JavaCommandSandboxAdmissionResult:
        """客户端未启用时的低敏结果。

        这不是成功准入，只是本地 dry-run 场景的显式跳过。runner 如果被要求必须 admission，会把它转换为失败预检。
        """

        return JavaCommandSandboxAdmissionResult(
            attempted=False,
            accepted=False,
            skipped=True,
            status_code=None,
            decision="ADMISSION_CLIENT_DISABLED",
            sandbox_run_id=None,
            issue_codes=("SANDBOX_ADMISSION_CLIENT_DISABLED",),
            evidence_codes=(),
            recommended_actions=("启用 Java sandbox admission 客户端或切换为预检模式。",),
            normalized_timeout_seconds=None,
            normalized_output_byte_limit_bytes=None,
            isolation_mode=None,
            process_started=False,
            raw_command_accepted=False,
            payload_policy=COMMAND_SANDBOX_ADMISSION_PAYLOAD_POLICY,
            error_code="ADMISSION_CLIENT_DISABLED",
            endpoint_configured=endpoint_configured,
            message="Java command sandbox admission 客户端未启用，未发起 HTTP 准入调用。",
        )

    def _failure(self, error_code: str, status_code: int | None) -> JavaCommandSandboxAdmissionResult:
        """构造统一失败结果，不包含异常栈、内部 endpoint 或响应正文。"""

        return JavaCommandSandboxAdmissionResult(
            attempted=True,
            accepted=False,
            skipped=False,
            status_code=status_code,
            decision="ADMISSION_CLIENT_FAILED",
            sandbox_run_id=None,
            issue_codes=(error_code,),
            evidence_codes=(),
            recommended_actions=("保持 fail-closed，不启动命令执行，并按队列重试或死信策略处理。",),
            normalized_timeout_seconds=None,
            normalized_output_byte_limit_bytes=None,
            isolation_mode=None,
            process_started=False,
            raw_command_accepted=False,
            payload_policy=COMMAND_SANDBOX_ADMISSION_PAYLOAD_POLICY,
            error_code=error_code,
            endpoint_configured=bool(self._settings.base_url.strip()),
            message="Java command sandbox admission 未完成，调用方必须禁止启动命令执行。",
        )


def _list_strings(value: Any) -> tuple[str, ...]:
    """把 Java 响应里的列表字段收敛成字符串元组，非列表输入直接忽略。"""

    if not isinstance(value, Iterable) or isinstance(value, (str, bytes, Mapping)):
        return ()
    return tuple(str(item) for item in value if item is not None)


def _optional_non_negative_int(value: Any) -> int | None:
    """解析 Java 响应中的可选非负整数字段。"""

    if value is None:
        return None
    return _non_negative_int(value)
