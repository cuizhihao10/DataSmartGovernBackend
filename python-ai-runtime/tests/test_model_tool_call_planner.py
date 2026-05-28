import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.config import default_tool_registry
from datasmart_ai_runtime.domain.contracts import ModelToolCall
from datasmart_ai_runtime.services.model_tool_call_planner import ModelToolCallPlanner


class ModelToolCallPlannerTest(unittest.TestCase):
    def test_valid_model_tool_call_maps_to_tool_plan_with_validation(self) -> None:
        """模型工具调用参数合法且工具已暴露时，应生成 DataSmart ToolPlan。"""

        tools = default_tool_registry()
        report = ModelToolCallPlanner().plan(
            tool_calls=(
                ModelToolCall(
                    call_id="call_quality_001",
                    name="quality.rule.suggest",
                    arguments="{\"datasourceId\":\"ds-001\",\"businessGoal\":\"客户主数据完整性\"}",
                ),
            ),
            registered_tools=tools,
            visible_tools=tuple(tool for tool in tools if tool.name == "quality.rule.suggest"),
        )

        self.assertEqual(1, len(report.accepted_tool_plans))
        plan = report.accepted_tool_plans[0]
        self.assertEqual("quality.rule.suggest", plan.tool_name)
        self.assertEqual("ds-001", plan.arguments["datasourceId"])
        self.assertEqual("call_quality_001", plan.governance_hints["modelToolCallId"])
        self.assertTrue(plan.parameter_validation.can_execute)
        self.assertFalse(report.rejected_candidates)

    def test_openai_function_alias_maps_back_to_datasmart_tool_name(self) -> None:
        """模型回传下划线函数名时，应能映射回 DataSmart 点号工具名。"""

        tools = default_tool_registry()
        report = ModelToolCallPlanner().plan(
            tool_calls=(
                ModelToolCall(
                    call_id="call_meta",
                    name="datasource_metadata_read",
                    arguments="{\"datasourceId\":\"ds-002\"}",
                ),
            ),
            registered_tools=tools,
            visible_tools=tuple(tool for tool in tools if tool.name == "datasource.metadata.read"),
        )

        self.assertEqual("datasource.metadata.read", report.accepted_tool_plans[0].tool_name)
        self.assertEqual("ds-002", report.accepted_tool_plans[0].arguments["datasourceId"])

    def test_unknown_tool_is_rejected(self) -> None:
        """未知工具必须拒绝，避免模型幻觉工具进入执行链路。"""

        report = ModelToolCallPlanner().plan(
            tool_calls=(ModelToolCall(call_id="call_unknown", name="unknown.tool", arguments="{}"),),
            registered_tools=default_tool_registry(),
        )

        self.assertEqual((), report.accepted_tool_plans)
        self.assertEqual("MODEL_TOOL_CALL_UNKNOWN_TOOL", report.issues[0].code)
        self.assertTrue(report.issues[0].blocking)

    def test_not_visible_tool_is_rejected_even_if_registered(self) -> None:
        """已注册但本轮未暴露的工具也必须拒绝，符合最小权限原则。"""

        tools = default_tool_registry()
        visible_tools = tuple(tool for tool in tools if tool.name == "datasource.metadata.read")
        report = ModelToolCallPlanner().plan(
            tool_calls=(
                ModelToolCall(
                    call_id="call_task",
                    name="task.create.draft",
                    arguments="{\"taskType\":\"DATA_QUALITY_SCAN\",\"payload\":{}}",
                ),
            ),
            registered_tools=tools,
            visible_tools=visible_tools,
        )

        self.assertEqual((), report.accepted_tool_plans)
        self.assertEqual("MODEL_TOOL_CALL_NOT_EXPOSED", report.issues[0].code)

    def test_invalid_json_arguments_are_rejected(self) -> None:
        """arguments 非法 JSON 时不能生成 ToolPlan。"""

        report = ModelToolCallPlanner().plan(
            tool_calls=(ModelToolCall(call_id="call_bad_json", name="quality.rule.suggest", arguments="{bad-json}"),),
            registered_tools=default_tool_registry(),
        )

        self.assertEqual((), report.accepted_tool_plans)
        self.assertEqual("MODEL_TOOL_CALL_ARGUMENTS_INVALID_JSON", report.issues[0].code)

    def test_missing_required_parameter_keeps_plan_but_validation_blocks_execution(self) -> None:
        """参数缺失时可以生成 ToolPlan，但参数校验会阻止直接执行。"""

        tools = default_tool_registry()
        report = ModelToolCallPlanner().plan(
            tool_calls=(ModelToolCall(call_id="call_missing", name="quality.rule.suggest", arguments="{}"),),
            registered_tools=tools,
            visible_tools=tuple(tool for tool in tools if tool.name == "quality.rule.suggest"),
        )

        plan = report.accepted_tool_plans[0]
        self.assertFalse(plan.parameter_validation.can_execute)
        self.assertEqual({"datasourceId", "businessGoal"}, {issue.parameter_name for issue in plan.parameter_validation.issues})

    def test_approval_required_tool_generates_non_blocking_issue_and_tool_plan(self) -> None:
        """需审批工具应生成 ToolPlan 和非阻断审批 issue，而不是静默自动执行。"""

        tools = default_tool_registry()
        report = ModelToolCallPlanner().plan(
            tool_calls=(
                ModelToolCall(
                    call_id="call_task",
                    name="task.create.draft",
                    arguments="{\"taskType\":\"DATA_QUALITY_SCAN\",\"payload\":{\"objective\":\"scan\"}}",
                ),
            ),
            registered_tools=tools,
            visible_tools=tuple(tool for tool in tools if tool.name == "task.create.draft"),
        )

        self.assertEqual(1, len(report.accepted_tool_plans))
        self.assertTrue(report.accepted_tool_plans[0].requires_human_approval)
        self.assertEqual("MODEL_TOOL_CALL_APPROVAL_REQUIRED", report.issues[0].code)
        self.assertFalse(report.issues[0].blocking)


if __name__ == "__main__":
    unittest.main()
