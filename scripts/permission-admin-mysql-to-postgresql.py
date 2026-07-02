#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""permission-admin MySQL -> PostgreSQL 存量数据迁移与对账工具。

本脚本服务于 permission-admin 已完成 PostgreSQL 代码路径切换之后的“存量客户数据搬迁”阶段。
它只处理权限中心自己拥有的 8 张表：

1. permission_role
2. permission_menu
3. permission_role_menu_binding
4. permission_route_policy
5. permission_data_scope_policy
6. permission_project_membership
7. permission_audit_record
8. permission_event_outbox

特别说明：
- 旧的 docker/mysql/init/permission-admin.sql 后半段混入了一批 agent_memory_* 表；
- 这些表属于 AI Memory / Agent Runtime 的长期记忆、候选写入、物化 receipt、worker lease、审计 outbox 能力；
- 它们不属于权限中心，不应被导入 PostgreSQL 的 permission_admin schema；
- 本脚本会在 plan/export/verify 中扫描并记录这些表，状态标为 DEFERRED，目标归属标为 ai_memory，
  但绝不会导出、导入或对账这些 Agent Memory 表。

设计原则：
- 默认 mode=plan，只读，不写 PostgreSQL；
- import/all 必须显式传入 --apply，避免误操作；
- 目标 PostgreSQL 表默认必须为空，除非显式传入 --allow-target-not-empty；
- 导出中间格式为 JSONL，便于人工审计和断点排查；
- 导入使用 PostgreSQL COPY，避免逐行 INSERT 造成迁移性能瓶颈；
- 保留 MySQL 原始主键 ID，并在导入后校正 PostgreSQL identity sequence；
- 对账使用稳定 SHA-256 行摘要，只输出行数和摘要前缀，不输出 payload、SQL 结果正文、token、prompt 或样本数据。
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
DEFAULT_OUTPUT_ROOT = REPO_ROOT / "artifacts" / "postgresql-migration" / "permission-admin"

# COPY FROM STDIN 需要一个“不会与真实业务文本混淆”的 NULL 标识。
# 这里不沿用常见的 \N，是因为权限审计 detail_json、outbox payload_json 等文本理论上可能出现 \N。
# 若业务字段真的等于该哨兵值，脚本会主动失败，避免把真实字符串误导入为 NULL。
NULL_SENTINEL = "__DATASMART_POSTGRES_COPY_NULL_8F7D0C9B__"


@dataclass(frozen=True)
class ColumnSpec:
    """跨库对账列定义。

    name:
        目标 JSONL 字段名，同时也是 PostgreSQL COPY 的列名。
    mysql_expr:
        MySQL 导出时的表达式。脚本尽量把数值、布尔、时间和 JSON 都规范化为稳定文本，
        这样源端 checksum 与目标端 checksum 才不会因为数据库默认格式差异而误报。
    postgres_expr:
        PostgreSQL 对账时的表达式。它必须与 mysql_expr 形成同一套文本规范。
    """

    name: str
    mysql_expr: str
    postgres_expr: str


@dataclass(frozen=True)
class TableSpec:
    """迁移表定义。

    permission-admin 迁移表全部以 id 排序并保留 id 导入。
    对权限数据来说，保留原 id 有两个价值：
    1. 审计、outbox、外部工单和排障记录中可能引用旧 id；
    2. 预生产对账时可以直接按主键抽样比对，降低迁移验收成本。
    """

    name: str
    columns: tuple[ColumnSpec, ...]

    @property
    def column_names(self) -> list[str]:
        return [column.name for column in self.columns]


def text_col(name: str) -> ColumnSpec:
    """普通文本列。

    MySQL JSON_OBJECT 会把 VARCHAR/TEXT 作为 JSON 字符串输出；PostgreSQL row_to_json
    也会把 TEXT/VARCHAR 作为 JSON 字符串输出，因此两端保持字段原值即可。
    """

    return ColumnSpec(name, name, name)


def int_col(name: str) -> ColumnSpec:
    """整数列。

    不同数据库驱动可能把 BIGINT/INTEGER 反序列化为 int、Decimal 或字符串。
    迁移对账时统一转为 text，避免同一个值在 JSON 中一边是数字、一边是字符串导致 checksum 假失败。
    COPY 导入 PostgreSQL 时仍会把这些文本值转换回目标整数列。
    """

    return ColumnSpec(name, f"CAST({name} AS CHAR)", f"{name}::text")


def bool_col(name: str) -> ColumnSpec:
    """布尔列。

    旧 MySQL 使用 TINYINT(1)，PostgreSQL 使用 BOOLEAN。
    这里显式规范为 true/false 文本，既能被 PostgreSQL COPY 识别，也能让 checksum 具有跨库稳定性。
    """

    return ColumnSpec(
        name,
        f"CASE WHEN {name} IS NULL THEN NULL WHEN {name} <> 0 THEN 'true' ELSE 'false' END",
        f"CASE WHEN {name} IS NULL THEN NULL WHEN {name} THEN 'true' ELSE 'false' END",
    )


def time_col(name: str) -> ColumnSpec:
    """时间列。

    MySQL DATETIME 与 PostgreSQL TIMESTAMP WITHOUT TIME ZONE 都不携带时区。
    迁移期间不做隐式时区换算，只把墙上时间格式化为微秒级文本。
    """

    return ColumnSpec(
        name,
        f"DATE_FORMAT({name}, '%Y-%m-%d %H:%i:%s.%f')",
        f"to_char({name}, 'YYYY-MM-DD HH24:MI:SS.US')",
    )


def json_text_col(name: str) -> ColumnSpec:
    """MySQL JSON -> PostgreSQL TEXT 列。

    permission_event_outbox.payload_json 在 MySQL 中是 JSON，在 PostgreSQL V1 中暂时是 TEXT，
    因为 Java 实体当前按 String 映射。迁移时要保存 JSON 的文本表示，而不是把它拆成结构化列。
    后续若升级到 JSONB，应同步改造 Java TypeHandler、DDL 和本脚本的对账表达式。
    """

    return ColumnSpec(name, f"CAST({name} AS CHAR)", name)


TABLES: tuple[TableSpec, ...] = (
    TableSpec(
        "permission_role",
        (
            int_col("id"),
            int_col("tenant_id"),
            text_col("role_code"),
            text_col("role_name"),
            text_col("description"),
            bool_col("system_role"),
            bool_col("enabled"),
            int_col("created_by"),
            int_col("updated_by"),
            time_col("create_time"),
            time_col("update_time"),
        ),
    ),
    TableSpec(
        "permission_menu",
        (
            int_col("id"),
            text_col("menu_code"),
            text_col("parent_code"),
            text_col("menu_name"),
            text_col("path"),
            text_col("icon"),
            int_col("sort_order"),
            bool_col("enabled"),
            text_col("description"),
            time_col("create_time"),
            time_col("update_time"),
        ),
    ),
    TableSpec(
        "permission_role_menu_binding",
        (
            int_col("id"),
            int_col("tenant_id"),
            text_col("role_code"),
            text_col("menu_code"),
            bool_col("enabled"),
            text_col("binding_source"),
            text_col("note"),
            time_col("create_time"),
            time_col("update_time"),
        ),
    ),
    TableSpec(
        "permission_route_policy",
        (
            int_col("id"),
            int_col("tenant_id"),
            text_col("policy_name"),
            text_col("role_code"),
            text_col("http_method"),
            text_col("path_pattern"),
            text_col("resource_type"),
            text_col("action"),
            text_col("effect"),
            int_col("priority"),
            bool_col("enabled"),
            text_col("description"),
            time_col("create_time"),
            time_col("update_time"),
        ),
    ),
    TableSpec(
        "permission_data_scope_policy",
        (
            int_col("id"),
            int_col("tenant_id"),
            text_col("role_code"),
            text_col("resource_type"),
            text_col("scope_level"),
            text_col("scope_expression"),
            bool_col("approval_required"),
            bool_col("enabled"),
            text_col("description"),
            time_col("create_time"),
            time_col("update_time"),
        ),
    ),
    TableSpec(
        "permission_project_membership",
        (
            int_col("id"),
            int_col("tenant_id"),
            int_col("actor_id"),
            int_col("project_id"),
            int_col("workspace_id"),
            text_col("project_role"),
            text_col("grant_source"),
            bool_col("enabled"),
            time_col("create_time"),
            time_col("update_time"),
        ),
    ),
    TableSpec(
        "permission_audit_record",
        (
            int_col("id"),
            text_col("trace_id"),
            int_col("tenant_id"),
            int_col("actor_id"),
            text_col("actor_role"),
            text_col("resource_type"),
            text_col("resource_id"),
            text_col("action"),
            text_col("result"),
            text_col("summary"),
            text_col("detail_json"),
            time_col("create_time"),
        ),
    ),
    TableSpec(
        "permission_event_outbox",
        (
            int_col("id"),
            text_col("event_id"),
            text_col("event_type"),
            text_col("topic"),
            text_col("event_key"),
            json_text_col("payload_json"),
            text_col("status"),
            int_col("attempt_count"),
            int_col("max_attempts"),
            time_col("next_retry_time"),
            text_col("last_error"),
            time_col("sent_time"),
            int_col("tenant_id"),
            text_col("resource_type"),
            text_col("resource_id"),
            text_col("trace_id"),
            time_col("create_time"),
            time_col("update_time"),
        ),
    ),
)


def redact_command(command: list[str]) -> str:
    """生成低敏错误命令摘要。

    Docker exec 参数里可能包含 MYSQL_PWD。即使只是本地开发脚本，也不能把密码写进日志或 CI 输出。
    因此错误消息只展示程序和少量非敏感参数，并把类似 MYSQL_PWD=xxx 的值替换为 MYSQL_PWD=***。
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

    该函数用于 COUNT、sequence 校正等低敏短 SQL。
    不返回 stderr 正文，是为了避免数据库错误中夹带 SQL 文本、连接串或样本值。
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

    权限审计和 outbox 在客户环境中可能比较大。
    使用流式读取可以避免 Python 一次性持有完整 SQL 输出，降低迁移脚本自身内存占用。
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

    数据库密码通过容器环境变量 MYSQL_PWD 传入，而不是拼接进 SQL。
    本脚本仍会避免把完整命令打印出来，因为环境变量参数本身也属于敏感信息。
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

    ON_ERROR_STOP=1 可以保证第一条 SQL 失败时 psql 立即退出，防止迁移脚本出现“部分成功却继续执行”的危险状态。
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

    agent_memory_* 表名来自 information_schema，理论上已经可信。
    但迁移工具属于运维入口，仍然做白名单校验，避免未来有人把异常表名拼进 COUNT SQL。
    """

    if not re.fullmatch(r"[A-Za-z0-9_]+", identifier):
        raise RuntimeError(f"MySQL 表名包含不安全字符，已拒绝处理：{identifier!r}")
    return f"`{identifier}`"


def mysql_json_select(table: TableSpec) -> str:
    """生成 MySQL JSONL 导出 SQL。

    JSON_OBJECT 的键顺序跟 columns 定义一致；checksum 时会再次 sort_keys，
    因此即使数据库 JSON 输出顺序有差异，也不会影响最终摘要。
    """

    pairs: list[str] = []
    for column in table.columns:
        pairs.append(f"'{column.name}'")
        pairs.append(column.mysql_expr)
    return f"SELECT JSON_OBJECT({', '.join(pairs)}) FROM {table.name} ORDER BY id"


def postgres_json_select(table: TableSpec, schema: str) -> str:
    """生成 PostgreSQL 对账 SQL。

    对账表达式必须与 MySQL 导出规范完全一致：
    - 整数转 text；
    - boolean 转 true/false；
    - timestamp 转微秒级文本；
    - outbox payload_json 按 TEXT 原样比较。
    """

    projection = ", ".join(f"{column.postgres_expr} AS {column.name}" for column in table.columns)
    return f"SELECT row_to_json(t) FROM (SELECT {projection} FROM {schema}.{table.name} ORDER BY id) t"


def canonical_row(table: TableSpec, row: dict[str, Any]) -> str:
    """把一行数据规范化成稳定 JSON 字符串。

    checksum 不直接使用数据库输出原文，而是按列清单重新组装：
    1. 防止某些数据库客户端改变 JSON 字段顺序；
    2. 防止多余空格、换行等表现形式影响摘要；
    3. 确保缺列时能尽早暴露为 KeyError 或 None 差异。
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


def detect_deferred_agent_memory_tables(args: argparse.Namespace) -> list[dict[str, Any]]:
    """扫描 MySQL 中混放的 Agent Memory 表。

    这些表在旧初始化脚本里临时混在 permission-admin 后半段，但它们的业务归属是 AI Memory。
    迁移工具把它们显式列出来，是为了让后续迁移计划可见，而不是悄悄忽略。
    """

    sql = (
        "SELECT table_name FROM information_schema.tables "
        "WHERE table_schema = DATABASE() AND table_name REGEXP '^agent_memory_' "
        "ORDER BY table_name"
    )
    deferred: list[dict[str, Any]] = []
    for line in docker_mysql_lines(args, sql):
        table_name = line.strip()
        if not table_name:
            continue
        row_count = int(docker_mysql(args, f"SELECT COUNT(1) FROM {safe_mysql_identifier(table_name)}").strip() or "0")
        deferred.append(
            {
                "table": table_name,
                "rows": row_count,
                "status": "DEFERRED",
                "targetSchema": "ai_memory",
                "reason": "Agent Memory 表属于 AI Memory / Agent Runtime，后续单独迁入 PostgreSQL ai_memory schema，本脚本不会导出或导入。",
            }
        )
    return deferred


def export_table(args: argparse.Namespace, table: TableSpec, export_dir: pathlib.Path) -> dict[str, Any]:
    """导出单表 JSONL 并计算源端摘要。

    只打印行数和 checksum 前缀，不打印任何行内容。
    对权限系统来说，detail_json、payload_json、summary 中都可能含有低敏但仍不该进入日志的业务上下文。
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
    return {"table": table.name, "rows": count, "checksum": checksum, "file": table_path.name}


def write_manifest(
    args: argparse.Namespace,
    export_dir: pathlib.Path,
    table_results: list[dict[str, Any]],
    deferred_tables: list[dict[str, Any]],
) -> None:
    """写入导出清单。

    manifest 是迁移验收附件，不包含密码、连接串或业务样本。
    deferredTables 专门用于记录 MySQL 中发现但不属于 permission-admin 的 Agent Memory 表。
    """

    manifest = {
        "module": "permission-admin",
        "source": "mysql",
        "target": "postgresql",
        "generatedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
        "mysqlContainer": args.mysql_container,
        "postgresContainer": args.postgres_container,
        "postgresSchema": args.postgres_schema,
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
    """保护目标库。

    存量导入默认只允许空目标表，原因是权限中心存在大量唯一约束：
    如果目标表已有 seed、测试数据或人工改动，直接 COPY 可能造成主键/唯一键冲突，
    更危险的是形成“部分来自 MySQL、部分来自 PostgreSQL 原有数据”的混合事实。
    """

    non_empty = [(table.name, count_target(args, table)) for table in TABLES if count_target(args, table) > 0]
    if non_empty and not args.allow_target_not_empty:
        details = ", ".join(f"{name}={count}" for name, count in non_empty)
        raise RuntimeError(f"PostgreSQL 目标表非空，默认拒绝导入：{details}")


def copy_value(table: TableSpec, column: str, value: Any) -> str:
    """把 JSONL 字段转换为 COPY 字段。

    None 使用 NULL_SENTINEL；其他值按字符串导入。
    若真实业务值刚好等于 NULL_SENTINEL，脚本停止，避免静默数据损坏。
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
    """使用 PostgreSQL COPY 导入 JSONL。

    COPY 的吞吐通常远高于逐行 INSERT，适合权限策略、审计记录和 outbox 的批量迁移。
    这里仍按行读取 JSONL，不把整个文件放进内存。
    """

    process = subprocess.Popen(
        psql_command(args, copy_sql, interactive=True),
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
        newline="",
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

    导入列顺序与 TableSpec 完全一致，显式包含 id。
    这意味着 PostgreSQL identity sequence 不会自动前进，必须在所有表导入后统一 reset。
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

    如果导入保留了旧 id，但 sequence 仍停留在 1，应用下一次插入就可能产生主键冲突。
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
    """计算目标端行数和 checksum。

    PostgreSQL 侧使用与 MySQL 导出等价的表达式生成 JSON，再由 Python 做最终规范化摘要。
    """

    rows = (json.loads(line) for line in docker_psql_lines(args, postgres_json_select(table, args.postgres_schema)) if line.strip())
    return checksum_jsonl(table, rows)


def run_plan(args: argparse.Namespace) -> None:
    """只读迁移计划检查。

    plan 不创建目录、不写 PostgreSQL、不导出业务数据，只展示：
    - 8 张权限表源端和目标端行数；
    - MySQL 中发现的 agent_memory_* 表及其延后迁移归属。
    """

    print("== permission-admin MySQL -> PostgreSQL 迁移计划 ==")
    for table in TABLES:
        source_count = count_source(args, table)
        target_count = count_target(args, table)
        print(f"[PLAN] {table.name}: mysqlRows={source_count}, postgresRows={target_count}")
    deferred = detect_deferred_agent_memory_tables(args)
    if deferred:
        print("== Agent Memory 混放表处理 ==")
        for item in deferred:
            print(f"[DEFERRED] {item['table']}: mysqlRows={item['rows']}, targetSchema={item['targetSchema']}")
    else:
        print("[DEFERRED] 未在当前 MySQL schema 中发现 agent_memory_* 表")
    print("提示：写入 PostgreSQL 需要使用 --mode import/all --apply，且默认要求目标权限表为空。")


def run_export(args: argparse.Namespace) -> pathlib.Path:
    """导出权限表并写 manifest。

    Agent Memory 表只进入 manifest.deferredTables，不进入 JSONL 文件集合。
    """

    export_dir = pathlib.Path(args.export_dir) if args.export_dir else DEFAULT_OUTPUT_ROOT / dt.datetime.now().strftime("%Y%m%d%H%M%S")
    export_dir.mkdir(parents=True, exist_ok=True)
    results = [export_table(args, table, export_dir) for table in TABLES]
    deferred = detect_deferred_agent_memory_tables(args)
    write_manifest(args, export_dir, results, deferred)
    print(f"[EXPORT] manifest={export_dir / 'manifest.json'}")
    if deferred:
        print(f"[DEFERRED] 已记录 {len(deferred)} 张 Agent Memory 表，后续迁入 ai_memory，不在本批导出。")
    return export_dir


def run_import(args: argparse.Namespace, export_dir: pathlib.Path) -> None:
    """导入权限表。

    import 是唯一会写 PostgreSQL 的阶段之一，因此必须显式 --apply。
    """

    if not args.apply:
        raise RuntimeError("导入会写入 PostgreSQL，必须显式传入 --apply")
    ensure_target_empty(args)
    for table in TABLES:
        import_table(args, table, export_dir)
    reset_identity_sequences(args)


def run_verify(args: argparse.Namespace, export_dir: pathlib.Path) -> None:
    """迁移后对账。

    verify 使用 manifest 中的源端 checksum 与当前 PostgreSQL 表重新计算出的 checksum 比对。
    deferredTables 只打印提示，不参与本批对账，因为它们不属于 permission-admin schema。
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
    parser = argparse.ArgumentParser(description="permission-admin MySQL 到 PostgreSQL 存量迁移与对账工具")
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
    parser.add_argument("--postgres-schema", default=os.getenv("DATASMART_POSTGRES_SCHEMA", "permission_admin"))
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
