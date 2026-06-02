import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.api_skill_admission import build_skill_admission_policy
from datasmart_ai_runtime.config import default_skill_registry
from datasmart_ai_runtime.domain.contracts import AgentRequest
from datasmart_ai_runtime.services.skill_admission_policy import (
    AgentSkillAdmissionDecision,
    JavaPermissionAdminSkillAdmissionClient,
    PermissionAdminSkillAdmissionClientError,
    RemoteThenLocalAgentSkillAdmissionPolicy,
)


class SkillAdmissionPolicyTest(unittest.TestCase):
    """Agent Skill 远程准入策略测试。

    这些测试保护的是 Python Runtime 与 Java permission-admin 的契约边界：
    Python 负责 Skill 语义选择，Java 控制面负责回答准入结果，远程失败时是否回退由部署策略决定。
    """

    def test_java_permission_admin_payload_maps_skill_and_request_context(self) -> None:
        """远程请求应同时携带 Skill descriptor 和 Agent 请求治理上下文。"""

        skill = self._skill("quality.rule.design")
        payload = JavaPermissionAdminSkillAdmissionClient.build_payload(
            skill,
            self._request(
                tenant_id="10",
                variables={
                    "projectId": "project-java",
                    "workspaceKey": "tenant-10:project-java:workspace-a",
                    "actorRole": "PROJECT_OWNER",
                    "grantedPermissions": ("quality:rule:draft", "datasource:metadata:read"),
                    "tenantSkillEnabled": False,
                    "workspaceRiskLevel": "HIGH",
                    "tenantPlanCode": "ENTERPRISE",
                },
            ),
        )

        self.assertEqual(10, payload["tenantId"])
        self.assertEqual("project-java", payload["projectId"])
        self.assertEqual("quality.rule.design", payload["skillCode"])
        self.assertEqual("MEDIUM", payload["riskLevel"])
        self.assertEqual(("quality:rule:draft",), payload["requiredPermissions"])
        self.assertEqual({"quality:rule:draft", "datasource:metadata:read"}, set(payload["grantedPermissions"]))
        self.assertFalse(payload["tenantSkillEnabled"])
        self.assertEqual("HIGH", payload["workspaceRiskLevel"])
        self.assertEqual("ENTERPRISE", payload["tenantPlanCode"])

    def test_java_permission_admin_response_maps_to_admission_decision(self) -> None:
        """Java Skill admission 响应应转换为 Python 准入决策。"""

        decision = JavaPermissionAdminSkillAdmissionClient.parse_platform_response(
            {
                "code": 0,
                "data": {
                    "allowed": False,
                    "admissionStatus": "DENIED_MISSING_PERMISSION",
                    "policyVersion": "agent-skill-admission:v1:quality",
                    "matchedPolicy": "MISSING_REQUIRED_PERMISSION",
                    "rejectionReason": "缺少 quality:rule:draft",
                    "notes": ("权限不足",),
                },
            }
        )

        self.assertFalse(decision.allowed)
        self.assertEqual("DENIED_MISSING_PERMISSION", decision.status)
        self.assertTrue(any("quality:rule:draft" in reason for reason in decision.reasons))
        self.assertTrue(any("policyVersion" in reason for reason in decision.reasons))

    def test_remote_policy_uses_permission_admin_decision_when_available(self) -> None:
        """远程可用时应优先采用 permission-admin 的准入结果。"""

        policy = RemoteThenLocalAgentSkillAdmissionPolicy(
            _FakeRemoteSkillAdmissionClient(AgentSkillAdmissionDecision(False, "DENIED_TENANT_DISABLED", ("租户关闭",))),
        )

        decision = policy.evaluate(self._skill("quality.rule.design"), self._request())

        self.assertFalse(decision.allowed)
        self.assertEqual("DENIED_TENANT_DISABLED", decision.status)

    def test_remote_policy_falls_back_to_local_policy_when_allowed(self) -> None:
        """远程不可用且允许回退时，应使用本地策略保障本地学习和灰度联调不断流。"""

        policy = RemoteThenLocalAgentSkillAdmissionPolicy(
            _FailingRemoteSkillAdmissionClient(),
            allow_remote_fallback=True,
        )

        decision = policy.evaluate(
            self._skill("quality.rule.design"),
            self._request(variables={"grantedPermissions": ("datasource:metadata:read",), "actorRole": "PROJECT_OWNER"}),
        )

        self.assertFalse(decision.allowed)
        self.assertEqual("DENIED_MISSING_PERMISSION", decision.status)

    def test_remote_policy_raises_when_fallback_disabled(self) -> None:
        """生产环境可关闭回退，让 permission-admin 故障显式暴露。"""

        policy = RemoteThenLocalAgentSkillAdmissionPolicy(
            _FailingRemoteSkillAdmissionClient(),
            allow_remote_fallback=False,
        )

        with self.assertRaises(PermissionAdminSkillAdmissionClientError):
            policy.evaluate(self._skill("quality.rule.design"), self._request())

    def test_bootstrap_builds_remote_skill_admission_policy_when_enabled(self) -> None:
        """API bootstrap 应能按环境开关装配远程 Skill admission provider。"""

        policy = build_skill_admission_policy(
            permission_admin_base_url="http://permission-admin.local",
            enable_remote=True,
        )

        self.assertIsInstance(policy, RemoteThenLocalAgentSkillAdmissionPolicy)

    @staticmethod
    def _skill(skill_code: str):
        return next(skill for skill in default_skill_registry() if skill.skill_code == skill_code)

    @staticmethod
    def _request(variables: dict[str, object] | None = None, tenant_id: str = "tenant-a") -> AgentRequest:
        return AgentRequest(
            tenant_id=tenant_id,
            project_id="project-a",
            actor_id="user-a",
            objective="测试 Skill 准入",
            variables=variables or {},
        )


class _FakeRemoteSkillAdmissionClient:
    """测试替身：模拟 permission-admin 返回准入结果。"""

    def __init__(self, decision: AgentSkillAdmissionDecision) -> None:
        self._decision = decision

    def evaluate(self, skill, request_context, trace_id=None):
        return self._decision


class _FailingRemoteSkillAdmissionClient:
    """测试替身：模拟 permission-admin 不可用。"""

    def evaluate(self, skill, request_context, trace_id=None):
        raise PermissionAdminSkillAdmissionClientError("permission-admin unavailable")


if __name__ == "__main__":
    unittest.main()
