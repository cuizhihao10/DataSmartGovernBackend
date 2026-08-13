"""LangGraph Durable Checkpointer 的 PostgreSQL 持久化实现。

本文件只负责把 `LangGraphCheckpointStore` 协议落到 PostgreSQL `ai_memory` schema 中的两张表：

- `langgraph_thread_checkpoint`：保存最新/历史 checkpoint；
- `langgraph_checkpoint_event`：保存节点、边、暂停、恢复、分支、循环等低敏事件。

为什么独立成文件：
- 领域服务不应该依赖 psycopg 或 DB-API 细节；
- 单元测试可以优先覆盖内存实现的状态机语义；
- 生产环境通过组件装配切换到 PostgreSQL，不需要改 LangGraph 节点代码。

本实现假设连接的 search_path 已包含 `ai_memory`，或 DSN 显式设置
`options=-c search_path=ai_memory`。建表和迁移仍由 `docker/postgresql/init/10-ai-memory-schema.sql`
或后续迁移工具负责，Runtime 不在启动时悄悄建表。
"""

from __future__ import annotations

import json
import hashlib
from datetime import datetime, timezone
from dataclasses import replace
from typing import Any, Mapping

from datasmart_ai_runtime.services.agent_execution.langgraph_durable_checkpointer import (
    LangGraphCheckpointEvent,
    LangGraphCheckpointStatus,
    LangGraphCheckpointStore,
    LangGraphDurableCheckpoint,
)


class PostgresLangGraphCheckpointStore(LangGraphCheckpointStore):
    """使用 PostgreSQL 保存 LangGraph checkpoint 和 event。

    连接对象只要求符合 Python DB-API：支持 `cursor()`、`execute()`、`fetchone()`、`fetchall()`、`commit()`。
    psycopg3 使用 dict_row 时会返回 dict-like row；如果某些测试替身返回 tuple，本类也按列顺序兼容。
    """

    def __init__(self, connection: Any, *, placeholder: str = "%s") -> None:
        self._connection = connection
        self._placeholder = placeholder

    def save_checkpoint(self, checkpoint: LangGraphDurableCheckpoint) -> LangGraphDurableCheckpoint:
        """插入或更新 checkpoint。

        `checkpoint_id` 是幂等键。重复保存同一个 checkpoint 时更新状态、state、next_nodes 和恢复条件；
        thread 内历史版本不会被删除，方便恢复到旧节点或审计回放。
        """

        normalized = _normalize_checkpoint(checkpoint)
        sql = f"""
            INSERT INTO langgraph_thread_checkpoint (
                checkpoint_id, thread_id, parent_checkpoint_id, tenant_id, project_id, actor_id,
                workspace_key, run_id, session_id, graph_name, graph_version, node_name, status,
                checkpoint_version, state_json, next_nodes_json, resume_requirements_json,
                low_sensitive_summary, expires_at, created_at, updated_at
            ) VALUES (
                {self._placeholder}, {self._placeholder}, {self._placeholder}, {self._placeholder}, {self._placeholder},
                {self._placeholder}, {self._placeholder}, {self._placeholder}, {self._placeholder}, {self._placeholder},
                {self._placeholder}, {self._placeholder}, {self._placeholder}, {self._placeholder},
                CAST({self._placeholder} AS jsonb), CAST({self._placeholder} AS jsonb),
                CAST({self._placeholder} AS jsonb), {self._placeholder}, {self._placeholder},
                {self._placeholder}, {self._placeholder}
            )
            ON CONFLICT (checkpoint_id) DO UPDATE SET
                thread_id = EXCLUDED.thread_id,
                parent_checkpoint_id = EXCLUDED.parent_checkpoint_id,
                tenant_id = EXCLUDED.tenant_id,
                project_id = EXCLUDED.project_id,
                actor_id = EXCLUDED.actor_id,
                workspace_key = EXCLUDED.workspace_key,
                run_id = EXCLUDED.run_id,
                session_id = EXCLUDED.session_id,
                graph_name = EXCLUDED.graph_name,
                graph_version = EXCLUDED.graph_version,
                node_name = EXCLUDED.node_name,
                status = EXCLUDED.status,
                checkpoint_version = EXCLUDED.checkpoint_version,
                state_json = EXCLUDED.state_json,
                next_nodes_json = EXCLUDED.next_nodes_json,
                resume_requirements_json = EXCLUDED.resume_requirements_json,
                low_sensitive_summary = EXCLUDED.low_sensitive_summary,
                expires_at = EXCLUDED.expires_at,
                updated_at = EXCLUDED.updated_at
        """
        self._execute(
            sql,
            (
                normalized.checkpoint_id,
                normalized.thread_id,
                normalized.parent_checkpoint_id,
                normalized.tenant_id,
                normalized.project_id,
                normalized.actor_id,
                normalized.workspace_key,
                normalized.run_id,
                normalized.session_id,
                normalized.graph_name,
                normalized.graph_version,
                normalized.node_name,
                normalized.status.value,
                normalized.checkpoint_version,
                json.dumps(normalized.state, ensure_ascii=False, sort_keys=True),
                json.dumps(list(normalized.next_nodes), ensure_ascii=False),
                json.dumps(normalized.resume_requirements, ensure_ascii=False, sort_keys=True),
                normalized.low_sensitive_summary,
                normalized.expires_at,
                normalized.created_at,
                normalized.updated_at,
            ),
        )
        self._commit()
        return normalized

    def get_checkpoint(self, checkpoint_id: str) -> LangGraphDurableCheckpoint | None:
        """按 checkpointId 查询。"""

        row = self._fetchone(
            f"SELECT {self._checkpoint_columns()} FROM langgraph_thread_checkpoint WHERE checkpoint_id = {self._placeholder}",
            (_bounded_text(checkpoint_id, 160),),
        )
        return _checkpoint_from_row(row) if row is not None else None

    def latest_for_thread(self, thread_id: str) -> LangGraphDurableCheckpoint | None:
        """读取 thread 最新 checkpoint。"""

        row = self._fetchone(
            (
                f"SELECT {self._checkpoint_columns()} FROM langgraph_thread_checkpoint "
                f"WHERE thread_id = {self._placeholder} "
                "ORDER BY checkpoint_version DESC, updated_at DESC LIMIT 1"
            ),
            (_bounded_text(thread_id, 160),),
        )
        return _checkpoint_from_row(row) if row is not None else None

    def append_event(self, event: LangGraphCheckpointEvent) -> LangGraphCheckpointEvent:
        """追加 checkpoint event。

        eventId 是幂等键；如果重放同一事件，不覆盖已存在事件，避免审计序列被意外改写。
        """

        normalized = _normalize_event(event)
        sql = f"""
            INSERT INTO langgraph_checkpoint_event (
                event_id, checkpoint_id, thread_id, tenant_id, project_id, run_id,
                event_type, node_name, edge_name, sequence_number, attributes_json, created_at
            ) VALUES (
                {self._placeholder}, {self._placeholder}, {self._placeholder}, {self._placeholder},
                {self._placeholder}, {self._placeholder}, {self._placeholder}, {self._placeholder},
                {self._placeholder}, {self._placeholder}, CAST({self._placeholder} AS jsonb),
                {self._placeholder}
            )
            ON CONFLICT (event_id) DO NOTHING
        """
        self._execute(
            sql,
            (
                normalized.event_id,
                normalized.checkpoint_id,
                normalized.thread_id,
                normalized.tenant_id,
                normalized.project_id,
                normalized.run_id,
                normalized.event_type,
                normalized.node_name,
                normalized.edge_name,
                normalized.sequence_number,
                json.dumps(normalized.attributes, ensure_ascii=False, sort_keys=True),
                normalized.created_at,
            ),
        )
        self._commit()
        return normalized

    def events_for_thread(self, thread_id: str) -> tuple[LangGraphCheckpointEvent, ...]:
        """读取 thread 事件流。"""

        rows = self._fetchall(
            (
                f"SELECT {self._event_columns()} FROM langgraph_checkpoint_event "
                f"WHERE thread_id = {self._placeholder} ORDER BY sequence_number ASC"
            ),
            (_bounded_text(thread_id, 160),),
        )
        return tuple(_event_from_row(row) for row in rows)

    def next_sequence(self, thread_id: str) -> int:
        """计算 thread 下一条事件序号。"""

        row = self._fetchone(
            f"SELECT COALESCE(MAX(sequence_number), 0) AS max_sequence FROM langgraph_checkpoint_event WHERE thread_id = {self._placeholder}",
            (thread_id,),
        )
        value = _row_get(row, "max_sequence", 0) if row is not None else 0
        try:
            return int(value) + 1
        except (TypeError, ValueError):
            return 1

    def diagnostics(self) -> dict[str, Any]:
        """返回不读取 checkpoint 正文的低敏诊断。"""

        return {
            "storeType": "postgresql",
            "checkpointCount": None,
            "checkpointCountReason": "COUNT_DISABLED_FOR_LIGHTWEIGHT_DIAGNOSTICS",
            "eventCount": None,
            "payloadPolicy": "LOW_SENSITIVE_LANGGRAPH_CHECKPOINT_ONLY",
        }

    def _execute(self, sql: str, params: tuple[Any, ...]) -> None:
        """执行 SQL 并兼容 context-manager / 普通 cursor。"""

        cursor = self._connection.cursor()
        try:
            cursor.execute(sql, params)
        except Exception:
            self._rollback_after_database_error()
            raise
        finally:
            close = getattr(cursor, "close", None)
            if callable(close):
                close()

    def _fetchone(self, sql: str, params: tuple[Any, ...]) -> Any | None:
        """执行查询并返回一行。"""

        cursor = self._connection.cursor()
        try:
            cursor.execute(sql, params)
            return cursor.fetchone()
        except Exception:
            self._rollback_after_database_error()
            raise
        finally:
            close = getattr(cursor, "close", None)
            if callable(close):
                close()

    def _fetchall(self, sql: str, params: tuple[Any, ...]) -> tuple[Any, ...]:
        """执行查询并返回多行。"""

        cursor = self._connection.cursor()
        try:
            cursor.execute(sql, params)
            return tuple(cursor.fetchall())
        except Exception:
            self._rollback_after_database_error()
            raise
        finally:
            close = getattr(cursor, "close", None)
            if callable(close):
                close()

    def _commit(self) -> None:
        """提交事务。"""

        commit = getattr(self._connection, "commit", None)
        if callable(commit):
            commit()

    def _rollback_after_database_error(self) -> None:
        """清理 PostgreSQL 失败事务，避免同一连接把后续 Recovery 请求全部拖入失败状态。

        psycopg 在一条 SQL 失败后会把连接标记为 ``transaction aborted``。如果这里只把异常抛回
        FastAPI，连接池下一次复用时会在完全无关的查询上继续报 ``InFailedSqlTransaction``，让
        Kafka 重试看起来像业务 Recovery 失败。rollback 不吞掉原异常，只恢复 DB-API 连接到可重试状态。
        """

        rollback = getattr(self._connection, "rollback", None)
        if callable(rollback):
            try:
                rollback()
            except Exception:
                # 原始 SQL 异常更有诊断价值；rollback 自身失败不能覆盖它。
                pass

    def _placeholders(self, count: int) -> str:
        """生成 DB-API placeholders。"""

        return ", ".join(self._placeholder for _ in range(count))

    @staticmethod
    def _checkpoint_columns() -> str:
        """checkpoint 查询列清单。"""

        return (
            "checkpoint_id,thread_id,parent_checkpoint_id,tenant_id,project_id,actor_id,"
            "workspace_key,run_id,session_id,graph_name,graph_version,node_name,status,"
            "checkpoint_version,state_json,next_nodes_json,resume_requirements_json,"
            "low_sensitive_summary,expires_at,created_at,updated_at"
        )

    @staticmethod
    def _event_columns() -> str:
        """event 查询列清单。"""

        return (
            "event_id,checkpoint_id,thread_id,tenant_id,project_id,run_id,event_type,"
            "node_name,edge_name,sequence_number,attributes_json,created_at"
        )


def _normalize_checkpoint(checkpoint: LangGraphDurableCheckpoint) -> LangGraphDurableCheckpoint:
    """按 ``ai_memory.langgraph_thread_checkpoint`` 的 VARCHAR 上限归一化 checkpoint。

    领域层允许较长的可读节点名和租户上下文，但 PostgreSQL 表是跨服务的稳定契约。这里在最后的
    持久化边界做长度裁剪，并在裁剪尾部保留短 SHA-256 摘要，避免两个长标识仅因前缀相同而碰撞。
    """

    return replace(
        checkpoint,
        checkpoint_id=_bounded_text(checkpoint.checkpoint_id, 160),
        thread_id=_bounded_text(checkpoint.thread_id, 160),
        parent_checkpoint_id=_bounded_optional_text(checkpoint.parent_checkpoint_id, 160),
        tenant_id=_bounded_optional_text(checkpoint.tenant_id, 64),
        project_id=_bounded_optional_text(checkpoint.project_id, 64),
        actor_id=_bounded_optional_text(checkpoint.actor_id, 64),
        workspace_key=_bounded_optional_text(checkpoint.workspace_key, 255),
        run_id=_bounded_optional_text(checkpoint.run_id, 128),
        session_id=_bounded_optional_text(checkpoint.session_id, 128),
        graph_name=_bounded_text(checkpoint.graph_name, 128),
        graph_version=_bounded_text(checkpoint.graph_version, 64),
        node_name=_bounded_text(checkpoint.node_name, 128),
        low_sensitive_summary=_bounded_optional_text(checkpoint.low_sensitive_summary, 1024),
    )


def _normalize_event(event: LangGraphCheckpointEvent) -> LangGraphCheckpointEvent:
    """按 ``ai_memory.langgraph_checkpoint_event`` 的 VARCHAR 上限归一化事件。"""

    return replace(
        event,
        event_id=_bounded_text(event.event_id, 160),
        checkpoint_id=_bounded_text(event.checkpoint_id, 160),
        thread_id=_bounded_text(event.thread_id, 160),
        tenant_id=_bounded_optional_text(event.tenant_id, 64),
        project_id=_bounded_optional_text(event.project_id, 64),
        run_id=_bounded_optional_text(event.run_id, 128),
        event_type=_bounded_text(event.event_type, 96),
        node_name=_bounded_optional_text(event.node_name, 128),
        edge_name=_bounded_optional_text(event.edge_name, 128),
    )


def _bounded_optional_text(value: Any, limit: int) -> str | None:
    """保留 NULL 语义并把非空值交给带摘要的长度保护。"""

    if value is None:
        return None
    return _bounded_text(value, limit)


def _bounded_text(value: Any, limit: int) -> str:
    """把持久化标识压到数据库上限，并保留短摘要帮助排查截断冲突。"""

    text = str(value or "")
    if len(text) <= limit:
        return text
    digest = hashlib.sha256(text.encode("utf-8")).hexdigest()[:8]
    return text[: max(1, limit - len(digest) - 1)] + "~" + digest


def _checkpoint_from_row(row: Any) -> LangGraphDurableCheckpoint:
    """把数据库行转换为领域 checkpoint。"""

    values = _row_values(row, PostgresLangGraphCheckpointStore._checkpoint_columns())
    return LangGraphDurableCheckpoint(
        checkpoint_id=str(values["checkpoint_id"]),
        thread_id=str(values["thread_id"]),
        parent_checkpoint_id=_optional_text(values.get("parent_checkpoint_id")),
        tenant_id=_optional_text(values.get("tenant_id")),
        project_id=_optional_text(values.get("project_id")),
        actor_id=_optional_text(values.get("actor_id")),
        workspace_key=_optional_text(values.get("workspace_key")),
        run_id=_optional_text(values.get("run_id")),
        session_id=_optional_text(values.get("session_id")),
        graph_name=str(values["graph_name"]),
        graph_version=str(values["graph_version"]),
        node_name=str(values["node_name"]),
        status=LangGraphCheckpointStatus(str(values["status"])),
        checkpoint_version=int(values["checkpoint_version"]),
        state=_loads_mapping(values.get("state_json")),
        next_nodes=tuple(str(item) for item in _loads_sequence(values.get("next_nodes_json"))),
        resume_requirements=_loads_mapping(values.get("resume_requirements_json")),
        low_sensitive_summary=_optional_text(values.get("low_sensitive_summary")),
        expires_at=_datetime_or_none(values.get("expires_at")),
        created_at=_datetime_or_now(values.get("created_at")),
        updated_at=_datetime_or_now(values.get("updated_at")),
    )


def _event_from_row(row: Any) -> LangGraphCheckpointEvent:
    """把数据库行转换为领域 event。"""

    values = _row_values(row, PostgresLangGraphCheckpointStore._event_columns())
    return LangGraphCheckpointEvent(
        event_id=str(values["event_id"]),
        checkpoint_id=str(values["checkpoint_id"]),
        thread_id=str(values["thread_id"]),
        tenant_id=_optional_text(values.get("tenant_id")),
        project_id=_optional_text(values.get("project_id")),
        run_id=_optional_text(values.get("run_id")),
        event_type=str(values["event_type"]),
        node_name=_optional_text(values.get("node_name")),
        edge_name=_optional_text(values.get("edge_name")),
        sequence_number=int(values["sequence_number"]),
        attributes=_loads_mapping(values.get("attributes_json")),
        created_at=_datetime_or_now(values.get("created_at")),
    )


def _row_values(row: Any, columns: str) -> dict[str, Any]:
    """兼容 dict_row、sqlite Row 和 tuple row。"""

    names = columns.split(",")
    if isinstance(row, Mapping):
        return {name: row.get(name) for name in names}
    if hasattr(row, "keys"):
        return {name: row[name] for name in names}
    return dict(zip(names, row))


def _row_get(row: Any, key: str, index: int) -> Any:
    """读取单列聚合查询结果。"""

    if isinstance(row, Mapping):
        return row.get(key)
    if hasattr(row, "keys"):
        return row[key]
    return row[index]


def _loads_mapping(value: Any) -> dict[str, Any]:
    """读取 JSON object，兼容 psycopg JSONB 自动解码和字符串。"""

    if isinstance(value, Mapping):
        return dict(value)
    if value is None:
        return {}
    decoded = json.loads(str(value))
    return dict(decoded) if isinstance(decoded, Mapping) else {}


def _loads_sequence(value: Any) -> tuple[Any, ...]:
    """读取 JSON array。"""

    if isinstance(value, (list, tuple)):
        return tuple(value)
    if value is None:
        return ()
    decoded = json.loads(str(value))
    return tuple(decoded) if isinstance(decoded, (list, tuple)) else ()


def _optional_text(value: Any) -> str | None:
    """读取可选文本。"""

    if value is None:
        return None
    text = str(value).strip()
    return text or None


def _datetime_or_none(value: Any) -> datetime | None:
    """读取可选时间。"""

    if value is None:
        return None
    return _datetime_or_now(value)


def _datetime_or_now(value: Any) -> datetime:
    """读取时间；缺失时回退当前 UTC。"""

    if isinstance(value, datetime):
        return value
    if value is None:
        return datetime.now(timezone.utc)
    return datetime.fromisoformat(str(value))


__all__ = ["PostgresLangGraphCheckpointStore"]
