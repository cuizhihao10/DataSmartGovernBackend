import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.api import build_default_orchestrator
from datasmart_ai_runtime.api.agent.plan_response import build_plan_response
from datasmart_ai_runtime.domain.contracts import AgentRequest
from datasmart_ai_runtime.services.agent_execution import DurableAgentLoopService
from datasmart_ai_runtime.services.memory import UserProfileMemoryService


class AgentProfileAndDurableLoopResponseTest(unittest.TestCase):
    def test_plan_response_contains_profile_and_durable_loop_summary(self) -> None:
        """同步计划响应应暴露画像摘要和 Durable Loop checkpoint。"""

        user_profile_memory = UserProfileMemoryService.default()
        durable_loop = DurableAgentLoopService()
        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="user-a",
            objective="请用中文详细注释，尽快完成 MySQL 增量 ETL 数据同步闭环。",
            variables={"workspaceKey": "workspace-a"},
        )
        response = build_plan_response(
            request,
            build_default_orchestrator(user_profile_memory=user_profile_memory),
            durable_agent_loop_service=durable_loop,
        )

        self.assertIn("userProfileMemory", response)
        self.assertIn("agentDurableLoop", response)
        self.assertGreater(response["userProfileMemory"]["activeFacetCount"], 0)
        self.assertIn(response["agentDurableLoop"]["phase"], {"plan_created", "waiting_control_plane"})
        self.assertEqual("LOW_SENSITIVE_LOOP_STATE_ONLY", response["agentDurableLoop"]["payloadPolicy"])


if __name__ == "__main__":
    unittest.main()
