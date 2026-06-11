import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.api.agent.routes import register_agent_runtime_routes
from datasmart_ai_runtime.api.agent.mcp_tool_call_intake import build_mcp_tool_call_intake_preview_response
from datasmart_ai_runtime.config import default_tool_registry
from datasmart_ai_runtime.domain.events import AgentRuntimeEventType


class FakeApp:
    """极简路由注册器，用于在不安装 FastAPI 的情况下验证 API handler。"""

    def __init__(self) -> None:
        self.post_routes: dict[str, object] = {}
        self.websocket_routes: dict[str, object] = {}

    def post(self, path: str):
        """记录 POST 路由装饰器。"""

        def decorator(func):
            self.post_routes[path] = func
            return func

        return decorator

    def websocket(self, path: str):
        """记录 WebSocket 路由装饰器，满足 Agent 路由注册函数的最小依赖。"""

        def decorator(func):
            self.websocket_routes[path] = func
            return func

        return decorator


class RecordingEventStore:
    """记录写入事件的测试替身。"""

    def __init__(self) -> None:
        self.events = ()

    def append_many(self, events) -> None:
        """模拟 RuntimeEventStore.append_many。"""

        self.events = self.events + tuple(events)


class RecordingLivePushHub:
    """记录 live push 事件的测试替身。"""

    def __init__(self) -> None:
        self.events = ()

    def publish(self, events) -> int:
        """模拟 RuntimeEventLivePushHub.publish。"""

        self.events = self.events + tuple(events)
        return len(tuple(events))


class RecordingPublisher:
    """记录异步发布事件的测试替身。"""

    def __init__(self) -> None:
        self.events = ()

    def publish(self, events) -> int:
        """模拟 RuntimeEventPublisher.publish。"""

        self.events = self.events + tuple(events)
        return len(tuple(events))


class FailingEventStore:
    """用于验证事件旁路 fail-open 的测试替身。"""

    def append_many(self, events) -> None:
        """模拟外部 replay store 故障。"""

        raise RuntimeError("store unavailable")


class McpToolCallIntakeApiTest(unittest.TestCase):
    """MCP tools/call intake preview API 契约测试。

    这组测试不启动真实 MCP Server，也不执行工具。我们只验证协议入口能否正确进入 DataSmart 的控制面语义：
    - 标准 JSON-RPC `method=tools/call` 会被归一为 ToolPlan 候选；
    - 响应必须明确 previewOnly 和 toolExecutionEnabled=false；
    - readiness graph 只代表执行前条件图，不代表工具已执行；
    - 原始参数值、prompt、SQL、内部 endpoint、secret 不能被回显。
    """

    def test_json_rpc_tools_call_is_normalized_without_echoing_sensitive_values(self) -> None:
        """标准 MCP JSON-RPC 请求应进入 intake/readiness，同时隐藏原始参数值。"""

        response = build_mcp_tool_call_intake_preview_response(
            {
                "jsonrpc": "2.0",
                "id": "rpc-001",
                "method": "tools/call",
                "params": {
                    "name": "quality.rule.suggest",
                    "arguments": {
                        "datasourceId": "ds-secret-001",
                        "businessGoal": "手机号唯一性校验",
                    },
                },
                "visibleToolNames": ["quality.rule.suggest"],
                "prompt": "原始用户目标不能出现在响应里",
                "sql": "select * from hidden_table",
                "targetEndpoint": "http://internal-service/tools",
                "secret": "hidden-secret",
            },
            registered_tools=default_tool_registry(),
        )
        serialized = str(response)

        self.assertEqual("datasmart.python-ai-runtime.mcp-tools-call-intake-preview.v1", response["schemaVersion"])
        self.assertTrue(response["previewOnly"])
        self.assertFalse(response["toolExecutionEnabled"])
        self.assertEqual("MCP", response["protocolFamily"])
        self.assertTrue(response["inputPayloadPolicy"]["jsonRpcDetected"])
        self.assertTrue(response["inputPayloadPolicy"]["methodAccepted"])
        self.assertEqual(1, response["toolActionIntake"]["acceptedToolPlanCount"])
        self.assertEqual(1, response["toolExecutionReadiness"]["draftOnlyCount"])
        self.assertEqual("PRE_EXECUTION_CONDITION_GRAPH_ONLY", response["toolExecutionReadinessGraph"]["executionBoundary"])
        self.assertFalse(response["toolExecutionReadinessGraph"]["durableActionBoundary"]["toolExecuted"])
        self.assertIn("SHOW_DRAFT_FOR_REVIEW", response["toolExecutionReadinessGraph"]["branchCounts"])
        self.assertFalse(response["productionReadiness"]["readyForExecution"])
        self.assertIn("OUTBOX_COMMAND_AND_WORKER_RECEIPT", response["productionReadiness"]["missingProductionRequirements"])
        self.assertNotIn("ds-secret-001", serialized)
        self.assertNotIn("手机号唯一性校验", serialized)
        self.assertNotIn("原始用户目标", serialized)
        self.assertNotIn("hidden_table", serialized)
        self.assertNotIn("internal-service", serialized)
        self.assertNotIn("hidden-secret", serialized)

    def test_invisible_tool_is_rejected_before_readiness(self) -> None:
        """调用未暴露工具时，应在 readiness 前按最小权限原则拒绝。"""

        response = build_mcp_tool_call_intake_preview_response(
            {
                "call": {
                    "id": "rpc-hidden",
                    "name": "datasource.metadata.read",
                    "arguments": {"datasourceId": "ds-secret-hidden"},
                },
                "visibleToolNames": [],
            },
            registered_tools=default_tool_registry(),
        )
        serialized = str(response)

        self.assertEqual(0, response["toolActionIntake"]["acceptedToolPlanCount"])
        self.assertEqual(1, response["toolActionIntake"]["rejectedBeforeReadinessCount"])
        self.assertEqual(0, response["toolExecutionReadiness"]["totalCount"])
        self.assertIn("NO_TOOL_PLAN", response["toolExecutionReadinessGraph"]["branchCounts"])
        self.assertIn("MODEL_TOOL_CALL_NOT_EXPOSED", serialized)
        self.assertNotIn("ds-secret-hidden", serialized)

    def test_agent_routes_register_mcp_tools_call_intake_preview(self) -> None:
        """Agent 路由注册函数应暴露 MCP tools/call intake preview POST 入口。"""

        app = FakeApp()
        event_store = RecordingEventStore()
        live_push_hub = RecordingLivePushHub()
        event_publisher = RecordingPublisher()
        register_agent_runtime_routes(
            app,
            request_type=object,
            orchestrator=object(),
            event_store=event_store,
            session_manager=None,
            live_push_hub=live_push_hub,
            event_publisher=event_publisher,
            runtime_event_replay_sources=(),
            plan_ingestion_client=None,
            control_plane_feedback_collector=None,
            runtime_event_feedback_bridge=None,
            loop_control_evaluator=None,
            second_turn_orchestrator=None,
            memory_write_governance=None,
            tool_registry=default_tool_registry(),
        )

        handler = app.post_routes["/agent/protocol-adapters/mcp/tools-call-intake-preview"]
        response = handler(
            {
                "method": "tools/call",
                "params": {
                    "name": "datasource.metadata.read",
                    "arguments": {"datasourceId": "ds-route-secret"},
                },
                "context": {
                    "tenantId": "tenant-route",
                    "projectId": "project-route",
                    "actorId": "actor-route",
                    "requestId": "request-route",
                    "runId": "run-route",
                    "sessionId": "session-route",
                },
            }
        )

        self.assertEqual(1, response["toolActionIntake"]["acceptedToolPlanCount"])
        self.assertEqual(1, response["toolExecutionReadiness"]["executableCount"])
        self.assertFalse(response["toolExecutionEnabled"])
        self.assertEqual(AgentRuntimeEventType.TOOL_ACTION_INTAKE_RECORDED.value, response["runtimeEvent"]["eventType"])
        self.assertEqual("request-route", response["runtimeEvent"]["requestId"])
        self.assertEqual("session-route", response["runtimeEvent"]["sessionId"])
        self.assertTrue(response["runtimeEventDelivery"]["stored"])
        self.assertEqual(1, response["runtimeEventDelivery"]["livePushedCount"])
        self.assertEqual(1, response["runtimeEventDelivery"]["publishedCount"])
        self.assertEqual(1, len(event_store.events))
        self.assertEqual(event_store.events, live_push_hub.events)
        self.assertEqual(event_store.events, event_publisher.events)
        event = event_store.events[0]
        self.assertEqual(AgentRuntimeEventType.TOOL_ACTION_INTAKE_RECORDED, event.event_type)
        self.assertEqual("tenant-route", event.tenant_id)
        self.assertEqual(1, event.attributes["acceptedToolPlanCount"])
        self.assertEqual(1, event.attributes["readinessExecutableCount"])
        self.assertFalse(event.attributes["graphToolExecuted"])
        self.assertNotIn("ds-route-secret", str(response))
        self.assertNotIn("ds-route-secret", str(event.attributes))

    def test_mcp_intake_event_delivery_fail_open(self) -> None:
        """事件旁路失败时，preview 响应仍应返回并暴露低敏 delivery 错误。"""

        app = FakeApp()
        register_agent_runtime_routes(
            app,
            request_type=object,
            orchestrator=object(),
            event_store=FailingEventStore(),
            session_manager=None,
            live_push_hub=None,
            event_publisher=None,
            runtime_event_replay_sources=(),
            plan_ingestion_client=None,
            control_plane_feedback_collector=None,
            runtime_event_feedback_bridge=None,
            loop_control_evaluator=None,
            second_turn_orchestrator=None,
            memory_write_governance=None,
            tool_registry=default_tool_registry(),
        )

        handler = app.post_routes["/agent/protocol-adapters/mcp/tools-call-intake-preview"]
        response = handler(
            {
                "method": "tools/call",
                "params": {
                    "name": "datasource.metadata.read",
                    "arguments": {"datasourceId": "ds-fail-open-secret"},
                },
            }
        )

        self.assertEqual(1, response["toolActionIntake"]["acceptedToolPlanCount"])
        self.assertFalse(response["runtimeEventDelivery"]["stored"])
        self.assertEqual("event_store", response["runtimeEventDelivery"]["errors"][0]["component"])
        self.assertEqual("RuntimeError", response["runtimeEventDelivery"]["errors"][0]["errorType"])
        self.assertNotIn("ds-fail-open-secret", str(response))


if __name__ == "__main__":
    unittest.main()
