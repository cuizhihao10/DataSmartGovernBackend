import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.domain.event_transport import RuntimeEventSubscriptionRequest
from datasmart_ai_runtime.domain.events import (
    AgentRuntimeEvent,
    AgentRuntimeEventSeverity,
    AgentRuntimeEventType,
)
from datasmart_ai_runtime.services.runtime_event_visibility import (
    RuntimeEventVisibilityLevel,
    RuntimeEventVisibilityPolicy,
    RuntimeEventVisibilityStats,
)


class RuntimeEventVisibilityPolicyTest(unittest.TestCase):
    def test_project_owner_masks_sensitive_attributes_but_keeps_safe_progress_event(self) -> None:
        """项目负责人可以看项目进度事件，但敏感工具参数必须脱敏。

        这个测试保护的是 WebSocket/replay 通道的内容级安全边界：订阅已经通过授权，不代表事件里的
        SQL、Token、原始输入输出都可以直接下发给前端。
        """

        policy = RuntimeEventVisibilityPolicy()
        request = RuntimeEventSubscriptionRequest(
            client_id="browser-a",
            roles=("PROJECT_OWNER",),
            session_id="session-a",
        )
        event = AgentRuntimeEvent(
            event_type=AgentRuntimeEventType.TOOL_PLANNED,
            stage="plan_tools",
            message="已规划 SQL 工具。",
            severity=AgentRuntimeEventSeverity.AUDIT,
            session_id="session-a",
            sequence=1,
            attributes={
                "sql": "select * from customer_sensitive_table",
                "apiKey": "ak-live-secret",
                "safeCounter": 3,
                "nested": {"authorization": "Bearer secret-token", "visible": "ok"},
            },
        )

        visible = policy.filter_and_mask((event,), request)

        self.assertEqual(1, len(visible))
        attributes = visible[0].attributes
        self.assertEqual(RuntimeEventVisibilityPolicy.MASKED_VALUE, attributes["sql"])
        self.assertEqual(RuntimeEventVisibilityPolicy.MASKED_VALUE, attributes["apiKey"])
        self.assertEqual(3, attributes["safeCounter"])
        self.assertEqual(RuntimeEventVisibilityPolicy.MASKED_VALUE, attributes["nested"]["authorization"])
        self.assertEqual(RuntimeEventVisibilityLevel.PROJECT.value, attributes["_datasmartVisibilityLevel"])
        self.assertIn("nested.authorization", attributes["_datasmartMaskedFields"])

    def test_basic_user_only_sees_progress_like_events(self) -> None:
        """普通用户采用白名单策略，只接收业务进度类事件。"""

        policy = RuntimeEventVisibilityPolicy()
        request = RuntimeEventSubscriptionRequest(client_id="browser-a", roles=("ORDINARY_USER",))
        memory_event = AgentRuntimeEvent(
            event_type=AgentRuntimeEventType.MEMORY_RETRIEVED,
            stage="retrieve_memory",
            message="已读取长期记忆。",
            sequence=1,
            attributes={"memory": "sensitive long-term memory"},
        )
        progress_event = AgentRuntimeEvent(
            event_type=AgentRuntimeEventType.TOOL_PLANNED,
            stage="plan_tools",
            message="已规划工具。",
            sequence=2,
            attributes={"safeCounter": 1},
        )

        visible = policy.filter_and_mask((memory_event, progress_event), request)

        self.assertEqual((2,), tuple(event.sequence for event in visible))
        self.assertEqual("事件详情已按当前角色权限脱敏", visible[0].message)
        self.assertEqual(RuntimeEventVisibilityPolicy.MASKED_VALUE, visible[0].attributes["safeCounter"])
        self.assertEqual(RuntimeEventVisibilityLevel.BASIC.value, visible[0].attributes["_datasmartVisibilityLevel"])

    def test_missing_roles_falls_back_to_basic_visibility(self) -> None:
        """缺少角色上下文时不能默认完整可见，应按 BASIC 兜底。"""

        policy = RuntimeEventVisibilityPolicy()
        request = RuntimeEventSubscriptionRequest(client_id="anonymous-client")

        self.assertEqual(RuntimeEventVisibilityLevel.BASIC, policy.resolve_level(request))

    def test_visibility_stats_records_filtered_and_masked_events(self) -> None:
        """策略统计应能反映过滤、脱敏和角色级别命中情况。"""

        stats = RuntimeEventVisibilityStats()
        policy = RuntimeEventVisibilityPolicy(stats)
        request = RuntimeEventSubscriptionRequest(client_id="browser-a", roles=("PROJECT_OWNER",))
        hidden = AgentRuntimeEvent(
            event_type=AgentRuntimeEventType.MEMORY_RETRIEVED,
            stage="retrieve_memory",
            message="已读取长期记忆。",
            sequence=1,
            attributes={"memory": "sensitive"},
        )
        visible = AgentRuntimeEvent(
            event_type=AgentRuntimeEventType.TOOL_PLANNED,
            stage="plan_tools",
            message="已规划工具。",
            sequence=2,
            attributes={"sql": "select * from sensitive_table"},
        )

        policy.filter_and_mask((hidden, visible), request)
        snapshot = stats.snapshot()

        self.assertEqual(1, snapshot["policyEvaluationCount"])
        self.assertEqual(2, snapshot["evaluatedEventCount"])
        self.assertEqual(1, snapshot["visibleEventCount"])
        self.assertEqual(1, snapshot["filteredEventCount"])
        self.assertEqual(1, snapshot["maskedEventCount"])
        self.assertEqual(1, snapshot["maskedFieldCount"])
        self.assertEqual(1, snapshot["levelHitCounts"]["PROJECT"])


if __name__ == "__main__":
    unittest.main()
