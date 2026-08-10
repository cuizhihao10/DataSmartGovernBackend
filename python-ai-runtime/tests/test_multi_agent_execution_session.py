import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.domain.contracts import AgentPlan, AgentRequest
from datasmart_ai_runtime.services.multi_agent import MultiAgentExecutionSessionService


class MultiAgentExecutionSessionTest(unittest.TestCase):
    """受控多 Agent 执行会话测试。

    这组测试保护的是“多 Agent 能力从执行前计划推进到会话状态”这一层语义。会话应该能告诉调用方：
    - 哪些 Agent 当前 active；
    - 哪些必做 Agent 处于 standby；
    - 每个 Agent 下一步应该等待审批、控制面反馈、二轮推理还是 Java handoff；
    - 响应里不能泄露用户目标、工具参数、SQL、样本数据或模型输出。
    """

    def test_builds_controlled_session_from_execution_plan_and_durable_loop(self) -> None:
        """执行前计划和 Durable Loop 摘要应组合成低敏受控会话。"""

        session = MultiAgentExecutionSessionService().build(
            request=_request(),
            plan=_plan(),
            scheduling={},
            collaboration_execution_plan=_execution_plan_summary(),
            durable_loop=_durable_loop_summary(),
        )
        summary = session.to_summary()
        serialized = str(summary)

        self.assertEqual("datasmart.multi-agent.execution-session.v1", summary["schemaVersion"])
        self.assertEqual("WAITING_APPROVAL_OR_HANDOFF", summary["status"])
        self.assertEqual("waiting_control_plane", summary["durablePhase"])
        self.assertEqual("LOW_SENSITIVE_MULTI_AGENT_EXECUTION_SESSION_ONLY", summary["payloadPolicy"])
        self.assertEqual("CONTROLLED_MULTI_AGENT_SESSION_NO_SIDE_EFFECTS", summary["executionBoundary"])
        self.assertFalse(summary["sideEffectBoundary"]["toolExecutedByPython"])
        self.assertFalse(summary["sideEffectBoundary"]["outboxWrittenByPython"])
        self.assertFalse(summary["sideEffectBoundary"]["approvalCreatedByPython"])
        self.assertTrue(summary["sideEffectBoundary"]["javaControlPlaneRequiredForSideEffects"])
        self.assertEqual(3, summary["workItemCount"])
        self.assertIn("MASTER_ORCHESTRATOR", summary["activeRoles"])
        self.assertIn("DATA_QUALITY_AGENT", summary["activeRoles"])
        self.assertIn("PERMISSION_AGENT", summary["activeRoles"])
        self.assertIn("DATASOURCE_AGENT", summary["rosterCoverage"]["standbyMustDoRoles"])
        self.assertIn("TASK_AGENT", summary["rosterCoverage"]["standbyMustDoRoles"])
        self.assertTrue(
            any(item["resumeAction"] == "WAIT_FOR_APPROVAL_OR_HANDOFF_FACT" for item in summary["workItems"])
        )
        self.assertNotIn("secret objective", serialized)
        self.assertNotIn("secret-datasource-id", serialized)
        self.assertNotIn("select * from customer", serialized.lower())

    def test_second_turn_completed_does_not_mark_unexecuted_specialists_complete(self) -> None:
        """全局第二轮完成只代表主控汇总，未执行 Specialist 仍必须可推进。

        如果把全局 Durable phase 直接复制给每个 work item，协调器会跳过
        尚未获得 turn 的 Specialist。这个用例锁住“主控已汇总”和“专业角色
        已实际执行”之间的区别，防止 API 展示出假的完成状态。
        """

        session = MultiAgentExecutionSessionService().build(
            request=_request(),
            plan=_plan(),
            scheduling={},
            collaboration_execution_plan={
                "planStatus": "SECOND_TURN_COMPLETED",
                "workItems": (
                    {"agentRole": "MASTER_ORCHESTRATOR", "participationMode": "PRIMARY"},
                    {"agentRole": "PRECHECK_AGENT", "participationMode": "SPECIALIST"},
                ),
            },
            durable_loop={"phase": "second_turn_completed", "runId": "run-second-turn"},
        )

        summary = session.to_summary()
        by_role = {item["agentRole"]: item for item in summary["workItems"]}
        self.assertEqual("COMPLETED_OR_SUMMARIZED", by_role["MASTER_ORCHESTRATOR"]["sessionStatus"])
        self.assertEqual("READY_FOR_AGENT_TURN", by_role["PRECHECK_AGENT"]["sessionStatus"])

    def test_plan_created_allows_checkpointed_specialist_turn_without_legacy_tool_feedback(self) -> None:
        """A read-only Specialist can start when no legacy Java ToolPlan is awaiting feedback."""

        session = MultiAgentExecutionSessionService().build(
            request=_request(),
            plan=_plan(),
            scheduling={
                "agentTiers": {"KNOWLEDGE_AGENT": "must_do"},
                "scheduledAgents": ("KNOWLEDGE_AGENT",),
            },
            collaboration_execution_plan={
                "workItems": (
                    {"agentRole": "KNOWLEDGE_AGENT", "participationMode": "SPECIALIST"},
                ),
            },
            durable_loop={"phase": "plan_created", "runId": "run-specialist-rag"},
        )

        summary = session.to_summary()
        self.assertEqual("READY_FOR_AGENT_TURNS", summary["status"])
        self.assertEqual("READY_FOR_AGENT_TURN", summary["workItems"][0]["sessionStatus"])

    def test_waiting_master_tool_does_not_block_independent_specialist_analysis(self) -> None:
        """Specialists may analyze trusted context while a separate master ToolPlan awaits feedback.

        The global Durable phase describes the master loop, not proof that every Specialist needs the
        same tool receipt. Releasing only SPECIALIST/OBSERVER turns prevents a recovery deadlock while
        keeping the master coordinator and all eventual side effects behind the Java control plane.
        """

        session = MultiAgentExecutionSessionService().build(
            request=_request(),
            plan=_plan(),
            scheduling={},
            collaboration_execution_plan={
                "workItems": (
                    {"agentRole": "MASTER_ORCHESTRATOR", "participationMode": "PRIMARY"},
                    {"agentRole": "KNOWLEDGE_AGENT", "participationMode": "SPECIALIST"},
                    {"agentRole": "RECOVERY_AGENT", "participationMode": "SPECIALIST"},
                    {"agentRole": "MONITOR_AGENT", "participationMode": "OBSERVER"},
                ),
            },
            durable_loop={"phase": "waiting_control_plane", "runId": "run-recovery-bootstrap"},
        )

        summary = session.to_summary()
        by_role = {item["agentRole"]: item for item in summary["workItems"]}
        self.assertEqual(
            "WAITING_CONTROL_PLANE_FEEDBACK",
            by_role["MASTER_ORCHESTRATOR"]["sessionStatus"],
        )
        self.assertEqual("READY_FOR_AGENT_TURN", by_role["KNOWLEDGE_AGENT"]["sessionStatus"])
        self.assertEqual("READY_FOR_AGENT_TURN", by_role["RECOVERY_AGENT"]["sessionStatus"])
        self.assertEqual("READY_FOR_AGENT_TURN", by_role["MONITOR_AGENT"]["sessionStatus"])

    def test_specialist_turn_runs_before_its_high_risk_tool_handoff(self) -> None:
        """高风险输出需要审批，但 Specialist 的规划 turn 仍应先执行。

        DATA_SYNC_AGENT 需要先生成对象映射、字段映射和受控 ToolPlan，Java
        控制面才能向用户展示待审批内容。若在生成方案前就因为最终写工具
        需要审批而阻断，该审批页不会有任何可审核内容。
        """

        session = MultiAgentExecutionSessionService().build(
            request=_request(),
            plan=_plan(),
            scheduling={},
            collaboration_execution_plan={
                "planStatus": "WAITING_HUMAN_OR_PERMISSION_HANDOFF",
                "workItems": (
                    {
                        "agentRole": "MASTER_ORCHESTRATOR",
                        "participationMode": "PRIMARY",
                        "status": "WAITING_HUMAN_OR_PERMISSION_HANDOFF",
                        "handoffRequired": True,
                    },
                    {
                        "agentRole": "DATA_SYNC_AGENT",
                        "participationMode": "SPECIALIST",
                        "status": "WAITING_HUMAN_OR_PERMISSION_HANDOFF",
                        "handoffRequired": True,
                    },
                ),
            },
            durable_loop={"phase": "second_turn_completed", "runId": "run-high-risk-sync"},
        )

        summary = session.to_summary()
        by_role = {item["agentRole"]: item for item in summary["workItems"]}
        self.assertEqual("WAITING_APPROVAL_OR_HANDOFF", by_role["MASTER_ORCHESTRATOR"]["sessionStatus"])
        self.assertEqual("READY_FOR_AGENT_TURN", by_role["DATA_SYNC_AGENT"]["sessionStatus"])
        self.assertTrue(by_role["DATA_SYNC_AGENT"]["handoffRequired"])

    def test_falls_back_to_session_scheduling_when_execution_plan_disabled(self) -> None:
        """LangGraph 执行前计划禁用时，会话层应回退到调度视图而不是丢失多 Agent 骨架。"""

        session = MultiAgentExecutionSessionService().build(
            request=_request(),
            plan=_plan(),
            scheduling={
                "status": "READY",
                "participatingAgents": (
                    {"role": "MASTER_ORCHESTRATOR", "participationMode": "PRIMARY", "status": "READY"},
                    {"role": "TASK_AGENT", "participationMode": "SPECIALIST", "status": "READY"},
                ),
            },
            collaboration_execution_plan={"status": "DISABLED", "workItems": ()},
            durable_loop=None,
        )
        summary = session.to_summary()

        self.assertEqual("agent_session_scheduling_fallback", summary["source"])
        self.assertEqual("READY_FOR_CONTROL_PLANE_HANDOFF", summary["status"])
        self.assertIn("TASK_AGENT", summary["activeRoles"])
        self.assertEqual("not_recorded", summary["durablePhase"])


def _request() -> AgentRequest:
    return AgentRequest(
        tenant_id="tenant-a",
        project_id="project-a",
        actor_id="user-a",
        objective="secret objective",
        variables={"datasourceId": "secret-datasource-id", "sql": "select * from customer"},
    )


def _plan() -> AgentPlan:
    return AgentPlan(
        request_id="request-a",
        selected_route=None,
        state_trace=("receive_goal",),
        tool_plans=(),
        requires_human_approval=True,
        response_summary="测试计划",
    )


def _execution_plan_summary() -> dict:
    return {
        "planStatus": "WAITING_HUMAN_OR_PERMISSION_HANDOFF",
        "collaborationEdgeCount": 4,
        "handoffContractCount": 2,
        "workItems": (
            {
                "agentRole": "MASTER_ORCHESTRATOR",
                "participationMode": "PRIMARY",
                "status": "PLANNED_READY",
                "responsibility": "统一拆解目标并汇总专家计划。",
                "plannedToolNames": ("quality.rule.suggest",),
                "handoffRequired": False,
            },
            {
                "agentRole": "DATA_QUALITY_AGENT",
                "participationMode": "SPECIALIST",
                "status": "WAITING_HUMAN_OR_PERMISSION_HANDOFF",
                "plannedToolNames": ("quality.rule.suggest",),
                "visibleSkillCodes": ("quality.rule.design",),
                "handoffRequired": True,
                "blockedBy": ("HUMAN_OR_PERMISSION_HANDOFF_REQUIRED",),
            },
            {
                "agentRole": "PERMISSION_AGENT",
                "participationMode": "GUARDRAIL",
                "status": "WAITING_HUMAN_OR_PERMISSION_HANDOFF",
                "handoffRequired": True,
            },
        ),
    }


def _durable_loop_summary() -> dict:
    return {
        "runId": "run-a",
        "phase": "waiting_control_plane",
        "resumeAction": "wait_event_replay",
        "waitingReasonCodes": ("CONTROL_PLANE_FEEDBACK_NOT_COLLECTED",),
    }


if __name__ == "__main__":
    unittest.main()
