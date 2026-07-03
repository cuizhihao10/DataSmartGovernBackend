#!/usr/bin/env python3
"""AI Memory PostgreSQL store 真实读写 smoke。

本脚本服务于 DataSmart Govern 当前“从 MySQL 迁入 PostgreSQL/pgvector，并尽快闭环收敛”的阶段。
它不是迁移脚本，也不是压测脚本，而是一个低风险验收工具：验证 Python Runtime 已经能够通过现有
DB-API store 真实读写 PostgreSQL `ai_memory` schema 中的核心控制面表。

为什么要单独提供 smoke：
- `10-ai-memory-schema.sql` 只能证明表结构可以创建，不能证明 Python Runtime 的 store 与 PostgreSQL
  参数占位符、BOOLEAN、JSONB、TIMESTAMPTZ、唯一键和幂等逻辑完全兼容；
- `ai-memory-mysql-to-postgresql.py` 只能证明历史数据可以搬迁，不能证明运行时的候选审批、正式记忆、
  receipt、lease、audit outbox 五段链路都能正常写入和回读；
- 真实商业化部署不能只依赖“服务启动成功”，必须能证明关键业务状态可以落库、回查、幂等和清理。

安全原则：
- 默认不写数据库，必须显式传入 `--apply`；
- 只写低敏测试摘要，不保存 prompt、SQL、样本数据、密钥、完整工具输出或完整异常堆栈；
- 所有测试 ID 都带 `run_id` 前缀，脚本会在执行前后按前缀清理；
- 默认清理测试数据，只有传入 `--keep-records` 才保留，便于人工排查。
"""

from __future__ import annotations

import argparse
import os
import sys
from dataclasses import replace
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any
from uuid import uuid4


REPO_ROOT = Path(__file__).resolve().parents[1]
PYTHON_RUNTIME_SRC = REPO_ROOT / "python-ai-runtime" / "src"
if str(PYTHON_RUNTIME_SRC) not in sys.path:
    # 允许脚本直接在宿主机仓库根目录运行，不要求先 pip install -e python-ai-runtime。
    # 在 python-ai-runtime 容器内运行时，包已经安装到 venv 中；重复插入 src 路径也不会影响导入。
    sys.path.insert(0, str(PYTHON_RUNTIME_SRC))

from datasmart_ai_runtime.domain.memory import (  # noqa: E402
    AgentMemoryRecord,
    AgentMemoryScope,
    AgentMemoryType,
    AgentMemoryWriteCandidate,
    AgentMemoryWriteCandidateStatus,
)
from datasmart_ai_runtime.services.memory.memory_materialization_audit_outbox import (  # noqa: E402
    AgentMemoryMaterializationAuditOutboxRecord,
)
from datasmart_ai_runtime.services.memory.memory_materialization_audit_outbox_sql_store import (  # noqa: E402
    SqlAgentMemoryMaterializationAuditOutboxStore,
)
from datasmart_ai_runtime.services.memory.memory_materialization_lease_sql_store import (  # noqa: E402
    SqlAgentMemoryMaterializationLeaseStore,
)
from datasmart_ai_runtime.services.memory.memory_materialization_receipt_sql_store import (  # noqa: E402
    SqlAgentMemoryMaterializationReceiptStore,
)
from datasmart_ai_runtime.services.memory.memory_sql_connection import (  # noqa: E402
    build_postgresql_connection,
    mask_postgresql_dsn,
)
from datasmart_ai_runtime.services.memory.memory_sql_store import SqlAgentMemoryStore  # noqa: E402
from datasmart_ai_runtime.services.memory.memory_store import AgentMemoryStoreEntry  # noqa: E402
from datasmart_ai_runtime.services.memory.memory_write_sql_store import (  # noqa: E402
    SqlAgentMemoryWriteCandidateStore,
)


REQUIRED_TABLES = (
    "agent_memory_write_candidate",
    "agent_memory_write_candidate_audit",
    "agent_memory_store_entry",
    "agent_memory_materialization_receipt",
    "agent_memory_materialization_lease",
    "agent_memory_materialization_audit_outbox",
)


class SmokeFailure(RuntimeError):
    """smoke 验收失败。

    单独定义异常类型是为了让 main 函数可以区分“预期验收失败”和“脚本自身 bug/依赖缺失”。
    输出给用户时只打印低敏摘要，不把 DSN 密码、SQL 参数或完整堆栈直接暴露出来。
    """


def main(argv: list[str] | None = None) -> int:
    """命令行入口。

    返回码约定：
    - 0：schema 检查或真实 smoke 成功；
    - 1：业务验收失败，例如缺表、store 未能回读、幂等语义不符合预期；
    - 2：参数或运行环境错误，例如没有 DSN、缺少 psycopg、数据库不可达。
    """

    args = parse_args(argv)
    dsn = args.postgresql_dsn or os.environ.get("DATASMART_AI_MEMORY_POSTGRESQL_DSN") or ""
    if not dsn:
        print(
            "[ERROR] 未提供 PostgreSQL DSN。请设置 DATASMART_AI_MEMORY_POSTGRESQL_DSN，"
            "或传入 --postgresql-dsn。",
            file=sys.stderr,
        )
        return 2

    run_id = args.run_id or f"smoke-{datetime.now(timezone.utc).strftime('%Y%m%d%H%M%S')}-{uuid4().hex[:8]}"
    print(f"[INFO] AI Memory PostgreSQL store smoke runId={run_id}")
    print(f"[INFO] PostgreSQL DSN={mask_postgresql_dsn(dsn)}")
    print(f"[INFO] targetSchema={args.schema}")

    try:
        connection = build_postgresql_connection(dsn, args.connect_timeout_seconds)
    except Exception as exc:
        print(f"[ERROR] PostgreSQL 连接创建失败：{type(exc).__name__}: {exc}", file=sys.stderr)
        return 2

    cleanup_after_run = False
    try:
        set_search_path(connection, args.schema)
        verify_required_tables(connection)
        if not args.apply:
            print("[INFO] 已完成只读 schema 检查。未传入 --apply，因此不会写入 smoke 数据。")
            print("[INFO] 若要执行真实 store 读写验证，请追加 --apply。")
            return 0

        cleanup_smoke_records(connection, run_id)
        cleanup_after_run = not args.keep_records
        summary = run_store_smoke(connection, run_id)
        if args.keep_records:
            print("[WARN] 已按 --keep-records 保留 smoke 数据，仅建议用于人工排查。")
        print_success(summary)
        return 0
    except SmokeFailure as exc:
        print(f"[ERROR] {exc}", file=sys.stderr)
        return 1
    except Exception as exc:
        # store smoke 可能暴露驱动类型适配、数据库约束或 SQL 方言问题。
        # 这里只输出异常类型和截断后的消息，避免完整堆栈、SQL 参数或连接凭据进入普通终端日志。
        message = str(exc).replace("\r", " ").replace("\n", " ")[:500]
        print(f"[ERROR] store smoke 执行失败：{type(exc).__name__}: {message}", file=sys.stderr)
        return 1
    finally:
        if cleanup_after_run:
            try:
                # SQL 失败会让 PostgreSQL 当前事务进入 aborted 状态，必须先 rollback 才能执行清理。
                connection.rollback()
                cleanup_smoke_records(connection, run_id)
            except Exception as cleanup_exc:
                cleanup_message = str(cleanup_exc).replace("\r", " ").replace("\n", " ")[:300]
                print(
                    f"[WARN] smoke 数据自动清理失败，请按 runId={run_id} 人工复核："
                    f"{type(cleanup_exc).__name__}: {cleanup_message}",
                    file=sys.stderr,
                )
        connection.close()


def parse_args(argv: list[str] | None) -> argparse.Namespace:
    """解析命令行参数。

    这里故意不把 `--apply` 设为默认值，避免运维或开发者只是想看帮助/检查 schema 时误写入数据库。
    `--schema` 默认为 `ai_memory`，即使 DSN 中没有 `options=-csearch_path=ai_memory`，脚本也会显式设置
    search_path，降低测试数据误写到 `public` 或其他 Java 微服务 schema 的风险。
    """

    parser = argparse.ArgumentParser(
        description="验证 Python Runtime AI Memory SQL stores 能否真实读写 PostgreSQL ai_memory schema。",
    )
    parser.add_argument("--postgresql-dsn", default="", help="PostgreSQL DSN；为空时读取 DATASMART_AI_MEMORY_POSTGRESQL_DSN。")
    parser.add_argument("--schema", default="ai_memory", help="目标 schema，默认 ai_memory。")
    parser.add_argument("--run-id", default="", help="测试数据前缀；为空时自动生成。")
    parser.add_argument("--connect-timeout-seconds", type=int, default=5, help="数据库连接超时秒数。")
    parser.add_argument("--apply", action="store_true", help="显式执行真实写入、回读、幂等和清理验证。")
    parser.add_argument("--keep-records", action="store_true", help="保留 smoke 数据，默认执行后清理。")
    return parser.parse_args(argv)


def set_search_path(connection: Any, schema: str) -> None:
    """设置当前连接的 schema 搜索路径。

    store 代码为了同时兼容 SQLite/MySQL/PostgreSQL，没有在 SQL 中写死 `ai_memory.` 前缀。
    因此 smoke 必须在连接级别确保 search_path 正确，否则同名表一旦出现在 `public` 或其他 schema 中，
    验收结果就会变成“看起来成功，实际写错位置”的危险假阳性。
    """

    safe_schema = require_safe_identifier(schema, "schema")
    with connection.cursor() as cursor:
        cursor.execute(f"SET search_path TO {safe_schema}, public")
    connection.commit()


def verify_required_tables(connection: Any) -> None:
    """确认当前 search_path 下存在本次 smoke 需要的全部表。

    缺表通常意味着已有 PostgreSQL 数据卷没有重新执行 init SQL。此时不应该让 Runtime 自动建表，
    而应该由运维显式执行 `docker/postgresql/init/10-ai-memory-schema.sql` 或后续 Flyway/迁移工具。
    """

    missing: list[str] = []
    with connection.cursor() as cursor:
        for table in REQUIRED_TABLES:
            cursor.execute("SELECT to_regclass(%s) AS table_name", (table,))
            if not cursor.fetchone()["table_name"]:
                missing.append(table)
    if missing:
        joined = ", ".join(missing)
        raise SmokeFailure(
            "PostgreSQL ai_memory schema 缺少必要表："
            f"{joined}。请先执行 docker/postgresql/init/10-ai-memory-schema.sql。"
        )


def run_store_smoke(connection: Any, run_id: str) -> dict[str, Any]:
    """执行五段 store 真实读写验证。

    本函数刻意使用项目现有 SQL store，而不是直接写 SQL：
    - 如果 store 与 PostgreSQL 的参数占位符、BOOLEAN、JSONB、TIMESTAMPTZ 不兼容，smoke 会直接失败；
    - 如果我们只在脚本里手写 SQL，即使脚本成功，也不能证明 Python Runtime 真实业务代码可用；
    - 这也是“闭环收敛”的关键：验证运行时真实路径，而不是验证一条旁路演示 SQL。
    """

    candidate_store = SqlAgentMemoryWriteCandidateStore(connection, placeholder="%s")
    formal_store = SqlAgentMemoryStore(connection, placeholder="%s")
    receipt_store = SqlAgentMemoryMaterializationReceiptStore(connection, placeholder="%s")
    lease_store = SqlAgentMemoryMaterializationLeaseStore(connection, placeholder="%s")
    audit_outbox_store = SqlAgentMemoryMaterializationAuditOutboxStore(connection, placeholder="%s")

    now = datetime.now(timezone.utc)
    candidate = build_candidate(run_id, now)
    saved_candidate = candidate_store.save(candidate)
    approved_candidate = candidate_store.update(
        replace(
            saved_candidate,
            status=AgentMemoryWriteCandidateStatus.APPROVED,
            decided_by=f"{run_id}-operator",
            decided_at=now,
            decision_reason="smoke 验证：低敏候选允许写入正式长期记忆。",
        )
    )
    assert_condition(
        approved_candidate.candidate_version == 2,
        "候选审批 update 后版本号应从 1 推进到 2。",
    )
    audits = candidate_store.list_decision_audits(candidate.candidate_id)
    assert_condition(len(audits) == 1, "候选审批后应产生 1 条候选决策审计。")

    formal_entry = build_formal_memory(run_id, candidate.candidate_id, now)
    first_write = formal_store.save_if_absent(formal_entry)
    second_write = formal_store.save_if_absent(formal_entry)
    assert_condition(first_write.created, "正式记忆首次写入应返回 created=True。")
    assert_condition(not second_write.created, "正式记忆重复写入应按幂等返回 created=False。")
    retrieved = formal_store.get_by_candidate_id(candidate.candidate_id)
    assert_condition(retrieved is not None, "正式记忆应能按 source_candidate_id 回读。")
    search_results = formal_store.search(
        memory_type=AgentMemoryType.EPISODIC,
        scope=AgentMemoryScope.PROJECT,
        tenant_id=f"{run_id}-tenant",
        project_id=f"{run_id}-project",
        session_id=None,
        memory_namespace=f"memory:{run_id}",
        limit=10,
    )
    assert_condition(len(search_results) == 1, "正式记忆应能按租户/项目/namespace 检索到 1 条结果。")

    receipt = receipt_store.begin(
        candidate_id=candidate.candidate_id,
        tenant_id=f"{run_id}-tenant",
        project_id=f"{run_id}-project",
        workspace_key=f"workspace:{run_id}",
        memory_namespace=f"memory:{run_id}",
        worker_id=f"{run_id}-worker",
    )
    receipt = receipt_store.succeed(
        receipt_id=receipt.receipt_id,
        memory_id=formal_entry.memory.memory_id,
        namespace=formal_entry.namespace,
        outcome="created",
        message="smoke 验证：正式记忆已写入。",
    )
    assert_condition(receipt.status.value == "succeeded", "receipt 应推进到 succeeded。")

    lease = lease_store.try_acquire(
        candidate_id=candidate.candidate_id,
        tenant_id=f"{run_id}-tenant",
        project_id=f"{run_id}-project",
        workspace_key=f"workspace:{run_id}",
        memory_namespace=f"memory:{run_id}",
        worker_id=f"{run_id}-worker",
        lease_seconds=60,
        now=now,
    )
    assert_condition(lease is not None, "lease 首次领取应成功。")
    assert_condition(lease.lease_token, "lease 成功领取后必须具备内部 fencing token。")
    completed_lease = lease_store.succeed(
        candidate_id=candidate.candidate_id,
        lease_token=lease.lease_token,
        memory_id=formal_entry.memory.memory_id,
        outcome="created",
        message="smoke 验证：lease 已完成。",
        now=now + timedelta(seconds=1),
    )
    assert_condition(completed_lease.status.value == "succeeded", "lease 应推进到 succeeded。")

    audit_record = build_audit_outbox_record(run_id, candidate.candidate_id, formal_entry.memory.memory_id, now)
    audit_outbox_store.append(audit_record)
    recent = audit_outbox_store.list_recent(limit=20)
    matched = [record for record in recent if record.outbox_id == audit_record.outbox_id]
    assert_condition(len(matched) == 1, "audit outbox 应能回读刚写入的记录。")

    return {
        "candidateId": candidate.candidate_id,
        "memoryId": formal_entry.memory.memory_id,
        "receiptId": receipt.receipt_id,
        "leaseId": completed_lease.lease_id,
        "auditOutboxId": audit_record.outbox_id,
        "candidateAuditCount": len(audits),
        "formalMemorySearchCount": len(search_results),
    }


def build_candidate(run_id: str, now: datetime) -> AgentMemoryWriteCandidate:
    """构造低敏候选记忆。

    字段值都使用 runId 前缀，便于清理和排查；正文只描述 smoke 事实，不引用任何真实用户输入、
    数据样本、SQL、prompt 或工具输出。这样即使 `--keep-records` 保留测试数据，也不会制造新的合规风险。
    """

    return AgentMemoryWriteCandidate(
        candidate_id=f"{run_id}-candidate",
        memory_type=AgentMemoryType.EPISODIC,
        scope=AgentMemoryScope.PROJECT,
        status=AgentMemoryWriteCandidateStatus.DRAFT,
        tenant_id=f"{run_id}-tenant",
        project_id=f"{run_id}-project",
        actor_id=f"{run_id}-actor",
        title="AI Memory PostgreSQL store smoke 候选",
        content_summary="低敏 smoke 摘要：验证候选审批、正式记忆、receipt、lease 与 audit outbox 的 PostgreSQL 路径。",
        source="ai-memory-postgresql-store-smoke",
        workspace_key=f"workspace:{run_id}",
        memory_namespace=f"memory:{run_id}",
        source_tool_name="smoke.ai-memory.postgresql",
        source_status="succeeded",
        source_audit_id=f"{run_id}-source-audit",
        source_run_id=run_id,
        output_ref=f"smoke://{run_id}/redacted-output",
        approval_required=False,
        retention_days=1,
        sensitivity_level="internal",
        privacy_notes=("只包含低敏 smoke 摘要。", "禁止写入真实 prompt、SQL、样本数据或密钥。"),
        idempotency_key=f"{run_id}|candidate",
        created_at=now,
        attributes={
            "smokeRunId": run_id,
            "payloadPolicy": "SUMMARY_ONLY",
            "schema": "ai_memory",
        },
    )


def build_formal_memory(run_id: str, candidate_id: str, now: datetime) -> AgentMemoryStoreEntry:
    """构造正式长期记忆条目。

    该对象验证 `agent_memory_store_entry` 的幂等键、候选来源、namespace、过期时间、JSONB 标签与属性。
    它模拟的是“候选被批准后，worker 将低敏摘要落成可召回记忆”的最小商业闭环。
    """

    return AgentMemoryStoreEntry(
        memory=AgentMemoryRecord(
            memory_id=f"{run_id}-memory",
            memory_type=AgentMemoryType.EPISODIC,
            scope=AgentMemoryScope.PROJECT,
            tenant_id=f"{run_id}-tenant",
            project_id=f"{run_id}-project",
            title="PostgreSQL AI Memory store smoke 记忆",
            content="低敏正式记忆：PostgreSQL ai_memory store 已完成候选、正式记忆、receipt、lease 和 audit outbox 验证。",
            source="ai-memory-postgresql-store-smoke",
            importance_score=0.5,
            sensitivity_level="internal",
            tags=("smoke", "ai-memory", "postgresql"),
            created_at=now,
            attributes={"smokeRunId": run_id, "payloadPolicy": "SUMMARY_ONLY"},
        ),
        workspace_key=f"workspace:{run_id}",
        memory_namespace=f"memory:{run_id}",
        namespace=("tenant", f"{run_id}-tenant", "project", f"{run_id}-project", "memory", f"memory:{run_id}"),
        idempotency_key=f"{run_id}|formal-memory",
        source_candidate_id=candidate_id,
        expires_at=now + timedelta(days=1),
        materialized_at=now,
    )


def build_audit_outbox_record(
    run_id: str,
    candidate_id: str,
    memory_id: str,
    now: datetime,
) -> AgentMemoryMaterializationAuditOutboxRecord:
    """构造低敏 audit outbox 记录。

    outbox payload 只保存控制面事实：runId、candidateId、memoryId、动作和计数。
    这与生产约束保持一致：审计 outbox 可以证明发生了什么，但不复制用户原始数据和模型上下文。
    """

    return AgentMemoryMaterializationAuditOutboxRecord(
        outbox_id=f"{run_id}-audit-outbox",
        event_type="memory_materialization_smoke_completed",
        event_purpose="ai_memory_postgresql_store_smoke",
        aggregate_id=candidate_id,
        tenant_id=f"{run_id}-tenant",
        project_id=f"{run_id}-project",
        actor_id=f"{run_id}-actor",
        request_id=f"{run_id}-request",
        run_id=run_id,
        session_id=f"{run_id}-session",
        severity="info",
        action="store_smoke_completed",
        dry_run=False,
        payload={
            "smokeRunId": run_id,
            "candidateId": candidate_id,
            "memoryId": memory_id,
            "tablesTouched": list(REQUIRED_TABLES),
            "payloadPolicy": "SUMMARY_ONLY",
        },
        created_at=now,
        updated_at=now,
    )


def cleanup_smoke_records(connection: Any, run_id: str) -> None:
    """按 runId 前缀清理 smoke 数据。

    清理顺序从下游执行证据到上游候选，避免唯一键或未来外键约束影响删除。
    当前 V1 schema 尚未声明外键，是为了给迁移和补偿留空间；脚本仍按业务依赖顺序删除，方便后续加外键时平滑演进。
    """

    like_prefix = f"{run_id}%"
    contains_prefix = f"%{run_id}%"
    statements = (
        (
            "DELETE FROM agent_memory_materialization_audit_outbox "
            "WHERE outbox_id LIKE %s OR aggregate_id LIKE %s OR run_id = %s",
            (like_prefix, like_prefix, run_id),
        ),
        (
            "DELETE FROM agent_memory_materialization_receipt "
            "WHERE candidate_id LIKE %s OR receipt_id LIKE %s OR memory_id LIKE %s",
            (like_prefix, contains_prefix, like_prefix),
        ),
        (
            "DELETE FROM agent_memory_materialization_lease "
            "WHERE candidate_id LIKE %s OR lease_id LIKE %s OR memory_id LIKE %s",
            (like_prefix, contains_prefix, like_prefix),
        ),
        (
            "DELETE FROM agent_memory_store_entry "
            "WHERE memory_id LIKE %s OR source_candidate_id LIKE %s OR idempotency_key LIKE %s",
            (like_prefix, like_prefix, like_prefix),
        ),
        (
            "DELETE FROM agent_memory_write_candidate_audit WHERE candidate_id LIKE %s",
            (like_prefix,),
        ),
        (
            "DELETE FROM agent_memory_write_candidate "
            "WHERE candidate_id LIKE %s OR idempotency_key LIKE %s OR source_run_id = %s",
            (like_prefix, like_prefix, run_id),
        ),
    )
    with connection.cursor() as cursor:
        for sql, params in statements:
            cursor.execute(sql, params)
    connection.commit()


def assert_condition(condition: bool, message: str) -> None:
    """业务断言。

    使用自定义异常而不是裸 `assert`，是为了避免 Python 优化模式 `-O` 跳过断言，也便于输出稳定错误信息。
    """

    if not condition:
        raise SmokeFailure(message)


def require_safe_identifier(value: str, label: str) -> str:
    """校验 SQL identifier，避免 search_path 这类无法参数化的位置出现注入风险。"""

    normalized = value.strip()
    if not normalized or not normalized.replace("_", "").isalnum() or normalized[0].isdigit():
        raise SmokeFailure(f"{label} 只能包含字母、数字和下划线，且不能以数字开头：{value}")
    return normalized


def print_success(summary: dict[str, Any]) -> None:
    """输出低敏成功摘要。"""

    print("[OK] AI Memory PostgreSQL store smoke 通过。")
    for key, value in summary.items():
        print(f"[OK] {key}={value}")


if __name__ == "__main__":
    raise SystemExit(main())
