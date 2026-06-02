"""长期记忆落成 receipt store 的运行时组装层。

正式长期记忆写入链路现在有三类事实：
- candidate store：记录“是否允许写入”；
- formal memory store：记录“模型可召回的正式记忆”；
- receipt store：记录“后台落成尝试是否执行成功”。

本文件负责按环境变量选择 receipt store 实现。它与正式记忆 store runtime builder 保持同样的
in-memory / sqlite / mysql / fail-open / fail-fast 语义，便于本地学习、准生产联调和生产部署使用同一套
配置模式。
"""

from __future__ import annotations

import os
from dataclasses import dataclass
from typing import Any, Callable

from datasmart_ai_runtime.services.memory.memory_materialization_receipt_sql_store import (
    SqlAgentMemoryMaterializationReceiptStore,
)
from datasmart_ai_runtime.services.memory.memory_materialization_receipt_store import (
    AgentMemoryMaterializationReceiptStore,
    InMemoryAgentMemoryMaterializationReceiptStore,
)
from datasmart_ai_runtime.services.memory.memory_sql_connection import (
    build_mysql_connection,
    build_sqlite_connection,
    mask_mysql_dsn,
)


ReceiptSqlConnectionFactory = Callable[["AgentMemoryMaterializationReceiptStoreSettings"], Any]


@dataclass(frozen=True)
class AgentMemoryMaterializationReceiptStoreSettings:
    """长期记忆落成 receipt store 启动配置。

    字段说明：
    - `store_type`：支持 `in-memory`、`sqlite`、`mysql`；
    - `sqlite_path`：SQLite 文件路径，仅在 sqlite 模式使用；
    - `mysql_dsn`：MySQL DSN，仅在 mysql 模式使用；
    - `connect_timeout_seconds`：数据库连接超时；
    - `fail_open`：持久化不可用时是否回退内存。

    生产建议：
    receipt 是补偿和审计的执行证据，生产环境应优先 `mysql + fail_open=false`，否则 worker 失败记录可能在
    Runtime 重启后丢失，管理员无法判断哪些候选需要补偿。
    """

    store_type: str = "in-memory"
    sqlite_path: str = "datasmart-agent-memory-materialization-receipts.sqlite3"
    mysql_dsn: str = ""
    connect_timeout_seconds: int = 3
    fail_open: bool = True


@dataclass(frozen=True)
class AgentMemoryMaterializationReceiptStoreRuntime:
    """receipt store 运行时结果。"""

    store: AgentMemoryMaterializationReceiptStore
    settings: AgentMemoryMaterializationReceiptStoreSettings
    implementation: str
    persistent: bool
    fallback_reason: str | None = None


def memory_materialization_receipt_store_settings_from_env(
    environ: dict[str, str] | None = None,
) -> AgentMemoryMaterializationReceiptStoreSettings:
    """从环境变量读取 receipt store 配置。

    支持的环境变量：
    - `DATASMART_AI_MEMORY_RECEIPT_STORE`；
    - `DATASMART_AI_MEMORY_RECEIPT_SQLITE_PATH`；
    - `DATASMART_AI_MEMORY_RECEIPT_MYSQL_DSN`；
    - `DATASMART_AI_MEMORY_RECEIPT_SQL_CONNECT_TIMEOUT_SECONDS`；
    - `DATASMART_AI_MEMORY_RECEIPT_STORE_FAIL_OPEN`。
    """

    source = environ if environ is not None else os.environ
    return AgentMemoryMaterializationReceiptStoreSettings(
        store_type=_normalize_store_type(source.get("DATASMART_AI_MEMORY_RECEIPT_STORE")),
        sqlite_path=source.get("DATASMART_AI_MEMORY_RECEIPT_SQLITE_PATH")
        or "datasmart-agent-memory-materialization-receipts.sqlite3",
        mysql_dsn=source.get("DATASMART_AI_MEMORY_RECEIPT_MYSQL_DSN") or "",
        connect_timeout_seconds=_positive_int(
            source.get("DATASMART_AI_MEMORY_RECEIPT_SQL_CONNECT_TIMEOUT_SECONDS"),
            default=3,
        ),
        fail_open=_truthy(source.get("DATASMART_AI_MEMORY_RECEIPT_STORE_FAIL_OPEN"), default=True),
    )


def build_memory_materialization_receipt_store_runtime(
    settings: AgentMemoryMaterializationReceiptStoreSettings | None = None,
    connection_factory: ReceiptSqlConnectionFactory | None = None,
) -> AgentMemoryMaterializationReceiptStoreRuntime:
    """按配置创建 receipt store。"""

    resolved = settings or memory_materialization_receipt_store_settings_from_env()
    try:
        if resolved.store_type == "in-memory":
            return _in_memory_runtime(resolved)
        if resolved.store_type == "sqlite":
            connection = connection_factory(resolved) if connection_factory else _build_sqlite_connection(resolved)
            return AgentMemoryMaterializationReceiptStoreRuntime(
                store=SqlAgentMemoryMaterializationReceiptStore(connection),
                settings=resolved,
                implementation="SqlAgentMemoryMaterializationReceiptStore(sqlite)",
                persistent=True,
            )
        connection = connection_factory(resolved) if connection_factory else _build_mysql_connection(resolved)
        return AgentMemoryMaterializationReceiptStoreRuntime(
            store=SqlAgentMemoryMaterializationReceiptStore(connection, placeholder="%s"),
            settings=resolved,
            implementation="SqlAgentMemoryMaterializationReceiptStore(mysql)",
            persistent=True,
        )
    except Exception as exc:
        if not resolved.fail_open:
            raise
        fallback = _in_memory_runtime(resolved)
        return AgentMemoryMaterializationReceiptStoreRuntime(
            store=fallback.store,
            settings=resolved,
            implementation=fallback.implementation,
            persistent=False,
            fallback_reason=f"{exc.__class__.__name__}: {exc}",
        )


def memory_materialization_receipt_store_diagnostics(
    runtime: AgentMemoryMaterializationReceiptStoreRuntime,
) -> dict[str, Any]:
    """生成 receipt store 低敏诊断信息。"""

    settings = runtime.settings
    return {
        "component": "python-ai-memory-materialization-receipt-store",
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
        "notes": (
            "receipt store 记录长期记忆落成尝试，不保存 prompt、原始工具输出、样本数据或完整异常堆栈。"
            "生产环境如果 configuredType=mysql 但 persistent=false，说明已按 fail-open 回退内存，"
            "worker 执行证据不会跨 Runtime 重启保留。"
        ),
    }


def _in_memory_runtime(
    settings: AgentMemoryMaterializationReceiptStoreSettings,
) -> AgentMemoryMaterializationReceiptStoreRuntime:
    """创建默认内存 receipt store。"""

    return AgentMemoryMaterializationReceiptStoreRuntime(
        store=InMemoryAgentMemoryMaterializationReceiptStore(),
        settings=settings,
        implementation="InMemoryAgentMemoryMaterializationReceiptStore",
        persistent=False,
    )


def _build_sqlite_connection(settings: AgentMemoryMaterializationReceiptStoreSettings) -> Any:
    """创建 SQLite 连接。"""

    return build_sqlite_connection(settings.sqlite_path, settings.connect_timeout_seconds)


def _build_mysql_connection(settings: AgentMemoryMaterializationReceiptStoreSettings) -> Any:
    """创建 MySQL 连接。"""

    if not settings.mysql_dsn:
        raise ValueError("DATASMART_AI_MEMORY_RECEIPT_MYSQL_DSN 未配置，无法启用 mysql receipt store。")
    return build_mysql_connection(settings.mysql_dsn, settings.connect_timeout_seconds)


def _normalize_store_type(value: str | None) -> str:
    """规范化 store 类型，并对非法配置快速失败。"""

    normalized = (value or "in-memory").strip().lower().replace("_", "-")
    if normalized not in {"in-memory", "sqlite", "mysql"}:
        raise ValueError(
            "DATASMART_AI_MEMORY_RECEIPT_STORE 只支持 in-memory、sqlite 或 mysql，"
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
