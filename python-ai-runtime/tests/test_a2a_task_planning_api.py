import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.api.agent.a2a_task_planning import build_a2a_task_planning_preview_response
from datasmart_ai_runtime.api.agent.routes import register_agent_runtime_routes
from datasmart_ai_runtime.domain.protocols import AgentTaskPlanningMode


class FakeApp:
    """极简路由注册器，用于在不安装 FastAPI 的情况下验证 API handler。"""

    def __init__(self) -> None:
        self.get_routes: dict[str, object] = {}
        self.post_routes: dict[str, object] = {}
        self.websocket_routes: dict[str, object] = {}

    def get(self, path: str):
        """记录 GET 路由装饰器。"""

        def decorator(func):
            self.get_routes[path] = func
            return func

        return decorator

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


class A2aTaskPlanningApiTest(unittest.TestCase):
    """A2A task planning preview API 契约测试。

    这里不启动 FastAPI，也不访问 Java 控制面。我们只验证 API helper 与路由注册的稳定契约：
    - route 必须能注册；
    - 响应必须明确 previewOnly 和 taskExecutionEnabled=false；
    - planningDecision 能反映 A2A task 状态；
    - 原始输入中的 prompt、工具参数、内部 endpoint、SQL 和 secret 不能被回显。
    """

    def test_preview_response_wraps_planning_decision_without_echoing_payload(self) -> None:
        """API helper 应包装 planning decision，并隐藏原始 payload。"""

        response = build_a2a_task_planning_preview_response(
            {
                "contract": {
                    "scenario": "auth-required",
                    "prompt": "原始用户请求不能回显",
                    "task": {
                        "taskPublicId": "task_pub_auth",
                        "currentState": "TASK_STATE_AUTH_REQUIRED",
                        "internalPhase": "APPROVAL_WAITING",
                    },
                    "toolArguments": {"datasourceId": "ds-secret"},
                    "targetEndpoint": "http://internal-service/tools",
                    "sql": "select * from hidden_table",
                    "secret": "hidden-secret",
                },
                "traceId": "trace-local-test",
            }
        )
        serialized = str(response)

        self.assertEqual("datasmart.python-ai-runtime.a2a-task-planning-preview.v1", response["schemaVersion"])
        self.assertTrue(response["previewOnly"])
        self.assertFalse(response["taskExecutionEnabled"])
        self.assertEqual(AgentTaskPlanningMode.WAIT_FOR_AUTHORIZATION.value, response["planningDecision"]["mode"])
        self.assertFalse(response["productionReadiness"]["readyForExecution"])
        self.assertIn("PERMISSION_ADMIN_SCOPE_CHECK", response["productionReadiness"]["missingProductionRequirements"])
        self.assertNotIn("原始用户请求", serialized)
        self.assertNotIn("ds-secret", serialized)
        self.assertNotIn("internal-service", serialized)
        self.assertNotIn("hidden_table", serialized)
        self.assertNotIn("hidden-secret", serialized)
        self.assertNotIn("prompt", serialized.lower())
        self.assertNotIn("toolarguments", serialized.lower())
        self.assertNotIn("targetendpoint", serialized.lower())

    def test_agent_routes_register_a2a_task_planning_preview(self) -> None:
        """Agent 路由注册函数应暴露 A2A task planning preview POST 入口。"""

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

        handler = app.post_routes["/agent/protocol-adapters/a2a/task-planning-preview"]
        response = handler(
            {
                "task": {
                    "taskPublicId": "task_pub_done",
                    "currentState": "TASK_STATE_COMPLETED",
                    "internalPhase": "RESULT_READY",
                    "terminal": True,
                },
                "artifactReferences": [
                    {
                        "artifactRef": "artifact_ref_done",
                        "artifactType": "quality-report",
                        "available": True,
                        "metadataOnly": True,
                    }
                ],
            }
        )

        self.assertEqual(AgentTaskPlanningMode.TERMINAL_NO_EXECUTION.value, response["planningDecision"]["mode"])
        self.assertIn("artifact_ref_done", str(response["planningDecision"]["snapshot"]["artifactReferences"]))
        self.assertFalse(response["taskExecutionEnabled"])


if __name__ == "__main__":
    unittest.main()
