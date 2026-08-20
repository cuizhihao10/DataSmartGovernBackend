"""把真实同步业务快照转换为受控 GraphRAG 事实候选。

本模块负责“事实物化”，不负责审批，也不负责直接连接 Neo4j。数据同步 Java 服务、
离线导出器或未来 Kafka consumer 可以把低敏业务快照交给本模块，模块会生成：

* 稳定标准实体 ID 和可检索别名；
* 任务、数据源、字段、执行、错误、事故、Runbook 等之间的关系边；
* 每条边的来源文档、来源记录、时间、范围和可信度；
* 一个可以交给 ``ControlledGraphRagIngestor`` 继续校验的 ``PROPOSED`` 事实包。

模型输出不能绕过本模块直接写图。即使未来用模型从运维记录中抽取候选，也必须先
转换成这里定义的结构化快照，再进入审批和摄取门禁。
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass, replace
from datetime import datetime, timezone
from typing import Any, Iterable, Mapping

from datasmart_ai_runtime.services.rag.graph_rag import GraphRagRelation
from datasmart_ai_runtime.services.rag.graph_ingestion import ControlledGraphRagIngestor
from datasmart_ai_runtime.services.rag.models import RagChunkSourceType, RagDocument


BUSINESS_GRAPH_SNAPSHOT_SCHEMA_VERSION = "datasmart.business-graph-snapshot.v1"
BUSINESS_GRAPH_FACT_SCHEMA_VERSION = "datasmart.graph-facts.v1"


class BusinessGraphBuildError(ValueError):
    """业务快照缺少构建图事实所需的稳定字段时抛出的异常。"""


@dataclass(frozen=True)
class BusinessGraphBuildResult:
    """一次业务快照构建结果。

    ``document`` 是待审批的事实候选，不代表已经写入图数据库。
    ``fingerprint`` 用于 permission-admin 审批事实绑定，防止审批后替换事实包。
    """

    document: RagDocument
    fingerprint: str
    entity_count: int
    edge_count: int
    skipped_relation_count: int
    warnings: tuple[str, ...] = ()

    def to_fact_bundle(self) -> dict[str, Any]:
        """输出可供 CLI、对象存储或 Kafka 控制面引用的低敏事实包。"""

        metadata = self.document.metadata
        return {
            "schemaVersion": BUSINESS_GRAPH_FACT_SCHEMA_VERSION,
            "documents": [
                {
                    "documentId": self.document.document_id,
                    "title": self.document.title,
                    "content": self.document.content,
                    "sourceUri": self.document.source_uri,
                    "tenantId": self.document.tenant_id,
                    "applicationId": self.document.application_id,
                    "projectId": self.document.project_id,
                    "sourceType": self.document.source_type.value,
                    "tags": list(self.document.tags),
                    "sensitivityLevel": self.document.sensitivity_level,
                    "metadata": dict(metadata),
                    "graphEntities": list(metadata.get("graphEntities", ())),
                    "graphRelations": list(metadata.get("graphRelations", ())),
                    "enabled": self.document.enabled,
                }
            ],
        }


class BusinessGraphBuilder:
    """从结构化同步业务快照构建图事实候选。

    输入快照只允许包含低敏控制面字段，例如 ID、名称、状态、错误码、版本号和配置
    摘要。连接密码、完整 JDBC URL、SQL 正文、样本数据和 token 不属于该合同，调用方
    应在进入本类前完成脱敏。
    """

    _SECTION_KINDS = {
        "applications": "APPLICATION",
        "projects": "PROJECT",
        "dataSources": "DATASOURCE",
        "datasources": "DATASOURCE",
        "connectors": "CONNECTOR",
        "schemas": "SCHEMA",
        "tables": "TABLE",
        "fields": "FIELD",
        "tasks": "TASK",
        "taskDefinitions": "TASK_DEFINITION",
        "taskVersions": "TASK_VERSION",
        "executions": "EXECUTION",
        "errors": "ERROR",
        "errorSamples": "ERROR",
        "logs": "LOG_EVENT",
        "errorLogs": "LOG_EVENT",
        "incidents": "INCIDENT",
        "runbooks": "RUNBOOK",
        "documents": "DOCUMENT",
        "actions": "RECOVERY_ACTION",
        "checkpoints": "CHECKPOINT",
        "replays": "REPLAY",
        "constraints": "CONSTRAINT",
    }

    def build(self, snapshot: Mapping[str, Any]) -> BusinessGraphBuildResult:
        """校验快照并生成一份尚未审批的图事实候选。"""

        if not isinstance(snapshot, Mapping):
            raise BusinessGraphBuildError("业务图谱快照必须是 JSON 对象。")
        if snapshot.get("schemaVersion") not in {None, BUSINESS_GRAPH_SNAPSHOT_SCHEMA_VERSION}:
            raise BusinessGraphBuildError("业务图谱快照 schemaVersion 不受支持。")

        scope = _mapping(snapshot.get("scope"), "scope")
        tenant_id = _required(scope, "tenantId", "tenant_id")
        application_id = _required(scope, "applicationId", "application_id")
        project_id = _required(scope, "projectId", "project_id")
        snapshot_id = _required(snapshot, "snapshotId", "snapshot_id")
        source_uri = str(snapshot.get("sourceUri") or f"business://datasmart/graph-snapshots/{snapshot_id}").strip()
        as_of = str(snapshot.get("asOf") or snapshot.get("as_of") or _now()).strip()
        document_id = f"business-graph:{snapshot_id}"

        entities: dict[str, dict[str, Any]] = {}
        aliases: dict[str, str] = {}
        warnings: list[str] = []
        skipped = 0

        # 先收集全部实体，再生成关系。这样任务可以引用稍后才出现的 execution、field
        # 或 error，而不会因为输入数组顺序变化产生不同的图事实。
        for section, kind in self._SECTION_KINDS.items():
            for index, item in enumerate(_sequence(snapshot.get(section))):
                if not isinstance(item, Mapping):
                    raise BusinessGraphBuildError(f"{section}[{index}] 必须是对象。")
                raw_id = _entity_raw_id(item, section, index)
                standard_id = _standard_id(kind, raw_id)
                canonical_name = _canonical_name(item, raw_id)
                entity = {
                    "standardId": standard_id,
                    "canonicalName": canonical_name,
                    "aliases": _aliases(item, canonical_name, raw_id),
                    "tenantId": str(item.get("tenantId") or item.get("tenant_id") or tenant_id),
                    "applicationId": str(item.get("applicationId") or item.get("application_id") or application_id),
                    "projectId": str(item.get("projectId") or item.get("project_id") or project_id),
                    "sensitivityLevel": str(item.get("sensitivityLevel") or "internal"),
                    "metadata": {
                        "entityType": kind,
                        "sourceSection": section,
                        "sourceRecordId": raw_id,
                        "status": _safe_summary_value(item.get("status") or item.get("state")),
                        "version": _safe_summary_value(item.get("version") or item.get("configVersion")),
                    },
                }
                existing = entities.get(standard_id)
                if existing is not None and existing != entity:
                    raise BusinessGraphBuildError(f"实体 {standard_id} 在快照中出现矛盾定义。")
                entities[standard_id] = entity
                for alias in entity["aliases"]:
                    aliases.setdefault(_normalize(alias), standard_id)

        def resolve(value: Any, *, kind: str | None = None) -> str | None:
            """把原始 ID、标准 ID 或别名解析为已经收集的标准实体 ID。"""

            if value is None or not str(value).strip():
                return None
            if isinstance(value, Mapping):
                value = next(
                    (
                        value.get(key)
                        for key in ("standardId", "standard_id", "id", "code", "key", "name")
                        if value.get(key) is not None and str(value.get(key)).strip()
                    ),
                    None,
                )
                if value is None:
                    return None
            text = str(value).strip()
            if text in entities:
                return text
            if _normalize(text) in aliases:
                return aliases[_normalize(text)]
            if kind:
                candidate = _standard_id(kind, text)
                if candidate in entities:
                    return candidate
            return None

        edges: list[dict[str, Any]] = []

        def relation(
            source: Any,
            target: Any,
            relation_type: str,
            *,
            section: str,
            record: Mapping[str, Any],
            index: int,
            source_kind: str | None = None,
            target_kind: str | None = None,
        ) -> None:
            """添加一条带来源和时间的关系；端点不完整时记录可诊断警告并跳过。"""

            nonlocal skipped
            source_id = resolve(source, kind=source_kind)
            target_id = resolve(target, kind=target_kind)
            if not source_id or not target_id:
                skipped += 1
                warnings.append(f"{section}[{index}] 的 {relation_type} 缺少可见关系端点。")
                return
            record_id = _entity_raw_id(record, section, index)
            edges.append(
                {
                    "sourceEntityId": source_id,
                    "targetEntityId": target_id,
                    "relation": relation_type,
                    "sourceDocumentId": document_id,
                    "sourceUri": source_uri,
                    "sourceChunkId": f"{section}:{record_id}",
                    "assertedAt": str(record.get("assertedAt") or as_of),
                    "effectiveAt": str(record.get("effectiveAt") or as_of),
                    "expiresAt": record.get("expiresAt"),
                    "confidence": _confidence(record.get("confidence"), default=0.9),
                    "status": str(record.get("status") or "active"),
                    "tenantId": str(record.get("tenantId") or record.get("tenant_id") or tenant_id),
                    "applicationId": str(record.get("applicationId") or record.get("application_id") or application_id),
                    "projectId": str(record.get("projectId") or record.get("project_id") or project_id),
                    "sensitivityLevel": str(record.get("sensitivityLevel") or "internal"),
                }
            )

        # 组织父子关系和资源归属关系。
        for index, item in enumerate(_sequence(snapshot.get("projects"))):
            relation(item.get("applicationId") or application_id, item, GraphRagRelation.APPLICATION_CONTAINS_PROJECT.value,
                     section="projects", record=item, index=index, source_kind="APPLICATION", target_kind="PROJECT")
        for section in ("dataSources", "datasources", "tasks", "runbooks", "incidents"):
            for index, item in enumerate(_sequence(snapshot.get(section))):
                relation(item.get("projectId") or project_id, item, GraphRagRelation.PROJECT_OWNS_RESOURCE.value,
                         section=section, record=item, index=index, source_kind="PROJECT", target_kind=self._SECTION_KINDS[section])

        for section in ("schemas", "tables", "fields"):
            parent_key = {"schemas": "dataSourceId", "tables": "schemaId", "fields": "tableId"}[section]
            parent_kind = {"schemas": "DATASOURCE", "tables": "SCHEMA", "fields": "TABLE"}[section]
            rel_type = {
                "schemas": GraphRagRelation.DATASOURCE_CONTAINS.value,
                "tables": GraphRagRelation.SCHEMA_CONTAINS_TABLE.value,
                "fields": GraphRagRelation.TABLE_HAS_FIELD.value,
            }[section]
            for index, item in enumerate(_sequence(snapshot.get(section))):
                relation(item.get(parent_key), item, rel_type, section=section, record=item, index=index,
                         source_kind=parent_kind, target_kind=self._SECTION_KINDS[section])

        for index, item in enumerate(_sequence(snapshot.get("connectors"))):
            for source in _values(item, "dataSourceId", "dataSourceIds", "datasourceId", "datasourceIds"):
                relation(source, item, GraphRagRelation.DATASOURCE_CONTAINS.value, section="connectors", record=item,
                         index=index, source_kind="DATASOURCE", target_kind="CONNECTOR")

        for index, item in enumerate(_sequence(snapshot.get("tasks"))):
            for source in _values(item, "sourceDataSourceId", "sourceDatasourceId", "sourceId"):
                relation(item, source, GraphRagRelation.TASK_USES_DATASOURCE.value, section="tasks", record=item,
                         index=index, source_kind="TASK", target_kind="DATASOURCE")
            for target in _values(item, "targetDataSourceId", "targetDatasourceId", "targetId"):
                relation(item, target, GraphRagRelation.TASK_USES_DATASOURCE.value, section="tasks", record=item,
                         index=index, source_kind="TASK", target_kind="DATASOURCE")
            for table in _values(item, "sourceTableId", "targetTableId", "tableId", "tableIds"):
                relation(item, table, GraphRagRelation.TASK_SYNCS_TABLE.value, section="tasks", record=item,
                         index=index, source_kind="TASK", target_kind="TABLE")
            successful_version = item.get("successfulVersionId") or item.get("lastSuccessfulVersionId")
            if successful_version is not None:
                relation(item, successful_version, GraphRagRelation.TASK_HAS_SUCCESSFUL_VERSION.value, section="tasks",
                         record=item, index=index, source_kind="TASK", target_kind="TASK_VERSION")

        for index, item in enumerate(_sequence(snapshot.get("taskVersions"))):
            relation(item.get("taskId"), item, GraphRagRelation.TASK_HAS_VERSION.value, section="taskVersions",
                     record=item, index=index, source_kind="TASK", target_kind="TASK_VERSION")

        for index, item in enumerate(_sequence(snapshot.get("mappings"))):
            relation(item.get("sourceFieldId"), item.get("targetFieldId"), GraphRagRelation.FIELD_MAPS_TO.value,
                     section="mappings", record=item, index=index, source_kind="FIELD", target_kind="FIELD")

        for index, item in enumerate(_sequence(snapshot.get("executions"))):
            relation(item.get("taskId"), item, "TASK_HAS_EXECUTION", section="executions", record=item, index=index,
                     source_kind="TASK", target_kind="EXECUTION")
            for error in _values(item, "errorId", "errorIds"):
                relation(item, error, GraphRagRelation.EXECUTION_FAILED_WITH.value, section="executions", record=item,
                         index=index, source_kind="EXECUTION", target_kind="ERROR")
            for checkpoint in _values(item, "checkpointId", "checkpointIds"):
                relation(item, checkpoint, GraphRagRelation.EXECUTION_HAS_CHECKPOINT.value, section="executions", record=item,
                         index=index, source_kind="EXECUTION", target_kind="CHECKPOINT")

            for log in _values(item, "logId", "logIds"):
                relation(item, log, GraphRagRelation.EXECUTION_HAS_LOG.value, section="executions", record=item,
                         index=index, source_kind="EXECUTION", target_kind="LOG_EVENT")

        for section in ("logs", "errorLogs"):
            for index, item in enumerate(_sequence(snapshot.get(section))):
                for execution in _values(item, "executionId", "executionIds"):
                    relation(execution, item, GraphRagRelation.EXECUTION_HAS_LOG.value, section=section, record=item,
                             index=index, source_kind="EXECUTION", target_kind="LOG_EVENT")
                for error in _values(item, "errorId", "errorIds"):
                    relation(item, error, GraphRagRelation.LOG_MATCHES_ERROR.value, section=section, record=item,
                             index=index, source_kind="LOG_EVENT", target_kind="ERROR")

        for index, item in enumerate(_sequence(snapshot.get("fields"))):
            for constraint in _values(item, "constraintId", "constraintIds"):
                relation(item, constraint, GraphRagRelation.FIELD_HAS_CONSTRAINT.value, section="fields", record=item,
                         index=index, source_kind="FIELD", target_kind="CONSTRAINT")
        for index, item in enumerate(_sequence(snapshot.get("constraints"))):
            for field in _values(item, "fieldId", "fieldIds"):
                relation(field, item, GraphRagRelation.FIELD_HAS_CONSTRAINT.value, section="constraints", record=item,
                         index=index, source_kind="FIELD", target_kind="CONSTRAINT")

        for index, item in enumerate(_sequence(snapshot.get("incidents"))):
            for error in _values(item, "errorId", "errorIds"):
                relation(item, error, GraphRagRelation.INCIDENT_DOCUMENTS_ERROR.value, section="incidents", record=item,
                         index=index, source_kind="INCIDENT", target_kind="ERROR")
        for index, item in enumerate(_sequence(snapshot.get("runbooks"))):
            for action in _values(item, "actionId", "actionIds", "recommendedAction", "recommendedActions"):
                relation(item, action, GraphRagRelation.RUNBOOK_RECOMMENDS_ACTION.value, section="runbooks", record=item,
                         index=index, source_kind="RUNBOOK", target_kind="RECOVERY_ACTION")
        for index, item in enumerate(_sequence(snapshot.get("replays"))):
            for error in _values(item, "errorId", "errorIds"):
                relation(item, error, GraphRagRelation.EXECUTION_REPLAYS_ERROR.value, section="replays", record=item,
                         index=index, source_kind="REPLAY", target_kind="ERROR")
        for index, item in enumerate(_sequence(snapshot.get("dependencies"))):
            relation(item.get("taskId"), item.get("dependsOnTaskId"), GraphRagRelation.TASK_DEPENDS_ON_TASK.value,
                     section="dependencies", record=item, index=index, source_kind="TASK", target_kind="TASK")

        metadata = {
            "sourceStatus": str(snapshot.get("sourceStatus") or "COMPLETE"),
            "graphIngestionApproval": {
                "status": "PROPOSED",
                "approvalId": str(snapshot.get("approvalFactId") or ""),
            },
            "graphBuild": {
                "schemaVersion": BUSINESS_GRAPH_SNAPSHOT_SCHEMA_VERSION,
                "snapshotId": snapshot_id,
                "sourceRecordRef": snapshot_id,
                "generatedAt": _now(),
                "entityCount": len(entities),
                "edgeCount": len(edges),
                "skippedRelationCount": skipped,
                "warnings": tuple(warnings[:50]),
            },
            "graphEntities": tuple(entities.values()),
            "graphRelations": tuple(edges),
        }
        document = RagDocument(
            document_id=document_id,
            title=f"DataSmart 业务图谱快照 {snapshot_id}",
            content="由数据同步控制面生成的低敏业务事实快照，具体引用通过 sourceChunkId 回查。",
            source_uri=source_uri,
            tenant_id=tenant_id,
            application_id=application_id,
            project_id=project_id,
            source_type=RagChunkSourceType.METADATA,
            tags=("business-graph", "data-sync", "proposed"),
            sensitivity_level="internal",
            metadata=metadata,
        )
        # 审批前的候选仍然使用与真正摄取完全相同的规范化指纹。这里临时把状态复制为
        # APPROVED 仅用于复用校验器计算指纹，不会改变返回文档的 PROPOSED 状态，也不会写数据库。
        fingerprint_document = replace(
            document,
            metadata={
                **metadata,
                "graphIngestionApproval": {
                    "status": "APPROVED",
                    "approvalId": "fingerprint-preview",
                },
            },
        )
        fingerprint = ControlledGraphRagIngestor().validate_documents((fingerprint_document,)).fingerprint
        return BusinessGraphBuildResult(
            document=document,
            fingerprint=fingerprint,
            entity_count=len(entities),
            edge_count=len(edges),
            skipped_relation_count=skipped,
            warnings=tuple(warnings[:50]),
        )


def _mapping(value: Any, name: str) -> Mapping[str, Any]:
    if value is None:
        return {}
    if not isinstance(value, Mapping):
        raise BusinessGraphBuildError(f"{name} 必须是对象。")
    return value


def _sequence(value: Any) -> tuple[Any, ...]:
    if value is None:
        return ()
    if not isinstance(value, (list, tuple)):
        raise BusinessGraphBuildError("业务图谱快照中的集合字段必须是数组。")
    return tuple(value)


def _required(mapping: Mapping[str, Any], *keys: str) -> str:
    for key in keys:
        value = mapping.get(key)
        if value is not None and str(value).strip():
            return str(value).strip()
    raise BusinessGraphBuildError(f"缺少必填字段：{keys[0]}。")


def _entity_raw_id(item: Mapping[str, Any], section: str, index: int) -> str:
    for key in ("standardId", "standard_id", "id", "code", "key", "name"):
        value = item.get(key)
        if value is not None and str(value).strip():
            return str(value).strip()
    raise BusinessGraphBuildError(f"{section}[{index}] 缺少稳定 ID。")


def _standard_id(kind: str, raw_id: Any) -> str:
    value = str(raw_id).strip()
    return value if value.startswith(f"{kind.lower()}:") else f"{kind.lower()}:{value}"


def _canonical_name(item: Mapping[str, Any], fallback: str) -> str:
    for key in ("canonicalName", "canonical_name", "displayName", "name", "code", "title"):
        value = item.get(key)
        if value is not None and str(value).strip():
            return str(value).strip()
    return fallback


def _aliases(item: Mapping[str, Any], canonical_name: str, raw_id: str) -> tuple[str, ...]:
    values: list[str] = [canonical_name, raw_id]
    aliases = item.get("aliases")
    if isinstance(aliases, str):
        values.append(aliases)
    elif isinstance(aliases, (list, tuple, set)):
        values.extend(str(value) for value in aliases)
    for key in ("code", "taskCode", "errorCode", "fieldName", "tableName"):
        if item.get(key) is not None:
            values.append(str(item[key]))
    return tuple(dict.fromkeys(value.strip() for value in values if str(value).strip()))


def _values(item: Mapping[str, Any], *keys: str) -> tuple[Any, ...]:
    result: list[Any] = []
    for key in keys:
        value = item.get(key)
        if isinstance(value, (list, tuple, set)):
            result.extend(value)
        elif value is not None and str(value).strip():
            result.append(value)
    return tuple(dict.fromkeys(result))


def _confidence(value: Any, *, default: float) -> float:
    try:
        parsed = float(value if value is not None else default)
    except (TypeError, ValueError):
        parsed = default
    return max(0.0, min(1.0, parsed))


def _safe_summary_value(value: Any) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    return text[:128] if text else None


def _normalize(value: Any) -> str:
    return "".join(str(value or "").casefold().split())


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _fingerprint(entities: Iterable[Mapping[str, Any]], edges: Iterable[Mapping[str, Any]]) -> str:
    payload = json.dumps(
        {"entities": list(entities), "edges": list(edges)},
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


__all__ = [
    "BUSINESS_GRAPH_FACT_SCHEMA_VERSION",
    "BUSINESS_GRAPH_SNAPSHOT_SCHEMA_VERSION",
    "BusinessGraphBuildError",
    "BusinessGraphBuildResult",
    "BusinessGraphBuilder",
]
