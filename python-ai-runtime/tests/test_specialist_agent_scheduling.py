import os
import sys
import unittest
from types import SimpleNamespace

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.domain.contracts import AgentPlan, AgentRequest, ToolExecutionMode, ToolPlan
from datasmart_ai_runtime.domain.intent import GovernanceDomain, IntentAnalysis
from datasmart_ai_runtime.services.agent_gateway.session_models import AgentParticipationMode, AgentSessionRole
from datasmart_ai_runtime.services.agent_gateway.session_scheduler import AgentSessionScheduler
from datasmart_ai_runtime.services.multi_agent.execution_plan_rules import (
    depends_on_roles,
    execution_lane,
)
from datasmart_ai_runtime.services.multi_agent.langgraph_execution_plan import (
    LangGraphMultiAgentExecutionPlanWorkflow,
)


class SpecialistAgentSchedulingTest(unittest.TestCase):
    """验证 PRECHECK、RECOVERY、MONITOR 三类专业角色的会话调度契约。

    测试直接构造已经完成的 AgentPlan，而不是依赖某个模型返回的自然语言。这样每个用例都能清楚地
    说明“哪个结构化事实激活了角色”，同时保护调度器不读取 prompt、工具参数或失败正文。
    """

    def test_new_sync_plan_precheck_depends_on_data_sync_planning_result(self) -> None:
        """同步规划中的确定性预检必须交给 PRECHECK_AGENT，且不能被模型补参阻塞。

        `sync.task.precheck` 是 data-sync 控制面提供的结构化工具事实，因此预检 Agent 必须出现；
        这里没有失败、重试、案例证据或监控请求，所以 RECOVERY、KNOWLEDGE、MONITOR 都不应被顺带拉起。
        """

        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="operator-a",
            objective="这段自由文本故意包含失败、恢复、监控和预检查等词，但不能作为唯一调度证据",
            variables={
                "dataSyncRequest": {
                    "sourceDatasourceId": 11,
                    "targetDatasourceId": 12,
                    "syncMode": "FULL",
                }
            },
        )
        plan = _plan(
            ("sync.task.draft.save", "sync.task.precheck"),
            domains=(GovernanceDomain.DATA_SYNC,),
        )

        scheduling = _schedule(plan, request)
        roster = {agent.role: agent for agent in scheduling.participating_agents}

        self.assertIn(AgentSessionRole.MASTER_ORCHESTRATOR, roster)
        self.assertIn(AgentSessionRole.DATA_SYNC_AGENT, roster)
        self.assertIn(AgentSessionRole.PRECHECK_AGENT, roster)
        self.assertNotIn(AgentSessionRole.RECOVERY_AGENT, roster)
        self.assertNotIn(AgentSessionRole.KNOWLEDGE_AGENT, roster)
        self.assertNotIn(AgentSessionRole.MONITOR_AGENT, roster)

    def test_recovery_with_explicit_new_sync_plan_still_activates_data_sync(self) -> None:
        """恢复后明确要求重新生成同步计划时，DATA_SYNC_AGENT 才能重新加入。"""

        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="operator-a",
            objective="用户已批准修复后的同步计划重建",
            variables={
                "failureContext": {"status": "FAILED"},
                "rebuildSyncConfiguration": True,
                "dataSyncRequest": {
                    "sourceDatasourceId": 11,
                    "targetDatasourceId": 12,
                    "syncMode": "FULL",
                    "planRevision": "after-approved-repair",
                },
            },
        )
        plan = _plan(
            ("sync.task.draft.save",),
            domains=(GovernanceDomain.DATA_SYNC,),
        )

        scheduling = _schedule(plan, request)
        roster = {agent.role: agent for agent in scheduling.participating_agents}

        self.assertIn(AgentSessionRole.DATA_SYNC_AGENT, roster)
        self.assertIn(AgentSessionRole.PRECHECK_AGENT, roster)
        self.assertIn(AgentSessionRole.RECOVERY_AGENT, roster)
        self.assertEqual(AgentParticipationMode.SPECIALIST, roster[AgentSessionRole.PRECHECK_AGENT].participation_mode)
        self.assertIn("sync.task.precheck", roster[AgentSessionRole.PRECHECK_AGENT].planned_tool_names)
        self.assertIn("确定性执行前预检查", "".join(roster[AgentSessionRole.PRECHECK_AGENT].activation_reasons))

        roles = tuple(role.value for role in roster)
        precheck_dependencies = depends_on_roles(AgentSessionRole.PRECHECK_AGENT.value, roles)
        self.assertIn(AgentSessionRole.MASTER_ORCHESTRATOR.value, precheck_dependencies)
        self.assertIn(AgentSessionRole.DATA_SYNC_AGENT.value, precheck_dependencies)
        self.assertNotIn(AgentSessionRole.PRECHECK_AGENT.value, depends_on_roles(AgentSessionRole.DATA_SYNC_AGENT.value, roles))

    def test_existing_task_precheck_remains_independent_when_no_sync_planner_is_scheduled(self) -> None:
        """Keep a saved-task precheck runnable when this turn has no DATA_SYNC_AGENT.

        A previously saved task can be rechecked after an edit, retry, or operational review without
        recreating its planning turn.  The dependency rule must therefore add DATA_SYNC_AGENT only when
        that role is actually part of the current execution roster; otherwise PRECHECK_AGENT may depend
        solely on the master orchestrator and can consume the task's persisted control-plane definition.
        """

        roles = (
            AgentSessionRole.MASTER_ORCHESTRATOR.value,
            AgentSessionRole.PRECHECK_AGENT.value,
        )

        self.assertEqual(
            (AgentSessionRole.MASTER_ORCHESTRATOR.value,),
            depends_on_roles(AgentSessionRole.PRECHECK_AGENT.value, roles),
        )

    def test_failed_sync_with_recovery_evidence_activates_knowledge_before_recovery(self) -> None:
        """结构化失败上下文和恢复证据计划应形成 KNOWLEDGE -> RECOVERY 的单向依赖。

        `sync.execution.diagnose` 与 `sync.execution.failed-objects.retry` 证明这是恢复流程，
        `sync.execution.rag.lookup` 证明本轮确实需要案例证据。没有这些结构化事实时，RAG 不会被当作
        每轮固定第一步；恢复 Agent 也不能反向成为知识 Agent 的依赖。
        """

        secret_failure_code = "PRIVATE_FAILURE_CODE_42"
        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="operator-a",
            objective="自由文本不应进入调度理由",
            variables={
                "failureContext": {
                    "status": "FAILED",
                    "failureCode": secret_failure_code,
                },
                "recoveryEvidenceRequired": True,
            },
        )
        plan = _plan(
            (
                ("sync.execution.diagnose", ToolExecutionMode.SYNC),
                ("sync.execution.rag.lookup", ToolExecutionMode.SYNC),
                ("sync.execution.failed-objects.retry", ToolExecutionMode.APPROVAL_REQUIRED),
            ),
            domains=(GovernanceDomain.DATA_SYNC,),
        )

        scheduling = _schedule(plan, request)
        roster = {agent.role: agent for agent in scheduling.participating_agents}
        roles = tuple(role.value for role in roster)
        serialized = str(scheduling.to_summary())

        self.assertNotIn(AgentSessionRole.DATA_SYNC_AGENT, roster)
        self.assertIn(AgentSessionRole.KNOWLEDGE_AGENT, roster)
        self.assertIn(AgentSessionRole.RECOVERY_AGENT, roster)
        self.assertNotIn(AgentSessionRole.PRECHECK_AGENT, roster)
        self.assertNotIn(AgentSessionRole.MONITOR_AGENT, roster)
        self.assertIn("sync.execution.rag.lookup", roster[AgentSessionRole.KNOWLEDGE_AGENT].activation_reasons[2])
        self.assertTrue(roster[AgentSessionRole.RECOVERY_AGENT].requires_handoff)
        self.assertNotIn(secret_failure_code, serialized)

        recovery_dependencies = depends_on_roles(AgentSessionRole.RECOVERY_AGENT.value, roles)
        self.assertNotIn(AgentSessionRole.DATA_SYNC_AGENT.value, recovery_dependencies)
        self.assertIn(AgentSessionRole.KNOWLEDGE_AGENT.value, recovery_dependencies)
        self.assertNotIn(
            AgentSessionRole.RECOVERY_AGENT.value,
            depends_on_roles(AgentSessionRole.KNOWLEDGE_AGENT.value, roles),
        )

    def test_monitoring_request_activates_read_only_observer(self) -> None:
        """运行状态/CDC 监控请求应激活只读 MONITOR_AGENT，而不激活恢复动作。"""

        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="operator-a",
            objective="即使文本提到重试，也不能把监控请求变成恢复请求",
            variables={
                "monitoringRequest": {
                    "taskId": 701,
                    "taskKind": "CDC_REALTIME",
                    "includeLogs": True,
                }
            },
        )
        plan = _plan((("task.monitor.read", ToolExecutionMode.SYNC),))

        scheduling = _schedule(plan, request)
        roster = {agent.role: agent for agent in scheduling.participating_agents}
        monitor = roster[AgentSessionRole.MONITOR_AGENT]

        self.assertEqual(AgentParticipationMode.OBSERVER, monitor.participation_mode)
        self.assertFalse(monitor.requires_handoff)
        self.assertIn("task.monitor.read", monitor.planned_tool_names)
        self.assertEqual("OBSERVABILITY_DIAGNOSTIC", execution_lane("MONITOR_AGENT", "OBSERVER"))
        self.assertNotIn(AgentSessionRole.RECOVERY_AGENT, roster)
        self.assertNotIn(AgentSessionRole.PRECHECK_AGENT, roster)
        self.assertEqual(
            (AgentSessionRole.MASTER_ORCHESTRATOR.value,),
            depends_on_roles(
                AgentSessionRole.MONITOR_AGENT.value,
                tuple(role.value for role in roster),
            ),
        )

    def test_failed_confirmation_continuation_activates_recovery_from_control_plane_facts(self) -> None:
        """确认工具续跑的失败字段也属于结构化恢复事实，不能依赖失败正文或用户关键词。"""

        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="operator-a",
            objective="自由文本不应决定本轮是否恢复",
            variables={
                "postConfirmContinuation": True,
                "failureRecoveryContinuation": True,
                "failedToolNames": ("sync.task.publish",),
                "failureRecoveryKind": "DUPLICATE_TASK_NAME",
                "recoveryExecutionId": "execution-42",
            },
        )
        plan = _plan(("sync.task.draft.save",), domains=(GovernanceDomain.DATA_SYNC,))

        scheduling = _schedule(plan, request)
        roster = {agent.role: agent for agent in scheduling.participating_agents}

        # The failed execution remains a DATA_SYNC governance case, but this wave is recovery-only;
        # the planner must not be restarted just because the failed task belongs to that domain.
        self.assertNotIn(AgentSessionRole.DATA_SYNC_AGENT, roster)
        self.assertIn(AgentSessionRole.RECOVERY_AGENT, roster)
        self.assertIn(AgentSessionRole.KNOWLEDGE_AGENT, roster)
        self.assertNotIn(AgentSessionRole.PRECHECK_AGENT, roster)
        self.assertNotIn("DUPLICATE_TASK_NAME", str(scheduling.to_summary()))

    def test_existing_failed_execution_uses_recovery_roles_without_stale_sync_planning(self) -> None:
        """Keep an existing failed execution in the diagnosis/recovery lane by default.

        This mirrors the production recovery target ``taskId=76/executionId=1805``.  A previous
        synchronization form and a stale draft-save proposal can coexist in the session payload, but
        they cannot restart DATA_SYNC_AGENT unless a separate explicit re-planning fact is present.
        Knowledge and monitoring both precede Recovery so its model sees grounded RAG evidence and a
        deterministic runtime snapshot rather than racing independent model calls.
        """

        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="operator-a",
            objective="诊断已有失败执行并准备恢复建议",
            variables={
                "failureContext": {
                    "taskId": 76,
                    "executionId": 1805,
                    "status": "FAILED",
                    "failureCode": "TARGET_WRITE_ERROR",
                },
                # The old editor state is deliberately present to prove it is not treated as a new
                # configuration request merely because the failure belongs to DATA_SYNC.
                "dataSyncRequest": {"sourceDatasourceId": 27, "targetDatasourceId": 28, "syncMode": "FULL"},
            },
        )
        plan = _plan(
            ("sync.task.draft.save", "sync.execution.diagnose", "sync.execution.rag.lookup"),
            domains=(GovernanceDomain.DATA_SYNC,),
        )

        scheduling = _schedule(plan, request)
        roster = {agent.role: agent for agent in scheduling.participating_agents}
        roles = tuple(role.value for role in roster)

        self.assertNotIn(AgentSessionRole.DATA_SYNC_AGENT, roster)
        self.assertNotIn(AgentSessionRole.PRECHECK_AGENT, roster)
        self.assertIn(AgentSessionRole.KNOWLEDGE_AGENT, roster)
        self.assertIn(AgentSessionRole.RECOVERY_AGENT, roster)
        self.assertIn(AgentSessionRole.MONITOR_AGENT, roster)
        self.assertEqual(
            {
                AgentSessionRole.MASTER_ORCHESTRATOR.value,
                AgentSessionRole.KNOWLEDGE_AGENT.value,
                AgentSessionRole.MONITOR_AGENT.value,
            },
            set(depends_on_roles(AgentSessionRole.RECOVERY_AGENT.value, roles)),
        )

    def test_unrelated_question_does_not_activate_specialist_roles_from_free_text(self) -> None:
        """无关问答只保留主控，证明调度器不会用自由文本关键词作为唯一证据。"""

        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="operator-a",
            objective="请解释失败恢复、监控、预检查和 RAG 的概念，但不要规划任何治理工具",
            variables={"latestUserMessage": "失败恢复监控预检查", "workspace": "must-not-be-used"},
        )
        plan = _plan((), domains=(GovernanceDomain.GENERAL_GOVERNANCE,))

        scheduling = _schedule(plan, request)
        roles = {agent.role for agent in scheduling.participating_agents}

        self.assertEqual({AgentSessionRole.MASTER_ORCHESTRATOR}, roles)
        self.assertNotIn("workspace", str(scheduling.to_summary()))

    def test_langgraph_edges_keep_recovery_evidence_and_observation_directional(self) -> None:
        """执行计划图只添加定向的预检/恢复/监控边，不创建 RAG 全局广播或反向环路。

        这里直接调用执行计划图的纯规则节点，使用最小工作项替身，避免安装真实 LangGraph 依赖；生产图
        编译后消费的仍是同一组 `agentRole` 字段。断言同时覆盖了本次新增的协作边和被移除的泛化 RAG 边。
        """

        roles = (
            AgentSessionRole.MASTER_ORCHESTRATOR.value,
            AgentSessionRole.DATA_SYNC_AGENT.value,
            AgentSessionRole.PRECHECK_AGENT.value,
            AgentSessionRole.KNOWLEDGE_AGENT.value,
            AgentSessionRole.RECOVERY_AGENT.value,
            AgentSessionRole.MONITOR_AGENT.value,
        )
        state = {
            "trace": (),
            "workItems": tuple(SimpleNamespace(agent_role=role) for role in roles),
        }

        updated = LangGraphMultiAgentExecutionPlanWorkflow()._build_collaboration_edges(state)
        edges = {
            (edge.from_role, edge.to_role, edge.reason_code)
            for edge in updated["collaborationEdges"]
        }

        self.assertIn(
            (
                AgentSessionRole.DATA_SYNC_AGENT.value,
                AgentSessionRole.PRECHECK_AGENT.value,
                "PRECHECK_NEEDS_SYNC_PLAN",
            ),
            edges,
        )
        self.assertIn(
            (
                AgentSessionRole.MONITOR_AGENT.value,
                AgentSessionRole.RECOVERY_AGENT.value,
                "RECOVERY_NEEDS_MONITORING_FACTS",
            ),
            edges,
        )
        self.assertIn(
            (
                AgentSessionRole.KNOWLEDGE_AGENT.value,
                AgentSessionRole.RECOVERY_AGENT.value,
                "RECOVERY_NEEDS_CASE_EVIDENCE",
            ),
            edges,
        )
        self.assertNotIn(
            (
                AgentSessionRole.DATA_SYNC_AGENT.value,
                AgentSessionRole.MONITOR_AGENT.value,
                "MONITOR_NEEDS_SYNC_RUNTIME_CONTEXT",
            ),
            edges,
        )
        self.assertFalse(
            any(
                source == AgentSessionRole.KNOWLEDGE_AGENT.value
                # KNOWLEDGE_AGENT 向主 Agent 汇报是通用控制流，不等于向所有专员广播 RAG。
                and target not in {
                    AgentSessionRole.MASTER_ORCHESTRATOR.value,
                    AgentSessionRole.RECOVERY_AGENT.value,
                }
                for source, target, _ in edges
            )
        )
        self.assertFalse(
            any(
                source == AgentSessionRole.RECOVERY_AGENT.value
                and target == AgentSessionRole.PRECHECK_AGENT.value
                for source, target, _ in edges
            )
        )


def _plan(
    tool_specs: tuple[str | tuple[str, ToolExecutionMode], ...],
    *,
    domains: tuple[GovernanceDomain, ...] = (),
    candidates: tuple[str, ...] = (),
) -> AgentPlan:
    """构造低敏测试计划；工具参数和用户目标不会成为调度输入。"""

    tools = tuple(
        ToolPlan(
            tool_name=item if isinstance(item, str) else item[0],
            reason="测试用结构化计划",
            execution_mode=ToolExecutionMode.SYNC if isinstance(item, str) else item[1],
        )
        for item in tool_specs
    )
    return AgentPlan(
        request_id="request-specialist-scheduling",
        selected_route=None,
        state_trace=("plan_tools",),
        tool_plans=tools,
        requires_human_approval=any(tool.execution_mode == ToolExecutionMode.APPROVAL_REQUIRED for tool in tools),
        response_summary="低敏测试计划",
        intent_analysis=IntentAnalysis(
            summary="结构化测试意图",
            governance_domains=domains,
            candidate_tools=candidates,
        ),
    )


def _schedule(plan: AgentPlan, request: AgentRequest):
    """使用全通过的治理摘要调用调度器，专注验证角色规则本身。"""

    return AgentSessionScheduler().schedule(
        plan,
        request,
        model_gateway={"available": True},
        skill_admission={"allowed": True, "selectedSkills": ()},
        tool_budget={"allowed": True},
        memory={"retrievalTargetCount": 0, "totalRetrieved": 0},
        skill_visibility={"visibleSkills": ()},
    )


if __name__ == "__main__":
    unittest.main()
