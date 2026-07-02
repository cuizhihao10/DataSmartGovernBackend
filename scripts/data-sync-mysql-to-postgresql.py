#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""data-sync MySQL -> PostgreSQL 存量数据迁移与对账工具。

本脚本用于 data-sync 微服务完成 PostgreSQL 代码路径切换之后的“历史业务数据搬迁”阶段。
它只迁移 data-sync 自己拥有的 10 张 `data_sync_*` 控制面事实表，不迁移 task-management
持有的 `task_data_sync_*` 表，也不迁移 Agent Runtime / AI Memory 持有的 `agent_memory_*` 表。

为什么 `task_data_sync_*` 不跟着本脚本一起迁移：
1. 表名前缀里虽然出现 data_sync，但这两张表的 Java Entity、Mapper、Service、Controller 都在 task-management。
2. 它们表达的是 task-management 向 data-sync worker 下发命令、接收执行回执投影的任务平台事实。
3. data-sync 自己保存的是模板、任务、执行、checkpoint、错误样本、事故、审计、幂等和向 task-management
   投递 receipt 的 outbox；这与 task-management 本地 outbox/receipt 是两个方向相反的协作契约。
4. 如果把 `task_data_sync_*` 临时塞进 `data_sync` schema，会让 task-management 后续迁移时出现跨 schema
   JOIN、重复导入、权限边界不清和回滚责任不清的问题。

运行模式：
- plan：只读检查 MySQL 源表、PostgreSQL 目标表和延期迁移表，不写文件、不写数据库。
- export：导出 10 张 data-sync 表为 JSONL，并生成低敏 manifest。
- import：把 JSONL 通过 PostgreSQL COPY 导入目标 schema，必须显式传入 --apply。
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
DEFAULT_OUTPUT_ROOT = REPO_ROOT / "artifacts" / "postgresql-migration" / "data-sync"

# COPY FROM STDIN 需要一个极低概率与真实业务值冲突的 NULL 哨兵。
# 不直接使用常见的 \N，是因为 checkpoint、payload、错误摘要或 JSON 文本理论上都可能出现该字面量。
NULL_SENTINEL = "__DATASMART_DATA_SYNC_POSTGRES_COPY_NULL_9E4D2A18__"

# 这些字段会参与迁移和 checksum，但不会以样本值形式写入 manifest 或终端日志。
# 迁移脚本记录“字段名”是为了提醒操作者导出目录需要按敏感介质保管；字段名本身不是敏感值。
SENSITIVE_COLUMNS_BY_TABLE: dict[str, tuple[str, ...]] = {
    "data_sync_template": (
        "field_mapping_config",
        "filter_config",
        "partition_config",
        "retry_policy",
        "timeout_policy",
    ),
    "data_sync_task": ("schedule_config", "attention_reason", "description"),
    "data_sync_execution": ("checkpoint_ref", "error_summary"),
    "data_sync_callback_idempotency": ("request_digest", "response_summary", "error_message"),
    "data_sync_task_management_receipt_outbox": (
        "last_error_summary",
        "payload_json",
    ),
    "data_sync_checkpoint": ("checkpoint_value",),
    "data_sync_execution_recovery_plan": (
        "window_start",
        "window_end",
        "shard_or_partition",
        "reason",
    ),
    "data_sync_error_sample": (
        "error_message",
        "source_record_key",
        "target_record_key",
        "sample_payload",
    ),
    "data_sync_incident_record": ("description", "resolution_summary"),
    "data_sync_audit_record": ("action_payload",),
}


@dataclass(frozen=True)
class ColumnSpec:
    """跨数据库迁移列定义。

    name:
        JSONL 字段名，也是 PostgreSQL COPY 的目标列名。
    mysql_expr:
        MySQL 导出表达式。为了让源端和目标端 checksum 稳定一致，数值、布尔、时间、JSON 会先规范成文本。
    postgres_expr:
        PostgreSQL 对账表达式。它必须与 MySQL 表达式使用同一套文本规范，否则同一业务值也可能产生不同摘要。
    """

    name: str
    mysql_expr: str
    postgres_expr: str


@dataclass(frozen=True)
class TableSpec:
    """迁移表定义。

    data-sync 这批表都使用 id 主键。迁移时保留 MySQL 原始 id，是为了让执行历史、审计记录、
    checkpoint 引用、事故工单截图和人工排障记录在迁移前后仍能用同一个主键定位。
    """

    name: str
    columns: tuple[ColumnSpec, ...]

    @property
    def column_names(self) -> list[str]:
        """返回 COPY 和 JSONL 使用的稳定列顺序。"""

        return [column.name for column in self.columns]

    @property
    def sensitive_columns(self) -> tuple[str, ...]:
        """返回需要按敏感迁移介质保管、禁止日志样本输出的字段名。"""

        return SENSITIVE_COLUMNS_BY_TABLE.get(self.name, ())


def text_col(name: str) -> ColumnSpec:
    """普通文本列。

    VARCHAR/TEXT 在 MySQL JSON_OBJECT 与 PostgreSQL row_to_json 中都会作为 JSON 字符串输出。
    这类列不额外改写，避免迁移脚本擅自改变用户配置、错误摘要、审计摘要或对象名称。
    """

    return ColumnSpec(name, name, name)


def int_col(name: str) -> ColumnSpec:
    """整数列。

    checksum 阶段统一转为 text，避免不同客户端把同一个数值序列化为 int、Decimal 或字符串时造成假失败。
    COPY 导入 PostgreSQL 时仍会把文本转换回 BIGINT/INTEGER 目标列。
    """

    return ColumnSpec(name, f"CAST({name} AS CHAR)", f"{name}::text")


def bool_col(name: str) -> ColumnSpec:
    """布尔列。

    历史 MySQL 使用 TINYINT(1)，目标 PostgreSQL 使用 BOOLEAN。
    迁移中统一输出 true/false 文本，既能被 PostgreSQL COPY 识别，也能让 checksum 跨库稳定。
    """

    return ColumnSpec(
        name,
        f"CASE WHEN {name} IS NULL THEN NULL WHEN {name} <> 0 THEN 'true' ELSE 'false' END",
        f"CASE WHEN {name} IS NULL THEN NULL WHEN {name} THEN 'true' ELSE 'false' END",
    )


def time_col(name: str) -> ColumnSpec:
    """时间列。

    MySQL DATETIME 与 PostgreSQL TIMESTAMP WITHOUT TIME ZONE 都不携带时区。
    本脚本不做隐式时区换算，只把库内值格式化到微秒级文本；如果客户生产库存在时区约定差异，
    应在停写迁移方案里单独确认，而不是让脚本悄悄改时间。
    """

    return ColumnSpec(
        name,
        f"DATE_FORMAT({name}, '%Y-%m-%d %H:%i:%s.%f')",
        f"to_char({name}, 'YYYY-MM-DD HH24:MI:SS.US')",
    )


def json_text_col(name: str) -> ColumnSpec:
    """MySQL JSON -> PostgreSQL TEXT 的列。

    data-sync 历史 MySQL 中 `payload_json` 是 JSON 类型，而 PostgreSQL V1 为了保持 Java String 映射稳定，
    暂按 TEXT 保存。MySQL 导出时必须 CAST AS CHAR，否则 JSON_OBJECT 会把它嵌成对象/数组，导入后与
    PostgreSQL TEXT 的 JSON 字符串语义不一致，checksum 也会失败。
    """

    return ColumnSpec(name, f"CAST({name} AS CHAR)", name)


TABLES: tuple[TableSpec, ...] = (
    TableSpec(
        "data_sync_template",
        (
            int_col("id"),
            int_col("tenant_id"),
            int_col("project_id"),
            int_col("workspace_id"),
            text_col("name"),
            text_col("description"),
            int_col("source_datasource_id"),
            int_col("target_datasource_id"),
            text_col("source_schema_name"),
            text_col("source_object_name"),
            text_col("target_schema_name"),
            text_col("target_object_name"),
            text_col("source_connector_type"),
            text_col("target_connector_type"),
            text_col("sync_mode"),
            text_col("write_strategy"),
            text_col("primary_key_field"),
            text_col("incremental_field"),
            text_col("field_mapping_config"),
            text_col("filter_config"),
            text_col("partition_config"),
            text_col("retry_policy"),
            text_col("timeout_policy"),
            bool_col("enabled"),
            int_col("created_by"),
            int_col("updated_by"),
            time_col("create_time"),
            time_col("update_time"),
        ),
    ),
    TableSpec(
        "data_sync_task",
        (
            int_col("id"),
            int_col("tenant_id"),
            int_col("project_id"),
            int_col("workspace_id"),
            int_col("template_id"),
            text_col("name"),
            text_col("current_state"),
            text_col("approval_state"),
            text_col("priority"),
            text_col("schedule_config"),
            text_col("run_mode"),
            text_col("trigger_type"),
            int_col("owner_id"),
            int_col("last_execution_id"),
            bool_col("attention_required"),
            text_col("attention_reason"),
            text_col("description"),
            time_col("create_time"),
            time_col("update_time"),
        ),
    ),
    TableSpec(
        "data_sync_execution",
        (
            int_col("id"),
            int_col("tenant_id"),
            int_col("project_id"),
            int_col("workspace_id"),
            int_col("sync_task_id"),
            int_col("execution_no"),
            text_col("execution_state"),
            text_col("trigger_type"),
            time_col("queued_at"),
            time_col("started_at"),
            time_col("finished_at"),
            text_col("checkpoint_ref"),
            int_col("records_read"),
            int_col("records_written"),
            int_col("failed_record_count"),
            text_col("error_summary"),
            int_col("triggered_by"),
            text_col("executor_id"),
            time_col("heartbeat_time"),
            time_col("lease_expire_time"),
            int_col("defer_count"),
            time_col("create_time"),
            time_col("update_time"),
        ),
    ),
    TableSpec(
        "data_sync_callback_idempotency",
        (
            int_col("id"),
            int_col("tenant_id"),
            int_col("sync_task_id"),
            int_col("execution_id"),
            text_col("scope_key"),
            text_col("action"),
            text_col("idempotency_key"),
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
        "data_sync_task_management_receipt_outbox",
        (
            int_col("id"),
            text_col("receipt_id"),
            int_col("tenant_id"),
            int_col("project_id"),
            int_col("workspace_id"),
            int_col("sync_task_id"),
            int_col("sync_execution_id"),
            text_col("event_type"),
            text_col("source_service"),
            text_col("outbox_state"),
            int_col("attempt_count"),
            int_col("max_attempt_count"),
            time_col("next_retry_at"),
            time_col("last_attempt_at"),
            time_col("delivered_at"),
            time_col("dead_letter_at"),
            text_col("last_error_code"),
            text_col("last_error_summary"),
            int_col("actor_id"),
            text_col("actor_role"),
            text_col("trace_id"),
            json_text_col("payload_json"),
            time_col("create_time"),
            time_col("update_time"),
        ),
    ),
    TableSpec(
        "data_sync_checkpoint",
        (
            int_col("id"),
            int_col("tenant_id"),
            int_col("project_id"),
            int_col("workspace_id"),
            int_col("sync_task_id"),
            int_col("execution_id"),
            text_col("checkpoint_type"),
            text_col("checkpoint_value"),
            text_col("shard_or_partition"),
            int_col("records_read"),
            int_col("records_written"),
            time_col("checkpoint_time"),
            time_col("create_time"),
            time_col("update_time"),
        ),
    ),
    TableSpec(
        "data_sync_execution_recovery_plan",
        (
            int_col("id"),
            int_col("tenant_id"),
            int_col("project_id"),
            int_col("workspace_id"),
            int_col("sync_task_id"),
            int_col("execution_id"),
            text_col("recovery_type"),
            int_col("source_execution_id"),
            int_col("source_checkpoint_id"),
            text_col("window_start"),
            text_col("window_end"),
            text_col("shard_or_partition"),
            text_col("reason"),
            text_col("plan_state"),
            time_col("create_time"),
            time_col("update_time"),
        ),
    ),
    TableSpec(
        "data_sync_error_sample",
        (
            int_col("id"),
            int_col("tenant_id"),
            int_col("project_id"),
            int_col("workspace_id"),
            int_col("sync_task_id"),
            int_col("execution_id"),
            text_col("error_type"),
            text_col("error_code"),
            text_col("error_message"),
            text_col("source_record_key"),
            text_col("target_record_key"),
            text_col("sample_payload"),
            bool_col("retryable"),
            time_col("create_time"),
        ),
    ),
    TableSpec(
        "data_sync_incident_record",
        (
            int_col("id"),
            int_col("tenant_id"),
            int_col("project_id"),
            int_col("workspace_id"),
            int_col("sync_task_id"),
            int_col("execution_id"),
            text_col("incident_type"),
            text_col("severity"),
            text_col("incident_status"),
            text_col("title"),
            text_col("description"),
            int_col("operator_id"),
            text_col("operator_role"),
            int_col("assigned_operator_id"),
            text_col("assigned_operator_role"),
            text_col("resolution_summary"),
            time_col("acknowledged_at"),
            time_col("resolved_at"),
            time_col("closed_at"),
            time_col("create_time"),
            time_col("update_time"),
        ),
    ),
    TableSpec(
        "data_sync_audit_record",
        (
            int_col("id"),
            int_col("tenant_id"),
            int_col("project_id"),
            int_col("workspace_id"),
            int_col("template_id"),
            int_col("sync_task_id"),
            int_col("execution_id"),
            text_col("action_type"),
            int_col("actor_id"),
            text_col("actor_role"),
            text_col("action_payload"),
            text_col("result"),
            text_col("trace_id"),
            time_col("create_time"),
        ),
    ),
)

TABLE_NAMES = {table.name for table in TABLES}
IDENTIFIER_PATTERN = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")


def safe_identifier(identifier: str) -> str:
    """校验 SQL 标识符只包含普通字母、数字和下划线。

    本脚本的表名和 schema 名都来自固定配置或命令行参数。即便如此，导入/导出工具仍然要把边界写清楚：
    迁移脚本通常在高权限数据库账号下运行，不能允许通过 schema/table 参数拼接任意 SQL。
    """

    if not IDENTIFIER_PATTERN.match(identifier):
        raise RuntimeError(f"非法 SQL 标识符：{identifier}")
    return identifier


def safe_mysql_identifier(identifier: str) -> str:
    """返回已校验的 MySQL 反引号标识符。"""

    return f"`{safe_identifier(identifier)}`"


def qualified_table(args: argparse.Namespace, table: TableSpec) -> str:
    """返回 PostgreSQL schema.table 表达式。

    这里不使用双引号大小写保留，是因为项目 DDL 全部采用小写下划线命名，保持未引号化标识符更贴近
    Spring/MyBatis 运行时行为。
    """

    return f"{safe_identifier(args.postgres_schema)}.{safe_identifier(table.name)}"


def run_command(command: list[str], *, stdin: str | None = None) -> str:
    """执行外部命令并返回 stdout。

    错误信息只输出程序和前几个参数，不回显完整命令，避免数据库密码、容器环境变量或长 SQL 进入 CI 日志。
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
        safe_program = " ".join(command[:4])
        raise RuntimeError(f"命令执行失败：{safe_program}，exitCode={completed.returncode}")
    return completed.stdout


def mysql_command(args: argparse.Namespace, sql: str) -> list[str]:
    """构造 MySQL CLI 命令。

    密码通过 docker exec 环境变量传入，不出现在 mysql 命令参数里；脚本失败时也不会打印完整命令。
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
    """构造 PostgreSQL CLI 命令。

    ON_ERROR_STOP=1 保证第一条 SQL 失败时立即退出，避免半成功迁移被误判为成功。
    interactive=True 用于 COPY FROM STDIN，这时 docker exec 必须保留 -i。
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
    """执行 MySQL SQL 并返回原始输出。"""

    return run_command(mysql_command(args, sql))


def docker_mysql_lines(args: argparse.Namespace, sql: str) -> Iterator[str]:
    """逐行返回 MySQL 查询结果。

    MySQL JSON_OBJECT 会把换行符转义在 JSON 字符串内，因此按行拆分不会破坏每条 JSON 记录。
    """

    output = docker_mysql(args, sql)
    yield from output.splitlines()


def docker_psql(args: argparse.Namespace, sql: str, *, stdin: str | None = None) -> str:
    """执行 PostgreSQL SQL 并返回原始输出。"""

    return run_command(psql_command(args, sql, interactive=stdin is not None), stdin=stdin)


def docker_psql_lines(args: argparse.Namespace, sql: str) -> Iterator[str]:
    """逐行返回 PostgreSQL 查询结果。"""

    output = docker_psql(args, sql)
    yield from output.splitlines()


def mysql_json_select(table: TableSpec) -> str:
    """生成 MySQL 导出 SQL。

    JSON_OBJECT 的字段顺序跟 TableSpec 一致；checksum 阶段还会按字段名重新规范化，因此数据库 JSON
    输出顺序变化不会影响最终摘要。
    """

    pairs: list[str] = []
    for column in table.columns:
        pairs.append(f"'{column.name}'")
        pairs.append(column.mysql_expr)
    return f"SELECT JSON_OBJECT({', '.join(pairs)}) FROM {safe_mysql_identifier(table.name)} ORDER BY id"


def postgres_json_select(table: TableSpec, schema: str) -> str:
    """生成 PostgreSQL 对账 SQL。

    PostgreSQL 侧表达式必须与 MySQL 导出规范保持一致：
    - 数值转 text；
    - boolean 转 true/false；
    - timestamp 转微秒级文本；
    - JSON/TEXT 字段按原文比较。
    """

    schema = safe_identifier(schema)
    projection = ", ".join(f"{column.postgres_expr} AS {column.name}" for column in table.columns)
    return f"SELECT row_to_json(t) FROM (SELECT {projection} FROM {schema}.{table.name} ORDER BY id) t"


def canonical_row(table: TableSpec, row: dict[str, Any]) -> str:
    """把一行数据规范化成稳定 JSON 字符串。

    checksum 不直接使用数据库原始 JSON 文本，而是按列清单重组，原因是：
    1. 防止字段顺序和空格差异制造假失败；
    2. 防止驱动把同一值表示成数字或字符串导致摘要不一致；
    3. 让缺列、错列尽早暴露，而不是在导入后变成隐蔽数据错位。
    """

    normalized = {column: (None if row.get(column) is None else str(row.get(column))) for column in table.column_names}
    return json.dumps(normalized, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def update_digest(digest: hashlib._Hash, table: TableSpec, row: dict[str, Any]) -> None:
    """把规范化后的行追加到 SHA-256 摘要。"""

    digest.update(canonical_row(table, row).encode("utf-8"))
    digest.update(b"\n")


def checksum_jsonl(table: TableSpec, rows: Iterable[dict[str, Any]]) -> tuple[int, str]:
    """计算行数和稳定 SHA-256 摘要。"""

    digest = hashlib.sha256()
    count = 0
    for row in rows:
        update_digest(digest, table, row)
        count += 1
    return count, digest.hexdigest()


def read_jsonl(path: pathlib.Path) -> Iterator[dict[str, Any]]:
    """按行读取 JSONL 导出文件。"""

    with path.open("r", encoding="utf-8") as handle:
        for line in handle:
            if line.strip():
                yield json.loads(line)


def count_source(args: argparse.Namespace, table: TableSpec) -> int:
    """统计 MySQL 源表行数。"""

    output = docker_mysql(args, f"SELECT COUNT(1) FROM {safe_mysql_identifier(table.name)}")
    return int(output.strip() or "0")


def count_target(args: argparse.Namespace, table: TableSpec) -> int:
    """统计 PostgreSQL 目标表行数。"""

    output = docker_psql(args, f"SELECT COUNT(1) FROM {qualified_table(args, table)}")
    return int(output.strip() or "0")


def detect_deferred_tables(args: argparse.Namespace) -> list[dict[str, Any]]:
    """扫描当前 MySQL schema 中不属于本批 data-sync 迁移的已知混放表。

    记录这些表不是为了迁移它们，而是为了防止“没出现在 JSONL 导出里”被误判为遗漏：
    - `task_data_sync_*` 属于 task-management 命令下发和执行回执投影，目标 schema 是 task_management。
    - `agent_memory_*` 属于 AI Memory / Agent Runtime，目标 schema 是 ai_memory。
    """

    sql = (
        "SELECT table_name FROM information_schema.tables "
        "WHERE table_schema = DATABASE() "
        "AND (table_name REGEXP '^task_data_sync_' "
        "OR table_name REGEXP '^agent_memory_') "
        "ORDER BY table_name"
    )
    deferred: list[dict[str, Any]] = []
    for line in docker_mysql_lines(args, sql):
        table_name = line.strip()
        if not table_name:
            continue
        row_count = int(docker_mysql(args, f"SELECT COUNT(1) FROM {safe_mysql_identifier(table_name)}").strip() or "0")
        if table_name.startswith("task_data_sync_"):
            target_schema = "task_management"
            reason = (
                "task_data_sync_* 是 task-management 保存的 DataSync worker 命令 outbox / 执行回执投影，"
                "应随 task-management PostgreSQL 批次迁入 task_management schema。"
            )
        else:
            target_schema = "ai_memory"
            reason = "agent_memory_* 属于 AI Memory / Agent Runtime，后续迁入 PostgreSQL ai_memory schema。"
        deferred.append(
            {
                "table": table_name,
                "rows": row_count,
                "status": "DEFERRED",
                "targetSchema": target_schema,
                "reason": reason,
            }
        )
    return deferred


def detect_unmapped_data_sync_tables(args: argparse.Namespace) -> list[dict[str, Any]]:
    """扫描 MySQL 中额外出现但不在本批 TableSpec 内的 data_sync_* 表。

    本脚本的迁移对象被固定为当前 Java 服务和 PostgreSQL V1 真实使用的 10 张表。若历史环境存在额外
    data_sync_* 表，脚本不擅自迁移，而是登记为 REVIEW_REQUIRED，让操作者决定是旧表归档、补 DDL，
    还是作为后续 data-sync 扩展表单独迁移。
    """

    sql = (
        "SELECT table_name FROM information_schema.tables "
        "WHERE table_schema = DATABASE() "
        "AND table_name REGEXP '^data_sync_' "
        "ORDER BY table_name"
    )
    review: list[dict[str, Any]] = []
    for line in docker_mysql_lines(args, sql):
        table_name = line.strip()
        if not table_name or table_name in TABLE_NAMES:
            continue
        row_count = int(docker_mysql(args, f"SELECT COUNT(1) FROM {safe_mysql_identifier(table_name)}").strip() or "0")
        review.append(
            {
                "table": table_name,
                "rows": row_count,
                "status": "REVIEW_REQUIRED",
                "targetSchema": "data_sync",
                "reason": (
                    "该表以 data_sync_ 开头，但不在当前 PostgreSQL V1 和 Java 实体使用的 10 张表内；"
                    "为了避免误迁旧实验表或废弃表，脚本只登记不导入。"
                ),
            }
        )
    return review


def export_table(args: argparse.Namespace, table: TableSpec, export_dir: pathlib.Path) -> dict[str, Any]:
    """导出单表 JSONL 并计算源端摘要。

    该函数故意不打印任何行内内容。同步配置、checkpoint、payload、错误样本和审计摘要都可能暴露
    客户业务结构或执行上下文，即便是“摘要”也不应进入普通终端日志。
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

    manifest 是迁移验收附件，但不是业务数据样本仓库。它只记录表名、行数、checksum、敏感字段名、
    延期迁移表和需要人工复核的额外表，不保存 SQL、连接串、凭据、checkpoint 原始值、样本载荷、
    prompt、模型输出或 HTTP 请求/响应正文。
    """

    manifest = {
        "module": "data-sync",
        "source": "mysql",
        "target": "postgresql",
        "generatedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
        "mysqlContainer": args.mysql_container,
        "postgresContainer": args.postgres_container,
        "postgresSchema": args.postgres_schema,
        "securityNotice": (
            "JSONL 导出文件包含真实迁移数据，可能含同步配置、checkpoint、错误样本、审计摘要和低敏 outbox payload。"
            "manifest 不保存样本值，迁移目录仍必须按敏感数据介质保管。"
        ),
        "ownershipNotice": (
            "本脚本只迁移 10 张 data_sync_* 表。task_data_sync_* 归 task_management，agent_memory_* 归 ai_memory。"
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

    存量导入默认只允许空目标表。这样做是为了避免把 PostgreSQL seed/test data 与 MySQL 历史事实混合，
    造成唯一键冲突、审计链路断裂、执行号重复或对账结果无法解释。
    """

    non_empty = [(table.name, count_target(args, table)) for table in TABLES if count_target(args, table) > 0]
    if non_empty and not args.allow_target_not_empty:
        details = ", ".join(f"{name}={count}" for name, count in non_empty)
        raise RuntimeError(f"PostgreSQL 目标表非空，默认拒绝导入：{details}")


def copy_value(table: TableSpec, column: str, value: Any) -> str:
    """把 JSONL 字段转换为 COPY 字段。

    None 使用 NULL_SENTINEL；其他值按字符串导入。若真实业务值刚好等于 NULL_SENTINEL，脚本主动停止，
    避免把真实字符串误导入为 NULL。
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

    COPY 比逐行 INSERT 更适合迁移执行历史、checkpoint、错误样本和审计这类可能持续增长的表。
    这里仍然按行读取 JSONL，不把整个文件读入内存，便于后续在预生产环境处理较大数据量。
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

    COPY 列顺序与 TableSpec 完全一致，并显式包含 id。因为 identity sequence 不会因显式 id 自动推进，
    所有表导入完成后必须统一 reset sequence。
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

    如果保留 MySQL 原始 id 导入后不校正 sequence，应用下一次插入就可能从旧序列值生成重复主键。
    setval(..., is_called=false) 用于空表场景，让下一次 nextval 从 1 开始。
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

    rows = (json.loads(line) for line in docker_psql_lines(args, postgres_json_select(table, args.postgres_schema)) if line.strip())
    return checksum_jsonl(table, rows)


def run_plan(args: argparse.Namespace) -> None:
    """只读迁移计划检查。

    plan 不创建导出目录、不写 PostgreSQL、不导出业务数据，只展示：
    - 10 张 data-sync 控制面表的源端/目标端行数；
    - MySQL 中发现但不属于本批 schema 的 task-management / Agent Memory 混放表；
    - 额外出现、需要人工复核的 data_sync_* 表。
    """

    print("== data-sync MySQL -> PostgreSQL 迁移计划 ==")
    for table in TABLES:
        source_count = count_source(args, table)
        target_count = count_target(args, table)
        sensitive_hint = " sensitive" if table.sensitive_columns else ""
        print(f"[PLAN] {table.name}: mysqlRows={source_count}, postgresRows={target_count}{sensitive_hint}")
    deferred = detect_deferred_tables(args)
    if deferred:
        print("== 延期迁移表 ==")
        for item in deferred:
            print(f"[DEFERRED] {item['table']}: mysqlRows={item['rows']}, targetSchema={item['targetSchema']}")
    else:
        print("[DEFERRED] 当前 MySQL schema 未发现 task_data_sync_* 或 agent_memory_* 混放表")
    review = detect_unmapped_data_sync_tables(args)
    if review:
        print("== 需人工复核的额外 data_sync_* 表 ==")
        for item in review:
            print(f"[REVIEW_REQUIRED] {item['table']}: mysqlRows={item['rows']}, targetSchema={item['targetSchema']}")
    print("提示：写入 PostgreSQL 需要使用 --mode import/all --apply，且默认要求目标表为空。")


def run_export(args: argparse.Namespace) -> pathlib.Path:
    """导出 data-sync 控制面表并写 manifest。"""

    export_dir = pathlib.Path(args.export_dir) if args.export_dir else DEFAULT_OUTPUT_ROOT / dt.datetime.now().strftime("%Y%m%d%H%M%S")
    export_dir.mkdir(parents=True, exist_ok=True)
    results = [export_table(args, table, export_dir) for table in TABLES]
    deferred = detect_deferred_tables(args)
    review = detect_unmapped_data_sync_tables(args)
    write_manifest(args, export_dir, results, deferred, review)
    print(f"[EXPORT] manifest={export_dir / 'manifest.json'}")
    if deferred:
        print(f"[DEFERRED] 已记录 {len(deferred)} 张非本批表；它们不会被导入 data_sync schema。")
    if review:
        print(f"[REVIEW_REQUIRED] 已记录 {len(review)} 张额外 data_sync_* 表；请先确认归属后再单独处理。")
    print("[SECURITY] 导出目录包含真实迁移数据，请按敏感数据介质保管并在验收后清理。")
    return export_dir


def run_import(args: argparse.Namespace, export_dir: pathlib.Path) -> None:
    """导入 data-sync 控制面表。

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
    deferredTables 和 reviewRequiredTables 只打印提示，不参与本批对账，因为它们明确不属于本批导入对象。
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

    默认值面向本地 Docker Compose；预生产/生产环境可以通过环境变量或命令行覆盖容器名、库名、用户和 schema。
    """

    parser = argparse.ArgumentParser(description="data-sync MySQL 到 PostgreSQL 存量迁移与对账工具")
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
    parser.add_argument("--postgres-schema", default=os.getenv("DATASMART_POSTGRES_SCHEMA", "data_sync"))
    parser.add_argument("--postgres-user", default=os.getenv("DATASMART_POSTGRES_USER", "datasmart"))
    return parser.parse_args()


def validate_args(args: argparse.Namespace) -> None:
    """执行运行前参数校验。"""

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
