"""Agent 长期记忆候选构造器。

候选生成原本与审批状态机放在同一个治理服务中。随着 workspace、幂等键、敏感级别、工具反馈和未来
脱敏策略逐步增加，继续把字段拼装塞进治理服务会让单文件持续膨胀，也会模糊“生成候选”和“审批候选”
两个不同职责。

本文件专门负责把 AgentRequest、AgentMemoryPlan、ToolPlan 和控制面反馈转换为低敏候选快照。
它不保存候选、不修改状态、不写正式记忆，从而保持可测试、可替换和低耦合。
"""

from __future__ import annotations

from typing import Any
from uuid import NAMESPACE_URL, uuid5

from datasmart_ai_runtime.domain.contracts import AgentPlan, AgentRequest, ToolPlan, ToolRiskLevel
from datasmart_ai_runtime.domain.memory import (
    AgentMemoryPlan,
    AgentMemoryScope,
    AgentMemoryType,
    AgentMemoryWriteCandidate,
    AgentMemoryWriteCandidateStatus,
)
from datasmart_ai_runtime.services.agent_control_plane_feedback import AgentControlPlaneFeedbackSnapshot
from datasmart_ai_runtime.services.memory_write_workspace import AgentMemoryWorkspaceSupport


class AgentMemoryWriteCandidateFactory:
    """构造可审批、可审计、可幂等的长期记忆候选。"""

    def create(
        self,
        *,
        request: AgentRequest,
        memory_plan: AgentMemoryPlan,
        tool_plan: ToolPlan,
        tool_index: int,
        feedback_item: Any | None,
    ) -> AgentMemoryWriteCandidate:
        """把单个工具计划与可选执行反馈转换为候选。

        workspace 绑定在候选形成时写入，而不是等到 materializer 再猜测。这样审批台看到的空间、
        数据库保存的空间和正式记忆最终落成的空间是同一份证据。
        """

        workspace = AgentMemoryWorkspaceSupport.bind(request, tool_plan)
        memory_type = self.memory_type_from_tool(tool_plan) or AgentMemoryType.EPISODIC
        source_status = self._source_status(feedback_item)
        sensitivity_level = self._sensitivity_level(tool_plan, feedback_item)
        approval_required = self._approval_required(memory_plan, tool_plan, sensitivity_level)
        status = (
            AgentMemoryWriteCandidateStatus.PENDING_APPROVAL
            if approval_required
            else AgentMemoryWriteCandidateStatus.DRAFT
        )
        audit_id = getattr(feedback_item, "audit_id", None)
        run_id = getattr(feedback_item, "run_id", None)
        idempotency_key = self._candidate_identity(request, tool_plan, tool_index, audit_id, run_id)
        return AgentMemoryWriteCandidate(
            candidate_id=f"mem-candidate-{uuid5(NAMESPACE_URL, idempotency_key)}",
            memory_type=memory_type,
            scope=memory_plan.default_scope,
            status=status,
            tenant_id=request.tenant_id,
            project_id=request.project_id,
            actor_id=request.actor_id,
            title=f"{tool_plan.tool_name} 工具结果记忆候选（{source_status}）",
            content_summary=self._content_summary(tool_plan, feedback_item),
            source="agent-runtime-tool-feedback" if feedback_item is not None else "agent-plan-tool-plan",
            workspace_key=workspace.workspace_key,
            memory_namespace=workspace.memory_namespace,
            source_tool_name=tool_plan.tool_name,
            source_status=source_status,
            source_audit_id=audit_id,
            source_run_id=run_id,
            output_ref=getattr(feedback_item, "output_ref", None),
            approval_required=approval_required,
            retention_days=memory_plan.retention_days,
            sensitivity_level=sensitivity_level,
            privacy_notes=memory_plan.privacy_notes,
            idempotency_key=idempotency_key,
            attributes={
                "riskLevel": tool_plan.risk_level.value,
                "requiresHumanApproval": tool_plan.requires_human_approval,
                "resultKeys": self._result_keys(feedback_item),
                "sensitiveFields": tuple(getattr(feedback_item, "sensitive_fields", ()) or ()),
                "governanceHintKeys": tuple(sorted(tool_plan.governance_hints.keys())),
                "workspaceBindingSource": workspace.source,
            },
        )

    @staticmethod
    def feedback_by_tool(
        control_plane_feedback: AgentControlPlaneFeedbackSnapshot | None,
    ) -> dict[str, Any]:
        """按工具名索引控制面反馈。

        当前同名工具只取第一条反馈。未来 DAG 允许同一工具多次出现时，应升级为按 modelToolCallId
        或 ToolPlan 节点 ID 关联，避免不同节点的输出摘要被错误复用。
        """

        if control_plane_feedback is None:
            return {}
        indexed: dict[str, Any] = {}
        for item in control_plane_feedback.feedback_items:
            indexed.setdefault(item.tool_name, item)
        return indexed

    @staticmethod
    def memory_type_from_tool(tool_plan: ToolPlan) -> AgentMemoryType | None:
        """从工具治理提示解析目标记忆类型。"""

        mapping = {
            "semantic": AgentMemoryType.SEMANTIC,
            "episodic": AgentMemoryType.EPISODIC,
            "procedural": AgentMemoryType.PROCEDURAL,
            "resource": AgentMemoryType.RESOURCE,
            "short_term": AgentMemoryType.SHORT_TERM,
        }
        return mapping.get(str(tool_plan.governance_hints.get("memoryWritePolicy", "none")).lower())

    @staticmethod
    def base_attributes(
        request: AgentRequest,
        plan: AgentPlan,
        control_plane_feedback: AgentControlPlaneFeedbackSnapshot | None,
    ) -> dict[str, Any]:
        """构造报告级低敏诊断属性。"""

        return {
            "tenantId": request.tenant_id,
            "projectId": request.project_id,
            "requestId": plan.request_id,
            "toolPlanCount": len(plan.tool_plans),
            "hasControlPlaneFeedback": control_plane_feedback is not None,
            "feedbackCount": len(control_plane_feedback.feedback_items) if control_plane_feedback else 0,
        }

    @staticmethod
    def _candidate_identity(
        request: AgentRequest,
        tool_plan: ToolPlan,
        tool_index: int,
        audit_id: str | None,
        run_id: str | None,
    ) -> str:
        """生成候选幂等键。

        幂等键用于识别同一请求、同一工具节点和同一控制面反馈的重复提交。workspaceKey 会在请求变量
        和 ToolPlan hints 发生变化时通过 session/workspace 维度体现；未来如支持人工迁移 workspace，
        应生成新候选而不是修改旧候选的证据链。
        """

        return "|".join(
            (
                request.tenant_id,
                request.project_id,
                request.actor_id,
                str(request.variables.get("workspaceId") or request.variables.get("workspace_id") or ""),
                str(request.variables.get("sessionId", "")),
                tool_plan.tool_name,
                str(tool_index),
                audit_id or "",
                run_id or "",
            )
        )

    @staticmethod
    def _source_status(feedback_item: Any | None) -> str:
        """读取控制面反馈状态，未执行时使用 planned。"""

        status = getattr(feedback_item, "status", "") or "planned"
        return str(getattr(status, "value", status))

    @staticmethod
    def _approval_required(memory_plan: AgentMemoryPlan, tool_plan: ToolPlan, sensitivity_level: str) -> bool:
        """判断候选是否必须人工审批。"""

        return bool(
            memory_plan.approval_required_for_write
            or tool_plan.requires_human_approval
            or tool_plan.risk_level in {ToolRiskLevel.HIGH, ToolRiskLevel.CRITICAL}
            or sensitivity_level not in {"public", "internal"}
            or memory_plan.default_scope in {AgentMemoryScope.TENANT, AgentMemoryScope.GLOBAL}
        )

    @staticmethod
    def _sensitivity_level(tool_plan: ToolPlan, feedback_item: Any | None) -> str:
        """估算候选敏感级别，后续应替换为字段分类与脱敏策略中心。"""

        if tuple(getattr(feedback_item, "sensitive_fields", ()) or ()):
            return "sensitive"
        if tool_plan.risk_level in {ToolRiskLevel.HIGH, ToolRiskLevel.CRITICAL}:
            return "restricted"
        return str(tool_plan.governance_hints.get("sensitivityLevel", "internal"))

    @staticmethod
    def _content_summary(tool_plan: ToolPlan, feedback_item: Any | None) -> str:
        """构造审批人可读的低敏摘要，不复制完整工具结果。"""

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
    def _result_keys(feedback_item: Any | None) -> tuple[str, ...]:
        """只保留结果顶层键名，避免把真实值写入候选摘要。"""

        result = getattr(feedback_item, "result", None)
        return tuple(sorted(str(key) for key in result.keys())) if isinstance(result, dict) else ()
