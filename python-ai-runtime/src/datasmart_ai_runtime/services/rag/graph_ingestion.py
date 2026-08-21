"""受控的 GraphRAG 图事实摄取。

本模块解决的是“文档事实如何安全地进入 Neo4j”，不是自然语言问答本身。
它刻意不对任意文档正文做自由文本关系抽取，也不允许模型直接写图。正确的
生产流程是：文档先经过授权和持久化，模型或规则只能生成候选图事实，人工或
上游治理流程将候选事实标记为 ``APPROVED``，本模块再校验范围、来源、时间、
稳定 ID 和矛盾关系，最后调用 GraphRAG Provider 幂等写入。

输入使用 ``RagDocument.metadata`` 中的两个结构化字段：

``graphEntities``
    实体标准 ID、规范名和别名。
``graphRelations``
    关系端点、关系类型、来源文档、来源 URI、来源 chunk、时间、可信度和范围。

这样既可以把事实放在文档 Manifest 的结构化元数据中，也可以使用本仓库提供的
JSON 事实包先构造 ``RagDocument``。二者共享同一套校验逻辑，避免 CLI、API 和
未来 Kafka 摄取消费者产生不同的安全语义。
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable, Mapping, Sequence

from datasmart_ai_runtime.services.rag.graph_rag import (
    GraphRagEdge,
    GraphRagEntity,
    GraphRagRelation,
)
from datasmart_ai_runtime.services.rag.models import RagChunkSourceType, RagDocument


GRAPH_FACT_BUNDLE_SCHEMA_VERSION = "datasmart.graph-facts.v1"
"""受控图事实包的稳定版本号。"""

GRAPH_FACT_APPROVED_STATUS = "APPROVED"
"""只有已审批的事实提案才能进入 Neo4j。"""

CONTROLLED_BUSINESS_GRAPH_RELATIONS = frozenset(item.value for item in GraphRagRelation)
"""业务图谱允许的关系白名单。

模型或导入文件不能通过随意拼接关系名扩大图谱语义。关系白名单只描述已经有稳定
来源、时间和范围字段的业务事实；自然语言查询是否支持某种关系，仍由 GraphRAG
查询解析器单独决定。
"""

GraphRagIngestionError = ValueError


@dataclass(frozen=True)
class GraphRagIngestionResult:
    """一次摄取的低敏结果。

    结果只保存数量和指纹，不保存文档正文、实体名称、数据库地址或凭据，
    便于写入审计日志和 CI 产物。
    """

    status: str
    document_count: int
    entity_count: int
    edge_count: int
    fingerprint: str

    def to_dict(self) -> dict[str, Any]:
        """返回命令行和审计事件使用的 camelCase 摘要。"""

        return {
            "status": self.status,
            "documentCount": self.document_count,
            "entityCount": self.entity_count,
            "edgeCount": self.edge_count,
            "fingerprint": self.fingerprint,
            "payloadPolicy": "GRAPH_INGEST_SUMMARY_NO_DOCUMENT_CONTENT_OR_SECRET",
        }


@dataclass(frozen=True)
class _ValidatedGraphFacts:
    """全量校验成功后、可以写入 Provider 的不可变事实集合。"""

    documents: tuple[RagDocument, ...]
    entities: tuple[GraphRagEntity, ...]
    edges: tuple[GraphRagEdge, ...]
    fingerprint: str


class ControlledGraphRagIngestor:
    """执行“先全量校验、后统一写入”的 GraphRAG 摄取流程。

    调用方必须传入一个具备 ``upsert_entities`` 和 ``upsert_edges`` 方法的 Provider。
    当前 Neo4j Provider 的两个写入接口都使用稳定 MERGE 键，因此重复执行同一
    事实包不会生成重复实体或重复关系。任何校验错误都会在写入前抛出，避免把
    一个半正确的文档包悄悄落进图数据库。
    """

    def validate_documents(self, documents: Iterable[RagDocument]) -> _ValidatedGraphFacts:
        """校验文档授权、结构化图事实、范围和关系冲突。

        这里不根据正文猜测关系。若以后接入 LLM 抽取器，它的输出也必须先转换
        成 ``RagDocument.metadata``，再经过本方法；因此模型不会绕开来源和权限门禁。
        """

        document_values = tuple(documents)
        if not document_values:
            raise GraphRagIngestionError("图事实摄取至少需要一份来源文档。")

        entities_by_id: dict[str, GraphRagEntity] = {}
        edges_by_identity: dict[str, GraphRagEdge] = {}
        all_edges: list[GraphRagEdge] = []

        for document in document_values:
            self._validate_document_approval(document)
            raw_entities = _sequence(document.metadata, "graphEntities", "graph_entities")
            raw_relations = _sequence(document.metadata, "graphRelations", "graph_relations")

            for index, raw_entity in enumerate(raw_entities):
                entity = self._entity_from_document(document, raw_entity, index=index)
                existing = entities_by_id.get(entity.standard_id)
                if existing is not None and existing != entity:
                    raise GraphRagIngestionError(
                        f"标准实体 ID {entity.standard_id} 在事实包中对应了互相矛盾的定义。"
                    )
                entities_by_id[entity.standard_id] = entity

            for index, raw_relation in enumerate(raw_relations):
                edge = self._edge_from_document(document, raw_relation, index=index)
                identity = _edge_identity(edge)
                existing = edges_by_identity.get(identity)
                if existing is not None and existing != edge:
                    raise GraphRagIngestionError("同一关系事实的来源身份发生冲突。")
                if existing is None:
                    edges_by_identity[identity] = edge
                    all_edges.append(edge)

        if not entities_by_id and all_edges:
            raise GraphRagIngestionError("关系事实不能在没有实体定义的情况下写入。")
        missing_endpoint_ids = sorted(
            {
                endpoint
                for edge in all_edges
                for endpoint in (edge.source_entity_id, edge.target_entity_id)
                if endpoint not in entities_by_id
            }
        )
        if missing_endpoint_ids:
            raise GraphRagIngestionError(
                "关系端点没有在同一受控事实包中定义：" + ", ".join(missing_endpoint_ids)
            )

        self._validate_current_conflicts(all_edges)
        entities = tuple(sorted(entities_by_id.values(), key=lambda item: item.standard_id))
        edges = tuple(sorted(all_edges, key=_edge_identity))
        fingerprint = _facts_fingerprint(entities, edges)
        return _ValidatedGraphFacts(document_values, entities, edges, fingerprint)

    def ingest(
        self,
        documents: Iterable[RagDocument],
        provider: Any,
        *,
        dry_run: bool = False,
        expected_fingerprint: str | None = None,
        authoritative_approval_fact_id: str | None = None,
    ) -> GraphRagIngestionResult:
        """校验并按实体后关系的顺序写入 GraphRAG Provider。

        ``dry_run=True`` 只执行全部校验和指纹计算，不接触数据库。真实写入前应
        先执行一次 dry-run，并由调用方记录审批号、操作者和指纹；重复写入同一
        指纹是安全的，便于任务重试和灾备重放。
        """

        facts = self.validate_documents(documents)
        if expected_fingerprint and facts.fingerprint != expected_fingerprint:
            raise GraphRagIngestionError("事实包指纹与服务端审批事实不一致，拒绝摄取。")
        if authoritative_approval_fact_id:
            for document in facts.documents:
                if _approval_id(document) != authoritative_approval_fact_id:
                    raise GraphRagIngestionError("来源文档审批事实与服务端审批事实不一致。")
        if not dry_run:
            upsert_entities = getattr(provider, "upsert_entities", None)
            upsert_edges = getattr(provider, "upsert_edges", None)
            if not callable(upsert_entities) or not callable(upsert_edges):
                raise GraphRagIngestionError("当前 GraphRAG Provider 不支持受控图数据写入。")
            # 先写节点，再写边；Neo4j 的关系语句会 MATCH 两端节点，避免悬空边。
            upsert_entities(facts.entities)
            upsert_edges(facts.edges)

        return GraphRagIngestionResult(
            status="VALIDATED_NOT_WRITTEN" if dry_run else "INGESTED",
            document_count=len(facts.documents),
            entity_count=len(facts.entities),
            edge_count=len(facts.edges),
            fingerprint=facts.fingerprint,
        )

    @staticmethod
    def _validate_document_approval(document: RagDocument) -> None:
        """检查来源文档是否明确完成图事实审批和内容完整性标记。"""

        metadata = document.metadata
        approval = metadata.get("graphIngestionApproval") or metadata.get("graph_ingestion_approval")
        if not isinstance(approval, Mapping) or str(approval.get("status", "")).upper() != GRAPH_FACT_APPROVED_STATUS:
            raise GraphRagIngestionError(
                f"来源文档 {document.document_id} 没有 APPROVED 图事实审批状态。"
            )
        if not str(approval.get("approvalId") or approval.get("approval_id") or "").strip():
            raise GraphRagIngestionError(f"来源文档 {document.document_id} 缺少图事实审批编号。")
        if str(metadata.get("sourceStatus") or "").upper() != "COMPLETE":
            raise GraphRagIngestionError(f"来源文档 {document.document_id} 的 sourceStatus 不是 COMPLETE。")
        if not document.source_uri.strip():
            raise GraphRagIngestionError(f"来源文档 {document.document_id} 缺少 sourceUri。")

    @staticmethod
    def _entity_from_document(document: RagDocument, raw: Any, *, index: int) -> GraphRagEntity:
        """把一条结构化实体事实映射为内部实体，并锁定到文档范围。"""

        item = _mapping(raw, f"graphEntities[{index}]")
        standard_id = _required(item, "standardId", "standard_id")
        canonical_name = _required(item, "canonicalName", "canonical_name", "name")
        tenant, application, project, workspace, sensitivity = _scope_from_item(document, item)
        metadata = item.get("metadata")
        if metadata is not None and not isinstance(metadata, Mapping):
            raise GraphRagIngestionError(f"实体 {standard_id} 的 metadata 必须是对象。")
        entity_metadata = dict(metadata or {})
        entity_metadata.update(
            {
                "sourceDocumentId": document.document_id,
                "sourceUri": document.source_uri,
                "graphFactApprovalId": _approval_id(document),
            }
        )
        return GraphRagEntity(
            standard_id=standard_id,
            canonical_name=canonical_name,
            aliases=_text_sequence(item, "aliases"),
            tenant=tenant,
            application=application,
            project=project,
            workspace=workspace,
            sensitivity=sensitivity,
            metadata=entity_metadata,
        )

    @staticmethod
    def _edge_from_document(document: RagDocument, raw: Any, *, index: int) -> GraphRagEdge:
        """把一条关系事实映射为内部边，并强制继承来源文档的身份和范围。"""

        item = _mapping(raw, f"graphRelations[{index}]")
        source_id = _required(item, "sourceEntityId", "source_entity_id", "sourceId")
        target_id = _required(item, "targetEntityId", "target_entity_id", "targetId")
        relation = _required(item, "relation", "relationType", "relation_type")
        source_document_id = _required(item, "sourceDocumentId", "source_document_id")
        source_uri = _required(item, "sourceUri", "source_uri")
        source_chunk_id = _required(item, "sourceChunkId", "source_chunk_id")
        if source_document_id != document.document_id or source_uri != document.source_uri:
            raise GraphRagIngestionError(
                f"关系 {source_id}->{target_id} 不能把来源绑定到当前文档之外。"
            )
        asserted_at = _required(item, "assertedAt", "asserted_at")
        effective_at = _required(item, "effectiveAt", "effective_at")
        tenant, application, project, workspace, sensitivity = _scope_from_item(document, item)
        edge = GraphRagEdge(
            source_entity_id=source_id,
            target_entity_id=target_id,
            relation=relation,
            source_document_id=source_document_id,
            source_uri=source_uri,
            source_chunk_id=source_chunk_id,
            asserted_at=asserted_at,
            effective_at=effective_at,
            expires_at=item.get("expiresAt", item.get("expires_at")),
            confidence=item.get("confidence", 1.0),
            status=item.get("status", "active"),
            tenant=tenant,
            application=application,
            project=project,
            workspace=workspace,
            sensitivity=sensitivity,
        )
        if edge.relation not in CONTROLLED_BUSINESS_GRAPH_RELATIONS:
            raise GraphRagIngestionError(
                f"当前受控摄取不允许关系 {edge.relation}，必须使用业务图谱关系白名单。"
            )
        if not edge.has_complete_provenance():
            raise GraphRagIngestionError("关系事实必须包含 sourceDocumentId、sourceUri 和 sourceChunkId。")
        return edge

    @staticmethod
    def _validate_current_conflicts(edges: Sequence[GraphRagEdge]) -> None:
        """拒绝同一范围同一时刻指向多个不同目标的当前关系。"""

        grouped: dict[tuple[str, str, str, str, str], set[str]] = {}
        now = datetime.now(timezone.utc)
        for edge in edges:
            # 只有 REPORTS_TO 当前要求“一个主体不能同时指向多个当前目标”。
            # 业务资源关系天然是一对多，例如一个项目拥有多个数据源、一个 schema 包含多张表，
            # 不能把所有关系都套用组织汇报关系的冲突规则。
            if edge.relation != GraphRagRelation.REPORTS_TO.value:
                continue
            if not edge.is_current(now):
                continue
            key = (
                edge.source_entity_id,
                edge.relation,
                edge.tenant,
                edge.application,
                edge.project,
            )
            grouped.setdefault(key, set()).add(edge.target_entity_id)
        conflicts = [key for key, targets in grouped.items() if len(targets) > 1]
        if conflicts:
            source, relation, tenant, application, project = conflicts[0]
            raise GraphRagIngestionError(
                f"当前关系存在冲突：{source} 在 {tenant}/{application}/{project} 的 {relation} 指向多个目标。"
            )


def load_graph_fact_documents(path: str | Path) -> tuple[RagDocument, ...]:
    """从 JSON/JSONL 事实包构造文档对象，不读取网络、不读取凭据。

    JSON 根节点格式为 ``{schemaVersion, documents: [...]}``；每个 document 必须
    携带来源文档身份、范围、审批元数据和显式 ``graphEntities``/``graphRelations``。
    ``content`` 只是来源摘要，可为空，真正的引用仍由每条关系的 sourceChunkId
    和 sourceUri 提供。
    """

    resolved = Path(path).resolve()
    try:
        raw = resolved.read_bytes()
    except (OSError, UnicodeDecodeError) as exc:
        raise GraphRagIngestionError("图事实包无法读取或解析。") from exc
    return load_graph_fact_documents_bytes(raw)


def load_graph_fact_documents_bytes(payload_bytes: bytes) -> tuple[RagDocument, ...]:
    """从已经由受控对象存储加载的 JSON 字节构造事实文档。

    <p>Kafka consumer 不应把 MinIO 对象复制到宿主机长期目录，也不应把事实正文塞进 Kafka。
    这个入口允许 worker 在内存中完成对象读取、schema 校验和后续指纹校验，保留对象存储作为
    原始事实包的耐久证据源。</p>
    """

    try:
        payload = json.loads(bytes(payload_bytes).decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError, TypeError) as exc:
        raise GraphRagIngestionError("图事实包无法读取或解析。") from exc
    if not isinstance(payload, Mapping) or payload.get("schemaVersion") != GRAPH_FACT_BUNDLE_SCHEMA_VERSION:
        raise GraphRagIngestionError("图事实包 schemaVersion 不受支持。")
    raw_documents = payload.get("documents")
    if not isinstance(raw_documents, list) or not raw_documents:
        raise GraphRagIngestionError("图事实包必须包含非空 documents 数组。")
    documents: list[RagDocument] = []
    seen_ids: set[str] = set()
    for index, raw in enumerate(raw_documents):
        item = _mapping(raw, f"documents[{index}]")
        document_id = _required(item, "documentId", "document_id")
        if document_id in seen_ids:
            raise GraphRagIngestionError(f"图事实包存在重复 documentId：{document_id}。")
        seen_ids.add(document_id)
        metadata = item.get("metadata")
        if not isinstance(metadata, Mapping):
            raise GraphRagIngestionError(f"文档 {document_id} 的 metadata 必须是对象。")
        metadata = {
            **dict(metadata),
            "graphEntities": item.get("graphEntities", metadata.get("graphEntities", ())),
            "graphRelations": item.get("graphRelations", metadata.get("graphRelations", ())),
        }
        documents.append(
            RagDocument(
                document_id=document_id,
                title=_required(item, "title"),
                content=str(item.get("content") or ""),
                source_uri=_required(item, "sourceUri", "source_uri"),
                tenant_id=_required(item, "tenantId", "tenant_id"),
                application_id=_required(item, "applicationId", "application_id", default="*"),
                project_id=_required(item, "projectId", "project_id"),
                # workspaceKey 是旧事实包字段；applicationId 才是当前产品合同的应用边界。
                # 读取旧包时保留通配值，避免历史数据阻塞迁移；新事实包无需提供该字段。
                workspace_key=_required(item, "workspaceKey", "workspace_key", default="*"),
                source_type=RagChunkSourceType(
                    _required(item, "sourceType", "source_type", default=RagChunkSourceType.METADATA.value)
                ),
                tags=_text_sequence(item, "tags"),
                sensitivity_level=_required(item, "sensitivityLevel", "sensitivity_level", default="internal"),
                metadata=metadata,
                enabled=bool(item.get("enabled", True)),
            )
        )
    return tuple(documents)


def _scope_from_item(document: RagDocument, item: Mapping[str, Any]) -> tuple[str, str, str, str, str]:
    """读取事实范围并确保它不能扩大所属文档的授权范围。

    返回值中最后的 workspace 仅服务于旧内存对象兼容；授权判断和新事实指纹
    都不再使用它，真实产品范围固定为 tenant/application/project/sensitivity。
    """

    values = (
        (_first(item, "tenantId", "tenant_id", default=document.tenant_id), document.tenant_id, "tenantId"),
        (_first(item, "applicationId", "application_id", default=document.application_id), document.application_id, "applicationId"),
        (_first(item, "projectId", "project_id", default=document.project_id), document.project_id, "projectId"),
        (_first(item, "workspaceKey", "workspace_key", default=document.workspace_key), document.workspace_key, "workspaceKey"),
    )
    for actual, document_value, field in values:
        # 私有来源文档不能被事实改成全局范围；全局来源允许事实继续使用全局范围，
        # 也允许在明确业务规则下收窄到一个具体范围。
        if field == "workspaceKey":
            # 旧字段不再是产品授权维度，只在反序列化时保留，不参与范围扩大校验。
            continue
        if document_value != "*" and actual != document_value:
            raise GraphRagIngestionError(f"图事实 {field} 超出了来源文档范围。")
    sensitivity = str(_first(item, "sensitivityLevel", "sensitivity_level", default=document.sensitivity_level)).strip()
    if sensitivity.casefold() != document.sensitivity_level.casefold():
        raise GraphRagIngestionError("图事实敏感级别不能高于或改写来源文档敏感级别。")
    return values[0][0], values[1][0], values[2][0], values[3][0], sensitivity


def _facts_fingerprint(entities: Sequence[GraphRagEntity], edges: Sequence[GraphRagEdge]) -> str:
    """按排序后的结构化事实计算可重放指纹。"""

    # approvalFactId 是授权事实，不是图事实本身。若把它放入指纹，审批服务在
    # 候选由 PROPOSED 变为 APPROVED 时会导致同一批实体关系产生不同指纹，
    # 从而无法证明“审批的就是即将写入的那批事实”。
    stable_entities = []
    for entity in entities:
        value = entity.to_dict()
        metadata = dict(value.get("metadata") or {})
        metadata.pop("graphFactApprovalId", None)
        value["metadata"] = metadata
        stable_entities.append(value)
    payload = json.dumps(
        {
            "entities": stable_entities,
            "edges": [edge.to_dict() for edge in edges],
        },
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def _edge_identity(edge: GraphRagEdge) -> str:
    """返回与 Neo4j Provider 一致的来源级幂等身份。"""

    return "|".join(
        (
            edge.source_entity_id,
            edge.target_entity_id,
            edge.relation,
            edge.source_document_id,
            edge.source_uri,
            edge.source_chunk_id,
            str(edge.asserted_at),
        )
    )


def _mapping(value: Any, field_name: str) -> Mapping[str, Any]:
    """检查结构化数组中的对象类型。"""

    if not isinstance(value, Mapping):
        raise GraphRagIngestionError(f"{field_name} 必须是对象。")
    return value


def _sequence(mapping: Mapping[str, Any], *keys: str) -> tuple[Any, ...]:
    """读取结构化数组，允许缺省为空但拒绝错误类型。"""

    value = _first(mapping, *keys, default=())
    if value is None:
        return ()
    if not isinstance(value, (list, tuple)):
        raise GraphRagIngestionError(f"{keys[0]} 必须是数组。")
    return tuple(value)


def _text_sequence(mapping: Mapping[str, Any], key: str) -> tuple[str, ...]:
    """规范化别名或标签数组。"""

    value = mapping.get(key, ())
    if value is None:
        return ()
    if isinstance(value, str):
        value = (value,)
    if not isinstance(value, (list, tuple)):
        raise GraphRagIngestionError(f"{key} 必须是字符串数组。")
    return tuple(dict.fromkeys(str(item).strip() for item in value if str(item).strip()))


def _required(mapping: Mapping[str, Any], *keys: str, default: Any = None) -> str:
    """从 camelCase/snake_case 兼容字段读取必填文本。"""

    value = _first(mapping, *keys, default=default)
    text = str(value or "").strip()
    if not text:
        raise GraphRagIngestionError(f"缺少必填字段：{keys[0]}。")
    return text


def _first(mapping: Mapping[str, Any], *keys: str, default: Any = None) -> Any:
    """返回第一个存在的字段，避免把合法的空数组误判成缺失。"""

    for key in keys:
        if key in mapping:
            return mapping[key]
    return default


def _approval_id(document: RagDocument) -> str:
    """读取已经由审批校验过的审批号。"""

    approval = document.metadata.get("graphIngestionApproval") or document.metadata.get("graph_ingestion_approval")
    return str(approval.get("approvalId") or approval.get("approval_id") or "") if isinstance(approval, Mapping) else ""


__all__ = [
    "GRAPH_FACT_APPROVED_STATUS",
    "GRAPH_FACT_BUNDLE_SCHEMA_VERSION",
    "ControlledGraphRagIngestor",
    "GraphRagIngestionError",
    "GraphRagIngestionResult",
    "load_graph_fact_documents",
    "load_graph_fact_documents_bytes",
]
