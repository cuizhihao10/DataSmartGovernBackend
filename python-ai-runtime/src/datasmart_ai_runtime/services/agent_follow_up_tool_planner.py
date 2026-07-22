"""Govern model-selected tools after a control-plane result has been returned.

The first model turn and every follow-up turn must cross the same trust boundary:
model output is an untrusted proposal, while a :class:`ToolPlan` is a platform
contract that may be submitted to Java or an MCP outbox.  This service keeps the
follow-up path from accidentally becoming a less-governed shortcut.
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass, replace

from datasmart_ai_runtime.domain.contracts import (
    AgentPlan,
    AgentRequest,
    ModelToolCall,
    ToolDefinition,
    ToolPlan,
)
from datasmart_ai_runtime.services.model_gateway.model_tool_call_budget_guard import (
    ModelToolCallBudgetGuard,
)
from datasmart_ai_runtime.services.model_gateway.model_tool_call_budget_policy_provider import (
    EnvAndRequestModelToolCallBudgetPolicyProvider,
    ModelToolCallBudgetPolicyProvider,
)
from datasmart_ai_runtime.services.model_gateway.model_tool_call_planner import (
    ModelToolCallGovernanceIssue,
    ModelToolCallPlanningReport,
)
from datasmart_ai_runtime.services.agent_control_plane_feedback import (
    AgentControlPlaneFeedbackSnapshot,
)
from datasmart_ai_runtime.services.tools import ToolActionIntakeService
from datasmart_ai_runtime.services.tool_planner import ToolPlanner
from datasmart_ai_runtime.services.model_tool_result_policies import model_result_governance


@dataclass(frozen=True)
class AgentFollowUpToolPlanningResult:
    """Low-sensitive result of governing one model follow-up proposal batch."""

    visible_tools: tuple[ToolDefinition, ...] = ()
    accepted_tool_plans: tuple[ToolPlan, ...] = ()
    proposed_count: int = 0
    accepted_before_repeat_guard: int = 0
    rejected_count: int = 0
    repeated_count: int = 0
    state_guard_rejected_count: int = 0
    budget_issue_codes: tuple[str, ...] = ()
    repeated_fingerprints: tuple[str, ...] = ()
    resource_reference_count: int = 0

    @property
    def continues(self) -> bool:
        """Whether the model produced a new governed batch for another run."""

        return bool(self.accepted_tool_plans)

    def to_summary(self) -> dict[str, object]:
        """Return observability fields without exposing tool argument values."""

        return {
            "visibleToolNames": tuple(tool.name for tool in self.visible_tools),
            "proposedCount": self.proposed_count,
            "acceptedCount": len(self.accepted_tool_plans),
            "rejectedCount": self.rejected_count,
            "repeatedCount": self.repeated_count,
            "stateGuardRejectedCount": self.state_guard_rejected_count,
            "acceptedToolNames": tuple(plan.tool_name for plan in self.accepted_tool_plans),
            "budgetIssueCodes": self.budget_issue_codes,
            "repeatedFingerprints": self.repeated_fingerprints,
            "resourceReferenceCount": self.resource_reference_count,
            "payloadPolicy": "LOW_SENSITIVE_TOOL_GOVERNANCE_ONLY",
        }


class AgentFollowUpToolPlanner:
    """Apply visibility, schema, risk, budget and repeat guards to later turns.

    A repeat fingerprint contains only a SHA-256 digest of canonical tool name and
    arguments.  The checkpoint and UI can therefore explain why a loop stopped
    without persisting SQL, identifiers or other argument values.
    """

    # Status polling is an intentional loop edge.  All mutating tools remain
    # non-repeatable within the same Agent request unless a later user action starts
    # a new request/idempotency scope.
    REPEATABLE_TOOLS = frozenset({"sync.execution.status"})

    # Derived arguments are never trusted from the model.  They are rebuilt from
    # successful control-plane audit facts and point to an allow-listed output
    # path.  Java performs the final same-session reference resolution.
    DERIVED_REFERENCES: dict[str, dict[str, tuple[str, str]]] = {
        "datasource.source.metadata.read": {
            "connectionTestRef": ("datasource.source.connection.test", "success"),
        },
        "datasource.target.metadata.read": {
            "connectionTestRef": ("datasource.target.connection.test", "success"),
        },
        "sync.task.draft.save": {
            "sourceMetadataRef": ("datasource.source.metadata.read", "metadata"),
            "targetMetadataRef": ("datasource.target.metadata.read", "metadata"),
        },
        "sync.task.precheck": {
            "draftRef": ("sync.task.draft.save", "templateId"),
        },
        "sync.task.publish": {
            "draftRef": ("sync.task.draft.save", "taskId"),
            "precheckRef": ("sync.task.precheck", "canStartExecution"),
        },
        "sync.task.run": {
            "taskRef": ("sync.task.publish", "taskId"),
        },
        "sync.execution.status": {
            "taskRef": ("sync.task.run", "taskId"),
        },
        "sync.execution.diagnose": {
            "statusRef": ("sync.execution.status", ""),
        },
        "sync.execution.rag.lookup": {
            "diagnosisRef": ("sync.execution.diagnose", ""),
        },
        "sync.execution.failed-objects.retry": {
            "diagnosisRef": ("sync.execution.diagnose", ""),
        },
        "sync.dirty-record.quarantine.preview": {
            "diagnosisRef": ("sync.execution.diagnose", ""),
        },
        "sync.dirty-record.quarantine.apply": {
            "previewRef": ("sync.dirty-record.quarantine.preview", ""),
        },
        "sync.dirty-record.replay": {
            "diagnosisRef": ("sync.execution.diagnose", ""),
        },
        "datasource.schema.repair.preview": {
            "diagnosisRef": ("sync.execution.diagnose", ""),
        },
        "datasource.schema.repair.apply": {
            "previewRef": ("datasource.schema.repair.preview", ""),
        },
        "sync.recovery.case.publish": {
            "diagnosisRef": ("sync.execution.diagnose", ""),
            "validationRef": ("sync.execution.status", ""),
        },
        "sync.task.import.rag.lookup": {
            "dryRunRef": ("sync.task.import.dry-run", "ragQuery"),
        },
        "sync.task.import.repair.apply": {
            "artifactRef": ("sync.task.import.dry-run", "artifact.artifactRef"),
            "baseVersion": ("sync.task.import.dry-run", "artifact.versionNumber"),
            "confirmationDigest": ("sync.task.import.dry-run", "confirmationDigest"),
        },
        "sync.task.import.commit": {
            "artifactRef": ("sync.task.import.dry-run", "artifact.artifactRef"),
            "confirmationDigest": ("sync.task.import.dry-run", "confirmationDigest"),
        },
        "sync.task.import.dry-run": {
            "artifactRef": ("sync.task.import.repair.apply", "artifactRef"),
        },
    }

    def __init__(
        self,
        *,
        tool_planner: ToolPlanner,
        intake_service: ToolActionIntakeService | None = None,
        budget_guard: ModelToolCallBudgetGuard | None = None,
        budget_policy_provider: ModelToolCallBudgetPolicyProvider | None = None,
    ) -> None:
        self._tool_planner = tool_planner
        self._intake_service = intake_service or ToolActionIntakeService()
        self._budget_guard = budget_guard or ModelToolCallBudgetGuard()
        self._budget_policy_provider = (
            budget_policy_provider or EnvAndRequestModelToolCallBudgetPolicyProvider()
        )

    def visible_tools(self, request: AgentRequest, plan: AgentPlan) -> tuple[ToolDefinition, ...]:
        """Resolve the minimum tool set that the next model turn may choose from."""

        visible = list(self._tool_planner.model_visible_follow_up_tools(
            request=request,
            intent_analysis=plan.intent_analysis,
            context_blocks=plan.context_blocks,
            skill_plan=plan.skill_plan,
            previous_tool_plans=plan.tool_plans,
        ))
        # A durable asynchronous worker may resume after the original Python
        # request has gone away.  The Java command carries only a server-created
        # allow-list snapshot, never model-supplied names.  Rehydrate those tools
        # from the immutable startup registry so MCP continuations keep the same
        # least-privilege boundary as the original turn.
        runtime_allowed: set[str] = set()
        for item in plan.tool_plans:
            names = item.governance_hints.get("runtimeContinuationVisibleToolNames")
            if not isinstance(names, (list, tuple, set)):
                continue
            runtime_allowed.update(str(name).strip() for name in names if str(name).strip())
        seen = {tool.name for tool in visible}
        for tool in self._tool_planner.registered_tools():
            if tool.name not in runtime_allowed or tool.name in seen:
                continue
            visible.append(tool)
            seen.add(tool.name)
        return tuple(visible)

    def govern(
        self,
        *,
        request: AgentRequest,
        plan: AgentPlan,
        tool_calls: tuple[ModelToolCall, ...],
        visible_tools: tuple[ToolDefinition, ...],
        control_plane_feedback: AgentControlPlaneFeedbackSnapshot | None = None,
    ) -> AgentFollowUpToolPlanningResult:
        """Convert untrusted model calls into a bounded non-repeating tool batch."""

        if not tool_calls:
            return AgentFollowUpToolPlanningResult(visible_tools=visible_tools)

        resource_ledger = self._resource_ledger(plan, control_plane_feedback)
        governed_calls = tuple(
            self._inject_derived_arguments(call, resource_ledger)
            for call in tool_calls
        )
        intake = self._intake_service.from_model_tool_calls(
            governed_calls,
            registered_tools=self._tool_planner.registered_tools(),
            visible_tools=visible_tools,
        )
        report = intake.planning_report
        if report is None:
            return AgentFollowUpToolPlanningResult(
                visible_tools=visible_tools,
                proposed_count=len(tool_calls),
                rejected_count=len(tool_calls),
            )

        state_guarded_report, state_guard_rejected_count = self._apply_state_guards(
            report,
            control_plane_feedback,
            resource_ledger,
        )
        guarded = self._budget_guard.evaluate(
            state_guarded_report,
            policy=self._budget_policy_provider.policy_for(request),
        )
        prior_fingerprints = {
            self.fingerprint(item.tool_name, item.arguments)
            for item in plan.tool_plans
        }
        prior_fingerprints.update(self._inherited_fingerprints(plan))
        accepted: list[ToolPlan] = []
        repeated: list[str] = []
        current_fingerprints: set[str] = set()
        for item in guarded.guarded_report.accepted_tool_plans:
            fingerprint = self.fingerprint(item.tool_name, item.arguments)
            if (
                item.tool_name not in self.REPEATABLE_TOOLS
                and (fingerprint in prior_fingerprints or fingerprint in current_fingerprints)
            ):
                repeated.append(fingerprint)
                continue
            current_fingerprints.add(fingerprint)
            accepted.append(
                replace(
                    item,
                    governance_hints={
                        **item.governance_hints,
                        "agentLoopFollowUp": True,
                        "toolCallFingerprint": fingerprint,
                        "agentLoopToolFingerprints": tuple(sorted(prior_fingerprints | current_fingerprints)),
                        "agentLoopResourceRefs": resource_ledger,
                        **model_result_governance(item.tool_name),
                    },
                )
            )

        return AgentFollowUpToolPlanningResult(
            visible_tools=visible_tools,
            accepted_tool_plans=tuple(accepted),
            proposed_count=len(report.candidates),
            accepted_before_repeat_guard=len(guarded.guarded_report.accepted_tool_plans),
            rejected_count=len(guarded.guarded_report.rejected_candidates),
            repeated_count=len(repeated),
            state_guard_rejected_count=state_guard_rejected_count,
            budget_issue_codes=guarded.budget_issue_codes,
            repeated_fingerprints=tuple(repeated),
            resource_reference_count=len(resource_ledger),
        )

    @staticmethod
    def _apply_state_guards(
        report: ModelToolCallPlanningReport,
        feedback: AgentControlPlaneFeedbackSnapshot | None,
        resource_ledger: dict[str, dict[str, str]],
    ) -> tuple[ModelToolCallPlanningReport, int]:
        """Reject lifecycle-invalid proposals before risk budgets are counted.

        A model may propose several tools in one native ``tool_calls`` batch.  For
        task imports, repair and commit are mutually exclusive: a repaired artifact
        must be dry-run again before it can be committed.  A failed dry-run also
        blocks commit even if the model proposed it optimistically.
        """

        if not report.candidates:
            return report, 0
        dry_run_requires_repair: bool | None = None
        latest_feedback_tool = ""
        if feedback is not None:
            for item in reversed(feedback.feedback_items):
                if not latest_feedback_tool:
                    latest_feedback_tool = item.tool_name
                if item.tool_name != "sync.task.import.dry-run":
                    continue
                value = item.result.get("repairRequired")
                if isinstance(value, bool):
                    dry_run_requires_repair = value
                break

        has_repair = dry_run_requires_repair is not False and any(
            candidate.accepted
            and candidate.tool_plan is not None
            and candidate.tool_plan.tool_name == "sync.task.import.repair.apply"
            for candidate in report.candidates
        )
        succeeded_tools = set(resource_ledger)
        succeeded_tools.update({
            item.tool_name
            for item in feedback.feedback_items
            if item.status.value == "succeeded"
        } if feedback is not None else set())
        latest_status_result: dict[str, object] = {}
        if feedback is not None:
            for item in reversed(feedback.feedback_items):
                if item.tool_name == "sync.execution.status" and item.status.value == "succeeded":
                    latest_status_result = dict(item.result)
                    break
        recovery_mutations = {
            "sync.execution.failed-objects.retry",
            "sync.dirty-record.quarantine.apply",
            "sync.dirty-record.replay",
            "datasource.schema.repair.apply",
        }
        preview_apply_tools = {
            "sync.dirty-record.quarantine.apply",
            "datasource.schema.repair.apply",
        }
        batch_apply_tools = {
            candidate.tool_plan.tool_name
            for candidate in report.candidates
            if candidate.accepted
            and candidate.tool_plan is not None
            and candidate.tool_plan.tool_name in preview_apply_tools
        }
        candidates = []
        rejected = 0
        for candidate in report.candidates:
            plan = candidate.tool_plan
            reject_code = ""
            reject_message = ""
            if candidate.accepted and plan is not None and plan.tool_name == "sync.task.import.commit" and (
                has_repair or dry_run_requires_repair is not False
            ):
                reject_code = "MODEL_TOOL_CALL_IMPORT_COMMIT_STATE_INVALID"
                reject_message = "当前导入试运行仍需修复，或同一批次包含修复动作；修复后的新制品必须再次试运行后才能提交。"
            elif (
                candidate.accepted
                and plan is not None
                and plan.tool_name == "sync.task.import.repair.apply"
                and dry_run_requires_repair is False
            ):
                reject_code = "MODEL_TOOL_CALL_IMPORT_REPAIR_NOT_REQUIRED"
                reject_message = "当前制品试运行已通过，不允许继续生成无必要的修改；可进入用户确认和正式导入。"
            elif (
                candidate.accepted
                and plan is not None
                and plan.tool_name == "sync.task.import.repair.apply"
                and latest_feedback_tool == "sync.task.import.dry-run"
                and dry_run_requires_repair is True
            ):
                reject_code = "MODEL_TOOL_CALL_IMPORT_REPAIR_REQUIRES_EVIDENCE"
                reject_message = "必须先使用试运行错误码检索产品文档和历史案例，再基于证据提出修复补丁。"
            elif (
                candidate.accepted
                and plan is not None
                and plan.tool_name in recovery_mutations
                and "sync.execution.rag.lookup" not in succeeded_tools
            ):
                reject_code = "MODEL_TOOL_CALL_RECOVERY_EVIDENCE_REQUIRED"
                reject_message = "执行恢复动作前必须先用失败诊断检索项目文档、历史案例和 Runbook。"
            elif (
                candidate.accepted
                and plan is not None
                and plan.tool_name == "sync.dirty-record.quarantine.apply"
                and "sync.dirty-record.quarantine.preview" not in succeeded_tools
            ):
                reject_code = "MODEL_TOOL_CALL_QUARANTINE_PREVIEW_REQUIRED"
                reject_message = "隔离坏行前必须先生成精确范围预览和确认摘要。"
            elif (
                candidate.accepted
                and plan is not None
                and plan.tool_name == "datasource.schema.repair.apply"
                and "datasource.schema.repair.preview" not in succeeded_tools
            ):
                reject_code = "MODEL_TOOL_CALL_SCHEMA_REPAIR_PREVIEW_REQUIRED"
                reject_message = "修改目标表结构前必须先读取实时元数据并生成白名单修复预览。"
            elif (
                candidate.accepted
                and plan is not None
                and plan.tool_name in {"sync.execution.failed-objects.retry", "sync.dirty-record.replay"}
                and batch_apply_tools
            ):
                reject_code = "MODEL_TOOL_CALL_RECOVERY_REQUIRES_NEXT_TURN"
                reject_message = "结构或隔离修复应用后必须先取得真实执行结果，再在下一轮发起重试或重放。"
            elif (
                candidate.accepted
                and plan is not None
                and plan.tool_name == "sync.recovery.case.publish"
                and (
                    str(latest_status_result.get("executionState") or "").upper() != "SUCCEEDED"
                    or int(latest_status_result.get("failedRecordCount") or 0) != 0
                )
            ):
                reject_code = "MODEL_TOOL_CALL_RECOVERY_CASE_NOT_VERIFIED"
                reject_message = "只有恢复后的验证执行成功且失败行数为 0，才能沉淀恢复案例。"
            if reject_code:
                rejected += 1
                candidates.append(
                    replace(
                        candidate,
                        issues=(*candidate.issues, ModelToolCallGovernanceIssue(
                            tool_name=plan.tool_name,
                            code=reject_code,
                            message=reject_message,
                            blocking=True,
                        )),
                    )
                )
                continue
            candidates.append(candidate)
        return ModelToolCallPlanningReport(candidates=tuple(candidates)), rejected

    @classmethod
    def _inject_derived_arguments(
        cls,
        call: ModelToolCall,
        resource_ledger: dict[str, dict[str, str]],
    ) -> ModelToolCall:
        """Replace model-supplied derived fields with trusted audit references."""

        try:
            parsed = json.loads(call.arguments or "{}")
        except (TypeError, ValueError, json.JSONDecodeError):
            parsed = {}
        arguments = dict(parsed) if isinstance(parsed, dict) else {}
        for argument_name, (source_tool, path) in cls.DERIVED_REFERENCES.get(call.name, {}).items():
            source = resource_ledger.get(source_tool)
            if source is None or not source.get("auditId"):
                arguments.pop(argument_name, None)
                continue
            arguments[argument_name] = {
                "fromTool": source_tool,
                "fromAuditId": source["auditId"],
                "fromRunId": source.get("runId"),
                "path": path or None,
            }
        return replace(
            call,
            arguments=json.dumps(arguments, ensure_ascii=False, sort_keys=True),
        )

    @classmethod
    def _resource_ledger(
        cls,
        plan: AgentPlan,
        feedback: AgentControlPlaneFeedbackSnapshot | None,
    ) -> dict[str, dict[str, str]]:
        """Merge inherited references with newly succeeded control-plane facts."""

        ledger: dict[str, dict[str, str]] = {}
        for tool_plan in plan.tool_plans:
            inherited = tool_plan.governance_hints.get("agentLoopResourceRefs")
            if not isinstance(inherited, dict):
                continue
            for tool_name, raw_reference in inherited.items():
                normalized = cls._normalized_reference(str(tool_name), raw_reference)
                if normalized is not None:
                    ledger[str(tool_name)] = normalized

        if feedback is None:
            return ledger
        for item in feedback.feedback_items:
            if item.status.value != "succeeded" or not item.audit_id:
                continue
            reference = {
                "toolCode": item.tool_name,
                "auditId": item.audit_id,
            }
            if item.run_id:
                reference["runId"] = item.run_id
            if item.output_ref:
                reference["outputRef"] = item.output_ref
            ledger[item.tool_name] = reference
        return ledger

    @staticmethod
    def _normalized_reference(tool_name: str, candidate: object) -> dict[str, str] | None:
        if not isinstance(candidate, dict):
            return None
        audit_id = str(candidate.get("auditId") or "").strip()
        if not audit_id:
            return None
        result = {"toolCode": tool_name, "auditId": audit_id}
        for name in ("runId", "outputRef"):
            value = str(candidate.get(name) or "").strip()
            if value:
                result[name] = value
        return result

    @staticmethod
    def _inherited_fingerprints(plan: AgentPlan) -> set[str]:
        fingerprints: set[str] = set()
        for item in plan.tool_plans:
            values = item.governance_hints.get("agentLoopToolFingerprints")
            if not isinstance(values, (list, tuple, set)):
                continue
            fingerprints.update(str(value) for value in values if str(value).strip())
        return fingerprints

    @staticmethod
    def fingerprint(tool_name: str, arguments: dict[str, object]) -> str:
        """Build a stable digest used only for loop repeat detection."""

        canonical = json.dumps(
            {"tool": tool_name, "arguments": arguments},
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
            default=str,
        )
        return hashlib.sha256(canonical.encode("utf-8")).hexdigest()
