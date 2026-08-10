"""验证 FastAPI 生产组合根确实装配六类专业 Agent。

单个 specialist 的单元测试只能证明领域类可运行，无法证明容器启动时真的把它注册进协调器。
本测试使用不会联网的假服务地址和 OpenAI-compatible 路由配置创建真实 FastAPI app，然后读取
``app.state`` 中的低敏装配事实。任何角色导入遗漏、环境变量命名漂移或工具白名单遗漏都会在这里
直接失败，而不是等到前端显示 ``SPECIALIST_NOT_REGISTERED`` 才被发现。
"""

from __future__ import annotations

import os
import sys
import unittest
from unittest.mock import patch


ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.api.app import create_app
from datasmart_ai_runtime.services.agent_gateway.session_models import AgentSessionRole
from datasmart_ai_runtime.services.multi_agent.specialist_metadata_adapters import (
    HttpSyncMetadataDiscoveryTool,
)


class SpecialistAppBootstrapTest(unittest.TestCase):
    """覆盖真实模型和 Java 服务地址齐备时的六角色生产装配。"""

    def test_real_provider_and_control_plane_register_all_six_specialists(self) -> None:
        """依赖齐备时注册六类 Agent，并为每个角色分配最小工具白名单。

        地址只用于构造客户端，本测试不会发起 HTTP 请求；模型 Provider 也不会真正调用。测试关注的是
        ``create_app`` 组合根是否把领域实现、治理模型适配器、只读 HTTP 客户端和 Durable 协调器连成
        同一个运行时对象图。
        """

        environment = {
            "DATASMART_AI_OPENAI_COMPATIBLE_BASE_URL": "http://model-gateway.test/v1",
            "DATASMART_AI_AGENT_REASONING_MODEL": "test-agent-model",
            "DATASMART_DATASOURCE_MANAGEMENT_BASE_URL": "http://datasource-management.test",
            "DATASMART_DATA_SYNC_BASE_URL": "http://data-sync.test",
            # 元数据读取复用现有 Agent Runtime 内部服务 token；测试只验证它进入共享
            # Settings，不会发起 HTTP，也不会把 token 写入断言输出。
            "DATASMART_AGENT_RUNTIME_INTERNAL_SERVICE_TOKEN": "bootstrap-internal-token",
            # 关闭网络型后台组件，确保该测试只验证应用装配而不依赖本地 Compose。
            "DATASMART_AGENT_RUNTIME_SPECIALIST_TURN_FACT_ENABLED": "false",
            "DATASMART_MEMORY_MATERIALIZATION_WORKER_ENABLED": "false",
        }
        expected_roles = {
            AgentSessionRole.KNOWLEDGE_AGENT,
            AgentSessionRole.DATASOURCE_AGENT,
            AgentSessionRole.DATA_SYNC_AGENT,
            AgentSessionRole.PRECHECK_AGENT,
            AgentSessionRole.RECOVERY_AGENT,
            AgentSessionRole.MONITOR_AGENT,
        }

        with patch.dict(os.environ, environment, clear=False):
            app = create_app()

        available_roles = set(app.state.specialist_agent_registry.available_roles())
        self.assertEqual(expected_roles, available_roles)
        self.assertEqual(
            {role.value for role in expected_roles},
            set(app.state.specialist_allowed_tools_by_role),
        )

        # 恢复 Agent 在 Python 中只能诊断；真正的修复工具必须由 Java 主控制面另行授权和执行。
        self.assertEqual(
            ("recovery.failure.diagnose",),
            app.state.specialist_allowed_tools_by_role[AgentSessionRole.RECOVERY_AGENT.value],
        )
        self.assertIsInstance(
            app.state.specialist_metadata_discovery_tool,
            HttpSyncMetadataDiscoveryTool,
        )
        self.assertEqual(
            "http://data-sync.test",
            app.state.specialist_metadata_discovery_tool._settings.base_url,
        )
        self.assertEqual(
            "bootstrap-internal-token",
            app.state.specialist_metadata_discovery_tool._settings.service_token,
        )


if __name__ == "__main__":
    unittest.main()
