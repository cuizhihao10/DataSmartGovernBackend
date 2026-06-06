import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.domain.protocols import AgentTaskPlanningMode, AgentTaskSuggestedAction
from datasmart_ai_runtime.services.agent_gateway import A2aTaskPlanningAdapter


class A2aTaskPlanningAdapterTest(unittest.TestCase):
    """A2A task 控制面合同到 Python 规划决策的测试。

    这些测试保护的不是 Java mock preview 的具体文案，而是跨服务 Agent 编排最核心的安全语义：
    - 非终态只能进入预检或 worker 规划，不能在 Python adapter 内直接执行；
    - 等待用户输入和等待授权必须显式阻断自动推进；
    - 终态任务不能被重新推进到 working；
    - artifact 只能以引用形式出现；
    - prompt、工具参数、SQL、artifact 正文、模型输出、内部 endpoint 和凭证类字段必须被丢弃。
    """

    def setUp(self) -> None:
        self.adapter = A2aTaskPlanningAdapter()

    def test_submitted_task_requires_precheck_and_idempotency(self) -> None:
        """submitted 状态只能进入执行前治理检查。

        真实 A2A `SendMessage` 后，服务端可能已经创建 task，但这不代表 DataSmart 可以直接执行工具。
        对数据治理产品来说，仍需要 permission-admin、幂等键、租户配额、限流和 worker pre-check。
        """

        decision = self.adapter.adapt(
            {
                "schemaVersion": "datasmart.agent-runtime.a2a-task-query-preview.v1",
                "previewOnly": True,
                "taskEndpointEnabled": False,
                "task": {
                    "taskPublicId": "task_pub_001",
                    "contextPublicId": "ctx_pub_001",
                    "currentState": "TASK_STATE_SUBMITTED",
                    "internalPhase": "POLICY_PRECHECK",
                    "sequence": 1,
                    "allowedClientOperations": ["tasks/get", "tasks/cancel"],
                },
                "historyEvents": [
                    {
                        "sequence": 1,
                        "eventType": "agent.a2a_task.submitted",
                        "a2aState": "TASK_STATE_SUBMITTED",
                        "internalPhase": "POLICY_PRECHECK",
                    }
                ],
            }
        )
        summary = decision.to_summary()

        self.assertEqual(AgentTaskPlanningMode.PRECHECK_REQUIRED, decision.mode)
        self.assertFalse(decision.executable)
        self.assertIn(
            AgentTaskSuggestedAction.REQUEST_PERMISSION_PRECHECK.value,
            summary["suggestedActions"],
        )
        self.assertIn(
            AgentTaskSuggestedAction.VALIDATE_IDEMPOTENCY_KEY.value,
            summary["suggestedActions"],
        )
        self.assertIn("PERMISSION_ADMIN_PRECHECK_REQUIRED_BEFORE_ANY_TOOL_EXECUTION", summary["guardrails"])

    def test_working_task_allows_worker_planning_but_not_direct_start(self) -> None:
        """working 状态可以规划 worker pre-check，但 adapter 本身不启动 worker。"""

        decision = self.adapter.adapt(
            {
                "task": {
                    "taskPublicId": "task_pub_working",
                    "contextPublicId": "ctx_pub_working",
                    "currentState": "working",
                    "internalPhase": "WORKER_PRECHECK",
                    "sequence": 3,
                }
            }
        )

        self.assertEqual(AgentTaskPlanningMode.WORKER_PLANNING_ALLOWED, decision.mode)
        self.assertTrue(decision.executable)
        self.assertFalse(decision.should_start_worker)
        self.assertIn(AgentTaskSuggestedAction.PLAN_WORKER_PRECHECK, decision.suggested_actions)
        self.assertIn("WORKER_PRECHECK_REQUIRED_BEFORE_SIDE_EFFECT", decision.guardrails)

    def test_input_and_auth_required_wait_for_external_facts(self) -> None:
        """input-required 与 auth-required 必须等待外部事实，不能让模型自动猜测。"""

        input_decision = self.adapter.adapt(
            {
                "task": {
                    "taskPublicId": "task_pub_input",
                    "currentState": "TASK_STATE_INPUT_REQUIRED",
                    "internalPhase": "INPUT_WAITING",
                    "interrupted": True,
                }
            }
        )
        auth_decision = self.adapter.adapt(
            {
                "task": {
                    "taskPublicId": "task_pub_auth",
                    "currentState": "TASK_STATE_SUBMITTED",
                    "internalPhase": "APPROVAL_WAITING",
                }
            }
        )

        self.assertEqual(AgentTaskPlanningMode.WAIT_FOR_USER_INPUT, input_decision.mode)
        self.assertTrue(input_decision.should_wait_for_human)
        self.assertIn(AgentTaskSuggestedAction.ASK_USER_FOR_INPUT, input_decision.suggested_actions)
        self.assertEqual(AgentTaskPlanningMode.WAIT_FOR_AUTHORIZATION, auth_decision.mode)
        self.assertTrue(auth_decision.should_wait_for_human)
        self.assertIn(AgentTaskSuggestedAction.REQUEST_AUTHORIZATION, auth_decision.suggested_actions)

    def test_completed_task_surfaces_only_artifact_reference_and_drops_sensitive_fields(self) -> None:
        """completed 终态只能展示 artifact 引用，且必须丢弃敏感载荷。

        这个用例故意塞入多种不应进入规划层的字段，确保 summary 中既没有字段值，也没有敏感字段名。
        """

        decision = self.adapter.adapt(
            {
                "scenario": "completed",
                "prompt": "用户原始需求不能进入 task planning 摘要",
                "toolArguments": {"datasourceId": "ds-secret"},
                "targetEndpoint": "http://internal-service.local/tools/call",
                "task": {
                    "taskPublicId": "task_pub_done",
                    "contextPublicId": "ctx_pub_done",
                    "currentState": "TASK_STATE_COMPLETED",
                    "internalPhase": "RESULT_READY",
                    "terminal": True,
                    "sequence": 6,
                    "modelOutput": "模型输出正文不能进入摘要",
                },
                "historyEvents": [
                    {
                        "sequence": 5,
                        "eventType": "agent.a2a_task.artifact_announced",
                        "a2aState": "TASK_STATE_WORKING",
                        "artifactRef": "artifact_ref_report",
                        "executionPath": ["hidden-node"],
                    },
                    {
                        "sequence": 6,
                        "eventType": "agent.a2a_task.completed",
                        "a2aState": "TASK_STATE_COMPLETED",
                        "terminal": True,
                    },
                ],
                "artifactReferences": [
                    {
                        "artifactRef": "artifact_ref_report",
                        "artifactType": "quality-rule-draft",
                        "available": True,
                        "metadataOnly": True,
                        "linkedEventSequences": [5],
                        "artifactBody": "artifact 正文不能进入摘要",
                    }
                ],
                "secret": "top-secret",
                "sql": "select * from sensitive_table",
            }
        )
        summary = decision.to_summary()
        serialized = str(summary)

        self.assertEqual(AgentTaskPlanningMode.TERMINAL_NO_EXECUTION, decision.mode)
        self.assertFalse(decision.executable)
        self.assertIn(AgentTaskSuggestedAction.SURFACE_ARTIFACT_REFERENCE.value, summary["suggestedActions"])
        self.assertEqual("artifact_ref_report", summary["snapshot"]["artifactReferences"][0]["artifactRef"])
        self.assertGreater(summary["snapshot"]["sensitiveFieldIgnoredCount"], 0)
        self.assertNotIn("用户原始需求", serialized)
        self.assertNotIn("ds-secret", serialized)
        self.assertNotIn("internal-service", serialized)
        self.assertNotIn("模型输出正文", serialized)
        self.assertNotIn("artifact 正文", serialized)
        self.assertNotIn("sensitive_table", serialized)
        self.assertNotIn("prompt", serialized.lower())
        self.assertNotIn("toolarguments", serialized.lower())
        self.assertNotIn("targetendpoint", serialized.lower())
        self.assertNotIn("artifactbody", serialized.lower())
        self.assertNotIn("modeloutput", serialized.lower())
        self.assertNotIn("executionpath", serialized.lower())

    def test_failed_canceled_rejected_are_terminal_no_execution(self) -> None:
        """failed/canceled/rejected 都是终态，不能自动重新进入执行链路。"""

        for state in ("TASK_STATE_FAILED", "TASK_STATE_CANCELED", "TASK_STATE_REJECTED"):
            with self.subTest(state=state):
                decision = self.adapter.adapt(
                    {
                        "task": {
                            "taskPublicId": f"task_pub_{state.lower()}",
                            "currentState": state,
                            "terminal": True,
                        }
                    }
                )

                self.assertEqual(AgentTaskPlanningMode.TERMINAL_NO_EXECUTION, decision.mode)
                self.assertFalse(decision.executable)
                self.assertIn("TERMINAL_TASK_MUST_NOT_REENTER_WORKING_STATE", decision.guardrails)

    def test_unknown_or_missing_state_fails_closed(self) -> None:
        """未知或缺失状态必须 fail-closed，避免协议漂移时误执行。"""

        unknown_decision = self.adapter.adapt({"task": {"taskPublicId": "task_pub_unknown", "state": "mystery"}})
        missing_decision = self.adapter.adapt(None)

        self.assertEqual(AgentTaskPlanningMode.REJECTED_OR_DIAGNOSTIC, unknown_decision.mode)
        self.assertFalse(unknown_decision.executable)
        self.assertTrue(unknown_decision.should_wait_for_human)
        self.assertIn("UNKNOWN_OR_UNTRUSTED_A2A_STATE_FAIL_CLOSED", unknown_decision.guardrails)
        self.assertEqual(AgentTaskPlanningMode.REJECTED_OR_DIAGNOSTIC, missing_decision.mode)
        self.assertIn("A2A_TASK_CONTRACT_MISSING_OR_INVALID", missing_decision.decision_reason)


if __name__ == "__main__":
    unittest.main()
