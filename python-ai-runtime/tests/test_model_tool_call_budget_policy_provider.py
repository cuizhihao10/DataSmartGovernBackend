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
    JavaPermissionAdminToolBudgetPolicyClient,
    PermissionAdminToolBudgetPolicyClientError,
    RemoteThenLocalModelToolCallBudgetPolicyProvider,
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

    def test_java_permission_admin_payload_maps_request_variables(self) -> None:
        """远程策略请求应把 Python Agent 上下文映射为 Java permission-admin 可理解的 DTO。

        这里不直接复用 Python 内部字段名，是因为 permission-admin 是后续企业版控制面的权威策略中心。
        它需要看到租户套餐、用户角色、工作空间风险、worker 积压、工具风险等业务维度，才能做出比本地
        env 配置更细的预算判断。测试这个映射可以防止后续重构时把关键治理维度漏传。
        """

        payload = JavaPermissionAdminToolBudgetPolicyClient.build_payload(
            self._request(
                tenant_id="10",
                variables={
                    "projectId": "project-java",
                    "workspaceKey": "tenant-10:project-java:workspace-a",
                    "actorRole": "TENANT_ADMIN",
                    "tenantPlanCode": "ENTERPRISE",
                    "workspaceRiskLevel": "HIGH",
                    "workerBacklogLevel": "BUSY",
                    "requestedToolRiskLevel": "MEDIUM",
                },
            )
        )

        self.assertEqual(10, payload["tenantId"])
        self.assertEqual("project-java", payload["projectId"])
        self.assertEqual("tenant-10:project-java:workspace-a", payload["workspaceKey"])
        self.assertEqual("TENANT_ADMIN", payload["actorRole"])
        self.assertEqual("ENTERPRISE", payload["tenantPlanCode"])
        self.assertEqual("HIGH", payload["workspaceRiskLevel"])
        self.assertEqual("BUSY", payload["workerBacklogLevel"])
        self.assertEqual("MEDIUM", payload["requestedToolRiskLevel"])

    def test_java_permission_admin_response_maps_tool_call_budget(self) -> None:
        """Java 统一响应中的 `toolCallBudget` 应转换为 Python 预算策略对象。"""

        policy = JavaPermissionAdminToolBudgetPolicyClient.parse_platform_response(
            {
                "code": 0,
                "data": {
                    "toolCallBudget": {
                        "maxProposedToolCalls": 9,
                        "maxAutoExecutableToolCalls": 4,
                        "maxHighRiskToolCalls": 1,
                        "maxSingleArgumentsBytes": 1234,
                        "maxTotalArgumentsBytes": 5678,
                    }
                },
            }
        )

        self.assertEqual(9, policy.max_proposed_tool_calls)
        self.assertEqual(4, policy.max_auto_executable_tool_calls)
        self.assertEqual(1, policy.max_high_risk_tool_calls)
        self.assertEqual(1234, policy.max_single_arguments_bytes)
        self.assertEqual(5678, policy.max_total_arguments_bytes)

    def test_remote_provider_uses_permission_admin_policy_when_available(self) -> None:
        """远程可用时应优先使用 permission-admin 策略，而不是本地环境变量策略。"""

        remote_policy = ModelToolCallBudgetPolicy(max_auto_executable_tool_calls=7)
        provider = RemoteThenLocalModelToolCallBudgetPolicyProvider(
            _FakeRemoteBudgetClient(remote_policy),
            local_provider=EnvAndRequestModelToolCallBudgetPolicyProvider(
                environ={"DATASMART_AI_TOOL_BUDGET_MAX_AUTO_EXECUTABLE_CALLS": "1"}
            ),
        )

        policy = provider.policy_for(self._request())

        self.assertEqual(7, policy.max_auto_executable_tool_calls)

    def test_remote_provider_falls_back_to_local_policy_when_allowed(self) -> None:
        """远程策略中心不可用且允许降级时，应回退到本地策略，保证本地开发与灰度环境不断流。"""

        provider = RemoteThenLocalModelToolCallBudgetPolicyProvider(
            _FailingRemoteBudgetClient(),
            local_provider=EnvAndRequestModelToolCallBudgetPolicyProvider(
                environ={"DATASMART_AI_TOOL_BUDGET_MAX_PROPOSED_CALLS": "6"}
            ),
            allow_remote_fallback=True,
        )

        policy = provider.policy_for(self._request())

        self.assertEqual(6, policy.max_proposed_tool_calls)

    def test_remote_provider_raises_when_fallback_disabled(self) -> None:
        """生产环境可关闭远程失败回退，让权限策略中心故障显式暴露而不是静默放宽预算。"""

        provider = RemoteThenLocalModelToolCallBudgetPolicyProvider(
            _FailingRemoteBudgetClient(),
            allow_remote_fallback=False,
        )

        with self.assertRaises(PermissionAdminToolBudgetPolicyClientError):
            provider.policy_for(self._request())

    @staticmethod
    def _request(variables: dict[str, object] | None = None, tenant_id: str = "tenant-a") -> AgentRequest:
        return AgentRequest(
            tenant_id=tenant_id,
            project_id="project-a",
            actor_id="user-a",
            objective="测试工具预算策略",
            variables=variables or {},
        )


class _FakeRemoteBudgetClient:
    """测试替身：模拟 permission-admin 正常返回远程预算策略。"""

    def __init__(self, policy: ModelToolCallBudgetPolicy) -> None:
        self._policy = policy

    def evaluate(self, request_context: AgentRequest, trace_id: str | None = None) -> ModelToolCallBudgetPolicy:
        """保持与真实 client 相同的方法签名，便于测试 provider 编排逻辑。"""

        return self._policy


class _FailingRemoteBudgetClient:
    """测试替身：模拟 permission-admin 不可用或返回非法响应。"""

    def evaluate(self, request_context: AgentRequest, trace_id: str | None = None) -> ModelToolCallBudgetPolicy:
        """抛出与真实 client 相同的异常类型，验证 fail-open/fail-closed 分支。"""

        raise PermissionAdminToolBudgetPolicyClientError("permission-admin unavailable")


if __name__ == "__main__":
    unittest.main()
