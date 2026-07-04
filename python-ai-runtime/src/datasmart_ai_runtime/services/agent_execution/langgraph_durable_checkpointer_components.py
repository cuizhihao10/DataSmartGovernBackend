"""LangGraph Durable Checkpointer 的启动配置与组件装配。

该模块把环境变量、PostgreSQL DSN、fail-open 策略和诊断摘要集中起来，避免 FastAPI `app.py` 或
LangGraph 节点直接理解数据库连接细节。默认使用 in-memory，生产/准生产可以显式切换到 PostgreSQL。
"""

from __future__ import annotations

import os
from dataclasses import dataclass
from typing import Any, Callable, Mapping

from datasmart_ai_runtime.services.agent_execution.langgraph_durable_checkpointer import (
    InMemoryLangGraphCheckpointStore,
    LangGraphCheckpointStore,
)
from datasmart_ai_runtime.services.agent_execution.langgraph_durable_checkpointer_postgresql import (
    PostgresLangGraphCheckpointStore,
)
from datasmart_ai_runtime.services.memory.memory_sql_connection import (
    build_postgresql_connection,
    mask_postgresql_dsn,
)


LangGraphPostgresConnectionFactory = Callable[["LangGraphDurableCheckpointerSettings"], Any]


@dataclass(frozen=True)
class LangGraphDurableCheckpointerSettings:
    """LangGraph checkpointer 启动配置。

    字段说明：
    - `store_type`：`in-memory` 或 `postgresql`；
    - `postgresql_dsn`：PostgreSQL DSN，建议包含 `options=-c search_path=ai_memory`；
    - `connect_timeout_seconds`：连接超时，避免启动被故障数据库长期卡住；
    - `fail_open`：PostgreSQL 不可用时是否回退内存。本地可开启，生产建议关闭。
    """

    store_type: str = "in-memory"
    postgresql_dsn: str = ""
    connect_timeout_seconds: int = 3
    fail_open: bool = True


def langgraph_durable_checkpointer_settings_from_env(
    environ: Mapping[str, str] | None = None,
) -> LangGraphDurableCheckpointerSettings:
    """读取 LangGraph checkpointer 环境变量。

    支持：
    - `DATASMART_LANGGRAPH_CHECKPOINT_STORE=in-memory|postgresql`；
    - `DATASMART_LANGGRAPH_CHECKPOINT_POSTGRESQL_DSN`；
    - `DATASMART_AI_MEMORY_POSTGRESQL_DSN` 作为全局 PostgreSQL DSN 兜底；
    - `DATASMART_LANGGRAPH_CHECKPOINT_CONNECT_TIMEOUT_SECONDS`；
    - `DATASMART_LANGGRAPH_CHECKPOINT_FAIL_OPEN`。
    """

    source = os.environ if environ is None else environ
    return LangGraphDurableCheckpointerSettings(
        store_type=_normalize_store_type(source.get("DATASMART_LANGGRAPH_CHECKPOINT_STORE")),
        postgresql_dsn=source.get("DATASMART_LANGGRAPH_CHECKPOINT_POSTGRESQL_DSN")
        or source.get("DATASMART_AI_MEMORY_POSTGRESQL_DSN")
        or "",
        connect_timeout_seconds=_positive_int(
            source.get("DATASMART_LANGGRAPH_CHECKPOINT_CONNECT_TIMEOUT_SECONDS"),
            default=3,
        ),
        fail_open=_truthy(source.get("DATASMART_LANGGRAPH_CHECKPOINT_FAIL_OPEN"), default=True),
    )


def build_langgraph_checkpoint_store(
    settings: LangGraphDurableCheckpointerSettings | None = None,
    *,
    connection_factory: LangGraphPostgresConnectionFactory | None = None,
) -> LangGraphCheckpointStore:
    """按配置创建 LangGraph checkpoint store。

    PostgreSQL 是目标生产路径；如果连接失败且 `fail_open=true`，会回退到内存实现，便于本地环境继续跑
    Agent 主链。但诊断接口会明确显示 configuredType 与 implementation，避免生产误以为已经持久化。
    """

    resolved = settings or langgraph_durable_checkpointer_settings_from_env()
    if resolved.store_type == "in-memory":
        return InMemoryLangGraphCheckpointStore()
    try:
        connection = connection_factory(resolved) if connection_factory else _build_postgresql_connection(resolved)
        return PostgresLangGraphCheckpointStore(connection)
    except Exception:
        if not resolved.fail_open:
            raise
        return InMemoryLangGraphCheckpointStore()


def langgraph_checkpoint_store_diagnostics(
    store: LangGraphCheckpointStore,
    settings: LangGraphDurableCheckpointerSettings,
) -> dict[str, Any]:
    """输出 checkpointer 启动诊断。

    该诊断不会读取任何 checkpoint 正文，只展示配置选择、实现类型、脱敏 DSN 和安全策略。
    """

    return {
        "component": "langgraph-durable-checkpointer-store",
        "configuredType": settings.store_type,
        "implementation": store.__class__.__name__,
        "persistent": settings.store_type == "postgresql" and isinstance(store, PostgresLangGraphCheckpointStore),
        "failOpen": settings.fail_open,
        "postgresql": {
            "dsn": mask_postgresql_dsn(settings.postgresql_dsn) if settings.store_type == "postgresql" else None,
            "connectTimeoutSeconds": settings.connect_timeout_seconds,
        },
        "sensitiveDataPolicy": {
            "rawPromptStored": False,
            "toolArgumentsStored": False,
            "modelOutputStored": False,
            "credentialStored": False,
            "largePayloadStored": False,
        },
        "store": store.diagnostics(),
    }


def _build_postgresql_connection(settings: LangGraphDurableCheckpointerSettings) -> Any:
    """创建 PostgreSQL 连接。"""

    if not settings.postgresql_dsn:
        raise ValueError("DATASMART_LANGGRAPH_CHECKPOINT_POSTGRESQL_DSN 未配置，无法启用 postgresql checkpointer。")
    return build_postgresql_connection(settings.postgresql_dsn, settings.connect_timeout_seconds)


def _normalize_store_type(value: str | None) -> str:
    """规范化 store 类型。"""

    normalized = (value or "in-memory").strip().lower().replace("_", "-")
    if normalized == "postgres":
        normalized = "postgresql"
    if normalized not in {"in-memory", "postgresql"}:
        raise ValueError("DATASMART_LANGGRAPH_CHECKPOINT_STORE 只支持 in-memory 或 postgresql。")
    return normalized


def _positive_int(value: str | None, *, default: int) -> int:
    """读取正整数配置。"""

    try:
        parsed = int(str(value).strip())
    except (TypeError, ValueError, AttributeError):
        return default
    return parsed if parsed > 0 else default


def _truthy(value: str | None, *, default: bool) -> bool:
    """读取布尔配置。"""

    if value is None or not str(value).strip():
        return default
    return str(value).strip().lower() in {"1", "true", "yes", "y", "on", "enabled"}


__all__ = [
    "LangGraphDurableCheckpointerSettings",
    "build_langgraph_checkpoint_store",
    "langgraph_checkpoint_store_diagnostics",
    "langgraph_durable_checkpointer_settings_from_env",
]
