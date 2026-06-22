"""Agent plan 响应中的工具准备度低敏视图。

`plan_response.py` 负责把一次 Agent 请求串成完整 HTTP 响应，但工具准备度响应和 Java command proposal
上下文本身是独立的“响应视图”逻辑。如果继续堆在主响应组装器里，文件会越来越长，也会让调用方难以分清：
- 哪些代码负责流程编排；
- 哪些代码负责把内部领域对象裁剪成可返回的低敏 JSON。

本模块专门承接后者。它只读取 readiness 报告中的结构化状态、字段名、风险等级、计数和策略版本，不读取
工具参数真实值、prompt、SQL、样本数据、模型输出或 Java 内部异常正文。
"""

from __future__ import annotations

from typing import Any

from datasmart_ai_runtime.domain.contracts import AgentPlan, AgentRequest


def build_command_proposal_context(
    request: AgentRequest,
    plan: AgentPlan,
    readiness_policy_snapshot: Any,
) -> dict[str, Any]:
    """构建 command proposal 模板所需的低敏控制面上下文。

    业务含义：
    - Java `agent-runtime` 后续会把工具意图转成 command proposal/outbox/worker receipt；
    - Python Runtime 当前只负责给出“下一步应如何进入 Java 控制面”的导航；
    - 因此这里必须只放路由、审计和策略版本字段，不能把用户目标、模型输出或工具参数塞进上下文。

    字段说明：
    - `tenantId` / `projectId` / `actorId`：用于 Java 侧做租户、项目、操作者隔离；
    - `requestId` / `clientRequestId`：用于幂等、审计串联和排障关联；
    - `policyVersion`：说明本轮 readiness 使用的策略版本，便于后续复盘为什么某个工具可执行或需审批。

    注意：
    - runId/sessionId 等 Java 控制面事实通常要等 plan ingestion 后生成，这里不伪造；
    - graphId、contractId、payloadReference、approvalConfirmationId 等受控引用也不在这里生成。
    """

    return {
        "tenantId": request.tenant_id,
        "projectId": request.project_id,
        "actorId": request.actor_id,
        "requestId": plan.request_id,
        "policyVersion": getattr(readiness_policy_snapshot, "policy_version", None),
        "clientRequestId": plan.request_id,
    }


def build_tool_execution_readiness_response(tool_execution_readiness: Any) -> dict[str, Any]:
    """构建 HTTP 响应中的工具执行准备度摘要。

    这个响应比 runtime event 略丰富，因为它要服务前端治理卡片、智能网关诊断和控制面预检；但仍然遵循
    严格低敏白名单：
    - 可以展示工具名、字段名、决策、风险等级、执行模式、原因 code 和重试提示；
    - 可以展示数量统计和策略摘要；
    - 不展示参数真实值、完整 payload、prompt、SQL、样本数据、模型输出、凭据或内部 endpoint。

    这样设计的原因是：readiness 是“真实工具执行之前”的门禁事实。它应该足够解释为什么不能执行或
    为什么需要审批，但不能成为绕过 Java 控制面、审批流和 payload 引用治理的捷径。
    """

    return {
        "snapshotType": "TOOL_EXECUTION_READINESS",
        "payloadPolicy": "LOW_SENSITIVE_METADATA_ONLY",
        "policy": tool_execution_readiness.policy_metadata or {},
        "totalCount": tool_execution_readiness.total_count,
        "executableCount": tool_execution_readiness.executable_count,
        "approvalRequiredCount": tool_execution_readiness.approval_required_count,
        "clarificationRequiredCount": tool_execution_readiness.clarification_required_count,
        "draftOnlyCount": tool_execution_readiness.draft_only_count,
        "queuedAsyncCount": tool_execution_readiness.queued_async_count,
        "throttledCount": tool_execution_readiness.throttled_count,
        "blockedCount": tool_execution_readiness.blocked_count,
        "hasBlockingDecision": tool_execution_readiness.has_blocking_decision,
        "nextActions": tool_execution_readiness.next_actions,
        "items": tuple(
            {
                "planIndex": item.plan_index,
                "toolName": item.tool_name,
                "decision": item.decision.value,
                "executable": item.executable,
                "queueRequired": item.queue_required,
                "requiresHumanApproval": item.requires_human_approval,
                "riskLevel": item.risk_level,
                "executionMode": item.execution_mode,
                "targetService": item.target_service,
                "argumentFieldNames": item.argument_field_names,
                "sensitiveArgumentNames": item.sensitive_argument_names,
                "parameterIssueCount": item.parameter_issue_count,
                "issueCodes": item.issue_codes,
                "reasonCodes": item.reason_codes,
                "retryHint": item.retry_hint,
                "payloadPolicy": item.payload_policy,
            }
            for item in tool_execution_readiness.items
        ),
    }
