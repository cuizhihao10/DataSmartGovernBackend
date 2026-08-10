from __future__ import annotations

import unittest

from datasmart_ai_runtime.services.agent_gateway.session_models import AgentSessionRole
from datasmart_ai_runtime.services.multi_agent.specialist_contracts import (
    SpecialistDelegationScope,
    SpecialistTurnRequest,
    SpecialistTurnResult,
    SpecialistTurnStatus,
)
from datasmart_ai_runtime.services.multi_agent.specialist_registry import SpecialistAgentRegistry


class _DatasourceSpecialist:
    @property
    def role(self) -> AgentSessionRole:
        return AgentSessionRole.DATASOURCE_AGENT

    def execute(self, request: SpecialistTurnRequest, event_sink=None) -> SpecialistTurnResult:
        return SpecialistTurnResult(
            agent_id="datasource-agent-local",
            role=self.role,
            turn_id=request.turn_id,
            status=SpecialistTurnStatus.COMPLETED,
            public_summary="已完成数据源候选检索。",
        )


def _request(role: AgentSessionRole = AgentSessionRole.DATASOURCE_AGENT) -> SpecialistTurnRequest:
    return SpecialistTurnRequest(
        turn_id="turn-1",
        session_id="session-1",
        run_id="run-1",
        role=role,
        objective="识别用户可用的数据源",
        scope=SpecialistDelegationScope(
            tenant_id="1",
            application_id="datasmart",
            project_id="101",
            actor_id="user-1",
            delegation_id="delegation-1",
            allowed_tool_names=("datasource.access",),
        ),
    )


class SpecialistAgentRegistryTest(unittest.TestCase):
    """验证真实专业 Agent 注册和 fail-closed 路由边界。"""

    def test_executes_registered_specialist_and_preserves_turn_identity(self) -> None:
        registry = SpecialistAgentRegistry((_DatasourceSpecialist(),))

        result = registry.execute(_request())

        self.assertEqual(AgentSessionRole.DATASOURCE_AGENT, result.role)
        self.assertEqual("turn-1", result.turn_id)
        self.assertEqual((AgentSessionRole.DATASOURCE_AGENT,), registry.available_roles())

    def test_rejects_unregistered_specialist_without_master_fallback(self) -> None:
        registry = SpecialistAgentRegistry((_DatasourceSpecialist(),))

        with self.assertRaisesRegex(LookupError, "尚未注册"):
            registry.execute(_request(AgentSessionRole.KNOWLEDGE_AGENT))

    def test_rejects_duplicate_role_registration(self) -> None:
        registry = SpecialistAgentRegistry((_DatasourceSpecialist(),))

        with self.assertRaisesRegex(ValueError, "已注册"):
            registry.register(_DatasourceSpecialist())


if __name__ == "__main__":
    unittest.main()
