import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.config import default_tool_registry
from datasmart_ai_runtime.domain.contracts import (
    ToolDefinition,
    ToolExecutionMode,
    ToolParameterIssueAction,
    ToolRiskLevel,
)
from datasmart_ai_runtime.services.tool_parameter_validator import ToolParameterValidator


class ToolParameterValidatorTest(unittest.TestCase):
    def test_sync_tool_missing_context_fillable_parameter_waits_for_context(self) -> None:
        tool = next(tool for tool in default_tool_registry() if tool.name == "datasource.metadata.read")

        result = ToolParameterValidator().validate(tool, {})

        self.assertFalse(result.can_execute)
        self.assertFalse(result.can_create_draft)
        self.assertEqual("datasourceId", result.issues[0].parameter_name)
        self.assertEqual(ToolParameterIssueAction.CAN_FILL_FROM_CONTEXT, result.issues[0].action)

    def test_draft_tool_missing_parameter_can_stay_as_draft(self) -> None:
        tool = next(tool for tool in default_tool_registry() if tool.name == "quality.rule.suggest")

        result = ToolParameterValidator().validate(tool, {"datasourceId": "ds-a"})

        self.assertFalse(result.can_execute)
        self.assertTrue(result.can_create_draft)
        self.assertEqual("businessGoal", result.issues[0].parameter_name)
        self.assertEqual(ToolParameterIssueAction.ALLOW_DRAFT, result.issues[0].action)

    def test_rich_schema_can_mark_context_fillable_parameter(self) -> None:
        tool = ToolDefinition(
            name="asset.lineage.lookup",
            description="根据资产 ID 查询血缘关系。",
            risk_level=ToolRiskLevel.LOW,
            execution_mode=ToolExecutionMode.SYNC,
            input_schema={
                "assetId": {
                    "type": "string",
                    "required": True,
                    "resolution": "context_or_clarify",
                }
            },
        )

        result = ToolParameterValidator().validate(tool, {})

        self.assertFalse(result.can_execute)
        self.assertEqual(ToolParameterIssueAction.CAN_FILL_FROM_CONTEXT, result.issues[0].action)


if __name__ == "__main__":
    unittest.main()
