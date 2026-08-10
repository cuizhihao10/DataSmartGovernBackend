from __future__ import annotations

import unittest

from datasmart_ai_runtime.domain.contracts import AgentPlan, AgentRequest
from datasmart_ai_runtime.services.multi_agent.specialist_events import build_specialist_runtime_events


class SpecialistRuntimeEventsTest(unittest.TestCase):
    """验证专业 Agent 动作进入统一事件总线时的字段白名单。"""

    def test_converts_low_sensitive_action_and_drops_nested_payload(self) -> None:
        """验证事件转换器只输出明确允许的低敏摘要，而不是“所有标量都输出”。

        SQL、Prompt、原始模型输出、工具参数和凭据即使都是字符串，也不能进入事件事实；否则
        管理员视图、Kafka 审计或未来的 replay source 仍可能绕过前端脱敏看到敏感内容。与此同时，
        模型名称和引用数量这类前端确实需要的治理摘要应继续保留。
        """

        request = AgentRequest("1", "101", "user-1", "查询故障案例", request_id="request-1")
        plan = AgentPlan(
            request_id="request-1",
            selected_route=None,
            state_trace=(),
            tool_plans=(),
            requires_human_approval=False,
            response_summary="测试计划",
        )

        events = build_specialist_runtime_events(
            request=request,
            plan=plan,
            action_events=(
                {
                    "eventType": "SPECIALIST_ACTION",
                    "agentId": "knowledge-1",
                    "agentRole": "KNOWLEDGE_AGENT",
                    "turnId": "turn-1",
                    "runId": "run-1",
                    "action": "knowledge.retrieval.completed",
                    "status": "COMPLETED",
                    "publicSummary": "已检索到 2 条可引用案例。",
                    "statistics": {
                        "citationCount": 2,
                        "sql": "SELECT SECRET",
                        "rawModelOutput": "forbidden-model-output",
                        "secret": {"body": "forbidden"},
                    },
                    "attributes": {
                        "modelName": "model-a",
                        "latencyMs": 18,
                        "prompt": {"body": "forbidden-prompt"},
                        "toolArgs": {"datasourceId": "forbidden"},
                        "credential": "forbidden-credential",
                        "unknownScalar": "must-be-dropped",
                    },
                },
            ),
        )

        self.assertEqual(1, len(events))
        self.assertEqual("specialist_agent_action_recorded", events[0].event_type.value)
        self.assertEqual({"citationCount": 2}, events[0].attributes["statistics"])
        self.assertEqual(
            {"modelName": "model-a", "latencyMs": 18},
            events[0].attributes["actionAttributes"],
        )
        self.assertNotIn("SELECT SECRET", str(events[0].attributes))
        self.assertNotIn("forbidden-model-output", str(events[0].attributes))
        self.assertNotIn("forbidden-prompt", str(events[0].attributes))
        self.assertNotIn("forbidden-credential", str(events[0].attributes))
        self.assertNotIn("must-be-dropped", str(events[0].attributes))


if __name__ == "__main__":
    unittest.main()
