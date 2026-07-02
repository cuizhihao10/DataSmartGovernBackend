#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""data-quality MySQL -> PostgreSQL 存量数据迁移与对账脚本。

脚本只依赖 Python 标准库和 Docker 容器内 mysql/psql CLI。默认 mode=plan 只读；
写 PostgreSQL 必须传 --apply，并且默认拒绝导入到非空目标表。导出使用 JSONL，
导入使用 PostgreSQL COPY，最后按表级行数和稳定 SHA-256 摘要对账。生产迁移仍
需要停写、备份、只读观察、回滚方案和业务验收；详细流程见
docs/data-quality-postgresql-data-migration.md。
"""

from __future__ import annotations

import argparse
import csv
import datetime as dt
import hashlib
import json
import os
import pathlib
import subprocess
import sys
from dataclasses import dataclass
from typing import Any, Iterable

REPO_ROOT = pathlib.Path(__file__).resolve().parents[1]
DEFAULT_OUTPUT_ROOT = REPO_ROOT / "artifacts" / "postgresql-migration" / "data-quality"
NULL_SENTINEL = r"\N"

@dataclass(frozen=True)
class ColumnSpec:
    name: str
    mysql_expr: str
    postgres_expr: str

@dataclass(frozen=True)
class TableSpec:
    name: str
    columns: tuple[ColumnSpec, ...]

    @property
    def column_names(self) -> list[str]:
        return [column.name for column in self.columns]


def text_col(name: str) -> ColumnSpec:
    return ColumnSpec(name, name, name)


def int_col(name: str) -> ColumnSpec:
    return ColumnSpec(name, f"CAST({name} AS CHAR)", f"{name}::text")


def decimal_col(name: str) -> ColumnSpec:
    return ColumnSpec(name, f"CAST({name} AS CHAR)", f"{name}::text")


def time_col(name: str) -> ColumnSpec:
    return ColumnSpec(
        name,
        f"DATE_FORMAT({name}, '%Y-%m-%d %H:%i:%s.%f')",
        f"to_char({name}, 'YYYY-MM-DD HH24:MI:SS.US')",
    )


TABLES: tuple[TableSpec, ...] = (
    TableSpec(
        "quality_rule",
        (
            int_col("tenant_id"),
            int_col("project_id"),
            int_col("workspace_id"),
            int_col("id"),
            text_col("name"),
            text_col("rule_type"),
            text_col("target_object"),
            text_col("target_type"),
            int_col("data_source_id"),
            text_col("database_name"),
            text_col("schema_name"),
            text_col("table_name"),
            text_col("field_name"),
            text_col("scan_strategy"),
            text_col("target_validation_status"),
            text_col("target_validation_message"),
            time_col("target_validated_time"),
            text_col("comparison_operator"),
            decimal_col("expected_value"),
            text_col("severity"),
            text_col("description"),
            text_col("status"),
            int_col("rule_version"),
            time_col("last_check_time"),
            text_col("last_check_status"),
            int_col("last_report_id"),
            time_col("archived_time"),
            time_col("create_time"),
            time_col("update_time"),
        ),
    ),
    TableSpec(
        "quality_check_execution",
        (
            int_col("tenant_id"),
            int_col("project_id"),
            int_col("workspace_id"),
            int_col("id"),
            int_col("rule_id"),
            int_col("execution_no"),
            text_col("trigger_type"),
            text_col("execution_state"),
            text_col("operator"),
            int_col("task_id"),
            int_col("task_run_id"),
            text_col("executor_id"),
            time_col("started_at"),
            time_col("finished_at"),
            int_col("duration_ms"),
            int_col("report_id"),
            text_col("scan_plan_snapshot"),
            text_col("message"),
            time_col("create_time"),
            time_col("update_time"),
        ),
    ),
    TableSpec(
        "quality_check_report",
        (
            int_col("tenant_id"),
            int_col("project_id"),
            int_col("workspace_id"),
            int_col("id"),
            int_col("rule_id"),
            int_col("execution_id"),
            int_col("rule_version"),
            text_col("rule_name"),
            text_col("rule_type"),
            text_col("target_object"),
            text_col("severity"),
            decimal_col("measured_value"),
            decimal_col("expected_value"),
            text_col("comparison_operator"),
            text_col("check_status"),
            int_col("sample_size"),
            int_col("exception_count"),
            decimal_col("pass_rate"),
            text_col("trigger_type"),
            text_col("summary"),
            text_col("notes"),
            time_col("create_time"),
        ),
    ),
    TableSpec(
        "quality_anomaly_detail",
        (
            int_col("tenant_id"),
            int_col("project_id"),
            int_col("workspace_id"),
            int_col("id"),
            int_col("report_id"),
            int_col("rule_id"),
            text_col("target_object"),
            text_col("anomaly_type"),
            text_col("field_name"),
            text_col("record_identifier"),
            text_col("observed_value"),
            text_col("expected_value"),
            text_col("severity"),
            text_col("recommendation"),
            text_col("sample_payload"),
            time_col("create_time"),
        ),
    ),
)


def run_command(command: list[str], *, stdin: str | None = None) -> str:
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


def docker_mysql(args: argparse.Namespace, sql: str) -> str:
    # 密码通过容器环境变量传入，不出现在命令行参数中。
    return run_command(
        [
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
    )


def docker_psql(args: argparse.Namespace, sql: str, *, stdin: str | None = None) -> str:
    # ON_ERROR_STOP 保证第一处 SQL 错误立即失败，避免半成功误判。
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
        "-At",
        "-c",
        sql,
    ]
    return run_command(command, stdin=stdin)


def docker_psql_copy_from_jsonl(
    args: argparse.Namespace,
    copy_sql: str,
    table: TableSpec,
    jsonl_path: pathlib.Path,
) -> int:
    # 流式 COPY 避免异常明细量大时把整张表加载进内存。
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
        "-c",
        copy_sql,
    ]
    process = subprocess.Popen(
        command,
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
            writer.writerow([NULL_SENTINEL if row.get(column) is None else str(row.get(column)) for column in table.column_names])
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


def canonical_row(table: TableSpec, row: dict[str, Any]) -> str:
    normalized = {column: (None if row.get(column) is None else str(row.get(column))) for column in table.column_names}
    return json.dumps(normalized, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def checksum_rows(table: TableSpec, rows: Iterable[dict[str, Any]]) -> tuple[int, str]:
    digest = hashlib.sha256()
    count = 0
    for row in rows:
        digest.update(canonical_row(table, row).encode("utf-8"))
        digest.update(b"\n")
        count += 1
    return count, digest.hexdigest()


def read_jsonl(path: pathlib.Path) -> Iterable[dict[str, Any]]:
    with path.open("r", encoding="utf-8") as handle:
        for line in handle:
            if line.strip():
                yield json.loads(line)


def mysql_json_select(table: TableSpec) -> str:
    # 所有数值和时间都先转成文本，保证跨数据库 checksum 口径稳定。
    pairs: list[str] = []
    for column in table.columns:
        pairs.append(f"'{column.name}'")
        pairs.append(column.mysql_expr)
    return f"SELECT JSON_OBJECT({', '.join(pairs)}) FROM {table.name} ORDER BY id"


def postgres_json_select(table: TableSpec, schema: str) -> str:
    # 表达式与 MySQL 导出保持同一文本规范。
    projection = ", ".join(f"{column.postgres_expr} AS {column.name}" for column in table.columns)
    return f"SELECT row_to_json(t) FROM (SELECT {projection} FROM {schema}.{table.name} ORDER BY id) t"


def count_source(args: argparse.Namespace, table: TableSpec) -> int:
    output = docker_mysql(args, f"SELECT COUNT(1) FROM {table.name}")
    return int(output.strip() or "0")


def count_target(args: argparse.Namespace, table: TableSpec) -> int:
    output = docker_psql(args, f"SELECT COUNT(1) FROM {args.postgres_schema}.{table.name}")
    return int(output.strip() or "0")


def export_table(args: argparse.Namespace, table: TableSpec, export_dir: pathlib.Path) -> dict[str, Any]:
    # 导出时同步计算源端 checksum，manifest 后续作为对账基准。
    output = docker_mysql(args, mysql_json_select(table))
    table_path = export_dir / f"{table.name}.jsonl"
    rows: list[dict[str, Any]] = []
    with table_path.open("w", encoding="utf-8", newline="\n") as handle:
        for line in output.splitlines():
            if not line.strip():
                continue
            row = json.loads(line)
            rows.append(row)
            handle.write(json.dumps(row, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n")
    count, checksum = checksum_rows(table, rows)
    print(f"[EXPORT] {table.name}: rows={count}, checksum={checksum[:16]}...")
    return {"table": table.name, "rows": count, "checksum": checksum, "file": table_path.name}


def write_manifest(args: argparse.Namespace, export_dir: pathlib.Path, table_results: list[dict[str, Any]]) -> None:
    manifest = {
        "module": "data-quality",
        "source": "mysql",
        "target": "postgresql",
        "generatedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
        "mysqlContainer": args.mysql_container,
        "postgresContainer": args.postgres_container,
        "postgresSchema": args.postgres_schema,
        "tables": table_results,
    }
    (export_dir / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")


def load_manifest(export_dir: pathlib.Path) -> dict[str, Any]:
    manifest_path = export_dir / "manifest.json"
    if not manifest_path.exists():
        raise RuntimeError(f"缺少 manifest.json：{manifest_path}")
    return json.loads(manifest_path.read_text(encoding="utf-8"))


def ensure_target_empty(args: argparse.Namespace) -> None:
    non_empty = [(table.name, count_target(args, table)) for table in TABLES if count_target(args, table) > 0]
    if non_empty and not args.allow_target_not_empty:
        details = ", ".join(f"{name}={count}" for name, count in non_empty)
        raise RuntimeError(f"PostgreSQL 目标表非空，默认拒绝导入：{details}")


def import_table(args: argparse.Namespace, table: TableSpec, export_dir: pathlib.Path) -> None:
    # JSONL -> COPY，保留原始 id，后续再统一校正 identity sequence。
    jsonl_path = export_dir / f"{table.name}.jsonl"
    if not jsonl_path.exists():
        raise RuntimeError(f"缺少导出文件：{jsonl_path}")
    columns = ", ".join(table.column_names)
    copy_sql = (
        f"COPY {args.postgres_schema}.{table.name} ({columns}) "
        "FROM STDIN WITH (FORMAT csv, DELIMITER E'\\t', NULL '\\N')"
    )
    row_count = docker_psql_copy_from_jsonl(args, copy_sql, table, jsonl_path)
    print(f"[IMPORT] {table.name}: rows={row_count}")


def reset_identity_sequences(args: argparse.Namespace) -> None:
    # 导入显式 id 后校正 sequence，避免下一次应用插入产生主键冲突。
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
    output = docker_psql(args, postgres_json_select(table, args.postgres_schema))
    rows = [json.loads(line) for line in output.splitlines() if line.strip()]
    return checksum_rows(table, rows)


def run_plan(args: argparse.Namespace) -> None:
    print("== data-quality MySQL -> PostgreSQL 迁移计划 ==")
    for table in TABLES:
        source_count = count_source(args, table)
        target_count = count_target(args, table)
        print(f"[PLAN] {table.name}: mysqlRows={source_count}, postgresRows={target_count}")
    print("提示：写入 PostgreSQL 需要使用 --mode import/all --apply，且默认要求目标表为空。")


def run_export(args: argparse.Namespace) -> pathlib.Path:
    export_dir = pathlib.Path(args.export_dir) if args.export_dir else DEFAULT_OUTPUT_ROOT / dt.datetime.now().strftime("%Y%m%d%H%M%S")
    export_dir.mkdir(parents=True, exist_ok=True)
    results = [export_table(args, table, export_dir) for table in TABLES]
    write_manifest(args, export_dir, results)
    print(f"[EXPORT] manifest={export_dir / 'manifest.json'}")
    return export_dir


def run_import(args: argparse.Namespace, export_dir: pathlib.Path) -> None:
    if not args.apply:
        raise RuntimeError("导入会写入 PostgreSQL，必须显式传入 --apply")
    ensure_target_empty(args)
    for table in TABLES:
        import_table(args, table, export_dir)
    reset_identity_sequences(args)


def run_verify(args: argparse.Namespace, export_dir: pathlib.Path) -> None:
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
    if mismatches:
        raise RuntimeError("迁移对账失败：" + ", ".join(mismatches))


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="data-quality MySQL 到 PostgreSQL 存量迁移与对账工具")
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
    parser.add_argument("--postgres-schema", default=os.getenv("DATASMART_POSTGRES_SCHEMA", "data_quality"))
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
