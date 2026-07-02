import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.services.multi_agent import MultiAgentExecutionSessionMetrics


class MultiAgentExecutionSessionMetricsTest(unittest.TestCase):
    """受控多 Agent 执行会话指标测试。

    这组测试保护的是 Agent 商业化运行时非常关键的一条边界：Prometheus 指标只能用来观察趋势，
    例如“多 Agent 会话是否大量等待审批”“必做 Agent 是否经常 standby”“控制面反馈是否堆积”；
    它不能承载租户、用户、runId、sessionId、工具名、SQL、prompt 或模型输出等高基数/敏感明细。
    明细排障应继续走 runtime event、Java projection、审计 replay 或受控诊断 API。
    """

    def test_summary_updates_low_cardinality_prometheus_metrics(self) -> None:
        """合法会话摘要应转为 session、work item、roster 三类低基数 counter。"""

        metrics = MultiAgentExecutionSessionMetrics()

        recorded = metrics.record_summary(
            {
                "status": "WAITING_APPROVAL_OR_HANDOFF",
                "source": "langgraph_multi_agent_execution_plan",
                "durablePhase": "waiting_control_plane",
                "sessionId": "secret-session-id",
                "requestId": "secret-request-id",
                "runId": "secret-run-id",
                "tenantId": "tenant-secret",
                "objective": "secret prompt should not leak",
                "workItems": (
                    {
                        "agentRole": "DATA_QUALITY_AGENT",
                        "sessionStatus": "WAITING_APPROVAL_OR_HANDOFF",
                        "deliveryTier": "must_do",
                        "resumeAction": "WAIT_FOR_APPROVAL_OR_HANDOFF_FACT",
                        "toolArguments": {"sql": "select * from hidden_customer"},
                    },
                    {
                        "agentRole": "MASTER_ORCHESTRATOR",
                        "sessionStatus": "READY_FOR_AGENT_TURN",
                        "deliveryTier": "must_do",
                        "resumeAction": "COORDINATE_SPECIALIST_NEXT_TURN",
                        "modelOutput": "secret model output",
                    },
                ),
                "rosterCoverage": {
                    "activeMustDoRoles": ("MASTER_ORCHESTRATOR", "DATA_QUALITY_AGENT"),
                    "standbyMustDoRoles": ("DATASOURCE_AGENT", "TASK_AGENT", "PERMISSION_AGENT"),
                    "activeControlledScopeRoles": ("MEMORY_AGENT",),
                    "standbyControlledScopeRoles": ("OPS_AGENT", "DATA_SYNC_AGENT"),
                    "deferredLightweightRoles": ("ETL_DEVELOPMENT_AGENT",),
                },
            }
        )
        text = metrics.render_prometheus()

        self.assertTrue(recorded)
        self.assertIn(
            'datasmart_ai_multi_agent_execution_sessions_total{durable_phase="waiting_control_plane",'
            'session_status="waiting_approval_or_handoff",source="langgraph_multi_agent_execution_plan"} 1',
            text,
        )
        self.assertIn(
            'datasmart_ai_multi_agent_execution_work_items_total{delivery_tier="must_do",'
            'resume_action="wait_for_approval_or_handoff_fact",session_status="waiting_approval_or_handoff",'
            'source="langgraph_multi_agent_execution_plan"} 1',
            text,
        )
        self.assertIn(
            'datasmart_ai_multi_agent_execution_work_items_total{delivery_tier="must_do",'
            'resume_action="coordinate_specialist_next_turn",session_status="ready_for_agent_turn",'
            'source="langgraph_multi_agent_execution_plan"} 1',
            text,
        )
        self.assertIn(
            'datasmart_ai_multi_agent_execution_roster_roles_total{role_group="must_do",role_state="active"} 2',
            text,
        )
        self.assertIn(
            'datasmart_ai_multi_agent_execution_roster_roles_total{role_group="must_do",role_state="standby"} 3',
            text,
        )
        self.assertIn(
            'datasmart_ai_multi_agent_execution_roster_roles_total{role_group="controlled_scope",role_state="standby"} 2',
            text,
        )
        self.assertNotIn("tenant-secret", text)
        self.assertNotIn("secret-session-id", text)
        self.assertNotIn("secret-request-id", text)
        self.assertNotIn("secret-run-id", text)
        self.assertNotIn("secret prompt", text)
        self.assertNotIn("select * from hidden_customer", text)
        self.assertNotIn("secret model output", text)

    def test_unknown_values_are_bounded_to_other_labels(self) -> None:
        """未知状态、来源、层级和恢复动作只能归入 `other`，不能制造动态 label。"""

        metrics = MultiAgentExecutionSessionMetrics()

        metrics.record_summary(
            {
                "status": "CUSTOM_STATUS_WITH_SECRET",
                "source": "CUSTOM_SOURCE_WITH_SECRET",
                "durablePhase": "CUSTOM_PHASE_WITH_SECRET",
                "workItems": (
                    {
                        "sessionStatus": "CUSTOM_ITEM_STATUS_WITH_SECRET",
                        "deliveryTier": "secret-tier",
                        "resumeAction": "CUSTOM_RESUME_ACTION_WITH_SECRET",
                    },
                ),
                "rosterCoverage": {"activeMustDoRoles": ("MASTER_ORCHESTRATOR",)},
            }
        )
        text = metrics.render_prometheus()

        self.assertIn('session_status="other"', text)
        self.assertIn('source="other"', text)
        self.assertIn('durable_phase="other"', text)
        self.assertIn('delivery_tier="other"', text)
        self.assertIn('resume_action="other"', text)
        self.assertNotIn("CUSTOM_STATUS_WITH_SECRET", text)
        self.assertNotIn("CUSTOM_SOURCE_WITH_SECRET", text)
        self.assertNotIn("secret-tier", text)

    def test_missing_summary_is_ignored_but_help_text_is_rendered(self) -> None:
        """指标记录器挂到可选链路后，应安全忽略空 summary，并仍能输出 HELP 文本。"""

        metrics = MultiAgentExecutionSessionMetrics()

        self.assertFalse(metrics.record_summary(None))
        self.assertEqual(0, metrics.snapshot()["metricCount"])
        self.assertIn(
            "# HELP datasmart_ai_multi_agent_execution_sessions_total",
            metrics.render_prometheus(),
        )


if __name__ == "__main__":
    unittest.main()
