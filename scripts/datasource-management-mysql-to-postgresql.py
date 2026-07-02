#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""datasource-management MySQL -> PostgreSQL 存量数据迁移与对账工具。

本脚本用于 datasource-management 已完成 PostgreSQL 代码路径切换之后的“历史业务数据搬迁”阶段。
它只处理 datasource-management 当前 Java 服务真实使用的 14 张控制面表，不处理独立 data-sync
微服务的 `data_sync_*` 表，也不处理 Agent Runtime / AI Memory 的 `agent_memory_*` 表。

为什么 datasource-management 的迁移脚本要比普通业务表更谨慎：
1. `datasource_config` 保存外部客户数据源连接信息，包含 JDBC URL、用户名、密码、驱动类名等敏感字段。
2. 只读 SQL 审计、同步审计、告警投递等表可能保存 SQL 预览、错误摘要、通道地址摘要、业务对象名。
3. Agent 命令 receipt 可能关联 session/run/audit/tool 信息，虽然是低敏控制面数据，但不应扩散到日志样本。

因此本脚本采用以下安全边界：
- 日志只输出表名、行数、checksum 前缀、迁移目录路径，不输出任何行内容。
- manifest 只记录表级统计、敏感列名和延期迁移表清单，不写入样本数据、JDBC URL、密码、SQL 正文或 token。
- JSONL 导出文件是迁移载体，确实包含真实业务数据；生产环境应放在加密磁盘、受控目录或临时安全工作区，
  迁移完成并通过验收后按企业数据销毁流程处理。
- 错误摘要会对命令参数脱敏，避免把数据库密码、连接串、SQL 或客户样本带到 CI/终端日志。

运行模式：
- plan：只读检查 MySQL 源表、PostgreSQL 目标表行数和延期迁移表，不写文件、不写数据库。
- export：从 MySQL 导出 14 张表为 JSONL，同时生成低敏 manifest。
- import：将 JSONL 通过 PostgreSQL COPY 导入目标 schema，必须显式传入 --apply。
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
DEFAULT_OUTPUT_ROOT = REPO_ROOT / "artifacts" / "postgresql-migration" / "datasource-management"

# COPY FROM STDIN 需要一个极低概率与真实业务值冲突的 NULL 哨兵。
# 不直接使用常见的 \N，是因为 datasource-management 中 SQL 摘要、错误摘要、JSON 文本理论上可能出现该字面量。
NULL_SENTINEL = "__DATASMART_DATASOURCE_POSTGRES_COPY_NULL_2C7B91F0__"

# 这些字段会参与迁移和 checksum，但不会以样本形式写入 manifest 或日志。
# 注意：字段名本身不是敏感值，manifest 记录字段名是为了提醒运维人员导出文件需要加密保管。
SENSITIVE_COLUMNS_BY_TABLE: dict[str, tuple[str, ...]] = {
    "datasource_config": ("jdbc_url", "username", "password"),
    "datasource_readonly_sql_execution_audit": ("sql_preview", "failure_message"),
    "sync_template": (
        "field_mapping_config",
        "filter_config",
        "partition_config",
        "retry_policy",
        "timeout_policy",
    ),
    "sync_task": ("schedule_config", "latest_error_summary", "incident_note"),
    "sync_agent_command_receipt": ("message",),
    "sync_execution": ("error_summary", "trigger_reason"),
    "sync_checkpoint": ("checkpoint_value",),
    "sync_audit_record": ("action_payload",),
    "sync_permission_policy_change_request": (
        "binding_values_json",
        "request_reason",
        "required_approver_roles_json",
        "approval_comment",
        "execution_summary",
    ),
    "sync_governance_alert": (
        "summary",
        "detail",
        "last_delivery_error",
        "dead_letter_reason",
    ),
    "sync_alert_delivery_record": (
        "target_endpoint",
        "response_summary",
        "error_summary",
    ),
    "sync_permission_governance_notification": (
        "summary",
        "detail",
        "last_dispatch_error",
    ),
}


@dataclass(frozen=True)
class ColumnSpec:
    """跨库迁移列定义。

    name:
        JSONL 字段名，也是 PostgreSQL COPY 的目标列名。
    mysql_expr:
        MySQL 导出表达式。为了让源端和目标端 checksum 稳定一致，数值、布尔、时间字段会先规范成文本。
    postgres_expr:
        PostgreSQL 对账表达式。它必须和 mysql_expr 使用同一套文本规范，否则相同业务值也可能产生不同摘要。
    """

    name: str
    mysql_expr: str
    postgres_expr: str


@dataclass(frozen=True)
class TableSpec:
    """迁移表定义。

    datasource-management 这批表都使用 id 主键，迁移时保留 MySQL 原始 id。
    保留原 id 的原因不是为了“偷懒”，而是为了让审计记录、告警、执行历史、外部工单截图或人工排障记录
    在迁移前后仍能用同一主键定位，降低预生产和生产切换时的验收成本。
    """

    name: str
    columns: tuple[ColumnSpec, ...]

    @property
    def column_names(self) -> list[str]:
        """返回 COPY 和 JSONL 使用的稳定列顺序。"""

        return [column.name for column in self.columns]

    @property
    def sensitive_columns(self) -> tuple[str, ...]:
        """返回该表中需要特殊保管、禁止日志样本输出的字段名。"""

        return SENSITIVE_COLUMNS_BY_TABLE.get(self.name, ())


def text_col(name: str) -> ColumnSpec:
    """普通文本列。

    MySQL JSON_OBJECT 与 PostgreSQL row_to_json 都会把 VARCHAR/TEXT 输出为 JSON 字符串。
    对这类列不额外转换，既保留原始业务语义，也避免迁移脚本擅自改写配置文本。
    """

    return ColumnSpec(name, name, name)


def int_col(name: str) -> ColumnSpec:
    """整数列。

    不同数据库客户端可能把整数反序列化成 int、Decimal 或字符串。
    checksum 阶段统一转成 text，可以避免“值相同但 JSON 类型不同”造成假失败。
    COPY 导入 PostgreSQL 时仍会把文本转换回目标整数列。
    """

    return ColumnSpec(name, f"CAST({name} AS CHAR)", f"{name}::text")


def bool_col(name: str) -> ColumnSpec:
    """布尔列。

    历史 MySQL 使用 TINYINT(1)，目标 PostgreSQL 使用 BOOLEAN。
    迁移中统一用 true/false 文本表达，既能被 PostgreSQL COPY 识别，也能让 checksum 跨库稳定。
    """

    return ColumnSpec(
        name,
        f"CASE WHEN {name} IS NULL THEN NULL WHEN {name} <> 0 THEN 'true' ELSE 'false' END",
        f"CASE WHEN {name} IS NULL THEN NULL WHEN {name} THEN 'true' ELSE 'false' END",
    )


def time_col(name: str) -> ColumnSpec:
    """时间列。

    MySQL DATETIME 与 PostgreSQL TIMESTAMP WITHOUT TIME ZONE 都不携带时区。
    迁移脚本不做隐式时区换算，只把库内值格式化为微秒级文本；如果客户生产库存在时区约定差异，
    应在停写迁移方案中单独确认，而不是让脚本悄悄改时间。
    """

    return ColumnSpec(
        name,
        f"DATE_FORMAT({name}, '%Y-%m-%d %H:%i:%s.%f')",
        f"to_char({name}, 'YYYY-MM-DD HH24:MI:SS.US')",
    )


TABLES: tuple[TableSpec, ...] = (
    TableSpec(
        "datasource_config",
        (
            int_col("id"),
            int_col("tenant_id"),
            int_col("project_id"),
            int_col("workspace_id"),
            text_col("name"),
            text_col("type"),
            text_col("jdbc_url"),
            text_col("username"),
            text_col("password"),
            text_col("driver_class_name"),
            text_col("description"),
            text_col("status"),
            text_col("last_test_status"),
            text_col("last_test_message"),
            time_col("last_test_time"),
            time_col("create_time"),
            time_col("update_time"),
        ),
    ),
    TableSpec(
        "datasource_readonly_sql_execution_audit",
        (
            int_col("datasource_tenant_id"),
            int_col("datasource_project_id"),
            int_col("datasource_workspace_id"),
            int_col("id"),
            int_col("datasource_id"),
            text_col("datasource_name"),
            text_col("datasource_type"),
            text_col("purpose"),
            int_col("actor_tenant_id"),
            int_col("actor_id"),
            text_col("actor_role"),
            text_col("actor_type"),
            text_col("source_service"),
            text_col("trace_id"),
            text_col("sql_fingerprint"),
            text_col("sql_preview"),
            int_col("requested_max_rows"),
            int_col("applied_max_rows"),
            int_col("requested_query_timeout_seconds"),
            int_col("applied_query_timeout_seconds"),
            int_col("returned_row_count"),
            int_col("column_count"),
            int_col("duration_ms"),
            text_col("execution_status"),
            text_col("failure_message"),
            time_col("executed_at"),
            time_col("create_time"),
        ),
    ),
    TableSpec(
        "sync_template",
        (
            int_col("id"),
            int_col("tenant_id"),
            int_col("project_id"),
            int_col("workspace_id"),
            text_col("name"),
            text_col("description"),
            int_col("source_datasource_id"),
            text_col("source_schema_name"),
            text_col("source_object_name"),
            int_col("target_datasource_id"),
            text_col("target_schema_name"),
            text_col("target_object_name"),
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
        "sync_task",
        (
            int_col("id"),
            int_col("tenant_id"),
            int_col("project_id"),
            int_col("workspace_id"),
            int_col("template_id"),
            text_col("name"),
            text_col("description"),
            text_col("current_state"),
            text_col("approval_state"),
            text_col("priority"),
            text_col("run_mode"),
            text_col("trigger_type"),
            text_col("schedule_config"),
            int_col("owner_id"),
            int_col("last_execution_id"),
            time_col("next_run_at"),
            time_col("queued_at"),
            text_col("current_executor_id"),
            time_col("dispatch_lease_expire_at"),
            bool_col("enabled"),
            bool_col("operator_attention_required"),
            int_col("timeout_seconds"),
            int_col("max_retry_count"),
            int_col("retry_count"),
            int_col("queue_attempt_count"),
            text_col("latest_error_summary"),
            text_col("incident_note"),
            int_col("created_by"),
            int_col("updated_by"),
            time_col("create_time"),
            time_col("update_time"),
        ),
    ),
    TableSpec(
        "sync_agent_command_receipt",
        (
            int_col("id"),
            text_col("receipt_id"),
            text_col("command_id"),
            text_col("idempotency_key"),
            text_col("agent_session_id"),
            text_col("agent_run_id"),
            text_col("audit_id"),
            text_col("tool_code"),
            int_col("tenant_id"),
            int_col("project_id"),
            int_col("workspace_id"),
            text_col("actor_id"),
            text_col("trace_id"),
            int_col("template_id"),
            int_col("sync_template_id"),
            int_col("resolved_template_id"),
            int_col("sync_task_id"),
            int_col("sync_execution_id"),
            text_col("status"),
            text_col("downstream_state"),
            bool_col("side_effect_started"),
            bool_col("side_effect_executed"),
            bool_col("duplicate"),
            text_col("message"),
            time_col("create_time"),
            time_col("update_time"),
        ),
    ),
    TableSpec(
        "sync_execution",
        (
            int_col("id"),
            int_col("sync_task_id"),
            int_col("execution_no"),
            text_col("state"),
            time_col("started_at"),
            time_col("finished_at"),
            text_col("checkpoint_ref"),
            int_col("records_read"),
            int_col("records_written"),
            int_col("failed_record_count"),
            text_col("error_summary"),
            int_col("triggered_by"),
            text_col("executor_id"),
            time_col("heartbeat_at"),
            time_col("lease_expire_at"),
            text_col("trigger_reason"),
            time_col("create_time"),
            time_col("update_time"),
        ),
    ),
    TableSpec(
        "sync_checkpoint",
        (
            int_col("id"),
            int_col("execution_id"),
            text_col("checkpoint_type"),
            text_col("checkpoint_value"),
            text_col("shard_or_partition"),
            time_col("update_time"),
        ),
    ),
    TableSpec(
        "sync_audit_record",
        (
            int_col("id"),
            int_col("tenant_id"),
            int_col("sync_task_id"),
            int_col("execution_id"),
            text_col("action_type"),
            int_col("actor_id"),
            text_col("actor_role"),
            text_col("action_payload"),
            time_col("create_time"),
        ),
    ),
    TableSpec(
        "sync_permission_policy_binding",
        (
            int_col("id"),
            int_col("tenant_id"),
            text_col("actor_role"),
            text_col("binding_type"),
            text_col("binding_value"),
            text_col("binding_source"),
            bool_col("enabled"),
            int_col("priority"),
            text_col("note"),
            int_col("created_by"),
            int_col("updated_by"),
            time_col("create_time"),
            time_col("update_time"),
        ),
    ),
    TableSpec(
        "sync_permission_policy_change_request",
        (
            int_col("id"),
            int_col("target_tenant_id"),
            int_col("requester_id"),
            text_col("requester_role"),
            int_col("requester_tenant_id"),
            text_col("target_role"),
            text_col("binding_type"),
            text_col("binding_values_json"),
            int_col("requested_priority"),
            text_col("requested_binding_source"),
            text_col("request_reason"),
            text_col("required_approver_roles_json"),
            text_col("request_status"),
            int_col("approver_id"),
            text_col("approver_role"),
            text_col("approval_mode"),
            int_col("delegated_from_approver_id"),
            text_col("delegated_from_approver_role"),
            text_col("approval_comment"),
            time_col("approved_at"),
            time_col("executed_at"),
            text_col("execution_summary"),
            time_col("create_time"),
            time_col("update_time"),
        ),
    ),
    TableSpec(
        "sync_permission_approval_delegate_rule",
        (
            int_col("id"),
            int_col("target_tenant_id"),
            int_col("delegator_id"),
            text_col("delegator_role"),
            int_col("delegate_id"),
            text_col("delegate_role"),
            time_col("effective_from"),
            time_col("effective_to"),
            bool_col("enabled"),
            text_col("delegate_reason"),
            int_col("created_by"),
            int_col("updated_by"),
            time_col("create_time"),
            time_col("update_time"),
        ),
    ),
    TableSpec(
        "sync_governance_alert",
        (
            int_col("id"),
            int_col("tenant_id"),
            int_col("sync_task_id"),
            text_col("alert_type"),
            text_col("severity"),
            text_col("alert_status"),
            text_col("delivery_status"),
            text_col("delivery_channel"),
            text_col("alert_key"),
            text_col("summary"),
            text_col("detail"),
            text_col("source_resource"),
            text_col("triggered_by_action"),
            time_col("first_occurred_at"),
            time_col("last_occurred_at"),
            int_col("occurrence_count"),
            int_col("acknowledged_by"),
            time_col("acknowledged_at"),
            int_col("resolved_by"),
            time_col("resolved_at"),
            time_col("last_delivery_at"),
            time_col("next_delivery_attempt_at"),
            int_col("delivery_attempt_count"),
            text_col("last_delivery_error"),
            time_col("dead_lettered_at"),
            text_col("dead_letter_reason"),
            text_col("dispatch_lease_owner"),
            time_col("dispatch_lease_expire_at"),
            time_col("create_time"),
            time_col("update_time"),
        ),
    ),
    TableSpec(
        "sync_alert_delivery_record",
        (
            int_col("id"),
            int_col("tenant_id"),
            int_col("alert_id"),
            int_col("sync_task_id"),
            int_col("attempt_no"),
            text_col("channel"),
            text_col("delivery_status"),
            text_col("target_endpoint"),
            bool_col("manual_dispatch"),
            int_col("operator_id"),
            text_col("operator_role"),
            text_col("response_summary"),
            text_col("error_summary"),
            time_col("started_at"),
            time_col("finished_at"),
            time_col("create_time"),
        ),
    ),
    TableSpec(
        "sync_permission_governance_notification",
        (
            int_col("id"),
            int_col("tenant_id"),
            int_col("change_request_id"),
            text_col("notification_type"),
            int_col("recipient_actor_id"),
            text_col("recipient_actor_role"),
            text_col("notification_channel"),
            text_col("notification_status"),
            text_col("summary"),
            text_col("detail"),
            time_col("next_dispatch_at"),
            int_col("dispatch_attempt_count"),
            time_col("dispatched_at"),
            text_col("last_dispatch_error"),
            int_col("read_by"),
            time_col("read_at"),
            time_col("create_time"),
            time_col("update_time"),
        ),
    ),
)


def redact_command(command: list[str]) -> str:
    """生成低敏命令摘要。

    Docker exec 参数中可能包含 MYSQL_PWD。即使只是本地脚本，也不能把密码写入终端、CI 日志或异常摘要。
    这里仅展示前几个命令片段并对环境变量形式的密码脱敏，避免故障时泄漏连接凭据。
    """

    redacted: list[str] = []
    for item in command[:8]:
        if item.startswith("MYSQL_PWD="):
            redacted.append("MYSQL_PWD=***")
        else:
            redacted.append(item)
    return " ".join(redacted)


def run_command(command: list[str], *, stdin: str | None = None) -> str:
    """执行短输出命令。

    该函数用于 COUNT、sequence reset 等低敏短 SQL。
    如果命令失败，只抛出脱敏命令摘要和退出码，不回显 stderr，避免数据库错误中带出 SQL 正文或敏感值。
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

    datasource-management 的审计、告警和执行历史可能比配置表大很多。
    使用流式读取可以避免 Python 一次性持有整张表输出，也减少迁移脚本自身成为内存瓶颈的风险。
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

    MySQL 密码通过容器环境变量 MYSQL_PWD 传入，不拼接到 SQL 中。
    由于命令参数仍可能被终端或 CI 记录，失败摘要会再次经过 redact_command 脱敏。
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

    ON_ERROR_STOP=1 确保第一条 SQL 失败时 psql 立刻退出，避免迁移脚本进入“前半段已写入、后半段继续跑”的危险状态。
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


def safe_mysql_identifier(identifier: str) -> str:
    """校验并引用 MySQL 标识符。

    延期迁移表名来自 information_schema，但迁移工具属于运维入口，仍然做白名单校验。
    这样即使未来有人创建了异常表名，也不会被拼进 COUNT SQL。
    """

    if not re.fullmatch(r"[A-Za-z0-9_]+", identifier):
        raise RuntimeError(f"MySQL 表名包含不安全字符，已拒绝处理：{identifier!r}")
    return f"`{identifier}`"


def mysql_json_select(table: TableSpec) -> str:
    """生成 MySQL JSONL 导出 SQL。

    JSON_OBJECT 的字段顺序跟 TableSpec 一致；checksum 阶段还会按字段名重新规范化，因此数据库 JSON 输出顺序变化
    不会影响最终摘要。
    """

    pairs: list[str] = []
    for column in table.columns:
        pairs.append(f"'{column.name}'")
        pairs.append(column.mysql_expr)
    return f"SELECT JSON_OBJECT({', '.join(pairs)}) FROM {table.name} ORDER BY id"


def postgres_json_select(table: TableSpec, schema: str) -> str:
    """生成 PostgreSQL 对账 SQL。

    PostgreSQL 侧表达式必须与 MySQL 导出规范保持一致：
    - 数值转 text；
    - boolean 转 true/false；
    - timestamp 转微秒级文本；
    - 配置、payload、错误摘要等 TEXT 字段按原文比较。
    """

    projection = ", ".join(f"{column.postgres_expr} AS {column.name}" for column in table.columns)
    return f"SELECT row_to_json(t) FROM (SELECT {projection} FROM {schema}.{table.name} ORDER BY id) t"


def canonical_row(table: TableSpec, row: dict[str, Any]) -> str:
    """把一行数据规范化成稳定 JSON 字符串。

    checksum 不直接使用数据库原始 JSON 文本，而是按列清单重组，原因是：
    1. 防止字段顺序和空格差异制造假失败；
    2. 防止驱动把同一值表示成数字或字符串导致摘要不一致；
    3. 让缺列、错列尽早暴露，而不是在导入后才变成隐蔽数据错位。
    """

    normalized = {column: (None if row.get(column) is None else str(row.get(column))) for column in table.column_names}
    return json.dumps(normalized, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def update_digest(digest: hashlib._Hash, table: TableSpec, row: dict[str, Any]) -> None:
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
    output = docker_mysql(args, f"SELECT COUNT(1) FROM {table.name}")
    return int(output.strip() or "0")


def count_target(args: argparse.Namespace, table: TableSpec) -> int:
    output = docker_psql(args, f"SELECT COUNT(1) FROM {args.postgres_schema}.{table.name}")
    return int(output.strip() or "0")


def detect_deferred_tables(args: argparse.Namespace) -> list[dict[str, Any]]:
    """扫描当前 MySQL schema 中不属于本批 datasource-management 的已知混放表。

    记录这些表不是为了迁移它们，而是为了防止“没出现在 JSONL 导出里”被误判为遗漏：
    - `data_sync_*` 属于独立 data-sync 微服务，应在后续 data-sync PostgreSQL 批次处理。
    - `agent_memory_*` 属于 AI Memory / Agent Runtime，应在 ai_memory PostgreSQL/pgvector 批次处理。
    - `task_data_sync_*` 属于 task-management 与 data-sync 的桥接 outbox/receipt，应随 task 或 data-sync 边界确认。
    """

    sql = (
        "SELECT table_name FROM information_schema.tables "
        "WHERE table_schema = DATABASE() "
        "AND (table_name REGEXP '^data_sync_' "
        "OR table_name REGEXP '^agent_memory_' "
        "OR table_name REGEXP '^task_data_sync_') "
        "ORDER BY table_name"
    )
    deferred: list[dict[str, Any]] = []
    for line in docker_mysql_lines(args, sql):
        table_name = line.strip()
        if not table_name:
            continue
        row_count = int(docker_mysql(args, f"SELECT COUNT(1) FROM {safe_mysql_identifier(table_name)}").strip() or "0")
        if table_name.startswith("agent_memory_"):
            target_schema = "ai_memory"
            reason = "Agent Memory 表属于 AI Memory / Agent Runtime，后续迁入 PostgreSQL ai_memory schema。"
        elif table_name.startswith("task_data_sync_"):
            target_schema = "task_management_or_data_sync"
            reason = "task_data_sync_* 是任务与同步执行桥接表，需在 task-management/data-sync 边界迁移时处理。"
        else:
            target_schema = "data_sync"
            reason = "data_sync_* 表属于独立 data-sync 微服务，本脚本不迁入 datasource_management schema。"
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


def export_table(args: argparse.Namespace, table: TableSpec, export_dir: pathlib.Path) -> dict[str, Any]:
    """导出单表 JSONL 并计算源端摘要。

    该函数故意不打印任何行内容。即使是配置名、SQL preview 或告警 summary，在客户环境中也可能暴露业务系统信息。
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
) -> None:
    """写入低敏 manifest。

    manifest 是迁移验收附件，但不是业务数据样本仓库。它只记录：
    - 哪些表被迁移；
    - 行数和 checksum；
    - 哪些列需要敏感数据保管；
    - 哪些混放表被延期到其他 schema。
    """

    manifest = {
        "module": "datasource-management",
        "source": "mysql",
        "target": "postgresql",
        "generatedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
        "mysqlContainer": args.mysql_container,
        "postgresContainer": args.postgres_container,
        "postgresSchema": args.postgres_schema,
        "securityNotice": (
            "JSONL 导出文件包含真实迁移数据，datasource_config 可能包含 JDBC URL、用户名和密码。"
            "manifest 不保存样本值，迁移目录应按敏感数据介质保管。"
        ),
        "tables": table_results,
        "deferredTables": deferred_tables,
    }
    (export_dir / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")


def load_manifest(export_dir: pathlib.Path) -> dict[str, Any]:
    manifest_path = export_dir / "manifest.json"
    if not manifest_path.exists():
        raise RuntimeError(f"缺少 manifest.json：{manifest_path}")
    return json.loads(manifest_path.read_text(encoding="utf-8"))


def ensure_target_empty(args: argparse.Namespace) -> None:
    """保护 PostgreSQL 目标表。

    存量导入默认只允许空目标表。这样做是为了避免把 PostgreSQL seed/test data 与 MySQL 历史事实混合，
    造成唯一键冲突、审计链路断裂或对账结果无法解释。
    """

    non_empty = [(table.name, count_target(args, table)) for table in TABLES if count_target(args, table) > 0]
    if non_empty and not args.allow_target_not_empty:
        details = ", ".join(f"{name}={count}" for name, count in non_empty)
        raise RuntimeError(f"PostgreSQL 目标表非空，默认拒绝导入：{details}")


def copy_value(table: TableSpec, column: str, value: Any) -> str:
    """把 JSONL 字段转换为 COPY 字段。

    None 使用 NULL_SENTINEL；其他值按字符串导入。
    如果真实业务值刚好等于 NULL_SENTINEL，脚本会主动停止，避免把真实字符串误导入为 NULL。
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

    COPY 比逐行 INSERT 更适合迁移执行历史、审计和告警这类可能持续增长的表。
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

    COPY 列顺序与 TableSpec 完全一致，并显式包含 id。
    因为 identity sequence 不会因显式 id 自动推进，所有表导入完成后必须统一 reset sequence。
    """

    jsonl_path = export_dir / f"{table.name}.jsonl"
    if not jsonl_path.exists():
        raise RuntimeError(f"缺少导出文件：{jsonl_path}")
    columns = ", ".join(table.column_names)
    copy_sql = (
        f"COPY {args.postgres_schema}.{table.name} ({columns}) "
        f"FROM STDIN WITH (FORMAT csv, DELIMITER E'\\t', NULL '{NULL_SENTINEL}')"
    )
    row_count = docker_psql_copy_from_jsonl(args, copy_sql, table, jsonl_path)
    print(f"[IMPORT] {table.name}: rows={row_count}")


def reset_identity_sequences(args: argparse.Namespace) -> None:
    """校正 PostgreSQL identity sequence。

    如果保留 MySQL 原始 id 导入后不校正 sequence，应用下一次插入就可能从旧序列值生成重复主键。
    setval(..., is_called=false) 用于空表场景，让下一次 nextval 从 1 开始。
    """

    statements = []
    for table in TABLES:
        statements.append(
            f"SELECT setval(pg_get_serial_sequence('{args.postgres_schema}.{table.name}', 'id'), "
            f"COALESCE((SELECT MAX(id) FROM {args.postgres_schema}.{table.name}), 1), "
            f"COALESCE((SELECT MAX(id) FROM {args.postgres_schema}.{table.name}), 0) > 0);"
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
    - 14 张 datasource-management 控制面表的源端/目标端行数；
    - MySQL 中发现但不属于本批 schema 的 data-sync / Agent Memory 混放表。
    """

    print("== datasource-management MySQL -> PostgreSQL 迁移计划 ==")
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
        print("[DEFERRED] 当前 MySQL schema 未发现 data_sync_*、task_data_sync_* 或 agent_memory_* 混放表")
    print("提示：写入 PostgreSQL 需要使用 --mode import/all --apply，且默认要求目标表为空。")


def run_export(args: argparse.Namespace) -> pathlib.Path:
    """导出 datasource-management 控制面表并写 manifest。"""

    export_dir = pathlib.Path(args.export_dir) if args.export_dir else DEFAULT_OUTPUT_ROOT / dt.datetime.now().strftime("%Y%m%d%H%M%S")
    export_dir.mkdir(parents=True, exist_ok=True)
    results = [export_table(args, table, export_dir) for table in TABLES]
    deferred = detect_deferred_tables(args)
    write_manifest(args, export_dir, results, deferred)
    print(f"[EXPORT] manifest={export_dir / 'manifest.json'}")
    if deferred:
        print(f"[DEFERRED] 已记录 {len(deferred)} 张非本批表；它们不会被导入 datasource_management schema。")
    print("[SECURITY] 导出目录包含真实迁移数据，请按敏感数据介质保管并在验收后清理。")
    return export_dir


def run_import(args: argparse.Namespace, export_dir: pathlib.Path) -> None:
    """导入 datasource-management 控制面表。

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
    deferredTables 只打印提示，不参与本批对账，因为它们明确不属于 datasource_management schema。
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
    if mismatches:
        raise RuntimeError("迁移对账失败：" + ", ".join(mismatches))


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="datasource-management MySQL 到 PostgreSQL 存量迁移与对账工具")
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
    parser.add_argument("--postgres-schema", default=os.getenv("DATASMART_POSTGRES_SCHEMA", "datasource_management"))
    parser.add_argument("--postgres-user", default=os.getenv("DATASMART_POSTGRES_USER", "datasmart"))
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
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
