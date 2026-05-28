"""Agent 长期记忆写入候选存储的运行时组装层。

前几个阶段已经把“记忆写入候选”拆成了领域模型、治理服务、内存 store 与 SQL store。
本文件负责最后一段启动装配：根据环境变量决定 Python Runtime 到底使用哪一种候选存储。

为什么单独建这个文件，而不是继续写在 `api.py` 或 `memory_write_governance.py` 中：
- `api.py` 应只做 FastAPI 应用创建、路由挂载和组件装配，不适合塞入数据库驱动选择细节；
- `memory_write_governance.py` 已经接近 500 行，且它的职责是业务治理，不应该理解 MySQL DSN；
- 独立组装层可以被 API、后台 worker、命令行诊断工具和测试复用，后续替换为 Java memory-service
  客户端时也不会影响候选状态机。

当前支持三种模式：
- `in-memory`：默认模式，零依赖、适合本地学习和单元测试，但重启丢失；
- `sqlite`：使用 Python 标准库 `sqlite3`，适合轻量集成测试或单机演示；
- `mysql`：动态导入 PyMySQL / mysqlclient，适合生产或准生产环境接入 MySQL 持久化表。
"""

from __future__ import annotations

import os
import sqlite3
from dataclasses import dataclass
from typing import Any, Callable
from urllib.parse import urlsplit, urlunsplit

from datasmart_ai_runtime.services.memory_write_candidate_store import (
    AgentMemoryWriteCandidateStore,
    InMemoryAgentMemoryWriteCandidateStore,
)
from datasmart_ai_runtime.services.memory_write_sql_store import SqlAgentMemoryWriteCandidateStore


SqlConnectionFactory = Callable[["AgentMemoryWriteStoreSettings"], Any]


@dataclass(frozen=True)
class AgentMemoryWriteStoreSettings:
    """记忆写入候选 store 启动配置。

    字段说明：
    - `store_type`：目标存储类型，支持 `in-memory`、`sqlite`、`mysql`；
    - `sqlite_path`：SQLite 文件路径，仅在 `store_type=sqlite` 时使用；
    - `mysql_dsn`：MySQL 连接字符串，仅在 `store_type=mysql` 时使用，诊断输出必须脱敏；
    - `connect_timeout_seconds`：数据库连接超时，防止 Runtime 启动时被故障数据库长时间卡住；
    - `fail_open`：显式选择持久化但连接失败时是否回退内存。

    `fail_open` 的产品意义：
    - 本地学习、开发联调阶段建议为 true，缺少驱动或数据库未启动时仍可跑通 Agent 主链；
    - 生产环境建议为 false，避免运维以为已持久化，实际却退回内存导致审批候选丢失。
    """

    store_type: str = "in-memory"
    sqlite_path: str = "datasmart-agent-memory-write-candidates.sqlite3"
    mysql_dsn: str = ""
    connect_timeout_seconds: int = 3
    fail_open: bool = True


@dataclass(frozen=True)
class AgentMemoryWriteStoreRuntime:
    """记忆写入候选 store 运行时结果。

    该对象同时保存“真实 store 实例”和“诊断元数据”。这样 API 路由可以直接使用 store，
    运维诊断接口也能解释本次启动有没有发生 fallback、是否具备持久化能力、哪些配置被采纳。
    """

    store: AgentMemoryWriteCandidateStore
    settings: AgentMemoryWriteStoreSettings
    implementation: str
    persistent: bool
    fallback_reason: str | None = None


def memory_write_store_settings_from_env(environ: dict[str, str] | None = None) -> AgentMemoryWriteStoreSettings:
    """从环境变量读取记忆写入候选 store 配置。

    支持的环境变量：
    - `DATASMART_AI_MEMORY_WRITE_STORE`：`in-memory` / `sqlite` / `mysql`；
    - `DATASMART_AI_MEMORY_WRITE_SQLITE_PATH`：SQLite 文件路径；
    - `DATASMART_AI_MEMORY_WRITE_MYSQL_DSN`：MySQL DSN，支持 URL 或 `host=...;user=...` 风格；
    - `DATASMART_AI_MEMORY_WRITE_SQL_CONNECT_TIMEOUT_SECONDS`：数据库连接超时秒数；
    - `DATASMART_AI_MEMORY_WRITE_STORE_FAIL_OPEN`：持久化不可用时是否回退内存。
    """

    source = environ if environ is not None else os.environ
    return AgentMemoryWriteStoreSettings(
        store_type=_normalize_store_type(source.get("DATASMART_AI_MEMORY_WRITE_STORE")),
        sqlite_path=source.get("DATASMART_AI_MEMORY_WRITE_SQLITE_PATH")
        or "datasmart-agent-memory-write-candidates.sqlite3",
        mysql_dsn=source.get("DATASMART_AI_MEMORY_WRITE_MYSQL_DSN") or "",
        connect_timeout_seconds=_positive_int(
            source.get("DATASMART_AI_MEMORY_WRITE_SQL_CONNECT_TIMEOUT_SECONDS"),
            default=3,
        ),
        fail_open=_truthy(source.get("DATASMART_AI_MEMORY_WRITE_STORE_FAIL_OPEN"), default=True),
    )


def build_memory_write_store_runtime(
    settings: AgentMemoryWriteStoreSettings | None = None,
    connection_factory: SqlConnectionFactory | None = None,
) -> AgentMemoryWriteStoreRuntime:
    """按配置创建记忆写入候选 store。

    组装规则：
    1. 默认永远使用内存 store，保证 Python Runtime 零依赖可启动；
    2. SQLite 使用标准库，适合持久化语义联调；
    3. MySQL 使用动态导入，避免默认安装包被数据库客户端绑定；
    4. 如果持久化启用失败且 `fail_open=true`，返回内存 store，并在诊断中记录原因；
    5. 如果 `fail_open=false`，直接抛错，让生产部署快速失败。
    """

    resolved = settings or memory_write_store_settings_from_env()
    try:
        if resolved.store_type == "in-memory":
            return _in_memory_runtime(resolved)
        if resolved.store_type == "sqlite":
            connection = connection_factory(resolved) if connection_factory else _build_sqlite_connection(resolved)
            return AgentMemoryWriteStoreRuntime(
                store=SqlAgentMemoryWriteCandidateStore(connection),
                settings=resolved,
                implementation="SqlAgentMemoryWriteCandidateStore(sqlite)",
                persistent=True,
            )
        connection = connection_factory(resolved) if connection_factory else _build_mysql_connection(resolved)
        return AgentMemoryWriteStoreRuntime(
            store=SqlAgentMemoryWriteCandidateStore(connection, placeholder="%s"),
            settings=resolved,
            implementation="SqlAgentMemoryWriteCandidateStore(mysql)",
            persistent=True,
        )
    except Exception as exc:
        if not resolved.fail_open:
            raise
        fallback = _in_memory_runtime(resolved)
        return AgentMemoryWriteStoreRuntime(
            store=fallback.store,
            settings=resolved,
            implementation=fallback.implementation,
            persistent=False,
            fallback_reason=f"{exc.__class__.__name__}: {exc}",
        )


def memory_write_store_diagnostics(runtime: AgentMemoryWriteStoreRuntime) -> dict[str, Any]:
    """生成记忆写入候选 store 诊断信息。

    该诊断只暴露配置类型、真实实现、是否持久化和脱敏后的连接目标，不返回候选内容。
    它解决的是运维排障中最常见的问题：“我配置了 MySQL，Runtime 现在到底有没有用上 MySQL？”
    """

    settings = runtime.settings
    return {
        "component": "python-ai-memory-write-candidate-store",
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
            "dsn": _mask_mysql_dsn(settings.mysql_dsn) if settings.store_type == "mysql" else None,
        },
        "notes": (
            "诊断只说明启动时选择的候选 store，不会执行真实写入探测。生产环境如果 configuredType=mysql "
            "但 persistent=false，说明已经按 fail-open 回退到内存，审批候选仍可能在重启后丢失。"
        ),
    }


def _in_memory_runtime(settings: AgentMemoryWriteStoreSettings) -> AgentMemoryWriteStoreRuntime:
    """创建默认内存 store 运行时。"""

    return AgentMemoryWriteStoreRuntime(
        store=InMemoryAgentMemoryWriteCandidateStore(),
        settings=settings,
        implementation="InMemoryAgentMemoryWriteCandidateStore",
        persistent=False,
    )


def _build_sqlite_connection(settings: AgentMemoryWriteStoreSettings) -> sqlite3.Connection:
    """创建 SQLite 连接。

    SQLite 模式不自动建表，是为了避免 Runtime 悄悄修改生产文件。调用方应在启动前执行迁移脚本，
    或在测试中显式创建 schema。这里设置 `row_factory=sqlite3.Row`，让 SQL store 能按字段名还原候选。
    """

    connection = sqlite3.connect(settings.sqlite_path, timeout=settings.connect_timeout_seconds)
    connection.row_factory = sqlite3.Row
    return connection


def _build_mysql_connection(settings: AgentMemoryWriteStoreSettings) -> Any:
    """创建 MySQL DB-API 连接。

    这里优先尝试 PyMySQL，再尝试 mysqlclient(MySQLdb)。两者都没有时抛出清晰错误。
    连接参数支持两类写法：
    - URL：`mysql://user:password@localhost:3306/datasmart?charset=utf8mb4`；
    - 分号键值：`host=localhost;port=3306;user=root;password=xxx;database=datasmart`。
    """

    if not settings.mysql_dsn:
        raise ValueError("DATASMART_AI_MEMORY_WRITE_MYSQL_DSN 未配置，无法启用 mysql store。")
    kwargs = _parse_mysql_dsn(settings.mysql_dsn, settings.connect_timeout_seconds)
    try:
        import pymysql  # type: ignore

        return pymysql.connect(**kwargs, cursorclass=pymysql.cursors.DictCursor)
    except ImportError:
        try:
            import MySQLdb  # type: ignore
        except ImportError as exc:
            raise RuntimeError("启用 mysql store 需要安装 PyMySQL 或 mysqlclient。") from exc
        return MySQLdb.connect(**kwargs)


def _parse_mysql_dsn(dsn: str, connect_timeout_seconds: int) -> dict[str, Any]:
    """解析 MySQL DSN 为 DB-API 连接参数。"""

    if "://" in dsn:
        parts = urlsplit(dsn)
        return {
            "host": parts.hostname or "localhost",
            "port": parts.port or 3306,
            "user": parts.username or "root",
            "password": parts.password or "",
            "database": parts.path.lstrip("/") or "datasmart",
            "charset": _query_value(parts.query, "charset") or "utf8mb4",
            "connect_timeout": connect_timeout_seconds,
        }
    values: dict[str, str] = {}
    for item in dsn.split(";"):
        if not item.strip() or "=" not in item:
            continue
        key, value = item.split("=", 1)
        values[key.strip().lower()] = value.strip()
    return {
        "host": values.get("host", "localhost"),
        "port": int(values.get("port", "3306")),
        "user": values.get("user") or values.get("username") or "root",
        "password": values.get("password", ""),
        "database": values.get("database") or values.get("db") or "datasmart",
        "charset": values.get("charset", "utf8mb4"),
        "connect_timeout": connect_timeout_seconds,
    }


def _query_value(query: str, name: str) -> str | None:
    """从 URL query 中读取一个简单参数。"""

    for item in query.split("&"):
        if not item or "=" not in item:
            continue
        key, value = item.split("=", 1)
        if key == name:
            return value
    return None


def _normalize_store_type(value: str | None) -> str:
    """规范化 store 类型，并对非法配置快速失败。"""

    normalized = (value or "in-memory").strip().lower().replace("_", "-")
    if normalized not in {"in-memory", "sqlite", "mysql"}:
        raise ValueError(f"DATASMART_AI_MEMORY_WRITE_STORE 只支持 in-memory、sqlite 或 mysql，当前值为：{value}")
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


def _mask_mysql_dsn(dsn: str) -> str:
    """脱敏 MySQL DSN，避免诊断接口泄露数据库密码。"""

    if not dsn:
        return ""
    if "://" in dsn:
        parts = urlsplit(dsn)
        host = parts.hostname or "localhost"
        port = f":{parts.port}" if parts.port else ""
        database = parts.path or ""
        return urlunsplit((parts.scheme, f"***:***@{host}{port}", database, parts.query, parts.fragment))
    masked: list[str] = []
    for item in dsn.split(";"):
        if "=" not in item:
            masked.append(item)
            continue
        key, value = item.split("=", 1)
        masked.append(f"{key}=***" if key.strip().lower() in {"password", "pwd"} else f"{key}={value}")
    return ";".join(masked)
