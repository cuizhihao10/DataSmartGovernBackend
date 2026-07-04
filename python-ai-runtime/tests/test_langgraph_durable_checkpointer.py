"""LangGraph Durable Checkpointer 状态机能力测试。

这些测试先覆盖项目级 checkpointer 合同，不依赖真实 PostgreSQL 或 LangGraph 包：
- 暂停后可恢复；
- 可以从任意 checkpoint 创建分支；
- 循环通过版本推进而不是递归覆盖；
- 多 Agent 状态可从最新 checkpoint 中恢复为低敏摘要。
"""

import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.services.agent_execution import (
    InMemoryLangGraphCheckpointStore,
    LangGraphCheckpointStatus,
    LangGraphDurableCheckpoint,
    LangGraphDurableCheckpointerService,
    build_langgraph_checkpoint_store,
    langgraph_checkpoint_store_diagnostics,
    langgraph_durable_checkpointer_settings_from_env,
)


class LangGraphDurableCheckpointerTest(unittest.TestCase):
    """验证 LangGraph checkpoint 的暂停、恢复、分支、循环与多 Agent 恢复。"""

    def test_pause_resume_branch_and_loop_are_recorded_as_state_machine_events(self) -> None:
        """状态机动作应生成新 checkpoint 版本和低敏事件序列。"""

        service = LangGraphDurableCheckpointerService()
        root = service.record_checkpoint(self._root_checkpoint())

        paused = service.pause(
            thread_id=root.thread_id,
            reason_code="WAIT_PERMISSION_APPROVAL",
            resume_requirements={"approvalFact": "required", "workerReceipt": "required"},
        )
        resumed = service.resume(
            thread_id=root.thread_id,
            resume_facts={"approvalFact": "approved", "operatorNote": "低敏确认"},
        )
        looped = service.record_loop_iteration(
            thread_id=root.thread_id,
            node_name="route_next_agent",
            edge_name="quality_to_permission_guard",
            state_patch={"lastEdge": "quality_to_permission_guard"},
        )
        branch = service.fork_branch(
            parent_checkpoint_id=paused.checkpoint_id,
            branch_name="retry-with-human-approval",
            next_nodes=("permission_gate",),
        )

        self.assertEqual(LangGraphCheckpointStatus.PAUSED, paused.status)
        self.assertEqual(LangGraphCheckpointStatus.RUNNING, resumed.status)
        self.assertEqual(root.checkpoint_id, paused.parent_checkpoint_id)
        self.assertEqual(resumed.checkpoint_id, looped.parent_checkpoint_id)
        self.assertEqual(4, looped.checkpoint_version)
        self.assertEqual(1, branch.checkpoint_version)
        self.assertEqual(paused.checkpoint_id, branch.parent_checkpoint_id)
        self.assertIn(":branch:retry-with-human-approval", branch.thread_id)
        main_events = service.events_for_thread(root.thread_id)
        self.assertEqual(("checkpoint_saved", "paused", "resumed", "loop_iteration", "branch_created"), tuple(event.event_type for event in main_events))
        self.assertEqual((1, 2, 3, 4, 5), tuple(event.sequence_number for event in main_events))

    def test_recovers_multi_agent_state_from_latest_checkpoint(self) -> None:
        """多 Agent 状态恢复应只返回角色、状态、边数量和 handoff 标记。"""

        service = LangGraphDurableCheckpointerService()
        service.record_checkpoint(
            LangGraphDurableCheckpoint(
                checkpoint_id="lgcp:multi:001",
                thread_id="thread-multi",
                graph_name="datasmart.multi-agent.execution",
                graph_version="v1",
                node_name="build_collaboration_edges",
                status=LangGraphCheckpointStatus.WAITING_TOOL,
                tenant_id="10",
                project_id="20",
                run_id="run-multi",
                state={
                    "multiAgentState": {
                        "MASTER_ORCHESTRATOR": {"status": "WAITING_SPECIALIST"},
                        "DATA_QUALITY_AGENT": {"status": "RUNNING"},
                        "PERMISSION_AGENT": {"status": "WAITING_APPROVAL"},
                    },
                    "collaborationEdges": (
                        {"fromRole": "DATA_QUALITY_AGENT", "toRole": "PERMISSION_AGENT", "edgeType": "guarded_by"},
                    ),
                    "handoffRequired": True,
                    "prompt": "should-not-be-returned",
                },
                next_nodes=("permission_gate",),
            )
        )

        recovered = service.recover_multi_agent_state("thread-multi").to_summary()

        self.assertTrue(recovered["found"])
        self.assertEqual(("DATA_QUALITY_AGENT", "MASTER_ORCHESTRATOR", "PERMISSION_AGENT"), recovered["agentRoles"])
        self.assertEqual("WAITING_APPROVAL", recovered["agentStatuses"]["PERMISSION_AGENT"])
        self.assertEqual(1, recovered["collaborationEdgeCount"])
        self.assertTrue(recovered["handoffRequired"])
        self.assertNotIn("prompt", str(recovered))

    def test_component_defaults_to_in_memory_and_diagnostics_are_low_sensitive(self) -> None:
        """默认组件应零依赖启动，并暴露低敏诊断。"""

        settings = langgraph_durable_checkpointer_settings_from_env({})
        store = build_langgraph_checkpoint_store(settings)
        diagnostics = langgraph_checkpoint_store_diagnostics(store, settings)

        self.assertIsInstance(store, InMemoryLangGraphCheckpointStore)
        self.assertEqual("in-memory", diagnostics["configuredType"])
        self.assertFalse(diagnostics["sensitiveDataPolicy"]["rawPromptStored"])

    @staticmethod
    def _root_checkpoint() -> LangGraphDurableCheckpoint:
        """构造根 checkpoint。"""

        return LangGraphDurableCheckpoint(
            checkpoint_id="lgcp:thread-a:root",
            thread_id="thread-a",
            tenant_id="10",
            project_id="20",
            actor_id="30",
            workspace_key="tenant:10:project:20",
            run_id="run-a",
            session_id="session-a",
            graph_name="datasmart.agent.mcp-feedback-loop",
            graph_version="v1",
            node_name="model_feedback",
            status=LangGraphCheckpointStatus.RUNNING,
            state={
                "currentAgent": "MASTER_ORCHESTRATOR",
                "multiAgentState": {"MASTER_ORCHESTRATOR": {"status": "RUNNING"}},
            },
            next_nodes=("permission_gate", "model_second_turn"),
        )


if __name__ == "__main__":
    unittest.main()
