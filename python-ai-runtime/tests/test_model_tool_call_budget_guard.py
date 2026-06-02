import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.domain.contracts import (
    ModelToolCall,
    ToolDefinition,
    ToolExecutionMode,
    ToolRiskLevel,
)
from datasmart_ai_runtime.services.model_gateway.model_tool_call_budget_guard import (
    ModelToolCallBudgetGuard,
    ModelToolCallBudgetPolicy,
)
from datasmart_ai_runtime.services.model_gateway.model_tool_call_planner import ModelToolCallPlanner


class ModelToolCallBudgetGuardTest(unittest.TestCase):
    """模型工具调用预算守卫测试。

    这些测试不直接覆盖“工具是否存在”和“参数 schema 是否正确”，那些由
    `test_model_tool_call_planner.py` 负责。本文件只关注智能网关层面的批量、体积和风险预算：
    模型即使提出了合法工具调用，也不代表平台应当在同一轮里全部放行。
    """

    def test_auto_executable_tool_count_is_limited(self) -> None:
        """可自动推进工具超过上限时，后续候选应被预算守卫阻断。"""

        report = self._planning_report(
            ModelToolCall(call_id="call-a", name="metadata.read", arguments='{"datasourceId":"ds-a"}'),
            ModelToolCall(call_id="call-b", name="profile.read", arguments='{"datasourceId":"ds-b"}'),
        )

        guarded = ModelToolCallBudgetGuard(
            ModelToolCallBudgetPolicy(max_auto_executable_tool_calls=1)
        ).evaluate(report)

        self.assertFalse(guarded.allowed)
        self.assertEqual(2, guarded.accepted_count_before_guard)
        self.assertEqual(1, guarded.accepted_count_after_guard)
        self.assertIn("MODEL_TOOL_CALL_BUDGET_AUTO_EXECUTABLE_COUNT_EXCEEDED", guarded.budget_issue_codes)
        self.assertEqual("profile.read", guarded.guarded_report.rejected_candidates[0].resolved_tool_name)

    def test_proposed_tool_count_overflow_blocks_tail_candidates(self) -> None:
        """模型一次提出过多工具调用时，应从超出上限的尾部候选开始阻断。"""

        report = self._planning_report(
            ModelToolCall(call_id="call-a", name="metadata.read", arguments='{"datasourceId":"ds-a"}'),
            ModelToolCall(call_id="call-b", name="profile.read", arguments='{"datasourceId":"ds-b"}'),
        )

        guarded = ModelToolCallBudgetGuard(
            ModelToolCallBudgetPolicy(max_proposed_tool_calls=1, max_auto_executable_tool_calls=10)
        ).evaluate(report)

        self.assertEqual(1, guarded.accepted_count_after_guard)
        self.assertIn("MODEL_TOOL_CALL_BUDGET_PROPOSED_COUNT_EXCEEDED", guarded.budget_issue_codes)

    def test_large_arguments_are_blocked_before_entering_execution_plan(self) -> None:
        """超大 arguments 应被阻断，避免样本数据或注入内容直接进入工具链。"""

        large_payload = "x" * 128
        report = self._planning_report(
            ModelToolCall(call_id="call-large", name="metadata.read", arguments=f'{{"payload":"{large_payload}"}}'),
        )

        guarded = ModelToolCallBudgetGuard(
            ModelToolCallBudgetPolicy(max_single_arguments_bytes=32, max_total_arguments_bytes=64)
        ).evaluate(report)

        self.assertEqual(0, guarded.accepted_count_after_guard)
        self.assertIn("MODEL_TOOL_CALL_BUDGET_SINGLE_ARGUMENTS_TOO_LARGE", guarded.budget_issue_codes)
        self.assertIn("MODEL_TOOL_CALL_BUDGET_TOTAL_ARGUMENTS_TOO_LARGE", guarded.budget_issue_codes)

    def test_high_risk_tool_count_is_limited_even_when_each_call_is_valid(self) -> None:
        """多个合法高风险工具也不能在同一轮全部进入计划。"""

        tools = (
            self._tool("task.persist.a", risk_level=ToolRiskLevel.HIGH, execution_mode=ToolExecutionMode.SYNC),
            self._tool("task.persist.b", risk_level=ToolRiskLevel.HIGH, execution_mode=ToolExecutionMode.SYNC),
        )
        report = ModelToolCallPlanner().plan(
            tool_calls=(
                ModelToolCall(call_id="call-a", name="task.persist.a", arguments='{"datasourceId":"ds-a"}'),
                ModelToolCall(call_id="call-b", name="task.persist.b", arguments='{"datasourceId":"ds-b"}'),
            ),
            registered_tools=tools,
            visible_tools=tools,
        )

        guarded = ModelToolCallBudgetGuard(
            ModelToolCallBudgetPolicy(max_high_risk_tool_calls=1, max_auto_executable_tool_calls=10)
        ).evaluate(report)

        self.assertEqual(1, guarded.accepted_count_after_guard)
        self.assertIn("MODEL_TOOL_CALL_BUDGET_HIGH_RISK_COUNT_EXCEEDED", guarded.budget_issue_codes)

    @staticmethod
    def _planning_report(*tool_calls: ModelToolCall):
        """生成只读低风险工具的规划报告，供预算守卫测试复用。"""

        tools = (
            ModelToolCallBudgetGuardTest._tool("metadata.read"),
            ModelToolCallBudgetGuardTest._tool("profile.read"),
        )
        return ModelToolCallPlanner().plan(tool_calls=tool_calls, registered_tools=tools, visible_tools=tools)

    @staticmethod
    def _tool(
        name: str,
        *,
        risk_level: ToolRiskLevel = ToolRiskLevel.LOW,
        execution_mode: ToolExecutionMode = ToolExecutionMode.SYNC,
    ) -> ToolDefinition:
        """构造一个最小工具定义。

        这里显式要求 `datasourceId`，让 planner 仍然会执行基础参数校验。预算测试要先保证工具调用本身
        合法，再验证 guard 是否因为数量、体积或风险阻断。
        """

        return ToolDefinition(
            name=name,
            description="测试工具",
            risk_level=risk_level,
            execution_mode=execution_mode,
            input_schema={
                "type": "object",
                "properties": {"datasourceId": {"type": "string"}, "payload": {"type": "string"}},
                "required": ["datasourceId"],
            },
            read_only=risk_level == ToolRiskLevel.LOW,
        )


if __name__ == "__main__":
    unittest.main()
