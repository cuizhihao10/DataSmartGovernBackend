import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.config import default_tool_registry
from datasmart_ai_runtime.domain.contracts import AgentRequest
from datasmart_ai_runtime.services.intent_analyzer import RuleBasedIntentAnalyzer
from datasmart_ai_runtime.services.tool_planner import ToolPlanner


class SyncTaskImportToolPlanningTest(unittest.TestCase):

    def test_artifact_reference_always_creates_real_dry_run_first_node(self) -> None:
        request = AgentRequest(
            tenant_id="10",
            project_id="101",
            actor_id="1001",
            objective="请检查这个任务文件，发现问题后帮我修复并导入。",
            variables={
                "taskImportArtifactRef": "sync-import-123",
                "taskImportRunImmediately": True,
            },
        )
        analyzer = RuleBasedIntentAnalyzer()
        intent = analyzer.analyze(request, ())
        plans = ToolPlanner(default_tool_registry()).plan(request, intent, ())

        self.assertIn("sync.task.import.dry-run", intent.candidate_tools)
        self.assertEqual((), intent.missing_parameters)
        self.assertEqual(1, len(plans))
        self.assertEqual("sync.task.import.dry-run", plans[0].tool_name)
        self.assertEqual("sync-import-123", plans[0].arguments["artifactRef"])
        self.assertTrue(plans[0].arguments["runImmediately"])
        self.assertEqual("model_summary_allowed", plans[0].governance_hints["outputContextPolicy"])
        self.assertIn("importResult.rows[].errorCode", plans[0].governance_hints["modelContextIncludePaths"])


if __name__ == "__main__":
    unittest.main()
