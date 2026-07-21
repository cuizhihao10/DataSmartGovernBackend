import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.api.agent.observation_timeline import build_agent_observation_timeline
from datasmart_ai_runtime.domain.contracts import AgentPlan, ToolPlan
from datasmart_ai_runtime.domain.intent import GovernanceDomain, IntentAnalysis


class AgentObservationTimelineTest(unittest.TestCase):
    """保护 Agent 可观测界面只展示执行事实，不泄露 prompt、参数或隐藏思维链。"""

    def test_projects_model_graph_tool_command_and_control_plane_facts(self) -> None:
        plan = AgentPlan(
            request_id="request-observation",
            selected_route=None,
            state_trace=("receive_goal", "invoke_model_intent", "plan_tools"),
            tool_plans=(ToolPlan(tool_name="datasource.connection.test", reason="验证已授权连接"),),
            requires_human_approval=False,
            response_summary="ready",
            model_invocation_summary={
                "selectedProviderName": "managed-router",
                "selectedModelName": "managed-model",
                "providerInvoked": True,
                "providerSucceeded": True,
                "latencyMs": 321,
                "promptTokens": 12,
                "completionTokens": 8,
                "totalTokens": 20,
                "toolCallCount": 1,
            },
            intent_analysis=IntentAnalysis(
                governance_domains=(GovernanceDomain.DATASOURCE,),
                candidate_tools=("datasource.connection.test",),
                confidence=0.65,
                summary="识别为数据源连接验证。",
            ),
        )
        timeline = build_agent_observation_timeline(
            plan,
            control_plane_handoff={
                "templateSummaries": (
                    {
                        "templateId": "template-1",
                        "toolName": "datasource.connection.test",
                        "decision": "EXECUTABLE",
                        "outboxPreflightCandidate": True,
                    },
                )
            },
            control_plane_ingestion={
                "ingested": True,
                "sessionId": "session-1",
                "runId": "run-1",
                "toolAuditCount": 1,
            },
        )

        categories = {item["category"] for item in timeline["items"]}
        self.assertEqual({"MODEL", "DECISION", "GRAPH", "TOOL", "COMMAND"}, categories)
        model_item = next(item for item in timeline["items"] if item["id"] == "model-invocation")
        self.assertEqual("SUCCEEDED", model_item["status"])
        self.assertEqual(20, model_item["details"]["totalTokens"])
        serialized = str(timeline).lower()
        self.assertNotIn("password", serialized)
        self.assertNotIn("select *", serialized)
        self.assertIn("chainofthought", serialized)
        self.assertEqual("LOW_SENSITIVE_EXECUTION_FACTS_NO_CHAIN_OF_THOUGHT", timeline["payloadPolicy"])


if __name__ == "__main__":
    unittest.main()
