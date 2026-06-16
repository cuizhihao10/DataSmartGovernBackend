"""工具动作执行前图 checkpoint store 的启动装配层。

本模块的职责类似 `runtime_event_components.py`：把“本地学习默认 in-memory”和“生产环境可选 Redis”
通过环境变量表达出来，而不是让 API 路由或 runner 自己决定创建哪种 store。

为什么需要单独的组件装配层：
- checkpoint store 必须在控制流预览、checkpoint 查询、resume-preview 之间共享同一个实例；
- 如果每个 API helper 都自己创建 in-memory store，就会出现“预览保存成功，但查询接口查不到”的问题；
- 如果未来要按租户、项目、环境切换 Redis、MySQL 或工作流引擎，也应从这里统一演进，而不是散落在路由里。

支持的环境变量：
- `DATASMART_TOOL_ACTION_CHECKPOINT_STORE`：`in-memory` 或 `redis`；
- `DATASMART_TOOL_ACTION_CHECKPOINT_REDIS_URL`：工具动作 checkpoint 专用 Redis URL；
- `DATASMART_AI_RUNTIME_REDIS_URL`：当上一个变量未设置时复用 AI Runtime 通用 Redis URL；
- `DATASMART_TOOL_ACTION_CHECKPOINT_REDIS_KEY_PREFIX`：Redis key 前缀；
- `DATASMART_TOOL_ACTION_CHECKPOINT_TTL_SECONDS`：Redis checkpoint TTL；
- `DATASMART_TOOL_ACTION_CHECKPOINT_MAX_PER_THREAD`：单 thread 最近 checkpoint 数量上限；
- `DATASMART_TOOL_ACTION_CHECKPOINT_MAX_TOTAL`：in-memory 全局 checkpoint 数量上限。
"""

from __future__ import annotations

import os
from dataclasses import dataclass
from typing import Any, Callable
from urllib.parse import urlsplit, urlunsplit

from datasmart_ai_runtime.services.tools.tool_action_execution_checkpoint import (
    InMemoryToolActionExecutionCheckpointStore,
    ToolActionExecutionCheckpointStore,
)
from datasmart_ai_runtime.services.tools.tool_action_execution_checkpoint_redis import (
    DEFAULT_REDIS_CHECKPOINT_KEY_PREFIX,
    RedisToolActionExecutionCheckpointStore,
)


RedisClientFactory = Callable[[str], Any]


@dataclass(frozen=True)
class ToolActionExecutionCheckpointStoreSettings:
    """工具动作 checkpoint store 配置。

    字段说明：
    - `store_type`：当前选择的存储实现，默认 `in-memory`，生产可配置为 `redis`；
    - `redis_url`：Redis 连接字符串，只在 `store_type=redis` 时使用；
    - `redis_key_prefix`：Redis key 前缀，用于区分环境、组件和命名空间；
    - `redis_ttl_seconds`：Redis checkpoint 保留时间，短期恢复状态不应永久驻留；
    - `max_checkpoints_per_thread`：单 thread 最近 checkpoint 数量上限；
    - `max_total_checkpoints`：in-memory 模式下的全局容量上限。

    这里没有把 MySQL 配置提前塞进来，是为了避免“配置先行但实现缺失”的假能力。后续真正做长期审计表时，
    可以新增 `store_type=mysql` 和对应配置，而不影响当前 Redis 短期恢复语义。
    """

    store_type: str = "in-memory"
    redis_url: str = "redis://localhost:6379/0"
    redis_key_prefix: str = DEFAULT_REDIS_CHECKPOINT_KEY_PREFIX
    redis_ttl_seconds: int = 3600
    max_checkpoints_per_thread: int = 20
    max_total_checkpoints: int = 2000


def tool_action_execution_checkpoint_store_settings_from_env(
    environ: dict[str, str] | None = None,
) -> ToolActionExecutionCheckpointStoreSettings:
    """从环境变量读取工具动作 checkpoint store 配置。

    配置策略：
    - 默认保持 `in-memory`，保证本地学习、单元测试和离线规划不需要 Redis；
    - 生产显式设置 `DATASMART_TOOL_ACTION_CHECKPOINT_STORE=redis` 后才会导入 redis-py；
    - Redis URL 优先使用专用变量，未设置时复用 AI Runtime 通用 Redis URL，方便小规模部署共用连接配置；
    - 所有容量和 TTL 都按正整数读取，非法值会回退默认值，避免拼写错误导致启动阶段难以理解。
    """

    source = environ if environ is not None else os.environ
    return ToolActionExecutionCheckpointStoreSettings(
        store_type=_normalize_store_type(
            source.get("DATASMART_TOOL_ACTION_CHECKPOINT_STORE"),
            default="in-memory",
            variable_name="DATASMART_TOOL_ACTION_CHECKPOINT_STORE",
        ),
        redis_url=(
            source.get("DATASMART_TOOL_ACTION_CHECKPOINT_REDIS_URL")
            or source.get("DATASMART_AI_RUNTIME_REDIS_URL")
            or "redis://localhost:6379/0"
        ),
        redis_key_prefix=source.get("DATASMART_TOOL_ACTION_CHECKPOINT_REDIS_KEY_PREFIX")
        or DEFAULT_REDIS_CHECKPOINT_KEY_PREFIX,
        redis_ttl_seconds=_positive_int(
            source.get("DATASMART_TOOL_ACTION_CHECKPOINT_TTL_SECONDS"),
            default=3600,
        ),
        max_checkpoints_per_thread=_positive_int(
            source.get("DATASMART_TOOL_ACTION_CHECKPOINT_MAX_PER_THREAD"),
            default=20,
        ),
        max_total_checkpoints=_positive_int(
            source.get("DATASMART_TOOL_ACTION_CHECKPOINT_MAX_TOTAL"),
            default=2000,
        ),
    )


def build_tool_action_execution_checkpoint_store(
    settings: ToolActionExecutionCheckpointStoreSettings | None = None,
    *,
    redis_client_factory: RedisClientFactory | None = None,
) -> ToolActionExecutionCheckpointStore:
    """按配置创建工具动作 checkpoint store。

    返回对象实现统一的 `ToolActionExecutionCheckpointStore` 协议，上层 API 不需要知道底层是内存还是 Redis。
    这也是后续商业化扩展的关键边界：如果要增加 MySQL、Redis Cluster、内部缓存 SDK 或 durable workflow
    checkpointer，只需要在这里增加构造分支，并保持协议不变。
    """

    resolved_settings = settings or tool_action_execution_checkpoint_store_settings_from_env()
    if resolved_settings.store_type == "in-memory":
        return InMemoryToolActionExecutionCheckpointStore(
            max_checkpoints_per_thread=resolved_settings.max_checkpoints_per_thread,
            max_total_checkpoints=resolved_settings.max_total_checkpoints,
        )
    redis_client = (
        redis_client_factory(resolved_settings.redis_url)
        if redis_client_factory is not None
        else _default_redis_client_factory(resolved_settings.redis_url)
    )
    return RedisToolActionExecutionCheckpointStore(
        redis_client,
        key_prefix=resolved_settings.redis_key_prefix,
        ttl_seconds=resolved_settings.redis_ttl_seconds,
        max_checkpoints_per_thread=resolved_settings.max_checkpoints_per_thread,
    )


def tool_action_execution_checkpoint_store_diagnostics(
    store: ToolActionExecutionCheckpointStore,
    settings: ToolActionExecutionCheckpointStoreSettings,
) -> dict[str, Any]:
    """生成工具动作 checkpoint store 诊断信息。

    该诊断只回答“启动时选了哪种 store、关键容量和 TTL 是多少”，不会 ping Redis，也不会返回任何
    checkpoint 内容。这样它可以安全展示给运维或 Java 控制面，用于排查“为什么恢复查询查不到状态”。
    """

    return {
        "component": "tool-action-execution-checkpoint-store",
        "configuredType": settings.store_type,
        "implementation": store.__class__.__name__,
        "maxCheckpointsPerThread": settings.max_checkpoints_per_thread,
        "maxTotalCheckpoints": settings.max_total_checkpoints if settings.store_type == "in-memory" else None,
        "redis": {
            "enabled": settings.store_type == "redis",
            "url": _mask_redis_url(settings.redis_url) if settings.store_type == "redis" else None,
            "keyPrefix": settings.redis_key_prefix if settings.store_type == "redis" else None,
            "ttlSeconds": settings.redis_ttl_seconds if settings.store_type == "redis" else None,
        },
        "payloadPolicy": "LOW_SENSITIVE_EXECUTION_GRAPH_CHECKPOINT_ONLY",
        "purpose": (
            "保存工具动作执行前图的短期、线程级、低敏 checkpoint，供审批、澄清、outbox/worker receipt "
            "等事实补齐后继续执行前图。它不是长期记忆，也不是审计正文存储。"
        ),
        "sensitiveDataPolicy": {
            "rawPromptStored": False,
            "rawArgumentsStored": False,
            "sqlStored": False,
            "sampleDataStored": False,
            "modelOutputStored": False,
            "credentialStored": False,
            "internalEndpointStored": False,
        },
        "notes": (
            "in-memory 适合本地学习和测试；redis 适合多实例短期恢复。若要长期审计、管理员检索和合规归档，"
            "后续应增加 MySQL/对象归档投影，而不是延长 Redis TTL 来承担长期存储。"
        ),
    }


def _default_redis_client_factory(redis_url: str) -> Any:
    """使用 redis-py 创建 Redis client。

    redis-py 是可选依赖，只有显式选择 Redis checkpoint store 时才导入。这样默认测试环境不会因为缺少
    Redis 包而失败；生产如果开启 Redis 但忘记安装依赖，会得到明确错误。
    """

    try:
        import redis  # type: ignore
    except ImportError as exc:  # pragma: no cover - 只有生产显式启用 Redis 且缺依赖时触发
        raise RuntimeError(
            "已配置 DATASMART_TOOL_ACTION_CHECKPOINT_STORE=redis，但当前环境未安装 redis-py。"
            "请安装 python-ai-runtime[redis]，或把 DATASMART_TOOL_ACTION_CHECKPOINT_STORE 改回 in-memory。"
        ) from exc
    return redis.from_url(redis_url)


def _normalize_store_type(value: str | None, *, default: str, variable_name: str) -> str:
    """规范化 store 类型，并对非法配置快速失败。"""

    normalized = (value or default).strip().lower().replace("_", "-")
    if normalized not in {"in-memory", "redis"}:
        raise ValueError(f"{variable_name} 只支持 in-memory 或 redis，当前值为：{value}")
    return normalized


def _positive_int(value: str | None, *, default: int) -> int:
    """读取正整数配置，非法或空值回退默认值。"""

    if value is None or not value.strip():
        return default
    try:
        parsed = int(value)
    except ValueError:
        return default
    return parsed if parsed > 0 else default


def _mask_redis_url(redis_url: str) -> str:
    """脱敏 Redis URL，避免诊断接口暴露用户名或密码。"""

    parts = urlsplit(redis_url)
    if not parts.netloc or "@" not in parts.netloc:
        return redis_url
    _, host = parts.netloc.rsplit("@", 1)
    return urlunsplit((parts.scheme, f"***:***@{host}", parts.path, parts.query, parts.fragment))
