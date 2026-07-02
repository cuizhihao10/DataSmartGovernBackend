"""Durable Agent Loop store 的启动配置与组件装配。

该模块集中处理环境变量、Redis client 延迟导入和低敏诊断，避免 FastAPI `app.py` 直接知道不同仓储实现细节。
默认仍使用 in-memory，保证单测和学习环境零依赖；生产环境显式选择 Redis 后才加载 redis-py。
"""

from __future__ import annotations

import os
from collections.abc import Mapping
from dataclasses import dataclass
from typing import Any, Callable
from urllib.parse import urlsplit

from datasmart_ai_runtime.services.agent_execution.durable_agent_loop import (
    DurableAgentLoopStore,
    InMemoryDurableAgentLoopStore,
)
from datasmart_ai_runtime.services.agent_execution.durable_agent_loop_redis import (
    DEFAULT_DURABLE_LOOP_REDIS_KEY_PREFIX,
    RedisDurableAgentLoopStore,
)


@dataclass(frozen=True)
class DurableAgentLoopStoreSettings:
    """Durable Loop store 配置快照。"""

    store_type: str = "in-memory"
    redis_url: str = "redis://localhost:6379/0"
    redis_key_prefix: str = DEFAULT_DURABLE_LOOP_REDIS_KEY_PREFIX
    ttl_seconds: int = 86400


def durable_agent_loop_store_settings_from_env(
    environ: Mapping[str, str] | None = None,
) -> DurableAgentLoopStoreSettings:
    """读取 Durable Loop store 环境变量。

    支持：
    - `DATASMART_AGENT_DURABLE_LOOP_STORE=in-memory|redis`；
    - `DATASMART_AGENT_DURABLE_LOOP_REDIS_URL`，未设置时复用 `DATASMART_REDIS_URL`；
    - `DATASMART_AGENT_DURABLE_LOOP_REDIS_KEY_PREFIX`；
    - `DATASMART_AGENT_DURABLE_LOOP_TTL_SECONDS`。
    """

    env = os.environ if environ is None else environ
    store_type = str(env.get("DATASMART_AGENT_DURABLE_LOOP_STORE") or "in-memory").strip().lower()
    if store_type not in {"in-memory", "redis"}:
        raise ValueError("DATASMART_AGENT_DURABLE_LOOP_STORE 只支持 in-memory 或 redis")
    return DurableAgentLoopStoreSettings(
        store_type=store_type,
        redis_url=str(
            env.get("DATASMART_AGENT_DURABLE_LOOP_REDIS_URL")
            or env.get("DATASMART_REDIS_URL")
            or "redis://localhost:6379/0"
        ).strip(),
        redis_key_prefix=str(
            env.get("DATASMART_AGENT_DURABLE_LOOP_REDIS_KEY_PREFIX")
            or DEFAULT_DURABLE_LOOP_REDIS_KEY_PREFIX
        ).strip(),
        ttl_seconds=_positive_int(env.get("DATASMART_AGENT_DURABLE_LOOP_TTL_SECONDS"), 86400),
    )


def build_durable_agent_loop_store(
    settings: DurableAgentLoopStoreSettings | None = None,
    *,
    redis_client_factory: Callable[[str], Any] | None = None,
) -> DurableAgentLoopStore:
    """按配置构建 Durable Loop store。

    Redis 依赖只在显式启用时导入；如果部署选择 Redis 却没有安装 `[redis]` 可选依赖，应用会在启动阶段
    fail-fast，而不是运行到第一次保存 checkpoint 才悄悄退化为内存。
    """

    resolved = settings or durable_agent_loop_store_settings_from_env()
    if resolved.store_type == "in-memory":
        return InMemoryDurableAgentLoopStore()
    factory = redis_client_factory or _default_redis_client_factory
    return RedisDurableAgentLoopStore(
        factory(resolved.redis_url),
        key_prefix=resolved.redis_key_prefix,
        ttl_seconds=resolved.ttl_seconds,
    )


def durable_agent_loop_store_diagnostics(
    store: DurableAgentLoopStore,
    settings: DurableAgentLoopStoreSettings,
) -> dict[str, Any]:
    """生成可进入启动诊断的脱敏配置摘要。"""

    return {
        "component": "durable-agent-loop-store",
        "configuredType": settings.store_type,
        "implementation": store.__class__.__name__,
        "redis": {
            "enabled": settings.store_type == "redis",
            "url": _masked_redis_url(settings.redis_url) if settings.store_type == "redis" else None,
            "keyPrefix": settings.redis_key_prefix if settings.store_type == "redis" else None,
            "ttlSeconds": settings.ttl_seconds if settings.store_type == "redis" else None,
        },
        "sensitiveDataPolicy": {
            "rawPromptStored": False,
            "sqlStored": False,
            "toolArgumentsStored": False,
            "modelOutputStored": False,
            "credentialStored": False,
        },
    }


def _default_redis_client_factory(redis_url: str) -> Any:
    """延迟创建 redis-py client。"""

    try:
        import redis
    except ImportError as exc:
        raise RuntimeError(
            '启用 Redis Durable Agent Loop 前请安装可选依赖：pip install -e "python-ai-runtime[redis]"'
        ) from exc
    return redis.Redis.from_url(redis_url)


def _masked_redis_url(redis_url: str) -> str:
    """隐藏 Redis 用户名、密码、query 和 fragment，只保留排障所需地址。"""

    parsed = urlsplit(redis_url)
    host = parsed.hostname or "unknown"
    port = f":{parsed.port}" if parsed.port is not None else ""
    database = parsed.path if parsed.path else ""
    credentials = "***:***@" if parsed.username or parsed.password else ""
    return f"{parsed.scheme or 'redis'}://{credentials}{host}{port}{database}"


def _positive_int(value: Any, default: int) -> int:
    """解析正整数配置。"""

    try:
        parsed = int(value)
    except (TypeError, ValueError):
        return default
    return parsed if parsed > 0 else default
