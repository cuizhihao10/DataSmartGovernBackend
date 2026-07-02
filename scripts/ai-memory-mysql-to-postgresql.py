#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""ai_memory MySQL -> PostgreSQL/pgvector 存量数据迁移与对账工具。

本脚本服务于 DataSmart Govern 从 MySQL 兼容期迁移到 PostgreSQL/pgvector 目标架构的 AI Memory 批次。

为什么 ai_memory 要单独迁移：
1. `agent_runtime` 保存的是 Agent 运行控制面事实，例如工具审计、outbox、receipt、恢复定位符；
2. `ai_memory` 保存的是长期记忆、用户画像、语义索引和 LangGraph durable state；
3. 二者在容量增长、权限隔离、保留期、向量索引、备份恢复和合规审计上的生命周期完全不同；
4. 因此旧 MySQL 初始化脚本里混放的 `agent_memory_*` 必须搬到独立 `ai_memory` schema，
   不能继续留在 MySQL，也不能塞进 `agent_runtime`。

本脚本只迁移旧 MySQL 已存在的 6 张长期记忆历史表：
- agent_memory_write_candidate
- agent_memory_write_candidate_audit
- agent_memory_store_entry
- agent_memory_materialization_receipt
- agent_memory_materialization_lease
- agent_memory_materialization_audit_outbox

以下表是 PostgreSQL/pgvector 新能力表，不从 MySQL 迁移：
- agent_memory_embedding_index：后续由 embedding adapter 根据正式记忆重建；
- user_profile_fact：后续由用户画像管道从会话/记忆/反馈事实中沉淀；
- langgraph_thread_checkpoint / langgraph_checkpoint_event：后续由 LangGraph checkpointer 写入。

运行模式：
- plan：只读检查源表、目标表、延期表和 PostgreSQL-only 表，不写文件、不写数据库；
- export：导出 JSONL 和低敏 manifest，不写 PostgreSQL；
- import：通过 PostgreSQL COPY 导入 JSONL，必须显式传入 --apply；
- verify：按行数和稳定 SHA-256 checksum 对账；
- all：export -> import -> verify，仍然必须显式传入 --apply 才能写 PostgreSQL。

时间策略：
旧 MySQL 使用 DATETIME(3)，没有时区信息；目标 PostgreSQL 使用 TIMESTAMPTZ。
脚本默认把旧 MySQL 时间解释为 UTC（--mysql-datetime-timezone +00:00），避免偷偷依赖本机时区。
如果生产库曾按 Asia/Shanghai 等本地时间写入，应在迁移方案中显式传入例如 --mysql-datetime-timezone +08:00，
并把这个选择记录到变更单、manifest 和验收报告中。
"""

from __future__ import annotations

import argparse
import csv
import datetime as dt
import hashlib
import json
import os
import pathlib
import re
import subprocess
import sys
from dataclasses import dataclass
from typing import Any, Iterable, Iterator

REPO_ROOT = pathlib.Path(__file__).resolve().parents[1]
DEFAULT_OUTPUT_ROOT = REPO_ROOT / "artifacts" / "postgresql-migration" / "ai-memory"
NULL_SENTINEL = "__DATASMART_AI_MEMORY_POSTGRES_COPY_NULL_9F3C8B21__"

SENSITIVE_COLUMNS_BY_TABLE: dict[str, tuple[str, ...]] = {
    "agent_memory_write_candidate": (
        "title",
        "content_summary",
        "output_ref",
        "privacy_notes_json",
        "decision_reason",
        "attributes_json",
    ),
    "agent_memory_write_candidate_audit": ("reason",),
    "agent_memory_store_entry": ("title", "content", "tags_json", "attributes_json", "namespace_json"),
    "agent_memory_materialization_receipt": ("namespace_json", "message", "error_message"),
    "agent_memory_materialization_lease": ("lease_token", "message", "error_message"),
    "agent_memory_materialization_audit_outbox": ("payload_json",),
}

POSTGRES_ONLY_TABLES: tuple[str, ...] = (
    "agent_memory_embedding_index",
    "user_profile_fact",
    "langgraph_thread_checkpoint",
    "langgraph_checkpoint_event",
)

IDENTIFIER_PATTERN = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")
TIMEZONE_OFFSET_PATTERN = re.compile(r"^[+-]\d{2}:\d{2}$")


@dataclass(frozen=True)
class ColumnSpec:
    """跨数据库迁移列定义。

    name:
        PostgreSQL 目标列名，也是 JSONL 字段名。
    mysql_sources:
        MySQL 源列候选。某些历史表经历过增量 migration，例如 candidate 的 `created_at`
        在目标 schema 中存在，但旧 MySQL 只有 `create_time`。脚本会按顺序选择第一个存在的源列。
    kind:
        规范化类型。checksum 会根据 kind 做稳定表达，避免 JSONB、BOOLEAN、TIMESTAMPTZ
        在不同数据库客户端中的默认格式差异造成误报。
    required:
        源列是否必须存在。历史兼容字段如 `workspace_key`、`memory_namespace`、`next_retry_at`
        可为可选；缺失时导出为 NULL，但不会猜测性回填。
    """

    name: str
    mysql_sources: tuple[str, ...]
    kind: str = "text"
    required: bool = True

    @property
    def primary_source(self) -> str:
        """返回最常见的源列名，用于错误提示。"""

        return self.mysql_sources[0]

    def mysql_expr(self, args: argparse.Namespace, available_columns: set[str]) -> str:
        """生成 MySQL 导出表达式。

        对可选字段，如果历史库没有该列，明确导出 NULL，避免迁移工具擅自填默认值导致审计误解。
        """

        source = next((column for column in self.mysql_sources if column in available_columns), None)
        if source is None:
            if self.required:
                raise RuntimeError(
                    f"MySQL 表缺少必需列：targetColumn={self.name}, expectedSource={self.primary_source}"
                )
            return "NULL"
        quoted = safe_mysql_identifier(source)
        if self.kind == "int":
            return f"CAST({quoted} AS CHAR)"
        if self.kind == "decimal":
            return f"CAST({quoted} AS CHAR)"
        if self.kind == "bool":
            return f"CASE WHEN {quoted} IS NULL THEN NULL WHEN {quoted} <> 0 THEN 'true' ELSE 'false' END"
        if self.kind == "time":
            offset = mysql_timezone_literal(args.mysql_datetime_timezone)
            return (
                f"CASE WHEN {quoted} IS NULL THEN NULL "
                f"ELSE DATE_FORMAT(CONVERT_TZ({quoted}, '{offset}', '+00:00'), '%Y-%m-%dT%H:%i:%s.%fZ') END"
            )
        if self.kind == "json":
            return f"CAST({quoted} AS CHAR)"
        return quoted

    def postgres_expr(self) -> str:
        """生成 PostgreSQL 对账表达式。"""

        quoted = safe_identifier(self.name)
        if self.kind == "int":
            return f"{quoted}::text"
        if self.kind == "decimal":
            return f"{quoted}::text"
        if self.kind == "bool":
            return f"CASE WHEN {quoted} IS NULL THEN NULL WHEN {quoted} THEN 'true' ELSE 'false' END"
        if self.kind == "time":
            return f"CASE WHEN {quoted} IS NULL THEN NULL ELSE to_char({quoted} AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"') END"
        if self.kind == "json":
            return quoted
        return quoted


@dataclass(frozen=True)
class TableSpec:
    """ai_memory 存量迁移表定义。

    本批 6 张表都保留 MySQL 原始 `id` 导入 PostgreSQL。这样审批台截图、运维排障记录、旧审计导出和
    新数据库行可以继续互相定位。导入后脚本会按 MAX(id) 校正 identity sequence。
    """

    name: str
    columns: tuple[ColumnSpec, ...]

    @property
    def column_names(self) -> list[str]:
        """返回 JSONL、COPY 和 checksum 使用的稳定列顺序。"""

        return [column.name for column in self.columns]

    @property
    def sensitive_columns(self) -> tuple[str, ...]:
        """返回需要按敏感迁移介质保护、禁止样本日志输出的列。"""

        return SENSITIVE_COLUMNS_BY_TABLE.get(self.name, ())


def text_col(name: str, *sources: str, required: bool = True) -> ColumnSpec:
    """普通文本列。"""

    return ColumnSpec(name, sources or (name,), required=required)


def int_col(name: str, *sources: str, required: bool = True) -> ColumnSpec:
    """整数列，导出和对账统一转成文本。"""

    return ColumnSpec(name, sources or (name,), kind="int", required=required)


def decimal_col(name: str, *sources: str, required: bool = True) -> ColumnSpec:
    """定点小数列，保留数据库中的 scale 文本。"""

    return ColumnSpec(name, sources or (name,), kind="decimal", required=required)


def bool_col(name: str, *sources: str, required: bool = True) -> ColumnSpec:
    """MySQL TINYINT(1) 到 PostgreSQL BOOLEAN 的列。"""

    return ColumnSpec(name, sources or (name,), kind="bool", required=required)


def time_col(name: str, *sources: str, required: bool = True) -> ColumnSpec:
    """MySQL DATETIME(3) 到 PostgreSQL TIMESTAMPTZ 的列。"""

    return ColumnSpec(name, sources or (name,), kind="time", required=required)


def json_col(name: str, *sources: str, required: bool = True) -> ColumnSpec:
    """MySQL JSON 到 PostgreSQL JSONB 的列。"""

    return ColumnSpec(name, sources or (name,), kind="json", required=required)


TABLES: tuple[TableSpec, ...] = (
    TableSpec(
        "agent_memory_write_candidate",
        (
            int_col("id"),
            text_col("candidate_id"),
            text_col("tenant_id"),
            text_col("project_id"),
            text_col("actor_id"),
            text_col("memory_type"),
            text_col("scope"),
            text_col("status"),
            text_col("title"),
            text_col("content_summary"),
            text_col("source"),
            text_col("workspace_key", required=False),
            text_col("memory_namespace", required=False),
            text_col("source_tool_name", required=False),
            text_col("source_status", required=False),
            text_col("source_audit_id", required=False),
            text_col("source_run_id", required=False),
            text_col("output_ref", required=False),
            bool_col("approval_required"),
            int_col("retention_days"),
            text_col("sensitivity_level"),
            json_col("privacy_notes_json", required=False),
            int_col("candidate_version"),
            text_col("idempotency_key", required=False),
            time_col("created_at", "created_at", "create_time"),
            time_col("decided_at", required=False),
            text_col("decided_by", required=False),
            text_col("decision_reason", required=False),
            json_col("attributes_json", required=False),
            time_col("create_time"),
            time_col("update_time"),
        ),
    ),
    TableSpec(
        "agent_memory_write_candidate_audit",
        (
            int_col("id"),
            text_col("candidate_id"),
            text_col("tenant_id"),
            text_col("project_id"),
            text_col("operator_id"),
            text_col("action"),
            text_col("previous_status"),
            text_col("next_status"),
            text_col("reason"),
            int_col("candidate_version"),
            time_col("decided_at", required=False),
            time_col("create_time"),
        ),
    ),
    TableSpec(
        "agent_memory_store_entry",
        (
            int_col("id"),
            text_col("memory_id"),
            text_col("tenant_id", required=False),
            text_col("project_id", required=False),
            text_col("session_id", required=False),
            text_col("memory_type"),
            text_col("scope"),
            text_col("title"),
            text_col("content"),
            text_col("source", required=False),
            decimal_col("importance_score"),
            text_col("sensitivity_level"),
            json_col("tags_json", required=False),
            time_col("created_at", required=False),
            json_col("attributes_json", required=False),
            text_col("workspace_key"),
            text_col("memory_namespace"),
            json_col("namespace_json", required=False),
            text_col("idempotency_key"),
            text_col("source_candidate_id"),
            time_col("expires_at"),
            time_col("materialized_at"),
            time_col("create_time"),
            time_col("update_time"),
        ),
    ),
    TableSpec(
        "agent_memory_materialization_receipt",
        (
            int_col("id"),
            text_col("receipt_id"),
            text_col("candidate_id"),
            text_col("tenant_id"),
            text_col("project_id"),
            text_col("workspace_key"),
            text_col("memory_namespace"),
            text_col("status"),
            int_col("attempt_count"),
            text_col("worker_id", required=False),
            text_col("memory_id", required=False),
            json_col("namespace_json", required=False),
            text_col("outcome", required=False),
            text_col("message", required=False),
            text_col("error_message", required=False),
            time_col("started_at", required=False),
            time_col("finished_at", required=False),
            time_col("create_time"),
            time_col("update_time"),
        ),
    ),
    TableSpec(
        "agent_memory_materialization_lease",
        (
            int_col("id"),
            text_col("lease_id"),
            text_col("candidate_id"),
            text_col("tenant_id"),
            text_col("project_id"),
            text_col("workspace_key"),
            text_col("memory_namespace"),
            text_col("status"),
            int_col("attempt_count"),
            text_col("worker_id"),
            text_col("lease_token"),
            time_col("leased_until"),
            time_col("next_retry_at", required=False),
            text_col("memory_id", required=False),
            text_col("outcome", required=False),
            text_col("message", required=False),
            text_col("error_message", required=False),
            time_col("started_at", required=False),
            time_col("finished_at", required=False),
            time_col("create_time"),
            time_col("update_time"),
        ),
    ),
    TableSpec(
        "agent_memory_materialization_audit_outbox",
        (
            int_col("id"),
            text_col("outbox_id"),
            text_col("event_type"),
            text_col("event_purpose"),
            text_col("aggregate_id"),
            text_col("tenant_id", required=False),
            text_col("project_id", required=False),
            text_col("actor_id", required=False),
            text_col("request_id", required=False),
            text_col("run_id", required=False),
            text_col("session_id", required=False),
            text_col("severity"),
            text_col("action", required=False),
            bool_col("dry_run"),
            json_col("payload_json"),
            text_col("delivery_status"),
            int_col("attempt_count"),
            time_col("next_delivery_attempt_at", required=False),
            time_col("created_at"),
            time_col("updated_at"),
            time_col("create_time"),
            time_col("update_time"),
        ),
    ),
)

TABLE_NAMES = {table.name for table in TABLES}

MYSQL_TABLE_NAMES_CACHE: dict[tuple[str, str], set[str]] = {}
MYSQL_COLUMN_NAMES_CACHE: dict[tuple[str, str, str], set[str]] = {}
POSTGRES_TABLE_NAMES_CACHE: dict[tuple[str, str, str], set[str]] = {}


def safe_identifier(identifier: str) -> str:
    """校验 SQL 标识符。"""

    if not IDENTIFIER_PATTERN.match(identifier):
        raise RuntimeError(f"非法 SQL 标识符：{identifier}")
    return identifier


def safe_mysql_identifier(identifier: str) -> str:
    """校验并引用 MySQL 标识符。"""

    return f"`{safe_identifier(identifier)}`"


def mysql_timezone_literal(offset: str) -> str:
    """校验 MySQL DATETIME 解释时区。"""

    if not TIMEZONE_OFFSET_PATTERN.match(offset):
        raise RuntimeError("mysql-datetime-timezone 必须是 +00:00 或 +08:00 这类固定偏移")
    return offset


def qualified_table(args: argparse.Namespace, table: TableSpec) -> str:
    """返回 PostgreSQL schema.table 表达式。"""

    return f"{safe_identifier(args.postgres_schema)}.{safe_identifier(table.name)}"


def redact_command(command: list[str]) -> str:
    """生成低敏命令摘要，避免把 MYSQL_PWD 打到日志。"""

    redacted: list[str] = []
    for item in command[:10]:
        redacted.append("MYSQL_PWD=***" if item.startswith("MYSQL_PWD=") else item)
    return " ".join(redacted)


def run_command(command: list[str], *, stdin: str | None = None) -> str:
    """执行短输出命令并返回 stdout。"""

    completed = subprocess.run(
        command,
        input=stdin,
        text=True,
        encoding="utf-8",
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if completed.returncode != 0:
        raise RuntimeError(f"命令执行失败：{redact_command(command)}，exitCode={completed.returncode}")
    return completed.stdout


def stream_command(command: list[str]) -> Iterator[str]:
    """按行读取长输出命令，避免把大表一次性加载到内存。"""

    process = subprocess.Popen(
        command,
        text=True,
        encoding="utf-8",
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    assert process.stdout is not None
    assert process.stderr is not None
    try:
        for line in process.stdout:
            yield line
        stderr = process.stderr.read()
        exit_code = process.wait()
    except Exception:
        process.kill()
        raise
    if exit_code != 0:
        _ = stderr
        raise RuntimeError(f"命令执行失败：{redact_command(command)}，exitCode={exit_code}")


def mysql_command(args: argparse.Namespace, sql: str) -> list[str]:
    """构造 MySQL Docker CLI 命令。"""

    return [
        "docker",
        "exec",
        "-i",
        "-e",
        f"MYSQL_PWD={args.mysql_password}",
        args.mysql_container,
        "mysql",
        "--default-character-set=utf8mb4",
        "--batch",
        "--raw",
        "--skip-column-names",
        "-u",
        args.mysql_user,
        args.mysql_database,
        "-e",
        sql,
    ]


def psql_command(args: argparse.Namespace, sql: str, *, interactive: bool = False) -> list[str]:
    """构造 PostgreSQL Docker CLI 命令。"""

    command = [
        "docker",
        "exec",
        "-i",
        args.postgres_container,
        "psql",
        "-U",
        args.postgres_user,
        "-d",
        args.postgres_database,
        "-v",
        "ON_ERROR_STOP=1",
    ]
    if not interactive:
        command.append("-At")
    command.extend(["-c", sql])
    return command


def docker_mysql_lines(args: argparse.Namespace, sql: str) -> Iterator[str]:
    """流式读取 MySQL 查询结果。"""

    yield from stream_command(mysql_command(args, sql))


def docker_mysql(args: argparse.Namespace, sql: str) -> str:
    """执行 MySQL 查询并返回完整 stdout。"""

    return run_command(mysql_command(args, sql))


def docker_psql_lines(args: argparse.Namespace, sql: str) -> Iterator[str]:
    """流式读取 PostgreSQL 查询结果。"""

    yield from stream_command(psql_command(args, sql))


def docker_psql(args: argparse.Namespace, sql: str) -> str:
    """执行 PostgreSQL 查询并返回完整 stdout。"""

    return run_command(psql_command(args, sql))


def mysql_table_cache_key(args: argparse.Namespace) -> tuple[str, str]:
    """MySQL 表级缓存 key。

    plan/export/import/verify 在一次脚本进程中会多次检查同一批表是否存在。
    如果每次都 docker exec，会把本来很轻的只读计划检查放大成几十次容器调用。
    """

    return (args.mysql_container, args.mysql_database)


def postgres_table_cache_key(args: argparse.Namespace) -> tuple[str, str, str]:
    """PostgreSQL 表级缓存 key。"""

    return (args.postgres_container, args.postgres_database, args.postgres_schema)


def mysql_all_table_names(args: argparse.Namespace) -> set[str]:
    """读取并缓存当前 MySQL database 下的全部表名。"""

    key = mysql_table_cache_key(args)
    cached = MYSQL_TABLE_NAMES_CACHE.get(key)
    if cached is not None:
        return cached
    sql = "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()"
    table_names = {line.strip() for line in docker_mysql_lines(args, sql) if line.strip()}
    MYSQL_TABLE_NAMES_CACHE[key] = table_names
    return table_names


def postgres_all_table_names(args: argparse.Namespace) -> set[str]:
    """读取并缓存 PostgreSQL 目标 schema 下的全部表名。"""

    key = postgres_table_cache_key(args)
    cached = POSTGRES_TABLE_NAMES_CACHE.get(key)
    if cached is not None:
        return cached
    schema = safe_identifier(args.postgres_schema)
    sql = f"SELECT tablename FROM pg_catalog.pg_tables WHERE schemaname = '{schema}'"
    table_names = {line.strip() for line in docker_psql_lines(args, sql) if line.strip()}
    POSTGRES_TABLE_NAMES_CACHE[key] = table_names
    return table_names


def mysql_table_exists(args: argparse.Namespace, table_name: str) -> bool:
    """判断 MySQL 源表是否存在。"""

    return safe_identifier(table_name) in mysql_all_table_names(args)


def mysql_column_names(args: argparse.Namespace, table_name: str) -> set[str]:
    """读取 MySQL 表列名。"""

    key = (args.mysql_container, args.mysql_database, table_name)
    cached = MYSQL_COLUMN_NAMES_CACHE.get(key)
    if cached is not None:
        return cached
    if not mysql_table_exists(args, table_name):
        return set()
    sql = (
        "SELECT column_name FROM information_schema.columns "
        "WHERE table_schema = DATABASE() "
        f"AND table_name = '{safe_identifier(table_name)}' "
        "ORDER BY ordinal_position"
    )
    columns = {line.strip() for line in docker_mysql_lines(args, sql) if line.strip()}
    MYSQL_COLUMN_NAMES_CACHE[key] = columns
    return columns


def postgres_table_exists(args: argparse.Namespace, table_name: str) -> bool:
    """判断 PostgreSQL 目标表是否存在。"""

    return safe_identifier(table_name) in postgres_all_table_names(args)


def mysql_table_counts(args: argparse.Namespace, table_names: Iterable[str]) -> dict[str, int]:
    """读取 MySQL 多表行数，源表不存在时按 0 处理。"""

    requested = [safe_identifier(table_name) for table_name in table_names]
    counts: dict[str, int] = {table_name: 0 for table_name in requested}
    existing = [table_name for table_name in requested if mysql_table_exists(args, table_name)]
    if not existing:
        return counts
    selects = [
        f"SELECT '{table_name}' AS table_name, COUNT(*) AS row_count FROM {safe_mysql_identifier(table_name)}"
        for table_name in existing
    ]
    for line in docker_mysql_lines(args, " UNION ALL ".join(selects)):
        parts = line.rstrip("\n").split("\t")
        if len(parts) == 2:
            counts[parts[0]] = int(parts[1])
    return counts


def postgres_table_counts(args: argparse.Namespace, table_names: Iterable[str]) -> dict[str, int]:
    """读取 PostgreSQL 多表行数，目标表不存在时按 0 处理。"""

    requested = [safe_identifier(table_name) for table_name in table_names]
    counts: dict[str, int] = {table_name: 0 for table_name in requested}
    schema = safe_identifier(args.postgres_schema)
    existing = [table_name for table_name in requested if postgres_table_exists(args, table_name)]
    if not existing:
        return counts
    selects = [
        f"SELECT '{table_name}' AS table_name, COUNT(*)::text AS row_count FROM {schema}.{table_name}"
        for table_name in existing
    ]
    for line in docker_psql_lines(args, " UNION ALL ".join(selects)):
        parts = line.rstrip("\n").split("|")
        if len(parts) == 2:
            counts[parts[0]] = int(parts[1])
    return counts


def mysql_json_select(args: argparse.Namespace, table: TableSpec) -> str:
    """构造 MySQL JSONL 导出 SQL。"""

    available_columns = mysql_column_names(args, table.name)
    if not available_columns:
        return ""
    parts: list[str] = []
    for column in table.columns:
        parts.append(f"'{column.name}'")
        parts.append(column.mysql_expr(args, available_columns))
    return (
        f"SELECT JSON_OBJECT({', '.join(parts)}) "
        f"FROM {safe_mysql_identifier(table.name)} ORDER BY {safe_mysql_identifier('id')}"
    )


def postgres_json_select(table: TableSpec, schema: str) -> str:
    """构造 PostgreSQL 对账 SQL。"""

    parts: list[str] = []
    for column in table.columns:
        parts.append(f"'{column.name}'")
        parts.append(column.postgres_expr())
    return (
        f"SELECT jsonb_build_object({', '.join(parts)})::text "
        f"FROM {safe_identifier(schema)}.{safe_identifier(table.name)} ORDER BY id"
    )


def canonical_value(column: ColumnSpec, value: Any) -> str:
    """把字段值转换为 checksum 使用的稳定文本。"""

    if value is None:
        return "<NULL>"
    if column.kind == "json":
        if isinstance(value, str):
            parsed = json.loads(value) if value else None
        else:
            parsed = value
        return json.dumps(parsed, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return str(value)


def update_digest(digest: hashlib._Hash, table: TableSpec, row: dict[str, Any]) -> None:
    """把一行数据加入 SHA-256 摘要。"""

    for column in table.columns:
        digest.update(column.name.encode("utf-8"))
        digest.update(b"=")
        digest.update(canonical_value(column, row.get(column.name)).encode("utf-8"))
        digest.update(b"\x1f")
    digest.update(b"\x1e")


def checksum_jsonl(table: TableSpec, rows: Iterable[dict[str, Any]]) -> tuple[int, str]:
    """计算 JSON 行集合的行数和稳定 checksum。"""

    digest = hashlib.sha256()
    count = 0
    for row in rows:
        update_digest(digest, table, row)
        count += 1
    return count, digest.hexdigest()


def read_jsonl(path: pathlib.Path) -> Iterator[dict[str, Any]]:
    """读取 JSONL 导出文件。"""

    with path.open("r", encoding="utf-8") as handle:
        for line in handle:
            if line.strip():
                yield json.loads(line)


def target_schema_for_deferred(table_name: str) -> tuple[str, str, str]:
    """判断非本批表的归属和处理状态。"""

    if table_name in POSTGRES_ONLY_TABLES:
        return "ai_memory", "POSTGRES_ONLY", "该表属于 ai_memory 新能力表，应由新管道写入或重建，不从旧 MySQL 迁移。"
    if table_name == "agent_async_task_command_inbox":
        return "task_management", "DEFERRED", "该表是 task-management 接收 Agent command 的 inbox 投影，已随任务中心迁移。"
    if table_name.startswith("agent_memory_"):
        return "ai_memory", "REVIEW_REQUIRED", "额外 agent_memory_* 表未纳入当前 V1，需要人工确认是否是实验表或后续 V2 表。"
    if table_name.startswith("agent_"):
        return "agent_runtime", "DEFERRED", "agent_* 控制面事实属于 agent-runtime，不随 ai_memory 迁移。"
    if table_name.startswith("task_"):
        return "task_management", "DEFERRED", "task_* 属于 task-management。"
    if table_name.startswith("data_sync_"):
        return "data_sync", "DEFERRED", "data_sync_* 属于 data-sync 微服务。"
    if table_name.startswith("sync_") or table_name.startswith("datasource_"):
        return "datasource_management", "DEFERRED", "sync_* / datasource_* 属于 datasource-management。"
    if table_name.startswith("quality_"):
        return "data_quality", "DEFERRED", "quality_* 属于 data-quality。"
    if table_name.startswith("permission_") or table_name.startswith("role_") or table_name.startswith("menu_"):
        return "permission_admin", "DEFERRED", "权限、角色、菜单相关表属于 permission-admin。"
    return "unknown", "REVIEW_REQUIRED", "未知表名前缀，需要人工确认归属。"


def detect_related_tables(args: argparse.Namespace) -> list[dict[str, Any]]:
    """扫描 MySQL 中非本批但相关的表。"""

    sql = (
        "SELECT table_name FROM information_schema.tables "
        "WHERE table_schema = DATABASE() "
        "AND (table_name REGEXP '^agent_' "
        "OR table_name REGEXP '^task_' "
        "OR table_name REGEXP '^data_sync_' "
        "OR table_name REGEXP '^sync_' "
        "OR table_name REGEXP '^datasource_' "
        "OR table_name REGEXP '^quality_' "
        "OR table_name REGEXP '^permission_' "
        "OR table_name REGEXP '^role_' "
        "OR table_name REGEXP '^menu_') "
        "ORDER BY table_name"
    )
    table_names = [line.strip() for line in docker_mysql_lines(args, sql) if line.strip()]
    table_names = [name for name in table_names if name not in TABLE_NAMES]
    counts = mysql_table_counts(args, table_names)
    related: list[dict[str, Any]] = []
    for table_name in table_names:
        target_schema, status, reason = target_schema_for_deferred(table_name)
        related.append(
            {
                "table": table_name,
                "rows": counts.get(table_name, 0),
                "status": status,
                "targetSchema": target_schema,
                "reason": reason,
            }
        )
    return related


def export_table(args: argparse.Namespace, table: TableSpec, export_dir: pathlib.Path) -> dict[str, Any]:
    """导出单表 JSONL 并计算源端 checksum。"""

    table_path = export_dir / f"{table.name}.jsonl"
    digest = hashlib.sha256()
    count = 0
    with table_path.open("w", encoding="utf-8", newline="\n") as handle:
        if mysql_table_exists(args, table.name):
            select_sql = mysql_json_select(args, table)
            for line in docker_mysql_lines(args, select_sql):
                if not line.strip():
                    continue
                row = json.loads(line)
                update_digest(digest, table, row)
                handle.write(json.dumps(row, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n")
                count += 1
    checksum = digest.hexdigest()
    print(f"[EXPORT] {table.name}: rows={count}, checksum={checksum[:16]}...")
    return {
        "table": table.name,
        "rows": count,
        "checksum": checksum,
        "file": table_path.name,
        "containsSensitiveData": bool(table.sensitive_columns),
        "sensitiveColumns": list(table.sensitive_columns),
    }


def write_manifest(
    args: argparse.Namespace,
    export_dir: pathlib.Path,
    table_results: list[dict[str, Any]],
    related_tables: list[dict[str, Any]],
) -> None:
    """写入低敏 manifest。"""

    manifest = {
        "module": "ai-memory",
        "source": "mysql",
        "target": "postgresql/pgvector",
        "generatedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
        "mysqlContainer": args.mysql_container,
        "postgresContainer": args.postgres_container,
        "postgresSchema": args.postgres_schema,
        "mysqlDatetimeTimezone": args.mysql_datetime_timezone,
        "timezoneNotice": (
            "旧 MySQL DATETIME(3) 没有时区。脚本按 mysqlDatetimeTimezone 解释源端墙钟时间，再转换为 UTC "
            "ISO 字符串导入 PostgreSQL TIMESTAMPTZ。生产迁移必须记录该策略。"
        ),
        "securityNotice": (
            "JSONL 导出目录包含真实 AI Memory 迁移数据，可能含长期记忆低敏正文、审批原因、错误摘要、"
            "审计 payload 和内部 lease token。manifest 不保存样本值，但整个目录仍必须按敏感迁移介质保管。"
        ),
        "ownershipNotice": (
            "本脚本只迁移 6 张旧 MySQL agent_memory_* 表。agent_memory_embedding_index、user_profile_fact、"
            "langgraph_thread_checkpoint 和 langgraph_checkpoint_event 是 PostgreSQL-only 新能力表。"
        ),
        "tables": table_results,
        "relatedTables": related_tables,
        "postgresOnlyTables": list(POSTGRES_ONLY_TABLES),
    }
    (export_dir / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")


def load_manifest(export_dir: pathlib.Path) -> dict[str, Any]:
    """读取导出 manifest。"""

    manifest_path = export_dir / "manifest.json"
    if not manifest_path.exists():
        raise RuntimeError(f"缺少 manifest.json：{manifest_path}")
    return json.loads(manifest_path.read_text(encoding="utf-8"))


def ensure_target_empty(args: argparse.Namespace) -> None:
    """保护 PostgreSQL 目标表。"""

    counts = postgres_table_counts(args, (table.name for table in TABLES))
    non_empty = [(table.name, counts.get(table.name, 0)) for table in TABLES if counts.get(table.name, 0) > 0]
    if non_empty and not args.allow_target_not_empty:
        details = ", ".join(f"{name}={count}" for name, count in non_empty)
        raise RuntimeError(f"PostgreSQL ai_memory 目标表非空，默认拒绝导入：{details}")


def copy_value(table: TableSpec, column: str, value: Any) -> str:
    """把 JSONL 字段转换为 COPY 字段。"""

    if value is None:
        return NULL_SENTINEL
    if isinstance(value, (dict, list, tuple)):
        text = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    else:
        text = str(value)
    if text == NULL_SENTINEL:
        raise RuntimeError(f"{table.name}.{column} 的值与 COPY NULL 哨兵冲突，已停止导入")
    return text


def docker_psql_copy_from_jsonl(
    args: argparse.Namespace,
    copy_sql: str,
    table: TableSpec,
    jsonl_path: pathlib.Path,
) -> int:
    """使用 PostgreSQL COPY 流式导入 JSONL。"""

    process = subprocess.Popen(
        psql_command(args, copy_sql, interactive=True),
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
    )
    assert process.stdin is not None
    writer = csv.writer(process.stdin, delimiter="\t", lineterminator="\n")
    row_count = 0
    try:
        for row in read_jsonl(jsonl_path):
            writer.writerow([copy_value(table, column, row.get(column)) for column in table.column_names])
            row_count += 1
        process.stdin.close()
        stdout = process.stdout.read() if process.stdout is not None else ""
        stderr = process.stderr.read() if process.stderr is not None else ""
        exit_code = process.wait()
    except Exception:
        process.kill()
        raise
    if exit_code != 0:
        _ = stdout, stderr
        raise RuntimeError(f"COPY 导入失败：{table.name}，exitCode={exit_code}")
    return row_count


def import_table(args: argparse.Namespace, table: TableSpec, export_dir: pathlib.Path) -> None:
    """导入单表 JSONL。"""

    jsonl_path = export_dir / f"{table.name}.jsonl"
    if not jsonl_path.exists():
        raise RuntimeError(f"缺少导出文件：{jsonl_path}")
    columns = ", ".join(table.column_names)
    copy_sql = (
        f"COPY {qualified_table(args, table)} ({columns}) "
        f"FROM STDIN WITH (FORMAT csv, DELIMITER E'\\t', NULL '{NULL_SENTINEL}')"
    )
    row_count = docker_psql_copy_from_jsonl(args, copy_sql, table, jsonl_path)
    print(f"[IMPORT] {table.name}: rows={row_count}")


def reset_identity_sequences(args: argparse.Namespace) -> None:
    """按导入后的 MAX(id) 校正 PostgreSQL identity sequence。"""

    schema = safe_identifier(args.postgres_schema)
    statements = []
    for table in TABLES:
        statements.append(
            f"SELECT setval(pg_get_serial_sequence('{schema}.{table.name}', 'id'), "
            f"COALESCE((SELECT MAX(id) FROM {schema}.{table.name}), 1), "
            f"COALESCE((SELECT MAX(id) FROM {schema}.{table.name}), 0) > 0);"
        )
    docker_psql(args, " ".join(statements))
    print("[SEQUENCE] PostgreSQL ai_memory identity sequence 已按最大 id 校正")


def target_checksum(args: argparse.Namespace, table: TableSpec) -> tuple[int, str]:
    """计算 PostgreSQL 目标表行数和 checksum。"""

    rows = (
        json.loads(line)
        for line in docker_psql_lines(args, postgres_json_select(table, args.postgres_schema))
        if line.strip()
    )
    return checksum_jsonl(table, rows)


def run_plan(args: argparse.Namespace) -> None:
    """只读迁移计划检查。"""

    print("== ai_memory MySQL -> PostgreSQL/pgvector 迁移计划 ==")
    print(f"[TIMEZONE] MySQL DATETIME(3) 将按 {args.mysql_datetime_timezone} 解释并转换为 UTC TIMESTAMPTZ")
    source_counts = mysql_table_counts(args, (table.name for table in TABLES))
    target_counts = postgres_table_counts(args, (table.name for table in TABLES))
    for table in TABLES:
        source_exists = "exists" if mysql_table_exists(args, table.name) else "missing"
        target_exists = "exists" if postgres_table_exists(args, table.name) else "missing"
        sensitive_hint = " sensitive" if table.sensitive_columns else ""
        print(
            f"[PLAN] {table.name}: mysql={source_exists}, mysqlRows={source_counts.get(table.name, 0)}, "
            f"postgres={target_exists}, postgresRows={target_counts.get(table.name, 0)}{sensitive_hint}"
        )
    postgres_only_counts = postgres_table_counts(args, POSTGRES_ONLY_TABLES)
    print("== PostgreSQL-only ai_memory 新能力表 ==")
    for table_name in POSTGRES_ONLY_TABLES:
        status = "exists" if postgres_table_exists(args, table_name) else "missing"
        print(f"[POSTGRES_ONLY] {table_name}: postgres={status}, postgresRows={postgres_only_counts.get(table_name, 0)}")
    related = detect_related_tables(args)
    if related:
        print("== 延期或人工复核表 ==")
        for item in related:
            print(f"[{item['status']}] {item['table']}: mysqlRows={item['rows']}, targetSchema={item['targetSchema']}")
    else:
        print("[RELATED] 当前 MySQL schema 未发现非本批相关表")
    print("提示：写入 PostgreSQL 必须使用 --mode import/all --apply，且默认要求 6 张目标迁移表为空。")


def run_export(args: argparse.Namespace) -> pathlib.Path:
    """导出 ai_memory 历史表并写 manifest。"""

    export_dir = pathlib.Path(args.export_dir) if args.export_dir else DEFAULT_OUTPUT_ROOT / dt.datetime.now().strftime("%Y%m%d%H%M%S")
    export_dir.mkdir(parents=True, exist_ok=True)
    results = [export_table(args, table, export_dir) for table in TABLES]
    related = detect_related_tables(args)
    write_manifest(args, export_dir, results, related)
    print(f"[EXPORT] manifest={export_dir / 'manifest.json'}")
    if related:
        print(f"[RELATED] 已记录 {len(related)} 张非本批相关表，它们不会被导入 ai_memory 迁移表。")
    print("[SECURITY] 导出目录包含真实 AI Memory 迁移数据，请按敏感迁移介质保管并在验收后清理。")
    return export_dir


def run_import(args: argparse.Namespace, export_dir: pathlib.Path) -> None:
    """导入 ai_memory 历史表。"""

    if not args.apply:
        raise RuntimeError("导入会写入 PostgreSQL，必须显式传入 --apply")
    ensure_target_empty(args)
    for table in TABLES:
        import_table(args, table, export_dir)
    reset_identity_sequences(args)


def run_verify(args: argparse.Namespace, export_dir: pathlib.Path) -> None:
    """迁移后对账。"""

    manifest = load_manifest(export_dir)
    expected = {item["table"]: item for item in manifest["tables"]}
    mismatches: list[str] = []
    for table in TABLES:
        target_count, target_digest = target_checksum(args, table)
        source = expected[table.name]
        ok = target_count == source["rows"] and target_digest == source["checksum"]
        status = "PASS" if ok else "FAIL"
        print(f"[VERIFY:{status}] {table.name}: rows={target_count}, checksum={target_digest[:16]}...")
        if not ok:
            mismatches.append(table.name)
    for item in manifest.get("relatedTables", []):
        print(f"[{item['status']}] {item['table']}: rows={item['rows']}, targetSchema={item['targetSchema']}")
    if mismatches:
        raise RuntimeError("ai_memory 迁移对账失败：" + ", ".join(mismatches))


def parse_args() -> argparse.Namespace:
    """解析命令行参数。"""

    parser = argparse.ArgumentParser(description="ai_memory MySQL 到 PostgreSQL/pgvector 存量迁移与对账工具")
    parser.add_argument("--mode", choices=["plan", "export", "import", "verify", "all"], default="plan")
    parser.add_argument("--apply", action="store_true", help="允许执行 PostgreSQL 写入动作")
    parser.add_argument("--allow-target-not-empty", action="store_true", help="允许导入到非空目标表，默认禁止")
    parser.add_argument("--export-dir", default="", help="导出目录；export/all 未指定时自动创建时间戳目录")
    parser.add_argument("--mysql-container", default=os.getenv("DATASMART_MYSQL_CONTAINER", "datasmart-mysql"))
    parser.add_argument("--mysql-database", default=os.getenv("DATASMART_MYSQL_DATABASE", "datasmart_govern"))
    parser.add_argument("--mysql-user", default=os.getenv("DATASMART_MYSQL_USER", "root"))
    parser.add_argument("--mysql-password", default=os.getenv("DATASMART_MYSQL_PASSWORD", "password"))
    parser.add_argument(
        "--mysql-datetime-timezone",
        default=os.getenv("DATASMART_MYSQL_DATETIME_TIMEZONE", "+00:00"),
        help="旧 MySQL DATETIME(3) 的解释时区，必须是 +00:00 或 +08:00 这类固定偏移",
    )
    parser.add_argument("--postgres-container", default=os.getenv("DATASMART_POSTGRES_CONTAINER", "datasmart-postgresql"))
    parser.add_argument("--postgres-database", default=os.getenv("DATASMART_POSTGRES_DATABASE", "datasmart_govern"))
    parser.add_argument("--postgres-schema", default=os.getenv("DATASMART_POSTGRES_SCHEMA", "ai_memory"))
    parser.add_argument("--postgres-user", default=os.getenv("DATASMART_POSTGRES_USER", "datasmart"))
    return parser.parse_args()


def validate_args(args: argparse.Namespace) -> None:
    """运行前参数校验。"""

    safe_identifier(args.postgres_schema)
    mysql_timezone_literal(args.mysql_datetime_timezone)


def main() -> int:
    """脚本入口，把异常折叠成清晰退出码。"""

    args = parse_args()
    try:
        validate_args(args)
        if args.mode == "plan":
            run_plan(args)
        elif args.mode == "export":
            run_export(args)
        elif args.mode == "import":
            if not args.export_dir:
                raise RuntimeError("import 模式必须指定 --export-dir")
            run_import(args, pathlib.Path(args.export_dir))
        elif args.mode == "verify":
            if not args.export_dir:
                raise RuntimeError("verify 模式必须指定 --export-dir")
            run_verify(args, pathlib.Path(args.export_dir))
        elif args.mode == "all":
            export_dir = run_export(args)
            run_import(args, export_dir)
            run_verify(args, export_dir)
        return 0
    except Exception as exc:  # noqa: BLE001 - 脚本入口需要把失败折叠成可读错误。
        print(f"[ERROR] {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
