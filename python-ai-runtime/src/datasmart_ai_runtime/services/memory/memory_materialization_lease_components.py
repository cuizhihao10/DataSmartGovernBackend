"""长期记忆落成 lease store 的运行时组装层。

该组件与 candidate、formal memory、receipt runtime builder 保持一致的配置模式：
- 本地默认使用 in-memory，零依赖启动；
- SQLite 用于轻量联调和持久化语义测试；
- MySQL 用于生产多实例协调；
- fail-open 适合开发，fail-fast 适合生产。

lease store 与 receipt store 必须分开配置。receipt 是执行证据，lease 是短时协调状态。两者虽然都围绕 candidateId，
但生命周期、写入频率和故障处理不同。拆分后，后续可以把 lease 切到 Redis 或专用队列，而不影响审计 receipt。
"""

from __future__ import annotations

import os
from dataclasses import dataclass
from typing import Any, Callable

from datasmart_ai_runtime.services.memory.memory_materialization_lease_sql_store import (
    SqlAgentMemoryMaterializationLeaseStore,
)
from datasmart_ai_runtime.services.memory.memory_materialization_lease_store import (
    AgentMemoryMaterializationLeaseStore,
    InMemoryAgentMemoryMaterializationLeaseStore,
)
from datasmart_ai_runtime.services.memory.memory_sql_connection import (
    build_mysql_connection,
    build_sqlite_connection,
    mask_mysql_dsn,
)


LeaseSqlConnectionFactory = Callable[["AgentMemoryMaterializationLeaseStoreSettings"], Any]


@dataclass(frozen=True)
class AgentMemoryMaterializationLeaseStoreSettings:
    """长期记忆落成 lease store 启动配置。

    字段说明：
    - `store_type`：支持 `in-memory`、`sqlite`、`mysql`；
    - `sqlite_path/mysql_dsn`：持久化目标；
    - `connect_timeout_seconds`：启动连接超时；
    - `fail_open`：连接失败时是否退回内存；
    - `default_lease_seconds`：Runner 默认租约窗口。

    租约窗口需要结合外部存储延迟压测调整：
    - 太短会导致慢 worker 尚未完成就被新实例接管；
    - 太长会导致 worker 崩溃后候选长时间无法恢复；
    - 当前默认 60 秒适合本地与早期联调，不代表最终生产 SLA。
    """

    store_type: str = "in-memory"
    sqlite_path: str = "datasmart-agent-memory-materialization-leases.sqlite3"
    mysql_dsn: str = ""
    connect_timeout_seconds: int = 3
    fail_open: bool = True
    default_lease_seconds: int = 60


@dataclass(frozen=True)
class AgentMemoryMaterializationLeaseStoreRuntime:
    """lease store 运行时结果。"""

    store: AgentMemoryMaterializationLeaseStore
    settings: AgentMemoryMaterializationLeaseStoreSettings
    implementation: str
    persistent: bool
    fallback_reason: str | None = None


def memory_materialization_lease_store_settings_from_env(
    environ: dict[str, str] | None = None,
) -> AgentMemoryMaterializationLeaseStoreSettings:
    """从环境变量读取 lease store 配置。

    支持：
    - `DATASMART_AI_MEMORY_LEASE_STORE`；
    - `DATASMART_AI_MEMORY_LEASE_SQLITE_PATH`；
    - `DATASMART_AI_MEMORY_LEASE_MYSQL_DSN`；
    - `DATASMART_AI_MEMORY_LEASE_SQL_CONNECT_TIMEOUT_SECONDS`；
    - `DATASMART_AI_MEMORY_LEASE_STORE_FAIL_OPEN`；
    - `DATASMART_AI_MEMORY_LEASE_SECONDS`。
    """

    source = environ if environ is not None else os.environ
    return AgentMemoryMaterializationLeaseStoreSettings(
        store_type=_normalize_store_type(source.get("DATASMART_AI_MEMORY_LEASE_STORE")),
        sqlite_path=source.get("DATASMART_AI_MEMORY_LEASE_SQLITE_PATH")
        or "datasmart-agent-memory-materialization-leases.sqlite3",
        mysql_dsn=source.get("DATASMART_AI_MEMORY_LEASE_MYSQL_DSN") or "",
        connect_timeout_seconds=_positive_int(
            source.get("DATASMART_AI_MEMORY_LEASE_SQL_CONNECT_TIMEOUT_SECONDS"),
            default=3,
        ),
        fail_open=_truthy(source.get("DATASMART_AI_MEMORY_LEASE_STORE_FAIL_OPEN"), default=True),
        default_lease_seconds=_positive_int(source.get("DATASMART_AI_MEMORY_LEASE_SECONDS"), default=60),
    )


def build_memory_materialization_lease_store_runtime(
    settings: AgentMemoryMaterializationLeaseStoreSettings | None = None,
    connection_factory: LeaseSqlConnectionFactory | None = None,
) -> AgentMemoryMaterializationLeaseStoreRuntime:
    """按配置创建 lease store。

    生产环境建议 `mysql + fail_open=false`。如果生产误用 fail-open，数据库故障时会退回进程内字典，
    多实例协调失效；诊断接口会明确标记 fallback，便于运维阻断上线。
    """

    resolved = settings or memory_materialization_lease_store_settings_from_env()
    try:
        if resolved.store_type == "in-memory":
            return _in_memory_runtime(resolved)
        if resolved.store_type == "sqlite":
            connection = connection_factory(resolved) if connection_factory else _build_sqlite_connection(resolved)
            return AgentMemoryMaterializationLeaseStoreRuntime(
                store=SqlAgentMemoryMaterializationLeaseStore(connection),
                settings=resolved,
                implementation="SqlAgentMemoryMaterializationLeaseStore(sqlite)",
                persistent=True,
            )
        connection = connection_factory(resolved) if connection_factory else _build_mysql_connection(resolved)
        return AgentMemoryMaterializationLeaseStoreRuntime(
            store=SqlAgentMemoryMaterializationLeaseStore(connection, placeholder="%s"),
            settings=resolved,
            implementation="SqlAgentMemoryMaterializationLeaseStore(mysql)",
            persistent=True,
        )
    except Exception as exc:
        if not resolved.fail_open:
            raise
        fallback = _in_memory_runtime(resolved)
        return AgentMemoryMaterializationLeaseStoreRuntime(
            store=fallback.store,
            settings=resolved,
            implementation=fallback.implementation,
            persistent=False,
            fallback_reason=f"{type(exc).__name__}: {exc}",
        )


def memory_materialization_lease_store_diagnostics(
    runtime: AgentMemoryMaterializationLeaseStoreRuntime,
) -> dict[str, Any]:
    """生成低敏 lease store 诊断。

    诊断不返回 lease token、真实租约记录或候选内容。
    """

    settings = runtime.settings
    return {
        "component": "python-ai-memory-materialization-lease-store",
        "configuredType": settings.store_type,
        "implementation": runtime.implementation,
        "persistent": runtime.persistent,
        "fallback": runtime.fallback_reason is not None,
        "fallbackReason": runtime.fallback_reason,
        "failOpen": settings.fail_open,
        "connectTimeoutSeconds": settings.connect_timeout_seconds,
        "defaultLeaseSeconds": settings.default_lease_seconds,
        "sqlite": {
            "path": settings.sqlite_path if settings.store_type == "sqlite" else None,
        },
        "mysql": {
            "dsn": mask_mysql_dsn(settings.mysql_dsn) if settings.store_type == "mysql" else None,
        },
        "notes": (
            "lease store 控制多 worker 并发领取，不保存 prompt、原始工具输出或 lease token 明文诊断。"
            "生产环境如果 configuredType=mysql 但 persistent=false，说明协调能力已退回进程内，不适合多实例自动 worker。"
        ),
    }


def _in_memory_runtime(
    settings: AgentMemoryMaterializationLeaseStoreSettings,
) -> AgentMemoryMaterializationLeaseStoreRuntime:
    """创建默认内存 lease store。"""

    return AgentMemoryMaterializationLeaseStoreRuntime(
        store=InMemoryAgentMemoryMaterializationLeaseStore(),
        settings=settings,
        implementation="InMemoryAgentMemoryMaterializationLeaseStore",
        persistent=False,
    )


def _build_sqlite_connection(settings: AgentMemoryMaterializationLeaseStoreSettings) -> Any:
    """创建 SQLite 连接。"""

    return build_sqlite_connection(settings.sqlite_path, settings.connect_timeout_seconds)


def _build_mysql_connection(settings: AgentMemoryMaterializationLeaseStoreSettings) -> Any:
    """创建 MySQL 连接。"""

    if not settings.mysql_dsn:
        raise ValueError("DATASMART_AI_MEMORY_LEASE_MYSQL_DSN 未配置，无法启用 mysql lease store。")
    return build_mysql_connection(settings.mysql_dsn, settings.connect_timeout_seconds)


def _normalize_store_type(value: str | None) -> str:
    """规范化 store 类型。"""

    normalized = (value or "in-memory").strip().lower().replace("_", "-")
    if normalized not in {"in-memory", "sqlite", "mysql"}:
        raise ValueError(f"DATASMART_AI_MEMORY_LEASE_STORE 只支持 in-memory、sqlite 或 mysql，当前值为：{value}")
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
