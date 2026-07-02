import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.api import build_default_orchestrator
from datasmart_ai_runtime.api.agent.plan_response import build_plan_response
from datasmart_ai_runtime.domain.contracts import AgentRequest
from datasmart_ai_runtime.domain.events import AgentRuntimeEventType
from datasmart_ai_runtime.services.agent_execution import DurableAgentLoopService
from datasmart_ai_runtime.services.memory import UserProfileMemoryService


class AgentProfileAndDurableLoopResponseTest(unittest.TestCase):
    def test_plan_response_contains_profile_and_durable_loop_summary(self) -> None:
        """同步计划响应应暴露画像摘要和 Durable Loop checkpoint。"""

        user_profile_memory = UserProfileMemoryService.default()
        durable_loop = DurableAgentLoopService()
        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="user-a",
            objective="请用中文详细注释，尽快完成 MySQL 增量 ETL 数据同步闭环。",
            variables={"workspaceKey": "workspace-a"},
        )
        response = build_plan_response(
            request,
            build_default_orchestrator(user_profile_memory=user_profile_memory),
            durable_agent_loop_service=durable_loop,
        )

        self.assertIn("userProfileMemory", response)
        self.assertIn("agentDurableLoop", response)
        self.assertIn("agentExecutionSession", response)
        self.assertGreater(response["userProfileMemory"]["activeFacetCount"], 0)
        self.assertIn(response["agentDurableLoop"]["phase"], {"plan_created", "waiting_control_plane"})
        self.assertEqual("LOW_SENSITIVE_LOOP_STATE_ONLY", response["agentDurableLoop"]["payloadPolicy"])
        self.assertEqual(
            "LOW_SENSITIVE_MULTI_AGENT_EXECUTION_SESSION_ONLY",
            response["agentExecutionSession"]["payloadPolicy"],
        )
        self.assertIn("MASTER_ORCHESTRATOR", response["agentExecutionSession"]["activeRoles"])
        self.assertFalse(response["agentExecutionSession"]["sideEffectBoundary"]["toolExecutedByPython"])

    def test_execution_session_event_is_added_to_plan_and_snapshot_envelope(self) -> None:
        """受控多 Agent 执行会话应作为低敏事件进入同步响应和事件 envelope。

        `agentExecutionSession` 顶层字段适合当前 HTTP 调用方即时读取，但真实产品还需要 WebSocket 断线
        恢复、Kafka 异步消费、Java 控制面投影和审计 replay。这个测试保护的就是“同一份会话事实不仅
        出现在响应顶层，也被压缩成 `agent_execution_session_recorded` runtime event”。

        事件 payload 必须保持低敏：即使请求里包含用户目标、SQL、datasourceId 等字段，也不能把这些
        正文复制进 runtime event attributes，否则 Java projection 或 Prometheus/日志链路会变成新的
        敏感数据扩散面。
        """

        durable_loop = DurableAgentLoopService()
        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="user-a",
            objective="secret objective: 请分析客户订单表并生成同步方案。",
            variables={
                "workspaceKey": "workspace-a",
                "sessionId": "session-agent-execution",
                "datasourceId": "secret-datasource-id",
                "sql": "select * from hidden_customer",
            },
        )
        response = build_plan_response(
            request,
            build_default_orchestrator(),
            durable_agent_loop_service=durable_loop,
        )

        events = [
            event
            for event in response["plan"]["runtime_events"]
            if event["event_type"] == AgentRuntimeEventType.AGENT_EXECUTION_SESSION_RECORDED
        ]
        self.assertEqual(1, len(events))
        event = events[0]
        attributes = event["attributes"]
        serialized_attributes = str(attributes)

        self.assertIn(event, response["eventEnvelope"]["events"])
        self.assertEqual(response["agentExecutionSession"]["status"], attributes["status"])
        self.assertEqual("AGENT_EXECUTION_SESSION_CONTROL_PLANE_VIEW", attributes["snapshotType"])
        self.assertEqual("LOW_SENSITIVE_MULTI_AGENT_EXECUTION_SESSION_ONLY", attributes["payloadPolicy"])
        self.assertTrue(attributes["javaControlPlaneRequiredForSideEffects"])
        self.assertFalse(attributes["toolExecutedByPython"])
        self.assertFalse(attributes["outboxWrittenByPython"])
        self.assertFalse(attributes["approvalCreatedByPython"])
        self.assertGreaterEqual(attributes["workItemCount"], 1)
        self.assertIn("MASTER_ORCHESTRATOR", attributes["activeRoles"])
        self.assertNotIn("secret objective", serialized_attributes)
        self.assertNotIn("secret-datasource-id", serialized_attributes)
        self.assertNotIn("hidden_customer", serialized_attributes)


if __name__ == "__main__":
    unittest.main()
