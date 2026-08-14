"""data-sync Specialist HTTP 适配器的定向契约测试。

测试替身只模拟 Java ``PlatformApiResponse``，不启动网络或数据库。每个测试都关注
一个边界：真实路径和 Header、DTO 映射、范围校验、401/403、超时以及错误正文脱敏。
"""

from __future__ import annotations

import json
import os
import sys
import unittest
from urllib.parse import parse_qs, urlsplit


ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.services.multi_agent.specialist_control_plane_adapters import (
    ControlPlaneHttpClientSettings,
    HttpFailureDiagnosticClient,
    HttpPrecheckControlPlaneClient,
    HttpTaskMonitoringClient,
    SpecialistControlPlaneAdapterError,
)
from datasmart_ai_runtime.services.multi_agent.specialists.monitor_agent import (
    TaskKind,
    TaskMonitoringQuery,
)
from datasmart_ai_runtime.services.multi_agent.specialists.precheck_agent import (
    PrecheckControlPlaneRequest,
)
from datasmart_ai_runtime.services.multi_agent.specialists.recovery_agent import (
    FailureDiagnosticRequest,
)


class _FakeResponse:
    """最小 urllib response 替身，并记录错误响应是否被读取。"""

    def __init__(self, payload: object, *, status: int = 200, raw: bool = False) -> None:
        """创建 JSON 或原始正文响应。"""

        self.status = status
        self.read_count = 0
        self._body = payload if raw else json.dumps(payload, ensure_ascii=False).encode("utf-8")

    def __enter__(self):
        """支持生产客户端使用的 response context manager 协议。"""

        return self

    def __exit__(self, exc_type, exc, traceback) -> bool:
        """不吞掉测试期间的异常。"""

        return False

    def read(self) -> bytes:
        """返回正文并记录读取次数，验证 401/403 不会读取错误正文。"""

        self.read_count += 1
        return self._body


class _RecordingTransport:
    """按调用顺序返回替身响应，并记录 Request 和 timeout。"""

    def __init__(self, responses: list[_FakeResponse] | None = None, error: Exception | None = None) -> None:
        """初始化响应队列或固定 transport 异常。"""

        self.responses = list(responses or [])
        self.error = error
        self.calls: list[tuple[object, float]] = []

    def __call__(self, request, timeout: float):
        """记录请求，依次返回响应，模拟确定性的内部 HTTP transport。"""

        self.calls.append((request, timeout))
        if self.error is not None:
            raise self.error
        if not self.responses:
            raise AssertionError("test transport response queue is empty")
        return self.responses.pop(0)


def _envelope(data: object, *, code: int = 0) -> dict[str, object]:
    """构造与 platform-common ``PlatformApiResponse`` 匹配的成功/失败包络。"""

    return {"code": code, "reason": "TEST", "message": "not public", "data": data}


def _precheck_request() -> PrecheckControlPlaneRequest:
    """创建真实预检查 Protocol 请求，故意放入不得出网的敏感配置。"""

    return PrecheckControlPlaneRequest(
        tenant_id="10",
        application_id="datasmart-govern",
        project_id="101",
        actor_id="37",
        delegation_id="delegation-1",
        turn_id="turn-1",
        run_id="run-1",
        task_id="42",
        configuration={"password": "top-secret", "sql": "SELECT * FROM private_table"},
        timeout_ms=2_000,
    )


def _diagnostic_request(**context_extra) -> FailureDiagnosticRequest:
    """创建只读诊断请求，context 中的未知字段用于验证白名单裁剪。"""

    context = {
        "taskId": "42",
        "executionId": "900",
        "traceId": "trace-1",
        "password": "top-secret",
        "rawSql": "SELECT password FROM private_table",
    }
    context.update(context_extra)
    return FailureDiagnosticRequest(
        turn_id="turn-1",
        session_id="session-1",
        run_id="run-1",
        delegation_id="delegation-1",
        tenant_id="10",
        project_id="101",
        actor_id="37",
        objective="诊断任务",
        context_summary=context,
    )


def _monitor_query(kind: TaskKind = TaskKind.LONG_RUNNING, *, run_id: str = "agent-run-1") -> TaskMonitoringQuery:
    """创建完整的 MONITOR 查询范围。"""

    return TaskMonitoringQuery(
        tenant_id="10",
        project_id="101",
        actor_id="37",
        delegation_id="delegation-1",
        task_id="42",
        task_kind=kind,
        run_id=run_id,
    )


def _execution(
    *,
    status: str = "RUNNING",
    trigger_type: str = "MANUAL",
    **extra,
) -> dict[str, object]:
    """构造真实 SyncExecution 字段的低敏记录。"""

    record: dict[str, object] = {
        "id": 900,
        "tenantId": 10,
        "projectId": 101,
        "syncTaskId": 42,
        "executionState": status,
        "triggerType": trigger_type,
        "queuedAt": "2026-08-05T00:00:00Z",
        "startedAt": "2026-08-05T00:00:01Z",
        "recordsRead": 100,
        "recordsWritten": 40,
        "failedRecordCount": 2,
        "heartbeatTime": "2026-08-05T00:00:05Z",
        "checkpointRef": "checkpoint-1",
        "updateTime": "2026-08-05T00:00:15Z",
    }
    record.update(extra)
    return record


def _page(records: list[dict[str, object]]) -> dict[str, object]:
    """构造真实 PlatformPageResponse 的 data 载荷。"""

    return {"current": 1, "size": len(records) or 20, "total": len(records), "pages": 1, "records": records}


def _assert_project_scope_headers(testcase: unittest.TestCase, request, project_id: str = "101") -> None:
    """断言只读请求显式使用 PROJECT 单项目范围，防止 data-sync 按空 actorRole 回退 TENANT。"""

    # urllib 会规范化 Header 名称中的大小写；断言规范化后的键和值，避免漏测真实传输。
    testcase.assertEqual("PROJECT", request.headers["X-datasmart-data-scope-level"])
    testcase.assertEqual(project_id, request.headers["X-datasmart-authorized-project-ids"])
    testcase.assertNotEqual("TENANT", request.headers["X-datasmart-data-scope-level"])


class SpecialistControlPlaneAdapterTest(unittest.TestCase):
    """验证三类客户端的生产边界和 Java 协议匹配。"""

    def test_precheck_posts_real_path_headers_and_no_configuration_body(self) -> None:
        """预检查必须使用真实路由、完整可信 Header，且 configuration 不得出网。"""

        transport = _RecordingTransport(
            [
                _FakeResponse(
                    _envelope(
                        {
                            "taskId": 42,
                            "tenantId": 10,
                            "projectId": 101,
                            "precheckStatus": "READY_TO_EXECUTE",
                            "canStartExecution": True,
                            "issueCodes": [],
                            "recommendedActions": [],
                            "performanceNotes": [],
                            "safetyNotes": [],
                        }
                    )
                )
            ]
        )
        client = HttpPrecheckControlPlaneClient(
            "http://data-sync.test",
            timeout_seconds=3,
            service_token="internal-secret",
            transport=transport,
        )

        result = client.precheck(_precheck_request())

        # Java 的 READY_TO_EXECUTE 在 Specialist Protocol 中对应 PASSED；适配器
        # 不应把下游专用状态码泄漏为 Protocol 的未知状态。
        self.assertEqual("PASSED", result.status)
        self.assertEqual("PASSED", result.precheck_status)
        self.assertTrue(result.can_start_execution)
        self.assertEqual(1, len(transport.calls))
        request, timeout = transport.calls[0]
        self.assertEqual(2.0, timeout)
        self.assertEqual("POST", request.get_method())
        self.assertEqual("http://data-sync.test/sync-tasks/42/precheck", request.full_url)
        self.assertIsNone(request.data)
        self.assertEqual("10", request.headers["X-datasmart-tenant-id"])
        self.assertEqual("101", request.headers["X-datasmart-project-id"])
        self.assertEqual("37", request.headers["X-datasmart-actor-id"])
        self.assertEqual("delegation-1", request.headers["X-datasmart-agent-delegation-id"])
        self.assertEqual("turn-1", request.headers["X-datasmart-trace-id"])
        _assert_project_scope_headers(self, request)
        self.assertEqual("internal-secret", request.headers["X-datasmart-internal-service-token"])
        self.assertNotIn("top-secret", str(request.data))

    def test_diagnosis_supports_execution_id_and_only_returns_low_sensitive_facts(self) -> None:
        """诊断只透传 executionId，并裁剪 ragQuery、对象名、错误正文和 SQL。"""

        transport = _RecordingTransport(
            [
                _FakeResponse(
                    _envelope(
                        {
                            "taskId": 42,
                            "executionId": 900,
                            "taskState": "RUNNING",
                            "executionState": "FAILED",
                            "syncMode": "FULL",
                            "writeStrategy": "INSERT",
                            "sourceConnectorType": "MYSQL",
                            "targetConnectorType": "POSTGRESQL",
                            "recordsRead": 100,
                            "recordsWritten": 80,
                            "failedRecordCount": 20,
                            "failedObjectCount": 1,
                            "retryableDirtySampleCount": 2,
                            "quarantinedDirtySampleCount": 1,
                            "runtimeMetrics": {
                                "recordsRead": 100,
                                "recordsWritten": 80,
                                "failedRecordCount": 20,
                                "failedObjectCount": 1,
                                "retryableDirtySampleCount": 2,
                                "quarantinedDirtySampleCount": 1,
                            },
                            "executionPolicyComparison": {
                                "comparisonStatus": "COMPARISON_AVAILABLE",
                                "current": {
                                    "executionId": 900,
                                    "resolvedChannel": 2,
                                    "readBatchSize": 100,
                                    "writeBatchSize": 100,
                                    "timeoutSeconds": 120,
                                },
                                "previousSuccessful": {
                                    "executionId": 899,
                                    "resolvedChannel": 4,
                                    "readBatchSize": 200,
                                    "writeBatchSize": 200,
                                    "timeoutSeconds": 60,
                                },
                                "changedFields": ["resolvedChannel", "timeoutSeconds"],
                            },
                            "connectorRuntimeSummaries": [
                                {
                                    "connectorRole": "SOURCE",
                                    "datasourceId": 1001,
                                    "lookupStatus": "AVAILABLE",
                                    "snapshotVersion": "datasmart.datasource.capability-snapshot.v1",
                                    "connectorRuntimeVersion": "9.2.0",
                                    "connectorRuntimeVersionSource": "PACKAGE_IMPLEMENTATION_VERSION",
                                    "connectorType": "MYSQL",
                                    "connectorFamily": "RELATIONAL_JDBC",
                                    "healthStatus": "CONNECTION_VERIFIED",
                                    "canRead": True,
                                    "canWrite": True,
                                    "supportsSchemaDiscovery": True,
                                    "supportsFieldMapping": True,
                                    "supportsCheckpointResume": True,
                                    "supportsPartitionParallelism": True,
                                    "runtimeLimitStatus": "EXECUTION_POLICY_SNAPSHOT_AVAILABLE",
                                    "effectiveChannel": 2,
                                    "effectiveReadBatchSize": 100,
                                    "effectiveWriteBatchSize": 100,
                                    "effectiveTimeoutSeconds": 120,
                                    "capacityStatus": "POLICY_GOVERNED_NO_HARD_CONNECTOR_CAPACITY_DECLARED",
                                    "performanceRecommendations": ["使用有界批量与背压"],
                                    "issueCodes": [],
                                }
                            ],
                            "evidenceRecords": [
                                {
                                    "evidenceId": "sync-evidence:abc123",
                                    "sourceType": "STRUCTURED_API",
                                    "sourceRef": "sync-execution:42:900:metrics",
                                    "retrievedAt": "2026-08-14T13:00:00Z",
                                    "sourceObservedAt": "2026-08-14T12:59:00",
                                    "confidence": 0.98,
                                    "confidenceBasis": "PERSISTED_EXECUTION_AND_OBJECT_LEDGER",
                                }
                            ],
                            "rootCauseCodes": ["TARGET_DUPLICATE_KEY"],
                            "recommendedRepairActions": ["REVIEW_EXECUTION_LOG"],
                            "ragQuery": "SELECT password FROM private_table",
                            "diagnosisDigest": "目标约束失败，password=plain-value",
                            "errors": [
                                {
                                    "errorType": "DATABASE",
                                    "errorCode": "23505",
                                    "message": "SELECT secret FROM private_table",
                                    "count": 20,
                                    "retryable": False,
                                }
                            ],
                            "failedObjects": [
                                {
                                    "objectExecutionId": 1,
                                    "objectOrdinal": 0,
                                    "targetObjectName": "private_table",
                                    "errorType": "DATABASE",
                                    "errorCode": "23505",
                                    "errorMessage": "password=top-secret",
                                }
                            ],
                        }
                    )
                )
            ]
        )
        client = HttpFailureDiagnosticClient(
            "http://data-sync.test",
            service_token="internal-secret",
            transport=transport,
        )

        result = client.diagnose(_diagnostic_request())

        request, _ = transport.calls[0]
        self.assertEqual(
            "http://data-sync.test/sync-tasks/42/agent-diagnosis?executionId=900",
            request.full_url,
        )
        self.assertEqual("GET", request.get_method())
        self.assertEqual("trace-1", request.headers["X-datasmart-trace-id"])
        _assert_project_scope_headers(self, request)
        self.assertNotIn("projectId", parse_qs(urlsplit(request.full_url).query))
        self.assertEqual("internal-secret", request.headers["X-datasmart-internal-service-token"])
        self.assertEqual("TARGET_DUPLICATE_KEY", result.failure_code)
        public_text = str(result)
        self.assertNotIn("private_table", public_text)
        self.assertNotIn("top-secret", public_text)
        self.assertNotIn("plain-value", public_text)
        self.assertNotIn("SELECT", public_text)
        self.assertNotIn("ragQuery", str(result.facts))
        self.assertEqual(20, result.facts["failedRecordCount"])
        self.assertEqual(2, result.facts["executionPolicyComparison"]["current"]["resolvedChannel"])
        self.assertEqual("9.2.0", result.facts["connectorRuntimeSummaries"][0]["connectorRuntimeVersion"])
        self.assertEqual(0.98, result.evidence_records[0]["confidence"])
        self.assertEqual("sync-execution:42:900:metrics", result.evidence_records[0]["sourceRef"])

    def test_monitor_combines_executions_logs_objects_and_checks_scope(self) -> None:
        """监控快照应由三路真实 DTO 聚合，并透传同一组可信 Header。"""

        execution = _execution()
        log_common = {"tenantId": 10, "projectId": 101, "syncTaskId": 42, "executionId": 900}
        logs = [
            {
                **log_common,
                "logStage": "COPY",
                "eventType": "PROGRESS",
                "logLevel": "INFO",
                "eventStatus": "PROGRESS",
                "speedRowsPerSecond": 20,
                "eventTime": "2026-08-05T00:00:14Z",
            }
        ]
        objects = [
            {
                **log_common,
                "id": 1,
                "objectOrdinal": 0,
                "objectState": "RUNNING",
                "recordsRead": 100,
                "recordsWritten": 40,
                "failedRecordCount": 2,
            }
        ]
        transport = _RecordingTransport(
            [
                _FakeResponse(_envelope(_page([execution]))),
                _FakeResponse(_envelope(_page(logs))),
                _FakeResponse(_envelope(_page(objects))),
            ]
        )
        client = HttpTaskMonitoringClient(
            "http://data-sync.test",
            service_token="internal-secret",
            transport=transport,
        )

        snapshot = client.get_snapshot(_monitor_query())

        self.assertEqual("42", snapshot.task_id)
        self.assertEqual("RUNNING", snapshot.status.value)
        self.assertEqual("COPY", snapshot.phase)
        self.assertEqual(100, snapshot.rows_total)
        self.assertEqual(40, snapshot.rows_processed)
        self.assertEqual(2, snapshot.failure_count)
        self.assertEqual(20.0, snapshot.throughput_rows_per_second)
        self.assertEqual(10.0, snapshot.heartbeat_age_seconds)
        self.assertEqual({"checkpointRef": "checkpoint-1"}, snapshot.checkpoint)
        self.assertEqual(3, len(transport.calls))
        paths = [urlsplit(request.full_url).path for request, _ in transport.calls]
        self.assertEqual(
            [
                "/sync-tasks/42/executions",
                "/sync-tasks/42/executions/900/logs",
                "/sync-tasks/42/executions/900/objects",
            ],
            paths,
        )
        for request, _ in transport.calls:
            self.assertEqual("10", request.headers["X-datasmart-tenant-id"])
            self.assertEqual("101", request.headers["X-datasmart-project-id"])
            self.assertEqual("37", request.headers["X-datasmart-actor-id"])
            self.assertEqual("delegation-1", request.headers["X-datasmart-agent-delegation-id"])
            self.assertEqual("agent-run-1", request.headers["X-datasmart-trace-id"])
            _assert_project_scope_headers(self, request)
            self.assertEqual("internal-secret", request.headers["X-datasmart-internal-service-token"])
            self.assertIsNone(request.data)
        self.assertEqual({"current": ["1"], "size": ["20"]}, parse_qs(urlsplit(transport.calls[0][0].full_url).query))

    def test_periodic_and_cdc_semantics_are_preserved(self) -> None:
        """定期终态回到 SCHEDULED，CDC 失败仍是长期健康事实而不是完成输入。"""

        periodic_transport = _RecordingTransport(
            [
                _FakeResponse(_envelope(_page([_execution(status="FAILED", trigger_type="SCHEDULED", finishedAt="2026-08-05T00:01:00Z")]))),
                _FakeResponse(_envelope(_page([]))),
                _FakeResponse(_envelope(_page([]))),
            ]
        )
        periodic = HttpTaskMonitoringClient("http://data-sync.test", transport=periodic_transport)
        periodic_snapshot = periodic.get_snapshot(_monitor_query(TaskKind.PERIODIC))
        self.assertEqual(TaskKind.PERIODIC, periodic_snapshot.task_kind)
        self.assertEqual("SCHEDULED", periodic_snapshot.status.value)
        self.assertEqual("FAILED", periodic_snapshot.last_run_status.value)

        cdc_transport = _RecordingTransport(
            [
                _FakeResponse(
                    _envelope(
                        _page(
                            [
                                _execution(
                                    status="FAILED",
                                    cdcLagSeconds=240,
                                    heartbeatAgeSeconds=180,
                                )
                            ]
                        )
                    )
                ),
                _FakeResponse(_envelope(_page([]))),
                _FakeResponse(_envelope(_page([]))),
            ]
        )
        cdc = HttpTaskMonitoringClient("http://data-sync.test", transport=cdc_transport)
        cdc_snapshot = cdc.get_snapshot(_monitor_query(TaskKind.CDC_REALTIME))
        self.assertEqual(TaskKind.CDC_REALTIME, cdc_snapshot.task_kind)
        self.assertEqual("FAILED", cdc_snapshot.status.value)
        self.assertEqual(240.0, cdc_snapshot.cdc_lag_seconds)
        self.assertEqual(180.0, cdc_snapshot.heartbeat_age_seconds)

    def test_unauthorized_forbidden_and_timeout_are_low_sensitive(self) -> None:
        """401/403/timeout fail-closed，异常只暴露机器码且不读取错误正文。"""

        secret_body = b'password=top-secret SELECT value FROM private_table'
        unauthorized_response = _FakeResponse(secret_body, status=401, raw=True)
        unauthorized_transport = _RecordingTransport([unauthorized_response])
        client = HttpPrecheckControlPlaneClient("http://data-sync.test", transport=unauthorized_transport)
        with self.assertRaises(SpecialistControlPlaneAdapterError) as raised:
            client.precheck(_precheck_request())
        self.assertEqual("CONTROL_PLANE_UNAUTHORIZED", raised.exception.code)
        self.assertNotIn("top-secret", str(raised.exception))
        self.assertEqual(0, unauthorized_response.read_count)

        forbidden_response = _FakeResponse(secret_body, status=403, raw=True)
        forbidden_transport = _RecordingTransport([forbidden_response])
        monitor = HttpTaskMonitoringClient("http://data-sync.test", transport=forbidden_transport)
        with self.assertRaises(SpecialistControlPlaneAdapterError) as forbidden:
            monitor.get_snapshot(_monitor_query())
        self.assertEqual("CONTROL_PLANE_FORBIDDEN", forbidden.exception.code)
        self.assertNotIn("private_table", str(forbidden.exception))
        self.assertEqual(0, forbidden_response.read_count)

        timeout_transport = _RecordingTransport(error=TimeoutError("password=top-secret"))
        diagnosis = HttpFailureDiagnosticClient("http://data-sync.test", transport=timeout_transport)
        with self.assertRaises(SpecialistControlPlaneAdapterError) as timeout:
            diagnosis.diagnose(_diagnostic_request())
        self.assertEqual("CONTROL_PLANE_TIMEOUT", timeout.exception.code)
        self.assertNotIn("top-secret", str(timeout.exception))

    def test_scope_mismatch_and_malformed_body_fail_closed(self) -> None:
        """范围不一致、非 JSON 和平台失败均不能产生部分成功快照。"""

        mismatch_transport = _RecordingTransport(
            [
                _FakeResponse(
                    _envelope(
                        {
                            "taskId": 42,
                            "tenantId": 10,
                            "projectId": 999,
                            "precheckStatus": "READY_TO_EXECUTE",
                            "canStartExecution": True,
                            "issueCodes": [],
                            "recommendedActions": [],
                            "performanceNotes": [],
                            "safetyNotes": [],
                        }
                    )
                )
            ]
        )
        precheck = HttpPrecheckControlPlaneClient("http://data-sync.test", transport=mismatch_transport)
        with self.assertRaises(SpecialistControlPlaneAdapterError) as mismatch:
            precheck.precheck(_precheck_request())
        self.assertEqual("CONTROL_PLANE_SCOPE_MISMATCH", mismatch.exception.code)

        diagnosis_scope_transport = _RecordingTransport(
            [
                _FakeResponse(
                    _envelope(
                        {
                            "taskId": 42,
                            "executionId": 900,
                            "tenantId": 10,
                            "projectId": 999,
                            "executionState": "FAILED",
                        }
                    )
                )
            ]
        )
        diagnosis_scope = HttpFailureDiagnosticClient(
            "http://data-sync.test", transport=diagnosis_scope_transport
        )
        with self.assertRaises(SpecialistControlPlaneAdapterError) as diagnosis_mismatch:
            diagnosis_scope.diagnose(_diagnostic_request())
        self.assertEqual("CONTROL_PLANE_SCOPE_MISMATCH", diagnosis_mismatch.exception.code)

        invalid_body = _FakeResponse(b'password=top-secret SELECT * FROM private_table', raw=True)
        invalid_transport = _RecordingTransport([invalid_body])
        diagnosis = HttpFailureDiagnosticClient("http://data-sync.test", transport=invalid_transport)
        with self.assertRaises(SpecialistControlPlaneAdapterError) as invalid:
            diagnosis.diagnose(_diagnostic_request())
        self.assertEqual("CONTROL_PLANE_RESPONSE_NOT_JSON", invalid.exception.code)
        self.assertNotIn("top-secret", str(invalid.exception))

        # 项目越界必须和租户越界一样 fail-closed，不能因为 tenant 相同就接受另一项目。
        cross_scope_execution = _execution(projectId=999)
        cross_scope_transport = _RecordingTransport(
            [_FakeResponse(_envelope(_page([cross_scope_execution])))]
        )
        monitor = HttpTaskMonitoringClient("http://data-sync.test", transport=cross_scope_transport)
        with self.assertRaises(SpecialistControlPlaneAdapterError) as scope:
            monitor.get_snapshot(_monitor_query())
        self.assertEqual("CONTROL_PLANE_SCOPE_MISMATCH", scope.exception.code)
        self.assertEqual(1, len(cross_scope_transport.calls))

    def test_settings_reject_unsafe_base_url_and_recovery_client_has_no_executor(self) -> None:
        """基础设施 URL 禁止携带凭据，Recovery 适配器只暴露诊断方法。"""

        with self.assertRaises(ValueError):
            ControlPlaneHttpClientSettings("http://user:password@data-sync.test")
        client = HttpFailureDiagnosticClient("http://data-sync.test")
        self.assertTrue(callable(client.diagnose))
        self.assertFalse(hasattr(client, "execute"))
        self.assertFalse(hasattr(client, "retry"))

    def test_settings_token_precedence_never_uses_generic_service_token(self) -> None:
        """data-sync 专用 token 优先，其次兼容旧 data-sync 名称和 Compose 现有 Agent Runtime token。"""

        base = {"DATASMART_DATA_SYNC_BASE_URL": "http://data-sync.test"}
        dedicated = ControlPlaneHttpClientSettings.from_env(
            {
                **base,
                "DATASMART_DATA_SYNC_INTERNAL_SERVICE_TOKEN": "data-sync-dedicated",
                "DATASMART_DATA_SYNC_SERVICE_TOKEN": "data-sync-legacy",
                "DATASMART_AGENT_RUNTIME_INTERNAL_SERVICE_TOKEN": "agent-runtime",
                "DATASMART_INTERNAL_SERVICE_TOKEN": "unrelated-service",
            }
        )
        self.assertEqual("data-sync-dedicated", dedicated.service_token)

        compatible = ControlPlaneHttpClientSettings.from_env(
            {
                **base,
                "DATASMART_DATA_SYNC_SERVICE_TOKEN": "data-sync-legacy",
                "DATASMART_AGENT_RUNTIME_INTERNAL_SERVICE_TOKEN": "agent-runtime",
            }
        )
        self.assertEqual("data-sync-legacy", compatible.service_token)

        compose_fallback = ControlPlaneHttpClientSettings.from_env(
            {**base, "DATASMART_AGENT_RUNTIME_INTERNAL_SERVICE_TOKEN": "agent-runtime"}
        )
        self.assertEqual("agent-runtime", compose_fallback.service_token)

        generic_only = ControlPlaneHttpClientSettings.from_env(
            {**base, "DATASMART_INTERNAL_SERVICE_TOKEN": "unrelated-service"}
        )
        self.assertIsNone(generic_only.service_token)


if __name__ == "__main__":
    unittest.main()
