import os
import sys
import unittest
from dataclasses import replace

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.api.agent.observation_timeline import build_agent_observation_timeline
from datasmart_ai_runtime.domain.contracts import (
    AgentPlan,
    ToolParameterIssue,
    ToolParameterIssueAction,
    ToolParameterValidationResult,
    ToolPlan,
)
from datasmart_ai_runtime.domain.intent import GovernanceDomain, IntentAnalysis
from datasmart_ai_runtime.domain.memory import AgentMemoryType
from datasmart_ai_runtime.domain.skills import AgentSkillPlan, AgentSkillSelection


class AgentObservationTimelineTest(unittest.TestCase):
    """保护 Agent 可观测界面只展示执行事实，不泄露 prompt、参数或隐藏思维链。"""

    def test_projects_public_decision_skill_orchestration_and_user_actions(self) -> None:
        plan = AgentPlan(
            request_id="request-observation",
            selected_route=None,
            state_trace=(
                "workflow:langgraph_executed",
                "receive_goal",
                "invoke_model_intent",
                "select_skills",
                "plan_tools",
                "clarify_missing_parameters",
            ),
            tool_plans=(
                ToolPlan(
                    tool_name="datasource.connection.test",
                    reason="验证已授权连接",
                    requires_human_approval=True,
                    parameter_validation=ToolParameterValidationResult(
                        can_execute=False,
                        issues=(
                            ToolParameterIssue(
                                parameter_name="sourceDatasourceId",
                                expected_type="integer",
                                action=ToolParameterIssueAction.MUST_CLARIFY,
                                message="请选择源端数据源。",
                            ),
                        ),
                    ),
                ),
            ),
            requires_human_approval=True,
            response_summary="ready",
            model_decision_summary=(
                "我理解你希望验证当前项目中的数据源连接；先加载数据源诊断能力，"
                "但执行前仍需要选择源端数据源。api_key=should-not-leak"
            ),
            model_invocation_summary={
                "selectedProviderName": "managed-router",
                "selectedModelName": "managed-model",
                "providerInvoked": True,
                "providerSucceeded": True,
                "latencyMs": 321,
                "promptTokens": 12,
                "completionTokens": 8,
                "cachedPromptTokens": 6,
                "totalTokens": 20,
                "toolCallCount": 1,
            },
            model_interaction_summary={
                "request": {
                    "objective": "验证当前项目中的数据源连接",
                    "instructionSummary": "生成公开回复，只能从可见工具中提出调用。",
                    "messageShape": "system 安全边界 + user 目标与结构化基线",
                    "structuredBaseline": "识别为数据源连接验证。",
                    "visibleToolNames": ("datasource.connection.test",),
                    "contextTitles": ("数据源元数据",),
                },
                "response": {
                    "content": "模型完整公开回复。secret=should-not-leak",
                    "secondTurnContent": "",
                },
                "planning": {
                    "toolSelectionSource": "SYSTEM_RULE_FALLBACK",
                    "modelGeneratedToolCount": 0,
                    "modelGeneratedToolNames": (),
                    "ruleGeneratedToolCount": 1,
                    "ruleGeneratedToolNames": ("datasource.connection.test",),
                    "finalToolCount": 1,
                    "finalToolNames": ("datasource.connection.test",),
                },
            },
            intent_analysis=IntentAnalysis(
                governance_domains=(GovernanceDomain.DATASOURCE,),
                candidate_tools=("datasource.connection.test",),
                confidence=0.65,
                summary="识别为数据源连接验证。",
                reasoning="目标涉及数据源连接，只规划读取与连接验证能力。",
                missing_parameters=("sourceDatasourceId",),
            ),
            skill_plan=AgentSkillPlan(
                selected_skills=(
                    AgentSkillSelection(
                        skill_code="datasource-diagnostics",
                        display_name="数据源诊断",
                        domain=GovernanceDomain.DATASOURCE,
                        score=0.91,
                        reason="目标需要验证项目内数据源连接。",
                        required_tools=("datasource.connection.test",),
                        required_permissions=("datasource:use",),
                        memory_dependencies=(AgentMemoryType.SHORT_TERM,),
                    ),
                ),
                rejected_skills=(
                    AgentSkillSelection(
                        skill_code="datasource-secret-admin",
                        display_name="数据源凭据管理",
                        domain=GovernanceDomain.DATASOURCE,
                        score=0.77,
                        reason="语义相关但普通 Agent 会话不得读取凭据。",
                        required_permissions=("datasource:credential:manage",),
                        admission_status="DENIED",
                        admission_reasons=("当前用户缺少凭据管理权限",),
                    ),
                ),
                available_skill_count=2,
            ),
        )
        timeline = build_agent_observation_timeline(
            plan,
            conversation={
                "phase": "WAITING_CLARIFICATION",
                "nextAction": "ANSWER_CLARIFICATIONS",
                "clarificationQuestions": (
                    {
                        "parameterName": "sourceDatasourceId",
                        "label": "源端数据源",
                        "question": "请选择当前项目内已授权的源端数据源。",
                        "inputType": "DATASOURCE_SELECT",
                        "required": True,
                        "sensitive": False,
                    },
                ),
            },
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
        self.assertEqual(
            {"MODEL", "DECISION", "SKILL", "ORCHESTRATION", "TOOL", "PERMISSION", "USER_ACTION", "COMMAND"},
            categories,
        )
        self.assertFalse(any(item["category"] == "GRAPH" for item in timeline["items"]))
        self.assertEqual(1, sum(item["category"] == "ORCHESTRATION" for item in timeline["items"]))
        model_item = next(item for item in timeline["items"] if item["id"] == "model-invocation")
        self.assertEqual("SUCCEEDED", model_item["status"])
        self.assertEqual(20, model_item["details"]["totalTokens"])
        self.assertEqual(6, model_item["details"]["cachedPromptTokens"])
        self.assertIn("模型完整公开回复", model_item["summary"])
        self.assertIn("secret=[已隐藏]", model_item["summary"])
        self.assertEqual("验证当前项目中的数据源连接", model_item["details"]["modelRequestObjective"])
        selection_item = next(item for item in timeline["items"] if item["id"] == "tool-selection-provenance")
        self.assertEqual("SYSTEM_RULE_FALLBACK", selection_item["details"]["toolSelectionSource"])
        self.assertIn("模型本轮没有提出原生工具调用", selection_item["summary"])
        decision_item = next(item for item in timeline["items"] if item["id"] == "structured-intent")
        self.assertNotIn("ruleConfidence", decision_item["details"])
        tool_item = next(item for item in timeline["items"] if item["category"] == "TOOL")
        self.assertEqual("WAITING_INPUT", tool_item["status"])
        self.assertEqual(("sourceDatasourceId",), tool_item["details"]["missingFields"])
        self.assertEqual("SYSTEM_RULE_FALLBACK", tool_item["details"]["planningSource"])
        self.assertTrue(all("sequence" not in item["details"] for item in timeline["items"]))
        serialized = str(timeline).lower()
        self.assertNotIn("should-not-leak", serialized)
        self.assertNotIn("select *", serialized)
        self.assertIn("chainofthought", serialized)
        self.assertEqual(
            "PUBLIC_DECISION_SUMMARIES_AND_LOW_SENSITIVE_EXECUTION_FACTS",
            timeline["payloadPolicy"],
        )

        cached_plan = replace(
            plan,
            model_invocation_summary={
                **plan.model_invocation_summary,
                "providerInvoked": False,
                "providerSucceeded": False,
                "responseAvailable": True,
                "responseSource": "DATASMART_RESULT_CACHE",
                "cacheHit": True,
                "latencyMs": 0,
                "providerLatencyMs": 321,
            },
        )
        cached_timeline = build_agent_observation_timeline(
            cached_plan,
            conversation={},
            control_plane_handoff={},
            control_plane_ingestion={},
        )
        cached_model_item = next(item for item in cached_timeline["items"] if item["id"] == "model-invocation")
        self.assertEqual("CACHED", cached_model_item["status"])
        self.assertEqual("DATASMART_RESULT_CACHE", cached_model_item["details"]["responseSource"])
        self.assertEqual(0, cached_model_item["details"]["latencyMs"])
        self.assertEqual(321, cached_model_item["details"]["providerLatencyMs"])


if __name__ == "__main__":
    unittest.main()
