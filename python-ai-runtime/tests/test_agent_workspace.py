import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.api import build_default_orchestrator
from datasmart_ai_runtime.api_plan_response import build_plan_response
from datasmart_ai_runtime.domain.contracts import AgentRequest
from datasmart_ai_runtime.services.agent_workspace import (
    AgentWorkspaceContextBuilder,
    AgentWorkspaceIsolationLevel,
)


class AgentWorkspaceContextTest(unittest.TestCase):
    """Agent 工作空间上下文测试。

    工作空间不是一个展示字段，而是未来工具执行、模型缓存、长期记忆和资源文件输出的安全边界。
    本测试固定三种隔离等级的 key 生成规则，避免后续重构时破坏 Java/Python 两侧的 workspace 对齐。
    """

    def test_project_isolation_builds_project_workspace_key(self) -> None:
        """PROJECT 是默认隔离等级，适合项目级治理上下文。"""

        context = AgentWorkspaceContextBuilder().build(
            AgentRequest(
                tenant_id="10",
                project_id="20",
                actor_id="analyst-a",
                objective="分析项目级数据治理目标",
            )
        )

        self.assertEqual(AgentWorkspaceIsolationLevel.PROJECT, context.isolation_level)
        self.assertEqual("tenant:10:project:20", context.workspace_key)
        self.assertEqual("memory:tenant:10:project:20", context.memory_namespace)

    def test_workspace_isolation_uses_workspace_id(self) -> None:
        """WORKSPACE 隔离会在项目下增加协作空间维度。"""

        context = AgentWorkspaceContextBuilder().build(
            AgentRequest(
                tenant_id="10",
                project_id="20",
                actor_id="owner-a",
                objective="在专项治理工作空间内规划任务",
                variables={"isolationLevel": "WORKSPACE", "workspaceId": "30"},
            )
        )

        self.assertEqual(AgentWorkspaceIsolationLevel.WORKSPACE, context.isolation_level)
        self.assertEqual("tenant:10:project:20:workspace:30", context.workspace_key)
        self.assertEqual("artifact:tenant:10:project:20:workspace:30", context.artifact_namespace)

    def test_session_isolation_uses_session_id(self) -> None:
        """SESSION 隔离适合敏感工具输出和一次性实验。"""

        context = AgentWorkspaceContextBuilder().build(
            AgentRequest(
                tenant_id="10",
                project_id="20",
                actor_id="auditor-a",
                objective="处理包含敏感字段的临时分析",
                variables={"isolation_level": "SESSION", "workspace_id": "30", "sessionId": "session-a"},
            )
        )

        self.assertEqual(AgentWorkspaceIsolationLevel.SESSION, context.isolation_level)
        self.assertEqual("tenant:10:project:20:workspace:30:session:session-a", context.workspace_key)
        self.assertEqual("cache:tenant:10:project:20:workspace:30:session:session-a", context.cache_namespace)

    def test_plan_response_exposes_agent_workspace_summary(self) -> None:
        """同步计划响应应暴露工作空间摘要，供前端、gateway 和 Java 控制面使用。"""

        response = build_plan_response(
            AgentRequest(
                tenant_id="10",
                project_id="20",
                actor_id="analyst-a",
                objective="请先分析这个数据源",
                variables={"sessionId": "session-a", "datasourceId": "ds-001"},
            ),
            build_default_orchestrator(),
        )

        workspace = response["agentWorkspace"]
        self.assertEqual("tenant:10:project:20", workspace["workspaceKey"])
        self.assertEqual("PROJECT", workspace["isolationLevel"])
        self.assertIn("memoryNamespace", workspace)
        self.assertTrue(workspace["recommendedActions"])

    def test_plan_response_attaches_workspace_hints_to_each_tool_plan(self) -> None:
        """每个 ToolPlan 都应携带 workspace hints。

        顶层 `agentWorkspace` 方便前端展示，但 Java 控制面和工具执行审计通常按单个 ToolPlan 工作。
        如果工具计划里没有 workspace hints，后续执行器就无法独立判断输出文件、缓存和长期记忆的 namespace。
        """

        response = build_plan_response(
            AgentRequest(
                tenant_id="10",
                project_id="20",
                actor_id="analyst-a",
                objective="请在会话沙箱内生成质量规则",
                variables={
                    "isolationLevel": "SESSION",
                    "workspaceId": "30",
                    "sessionId": "session-a",
                    "datasourceId": "ds-001",
                },
            ),
            build_default_orchestrator(),
        )

        tool_plans = response["plan"]["tool_plans"]
        self.assertTrue(tool_plans)
        for tool_plan in tool_plans:
            hints = tool_plan["governance_hints"]
            self.assertEqual("tenant:10:project:20:workspace:30:session:session-a", hints["workspaceKey"])
            self.assertEqual("SESSION", hints["workspaceIsolationLevel"])
            self.assertEqual("memory:tenant:10:project:20:workspace:30:session:session-a", hints["memoryNamespace"])
            self.assertEqual("artifact:tenant:10:project:20:workspace:30:session:session-a", hints["artifactNamespace"])


if __name__ == "__main__":
    unittest.main()
