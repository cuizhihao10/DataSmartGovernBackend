import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.domain.contracts import AgentRequest
from datasmart_ai_runtime.services.model_tool_call_budget_guard import ModelToolCallBudgetPolicy
from datasmart_ai_runtime.services.model_tool_call_budget_policy_provider import (
    EnvAndRequestModelToolCallBudgetPolicyProvider,
)


class ModelToolCallBudgetPolicyProviderTest(unittest.TestCase):
    """工具调用预算策略来源测试。"""

    def test_environment_values_override_default_policy(self) -> None:
        """部署级环境变量应能覆盖默认预算策略。"""

        provider = EnvAndRequestModelToolCallBudgetPolicyProvider(
            environ={
                "DATASMART_AI_TOOL_BUDGET_MAX_PROPOSED_CALLS": "5",
                "DATASMART_AI_TOOL_BUDGET_MAX_AUTO_EXECUTABLE_CALLS": "2",
            }
        )

        policy = provider.policy_for(self._request())

        self.assertEqual(5, policy.max_proposed_tool_calls)
        self.assertEqual(2, policy.max_auto_executable_tool_calls)
        self.assertEqual(ModelToolCallBudgetPolicy().max_high_risk_tool_calls, policy.max_high_risk_tool_calls)

    def test_request_tool_call_budget_overrides_environment(self) -> None:
        """请求变量应优先于环境变量，便于 Java gateway 按场景下发策略。"""

        provider = EnvAndRequestModelToolCallBudgetPolicyProvider(
            environ={"DATASMART_AI_TOOL_BUDGET_MAX_AUTO_EXECUTABLE_CALLS": "3"}
        )

        policy = provider.policy_for(
            self._request(
                variables={
                    "toolCallBudget": {
                        "maxAutoExecutableToolCalls": 1,
                        "maxTotalArgumentsBytes": "256",
                    }
                }
            )
        )

        self.assertEqual(1, policy.max_auto_executable_tool_calls)
        self.assertEqual(256, policy.max_total_arguments_bytes)

    def test_invalid_values_are_ignored_to_avoid_accidental_lockdown(self) -> None:
        """非法值或非正数应被忽略，避免错误配置把工具链直接锁死。"""

        provider = EnvAndRequestModelToolCallBudgetPolicyProvider(
            default_policy=ModelToolCallBudgetPolicy(max_proposed_tool_calls=8),
            environ={"DATASMART_AI_TOOL_BUDGET_MAX_PROPOSED_CALLS": "-1"},
        )

        policy = provider.policy_for(
            self._request(variables={"toolCallBudget": {"maxProposedToolCalls": "bad"}})
        )

        self.assertEqual(8, policy.max_proposed_tool_calls)

    @staticmethod
    def _request(variables: dict[str, object] | None = None) -> AgentRequest:
        return AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="user-a",
            objective="测试工具预算策略",
            variables=variables or {},
        )


if __name__ == "__main__":
    unittest.main()
