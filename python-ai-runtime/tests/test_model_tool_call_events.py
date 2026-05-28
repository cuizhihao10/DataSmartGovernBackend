import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.config import default_tool_registry
from datasmart_ai_runtime.domain.contracts import AgentRequest, ModelToolCall
from datasmart_ai_runtime.domain.events import AgentRuntimeEventSeverity, AgentRuntimeEventType
from datasmart_ai_runtime.services.model_tool_call_events import record_model_tool_call_planning_events
from datasmart_ai_runtime.services.model_tool_call_planner import ModelToolCallPlanner
from datasmart_ai_runtime.services.runtime_event_recorder import RuntimeEventRecorder


class ModelToolCallEventsTest(unittest.TestCase):
    def test_planning_report_records_proposed_accepted_approval_and_rejected_events(self) -> None:
        """模型工具调用治理报告应转换为可审计的结构化事件。"""

        tools = default_tool_registry()
        report = ModelToolCallPlanner().plan(
            tool_calls=(
                ModelToolCall(
                    call_id="call_quality",
                    name="quality.rule.suggest",
                    arguments="{\"datasourceId\":\"ds-secret\",\"businessGoal\":\"客户主数据完整性\"}",
                ),
                ModelToolCall(
                    call_id="call_task",
                    name="task.create.draft",
                    arguments="{\"taskType\":\"DATA_QUALITY_SCAN\",\"payload\":{\"datasourceId\":\"ds-secret\"}}",
                ),
                ModelToolCall(call_id="call_unknown", name="unknown.tool", arguments="{}"),
            ),
            registered_tools=tools,
            visible_tools=tuple(
                tool
                for tool in tools
                if tool.name in {"quality.rule.suggest", "task.create.draft"}
            ),
        )
        recorder = self._recorder()

        summary = record_model_tool_call_planning_events(recorder, report)
        events = summary.events

        self.assertEqual(3, summary.proposed_count)
        self.assertEqual(2, summary.accepted_count)
        self.assertEqual(1, summary.rejected_count)
        self.assertEqual(1, summary.approval_required_count)
        self.assertEqual(
            (
                AgentRuntimeEventType.MODEL_TOOL_CALL_PROPOSED,
                AgentRuntimeEventType.MODEL_TOOL_CALL_ACCEPTED,
                AgentRuntimeEventType.MODEL_TOOL_CALL_ACCEPTED,
                AgentRuntimeEventType.MODEL_TOOL_CALL_APPROVAL_REQUIRED,
                AgentRuntimeEventType.MODEL_TOOL_CALL_REJECTED,
            ),
            tuple(event.event_type for event in events),
        )
        self.assertEqual(AgentRuntimeEventSeverity.AUDIT, events[3].severity)
        self.assertEqual(AgentRuntimeEventSeverity.WARNING, events[4].severity)
        self.assertEqual("MODEL_TOOL_CALL_UNKNOWN_TOOL", events[4].attributes["issueCodes"][0])

    def test_event_attributes_do_not_expose_raw_argument_values(self) -> None:
        """事件只允许记录参数字段名，不能把模型生成的真实参数值写入实时事件流。"""

        tools = default_tool_registry()
        report = ModelToolCallPlanner().plan(
            tool_calls=(
                ModelToolCall(
                    call_id="call_quality",
                    name="quality.rule.suggest",
                    arguments="{\"datasourceId\":\"ds-sensitive-001\",\"businessGoal\":\"手机号唯一性\"}",
                ),
            ),
            registered_tools=tools,
            visible_tools=tuple(tool for tool in tools if tool.name == "quality.rule.suggest"),
        )
        summary = record_model_tool_call_planning_events(self._recorder(), report)
        accepted_event = summary.events[1]

        self.assertEqual(("datasourceId", "businessGoal"), accepted_event.attributes["argumentFieldNames"])
        self.assertNotIn("ds-sensitive-001", str(accepted_event.attributes))
        self.assertNotIn("手机号唯一性", str(accepted_event.attributes))

    @staticmethod
    def _recorder() -> RuntimeEventRecorder:
        """构造测试用 recorder，补齐租户、项目和操作者上下文。"""

        return RuntimeEventRecorder(
            request=AgentRequest(
                tenant_id="tenant-a",
                project_id="project-a",
                actor_id="actor-a",
                objective="生成质量规则并创建任务草案",
            ),
            request_id="request-model-tool-call-events",
            run_id="run-model-tool-call-events",
            session_id="session-model-tool-call-events",
        )


if __name__ == "__main__":
    unittest.main()
