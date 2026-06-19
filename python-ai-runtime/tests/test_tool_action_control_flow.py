import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.api.agent.routes import register_agent_runtime_routes
from datasmart_ai_runtime.api.agent.tool_action_control_flow import (
    build_tool_action_control_flow_preview_response,
)
from datasmart_ai_runtime.config import default_tool_registry
from datasmart_ai_runtime.domain.contracts import ModelToolCall
from datasmart_ai_runtime.services.tools import InMemoryToolActionExecutionCheckpointStore, ToolActionControlFlowService


class FakeApp:
    """极简路由注册器。

    本测试不启动 FastAPI，只验证 `register_agent_runtime_routes` 是否把 handler 挂到预期路径上。
    这样既能保护 API 契约，又不会让核心测试依赖真实 HTTP server。
    """

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


class ToolActionControlFlowServiceTest(unittest.TestCase):
    """工具动作控制流预览测试。

    这组测试保护的是“执行前控制流”，不是工具执行：
    - 模型 tool_call、MCP tools/call、A2A task/action 都能进入统一响应结构；
    - READY / DRAFT / A2A 决策都必须明确 previewOnly 和 toolExecutionEnabled=false；
    - 原始参数值、prompt、SQL、内部 endpoint、secret 不允许出现在响应中。
    """

    def setUp(self) -> None:
        self.tools = default_tool_registry()
        self.service = ToolActionControlFlowService()

    def test_model_tool_call_builds_control_flow_without_leaking_arguments(self) -> None:
        """模型 tool_call 应进入统一控制流，并只展示字段名。"""

        report = self.service.from_model_tool_calls(
            (
                ModelToolCall(
                    call_id="model-call-001",
                    name="quality_rule_suggest",
                    arguments='{"datasourceId":"ds-model-secret","businessGoal":"客户手机号唯一性"}',
                ),
            ),
            registered_tools=self.tools,
            visible_tools=tuple(tool for tool in self.tools if tool.name == "quality.rule.suggest"),
        )
        response = report.to_low_sensitive_response()
        serialized = str(response)

        self.assertEqual("MODEL_TOOL_CALL", response["source"])
        self.assertEqual("MODEL", response["protocolFamily"])
        self.assertTrue(response["previewOnly"])
        self.assertFalse(response["toolExecutionEnabled"])
        self.assertEqual(1, response["toolActionIntake"]["acceptedToolPlanCount"])
        self.assertEqual(1, response["toolExecutionReadiness"]["draftOnlyCount"])
        self.assertEqual("PRE_EXECUTION_CONDITION_GRAPH_ONLY", response["toolExecutionReadinessGraph"]["executionBoundary"])
        self.assertFalse(response["executionContract"]["toolExecuted"])
        templates = response["toolActionCommandProposalTemplates"]
        self.assertEqual(1, templates["totalTemplateCount"])
        self.assertEqual(0, templates["outboxPreflightCandidateCount"])
        self.assertEqual("SHOW_DRAFT_AND_WAIT_FOR_REVIEW", templates["templates"][0]["nextAction"])
        self.assertNotIn("ds-model-secret", serialized)
        self.assertNotIn("客户手机号唯一性", serialized)

    def test_mcp_tools_call_builds_same_control_flow_and_preserves_mcp_requirements(self) -> None:
        """MCP tools/call 应复用同一控制流，同时保留 MCP 生产化缺口提示。"""

        response = build_tool_action_control_flow_preview_response(
            {
                "source": "MCP_TOOLS_CALL",
                "jsonrpc": "2.0",
                "id": "rpc-control-flow-001",
                "method": "tools/call",
                "params": {
                    "name": "datasource.metadata.read",
                    "arguments": {"datasourceId": "ds-mcp-secret"},
                },
                "visibleToolNames": ["datasource.metadata.read"],
                "context": {
                    "tenantId": "tenant-control-flow",
                    "projectId": "project-control-flow",
                    "actorId": "actor-control-flow",
                    "requestId": "request-control-flow",
                    "runId": "run-control-flow",
                    "sessionId": "session-control-flow",
                    "policyVersion": "tool-readiness-policy.v1",
                    "clientRequestId": "client-control-flow",
                },
                "prompt": "MCP 原始 prompt 不能进入响应",
                "sql": "select * from hidden_table",
                "targetEndpoint": "http://internal-mcp.local/tools",
            },
            registered_tools=self.tools,
        )
        serialized = str(response)

        self.assertEqual("MCP_TOOLS_CALL", response["source"])
        self.assertEqual("MCP", response["protocolFamily"])
        self.assertEqual(1, response["toolExecutionReadiness"]["executableCount"])
        self.assertIn("READY_TO_EXECUTE", response["toolExecutionReadinessGraph"]["branchCounts"])
        self.assertIn("MCP_JSON_RPC_SERVER_AND_SESSION_LIFECYCLE", response["productionReadiness"]["missingProductionRequirements"])
        templates = response["toolActionCommandProposalTemplates"]
        self.assertEqual(1, templates["totalTemplateCount"])
        self.assertEqual(1, templates["outboxPreflightCandidateCount"])
        template = templates["templates"][0]
        self.assertTrue(template["outboxPreflightCandidate"])
        self.assertEqual("CALL_JAVA_COMMAND_PROPOSAL_AFTER_GRAPH_AND_PAYLOAD_REFERENCE_READY", template["nextAction"])
        graph_run = response["toolActionExecutionGraphRun"]
        self.assertEqual("PRE_EXECUTION_GRAPH_RUNNER_ONLY", graph_run["executionBoundary"])
        self.assertEqual("WAITING_COMMAND_PROPOSAL_EVIDENCE", graph_run["steps"][0]["stepStatus"])
        self.assertIn("GRAPH_ID_OR_CONTRACT_ID_REQUIRED", template["missingBeforeJavaProposal"])
        self.assertIn("PAYLOAD_REFERENCE_REQUIRED", template["missingBeforeJavaProposal"])
        self.assertNotIn("POLICY_VERSION_REQUIRED", template["missingBeforeJavaProposal"])
        self.assertEqual("tenant-control-flow", template["requestBodyTemplate"]["tenantId"])
        self.assertEqual("run-control-flow", template["requestBodyTemplate"]["runId"])
        self.assertEqual("tool-readiness-policy.v1", template["requestBodyTemplate"]["policyVersion"])
        self.assertIsNone(template["requestBodyTemplate"]["payloadReference"])
        self.assertNotIn("ds-mcp-secret", serialized)
        self.assertNotIn("MCP 原始 prompt", serialized)
        self.assertNotIn("hidden_table", serialized)
        self.assertNotIn("internal-mcp", serialized)

    def test_a2a_task_action_stays_control_plane_decision_with_empty_readiness_graph(self) -> None:
        """A2A task/action 应保留为控制面决策，而不是伪装成 ToolPlan。"""

        response = build_tool_action_control_flow_preview_response(
            {
                "source": "A2A_TASK_ACTION",
                "contract": {
                    "schemaVersion": "datasmart.agent-runtime.a2a-task-query-preview.v1",
                    "previewOnly": True,
                    "task": {
                        "taskPublicId": "task_pub_control_flow",
                        "contextPublicId": "ctx_pub_control_flow",
                        "currentState": "TASK_STATE_SUBMITTED",
                        "internalPhase": "POLICY_PRECHECK",
                        "sequence": 1,
                    },
                    "prompt": "A2A 用户原文不能进入响应",
                    "toolArguments": {"datasourceId": "ds-a2a-secret"},
                    "targetEndpoint": "http://internal-a2a.local/run",
                },
            },
            registered_tools=self.tools,
        )
        serialized = str(response)

        self.assertEqual("A2A_TASK_ACTION", response["source"])
        self.assertEqual("A2A", response["protocolFamily"])
        self.assertEqual(0, response["toolExecutionReadiness"]["totalCount"])
        self.assertIn("NO_TOOL_PLAN", response["toolExecutionReadinessGraph"]["branchCounts"])
        self.assertEqual("A2A_TASK_CONTROL_PLANE_DECISION", response["controlPlaneDecision"]["boundary"])
        self.assertEqual("PRECHECK_REQUIRED", response["controlPlaneDecision"]["mode"])
        self.assertIn("REQUEST_PERMISSION_PRECHECK", response["controlPlaneDecision"]["suggestedActions"])
        self.assertEqual(0, response["toolActionCommandProposalTemplates"]["totalTemplateCount"])
        self.assertEqual(0, response["toolActionCommandProposalTemplates"]["outboxPreflightCandidateCount"])
        self.assertEqual(0, response["toolActionExecutionGraphRun"]["stepCount"])
        self.assertNotIn("A2A 用户原文", serialized)
        self.assertNotIn("ds-a2a-secret", serialized)
        self.assertNotIn("internal-a2a", serialized)

    def test_control_flow_preview_uses_injected_checkpoint_store(self) -> None:
        """控制流预览应把执行图 checkpoint 写入调用方注入的 store。

        这条测试对应生产装配中的关键链路：FastAPI 启动阶段创建一个共享 store，然后 control-flow-preview
        保存 checkpoint，checkpoint query/resume-preview 再读取同一个 store。只要这里验证注入 store 生效，
        后续把底层从 in-memory 切到 Redis 时，上层 API 就不需要再改变。
        """

        checkpoint_store = InMemoryToolActionExecutionCheckpointStore(max_checkpoints_per_thread=5, max_total_checkpoints=20)
        response = build_tool_action_control_flow_preview_response(
            {
                "source": "MCP_TOOLS_CALL",
                "method": "tools/call",
                "params": {
                    "name": "datasource.metadata.read",
                    "arguments": {"datasourceId": "ds-injected-secret"},
                },
                "visibleToolNames": ["datasource.metadata.read"],
                "context": {
                    "tenantId": "tenant-injected-store",
                    "runId": "run-injected-store",
                    "policyVersion": "tool-readiness-policy.v1",
                },
            },
            registered_tools=self.tools,
            checkpoint_store=checkpoint_store,
        )

        checkpoint_id = response["toolActionExecutionGraphRun"]["checkpoint"]["checkpointId"]
        saved = checkpoint_store.get(checkpoint_id)

        self.assertIsNotNone(saved)
        self.assertEqual("run-injected-store", saved.thread_id)
        self.assertNotIn("ds-injected-secret", str(saved.to_summary(include_graph_run=True)))

    def test_agent_routes_register_unified_control_flow_preview(self) -> None:
        """Agent 路由注册函数应暴露统一控制流预览入口。"""

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
            tool_registry=self.tools,
        )

        handler = app.post_routes["/agent/tool-actions/control-flow-preview"]
        response = handler(
            {
                "toolCalls": [
                    {
                        "id": "route-tool-call-001",
                        "function": {
                            "name": "datasource_metadata_read",
                            "arguments": {"datasourceId": "ds-route-control-flow-secret"},
                        },
                    }
                ],
                "visibleToolNames": ["datasource.metadata.read"],
            }
        )

        self.assertEqual("MODEL_TOOL_CALL", response["source"])
        self.assertEqual(1, response["toolExecutionReadiness"]["executableCount"])
        self.assertFalse(response["toolExecutionEnabled"])
        self.assertEqual(1, response["toolActionCommandProposalTemplates"]["outboxPreflightCandidateCount"])
        self.assertNotIn("ds-route-control-flow-secret", str(response))


if __name__ == "__main__":
    unittest.main()
