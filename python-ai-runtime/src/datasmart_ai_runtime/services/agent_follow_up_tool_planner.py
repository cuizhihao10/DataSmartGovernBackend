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
    state_guard_issue_codes: tuple[str, ...] = ()
    state_guard_issue_messages: tuple[str, ...] = ()
    budget_issue_codes: tuple[str, ...] = ()
    repeated_fingerprints: tuple[str, ...] = ()
    resource_reference_count: int = 0
    platform_expanded_tool_names: tuple[str, ...] = ()

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
            "stateGuardIssueCodes": self.state_guard_issue_codes,
            "stateGuardIssueMessages": self.state_guard_issue_messages,
            "acceptedToolNames": tuple(plan.tool_name for plan in self.accepted_tool_plans),
            "budgetIssueCodes": self.budget_issue_codes,
            "repeatedFingerprints": self.repeated_fingerprints,
            "resourceReferenceCount": self.resource_reference_count,
            "platformExpandedToolCount": len(self.platform_expanded_tool_names),
            "platformExpandedToolNames": self.platform_expanded_tool_names,
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
        "datasource.source.connection.test": {
            "catalogSearchRef": ("datasource.source.catalog.search", "resolvedDatasourceId"),
        },
        "datasource.target.connection.test": {
            "catalogSearchRef": ("datasource.target.catalog.search", "resolvedDatasourceId"),
        },
        "datasource.source.metadata.read": {
            "connectionTestRef": ("datasource.source.connection.test", "datasourceId"),
        },
        "datasource.target.metadata.read": {
            "connectionTestRef": ("datasource.target.connection.test", "datasourceId"),
        },
        "sync.task.draft.save": {
            "sourceMetadataRef": ("datasource.source.metadata.read", "metadata"),
            "targetMetadataRef": ("datasource.target.metadata.read", "metadata"),
            "cdcReadinessRef": ("sync.cdc.readiness.check", "ready"),
        },
        "sync.cdc.readiness.check": {
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
        "datasource.target-table.create.preview": {
            "sourceMetadataRef": ("datasource.source.metadata.read", "metadata"),
            "targetMetadataRef": ("datasource.target.metadata.read", "metadata"),
        },
        "datasource.target-table.create.apply": {
            "previewRef": ("datasource.target-table.create.preview", ""),
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
    DERIVED_REFERENCE_GROUPS: dict[str, dict[str, tuple[str, str]]] = {
        "sync.task.draft.save": {
            "sourceMetadataRefs": ("datasource.source.metadata.read", "metadata"),
            "targetMetadataRefs": ("datasource.target.metadata.read", "metadata"),
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
        resource_reference_groups = self._resource_reference_groups(plan, control_plane_feedback)
        prerequisite_calls, prerequisite_call_ids = self._replace_cdc_draft_with_readiness_prerequisite(
            tool_calls,
            control_plane_feedback,
        )
        governed_calls = tuple(
            self._inject_derived_arguments(call, resource_ledger, resource_reference_groups)
            for call in prerequisite_calls
        )
        effective_visible_tools = self._with_cdc_readiness_visible(
            visible_tools,
            required=bool(prerequisite_call_ids),
        )
        intake = self._intake_service.from_model_tool_calls(
            governed_calls,
            registered_tools=self._tool_planner.registered_tools(),
            visible_tools=effective_visible_tools,
        )
        report = intake.planning_report
        if report is None:
            return AgentFollowUpToolPlanningResult(
                visible_tools=effective_visible_tools,
                proposed_count=len(tool_calls),
                rejected_count=len(tool_calls),
            )

        state_guarded_report, state_guard_rejected_count = self._apply_state_guards(
            report,
            control_plane_feedback,
            resource_ledger,
        )
        state_guard_issues = tuple(
            issue
            for issue in state_guarded_report.issues
            if issue.blocking
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
            governance_hints = {
                **item.governance_hints,
                "agentLoopFollowUp": True,
                "toolCallFingerprint": fingerprint,
                "agentLoopToolFingerprints": tuple(sorted(prior_fingerprints | current_fingerprints)),
                "agentLoopResourceRefs": resource_ledger,
                "agentLoopResourceRefGroups": resource_reference_groups,
                **model_result_governance(item.tool_name),
            }
            if item.governance_hints.get("modelToolCallId") in prerequisite_call_ids:
                governance_hints.update({
                    "source": "platform_cdc_readiness_prerequisite",
                    "toolCallOrigin": "MODEL_NATIVE_WITH_PLATFORM_PREREQUISITE",
                    "platformPrerequisiteFor": "sync.task.draft.save",
                })
            accepted.append(
                replace(
                    item,
                    governance_hints=governance_hints,
                )
            )

        accepted_plans, platform_expanded_tool_names = self._expand_confirmed_sync_lifecycle(
            tuple(accepted),
            prior_fingerprints=prior_fingerprints,
        )

        return AgentFollowUpToolPlanningResult(
            visible_tools=effective_visible_tools,
            accepted_tool_plans=accepted_plans,
            proposed_count=len(report.candidates),
            accepted_before_repeat_guard=len(guarded.guarded_report.accepted_tool_plans),
            rejected_count=len(guarded.guarded_report.rejected_candidates),
            repeated_count=len(repeated),
            state_guard_rejected_count=state_guard_rejected_count,
            state_guard_issue_codes=tuple(dict.fromkeys(
                issue.code for issue in state_guard_issues
            )),
            state_guard_issue_messages=tuple(dict.fromkeys(
                issue.message for issue in state_guard_issues
            )),
            budget_issue_codes=guarded.budget_issue_codes,
            repeated_fingerprints=tuple(repeated),
            resource_reference_count=len(resource_ledger),
            platform_expanded_tool_names=platform_expanded_tool_names,
        )

    def _with_cdc_readiness_visible(
        self,
        visible_tools: tuple[ToolDefinition, ...],
        *,
        required: bool,
    ) -> tuple[ToolDefinition, ...]:
        """Admit the safe CDC prerequisite when platform governance inserted it."""

        if not required or any(tool.name == "sync.cdc.readiness.check" for tool in visible_tools):
            return visible_tools
        readiness = next(
            (
                tool
                for tool in self._tool_planner.registered_tools()
                if tool.name == "sync.cdc.readiness.check"
            ),
            None,
        )
        return (*visible_tools, readiness) if readiness is not None else visible_tools

    @staticmethod
    def _replace_cdc_draft_with_readiness_prerequisite(
        tool_calls: tuple[ModelToolCall, ...],
        feedback: AgentControlPlaneFeedbackSnapshot | None,
    ) -> tuple[tuple[ModelToolCall, ...], frozenset[str]]:
        """Run the real CDC gate before accepting a model-proposed realtime draft.

        The provider may jump directly from metadata to ``sync.task.draft.save``.
        A rejected call would leave the user at a misleading clarification screen,
        while accepting it would bypass the CDC runtime boundary. Reusing the
        provider's call id for the prerequisite preserves the native tool-result
        protocol: the next model turn receives a result for the call it made, but
        the platform has safely narrowed the action to a read-only readiness check.
        """

        readiness_completed = any(
            item.tool_name == "sync.cdc.readiness.check"
            and item.status.value == "succeeded"
            for item in (feedback.feedback_items if feedback is not None else ())
        )
        if readiness_completed:
            return tool_calls, frozenset()

        model_already_requested_readiness = any(
            call.name == "sync.cdc.readiness.check" for call in tool_calls
        )
        rewritten: list[ModelToolCall] = []
        prerequisite_call_ids: set[str] = set()
        for call in tool_calls:
            if call.name != "sync.task.draft.save":
                rewritten.append(call)
                continue
            try:
                parsed = json.loads(call.arguments or "{}")
            except (TypeError, ValueError, json.JSONDecodeError):
                parsed = {}
            arguments = dict(parsed) if isinstance(parsed, dict) else {}
            sync_mode = str(arguments.get("syncMode") or "FULL").strip().upper()
            if sync_mode not in {"CDC_STREAMING", "REAL_TIME"}:
                rewritten.append(call)
                continue

            # If the model proposed both tools, execute only the readiness check in
            # this turn. The draft must be proposed again after a positive result.
            if model_already_requested_readiness:
                continue
            call_id = str(call.call_id or "").strip()
            rewritten.append(ModelToolCall(
                call_id=call.call_id,
                type=call.type,
                name="sync.cdc.readiness.check",
                arguments=json.dumps(
                    {"objectMappings": arguments.get("objectMappings", [])},
                    ensure_ascii=False,
                    separators=(",", ":"),
                ),
                raw_call={
                    "source": "platform_cdc_readiness_prerequisite",
                    "parentToolName": "sync.task.draft.save",
                },
            ))
            if call_id:
                prerequisite_call_ids.add(call_id)
        return tuple(rewritten), frozenset(prerequisite_call_ids)

    def _expand_confirmed_sync_lifecycle(
        self,
        accepted_plans: tuple[ToolPlan, ...],
        *,
        prior_fingerprints: set[str],
    ) -> tuple[tuple[ToolPlan, ...], tuple[str, ...]]:
        """Attach deterministic lifecycle nodes to an accepted sync draft.

        Only ``sync.task.draft.save`` comes from the model's native tool call.  The
        remaining nodes are generated from the platform registry and linked through
        output references.  They deliberately omit ``modelToolCallId`` so audit views
        can distinguish model choice from platform lifecycle expansion.
        """

        if not any(plan.tool_name == "sync.task.draft.save" for plan in accepted_plans):
            return accepted_plans, ()

        expanded: list[ToolPlan] = []
        platform_names: list[str] = []
        fingerprints = set(prior_fingerprints)
        for accepted in accepted_plans:
            if accepted.tool_name != "sync.task.draft.save":
                expanded.append(accepted)
                fingerprints.add(self.fingerprint(accepted.tool_name, accepted.arguments))
                continue

            lifecycle = self._tool_planner.expand_confirmed_data_sync_lifecycle(accepted)
            inherited_hints = {
                key: value
                for key, value in accepted.governance_hints.items()
                if key in {
                    "workspaceKey",
                    "memoryNamespace",
                    "cacheNamespace",
                    "artifactNamespace",
                    "agentLoopResourceRefs",
                    "agentLoopResourceRefGroups",
                }
            }
            parent_call_id = accepted.governance_hints.get("modelToolCallId")
            for index, lifecycle_plan in enumerate(lifecycle):
                fingerprint = self.fingerprint(lifecycle_plan.tool_name, lifecycle_plan.arguments)
                fingerprints.add(fingerprint)
                if index == 0:
                    expanded.append(replace(
                        lifecycle_plan,
                        governance_hints={
                            **lifecycle_plan.governance_hints,
                            "agentLoopToolFingerprints": tuple(sorted(fingerprints)),
                        },
                    ))
                    continue

                platform_names.append(lifecycle_plan.tool_name)
                tail_hints = {
                    **lifecycle_plan.governance_hints,
                    **inherited_hints,
                    "source": "platform_sync_lifecycle_expansion",
                    "agentLoopFollowUp": True,
                    "platformLifecycleExpansion": True,
                    "confirmationScope": "CREATE_CONFIGURE_AND_START_SYNC_TASK",
                    "parentModelToolCallId": parent_call_id,
                    "toolCallFingerprint": fingerprint,
                    "agentLoopToolFingerprints": tuple(sorted(fingerprints)),
                }
                tail_hints.pop("modelToolCallId", None)
                expanded.append(replace(lifecycle_plan, governance_hints=tail_hints))

        return tuple(expanded), tuple(platform_names)

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
        catalog_resolutions: dict[str, dict[str, object]] = {}
        metadata_summaries: dict[str, dict[str, object]] = {}
        cdc_readiness_result: dict[str, object] = {}
        if feedback is not None:
            for item in reversed(feedback.feedback_items):
                if not latest_feedback_tool:
                    latest_feedback_tool = item.tool_name
                if (
                    item.tool_name in {
                        "datasource.source.catalog.search",
                        "datasource.target.catalog.search",
                    }
                    and item.tool_name not in catalog_resolutions
                    and item.status.value == "succeeded"
                ):
                    catalog_resolutions[item.tool_name] = dict(item.result)
                if (
                    item.tool_name in {
                        "datasource.source.metadata.read",
                        "datasource.target.metadata.read",
                        "datasource.target-table.create.apply",
                    }
                    and item.status.value == "succeeded"
                ):
                    summary = item.result.get("summary")
                    if isinstance(summary, dict):
                        summary_key = (
                            "datasource.target.metadata.read"
                            if item.tool_name == "datasource.target-table.create.apply"
                            else item.tool_name
                        )
                        existing = metadata_summaries.get(summary_key)
                        metadata_summaries[summary_key] = (
                            dict(summary)
                            if existing is None
                            else AgentFollowUpToolPlanner._merge_metadata_summaries(
                                newest=existing,
                                older=summary,
                            )
                        )
                if (
                    item.tool_name == "sync.cdc.readiness.check"
                    and item.status.value == "succeeded"
                    and not cdc_readiness_result
                ):
                    cdc_readiness_result = dict(item.result)
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
            "datasource.target-table.create.apply",
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
                and plan.tool_name == "datasource.target-table.create.apply"
                and "datasource.target-table.create.preview" not in succeeded_tools
            ):
                reject_code = "MODEL_TOOL_CALL_TARGET_TABLE_CREATE_PREVIEW_REQUIRED"
                reject_message = "创建目标表前必须先根据可信源表元数据生成预览，并由用户确认完整字段定义。"
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
                and plan.tool_name == "sync.task.draft.save"
                and "datasource.target-table.create.apply" in batch_apply_tools
            ):
                reject_code = "MODEL_TOOL_CALL_TARGET_TABLE_REFRESH_REQUIRES_NEXT_TURN"
                reject_message = "目标表创建完成后必须先重新读取真实目标元数据，再在下一轮保存任务草稿。"
            elif (
                candidate.accepted
                and plan is not None
                and plan.tool_name == "sync.task.draft.save"
                and str(plan.arguments.get("syncMode") or "FULL").strip().upper()
                in {"CDC_STREAMING", "REAL_TIME"}
                and "sync.cdc.readiness.check" not in succeeded_tools
            ):
                reject_code = "MODEL_TOOL_CALL_CDC_READINESS_REQUIRED"
                reject_message = (
                    "实时同步任务保存前必须先完成真实 CDC 准入检查，不能仅凭元数据或模型判断创建草稿。"
                )
            elif (
                candidate.accepted
                and plan is not None
                and plan.tool_name == "sync.task.draft.save"
                and str(plan.arguments.get("syncMode") or "FULL").strip().upper()
                in {"CDC_STREAMING", "REAL_TIME"}
                and cdc_readiness_result.get("ready") is not True
            ):
                reject_code = "MODEL_TOOL_CALL_CDC_READINESS_BLOCKED"
                issue_codes = cdc_readiness_result.get("issueCodes")
                rendered_codes = ", ".join(str(code) for code in issue_codes) if isinstance(issue_codes, list) else ""
                reject_message = (
                    "CDC 准入检查存在阻断项，当前不能保存实时同步任务草稿。"
                    + (f"阻断编码：{rendered_codes}。" if rendered_codes else "请查看检查详情并按建议修复。")
                )
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
            elif candidate.accepted and plan is not None and plan.tool_name in {
                "datasource.source.connection.test",
                "datasource.target.connection.test",
            }:
                catalog_tool = (
                    "datasource.source.catalog.search"
                    if plan.tool_name == "datasource.source.connection.test"
                    else "datasource.target.catalog.search"
                )
                resolution = catalog_resolutions.get(catalog_tool)
                catalog_ref = plan.arguments.get("catalogSearchRef")
                has_trusted_catalog_ref = (
                    isinstance(catalog_ref, dict)
                    and catalog_ref.get("fromTool") == catalog_tool
                    and bool(catalog_ref.get("fromAuditId"))
                )
                if resolution is None or not has_trusted_catalog_ref:
                    reject_code = "MODEL_TOOL_CALL_DATASOURCE_CATALOG_EVIDENCE_REQUIRED"
                    reject_message = (
                        "连接测试必须继承本会话中已成功完成的精确数据源目录查询结果，"
                        "不能由模型直接填写或猜测内部数据源 ID。"
                    )
                else:
                    match_status = str(resolution.get("matchStatus") or "").strip().upper()
                    resolved_id = resolution.get("resolvedDatasourceId")
                    proposed_id = plan.arguments.get("datasourceId")
                    if match_status != "EXACT" or resolved_id is None:
                        reject_code = "MODEL_TOOL_CALL_DATASOURCE_SELECTION_REQUIRES_USER"
                        reject_message = (
                            "数据源名称未得到唯一精确匹配，不能由模型替用户选择候选项。"
                            "请向用户展示当前项目内的候选数据源，待用户明确选择后再继续连接测试。"
                        )
                    elif proposed_id is not None and str(proposed_id).strip() != str(resolved_id).strip():
                        reject_code = "MODEL_TOOL_CALL_DATASOURCE_ID_NOT_CATALOG_RESOLVED"
                        reject_message = (
                            "连接测试中的数据源 ID 与目录工具唯一精确匹配的 ID 不一致，"
                            "已阻止模型改用其他数据源。"
                        )
            elif candidate.accepted and plan is not None and plan.tool_name in {
                "datasource.source.metadata.read",
                "datasource.target.metadata.read",
            }:
                connection_tool = (
                    "datasource.source.connection.test"
                    if plan.tool_name == "datasource.source.metadata.read"
                    else "datasource.target.connection.test"
                )
                connection_ref = plan.arguments.get("connectionTestRef")
                if (
                    connection_tool not in succeeded_tools
                    or not isinstance(connection_ref, dict)
                    or connection_ref.get("fromTool") != connection_tool
                    or not connection_ref.get("fromAuditId")
                ):
                    reject_code = "MODEL_TOOL_CALL_DATASOURCE_CONNECTION_EVIDENCE_REQUIRED"
                    reject_message = (
                        "读取元数据前必须先取得同一端数据源的真实连接测试成功记录，"
                        "不能跳过连接验证或由模型直接指定内部数据源 ID。"
                    )
            elif candidate.accepted and plan is not None and plan.tool_name in {
                "sync.cdc.readiness.check",
                "sync.task.draft.save",
            }:
                reject_code, reject_message = AgentFollowUpToolPlanner._validate_sync_draft_against_metadata(
                    plan.arguments,
                    metadata_summaries,
                )
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

    @staticmethod
    def _validate_sync_draft_against_metadata(
        arguments: dict[str, object],
        metadata_summaries: dict[str, dict[str, object]],
    ) -> tuple[str, str]:
        """Validate model-proposed mappings against the latest bounded metadata.

        The model is allowed to interpret the user's request, but it is not the
        source of truth for database objects.  This guard runs before the draft
        reaches Java readiness/approval so a hallucinated table or field cannot
        be presented to the user as an executable plan.
        """

        source_summary = metadata_summaries.get("datasource.source.metadata.read")
        target_summary = metadata_summaries.get("datasource.target.metadata.read")
        if source_summary is None or target_summary is None:
            return "", ""
        mappings = arguments.get("objectMappings")
        if not isinstance(mappings, list) or not mappings:
            return (
                "MODEL_TOOL_CALL_SYNC_MAPPING_MISSING",
                "模型尚未生成源表到目标表的对象映射，不能保存任务草稿。请补充映射或根据真实元数据重新规划。",
            )

        sync_mode = str(arguments.get("syncMode") or "FULL").strip().upper()
        source_objects = AgentFollowUpToolPlanner._metadata_objects(source_summary)
        target_objects = AgentFollowUpToolPlanner._metadata_objects(target_summary)
        for index, raw_mapping in enumerate(mappings, start=1):
            if not isinstance(raw_mapping, dict):
                return (
                    "MODEL_TOOL_CALL_SYNC_MAPPING_INVALID",
                    f"第 {index} 条对象映射不是有效对象，请重新生成结构化映射。",
                )
            source_object = None
            if sync_mode != "CUSTOM_SQL_QUERY":
                source_object = AgentFollowUpToolPlanner._find_metadata_object(
                    source_objects,
                    raw_mapping.get("sourceSchemaName"),
                    raw_mapping.get("sourceObjectName"),
                )
                if source_object is None:
                    return (
                        "MODEL_TOOL_CALL_SOURCE_OBJECT_NOT_IN_METADATA",
                        AgentFollowUpToolPlanner._missing_object_message(
                            side="源端",
                            index=index,
                            schema_name=raw_mapping.get("sourceSchemaName"),
                            object_name=raw_mapping.get("sourceObjectName"),
                            truncated=bool(source_summary.get("truncated")),
                        ),
                    )
            target_object = AgentFollowUpToolPlanner._find_metadata_object(
                target_objects,
                raw_mapping.get("targetSchemaName"),
                raw_mapping.get("targetObjectName"),
            )
            if target_object is None:
                return (
                    "MODEL_TOOL_CALL_TARGET_OBJECT_NOT_IN_METADATA",
                    AgentFollowUpToolPlanner._missing_object_message(
                        side="目标端",
                        index=index,
                        schema_name=raw_mapping.get("targetSchemaName"),
                        object_name=raw_mapping.get("targetObjectName"),
                        truncated=bool(target_summary.get("truncated")),
                    ),
                )

            field_mappings = raw_mapping.get("fieldMappings")
            if not isinstance(field_mappings, list):
                continue
            source_field_metadata = AgentFollowUpToolPlanner._metadata_fields(source_object)
            target_field_metadata = AgentFollowUpToolPlanner._metadata_fields(target_object)
            source_fields = set(source_field_metadata)
            target_fields = set(target_field_metadata)
            for field_mapping in field_mappings:
                if not isinstance(field_mapping, dict) or field_mapping.get("syncEnabled") is False:
                    continue
                source_field = str(field_mapping.get("sourceField") or "").strip()
                target_field = str(field_mapping.get("targetField") or "").strip()
                if source_object is not None and source_field and source_field.lower() not in source_fields:
                    return (
                        "MODEL_TOOL_CALL_SOURCE_FIELD_NOT_IN_METADATA",
                        f"第 {index} 条映射的源字段“{source_field}”不存在于真实源表中。"
                        "请修正字段名、关闭该字段同步，或重新读取更精确的元数据。",
                    )
                if target_field and target_field.lower() not in target_fields:
                    return (
                        "MODEL_TOOL_CALL_TARGET_FIELD_NOT_IN_METADATA",
                        f"第 {index} 条映射的目标字段“{target_field}”不存在于真实目标表中。"
                        "请改为目标表已有字段，或经用户授权后进入结构修复流程。",
                    )
                if not source_field or not target_field:
                    continue
                source_column = source_field_metadata.get(source_field.lower())
                target_column = target_field_metadata.get(target_field.lower())
                source_type = str(
                    field_mapping.get("sourceType")
                    or (source_column or {}).get("dataTypeName")
                    or ""
                ).strip()
                target_type = str(
                    field_mapping.get("targetType")
                    or (target_column or {}).get("dataTypeName")
                    or ""
                ).strip()
                transform = str(field_mapping.get("transform") or "").strip()
                declared_compatible = field_mapping.get("typeCompatible") is not False
                if (
                    not transform
                    and (
                        not declared_compatible
                        or not AgentFollowUpToolPlanner._types_implicitly_compatible(
                            source_type,
                            target_type,
                        )
                    )
                ):
                    return (
                        "MODEL_TOOL_CALL_FIELD_TYPE_INCOMPATIBLE",
                        f"第 {index} 条映射的字段“{source_field} -> {target_field}”类型不兼容："
                        f"源端为 {source_type or '未知类型'}，目标端为 {target_type or '未知类型'}。"
                        "请明确配置转换表达式、关闭该字段同步，或修改目标字段类型后重新预检。",
                    )
        return "", ""

    @staticmethod
    def _metadata_objects(summary: dict[str, object]) -> tuple[dict[str, object], ...]:
        objects = summary.get("objects")
        if not isinstance(objects, list):
            return ()
        return tuple(item for item in objects if isinstance(item, dict))

    @classmethod
    def _merge_metadata_summaries(
        cls,
        *,
        newest: dict[str, object],
        older: dict[str, object],
    ) -> dict[str, object]:
        """Merge bounded metadata reads without losing separately queried tables.

        A multi-table request may intentionally issue the same directional metadata
        tool more than once with different exact table patterns.  Feedback is scanned
        newest-first, so scalar datasource facts and duplicate objects from the latest
        read remain authoritative while older, distinct objects are appended.  If any
        contributing read was truncated, the merged view remains truncated; a missing
        object must then trigger a precise re-read instead of a false non-existence
        conclusion.
        """

        merged = dict(newest)
        merged_objects: list[dict[str, object]] = []
        seen_objects: set[tuple[str, str]] = set()
        for metadata_object in (*cls._metadata_objects(newest), *cls._metadata_objects(older)):
            schema_name = str(metadata_object.get("schemaName") or "").strip().lower()
            table_name = str(metadata_object.get("tableName") or "").strip().lower()
            object_key = (schema_name, table_name)
            if table_name and object_key in seen_objects:
                continue
            if table_name:
                seen_objects.add(object_key)
            merged_objects.append(dict(metadata_object))
        merged["objects"] = merged_objects
        merged["truncated"] = bool(newest.get("truncated")) or bool(older.get("truncated"))
        return merged

    @staticmethod
    def _find_metadata_object(
        objects: tuple[dict[str, object], ...],
        schema_name: object,
        object_name: object,
    ) -> dict[str, object] | None:
        normalized_object = str(object_name or "").strip().lower()
        normalized_schema = str(schema_name or "").strip().lower()
        if not normalized_object:
            return None
        for item in objects:
            if str(item.get("tableName") or "").strip().lower() != normalized_object:
                continue
            item_schema = str(item.get("schemaName") or "").strip().lower()
            if normalized_schema and item_schema != normalized_schema:
                continue
            return item
        return None

    @staticmethod
    def _metadata_field_names(metadata_object: dict[str, object]) -> set[str]:
        return set(AgentFollowUpToolPlanner._metadata_fields(metadata_object))

    @staticmethod
    def _metadata_fields(metadata_object: dict[str, object] | None) -> dict[str, dict[str, object]]:
        if metadata_object is None:
            return {}
        columns = metadata_object.get("columns")
        if not isinstance(columns, list):
            return {}
        return {
            str(item.get("columnName") or "").strip().lower(): item
            for item in columns
            if isinstance(item, dict) and str(item.get("columnName") or "").strip()
        }

    @staticmethod
    def _types_implicitly_compatible(source_type: str, target_type: str) -> bool:
        """Allow only conversions that do not require a business decision.

        JDBC type names vary by connector, so the guard compares normalized type
        families.  Unknown metadata is left to the existing Java precheck instead
        of being guessed as incompatible.  Character-to-number, timestamp-to-date,
        decimal-to-integer and other narrowing/semantic conversions require an
        explicit transform selected by the user.
        """

        source = AgentFollowUpToolPlanner._normalized_type(source_type)
        target = AgentFollowUpToolPlanner._normalized_type(target_type)
        if not source or not target:
            return True
        if source == target:
            return True
        source_family, source_rank = AgentFollowUpToolPlanner._type_family(source)
        target_family, target_rank = AgentFollowUpToolPlanner._type_family(target)
        if source_family == "UNKNOWN" or target_family == "UNKNOWN":
            return True
        if source_family == target_family == "STRING":
            return True
        if source_family == target_family == "INTEGER":
            return source_rank <= target_rank
        if source_family == "INTEGER" and target_family == "DECIMAL":
            return True
        if source_family == "DATE" and target_family == "TIMESTAMP":
            return True
        return False

    @staticmethod
    def _normalized_type(value: str) -> str:
        normalized = str(value or "").strip().upper()
        for token in (" UNSIGNED", " WITHOUT TIME ZONE", " WITH TIME ZONE"):
            normalized = normalized.replace(token, "")
        return normalized.split("(", 1)[0].strip()

    @staticmethod
    def _type_family(value: str) -> tuple[str, int]:
        if value in {"TINYINT", "INT1"}:
            return "INTEGER", 1
        if value in {"SMALLINT", "INT2"}:
            return "INTEGER", 2
        if value in {"MEDIUMINT", "INTEGER", "INT", "INT4", "SERIAL"}:
            return "INTEGER", 3
        if value in {"BIGINT", "INT8", "BIGSERIAL"}:
            return "INTEGER", 4
        if value in {"DECIMAL", "NUMERIC", "REAL", "FLOAT", "DOUBLE", "DOUBLE PRECISION"}:
            return "DECIMAL", 1
        if value in {
            "CHAR", "NCHAR", "VARCHAR", "NVARCHAR", "CHARACTER VARYING",
            "TEXT", "TINYTEXT", "MEDIUMTEXT", "LONGTEXT", "CLOB",
        }:
            return "STRING", 1
        if value == "DATE":
            return "DATE", 1
        if value in {"TIMESTAMP", "DATETIME"}:
            return "TIMESTAMP", 1
        if value in {"BOOL", "BOOLEAN", "BIT"}:
            return "BOOLEAN", 1
        if value in {"BINARY", "VARBINARY", "BYTEA", "BLOB", "LONGBLOB"}:
            return "BINARY", 1
        if value in {"JSON", "JSONB"}:
            return "JSON", 1
        return "UNKNOWN", 0

    @staticmethod
    def _missing_object_message(
        *,
        side: str,
        index: int,
        schema_name: object,
        object_name: object,
        truncated: bool,
    ) -> str:
        qualified_name = ".".join(
            part for part in (
                str(schema_name or "").strip(),
                str(object_name or "").strip(),
            )
            if part
        ) or "未填写"
        if truncated:
            return (
                f"第 {index} 条映射的{side}对象“{qualified_name}”未出现在当前有界元数据摘要中。"
                "目录可能被截断，请使用 schemaPattern/tableNamePattern 精确重读后再判断，不能猜测该对象存在。"
            )
        return (
            f"第 {index} 条映射的{side}对象“{qualified_name}”不存在。"
            "请核对 schema/表名，或从已读取的真实对象列表中选择正确对象。"
        )

    @classmethod
    def _inject_derived_arguments(
        cls,
        call: ModelToolCall,
        resource_ledger: dict[str, dict[str, str]],
        resource_reference_groups: dict[str, tuple[dict[str, str], ...]],
    ) -> ModelToolCall:
        """Replace model-supplied derived fields with trusted audit references."""

        try:
            parsed = json.loads(call.arguments or "{}")
        except (TypeError, ValueError, json.JSONDecodeError):
            parsed = {}
        arguments = dict(parsed) if isinstance(parsed, dict) else {}
        if call.name in {
            "datasource.source.connection.test",
            "datasource.target.connection.test",
            "datasource.source.metadata.read",
            "datasource.target.metadata.read",
        }:
            # Data source identity is a control-plane fact.  Even if a provider
            # emits an undeclared datasourceId, only the trusted audit reference
            # below may select the resource used by Java.
            arguments.pop("datasourceId", None)
        if call.name == "sync.cdc.readiness.check":
            # Both endpoint resources are control-plane facts derived from metadata
            # outputs; model-proposed IDs must never survive into the ToolPlan.
            arguments.pop("sourceDatasourceId", None)
            arguments.pop("targetDatasourceId", None)
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
        for argument_name, (source_tool, path) in cls.DERIVED_REFERENCE_GROUPS.get(call.name, {}).items():
            references = resource_reference_groups.get(source_tool, ())
            arguments[argument_name] = [
                {
                    "fromTool": source_tool,
                    "fromAuditId": reference["auditId"],
                    "fromRunId": reference.get("runId"),
                    "path": path or None,
                }
                for reference in references
                if reference.get("auditId")
            ]
        if call.name == "sync.task.draft.save":
            created_target = resource_ledger.get("datasource.target-table.create.apply")
            if created_target is not None and created_target.get("auditId"):
                created_reference = {
                    "fromTool": "datasource.target-table.create.apply",
                    "fromAuditId": created_target["auditId"],
                    "fromRunId": created_target.get("runId"),
                    "path": "metadata",
                }
                # Once a target table was created, the refreshed metadata returned by
                # the apply tool is authoritative; stale pre-create metadata must not
                # be reused by the draft validator or Java adapter.
                arguments["targetMetadataRef"] = created_reference
                arguments["targetMetadataRefs"] = [created_reference]
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

    @classmethod
    def _resource_reference_groups(
        cls,
        plan: AgentPlan,
        feedback: AgentControlPlaneFeedbackSnapshot | None,
    ) -> dict[str, tuple[dict[str, str], ...]]:
        """Preserve every successful same-tool output needed by multi-table flows."""

        grouped: dict[str, list[dict[str, str]]] = {}
        for tool_plan in plan.tool_plans:
            inherited = tool_plan.governance_hints.get("agentLoopResourceRefGroups")
            if not isinstance(inherited, dict):
                continue
            for tool_name, candidates in inherited.items():
                if not isinstance(candidates, (list, tuple)):
                    continue
                for candidate in candidates:
                    normalized = cls._normalized_reference(str(tool_name), candidate)
                    if normalized is not None:
                        cls._append_unique_reference(grouped, str(tool_name), normalized)

        if feedback is not None:
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
                cls._append_unique_reference(grouped, item.tool_name, reference)

        return {
            tool_name: tuple(references)
            for tool_name, references in grouped.items()
        }

    @staticmethod
    def _append_unique_reference(
        grouped: dict[str, list[dict[str, str]]],
        tool_name: str,
        reference: dict[str, str],
    ) -> None:
        references = grouped.setdefault(tool_name, [])
        if any(item.get("auditId") == reference.get("auditId") for item in references):
            return
        references.append(reference)

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
