"""PostgreSQL/pgvector 长期记忆二级索引适配器。

本模块让 `agent_memory_embedding_index` 从“只有 DDL 的规划表”变成 Python Runtime 可真实使用的
向量索引。它同时实现两类现有协议：

- `AgentMemorySecondaryIndexSyncAdapter`：消费 materializer 生成的 vector 同步任务；
- `AgentMemorySecondaryIndex`：在 Agent 检索阶段执行带范围过滤的 pgvector 相似度查询。

设计原则：
1. `agent_memory_store_entry` 仍是正式记忆事实源，向量表只是可重建二级索引；
2. tenant/project/session/workspace/memoryNamespace 必须在 SQL 中先过滤，不能全局召回后再由 Python 判断；
3. Embedding Provider 通过独立协议注入，不绑定 Qwen、BGE、Jina、vLLM 或任何固定模型；
4. 同一 memory 可以保留多个 embedding model 版本；同模型正文变化时旧 fingerprint 标记为 stale；
5. DELETE/EXPIRE 会清空向量并标记 deleted，满足遗忘、撤销和过期治理的基础要求；
6. attributes 和 diagnostics 只返回计数、维度、模型名和相似度摘要，不返回记忆正文或向量。
"""

from __future__ import annotations

import hashlib
import json
import math
import re
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any

from datasmart_ai_runtime.domain.memory import AgentMemoryScope
from datasmart_ai_runtime.services.memory.memory_embedding_provider import (
    AgentMemoryEmbeddingProvider,
    validate_embedding_vector,
)
from datasmart_ai_runtime.services.memory.memory_secondary_index import (
    AgentMemorySecondaryIndexKind,
    AgentMemorySecondaryIndexQuery,
    AgentMemorySecondaryIndexResult,
)
from datasmart_ai_runtime.services.memory.memory_secondary_index_sync import (
    AgentMemorySecondaryIndexSyncAction,
    AgentMemorySecondaryIndexSyncAdapterResult,
    AgentMemorySecondaryIndexSyncTask,
)
from datasmart_ai_runtime.services.memory.memory_store import AgentMemoryStore, AgentMemoryStoreEntry


_IDENTIFIER_PATTERN = re.compile(r"^[A-Za-z_][A-Za-z0-9_]{0,63}$")


@dataclass(frozen=True)
class PgvectorMemoryIndexSettings:
    """pgvector 适配器运行参数。

    字段说明：
    - `schema_name`：向量索引和正式记忆所在 PostgreSQL schema；
    - `embedding_model`：本适配器当前读写的模型版本，用于模型升级并存与维度隔离；
    - `document_max_chars`：进入 Embedding Provider 的低敏摘要长度上限；
    - `minimum_similarity`：余弦相似度最低阈值，低于阈值的候选不进入正式 store 回查；
    - `auto_commit`：是否由适配器提交事务；未来同事务 outbox 场景可以关闭；
    - `payload_policy`：诊断中展示的低敏索引策略。

    当前 V1 表使用未固定维度的 `vector`，因此不能建立一个覆盖所有模型的统一 HNSW 索引。生产环境在
    固定主力模型和维度后，应按模型/维度建立表达式索引、部分索引或分区，并用真实容量压测验收。
    """

    schema_name: str = "ai_memory"
    embedding_model: str = ""
    document_max_chars: int = 4000
    minimum_similarity: float = -1.0
    auto_commit: bool = True
    payload_policy: str = "SUMMARY_ONLY_METADATA_FILTERED_NO_RAW_TOOL_RESULT"


@dataclass(frozen=True)
class PgvectorMemoryIndexUpsertResult:
    """单条向量索引 upsert 的低敏结果。"""

    embedding_id: str
    memory_id: str
    embedding_model: str
    embedding_dimension: int
    content_fingerprint: str
    stale_version_count: int
    indexed: bool


class PgvectorAgentMemorySecondaryIndex:
    """pgvector 的长期记忆 VECTOR 二级索引。

    该类持有一个 DB-API PostgreSQL 连接和正式记忆 store。向量命中后仍按 sourceCandidateId 回查正式
    store，并重新校验过期时间与范围字段，形成“SQL metadata filter + 正式事实源复核”的防御纵深。
    """

    kind = AgentMemorySecondaryIndexKind.VECTOR

    def __init__(
        self,
        *,
        connection: Any,
        memory_store: AgentMemoryStore,
        embedding_provider: AgentMemoryEmbeddingProvider,
        settings: PgvectorMemoryIndexSettings,
    ) -> None:
        if not settings.embedding_model.strip():
            raise ValueError("pgvector adapter 必须配置 embedding_model。")
        _validate_identifier(settings.schema_name, "schema_name")
        self._connection = connection
        self._memory_store = memory_store
        self._embedding_provider = embedding_provider
        self._settings = settings
        self._embedding_table = f"{settings.schema_name}.agent_memory_embedding_index"
        self._memory_table = f"{settings.schema_name}.agent_memory_store_entry"

    def sync(self, task: AgentMemorySecondaryIndexSyncTask) -> AgentMemorySecondaryIndexSyncAdapterResult:
        """处理 vector 二级索引同步任务。

        UPSERT 会回查正式记忆、验证任务边界并生成向量；DELETE/EXPIRE 不需要再次调用模型，而是清空
        已有向量并标记 deleted。任务不是 vector 类型时返回 `synced=False`，让 worker 进入受控重试/DLQ，
        而不是静默把 graph/resource 任务写进向量表。
        """

        if task.index_kind != AgentMemorySecondaryIndexKind.VECTOR:
            return AgentMemorySecondaryIndexSyncAdapterResult(
                synced=False,
                message=f"pgvector adapter 只处理 vector 任务，当前为 {task.index_kind.value}。",
            )
        if task.action in {
            AgentMemorySecondaryIndexSyncAction.DELETE,
            AgentMemorySecondaryIndexSyncAction.EXPIRE,
        }:
            affected = self.delete_memory(task.memory_id)
            return AgentMemorySecondaryIndexSyncAdapterResult(
                synced=True,
                message=f"pgvector 索引已按 {task.action.value} 标记 deleted，affected={affected}。",
            )

        entry = self._memory_store.get_by_candidate_id(task.source_candidate_id)
        if entry is None:
            return AgentMemorySecondaryIndexSyncAdapterResult(
                synced=False,
                message=f"正式记忆不存在，无法同步 pgvector: candidateId={task.source_candidate_id}",
            )
        self._validate_task_matches_entry(task, entry)
        result = self.upsert_entry(entry)
        return AgentMemorySecondaryIndexSyncAdapterResult(
            synced=result.indexed,
            message=(
                "正式记忆已同步到 PostgreSQL/pgvector，"
                f"dimension={result.embedding_dimension}, staleVersions={result.stale_version_count}。"
            ),
        )

    def upsert_entry(self, entry: AgentMemoryStoreEntry) -> PgvectorMemoryIndexUpsertResult:
        """为一条正式记忆生成并幂等写入向量。

        写入步骤：
        1. 构造受长度限制的低敏文档并生成 content fingerprint；
        2. 调用独立 Embedding Provider，拒绝空向量、超大维度、NaN 和 Infinity；
        3. 将同 memoryId + 同模型但旧 fingerprint 的 ready 版本标记为 stale；
        4. 使用 `(memory_id, embedding_model, content_fingerprint)` 唯一键执行 PostgreSQL upsert；
        5. metadata_json 只保存范围与治理字段，不复制正文。
        """

        if entry.expires_at <= datetime.now(timezone.utc):
            affected = self.delete_memory(entry.memory.memory_id)
            return PgvectorMemoryIndexUpsertResult(
                embedding_id=_embedding_id(
                    entry.memory.memory_id,
                    self._settings.embedding_model,
                    "expired",
                ),
                memory_id=entry.memory.memory_id,
                embedding_model=self._settings.embedding_model,
                embedding_dimension=0,
                content_fingerprint="expired",
                stale_version_count=affected,
                indexed=False,
            )

        document = self._document(entry)
        content_fingerprint = hashlib.sha256(document.encode("utf-8")).hexdigest()
        embedding = validate_embedding_vector(self._embedding_provider.embed_text(document))
        vector_literal = _vector_literal(embedding)
        embedding_id = _embedding_id(
            entry.memory.memory_id,
            self._settings.embedding_model,
            content_fingerprint,
        )
        now = datetime.now(timezone.utc)
        try:
            stale_cursor = self._execute(
                f"UPDATE {self._embedding_table} SET index_status = 'stale', update_time = %s "
                "WHERE memory_id = %s AND embedding_model = %s AND content_fingerprint <> %s "
                "AND index_status = 'ready'",
                (now, entry.memory.memory_id, self._settings.embedding_model, content_fingerprint),
            )
            self._execute(
                f"""
                INSERT INTO {self._embedding_table} (
                    embedding_id, memory_id, tenant_id, project_id, session_id, workspace_key,
                    memory_namespace, memory_type, scope, embedding_model, embedding_dimension,
                    embedding, content_fingerprint, metadata_json, index_status, indexed_at,
                    create_time, update_time
                ) VALUES (
                    %s, %s, %s, %s, %s, %s,
                    %s, %s, %s, %s, %s,
                    CAST(%s AS vector), %s, CAST(%s AS jsonb), 'ready', %s,
                    %s, %s
                )
                ON CONFLICT (memory_id, embedding_model, content_fingerprint)
                DO UPDATE SET
                    embedding_id = EXCLUDED.embedding_id,
                    tenant_id = EXCLUDED.tenant_id,
                    project_id = EXCLUDED.project_id,
                    session_id = EXCLUDED.session_id,
                    workspace_key = EXCLUDED.workspace_key,
                    memory_namespace = EXCLUDED.memory_namespace,
                    memory_type = EXCLUDED.memory_type,
                    scope = EXCLUDED.scope,
                    embedding_dimension = EXCLUDED.embedding_dimension,
                    embedding = EXCLUDED.embedding,
                    metadata_json = EXCLUDED.metadata_json,
                    index_status = 'ready',
                    indexed_at = EXCLUDED.indexed_at,
                    update_time = EXCLUDED.update_time
                """,
                (
                    embedding_id,
                    entry.memory.memory_id,
                    entry.memory.tenant_id,
                    entry.memory.project_id,
                    entry.memory.session_id,
                    entry.workspace_key,
                    entry.memory_namespace,
                    entry.memory.memory_type.value,
                    entry.memory.scope.value,
                    self._settings.embedding_model,
                    len(embedding),
                    vector_literal,
                    content_fingerprint,
                    json.dumps(self._metadata(entry), ensure_ascii=False, sort_keys=True),
                    now,
                    now,
                    now,
                ),
            )
            self._commit()
        except Exception:
            self._rollback()
            raise
        return PgvectorMemoryIndexUpsertResult(
            embedding_id=embedding_id,
            memory_id=entry.memory.memory_id,
            embedding_model=self._settings.embedding_model,
            embedding_dimension=len(embedding),
            content_fingerprint=content_fingerprint,
            stale_version_count=max(0, int(stale_cursor.rowcount or 0)),
            indexed=True,
        )

    def search(self, query: AgentMemorySecondaryIndexQuery) -> AgentMemorySecondaryIndexResult:
        """执行带硬范围过滤的 pgvector 余弦相似度检索。

        SQL 同时过滤 index 与正式记忆表的范围字段、模型、维度、状态和过期时间。即使索引表出现残留或
        metadata 错位，也不能仅凭向量相似度跨 workspace 返回记忆。
        """

        query_text = self._query_text(query)
        if not query_text:
            return AgentMemorySecondaryIndexResult(
                skipped_reason="缺少可用于 pgvector 的低敏检索文本。",
                attributes=self._search_attributes(query, 0, 0, None, None),
            )
        query_embedding = validate_embedding_vector(self._embedding_provider.embed_text(query_text))
        rows = self._query_rows(query, query_embedding)
        entries: list[AgentMemoryStoreEntry] = []
        similarities: list[float] = []
        seen_memory_ids: set[str] = set()
        for row in rows:
            memory_id = str(_row_value(row, "memory_id", 1))
            if memory_id in seen_memory_ids:
                continue
            distance = float(_row_value(row, "distance", 2))
            similarity = 1.0 - distance
            if not math.isfinite(similarity) or similarity < self._settings.minimum_similarity:
                continue
            source_candidate_id = str(_row_value(row, "source_candidate_id", 0))
            entry = self._memory_store.get_by_candidate_id(source_candidate_id)
            if entry and _entry_matches_query(entry, query):
                entries.append(entry)
                similarities.append(similarity)
                seen_memory_ids.add(memory_id)
        return AgentMemorySecondaryIndexResult(
            entries=tuple(entries),
            attributes=self._search_attributes(
                query,
                raw_count=len(rows),
                returned_count=len(entries),
                top_similarity=max(similarities) if similarities else None,
                embedding_dimension=len(query_embedding),
            ),
        )

    def delete_memory(self, memory_id: str) -> int:
        """清空某条正式记忆的向量并标记 deleted。

        保留低敏索引行是为了审计和迁移对账，但 `embedding=NULL + index_status=deleted` 保证后续检索不会
        使用已撤销向量。更严格的客户环境可在保留期结束后执行物理删除。
        """

        cursor = self._execute(
            f"UPDATE {self._embedding_table} SET embedding = NULL, index_status = 'deleted', "
            "update_time = %s WHERE memory_id = %s AND index_status <> 'deleted'",
            (datetime.now(timezone.utc), memory_id),
        )
        self._commit()
        return max(0, int(cursor.rowcount or 0))

    def diagnostics(self) -> dict[str, object]:
        """返回低敏 pgvector 运行诊断。"""

        cursor = self._execute(
            f"SELECT index_status, COUNT(*) AS item_count FROM {self._embedding_table} "
            "WHERE embedding_model = %s GROUP BY index_status",
            (self._settings.embedding_model,),
        )
        counts = {
            str(_row_value(row, "index_status", 0)): int(_row_value(row, "item_count", 1))
            for row in cursor.fetchall()
        }
        return {
            "indexKind": self.kind.value,
            "implementation": type(self).__name__,
            "schemaVersion": "datasmart.memory.pgvector.v1",
            "embeddingModel": self._settings.embedding_model,
            "documentMaxChars": max(100, self._settings.document_max_chars),
            "minimumSimilarity": self._settings.minimum_similarity,
            "statusCounts": counts,
            "payloadPolicy": self._settings.payload_policy,
            "memoryBodyReturnedInDiagnostics": False,
        }

    def _query_rows(
        self,
        query: AgentMemorySecondaryIndexQuery,
        embedding: tuple[float, ...],
    ) -> tuple[Any, ...]:
        """构造并执行结构化范围过滤 + vector distance SQL。"""

        vector_literal = _vector_literal(embedding)
        sql = [
            "SELECT m.source_candidate_id, i.memory_id,",
            "i.embedding <=> CAST(%s AS vector) AS distance",
            f"FROM {self._embedding_table} i",
            f"JOIN {self._memory_table} m ON m.memory_id = i.memory_id",
            "WHERE i.index_status = 'ready' AND i.embedding IS NOT NULL",
            "AND i.embedding_model = %s AND i.embedding_dimension = %s",
            "AND i.memory_type = %s AND m.memory_type = %s",
            "AND i.scope = %s AND m.scope = %s",
            "AND i.workspace_key = %s AND m.workspace_key = %s",
            "AND i.memory_namespace = %s AND m.memory_namespace = %s",
            "AND m.expires_at > %s",
        ]
        params: list[Any] = [
            vector_literal,
            self._settings.embedding_model,
            len(embedding),
            query.target.memory_type.value,
            query.target.memory_type.value,
            query.target.scope.value,
            query.target.scope.value,
            query.workspace_key,
            query.workspace_key,
            query.memory_namespace,
            query.memory_namespace,
            datetime.now(timezone.utc),
        ]
        if query.target.scope == AgentMemoryScope.SESSION:
            sql.extend(
                [
                    "AND i.tenant_id = %s AND m.tenant_id = %s",
                    "AND i.project_id = %s AND m.project_id = %s",
                    "AND i.session_id = %s AND m.session_id = %s",
                ]
            )
            params.extend(
                [
                    query.tenant_id,
                    query.tenant_id,
                    query.project_id,
                    query.project_id,
                    query.session_id,
                    query.session_id,
                ]
            )
        elif query.target.scope == AgentMemoryScope.PROJECT:
            sql.extend(
                [
                    "AND i.tenant_id = %s AND m.tenant_id = %s",
                    "AND i.project_id = %s AND m.project_id = %s",
                ]
            )
            params.extend([query.tenant_id, query.tenant_id, query.project_id, query.project_id])
        elif query.target.scope == AgentMemoryScope.TENANT:
            sql.append("AND i.tenant_id = %s AND m.tenant_id = %s")
            params.extend([query.tenant_id, query.tenant_id])
        sql.append("ORDER BY distance ASC, m.materialized_at DESC LIMIT %s")
        params.append(max(1, min(query.candidate_limit, 200)))
        cursor = self._execute(" ".join(sql), tuple(params))
        return tuple(cursor.fetchall())

    def _validate_task_matches_entry(
        self,
        task: AgentMemorySecondaryIndexSyncTask,
        entry: AgentMemoryStoreEntry,
    ) -> None:
        """校验同步任务和正式事实源的定位字段，防止错任务污染其他 workspace 索引。"""

        comparisons = (
            (task.memory_id, entry.memory.memory_id, "memoryId"),
            (task.memory_namespace, entry.memory_namespace, "memoryNamespace"),
            (task.workspace_key, entry.workspace_key, "workspaceKey"),
            (task.tenant_id, entry.memory.tenant_id, "tenantId"),
            (task.project_id, entry.memory.project_id, "projectId"),
            (task.session_id, entry.memory.session_id, "sessionId"),
        )
        for actual, expected, field_name in comparisons:
            if actual != expected:
                raise ValueError(f"同步任务 {field_name} 与正式记忆不一致。")

    def _document(self, entry: AgentMemoryStoreEntry) -> str:
        """构造进入 Embedding Provider 的低敏摘要。"""

        text = f"{entry.memory.title}\n\n{entry.memory.content}\n\n{' '.join(entry.memory.tags)}".strip()
        return text[: max(100, self._settings.document_max_chars)]

    def _query_text(self, query: AgentMemorySecondaryIndexQuery) -> str:
        """构造检索向量输入，不做持久化或日志输出。"""

        text = f"{query.target.query_hint}\n{query.objective}".strip()
        return text[: max(100, self._settings.document_max_chars)]

    def _metadata(self, entry: AgentMemoryStoreEntry) -> dict[str, object]:
        """构造不含正文和向量的低敏 metadata_json。"""

        return {
            "sourceCandidateId": entry.source_candidate_id,
            "sensitivityLevel": entry.memory.sensitivity_level,
            "expiresAt": entry.expires_at.isoformat(),
            "materializedAt": entry.materialized_at.isoformat(),
            "payloadPolicy": str(entry.memory.attributes.get("payloadPolicy") or self._settings.payload_policy),
        }

    def _search_attributes(
        self,
        query: AgentMemorySecondaryIndexQuery,
        raw_count: int,
        returned_count: int,
        top_similarity: float | None,
        embedding_dimension: int | None,
    ) -> dict[str, object]:
        """生成低敏检索摘要。"""

        return {
            "indexKind": self.kind.value,
            "implementation": type(self).__name__,
            "embeddingModel": self._settings.embedding_model,
            "embeddingDimension": embedding_dimension,
            "candidateLimit": query.candidate_limit,
            "rawCandidateCount": raw_count,
            "candidateCount": returned_count,
            "topSimilarity": round(top_similarity, 6) if top_similarity is not None else None,
            "minimumSimilarity": self._settings.minimum_similarity,
            "scope": query.target.scope.value,
            "memoryType": query.target.memory_type.value,
            "workspaceBoundaryApplied": bool(query.workspace_key),
            "memoryNamespaceBoundaryApplied": bool(query.memory_namespace),
            "memoryBodyReturnedInAttributes": False,
            "payloadPolicy": self._settings.payload_policy,
        }

    def _execute(self, sql: str, params: tuple[Any, ...] = ()) -> Any:
        """执行参数化 PostgreSQL SQL。schema 名在构造时已通过 identifier 白名单。"""

        cursor = self._connection.cursor()
        cursor.execute(sql, params)
        return cursor

    def _commit(self) -> None:
        """按配置提交事务。"""

        if self._settings.auto_commit:
            self._connection.commit()

    def _rollback(self) -> None:
        """写入失败时回滚 stale 标记和 upsert，保持索引版本一致。"""

        self._connection.rollback()


def _entry_matches_query(entry: AgentMemoryStoreEntry, query: AgentMemorySecondaryIndexQuery) -> bool:
    """回查正式记忆后再次验证范围边界。"""

    memory = entry.memory
    if entry.expires_at <= datetime.now(timezone.utc):
        return False
    if memory.memory_type != query.target.memory_type or memory.scope != query.target.scope:
        return False
    if entry.workspace_key != query.workspace_key or entry.memory_namespace != query.memory_namespace:
        return False
    if memory.scope == AgentMemoryScope.SESSION:
        return (
            memory.tenant_id == query.tenant_id
            and memory.project_id == query.project_id
            and memory.session_id == query.session_id
        )
    if memory.scope == AgentMemoryScope.PROJECT:
        return memory.tenant_id == query.tenant_id and memory.project_id == query.project_id
    if memory.scope == AgentMemoryScope.TENANT:
        return memory.tenant_id == query.tenant_id
    return memory.scope == AgentMemoryScope.GLOBAL


def _vector_literal(embedding: tuple[float, ...]) -> str:
    """把有限浮点数组转换为 pgvector 文本格式。

    向量内容通过 SQL 参数传入，再由 `CAST(... AS vector)` 解析，不直接拼接到 SQL。
    """

    return "[" + ",".join(format(value, ".12g") for value in embedding) + "]"


def _embedding_id(memory_id: str, model: str, fingerprint: str) -> str:
    """生成稳定 embeddingId，避免泄露正文且支持重复 upsert。"""

    digest = hashlib.sha256(f"{memory_id}|{model}|{fingerprint}".encode("utf-8")).hexdigest()
    return f"memory-embedding-{digest}"


def _row_value(row: Any, key: str, index: int) -> Any:
    """兼容 psycopg dict_row 与普通 tuple row。"""

    return row[key] if hasattr(row, "keys") else row[index]


def _validate_identifier(value: str, field_name: str) -> None:
    """校验 schema identifier，避免配置值直接形成 SQL 注入面。"""

    if not _IDENTIFIER_PATTERN.fullmatch(value):
        raise ValueError(f"{field_name} 只能包含字母、数字和下划线，且必须以字母或下划线开头。")


__all__ = [
    "PgvectorAgentMemorySecondaryIndex",
    "PgvectorMemoryIndexSettings",
    "PgvectorMemoryIndexUpsertResult",
]
