"""permission-admin 工具动作恢复事实远程客户端。

本模块把 5.68 的 `ToolActionResumeFactProvider` 抽象向真实 Java 控制面推进一步：
Python Runtime 仍然不执行工具、不写 outbox、不派发 worker，但当调用方携带 approvalConfirmationId 时，
它可以向 permission-admin 验证“这个审批事实是否真实存在、是否已批准、是否匹配当前 tenant/project/actor/run/tool”。

为什么先做“审批事实校验”而不是一次性实现所有恢复事实查询：
- Java `permission-admin` 当前已经存在 `/permissions/agent/tool-action-approvals/evaluate` 端点，可直接复用；
- 澄清事实、outbox confirmation、worker receipt projection 还没有统一 bundle API，贸然在 Python 侧拼多个半成品接口
  会让恢复链路过早耦合；
- 先把 approvalId 从“客户端自报字符串”升级为“服务端可验证事实”，可以马上补上最危险的绕过风险；
- 后续 Java 控制面可以新增按 checkpointId/threadId 查询的 resume-fact bundle，届时只需要替换本 provider 的内部查询逻辑。
"""

from __future__ import annotations

import json
from collections.abc import Mapping
from dataclasses import dataclass
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from datasmart_ai_runtime.services.tools.tool_action_resume_fact_provider import (
    ToolActionResumeFactSnapshot,
)


DEFAULT_PERMISSION_ADMIN_APPROVAL_FACT_EVALUATE_PATH = "/permissions/agent/tool-action-approvals/evaluate"
PERMISSION_ADMIN_RESUME_FACT_SOURCE = "PERMISSION_ADMIN_APPROVAL_FACT_PROVIDER"
DISABLED_RESUME_FACT_SOURCE = "PERMISSION_ADMIN_APPROVAL_FACT_PROVIDER_DISABLED"


class PermissionAdminResumeFactClientError(RuntimeError):
    """permission-admin 恢复事实客户端异常。

    异常只携带低敏机器码，不携带 URL、响应 body、Header、token 或 Java 端 message。API 层如果捕获到该异常，
    也只能把 `code` 这类机器码暴露出去，不能把底层异常字符串透传给外部调用方。
    """

    def __init__(self, code: str) -> None:
        self.code = code
        super().__init__(code)


@dataclass(frozen=True)
class PermissionAdminResumeFactClientSettings:
    """permission-admin 恢复事实客户端配置。

    字段说明：
    - `enabled`：默认关闭，避免本地学习、单元测试或未启动 Java 服务时发生网络副作用；
    - `base_url`：permission-admin 服务根地址，通常来自 `DATASMART_PERMISSION_ADMIN_BASE_URL`；
    - `approval_fact_evaluate_path`：审批事实评估路径，保留可配置是为了兼容 gateway 前缀或灰度路由；
    - `timeout_seconds`：同步 preflight 的网络超时，必须较短，避免 resume-preview 长时间阻塞用户交互；
    - `service_token`：可选服务间 Bearer token。当前项目还在演进统一服务账号签名，因此这里只预留 Header
      装配能力，不把 token 写入摘要、日志或异常。
    """

    enabled: bool = False
    base_url: str = "http://localhost:8085"
    approval_fact_evaluate_path: str = DEFAULT_PERMISSION_ADMIN_APPROVAL_FACT_EVALUATE_PATH
    timeout_seconds: int = 3
    service_token: str | None = None


class JavaPermissionAdminToolActionResumeFactClient:
    """通过 Java permission-admin 校验工具动作审批恢复事实。

    该类实现 `ToolActionResumeFactProvider` 协议。它的业务语义不是“寻找所有缺失事实”，而是验证调用方提交的
    approvalConfirmationId 是否可被服务端采信。这样可以避免以下风险：
    - 前端或外部 Agent 伪造 `approvalConfirmationId` 字段；
    - 旧 run 的 approvalId 被跨租户、跨项目、跨工具复用；
    - 已过期、已拒绝、作用域不匹配的审批事实被 Python 当作可恢复条件。

    返回值仍然只包含事实类型和低敏错误码，不回显 approvalFactId、审批意见、Java reason、URL 或原始响应。
    """

    def __init__(
        self,
        settings: PermissionAdminResumeFactClientSettings | None = None,
        *,
        urlopen_func: Any = urlopen,
    ) -> None:
        self._settings = settings or PermissionAdminResumeFactClientSettings()
        self._urlopen = urlopen_func

    def collect(
        self,
        *,
        checkpoint: Any,
        request_payload: Mapping[str, Any] | None = None,
    ) -> ToolActionResumeFactSnapshot:
        """收集并校验审批恢复事实。

        执行流程：
        1. 未启用时返回空事实源，保持本地默认行为；
        2. 从请求顶层、`resumeFacts`、`context` 中提取 approvalFactId，但不把该值写入响应；
        3. 只构造 Java DTO 白名单字段，敏感 payload 一律不发送；
        4. 根据 Java `approved/decision/retryable/issueCodes` 生成 available/missing/rejected fact types。
        """

        if not self._settings.enabled:
            return ToolActionResumeFactSnapshot(source=DISABLED_RESUME_FACT_SOURCE)

        payload = request_payload if isinstance(request_payload, Mapping) else {}
        approval_fact_id = _approval_fact_id_from_payload(payload)
        if not approval_fact_id:
            return ToolActionResumeFactSnapshot(
                source=PERMISSION_ADMIN_RESUME_FACT_SOURCE,
                missing_fact_types=("APPROVAL_CONFIRMATION_FACT",),
                error_codes=("APPROVAL_FACT_ID_REQUIRED_FOR_REMOTE_VALIDATION",),
            )

        request_body = self.build_approval_fact_evaluate_payload(
            checkpoint=checkpoint,
            request_payload=payload,
            approval_fact_id=approval_fact_id,
        )
        try:
            data = self._post_platform_request(request_body, trace_id=_trace_id_from_payload(payload, checkpoint))
        except PermissionAdminResumeFactClientError as exc:
            return ToolActionResumeFactSnapshot(
                source=PERMISSION_ADMIN_RESUME_FACT_SOURCE,
                missing_fact_types=("APPROVAL_CONFIRMATION_FACT",),
                rejected_fact_types=("APPROVAL_CONFIRMATION_FACT",),
                error_codes=(exc.code,),
            )

        if _approved(data):
            available = ["APPROVAL_CONFIRMATION_FACT"]
            if _text(data.get("policyVersion")):
                available.append("POLICY_VERSION")
            return ToolActionResumeFactSnapshot(
                source=PERMISSION_ADMIN_RESUME_FACT_SOURCE,
                available_fact_types=tuple(available),
                fact_reference_count=1,
                error_codes=_safe_code_tuple(data.get("issueCodes")),
            )

        return ToolActionResumeFactSnapshot(
            source=PERMISSION_ADMIN_RESUME_FACT_SOURCE,
            missing_fact_types=("APPROVAL_CONFIRMATION_FACT",),
            rejected_fact_types=("APPROVAL_CONFIRMATION_FACT",),
            error_codes=_not_approved_error_codes(data),
        )

    def build_approval_fact_evaluate_payload(
        self,
        *,
        checkpoint: Any,
        request_payload: Mapping[str, Any],
        approval_fact_id: str,
    ) -> dict[str, Any]:
        """构造 Java `AgentToolActionApprovalFactEvaluateRequest` 请求体。

        请求体只包含 permission-admin 校验作用域所需字段：
        - `approvalFactId` 是被验证的事实引用；
        - tenant/project/actor/session/run/command/tool/policy 是低敏控制面定位字段；
        - prompt、SQL、arguments、payloadReference、graphRunSummary、模型输出、凭证都不会进入请求体。

        tenantId/projectId 在 Java DTO 中是 Long，这里只在值可解析为整数时发送，否则置空，避免 Python 本地测试里的
        `tenant-api` 这类字符串造成 Jackson 反序列化失败。
        """

        context = request_payload.get("context")
        context_mapping = context if isinstance(context, Mapping) else {}
        return {
            "approvalFactId": approval_fact_id,
            "tenantId": _optional_int(
                _first_text(
                    request_payload.get("tenantId"),
                    context_mapping.get("tenantId"),
                    getattr(checkpoint, "tenant_id", None),
                )
            ),
            "projectId": _optional_int(
                _first_text(
                    request_payload.get("projectId"),
                    context_mapping.get("projectId"),
                    getattr(checkpoint, "project_id", None),
                )
            ),
            "actorId": _first_text(
                request_payload.get("actorId"),
                context_mapping.get("actorId"),
                getattr(checkpoint, "actor_id", None),
            ),
            "sessionId": _first_text(
                request_payload.get("sessionId"),
                context_mapping.get("sessionId"),
                getattr(checkpoint, "session_id", None),
            ),
            "runId": _first_text(
                request_payload.get("runId"),
                context_mapping.get("runId"),
                getattr(checkpoint, "run_id", None),
                getattr(checkpoint, "thread_id", None),
            ),
            "commandId": _first_text(
                request_payload.get("commandId"),
                request_payload.get("clientRequestId"),
                context_mapping.get("commandId"),
                context_mapping.get("clientRequestId"),
                getattr(checkpoint, "request_id", None),
                getattr(checkpoint, "checkpoint_id", None),
            ),
            "toolCode": _tool_code_from_payload_or_checkpoint(request_payload, checkpoint),
            "requestedPolicyVersion": _first_text(
                request_payload.get("policyVersion"),
                context_mapping.get("policyVersion"),
                _fact_value_from_payload(request_payload, "policyVersion"),
                _policy_version_from_checkpoint(checkpoint),
            ),
        }

    def _post_platform_request(self, request_body: Mapping[str, Any], *, trace_id: str | None) -> Mapping[str, Any]:
        """发送 permission-admin 请求并解析统一响应信封。

        这里不把 HTTP 状态码、响应 body 或 Java message 拼进异常字符串，因为这些内容可能包含内部网关路径、
        Java 业务说明或部署信息。调用方只需要知道“远程事实源不可用/响应无效/业务拒绝”这类低敏机器码。
        """

        request = Request(
            self._approval_fact_evaluate_url(),
            data=json.dumps(request_body, ensure_ascii=False).encode("utf-8"),
            headers=self._headers(trace_id=trace_id),
            method="POST",
        )
        try:
            with self._urlopen(request, timeout=max(1, int(self._settings.timeout_seconds))) as response:
                payload = json.loads(response.read().decode("utf-8"))
        except HTTPError as exc:
            raise PermissionAdminResumeFactClientError("PERMISSION_ADMIN_HTTP_ERROR") from exc
        except URLError as exc:
            raise PermissionAdminResumeFactClientError("PERMISSION_ADMIN_NETWORK_ERROR") from exc
        except TimeoutError as exc:
            raise PermissionAdminResumeFactClientError("PERMISSION_ADMIN_TIMEOUT") from exc
        except (OSError, ValueError, json.JSONDecodeError) as exc:
            raise PermissionAdminResumeFactClientError("PERMISSION_ADMIN_RESPONSE_INVALID") from exc

        if not isinstance(payload, Mapping):
            raise PermissionAdminResumeFactClientError("PERMISSION_ADMIN_RESPONSE_INVALID")
        if int(payload.get("code") or 0) != 0:
            raise PermissionAdminResumeFactClientError("PERMISSION_ADMIN_PLATFORM_RESPONSE_REJECTED")
        data = payload.get("data")
        if not isinstance(data, Mapping):
            raise PermissionAdminResumeFactClientError("PERMISSION_ADMIN_DATA_MISSING")
        return data

    def _approval_fact_evaluate_url(self) -> str:
        """拼接审批事实评估 URL，兼容 base_url/path 是否带斜杠的不同写法。"""

        base_url = str(self._settings.base_url or "").rstrip("/")
        path = str(self._settings.approval_fact_evaluate_path or "").strip()
        if not base_url:
            raise PermissionAdminResumeFactClientError("PERMISSION_ADMIN_BASE_URL_MISSING")
        return f"{base_url}/{path.lstrip('/')}"

    def _headers(self, *, trace_id: str | None) -> dict[str, str]:
        """构造服务间调用 Header。

        Header 只包含内容类型、低敏 traceId 和来源服务标识。`service_token` 如果配置，只作为 Authorization
        发送给 Java 服务，不会进入任何 API summary；后续项目完成 gateway HMAC/服务账号体系后，可以在这里替换
        为更严格的签名 Header。
        """

        headers = {
            "Content-Type": "application/json; charset=utf-8",
            "Accept": "application/json",
            "X-DataSmart-Source-Service": "python-ai-runtime",
        }
        if trace_id:
            headers["X-DataSmart-Trace-Id"] = trace_id
        if self._settings.service_token:
            headers["Authorization"] = f"Bearer {self._settings.service_token}"
        return headers


def _approved(data: Mapping[str, Any]) -> bool:
    """解析 permission-admin 低敏判定结果。"""

    if data.get("approved") is True:
        return True
    decision = str(data.get("decision") or data.get("status") or "").strip().upper()
    return decision == "APPROVED"


def _not_approved_error_codes(data: Mapping[str, Any]) -> tuple[str, ...]:
    """生成未通过审批校验时的低敏错误码集合。"""

    codes = ["APPROVAL_FACT_NOT_APPROVED"]
    decision = _text(data.get("decision"))
    if decision:
        codes.append(f"APPROVAL_DECISION_{decision.upper()}")
    if data.get("retryable") is True:
        codes.append("APPROVAL_FACT_RETRYABLE")
    for code in _safe_code_tuple(data.get("issueCodes")):
        if code not in codes:
            codes.append(code)
    return tuple(codes[:8])


def _approval_fact_id_from_payload(payload: Mapping[str, Any]) -> str | None:
    """从请求中提取 approval fact ID，但只供远程校验使用，不进入响应。"""

    return _first_text(
        payload.get("approvalFactId"),
        payload.get("approvalConfirmationId"),
        payload.get("confirmationId"),
        _fact_value_from_payload(payload, "approvalFactId"),
        _fact_value_from_payload(payload, "approvalConfirmationId"),
        _fact_value_from_payload(payload, "confirmationId"),
    )


def _fact_value_from_payload(payload: Mapping[str, Any], key: str) -> Any:
    """读取 `resumeFacts` 内层事实字段。"""

    facts = payload.get("resumeFacts")
    return facts.get(key) if isinstance(facts, Mapping) else None


def _tool_code_from_payload_or_checkpoint(payload: Mapping[str, Any], checkpoint: Any) -> str | None:
    """解析当前受控工具编码。

    MCP tools/call 请求通常把工具名放在 `params.name`；执行前图 checkpoint 则把工具名保存在低敏 step 摘要里。
    这里按“请求显式字段优先、MCP params 其次、checkpoint 步骤兜底”的顺序读取。
    """

    params = payload.get("params")
    params_mapping = params if isinstance(params, Mapping) else {}
    return _first_text(
        payload.get("toolCode"),
        payload.get("toolName"),
        params_mapping.get("name"),
        _first_checkpoint_step_field(checkpoint, "toolName"),
    )


def _policy_version_from_checkpoint(checkpoint: Any) -> str | None:
    """从 checkpoint 的 proposal requestPayload 中读取策略版本。"""

    graph_run = getattr(checkpoint, "graph_run_summary", None)
    if not isinstance(graph_run, Mapping):
        return None
    steps = graph_run.get("steps")
    if not isinstance(steps, (list, tuple)):
        return None
    for step in steps:
        if not isinstance(step, Mapping):
            continue
        proposal = step.get("proposalSubmission")
        if not isinstance(proposal, Mapping):
            continue
        request_payload = proposal.get("requestPayload")
        if isinstance(request_payload, Mapping):
            value = _text(request_payload.get("policyVersion"))
            if value:
                return value
    return None


def _first_checkpoint_step_field(checkpoint: Any, field: str) -> str | None:
    """读取 checkpoint 第一个低敏 step 字段。"""

    graph_run = getattr(checkpoint, "graph_run_summary", None)
    if not isinstance(graph_run, Mapping):
        return None
    steps = graph_run.get("steps")
    if not isinstance(steps, (list, tuple)):
        return None
    for step in steps:
        if isinstance(step, Mapping):
            value = _text(step.get(field))
            if value:
                return value
    return None


def _trace_id_from_payload(payload: Mapping[str, Any], checkpoint: Any) -> str | None:
    """解析 traceId，用于 Java 日志链路关联。"""

    context = payload.get("context")
    context_mapping = context if isinstance(context, Mapping) else {}
    return _first_text(
        payload.get("traceId"),
        payload.get("requestId"),
        context_mapping.get("traceId"),
        context_mapping.get("requestId"),
        getattr(checkpoint, "request_id", None),
        getattr(checkpoint, "checkpoint_id", None),
    )


def _safe_code_tuple(value: Any) -> tuple[str, ...]:
    """解析 Java 返回的低敏 issue/evidence code，限制数量和长度避免响应膨胀。"""

    if not isinstance(value, (list, tuple)):
        return ()
    result: list[str] = []
    for item in value:
        text = _text(item)
        if text and text not in result:
            result.append(text[:80])
        if len(result) >= 6:
            break
    return tuple(result)


def _first_text(*values: Any) -> str | None:
    """返回第一个非空字符串。"""

    for value in values:
        text = _text(value)
        if text:
            return text
    return None


def _optional_int(value: Any) -> int | None:
    """解析可选整数，非法值返回 None。"""

    text = _text(value)
    if not text:
        return None
    try:
        return int(text)
    except ValueError:
        return None


def _text(value: Any) -> str | None:
    """解析非空字符串。"""

    if value is None:
        return None
    text = str(value).strip()
    return text or None
