import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.api.agent.plan_response import build_plan_response
from datasmart_ai_runtime.domain.contracts import AgentPlan, AgentRequest, ToolPlan
from datasmart_ai_runtime.services.tools.tool_execution_readiness import (
    ToolExecutionReadinessDecision,
    ToolExecutionReadinessPolicy,
)
from datasmart_ai_runtime.services.tools.tool_execution_readiness_policy_provider import (
    RemoteThenLocalToolExecutionReadinessPolicyProvider,
    ToolExecutionReadinessPolicyProvider,
    ToolExecutionReadinessPolicySnapshot,
)


class ToolExecutionReadinessPolicyProviderTest(unittest.TestCase):
    """工具执行准备度策略来源测试。

    这些测试关注“策略从哪里来”，而不是重复验证 readiness service 的每个决策分支：
    - 远程 permission-admin 策略应能进入 trusted control plane；
    - 远程失败时应按配置本地回退或 fail-closed；
    - `/agent/plans` 主链路必须实际使用注入的 provider。
    """

    def test_remote_policy_is_injected_into_trusted_control_plane_without_mutating_request(self) -> None:
        """远程 readiness policy 应复用本地 trusted-control-plane 解析和安全收敛逻辑。"""

        remote_client = _RemoteReadinessClient(
            {
                "source": "permission-admin",
                "policyVersion": "perm-readiness-v2",
                "actorRole": "AUDITOR",
                "tenantPlanCode": "TRIAL",
                "workspaceRiskLevel": "HIGH",
                "workerBacklogLevel": "CRITICAL",
                "maxAutoSyncTools": 5,
                "maxAsyncTools": 4,
                "influenceCodes": ("REMOTE_PERMISSION_ADMIN_POLICY",),
                "prompt": "should-not-leak",
                "arguments": {"datasourceId": "ds-sensitive"},
            }
        )
        request = self._request()
        provider = RemoteThenLocalToolExecutionReadinessPolicyProvider(remote_client)

        snapshot = provider.policy_for(request)

        self.assertEqual(1, remote_client.calls)
        self.assertEqual("trusted-control-plane", snapshot.source)
        self.assertEqual("perm-readiness-v2", snapshot.policy_version)
        self.assertEqual(0, snapshot.policy.max_auto_sync_tools)
        self.assertEqual(0, snapshot.policy.max_async_tools)
        self.assertIn("REMOTE_PERMISSION_ADMIN_POLICY", snapshot.influence_codes)
        self.assertIn("READ_ONLY_ROLE_BLOCKS_AUTO_EXECUTION", snapshot.influence_codes)
        self.assertIn("WORKER_BACKLOG_BLOCKS_TOOL_BUDGET", snapshot.influence_codes)
        self.assertNotIn("trustedControlPlane", request.variables)
        self.assertNotIn("should-not-leak", str(snapshot.to_low_sensitive_summary()))
        self.assertNotIn("ds-sensitive", str(snapshot.to_low_sensitive_summary()))

    def test_remote_policy_skips_permission_admin_when_trusted_readiness_exists(self) -> None:
        """已存在 gateway 签名 readiness policy 时，不应再次调用 permission-admin。

        这条用例固定 5.42 的控制面优化：gateway 生成并签名 policy envelope 后，Python Runtime 只需要
        本地解析 `trustedControlPlane.toolExecutionReadinessPolicy`。如果这里仍然远程调用，就会让
        同一请求再次承受网络延迟，并可能拿到与 gateway envelope 不一致的策略版本。
        """

        remote_client = _RemoteReadinessClient(
            {
                "source": "permission-admin",
                "policyVersion": "should-not-be-used",
                "maxAutoSyncTools": 9,
            }
        )
        provider = RemoteThenLocalToolExecutionReadinessPolicyProvider(remote_client)

        snapshot = provider.policy_for(
            self._request(
                variables={
                    "trustedControlPlane": {
                        "toolExecutionReadinessPolicy": {
                            "source": "gateway-local-fallback",
                            "policyVersion": "gateway-envelope-v1",
                            "actorRole": "PROJECT_OWNER",
                            "tenantPlanCode": "STANDARD",
                            "workspaceRiskLevel": "NORMAL",
                            "workerBacklogLevel": "NORMAL",
                            "maxAutoSyncTools": 1,
                            "maxAsyncTools": 1,
                            "influenceCodes": ("GATEWAY_SIGNED_POLICY_ENVELOPE",),
                        }
                    }
                }
            )
        )

        self.assertEqual(0, remote_client.calls)
        self.assertEqual("trusted-control-plane", snapshot.source)
        self.assertEqual("gateway-envelope-v1", snapshot.policy_version)
        self.assertEqual(1, snapshot.policy.max_auto_sync_tools)
        self.assertIn("GATEWAY_SIGNED_POLICY_ENVELOPE", snapshot.influence_codes)

    def test_remote_policy_falls_back_to_local_provider_when_allowed(self) -> None:
        """远程不可用且允许回退时，应继续使用本地策略保证学习环境不断流。"""

        provider = RemoteThenLocalToolExecutionReadinessPolicyProvider(
            _FailingReadinessClient(),
            local_provider=ToolExecutionReadinessPolicyProvider(),
            allow_remote_fallback=True,
        )

        snapshot = provider.policy_for(self._request())

        self.assertEqual("local-default", snapshot.source)
        self.assertEqual(3, snapshot.policy.max_auto_sync_tools)

    def test_remote_policy_raises_when_fallback_disabled(self) -> None:
        """生产环境可关闭回退，让控制面策略中心故障显式暴露而不是静默放宽执行预算。"""

        provider = RemoteThenLocalToolExecutionReadinessPolicyProvider(
            _FailingReadinessClient(),
            allow_remote_fallback=False,
        )

        with self.assertRaises(RuntimeError):
            provider.policy_for(self._request())

    def test_plan_response_uses_injected_readiness_policy_provider(self) -> None:
        """`build_plan_response` 应使用调用方注入的 readiness provider，而不是固定本地默认策略。"""

        response = build_plan_response(
            self._request(),
            _SingleToolOrchestrator(),
            tool_execution_readiness_policy_provider=_FixedReadinessPolicyProvider(
                ToolExecutionReadinessPolicySnapshot(
                    policy=ToolExecutionReadinessPolicy(max_auto_sync_tools=0, max_async_tools=0),
                    source="trusted-control-plane",
                    policy_version="perm-readiness-v3",
                    actor_role="AUDITOR",
                    tenant_plan_code="TRIAL",
                    workspace_risk_level="HIGH",
                    worker_backlog_level="CRITICAL",
                    influence_codes=("REMOTE_PERMISSION_ADMIN_POLICY",),
                )
            ),
        )

        readiness = response["toolExecutionReadiness"]
        readiness_policy = response["toolExecutionReadinessPolicy"]

        self.assertEqual("trusted-control-plane", readiness_policy["source"])
        self.assertEqual("perm-readiness-v3", readiness_policy["policyVersion"])
        self.assertEqual(0, readiness_policy["maxAutoSyncTools"])
        self.assertEqual(ToolExecutionReadinessDecision.THROTTLED.value, readiness["items"][0]["decision"])
        self.assertIn("REMOTE_PERMISSION_ADMIN_POLICY", readiness["policy"]["influenceCodes"])

    @staticmethod
    def _request(variables: dict[str, object] | None = None) -> AgentRequest:
        return AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="user-a",
            objective="测试 readiness policy 来源",
            variables=variables or {},
        )


class _RemoteReadinessClient:
    """测试替身：模拟 permission-admin 返回标准 readiness policy。"""

    def __init__(self, policy: dict[str, object]) -> None:
        self._policy = policy
        self.calls = 0

    def evaluate_readiness_policy(
        self,
        request_context: AgentRequest,
        trace_id: str | None = None,
    ) -> dict[str, object]:
        """保持与真实远程客户端相同的方法签名。"""

        self.calls += 1
        return self._policy


class _FailingReadinessClient:
    """测试替身：模拟 permission-admin 不可用。"""

    def evaluate_readiness_policy(
        self,
        request_context: AgentRequest,
        trace_id: str | None = None,
    ) -> dict[str, object] | None:
        """抛出异常以验证 fallback 和 fail-closed 两个分支。"""

        raise RuntimeError("permission-admin unavailable")


class _FixedReadinessPolicyProvider:
    """测试替身：直接返回固定 snapshot，用来验证 API 主链路的依赖注入。"""

    def __init__(self, snapshot: ToolExecutionReadinessPolicySnapshot) -> None:
        self._snapshot = snapshot

    def policy_for(self, request: AgentRequest) -> ToolExecutionReadinessPolicySnapshot:
        """与真实 provider 保持相同协议。"""

        return self._snapshot


class _SingleToolOrchestrator:
    """测试替身：返回一个低风险同步工具计划，便于观察策略预算是否生效。"""

    def plan(self, request: AgentRequest) -> AgentPlan:
        """构造最小 AgentPlan；业务重点在 readiness policy 注入，不在模型编排。"""

        return AgentPlan(
            request_id="request-readiness-policy",
            selected_route=None,
            state_trace=("intent", "tool-planning"),
            tool_plans=(
                ToolPlan(
                    tool_name="datasource.metadata.read",
                    reason="读取元数据用于测试 readiness policy。",
                    arguments={"datasourceId": "ds-sensitive"},
                ),
            ),
            requires_human_approval=False,
            response_summary="已生成工具计划。",
        )


if __name__ == "__main__":
    unittest.main()
