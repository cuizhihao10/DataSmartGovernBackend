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
                "path": "templateId",
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

        self.assertEqual(1, len(result.accepted_tool_plans))
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


if __name__ == "__main__":
    unittest.main()
