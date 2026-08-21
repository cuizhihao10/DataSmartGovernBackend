"""Neo4j GraphRAG 适配器。

本文件只负责把 Neo4j 中的实体节点和关系边读取为项目内部的 GraphRAG
对象。真正的别名消歧、时间有效性、冲突拒答、跳数限制和引用链生成仍由
`graph_rag.py` 中的受治理核心负责，避免不同存储实现出现不同的安全语义。

Neo4j Python Driver 是可选依赖。没有安装 Driver 或没有配置 GraphRAG 时，
工厂会返回 fail-closed Provider；上层 API 会明确告诉调用方当前关系数据源
不可用，而不会使用普通向量文档猜测关系答案。
"""

from __future__ import annotations

import hashlib
import json
import os
from dataclasses import dataclass
from typing import Any, Callable, Iterable, Mapping
from urllib.parse import urlsplit

from datasmart_ai_runtime.services.rag.graph_rag import (
    MAX_GRAPH_RAG_HOPS,
    GraphRagEdge,
    GraphRagEntity,
    GraphRagParsedQuestion,
    GraphRagPathStep,
    GraphRagProvider,
    GraphRagQuery,
    GraphRagReasonCode,
    GraphRagRelation,
    GraphRagResult,
    GraphRagResultStatus,
    InMemoryGraphRag,
    _normalize_alias,
    _semantic_alias_score,
    parse_graph_rag_question,
)


GraphDatabaseDriverFactory = Callable[..., Any]


@dataclass(frozen=True)
class GraphRagNeo4jSettings:
    """Neo4j GraphRAG 连接和查询边界。

    `password` 只在进程内传递给 Neo4j Driver，诊断、异常和评测报告都不会
    序列化它。`max_edges_per_hop` 用于防止一个实体拥有异常多的关系时把
    一次查询扩展成不可控的全图扫描。
    """

    provider_type: str = "disabled"
    uri: str = ""
    username: str = ""
    password: str = ""
    database: str = "neo4j"
    connect_timeout_seconds: int = 5
    max_edges_per_hop: int = 128
    max_hops: int = MAX_GRAPH_RAG_HOPS
    initialize_schema: bool = False

    @property
    def enabled(self) -> bool:
        """判断是否显式启用 Neo4j GraphRAG。"""

        return self.provider_type == "neo4j"

    def to_diagnostics(self) -> dict[str, Any]:
        """输出不包含 URI 凭据和密码的低敏诊断。"""

        return {
            "provider": self.provider_type,
            "configured": bool(self.uri and self.username and self.password),
            "databaseConfigured": bool(self.database),
            "connectTimeoutSeconds": self.connect_timeout_seconds,
            "maxEdgesPerHop": self.max_edges_per_hop,
            "maxHops": self.max_hops,
            "initializeSchema": self.initialize_schema,
        }


class UnavailableGraphRagProvider:
    """配置或依赖不完整时使用的明确拒答 Provider。"""

    def __init__(self, *, reason_code: str, message: str) -> None:
        """保存稳定原因码和面向调用方的安全提示。"""

        self._reason_code = reason_code
        self._message = message

    def query(self, query: GraphRagQuery) -> GraphRagResult:
        """拒绝查询，不访问任何外部系统，也不泄露配置细节。"""

        return GraphRagResult(
            status=GraphRagResultStatus.REFUSAL.value,
            reason_code=self._reason_code,
            message=self._message,
        )

    def diagnostics(self) -> dict[str, Any]:
        """返回 Provider 不可用的低敏状态。"""

        return {
            "provider": "neo4j",
            "available": False,
            "reasonCode": self._reason_code,
        }


class Neo4jGraphRagProvider:
    """使用 Neo4j 保存实体/别名和带来源关系边的 GraphRAG Provider。

    数据模型约定：

    - 实体节点标签为 `GraphEntity`，标准 ID 保存在 `standard_id`；
    - 规范名称保存在 `canonical_name`，别名数组保存在 `aliases`；
    - 为了让中文别名消歧稳定，写入时同时保存归一化后的 `lookup_aliases`；
    - 关系使用 `GRAPH_RELATION` 类型，关系类型、来源、时间、可信度和范围
      全部保存为关系属性；
    - 关系两端带有租户/应用/项目/敏感级别，查询时同时校验节点和关系；
      新写入不再创建 workspace 属性。

    查询采用“逐跳读取”的方式，而不是把整张图一次性拉回内存：先在授权
    范围内解析起点别名，然后每一跳只读取当前实体的指定关系和目标实体。
    读取到的对象交给同一套路径和冲突规则，保证内存 Provider 与 Neo4j
    Provider 的结果合同一致。
    """

    _ENTITY_BY_ALIAS_CYPHER = """
    MATCH (entity:GraphEntity)
    WHERE $lookup_alias IN coalesce(entity.lookup_aliases, [])
      AND entity.tenant IN [$tenant, '*']
      AND entity.application IN [$application, '*']
      AND entity.project IN [$project, '*']
      AND coalesce(entity.sensitivity_rank, 1) <= $sensitivity_rank
    RETURN properties(entity) AS entity
    LIMIT 20
    """

    # 精确别名没有命中时，读取授权范围内的有限实体候选供 Python 侧严格消歧。关系边仍按逐跳
    # 查询，不会因为自然语言简称而展开整张图；LIMIT 同时约束延迟和内存占用。
    _ENTITY_CANDIDATES_CYPHER = """
    MATCH (entity:GraphEntity)
    WHERE entity.tenant IN [$tenant, '*']
      AND entity.application IN [$application, '*']
      AND entity.project IN [$project, '*']
      AND coalesce(entity.sensitivity_rank, 1) <= $sensitivity_rank
    RETURN properties(entity) AS entity
    LIMIT 200
    """

    _OUTGOING_EDGE_CYPHER = """
    MATCH (source:GraphEntity)-[relationship:GRAPH_RELATION]->(target:GraphEntity)
    WHERE source.standard_id = $source_id
      AND relationship.relation = $relation
      AND relationship.tenant IN [$tenant, '*']
      AND relationship.application IN [$application, '*']
      AND relationship.project IN [$project, '*']
      AND coalesce(relationship.sensitivity_rank, 1) <= $sensitivity_rank
      AND target.tenant IN [$tenant, '*']
      AND target.application IN [$application, '*']
      AND target.project IN [$project, '*']
      AND coalesce(target.sensitivity_rank, 1) <= $sensitivity_rank
    RETURN properties(source) AS source,
           properties(target) AS target,
           properties(relationship) AS relationship
    LIMIT $row_limit
    """

    _ENTITY_CONSTRAINT_CYPHER = (
        "CREATE CONSTRAINT datasmart_graph_entity_standard_id IF NOT EXISTS "
        "FOR (entity:GraphEntity) REQUIRE entity.standard_id IS UNIQUE"
    )
    _ENTITY_ALIAS_INDEX_CYPHER = (
        "CREATE INDEX datasmart_graph_entity_lookup_aliases IF NOT EXISTS "
        "FOR (entity:GraphEntity) ON (entity.lookup_aliases)"
    )

    def __init__(self, driver: Any, settings: GraphRagNeo4jSettings) -> None:
        """注入已创建的 Driver，便于生产复用连接池、单测注入 fake driver。"""

        self._driver = driver
        self._settings = settings

    @property
    def settings(self) -> GraphRagNeo4jSettings:
        """返回不可变配置对象，调用方不能通过它获取密码之外的隐藏状态。"""

        return self._settings

    def query(self, query: GraphRagQuery) -> GraphRagResult:
        """执行授权范围内的最多三跳图查询。"""

        parsed, parse_reason = self._parse_query(query)
        if parsed is None:
            return GraphRagResult(
                status=GraphRagResultStatus.NOT_APPLICABLE.value,
                reason_code=parse_reason or GraphRagReasonCode.UNSUPPORTED_QUERY.value,
                message="当前问题未匹配受治理的图关系查询合同。",
            )
        if not query.scope.is_concrete_query_scope():
            return GraphRagResult(
                status=GraphRagResultStatus.REFUSAL.value,
                reason_code=GraphRagReasonCode.SCOPE_REQUIRED.value,
                message="缺少具体的租户、应用、项目或敏感级别范围。",
                requested_hops=parsed.hops,
                relation=parsed.relation,
            )
        if (
            parsed.hops < 1
            or parsed.hops > MAX_GRAPH_RAG_HOPS
            or parsed.hops > query.max_hops
            or query.max_hops > min(MAX_GRAPH_RAG_HOPS, self._settings.max_hops)
        ):
            return GraphRagResult(
                status=GraphRagResultStatus.REFUSAL.value,
                reason_code=GraphRagReasonCode.MAX_HOPS_EXCEEDED.value,
                message=f"查询最多允许 {min(MAX_GRAPH_RAG_HOPS, self._settings.max_hops)} 跳。",
                requested_hops=parsed.hops,
                relation=parsed.relation,
            )

        scope = query.scope
        lookup_subject = query.start_entity or parsed.subject
        visible_entities = self._find_entities(
            lookup_subject,
            tenant=scope.tenant,
            application=scope.application,
            project=scope.project,
            sensitivity=scope.sensitivity,
        )
        if not visible_entities:
            return GraphRagResult(
                status=GraphRagResultStatus.NOT_APPLICABLE.value,
                reason_code=GraphRagReasonCode.ALIAS_NOT_FOUND.value,
                message="当前范围内没有找到该实体。",
                requested_hops=parsed.hops,
                relation=parsed.relation,
            )
        by_id = {entity.standard_id: entity for entity in visible_entities}
        if len(by_id) > 1:
            return GraphRagResult(
                status=GraphRagResultStatus.REFUSAL.value,
                reason_code=GraphRagReasonCode.AMBIGUOUS_ALIAS.value,
                message="名称对应多个可见实体，无法安全判断起点。",
                requested_hops=parsed.hops,
                relation=parsed.relation,
            )

        entity_resolution = "exact" if any(
            _normalize_alias(alias) == _normalize_alias(lookup_subject)
            for alias in next(iter(by_id.values())).aliases_for_lookup()
        ) else "semantic"

        current = next(iter(by_id.values()))
        path: list[GraphRagPathStep] = []
        try:
            hop_candidates = (
                self._find_outgoing_edges(
                    current.standard_id,
                    relation=parsed.relation,
                    tenant=scope.tenant,
                    application=scope.application,
                    project=scope.project,
                    sensitivity=scope.sensitivity,
                    as_of=query.as_of,
                )
                for _ in range(parsed.hops)
            )
            for hop, candidates in enumerate(hop_candidates, start=1):
                # 这里的循环体在下面继续完成冲突检测和路径追加；每一轮只查询当前实体，
                # 因此不会把 Neo4j 的整个授权子图展开到 Python 进程。
                if not candidates:
                    return GraphRagResult(
                        status=GraphRagResultStatus.NOT_APPLICABLE.value,
                        reason_code=GraphRagReasonCode.NO_CURRENT_PATH.value,
                        message="当前有效证据不足以形成完整路径。",
                        path=tuple(path),
                        requested_hops=parsed.hops,
                        relation=parsed.relation,
                    )
                by_target: dict[str, list[tuple[GraphRagEntity, GraphRagEdge]]] = {}
                for target, relationship in candidates:
                    by_target.setdefault(target.standard_id, []).append((target, relationship))
                target_ids = tuple(sorted(by_target))
                collection_relation = parsed.relation != GraphRagRelation.REPORTS_TO.value
                if len(target_ids) > 1 and not collection_relation:
                    return GraphRagResult(
                        status=GraphRagResultStatus.REFUSAL.value,
                        reason_code=GraphRagReasonCode.CONFLICTING_CURRENT_EDGES.value,
                        message="同一跳存在多个相互矛盾的当前有效关系，无法安全回答。",
                        path=tuple(path),
                        requested_hops=parsed.hops,
                        relation=parsed.relation,
                        conflicting_target_ids=target_ids,
                    )
                next_targets: list[GraphRagEntity] = []
                for target_id in target_ids:
                    target, selected_edge = sorted(
                        by_target[target_id],
                        key=lambda item: InMemoryGraphRag._edge_sort_key(item[1]),
                        reverse=True,
                    )[0]
                    supporting_edges = tuple(
                        edge for _, edge in sorted(
                            by_target[target_id],
                            key=lambda item: InMemoryGraphRag._edge_sort_key(item[1]),
                            reverse=True,
                        )
                    )
                    if not selected_edge.has_complete_provenance():
                        return GraphRagResult(
                            status=GraphRagResultStatus.REFUSAL.value,
                            reason_code=GraphRagReasonCode.INCOMPLETE_PROVENANCE.value,
                            message="关系边缺少完整来源信息，拒绝生成不可审计的答案。",
                            path=tuple(path),
                            requested_hops=parsed.hops,
                            relation=parsed.relation,
                        )
                    path.append(GraphRagPathStep(
                        hop=hop,
                        source_entity=current,
                        target_entity=target,
                        edge=selected_edge,
                        supporting_edges=supporting_edges,
                    ))
                    next_targets.append(target)
                if collection_relation and len(next_targets) > 1 and hop < parsed.hops:
                    return GraphRagResult(
                        status=GraphRagResultStatus.REFUSAL.value,
                        reason_code=GraphRagReasonCode.MAX_HOPS_EXCEEDED.value,
                        message="集合关系只允许一跳查询，避免无界分支遍历。",
                        path=tuple(path),
                        requested_hops=parsed.hops,
                        relation=parsed.relation,
                    )
                current = next_targets[0]
        except GraphRagQueryLimitError:
            return GraphRagResult(
                status=GraphRagResultStatus.REFUSAL.value,
                reason_code=GraphRagReasonCode.RESULT_LIMIT_EXCEEDED.value,
                message="关系候选数量超过治理上限，已拒绝扩大图遍历范围。",
                path=tuple(path),
                requested_hops=parsed.hops,
                relation=parsed.relation,
            )

        return GraphRagResult(
            status=GraphRagResultStatus.SUCCESS.value,
            answer=("、".join(step.target_entity.canonical_name for step in path[-len(target_ids):]) if len(target_ids) > 1 else current.canonical_name),
            entity_id=current.standard_id,
            path=tuple(path),
            reason_code=GraphRagReasonCode.ANSWERED.value,
            message="已根据当前有效关系和完整来源路径回答。",
            requested_hops=parsed.hops,
            relation=parsed.relation,
            entity_resolution=entity_resolution,
        )

    def retrieve(self, query: GraphRagQuery) -> GraphRagResult:
        """提供 Retriever 命名兼容方法。"""

        return self.query(query)

    def diagnostics(self) -> dict[str, Any]:
        """返回不包含连接地址、账号和密码的运行诊断。"""

        return {
            "provider": "neo4j",
            "available": True,
            "databaseConfigured": bool(self._settings.database),
            "maxHops": min(MAX_GRAPH_RAG_HOPS, self._settings.max_hops),
            "maxEdgesPerHop": self._settings.max_edges_per_hop,
        }

    def initialize_schema(self) -> None:
        """创建实体标准 ID 约束和别名索引。"""

        self._run_write(self._ENTITY_CONSTRAINT_CYPHER, {})
        self._run_write(self._ENTITY_ALIAS_INDEX_CYPHER, {})

    def upsert_entities(self, entities: Iterable[GraphRagEntity]) -> None:
        """写入标准实体和别名查找表，重复标准 ID 使用幂等更新。"""

        statement = """
        MERGE (entity:GraphEntity {standard_id: $standard_id})
        SET entity.canonical_name = $canonical_name,
            entity.aliases = $aliases,
            entity.lookup_aliases = $lookup_aliases,
            entity.tenant = $tenant,
            entity.application = $application,
            entity.project = $project,
            entity.sensitivity = $sensitivity,
            entity.sensitivity_rank = $sensitivity_rank,
            entity.metadata = $metadata
        """
        for entity in entities:
            self._run_write(
                statement,
                {
                    "standard_id": entity.standard_id,
                    "canonical_name": entity.canonical_name,
                    "aliases": list(entity.aliases),
                    "lookup_aliases": [_normalize_lookup(value) for value in entity.aliases_for_lookup()],
                    "tenant": entity.tenant,
                    "application": entity.application,
                    "project": entity.project,
                    "sensitivity": entity.sensitivity,
                    "sensitivity_rank": _sensitivity_rank(entity.sensitivity),
            # Neo4j 属性不能直接保存 Python Map；使用稳定 JSON 字符串保留可选元数据，
            # 避免实体写入在真实 Driver 中因 ``Map{}`` 类型失败。
            "metadata": json.dumps(
                dict(entity.metadata),
                ensure_ascii=False,
                sort_keys=True,
                separators=(",", ":"),
            ),
                },
            )

    def upsert_edges(self, edges: Iterable[GraphRagEdge]) -> None:
        """写入带来源、时间、可信度和范围的关系边。"""

        statement = """
        MATCH (source:GraphEntity {standard_id: $source_entity_id})
        MATCH (target:GraphEntity {standard_id: $target_entity_id})
        MERGE (source)-[relationship:GRAPH_RELATION {edge_id: $edge_id}]->(target)
        SET relationship.relation = $relation,
            relationship.source_document_id = $source_document_id,
            relationship.source_uri = $source_uri,
            relationship.source_chunk_id = $source_chunk_id,
            relationship.asserted_at = $asserted_at,
            relationship.effective_at = $effective_at,
            relationship.expires_at = $expires_at,
            relationship.confidence = $confidence,
            relationship.status = $status,
            relationship.tenant = $tenant,
            relationship.application = $application,
            relationship.project = $project,
            relationship.sensitivity = $sensitivity,
            relationship.sensitivity_rank = $sensitivity_rank
        """
        for edge in edges:
            self._run_write(statement, _edge_parameters(edge))

    def close(self) -> None:
        """关闭 Neo4j Driver，供应用生命周期或测试清理调用。"""

        close = getattr(self._driver, "close", None)
        if callable(close):
            close()

    def _find_entities(
        self,
        subject: str,
        *,
        tenant: str,
        application: str,
        project: str,
        sensitivity: str,
    ) -> tuple[GraphRagEntity, ...]:
        """只在已经授权的图范围内按标准 ID/规范名/别名查找实体。"""

        parameters = {
            "lookup_alias": _normalize_lookup(subject),
            "tenant": tenant,
            "application": application,
            "project": project,
            "sensitivity_rank": _sensitivity_rank(sensitivity),
        }
        rows = self._run_read(
            self._ENTITY_BY_ALIAS_CYPHER,
            parameters,
        )
        entities: dict[str, GraphRagEntity] = {}
        for row in rows:
            entity = _entity_from_mapping(_record_value(row, "entity"))
            if entity is not None:
                entities[entity.standard_id] = entity
        if entities:
            return tuple(entities.values())

        candidate_rows = self._run_read(
            self._ENTITY_CANDIDATES_CYPHER,
            {key: value for key, value in parameters.items() if key != "lookup_alias"},
        )
        scored: list[tuple[float, GraphRagEntity]] = []
        for row in candidate_rows:
            entity = _entity_from_mapping(_record_value(row, "entity"))
            if entity is None:
                continue
            score = _semantic_alias_score(_normalize_alias(subject), entity.aliases_for_lookup())
            if score >= 0.75:
                scored.append((score, entity))
        scored.sort(key=lambda item: (item[0], item[1].standard_id), reverse=True)
        if not scored:
            return ()
        best_score = scored[0][0]
        # 保留近似并列候选，让 query() 统一返回 AMBIGUOUS_ALIAS，不能在 Adapter 内按数据库顺序猜测。
        return tuple(
            entity
            for score, entity in scored
            if best_score - score < 0.10
        )[:20]

    def _find_outgoing_edges(
        self,
        source_id: str,
        *,
        relation: str,
        tenant: str,
        application: str,
        project: str,
        sensitivity: str,
        as_of: Any,
    ) -> tuple[tuple[GraphRagEntity, GraphRagEdge], ...]:
        """读取一跳出边，并在 Python 侧再次执行时间和字段校验。"""

        rows = self._run_read(
            self._OUTGOING_EDGE_CYPHER,
            {
                "source_id": source_id,
                "relation": relation,
                "tenant": tenant,
                "application": application,
                "project": project,
                "sensitivity_rank": _sensitivity_rank(sensitivity),
                "row_limit": self._settings.max_edges_per_hop + 1,
            },
        )
        if len(rows) > self._settings.max_edges_per_hop:
            raise GraphRagQueryLimitError("GraphRAG 单跳关系数量超过治理上限。")
        visible: list[tuple[GraphRagEntity, GraphRagEdge]] = []
        for row in rows:
            source = _entity_from_mapping(_record_value(row, "source"))
            target = _entity_from_mapping(_record_value(row, "target"))
            edge = _edge_from_mapping(
                _record_value(row, "relationship"),
                source_entity_id=source.standard_id,
                target_entity_id=target.standard_id,
            )
            if source is None or target is None or edge is None:
                continue
            if edge.source_entity_id != source_id:
                continue
            if not edge.is_current(as_of):
                continue
            visible.append((target, edge))
        return tuple(visible)

    def _run_read(self, statement: str, parameters: Mapping[str, Any]) -> tuple[Any, ...]:
        """在只读查询边界内执行参数化 Cypher，并把结果完整消费后关闭会话。"""

        return self._run(statement, parameters)

    def _run_write(self, statement: str, parameters: Mapping[str, Any]) -> tuple[Any, ...]:
        """执行参数化写入语句；写入接口仅供受控索引同步调用。"""

        return self._run(statement, parameters)

    def _run(self, statement: str, parameters: Mapping[str, Any]) -> tuple[Any, ...]:
        """兼容真实 Driver 和无外部依赖的测试替身。"""

        session = self._open_session()
        try:
            result = session.run(statement, **dict(parameters))
            return tuple(result)
        finally:
            close = getattr(session, "close", None)
            if callable(close):
                close()

    def _open_session(self) -> Any:
        """按配置打开数据库会话，并兼容只接受无参数 session 的 fake driver。"""

        session_factory = getattr(self._driver, "session", None)
        if not callable(session_factory):
            raise RuntimeError("Neo4j Driver 未提供 session 接口。")
        try:
            return session_factory(database=self._settings.database)
        except TypeError:
            return session_factory()

    @staticmethod
    def _parse_query(
        query: GraphRagQuery,
    ) -> tuple[GraphRagParsedQuestion | None, str | None]:
        """复用内存核心的最小查询解析规则，避免两个 Provider 解析漂移。"""

        return InMemoryGraphRag._parse_query(query)


class GraphRagQueryLimitError(RuntimeError):
    """Neo4j 单跳关系超过治理上限时抛出的内部稳定异常。"""


def graph_rag_neo4j_settings_from_env(
    environ: Mapping[str, str] | None = None,
) -> GraphRagNeo4jSettings:
    """读取 GraphRAG Neo4j 配置，默认关闭且不影响普通 RAG 启动。"""

    source = environ if environ is not None else os.environ
    provider = str(source.get("DATASMART_GRAPH_RAG_PROVIDER") or "disabled").strip().lower()
    if provider in {"off", "none", "in-memory", "memory"}:
        provider = "disabled"
    if provider in {"neo4j-bolt", "neo-4j"}:
        provider = "neo4j"
    if provider not in {"disabled", "neo4j"}:
        raise ValueError("DATASMART_GRAPH_RAG_PROVIDER 只支持 disabled 或 neo4j。")
    return GraphRagNeo4jSettings(
        provider_type=provider,
        uri=str(
            source.get("DATASMART_GRAPH_RAG_NEO4J_URI")
            or source.get("DATASMART_NEO4J_URI")
            or ""
        ).strip(),
        username=str(
            source.get("DATASMART_GRAPH_RAG_NEO4J_USERNAME")
            or source.get("DATASMART_NEO4J_USERNAME")
            or "neo4j"
        ).strip(),
        password=str(
            source.get("DATASMART_GRAPH_RAG_NEO4J_PASSWORD")
            or source.get("DATASMART_NEO4J_PASSWORD")
            or ""
        ).strip(),
        database=str(source.get("DATASMART_GRAPH_RAG_NEO4J_DATABASE") or "neo4j").strip() or "neo4j",
        connect_timeout_seconds=_positive_int(source.get("DATASMART_GRAPH_RAG_CONNECT_TIMEOUT_SECONDS"), 5),
        max_edges_per_hop=_positive_int(source.get("DATASMART_GRAPH_RAG_MAX_EDGES_PER_HOP"), 128),
        max_hops=max(
            1,
            min(
                MAX_GRAPH_RAG_HOPS,
                _positive_int(source.get("DATASMART_GRAPH_RAG_MAX_HOPS"), MAX_GRAPH_RAG_HOPS),
            ),
        ),
        initialize_schema=_truthy(source.get("DATASMART_GRAPH_RAG_INITIALIZE_SCHEMA"), default=False),
    )


def graph_rag_provider_from_env(
    environ: Mapping[str, str] | None = None,
    *,
    driver_factory: GraphDatabaseDriverFactory | None = None,
) -> GraphRagProvider | None:
    """按环境配置构建 Neo4j Provider；禁用时返回 None。"""

    settings = graph_rag_neo4j_settings_from_env(environ)
    if not settings.enabled:
        return None
    if not settings.uri or not settings.username or not settings.password:
        return UnavailableGraphRagProvider(
            reason_code="GRAPH_PROVIDER_CONFIGURATION_INVALID",
            message="GraphRAG Neo4j 配置不完整，无法安全回答关系链问题。",
        )
    _validate_neo4j_uri(settings.uri)
    if driver_factory is None:
        try:
            from neo4j import GraphDatabase
        except ImportError:
            return UnavailableGraphRagProvider(
                reason_code="GRAPH_PROVIDER_DRIVER_UNAVAILABLE",
                message="GraphRAG Neo4j 驱动未安装，无法安全回答关系链问题。",
            )
        driver_factory = GraphDatabase.driver
    driver = driver_factory(
        settings.uri,
        auth=(settings.username, settings.password),
        connection_timeout=settings.connect_timeout_seconds,
    )
    provider = Neo4jGraphRagProvider(driver, settings)
    if settings.initialize_schema:
        provider.initialize_schema()
    return provider


def _entity_from_mapping(value: Any) -> GraphRagEntity | None:
    """把 Neo4j properties 映射为内部实体；缺少标准 ID 时拒绝该行。"""

    if not isinstance(value, Mapping):
        return None
    standard_id = value.get("standard_id") or value.get("standardId")
    canonical_name = value.get("canonical_name") or value.get("canonicalName")
    if not standard_id or not canonical_name:
        return None
    metadata = value.get("metadata")
    if isinstance(metadata, str):
        try:
            decoded_metadata = json.loads(metadata)
        except (TypeError, ValueError):
            decoded_metadata = {}
        metadata = decoded_metadata if isinstance(decoded_metadata, Mapping) else {}
    return GraphRagEntity(
        standard_id=str(standard_id),
        canonical_name=str(canonical_name),
        aliases=tuple(value.get("aliases") or ()),
        tenant=str(value.get("tenant") or "*"),
        application=str(value.get("application") or value.get("application_id") or "*"),
        project=str(value.get("project") or "*"),
        sensitivity=str(value.get("sensitivity") or "internal"),
        metadata=metadata if isinstance(metadata, Mapping) else {},
    )


def _edge_from_mapping(
    value: Any,
    *,
    source_entity_id: str | None = None,
    target_entity_id: str | None = None,
) -> GraphRagEdge | None:
    """把 Neo4j 关系属性映射为内部关系边。

    关系端点本来就由 Cypher 返回的 ``source`` 和 ``target`` 节点确定，写入时不需要把两个
    标准 ID 再复制到关系属性中。读取时优先使用关系属性中的兼容字段，缺少时使用节点标准
    ID；这样既兼容早期测试数据，也保证真实 ``upsert_edges`` 写入的关系能够被再次读取。
    """

    if not isinstance(value, Mapping):
        return None
    source_id = (
        value.get("source_entity_id")
        or value.get("sourceEntityId")
        or source_entity_id
    )
    target_id = (
        value.get("target_entity_id")
        or value.get("targetEntityId")
        or target_entity_id
    )
    relation = value.get("relation") or value.get("relation_type")
    if not source_id or not target_id or not relation:
        return None
    return GraphRagEdge(
        source_entity_id=str(source_id),
        target_entity_id=str(target_id),
        relation=str(relation),
        source_document_id=str(value.get("source_document_id") or value.get("sourceDocumentId") or ""),
        source_uri=str(value.get("source_uri") or value.get("sourceUri") or ""),
        source_chunk_id=str(value.get("source_chunk_id") or value.get("sourceChunkId") or ""),
        asserted_at=value.get("asserted_at") or value.get("assertedAt"),
        effective_at=value.get("effective_at") or value.get("effectiveAt"),
        expires_at=value.get("expires_at") or value.get("expiresAt"),
        confidence=value.get("confidence", 1.0),
        status=str(value.get("status") or "active"),
        tenant=str(value.get("tenant") or "*"),
        application=str(value.get("application") or value.get("application_id") or "*"),
        project=str(value.get("project") or "*"),
        sensitivity=str(value.get("sensitivity") or "internal"),
    )


def _edge_parameters(edge: GraphRagEdge) -> dict[str, Any]:
    """生成 Neo4j 写入参数，并为关系边创建稳定幂等键。"""

    identity = "|".join(
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
    return {
        "edge_id": hashlib.sha256(identity.encode("utf-8")).hexdigest(),
        "source_entity_id": edge.source_entity_id,
        "target_entity_id": edge.target_entity_id,
        "relation": edge.relation,
        "source_document_id": edge.source_document_id,
        "source_uri": edge.source_uri,
        "source_chunk_id": edge.source_chunk_id,
        "asserted_at": _timestamp_property(edge.asserted_at),
        "effective_at": _timestamp_property(edge.effective_at),
        "expires_at": _timestamp_property(edge.expires_at),
        "confidence": edge.confidence,
        "status": edge.status,
        "tenant": edge.tenant,
        "application": edge.application,
        "project": edge.project,
        "sensitivity": edge.sensitivity,
        "sensitivity_rank": _sensitivity_rank(edge.sensitivity),
    }


def _record_value(record: Any, key: str) -> Any:
    """兼容 Neo4j Record、普通字典和测试替身的字段读取。"""

    if isinstance(record, Mapping):
        return record.get(key)
    getter = getattr(record, "get", None)
    if callable(getter):
        return getter(key)
    try:
        return record[key]
    except (KeyError, IndexError, TypeError):
        return None


def _normalize_lookup(value: Any) -> str:
    """与核心别名归一化保持一致，同时避免把空值写入索引。"""

    import re
    import unicodedata

    normalized = unicodedata.normalize("NFKC", str(value or "")).casefold()
    return re.sub(r"\s+", "", normalized).strip("。！？?!、，,：:；;\"'“”‘’()（）[]【】")


def _timestamp_property(value: Any) -> str | None:
    """把时间转换成 Neo4j 可比较的 UTC ISO 文本。"""

    if value is None:
        return None
    if hasattr(value, "isoformat"):
        return value.isoformat()
    return str(value)


def _sensitivity_rank(value: str) -> int:
    """把敏感级别转换为查询可用的单调等级。"""

    return {
        "public": 0,
        "internal": 1,
        "confidential": 2,
        "restricted": 3,
        "secret": 4,
    }.get(str(value or "internal").strip().casefold(), 1)


def _positive_int(value: str | None, default: int) -> int:
    """读取正整数配置，非法或非正值回退到安全默认值。"""

    try:
        parsed = int(str(value).strip()) if value is not None and str(value).strip() else default
    except (TypeError, ValueError):
        return default
    return parsed if parsed > 0 else default


def _truthy(value: str | None, *, default: bool) -> bool:
    """读取显式布尔配置。"""

    if value is None or not str(value).strip():
        return default
    return str(value).strip().lower() in {"1", "true", "yes", "on", "enabled"}


def _validate_neo4j_uri(uri: str) -> None:
    """拒绝带凭据、查询参数或 fragment 的 Neo4j 地址。"""

    parts = urlsplit(str(uri).strip())
    if parts.scheme not in {"bolt", "bolt+s", "bolt+ssc", "neo4j", "neo4j+s", "neo4j+ssc"}:
        raise ValueError("GraphRAG Neo4j URI 必须使用 bolt/neo4j 协议。")
    if not parts.hostname or parts.username or parts.password or parts.query or parts.fragment:
        raise ValueError("GraphRAG Neo4j URI 不能包含凭据、查询参数或 fragment。")


__all__ = [
    "GraphRagNeo4jSettings",
    "GraphRagQueryLimitError",
    "Neo4jGraphRagProvider",
    "UnavailableGraphRagProvider",
    "graph_rag_neo4j_settings_from_env",
    "graph_rag_provider_from_env",
]
