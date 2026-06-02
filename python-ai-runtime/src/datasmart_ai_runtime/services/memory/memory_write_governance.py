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
from datasmart_ai_runtime.domain.contracts import AgentPlan, AgentRequest
from datasmart_ai_runtime.domain.events import (
    AgentRuntimeEvent,
    AgentRuntimeEventSeverity,
    AgentRuntimeEventType,
)
from datasmart_ai_runtime.domain.memory import (
    AgentMemoryWriteCandidate,
    AgentMemoryWriteCandidateStatus,
    AgentMemoryWriteDecision,
    AgentMemoryWriteDecisionAction,
    AgentMemoryWriteProposalReport,
)
from datasmart_ai_runtime.services.agent_control_plane_feedback import AgentControlPlaneFeedbackSnapshot
from datasmart_ai_runtime.services.memory.memory_write_candidate_store import (
    AgentMemoryWriteCandidateStore,
    InMemoryAgentMemoryWriteCandidateStore,
)
from datasmart_ai_runtime.services.memory.memory_write_candidate_factory import AgentMemoryWriteCandidateFactory
from datasmart_ai_runtime.services.model_tool_result_feedback import ToolExecutionFeedbackStatus


class AgentMemoryWriteGovernanceService:
    """治理 Agent 长期记忆写入候选。

    服务职责边界：
    - 负责判断“哪些工具结果值得被提出为长期记忆候选”；
    - 负责判断候选是否需要人工审批；
    - 负责记录审批/拒绝后的候选状态；
    - 不负责真实写入向量库、图数据库或对象存储；
    - 不负责绕过 Java 控制面的权限、审计和工具执行状态。

    当前默认使用内存 store，目的是让 API、测试和后续 Java 接入先共享稳定领域契约。
    生产化时应注入 MySQL store 或 Java memory-service 客户端，并通过 outbox/Kafka 触发持久化写入。
    """

    def __init__(
        self,
        store: AgentMemoryWriteCandidateStore | None = None,
        candidate_factory: AgentMemoryWriteCandidateFactory | None = None,
    ) -> None:
        self._store = store or InMemoryAgentMemoryWriteCandidateStore()
        self._candidate_factory = candidate_factory or AgentMemoryWriteCandidateFactory()

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
                attributes=self._candidate_factory.base_attributes(request, plan, control_plane_feedback),
            )

        feedback_by_tool = self._candidate_factory.feedback_by_tool(control_plane_feedback)
        candidates: list[AgentMemoryWriteCandidate] = []
        skipped: list[str] = []
        for index, tool_plan in enumerate(plan.tool_plans):
            memory_type = self._candidate_factory.memory_type_from_tool(tool_plan)
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

            candidate = self._candidate_factory.create(
                request=request,
                memory_plan=memory_plan,
                tool_plan=tool_plan,
                tool_index=index,
                feedback_item=feedback_item,
            )
            candidates.append(self._store.save(candidate))

        return AgentMemoryWriteProposalReport(
            candidates=tuple(candidates),
            skipped_reasons=tuple(skipped),
            approval_required_count=sum(1 for item in candidates if item.approval_required),
            writable_type_count=len(writable_types),
            attributes=self._candidate_factory.base_attributes(request, plan, control_plane_feedback),
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

        candidate = self._store.get(decision.candidate_id)
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
        return self._store.update(updated)

    def get(self, candidate_id: str) -> AgentMemoryWriteCandidate | None:
        """按候选 ID 查询当前内存中的候选状态，主要用于测试和未来 API 查询。"""

        return self._store.get(candidate_id)

    def list_candidates(
        self,
        *,
        tenant_id: str | None = None,
        project_id: str | None = None,
        status: AgentMemoryWriteCandidateStatus | None = None,
        limit: int = 100,
    ) -> tuple[AgentMemoryWriteCandidate, ...]:
        """查询候选列表。

        该方法是 API 审批台和未来异步写入 worker 的读取入口。它保留 tenant/project/status
        这些最基础的过滤条件，避免调用方绕过治理服务直接访问 store。
        """

        return self._store.list(tenant_id=tenant_id, project_id=project_id, status=status, limit=limit)

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

    @staticmethod
    def _session_id(request: AgentRequest) -> str | None:
        """从请求变量中读取会话 ID。"""

        value = request.variables.get("sessionId")
        return str(value) if value is not None and str(value).strip() else None

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
