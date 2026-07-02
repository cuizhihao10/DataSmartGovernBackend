import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.api import build_default_orchestrator
from datasmart_ai_runtime.api.agent.plan_response import build_plan_response
from datasmart_ai_runtime.domain.contracts import AgentRequest
from datasmart_ai_runtime.services.agent_execution import DurableAgentLoopService
from datasmart_ai_runtime.services.multi_agent import MultiAgentTurnRunnerMetrics


class AgentTurnRunnerMetricsResponseTest(unittest.TestCase):
    """`/agent/plans` 响应路径中的 Turn Runner 指标测试。

    指标模块自身只能证明“给它 summary 时能聚合”。本测试进一步保护 API 组装层：当同步 plan response
    生成 `agentTurnRunner` 后，注入的指标记录器必须真实收到低敏摘要，供 `/agent/metrics` 暴露给
    Prometheus。这样 Python 响应、Java projection 和生产告警能围绕同一份 turn runner 事实演进。
    """

    def test_build_plan_response_records_turn_runner_metrics_without_sensitive_labels(self) -> None:
        """同步 plan response 应记录 turn runner 指标，但不能把请求明细泄露到 Prometheus 文本。"""

        metrics = MultiAgentTurnRunnerMetrics()
        request = AgentRequest(
            tenant_id="tenant-secret",
            project_id="project-secret",
            actor_id="actor-secret",
            objective="secret objective: build mysql to pgsql sync with hidden table",
            variables={
                "workspaceKey": "workspace-secret",
                "sessionId": "session-secret",
                "sql": "select * from hidden_customer",
                "datasourceId": "datasource-secret",
                "checkpointId": "checkpoint-secret",
                "commandId": "command-secret",
            },
        )

        response = build_plan_response(
            request,
            build_default_orchestrator(),
            durable_agent_loop_service=DurableAgentLoopService(),
            multi_agent_turn_runner_metrics=metrics,
        )
        text = metrics.render_prometheus()

        self.assertIn("agentTurnRunner", response)
        self.assertGreater(metrics.snapshot()["metricCount"], 0)
        self.assertIn("datasmart_ai_multi_agent_turn_runner_runs_total", text)
        self.assertIn("datasmart_ai_multi_agent_turn_runner_attempts_total", text)
        self.assertIn("datasmart_ai_multi_agent_turn_runner_required_evidence_total", text)
        self.assertIn("datasmart_ai_multi_agent_turn_runner_safety_boundary_total", text)
        self.assertNotIn("tenant-secret", text)
        self.assertNotIn("project-secret", text)
        self.assertNotIn("actor-secret", text)
        self.assertNotIn("session-secret", text)
        self.assertNotIn("workspace-secret", text)
        self.assertNotIn("secret objective", text)
        self.assertNotIn("select * from hidden_customer", text)
        self.assertNotIn("datasource-secret", text)
        self.assertNotIn("checkpoint-secret", text)
        self.assertNotIn("command-secret", text)


if __name__ == "__main__":
    unittest.main()
