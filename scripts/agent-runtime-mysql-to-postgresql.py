#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""agent-runtime MySQL -> PostgreSQL 存量数据迁移与对账工具。

本脚本服务于 `agent-runtime` 已完成 PostgreSQL 控制面代码路径切换之后的历史数据搬迁阶段。
它只迁移 PostgreSQL V1 中已经明确属于 Java Agent Runtime 控制面的 11 张表：

1. agent_tool_execution_audit
2. agent_tool_execution_event_outbox
3. agent_async_task_command_outbox
4. agent_run_tool_dag_confirmation
5. agent_skill_visibility_snapshot_index
6. agent_tool_action_resume_locator_index
7. agent_tool_action_clarification_fact
8. agent_tool_action_worker_receipt_index
9. agent_command_worker_lease
10. agent_artifact_body_read_grant_fact
11. agent_tool_action_submission_fact

边界说明：
- `agent_memory_*` 不属于本批迁移对象。长期记忆、用户画像、pgvector 语义索引和 LangGraph durable state
  后续必须进入独立 `ai_memory` schema，不能混入 `agent_runtime`。
- `task_*` / `task_data_sync_*` 属于 task-management，尤其 `task_data_sync_worker_*` 是任务中心保存的
  DataSync worker 命令 outbox 和执行回执投影，不随 agent-runtime 迁移。
- `data_sync_*`、`sync_*`、`datasource_*`、`quality_*` 分别属于其他业务微服务，本脚本只登记归属，不导出、不导入。

运行模式：
- plan：只读检查源表、目标表、延期表和待人工复核表，不写文件、不写 PostgreSQL。
- export：导出 11 张控制面表为 JSONL，并生成低敏 manifest。
- import：通过 PostgreSQL COPY FROM STDIN 导入 JSONL，必须显式传入 --apply。
- verify：按行数和稳定 SHA-256 checksum 对账。
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
DEFAULT_OUTPUT_ROOT = REPO_ROOT / "artifacts" / "postgresql-migration" / "agent-runtime"

# COPY FROM STDIN 需要一个极低概率与真实业务值冲突的 NULL 哨兵。
# 不直接使用 PostgreSQL 常见的 \N，是因为 payload、attributes、issueCodes、message 等文本理论上都可能出现该字面量。
# 如果真实值刚好等于哨兵，脚本会主动失败，避免把真实字符串误导入为 NULL。
NULL_SENTINEL = "__DATASMART_AGENT_RUNTIME_POSTGRES_COPY_NULL_D0C4A713__"

# 这些列会进入导出文件和 checksum，但不会以样本值形式写入 manifest 或终端日志。
# 字段名本身不是敏感数据；记录字段名是为了提醒迁移操作者：导出目录必须按敏感迁移介质保管。
SENSITIVE_COLUMNS_BY_TABLE: dict[str, tuple[str, ...]] = {
    "agent_tool_execution_audit": (
        "allowed_actions",
        "plan_reason",
        "plan_arguments",
        "governance_hints",
        "parameter_validation",
        "message",
        "approval_comment",
        "output_summary",
    ),
    "agent_tool_execution_event_outbox": ("payload_json", "last_error"),
    "agent_async_task_command_outbox": ("payload_reference", "payload_json", "last_error"),
    "agent_run_tool_dag_confirmation": (
        "selected_node_ids",
        "selected_audit_ids",
        "policy_versions",
        "delegation_evidence",
        "bridge_source_evidence",
        "outbox_ids",
        "command_ids",
    ),
    "agent_skill_visibility_snapshot_index": (
        "visible_skill_codes_json",
        "hidden_skill_codes_json",
        "visible_risk_level_counts_json",
        "visible_domain_counts_json",
        "hidden_admission_status_counts_json",
        "attributes_json",
        "display_summary",
    ),
    "agent_tool_action_clarification_fact": ("evidence_codes_json", "issue_codes_json"),
    "agent_command_worker_lease": ("fencing_token",),
    "agent_artifact_body_read_grant_fact": (
        "artifact_reference",
        "matched_receipt_fingerprint",
        "revoke_reason_code",
    ),
    "agent_tool_action_submission_fact": (
        "payload_reference",
        "issue_codes",
        "recommended_actions",
        "low_sensitive_message",
    ),
}


@dataclass(frozen=True)
class ColumnSpec:
    """跨数据库迁移列定义。

    name:
        JSONL 字段名，也是 PostgreSQL COPY 的目标列名。
    mysql_expr:
        MySQL 导出表达式。脚本会把数值、布尔、时间、JSON 按稳定文本规范导出，避免同一业务值因客户端格式不同导致 checksum 误报。
    postgres_expr:
        PostgreSQL 对账表达式。它必须与 mysql_expr 使用同一套文本规范，才能证明迁移前后语义一致。
    """

    name: str
    mysql_expr: str
    postgres_expr: str


@dataclass(frozen=True)
class TableSpec:
    """迁移表定义。

    has_identity_id:
        只有拥有 `id` identity 主键的表才需要导入后校正 sequence。
        `agent_command_worker_lease` 和 `agent_tool_action_submission_fact` 使用业务键作为主键，不能盲目调用
        pg_get_serial_sequence，否则迁移工具本身会制造失败。
    """

    name: str
    columns: tuple[ColumnSpec, ...]
    has_identity_id: bool = True

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

    这类字段在 MySQL JSON_OBJECT 和 PostgreSQL jsonb_build_object 中都会作为 JSON 字符串输出。
    脚本不额外改写，避免迁移工具擅自改变控制面事实的业务语义。
    """

    return ColumnSpec(name, name, name)


def int_col(name: str) -> ColumnSpec:
    """整数列。

    checksum 阶段统一转为 text，避免不同数据库客户端把同一个 BIGINT/INTEGER 序列化为 int、Decimal 或字符串时产生假差异。
    COPY 导入时 PostgreSQL 仍会按目标列类型把文本转换回整数。
    """

    return ColumnSpec(name, f"CAST({name} AS CHAR)", f"{name}::text")


def bool_col(name: str) -> ColumnSpec:
    """布尔列。

    历史 MySQL 使用 TINYINT(1)，目标 PostgreSQL 使用 BOOLEAN。
    迁移中统一使用 true/false 文本，既能被 PostgreSQL COPY 正确识别，也能让源端和目标端 checksum 稳定一致。
    """

    return ColumnSpec(
        name,
        f"CASE WHEN {name} IS NULL THEN NULL WHEN {name} <> 0 THEN 'true' ELSE 'false' END",
        f"CASE WHEN {name} IS NULL THEN NULL WHEN {name} THEN 'true' ELSE 'false' END",
    )


def time_col(name: str) -> ColumnSpec:
    """时间列。

    MySQL DATETIME/DATETIME(3) 与 PostgreSQL TIMESTAMP WITHOUT TIME ZONE 都不携带时区。
    脚本不做隐式时区换算，只把库内墙上时间格式化为微秒级文本；如果客户环境存在时区约定差异，应在迁移方案中人工确认。
    """

    return ColumnSpec(
        name,
        f"DATE_FORMAT({name}, '%Y-%m-%d %H:%i:%s.%f')",
        f"to_char({name}, 'YYYY-MM-DD HH24:MI:SS.US')",
    )


def json_text_col(name: str) -> ColumnSpec:
    """MySQL JSON -> PostgreSQL TEXT 的列。

    agent-runtime PostgreSQL V1 为了保持当前 Java JDBC mapper 的 String 绑定稳定，JSON-like 字段暂按 TEXT 保存。
    MySQL 导出时必须 CAST AS CHAR，否则 JSON_OBJECT 会把 JSON 嵌成对象/数组，导入后 TEXT 语义和 checksum 都会不一致。
    """

    return ColumnSpec(name, f"CAST({name} AS CHAR)", name)


TABLES: tuple[TableSpec, ...] = (
    TableSpec(
        "agent_tool_execution_audit",
        (
            int_col("id"),
            text_col("audit_id"),
            text_col("session_id"),
            text_col("run_id"),
            text_col("binding_id"),
            text_col("tool_code"),
            text_col("tool_type"),
            text_col("target_service"),
            text_col("target_endpoint"),
            int_col("target_resource_id"),
            int_col("tenant_id"),
            int_col("project_id"),
            int_col("workspace_id"),
            text_col("actor_id"),
            text_col("risk_level"),
            text_col("execution_mode"),
            bool_col("requires_approval"),
            bool_col("read_only"),
            bool_col("idempotent"),
            json_text_col("allowed_actions"),
            text_col("plan_reason"),
            json_text_col("plan_arguments"),
            json_text_col("governance_hints"),
            json_text_col("parameter_validation"),
            text_col("state"),
            text_col("trace_id"),
            text_col("message"),
            text_col("approval_operator_id"),
            text_col("approval_comment"),
            time_col("approval_time"),
            time_col("execution_start_time"),
            time_col("execution_finish_time"),
            text_col("output_summary"),
            text_col("error_code"),
            time_col("create_time"),
            time_col("update_time"),
        ),
    ),
    TableSpec(
        "agent_tool_execution_event_outbox",
        (
            int_col("id"),
            text_col("outbox_id"),
            text_col("event_id"),
            text_col("event_type"),
            text_col("schema_version"),
            text_col("source"),
            text_col("partition_key"),
            int_col("tenant_id"),
            int_col("project_id"),
            int_col("workspace_id"),
            text_col("actor_id"),
            text_col("session_id"),
            text_col("run_id"),
            text_col("audit_id"),
            text_col("tool_code"),
            text_col("current_state"),
            text_col("status"),
            int_col("attempt_count"),
            json_text_col("payload_json"),
            int_col("payload_size_bytes"),
            bool_col("payload_truncated"),
            time_col("occurred_at"),
            time_col("next_retry_at"),
            time_col("published_at"),
            text_col("last_error"),
            time_col("create_time"),
            time_col("update_time"),
        ),
    ),
    TableSpec(
        "agent_async_task_command_outbox",
        (
            int_col("id"),
            text_col("outbox_id"),
            text_col("command_id"),
            text_col("idempotency_key"),
            text_col("schema_version"),
            text_col("command_type"),
            text_col("partition_key"),
            text_col("command_topic"),
            text_col("consumer_service"),
            text_col("session_id"),
            text_col("run_id"),
            text_col("audit_id"),
            text_col("tool_code"),
            text_col("target_service"),
            text_col("target_endpoint"),
            int_col("tenant_id"),
            int_col("project_id"),
            int_col("workspace_id"),
            text_col("actor_id"),
            text_col("trace_id"),
            text_col("payload_reference"),
            text_col("status"),
            int_col("attempt_count"),
            json_text_col("payload_json"),
            int_col("payload_size_bytes"),
            bool_col("payload_truncated"),
            time_col("next_retry_at"),
            time_col("published_at"),
            text_col("last_error"),
            time_col("create_time"),
            time_col("update_time"),
        ),
    ),
    TableSpec(
        "agent_run_tool_dag_confirmation",
        (
            int_col("id"),
            text_col("confirmation_id"),
            text_col("session_id"),
            text_col("run_id"),
            text_col("selection_fingerprint"),
            json_text_col("selected_node_ids"),
            json_text_col("selected_audit_ids"),
            json_text_col("policy_versions"),
            json_text_col("delegation_evidence"),
            json_text_col("bridge_source_evidence"),
            json_text_col("outbox_ids"),
            json_text_col("command_ids"),
            int_col("tenant_id"),
            int_col("project_id"),
            int_col("workspace_id"),
            text_col("actor_id"),
            text_col("trace_id"),
            bool_col("confirmed"),
            text_col("status"),
            time_col("expires_at"),
            time_col("create_time"),
            time_col("update_time"),
        ),
    ),
    TableSpec(
        "agent_skill_visibility_snapshot_index",
        (
            int_col("id"),
            text_col("identity_key"),
            text_col("schema_version"),
            text_col("source"),
            text_col("event_type"),
            text_col("stage"),
            text_col("message"),
            text_col("severity"),
            text_col("tenant_id"),
            text_col("project_id"),
            text_col("actor_id"),
            text_col("request_id"),
            text_col("run_id"),
            text_col("session_id"),
            int_col("producer_sequence"),
            int_col("replay_sequence"),
            time_col("created_at"),
            time_col("published_at"),
            time_col("consumed_at"),
            text_col("snapshot_type"),
            text_col("snapshot_source"),
            bool_col("available"),
            int_col("available_skill_count"),
            int_col("visible_skill_count"),
            int_col("hidden_skill_count"),
            int_col("conditional_visible_skill_count"),
            text_col("permission_fact_source"),
            text_col("actor_role_source"),
            text_col("actor_role"),
            int_col("granted_permission_count"),
            bool_col("tenant_skill_enabled"),
            text_col("workspace_risk_level"),
            text_col("tenant_plan_code"),
            text_col("policy_version"),
            bool_col("legacy_request_variables_detected"),
            bool_col("model_gateway_available"),
            bool_col("tool_budget_allowed"),
            text_col("manifest_binding_status"),
            text_col("manifest_status"),
            text_col("manifest_source"),
            text_col("manifest_fingerprint"),
            text_col("manifest_schema_version"),
            int_col("manifest_skill_count"),
            int_col("manifest_ready_skill_count"),
            int_col("manifest_non_ready_skill_count"),
            bool_col("manifest_fallback"),
            json_text_col("visible_skill_codes_json"),
            int_col("visible_skill_codes_truncated_count"),
            json_text_col("hidden_skill_codes_json"),
            int_col("hidden_skill_codes_truncated_count"),
            json_text_col("visible_risk_level_counts_json"),
            json_text_col("visible_domain_counts_json"),
            json_text_col("hidden_admission_status_counts_json"),
            text_col("display_summary"),
            int_col("recommended_action_count"),
            json_text_col("attributes_json"),
            time_col("create_time"),
            time_col("update_time"),
        ),
    ),
    TableSpec(
        "agent_tool_action_resume_locator_index",
        (
            int_col("id"),
            text_col("checkpoint_id"),
            text_col("thread_id"),
            text_col("session_id"),
            text_col("run_id"),
            text_col("command_id"),
            text_col("outbox_id"),
            text_col("approval_fact_id"),
            text_col("clarification_fact_id"),
            text_col("tool_code"),
            text_col("requested_policy_version"),
            text_col("tenant_id"),
            text_col("project_id"),
            text_col("actor_id"),
            time_col("updated_at"),
            time_col("create_time"),
            time_col("update_time"),
        ),
    ),
    TableSpec(
        "agent_tool_action_clarification_fact",
        (
            int_col("id"),
            text_col("clarification_fact_id"),
            text_col("session_id"),
            text_col("run_id"),
            text_col("command_id"),
            text_col("tool_code"),
            text_col("requested_policy_version"),
            text_col("tenant_id"),
            text_col("project_id"),
            text_col("actor_id"),
            text_col("status"),
            json_text_col("evidence_codes_json"),
            json_text_col("issue_codes_json"),
            time_col("expires_at"),
            time_col("created_at"),
            time_col("updated_at"),
            time_col("create_time"),
            time_col("update_time"),
        ),
    ),
    TableSpec(
        "agent_tool_action_worker_receipt_index",
        (
            int_col("id"),
            text_col("event_identity_key"),
            text_col("command_id"),
            text_col("tenant_id"),
            text_col("project_id"),
            text_col("actor_id"),
            text_col("run_id"),
            text_col("session_id"),
            text_col("tool_code"),
            text_col("task_status"),
            text_col("outcome"),
            bool_col("pre_check_passed"),
            bool_col("side_effect_executed"),
            text_col("error_code"),
            int_col("replay_sequence"),
            time_col("consumed_at"),
            time_col("indexed_at"),
            time_col("create_time"),
            time_col("update_time"),
        ),
    ),
    TableSpec(
        "agent_command_worker_lease",
        (
            text_col("lease_identity_key"),
            text_col("session_id"),
            text_col("run_id"),
            text_col("command_id"),
            text_col("executor_id"),
            text_col("tenant_id"),
            text_col("project_id"),
            text_col("actor_id"),
            text_col("fencing_token"),
            int_col("lease_version"),
            time_col("lease_expires_at"),
            time_col("acquired_at"),
            time_col("updated_at"),
            time_col("create_time"),
            time_col("update_time"),
        ),
        has_identity_id=False,
    ),
    TableSpec(
        "agent_artifact_body_read_grant_fact",
        (
            int_col("id"),
            text_col("grant_decision_reference"),
            text_col("command_id"),
            text_col("artifact_reference"),
            text_col("artifact_reference_type"),
            text_col("read_purpose"),
            text_col("requested_content_mode"),
            int_col("max_readable_bytes"),
            text_col("tenant_id"),
            text_col("project_id"),
            text_col("actor_id"),
            text_col("run_id"),
            text_col("session_id"),
            text_col("tool_code"),
            text_col("matched_receipt_fingerprint"),
            int_col("replay_sequence"),
            text_col("receipt_outcome"),
            time_col("issued_at"),
            time_col("expires_at"),
            text_col("status"),
            time_col("revoked_at"),
            text_col("revoked_by"),
            text_col("revoke_reason_code"),
            time_col("create_time"),
            time_col("update_time"),
        ),
    ),
    TableSpec(
        "agent_tool_action_submission_fact",
        (
            text_col("submission_identity_key"),
            text_col("command_id"),
            text_col("idempotency_key"),
            text_col("session_id"),
            text_col("run_id"),
            text_col("audit_id"),
            text_col("tool_code"),
            text_col("tenant_id"),
            text_col("project_id"),
            text_col("actor_id"),
            text_col("payload_reference"),
            text_col("confirmation_id"),
            text_col("policy_version"),
            text_col("target_service"),
            text_col("target_endpoint"),
            text_col("status"),
            bool_col("side_effect_started"),
            bool_col("side_effect_executed"),
            text_col("outcome"),
            int_col("downstream_task_id"),
            text_col("downstream_task_status"),
            text_col("error_code"),
            text_col("issue_codes"),
            text_col("recommended_actions"),
            text_col("low_sensitive_message"),
            time_col("first_submitted_at"),
            time_col("last_updated_at"),
            time_col("create_time"),
            time_col("update_time"),
        ),
        has_identity_id=False,
    ),
)

TABLE_NAMES = {table.name for table in TABLES}
IDENTIFIER_PATTERN = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")


def safe_identifier(identifier: str) -> str:
    """校验 SQL 标识符只包含普通字母、数字和下划线。

    迁移脚本经常使用高权限数据库账号运行，所以即使命令行参数看似只在本地使用，也必须阻止任意 SQL 拼接。
    """

    if not IDENTIFIER_PATTERN.match(identifier):
        raise RuntimeError(f"非法 SQL 标识符：{identifier}")
    return identifier


def safe_mysql_identifier(identifier: str) -> str:
    """返回已经校验过的 MySQL 反引号表名。"""

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


def mysql_command(args: argparse.Namespace, sql: str) -> list[str]:
    """构造 MySQL Docker CLI 命令。

    密码通过 MYSQL_PWD 环境变量传入，不拼到 SQL 中；脚本失败时也不会打印完整命令。
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

    `ON_ERROR_STOP=1` 确保第一条 SQL 失败时立即退出，避免半成功迁移被误判为成功。
    interactive=True 用于 COPY FROM STDIN，此时 docker exec 必须保留 -i。
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
    """执行 MySQL 查询并返回原始 stdout。"""

    return run_command(mysql_command(args, sql))


def docker_psql(args: argparse.Namespace, sql: str) -> str:
    """执行 PostgreSQL 查询并返回原始 stdout。"""

    return run_command(psql_command(args, sql))


def docker_mysql_lines(args: argparse.Namespace, sql: str) -> list[str]:
    """执行 MySQL 查询并按行返回非空结果。"""

    return [line for line in docker_mysql(args, sql).splitlines() if line.strip()]


def docker_psql_lines(args: argparse.Namespace, sql: str) -> list[str]:
    """执行 PostgreSQL 查询并按行返回非空结果。"""

    return [line for line in docker_psql(args, sql).splitlines() if line.strip()]


def mysql_table_exists(args: argparse.Namespace, table_name: str) -> bool:
    """检查源端 MySQL 是否存在指定表。

    迁移脚本要兼容不同历史环境：如果某些较新的 agent-runtime 表尚未在旧库中创建，应当把行数视为 0，
    而不是让计划检查直接失败。
    """

    sql = (
        "SELECT COUNT(1) FROM information_schema.tables "
        "WHERE table_schema = DATABASE() "
        f"AND table_name = '{safe_identifier(table_name)}'"
    )
    return int(docker_mysql(args, sql).strip() or "0") > 0


def postgres_table_exists(args: argparse.Namespace, table_name: str) -> bool:
    """检查目标 PostgreSQL schema 是否存在指定表。"""

    sql = (
        "SELECT COUNT(1) FROM information_schema.tables "
        f"WHERE table_schema = '{safe_identifier(args.postgres_schema)}' "
        f"AND table_name = '{safe_identifier(table_name)}'"
    )
    return int(docker_psql(args, sql).strip() or "0") > 0


def mysql_table_counts(args: argparse.Namespace, table_names: Iterable[str]) -> dict[str, int]:
    """批量读取 MySQL 表行数。

    早期版本逐表执行 `docker exec mysql ... SELECT COUNT(1)`，在当前项目这种历史迁移脚本很多、混放表很多的库里，
    单次 `plan` 会因为 Docker CLI 往返次数过多而变慢甚至超时。
    现在分两步处理：
    1. 先从 information_schema 一次性确认哪些表真实存在；
    2. 再用一个 UNION ALL 精确统计存在表的行数。

    这样既保留精确 COUNT，又能让只读计划检查在真实本地容器环境中快速返回。
    """

    names = [safe_identifier(table_name) for table_name in table_names]
    counts: dict[str, int] = {table_name: 0 for table_name in names}
    if not names:
        return counts
    quoted_names = ", ".join(f"'{table_name}'" for table_name in names)
    existing_sql = (
        "SELECT table_name FROM information_schema.tables "
        "WHERE table_schema = DATABASE() "
        f"AND table_name IN ({quoted_names}) "
        "ORDER BY table_name"
    )
    existing = [line.strip() for line in docker_mysql_lines(args, existing_sql)]
    if not existing:
        return counts
    count_sql = " UNION ALL ".join(
        f"SELECT '{table_name}' AS table_name, COUNT(1) AS row_count FROM {safe_mysql_identifier(table_name)}"
        for table_name in existing
    )
    for line in docker_mysql_lines(args, count_sql):
        table_name, row_count = line.split("\t", 1)
        counts[table_name] = int(row_count)
    return counts


def postgres_table_counts(args: argparse.Namespace, table_names: Iterable[str]) -> dict[str, int]:
    """批量读取 PostgreSQL 表行数。"""

    counts: dict[str, int] = {}
    for table_name in table_names:
        if not postgres_table_exists(args, table_name):
            raise RuntimeError(f"PostgreSQL 目标表不存在：{args.postgres_schema}.{table_name}")
        counts[table_name] = int(docker_psql(args, f"SELECT COUNT(1) FROM {safe_identifier(args.postgres_schema)}.{safe_identifier(table_name)}").strip() or "0")
    return counts


def mysql_json_select(table: TableSpec) -> str:
    """生成 MySQL JSONL 导出查询。

    JSON_OBJECT 的 key 顺序不参与最终 checksum，因为脚本会读取 JSON 后按 TableSpec 顺序重新摘要。
    ORDER BY 使用第一列，保持导出文件可复现；控制面表的第一列不是 id 时，也都是稳定业务主键。
    """

    pairs: list[str] = []
    for column in table.columns:
        pairs.append(f"'{column.name}', {column.mysql_expr}")
    return f"SELECT JSON_OBJECT({', '.join(pairs)}) FROM {safe_mysql_identifier(table.name)} ORDER BY {safe_identifier(table.column_names[0])}"


def postgres_json_select(table: TableSpec, schema: str) -> str:
    """生成 PostgreSQL 对账 JSON 查询。"""

    pairs: list[str] = []
    for column in table.columns:
        pairs.append(f"'{column.name}', {column.postgres_expr}")
    qualified = f"{safe_identifier(schema)}.{safe_identifier(table.name)}"
    return f"SELECT jsonb_build_object({', '.join(pairs)})::text FROM {qualified} ORDER BY {safe_identifier(table.column_names[0])}"


def normalize_value(value: Any) -> str:
    """把 Python 值规范为 checksum 文本。

    这里不试图理解业务 JSON 的内部结构，只保证同一个导出字段在迁移前后用同一套空值和字符串规则参与摘要。
    """

    if value is None:
        return "<NULL>"
    return str(value)


def update_digest(digest: hashlib._Hash, table: TableSpec, row: dict[str, Any]) -> None:
    """按表名、列名和值更新 SHA-256。

    把表名和列名纳入摘要，可以防止列顺序、列缺失或跨表误用导出文件时出现“行数相同但语义不同”的假通过。
    """

    digest.update(table.name.encode("utf-8"))
    digest.update(b"\n")
    for column in table.column_names:
        digest.update(column.encode("utf-8"))
        digest.update(b"=")
        digest.update(normalize_value(row.get(column)).encode("utf-8"))
        digest.update(b"\n")


def checksum_jsonl(table: TableSpec, rows: Iterable[dict[str, Any]]) -> tuple[int, str]:
    """计算行数与稳定 checksum。"""

    digest = hashlib.sha256()
    count = 0
    for row in rows:
        update_digest(digest, table, row)
        count += 1
    return count, digest.hexdigest()


def read_jsonl(path: pathlib.Path) -> Iterator[dict[str, Any]]:
    """按行读取 JSONL 文件。"""

    with path.open("r", encoding="utf-8") as handle:
        for line in handle:
            if line.strip():
                yield json.loads(line)


def target_schema_for_deferred(table_name: str) -> tuple[str, str]:
    """判断非本批表的延期归属。

    该函数是迁移治理的护栏：发现表不代表马上迁移，先确认它属于哪个服务边界。
    """

    if table_name == "agent_async_task_command_inbox":
        return "task_management", "agent_async_task_command_inbox 是 task-management 的 Agent command inbox 投影，已随任务中心批次迁移。"
    if table_name.startswith("agent_memory_"):
        return "ai_memory", "agent_memory_* 属于长期记忆、用户画像、pgvector 和 LangGraph durable state 后续批次。"
    if table_name.startswith("task_data_sync_") or table_name.startswith("task_"):
        return "task_management", "task/task_data_sync_* 属于 task-management，不随 agent-runtime 迁移。"
    if table_name.startswith("data_sync_"):
        return "data_sync", "data_sync_* 属于 data-sync 微服务自有控制面。"
    if table_name.startswith("sync_") or table_name.startswith("datasource_"):
        return "datasource_management", "sync_* / datasource_* 属于 datasource-management。"
    if table_name.startswith("quality_"):
        return "data_quality", "quality_* 属于 data-quality。"
    if table_name.startswith("permission_") or table_name.startswith("role_") or table_name.startswith("menu_"):
        return "permission_admin", "权限、角色、菜单相关表属于 permission-admin。"
    if table_name.startswith("agent_"):
        return "agent_runtime", "额外 agent_* 表看起来属于 agent-runtime，但未纳入当前 V1，需要人工复核。"
    return "unknown", "未知表名前缀，需要人工确认归属。"


def detect_deferred_tables(args: argparse.Namespace) -> list[dict[str, Any]]:
    """扫描 MySQL 中非本批但常见混放的表。

    登记结果会写入 manifest，便于生产迁移审计说明“这些表不是遗漏，而是归属其他批次”。
    """

    sql = (
        "SELECT table_name FROM information_schema.tables "
        "WHERE table_schema = DATABASE() "
        "AND (table_name REGEXP '^agent_' "
        "OR table_name REGEXP '^agent_memory_' "
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
        status = "REVIEW_REQUIRED" if table_name.startswith("agent_") and target_schema == "agent_runtime" else "DEFERRED"
        deferred.append(
            {
                "table": table_name,
                "rows": counts.get(table_name, 0),
                "status": status,
                "targetSchema": target_schema,
                "reason": reason,
            }
        )
    return deferred


def export_table(args: argparse.Namespace, table: TableSpec, export_dir: pathlib.Path) -> dict[str, Any]:
    """导出单表 JSONL 并计算源端 checksum。

    该函数故意不打印任何行内容。工具参数、payload、治理提示、issue codes 和 artifact 引用都可能包含客户上下文，
    即便是“摘要”也不应该进入普通终端日志。
    """

    table_path = export_dir / f"{table.name}.jsonl"
    digest = hashlib.sha256()
    count = 0
    with table_path.open("w", encoding="utf-8", newline="\n") as handle:
        if mysql_table_exists(args, table.name):
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

    manifest 是迁移验收附件，不是业务样本仓库。它只保存表级统计、checksum、敏感字段名和非本批表归属，
    不保存 SQL、连接串、密码、token、prompt、模型输出、工具参数正文、payload 正文或 artifact 正文。
    """

    manifest = {
        "module": "agent-runtime",
        "source": "mysql",
        "target": "postgresql",
        "generatedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
        "mysqlContainer": args.mysql_container,
        "postgresContainer": args.postgres_container,
        "postgresSchema": args.postgres_schema,
        "securityNotice": (
            "JSONL 导出文件包含真实 agent-runtime 控制面事实，可能含工具参数引用、payload 引用、治理提示、"
            "低敏错误摘要、artifact 引用和 worker 租约信息。manifest 不保存样本值，但整个导出目录仍必须按敏感数据介质保管。"
        ),
        "ownershipNotice": (
            "本脚本只迁移 11 张 agent_runtime 控制面表。agent_memory_* 归 ai_memory；task/task_data_sync_* 归 task_management；"
            "data_sync_* 归 data_sync；sync_* / datasource_* 归 datasource_management；quality_* 归 data_quality。"
        ),
        "tables": table_results,
        "deferredTables": deferred_tables,
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

    存量导入默认只允许空目标表。agent-runtime 的 outbox、audit、receipt、lease 都有幂等键和状态历史；
    如果目标表已有 seed/test data、失败残留或人工写入，继续 COPY 会造成主键冲突、唯一键冲突或混合事实，后续审计很难解释。
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

    COPY 比逐行 INSERT 更适合迁移 agent-runtime 的事件 outbox、审计事实和投影索引。
    这里仍然按行读取 JSONL，不把整个文件读入内存，便于后续处理较大的预生产或生产数据集。
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

    COPY 列顺序与 TableSpec 完全一致。对于自增 id 表，脚本显式保留 MySQL 原始 id；
    对于业务键主键表，脚本只导入业务键和事实字段，不尝试伪造 id。
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

    显式导入 MySQL 原始 id 后，PostgreSQL identity sequence 不会自动前进。
    如果不按 MAX(id) 校正，服务恢复写入时可能生成重复主键。
    """

    schema = safe_identifier(args.postgres_schema)
    statements = []
    for table in TABLES:
        if not table.has_identity_id:
            continue
        statements.append(
            f"SELECT setval(pg_get_serial_sequence('{schema}.{table.name}', 'id'), "
            f"COALESCE((SELECT MAX(id) FROM {schema}.{table.name}), 1), "
            f"COALESCE((SELECT MAX(id) FROM {schema}.{table.name}), 0) > 0);"
        )
    if statements:
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

    plan 不创建导出目录、不写 PostgreSQL、不导出业务数据，只展示：
    - 11 张 agent-runtime 控制面表的源端/目标端行数；
    - MySQL 中发现但不属于本批的延期或复核表。
    """

    print("== agent-runtime MySQL -> PostgreSQL 迁移计划 ==")
    source_counts = mysql_table_counts(args, (table.name for table in TABLES))
    target_counts = postgres_table_counts(args, (table.name for table in TABLES))
    for table in TABLES:
        source_count = source_counts.get(table.name, 0)
        target_count = target_counts.get(table.name, 0)
        sensitive_hint = " sensitive" if table.sensitive_columns else ""
        key_hint = " business-key" if not table.has_identity_id else ""
        print(f"[PLAN] {table.name}: mysqlRows={source_count}, postgresRows={target_count}{sensitive_hint}{key_hint}")
    deferred = detect_deferred_tables(args)
    if deferred:
        print("== 延期或人工复核表 ==")
        for item in deferred:
            print(f"[{item['status']}] {item['table']}: mysqlRows={item['rows']}, targetSchema={item['targetSchema']}")
    else:
        print("[DEFERRED] 当前 MySQL schema 未发现非本批混放表。")
    print("提示：写入 PostgreSQL 需要使用 --mode import/all --apply，且默认要求目标表为空。")


def run_export(args: argparse.Namespace) -> pathlib.Path:
    """导出 agent-runtime 控制面表并写 manifest。"""

    export_dir = pathlib.Path(args.export_dir) if args.export_dir else DEFAULT_OUTPUT_ROOT / dt.datetime.now().strftime("%Y%m%d%H%M%S")
    export_dir.mkdir(parents=True, exist_ok=True)
    results = [export_table(args, table, export_dir) for table in TABLES]
    deferred = detect_deferred_tables(args)
    write_manifest(args, export_dir, results, deferred)
    print(f"[EXPORT] manifest={export_dir / 'manifest.json'}")
    if deferred:
        print(f"[DEFERRED] 已记录 {len(deferred)} 张非本批表；它们不会被导入 agent_runtime schema。")
    print("[SECURITY] 导出目录包含真实迁移数据，请按敏感数据介质保管并在验收后清理。")
    return export_dir


def run_import(args: argparse.Namespace, export_dir: pathlib.Path) -> None:
    """导入 agent-runtime 控制面表。

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
    deferredTables 只打印提示，不参与本批对账。
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
        print(f"[{item['status']}] {item['table']}: rows={item['rows']}, targetSchema={item['targetSchema']}")
    if mismatches:
        raise RuntimeError("迁移对账失败：" + ", ".join(mismatches))


def parse_args() -> argparse.Namespace:
    """解析命令行参数。

    默认值面向本地 Docker Compose；预生产/生产可通过环境变量或命令行覆盖容器名、库名、用户和 schema。
    """

    parser = argparse.ArgumentParser(description="agent-runtime MySQL 到 PostgreSQL 存量迁移与对账工具")
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
    parser.add_argument("--postgres-schema", default=os.getenv("DATASMART_POSTGRES_SCHEMA", "agent_runtime"))
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
