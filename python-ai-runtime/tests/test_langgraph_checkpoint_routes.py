"""LangGraph checkpoint 控制面路由测试。

这些测试不启动 FastAPI，也不连接 PostgreSQL。目标是验证 API 层的最小闭环：
- 可以读取最新 checkpoint 与事件流；
- 可以暂停、恢复、分支；
- 可以通过 API 恢复多 Agent 状态摘要；
- 返回值只包含 summary，不返回 checkpoint state 正文。
"""

import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.api.agent.langgraph_checkpoints import register_langgraph_checkpoint_routes
from datasmart_ai_runtime.services.agent_execution import (
    LangGraphCheckpointStatus,
    LangGraphDurableCheckpoint,
    LangGraphDurableCheckpointerService,
)


class LangGraphCheckpointRoutesTest(unittest.TestCase):
    """验证 LangGraph checkpoint 查询与控制路由。"""

    def test_routes_query_pause_resume_fork_and_recover_multi_agent_state(self) -> None:
        """控制面路由应复用同一个 checkpointer，并始终返回低敏摘要。"""

        app = FakeApp()
        service = LangGraphDurableCheckpointerService()
        root = service.record_checkpoint(self._root_checkpoint())
        register_langgraph_checkpoint_routes(app, checkpointer_service=service)

        latest = app.get_routes["/agent/langgraph/checkpoints/latest"](threadId=root.thread_id)
        paused = app.post_routes["/agent/langgraph/checkpoints/pause"](
            {
                "threadId": root.thread_id,
                "reasonCode": "WAIT_OPERATOR_CONFIRMATION",
                "resumeRequirements": {"operatorApproval": "required"},
            }
        )
        resumed = app.post_routes["/agent/langgraph/checkpoints/resume"](
            {
                "threadId": root.thread_id,
                "resumeFacts": {"operatorApproval": "approved"},
            }
        )
        forked = app.post_routes["/agent/langgraph/checkpoints/fork"](
            {
                "parentCheckpointId": paused["checkpoint"]["checkpointId"],
                "branchName": "retry-with-quality-agent",
                "nextNodes": ["data_quality_agent"],
            }
        )
        recovered = app.post_routes["/agent/langgraph/checkpoints/recover/multi-agent"](
            {"threadId": root.thread_id}
        )
        events = app.get_routes["/agent/langgraph/checkpoints/events"](threadId=root.thread_id)

        self.assertTrue(latest["found"])
        self.assertEqual("paused", paused["checkpoint"]["status"])
        self.assertEqual("running", resumed["checkpoint"]["status"])
        self.assertTrue(forked["forked"])
        self.assertIn(":branch:retry-with-quality-agent", forked["checkpoint"]["threadId"])
        self.assertTrue(recovered["recovered"]["found"])
        self.assertIn("MASTER_ORCHESTRATOR", recovered["recovered"]["agentRoles"])
        self.assertGreaterEqual(events["eventCount"], 4)
        response_text = str((latest, paused, resumed, forked, recovered, events))
        self.assertNotIn("should-not-be-returned", response_text)

    @staticmethod
    def _root_checkpoint() -> LangGraphDurableCheckpoint:
        """构造包含多 Agent 摘要的根 checkpoint。"""

        return LangGraphDurableCheckpoint(
            checkpoint_id="lgcp:routes:root",
            thread_id="thread-routes",
            tenant_id="10",
            project_id="20",
            actor_id="30",
            workspace_key="tenant:10:project:20",
            run_id="run-routes",
            session_id="session-routes",
            graph_name="datasmart.agent.multi-agent-runtime",
            graph_version="v1",
            node_name="master_orchestrator",
            status=LangGraphCheckpointStatus.RUNNING,
            state={
                "multiAgentState": {
                    "MASTER_ORCHESTRATOR": {"status": "RUNNING"},
                    "DATA_QUALITY_AGENT": {"status": "WAITING_TASK"},
                },
                "collaborationEdges": (
                    {"fromRole": "MASTER_ORCHESTRATOR", "toRole": "DATA_QUALITY_AGENT"},
                ),
                "prompt": "should-not-be-returned",
            },
            next_nodes=("data_quality_agent",),
        )


class FakeApp:
    """极简 FastAPI 替身，用于捕获 get/post decorator 注册的 handler。"""

    def __init__(self) -> None:
        self.get_routes = {}
        self.post_routes = {}

    def get(self, path):
        """模拟 FastAPI get decorator。"""

        def decorator(handler):
            self.get_routes[path] = handler
            return handler

        return decorator

    def post(self, path):
        """模拟 FastAPI post decorator。"""

        def decorator(handler):
            self.post_routes[path] = handler
            return handler

        return decorator


if __name__ == "__main__":
    unittest.main()
