"""长期记忆物化审计 outbox 运行时组装层。

本组件负责把环境变量转换为可用的审计 outbox recorder。它与 receipt/lease runtime builder 保持相同风格：

- 本地默认关闭审计 outbox，避免学习环境启动后产生额外持久化要求；
- 可选择 in-memory、sqlite、mysql、postgresql 四类 store；
- SQL 连接失败时可以 fail-open 回退内存，也可以 fail-fast 阻止服务继续启动；
- `required` 独立于 store fail-open，用于控制“运行中审计写入失败时，worker/API 是否应按失败处理”。
"""

from __future__ import annotations

import os
from dataclasses import dataclass
from typing import Any, Callable

from datasmart_ai_runtime.services.memory.memory_materialization_audit_outbox import (
    AgentMemoryMaterializationAuditOutboxRecorder,
    AgentMemoryMaterializationAuditOutboxStore,
    InMemoryAgentMemoryMaterializationAuditOutboxStore,
)
from datasmart_ai_runtime.services.memory.memory_materialization_audit_outbox_sql_store import (
    SqlAgentMemoryMaterializationAuditOutboxStore,
)
from datasmart_ai_runtime.services.memory.memory_sql_connection import (
    build_mysql_connection,
    build_postgresql_connection,
    build_sqlite_connection,
    mask_mysql_dsn,
    mask_postgresql_dsn,
)


AuditOutboxSqlConnectionFactory = Callable[["AgentMemoryMaterializationAuditOutboxSettings"], Any]


@dataclass(frozen=True)
class AgentMemoryMaterializationAuditOutboxSettings:
    """长期记忆物化审计 outbox 配置。

    字段说明：
    - `enabled`：是否写审计 outbox。默认关闭，避免本地学习环境必须配置数据库；
    - `required`：审计写入失败是否让调用方按失败处理。强合规环境可开启，本地默认关闭；
    - `store_type/sqlite_path/mysql_dsn/postgresql_dsn/connect_timeout_seconds/store_fail_open`：与 receipt/lease store 保持一致；
    - `store_fail_open` 控制“启动期连接失败是否回退内存”，`required` 控制“运行期 append 失败是否 fail-closed”，两者不要混淆。

    生产建议：
    - `enabled=true`；
    - `store_type=postgresql`；
    - `store_fail_open=false`；
    - 在真正实现同库事务 outbox 后，再把 `required=true` 作为强合规默认策略。
    """

    enabled: bool = False
    required: bool = False
    store_type: str = "in-memory"
    sqlite_path: str = "datasmart-agent-memory-materialization-audit-outbox.sqlite3"
    mysql_dsn: str = ""
    postgresql_dsn: str = ""
    connect_timeout_seconds: int = 3
    store_fail_open: bool = True


@dataclass(frozen=True)
class AgentMemoryMaterializationAuditOutboxRuntime:
    """审计 outbox 运行时结果。"""

    recorder: AgentMemoryMaterializationAuditOutboxRecorder
    store: AgentMemoryMaterializationAuditOutboxStore | None
    settings: AgentMemoryMaterializationAuditOutboxSettings
    implementation: str
    persistent: bool
    fallback_reason: str | None = None


def memory_materialization_audit_outbox_settings_from_env(
    environ: dict[str, str] | None = None,
) -> AgentMemoryMaterializationAuditOutboxSettings:
    """从环境变量读取审计 outbox 配置。

    支持的环境变量：
    - `DATASMART_AI_MEMORY_MATERIALIZATION_AUDIT_OUTBOX_ENABLED`；
    - `DATASMART_AI_MEMORY_MATERIALIZATION_AUDIT_OUTBOX_REQUIRED`；
    - `DATASMART_AI_MEMORY_MATERIALIZATION_AUDIT_OUTBOX_STORE`；
    - `DATASMART_AI_MEMORY_MATERIALIZATION_AUDIT_OUTBOX_SQLITE_PATH`；
    - `DATASMART_AI_MEMORY_MATERIALIZATION_AUDIT_OUTBOX_MYSQL_DSN`；
    - `DATASMART_AI_MEMORY_MATERIALIZATION_AUDIT_OUTBOX_POSTGRESQL_DSN`；
    - `DATASMART_AI_MEMORY_POSTGRESQL_DSN`：全局 AI Memory PostgreSQL DSN，组件专属 DSN 为空时回退使用；
    - `DATASMART_AI_MEMORY_MATERIALIZATION_AUDIT_OUTBOX_SQL_CONNECT_TIMEOUT_SECONDS`；
    - `DATASMART_AI_MEMORY_MATERIALIZATION_AUDIT_OUTBOX_STORE_FAIL_OPEN`。
    """

    source = environ if environ is not None else os.environ
    return AgentMemoryMaterializationAuditOutboxSettings(
        enabled=_truthy(
            source.get("DATASMART_AI_MEMORY_MATERIALIZATION_AUDIT_OUTBOX_ENABLED"),
            default=False,
        ),
        required=_truthy(
            source.get("DATASMART_AI_MEMORY_MATERIALIZATION_AUDIT_OUTBOX_REQUIRED"),
            default=False,
        ),
        store_type=_normalize_store_type(source.get("DATASMART_AI_MEMORY_MATERIALIZATION_AUDIT_OUTBOX_STORE")),
        sqlite_path=source.get("DATASMART_AI_MEMORY_MATERIALIZATION_AUDIT_OUTBOX_SQLITE_PATH")
        or "datasmart-agent-memory-materialization-audit-outbox.sqlite3",
        mysql_dsn=source.get("DATASMART_AI_MEMORY_MATERIALIZATION_AUDIT_OUTBOX_MYSQL_DSN") or "",
        postgresql_dsn=source.get("DATASMART_AI_MEMORY_MATERIALIZATION_AUDIT_OUTBOX_POSTGRESQL_DSN")
        or source.get("DATASMART_AI_MEMORY_POSTGRESQL_DSN")
        or "",
        connect_timeout_seconds=_positive_int(
            source.get("DATASMART_AI_MEMORY_MATERIALIZATION_AUDIT_OUTBOX_SQL_CONNECT_TIMEOUT_SECONDS"),
            default=3,
        ),
        store_fail_open=_truthy(
            source.get("DATASMART_AI_MEMORY_MATERIALIZATION_AUDIT_OUTBOX_STORE_FAIL_OPEN"),
            default=True,
        ),
    )


def build_memory_materialization_audit_outbox_runtime(
    settings: AgentMemoryMaterializationAuditOutboxSettings | None = None,
    connection_factory: AuditOutboxSqlConnectionFactory | None = None,
) -> AgentMemoryMaterializationAuditOutboxRuntime:
    """按配置创建审计 outbox runtime。

    即使 `enabled=false`，也会创建一个 recorder；它会在 record 时返回 `enabled=false` 摘要，便于 worker/API
    诊断保持稳定结构。
    """

    resolved = settings or memory_materialization_audit_outbox_settings_from_env()
    try:
        if resolved.store_type == "in-memory":
            return _runtime(
                resolved,
                InMemoryAgentMemoryMaterializationAuditOutboxStore(),
                implementation="InMemoryAgentMemoryMaterializationAuditOutboxStore",
                persistent=False,
            )
        if resolved.store_type == "sqlite":
            connection = connection_factory(resolved) if connection_factory else _build_sqlite_connection(resolved)
            return _runtime(
                resolved,
                SqlAgentMemoryMaterializationAuditOutboxStore(connection),
                implementation="SqlAgentMemoryMaterializationAuditOutboxStore(sqlite)",
                persistent=True,
            )
        if resolved.store_type == "postgresql":
            connection = connection_factory(resolved) if connection_factory else _build_postgresql_connection(resolved)
            return _runtime(
                resolved,
                SqlAgentMemoryMaterializationAuditOutboxStore(connection, placeholder="%s"),
                implementation="SqlAgentMemoryMaterializationAuditOutboxStore(postgresql)",
                persistent=True,
            )
        connection = connection_factory(resolved) if connection_factory else _build_mysql_connection(resolved)
        return _runtime(
            resolved,
            SqlAgentMemoryMaterializationAuditOutboxStore(connection, placeholder="%s"),
            implementation="SqlAgentMemoryMaterializationAuditOutboxStore(mysql)",
            persistent=True,
        )
    except Exception as exc:
        if not resolved.store_fail_open:
            raise
        fallback_store = InMemoryAgentMemoryMaterializationAuditOutboxStore()
        return _runtime(
            resolved,
            fallback_store,
            implementation="InMemoryAgentMemoryMaterializationAuditOutboxStore",
            persistent=False,
            fallback_reason=f"{type(exc).__name__}: {exc}",
        )


def memory_materialization_audit_outbox_diagnostics(
    runtime: AgentMemoryMaterializationAuditOutboxRuntime,
) -> dict[str, Any]:
    """生成审计 outbox 低敏诊断。"""

    settings = runtime.settings
    recent_count = 0
    if runtime.store is not None:
        try:
            recent_count = len(runtime.store.list_recent(limit=20))
        except Exception:
            recent_count = -1
    return {
        "component": "python-ai-memory-materialization-audit-outbox",
        "enabled": settings.enabled,
        "required": settings.required,
        "configuredType": settings.store_type,
        "implementation": runtime.implementation,
        "persistent": runtime.persistent,
        "fallback": runtime.fallback_reason is not None,
        "fallbackReason": runtime.fallback_reason,
        "storeFailOpen": settings.store_fail_open,
        "connectTimeoutSeconds": settings.connect_timeout_seconds,
        "recentSampleCount": recent_count,
        "sqlite": {
            "path": settings.sqlite_path if settings.store_type == "sqlite" else None,
        },
        "mysql": {
            "dsn": mask_mysql_dsn(settings.mysql_dsn) if settings.store_type == "mysql" else None,
        },
        "postgresql": {
            "dsn": mask_postgresql_dsn(settings.postgresql_dsn) if settings.store_type == "postgresql" else None,
        },
        "notes": (
            "审计 outbox 记录 worker 批次和管理员补偿的低敏控制面事实，不保存候选正文、SQL、样本数据或工具原始输出。"
            "当前 required=true 只能让调用方按失败处理，不能自动回滚已提交的 lease/formal memory 状态；"
            "强一致生产终态应继续演进为同库事务 outbox。"
        ),
    }


def _runtime(
    settings: AgentMemoryMaterializationAuditOutboxSettings,
    store: AgentMemoryMaterializationAuditOutboxStore | None,
    *,
    implementation: str,
    persistent: bool,
    fallback_reason: str | None = None,
) -> AgentMemoryMaterializationAuditOutboxRuntime:
    """构造 runtime 并把策略注入 recorder。"""

    return AgentMemoryMaterializationAuditOutboxRuntime(
        recorder=AgentMemoryMaterializationAuditOutboxRecorder(
            store=store,
            enabled=settings.enabled,
            required=settings.required,
        ),
        store=store,
        settings=settings,
        implementation=implementation,
        persistent=persistent,
        fallback_reason=fallback_reason,
    )


def _build_sqlite_connection(settings: AgentMemoryMaterializationAuditOutboxSettings) -> Any:
    """创建 SQLite 连接。"""

    return build_sqlite_connection(settings.sqlite_path, settings.connect_timeout_seconds)


def _build_mysql_connection(settings: AgentMemoryMaterializationAuditOutboxSettings) -> Any:
    """创建 MySQL 连接。"""

    if not settings.mysql_dsn:
        raise ValueError("DATASMART_AI_MEMORY_MATERIALIZATION_AUDIT_OUTBOX_MYSQL_DSN 未配置，无法启用 mysql audit outbox。")
    return build_mysql_connection(settings.mysql_dsn, settings.connect_timeout_seconds)


def _build_postgresql_connection(settings: AgentMemoryMaterializationAuditOutboxSettings) -> Any:
    """创建 PostgreSQL 连接。"""

    if not settings.postgresql_dsn:
        raise ValueError(
            "DATASMART_AI_MEMORY_MATERIALIZATION_AUDIT_OUTBOX_POSTGRESQL_DSN 未配置，无法启用 postgresql audit outbox。"
        )
    return build_postgresql_connection(settings.postgresql_dsn, settings.connect_timeout_seconds)


def _normalize_store_type(value: str | None) -> str:
    """规范化 store 类型。"""

    normalized = (value or "in-memory").strip().lower().replace("_", "-")
    if normalized == "postgres":
        normalized = "postgresql"
    if normalized not in {"in-memory", "sqlite", "mysql", "postgresql"}:
        raise ValueError(
            "DATASMART_AI_MEMORY_MATERIALIZATION_AUDIT_OUTBOX_STORE 只支持 in-memory、sqlite、mysql 或 postgresql，"
            f"当前值为：{value}"
        )
    return normalized


def _positive_int(value: str | None, default: int) -> int:
    """读取正整数配置，非法或空值回退默认值。"""

    if value is None or not value.strip():
        return default
    parsed = int(value)
    return parsed if parsed > 0 else default


def _truthy(value: str | None, default: bool = False) -> bool:
    """读取布尔风格配置。"""

    if value is None or not value.strip():
        return default
    return value.strip().lower() in {"true", "1", "yes", "on", "enabled"}
