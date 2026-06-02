"""正式长期记忆的关系型数据库 store。

候选审批通过后，`AgentApprovedMemoryWriteMaterializer` 会把低敏摘要转换为正式长期记忆。此前正式记忆
只有 `InMemoryAgentMemoryStore`，适合单元测试和本地学习，但不适合真实产品：
- Python Runtime 重启后，已批准并落成的记忆会消失；
- 多实例部署时，每个实例看到的正式记忆不同；
- 审计和补偿无法从数据库反查“某个候选到底落成了哪条正式记忆”。

本文件提供 DB-API 风格的 SQL store。它不是最终向量检索引擎，而是正式长期记忆的 durable control-plane
baseline：保存低敏正文、范围字段、workspace namespace、幂等键、候选来源和过期时间。后续 Chroma、Neo4j、
MinIO 或对象索引可以围绕同一 `memory_id` 扩展，而不需要推翻 materializer 与 retriever 的协议。
"""

from __future__ import annotations

import json
from datetime import datetime, timezone
from typing import Any

from datasmart_ai_runtime.domain.memory import AgentMemoryRecord, AgentMemoryScope, AgentMemoryType
from datasmart_ai_runtime.services.memory.memory_store import (
    AgentMemoryStoreEntry,
    AgentMemoryStoreWriteResult,
)


class SqlAgentMemoryStore:
    """基于关系型数据库的正式长期记忆 store。

    设计边界：
    - 本类只负责正式记忆的幂等写入、按 candidate 反查和范围检索；
    - 本类不负责向量化、Embedding、图谱写入或对象存储解析；
    - 本类不直接做权限判断，但会把 tenant/project/session/workspace 过滤下沉到 SQL；
    - 本类不创建连接池，连接生命周期由启动配置、worker 或测试夹具管理。

    为什么先做 SQL 正式记忆：
    企业 Agent 的长期记忆并不全是向量。审批证据、来源候选、保留期、workspace namespace、数据范围和
    幂等键都更适合先进入关系型控制面表。向量库可以作为检索加速层，但不能成为唯一事实源。
    """

    def __init__(
        self,
        connection: Any,
        *,
        placeholder: str = "?",
        auto_commit: bool = True,
    ) -> None:
        """创建 SQL 正式记忆 store。

        参数说明：
        - `connection`：符合 Python DB-API 的连接对象，例如 sqlite3 连接、pymysql 连接或未来封装后的连接；
        - `placeholder`：参数占位符。sqlite 使用 `?`，pymysql/mysqlclient 通常使用 `%s`；
        - `auto_commit`：是否在写入后立即提交。测试和简单 worker 可以打开；如果外层要把 receipt、outbox、
          二级索引写入放进同一事务，应关闭并由调用方统一 commit/rollback。

        这里刻意不创建连接池。连接池属于运行时装配/基础设施职责，如果 store 自己创建连接池，会让单测、
        本地脚本、MySQL 部署和未来 Java memory-service 代理之间的边界变模糊。
        """

        self._connection = connection
        self._placeholder = placeholder
        self._auto_commit = auto_commit

    def save_if_absent(self, entry: AgentMemoryStoreEntry) -> AgentMemoryStoreWriteResult:
        """按幂等键保存正式记忆。

        幂等顺序：
        1. 先按 `idempotency_key` 查询，处理 worker 重试、消息重复投递和 HTTP 补偿重复调用；
        2. 再按 `source_candidate_id` 查询，兜底处理历史数据没有幂等键但候选 ID 稳定的场景；
        3. 最后检查 `memory_id` 是否冲突，避免不同候选写入同一正式记忆 ID。

        如果找到已有记录，返回 `created=False`，调用方应视为成功确认，而不是失败。
        """

        existing = self._get_by_unique_key(
            idempotency_key=entry.idempotency_key,
            source_candidate_id=entry.source_candidate_id,
        )
        if existing:
            return AgentMemoryStoreWriteResult(existing, created=False)
        memory_id_conflict = self._get_by_memory_id(entry.memory.memory_id)
        if memory_id_conflict:
            raise ValueError(f"正式记忆 ID 冲突: {entry.memory.memory_id}")
        self._execute(self._insert_sql(), self._insert_params(entry))
        self._commit()
        return AgentMemoryStoreWriteResult(entry, created=True)

    def get_by_candidate_id(self, candidate_id: str) -> AgentMemoryStoreEntry | None:
        """按审批候选 ID 反查正式记忆。"""

        cursor = self._execute(
            f"SELECT {self._columns()} FROM agent_memory_store_entry "
            f"WHERE source_candidate_id = {self._placeholder}",
            (candidate_id,),
        )
        row = cursor.fetchone()
        return self._entry_from_row(row) if row else None

    def search(
        self,
        *,
        memory_type: AgentMemoryType,
        scope: AgentMemoryScope,
        tenant_id: str | None,
        project_id: str | None,
        session_id: str | None,
        memory_namespace: str | None = None,
        limit: int = 100,
    ) -> tuple[AgentMemoryStoreEntry, ...]:
        """按范围和 workspace namespace 检索正式记忆。

        这里把过滤条件下沉到 SQL，而不是读取全量后在 Python 内存里筛选。真实商业环境中，长期记忆可能
        逐步增长到百万级，如果先全量读取再过滤，会同时带来性能问题和越权风险。
        """

        safe_limit = max(1, min(limit, 500))
        sql = [
            f"SELECT {self._columns()} FROM agent_memory_store_entry WHERE memory_type = {self._placeholder}",
            f"AND scope = {self._placeholder}",
            f"AND expires_at > {self._placeholder}",
        ]
        params: list[Any] = [memory_type.value, scope.value, self._format_datetime(datetime.now(timezone.utc))]
        if memory_namespace:
            sql.append(f"AND memory_namespace = {self._placeholder}")
            params.append(memory_namespace)
        else:
            # 与内存实现保持一致：缺少 workspace namespace 时 fail-closed。
            sql.append("AND 1 = 0")
        if scope == AgentMemoryScope.SESSION:
            sql.extend(
                [
                    f"AND tenant_id = {self._placeholder}",
                    f"AND project_id = {self._placeholder}",
                    f"AND session_id = {self._placeholder}",
                ]
            )
            params.extend([tenant_id, project_id, session_id])
        elif scope == AgentMemoryScope.PROJECT:
            sql.extend([f"AND tenant_id = {self._placeholder}", f"AND project_id = {self._placeholder}"])
            params.extend([tenant_id, project_id])
        elif scope == AgentMemoryScope.TENANT:
            sql.append(f"AND tenant_id = {self._placeholder}")
            params.append(tenant_id)
        sql.append(f"ORDER BY materialized_at DESC LIMIT {self._placeholder}")
        params.append(safe_limit)
        cursor = self._execute(" ".join(sql), tuple(params))
        return tuple(self._entry_from_row(row) for row in cursor.fetchall())

    def _get_by_unique_key(self, *, idempotency_key: str, source_candidate_id: str) -> AgentMemoryStoreEntry | None:
        """按幂等键或候选 ID 查询已有正式记忆。"""

        cursor = self._execute(
            f"SELECT {self._columns()} FROM agent_memory_store_entry "
            f"WHERE idempotency_key = {self._placeholder} OR source_candidate_id = {self._placeholder} "
            "ORDER BY materialized_at ASC",
            (idempotency_key, source_candidate_id),
        )
        row = cursor.fetchone()
        return self._entry_from_row(row) if row else None

    def _get_by_memory_id(self, memory_id: str) -> AgentMemoryStoreEntry | None:
        """按正式记忆 ID 查询记录。"""

        cursor = self._execute(
            f"SELECT {self._columns()} FROM agent_memory_store_entry WHERE memory_id = {self._placeholder}",
            (memory_id,),
        )
        row = cursor.fetchone()
        return self._entry_from_row(row) if row else None

    def _execute(self, sql: str, params: tuple[Any, ...] = ()):
        """执行一条参数化 SQL。

        所有外部输入都通过 params 传递，而不是字符串拼接。长期记忆虽然主要保存模型可读摘要，但摘要标题、
        标签和来源字段仍然来自工具反馈或审批流程，必须保持参数化写法，避免 SQL 注入或特殊字符破坏语句。
        """

        cursor = self._connection.cursor()
        cursor.execute(sql, params)
        return cursor

    def _commit(self) -> None:
        """按配置提交事务。

        `auto_commit=False` 的场景通常出现在更成熟的 worker 中：正式记忆主表、materialization receipt、
        向量索引 outbox 和审计事件可能需要由同一个上层事务协调。当前 store 保留这个开关，是为了避免
        后续接 outbox 时再重写持久化协议。
        """

        if self._auto_commit:
            self._connection.commit()

    def _insert_sql(self) -> str:
        """构造插入 SQL。

        当前不使用 MySQL 专属的 `INSERT ... ON DUPLICATE KEY UPDATE`，是为了让 sqlite 单测、MySQL 生产
        和未来 PostgreSQL 迁移共享同一套领域语义。幂等判断由 `save_if_absent()` 先查后写完成。
        """

        placeholders = ",".join([self._placeholder] * len(self._columns().split(",")))
        return f"INSERT INTO agent_memory_store_entry ({self._columns()}) VALUES ({placeholders})"

    @staticmethod
    def _columns() -> str:
        """返回主表字段顺序。

        `_insert_params()` 与 `_entry_from_row()` 都依赖这个顺序。把字段集中在这里，可以降低“新增列时只改了
        insert 没改 row mapping”的风险。真实生产中还应由迁移脚本和集成测试共同保护该契约。
        """

        return (
            "memory_id,tenant_id,project_id,session_id,memory_type,scope,title,content,source,importance_score,"
            "sensitivity_level,tags_json,created_at,attributes_json,workspace_key,memory_namespace,namespace_json,"
            "idempotency_key,source_candidate_id,expires_at,materialized_at"
        )

    def _insert_params(self, entry: AgentMemoryStoreEntry) -> tuple[Any, ...]:
        """把领域对象转换为数据库参数。

        这里保存的是正式记忆的低敏摘要与治理元数据，不保存候选 `output_ref` 指向的原始工具结果。这样做是
        为了避免长期记忆表变成敏感数据的二次扩散面：模型需要的是可复用经验摘要，原始数据访问应继续经过
        资源引用 resolver、权限校验和上下文准入策略。
        """

        memory = entry.memory
        return (
            memory.memory_id,
            memory.tenant_id,
            memory.project_id,
            memory.session_id,
            memory.memory_type.value,
            memory.scope.value,
            memory.title,
            memory.content,
            memory.source,
            memory.importance_score,
            memory.sensitivity_level,
            self._json(memory.tags),
            self._format_datetime(memory.created_at),
            self._json(memory.attributes),
            entry.workspace_key,
            entry.memory_namespace,
            self._json(entry.namespace),
            entry.idempotency_key,
            entry.source_candidate_id,
            self._format_datetime(entry.expires_at),
            self._format_datetime(entry.materialized_at),
        )

    @staticmethod
    def _entry_from_row(row: Any) -> AgentMemoryStoreEntry:
        """把数据库行转换回正式记忆信封。

        sqlite3.Row、dict cursor 和普通 tuple cursor 的返回结构不同，因此这里同时兼容三类形态。这样测试
        可以使用 sqlite3，生产可以使用 MySQL 驱动，后续如果换成 PostgreSQL DB-API 连接也不需要改领域层。
        """

        values = dict(row) if hasattr(row, "keys") else dict(zip(SqlAgentMemoryStore._columns().split(","), row))
        memory = AgentMemoryRecord(
            memory_id=values["memory_id"],
            memory_type=AgentMemoryType(values["memory_type"]),
            scope=AgentMemoryScope(values["scope"]),
            tenant_id=values["tenant_id"],
            project_id=values["project_id"],
            session_id=values["session_id"],
            title=values["title"],
            content=values["content"],
            source=values["source"] or "",
            importance_score=float(values["importance_score"]),
            sensitivity_level=values["sensitivity_level"],
            tags=tuple(SqlAgentMemoryStore._loads(values["tags_json"]) or ()),
            created_at=SqlAgentMemoryStore._parse_datetime(values["created_at"]) or datetime.now(timezone.utc),
            attributes=SqlAgentMemoryStore._loads(values["attributes_json"]) or {},
        )
        return AgentMemoryStoreEntry(
            memory=memory,
            workspace_key=values["workspace_key"],
            memory_namespace=values["memory_namespace"],
            namespace=tuple(SqlAgentMemoryStore._loads(values["namespace_json"]) or ()),
            idempotency_key=values["idempotency_key"],
            source_candidate_id=values["source_candidate_id"],
            expires_at=SqlAgentMemoryStore._parse_datetime(values["expires_at"]) or datetime.now(timezone.utc),
            materialized_at=SqlAgentMemoryStore._parse_datetime(values["materialized_at"])
            or datetime.now(timezone.utc),
        )

    @staticmethod
    def _json(value: Any) -> str:
        """序列化 JSON 字段。

        `ensure_ascii=False` 保留中文标签和说明，方便审计台、排障日志和数据库管理工具直接阅读；`sort_keys`
        让同一对象得到稳定字符串，便于后续做差异检查、快照测试或幂等摘要。
        """

        return json.dumps(value, ensure_ascii=False, sort_keys=True)

    @staticmethod
    def _loads(value: str | None) -> Any:
        """读取 JSON 字段并恢复不可变 tuple 结构。

        JSON 天然没有 tuple，写入时 tuple 会变成 list。长期记忆领域对象使用 tuple 表达“调用方不应随意修改
        tags/namespace”，所以读取时恢复 tuple，避免上层在无意中修改 store 返回的历史快照。
        """

        if not value:
            return None
        return SqlAgentMemoryStore._restore_json_value(json.loads(value))

    @staticmethod
    def _restore_json_value(value: Any) -> Any:
        if isinstance(value, list):
            return tuple(SqlAgentMemoryStore._restore_json_value(item) for item in value)
        if isinstance(value, dict):
            return {key: SqlAgentMemoryStore._restore_json_value(item) for key, item in value.items()}
        return value

    @staticmethod
    def _format_datetime(value: datetime | None) -> str | None:
        """把时间转换成 MySQL DATETIME(3) 友好的 UTC 字符串。

        sqlite 可以接受 ISO `2026-06-02T10:00:00+00:00`，但 MySQL `DATETIME(3)` 更稳妥的格式是
        `2026-06-02 10:00:00.000`。这里统一写入 UTC 且只保留毫秒，既贴合迁移脚本的 DATETIME(3)，也避免
        不同数据库驱动对时区后缀的兼容差异。读取时 `_parse_datetime()` 仍兼容旧 ISO 字符串，方便平滑迁移。
        """

        if value is None:
            return None
        utc_value = value.astimezone(timezone.utc)
        return utc_value.strftime("%Y-%m-%d %H:%M:%S.%f")[:-3]

    @staticmethod
    def _parse_datetime(value: str | datetime | None) -> datetime | None:
        """解析数据库时间。

        不同 DB-API 驱动返回时间的方式不完全一样：
        - sqlite 测试夹具通常返回字符串；
        - MySQL 驱动可能直接返回 `datetime`；
        - 早期实现或手工补偿脚本可能留下带 `+00:00` 的 ISO 字符串。

        本方法统一把无时区时间视为 UTC。长期记忆的过期、召回窗口和审计排序都依赖时间一致性，因此不要在
        store 内混用本地时区。
        """

        if value is None:
            return None
        parsed = value if isinstance(value, datetime) else datetime.fromisoformat(value)
        return parsed.replace(tzinfo=timezone.utc) if parsed.tzinfo is None else parsed.astimezone(timezone.utc)
