"""Build a confirmation-gated repair for a duplicate sync task name.

A draft-save conflict is different from an execution failure: the complete task
configuration is still valid, but the project uniqueness constraint rejected the
requested name.  This module recognizes only that narrow, deterministic case,
preserves every reviewed task field, proposes a traceable unique suffix, and
rebuilds the normal save/precheck/publish/run lifecycle.  It never executes the
repair; Java still creates a new approval-gated Run that requires the user to
accept the exact old-to-new name change.
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass, replace
from typing import Any

from datasmart_ai_runtime.domain.contracts import (
    ToolExecutionMode,
    ToolPlan,
    ToolRiskLevel,
)
from datasmart_ai_runtime.services.agent_control_plane_feedback import (
    AgentControlPlaneFeedbackItem,
)
from datasmart_ai_runtime.services.model_gateway.model_tool_result_feedback import (
    ToolExecutionFeedbackStatus,
)
from datasmart_ai_runtime.services.tool_planner import ToolPlanner


@dataclass(frozen=True)
class DuplicateTaskNameRepairProposal:
    """Public, low-sensitive description of the proposed configuration change."""

    failure_code: str
    original_task_name: str
    proposed_task_name: str

    def to_summary(self) -> dict[str, Any]:
        return {
            "kind": "DUPLICATE_TASK_NAME",
            "failureCode": self.failure_code,
            "failedToolName": "sync.task.draft.save",
            "originalTaskName": self.original_task_name,
            "proposedTaskName": self.proposed_task_name,
            "requiresConfirmation": True,
            "summary": (
                f"当前项目已存在名为“{self.original_task_name}”的任务。"
                f"Agent 建议改为“{self.proposed_task_name}”；该修改尚未保存或执行。"
            ),
            "changes": (
                f"任务名称：{self.original_task_name} -> {self.proposed_task_name}",
                "源端、目标端、对象映射、字段映射、WHERE、同步模式和写入策略保持不变",
                "确认后重新执行草稿保存、预检查、发布，并按同步模式提交运行或调度",
            ),
            "payloadPolicy": "LOW_SENSITIVE_EXACT_REPAIR_DIFF",
        }


@dataclass(frozen=True)
class DuplicateTaskNameRecoveryPlan:
    """A public proposal plus the governed lifecycle awaiting confirmation."""

    proposal: DuplicateTaskNameRepairProposal
    tool_plans: tuple[ToolPlan, ...]


class DuplicateTaskNameRecoveryPlanner:
    """Classify and prepare only recoverable task-name uniqueness conflicts."""

    _METADATA_REFERENCE_ARGUMENTS = {
        "sourceMetadataRef": "datasource.source.metadata.read",
        "targetMetadataRef": "datasource.target.metadata.read",
    }
    _METADATA_REFERENCE_GROUP_ARGUMENTS = {
        "sourceMetadataRefs": "datasource.source.metadata.read",
        "targetMetadataRefs": "datasource.target.metadata.read",
    }

    def __init__(self, tool_planner: ToolPlanner) -> None:
        self._tool_planner = tool_planner

    def build(
        self,
        *,
        source_run_id: str,
        tool_plans: tuple[ToolPlan, ...],
        feedback_items: tuple[AgentControlPlaneFeedbackItem, ...],
    ) -> DuplicateTaskNameRecoveryPlan | None:
        failed_feedback = next(
            (
                item
                for item in feedback_items
                if item.tool_name == "sync.task.draft.save"
                and item.status is ToolExecutionFeedbackStatus.FAILED
                and self._is_duplicate_name_failure(item)
            ),
            None,
        )
        if failed_feedback is None:
            return None

        failed_plan = next(
            (item for item in tool_plans if item.tool_name == "sync.task.draft.save"),
            None,
        )
        if failed_plan is None:
            return None
        original_name = str(failed_plan.arguments.get("taskName") or "").strip()
        if not original_name:
            return None

        proposed_name = self._proposed_name(original_name, source_run_id)
        arguments = dict(failed_plan.arguments)
        arguments["taskName"] = proposed_name
        self._bind_trusted_metadata_references(arguments, feedback_items)
        call_digest = hashlib.sha256(
            f"{source_run_id}|{original_name}|{proposed_name}".encode("utf-8")
        ).hexdigest()[:20]
        stale_hint_names = {
            "agentRuntimeRunId",
            "agentRuntimeAuditId",
            "toolCallFingerprint",
            "agentLoopToolFingerprints",
        }
        inherited_hints = {
            key: value
            for key, value in failed_plan.governance_hints.items()
            if key not in stale_hint_names
        }
        repair_draft = self._tool_planner.revalidate_plan(failed_plan, arguments)
        repair_draft = replace(
            repair_draft,
            reason=(
                "当前项目存在同名任务；仅按已展示并等待用户确认的方案修改任务名称，"
                "其余已审核同步配置保持不变。"
            ),
            risk_level=ToolRiskLevel.HIGH,
            execution_mode=ToolExecutionMode.APPROVAL_REQUIRED,
            requires_human_approval=True,
            governance_hints={
                **inherited_hints,
                "modelToolCallId": f"duplicate-name-repair-{call_digest}",
                "source": "platform_duplicate_task_name_recovery",
                "toolCallOrigin": "PLATFORM_REPAIR_FROM_MODEL_OBSERVED_FAILURE",
                "failureRecoveryKind": "DUPLICATE_TASK_NAME",
                "failureSourceRunId": source_run_id,
                "requiresExactRepairConfirmation": True,
            },
        )
        lifecycle = self._tool_planner.expand_confirmed_data_sync_lifecycle(repair_draft)
        governed_lifecycle = tuple(
            replace(
                item,
                governance_hints={
                    **item.governance_hints,
                    "source": "platform_duplicate_task_name_recovery",
                    "failureRecoveryKind": "DUPLICATE_TASK_NAME",
                    "failureSourceRunId": source_run_id,
                    "requiresExactRepairConfirmation": True,
                    "confirmationScope": "RENAME_CREATE_CONFIGURE_AND_START_SYNC_TASK",
                },
            )
            for item in lifecycle
        )
        proposal = DuplicateTaskNameRepairProposal(
            failure_code=(failed_feedback.error_code or "DUPLICATE_OPERATION"),
            original_task_name=original_name,
            proposed_task_name=proposed_name,
        )
        return DuplicateTaskNameRecoveryPlan(proposal=proposal, tool_plans=governed_lifecycle)

    @classmethod
    def _bind_trusted_metadata_references(
        cls,
        arguments: dict[str, Any],
        feedback_items: tuple[AgentControlPlaneFeedbackItem, ...],
    ) -> None:
        """Rebind draft metadata inputs to successful Java facts from the source Run.

        Duplicate-name recovery intentionally creates a small write lifecycle instead of
        re-running every connection and metadata discovery node.  The failed draft still
        contains references such as ``{"fromTool": "datasource.source.metadata.read"}``,
        but a tool-only reference means "latest output in the current Run" to Java.  Once
        the repair is ingested as a new Run, that lookup is empty and draft validation
        correctly fails with ``缺少源端元数据结果``.

        The continuation payload already carries terminal Java facts with trusted audit
        and Run identifiers.  Binding those identifiers here preserves the reviewed
        metadata across Runs without copying metadata bodies into the plan and without
        trusting model-supplied resource IDs.  Java resolves the reference by
        ``sessionId + auditId`` and still verifies that the referenced tool code matches.
        If no successful fact exists, the original argument is retained so downstream
        fail-closed validation remains authoritative rather than manufacturing evidence.
        """

        successful_by_tool: dict[str, list[AgentControlPlaneFeedbackItem]] = {}
        for item in feedback_items:
            if (
                item.status is ToolExecutionFeedbackStatus.SUCCEEDED
                and item.audit_id
            ):
                successful_by_tool.setdefault(item.tool_name, []).append(item)

        for argument_name, tool_name in cls._METADATA_REFERENCE_ARGUMENTS.items():
            facts = successful_by_tool.get(tool_name, [])
            if facts:
                arguments[argument_name] = cls._metadata_reference(tool_name, facts[-1])

        for argument_name, tool_name in cls._METADATA_REFERENCE_GROUP_ARGUMENTS.items():
            facts = successful_by_tool.get(tool_name, [])
            if facts:
                arguments[argument_name] = [
                    cls._metadata_reference(tool_name, fact)
                    for fact in facts
                ]

    @staticmethod
    def _metadata_reference(
        tool_name: str,
        fact: AgentControlPlaneFeedbackItem,
    ) -> dict[str, str]:
        """Render the low-sensitive reference shape accepted by Java's output resolver."""

        reference = {
            "fromTool": tool_name,
            "fromAuditId": str(fact.audit_id),
            "path": "metadata",
        }
        if fact.run_id:
            reference["fromRunId"] = fact.run_id
        return reference

    @staticmethod
    def _is_duplicate_name_failure(item: AgentControlPlaneFeedbackItem) -> bool:
        evidence = " ".join((
            str(item.error_code or ""),
            str(item.error_message or ""),
            str(item.summary or ""),
            json.dumps(item.result, ensure_ascii=False, sort_keys=True, default=str),
        )).upper()
        return "DUPLICATE_OPERATION" in evidence and (
            "TASK" in evidence
            or "任务" in evidence
            or "SYNC.TASK.DRAFT.SAVE" in evidence
        )

    @staticmethod
    def _proposed_name(original_name: str, source_run_id: str) -> str:
        suffix = hashlib.sha256(
            f"{source_run_id}|{original_name}|duplicate-task-name".encode("utf-8")
        ).hexdigest()[:8]
        rendered_suffix = f"_agent_{suffix}"
        # data-sync persists at most 160 characters. Truncate the base before
        # adding the traceable suffix so the proposed value remains distinct.
        return f"{original_name[:160 - len(rendered_suffix)]}{rendered_suffix}"


__all__ = (
    "DuplicateTaskNameRecoveryPlan",
    "DuplicateTaskNameRecoveryPlanner",
    "DuplicateTaskNameRepairProposal",
)
