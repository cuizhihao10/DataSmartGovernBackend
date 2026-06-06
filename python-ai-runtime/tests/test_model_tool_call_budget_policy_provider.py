import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.domain.contracts import AgentRequest
from datasmart_ai_runtime.services.model_gateway.model_tool_call_budget_guard import ModelToolCallBudgetPolicy
from datasmart_ai_runtime.services.model_gateway.model_tool_call_budget_policy_provider import (
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

    def test_trusted_control_plane_budget_overrides_untrusted_request_budget(self) -> None:
        """受信控制面预算应覆盖普通请求预算，防止客户端伪造扩大自动工具执行额度。

        这个用例固定 5.41 的网关策略 envelope 语义：客户端仍可以在迁移期携带 `toolCallBudget`
        或顶层预算字段，但只要 gateway 验签后重建了 `trustedControlPlane.toolBudget`，Python Runtime
        就必须优先采用受信预算。这样高风险 workspace、审计角色、试用租户或 worker backlog 过高等场景，
        不会因为请求体里声明“我可以自动执行更多工具”而绕过 Java permission-admin 的策略判断。
        """

        provider = EnvAndRequestModelToolCallBudgetPolicyProvider(
            environ={"DATASMART_AI_TOOL_BUDGET_MAX_AUTO_EXECUTABLE_CALLS": "5"}
        )

        policy = provider.policy_for(
            self._request(
                variables={
                    "toolCallBudget": {
                        "maxProposedToolCalls": 9,
                        "maxAutoExecutableToolCalls": 9,
                        "maxHighRiskToolCalls": 9,
                    },
                    "maxTotalArgumentsBytes": 4096,
                    "trustedControlPlane": {
                        "toolBudget": {
                            "maxProposedToolCalls": 2,
                            "maxAutoExecutableToolCalls": 1,
                            "maxHighRiskToolCalls": 0,
                        }
                    },
                }
            )
        )

        self.assertEqual(2, policy.max_proposed_tool_calls)
        self.assertEqual(1, policy.max_auto_executable_tool_calls)
        self.assertEqual(0, policy.max_high_risk_tool_calls)
        self.assertEqual(4096, policy.max_total_arguments_bytes)

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
                    "trustedControlPlane": {
                        "toolBudget": {
                            "workspaceKey": "tenant-10:project-java:workspace-a",
                            "actorRole": "TENANT_ADMIN",
                            "tenantPlanCode": "ENTERPRISE",
                            "workspaceRiskLevel": "HIGH",
                            "workerBacklogLevel": "BUSY",
                            "requestedToolRiskLevel": "MEDIUM",
                        }
                    },
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

    def test_java_permission_admin_payload_ignores_untrusted_budget_governance_variables(self) -> None:
        """普通 variables 不能伪造管理员角色、套餐或 backlog 状态来扩大远程工具预算。"""

        payload = JavaPermissionAdminToolBudgetPolicyClient.build_payload(
            self._request(
                variables={
                    "workspaceKey": "forged-workspace",
                    "actorRole": "PLATFORM_ADMIN",
                    "tenantPlanCode": "ENTERPRISE",
                    "workspaceRiskLevel": "LOW",
                    "workerBacklogLevel": "IDLE",
                    "requestedToolRiskLevel": "LOW",
                }
            )
        )

        self.assertIsNone(payload["workspaceKey"])
        self.assertEqual("ORDINARY_USER", payload["actorRole"])
        self.assertEqual("STANDARD", payload["tenantPlanCode"])
        self.assertEqual("NORMAL", payload["workspaceRiskLevel"])
        self.assertEqual("NORMAL", payload["workerBacklogLevel"])
        self.assertEqual("LOW", payload["requestedToolRiskLevel"])

    def test_java_permission_admin_payload_can_explicitly_enable_legacy_budget_compatibility(self) -> None:
        """迁移期开关可以读取旧 variables，但默认关闭以保护生产安全边界。"""

        payload = JavaPermissionAdminToolBudgetPolicyClient.build_payload(
            self._request(variables={"actorRole": "TENANT_ADMIN", "tenantPlanCode": "ENTERPRISE"}),
            allow_legacy_request_variables=True,
        )

        self.assertEqual("TENANT_ADMIN", payload["actorRole"])
        self.assertEqual("ENTERPRISE", payload["tenantPlanCode"])

    def test_java_permission_admin_response_maps_tool_call_budget(self) -> None:
        """Java 统一响应中的 `toolCallBudget` 应转换为 Python 预算策略对象。"""

        policy = JavaPermissionAdminToolBudgetPolicyClient.parse_platform_response(
            {
                "code": 0,
                "data": {
                    "toolCallBudget": {
                        "maxProposedToolCalls": 9,
                        "maxAutoExecutableToolCalls": 4,
                        "maxHighRiskToolCalls": 0,
                        "maxSingleArgumentsBytes": 1234,
                        "maxTotalArgumentsBytes": 5678,
                    }
                },
            }
        )

        self.assertEqual(9, policy.max_proposed_tool_calls)
        self.assertEqual(4, policy.max_auto_executable_tool_calls)
        self.assertEqual(0, policy.max_high_risk_tool_calls)
        self.assertEqual(1234, policy.max_single_arguments_bytes)
        self.assertEqual(5678, policy.max_total_arguments_bytes)

    def test_java_permission_admin_response_exposes_low_sensitive_readiness_policy(self) -> None:
        """Java 标准 readiness policy 应被白名单裁剪后提供给 Python 执行准备度 provider。

        这个测试故意在远程响应里放入 `prompt/sql/arguments/internalEndpoint/secret` 等字段，验证客户端不会
        因为 Java DTO 后续扩展或错误返回而把敏感上下文透传到 `/agent/plans` 响应、runtime event 或 Java replay。
        """

        response = JavaPermissionAdminToolBudgetPolicyClient.parse_platform_policy_response(
            {
                "code": 0,
                "data": {
                    "toolCallBudget": {
                        "maxProposedToolCalls": 8,
                        "maxAutoExecutableToolCalls": 3,
                    },
                    "toolExecutionReadinessPolicy": {
                        "source": "permission-admin",
                        "policyVersion": "perm-tool-readiness-v1",
                        "actorRole": "AUDITOR",
                        "tenantPlanCode": "TRIAL",
                        "workspaceRiskLevel": "HIGH",
                        "workerBacklogLevel": "CRITICAL",
                        "maxAutoSyncTools": 4,
                        "maxAsyncTools": 2,
                        "highRiskRequiresApproval": True,
                        "criticalRiskBlocked": True,
                        "allowDraftWithoutAllParameters": False,
                        "influenceCodes": ["REMOTE_POLICY", "WORKER_BACKLOG_BLOCKS_TOOL_BUDGET"],
                        "prompt": "不要泄露",
                        "sql": "select * from secret_table",
                        "arguments": {"datasourceId": "ds-sensitive"},
                        "internalEndpoint": "http://permission-admin.internal",
                        "secret": "token",
                    },
                },
            }
        )

        readiness_policy = response.tool_execution_readiness_policy

        self.assertIsNotNone(readiness_policy)
        self.assertEqual(8, response.tool_call_budget_policy.max_proposed_tool_calls)
        self.assertEqual("permission-admin", readiness_policy["source"])
        self.assertEqual("perm-tool-readiness-v1", readiness_policy["policyVersion"])
        self.assertEqual(4, readiness_policy["maxAutoSyncTools"])
        self.assertEqual(2, readiness_policy["maxAsyncTools"])
        self.assertFalse(readiness_policy["allowDraftWithoutAllParameters"])
        self.assertEqual(("REMOTE_POLICY", "WORKER_BACKLOG_BLOCKS_TOOL_BUDGET"), readiness_policy["influenceCodes"])
        self.assertNotIn("prompt", readiness_policy)
        self.assertNotIn("sql", readiness_policy)
        self.assertNotIn("arguments", readiness_policy)
        self.assertNotIn("internalEndpoint", readiness_policy)
        self.assertNotIn("secret", readiness_policy)

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
