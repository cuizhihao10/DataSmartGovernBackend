import json
import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.config import default_model_routes, default_tool_registry
from datasmart_ai_runtime.domain.contracts import AgentPlan, AgentRequest, ModelToolCall, ToolPlan
from datasmart_ai_runtime.services.agent_control_plane_feedback import (
    AgentControlPlaneFeedbackItem,
    AgentControlPlaneFeedbackSnapshot,
)
from datasmart_ai_runtime.services.agent_follow_up_tool_planner import AgentFollowUpToolPlanner
from datasmart_ai_runtime.services.model_gateway.model_tool_result_feedback import ToolExecutionFeedbackStatus
from datasmart_ai_runtime.services.tool_planner import ToolPlanner


class AgentFollowUpToolPlannerTest(unittest.TestCase):

    IMMEDIATE_SYNC_LIFECYCLE = (
        "sync.task.draft.save",
        "sync.task.precheck",
        "sync.task.publish",
        "sync.task.run",
        "sync.execution.status",
    )

    def setUp(self) -> None:
        self.tool_planner = ToolPlanner(default_tool_registry())
        self.planner = AgentFollowUpToolPlanner(tool_planner=self.tool_planner)
        self.request = AgentRequest(
            tenant_id="10",
            project_id="101",
            actor_id="1001",
            objective="Create and run a governed full synchronization task.",
        )

    def test_injects_durable_draft_reference_before_schema_validation(self) -> None:
        parent = self._plan(ToolPlan(tool_name="sync.task.draft.save", reason="draft"))
        visible = self._visible("sync.task.precheck")

        result = self.planner.govern(
            request=self.request,
            plan=parent,
            tool_calls=(self._call("call-precheck", "sync.task.precheck", {}),),
            visible_tools=visible,
            control_plane_feedback=self._feedback(
                "sync.task.draft.save", "audit-draft", "run-draft", "call-draft"
            ),
        )

        self.assertTrue(result.continues)
        self.assertEqual(1, result.resource_reference_count)
        plan = result.accepted_tool_plans[0]
        self.assertTrue(plan.parameter_validation.can_execute)
        self.assertEqual(
            {
                "fromTool": "sync.task.draft.save",
                "fromAuditId": "audit-draft",
                "fromRunId": "run-draft",
                "path": "taskId",
            },
            plan.arguments["draftRef"],
        )

    def test_publish_receives_inherited_draft_and_new_precheck_references(self) -> None:
        inherited = {
            "sync.task.draft.save": {
                "toolCode": "sync.task.draft.save",
                "auditId": "audit-draft",
                "runId": "run-draft",
            }
        }
        parent = self._plan(
            ToolPlan(
                tool_name="sync.task.precheck",
                reason="precheck",
                governance_hints={"agentLoopResourceRefs": inherited},
            )
        )

        result = self.planner.govern(
            request=self.request,
            plan=parent,
            tool_calls=(self._call("call-publish", "sync.task.publish", {}),),
            visible_tools=self._visible("sync.task.publish"),
            control_plane_feedback=self._feedback(
                "sync.task.precheck", "audit-precheck", "run-precheck", "call-precheck"
            ),
        )

        self.assertEqual(("sync.task.publish",), tuple(
            plan.tool_name for plan in result.accepted_tool_plans
        ))
        arguments = result.accepted_tool_plans[0].arguments
        self.assertEqual("audit-draft", arguments["draftRef"]["fromAuditId"])
        self.assertEqual("taskId", arguments["draftRef"]["path"])
        self.assertEqual("audit-precheck", arguments["precheckRef"]["fromAuditId"])
        self.assertEqual("canStartExecution", arguments["precheckRef"]["path"])

    def test_model_cannot_override_platform_derived_reference(self) -> None:
        parent = self._plan(ToolPlan(tool_name="sync.task.draft.save", reason="draft"))
        malicious = {
            "draftRef": {
                "fromTool": "sync.task.draft.save",
                "fromAuditId": "audit-from-model",
                "path": "taskId",
            }
        }

        result = self.planner.govern(
            request=self.request,
            plan=parent,
            tool_calls=(self._call("call-precheck", "sync.task.precheck", malicious),),
            visible_tools=self._visible("sync.task.precheck"),
            control_plane_feedback=self._feedback(
                "sync.task.draft.save", "audit-platform", "run-draft", "call-draft"
            ),
        )

        self.assertEqual(
            "audit-platform",
            result.accepted_tool_plans[0].arguments["draftRef"]["fromAuditId"],
        )

    def test_failed_import_dry_run_requires_rag_before_repair_and_blocks_commit(self) -> None:
        parent = self._plan(ToolPlan(tool_name="sync.task.import.dry-run", reason="dry-run"))
        calls = (
            self._call("call-rag", "sync.task.import.rag.lookup", {}),
            self._call(
                "call-repair",
                "sync.task.import.repair.apply",
                {"patches": [{"rowNumber": 2, "columnName": "name", "replacementValue": "fixed"}]},
            ),
            self._call("call-commit", "sync.task.import.commit", {"runImmediately": False}),
        )

        result = self.planner.govern(
            request=self.request,
            plan=parent,
            tool_calls=calls,
            visible_tools=self._visible(
                "sync.task.import.rag.lookup",
                "sync.task.import.repair.apply",
                "sync.task.import.commit",
            ),
            control_plane_feedback=self._feedback(
                "sync.task.import.dry-run",
                "audit-dry-run",
                "run-dry-run",
                "call-dry-run",
                result={"repairRequired": True},
            ),
        )

        self.assertEqual(("sync.task.import.rag.lookup",), tuple(
            plan.tool_name for plan in result.accepted_tool_plans
        ))
        self.assertEqual(2, result.state_guard_rejected_count)
        self.assertEqual("model_summary_allowed", result.accepted_tool_plans[0].governance_hints["outputContextPolicy"])

    def test_rag_feedback_allows_model_to_propose_confirmed_repair(self) -> None:
        parent = self._plan(ToolPlan(
            tool_name="sync.task.import.rag.lookup",
            reason="evidence",
            governance_hints={
                "agentLoopResourceRefs": {
                    "sync.task.import.dry-run": {
                        "toolCode": "sync.task.import.dry-run",
                        "auditId": "audit-dry-run",
                        "runId": "run-dry-run",
                    }
                }
            },
        ))
        result = self.planner.govern(
            request=self.request,
            plan=parent,
            tool_calls=(self._call(
                "call-repair",
                "sync.task.import.repair.apply",
                {"patches": [{"rowNumber": 2, "columnName": "name", "replacementValue": "fixed"}]},
            ),),
            visible_tools=self._visible("sync.task.import.repair.apply"),
            control_plane_feedback=self._feedback(
                "sync.task.import.rag.lookup",
                "audit-rag",
                "run-rag",
                "call-rag",
                result={"answer": "Use a unique task name."},
            ),
        )

        self.assertEqual(("sync.task.import.repair.apply",), tuple(
            plan.tool_name for plan in result.accepted_tool_plans
        ))
        self.assertEqual(0, result.state_guard_rejected_count)

    def test_validated_import_dry_run_allows_commit_and_blocks_repair(self) -> None:
        parent = self._plan(ToolPlan(tool_name="sync.task.import.dry-run", reason="dry-run"))
        calls = (
            self._call(
                "call-repair",
                "sync.task.import.repair.apply",
                {"patches": [{"rowNumber": 2, "columnName": "name", "replacementValue": "unused"}]},
            ),
            self._call("call-commit", "sync.task.import.commit", {"runImmediately": True}),
        )

        result = self.planner.govern(
            request=self.request,
            plan=parent,
            tool_calls=calls,
            visible_tools=self._visible("sync.task.import.repair.apply", "sync.task.import.commit"),
            control_plane_feedback=self._feedback(
                "sync.task.import.dry-run",
                "audit-dry-run",
                "run-dry-run",
                "call-dry-run",
                result={"repairRequired": False},
            ),
        )

        self.assertEqual(("sync.task.import.commit",), tuple(
            plan.tool_name for plan in result.accepted_tool_plans
        ))
        self.assertEqual(1, result.state_guard_rejected_count)

    def test_recovery_mutation_is_blocked_until_rag_evidence_succeeds(self) -> None:
        parent = self._plan(ToolPlan(
            tool_name="sync.execution.diagnose",
            reason="diagnosis",
            governance_hints={
                "agentLoopResourceRefs": {
                    "sync.execution.diagnose": {
                        "toolCode": "sync.execution.diagnose",
                        "auditId": "audit-diagnosis",
                        "runId": "run-recovery",
                    }
                }
            },
        ))
        result = self.planner.govern(
            request=self.request,
            plan=parent,
            tool_calls=(self._call(
                "call-retry",
                "sync.execution.failed-objects.retry",
                {"retryAttemptBudget": 2},
            ),),
            visible_tools=self._visible("sync.execution.failed-objects.retry"),
            control_plane_feedback=self._feedback(
                "sync.execution.diagnose",
                "audit-diagnosis",
                "run-recovery",
                "call-diagnosis",
                result={"rootCauseCodes": ["TARGET_COLUMN_TOO_NARROW"]},
            ),
        )

        self.assertEqual((), result.accepted_tool_plans)
        self.assertEqual(1, result.state_guard_rejected_count)

    def test_schema_apply_requires_prior_preview_and_keeps_server_reference(self) -> None:
        inherited = {
            "sync.execution.diagnose": {
                "toolCode": "sync.execution.diagnose",
                "auditId": "audit-diagnosis",
                "runId": "run-recovery",
            },
            "sync.execution.rag.lookup": {
                "toolCode": "sync.execution.rag.lookup",
                "auditId": "audit-rag",
                "runId": "run-recovery",
            },
        }
        parent = self._plan(ToolPlan(
            tool_name="datasource.schema.repair.preview",
            reason="preview",
            governance_hints={"agentLoopResourceRefs": inherited},
        ))
        result = self.planner.govern(
            request=self.request,
            plan=parent,
            tool_calls=(self._call(
                "call-schema-apply",
                "datasource.schema.repair.apply",
                {"previewRef": {"fromAuditId": "model-forged"}},
            ),),
            visible_tools=self._visible("datasource.schema.repair.apply"),
            control_plane_feedback=self._feedback(
                "datasource.schema.repair.preview",
                "audit-schema-preview",
                "run-recovery",
                "call-schema-preview",
                result={"planStatus": "PREVIEWED", "requiresConfirmation": True},
            ),
        )

        self.assertEqual(("datasource.schema.repair.apply",), tuple(
            plan.tool_name for plan in result.accepted_tool_plans
        ))
        preview_ref = result.accepted_tool_plans[0].arguments["previewRef"]
        self.assertEqual("audit-schema-preview", preview_ref["fromAuditId"])
        self.assertIsNone(preview_ref["path"])

    def test_target_table_preview_uses_trusted_source_and_target_metadata_references(self) -> None:
        parent = self._plan(ToolPlan(
            tool_name="datasource.target.metadata.read",
            reason="target metadata",
            governance_hints={
                "agentLoopResourceRefs": {
                    "datasource.source.metadata.read": {
                        "toolCode": "datasource.source.metadata.read",
                        "auditId": "audit-source",
                        "runId": "run-metadata",
                    }
                }
            },
        ))

        result = self.planner.govern(
            request=self.request,
            plan=parent,
            tool_calls=(self._call(
                "call-create-preview",
                "datasource.target-table.create.preview",
                {
                    "sourceMetadataRef": {"fromAuditId": "model-forged-source"},
                    "targetMetadataRef": {"fromAuditId": "model-forged-target"},
                    "sourceTableName": "customer",
                    "targetSchemaName": "public",
                    "targetTableName": "customer",
                },
            ),),
            visible_tools=self._visible("datasource.target-table.create.preview"),
            control_plane_feedback=self._feedback(
                "datasource.target.metadata.read",
                "audit-target",
                "run-metadata",
                "call-target",
                result={"summary": {"objects": []}},
            ),
        )

        self.assertEqual(1, len(result.accepted_tool_plans))
        arguments = result.accepted_tool_plans[0].arguments
        self.assertEqual("audit-source", arguments["sourceMetadataRef"]["fromAuditId"])
        self.assertEqual("audit-target", arguments["targetMetadataRef"]["fromAuditId"])

    def test_target_table_apply_keeps_digest_preview_reference(self) -> None:
        parent = self._plan(ToolPlan(
            tool_name="datasource.target-table.create.preview",
            reason="preview target table",
        ))
        result = self.planner.govern(
            request=self.request,
            plan=parent,
            tool_calls=(self._call(
                "call-create-apply",
                "datasource.target-table.create.apply",
                {"previewRef": {"fromAuditId": "model-forged"}},
            ),),
            visible_tools=self._visible("datasource.target-table.create.apply"),
            control_plane_feedback=self._feedback(
                "datasource.target-table.create.preview",
                "audit-create-preview",
                "run-create",
                "call-create-preview",
                result={"planStatus": "PREVIEWED", "requiresConfirmation": True},
            ),
        )

        self.assertEqual(1, len(result.accepted_tool_plans))
        preview_ref = result.accepted_tool_plans[0].arguments["previewRef"]
        self.assertEqual("datasource.target-table.create.preview", preview_ref["fromTool"])
        self.assertEqual("audit-create-preview", preview_ref["fromAuditId"])

    def test_sync_draft_uses_refreshed_metadata_after_target_table_creation(self) -> None:
        parent = self._plan(ToolPlan(
            tool_name="datasource.target-table.create.apply",
            reason="created target table",
        ))
        source_summary = {
            "truncated": False,
            "objects": [{
                "schemaName": None,
                "tableName": "customer",
                "columns": [{"columnName": "id", "dataTypeName": "BIGINT"}],
            }],
        }
        created_target_summary = {
            "truncated": False,
            "objects": [{
                "schemaName": "public",
                "tableName": "customer_target",
                "columns": [{"columnName": "id", "dataTypeName": "BIGINT"}],
            }],
        }
        feedback = AgentControlPlaneFeedbackSnapshot(
            expected_tool_call_count=3,
            feedback_items=(
                AgentControlPlaneFeedbackItem(
                    model_tool_call_id="call-source",
                    tool_name="datasource.source.metadata.read",
                    status=ToolExecutionFeedbackStatus.SUCCEEDED,
                    summary="source metadata",
                    result={"summary": source_summary},
                    audit_id="audit-source",
                    run_id="run-create",
                ),
                AgentControlPlaneFeedbackItem(
                    model_tool_call_id="call-target-old",
                    tool_name="datasource.target.metadata.read",
                    status=ToolExecutionFeedbackStatus.SUCCEEDED,
                    summary="target table absent",
                    result={"summary": {"truncated": False, "objects": []}},
                    audit_id="audit-target-old",
                    run_id="run-create",
                ),
                AgentControlPlaneFeedbackItem(
                    model_tool_call_id="call-create-apply",
                    tool_name="datasource.target-table.create.apply",
                    status=ToolExecutionFeedbackStatus.SUCCEEDED,
                    summary="target table created",
                    result={"summary": created_target_summary},
                    audit_id="audit-create-apply",
                    run_id="run-create",
                ),
            ),
            missing_tool_call_ids=(),
            status_counts={"succeeded": 3},
            second_turn_eligible=True,
            recommended_actions=(),
        )

        result = self.planner.govern(
            request=self.request,
            plan=parent,
            tool_calls=(self._call(
                "call-draft-after-create",
                "sync.task.draft.save",
                {
                    "taskName": "customer-full",
                    "syncMode": "FULL",
                    "writeStrategy": "INSERT",
                    "objectMappings": [{
                        "sourceObjectName": "customer",
                        "targetSchemaName": "public",
                        "targetObjectName": "customer_target",
                        "fieldMappings": [{
                            "sourceField": "id",
                            "targetField": "id",
                            "syncEnabled": True,
                        }],
                    }],
                },
            ),),
            visible_tools=self._visible("sync.task.draft.save"),
            control_plane_feedback=feedback,
        )

        self.assertEqual(self.IMMEDIATE_SYNC_LIFECYCLE, tuple(
            plan.tool_name for plan in result.accepted_tool_plans
        ))
        arguments = result.accepted_tool_plans[0].arguments
        self.assertEqual(
            "datasource.target-table.create.apply",
            arguments["targetMetadataRef"]["fromTool"],
        )
        self.assertEqual("audit-create-apply", arguments["targetMetadataRef"]["fromAuditId"])
        self.assertEqual(
            ["audit-create-apply"],
            [item["fromAuditId"] for item in arguments["targetMetadataRefs"]],
        )

    def test_recovery_case_is_published_only_after_successful_validation(self) -> None:
        parent = self._plan(ToolPlan(
            tool_name="sync.execution.status",
            reason="validation",
            governance_hints={
                "agentLoopResourceRefs": {
                    "sync.execution.diagnose": {
                        "toolCode": "sync.execution.diagnose",
                        "auditId": "audit-diagnosis",
                        "runId": "run-recovery",
                    }
                }
            },
        ))
        result = self.planner.govern(
            request=self.request,
            plan=parent,
            tool_calls=(self._call("call-case", "sync.recovery.case.publish", {}),),
            visible_tools=self._visible("sync.recovery.case.publish"),
            control_plane_feedback=self._feedback(
                "sync.execution.status",
                "audit-validation",
                "run-recovery",
                "call-status",
                result={"executionState": "SUCCEEDED", "failedRecordCount": 0},
            ),
        )

        self.assertEqual(1, len(result.accepted_tool_plans))
        arguments = result.accepted_tool_plans[0].arguments
        self.assertEqual("audit-diagnosis", arguments["diagnosisRef"]["fromAuditId"])
        self.assertEqual("audit-validation", arguments["validationRef"]["fromAuditId"])

    def test_catalog_exact_match_replaces_model_datasource_id_with_trusted_reference(self) -> None:
        parent = self._plan(ToolPlan(
            tool_name="datasource.source.catalog.search",
            reason="resolve source",
        ))
        feedback = self._feedback(
            "datasource.source.catalog.search",
            "audit-catalog",
            "run-catalog",
            "call-catalog",
            result={
                "matchStatus": "EXACT",
                "resolvedDatasourceId": 27,
                "requiresUserChoice": False,
            },
        )

        accepted = self.planner.govern(
            request=self.request,
            plan=parent,
            tool_calls=(self._call(
                "call-connection",
                "datasource.source.connection.test",
                {"datasourceId": 27},
            ),),
            visible_tools=self._visible("datasource.source.connection.test"),
            control_plane_feedback=feedback,
        )
        forged = self.planner.govern(
            request=self.request,
            plan=parent,
            tool_calls=(self._call(
                "call-forged",
                "datasource.source.connection.test",
                {"datasourceId": 28},
            ),),
            visible_tools=self._visible("datasource.source.connection.test"),
            control_plane_feedback=feedback,
        )

        self.assertEqual(1, len(accepted.accepted_tool_plans))
        self.assertEqual(1, len(forged.accepted_tool_plans))
        for result in (accepted, forged):
            arguments = result.accepted_tool_plans[0].arguments
            self.assertNotIn("datasourceId", arguments)
            self.assertEqual(
                {
                    "fromTool": "datasource.source.catalog.search",
                    "fromAuditId": "audit-catalog",
                    "fromRunId": "run-catalog",
                    "path": "resolvedDatasourceId",
                },
                arguments["catalogSearchRef"],
            )
            self.assertEqual(0, result.state_guard_rejected_count)

    def test_metadata_requires_matching_connection_evidence(self) -> None:
        parent = self._plan(ToolPlan(
            tool_name="datasource.source.catalog.search",
            reason="catalog only",
        ))

        result = self.planner.govern(
            request=self.request,
            plan=parent,
            tool_calls=(self._call(
                "call-metadata",
                "datasource.source.metadata.read",
                {"datasourceId": 27, "tableNamePattern": "customer"},
            ),),
            visible_tools=self._visible("datasource.source.metadata.read"),
            control_plane_feedback=self._feedback(
                "datasource.source.catalog.search",
                "audit-catalog",
                "run-catalog",
                "call-catalog",
                result={
                    "matchStatus": "EXACT",
                    "resolvedDatasourceId": 27,
                },
            ),
        )

        self.assertEqual((), result.accepted_tool_plans)
        self.assertIn(
            "MODEL_TOOL_CALL_DATASOURCE_CONNECTION_EVIDENCE_REQUIRED",
            result.state_guard_issue_codes,
        )

    def test_metadata_inherits_datasource_identity_from_connection_evidence(self) -> None:
        parent = self._plan(ToolPlan(
            tool_name="datasource.target.connection.test",
            reason="target connection",
        ))

        result = self.planner.govern(
            request=self.request,
            plan=parent,
            tool_calls=(self._call(
                "call-metadata",
                "datasource.target.metadata.read",
                {"datasourceId": 27, "tableNamePattern": "customer"},
            ),),
            visible_tools=self._visible("datasource.target.metadata.read"),
            control_plane_feedback=self._feedback(
                "datasource.target.connection.test",
                "audit-connection",
                "run-connection",
                "call-connection",
                result={"datasourceId": 28, "success": True},
            ),
        )

        self.assertEqual(1, len(result.accepted_tool_plans))
        arguments = result.accepted_tool_plans[0].arguments
        self.assertNotIn("datasourceId", arguments)
        self.assertEqual(
            {
                "fromTool": "datasource.target.connection.test",
                "fromAuditId": "audit-connection",
                "fromRunId": "run-connection",
                "path": "datasourceId",
            },
            arguments["connectionTestRef"],
        )

    def test_catalog_ambiguous_match_requires_user_selection(self) -> None:
        parent = self._plan(ToolPlan(
            tool_name="datasource.target.catalog.search",
            reason="resolve target",
        ))
        result = self.planner.govern(
            request=self.request,
            plan=parent,
            tool_calls=(self._call(
                "call-connection",
                "datasource.target.connection.test",
                {"datasourceId": 41},
            ),),
            visible_tools=self._visible("datasource.target.connection.test"),
            control_plane_feedback=self._feedback(
                "datasource.target.catalog.search",
                "audit-catalog",
                "run-catalog",
                "call-catalog",
                result={
                    "matchStatus": "AMBIGUOUS",
                    "candidateCount": 2,
                    "requiresUserChoice": True,
                },
            ),
        )

        self.assertEqual((), result.accepted_tool_plans)
        self.assertEqual(1, result.state_guard_rejected_count)

    def test_sync_draft_rejects_target_object_not_present_in_real_metadata(self) -> None:
        parent = self._plan(ToolPlan(
            tool_name="datasource.target.metadata.read",
            reason="target metadata",
        ))
        result = self.planner.govern(
            request=self.request,
            plan=parent,
            tool_calls=(self._call(
                "call-draft",
                "sync.task.draft.save",
                {
                    "taskName": "customer-full",
                    "sourceDatasourceId": 27,
                    "targetDatasourceId": 28,
                    "syncMode": "FULL",
                    "writeStrategy": "INSERT",
                    "objectMappings": [{
                        "sourceObjectName": "customer",
                        "targetSchemaName": "public",
                        "targetObjectName": "customer_missing",
                        "fieldMappings": [{
                            "sourceField": "id",
                            "targetField": "id",
                            "syncEnabled": True,
                        }],
                    }],
                },
            ),),
            visible_tools=self._visible("sync.task.draft.save"),
            control_plane_feedback=self._metadata_feedback(),
        )

        self.assertEqual((), result.accepted_tool_plans)
        self.assertEqual(1, result.state_guard_rejected_count)
        self.assertIn(
            "MODEL_TOOL_CALL_TARGET_OBJECT_NOT_IN_METADATA",
            result.state_guard_issue_codes,
        )
        self.assertIn("customer_missing", result.state_guard_issue_messages[0])

    def test_sync_draft_rejects_zero_enabled_field_mappings(self) -> None:
        parent = self._plan(ToolPlan(
            tool_name="datasource.target.metadata.read",
            reason="target metadata",
        ))
        result = self.planner.govern(
            request=self.request,
            plan=parent,
            tool_calls=(self._call(
                "call-draft-without-fields",
                "sync.task.draft.save",
                {
                    "taskName": "customer-full",
                    "syncMode": "FULL",
                    "objectMappings": [{
                        "sourceObjectName": "customer",
                        "targetSchemaName": "public",
                        "targetObjectName": "customer",
                        "fieldMappings": [],
                    }],
                },
            ),),
            visible_tools=self._visible("sync.task.draft.save"),
            control_plane_feedback=self._metadata_feedback(),
        )

        self.assertEqual((), result.accepted_tool_plans)
        self.assertIn(
            "MODEL_TOOL_CALL_SYNC_FIELD_MAPPING_MISSING",
            result.state_guard_issue_codes,
        )
        self.assertIn("至少一个", result.state_guard_issue_messages[0])

    def test_sync_draft_rejects_implicit_incompatible_field_type_conversion(self) -> None:
        parent = self._plan(ToolPlan(
            tool_name="datasource.target.metadata.read",
            reason="target metadata",
        ))
        feedback = self._metadata_feedback_with_types("VARCHAR", "BIGINT")

        result = self.planner.govern(
            request=self.request,
            plan=parent,
            tool_calls=(self._call(
                "call-draft",
                "sync.task.draft.save",
                {
                    "taskName": "customer-full",
                    "syncMode": "FULL",
                    "objectMappings": [{
                        "sourceObjectName": "customer",
                        "targetSchemaName": "public",
                        "targetObjectName": "customer",
                        "fieldMappings": [{
                            "sourceField": "id",
                            "targetField": "id",
                            "syncEnabled": True,
                        }],
                    }],
                },
            ),),
            visible_tools=self._visible("sync.task.draft.save"),
            control_plane_feedback=feedback,
        )

        self.assertEqual((), result.accepted_tool_plans)
        self.assertIn("MODEL_TOOL_CALL_FIELD_TYPE_INCOMPATIBLE", result.state_guard_issue_codes)
        self.assertIn("VARCHAR", result.state_guard_issue_messages[0])
        self.assertIn("BIGINT", result.state_guard_issue_messages[0])

    def test_sync_draft_accepts_explicit_transform_for_incompatible_types(self) -> None:
        parent = self._plan(ToolPlan(
            tool_name="datasource.target.metadata.read",
            reason="target metadata",
        ))

        result = self.planner.govern(
            request=self.request,
            plan=parent,
            tool_calls=(self._call(
                "call-draft",
                "sync.task.draft.save",
                {
                    "taskName": "customer-full",
                    "syncMode": "FULL",
                    "objectMappings": [{
                        "sourceObjectName": "customer",
                        "targetSchemaName": "public",
                        "targetObjectName": "customer",
                        "fieldMappings": [{
                            "sourceField": "id",
                            "targetField": "id",
                            "syncEnabled": True,
                            "transform": "CAST_TO_BIGINT",
                        }],
                    }],
                },
            ),),
            visible_tools=self._visible("sync.task.draft.save"),
            control_plane_feedback=self._metadata_feedback_with_types("VARCHAR", "BIGINT"),
        )

        self.assertEqual(self.IMMEDIATE_SYNC_LIFECYCLE, tuple(
            plan.tool_name for plan in result.accepted_tool_plans
        ))

    def test_sync_draft_accepts_objects_and_fields_verified_by_real_metadata(self) -> None:
        parent = self._plan(ToolPlan(
            tool_name="datasource.target.metadata.read",
            reason="target metadata",
        ))
        result = self.planner.govern(
            request=self.request,
            plan=parent,
            tool_calls=(self._call(
                "call-draft",
                "sync.task.draft.save",
                {
                    "taskName": "customer-full",
                    "sourceDatasourceId": 27,
                    "targetDatasourceId": 28,
                    "syncMode": "FULL",
                    "writeStrategy": "INSERT",
                    "objectMappings": [{
                        "sourceObjectName": "customer",
                        "targetSchemaName": "public",
                        "targetObjectName": "customer",
                        "fieldMappings": [{
                            "sourceField": "id",
                            "targetField": "id",
                            "syncEnabled": True,
                        }],
                    }],
                },
            ),),
            visible_tools=self._visible("sync.task.draft.save"),
            control_plane_feedback=self._metadata_feedback(),
        )

        self.assertEqual(self.IMMEDIATE_SYNC_LIFECYCLE, tuple(
            plan.tool_name for plan in result.accepted_tool_plans
        ))
        self.assertEqual(0, result.state_guard_rejected_count)
        self.assertEqual(self.IMMEDIATE_SYNC_LIFECYCLE[1:], result.platform_expanded_tool_names)
        self.assertEqual(
            "platform_sync_lifecycle_expansion",
            result.accepted_tool_plans[1].governance_hints["source"],
        )
        self.assertNotIn("modelToolCallId", result.accepted_tool_plans[1].governance_hints)

    def test_sync_draft_accepts_two_source_tables_collected_by_separate_metadata_reads(self) -> None:
        parent = self._plan(ToolPlan(
            tool_name="datasource.target.metadata.read",
            reason="target metadata",
        ))
        source_table_names = ("fs_test_customer_source", "fs_test_customer_target")
        source_feedback_items = tuple(
            AgentControlPlaneFeedbackItem(
                model_tool_call_id=f"call-source-{index}",
                tool_name="datasource.source.metadata.read",
                status=ToolExecutionFeedbackStatus.SUCCEEDED,
                summary=f"source metadata {index} succeeded",
                result={
                    "summary": {
                        "truncated": index == 1,
                        "objects": [{
                            "schemaName": None,
                            "tableName": table_name,
                            "columns": [{"columnName": "id", "dataTypeName": "BIGINT"}],
                        }],
                    }
                },
                audit_id=f"audit-source-{index}",
                run_id=f"run-source-{index}",
            )
            for index, table_name in enumerate(source_table_names, start=1)
        )
        target_feedback = AgentControlPlaneFeedbackItem(
            model_tool_call_id="call-target",
            tool_name="datasource.target.metadata.read",
            status=ToolExecutionFeedbackStatus.SUCCEEDED,
            summary="target metadata succeeded",
            result={
                "summary": {
                    "truncated": False,
                    "objects": [
                        {
                            "schemaName": "public",
                            "tableName": table_name,
                            "columns": [{"columnName": "id", "dataTypeName": "BIGINT"}],
                        }
                        for table_name in source_table_names
                    ],
                }
            },
            audit_id="audit-target",
            run_id="run-target",
        )
        feedback = AgentControlPlaneFeedbackSnapshot(
            expected_tool_call_count=3,
            feedback_items=(*source_feedback_items, target_feedback),
            missing_tool_call_ids=(),
            status_counts={"succeeded": 3},
            second_turn_eligible=True,
            recommended_actions=(),
        )

        result = self.planner.govern(
            request=self.request,
            plan=parent,
            tool_calls=(self._call(
                "call-draft",
                "sync.task.draft.save",
                {
                    "taskName": "two-table-full-sync",
                    "sourceDatasourceId": 27,
                    "targetDatasourceId": 28,
                    "syncMode": "FULL",
                    "writeStrategy": "INSERT",
                    "objectMappings": [
                        {
                            "sourceObjectName": table_name,
                            "targetSchemaName": "public",
                            "targetObjectName": table_name,
                            "fieldMappings": [{
                                "sourceField": "id",
                                "targetField": "id",
                                "syncEnabled": True,
                            }],
                        }
                        for table_name in source_table_names
                    ],
                },
            ),),
            visible_tools=self._visible("sync.task.draft.save"),
            control_plane_feedback=feedback,
        )

        self.assertEqual(self.IMMEDIATE_SYNC_LIFECYCLE, tuple(
            plan.tool_name for plan in result.accepted_tool_plans
        ))
        self.assertEqual(0, result.state_guard_rejected_count)

    def test_sync_draft_preserves_all_metadata_reference_groups(self) -> None:
        inherited_ledger = {
            "datasource.source.metadata.read": {
                "toolCode": "datasource.source.metadata.read",
                "auditId": "audit-source-b",
                "runId": "run-source-b",
            }
        }
        inherited_groups = {
            "datasource.source.metadata.read": (
                {
                    "toolCode": "datasource.source.metadata.read",
                    "auditId": "audit-source-a",
                    "runId": "run-source-a",
                },
                {
                    "toolCode": "datasource.source.metadata.read",
                    "auditId": "audit-source-b",
                    "runId": "run-source-b",
                },
            )
        }
        parent = self._plan(ToolPlan(
            tool_name="datasource.target.metadata.read",
            reason="target metadata",
            governance_hints={
                "agentLoopResourceRefs": inherited_ledger,
                "agentLoopResourceRefGroups": inherited_groups,
            },
        ))
        feedback = self._feedback(
            "datasource.target.metadata.read",
            "audit-target",
            "run-target",
            "call-target",
            result={"summary": {"objects": []}},
        )

        result = self.planner.govern(
            request=self.request,
            plan=parent,
            tool_calls=(self._call(
                "call-draft",
                "sync.task.draft.save",
                {
                    "taskName": "customer-full",
                    "syncMode": "FULL",
                    "writeStrategy": "INSERT",
                    "objectMappings": [{
                        "sourceObjectName": "customer",
                        "targetSchemaName": "public",
                        "targetObjectName": "customer",
                    }],
                },
            ),),
            visible_tools=self._visible("sync.task.draft.save"),
            control_plane_feedback=feedback,
        )

        self.assertEqual(self.IMMEDIATE_SYNC_LIFECYCLE, tuple(
            plan.tool_name for plan in result.accepted_tool_plans
        ))
        arguments = result.accepted_tool_plans[0].arguments
        self.assertEqual(
            ["audit-source-a", "audit-source-b"],
            [item["fromAuditId"] for item in arguments["sourceMetadataRefs"]],
        )
        self.assertEqual(
            ["audit-target"],
            [item["fromAuditId"] for item in arguments["targetMetadataRefs"]],
        )
        self.assertEqual("audit-source-b", arguments["sourceMetadataRef"]["fromAuditId"])
        self.assertEqual("audit-target", arguments["targetMetadataRef"]["fromAuditId"])
        self.assertTrue(result.accepted_tool_plans[0].parameter_validation.can_execute)

    def test_scheduled_sync_draft_expands_to_publish_without_immediate_run(self) -> None:
        """Scheduled modes must publish a schedule but must not enqueue an immediate execution."""

        parent = self._plan(ToolPlan(
            tool_name="datasource.target.metadata.read",
            reason="target metadata",
        ))
        result = self.planner.govern(
            request=self.request,
            plan=parent,
            tool_calls=(self._call(
                "call-scheduled-draft",
                "sync.task.draft.save",
                {
                    "taskName": "customer-scheduled-full",
                    "syncMode": "SCHEDULED_FULL",
                    "writeStrategy": "UPDATE",
                    "scheduleConfig": '{"cron":"0 0 * * * ?","timezone":"Asia/Shanghai"}',
                    "objectMappings": [{
                        "sourceObjectName": "customer",
                        "targetSchemaName": "public",
                        "targetObjectName": "customer",
                        "fieldMappings": [{
                            "sourceField": "id",
                            "targetField": "id",
                            "syncEnabled": True,
                        }],
                    }],
                },
            ),),
            visible_tools=self._visible("sync.task.draft.save"),
            control_plane_feedback=self._metadata_feedback(),
        )

        self.assertEqual(
            ("sync.task.draft.save", "sync.task.precheck", "sync.task.publish"),
            tuple(plan.tool_name for plan in result.accepted_tool_plans),
        )
        publish = result.accepted_tool_plans[-1]
        self.assertTrue(publish.arguments["enableSchedule"])
        self.assertEqual("SCHEDULED_FULL", publish.arguments["syncMode"])

    def test_cdc_readiness_check_inherits_trusted_metadata_references(self) -> None:
        parent = self._plan(ToolPlan(
            tool_name="datasource.target.metadata.read",
            reason="target metadata",
        ))
        result = self.planner.govern(
            request=self.request,
            plan=parent,
            tool_calls=(self._call(
                "call-cdc-readiness",
                "sync.cdc.readiness.check",
                {
                    "sourceDatasourceId": 999,
                    "targetDatasourceId": 999,
                    "objectMappings": [{
                        "sourceObjectName": "customer",
                        "targetSchemaName": "public",
                        "targetObjectName": "customer",
                        "fieldMappings": [{
                            "sourceField": "id",
                            "targetField": "id",
                            "syncEnabled": True,
                        }],
                    }],
                },
            ),),
            visible_tools=self._visible("sync.cdc.readiness.check"),
            control_plane_feedback=self._metadata_feedback(),
        )

        self.assertEqual(("sync.cdc.readiness.check",), tuple(
            plan.tool_name for plan in result.accepted_tool_plans
        ))
        arguments = result.accepted_tool_plans[0].arguments
        self.assertNotIn("sourceDatasourceId", arguments)
        self.assertNotIn("targetDatasourceId", arguments)
        self.assertEqual("audit-source-metadata", arguments["sourceMetadataRef"]["fromAuditId"])
        self.assertEqual("audit-target-metadata", arguments["targetMetadataRef"]["fromAuditId"])

    def test_cdc_draft_is_narrowed_to_readiness_before_any_write(self) -> None:
        parent = self._plan(ToolPlan(
            tool_name="datasource.target.metadata.read",
            reason="target metadata",
        ))
        result = self.planner.govern(
            request=self.request,
            plan=parent,
            tool_calls=(self._call(
                "call-cdc-draft",
                "sync.task.draft.save",
                {
                    "taskName": "customer-cdc",
                    "syncMode": "CDC_STREAMING",
                    "objectMappings": [{
                        "sourceObjectName": "customer",
                        "targetSchemaName": "public",
                        "targetObjectName": "customer",
                        "fieldMappings": [{
                            "sourceField": "id",
                            "targetField": "id",
                            "syncEnabled": True,
                        }],
                    }],
                },
            ),),
            visible_tools=self._visible("sync.task.draft.save"),
            control_plane_feedback=self._metadata_feedback(),
        )

        self.assertEqual(("sync.cdc.readiness.check",), tuple(
            plan.tool_name for plan in result.accepted_tool_plans
        ))
        readiness = result.accepted_tool_plans[0]
        self.assertFalse(readiness.requires_human_approval)
        self.assertEqual(
            "platform_cdc_readiness_prerequisite",
            readiness.governance_hints["source"],
        )
        self.assertEqual("call-cdc-draft", readiness.governance_hints["modelToolCallId"])

    def test_cdc_draft_is_blocked_when_readiness_report_contains_failures(self) -> None:
        metadata = self._metadata_feedback()
        feedback = AgentControlPlaneFeedbackSnapshot(
            expected_tool_call_count=3,
            feedback_items=(*metadata.feedback_items, AgentControlPlaneFeedbackItem(
                model_tool_call_id="call-cdc-readiness",
                tool_name="sync.cdc.readiness.check",
                status=ToolExecutionFeedbackStatus.SUCCEEDED,
                summary="CDC readiness blocked",
                result={
                    "ready": False,
                    "decision": "BLOCKED",
                    "issueCodes": ["CDC_PIPELINE_RUNTIME_NOT_IMPLEMENTED"],
                },
                audit_id="audit-cdc-readiness",
                run_id="run-metadata",
            )),
            missing_tool_call_ids=(),
            status_counts={"succeeded": 3},
            second_turn_eligible=True,
            recommended_actions=(),
        )
        parent = self._plan(ToolPlan(
            tool_name="sync.cdc.readiness.check",
            reason="readiness checked",
        ))
        result = self.planner.govern(
            request=self.request,
            plan=parent,
            tool_calls=(self._call(
                "call-cdc-draft",
                "sync.task.draft.save",
                {
                    "taskName": "customer-cdc",
                    "syncMode": "CDC_STREAMING",
                    "objectMappings": [{
                        "sourceObjectName": "customer",
                        "targetSchemaName": "public",
                        "targetObjectName": "customer",
                        "fieldMappings": [{
                            "sourceField": "id",
                            "targetField": "id",
                            "syncEnabled": True,
                        }],
                    }],
                },
            ),),
            visible_tools=self._visible("sync.task.draft.save"),
            control_plane_feedback=feedback,
        )

        self.assertEqual((), result.accepted_tool_plans)
        self.assertIn("MODEL_TOOL_CALL_CDC_READINESS_BLOCKED", result.state_guard_issue_codes)
        self.assertIn("CDC_PIPELINE_RUNTIME_NOT_IMPLEMENTED", result.state_guard_issue_messages[0])

    def _visible(self, *names: str):
        by_name = {tool.name: tool for tool in default_tool_registry()}
        return tuple(by_name[name] for name in names)

    @staticmethod
    def _call(call_id: str, name: str, arguments: dict[str, object]) -> ModelToolCall:
        return ModelToolCall(
            call_id=call_id,
            name=name,
            arguments=json.dumps(arguments),
            raw_call={"source": "test"},
        )

    @staticmethod
    def _plan(tool_plan: ToolPlan) -> AgentPlan:
        return AgentPlan(
            request_id="request-1",
            selected_route=default_model_routes()[0],
            state_trace=("plan_tools",),
            tool_plans=(tool_plan,),
            requires_human_approval=False,
            response_summary="test",
        )

    @staticmethod
    def _feedback(
        tool_name: str,
        audit_id: str,
        run_id: str,
        call_id: str,
        *,
        result: dict[str, object] | None = None,
    ):
        return AgentControlPlaneFeedbackSnapshot(
            expected_tool_call_count=1,
            feedback_items=(
                AgentControlPlaneFeedbackItem(
                    model_tool_call_id=call_id,
                    tool_name=tool_name,
                    status=ToolExecutionFeedbackStatus.SUCCEEDED,
                    summary="succeeded",
                    result=result or {},
                    audit_id=audit_id,
                    run_id=run_id,
                    output_ref=f"agent-runtime://tool-results/{audit_id}",
                ),
            ),
            missing_tool_call_ids=(),
            status_counts={"succeeded": 1},
            second_turn_eligible=True,
            recommended_actions=(),
        )

    @staticmethod
    def _metadata_feedback() -> AgentControlPlaneFeedbackSnapshot:
        source_summary = {
            "truncated": False,
            "objects": [{
                "schemaName": None,
                "tableName": "customer",
                "columns": [{"columnName": "id", "dataTypeName": "BIGINT"}],
            }],
        }
        target_summary = {
            "truncated": False,
            "objects": [{
                "schemaName": "public",
                "tableName": "customer",
                "columns": [{"columnName": "id", "dataTypeName": "BIGINT"}],
            }],
        }
        return AgentControlPlaneFeedbackSnapshot(
            expected_tool_call_count=2,
            feedback_items=(
                AgentControlPlaneFeedbackItem(
                    model_tool_call_id="call-source-metadata",
                    tool_name="datasource.source.metadata.read",
                    status=ToolExecutionFeedbackStatus.SUCCEEDED,
                    summary="source metadata succeeded",
                    result={"summary": source_summary},
                    audit_id="audit-source-metadata",
                    run_id="run-metadata",
                ),
                AgentControlPlaneFeedbackItem(
                    model_tool_call_id="call-target-metadata",
                    tool_name="datasource.target.metadata.read",
                    status=ToolExecutionFeedbackStatus.SUCCEEDED,
                    summary="target metadata succeeded",
                    result={"summary": target_summary},
                    audit_id="audit-target-metadata",
                    run_id="run-metadata",
                ),
            ),
            missing_tool_call_ids=(),
            status_counts={"succeeded": 2},
            second_turn_eligible=True,
            recommended_actions=(),
        )

    @staticmethod
    def _metadata_feedback_with_types(
        source_type: str,
        target_type: str,
    ) -> AgentControlPlaneFeedbackSnapshot:
        source_summary = {
            "truncated": False,
            "objects": [{
                "schemaName": None,
                "tableName": "customer",
                "columns": [{"columnName": "id", "dataTypeName": source_type}],
            }],
        }
        target_summary = {
            "truncated": False,
            "objects": [{
                "schemaName": "public",
                "tableName": "customer",
                "columns": [{"columnName": "id", "dataTypeName": target_type}],
            }],
        }
        return AgentControlPlaneFeedbackSnapshot(
            expected_tool_call_count=2,
            feedback_items=(
                AgentControlPlaneFeedbackItem(
                    model_tool_call_id="call-source-metadata",
                    tool_name="datasource.source.metadata.read",
                    status=ToolExecutionFeedbackStatus.SUCCEEDED,
                    summary="source metadata succeeded",
                    result={"summary": source_summary},
                    audit_id="audit-source-metadata",
                    run_id="run-metadata",
                ),
                AgentControlPlaneFeedbackItem(
                    model_tool_call_id="call-target-metadata",
                    tool_name="datasource.target.metadata.read",
                    status=ToolExecutionFeedbackStatus.SUCCEEDED,
                    summary="target metadata succeeded",
                    result={"summary": target_summary},
                    audit_id="audit-target-metadata",
                    run_id="run-metadata",
                ),
            ),
            missing_tool_call_ids=(),
            status_counts={"succeeded": 2},
            second_turn_eligible=True,
            recommended_actions=(),
        )


if __name__ == "__main__":
    unittest.main()
