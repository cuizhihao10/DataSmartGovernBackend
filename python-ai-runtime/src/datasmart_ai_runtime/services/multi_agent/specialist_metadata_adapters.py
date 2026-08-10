"""DATA_SYNC_AGENT 的专业元数据 HTTP 适配器。

本模块只负责把 ``SyncMetadataDiscoveryTool`` 映射到 data-sync 的创建向导元数据接口。
它不会创建任务、修改表结构或读取样本行；真正的数据源连接和下游授权仍由 Java
data-sync/datasource-management 控制面完成。

适配器刻意复用现有控制面 HTTP 基础设施的 Header、超时和响应包络处理，避免专业元数据
客户端重新实现一套容易漏掉 401/403、PROJECT scope 或 service token 保护的网络代码。
由于本文件只使用该基础设施的只读能力，所以不会改变其他 Agent 的控制面适配器行为。
"""

from __future__ import annotations

import hashlib
import json
import re
from dataclasses import replace
from collections.abc import Callable, Mapping
from typing import Any

from datasmart_ai_runtime.services.multi_agent.specialist_control_plane_adapters import (
    ControlPlaneHttpClientSettings,
    SpecialistControlPlaneAdapterError,
    _ControlPlaneHttpClientBase,
)
from datasmart_ai_runtime.services.multi_agent.specialists.data_sync_agent import (
    SyncMetadataDiscoveryError,
    SyncMetadataDiscoveryRequest,
    SyncMetadataDiscoveryResult,
    SyncMetadataDiscoveryTool,
)


# data-sync 同时保留旧路径和创建向导别名。优先调用语义更通用的旧路径，只有路由不存在时
# 才尝试别名；401/403、超时和业务拒绝绝不重试，避免把真正的权限问题掩盖成网络问题。
METADATA_DISCOVERY_PATH = "/sync-tasks/metadata/objects/discover"
METADATA_DISCOVERY_WIZARD_PATH = "/sync-tasks/create-wizard/metadata/objects/discover"

_SAFE_IDENTIFIER = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
_SAFE_CONNECTOR = re.compile(r"^[A-Z0-9][A-Z0-9_.:-]{0,63}$")
_SUSPICIOUS_TEXT = re.compile(
    r"(?i)(?:password|passwd|secret|token|api[_-]?key|jdbc:|bearer\s+|select\s+|insert\s+|update\s+|delete\s+)"
)


class HttpSyncMetadataDiscoveryTool(_ControlPlaneHttpClientBase, SyncMetadataDiscoveryTool):
    """通过 data-sync 创建向导接口读取低敏 schema/table/field 元数据。

    ``discover`` 是本类唯一的业务方法，符合只读 Tool Protocol。请求中的数据源 ID
    不是从名称搜索得到的，而是由 DATA_SYNC_AGENT 先从可信结构化事实解析后传入；本类
    还会检查 data-sync 响应回显的 datasourceId、side、tenantId/projectId，避免代理、
    错误路由或缓存串出另一个项目的元数据。
    """

    DEFAULT_AGENT_ID = "data-sync-specialist-v1"

    def __init__(
        self,
        base_url: str | ControlPlaneHttpClientSettings | None = None,
        *,
        settings: ControlPlaneHttpClientSettings | None = None,
        timeout_seconds: float = 3.0,
        service_token: str | None = None,
        source_service: str = "python-ai-runtime",
        agent_id: str | None = DEFAULT_AGENT_ID,
        trace_id: str | None = None,
        transport: Callable[..., Any] | None = None,
        urlopen_func: Callable[..., Any] | None = None,
    ) -> None:
        """创建元数据 HTTP 客户端。

        ``transport``/``urlopen_func`` 是测试和生产连接池注入点。所有真实调用仍通过基类
        的有限响应读取、无正文错误处理、超时上限和 service token Header 规则执行。
        """

        resolved_settings = self._coerce_settings(
            base_url,
            settings=settings,
            timeout_seconds=timeout_seconds,
            service_token=service_token,
            source_service=source_service,
            agent_id=agent_id,
            trace_id=trace_id,
        )
        super().__init__(resolved_settings, transport=transport, urlopen_func=urlopen_func)

    @classmethod
    def _coerce_settings(
        cls,
        base_url: str | ControlPlaneHttpClientSettings | None,
        *,
        settings: ControlPlaneHttpClientSettings | None,
        timeout_seconds: float,
        service_token: str | None,
        source_service: str,
        agent_id: str | None,
        trace_id: str | None,
    ) -> ControlPlaneHttpClientSettings:
        """兼容统一 Settings 对象、直接 URL 和环境变量三种生产装配方式。"""

        if isinstance(base_url, ControlPlaneHttpClientSettings):
            if settings is not None:
                raise ValueError("base_url 已经是 Settings 时不能重复提供 settings")
            return base_url
        if settings is not None:
            return settings
        if base_url is None:
            return ControlPlaneHttpClientSettings.from_env()
        return ControlPlaneHttpClientSettings(
            base_url=base_url,
            timeout_seconds=timeout_seconds,
            service_token=service_token,
            source_service=source_service,
            agent_id=agent_id,
            trace_id=trace_id,
        )

    def discover(self, request: SyncMetadataDiscoveryRequest) -> SyncMetadataDiscoveryResult:
        """读取一侧元数据并裁剪为低敏 ``SyncMetadataDiscoveryResult``。

        发出的 body 只有已确认的数据源 ID、方向、连接器和有限扫描参数；不携带密码、连接串、
        SQL、样本行或自由文本目标。Headers 会透传 tenant/project/actor/delegation/session/run/trace，
        并固定为 ``DATA_SCOPE_LEVEL=PROJECT``、``AUTHORIZED_PROJECT_IDS=<projectId>``。
        """

        if not isinstance(request, SyncMetadataDiscoveryRequest):
            raise SyncMetadataDiscoveryError("SYNC_METADATA_REQUEST_TYPE_INVALID")
        try:
            headers = self._headers(
                tenant_id=request.tenant_id,
                project_id=request.project_id,
                actor_id=request.actor_id,
                delegation_id=request.delegation_id,
                trace_id=request.trace_id,
                session_id=request.session_id,
                run_id=request.run_id,
                agent_id=self.DEFAULT_AGENT_ID,
            )
            headers = {**headers, "Content-Type": "application/json"}
            # 明确表名时逐表精确查询。这样即使数据源有数千张表，也不会因为控制面
            # 的 maxTables 上限只返回前 N 张而把用户点名的表错误判定为不存在。
            if request.table_names:
                discoveries: list[SyncMetadataDiscoveryResult] = []
                for table_name in request.table_names:
                    exact_request = replace(
                        request,
                        table_names=(),
                        table_name_pattern=table_name,
                        max_tables=1,
                    )
                    data = self._request_with_alias(
                        headers=headers,
                        body=self._request_body(exact_request),
                    )
                    discoveries.append(self._map_response(data, exact_request))
                return self._merge_results(request, discoveries)

            body = self._request_body(request)
            data = self._request_with_alias(headers=headers, body=body)
            return self._map_response(data, request)
        except SyncMetadataDiscoveryError:
            raise
        except SpecialistControlPlaneAdapterError as exc:
            raise SyncMetadataDiscoveryError(
                self._stable_control_plane_code(exc),
                status_code=exc.status_code,
            ) from None
        except (TypeError, ValueError):
            # DTO/范围校验错误本身不应该带出原始值；调用方只需知道请求不满足合同。
            raise SyncMetadataDiscoveryError("SYNC_METADATA_REQUEST_INVALID") from None
        except Exception:
            # 适配器作为 Agent 工具边界，永远不能把 URL、响应正文或认证异常泄露给模型。
            raise SyncMetadataDiscoveryError("SYNC_METADATA_HTTP_FAILED") from None

    def _request_body(self, request: SyncMetadataDiscoveryRequest) -> bytes:
        """构造与 data-sync ``SyncTaskMetadataDiscoveryRequest`` 对齐的有限 JSON body。"""

        payload = {
            "datasourceId": request.datasource_id,
            "side": request.side,
            "connectorType": request.connector_type,
            "filterMode": request.filter_mode,
            "includeColumns": bool(request.include_columns),
            "includeViews": False,
            "maxTables": request.max_tables,
            "maxColumnsPerTable": request.max_columns,
        }
        # 保持默认“发现全部对象”的历史请求体稳定；只有精确表查询或明确 schema
        # 筛选时才增加可选字段，避免不同版本控制面因为多余 null 字段产生契约差异。
        if request.schema_pattern:
            payload["schemaPattern"] = request.schema_pattern
        if request.table_name_pattern:
            payload["tableNamePattern"] = request.table_name_pattern
        return json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")

    def _merge_results(
        self,
        request: SyncMetadataDiscoveryRequest,
        discoveries: list[SyncMetadataDiscoveryResult],
    ) -> SyncMetadataDiscoveryResult:
        """合并多次精确查询的低敏结果，同时保留空结果和每次查询的 warning。

        “某一张表没有返回”不能在适配器层被改写成网络错误；合并后的对象集合会由同步规划
        Agent 再结合源/目标映射做确定性校验。这里也按名称去重，兼容 JDBC 驱动对大小写的
        不同处理方式，并确保一次 Agent turn 只向上游暴露一个稳定 metadata 快照。
        """

        objects: list[dict[str, Any]] = []
        schemas: list[str] = []
        warnings: list[str] = []
        connector_type = request.connector_type
        for discovery in discoveries:
            metadata = discovery.metadata if isinstance(discovery.metadata, Mapping) else {}
            for item in metadata.get("objects", ()):
                if not isinstance(item, Mapping):
                    continue
                key = (
                    str(item.get("schemaName") or "").casefold(),
                    str(item.get("tableName") or "").casefold(),
                )
                if not any(
                    (
                        str(existing.get("schemaName") or "").casefold(),
                        str(existing.get("tableName") or "").casefold(),
                    )
                    == key
                    for existing in objects
                ):
                    objects.append(dict(item))
            for schema in metadata.get("schemas", ()):
                value = self._safe_text(schema)
                if value and value.casefold() not in {item.casefold() for item in schemas}:
                    schemas.append(value)
            for warning in discovery.warnings:
                if warning not in warnings:
                    warnings.append(warning)
            connector_type = connector_type or discovery.connector_type

        metadata = {
            "datasourceId": request.datasource_id,
            "connectorType": connector_type,
            "schemas": schemas,
            "objects": objects,
            "warnings": warnings,
            "exactQuery": True,
            "requestedTableCount": len(request.table_names),
        }
        return SyncMetadataDiscoveryResult(
            datasource_id=request.datasource_id,
            side=request.side,
            connector_type=connector_type,
            metadata=metadata,
            object_count=len(objects),
            field_count=sum(len(item.get("columns") or ()) for item in objects),
            warnings=tuple(warnings),
            evidence_reference=self._evidence_reference(request),
        )

    def _request_with_alias(
        self,
        *,
        headers: Mapping[str, str],
        body: bytes,
    ) -> Mapping[str, Any]:
        """调用主路径，只有 404 路由不存在时才调用创建向导别名。"""

        try:
            return self._request_json(
                method="POST",
                url=self._url(METADATA_DISCOVERY_PATH),
                headers=headers,
                body=body,
            )
        except SpecialistControlPlaneAdapterError as exc:
            if exc.status_code != 404:
                raise
            try:
                return self._request_json(
                    method="POST",
                    url=self._url(METADATA_DISCOVERY_WIZARD_PATH),
                    headers=headers,
                    body=body,
                )
            except SpecialistControlPlaneAdapterError:
                raise

    def _map_response(
        self,
        data: Mapping[str, Any],
        request: SyncMetadataDiscoveryRequest,
    ) -> SyncMetadataDiscoveryResult:
        """校验范围回显并把 Java DTO 映射成 Agent 的低敏 metadata 结构。"""

        if not isinstance(data, Mapping):
            raise SyncMetadataDiscoveryError("SYNC_METADATA_RESPONSE_INVALID")
        self._validate_scope_echo(data, request)
        if data.get("discoverable") is False:
            raise SyncMetadataDiscoveryError("SYNC_METADATA_NOT_DISCOVERABLE")
        raw_tables = data.get("tables")
        if not isinstance(raw_tables, list):
            raise SyncMetadataDiscoveryError("SYNC_METADATA_TABLES_INVALID")

        objects: list[dict[str, Any]] = []
        field_count = 0
        for raw_table in raw_tables[: request.max_tables]:
            if not isinstance(raw_table, Mapping):
                raise SyncMetadataDiscoveryError("SYNC_METADATA_TABLE_INVALID")
            table = self._map_table(raw_table, request.max_columns)
            if table is None:
                # 空名称对象无法用于映射，丢弃它比把不可定位的事实交给模型更安全。
                continue
            if request.table_name_pattern and (
                str(table.get("tableName") or "").casefold()
                != request.table_name_pattern.casefold()
            ):
                # JDBC 的 tableNamePattern 遵循 LIKE 语义，下划线可能被解释成通配符。
                # Agent 需要的是用户点名的确定对象，所以在低敏边界再次做精确名称收口。
                continue
            if request.schema_pattern and (
                str(table.get("schemaName") or "").casefold()
                != request.schema_pattern.casefold()
            ):
                continue
            field_count += len(table["columns"])
            objects.append(table)

        connector_type = self._connector_type(data.get("connectorType")) or request.connector_type
        if request.connector_type and connector_type and connector_type != request.connector_type:
            raise SyncMetadataDiscoveryError("SYNC_METADATA_CONNECTOR_MISMATCH")
        schemas = self._safe_string_list(data.get("schemas"), limit=200)
        if not schemas:
            schemas = list(
                dict.fromkeys(
                    str(item["schemaName"]).strip()
                    for item in objects
                    if item.get("schemaName")
                )
            )
        warnings = self._safe_warning_list(data.get("warnings"))
        metadata = {
            "datasourceId": request.datasource_id,
            "connectorType": connector_type,
            "schemas": schemas,
            "objects": objects,
            "warnings": warnings,
        }
        return SyncMetadataDiscoveryResult(
            datasource_id=request.datasource_id,
            side=request.side,
            connector_type=connector_type,
            metadata=metadata,
            object_count=len(objects),
            field_count=field_count,
            warnings=tuple(warnings),
            evidence_reference=self._evidence_reference(request),
        )

    def _validate_scope_echo(
        self,
        data: Mapping[str, Any],
        request: SyncMetadataDiscoveryRequest,
    ) -> None:
        """严格校验响应中的可选租户、项目、数据源和方向回显。"""

        response_datasource_id = self._positive_id(data.get("datasourceId"))
        if response_datasource_id != request.datasource_id:
            raise SyncMetadataDiscoveryError("SYNC_METADATA_SCOPE_MISMATCH")
        for field_name, expected in (
            ("tenantId", request.tenant_id),
            ("projectId", request.project_id),
            ("authorizedProjectId", request.authorized_project_id),
        ):
            if field_name in data and data.get(field_name) is not None:
                actual = str(data.get(field_name)).strip()
                if not _SAFE_IDENTIFIER.fullmatch(actual) or actual != str(expected):
                    raise SyncMetadataDiscoveryError("SYNC_METADATA_SCOPE_MISMATCH")
        if data.get("side") is not None and str(data.get("side")).strip().upper() != request.side:
            raise SyncMetadataDiscoveryError("SYNC_METADATA_SCOPE_MISMATCH")

    def _map_table(self, raw_table: Mapping[str, Any], max_columns: int) -> dict[str, Any] | None:
        """裁剪一张表的 schema、名称、主键和有限字段摘要。"""

        table_name = self._safe_text(raw_table.get("tableName") or raw_table.get("objectName"))
        if not table_name:
            return None
        fields = raw_table.get("fields")
        if not isinstance(fields, list):
            fields = raw_table.get("columns")
        if fields is None:
            fields = []
        if not isinstance(fields, list):
            raise SyncMetadataDiscoveryError("SYNC_METADATA_FIELDS_INVALID")

        columns: list[dict[str, Any]] = []
        for raw_field in fields[:max_columns]:
            if not isinstance(raw_field, Mapping):
                raise SyncMetadataDiscoveryError("SYNC_METADATA_FIELD_INVALID")
            field_name = self._safe_text(raw_field.get("fieldName") or raw_field.get("columnName"))
            if not field_name:
                continue
            columns.append(
                {
                    "columnName": field_name,
                    "dataTypeName": self._safe_text(
                        raw_field.get("dataTypeName") or raw_field.get("dataType")
                    ),
                    "nullable": self._safe_bool(raw_field.get("nullable")),
                    "primaryKey": self._safe_bool(raw_field.get("primaryKey")),
                    "ordinalPosition": self._safe_int(raw_field.get("ordinalPosition"), minimum=0),
                }
            )
        primary_keys = self._safe_string_list(raw_table.get("primaryKeys"), limit=max_columns)
        return {
            "catalog": self._safe_text(raw_table.get("catalog")),
            "schemaName": self._safe_text(raw_table.get("schemaName") or raw_table.get("schema")),
            "tableName": table_name,
            "tableType": self._safe_text(raw_table.get("tableType")) or "TABLE",
            "primaryKeys": primary_keys,
            "columns": columns,
        }

    @staticmethod
    def _stable_control_plane_code(error: SpecialistControlPlaneAdapterError) -> str:
        """把共享 HTTP 基础设施错误映射为元数据工具稳定错误码。"""

        if error.status_code == 401 or error.code == "CONTROL_PLANE_UNAUTHORIZED":
            return "SYNC_METADATA_HTTP_UNAUTHORIZED"
        if error.status_code == 403 or error.code == "CONTROL_PLANE_FORBIDDEN":
            return "SYNC_METADATA_HTTP_FORBIDDEN"
        if error.code == "CONTROL_PLANE_TIMEOUT":
            return "SYNC_METADATA_HTTP_TIMEOUT"
        if error.status_code == 404:
            return "SYNC_METADATA_ROUTE_NOT_FOUND"
        return "SYNC_METADATA_HTTP_FAILED"

    @staticmethod
    def _positive_id(value: Any) -> int | None:
        """规范化 response datasourceId，拒绝布尔、零值、负数和自然语言。"""

        if isinstance(value, bool):
            return None
        try:
            result = int(str(value).strip())
        except (TypeError, ValueError):
            return None
        return result if result > 0 else None

    @staticmethod
    def _connector_type(value: Any) -> str | None:
        """只保留有限连接器标识，避免把自由文本响应当作执行事实。"""

        normalized = str(value or "").strip().upper()
        return normalized if normalized and _SAFE_CONNECTOR.fullmatch(normalized) else None

    @staticmethod
    def _safe_text(value: Any, limit: int = 256) -> str | None:
        """裁剪字段/表名等低敏标识，并拒绝疑似凭据或 SQL 片段。"""

        text = str(value or "").strip()
        if not text or len(text) > limit or _SUSPICIOUS_TEXT.search(text):
            return None
        return text

    @classmethod
    def _safe_string_list(cls, value: Any, *, limit: int) -> list[str]:
        """把 schema、主键等标识列表裁剪为有限安全字符串。"""

        if not isinstance(value, list):
            return []
        result: list[str] = []
        for item in value[:limit]:
            text = cls._safe_text(item)
            if text and text not in result:
                result.append(text)
        return result

    @staticmethod
    def _safe_bool(value: Any) -> bool | None:
        """只接受布尔字段，避免把任意响应文本当成字段约束。"""

        return value if isinstance(value, bool) else None

    @staticmethod
    def _safe_int(value: Any, *, minimum: int = 0) -> int | None:
        """规范化字段序号等非负整数。"""

        if isinstance(value, bool):
            return None
        try:
            result = int(value)
        except (TypeError, ValueError):
            return None
        return result if result >= minimum else None

    @classmethod
    def _safe_warning_list(cls, value: Any) -> list[str]:
        """只返回短的非敏感提示；怀疑包含 SQL/凭据的提示直接省略。"""

        if not isinstance(value, list):
            return []
        result: list[str] = []
        for item in value[:20]:
            text = cls._safe_text(item, limit=300)
            if text and text not in result:
                result.append(text)
        return result

    @staticmethod
    def _evidence_reference(request: SyncMetadataDiscoveryRequest) -> str:
        """生成不暴露原始项目/数据源值的稳定低敏证据引用。"""

        material = "|".join(
            (
                request.tenant_id,
                request.project_id,
                str(request.datasource_id),
                request.side,
            )
        )
        digest = hashlib.sha256(material.encode("utf-8")).hexdigest()[:32]
        return f"sync-metadata://{digest}"


# 为调用方提供一个按“数据源元数据”命名的别名，同时保持唯一实现和唯一权限边界。
HttpDatasourceMetadataDiscoveryTool = HttpSyncMetadataDiscoveryTool


__all__ = [
    "HttpDatasourceMetadataDiscoveryTool",
    "HttpSyncMetadataDiscoveryTool",
    "METADATA_DISCOVERY_PATH",
    "METADATA_DISCOVERY_WIZARD_PATH",
]
