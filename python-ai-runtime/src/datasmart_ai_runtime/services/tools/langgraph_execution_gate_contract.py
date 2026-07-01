"""LangGraph 执行门禁与 Java 控制面之间的低敏恢复契约。

本模块专门承载 `langgraph_execution_gate.py` 中不适合继续堆叠的“契约说明型”逻辑：
- dominant route 选择规则，用于把 readiness 计数转换为单一主门禁；
- resume gate 的默认摘要、等待事实摘要和 READY/QUEUED 工具的 Java 预检摘要；
- Java checkpoint locator、resume fact bundle、resume gate graph 与 worker receipt 的字段对齐表。

为什么要把这些内容拆出来：
1. `LangGraphExecutionGateWorkflow` 应该专注于 LangGraph 节点和条件边，不应该同时维护大量跨服务字段清单；
2. Java agent-runtime 已经拥有 fact bundle、resume gate graph、worker receipt index 等控制面合同，Python 侧需要显式对齐，而不是在注释中零散描述；
3. 这些字段清单是学习和排障的重要资料，单独成文件后可以更容易阅读，也能避免主 workflow 文件继续超过 500 行。

安全边界：
- 本文件只暴露字段名、路由模板和事实类型，不暴露任何真实 checkpointId、commandId、approvalFactId、payloadReference、token 或 artifact 正文；
- `resumePreflightContract` 是“后续必须由 Java 控制面验证什么”的说明，不是“Python 已经验证通过”的声明；
- Python Runtime 仍然不能执行工具、写 outbox、创建审批、修改 checkpoint 或派发 worker。
"""

from __future__ import annotations

from collections.abc import Mapping
from typing import Any

from datasmart_ai_runtime.services.tools.controlled_command_worker_contract import (
    JAVA_COMMAND_WORKER_RECEIPT_ROUTE_TEMPLATE,
)
from datasmart_ai_runtime.services.tools.tool_action_resume_fact_bundle_client import (
    DEFAULT_AGENT_RUNTIME_RESUME_FACT_BUNDLE_PATH,
)
from datasmart_ai_runtime.services.tools.tool_action_resume_gate_graph_client import (
    DEFAULT_AGENT_RUNTIME_RESUME_GATE_GRAPH_PATH,
)


JAVA_WORKER_RECEIPT_INDEX_QUERY_PATH = "/agent-runtime/tool-action-worker-receipts/receipts"

RESUME_PREFLIGHT_CONTRACT_VERSION = "datasmart.python-ai-runtime.execution-gate-resume-preflight.v1"

# Java fact bundle / gate graph 当前真正理解的服务端事实类型。
# 注意：GRAPH_OR_CONTRACT_EVIDENCE、PAYLOAD_REFERENCE、POLICY_VERSION 在 Python 的老摘要里曾被放进
# requiredFactTypes；但 Java fact bundle 的核心“可采信事实”主要是审批、澄清、outbox 和 worker receipt。
# 因此本轮把 graph/payload/policy 移到 controlPlaneLocatorFields 与 payloadReferenceContract 中说明，
# 避免把“定位字段/合同字段”和“服务端事实类型”混在同一个列表里。
RESUME_PREFLIGHT_REQUIRED_FACT_TYPES = (
    "OUTBOX_WRITE_CONFIRMATION",
    "WORKER_RECEIPT_PROJECTION",
)

# 这些字段名直接对齐 Java `AgentToolActionResumeFactBundleQueryRequest` 与 locator index record。
# 它们只用于回查和收口，不代表 Python 已经信任调用方传入的值。
CONTROL_PLANE_LOCATOR_FIELDS = (
    "checkpointId",
    "threadId",
    "sessionId",
    "runId",
    "commandId",
    "outboxId",
    "approvalFactId",
    "clarificationFactId",
    "toolCode",
    "requestedPolicyVersion",
    "tenantId",
    "projectId",
    "actorId",
)

# worker receipt 的“公开低敏字段”对齐 Java `AgentToolActionCommandWorkerReceiptRequest` 与
# `AgentToolActionWorkerReceiptIndexView`。这里刻意不列出 fencingToken、artifactReference、message 这类
# 只能由 Java 内部写入路由接收并继续裁剪的字段，避免 Python 计划响应暗示这些值可以在外部回显。
WORKER_RECEIPT_PUBLIC_FIELDS = (
    "commandId",
    "taskId",
    "taskRunId",
    "executorId",
    "tenantId",
    "projectId",
    "actorId",
    "runId",
    "sessionId",
    "toolCode",
    "taskStatus",
    "outcome",
    "preCheckPassed",
    "sideEffectStarted",
    "sideEffectExecuted",
    "workerLeaseRequired",
    "workerLeaseVersion",
    "workerLeaseExpiresAtMs",
    "commandSafetyDecision",
    "commandSafetyPolicyVersion",
    "commandSafetyIssueCodes",
    "normalizedTimeoutSeconds",
    "normalizedOutputByteLimitBytes",
    "artifactReferenceType",
    "artifactAvailable",
    "errorCode",
    "auditId",
    "targetService",
    "workerReceiptMode",
    "recommendedActions",
    "idempotencyKey",
)


def dominant_route(counts: Mapping[str, int]) -> tuple[str, tuple[str, ...]]:
    """根据 readiness 计数选择最保守的 dominant gate。

    dominant gate 的核心原则是“先处理最不能忽略的阻断条件”：
    1. 没有工具计划时，返回 NO_TOOL_PLAN；
    2. 存在明确 blocked 工具时，直接阻断；
    3. 缺上下文优先于审批，因为审批前必须先知道审批对象；
    4. 审批优先于预算等待，因为高风险动作不能只等容量恢复；
    5. READY/QUEUED 最后进入 RESUME_PREFLIGHT，交给 Java 控制面验证 checkpoint、outbox 和 worker receipt。
    """

    if int(counts.get("totalCount") or 0) == 0:
        return "NO_TOOL_PLAN", ("NO_TOOL_PLAN",)
    if int(counts.get("blockedCount") or 0) > 0:
        return "BLOCKED", ("BLOCKED_TOOL_PRESENT",)
    if int(counts.get("clarificationRequiredCount") or 0) > 0:
        return "HUMAN_INPUT", ("PARAMETER_OR_CONTEXT_CLARIFICATION_REQUIRED",)
    if int(counts.get("approvalRequiredCount") or 0) > 0:
        return "HUMAN_APPROVAL", ("APPROVAL_REQUIRED_BEFORE_EXECUTION",)
    if int(counts.get("throttledCount") or 0) > 0:
        return "CAPACITY_WAIT", ("TOOL_BUDGET_OR_WORKER_CAPACITY_LIMITED",)
    if int(counts.get("draftOnlyCount") or 0) > 0:
        return "DRAFT_REVIEW", ("DRAFT_REVIEW_REQUIRED_BEFORE_SIDE_EFFECT",)
    return "RESUME_PREFLIGHT", ("READY_OR_QUEUED_TOOL_REQUIRES_JAVA_CONTROL_PLANE",)


def default_resume_gate() -> dict[str, Any]:
    """生成默认 resume gate 摘要。

    默认摘要用于非 READY/QUEUED 分支和 fallback 场景，表示“当前还没有进入可恢复执行预检”。
    这能避免调用方把缺字段误解成可执行，也能让前端或 Java projection 始终获得稳定字段结构。
    """

    return {
        "status": "NOT_APPLICABLE_BEFORE_EXECUTION_CHECKPOINT",
        "resumePreviewReady": False,
        "requiredFactTypes": (),
        "checkpointMutationAllowed": False,
        "meaning": "当前阶段尚未进入可恢复 checkpoint；如后续产生副作用，必须由 Java 控制面提供恢复事实。",
    }


def resume_gate_waiting_for(fact_type: str) -> dict[str, Any]:
    """生成等待某类 Java host fact 的 resume gate 摘要。

    例如审批分支等待 `APPROVAL_CONFIRMATION_FACT`，预算分支等待
    `TOOL_BUDGET_OR_WORKER_CAPACITY_RECOVERY`。这些事实必须由 Java 控制面或企业系统产生，
    不能由客户端自报字段直接绕过。
    """

    return {
        "status": "WAITING_FOR_HOST_FACT",
        "resumePreviewReady": False,
        "requiredFactTypes": (fact_type,),
        "checkpointMutationAllowed": False,
        "meaning": "该分支需要受控服务端事实，不能由客户端自报字段直接越过。",
    }


def resume_gate_preflight() -> dict[str, Any]:
    """生成 READY/QUEUED 工具进入 Java 控制面之前的标准化预检摘要。

    这个对象会出现在 `/agent/plans.agentExecutionGateWorkflow.resumeGate` 中。它的产品语义是：
    - Python 已经通过 LangGraph 条件图判断“本轮可以进入恢复预检”；
    - 但真实副作用仍然必须等待 Java agent-runtime 创建/查询 checkpoint locator、fact bundle、resume gate graph；
    - 只有 worker receipt 明确证明受控执行结果后，才能把 side effect 视为发生。

    这里返回的字段都是“字段名/路由名/事实类型”，不包含任何真实 ID 或正文值。
    """

    return {
        "status": "PENDING_JAVA_CHECKPOINT_AND_HOST_FACTS",
        "resumePreviewReady": False,
        "requiredFactTypes": RESUME_PREFLIGHT_REQUIRED_FACT_TYPES,
        "checkpointMutationAllowed": False,
        "resumePreflightContract": resume_preflight_contract(),
        "meaning": "本轮只生成执行前门禁路线；真实 resume 必须等待 Java checkpoint、outbox 与 worker receipt 事实。",
    }


def resume_preflight_contract() -> dict[str, Any]:
    """生成 Python execution gate 与 Java agent-runtime 的字段级对齐合同。

    合同分四层：
    - `checkpointLocator`：恢复入口需要哪些低敏 locator 字段；
    - `resumeFactBundle`：Java fact bundle / gate graph 使用哪些事实类型和只读路由；
    - `workerReceipt`：worker 回写和索引查询使用哪些低敏字段；
    - `sideEffectPolicy`：再次声明 Python 不能产生副作用，必须等待 Java receipt。
    """

    return {
        "schemaVersion": RESUME_PREFLIGHT_CONTRACT_VERSION,
        "checkpointLocator": {
            "javaRequestDto": "AgentToolActionResumeFactBundleQueryRequest",
            "javaIndexRecord": "AgentToolActionResumeLocatorIndexRecord",
            "fieldNames": CONTROL_PLANE_LOCATOR_FIELDS,
            "payloadPolicy": "FIELD_NAMES_ONLY_VALUES_VERIFIED_BY_JAVA_CONTROL_PLANE",
        },
        "resumeFactBundle": {
            "javaRoutes": {
                "factBundleQuery": DEFAULT_AGENT_RUNTIME_RESUME_FACT_BUNDLE_PATH,
                "resumeGateGraphPreview": DEFAULT_AGENT_RUNTIME_RESUME_GATE_GRAPH_PATH,
            },
            "requiredFactTypes": RESUME_PREFLIGHT_REQUIRED_FACT_TYPES,
            "responseFields": (
                "requiredFactTypes",
                "availableFactTypes",
                "missingFactTypes",
                "rejectedFactTypes",
                "resumePreviewReady",
                "graphState",
                "terminalState",
                "recommendedActions",
            ),
        },
        "workerReceipt": {
            "javaWriteRouteTemplate": JAVA_COMMAND_WORKER_RECEIPT_ROUTE_TEMPLATE,
            "javaReadRoute": JAVA_WORKER_RECEIPT_INDEX_QUERY_PATH,
            "javaWriteDto": "AgentToolActionCommandWorkerReceiptRequest",
            "javaIndexView": "AgentToolActionWorkerReceiptIndexView",
            "publicFieldNames": WORKER_RECEIPT_PUBLIC_FIELDS,
            "javaOnlySensitiveFieldNames": ("fencingToken", "artifactReference", "message"),
            "payloadPolicy": "LOW_SENSITIVE_RECEIPT_INDEX_NO_MESSAGE_NO_ARGUMENTS_NO_PROMPT_NO_SQL_NO_PAYLOAD_BODY",
        },
        "sideEffectPolicy": {
            "pythonToolExecutionAllowed": False,
            "pythonOutboxWriteAllowed": False,
            "javaControlPlaneRequiredForSideEffects": True,
            "workerReceiptRequiredForSideEffects": True,
        },
    }


def side_effect_boundary() -> dict[str, Any]:
    """生成执行门禁图的固定副作用边界。

    该边界同时服务 HTTP 响应、runtime event、Java projection 和学习说明。它明确告诉调用方：
    execution gate 只是条件图和预检合同，不是执行器。
    """

    return {
        "toolExecuted": False,
        "outboxWritten": False,
        "approvalCreated": False,
        "checkpointMutated": False,
        "workerDispatched": False,
        "javaControlPlaneRequiredForSideEffects": True,
        "workerReceiptRequiredForSideEffects": True,
    }
