"""Agent 长期记忆写入候选与审批治理服务。

真实商业化 Agent 的长期记忆能力不能简单理解为“把聊天记录或工具结果写进向量库”。
在 DataSmart Govern 的数据治理场景里，工具结果可能包含数据源结构、质量异常、审批状态、
SQL 草案、任务执行失败原因、导出路径、审计 ID，甚至间接暴露敏感业务数据。

因此本服务采用“先生成候选，再审批，再由后续持久化层写入”的三段式设计：
1. Python Runtime 根据 `AgentMemoryPlan`、`ToolPlan` 和 Java 控制面反馈生成候选；
2. 候选根据租户/项目范围、敏感级别、工具风险、写入类型策略进入草稿或待审批；
3. 审批通过后，未来再交给 Chroma、Neo4j、MySQL、MinIO 或事件 outbox 做真实持久化。

这个阶段只实现候选和决策闭环，刻意不写 Chroma/Neo4j。这样做能避免在权限、脱敏、
保留期、遗忘策略还没稳定前，把高风险工具结果永久沉淀成 Agent 可反复引用的记忆。
"""

from __future__ import annotations

from dataclasses import replace
from datetime import datetime, timezone
from typing import Any
from uuid import uuid5, NAMESPACE_URL

from datasmart_ai_runtime.domain.contracts import AgentPlan, AgentRequest, ToolPlan, ToolRiskLevel
from datasmart_ai_runtime.domain.events import (
    AgentRuntimeEvent,
    AgentRuntimeEventSeverity,
    AgentRuntimeEventType,
)
from datasmart_ai_runtime.domain.memory import (
    AgentMemoryPlan,
    AgentMemoryScope,
    AgentMemoryType,
    AgentMemoryWriteCandidate,
    AgentMemoryWriteCandidateStatus,
    AgentMemoryWriteDecision,
    AgentMemoryWriteDecisionAction,
    AgentMemoryWriteProposalReport,
)
from datasmart_ai_runtime.services.agent_control_plane_feedback import AgentControlPlaneFeedbackSnapshot
from datasmart_ai_runtime.services.model_tool_result_feedback import ToolExecutionFeedbackStatus


class AgentMemoryWriteGovernanceService:
    """治理 Agent 长期记忆写入候选。

    服务职责边界：
    - 负责判断“哪些工具结果值得被提出为长期记忆候选”；
    - 负责判断候选是否需要人工审批；
    - 负责记录审批/拒绝后的候选状态；
    - 不负责真实写入向量库、图数据库或对象存储；
    - 不负责绕过 Java 控制面的权限、审计和工具执行状态。

    当前实现使用内存字典保存候选，目的是让 API、测试和后续 Java 接入先共享稳定领域契约。
    生产化时应替换为 MySQL 表或 Java memory-service 审批单，并通过 outbox/Kafka 触发持久化写入。
    """

    def __init__(self) -> None:
        self._candidates: dict[str, AgentMemoryWriteCandidate] = {}

    def propose(
        self,
        *,
        request: AgentRequest,
        plan: AgentPlan,
        control_plane_feedback: AgentControlPlaneFeedbackSnapshot | None = None,
    ) -> AgentMemoryWriteProposalReport:
        """根据 AgentPlan 和控制面反馈生成记忆写入候选。

        输入说明：
        - `request` 提供租户、项目、操作者和 sessionId，是记忆隔离边界的来源；
        - `plan.memory_plan` 提供允许写入的记忆类型、默认 scope、保留期和审批策略；
        - `plan.tool_plans` 提供工具级 memoryWritePolicy、riskLevel、approval 标记和治理 hints；
        - `control_plane_feedback` 提供真实工具执行状态、摘要、auditId、runId、outputRef 和敏感字段。

        重要业务规则：
        - 如果本次计划没有任何允许写入的记忆类型，则只返回跳过原因，不生成候选；
        - 只有工具 descriptor 或 ToolPlan 明确声明了 memoryWritePolicy 的结果才会成为候选；
        - 等待审批中的工具结果不会生成记忆候选，因为真实结果还没有完成；
        - 敏感、跨范围、高风险或计划要求审批的候选会进入 `PENDING_APPROVAL`；
        - 低风险候选先进入 `DRAFT`，仍不直接写入长期存储。
        """

        memory_plan = plan.memory_plan
        writable_types = set(memory_plan.writable_memory_types)
        if not writable_types:
            return AgentMemoryWriteProposalReport(
                skipped_reasons=("当前 AgentMemoryPlan 没有声明可写入记忆类型，平台不会猜测性沉淀长期记忆。",),
                writable_type_count=0,
                attributes=self._base_attributes(request, plan, control_plane_feedback),
            )

        feedback_by_tool = self._feedback_by_tool(control_plane_feedback)
        candidates: list[AgentMemoryWriteCandidate] = []
        skipped: list[str] = []
        for index, tool_plan in enumerate(plan.tool_plans):
            memory_type = self._memory_type_from_tool(tool_plan)
            if memory_type is None:
                skipped.append(f"{tool_plan.tool_name} 未声明 memoryWritePolicy，跳过长期记忆候选。")
                continue
            if memory_type not in writable_types:
                skipped.append(
                    f"{tool_plan.tool_name} 声明写入 {memory_type.value}，但本次计划未授权该类型。"
                )
                continue

            feedback_item = feedback_by_tool.get(tool_plan.tool_name)
            if feedback_item is not None and feedback_item.status == ToolExecutionFeedbackStatus.WAITING_APPROVAL:
                skipped.append(f"{tool_plan.tool_name} 仍等待工具执行审批，暂不生成记忆写入候选。")
                continue

            candidate = self._candidate_from_tool(
                request=request,
                memory_plan=memory_plan,
                tool_plan=tool_plan,
                tool_index=index,
                feedback_item=feedback_item,
            )
            self._candidates[candidate.candidate_id] = candidate
            candidates.append(candidate)

        return AgentMemoryWriteProposalReport(
            candidates=tuple(candidates),
            skipped_reasons=tuple(skipped),
            approval_required_count=sum(1 for item in candidates if item.approval_required),
            writable_type_count=len(writable_types),
            attributes=self._base_attributes(request, plan, control_plane_feedback),
        )

    def decide(self, decision: AgentMemoryWriteDecision) -> AgentMemoryWriteCandidate:
        """记录某个记忆写入候选的审批决策。

        决策规则：
        - `reason` 必填，避免审计链路里出现无法解释的批准或拒绝；
        - 只能处理存在的候选，避免审批系统对不存在 ID 产生“悬空决策”；
        - 已批准、已拒绝或已忽略的候选不允许重复决策；
        - `APPROVE` 会把候选推进到 `APPROVED`，但仍不代表已经写入 Chroma/Neo4j；
        - `REJECT` 会把候选推进到 `REJECTED`，后续持久化 worker 必须跳过。
        """

        reason = decision.reason.strip()
        if not reason:
            raise ValueError("记忆写入审批必须填写原因，便于后续审计和复盘。")

        candidate = self._candidates.get(decision.candidate_id)
        if candidate is None:
            raise KeyError(f"记忆写入候选不存在: {decision.candidate_id}")
        if candidate.status in {
            AgentMemoryWriteCandidateStatus.APPROVED,
            AgentMemoryWriteCandidateStatus.REJECTED,
            AgentMemoryWriteCandidateStatus.IGNORED,
        }:
            raise ValueError(f"候选 {decision.candidate_id} 已处于终态，不能重复审批。")

        next_status = (
            AgentMemoryWriteCandidateStatus.APPROVED
            if decision.action == AgentMemoryWriteDecisionAction.APPROVE
            else AgentMemoryWriteCandidateStatus.REJECTED
        )
        updated = replace(
            candidate,
            status=next_status,
            decided_at=decision.decided_at,
            decided_by=decision.operator_id,
            decision_reason=reason,
        )
        self._candidates[updated.candidate_id] = updated
        return updated

    def get(self, candidate_id: str) -> AgentMemoryWriteCandidate | None:
        """按候选 ID 查询当前内存中的候选状态，主要用于测试和未来 API 查询。"""

        return self._candidates.get(candidate_id)

    def proposal_events(
        self,
        *,
        request: AgentRequest,
        plan: AgentPlan,
        report: AgentMemoryWriteProposalReport,
    ) -> tuple[AgentRuntimeEvent, ...]:
        """把候选报告转换为 runtime events。

        事件只记录候选数量、类型、scope、状态和审批要求，不记录完整工具结果。
        这样前端和审计可以看到“平台提出了哪些记忆写入候选”，但不会因为事件流泄露敏感内容。
        """

        events: list[AgentRuntimeEvent] = []
        start_sequence = len(plan.runtime_events) + 1
        for offset, candidate in enumerate(report.candidates):
            events.append(
                AgentRuntimeEvent(
                    event_type=AgentRuntimeEventType.MEMORY_WRITE_CANDIDATE_PROPOSED,
                    stage="govern_memory_write",
                    message=f"已生成记忆写入候选：{candidate.title}",
                    severity=(
                        AgentRuntimeEventSeverity.AUDIT
                        if candidate.approval_required
                        else AgentRuntimeEventSeverity.INFO
                    ),
                    tenant_id=request.tenant_id,
                    project_id=request.project_id,
                    actor_id=request.actor_id,
                    request_id=plan.request_id,
                    session_id=self._session_id(request),
                    sequence=start_sequence + offset,
                    attributes={
                        "candidateId": candidate.candidate_id,
                        "memoryType": candidate.memory_type.value,
                        "scope": candidate.scope.value,
                        "status": candidate.status.value,
                        "approvalRequired": candidate.approval_required,
                        "sourceToolName": candidate.source_tool_name,
                        "sourceStatus": candidate.source_status,
                        "retentionDays": candidate.retention_days,
                    },
                )
            )
        return tuple(events)

    @staticmethod
    def decision_event(
        *,
        request: AgentRequest,
        plan: AgentPlan,
        candidate: AgentMemoryWriteCandidate,
    ) -> AgentRuntimeEvent:
        """构造审批决策事件，供未来审批 API 或 Java 控制面复用。"""

        return AgentRuntimeEvent(
            event_type=AgentRuntimeEventType.MEMORY_WRITE_DECISION_RECORDED,
            stage="govern_memory_write",
            message=f"记忆写入候选已记录决策：{candidate.status.value}",
            severity=AgentRuntimeEventSeverity.AUDIT,
            tenant_id=request.tenant_id,
            project_id=request.project_id,
            actor_id=request.actor_id,
            request_id=plan.request_id,
            session_id=AgentMemoryWriteGovernanceService._session_id(request),
            sequence=len(plan.runtime_events) + 1,
            attributes={
                "candidateId": candidate.candidate_id,
                "memoryType": candidate.memory_type.value,
                "scope": candidate.scope.value,
                "status": candidate.status.value,
                "decidedBy": candidate.decided_by,
            },
        )

    def _candidate_from_tool(
        self,
        *,
        request: AgentRequest,
        memory_plan: AgentMemoryPlan,
        tool_plan: ToolPlan,
        tool_index: int,
        feedback_item: Any | None,
    ) -> AgentMemoryWriteCandidate:
        """把单个 ToolPlan/Feedback 转成记忆写入候选。

        候选 ID 使用稳定 UUID5，而不是随机 UUID4。这样同一次 request、同一工具、同一 auditId
        重复生成候选时可以得到相同 ID，未来接入幂等写入或审批重试时更容易去重。
        """

        memory_type = self._memory_type_from_tool(tool_plan) or AgentMemoryType.EPISODIC
        source_status = str(getattr(feedback_item, "status", "") or "planned")
        if hasattr(getattr(feedback_item, "status", None), "value"):
            source_status = getattr(feedback_item.status, "value")
        sensitivity_level = self._sensitivity_level(tool_plan, feedback_item)
        approval_required = self._approval_required(memory_plan, tool_plan, sensitivity_level)
        status = (
            AgentMemoryWriteCandidateStatus.PENDING_APPROVAL
            if approval_required
            else AgentMemoryWriteCandidateStatus.DRAFT
        )
        audit_id = getattr(feedback_item, "audit_id", None)
        run_id = getattr(feedback_item, "run_id", None)
        output_ref = getattr(feedback_item, "output_ref", None)
        summary = self._content_summary(tool_plan, feedback_item)
        candidate_id = self._candidate_id(request, tool_plan, tool_index, audit_id, run_id)

        return AgentMemoryWriteCandidate(
            candidate_id=candidate_id,
            memory_type=memory_type,
            scope=memory_plan.default_scope,
            status=status,
            tenant_id=request.tenant_id,
            project_id=request.project_id,
            actor_id=request.actor_id,
            title=self._title(tool_plan, source_status),
            content_summary=summary,
            source="agent-runtime-tool-feedback" if feedback_item is not None else "agent-plan-tool-plan",
            source_tool_name=tool_plan.tool_name,
            source_status=source_status,
            source_audit_id=audit_id,
            source_run_id=run_id,
            output_ref=output_ref,
            approval_required=approval_required,
            retention_days=memory_plan.retention_days,
            sensitivity_level=sensitivity_level,
            privacy_notes=memory_plan.privacy_notes,
            attributes={
                "riskLevel": tool_plan.risk_level.value,
                "requiresHumanApproval": tool_plan.requires_human_approval,
                "resultKeys": self._result_keys(feedback_item),
                "sensitiveFields": tuple(getattr(feedback_item, "sensitive_fields", ()) or ()),
                "governanceHintKeys": tuple(sorted(tool_plan.governance_hints.keys())),
            },
        )

    @staticmethod
    def _feedback_by_tool(control_plane_feedback: AgentControlPlaneFeedbackSnapshot | None) -> dict[str, Any]:
        """按工具名索引控制面反馈。

        当前一个工具名只取第一条反馈。未来如果同一工具在 DAG 中出现多次，应改为按
        `modelToolCallId` 或 ToolPlan 节点 ID 精确关联。
        """

        if control_plane_feedback is None:
            return {}
        indexed: dict[str, Any] = {}
        for item in control_plane_feedback.feedback_items:
            indexed.setdefault(item.tool_name, item)
        return indexed

    @staticmethod
    def _memory_type_from_tool(tool_plan: ToolPlan) -> AgentMemoryType | None:
        """从 ToolPlan 治理 hint 中解析写入类型。"""

        mapping = {
            "semantic": AgentMemoryType.SEMANTIC,
            "episodic": AgentMemoryType.EPISODIC,
            "procedural": AgentMemoryType.PROCEDURAL,
            "resource": AgentMemoryType.RESOURCE,
            "short_term": AgentMemoryType.SHORT_TERM,
        }
        value = str(tool_plan.governance_hints.get("memoryWritePolicy", "none")).lower()
        return mapping.get(value)

    @staticmethod
    def _approval_required(memory_plan: AgentMemoryPlan, tool_plan: ToolPlan, sensitivity_level: str) -> bool:
        """判断候选是否必须人工审批。"""

        high_risk = tool_plan.risk_level in {ToolRiskLevel.HIGH, ToolRiskLevel.CRITICAL}
        sensitive = sensitivity_level not in {"public", "internal"}
        wide_scope = memory_plan.default_scope in {AgentMemoryScope.TENANT, AgentMemoryScope.GLOBAL}
        return bool(
            memory_plan.approval_required_for_write
            or tool_plan.requires_human_approval
            or high_risk
            or sensitive
            or wide_scope
        )

    @staticmethod
    def _sensitivity_level(tool_plan: ToolPlan, feedback_item: Any | None) -> str:
        """估算候选敏感级别。

        当前优先使用反馈中的 sensitive_fields 和工具治理 hint。后续应接入 permission-admin
        字段级策略、脱敏规则、数据分级分类结果和租户级记忆策略。
        """

        sensitive_fields = tuple(getattr(feedback_item, "sensitive_fields", ()) or ())
        if sensitive_fields:
            return "sensitive"
        if tool_plan.risk_level in {ToolRiskLevel.HIGH, ToolRiskLevel.CRITICAL}:
            return "restricted"
        return str(tool_plan.governance_hints.get("sensitivityLevel", "internal"))

    @staticmethod
    def _content_summary(tool_plan: ToolPlan, feedback_item: Any | None) -> str:
        """构造审批人可读的候选内容摘要，避免直接暴露完整工具结果。"""

        if feedback_item is None:
            return f"工具 {tool_plan.tool_name} 已被规划，原因：{tool_plan.reason}"
        summary = str(getattr(feedback_item, "summary", "") or "").strip()
        if summary:
            return summary
        error_message = str(getattr(feedback_item, "error_message", "") or "").strip()
        if error_message:
            return f"工具 {tool_plan.tool_name} 返回错误摘要：{error_message}"
        return f"工具 {tool_plan.tool_name} 已返回控制面反馈，但没有可展示摘要。"

    @staticmethod
    def _title(tool_plan: ToolPlan, source_status: str) -> str:
        """生成候选标题，便于审批列表和调试面板展示。"""

        return f"{tool_plan.tool_name} 工具结果记忆候选（{source_status}）"

    @staticmethod
    def _result_keys(feedback_item: Any | None) -> tuple[str, ...]:
        """只暴露 result 顶层键名，不把真实值写入候选摘要。"""

        result = getattr(feedback_item, "result", None)
        if isinstance(result, dict):
            return tuple(sorted(str(key) for key in result.keys()))
        return ()

    @staticmethod
    def _candidate_id(
        request: AgentRequest,
        tool_plan: ToolPlan,
        tool_index: int,
        audit_id: str | None,
        run_id: str | None,
    ) -> str:
        """生成稳定候选 ID。"""

        raw = "|".join(
            (
                request.tenant_id,
                request.project_id,
                request.actor_id,
                str(request.variables.get("sessionId", "")),
                tool_plan.tool_name,
                str(tool_index),
                audit_id or "",
                run_id or "",
            )
        )
        return f"mem-candidate-{uuid5(NAMESPACE_URL, raw)}"

    @staticmethod
    def _session_id(request: AgentRequest) -> str | None:
        """从请求变量中读取会话 ID。"""

        value = request.variables.get("sessionId")
        return str(value) if value is not None and str(value).strip() else None

    @staticmethod
    def _base_attributes(
        request: AgentRequest,
        plan: AgentPlan,
        control_plane_feedback: AgentControlPlaneFeedbackSnapshot | None,
    ) -> dict[str, Any]:
        """构造报告级诊断属性。"""

        return {
            "tenantId": request.tenant_id,
            "projectId": request.project_id,
            "requestId": plan.request_id,
            "toolPlanCount": len(plan.tool_plans),
            "hasControlPlaneFeedback": control_plane_feedback is not None,
            "feedbackCount": len(control_plane_feedback.feedback_items) if control_plane_feedback else 0,
        }


def approve_memory_write_candidate(
    service: AgentMemoryWriteGovernanceService,
    *,
    candidate_id: str,
    operator_id: str,
    reason: str,
    decided_at: datetime | None = None,
) -> AgentMemoryWriteCandidate:
    """审批通过记忆写入候选的便捷函数。

    未来如果审批动作来自 Java permission-admin 或审批中心，可以复用同一决策对象构造逻辑。
    """

    return service.decide(
        AgentMemoryWriteDecision(
            candidate_id=candidate_id,
            action=AgentMemoryWriteDecisionAction.APPROVE,
            operator_id=operator_id,
            reason=reason,
            decided_at=decided_at or datetime.now(timezone.utc),
        )
    )


def reject_memory_write_candidate(
    service: AgentMemoryWriteGovernanceService,
    *,
    candidate_id: str,
    operator_id: str,
    reason: str,
    decided_at: datetime | None = None,
) -> AgentMemoryWriteCandidate:
    """拒绝记忆写入候选的便捷函数。"""

    return service.decide(
        AgentMemoryWriteDecision(
            candidate_id=candidate_id,
            action=AgentMemoryWriteDecisionAction.REJECT,
            operator_id=operator_id,
            reason=reason,
            decided_at=decided_at or datetime.now(timezone.utc),
        )
    )
