from __future__ import annotations

import unittest
from threading import Barrier, Lock
from time import sleep

from datasmart_ai_runtime.services.agent_gateway.session_models import AgentSessionRole
from datasmart_ai_runtime.services.multi_agent.specialist_contracts import (
    SpecialistDelegationScope,
    SpecialistTurnRequest,
    SpecialistTurnResult,
    SpecialistTurnStatus,
)
from datasmart_ai_runtime.services.multi_agent.specialist_langgraph_fanout import (
    LangGraphSpecialistFanoutExecutor,
)


class LangGraphSpecialistFanoutExecutorTest(unittest.TestCase):
    """验证 Specialist 波次由 LangGraph 动态 Send 和可复用子图真实执行。"""

    def test_dynamic_send_dispatches_only_runtime_selected_roles(self) -> None:
        """固定能力 roster 不能变成固定执行路径，实际请求数才决定本轮 fan-out。"""

        calls: list[str] = []

        def execute_turn(request, event_sink, result_sink):  # noqa: ANN001, ANN202 - 测试替身保留生产协议。
            calls.append(request.role.value)
            result = _result(request)
            if result_sink is not None:
                result_sink(request, result)
            return result

        executor = LangGraphSpecialistFanoutExecutor(execute_turn)
        requests = {
            AgentSessionRole.DATASOURCE_AGENT: _request(AgentSessionRole.DATASOURCE_AGENT),
            AgentSessionRole.DATA_SYNC_AGENT: _request(AgentSessionRole.DATA_SYNC_AGENT),
        }

        execution = executor.execute(requests, event_sink=None, result_sink=None)

        self.assertEqual("langgraph", execution.engine)
        self.assertEqual("DYNAMIC_SEND_SUBGRAPH", execution.dispatch_mode)
        self.assertEqual(2, execution.dynamic_dispatch_count)
        self.assertEqual(2, execution.subgraph_invocation_count)
        self.assertEqual(
            {"DATASOURCE_AGENT", "DATA_SYNC_AGENT"},
            {role.value for role in execution.results},
        )
        self.assertEqual(
            {"DATASOURCE_AGENT", "DATA_SYNC_AGENT"},
            set(calls),
        )
        self.assertNotIn("RECOVERY_AGENT", execution.dispatched_roles)

    def test_each_send_uses_prepare_execute_finalize_subgraph_and_reducer(self) -> None:
        """每个 Send 分支必须经过完整子图，并由父图 reducer 无损汇总。"""

        registered: list[str] = []

        def execute_turn(request, event_sink, result_sink):  # noqa: ANN001, ANN202 - 测试替身保留生产协议。
            result = _result(request)
            if result_sink is not None:
                result_sink(request, result)
            return result

        def result_sink(request, result):  # noqa: ANN001, ANN202 - 只记录低敏稳定角色码。
            registered.append(request.role.value)

        requests = {
            role: _request(role)
            for role in (
                AgentSessionRole.KNOWLEDGE_AGENT,
                AgentSessionRole.DATASOURCE_AGENT,
                AgentSessionRole.DATA_SYNC_AGENT,
            )
        }

        execution = LangGraphSpecialistFanoutExecutor(execute_turn).execute(
            requests,
            event_sink=None,
            result_sink=result_sink,
        )

        self.assertEqual(3, len(execution.results))
        self.assertEqual(3, len(execution.subgraph_traces))
        self.assertEqual(3, len(registered))
        self.assertEqual(3, len(set(registered)))
        for role, trace in execution.subgraph_traces.items():
            with self.subTest(role=role):
                self.assertEqual(
                    (
                        "langgraph.specialist.prepare_turn",
                        "langgraph.specialist.execute_turn",
                        "langgraph.specialist.finalize_turn",
                    ),
                    trace,
                )

    def test_fail_closed_sink_error_waits_for_same_wave_before_propagation(self) -> None:
        """一个分支事实登记失败时，其他已派发分支仍应执行完成，再原样抛出异常。

        协调器原来的并发实现会等待同波次全部 future 收口后再传播 Java fact sink 的 fail-closed 异常。
        LangGraph 迁移不能因为某个分支先失败就取消其他已授权角色，否则会丢失本波次已经发生的专业事实。
        ``Barrier`` 确保三个 Send 都真正开始，测试随后核对每个角色只调用一次并保留原异常类型。
        """

        barrier = Barrier(3)
        lock = Lock()
        calls: list[str] = []
        completed: list[str] = []

        class FactSinkUnavailable(RuntimeError):
            """模拟 Java Specialist fact sink 的 fail-closed 异常。"""

        def execute_turn(request, event_sink, result_sink):  # noqa: ANN001, ANN202 - 测试替身保留生产协议。
            barrier.wait(timeout=5)
            with lock:
                calls.append(request.role.value)
            if request.role is AgentSessionRole.DATASOURCE_AGENT:
                raise FactSinkUnavailable("模拟事实登记失败")
            # 让失败分支先抛出；执行器仍必须等待另外两个分支收口，不能只证明它们曾经启动。
            sleep(0.1)
            with lock:
                completed.append(request.role.value)
            return _result(request)

        requests = {
            role: _request(role)
            for role in (
                AgentSessionRole.KNOWLEDGE_AGENT,
                AgentSessionRole.DATASOURCE_AGENT,
                AgentSessionRole.DATA_SYNC_AGENT,
            )
        }

        with self.assertRaises(FactSinkUnavailable):
            LangGraphSpecialistFanoutExecutor(execute_turn).execute(
                requests,
                event_sink=None,
                result_sink=None,
            )

        self.assertEqual(
            {"KNOWLEDGE_AGENT", "DATASOURCE_AGENT", "DATA_SYNC_AGENT"},
            set(calls),
        )
        self.assertEqual(3, len(calls))
        self.assertEqual({"KNOWLEDGE_AGENT", "DATA_SYNC_AGENT"}, set(completed))


def _request(role: AgentSessionRole) -> SpecialistTurnRequest:
    """创建不含工具参数或业务正文的最小专业 turn。"""

    return SpecialistTurnRequest(
        turn_id=f"turn-{role.value.lower()}",
        session_id="session-fanout",
        run_id="run-fanout",
        role=role,
        objective="执行本轮受治理专业分析",
        scope=SpecialistDelegationScope(
            tenant_id="10",
            application_id="10010",
            project_id="101",
            actor_id="1001",
            delegation_id=f"delegation-{role.value.lower()}",
            allowed_tool_names=(),
        ),
    )


def _result(request: SpecialistTurnRequest) -> SpecialistTurnResult:
    """返回可由 reducer 汇总的低敏完成结果。"""

    return SpecialistTurnResult(
        agent_id=f"{request.role.value.lower()}-test",
        role=request.role,
        turn_id=request.turn_id,
        status=SpecialistTurnStatus.COMPLETED,
        public_summary="专业分析完成",
    )


if __name__ == "__main__":
    unittest.main()
