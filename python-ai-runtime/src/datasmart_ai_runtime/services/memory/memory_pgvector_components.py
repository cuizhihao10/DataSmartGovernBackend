"""pgvector 长期记忆索引的运行时装配层。

`memory_pgvector_adapter.py` 只负责索引业务语义；本模块负责把环境变量、PostgreSQL 连接、
Embedding Provider 和 fail-open/fail-fast 策略组装成 API 可使用的运行时。

默认不启用 pgvector，原因是本地学习环境可能没有真实 Embedding 服务。只有显式配置
`DATASMART_AI_MEMORY_PGVECTOR_ENABLED=true` 后才尝试连接数据库并创建索引适配器：

- 开发环境可设置 `FAIL_OPEN=true`，连接或模型配置失败时回退 store-backed VECTOR 通道；
- 生产环境应设置 `FAIL_OPEN=false`，防止管理台显示“已启用 pgvector”，实际却悄悄走关键词候选窗口；
- `deterministic` Embedding Provider 只允许测试/smoke，不应作为生产配置。
"""

from __future__ import annotations

import os
from dataclasses import dataclass
from typing import Any, Callable

from datasmart_ai_runtime.services.memory.memory_embedding_provider import (
    AgentMemoryEmbeddingProvider,
    MemoryEmbeddingProviderSettings,
    build_memory_embedding_provider,
    memory_embedding_provider_diagnostics,
    memory_embedding_provider_settings_from_env,
)
from datasmart_ai_runtime.services.memory.memory_pgvector_adapter import (
    PgvectorAgentMemorySecondaryIndex,
    PgvectorMemoryIndexSettings,
)
from datasmart_ai_runtime.services.memory.memory_sql_connection import (
    build_postgresql_connection,
    mask_postgresql_dsn,
)
from datasmart_ai_runtime.services.memory.memory_store import AgentMemoryStore


PgvectorConnectionFactory = Callable[["PgvectorMemoryIndexRuntimeSettings"], Any]


@dataclass(frozen=True)
class PgvectorMemoryIndexRuntimeSettings:
    """pgvector 索引运行时配置。

    字段说明：
    - `enabled`：是否启用真实 pgvector VECTOR 通道；
    - `postgresql_dsn`：目标 PostgreSQL DSN，默认回退全局 AI Memory DSN；
    - `schema_name`：目标 schema，默认 `ai_memory`；
    - `connect_timeout_seconds`：启动连接超时；
    - `fail_open`：启动失败是否回退 store-backed VECTOR；
    - `document_max_chars`：索引和查询进入 Embedding Provider 的字符上限；
    - `minimum_similarity`：余弦相似度最低阈值。
    """

    enabled: bool = False
    postgresql_dsn: str = ""
    schema_name: str = "ai_memory"
    connect_timeout_seconds: int = 5
    fail_open: bool = True
    document_max_chars: int = 4000
    minimum_similarity: float = -1.0


@dataclass(frozen=True)
class PgvectorMemoryIndexRuntime:
    """pgvector 索引运行时结果。

    `index=None` 表示真实 pgvector 未激活，上层应保留 store-backed VECTOR fallback。
    `configured_enabled` 与 `active` 分开，便于诊断“用户要求启用但启动失败”的配置漂移。
    """

    settings: PgvectorMemoryIndexRuntimeSettings
    embedding_settings: MemoryEmbeddingProviderSettings
    index: PgvectorAgentMemorySecondaryIndex | None
    configured_enabled: bool
    active: bool
    fallback_reason: str | None = None


def pgvector_memory_index_runtime_settings_from_env(
    environ: dict[str, str] | None = None,
) -> PgvectorMemoryIndexRuntimeSettings:
    """读取 pgvector 运行时环境变量。

    支持：
- `DATASMART_AI_MEMORY_PGVECTOR_ENABLED`；
- `DATASMART_AI_MEMORY_PGVECTOR_POSTGRESQL_DSN`；
- `DATASMART_AI_MEMORY_POSTGRESQL_DSN`；
- `DATASMART_AI_MEMORY_PGVECTOR_SCHEMA`；
- `DATASMART_AI_MEMORY_PGVECTOR_CONNECT_TIMEOUT_SECONDS`；
- `DATASMART_AI_MEMORY_PGVECTOR_FAIL_OPEN`；
- `DATASMART_AI_MEMORY_PGVECTOR_DOCUMENT_MAX_CHARS`；
- `DATASMART_AI_MEMORY_PGVECTOR_MINIMUM_SIMILARITY`。
    """

    source = environ if environ is not None else os.environ
    return PgvectorMemoryIndexRuntimeSettings(
        enabled=_truthy(source.get("DATASMART_AI_MEMORY_PGVECTOR_ENABLED"), default=False),
        postgresql_dsn=source.get("DATASMART_AI_MEMORY_PGVECTOR_POSTGRESQL_DSN")
        or source.get("DATASMART_AI_MEMORY_POSTGRESQL_DSN")
        or "",
        schema_name=source.get("DATASMART_AI_MEMORY_PGVECTOR_SCHEMA") or "ai_memory",
        connect_timeout_seconds=_positive_int(
            source.get("DATASMART_AI_MEMORY_PGVECTOR_CONNECT_TIMEOUT_SECONDS"),
            5,
        ),
        fail_open=_truthy(source.get("DATASMART_AI_MEMORY_PGVECTOR_FAIL_OPEN"), default=True),
        document_max_chars=_positive_int(
            source.get("DATASMART_AI_MEMORY_PGVECTOR_DOCUMENT_MAX_CHARS"),
            4000,
        ),
        minimum_similarity=_bounded_float(
            source.get("DATASMART_AI_MEMORY_PGVECTOR_MINIMUM_SIMILARITY"),
            default=-1.0,
            minimum=-1.0,
            maximum=1.0,
        ),
    )


def build_pgvector_memory_index_runtime(
    *,
    memory_store: AgentMemoryStore,
    runtime_settings: PgvectorMemoryIndexRuntimeSettings | None = None,
    embedding_settings: MemoryEmbeddingProviderSettings | None = None,
    connection_factory: PgvectorConnectionFactory | None = None,
    embedding_provider: AgentMemoryEmbeddingProvider | None = None,
) -> PgvectorMemoryIndexRuntime:
    """创建可选 pgvector 索引运行时。

    组装顺序：
    1. 未启用时不创建数据库连接，也不要求模型配置；
    2. 启用后要求 PostgreSQL DSN 与 Embedding Provider；
    3. 创建独立 pgvector 连接，避免向量慢查询占用正式 memory store 的单连接；
    4. 失败时按 fail-open/fail-fast 决定回退还是阻断启动。
    """

    resolved_runtime = runtime_settings or pgvector_memory_index_runtime_settings_from_env()
    resolved_embedding = embedding_settings or memory_embedding_provider_settings_from_env()
    if not resolved_runtime.enabled:
        return PgvectorMemoryIndexRuntime(
            settings=resolved_runtime,
            embedding_settings=resolved_embedding,
            index=None,
            configured_enabled=False,
            active=False,
        )

    try:
        if not resolved_runtime.postgresql_dsn:
            raise ValueError("启用 pgvector 时必须配置 DATASMART_AI_MEMORY_POSTGRESQL_DSN。")
        provider = embedding_provider or build_memory_embedding_provider(resolved_embedding)
        if provider is None:
            raise ValueError("启用 pgvector 时必须配置独立 Embedding Provider。")
        if not resolved_embedding.model.strip():
            raise ValueError("启用 pgvector 时必须配置 DATASMART_AI_MEMORY_EMBEDDING_MODEL。")
        connection = (
            connection_factory(resolved_runtime)
            if connection_factory
            else build_postgresql_connection(
                resolved_runtime.postgresql_dsn,
                resolved_runtime.connect_timeout_seconds,
            )
        )
        index = PgvectorAgentMemorySecondaryIndex(
            connection=connection,
            memory_store=memory_store,
            embedding_provider=provider,
            settings=PgvectorMemoryIndexSettings(
                schema_name=resolved_runtime.schema_name,
                embedding_model=resolved_embedding.model,
                document_max_chars=resolved_runtime.document_max_chars,
                minimum_similarity=resolved_runtime.minimum_similarity,
            ),
        )
        return PgvectorMemoryIndexRuntime(
            settings=resolved_runtime,
            embedding_settings=resolved_embedding,
            index=index,
            configured_enabled=True,
            active=True,
        )
    except Exception as exc:
        if not resolved_runtime.fail_open:
            raise
        return PgvectorMemoryIndexRuntime(
            settings=resolved_runtime,
            embedding_settings=resolved_embedding,
            index=None,
            configured_enabled=True,
            active=False,
            fallback_reason=f"{type(exc).__name__}: {str(exc)[:300]}",
        )


def pgvector_memory_index_diagnostics(runtime: PgvectorMemoryIndexRuntime) -> dict[str, object]:
    """生成低敏 pgvector 运行时诊断。

    诊断不返回完整 DSN、endpoint、API Key、向量、queryHint 或记忆正文。索引内部计数查询失败时，
    只记录错误类型，不让诊断接口拖垮 Runtime 主链。
    """

    index_diagnostics: dict[str, object] | None = None
    diagnostic_error: str | None = None
    if runtime.index is not None:
        try:
            index_diagnostics = runtime.index.diagnostics()
        except Exception as exc:
            diagnostic_error = f"{type(exc).__name__}: {str(exc)[:200]}"
    return {
        "component": "python-ai-memory-pgvector-index",
        "configuredEnabled": runtime.configured_enabled,
        "active": runtime.active,
        "fallback": runtime.fallback_reason is not None,
        "fallbackReason": runtime.fallback_reason,
        "failOpen": runtime.settings.fail_open,
        "schema": runtime.settings.schema_name,
        "postgresqlDsn": mask_postgresql_dsn(runtime.settings.postgresql_dsn),
        "connectTimeoutSeconds": runtime.settings.connect_timeout_seconds,
        "documentMaxChars": runtime.settings.document_max_chars,
        "minimumSimilarity": runtime.settings.minimum_similarity,
        "embeddingProvider": memory_embedding_provider_diagnostics(runtime.embedding_settings),
        "index": index_diagnostics,
        "diagnosticError": diagnostic_error,
        "notes": (
            "active=false 时 StoreBackedAgentMemoryRetriever 保留 store-backed VECTOR fallback。",
            "生产必须使用真实 Embedding Provider、failOpen=false，并完成维度专用索引与容量压测。",
        ),
    }


def _truthy(value: str | None, *, default: bool) -> bool:
    """读取布尔配置。"""

    if value is None or not value.strip():
        return default
    return value.strip().lower() in {"true", "1", "yes", "on", "enabled"}


def _positive_int(value: str | None, default: int) -> int:
    """读取正整数配置。"""

    if value is None or not value.strip():
        return default
    parsed = int(value)
    return parsed if parsed > 0 else default


def _bounded_float(
    value: str | None,
    *,
    default: float,
    minimum: float,
    maximum: float,
) -> float:
    """读取并裁剪浮点配置。"""

    if value is None or not value.strip():
        return default
    return max(minimum, min(maximum, float(value)))


__all__ = [
    "PgvectorMemoryIndexRuntime",
    "PgvectorMemoryIndexRuntimeSettings",
    "build_pgvector_memory_index_runtime",
    "pgvector_memory_index_diagnostics",
    "pgvector_memory_index_runtime_settings_from_env",
]
