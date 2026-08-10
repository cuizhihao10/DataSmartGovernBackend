"""DATA_SYNC_AGENT 专业元数据 HTTP 适配器契约测试。

测试只模拟 data-sync 的 PlatformApiResponse，不连接数据库。重点验证请求体只包含有限
元数据参数、Header 携带完整审计范围，以及 401/403、超时和项目范围错配都 fail-closed。
"""

from __future__ import annotations

import json
import unittest

from datasmart_ai_runtime.services.multi_agent.specialist_metadata_adapters import (
    HttpSyncMetadataDiscoveryTool,
)
from datasmart_ai_runtime.services.multi_agent.specialists.data_sync_agent import (
    SyncMetadataDiscoveryError,
    SyncMetadataDiscoveryRequest,
)
from datasmart_ai_runtime.services.multi_agent.specialist_control_plane_adapters import (
    ControlPlaneHttpClientSettings,
)


class _FakeResponse:
    """最小 urllib response 替身，同时记录错误响应是否被读取。"""

    def __init__(self, payload: object, *, status: int = 200) -> None:
        """创建 JSON 响应。"""

        self.status = status
        self.read_count = 0
        self._body = json.dumps(payload, ensure_ascii=False).encode("utf-8")

    def __enter__(self):
        """支持生产客户端使用 response context manager。"""

        return self

    def __exit__(self, exc_type, exc, traceback) -> bool:
        """不吞掉测试异常。"""

        return False

    def read(self) -> bytes:
        """返回响应正文并记录读取次数。"""

        self.read_count += 1
        return self._body


class _RecordingTransport:
    """按顺序返回响应并记录 Request 和超时。"""

    def __init__(self, responses: list[_FakeResponse], error: Exception | None = None) -> None:
        """初始化响应队列或固定 transport 异常。"""

        self.responses = list(responses)
        self.error = error
        self.calls: list[tuple[object, float]] = []

    def __call__(self, request, timeout: float):
        """记录请求并返回下一个响应。"""

        self.calls.append((request, timeout))
        if self.error is not None:
            raise self.error
        return self.responses.pop(0)


def _envelope(data: object, *, code: int = 0) -> dict[str, object]:
    """构造 data-sync PlatformApiResponse。"""

    return {"code": code, "message": "内部消息不应外传", "data": data}


def _request(
    *,
    table_names: tuple[str, ...] = (),
    schema_pattern: str | None = None,
    filter_mode: str = "ALL",
) -> SyncMetadataDiscoveryRequest:
    """构造具备双主体和项目范围的元数据发现请求。"""

    return SyncMetadataDiscoveryRequest(
        tenant_id="10",
        project_id="101",
        actor_id="37",
        delegation_id="delegation-metadata-1",
        session_id="session-1",
        run_id="run-1",
        trace_id="turn-1",
        datasource_id=23,
        side="source",
        connector_type="mysql",
        table_names=table_names,
        schema_pattern=schema_pattern,
        filter_mode=filter_mode,
    )


def _success_data(**extra) -> dict[str, object]:
    """返回包含表、字段和主键的最小真实创建向导 DTO。"""

    data: dict[str, object] = {
        "datasourceId": 23,
        "side": "SOURCE",
        "connectorType": "MYSQL",
        "discoverable": True,
        "schemas": [],
        "tables": [
            {
                "schemaName": "app",
                "tableName": "customer",
                "tableType": "TABLE",
                "primaryKeys": ["id"],
                "fields": [
                    {
                        "fieldName": "id",
                        "dataTypeName": "BIGINT",
                        "nullable": False,
                        "primaryKey": True,
                        "ordinalPosition": 1,
                    },
                    {
                        "fieldName": "name",
                        "dataTypeName": "VARCHAR",
                        "nullable": True,
                        "primaryKey": False,
                        "ordinalPosition": 2,
                    },
                ],
            }
        ],
        "warnings": [],
    }
    data.update(extra)
    return data


class SpecialistMetadataAdapterTest(unittest.TestCase):
    """验证 HTTP 适配器的真实 DTO、审计 Header 和 fail-closed 行为。"""

    def test_posts_bounded_request_and_maps_low_sensitive_metadata(self) -> None:
        transport = _RecordingTransport([_FakeResponse(_envelope(_success_data()))])
        settings = ControlPlaneHttpClientSettings(
            base_url="http://data-sync:8086",
            timeout_seconds=2.5,
            service_token="internal-secret",
        )
        tool = HttpSyncMetadataDiscoveryTool(settings=settings, transport=transport)

        result = tool.discover(_request())

        self.assertEqual(1, len(transport.calls))
        sent_request, timeout = transport.calls[0]
        self.assertEqual(2.5, timeout)
        self.assertEqual("http://data-sync:8086/sync-tasks/metadata/objects/discover", sent_request.full_url)
        headers = {key.lower(): value for key, value in sent_request.header_items()}
        self.assertEqual("10", headers["x-datasmart-tenant-id"])
        self.assertEqual("101", headers["x-datasmart-project-id"])
        self.assertEqual("37", headers["x-datasmart-actor-id"])
        self.assertEqual("delegation-metadata-1", headers["x-datasmart-agent-delegation-id"])
        self.assertEqual("session-1", headers["x-datasmart-agent-session-id"])
        self.assertEqual("run-1", headers["x-datasmart-agent-run-id"])
        self.assertEqual("turn-1", headers["x-datasmart-trace-id"])
        self.assertEqual("PROJECT", headers["x-datasmart-data-scope-level"])
        self.assertEqual("101", headers["x-datasmart-authorized-project-ids"])
        self.assertEqual("internal-secret", headers["x-datasmart-internal-service-token"])
        body = json.loads(sent_request.data.decode("utf-8"))
        self.assertEqual(
            {
                "datasourceId": 23,
                "side": "SOURCE",
                "connectorType": "MYSQL",
                "filterMode": "ALL",
                "includeColumns": True,
                "includeViews": False,
                "maxTables": 100,
                "maxColumnsPerTable": 200,
            },
            body,
        )
        self.assertEqual(23, result.datasource_id)
        self.assertEqual("SOURCE", result.side)
        self.assertEqual(1, result.object_count)
        self.assertEqual(2, result.field_count)
        self.assertEqual("id", result.metadata["objects"][0]["columns"][0]["columnName"])
        self.assertNotIn("message", result.metadata)

    def test_queries_each_explicit_table_and_merges_results(self) -> None:
        """明确表名必须绕过前 N 张表扫描，并在适配器边界按真实名称收口。"""

        tables = [
            {
                "schemaName": None,
                "tableName": "fs_test_customer_source",
                "fields": [],
            },
            {
                "schemaName": None,
                "tableName": "fs_test_customer_target",
                "fields": [],
            },
        ]
        transport = _RecordingTransport(
            [
                _FakeResponse(_envelope(_success_data(tables=[tables[0]]))),
                _FakeResponse(_envelope(_success_data(tables=[tables[1]]))),
            ]
        )
        tool = HttpSyncMetadataDiscoveryTool(
            base_url="http://data-sync:8086",
            transport=transport,
        )

        result = tool.discover(
            _request(
                table_names=("fs_test_customer_source", "fs_test_customer_target"),
                filter_mode="TABLE",
            )
        )

        self.assertEqual(2, len(transport.calls))
        bodies = [json.loads(call[0].data.decode("utf-8")) for call in transport.calls]
        self.assertEqual(
            [
                "fs_test_customer_source",
                "fs_test_customer_target",
            ],
            [body["tableNamePattern"] for body in bodies],
        )
        self.assertTrue(all(body["filterMode"] == "TABLE" for body in bodies))
        self.assertTrue(all("schemaPattern" not in body for body in bodies))
        self.assertEqual(
            ["fs_test_customer_source", "fs_test_customer_target"],
            [item["tableName"] for item in result.metadata["objects"]],
        )
        self.assertTrue(result.metadata["exactQuery"])

    def test_uses_create_wizard_alias_only_when_primary_route_is_missing(self) -> None:
        transport = _RecordingTransport(
            [
                _FakeResponse({"message": "not read", "data": {}}, status=404),
                _FakeResponse(_envelope(_success_data())),
            ]
        )
        tool = HttpSyncMetadataDiscoveryTool(
            base_url="http://data-sync:8086",
            transport=transport,
        )

        tool.discover(_request())

        self.assertEqual(
            [
                "http://data-sync:8086/sync-tasks/metadata/objects/discover",
                "http://data-sync:8086/sync-tasks/create-wizard/metadata/objects/discover",
            ],
            [call[0].full_url for call in transport.calls],
        )

    def test_unauthorized_forbidden_and_timeout_are_stable_and_do_not_read_error_body(self) -> None:
        for status, expected_code in (
            (401, "SYNC_METADATA_HTTP_UNAUTHORIZED"),
            (403, "SYNC_METADATA_HTTP_FORBIDDEN"),
        ):
            response = _FakeResponse({"message": "password=must-not-leak"}, status=status)
            tool = HttpSyncMetadataDiscoveryTool(
                base_url="http://data-sync:8086",
                transport=_RecordingTransport([response]),
            )
            with self.subTest(status=status):
                with self.assertRaises(SyncMetadataDiscoveryError) as raised:
                    tool.discover(_request())
                self.assertEqual(expected_code, raised.exception.code)
                self.assertEqual(0, response.read_count)

        tool = HttpSyncMetadataDiscoveryTool(
            base_url="http://data-sync:8086",
            transport=_RecordingTransport([], error=TimeoutError()),
        )
        with self.assertRaises(SyncMetadataDiscoveryError) as raised:
            tool.discover(_request())
        self.assertEqual("SYNC_METADATA_HTTP_TIMEOUT", raised.exception.code)

    def test_scope_mismatch_and_connector_mismatch_fail_closed(self) -> None:
        mismatched_scope = _success_data(projectId="900")
        tool = HttpSyncMetadataDiscoveryTool(
            base_url="http://data-sync:8086",
            transport=_RecordingTransport([_FakeResponse(_envelope(mismatched_scope))]),
        )
        with self.assertRaises(SyncMetadataDiscoveryError) as raised:
            tool.discover(_request())
        self.assertEqual("SYNC_METADATA_SCOPE_MISMATCH", raised.exception.code)

        mismatched_connector = _success_data(connectorType="POSTGRESQL")
        tool = HttpSyncMetadataDiscoveryTool(
            base_url="http://data-sync:8086",
            transport=_RecordingTransport([_FakeResponse(_envelope(mismatched_connector))]),
        )
        with self.assertRaises(SyncMetadataDiscoveryError) as raised:
            tool.discover(_request())
        self.assertEqual("SYNC_METADATA_CONNECTOR_MISMATCH", raised.exception.code)


if __name__ == "__main__":
    unittest.main()
