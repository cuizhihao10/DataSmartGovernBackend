"""真实 MONITOR_AGENT 的学习型单元测试。

测试使用可记录的客户端和模型替身，不连接数据库、不启动 worker，也不执行
任何停止、重试、补数或重放动作。每个断言都围绕一个重要边界：事实必须来自
TaskMonitoringClient，模型只能总结，定期/CDC 的失败不能被误判为等待输入。
"""

from __future__ import annotations

import os
import sys
import unittest


ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.services.agent_gateway.session_models import AgentSessionRole
from datasmart_ai_runtime.services.multi_agent.specialist_contracts import (
    SpecialistDelegationScope,
    SpecialistTurnBudget,
    SpecialistTurnRequest,
    SpecialistTurnStatus,
)
from datasmart_ai_runtime.services.multi_agent.specialists.monitor_agent import (
    MONITOR_TOOL_CODE,
    MonitorSpecialistAgent,
    MonitoringModelInput,
)


class _RecordingMonitoringClient:
    """返回固定快照的只读客户端替身，并记录完整权限查询范围。"""

    def __init__(self, snapshot=None, error: Exception | None = None) -> None:
        self.snapshot = snapshot
        self.error = error
        self.queries = []

    def get_snapshot(self, query):
        """模拟一次确定性读取；测试可以据此确认没有重复或越权调用。"""

        self.queries.append(query)
        if self.error is not None:
            raise self.error
        return self.snapshot


class _RecordingSummaryModel:
    """只接收低敏 MonitoringModelInput 的模型替身。"""

    def __init__(self, output=None, error: Exception | None = None) -> None:
        self.output = output or {"publicSummary": "事实摘要", "invocationSummary": {"modelName": "test-model"}}
        self.error = error
        self.requests: list[MonitoringModelInput] = []

    def summarize(self, request: MonitoringModelInput):
        """记录模型输入，验证模型看不到原始 SQL、行数据或工具参数。"""

        self.requests.append(request)
        if self.error is not None:
            raise self.error
        return self.output


def _request(
    *,
    task_type: str = "FULL",
    task_id: str = "task-1",
    allowed_tools: tuple[str, ...] = (MONITOR_TOOL_CODE,),
    project_id: str | None = "project-1",
    tenant_id: str = "tenant-1",
    actor_id: str = "user-1",
    delegation_id: str = "delegation-1",
    context_extra: dict | None = None,
    budget: SpecialistTurnBudget | None = None,
) -> SpecialistTurnRequest:
    """构造每个测试共享的完整双主体委派请求。"""

    context = {"taskId": task_id, "taskType": task_type}
    context.update(context_extra or {})
    return SpecialistTurnRequest(
        turn_id="monitor-turn-1",
        session_id="session-1",
        run_id="run-1",
        role=AgentSessionRole.MONITOR_AGENT,
        objective="请只读监控任务运行状况",
        scope=SpecialistDelegationScope(
            tenant_id=tenant_id,
            application_id="datasmart-govern",
            project_id=project_id,
            actor_id=actor_id,
            delegation_id=delegation_id,
            allowed_tool_names=allowed_tools,
        ),
        budget=budget or SpecialistTurnBudget(max_tool_calls=1, max_model_invocations=1),
        context_summary=context,
    )


def _snapshot(status: str = "RUNNING", task_type: str = "FULL", **extra) -> dict:
    """构造只含聚合指标的基础快照，单个测试再覆盖需要的事实。"""

    result = {
        "taskId": "task-1",
        "taskType": task_type,
        "status": status,
        "phase": "COPY",
        "rowsTotal": 100,
        "rowsProcessed": 50,
        "successCount": 48,
        "failureCount": 2,
        "throughputRowsPerSecond": 20,
        "latencyMs": 80,
        "heartbeatAgeSeconds": 5,
        "heartbeatPresent": True,
        "checkpoint": {"checkpointId": "checkpoint-1", "offset": 42, "token": "do-not-expose"},
        "capturedAt": "2026-08-05T00:00:00Z",
    }
    result.update(extra)
    return result


class MonitorSpecialistAgentTest(unittest.TestCase):
    """验证 MONITOR_AGENT 的事实边界、生命周期语义和安全失败路径。"""

    def test_supports_six_lifecycle_states_without_model_owned_progress(self) -> None:
        """六种状态都能被观察；终止性和 50% 进度来自快照而非模型。"""

        expected_terminal = {
            "RUNNING": False,
            "QUEUED": False,
            "SCHEDULED": False,
            "SUCCEEDED": True,
            "FAILED": True,
            "CANCELLED": True,
        }
        for status, terminal in expected_terminal.items():
            with self.subTest(status=status):
                client = _RecordingMonitoringClient(_snapshot(status=status))
                model = _RecordingSummaryModel(
                    {
                        "publicSummary": "模型不能把进度改成 99%",
                        "status": "SUCCEEDED",
                        "progress": {"percent": 99},
                        "health": "HEALTHY",
                        "invocationSummary": {"modelName": "test-model"},
                    }
                )
                events: list[dict] = []

                result = MonitorSpecialistAgent(client, model).execute(_request(), events.append)

                self.assertEqual(SpecialistTurnStatus.COMPLETED, result.status)
                self.assertEqual(status, result.structured_output["status"])
                self.assertEqual(terminal, result.structured_output["terminal"])
                self.assertEqual(50.0, result.structured_output["progress"]["percent"])
                self.assertEqual(1, len(client.queries))
                self.assertEqual("tenant-1", client.queries[0].tenant_id)
                self.assertEqual("project-1", client.queries[0].project_id)
                self.assertEqual("user-1", client.queries[0].actor_id)
                self.assertEqual("delegation-1", client.queries[0].delegation_id)
                self.assertEqual(1, len(model.requests))
                self.assertEqual("tenant-1", model.requests[0].audit_scope.tenant_id)
                self.assertEqual("project-1", model.requests[0].audit_scope.project_id)
                self.assertEqual("user-1", model.requests[0].audit_scope.actor_id)
                self.assertEqual("session-1", model.requests[0].audit_scope.session_id)
                self.assertEqual("monitor-turn-1", model.requests[0].audit_scope.trace_id)
                self.assertEqual(status, model.requests[0].facts["status"])
                self.assertEqual("LONG_RUNNING", model.requests[0].facts["taskKind"])
                self.assertTrue(result.structured_output["readOnly"])
                self.assertFalse(result.structured_output["sideEffectsPerformed"])
                self.assertNotIn("99", str(result.structured_output["progress"]))
                self.assertIn("SPECIALIST_TOOL_COMPLETED", {event["action"] for event in events})

    def test_periodic_task_exposes_recent_and_next_run_without_waiting_input(self) -> None:
        """定期任务最近一次失败不等于整个调度生命周期结束或等待用户补参。"""

        client = _RecordingMonitoringClient(
            _snapshot(
                status="SCHEDULED",
                task_type="PERIODIC",
                schedule={
                    "lastRunStatus": "FAILED",
                    "lastRunAt": "2026-08-05T01:00:00Z",
                    "nextRunAt": "2026-08-05T02:00:00Z",
                    "enabled": True,
                },
                exception={"code": "SOURCE_TIMEOUT", "message": "safe summary"},
            )
        )
        result = MonitorSpecialistAgent(client).execute(
            _request(task_type="SCHEDULED_BATCH"),
        )

        output = result.structured_output
        self.assertEqual(SpecialistTurnStatus.COMPLETED, result.status)
        self.assertEqual("PERIODIC", output["taskType"])
        self.assertEqual("SCHEDULED", output["status"])
        self.assertFalse(output["terminal"])
        self.assertEqual("DEGRADED", output["health"])
        self.assertEqual("FAILED", output["recentRun"]["status"])
        self.assertEqual("2026-08-05T02:00:00Z", output["nextRun"]["scheduledAt"])
        self.assertEqual(60, output["nextPollAfterSeconds"])
        self.assertIn("RECENT_RUN_FAILED", {item["code"] for item in output["anomalies"]})
        self.assertEqual((), result.required_input_fields)

        failed_run_client = _RecordingMonitoringClient(
            _snapshot(status="FAILED", task_type="PERIODIC", exception={"code": "RUN_FAILED"})
        )
        failed_run = MonitorSpecialistAgent(failed_run_client).execute(_request(task_type="PERIODIC"))
        self.assertEqual(SpecialistTurnStatus.COMPLETED, failed_run.status)
        self.assertFalse(failed_run.structured_output["terminal"])
        self.assertEqual("DEGRADED", failed_run.structured_output["health"])

    def test_cdc_failure_is_long_term_degraded_health_and_keeps_polling(self) -> None:
        """CDC 失败、lag 和心跳异常应表达长期健康度，而不是未完成输入。"""

        client = _RecordingMonitoringClient(
            _snapshot(
                status="FAILED",
                task_type="CDC_STREAMING",
                heartbeatAgeSeconds=180,
                cdcLagSeconds=240,
                checkpoint={"checkpointId": "cdc-checkpoint-7", "offset": 900},
            )
        )
        result = MonitorSpecialistAgent(client).execute(_request(task_type="CDC_STREAMING"))

        output = result.structured_output
        codes = {item["code"] for item in output["anomalies"]}
        self.assertEqual(SpecialistTurnStatus.COMPLETED, result.status)
        self.assertEqual("CDC_REALTIME", output["taskType"])
        self.assertEqual("FAILED", output["status"])
        self.assertFalse(output["terminal"])
        self.assertEqual("DEGRADED", output["health"])
        self.assertEqual("DEGRADED", output["longTermHealth"]["health"])
        self.assertEqual(240.0, output["longTermHealth"]["cdcLagSeconds"])
        self.assertEqual(15, output["nextPollAfterSeconds"])
        self.assertTrue({"HEARTBEAT_LOST", "CDC_LAG_HIGH", "TASK_FAILED"}.issubset(codes))
        self.assertEqual((), result.required_input_fields)

    def test_structured_thresholds_detect_queue_heartbeat_throughput_and_failure_rate(self) -> None:
        """结构化阈值驱动告警，边界值不会交给模型自由发挥。"""

        client = _RecordingMonitoringClient(
            _snapshot(
                status="QUEUED",
                queueWaitSeconds=11,
                heartbeatAgeSeconds=11,
                throughputRowsPerSecond=10,
                baselineThroughputRowsPerSecond=100,
                successCount=80,
                failureCount=20,
            )
        )
        thresholds = {
            "queueTimeoutSeconds": 10,
            "heartbeatTimeoutSeconds": 10,
            "throughputDropRatio": 0.5,
            "failureRateThreshold": 0.1,
            "cdcLagThresholdSeconds": 50,
            "scheduleMissGraceSeconds": 0,
        }
        result = MonitorSpecialistAgent(client).execute(
            _request(context_extra={"thresholds": thresholds}),
        )

        codes = {item["code"] for item in result.structured_output["anomalies"]}
        self.assertTrue(
            {"QUEUE_TIMEOUT", "HEARTBEAT_LOST", "THROUGHPUT_DROP", "FAILURE_RATE_HIGH"}.issubset(codes)
        )
        self.assertEqual("DEGRADED", result.structured_output["health"])
        self.assertEqual(15, result.structured_output["nextPollAfterSeconds"])

        schedule_client = _RecordingMonitoringClient(
            _snapshot(
                status="SCHEDULED",
                task_type="PERIODIC",
                schedule={"missed": True, "nextRunAt": "2026-08-05T03:00:00Z"},
            )
        )
        schedule_result = MonitorSpecialistAgent(schedule_client).execute(
            _request(task_type="PERIODIC"),
        )
        self.assertIn(
            "SCHEDULE_MISSED",
            {item["code"] for item in schedule_result.structured_output["anomalies"]},
        )

        time_based_client = _RecordingMonitoringClient(
            _snapshot(
                status="SCHEDULED",
                task_type="PERIODIC",
                nextRunAt="2026-08-05T00:00:00Z",
                capturedAt="2026-08-05T00:02:00Z",
            )
        )
        time_based_result = MonitorSpecialistAgent(time_based_client).execute(
            _request(
                task_type="PERIODIC",
                context_extra={"thresholds": {"scheduleMissGraceSeconds": 60}},
            )
        )
        self.assertIn(
            "SCHEDULE_MISSED",
            {item["code"] for item in time_based_result.structured_output["anomalies"]},
        )

    def test_scope_tool_and_budget_fail_closed_before_any_dependency_call(self) -> None:
        """权限主体、工具白名单或工具预算不完整时不触发客户端和模型。"""

        cases = (
            (
                "missing-project",
                _request(project_id=None),
                "MONITOR_SCOPE_INVALID",
            ),
            (
                "missing-tool",
                _request(allowed_tools=("task.read",)),
                "MONITOR_TOOL_NOT_AUTHORIZED",
            ),
            (
                "zero-tool-budget",
                _request(budget=SpecialistTurnBudget(max_tool_calls=0)),
                "MONITOR_TOOL_BUDGET_EXHAUSTED",
            ),
            (
                "invalid-threshold",
                _request(context_extra={"thresholds": {"failureRateThreshold": 2}}),
                "MONITOR_THRESHOLDS_INVALID",
            ),
        )
        for name, request, expected_code in cases:
            with self.subTest(name=name):
                client = _RecordingMonitoringClient(_snapshot())
                model = _RecordingSummaryModel()
                result = MonitorSpecialistAgent(client, model).execute(request)

                self.assertEqual(SpecialistTurnStatus.FAILED, result.status)
                self.assertEqual(expected_code, result.error_code)
                self.assertEqual([], client.queries)
                self.assertEqual([], model.requests)
                self.assertEqual("UNKNOWN", result.structured_output["health"])
                if expected_code in {"MONITOR_TOOL_NOT_AUTHORIZED", "MONITOR_TOOL_BUDGET_EXHAUSTED"}:
                    self.assertEqual("DENIED", result.tool_activities[0].status)

    def test_scope_mismatch_is_rejected_even_when_tool_is_authorized(self) -> None:
        """上下文声明的项目若与 delegation 不同，不能让客户端自行扩大范围。"""

        client = _RecordingMonitoringClient(_snapshot())
        request = _request(context_extra={"projectId": "other-project"})
        result = MonitorSpecialistAgent(client).execute(request)

        self.assertEqual(SpecialistTurnStatus.FAILED, result.status)
        self.assertEqual("MONITOR_SCOPE_MISMATCH", result.error_code)
        self.assertEqual([], client.queries)

    def test_client_failure_stops_but_model_failure_uses_low_sensitive_deterministic_fallback(self) -> None:
        """事实源失败必须停止；可选总结模型失败不能抹掉已经验证的监控快照。

        两类外部异常都只能暴露稳定错误码，不能把凭据、SQL 或堆栈传到结果/事件。区别在于监控
        client 是权威事实源，失败后没有可返回状态；模型只负责语言总结，失败时应使用确定性模板并
        将 providerSucceeded=false 留在低敏调用元数据中。
        """

        secret_error = "password=top-secret SELECT value FROM private_table"
        client = _RecordingMonitoringClient(error=RuntimeError(secret_error))
        client_events: list[dict] = []
        client_result = MonitorSpecialistAgent(client).execute(_request(), client_events.append)
        self.assertEqual(SpecialistTurnStatus.FAILED, client_result.status)
        self.assertEqual("MONITOR_TASK_CLIENT_FAILED", client_result.error_code)
        self.assertNotIn("top-secret", str(client_result.to_summary()))
        self.assertNotIn("private_table", str(client_events))

        model = _RecordingSummaryModel(error=RuntimeError(secret_error))
        model_client = _RecordingMonitoringClient(_snapshot())
        model_events: list[dict] = []
        model_result = MonitorSpecialistAgent(model_client, model).execute(_request(), model_events.append)
        self.assertEqual(SpecialistTurnStatus.COMPLETED, model_result.status)
        self.assertIsNone(model_result.error_code)
        self.assertEqual("RUNNING", model_result.structured_output["status"])
        self.assertEqual(
            "deterministic_fallback",
            model_result.model_invocation_summary["responseSource"],
        )
        self.assertFalse(model_result.model_invocation_summary["providerSucceeded"])
        self.assertEqual(
            "MONITOR_SUMMARY_MODEL_FAILED",
            model_result.model_invocation_summary["errorCode"],
        )
        self.assertEqual(1, len(model_client.queries))
        self.assertEqual(1, len(model.requests))
        self.assertNotIn("top-secret", str(model_result.to_summary()))
        self.assertNotIn("private_table", str(model_events))

    def test_model_cannot_override_facts_and_outputs_are_low_sensitive(self) -> None:
        """模型的状态性字段、隐藏思维链和敏感文字都不会进入权威结果。"""

        model = _RecordingSummaryModel(
            {
                "publicSummary": "SELECT secret FROM private_table，任务 99% 完成",
                "status": "SUCCEEDED",
                "progress": {"percent": 99, "rows": [{"customer": "Alice"}]},
                "chainOfThought": "hidden reasoning",
                "recommendedActions": ["建议由控制面评估是否重试"],
                "invocationSummary": {
                    "modelName": "test-model",
                    "prompt": "password=top-secret",
                    "chainOfThought": "must not persist",
                },
            }
        )
        client = _RecordingMonitoringClient(
            _snapshot(
                rowsProcessed=20,
                checkpoint={"checkpointId": "cp-safe", "rows": [{"customer": "Alice"}]},
                exception={"code": "E1", "message": "password=top-secret SELECT x FROM private_table"},
            )
        )
        result = MonitorSpecialistAgent(client, model).execute(_request())
        output_text = str(result.to_summary())

        self.assertEqual("RUNNING", result.structured_output["status"])
        self.assertEqual(20.0, result.structured_output["progress"]["percent"])
        self.assertNotIn("99", str(result.structured_output["progress"]))
        self.assertNotIn("chainOfThought", output_text)
        self.assertNotIn("top-secret", output_text)
        self.assertNotIn("private_table", output_text)
        self.assertNotIn("Alice", output_text)
        self.assertNotIn("prompt", str(result.model_invocation_summary))
        self.assertTrue(result.structured_output["sideEffectsPerformed"] is False)

    def test_default_summary_model_is_not_reported_as_provider_invocation(self) -> None:
        """没有 LLM Provider 时仍能返回事实，但调用摘要必须如实标记替身。"""

        result = MonitorSpecialistAgent(_RecordingMonitoringClient(_snapshot())).execute(_request())

        self.assertFalse(result.model_invocation_summary["invoked"])
        self.assertEqual(0, result.model_invocation_summary["invocationCount"])
        self.assertEqual("deterministic_fallback", result.model_invocation_summary["responseSource"])
        self.assertFalse(result.model_invocation_summary["rawModelOutputStored"])


if __name__ == "__main__":
    unittest.main()
