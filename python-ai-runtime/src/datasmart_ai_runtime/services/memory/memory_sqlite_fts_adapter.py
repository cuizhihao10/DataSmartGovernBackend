"""SQLite FTS5 长期记忆全文索引适配器。

这个模块用于补齐 Agent 长期记忆里的 `KEYWORD` 二级索引能力。它不是新的事实源，也不替代
`SqlAgentMemoryStore` 或 Chroma 语义索引，而是作为本地学习、单机部署和轻量客户现场可用的全文检索通道：

- 正式记忆仍以 `AgentMemoryStore` 为事实源，FTS 表只保存可重建的检索索引；
- 查询必须先在 SQLite metadata 中做 tenant/project/session/workspace 过滤，再返回候选；
- 返回候选前还会二次回查正式 store，避免索引表滞后、过期或残留时产生越权召回；
- 响应 attributes 只暴露低敏索引状态和计数，不返回记忆正文、SQL、prompt、工具参数或样本数据。
"""

from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any

from datasmart_ai_runtime.domain.memory import AgentMemoryScope
from datasmart_ai_runtime.services.memory.memory_secondary_index import (
    AgentMemorySecondaryIndexKind,
    AgentMemorySecondaryIndexQuery,
    AgentMemorySecondaryIndexResult,
)
from datasmart_ai_runtime.services.memory.memory_store import AgentMemoryStore, AgentMemoryStoreEntry


_IDENTIFIER_PATTERN = re.compile(r"^[A-Za-z_][A-Za-z0-9_]{0,63}$")
_TERM_PATTERN = re.compile(r"[\w\u4e00-\u9fff-]{2,}", re.UNICODE)


@dataclass(frozen=True)
class SQLiteFtsMemoryIndexSettings:
    """SQLite FTS 适配器运行参数。

    字段说明：
    - `fts_table_name`：FTS5 虚拟表名。它属于本地索引实现细节，必须经过标识符校验后才能拼入 SQL；
    - `metadata_table_name`：低敏 metadata 表名，用于在 FTS 命中前先执行范围过滤；
    - `document_max_chars`：进入全文索引的最大字符数，防止异常长摘要拖慢本地索引和测试；
    - `max_query_terms`：从 queryHint/objective 抽取的最大检索词数，避免用户输入过长导致 MATCH 表达式膨胀；
    - `auto_commit`：是否由适配器提交事务。本地测试通常开启，生产 outbox 同事务场景可以关闭；
    - `payload_policy`：对外诊断用的低敏策略说明，提醒调用方索引结果不回显记忆正文。
    """

    fts_table_name: str = "agent_memory_sqlite_fts"
    metadata_table_name: str = "agent_memory_sqlite_fts_metadata"
    document_max_chars: int = 4000
    max_query_terms: int = 12
    auto_commit: bool = True
    payload_policy: str = "LOW_SENSITIVE_INDEX_METADATA_ONLY_NO_MEMORY_BODY"


@dataclass(frozen=True)
class SQLiteFtsMemoryIndexUpsertResult:
    """单条正式记忆写入 SQLite FTS 索引后的低敏结果。

    这里刻意只返回摘要字段：
    - `memory_id` / `source_candidate_id` 用于测试和后台对账；
    - `indexed` 表示是否进入索引；
    - `removed_expired` 表示过期记忆被清理而不是继续索引；
    - `content_digest` 是正文摘要指纹，不包含正文；
    - `indexed_char_count` 只用于容量诊断，不代表正文外泄。
    """

    memory_id: str
    source_candidate_id: str
    indexed: bool
    removed_expired: bool
    content_digest: str
    indexed_char_count: int


class SQLiteFtsAgentMemorySecondaryIndex:
    """基于 SQLite FTS5 的长期记忆 KEYWORD 二级索引。

    商业化定位：
    - 对中小规模、本地部署、离线演示环境，SQLite FTS 可以让记忆检索不再退化为全量扫描；
    - 对生产大规模环境，本类提供的是接口和治理语义样板，后续可以替换为 MySQL FULLTEXT、OpenSearch、
      pgvector hybrid search 或企业搜索服务，而不改变 `StoreBackedAgentMemoryRetriever` 的上层契约。

    安全边界：
    - 索引写入只接受已经通过审批并落成的正式记忆，不能直接索引工具原始输出；
    - 查询时先用 metadata 表过滤 tenant/project/session/workspace，再做 FTS 命中；
    - 返回前必须回查正式 store 并重新校验范围，避免索引残留带来跨 workspace 泄漏；
    - attributes 不返回命中的正文片段、snippet、SQL、prompt、工具参数或样本数据。
    """

    kind = AgentMemorySecondaryIndexKind.KEYWORD

    def __init__(
        self,
        *,
        connection: Any,
        memory_store: AgentMemoryStore,
        settings: SQLiteFtsMemoryIndexSettings | None = None,
        ensure_schema: bool = True,
    ) -> None:
        """创建 SQLite FTS 二级索引适配器。

        参数说明：
        - `connection`：sqlite3 兼容连接。适配器只依赖 DB-API 的 cursor/execute/commit 能力；
        - `memory_store`：正式记忆事实源。FTS 命中后必须回查它，不能把索引表当作最终事实；
        - `settings`：索引表名、文档长度、查询词数量和提交策略；
        - `ensure_schema`：是否在构造时自动建表。测试和本地开发建议开启，生产迁移可以显式调用。
        """

        self._connection = connection
        self._memory_store = memory_store
        self._settings = settings or SQLiteFtsMemoryIndexSettings()
        _validate_identifier(self._settings.fts_table_name, "fts_table_name")
        _validate_identifier(self._settings.metadata_table_name, "metadata_table_name")
        if ensure_schema:
            self.ensure_schema()

    def ensure_schema(self) -> None:
        """初始化 SQLite FTS5 schema。

        FTS 表负责倒排索引，metadata 表负责范围过滤和索引生命周期管理。之所以不把 tenant/project 等字段
        直接放进 FTS MATCH 条件，是因为权限边界应该是结构化等值过滤，而不是让全文检索语法参与安全判断。
        """

        if not sqlite_fts5_available(self._connection):
            raise RuntimeError("当前 sqlite3 运行时未启用 FTS5，无法创建长期记忆本地全文索引。")
        self._execute(
            f"""
            CREATE TABLE IF NOT EXISTS {self._settings.metadata_table_name} (
                memory_id TEXT PRIMARY KEY,
                source_candidate_id TEXT NOT NULL UNIQUE,
                tenant_id TEXT,
                project_id TEXT,
                session_id TEXT,
                memory_type TEXT NOT NULL,
                scope TEXT NOT NULL,
                workspace_key TEXT NOT NULL,
                memory_namespace TEXT NOT NULL,
                sensitivity_level TEXT NOT NULL,
                expires_at TEXT NOT NULL,
                materialized_at TEXT NOT NULL,
                content_digest TEXT NOT NULL,
                indexed_at TEXT NOT NULL
            )
            """
        )
        self._execute(
            f"""
            CREATE VIRTUAL TABLE IF NOT EXISTS {self._settings.fts_table_name}
            USING fts5(memory_id UNINDEXED, title, content, tags, tokenize='unicode61')
            """
        )
        self._commit()

    def upsert_entry(self, entry: AgentMemoryStoreEntry) -> SQLiteFtsMemoryIndexUpsertResult:
        """把一条正式长期记忆写入或更新到本地 FTS 索引。

        写入流程：
        1. 先删除同 `memory_id` 的旧索引行，保证重建、补偿和重复同步是幂等的；
        2. 如果正式记忆已经过期，只删除旧索引并返回 `removed_expired=True`；
        3. 截断可检索文本长度，写入 FTS 表和 metadata 表；
        4. metadata 中只保存范围过滤字段和 digest，不保存额外正文副本。
        """

        document = self._document(entry)
        content_digest = hashlib.sha256(document.encode("utf-8")).hexdigest()
        self._delete_entry(entry.memory.memory_id)
        if entry.expires_at <= datetime.now(timezone.utc):
            self._commit()
            return SQLiteFtsMemoryIndexUpsertResult(
                memory_id=entry.memory.memory_id,
                source_candidate_id=entry.source_candidate_id,
                indexed=False,
                removed_expired=True,
                content_digest=content_digest,
                indexed_char_count=0,
            )
        self._execute(
            f"INSERT INTO {self._settings.fts_table_name} (memory_id, title, content, tags) VALUES (?, ?, ?, ?)",
            (
                entry.memory.memory_id,
                entry.memory.title[: self._settings.document_max_chars],
                document,
                " ".join(entry.memory.tags),
            ),
        )
        self._execute(
            f"""
            INSERT INTO {self._settings.metadata_table_name} (
                memory_id, source_candidate_id, tenant_id, project_id, session_id, memory_type, scope,
                workspace_key, memory_namespace, sensitivity_level, expires_at, materialized_at,
                content_digest, indexed_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                entry.memory.memory_id,
                entry.source_candidate_id,
                entry.memory.tenant_id,
                entry.memory.project_id,
                entry.memory.session_id,
                entry.memory.memory_type.value,
                entry.memory.scope.value,
                entry.workspace_key,
                entry.memory_namespace,
                entry.memory.sensitivity_level,
                _format_datetime(entry.expires_at),
                _format_datetime(entry.materialized_at),
                content_digest,
                _format_datetime(datetime.now(timezone.utc)),
            ),
        )
        self._commit()
        return SQLiteFtsMemoryIndexUpsertResult(
            memory_id=entry.memory.memory_id,
            source_candidate_id=entry.source_candidate_id,
            indexed=True,
            removed_expired=False,
            content_digest=content_digest,
            indexed_char_count=len(document),
        )

    def search(self, query: AgentMemorySecondaryIndexQuery) -> AgentMemorySecondaryIndexResult:
        """执行一次受范围保护的全文检索。

        检索词来自 `query.target.query_hint` 与 `query.objective`，但会被严格裁剪为普通词项并加引号，
        防止用户输入直接变成复杂 FTS 语法。FTS 命中后只拿 `source_candidate_id` 回查正式 store，
        最终返回的仍是 `AgentMemoryStoreEntry`，以便上层继续复用统一的记忆检索报告。
        """

        terms = _extract_query_terms(
            f"{query.target.query_hint} {query.objective}",
            max_terms=self._settings.max_query_terms,
        )
        if not terms:
            return AgentMemorySecondaryIndexResult(
                skipped_reason="缺少可用于 SQLite FTS 的检索词，已跳过本地全文索引。",
                attributes=self._attributes(query, query_terms=(), raw_candidate_count=0, returned_count=0),
            )
        rows = self._query_rows(query, terms)
        entries = self._resolve_entries(query, rows)
        return AgentMemorySecondaryIndexResult(
            entries=tuple(entries),
            attributes=self._attributes(
                query,
                query_terms=terms,
                raw_candidate_count=len(rows),
                returned_count=len(entries),
            ),
        )

    def cleanup_expired(self, *, now: datetime | None = None) -> int:
        """清理已经过期的 FTS metadata 与倒排索引记录。

        本方法服务于本地后台维护或测试。真实生产环境可以把它接到周期性 worker，并把删除数量映射成
        低基数指标；不要把被删除的 memoryId 列表写入高频事件或 Prometheus label。
        """

        cutoff = _format_datetime(now or datetime.now(timezone.utc))
        cursor = self._execute(
            f"SELECT memory_id FROM {self._settings.metadata_table_name} WHERE expires_at <= ?",
            (cutoff,),
        )
        memory_ids = tuple(str(row[0]) for row in cursor.fetchall())
        for memory_id in memory_ids:
            self._delete_entry(memory_id)
        self._commit()
        return len(memory_ids)

    def diagnostics(self) -> dict[str, object]:
        """返回 SQLite FTS 运行诊断。

        诊断只包含 schema、表名、payloadPolicy、FTS5 是否可用和索引行数，不返回任何记忆内容、查询词、
        workspace 明细或内部文件路径。
        """

        return {
            "indexKind": self.kind.value,
            "implementation": type(self).__name__,
            "schemaVersion": "datasmart.memory.sqlite-fts.v1",
            "payloadPolicy": self._settings.payload_policy,
            "fts5Available": sqlite_fts5_available(self._connection),
            "indexedMemoryCount": self._count_rows(),
            "documentMaxChars": max(100, self._settings.document_max_chars),
            "maxQueryTerms": max(1, self._settings.max_query_terms),
        }

    def _query_rows(
        self,
        query: AgentMemorySecondaryIndexQuery,
        terms: tuple[str, ...],
    ) -> tuple[tuple[str, str], ...]:
        """在 SQLite 中执行结构化范围过滤 + FTS MATCH。

        返回值只保留 `(source_candidate_id, memory_id)`，不读取 title/content/tags。这样即使命中结果需要上层
        继续处理，也不会通过索引层 attributes 把正文片段扩散出去。
        """

        sql = [
            f"SELECT meta.source_candidate_id, meta.memory_id, bm25({self._settings.fts_table_name}) AS rank",
            f"FROM {self._settings.fts_table_name}",
            f"JOIN {self._settings.metadata_table_name} meta",
            f"ON meta.memory_id = {self._settings.fts_table_name}.memory_id",
            f"WHERE {self._settings.fts_table_name} MATCH ?",
            "AND meta.memory_type = ?",
            "AND meta.scope = ?",
            "AND meta.workspace_key = ?",
            "AND meta.memory_namespace = ?",
            "AND meta.expires_at > ?",
        ]
        params: list[Any] = [
            _fts_match_expression(terms),
            query.target.memory_type.value,
            query.target.scope.value,
            query.workspace_key,
            query.memory_namespace,
            _format_datetime(datetime.now(timezone.utc)),
        ]
        if query.target.scope == AgentMemoryScope.SESSION:
            sql.extend(["AND meta.tenant_id = ?", "AND meta.project_id = ?", "AND meta.session_id = ?"])
            params.extend([query.tenant_id, query.project_id, query.session_id])
        elif query.target.scope == AgentMemoryScope.PROJECT:
            sql.extend(["AND meta.tenant_id = ?", "AND meta.project_id = ?"])
            params.extend([query.tenant_id, query.project_id])
        elif query.target.scope == AgentMemoryScope.TENANT:
            sql.append("AND meta.tenant_id = ?")
            params.append(query.tenant_id)
        sql.append("ORDER BY rank ASC, meta.materialized_at DESC LIMIT ?")
        params.append(max(1, min(query.candidate_limit, 200)))
        cursor = self._execute(" ".join(sql), tuple(params))
        return tuple((str(row[0]), str(row[1])) for row in cursor.fetchall())

    def _resolve_entries(
        self,
        query: AgentMemorySecondaryIndexQuery,
        rows: tuple[tuple[str, str], ...],
    ) -> list[AgentMemoryStoreEntry]:
        """把 FTS 命中的候选 ID 解析为正式记忆 entry，并重新执行安全过滤。"""

        entries: list[AgentMemoryStoreEntry] = []
        seen_memory_ids: set[str] = set()
        for source_candidate_id, memory_id in rows:
            if memory_id in seen_memory_ids:
                continue
            entry = self._memory_store.get_by_candidate_id(source_candidate_id)
            if entry and _entry_matches_query(entry, query):
                entries.append(entry)
                seen_memory_ids.add(memory_id)
        return entries

    def _document(self, entry: AgentMemoryStoreEntry) -> str:
        """构造进入 FTS 的低敏文档。

        正式记忆本身应已经是审批后的低敏摘要。这里仍做长度上限，是为了避免一次异常物化把本地索引膨胀
        成隐形大对象存储。原始工具结果、SQL、样本数据和模型输出不应该出现在正式记忆 content 中。
        """

        text = f"{entry.memory.title}\n{entry.memory.content}\n{' '.join(entry.memory.tags)}".strip()
        return text[: max(100, self._settings.document_max_chars)]

    def _delete_entry(self, memory_id: str) -> None:
        """按 memoryId 删除 FTS 与 metadata 中的旧索引行。"""

        self._execute(f"DELETE FROM {self._settings.fts_table_name} WHERE memory_id = ?", (memory_id,))
        self._execute(f"DELETE FROM {self._settings.metadata_table_name} WHERE memory_id = ?", (memory_id,))

    def _attributes(
        self,
        query: AgentMemorySecondaryIndexQuery,
        *,
        query_terms: tuple[str, ...],
        raw_candidate_count: int,
        returned_count: int,
    ) -> dict[str, object]:
        """生成低敏检索摘要，供报告和诊断使用。"""

        return {
            "indexKind": self.kind.value,
            "implementation": type(self).__name__,
            "payloadPolicy": self._settings.payload_policy,
            "queryTermCount": len(query_terms),
            "candidateLimit": query.candidate_limit,
            "rawCandidateCount": raw_candidate_count,
            "candidateCount": returned_count,
            "scope": query.target.scope.value,
            "memoryType": query.target.memory_type.value,
            "memoryBodyReturnedInAttributes": False,
        }

    def _count_rows(self) -> int:
        """统计 metadata 行数，用于诊断索引规模。"""

        cursor = self._execute(f"SELECT COUNT(*) FROM {self._settings.metadata_table_name}")
        return int(cursor.fetchone()[0])

    def _execute(self, sql: str, params: tuple[Any, ...] = ()):
        """执行参数化 SQL。

        表名由 settings 给出，构造阶段已做白名单校验；用户输入、查询词和范围字段全部通过 params 传递。
        """

        cursor = self._connection.cursor()
        cursor.execute(sql, params)
        return cursor

    def _commit(self) -> None:
        """按配置提交事务，方便后续接入同事务 outbox。"""

        if self._settings.auto_commit:
            self._connection.commit()


def sqlite_fts5_available(connection: Any) -> bool:
    """检测当前 sqlite3 运行时是否支持 FTS5。"""

    try:
        connection.execute("CREATE VIRTUAL TABLE IF NOT EXISTS __datasmart_fts5_probe USING fts5(value)")
        connection.execute("DROP TABLE IF EXISTS __datasmart_fts5_probe")
        return True
    except Exception:
        return False


def sqlite_fts_memory_index_diagnostics(connection: Any) -> dict[str, object]:
    """生成不依赖适配器实例的 SQLite FTS 能力诊断。"""

    return {
        "schemaVersion": "datasmart.memory.sqlite-fts.diagnostics.v1",
        "diagnosticType": "SQLITE_FTS_MEMORY_INDEX",
        "payloadPolicy": "LOW_SENSITIVE_RUNTIME_CAPABILITY_ONLY",
        "fts5Available": sqlite_fts5_available(connection),
    }


def _entry_matches_query(entry: AgentMemoryStoreEntry, query: AgentMemorySecondaryIndexQuery) -> bool:
    """二次校验正式 store entry 是否仍属于本次查询边界。"""

    memory = entry.memory
    if entry.expires_at <= datetime.now(timezone.utc):
        return False
    if memory.memory_type != query.target.memory_type or memory.scope != query.target.scope:
        return False
    if entry.workspace_key != query.workspace_key or entry.memory_namespace != query.memory_namespace:
        return False
    if memory.scope == AgentMemoryScope.SESSION:
        return memory.tenant_id == query.tenant_id and memory.project_id == query.project_id and memory.session_id == query.session_id
    if memory.scope == AgentMemoryScope.PROJECT:
        return memory.tenant_id == query.tenant_id and memory.project_id == query.project_id
    if memory.scope == AgentMemoryScope.TENANT:
        return memory.tenant_id == query.tenant_id
    return memory.scope == AgentMemoryScope.GLOBAL


def _extract_query_terms(text: str, *, max_terms: int) -> tuple[str, ...]:
    """从用户目标和 queryHint 中抽取安全 FTS 词项。"""

    terms: list[str] = []
    seen: set[str] = set()
    for match in _TERM_PATTERN.finditer(text.lower()):
        term = match.group(0).strip("-_")
        if not term or term in seen:
            continue
        seen.add(term)
        terms.append(term)
        if len(terms) >= max(1, max_terms):
            break
    return tuple(terms)


def _fts_match_expression(terms: tuple[str, ...]) -> str:
    """把词项转换成安全的 FTS5 MATCH 表达式。"""

    return " OR ".join(json.dumps(term, ensure_ascii=False) for term in terms)


def _format_datetime(value: datetime) -> str:
    """统一使用 UTC DATETIME(3) 字符串，便于 SQLite 字符串比较和 MySQL 迁移理解。"""

    return value.astimezone(timezone.utc).strftime("%Y-%m-%d %H:%M:%S.%f")[:-3]


def _validate_identifier(value: str, field_name: str) -> None:
    """校验被拼入 SQL 的表名，避免配置错误形成 SQL 注入面。"""

    if not _IDENTIFIER_PATTERN.fullmatch(value):
        raise ValueError(f"{field_name} 只能包含字母、数字和下划线，且必须以字母或下划线开头。")


__all__ = [
    "SQLiteFtsAgentMemorySecondaryIndex",
    "SQLiteFtsMemoryIndexSettings",
    "SQLiteFtsMemoryIndexUpsertResult",
    "sqlite_fts5_available",
    "sqlite_fts_memory_index_diagnostics",
]
