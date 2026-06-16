import os
import sys
import unittest
from unittest.mock import patch

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.api import build_tool_action_resume_fact_provider
from datasmart_ai_runtime.services.tools import (
    EmptyToolActionResumeFactProvider,
    JavaAgentRuntimeToolActionResumeFactBundleClient,
    JavaPermissionAdminToolActionResumeFactClient,
)


class ToolActionResumeFactBootstrapTest(unittest.TestCase):
    """工具动作恢复事实 provider 启动装配测试。

    该测试从 `test_api_bootstrap.py` 中拆出，避免通用 API bootstrap 测试文件超过 500 行。
    它只固定一个启动契约：远程 permission-admin 校验必须显式开启，不能因为其他工具预算/readiness
    开关被误触发。
    """

    def test_build_tool_action_resume_fact_provider_is_default_disabled_and_can_use_permission_admin(self) -> None:
        """恢复事实 provider 默认不访问远程，显式开启旧开关后仍可装配 permission-admin 客户端。"""

        with patch.dict(os.environ, {}, clear=True):
            disabled_provider = build_tool_action_resume_fact_provider()
        with patch.dict(
            os.environ,
            {
                "DATASMART_PERMISSION_ADMIN_TOOL_ACTION_RESUME_FACTS_ENABLED": "true",
                "DATASMART_PERMISSION_ADMIN_BASE_URL": "http://permission-admin.test",
            },
            clear=True,
        ):
            remote_provider = build_tool_action_resume_fact_provider()

        self.assertIsInstance(disabled_provider, EmptyToolActionResumeFactProvider)
        self.assertIsInstance(remote_provider, JavaPermissionAdminToolActionResumeFactClient)

    def test_build_tool_action_resume_fact_provider_prefers_agent_runtime_bundle(self) -> None:
        """启用 Java fact bundle 时，应优先装配 agent-runtime 控制面 provider。"""

        with patch.dict(
            os.environ,
            {
                "DATASMART_AGENT_RUNTIME_RESUME_FACT_BUNDLE_ENABLED": "true",
                "DATASMART_AGENT_RUNTIME_BASE_URL": "http://agent-runtime.test",
                "DATASMART_PERMISSION_ADMIN_TOOL_ACTION_RESUME_FACTS_ENABLED": "true",
                "DATASMART_PERMISSION_ADMIN_BASE_URL": "http://permission-admin.test",
            },
            clear=True,
        ):
            remote_provider = build_tool_action_resume_fact_provider()

        self.assertIsInstance(remote_provider, JavaAgentRuntimeToolActionResumeFactBundleClient)


if __name__ == "__main__":
    unittest.main()
