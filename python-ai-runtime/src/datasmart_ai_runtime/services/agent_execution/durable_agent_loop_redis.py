"""Durable Agent Loop checkpoint 的 Redis 持久化实现。

本模块把 `DurableAgentLoopStore` 从单进程内存实现推进到可跨实例、跨重启共享的短期状态存储。
Redis 在这里保存的是 Agent 的“恢复现场”，而不是业务正文：

- 保存 request/run/session 定位字段、阶段、恢复动作、进度计数和低敏原因码；
- 不保存 prompt、SQL、工具参数、模型输出、凭证、token、artifact 正文或内部 endpoint；
- 使用 runId 作为主恢复键，缺失时退化为 requestId；
- 使用 TTL 防止已经结束或被遗忘的会话永久占用内存。

为什么不直接把 Redis 访问写进 `DurableAgentLoopService`：
服务层只应该决定“当前处于哪个阶段”，仓储层才决定“状态保存在哪里”。保持协议隔离后，未来可以替换为
Redis Cluster、MySQL、LangGraph checkpointer 或企业内部状态服务，而不需要重写阶段判断逻辑。
"""

from __future__ import annotations

import json
import re
from collections.abc import Mapping
from datetime import datetime
from typing import Any

from datasmart_ai_runtime.services.agent_execution.durable_agent_loop import (
    DurableAgentLoopCheckpoint,
    DurableAgentLoopPhase,
    DurableAgentLoopResumeAction,
)


DEFAULT_DURABLE_LOOP_REDIS_KEY_PREFIX = "datasmart:agent-runtime:durable-loop"


class RedisDurableAgentLoopStore:
    """使用 Redis 保存最新 Durable Agent Loop checkpoint。

    Redis 数据结构刻意保持简单：`{prefix}:run:{runId}` 对应一个 JSON checkpoint。当前上层查询也是按 runId
    精确读取，不需要维护全量索引；这可以避免诊断接口通过 `KEYS` 或大范围 `SCAN` 给生产 Redis 带来额外压力。

    并发语义：
    - 同一个 run 的新 checkpoint 覆盖旧 checkpoint，符合“恢复时读取最新现场”的业务语义；
    - `createdAt` 由服务层内存实现负责保留，但跨进程覆盖时当前版本以新 checkpoint 为准；
    - 如果后续需要乐观锁和严格版本比较，可以基于 `checkpointVersion/updatedAt` 增加 Lua CAS，而不改变 store 协议。
    """

    def __init__(
        self,
        client: Any,
        *,
        key_prefix: str = DEFAULT_DURABLE_LOOP_REDIS_KEY_PREFIX,
        ttl_seconds: int | None = 86400,
    ) -> None:
        """创建 Redis store。

        参数说明：
        - `client`：redis-py 或测试 fake，只需实现 `set/get`；
        - `key_prefix`：环境级命名空间，不能包含用户输入正文；
        - `ttl_seconds`：恢复现场保留时间，非正数表示不自动过期。
        """

        self._client = client
        self._key_prefix = (key_prefix or DEFAULT_DURABLE_LOOP_REDIS_KEY_PREFIX).rstrip(":")
        self._ttl_seconds = ttl_seconds if ttl_seconds and ttl_seconds > 0 else None

    def save(self, checkpoint: DurableAgentLoopCheckpoint) -> DurableAgentLoopCheckpoint:
        """保存当前 run 的最新低敏 checkpoint。

        写入前只调用本模块白名单序列化函数；即使未来 checkpoint dataclass 新增字段，也不会未经审查自动进入
        Redis。这样能避免业务正文或第三方响应因为 `checkpoint.__dict__` 被整体序列化而扩大泄漏面。
        """

        key = self._checkpoint_key(checkpoint.run_id or checkpoint.request_id)
        payload = json.dumps(_serialize_checkpoint(checkpoint), ensure_ascii=False, separators=(",", ":"))
        if self._ttl_seconds is None:
            self._client.set(key, payload)
        else:
            self._client.set(key, payload, ex=self._ttl_seconds)
        return checkpoint

    def get(self, run_id: str) -> DurableAgentLoopCheckpoint | None:
        """按 runId/requestId 读取最新 checkpoint。

        Redis key 不存在通常意味着 checkpoint 从未写入、TTL 已到期或当前实例连接到了错误命名空间。
        上层只返回 `found=false`，不会泄露 Redis 地址、key 前缀或连接异常详情。
        """

        raw = self._client.get(self._checkpoint_key(run_id))
        payload = _decode_mapping(raw)
        return _deserialize_checkpoint(payload) if payload is not None else None

    def diagnostics(self) -> dict[str, Any]:
        """返回不触发 Redis 全量扫描的低敏诊断。

        诊断只说明 store 类型、TTL 和 payload policy。checkpoint 数量应由独立指标或 Redis 运维系统统计，
        不能为了一个 HTTP 诊断请求扫描生产 keyspace。
        """

        return {
            "storeType": "redis",
            "checkpointCount": None,
            "checkpointCountReason": "REDIS_KEYSPACE_SCAN_DISABLED",
            "ttlSeconds": self._ttl_seconds,
            "payloadPolicy": "LOW_SENSITIVE_LOOP_STATE_ONLY",
        }

    def _checkpoint_key(self, run_id: str) -> str:
        """构造稳定且可排障的 Redis key。"""

        return f"{self._key_prefix}:run:{_safe_key_part(run_id)}"


def _serialize_checkpoint(checkpoint: DurableAgentLoopCheckpoint) -> dict[str, Any]:
    """把 checkpoint 转换为严格白名单 JSON 结构。"""

    return {
        "requestId": checkpoint.request_id,
        "runId": checkpoint.run_id,
        "sessionId": checkpoint.session_id,
        "tenantId": checkpoint.tenant_id,
        "projectId": checkpoint.project_id,
        "actorId": checkpoint.actor_id,
        "phase": checkpoint.phase.value,
        "resumeAction": checkpoint.resume_action.value,
        "toolPlanCount": checkpoint.tool_plan_count,
        "expectedFeedbackCount": checkpoint.expected_feedback_count,
        "receivedFeedbackCount": checkpoint.received_feedback_count,
        "loopAction": checkpoint.loop_action,
        "waitingReasonCodes": list(checkpoint.waiting_reason_codes),
        "secondTurnExecuted": checkpoint.second_turn_executed,
        "checkpointVersion": checkpoint.checkpoint_version,
        "createdAt": checkpoint.created_at.isoformat(),
        "updatedAt": checkpoint.updated_at.isoformat(),
        "attributes": _safe_attributes(checkpoint.attributes),
    }


def _deserialize_checkpoint(payload: Mapping[str, Any]) -> DurableAgentLoopCheckpoint:
    """从 Redis 白名单 JSON 恢复领域 checkpoint。"""

    return DurableAgentLoopCheckpoint(
        request_id=_required_text(payload.get("requestId"), "requestId"),
        run_id=_text(payload.get("runId")),
        session_id=_text(payload.get("sessionId")),
        tenant_id=_required_text(payload.get("tenantId"), "tenantId"),
        project_id=_required_text(payload.get("projectId"), "projectId"),
        actor_id=_required_text(payload.get("actorId"), "actorId"),
        phase=DurableAgentLoopPhase(_required_text(payload.get("phase"), "phase")),
        resume_action=DurableAgentLoopResumeAction(_required_text(payload.get("resumeAction"), "resumeAction")),
        tool_plan_count=_non_negative_int(payload.get("toolPlanCount")),
        expected_feedback_count=_non_negative_int(payload.get("expectedFeedbackCount")),
        received_feedback_count=_non_negative_int(payload.get("receivedFeedbackCount")),
        loop_action=_text(payload.get("loopAction")),
        waiting_reason_codes=_string_tuple(payload.get("waitingReasonCodes")),
        second_turn_executed=payload.get("secondTurnExecuted") is True,
        checkpoint_version=max(1, _non_negative_int(payload.get("checkpointVersion"))),
        created_at=_datetime(payload.get("createdAt")),
        updated_at=_datetime(payload.get("updatedAt")),
        attributes=_safe_attributes(payload.get("attributes")),
    )


def _safe_attributes(value: Any) -> dict[str, Any]:
    """只保留 Durable Loop 已定义的布尔型低敏属性。"""

    if not isinstance(value, Mapping):
        return {}
    allowed = ("requiresHumanApproval", "hasControlPlaneFeedback", "hasLoopDecision", "turnDepth")
    return {key: value[key] for key in allowed if key in value and isinstance(value[key], (bool, int))}


def _decode_mapping(value: Any) -> Mapping[str, Any] | None:
    """兼容 redis-py bytes 与测试 fake 的字符串返回。"""

    if isinstance(value, bytes):
        value = value.decode("utf-8")
    text = _text(value)
    if not text:
        return None
    payload = json.loads(text)
    return payload if isinstance(payload, Mapping) else None


def _safe_key_part(value: Any) -> str:
    """把外部 ID 约束为 Redis key 安全片段。"""

    return re.sub(r"[^A-Za-z0-9:_.-]", "_", _required_text(value, "runId"))


def _datetime(value: Any) -> datetime:
    """解析 ISO 时间；非法数据让读取失败，避免带着伪造时间继续恢复。"""

    return datetime.fromisoformat(_required_text(value, "checkpoint timestamp"))


def _string_tuple(value: Any) -> tuple[str, ...]:
    """读取有限低敏原因码集合。"""

    if not isinstance(value, (list, tuple)):
        return ()
    return tuple(str(item)[:100] for item in value if _text(item))[:16]


def _non_negative_int(value: Any) -> int:
    """把计数字段规范为非负整数。"""

    try:
        return max(0, int(value))
    except (TypeError, ValueError):
        return 0


def _required_text(value: Any, field: str) -> str:
    """读取必填文本，Redis 脏数据应 fail-fast 而不是构造不可恢复状态。"""

    text = _text(value)
    if not text:
        raise ValueError(f"Durable Agent Loop Redis checkpoint 缺少必填字段: {field}")
    return text


def _text(value: Any) -> str | None:
    """读取可选非空文本。"""

    if value is None:
        return None
    text = str(value).strip()
    return text or None
