import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.domain.contracts import (
    AgentPlan,
    AgentRequest,
)
from datasmart_ai_runtime.domain.events import (
    AgentRuntimeEvent,
    AgentRuntimeEventSeverity,
    AgentRuntimeEventType,
)
from datasmart_ai_runtime.services.tools import (
    LangGraphExecutionGateMetrics,
    build_langgraph_execution_gate_runtime_event,
)


class LangGraphExecutionGateMetricsTest(unittest.TestCase):
    """LangGraph execution gate 指标测试。

    这组测试保护的是生产可观测边界：execution gate 可以被 Prometheus 观察，但指标只能回答聚合问题，
    不能携带单个租户、请求、checkpoint、工具参数、SQL、prompt 或模型输出等明细。
    """

    def test_runtime_event_updates_low_cardinality_prometheus_metrics(self) -> None:
        """`agent_execution_gate_recorded` 事件应转成 route/status/readiness/fact 聚合指标。"""

        metrics = LangGraphExecutionGateMetrics()
        event = build_langgraph_execution_gate_runtime_event(
            self._plan(),
            self._request(),
            {
                "engine": "langgraph",
                "status": "LANGGRAPH_EXECUTION_GATE_EVALUATED",
                "gateRoute": "RESUME_PREFLIGHT",
                "gateStatus": "READY_FOR_JAVA_CONTROL_PLANE_PREFLIGHT",
                "compiled": True,
                "executed": True,
                "fallbackUsed": False,
                "readinessCounts": {
                    "totalCount": 1,
                    "executableCount": 1,
                    "approvalRequiredCount": 0,
                    "clarificationRequiredCount": 0,
                    "draftOnlyCount": 0,
                    "queuedAsyncCount": 0,
                    "throttledCount": 0,
                    "blockedCount": 0,
                },
                "resumeGate": {
                    "requiredFactTypes": ("OUTBOX_WRITE_CONFIRMATION", "WORKER_RECEIPT_PROJECTION"),
                },
                "sideEffectBoundary": {
                    "toolExecuted": False,
                    "outboxWritten": False,
                    "approvalCreated": False,
                    "checkpointMutated": False,
                    "workerDispatched": False,
                    "javaControlPlaneRequiredForSideEffects": True,
                    "workerReceiptRequiredForSideEffects": True,
                },
            },
        )

        recorded = metrics.record_runtime_event(event)
        text = metrics.render_prometheus()

        self.assertTrue(recorded)
        self.assertIn(
            'datasmart_ai_langgraph_execution_gate_events_total{fallback="false",gate_route="resume_preflight",'
            'gate_status="ready_for_java_control_plane_preflight",result="evaluated",severity="info"} 1',
            text,
        )
        self.assertIn(
            'datasmart_ai_langgraph_execution_gate_readiness_items_total{decision="total",gate_route="resume_preflight"} 1',
            text,
        )
        self.assertIn(
            'datasmart_ai_langgraph_execution_gate_readiness_items_total{decision="executable",gate_route="resume_preflight"} 1',
            text,
        )
        self.assertIn(
            'datasmart_ai_langgraph_execution_gate_resume_facts_total{fact_state="required",gate_route="resume_preflight"} 2',
            text,
        )
        self.assertNotIn("tenant-secret", text)
        self.assertNotIn("project-secret", text)
        self.assertNotIn("actor-secret", text)
        self.assertNotIn("checkpoint-secret", text)
        self.assertNotIn("command-secret", text)
        self.assertNotIn("select * from hidden_table", text)
        self.assertNotIn("raw prompt", text)

    def test_unknown_runtime_event_is_ignored(self) -> None:
        """指标器挂到统一 event pipeline 后，应安全忽略非 execution gate 事件。"""

        metrics = LangGraphExecutionGateMetrics()
        event = AgentRuntimeEvent(
            event_type=AgentRuntimeEventType.TOOL_PLANNED,
            stage="plan_tools",
            message="普通工具规划事件",
            severity=AgentRuntimeEventSeverity.INFO,
            attributes={"toolName": "datasource.metadata.read"},
        )

        recorded = metrics.record_runtime_event(event)
        snapshot = metrics.snapshot()
        text = metrics.render_prometheus()

        self.assertFalse(recorded)
        self.assertEqual(0, snapshot["metricCount"])
        self.assertIn("# HELP datasmart_ai_langgraph_execution_gate_events_total", text)
        self.assertNotIn("datasource.metadata.read", text)

    def test_fallback_status_is_bounded_to_safe_result_label(self) -> None:
        """依赖缺失或执行失败这类 fallback 只能变成固定 result 标签，不能制造动态 label。"""

        metrics = LangGraphExecutionGateMetrics()

        metrics.record_summary(
            {
                "status": "DEPENDENCY_MISSING",
                "gateRoute": "INVENTED_ROUTE_WITH_secret_value",
                "gateStatus": "CUSTOM_STATUS_WITH_checkpoint-secret",
                "fallbackUsed": True,
                "readinessCounts": {"totalCount": 3, "blockedCount": 2},
                "resumeRequiredFactTypes": ("APPROVAL_CONFIRMATION_FACT",),
            }
        )
        text = metrics.render_prometheus()

        self.assertIn('result="dependency_missing"', text)
        self.assertIn('fallback="true"', text)
        self.assertIn('gate_route="other"', text)
        self.assertIn('gate_status="other"', text)
        self.assertNotIn("secret_value", text)
        self.assertNotIn("checkpoint-secret", text)

    @staticmethod
    def _request() -> AgentRequest:
        """构造带敏感上下文字段的请求，验证这些字段不会进入指标文本。"""

        return AgentRequest(
            tenant_id="tenant-secret",
            project_id="project-secret",
            actor_id="actor-secret",
            objective="raw prompt should not leak",
            variables={
                "checkpointId": "checkpoint-secret",
                "commandId": "command-secret",
                "sql": "select * from hidden_table",
            },
        )

    @staticmethod
    def _plan() -> AgentPlan:
        """构造最小 AgentPlan，供 execution gate event builder 填充 request/run/session 语义。"""

        return AgentPlan(
            request_id="request-secret",
            selected_route=None,
            state_trace=(),
            tool_plans=(),
            requires_human_approval=False,
            response_summary="unit-test",
        )


if __name__ == "__main__":
    unittest.main()
