import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.services.multi_agent import MultiAgentTurnRunnerMetrics


class MultiAgentTurnRunnerMetricsTest(unittest.TestCase):
    """受控多 Agent Turn Runner 指标测试。

    这组测试保护的是多 Agent 真实执行前非常重要的观测边界：Prometheus 只能看到低基数趋势，例如
    “turn runner 是否长期等待审批”“requiredEvidence 是否长期缺 worker receipt”“是否出现副作用边界违规”。
    单次 turnId、workItemId、managerToolName、prompt、SQL、checkpointId 和 commandId 仍应走 runtime event、
    Java projection 或审计 replay，不能进入指标标签。
    """

    def test_summary_updates_low_cardinality_prometheus_metrics(self) -> None:
        """合法 turn runner 摘要应转为 run、attempt、evidence、manager-as-tools 和边界指标。"""

        metrics = MultiAgentTurnRunnerMetrics()

        recorded = metrics.record_summary(
            {
                "status": "LANGGRAPH_MULTI_AGENT_TURN_RUNNER_BUILT",
                "runStatus": "WAITING_APPROVAL_OR_HANDOFF_FACT",
                "sessionStatus": "WAITING_APPROVAL_OR_HANDOFF",
                "durablePhase": "waiting_control_plane",
                "managerAsToolsCount": 2,
                "tenantId": "tenant-secret",
                "runId": "run-secret",
                "objective": "secret objective should not leak",
                "prompt": "secret prompt should not leak",
                "sql": "select * from hidden_customer",
                "checkpointId": "checkpoint-secret",
                "commandId": "command-secret",
                "turnAttempts": (
                    {
                        "turnId": "turn-secret-1",
                        "workItemId": "workitem-secret-1",
                        "agentRole": "DATA_QUALITY_AGENT",
                        "deliveryTier": "must_do",
                        "turnStatus": "WAITING_APPROVAL_OR_HANDOFF_FACT",
                        "resumeAction": "WAIT_FOR_APPROVAL_OR_HANDOFF_FACT",
                        "managerToolName": "manager_call_secret_agent",
                        "requiredEvidenceCodes": (
                            "TURN_CHECKPOINT_REQUIRED",
                            "APPROVAL_DECISION_FACT_REQUIRED",
                        ),
                        "toolArguments": {"sql": "select * from hidden_customer"},
                    },
                    {
                        "turnId": "turn-secret-2",
                        "workItemId": "workitem-secret-2",
                        "agentRole": "TASK_AGENT",
                        "deliveryTier": "controlled_scope",
                        "turnStatus": "READY_FOR_JAVA_CONTROL_PLANE_HANDOFF",
                        "resumeAction": "HANDOFF_TO_JAVA_CONTROL_PLANE",
                        "managerToolName": "manager_call_task_agent",
                        "requiredEvidenceCodes": (
                            "JAVA_COMMAND_PROPOSAL_OR_OUTBOX_REQUIRED",
                            "WORKER_RECEIPT_REQUIRED",
                        ),
                        "modelOutput": "secret model output",
                    },
                ),
                "toolExecutedByPython": False,
                "modelCalledByTurnRunner": False,
                "outboxWrittenByPython": False,
                "approvalCreatedByPython": False,
                "workerDispatchedByPython": False,
                "javaControlPlaneRequiredForSideEffects": True,
                "workerReceiptRequiredForSideEffects": True,
            }
        )
        text = metrics.render_prometheus()

        self.assertTrue(recorded)
        self.assertIn(
            'datasmart_ai_multi_agent_turn_runner_runs_total{durable_phase="waiting_control_plane",'
            'run_status="waiting_approval_or_handoff_fact",session_status="waiting_approval_or_handoff",'
            'status="langgraph_multi_agent_turn_runner_built"} 1',
            text,
        )
        self.assertIn(
            'datasmart_ai_multi_agent_turn_runner_attempts_total{delivery_tier="must_do",'
            'resume_action="wait_for_approval_or_handoff_fact",turn_status="waiting_approval_or_handoff_fact"} 1',
            text,
        )
        self.assertIn(
            'datasmart_ai_multi_agent_turn_runner_required_evidence_total'
            '{evidence_code="worker_receipt_required"} 1',
            text,
        )
        self.assertIn(
            'datasmart_ai_multi_agent_turn_runner_manager_tools_total'
            '{run_status="waiting_approval_or_handoff_fact"} 2',
            text,
        )
        self.assertIn(
            'datasmart_ai_multi_agent_turn_runner_safety_boundary_total'
            '{boundary="tool_executed_by_python",state="clean"} 1',
            text,
        )
        self.assertIn(
            'datasmart_ai_multi_agent_turn_runner_safety_boundary_total'
            '{boundary="java_control_plane_required_for_side_effects",state="required"} 1',
            text,
        )
        self.assertNotIn("tenant-secret", text)
        self.assertNotIn("run-secret", text)
        self.assertNotIn("turn-secret", text)
        self.assertNotIn("workitem-secret", text)
        self.assertNotIn("manager_call_secret_agent", text)
        self.assertNotIn("secret objective", text)
        self.assertNotIn("select * from hidden_customer", text)
        self.assertNotIn("secret model output", text)
        self.assertNotIn("checkpoint-secret", text)
        self.assertNotIn("command-secret", text)

    def test_unknown_values_are_bounded_to_other_labels_and_boundary_violation_is_visible(self) -> None:
        """未知状态只能归入 `other`，副作用违规必须以低基数 state 暴露。"""

        metrics = MultiAgentTurnRunnerMetrics()

        metrics.record_summary(
            {
                "status": "CUSTOM_STATUS_WITH_SECRET",
                "runStatus": "CUSTOM_RUN_STATUS_WITH_SECRET",
                "sessionStatus": "CUSTOM_SESSION_STATUS_WITH_SECRET",
                "durablePhase": "CUSTOM_PHASE_WITH_SECRET",
                "managerAsToolsCount": 1,
                "turnAttempts": (
                    {
                        "turnStatus": "CUSTOM_TURN_STATUS_WITH_SECRET",
                        "deliveryTier": "secret-tier",
                        "resumeAction": "CUSTOM_RESUME_ACTION_WITH_SECRET",
                        "requiredEvidenceCodes": ("CUSTOM_EVIDENCE_WITH_SECRET",),
                    },
                ),
                "toolExecutedByPython": True,
                "javaControlPlaneRequiredForSideEffects": False,
            }
        )
        text = metrics.render_prometheus()

        self.assertIn('status="other"', text)
        self.assertIn('run_status="other"', text)
        self.assertIn('session_status="other"', text)
        self.assertIn('durable_phase="other"', text)
        self.assertIn('delivery_tier="other"', text)
        self.assertIn('resume_action="other"', text)
        self.assertIn('evidence_code="other"', text)
        self.assertIn('boundary="tool_executed_by_python",state="violation"', text)
        self.assertIn('boundary="java_control_plane_required_for_side_effects",state="missing"', text)
        self.assertNotIn("CUSTOM_STATUS_WITH_SECRET", text)
        self.assertNotIn("secret-tier", text)
        self.assertNotIn("CUSTOM_EVIDENCE_WITH_SECRET", text)

    def test_missing_summary_is_ignored_but_help_text_is_rendered(self) -> None:
        """指标记录器挂到可选链路后，应安全忽略空 summary，并仍能输出 HELP 文本。"""

        metrics = MultiAgentTurnRunnerMetrics()

        self.assertFalse(metrics.record_summary(None))
        self.assertEqual(0, metrics.snapshot()["metricCount"])
        self.assertIn(
            "# HELP datasmart_ai_multi_agent_turn_runner_runs_total",
            metrics.render_prometheus(),
        )


if __name__ == "__main__":
    unittest.main()
