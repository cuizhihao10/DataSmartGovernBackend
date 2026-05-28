import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.domain.contracts import AgentRequest
from datasmart_ai_runtime.domain.event_transport import RuntimeEventSubscriptionRequest
from datasmart_ai_runtime.domain.events import AgentRuntimeEventType
from datasmart_ai_runtime.services.runtime_event_live_push import RuntimeEventLivePushHub
from datasmart_ai_runtime.services.runtime_event_outbox_store import InMemoryRuntimeEventOutboxStore
from datasmart_ai_runtime.services.runtime_event_recorder import RuntimeEventRecorder
from datasmart_ai_runtime.services.runtime_event_session import RuntimeEventSessionManager


class RuntimeEventLivePushHubTest(unittest.TestCase):
    def test_publish_queues_live_frame_for_matching_subscription(self) -> None:
        manager = RuntimeEventSessionManager()
        hub = RuntimeEventLivePushHub(session_manager=manager)
        snapshot = manager.subscribe(
            RuntimeEventSubscriptionRequest(
                client_id="browser-a",
                tenant_id="tenant-a",
                project_id="project-a",
                actor_id="user-a",
                roles=("PROJECT_OWNER",),
                session_id="session-live",
                after_sequence=1,
            )
        )

        recorder = self._recorder()
        queued = hub.publish(recorder.events())
        payloads = hub.drain_payloads(snapshot.plan.subscription_id)

        self.assertEqual(1, queued)
        self.assertEqual(1, len(payloads))
        self.assertEqual("event_envelope", payloads[0]["frameType"])
        self.assertEqual((2, 3), tuple(event["sequence"] for event in payloads[0]["payload"]["events"]))
        self.assertTrue(payloads[0]["payload"]["attributes"]["visibilityPolicyApplied"])

    def test_publish_masks_sensitive_live_event_attributes_before_outbox(self) -> None:
        """live frame 写入 outbox 前就必须完成脱敏。

        生产部署时 outbox 可能是 Redis，且 drain frame 的实例不一定是 publish 事件的实例。
        如果脱敏放在 drain 阶段，就会让未经处理的敏感内容进入共享 outbox，形成横向泄露风险。
        """

        manager = RuntimeEventSessionManager()
        hub = RuntimeEventLivePushHub(session_manager=manager)
        snapshot = manager.subscribe(
            RuntimeEventSubscriptionRequest(
                client_id="browser-a",
                tenant_id="tenant-a",
                project_id="project-a",
                actor_id="user-a",
                roles=("PROJECT_OWNER",),
                session_id="session-live",
            )
        )
        recorder = RuntimeEventRecorder(
            request=AgentRequest(
                tenant_id="tenant-a",
                project_id="project-a",
                actor_id="user-a",
                objective="测试 live push 脱敏",
            ),
            request_id="request-live",
            run_id="run-live",
            session_id="session-live",
        )
        recorder.record(
            AgentRuntimeEventType.TOOL_PLANNED,
            "plan_tools",
            "已规划带敏感参数的工具。",
            attributes={
                "sql": "select * from sensitive_table",
                "safeCounter": 1,
                "nested": {"apiKey": "ak-secret"},
            },
        )

        queued = hub.publish(recorder.events())
        payloads = hub.drain_payloads(snapshot.plan.subscription_id)

        self.assertEqual(1, queued)
        attributes = payloads[0]["payload"]["events"][0]["attributes"]
        self.assertEqual("***MASKED***", attributes["sql"])
        self.assertEqual("***MASKED***", attributes["nested"]["apiKey"])
        self.assertEqual(1, attributes["safeCounter"])
        self.assertEqual("PROJECT", attributes["_datasmartVisibilityLevel"])

    def test_publish_ignores_non_matching_subscription_scope(self) -> None:
        manager = RuntimeEventSessionManager()
        hub = RuntimeEventLivePushHub(session_manager=manager)
        snapshot = manager.subscribe(
            RuntimeEventSubscriptionRequest(
                client_id="browser-a",
                tenant_id="tenant-a",
                project_id="project-a",
                actor_id="user-a",
                session_id="other-session",
            )
        )

        recorder = self._recorder()
        queued = hub.publish(recorder.events())
        payloads = hub.drain_payloads(snapshot.plan.subscription_id)

        self.assertEqual(0, queued)
        self.assertEqual((), payloads)

    def test_publish_can_be_drained_by_another_hub_with_same_outbox_store(self) -> None:
        """同一个 outbox store 可被不同 hub 实例共享。

        这个测试模拟多实例生产场景：A 实例负责接收事件并入队，B 实例负责承载用户 WebSocket 连接并
        drain。当前用内存 store 模拟共享对象，真实部署时应替换成 Redis outbox store。
        """

        outbox_store = InMemoryRuntimeEventOutboxStore()
        manager = RuntimeEventSessionManager()
        publishing_hub = RuntimeEventLivePushHub(session_manager=manager, outbox_store=outbox_store)
        draining_hub = RuntimeEventLivePushHub(session_manager=manager, outbox_store=outbox_store)
        snapshot = manager.subscribe(
            RuntimeEventSubscriptionRequest(
                client_id="browser-a",
                tenant_id="tenant-a",
                project_id="project-a",
                actor_id="user-a",
                session_id="session-live",
            )
        )

        queued = publishing_hub.publish(self._recorder().events())
        payloads = draining_hub.drain_payloads(snapshot.plan.subscription_id)

        self.assertEqual(1, queued)
        self.assertEqual(1, len(payloads))
        self.assertEqual((1, 2, 3), tuple(event["sequence"] for event in payloads[0]["payload"]["events"]))

    @staticmethod
    def _recorder() -> RuntimeEventRecorder:
        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="user-a",
            objective="测试 live push",
        )
        recorder = RuntimeEventRecorder(
            request=request,
            request_id="request-live",
            run_id="run-live",
            session_id="session-live",
        )
        recorder.record(AgentRuntimeEventType.CONTEXT_COLLECTED, "build_context", "已收集上下文。")
        recorder.record(AgentRuntimeEventType.INTENT_ANALYZED, "analyze_intent", "已分析意图。")
        recorder.record(AgentRuntimeEventType.TOOL_PLANNED, "plan_tools", "已规划工具。")
        return recorder


if __name__ == "__main__":
    unittest.main()
