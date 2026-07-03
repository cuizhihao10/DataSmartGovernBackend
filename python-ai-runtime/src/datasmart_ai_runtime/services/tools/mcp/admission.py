"""MCP Durable 执行前的可信 Admission 构造器。

`McpDurableToolExecutionService` 已经能在拿到 `McpToolCallAdmission` 后执行真实 MCP `tools/call`。
但在真实产品里，admission 不应该由测试代码、模型输出或普通 HTTP 请求手工拼出来，而应该来自 Java
agent-runtime、permission-admin、readiness graph、command proposal、outbox 和人工审批事实。

本模块就是这一层“控制面事实 -> MCP admission”的收敛合同：
- 它只读取低敏事实字段，不读取 prompt、SQL、样本数据、工具结果正文或外部 endpoint；
- 它接受 Java 侧常见 camelCase，也兼容 Python 内部 snake_case，降低跨语言接入成本；
- 它会把缺失字段、权限未授予、审批未验证、allowlist 不包含目标工具等问题汇总成机器码；
- 它输出 `McpToolCallAdmission`，交给 MCP Runtime 做最终 fail-closed 校验。

这样做的商业意义是：以后无论 MCP 调用由 LangGraph 节点、Kafka worker、HTTP 内部接口还是 Java outbox
dispatcher 触发，都不能绕开同一套 admission 生成与校验规则。
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass, field
from typing import Any

from datasmart_ai_runtime.services.tools.mcp.contracts import McpToolCallAdmission


MCP_ADMISSION_BUILDER_SCHEMA_VERSION = "datasmart.mcp-admission-builder.v1"


@dataclass(frozen=True)
class McpAdmissionBuildResult:
    """Admission 构造结果。

    字段说明：
    - `ready`：是否已经具备执行 MCP 工具的最小可信事实；
    - `admission`：构造成功时的 `McpToolCallAdmission`，失败时为 `None`；
    - `issue_codes`：低基数机器码，供 Java/Python runtime event、前端卡片和运维诊断使用；
    - `source_summary`：输入事实的低敏摘要，只包含引用 ID 和布尔状态，不包含正文。
    """

    ready: bool
    admission: McpToolCallAdmission | None
    issue_codes: tuple[str, ...] = field(default_factory=tuple)
    source_summary: Mapping[str, Any] = field(default_factory=dict)

    def to_summary(self) -> dict[str, Any]:
        """输出可进入日志、事件或诊断接口的低敏摘要。"""

        return {
            "schemaVersion": MCP_ADMISSION_BUILDER_SCHEMA_VERSION,
            "ready": self.ready,
            "issueCodes": self.issue_codes,
            "sourceSummary": dict(self.source_summary),
            "admission": {
                "source": self.admission.source if self.admission else None,
                "readinessDecision": self.admission.readiness_decision if self.admission else None,
                "permissionGranted": self.admission.permission_granted if self.admission else None,
                "approvalVerified": self.admission.approval_verified if self.admission else None,
                "allowedToolCount": len(self.admission.allowed_internal_tool_names) if self.admission else 0,
                "scopePresent": bool(
                    self.admission
                    and self.admission.tenant_id
                    and self.admission.project_id
                    and self.admission.workspace_key
                    and self.admission.actor_id
                    and self.admission.run_id
                    and self.admission.call_id
                ),
            },
            "payloadPolicy": "LOW_SENSITIVE_MCP_ADMISSION_FACTS_ONLY",
        }


class McpToolCallAdmissionBuilder:
    """把 Java/permission/readiness/outbox 事实转换为 MCP admission。

    构造器不尝试“猜测授权”。例如缺少 `permissionGranted` 时不会默认放行，缺少 `approvalVerified`
    时也不会因为工具看起来只读就伪造审批；高风险与否的最终判断仍由 `McpClientRuntime` 基于工具目录执行。
    这里的职责是确保 admission 至少来自可信事实集，并具备执行前校验所需的全部字段。
    """

    def build(
        self,
        *,
        tool_name: str,
        control_facts: Mapping[str, Any],
        fallback_context: Mapping[str, Any] | None = None,
    ) -> McpAdmissionBuildResult:
        """构造 MCP admission。

        `control_facts` 推荐来自 Java command proposal/outbox/approval/resume fact bundle。`fallback_context`
        只用于补齐 tenant/project/workspace/run 等已经由 gateway 或当前 Agent request 可信注入的上下文；
        它不能覆盖 control_facts 中已有字段，也不能作为 permission/approval 的唯一来源。
        """

        facts = dict(control_facts)
        context = dict(fallback_context or {})
        internal_tool_name = _required_text(tool_name)
        allowed_tools = _allowed_tools(facts, internal_tool_name)
        admission = McpToolCallAdmission(
            tenant_id=_text_from(facts, context, "tenantId", "tenant_id"),
            project_id=_text_from(facts, context, "projectId", "project_id"),
            workspace_key=_text_from(facts, context, "workspaceKey", "workspace_key"),
            actor_id=_text_from(facts, context, "actorId", "actor_id"),
            run_id=_text_from(facts, context, "runId", "run_id"),
            call_id=_text_from(facts, context, "callId", "call_id", "commandId", "command_id", "auditId", "audit_id"),
            readiness_decision=_readiness_decision(facts),
            permission_granted=_bool_from(facts, "permissionGranted", "permission_granted", default=False),
            approval_verified=_bool_from(facts, "approvalVerified", "approval_verified", default=False),
            allowed_internal_tool_names=allowed_tools,
            source=_text_from(facts, context, "source", "admissionSource", "admission_source") or "MCP_TOOLS_CALL",
        )
        issue_codes = _issue_codes(admission, internal_tool_name)
        return McpAdmissionBuildResult(
            ready=not issue_codes,
            admission=admission if not issue_codes else None,
            issue_codes=tuple(issue_codes),
            source_summary=_source_summary(facts, context, internal_tool_name, allowed_tools),
        )

    def build_or_raise(
        self,
        *,
        tool_name: str,
        control_facts: Mapping[str, Any],
        fallback_context: Mapping[str, Any] | None = None,
    ) -> McpToolCallAdmission:
        """构造 admission，失败时抛出低敏错误。

        这个方法适合 worker 主路径使用：如果 admission 不完整，worker 应该 fail-closed 并写失败 receipt，
        而不是继续调用外部 MCP Server。
        """

        result = self.build(
            tool_name=tool_name,
            control_facts=control_facts,
            fallback_context=fallback_context,
        )
        if result.admission is None:
            raise McpAdmissionBuildError(result.issue_codes, result.to_summary())
        return result.admission


class McpAdmissionBuildError(RuntimeError):
    """Admission 构造失败。

    `issue_codes` 与 `summary` 都是低敏信息，可以安全进入测试断言、事件或 worker receipt。
    """

    def __init__(self, issue_codes: tuple[str, ...], summary: Mapping[str, Any]) -> None:
        super().__init__("MCP admission facts are incomplete or not trusted.")
        self.issue_codes = issue_codes
        self.summary = dict(summary)


def _issue_codes(admission: McpToolCallAdmission, internal_tool_name: str) -> list[str]:
    """汇总 admission 缺口机器码。"""

    issues: list[str] = []
    required_fields = {
        "TENANT_ID_REQUIRED": admission.tenant_id,
        "PROJECT_ID_REQUIRED": admission.project_id,
        "WORKSPACE_KEY_REQUIRED": admission.workspace_key,
        "ACTOR_ID_REQUIRED": admission.actor_id,
        "RUN_ID_REQUIRED": admission.run_id,
        "CALL_ID_REQUIRED": admission.call_id,
    }
    for code, value in required_fields.items():
        if not value:
            issues.append(code)
    if admission.readiness_decision.strip().upper() != "READY":
        issues.append("READINESS_NOT_READY")
    if not admission.permission_granted:
        issues.append("PERMISSION_NOT_GRANTED")
    if internal_tool_name not in admission.allowed_internal_tool_names:
        issues.append("TOOL_NOT_IN_ADMISSION_ALLOWLIST")
    return issues


def _source_summary(
    facts: Mapping[str, Any],
    context: Mapping[str, Any],
    internal_tool_name: str,
    allowed_tools: tuple[str, ...],
) -> dict[str, Any]:
    """生成控制面事实低敏摘要。

    摘要只展示是否存在引用、状态与计数，不展示 payloadReference 正文、参数、工具结果或内部 endpoint。
    """

    return {
        "internalToolName": internal_tool_name,
        "allowedToolCount": len(allowed_tools),
        "proposalId": _optional_text(_first(facts, "proposalId", "commandProposalId", "command_proposal_id")),
        "outboxMessageId": _optional_text(_first(facts, "outboxMessageId", "outboxId", "outbox_id")),
        "approvalFactPresent": bool(
            _optional_text(_first(facts, "approvalConfirmationId", "approvalFactId", "approval_fact_id"))
        ),
        "permissionFactPresent": "permissionGranted" in facts or "permission_granted" in facts,
        "readinessDecision": _readiness_decision(facts),
        "fallbackContextUsed": bool(context),
        "payloadReferencePresent": bool(_optional_text(_first(facts, "payloadReference", "payload_reference"))),
    }


def _allowed_tools(facts: Mapping[str, Any], internal_tool_name: str) -> tuple[str, ...]:
    """读取本轮允许调用的 MCP 工具列表。

    如果 Java 事实只声明单个 `internalToolName/toolName`，则把它作为最小 allowlist。这样可以降低接入门槛，
    但仍然不会在缺少工具名时默认允许所有工具。
    """

    raw = _first(
        facts,
        "allowedInternalToolNames",
        "allowed_internal_tool_names",
        "allowedToolNames",
        "allowed_tool_names",
    )
    if raw is not None:
        return _string_tuple(raw)
    values = _string_tuple(raw)
    if values:
        return values
    fact_tool_name = _optional_text(_first(facts, "internalToolName", "internal_tool_name", "toolName", "tool_name"))
    if fact_tool_name:
        return (fact_tool_name,)
    return (internal_tool_name,) if internal_tool_name else ()


def _readiness_decision(facts: Mapping[str, Any]) -> str:
    """读取 readiness 决策，并兼容历史 `ready_to_execute` 命名。"""

    value = _optional_text(_first(facts, "readinessDecision", "readiness_decision", "decision"))
    normalized = (value or "").strip().upper()
    if normalized in {"READY", "READY_TO_EXECUTE", "APPROVED_FOR_EXECUTION"}:
        return "READY"
    return normalized or "UNKNOWN"


def _text_from(facts: Mapping[str, Any], context: Mapping[str, Any], *keys: str) -> str:
    """优先从 control facts 读取文本字段，其次从 fallback context 读取。"""

    value = _optional_text(_first(facts, *keys))
    if value:
        return value
    return _optional_text(_first(context, *keys)) or ""


def _bool_from(facts: Mapping[str, Any], *keys: str, default: bool) -> bool:
    """按常见 JSON 表达解析布尔值。"""

    value = _first(facts, *keys)
    if value is None:
        return default
    if isinstance(value, bool):
        return value
    text = str(value).strip().lower()
    if text in {"true", "1", "yes", "y", "ready", "granted", "approved"}:
        return True
    if text in {"false", "0", "no", "n", "denied", "blocked"}:
        return False
    return default


def _string_tuple(value: Any) -> tuple[str, ...]:
    """把数组、逗号字符串或单个字符串规范化为去重 tuple。"""

    if value is None:
        return ()
    raw_values: Sequence[Any]
    if isinstance(value, str):
        raw_values = value.split(",")
    elif isinstance(value, Sequence) and not isinstance(value, (bytes, bytearray)):
        raw_values = value
    else:
        raw_values = (value,)
    result: list[str] = []
    for item in raw_values:
        text = _optional_text(item)
        if text and text not in result:
            result.append(text)
    return tuple(result)


def _first(mapping: Mapping[str, Any], *keys: str) -> Any:
    """读取第一个存在的键，保留 False/0 等显式值。"""

    for key in keys:
        if key in mapping:
            return mapping[key]
    return None


def _required_text(value: Any) -> str:
    """读取必填工具名。"""

    text = _optional_text(value)
    if not text:
        raise ValueError("internal MCP tool name is required.")
    return text


def _optional_text(value: Any) -> str | None:
    """读取非空字符串。"""

    if value is None:
        return None
    text = str(value).strip()
    return text or None


__all__ = [
    "MCP_ADMISSION_BUILDER_SCHEMA_VERSION",
    "McpAdmissionBuildError",
    "McpAdmissionBuildResult",
    "McpToolCallAdmissionBuilder",
]
