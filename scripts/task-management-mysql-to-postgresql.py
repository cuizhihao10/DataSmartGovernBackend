#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""task-management MySQL -> PostgreSQL 存量数据迁移与对账工具。

本脚本服务于 task-management 已完成 PostgreSQL 代码路径切换之后的“历史任务数据搬迁”阶段。
它只迁移 task-management 当前 PostgreSQL V1 和 Java 服务真实使用的 8 张任务域表：

1. task
2. task_draft
3. task_execution_log
4. task_execution_run
5. task_callback_idempotency
6. agent_async_task_command_inbox
7. task_data_sync_worker_command_outbox
8. task_data_sync_worker_execution_receipt

为什么脚本必须严格限定边界：
- `task_data_sync_worker_*` 虽然名字里有 data_sync，但实体、Mapper、状态流转和任务侧时间线都在
  task-management；它们表达的是任务中心向 data-sync worker 下发命令、保存执行回执投影的本地事实。
- `agent_async_task_command_outbox`、`agent_run_tool_dag_confirmation` 等表属于 agent-runtime 控制面，
  负责 Agent 运行时出箱、DAG 选择确认和工具执行治理，不应塞进 `task_management` schema。
- `agent_memory_*` 属于 AI Memory / 长期记忆 / pgvector 迁移批次，后续应进入 `ai_memory` schema。
- `data_sync_*`、`sync_*`、`datasource_*`、`quality_*` 等表分别属于其他微服务，脚本只登记它们的延期归属，
  不导出、不导入、不参与 checksum。

运行模式：
- plan：只读检查源表、目标表、延期迁移表和额外待复核 task 表，不写文件、不写数据库。
- export：把 8 张任务域表导出为 JSONL，并生成低敏 manifest。
- import：把 JSONL 通过 PostgreSQL COPY 导入 `task_management` schema，必须显式传入 --apply。
- verify：按行数与稳定 SHA-256 摘要对账。
- all：export -> import -> verify，仍然必须显式传入 --apply 才能写 PostgreSQL。
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
DEFAULT_OUTPUT_ROOT = REPO_ROOT / "artifacts" / "postgresql-migration" / "task-management"

# COPY FROM STDIN 的 NULL 标识必须尽量避免与真实业务字段冲突。
# 不使用常见的 \N，是因为任务 checkpoint、执行摘要、payload_json 或审计 details 里理论上可能出现该字面量。
# 如果真实值刚好等于该哨兵，脚本会主动失败，避免把真实字符串误导入为 NULL。
NULL_SENTINEL = "__DATASMART_TASK_POSTGRES_COPY_NULL_5A36C1F4__"

# 这些列会进入 JSONL 和 checksum，但不会以样本值写入 manifest 或终端日志。
# 字段名本身不是敏感数据；记录字段名的目的是提醒迁移操作者：导出目录必须按敏感迁移介质保管。
SENSITIVE_COLUMNS_BY_TABLE: dict[str, tuple[str, ...]] = {
    "task": ("params", "checkpoint", "result"),
    "task_draft": ("params", "approval_comment"),
    "task_execution_log": ("details",),
    "task_execution_run": ("checkpoint", "error_message"),
    "task_callback_idempotency": ("request_digest", "response_summary", "error_message"),
    "agent_async_task_command_inbox": ("payload_reference", "argument_names", "sensitive_argument_names"),
    "task_data_sync_worker_command_outbox": ("payload_json", "last_error"),
    "task_data_sync_worker_execution_receipt": (
        "checkpoint_value_visibility",
        "error_summary",
        "warning_summary",
    ),
}


@dataclass(frozen=True)
class ColumnSpec:
    """跨数据库迁移列定义。

    name:
        JSONL 字段名，也是 PostgreSQL COPY 的目标列名。
    mysql_expr:
        MySQL 导出表达式。脚本会把数值、布尔、时间、JSON 显式规范化，避免同一业务值因为数据库默认格式
        不同而造成 checksum 假失败。
    postgres_expr:
        PostgreSQL 对账表达式。它必须与 mysql_expr 使用同一套文本规范，才能证明迁移前后语义一致。
    """

    name: str
    mysql_expr: str
    postgres_expr: str


@dataclass(frozen=True)
class TableSpec:
    """迁移表定义。

    task-management 迁移表全部保留 MySQL 原始 id。这样做不是为了迁移脚本省事，而是为了让任务详情、执行日志、
    Agent command、data-sync worker receipt、外部工单截图和生产排障记录在迁移前后仍能用同一个主键定位。
    """

    name: str
    columns: tuple[ColumnSpec, ...]

    @property
    def column_names(self) -> list[str]:
        """返回 JSONL、COPY 和 checksum 使用的稳定列顺序。"""

        return [column.name for column in self.columns]

    @property
    def sensitive_columns(self) -> tuple[str, ...]:
        """返回需要按敏感迁移介质保管、禁止样本日志输出的列名。"""

        return SENSITIVE_COLUMNS_BY_TABLE.get(self.name, ())


def text_col(name: str) -> ColumnSpec:
    """普通文本列。

    任务名称、状态、摘要、类型等字段在 MySQL JSON_OBJECT 和 PostgreSQL row_to_json 中都会以字符串输出。
    对这类字段不额外改写，可以降低迁移脚本擅自改变业务语义的风险。
    """

    return ColumnSpec(name, name, name)


def int_col(name: str) -> ColumnSpec:
    """整数列。

    不同数据库客户端可能把 BIGINT/INTEGER 表达为 int、Decimal 或字符串。
    checksum 阶段统一转成 text，可以避免“数值相同但 JSON 类型不同”的误报；COPY 导入时仍会转回目标整数列。
    """

    return ColumnSpec(name, f"CAST({name} AS CHAR)", f"{name}::text")


def bool_col(name: str) -> ColumnSpec:
    """布尔列。

    历史 MySQL 使用 TINYINT(1)，目标 PostgreSQL 使用 BOOLEAN。
    迁移中统一导出 true/false 文本，既能被 PostgreSQL COPY 正确识别，也能让源端和目标端 checksum 稳定一致。
    """

    return ColumnSpec(
        name,
        f"CASE WHEN {name} IS NULL THEN NULL WHEN {name} <> 0 THEN 'true' ELSE 'false' END",
        f"CASE WHEN {name} IS NULL THEN NULL WHEN {name} THEN 'true' ELSE 'false' END",
    )


def time_col(name: str) -> ColumnSpec:
    """时间列。

    MySQL DATETIME/DATETIME(3) 与 PostgreSQL TIMESTAMP WITHOUT TIME ZONE 都不携带时区。
    迁移脚本不做隐式时区换算，只把库内墙上时间格式化为微秒级文本；如果客户环境存在时区约定差异，
    应在迁移方案里人工确认，而不是让脚本悄悄改时间。
    """

    return ColumnSpec(
        name,
        f"DATE_FORMAT({name}, '%Y-%m-%d %H:%i:%s.%f')",
        f"to_char({name}, 'YYYY-MM-DD HH24:MI:SS.US')",
    )


def json_text_col(name: str) -> ColumnSpec:
    """MySQL JSON -> PostgreSQL TEXT 的列。

    任务中心的 `argument_names`、`sensitive_argument_names`、`payload_json` 在旧 MySQL 中是 JSON，
    但 PostgreSQL V1 为保持 Java String 映射稳定，暂按 TEXT 保存。导出时必须 CAST AS CHAR，
    否则 JSON_OBJECT 可能把 JSON 嵌成对象/数组，导致导入后的 TEXT 语义和 checksum 都不一致。
    """

    return ColumnSpec(name, f"CAST({name} AS CHAR)", name)


TABLES: tuple[TableSpec, ...] = (
    TableSpec(
        "task",
        (
            int_col("id"),
            text_col("name"),
            text_col("description"),
            text_col("type"),
            text_col("creation_idempotency_key"),
            int_col("tenant_id"),
            int_col("owner_id"),
            int_col("project_id"),
            text_col("status"),
            text_col("params"),
            int_col("progress"),
            text_col("checkpoint"),
            text_col("priority"),
            int_col("retry_count"),
            int_col("max_retry_count"),
            int_col("defer_count"),
            int_col("max_defer_count"),
            int_col("current_execution_run_id"),
            text_col("current_executor_id"),
            time_col("queued_time"),
            time_col("heartbeat_time"),
            time_col("lease_expire_time"),
            bool_col("attention_required"),
            int_col("timeout_seconds"),
            time_col("create_time"),
            time_col("update_time"),
            time_col("start_time"),
            time_col("end_time"),
            text_col("result"),
        ),
    ),
    TableSpec(
        "task_draft",
        (
            int_col("id"),
            text_col("name"),
            text_col("description"),
            text_col("type"),
            int_col("tenant_id"),
            int_col("owner_id"),
            int_col("project_id"),
            text_col("status"),
            text_col("params"),
            text_col("priority"),
            int_col("max_retry_count"),
            int_col("max_defer_count"),
            text_col("source_type"),
            text_col("source_ref"),
            int_col("created_by"),
            int_col("submitted_by"),
            int_col("approved_by"),
            text_col("approval_comment"),
            int_col("converted_task_id"),
            time_col("create_time"),
            time_col("update_time"),
            time_col("submit_time"),
            time_col("approval_time"),
            time_col("convert_time"),
        ),
    ),
    TableSpec(
        "task_execution_log",
        (
            int_col("id"),
            int_col("task_id"),
            text_col("action"),
            text_col("from_status"),
            text_col("to_status"),
            text_col("message"),
            text_col("operator"),
            text_col("details"),
            time_col("create_time"),
        ),
    ),
    TableSpec(
        "task_execution_run",
        (
            int_col("id"),
            int_col("task_id"),
            int_col("run_no"),
            text_col("executor_id"),
            text_col("state"),
            text_col("trigger_type"),
            int_col("triggered_by"),
            time_col("started_at"),
            time_col("finished_at"),
            time_col("heartbeat_at"),
            time_col("lease_expire_time"),
            int_col("progress"),
            text_col("checkpoint"),
            text_col("error_message"),
            time_col("create_time"),
            time_col("update_time"),
        ),
    ),
    TableSpec(
        "task_callback_idempotency",
        (
            int_col("id"),
            int_col("task_id"),
            text_col("action"),
            text_col("idempotency_key"),
            int_col("run_id"),
            text_col("executor_id"),
            text_col("request_digest"),
            text_col("callback_state"),
            text_col("response_summary"),
            text_col("error_message"),
            time_col("first_seen_time"),
            time_col("last_seen_time"),
            time_col("create_time"),
            time_col("update_time"),
        ),
    ),
    TableSpec(
        "agent_async_task_command_inbox",
        (
            int_col("id"),
            text_col("command_id"),
            text_col("idempotency_key"),
            text_col("schema_version"),
            text_col("command_type"),
            text_col("audit_id"),
            text_col("session_id"),
            text_col("run_id"),
            text_col("tool_code"),
            text_col("target_service"),
            text_col("target_endpoint"),
            int_col("tenant_id"),
            int_col("project_id"),
            int_col("workspace_id"),
            text_col("actor_id"),
            text_col("trace_id"),
            text_col("payload_reference"),
            json_text_col("argument_names"),
            json_text_col("sensitive_argument_names"),
            text_col("consume_state"),
            int_col("task_id"),
            time_col("first_seen_time"),
            time_col("last_seen_time"),
            time_col("create_time"),
            time_col("update_time"),
        ),
    ),
    TableSpec(
        "task_data_sync_worker_command_outbox",
        (
            int_col("id"),
            text_col("outbox_id"),
            text_col("command_id"),
            text_col("idempotency_key"),
            int_col("task_id"),
            text_col("agent_run_id"),
            text_col("agent_session_id"),
            text_col("audit_id"),
            text_col("tool_code"),
            text_col("target_service"),
            text_col("operation"),
            int_col("tenant_id"),
            int_col("project_id"),
            int_col("workspace_id"),
            text_col("actor_id"),
            text_col("trace_id"),
            int_col("template_id"),
            int_col("sync_template_id"),
            text_col("status"),
            int_col("attempt_count"),
            json_text_col("payload_json"),
            int_col("payload_size_bytes"),
            bool_col("payload_truncated"),
            time_col("next_retry_at"),
            time_col("dispatched_at"),
            text_col("receipt_id"),
            int_col("sync_task_id"),
            int_col("sync_execution_id"),
            bool_col("side_effect_started"),
            bool_col("side_effect_executed"),
            text_col("last_error"),
            time_col("create_time"),
            time_col("update_time"),
        ),
    ),
    TableSpec(
        "task_data_sync_worker_execution_receipt",
        (
            int_col("id"),
            text_col("receipt_id"),
            text_col("command_id"),
            text_col("outbox_id"),
            int_col("task_id"),
            text_col("agent_run_id"),
            text_col("agent_session_id"),
            text_col("audit_id"),
            int_col("tenant_id"),
            int_col("project_id"),
            int_col("workspace_id"),
            int_col("sync_task_id"),
            int_col("sync_execution_id"),
            text_col("event_type"),
            time_col("event_time"),
            text_col("executor_id"),
            text_col("source_service"),
            int_col("batch_records_read"),
            int_col("batch_records_written"),
            int_col("batch_failed_record_count"),
            int_col("total_records_read"),
            int_col("total_records_written"),
            int_col("total_failed_record_count"),
            int_col("progress_percent"),
            bool_col("end_of_source"),
            bool_col("completed"),
            bool_col("failed"),
            bool_col("progress_reported"),
            bool_col("checkpoint_persisted"),
            text_col("checkpoint_type"),
            text_col("checkpoint_value_visibility"),
            text_col("error_summary"),
            int_col("warning_count"),
            text_col("warning_summary"),
            time_col("create_time"),
            time_col("update_time"),
        ),
    ),
)

TABLE_NAMES = {table.name for table in TABLES}
IDENTIFIER_PATTERN = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")


def safe_identifier(identifier: str) -> str:
    """校验 SQL 标识符。

    表名来自固定 TableSpec，schema 名来自命令行或环境变量。迁移脚本通常使用高权限账号运行，
    因此即使参数看起来“只在本地用”，也必须阻止任意 SQL 拼接。
    """

    if not IDENTIFIER_PATTERN.match(identifier):
        raise RuntimeError(f"非法 SQL 标识符：{identifier}")
    return identifier


def safe_mysql_identifier(identifier: str) -> str:
    """校验并引用 MySQL 表名。"""

    return f"`{safe_identifier(identifier)}`"


def qualified_table(args: argparse.Namespace, table: TableSpec) -> str:
    """返回 PostgreSQL schema.table 表达式。"""

    return f"{safe_identifier(args.postgres_schema)}.{safe_identifier(table.name)}"


def redact_command(command: list[str]) -> str:
    """生成低敏命令摘要。

    Docker exec 参数中会出现 MYSQL_PWD。错误日志只展示前几个片段并对密码脱敏，避免 CI 或终端输出泄漏凭据。
    """

    redacted: list[str] = []
    for item in command[:8]:
        redacted.append("MYSQL_PWD=***" if item.startswith("MYSQL_PWD=") else item)
    return " ".join(redacted)


def run_command(command: list[str], *, stdin: str | None = None) -> str:
    """执行短输出命令并返回 stdout。

    stderr 不透传到异常正文，避免数据库错误里夹带长 SQL、连接信息或业务样本。
    """

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
    """按行读取长输出命令。

    任务执行日志、回执和 outbox 在真实客户环境中可能很大。流式读取可以避免脚本一次性持有完整结果集。
    """

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
    """构造 MySQL Docker CLI 命令。

    密码通过 MYSQL_PWD 环境变量传入，不拼到 SQL 中；脚本也不会打印完整命令。
    """

    return [
        "docker",
        "exec",
        "-e",
        f"MYSQL_PWD={args.mysql_password}",
        args.mysql_container,
        "mysql",
        "--batch",
        "--raw",
        "--skip-column-names",
        "--default-character-set=utf8mb4",
        "-u",
        args.mysql_user,
        args.mysql_database,
        "-e",
        sql,
    ]


def psql_command(args: argparse.Namespace, sql: str, *, interactive: bool = False) -> list[str]:
    """构造 PostgreSQL Docker CLI 命令。

    ON_ERROR_STOP=1 确保第一条 SQL 失败时立即停止，避免迁移过程出现“前半段失败但后半段继续执行”的危险状态。
    """

    command = ["docker", "exec"]
    if interactive:
        command.append("-i")
    command.extend(
        [
            args.postgres_container,
            "psql",
            "-U",
            args.postgres_user,
            "-d",
            args.postgres_database,
            "-v",
            "ON_ERROR_STOP=1",
            "-At",
            "-c",
            sql,
        ]
    )
    return command


def docker_mysql(args: argparse.Namespace, sql: str) -> str:
    return run_command(mysql_command(args, sql))


def docker_mysql_lines(args: argparse.Namespace, sql: str) -> Iterator[str]:
    return stream_command(mysql_command(args, sql))


def docker_psql(args: argparse.Namespace, sql: str, *, stdin: str | None = None) -> str:
    return run_command(psql_command(args, sql, interactive=stdin is not None), stdin=stdin)


def docker_psql_lines(args: argparse.Namespace, sql: str) -> Iterator[str]:
    return stream_command(psql_command(args, sql))


def mysql_json_select(table: TableSpec) -> str:
    """生成 MySQL JSONL 导出 SQL。

    JSON_OBJECT 的字段顺序跟 TableSpec 一致；checksum 阶段还会 sort_keys，因此数据库 JSON 输出顺序差异不会影响摘要。
    """

    pairs: list[str] = []
    for column in table.columns:
        pairs.append(f"'{column.name}'")
        pairs.append(column.mysql_expr)
    return f"SELECT JSON_OBJECT({', '.join(pairs)}) FROM {table.name} ORDER BY id"


def postgres_json_select(table: TableSpec, schema: str) -> str:
    """生成 PostgreSQL 对账 SQL。"""

    safe_schema = safe_identifier(schema)
    projection = ", ".join(f"{column.postgres_expr} AS {column.name}" for column in table.columns)
    return f"SELECT row_to_json(t) FROM (SELECT {projection} FROM {safe_schema}.{table.name} ORDER BY id) t"


def canonical_row(table: TableSpec, row: dict[str, Any]) -> str:
    """把一行数据规范化成稳定 JSON 字符串。

    checksum 不直接用数据库输出原文，原因是：字段顺序、空格、数值/字符串表现形式都可能制造假差异。
    按 TableSpec 重组后再 hash，可以把对账焦点放在真正的业务值变化上。
    """

    normalized = {column: (None if row.get(column) is None else str(row.get(column))) for column in table.column_names}
    return json.dumps(normalized, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def update_digest(digest: Any, table: TableSpec, row: dict[str, Any]) -> None:
    digest.update(canonical_row(table, row).encode("utf-8"))
    digest.update(b"\n")


def checksum_jsonl(table: TableSpec, rows: Iterable[dict[str, Any]]) -> tuple[int, str]:
    digest = hashlib.sha256()
    count = 0
    for row in rows:
        update_digest(digest, table, row)
        count += 1
    return count, digest.hexdigest()


def read_jsonl(path: pathlib.Path) -> Iterator[dict[str, Any]]:
    with path.open("r", encoding="utf-8") as handle:
        for line in handle:
            if line.strip():
                yield json.loads(line)


def count_source(args: argparse.Namespace, table: TableSpec) -> int:
    output = docker_mysql(args, f"SELECT COUNT(1) FROM {safe_mysql_identifier(table.name)}")
    return int(output.strip() or "0")


def count_target(args: argparse.Namespace, table: TableSpec) -> int:
    output = docker_psql(args, f"SELECT COUNT(1) FROM {qualified_table(args, table)}")
    return int(output.strip() or "0")


def mysql_table_counts(args: argparse.Namespace, table_names: Iterable[str]) -> dict[str, int]:
    """批量读取 MySQL 表行数。

    在 Windows + Docker Desktop 下，每次 `docker exec mysql ...` 都有明显启动开销。
    如果 plan 阶段逐表 COUNT，几十张跨模块延期表会把一次只读检查拖到数分钟。
    这里把同一批表合成 UNION ALL，一次进入容器即可完成统计，同时仍然通过 safe_mysql_identifier
    校验表名，避免把 information_schema 中的异常名称拼进 SQL。
    """

    names = [safe_identifier(name) for name in table_names]
    if not names:
        return {}
    sql = " UNION ALL ".join(f"SELECT '{name}' AS table_name, COUNT(1) AS row_count FROM {safe_mysql_identifier(name)}" for name in names)
    counts: dict[str, int] = {}
    for line in docker_mysql_lines(args, sql):
        if not line.strip():
            continue
        table_name, row_count = line.rstrip("\n").split("\t", 1)
        counts[table_name] = int(row_count or "0")
    return counts


def postgres_table_counts(args: argparse.Namespace, table_names: Iterable[str]) -> dict[str, int]:
    """批量读取 PostgreSQL 表行数。

    该函数和 mysql_table_counts 的目的相同：把只读 plan 和目标非空保护从“逐表启动容器 CLI”
    优化成“一批表一次查询”，减少本地 E2E 验证时间，也更接近生产迁移工具应具备的可操作性。
    """

    names = [safe_identifier(name) for name in table_names]
    if not names:
        return {}
    schema = safe_identifier(args.postgres_schema)
    sql = " UNION ALL ".join(f"SELECT '{name}' AS table_name, COUNT(1) AS row_count FROM {schema}.{name}" for name in names)
    counts: dict[str, int] = {}
    for line in docker_psql_lines(args, sql):
        if not line.strip():
            continue
        table_name, row_count = line.rstrip("\n").split("|", 1)
        counts[table_name] = int(row_count or "0")
    return counts


def target_schema_for_deferred(table_name: str) -> tuple[str, str]:
    """判断非本批表的后续归属。

    这里做的是迁移治理登记，不是导入。目的是让 `plan/export/verify` 输出清楚说明“哪些表没迁、为什么没迁、后续迁到哪里”，
    避免后续验收时把有意延期误判成遗漏。
    """

    if table_name.startswith("agent_memory_"):
        return "ai_memory", "Agent Memory 表属于长期记忆 / AI Memory，后续应迁入 ai_memory schema 并结合 pgvector 验收。"
    if table_name.startswith("agent_"):
        return "agent_runtime", "Agent Runtime 控制面表属于 agent-runtime，后续应迁入 agent_runtime schema。"
    if table_name.startswith("data_sync_"):
        return "data_sync", "data_sync_* 表属于独立 data-sync 微服务，不应迁入 task_management。"
    if table_name.startswith("sync_") or table_name.startswith("datasource_"):
        return "datasource_management", "sync_* / datasource_* 表属于 datasource-management，不应迁入 task_management。"
    if table_name.startswith("quality_"):
        return "data_quality", "quality_* 表属于 data-quality，不应迁入 task_management。"
    return "unknown", "未知归属表，脚本只登记不迁移，需要人工复核。"


def detect_deferred_tables(args: argparse.Namespace) -> list[dict[str, Any]]:
    """扫描 MySQL 中已知但不属于 task-management 本批的表。

    本函数会把 agent、data-sync、datasource-management、data-quality 的表登记为 DEFERRED。
    登记结果会进入 manifest，便于生产迁移审计说明“这些表不是漏迁，而是归属其他批次”。
    """

    sql = (
        "SELECT table_name FROM information_schema.tables "
        "WHERE table_schema = DATABASE() "
        "AND (table_name REGEXP '^agent_' "
        "OR table_name REGEXP '^data_sync_' "
        "OR table_name REGEXP '^sync_' "
        "OR table_name REGEXP '^datasource_' "
        "OR table_name REGEXP '^quality_') "
        "ORDER BY table_name"
    )
    table_names: list[str] = []
    for line in docker_mysql_lines(args, sql):
        table_name = line.strip()
        if not table_name or table_name in TABLE_NAMES:
            continue
        table_names.append(table_name)
    counts = mysql_table_counts(args, table_names)
    deferred: list[dict[str, Any]] = []
    for table_name in table_names:
        target_schema, reason = target_schema_for_deferred(table_name)
        deferred.append(
            {
                "table": table_name,
                "rows": counts.get(table_name, 0),
                "status": "DEFERRED",
                "targetSchema": target_schema,
                "reason": reason,
            }
        )
    return deferred


def detect_review_required_task_tables(args: argparse.Namespace) -> list[dict[str, Any]]:
    """扫描额外出现但未纳入 V1 的 task 相关表。

    如果历史环境里存在 `task_*` 扩展表，而它不在当前 PostgreSQL V1 和 Java 实体清单内，脚本不会擅自迁移。
    它会标记为 REVIEW_REQUIRED，让操作者决定是废弃旧实验表、补充 V2 迁移，还是归档到其他模块。
    """

    sql = (
        "SELECT table_name FROM information_schema.tables "
        "WHERE table_schema = DATABASE() "
        "AND (table_name = 'task' OR table_name REGEXP '^task_') "
        "ORDER BY table_name"
    )
    table_names: list[str] = []
    for line in docker_mysql_lines(args, sql):
        table_name = line.strip()
        if not table_name or table_name in TABLE_NAMES:
            continue
        table_names.append(table_name)
    counts = mysql_table_counts(args, table_names)
    review: list[dict[str, Any]] = []
    for table_name in table_names:
        review.append(
            {
                "table": table_name,
                "rows": counts.get(table_name, 0),
                "status": "REVIEW_REQUIRED",
                "targetSchema": "task_management",
                "reason": (
                    "该表看起来属于 task-management，但不在当前 PostgreSQL V1 和 Java 实体清单内；"
                    "为避免误迁实验表、废弃表或未来表，脚本只登记不导入。"
                ),
            }
        )
    return review


def export_table(args: argparse.Namespace, table: TableSpec, export_dir: pathlib.Path) -> dict[str, Any]:
    """导出单表 JSONL 并计算源端 checksum。

    该函数只打印表名、行数和 checksum 前缀，绝不打印行内容。任务参数、checkpoint、payload 和错误摘要在客户环境中
    都可能包含低敏业务上下文，不能进入普通终端日志。
    """

    table_path = export_dir / f"{table.name}.jsonl"
    digest = hashlib.sha256()
    count = 0
    with table_path.open("w", encoding="utf-8", newline="\n") as handle:
        for line in docker_mysql_lines(args, mysql_json_select(table)):
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
    deferred_tables: list[dict[str, Any]],
    review_tables: list[dict[str, Any]],
) -> None:
    """写入低敏 manifest。

    manifest 是迁移验收附件，不是业务样本仓库。它只保存表级统计、checksum、敏感字段名和非本批表归属，
    不保存 SQL、连接串、密码、prompt、工具参数正文、模型输出、checkpoint 原值或 payload 样本。
    """

    manifest = {
        "module": "task-management",
        "source": "mysql",
        "target": "postgresql",
        "generatedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
        "mysqlContainer": args.mysql_container,
        "postgresContainer": args.postgres_container,
        "postgresSchema": args.postgres_schema,
        "securityNotice": (
            "JSONL 导出文件包含真实任务迁移数据，可能含任务参数、checkpoint、执行摘要、Agent 命令引用、"
            "data-sync worker payload 和低敏错误摘要。manifest 不保存样本值，迁移目录仍必须按敏感数据介质保管。"
        ),
        "ownershipNotice": (
            "本脚本只迁移 8 张 task-management V1 表。agent_async_task_command_outbox / agent_run_tool_dag_confirmation "
            "归 agent_runtime；agent_memory_* 归 ai_memory；data_sync_* 归 data_sync；sync_* / datasource_* 归 datasource_management。"
        ),
        "tables": table_results,
        "deferredTables": deferred_tables,
        "reviewRequiredTables": review_tables,
    }
    (export_dir / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")


def load_manifest(export_dir: pathlib.Path) -> dict[str, Any]:
    """读取导出 manifest。"""

    manifest_path = export_dir / "manifest.json"
    if not manifest_path.exists():
        raise RuntimeError(f"缺少 manifest.json：{manifest_path}")
    return json.loads(manifest_path.read_text(encoding="utf-8"))


def ensure_target_empty(args: argparse.Namespace) -> None:
    """保护 PostgreSQL 目标表。

    存量导入默认只允许空目标表。任务中心的 task、run、inbox、outbox 都有唯一约束和状态历史；
    如果目标表已有 seed/test data 或上次失败残留，继续 COPY 会造成主键冲突、幂等键冲突或混合事实，难以审计。
    """

    counts = postgres_table_counts(args, (table.name for table in TABLES))
    non_empty = [(table.name, counts.get(table.name, 0)) for table in TABLES if counts.get(table.name, 0) > 0]
    if non_empty and not args.allow_target_not_empty:
        details = ", ".join(f"{name}={count}" for name, count in non_empty)
        raise RuntimeError(f"PostgreSQL 目标表非空，默认拒绝导入：{details}")


def copy_value(table: TableSpec, column: str, value: Any) -> str:
    """把 JSONL 字段转换为 COPY 字段。

    None 使用 NULL_SENTINEL；其他值按字符串导入。如果真实业务值等于 NULL_SENTINEL，脚本停止，避免静默数据损坏。
    """

    if value is None:
        return NULL_SENTINEL
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
    """使用 PostgreSQL COPY 流式导入 JSONL。

    COPY 比逐行 INSERT 更适合迁移任务日志、执行 run、Agent inbox 和 data-sync worker receipt 这类可能持续增长的表。
    这里仍按行读取 JSONL，不把整个文件读入内存，便于后续处理较大的预生产数据集。
    """

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
    """导入单表。

    COPY 列顺序与 TableSpec 完全一致，并显式包含 id。保留 id 后 identity sequence 不会自动前进，
    所以全部表导入完成后必须统一 reset sequence。
    """

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
    """校正 PostgreSQL identity sequence。

    如果保留 MySQL 原始 id 导入后不校正 sequence，应用恢复写入时可能从旧序列值生成重复主键。
    setval(..., is_called=false) 兼容空表场景，让下一次 nextval 从 1 开始。
    """

    schema = safe_identifier(args.postgres_schema)
    statements = []
    for table in TABLES:
        statements.append(
            f"SELECT setval(pg_get_serial_sequence('{schema}.{table.name}', 'id'), "
            f"COALESCE((SELECT MAX(id) FROM {schema}.{table.name}), 1), "
            f"COALESCE((SELECT MAX(id) FROM {schema}.{table.name}), 0) > 0);"
        )
    docker_psql(args, " ".join(statements))
    print("[SEQUENCE] PostgreSQL identity sequence 已按最大 id 校正")


def target_checksum(args: argparse.Namespace, table: TableSpec) -> tuple[int, str]:
    """计算 PostgreSQL 目标表行数和 checksum。"""

    rows = (
        json.loads(line)
        for line in docker_psql_lines(args, postgres_json_select(table, args.postgres_schema))
        if line.strip()
    )
    return checksum_jsonl(table, rows)


def run_plan(args: argparse.Namespace) -> None:
    """只读迁移计划检查。

    plan 不创建导出目录、不写 PostgreSQL、不导出业务数据，只展示本批 8 张任务域表行数、
    非本批表延期归属，以及额外 task 表的人工复核提示。
    """

    print("== task-management MySQL -> PostgreSQL 迁移计划 ==")
    source_counts = mysql_table_counts(args, (table.name for table in TABLES))
    target_counts = postgres_table_counts(args, (table.name for table in TABLES))
    for table in TABLES:
        source_count = source_counts.get(table.name, 0)
        target_count = target_counts.get(table.name, 0)
        sensitive_hint = " sensitive" if table.sensitive_columns else ""
        print(f"[PLAN] {table.name}: mysqlRows={source_count}, postgresRows={target_count}{sensitive_hint}")
    deferred = detect_deferred_tables(args)
    if deferred:
        print("== 延期迁移表 ==")
        for item in deferred:
            print(f"[DEFERRED] {item['table']}: mysqlRows={item['rows']}, targetSchema={item['targetSchema']}")
    else:
        print("[DEFERRED] 当前 MySQL schema 未发现非本批 agent/data-sync/datasource/data-quality 混放表")
    review = detect_review_required_task_tables(args)
    if review:
        print("== 需要人工复核的额外 task 表 ==")
        for item in review:
            print(f"[REVIEW_REQUIRED] {item['table']}: mysqlRows={item['rows']}, targetSchema={item['targetSchema']}")
    print("提示：写入 PostgreSQL 需要使用 --mode import/all --apply，且默认要求目标任务域表为空。")


def run_export(args: argparse.Namespace) -> pathlib.Path:
    """导出 task-management 任务域表并写 manifest。"""

    export_dir = pathlib.Path(args.export_dir) if args.export_dir else DEFAULT_OUTPUT_ROOT / dt.datetime.now().strftime("%Y%m%d%H%M%S")
    export_dir.mkdir(parents=True, exist_ok=True)
    results = [export_table(args, table, export_dir) for table in TABLES]
    deferred = detect_deferred_tables(args)
    review = detect_review_required_task_tables(args)
    write_manifest(args, export_dir, results, deferred, review)
    print(f"[EXPORT] manifest={export_dir / 'manifest.json'}")
    if deferred:
        print(f"[DEFERRED] 已记录 {len(deferred)} 张非本批表；它们不会被导入 task_management schema。")
    if review:
        print(f"[REVIEW_REQUIRED] 已记录 {len(review)} 张额外 task 表；请先确认归属后再单独处理。")
    print("[SECURITY] 导出目录包含真实迁移数据，请按敏感数据介质保管并在验收后清理。")
    return export_dir


def run_import(args: argparse.Namespace, export_dir: pathlib.Path) -> None:
    """导入 task-management 任务域表。

    import 是写 PostgreSQL 的阶段，必须显式 --apply。
    """

    if not args.apply:
        raise RuntimeError("导入会写入 PostgreSQL，必须显式传入 --apply")
    ensure_target_empty(args)
    for table in TABLES:
        import_table(args, table, export_dir)
    reset_identity_sequences(args)


def run_verify(args: argparse.Namespace, export_dir: pathlib.Path) -> None:
    """迁移后对账。

    verify 使用 manifest 中的源端 checksum 与当前 PostgreSQL 重新计算出的 checksum 比对。
    deferredTables 和 reviewRequiredTables 只打印提示，不参与本批对账。
    """

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
    for item in manifest.get("deferredTables", []):
        print(f"[DEFERRED] {item['table']}: rows={item['rows']}, targetSchema={item['targetSchema']}, status={item['status']}")
    for item in manifest.get("reviewRequiredTables", []):
        print(f"[REVIEW_REQUIRED] {item['table']}: rows={item['rows']}, targetSchema={item['targetSchema']}, status={item['status']}")
    if mismatches:
        raise RuntimeError("迁移对账失败：" + ", ".join(mismatches))


def parse_args() -> argparse.Namespace:
    """解析命令行参数。

    默认值面向本地 Docker Compose；预生产/生产可以通过环境变量或命令行覆盖容器名、库名、用户和 schema。
    """

    parser = argparse.ArgumentParser(description="task-management MySQL 到 PostgreSQL 存量迁移与对账工具")
    parser.add_argument("--mode", choices=["plan", "export", "import", "verify", "all"], default="plan")
    parser.add_argument("--apply", action="store_true", help="允许执行 PostgreSQL 写入动作")
    parser.add_argument("--allow-target-not-empty", action="store_true", help="允许导入到非空目标表，默认禁止")
    parser.add_argument("--export-dir", default="", help="导出目录；export/all 未指定时自动创建时间戳目录")
    parser.add_argument("--mysql-container", default=os.getenv("DATASMART_MYSQL_CONTAINER", "datasmart-mysql"))
    parser.add_argument("--mysql-database", default=os.getenv("DATASMART_MYSQL_DATABASE", "datasmart_govern"))
    parser.add_argument("--mysql-user", default=os.getenv("DATASMART_MYSQL_USER", "root"))
    parser.add_argument("--mysql-password", default=os.getenv("DATASMART_MYSQL_PASSWORD", "password"))
    parser.add_argument("--postgres-container", default=os.getenv("DATASMART_POSTGRES_CONTAINER", "datasmart-postgresql"))
    parser.add_argument("--postgres-database", default=os.getenv("DATASMART_POSTGRES_DATABASE", "datasmart_govern"))
    parser.add_argument("--postgres-schema", default=os.getenv("DATASMART_POSTGRES_SCHEMA", "task_management"))
    parser.add_argument("--postgres-user", default=os.getenv("DATASMART_POSTGRES_USER", "datasmart"))
    return parser.parse_args()


def validate_args(args: argparse.Namespace) -> None:
    """运行前参数校验。"""

    safe_identifier(args.postgres_schema)


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
    except Exception as exc:  # noqa: BLE001 - 脚本入口需要把失败折叠成清晰退出码。
        print(f"[ERROR] {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
