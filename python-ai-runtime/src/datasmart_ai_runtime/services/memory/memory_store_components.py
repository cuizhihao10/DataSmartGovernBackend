"""正式长期记忆 Store 的运行时组装层。

`memory_sql_store.py` 已经提供了正式长期记忆 SQL store，但真实产品还需要回答一个更基础的问题：
Python Runtime 启动时到底应该用内存、SQLite 还是 MySQL？如果数据库不可用，是继续本地联调，还是让生产
部署直接失败？

本文件负责这段启动装配。它与 `memory_write_components.py` 的区别是：
- `memory_write_components.py` 装配“写入候选 store”，服务于候选生成、审批和拒绝；
- 本文件装配“正式记忆 store”，服务于 APPROVED 候选落成后的持久化事实和后续召回。

把二者拆开很重要：候选审批可以暂存在一个 store，正式记忆可以进入另一个更严格的 store；未来也可能出现
“候选在 Java 控制面，正式记忆在 Python/向量库控制面”的部署形态。拆开后不会互相牵连。
"""

from __future__ import annotations

import os
from dataclasses import dataclass
from typing import Any, Callable

from datasmart_ai_runtime.services.memory.memory_sql_connection import (
    build_mysql_connection,
    build_postgresql_connection,
    build_sqlite_connection,
    mask_mysql_dsn,
    mask_postgresql_dsn,
)
from datasmart_ai_runtime.services.memory.memory_sql_store import SqlAgentMemoryStore
from datasmart_ai_runtime.services.memory.memory_store import AgentMemoryStore, InMemoryAgentMemoryStore


FormalMemorySqlConnectionFactory = Callable[["AgentMemoryStoreSettings"], Any]


@dataclass(frozen=True)
class AgentMemoryStoreSettings:
    """正式长期记忆 store 启动配置。

    字段说明：
    - `store_type`：正式记忆存储类型，支持 `in-memory`、`sqlite`、`mysql`、`postgresql`；
    - `sqlite_path`：SQLite 文件路径，仅在 `store_type=sqlite` 时使用；
    - `mysql_dsn`：MySQL 连接字符串，仅在 `store_type=mysql` 时使用；
    - `postgresql_dsn`：PostgreSQL 连接字符串，仅在 `store_type=postgresql` 时使用，目标 schema 应为 `ai_memory`；
    - `connect_timeout_seconds`：数据库连接超时，防止启动被故障数据库长时间卡住；
    - `fail_open`：显式选择持久化但连接失败时是否回退内存。

    `fail_open` 的产品取舍：
    - 本地学习或临时联调可以开启，数据库没起来时 Agent 主链仍能运行；
    - 生产环境建议关闭，避免用户以为长期记忆已经落库，实际却退回内存并在重启后丢失。
    """

    store_type: str = "in-memory"
    sqlite_path: str = "datasmart-agent-memory-store.sqlite3"
    mysql_dsn: str = ""
    postgresql_dsn: str = ""
    connect_timeout_seconds: int = 3
    fail_open: bool = True


@dataclass(frozen=True)
class AgentMemoryStoreRuntime:
    """正式长期记忆 store 运行时结果。

    `store` 是业务组件真正使用的实例；其余字段用于诊断、测试和运维排障。
    把诊断信息与 store 一起返回，可以让 API/worker 明确知道自己是否已经使用持久化实现。
    """

    store: AgentMemoryStore
    settings: AgentMemoryStoreSettings
    implementation: str
    persistent: bool
    fallback_reason: str | None = None


def memory_store_settings_from_env(environ: dict[str, str] | None = None) -> AgentMemoryStoreSettings:
    """从环境变量读取正式长期记忆 store 配置。

    支持的环境变量：
    - `DATASMART_AI_FORMAL_MEMORY_STORE`：`in-memory` / `sqlite` / `mysql` / `postgresql`；
    - `DATASMART_AI_FORMAL_MEMORY_SQLITE_PATH`：SQLite 文件路径；
    - `DATASMART_AI_FORMAL_MEMORY_MYSQL_DSN`：MySQL DSN；
    - `DATASMART_AI_FORMAL_MEMORY_POSTGRESQL_DSN`：PostgreSQL DSN；
    - `DATASMART_AI_MEMORY_POSTGRESQL_DSN`：全局 AI Memory PostgreSQL DSN，组件专属 DSN 为空时回退使用；
    - `DATASMART_AI_FORMAL_MEMORY_SQL_CONNECT_TIMEOUT_SECONDS`：数据库连接超时秒数；
    - `DATASMART_AI_FORMAL_MEMORY_STORE_FAIL_OPEN`：持久化不可用时是否回退内存。

    这里用 `FORMAL_MEMORY` 前缀，而不是复用 `MEMORY_WRITE`，是为了明确区分“候选审批事实”和
    “正式可召回记忆事实”。两者生命周期不同，生产环境也可能使用不同表、不同库或不同权限账号。
    """

    source = environ if environ is not None else os.environ
    return AgentMemoryStoreSettings(
        store_type=_normalize_store_type(source.get("DATASMART_AI_FORMAL_MEMORY_STORE")),
        sqlite_path=source.get("DATASMART_AI_FORMAL_MEMORY_SQLITE_PATH")
        or "datasmart-agent-memory-store.sqlite3",
        mysql_dsn=source.get("DATASMART_AI_FORMAL_MEMORY_MYSQL_DSN") or "",
        postgresql_dsn=source.get("DATASMART_AI_FORMAL_MEMORY_POSTGRESQL_DSN")
        or source.get("DATASMART_AI_MEMORY_POSTGRESQL_DSN")
        or "",
        connect_timeout_seconds=_positive_int(
            source.get("DATASMART_AI_FORMAL_MEMORY_SQL_CONNECT_TIMEOUT_SECONDS"),
            default=3,
        ),
        fail_open=_truthy(source.get("DATASMART_AI_FORMAL_MEMORY_STORE_FAIL_OPEN"), default=True),
    )


def build_memory_store_runtime(
    settings: AgentMemoryStoreSettings | None = None,
    connection_factory: FormalMemorySqlConnectionFactory | None = None,
) -> AgentMemoryStoreRuntime:
    """按配置创建正式长期记忆 store。

    组装规则：
    1. 默认使用内存 store，保证本地零依赖启动；
    2. SQLite 使用标准库，适合轻量集成测试或单机演示；
    3. MySQL 作为迁移期兼容路径保留；
    4. PostgreSQL 使用 psycopg3 动态导入，目标指向 `ai_memory` schema；
    5. 持久化连接失败时，如果 `fail_open=true`，回退内存并记录原因；
    6. 如果 `fail_open=false`，直接抛错，让生产配置错误在启动阶段暴露。
    """

    resolved = settings or memory_store_settings_from_env()
    try:
        if resolved.store_type == "in-memory":
            return _in_memory_runtime(resolved)
        if resolved.store_type == "sqlite":
            connection = connection_factory(resolved) if connection_factory else _build_sqlite_connection(resolved)
            return AgentMemoryStoreRuntime(
                store=SqlAgentMemoryStore(connection),
                settings=resolved,
                implementation="SqlAgentMemoryStore(sqlite)",
                persistent=True,
            )
        if resolved.store_type == "postgresql":
            connection = connection_factory(resolved) if connection_factory else _build_postgresql_connection(resolved)
            return AgentMemoryStoreRuntime(
                store=SqlAgentMemoryStore(connection, placeholder="%s"),
                settings=resolved,
                implementation="SqlAgentMemoryStore(postgresql)",
                persistent=True,
            )
        connection = connection_factory(resolved) if connection_factory else _build_mysql_connection(resolved)
        return AgentMemoryStoreRuntime(
            store=SqlAgentMemoryStore(connection, placeholder="%s"),
            settings=resolved,
            implementation="SqlAgentMemoryStore(mysql)",
            persistent=True,
        )
    except Exception as exc:
        if not resolved.fail_open:
            raise
        fallback = _in_memory_runtime(resolved)
        return AgentMemoryStoreRuntime(
            store=fallback.store,
            settings=resolved,
            implementation=fallback.implementation,
            persistent=False,
            fallback_reason=f"{exc.__class__.__name__}: {exc}",
        )


def memory_store_diagnostics(runtime: AgentMemoryStoreRuntime) -> dict[str, Any]:
    """生成正式长期记忆 store 诊断信息。

    该诊断不返回任何记忆正文、标题、标签或 namespace 明细，只返回启动选择和脱敏后的连接目标。
    真实生产中，诊断接口必须由 gateway/permission-admin 保护，因为它仍然会暴露数据库 host 和表能力状态。
    """

    settings = runtime.settings
    return {
        "component": "python-ai-formal-memory-store",
        "configuredType": settings.store_type,
        "implementation": runtime.implementation,
        "persistent": runtime.persistent,
        "fallback": runtime.fallback_reason is not None,
        "fallbackReason": runtime.fallback_reason,
        "failOpen": settings.fail_open,
        "connectTimeoutSeconds": settings.connect_timeout_seconds,
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
            "诊断只说明正式长期记忆 store 的启动选择，不读取记忆内容，也不执行 schema 探测。"
            "生产环境 configuredType=mysql/postgresql 但 persistent=false 时，说明已按 fail-open 回退内存，"
            "正式记忆不会跨 Runtime 重启保留。"
        ),
    }


def _in_memory_runtime(settings: AgentMemoryStoreSettings) -> AgentMemoryStoreRuntime:
    """创建默认内存正式记忆 store。"""

    return AgentMemoryStoreRuntime(
        store=InMemoryAgentMemoryStore(),
        settings=settings,
        implementation="InMemoryAgentMemoryStore",
        persistent=False,
    )


def _build_sqlite_connection(settings: AgentMemoryStoreSettings) -> Any:
    """创建 SQLite 连接。

    与候选 store 一样，正式记忆 store 也不自动建表。测试应显式创建 schema，生产应执行 migration。
    这样可以避免 Runtime 用旧 schema 静默启动，导致后续 materialize 或 retrieve 才暴露问题。
    """

    return build_sqlite_connection(settings.sqlite_path, settings.connect_timeout_seconds)


def _build_mysql_connection(settings: AgentMemoryStoreSettings) -> Any:
    """创建 MySQL 连接。"""

    if not settings.mysql_dsn:
        raise ValueError("DATASMART_AI_FORMAL_MEMORY_MYSQL_DSN 未配置，无法启用 mysql store。")
    return build_mysql_connection(settings.mysql_dsn, settings.connect_timeout_seconds)


def _build_postgresql_connection(settings: AgentMemoryStoreSettings) -> Any:
    """创建 PostgreSQL 连接。

    正式记忆 store 是模型召回链路的事实源，生产环境应优先使用 PostgreSQL/pgvector 的 `ai_memory`
    schema。这里仅创建连接，不做建表；缺表应通过启动失败或集成测试暴露，而不是 Runtime 静默修复。
    """

    if not settings.postgresql_dsn:
        raise ValueError("DATASMART_AI_FORMAL_MEMORY_POSTGRESQL_DSN 未配置，无法启用 postgresql store。")
    return build_postgresql_connection(settings.postgresql_dsn, settings.connect_timeout_seconds)


def _normalize_store_type(value: str | None) -> str:
    """规范化 store 类型，并对非法配置快速失败。"""

    normalized = (value or "in-memory").strip().lower().replace("_", "-")
    if normalized == "postgres":
        normalized = "postgresql"
    if normalized not in {"in-memory", "sqlite", "mysql", "postgresql"}:
        raise ValueError(
            "DATASMART_AI_FORMAL_MEMORY_STORE 只支持 in-memory、sqlite、mysql 或 postgresql，"
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
