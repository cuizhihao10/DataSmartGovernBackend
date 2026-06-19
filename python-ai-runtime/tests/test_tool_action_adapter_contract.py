import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.api.agent.routes import register_agent_runtime_routes
from datasmart_ai_runtime.api.agent.tool_action_adapter_contract import (
    build_tool_action_adapter_contracts_response,
)
from datasmart_ai_runtime.api.agent.tool_action_control_flow import build_tool_action_control_flow_preview_response
from datasmart_ai_runtime.config import default_tool_registry
from datasmart_ai_runtime.services.tools import (
    ToolActionAdapterStage,
    ToolActionIntakeBoundary,
    ToolActionIntakeSource,
    default_tool_action_adapter_contract_registry,
)


class FakeApp:
    """极简路由注册器，用于验证 contract GET 路由。"""

    def __init__(self) -> None:
        self.get_routes: dict[str, object] = {}
        self.post_routes: dict[str, object] = {}
        self.websocket_routes: dict[str, object] = {}

    def get(self, path: str):
        """模拟 FastAPI 的 `@app.get(...)` 装饰器。"""

        def decorator(func):
            self.get_routes[path] = func
            return func

        return decorator

    def post(self, path: str):
        """模拟 FastAPI 的 `@app.post(...)` 装饰器。"""

        def decorator(func):
            self.post_routes[path] = func
            return func

        return decorator

    def websocket(self, path: str):
        """模拟 FastAPI 的 `@app.websocket(...)` 装饰器。"""

        def decorator(func):
            self.websocket_routes[path] = func
            return func

        return decorator


class ToolActionAdapterContractTest(unittest.TestCase):
    """工具动作 Adapter Contract 测试。

    这组测试保护的是“入口契约收敛”，不是工具执行：
    - 模型 tool_call、MCP tools/call、A2A task/action 都必须有显式 contract；
    - MCP 和模型来源都进入 ToolPlan/readiness graph，A2A 保持控制面决策；
    - contract 与 control-flow 响应都不能泄露工具参数、prompt、SQL、内部地址或凭证。
    """

    def test_registry_exposes_contracts_for_all_known_sources(self) -> None:
        """注册表应覆盖当前所有工具动作来源。"""

        diagnostics = default_tool_action_adapter_contract_registry().diagnostics()
        contracts = {item["source"]: item for item in diagnostics["contracts"]}

        self.assertEqual("datasmart.tool-action-adapter-contract-registry.v1", diagnostics["schemaVersion"])
        self.assertIn(ToolActionIntakeSource.MODEL_TOOL_CALL.value, contracts)
        self.assertIn(ToolActionIntakeSource.MCP_TOOLS_CALL.value, contracts)
        self.assertIn(ToolActionIntakeSource.A2A_TASK_ACTION.value, contracts)
        self.assertEqual(
            ToolActionIntakeBoundary.TOOL_PLAN_READINESS_GRAPH.value,
            contracts[ToolActionIntakeSource.MCP_TOOLS_CALL.value]["normalizationTarget"],
        )
        self.assertEqual(
            ToolActionIntakeBoundary.A2A_TASK_CONTROL_PLANE_DECISION.value,
            contracts[ToolActionIntakeSource.A2A_TASK_ACTION.value]["normalizationTarget"],
        )
        self.assertIn(
            ToolActionAdapterStage.DURABLE_OUTBOX.value,
            contracts[ToolActionIntakeSource.MCP_TOOLS_CALL.value]["requiredStages"],
        )
        self.assertIn(
            ToolActionAdapterStage.TASK_MANAGEMENT_BINDING.value,
            contracts[ToolActionIntakeSource.A2A_TASK_ACTION.value]["requiredStages"],
        )

    def test_api_helper_can_filter_mcp_contract_without_sensitive_runtime_data(self) -> None:
        """只查 MCP contract 时，也只能返回低敏契约元数据。"""

        response = build_tool_action_adapter_contracts_response({"source": "mcp"})
        serialized = str(response).lower()

        self.assertEqual(1, response["contractCount"])
        contract = response["contracts"][0]
        self.assertEqual("datasmart.adapter.mcp-tools-call.v1", contract["adapterId"])
        self.assertIn("JSON-RPC 2.0 method=tools/call params={name, arguments}", contract["supportedInputShapes"])
        self.assertFalse(any("execute" == item.lower() for item in contract["sideEffectBoundary"]))
        self.assertNotIn("ds-secret", serialized)
        self.assertNotIn("raw prompt", serialized)
        self.assertNotIn("select * from", serialized)
        self.assertNotIn("internal-service", serialized)
        self.assertNotIn("api_key", serialized)
        self.assertNotIn("secret", serialized)
        self.assertNotIn("token", serialized)

    def test_control_flow_response_embeds_adapter_contract(self) -> None:
        """统一控制流响应应携带当前来源的 adapter contract 摘要。"""

        response = build_tool_action_control_flow_preview_response(
            {
                "source": "MCP_TOOLS_CALL",
                "method": "tools/call",
                "params": {
                    "name": "datasource.metadata.read",
                    "arguments": {"datasourceId": "ds-contract-secret"},
                },
                "visibleToolNames": ["datasource.metadata.read"],
                "prompt": "raw prompt should stay hidden",
                "sql": "select * from hidden_table",
                "targetEndpoint": "http://internal-service.local/tools",
            },
            registered_tools=default_tool_registry(),
        )
        serialized = str(response)
        contract = response["toolActionAdapterContract"]

        self.assertEqual("datasmart.adapter.mcp-tools-call.v1", contract["adapterId"])
        self.assertEqual("MCP_TOOLS_CALL", contract["source"])
        self.assertEqual("TOOL_PLAN_READINESS_GRAPH", contract["normalizationTarget"])
        self.assertIn("DURABLE_OUTBOX", contract["requiredStages"])
        self.assertIn("WORKER_RECEIPT", contract["requiredStages"])
        self.assertFalse(response["executionContract"]["toolExecuted"])
        self.assertNotIn("ds-contract-secret", serialized)
        self.assertNotIn("raw prompt should stay hidden", serialized)
        self.assertNotIn("hidden_table", serialized)
        self.assertNotIn("internal-service", serialized)

    def test_agent_routes_register_adapter_contract_query(self) -> None:
        """Agent 路由注册函数应暴露 adapter contract 查询入口。"""

        app = FakeApp()
        register_agent_runtime_routes(
            app,
            request_type=object,
            orchestrator=object(),
            event_store=None,
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
        )

        self.assertIn("/agent/tool-actions/adapter-contracts", app.get_routes)
        response = app.get_routes["/agent/tool-actions/adapter-contracts"](source="A2A")

        self.assertEqual(1, response["contractCount"])
        self.assertEqual("datasmart.adapter.a2a-task-action.v1", response["contracts"][0]["adapterId"])
        self.assertEqual("A2A_TASK_CONTROL_PLANE_DECISION", response["contracts"][0]["normalizationTarget"])


if __name__ == "__main__":
    unittest.main()
