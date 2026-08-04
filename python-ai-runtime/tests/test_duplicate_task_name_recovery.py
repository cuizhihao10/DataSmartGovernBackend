import os
import sys
import unittest
from dataclasses import replace

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.domain.contracts import (
    ToolExecutionMode,
    ToolParameterValidationResult,
    ToolPlan,
    ToolRiskLevel,
)
from datasmart_ai_runtime.services.agent_control_plane_feedback import (
    AgentControlPlaneFeedbackItem,
)
from datasmart_ai_runtime.services.agent_execution.duplicate_task_name_recovery import (
    DuplicateTaskNameRecoveryPlanner,
)
from datasmart_ai_runtime.services.model_gateway.model_tool_result_feedback import (
    ToolExecutionFeedbackStatus,
)


class DuplicateTaskNameRecoveryPlannerTest(unittest.TestCase):
    def test_duplicate_downstream_error_proposes_renamed_full_lifecycle(self) -> None:
        original_arguments = {
            "taskName": "Agent 创建的数据同步任务",
            "sourceDatasourceId": 27,
            "targetDatasourceId": 28,
            "syncMode": "FULL",
            "writeStrategy": "INSERT",
            "sourceMetadataRef": {"fromAuditId": "source-meta"},
            "targetMetadataRef": {"fromAuditId": "target-meta"},
            "objectMappings": [{
                "sourceObjectName": "customer",
                "targetSchemaName": "public",
                "targetObjectName": "customer",
                "whereCondition": "status = 1",
                "fieldMappings": [{
                    "sourceField": "id",
                    "targetField": "id",
                    "syncEnabled": True,
                }],
            }],
        }
        failed_plan = ToolPlan(
            tool_name="sync.task.draft.save",
            reason="save reviewed task",
            arguments=original_arguments,
            risk_level=ToolRiskLevel.HIGH,
            execution_mode=ToolExecutionMode.APPROVAL_REQUIRED,
            governance_hints={
                "modelToolCallId": "original-call",
                "agentRuntimeSessionId": "session-1",
                "agentRuntimeRunId": "run-failed",
                "workspaceKey": "tenant:10:project:101",
            },
        )
        feedback = AgentControlPlaneFeedbackItem(
            model_tool_call_id="original-call",
            tool_name="sync.task.draft.save",
            status=ToolExecutionFeedbackStatus.FAILED,
            summary="data-sync returned 409 Conflict",
            error_code="SYNC_DOWNSTREAM_ERROR",
            error_message=(
                '409 Conflict: {"code":"40002","reason":"DUPLICATE_OPERATION",'
                '"message":"当前项目下已存在同名同步任务"}'
            ),
        )
        source_metadata_feedback = AgentControlPlaneFeedbackItem(
            model_tool_call_id="source-metadata-call",
            tool_name="datasource.source.metadata.read",
            status=ToolExecutionFeedbackStatus.SUCCEEDED,
            summary="源端元数据读取成功",
            audit_id="source-meta-current",
            run_id="run-failed",
        )
        target_metadata_feedback = AgentControlPlaneFeedbackItem(
            model_tool_call_id="target-metadata-call",
            tool_name="datasource.target.metadata.read",
            status=ToolExecutionFeedbackStatus.SUCCEEDED,
            summary="目标端元数据读取成功",
            audit_id="target-meta-current",
            run_id="run-failed",
        )

        result = DuplicateTaskNameRecoveryPlanner(_ToolPlanner()).build(
            source_run_id="run-failed",
            tool_plans=(failed_plan,),
            feedback_items=(source_metadata_feedback, target_metadata_feedback, feedback),
        )

        self.assertIsNotNone(result)
        assert result is not None
        self.assertEqual("DUPLICATE_TASK_NAME", result.proposal.to_summary()["kind"])
        self.assertEqual("Agent 创建的数据同步任务", result.proposal.original_task_name)
        self.assertTrue(result.proposal.proposed_task_name.startswith("Agent 创建的数据同步任务_agent_"))
        self.assertEqual(
            (
                "sync.task.draft.save",
                "sync.task.precheck",
                "sync.task.publish",
                "sync.task.run",
            ),
            tuple(item.tool_name for item in result.tool_plans),
        )
        repair_arguments = result.tool_plans[0].arguments
        self.assertNotEqual(original_arguments["taskName"], repair_arguments["taskName"])
        self.assertEqual(original_arguments["objectMappings"], repair_arguments["objectMappings"])
        self.assertEqual(
            {
                "fromTool": "datasource.source.metadata.read",
                "fromAuditId": "source-meta-current",
                "fromRunId": "run-failed",
                "path": "metadata",
            },
            repair_arguments["sourceMetadataRef"],
        )
        self.assertEqual("target-meta-current", repair_arguments["targetMetadataRef"]["fromAuditId"])
        self.assertTrue(result.tool_plans[0].requires_human_approval)
        self.assertNotEqual(
            "original-call",
            result.tool_plans[0].governance_hints["modelToolCallId"],
        )

    def test_unrelated_downstream_failure_does_not_create_name_repair(self) -> None:
        failed_plan = ToolPlan(
            tool_name="sync.task.draft.save",
            reason="save reviewed task",
            arguments={"taskName": "customer-sync"},
        )
        feedback = AgentControlPlaneFeedbackItem(
            model_tool_call_id="call-1",
            tool_name="sync.task.draft.save",
            status=ToolExecutionFeedbackStatus.FAILED,
            summary="downstream timeout",
            error_code="SYNC_DOWNSTREAM_ERROR",
            error_message="data-sync request timed out",
        )

        result = DuplicateTaskNameRecoveryPlanner(_ToolPlanner()).build(
            source_run_id="run-failed",
            tool_plans=(failed_plan,),
            feedback_items=(feedback,),
        )

        self.assertIsNone(result)

    def test_repair_keeps_existing_reference_when_successful_metadata_fact_is_missing(self) -> None:
        """Missing trusted evidence must stay fail-closed instead of inventing an audit ID."""

        original_reference = {
            "fromTool": "datasource.source.metadata.read",
            "fromAuditId": "already-trusted-source-audit",
            "path": "metadata",
        }
        failed_plan = ToolPlan(
            tool_name="sync.task.draft.save",
            reason="save reviewed task",
            arguments={
                "taskName": "customer-sync",
                "sourceMetadataRef": original_reference,
            },
        )
        failed_feedback = AgentControlPlaneFeedbackItem(
            model_tool_call_id="failed-draft",
            tool_name="sync.task.draft.save",
            status=ToolExecutionFeedbackStatus.FAILED,
            summary="duplicate task",
            error_code="SYNC_DOWNSTREAM_ERROR",
            error_message="DUPLICATE_OPERATION：当前项目下已存在同名同步任务",
        )

        result = DuplicateTaskNameRecoveryPlanner(_ToolPlanner()).build(
            source_run_id="run-failed",
            tool_plans=(failed_plan,),
            feedback_items=(failed_feedback,),
        )

        self.assertIsNotNone(result)
        assert result is not None
        self.assertEqual(original_reference, result.tool_plans[0].arguments["sourceMetadataRef"])


class _ToolPlanner:
    def revalidate_plan(self, plan, arguments):
        return replace(
            plan,
            arguments=dict(arguments),
            parameter_validation=ToolParameterValidationResult(can_execute=True),
        )

    def expand_confirmed_data_sync_lifecycle(self, draft_plan):
        return (
            draft_plan,
            ToolPlan("sync.task.precheck", "precheck", requires_human_approval=True),
            ToolPlan("sync.task.publish", "publish", requires_human_approval=True),
            ToolPlan("sync.task.run", "run", requires_human_approval=True),
        )


if __name__ == "__main__":
    unittest.main()
