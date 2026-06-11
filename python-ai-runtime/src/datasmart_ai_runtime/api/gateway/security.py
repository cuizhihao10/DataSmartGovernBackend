"""gateway 签名安全辅助组件。

本模块承接 ``api/gateway/signature.py`` 的密码学校验结果，提供两个更偏“生产运营”的能力：

1. nonce 去重：
   HMAC 签名能证明请求确实由 gateway 生成，但如果请求在允许时间窗口内被截获并重放，单纯 HMAC
   仍然会通过。因此生产环境需要把 nonce 记录到短 TTL 存储中，同一个 keyId + nonce 只能使用一次。

2. 安全统计：
   签名失败不能只写一条普通日志。商业化运维需要知道失败原因分布，例如缺少签名、签名不匹配、
   timestamp 过期、nonce 重放等。这里先提供进程内统计快照，后续可以替换为 Prometheus Counter、
   Java 审计事件、Kafka 安全事件或 SIEM 告警。

当前模块仍保持轻依赖：默认内存实现适合本地学习、单元测试和单实例开发；生产多实例应显式启用 Redis。
"""

from __future__ import annotations

import os
import time
from dataclasses import dataclass, field
from threading import Lock
from typing import Any, Mapping


@dataclass(frozen=True)
class GatewaySignatureNonceStoreSettings:
    """gateway 签名 nonce store 配置。

    字段说明：
    - ``store_type``：``disabled``、``in-memory`` 或 ``redis``。默认使用 ``in-memory``，让本地单进程也具备
      基础防重放能力；生产多实例必须切换为 ``redis``，否则不同 Python worker 之间无法共享 nonce。
    - ``ttl_seconds``：nonce 保存时长。它应不小于签名 timestamp 允许偏移窗口，否则请求可能在签名仍有效时
      因 nonce 已过期而再次被接受。
    - ``redis_url``：Redis 连接字符串，只在 ``store_type=redis`` 时使用，可能包含密码，不能原样写入日志。
    - ``key_prefix``：Redis key 前缀，便于和 runtime event、缓存、会话等其他 Redis 数据隔离。
    """

    store_type: str = "in-memory"
    ttl_seconds: int = 300
    redis_url: str = "redis://localhost:6379/0"
    key_prefix: str = "datasmart:gateway-signature:nonce"


class InMemoryGatewaySignatureNonceStore:
    """进程内 nonce 去重实现。

    这个实现的价值不是替代 Redis，而是让本地开发、单元测试、单实例部署也能验证“同一个 nonce 不能重复使用”
    的语义。它使用 Lock 保护字典，避免多线程 ASGI worker 或测试并发调用时出现竞态。

    生产边界：
    - 多进程 Uvicorn/Gunicorn worker 各自有独立内存，不能共享 nonce；
    - 多实例 Kubernetes 部署更不能依赖内存；
    - 因此生产应使用 ``RedisGatewaySignatureNonceStore`` 或企业内部统一缓存 SDK。
    """

    def __init__(self) -> None:
        self._seen_until_ms: dict[str, int] = {}
        self._lock = Lock()

    def try_mark_seen(self, *, key_id: str, nonce: str, timestamp_ms: int, ttl_seconds: int) -> bool:
        """登记 nonce，首次使用返回 True，重复使用返回 False。"""

        key = _nonce_key(key_id, nonce)
        now_ms = int(time.time() * 1000)
        expires_at_ms = now_ms + ttl_seconds * 1000
        with self._lock:
            self._purge_expired_locked(now_ms)
            if key in self._seen_until_ms:
                return False
            self._seen_until_ms[key] = expires_at_ms
            return True

    def diagnostics(self) -> dict[str, Any]:
        """返回不含 nonce 原文的诊断快照。"""

        with self._lock:
            self._purge_expired_locked(int(time.time() * 1000))
            active_count = len(self._seen_until_ms)
        return {
            "storeType": "in-memory",
            "activeNonceCount": active_count,
            "clusterSafe": False,
            "note": "仅适合本地学习、单元测试或单实例开发；生产多实例应使用 Redis。",
        }

    def _purge_expired_locked(self, now_ms: int) -> None:
        """清理已过期 nonce，避免长时间运行后内存无限增长。"""

        expired_keys = [key for key, expires_at_ms in self._seen_until_ms.items() if expires_at_ms <= now_ms]
        for key in expired_keys:
            self._seen_until_ms.pop(key, None)


class RedisGatewaySignatureNonceStore:
    """Redis nonce 去重实现。

    Redis 适合签名 nonce 的原因：
    - ``SET key value NX EX ttl`` 是原子操作，可以天然表达“只有第一次使用成功”；
    - TTL 到期后 Redis 自动清理 key，不需要 Python Runtime 自己维护清理任务；
    - 多个 Python Runtime 实例、多个 Uvicorn worker 可以共享同一个 Redis，避免进程间重放绕过。

    这里不直接依赖 redis-py 的具体类型，只要求 client 具备 ``set`` 方法。这样测试可以注入 fake client，
    生产可以传 redis-py、Redis Cluster 或企业内部缓存客户端。
    """

    def __init__(self, redis_client: Any, *, key_prefix: str) -> None:
        self._redis_client = redis_client
        self._key_prefix = key_prefix.rstrip(":")

    def try_mark_seen(self, *, key_id: str, nonce: str, timestamp_ms: int, ttl_seconds: int) -> bool:
        """通过 Redis 原子 SET NX EX 登记 nonce。"""

        key = f"{self._key_prefix}:{_nonce_key(key_id, nonce)}"
        result = self._redis_client.set(key, str(timestamp_ms), ex=ttl_seconds, nx=True)
        return bool(result)

    def diagnostics(self) -> dict[str, Any]:
        """返回 Redis nonce store 的安全诊断，不做 Redis 网络探测。"""

        return {
            "storeType": "redis",
            "keyPrefix": self._key_prefix,
            "clusterSafe": True,
            "note": "依赖 Redis SET NX EX 实现多实例共享 nonce 去重；该诊断不代表 Redis 当前一定可用。",
        }


class DisabledGatewaySignatureNonceStore:
    """显式关闭 nonce 去重的实现。

    该实现只用于极少数迁移场景或问题排查。生产环境不应使用它，因为它会让时间窗口内的重放请求仍然可能通过
    HMAC 校验。
    """

    def try_mark_seen(self, *, key_id: str, nonce: str, timestamp_ms: int, ttl_seconds: int) -> bool:
        """始终允许 nonce，用于显式关闭防重放。"""

        return True

    def diagnostics(self) -> dict[str, Any]:
        """返回关闭状态诊断。"""

        return {
            "storeType": "disabled",
            "clusterSafe": False,
            "note": "nonce 去重已关闭；仅建议本地迁移或故障排查临时使用，生产环境应启用 Redis。",
        }


@dataclass
class GatewaySignatureSecurityStats:
    """gateway 签名安全统计。

    该对象是进程内轻量统计，不替代正式监控系统。它的作用是先把指标语义固定下来：
    - ``failureCountByReason``：按失败 reason 聚合，帮助区分攻击、密钥不一致、时钟漂移或配置缺失；
    - ``recentFailures``：保留最近少量失败快照，便于本地联调和诊断接口展示；
    - ``totalFailureCount``：总失败数。

    后续接 Prometheus 时，可以把 ``record_failure`` 改造成同时递增 Counter；接 Java 审计时，可以把快照
    发送到 Kafka 或 agent-runtime 审计接口。
    """

    max_recent_failures: int = 20
    failure_count_by_reason: dict[str, int] = field(default_factory=dict)
    recent_failures: list[dict[str, Any]] = field(default_factory=list)
    _lock: Lock = field(default_factory=Lock, init=False, repr=False)

    def record_failure(self, detail: Mapping[str, Any]) -> None:
        """记录一次签名失败。

        ``detail`` 应来自 API 层统一错误对象，且不能包含 secret、签名值、签名原文或完整 Header。
        """

        reason = str(detail.get("reason") or "unknown")
        snapshot = {
            "code": detail.get("code"),
            "reason": reason,
            "traceId": detail.get("traceId"),
            "sourceService": detail.get("sourceService"),
            "path": detail.get("path"),
            "recordedAtEpochMs": int(time.time() * 1000),
        }
        with self._lock:
            self.failure_count_by_reason[reason] = self.failure_count_by_reason.get(reason, 0) + 1
            self.recent_failures.append(snapshot)
            if len(self.recent_failures) > self.max_recent_failures:
                del self.recent_failures[: len(self.recent_failures) - self.max_recent_failures]

    def snapshot(self) -> dict[str, Any]:
        """生成可用于诊断接口、日志或测试断言的统计快照。"""

        with self._lock:
            failure_count_by_reason = dict(self.failure_count_by_reason)
            recent_failures = tuple(dict(item) for item in self.recent_failures)
        return {
            "component": "gateway-signature-security",
            "totalFailureCount": sum(failure_count_by_reason.values()),
            "failureCountByReason": failure_count_by_reason,
            "recentFailures": recent_failures,
            "notes": (
                "该快照是进程内轻量统计，不替代 Prometheus、审计表或 SIEM。生产环境应继续把 reason "
                "指标化，并把高风险失败写入统一安全审计链路。"
            ),
        }


def gateway_signature_nonce_store_settings_from_env(
    environ: Mapping[str, str] | None = None,
) -> GatewaySignatureNonceStoreSettings:
    """从环境变量读取 nonce store 配置。

    环境变量：
    - ``DATASMART_GATEWAY_SIGNATURE_NONCE_STORE``：``disabled``、``in-memory``、``redis``；
    - ``DATASMART_GATEWAY_SIGNATURE_NONCE_TTL_SECONDS``：nonce TTL 秒数；
    - ``DATASMART_GATEWAY_SIGNATURE_NONCE_REDIS_URL``：专用 Redis URL；
    - ``DATASMART_AI_RUNTIME_REDIS_URL``：如果未配置专用 URL，则复用 runtime event Redis URL；
    - ``DATASMART_GATEWAY_SIGNATURE_NONCE_KEY_PREFIX``：Redis key 前缀。
    """

    values = os.environ if environ is None else environ
    return GatewaySignatureNonceStoreSettings(
        store_type=_normalize_nonce_store_type(values.get("DATASMART_GATEWAY_SIGNATURE_NONCE_STORE")),
        ttl_seconds=_positive_int(values.get("DATASMART_GATEWAY_SIGNATURE_NONCE_TTL_SECONDS"), default=300),
        redis_url=(
            values.get("DATASMART_GATEWAY_SIGNATURE_NONCE_REDIS_URL")
            or values.get("DATASMART_AI_RUNTIME_REDIS_URL")
            or "redis://localhost:6379/0"
        ),
        key_prefix=values.get("DATASMART_GATEWAY_SIGNATURE_NONCE_KEY_PREFIX")
        or "datasmart:gateway-signature:nonce",
    )


def build_gateway_signature_nonce_store(
    settings: GatewaySignatureNonceStoreSettings | None = None,
    *,
    redis_client_factory: Any | None = None,
) -> Any:
    """按配置创建 nonce store。

    返回类型保持为 ``Any``，是为了避免核心包对具体 Redis client 类型产生依赖。调用方只需要把返回对象传给
    ``ensure_gateway_signature(..., nonce_store=...)``，签名模块会按最小协议调用 ``try_mark_seen``。
    """

    resolved = settings or gateway_signature_nonce_store_settings_from_env()
    if resolved.store_type == "disabled":
        return DisabledGatewaySignatureNonceStore()
    if resolved.store_type == "in-memory":
        return InMemoryGatewaySignatureNonceStore()
    factory = redis_client_factory or _default_redis_client_factory
    return RedisGatewaySignatureNonceStore(factory(resolved.redis_url), key_prefix=resolved.key_prefix)


def gateway_signature_security_diagnostics(
    stats: GatewaySignatureSecurityStats,
    nonce_store: Any,
    settings: GatewaySignatureNonceStoreSettings,
) -> dict[str, Any]:
    """构造 gateway 签名安全诊断响应。"""

    diagnostics = nonce_store.diagnostics() if hasattr(nonce_store, "diagnostics") else {}
    return {
        "component": "gateway-signature-security",
        "nonceStore": {
            "configuredType": settings.store_type,
            "ttlSeconds": settings.ttl_seconds,
            **diagnostics,
        },
        "signatureFailures": stats.snapshot(),
        "notes": (
            "该诊断只返回配置、实现类型和聚合统计，不返回 secret、nonce 原文、签名值或签名原文。"
            "生产环境仍应把该端点置于 gateway/permission-admin 管理员权限之后。"
        ),
    }


def _default_redis_client_factory(redis_url: str) -> Any:
    """使用 redis-py 创建 Redis client。"""

    try:
        import redis  # type: ignore
    except ImportError as exc:  # pragma: no cover - 只有显式启用 Redis 且缺依赖时触发
        raise RuntimeError(
            "已配置 gateway 签名 nonce store 使用 Redis，但当前环境未安装 redis-py。"
            "请安装 `pip install -e python-ai-runtime[redis]`，或把 "
            "DATASMART_GATEWAY_SIGNATURE_NONCE_STORE 改为 in-memory/disabled。"
        ) from exc
    return redis.from_url(redis_url)


def _nonce_key(key_id: str, nonce: str) -> str:
    """构造不含签名原文的 nonce 逻辑 key。"""

    return f"{_safe_key_part(key_id)}:{_safe_key_part(nonce)}"


def _safe_key_part(value: str) -> str:
    """把 keyId/nonce 中不适合放入 Redis key 的字符替换掉。"""

    return "".join(character if character.isalnum() or character in {"-", "_", "."} else "_" for character in value)


def _normalize_nonce_store_type(value: str | None) -> str:
    """规范化 nonce store 类型，并对非法配置快速失败。"""

    normalized = (value or "in-memory").strip().lower().replace("_", "-")
    if normalized not in {"disabled", "in-memory", "redis"}:
        raise ValueError(
            "DATASMART_GATEWAY_SIGNATURE_NONCE_STORE 只支持 disabled、in-memory 或 redis，"
            f"当前值为：{value}"
        )
    return normalized


def _positive_int(value: str | None, default: int) -> int:
    """读取正整数环境变量，非法或空值回退默认值。"""

    if value is None or not value.strip():
        return default
    try:
        parsed = int(value)
    except ValueError:
        return default
    return parsed if parsed > 0 else default
