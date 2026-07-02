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
from datasmart_ai_runtime.services.multi_agent import MultiAgentExecutionSessionMetrics


class AgentExecutionSessionMetricsResponseTest(unittest.TestCase):
    """`/agent/plans` 响应路径中的多 Agent 会话指标测试。

    指标模块自身通过单元测试只能证明“给它 summary 时能正确聚合”。本测试进一步保护 API 组装层：
    当同步 plan response 生成 `agentExecutionSession` 后，注入的指标记录器必须真正收到低敏摘要。
    这样本地 FastAPI、未来 Java gateway 反向代理和生产 Prometheus scrape 才能围绕同一份会话事实观察系统。
    """

    def test_build_plan_response_records_execution_session_metrics_without_sensitive_labels(self) -> None:
        """同步 plan response 应记录会话指标，但不能把请求明细泄露到 Prometheus 文本。"""

        metrics = MultiAgentExecutionSessionMetrics()
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
            },
        )

        response = build_plan_response(
            request,
            build_default_orchestrator(),
            durable_agent_loop_service=DurableAgentLoopService(),
            multi_agent_execution_session_metrics=metrics,
        )
        text = metrics.render_prometheus()

        self.assertIn("agentExecutionSession", response)
        self.assertGreater(metrics.snapshot()["metricCount"], 0)
        self.assertIn("datasmart_ai_multi_agent_execution_sessions_total", text)
        self.assertIn("datasmart_ai_multi_agent_execution_work_items_total", text)
        self.assertIn("datasmart_ai_multi_agent_execution_roster_roles_total", text)
        self.assertNotIn("tenant-secret", text)
        self.assertNotIn("project-secret", text)
        self.assertNotIn("actor-secret", text)
        self.assertNotIn("session-secret", text)
        self.assertNotIn("workspace-secret", text)
        self.assertNotIn("secret objective", text)
        self.assertNotIn("select * from hidden_customer", text)
        self.assertNotIn("datasource-secret", text)


if __name__ == "__main__":
    unittest.main()
