"""工具动作执行前图 checkpoint 的 Redis 存储实现。

本文件只负责一种事情：把已经低敏化的工具动作执行图 checkpoint 写入 Redis，并支持按
`checkpointId` 或 `threadId` 读取。它刻意不和 FastAPI、runner、Java 控制面客户端耦合，原因是
checkpoint store 是 Agent Host 的基础设施能力，未来可能被 HTTP API、LangGraph/OpenClaw 风格图节点、
后台 worker 或测试脚本共同复用。

为什么这里优先做 Redis，而不是直接上 MySQL：
- Redis 更适合短期、线程级、恢复游标类状态，例如“等待审批后继续”“等待澄清事实后继续”；
- 工具动作 checkpoint 当前不保存完整 prompt、SQL、工具参数和模型输出，所以它不是长期审计正文；
- MySQL 更适合后续做长期审计、运行回放、管理员检索、归档报表，届时可以实现同一个
  `ToolActionExecutionCheckpointStore` 协议，而不是重写上层 API。

安全边界：
- 保存前仍然调用 `low_sensitive_execution_graph_summary(...)` 做二次白名单裁剪；
- Redis key 中只使用 checkpoint/thread/sequence 等控制面标识；
- JSON payload 只包含低敏恢复线索，不包含工具真实参数、prompt、SQL、样本数据、模型输出、凭证或内部 endpoint。
"""

from __future__ import annotations

import json
import re
from collections.abc import Mapping
from datetime import datetime, timezone
from typing import Any
from uuid import uuid4

from datasmart_ai_runtime.services.tools.tool_action_execution_checkpoint import (
    CHECKPOINT_PAYLOAD_POLICY,
    CHECKPOINT_SCHEMA_VERSION,
    DEFAULT_CHECKPOINT_THREAD_ID,
    ToolActionExecutionCheckpoint,
    _checkpoint_thread_id,
    _int_mapping,
    _int_or_zero,
    _safe_context,
    _string_tuple,
    _text,
    low_sensitive_execution_graph_summary,
)


DEFAULT_REDIS_CHECKPOINT_KEY_PREFIX = "datasmart:agent-runtime:tool-action-checkpoint"


class RedisToolActionExecutionCheckpointStore:
    """基于 Redis 的工具动作执行前图 checkpoint store。

    字段与 Redis 数据结构说明：
    - `_client`：兼容 redis-py 的客户端对象，至少需要支持 `set/get/delete/incr/rpush/ltrim/lrange/expire`；
    - `_key_prefix`：所有 key 的前缀，生产环境可以按租户集群、环境、命名空间区分；
    - `_ttl_seconds`：checkpoint 保留时间，默认由组件装配层传入，避免短期恢复状态无限滞留；
    - `_max_checkpoints_per_thread`：单个 thread 最近 checkpoint 数量上限，防止异常循环把 Redis list 打爆。

    Redis key 设计：
    - `{prefix}:item:{checkpointId}` 保存单个 checkpoint JSON；
    - `{prefix}:thread:{threadId}` 保存该 thread 最近 checkpointId 列表；
    - `{prefix}:sequence:{threadId}` 保存该 thread 的递增序号。

    并发语义：
    - `INCR` 提供同 thread 下的原子递增 sequence；
    - `RPUSH/LTRIM` 保证列表最终只保留最近 N 个 ID；
    - 当前阶段不使用 Lua 脚本把“写 item、追加 list、删除被裁剪 item”包成单个原子事务，是为了让本地 fake
      client 和普通 Redis client 都能简单测试。高并发生产环境后续可以把 `_append_thread_index(...)`
      升级为 Lua/transaction，而不影响上层协议。
    """

    def __init__(
        self,
        client: Any,
        *,
        key_prefix: str = DEFAULT_REDIS_CHECKPOINT_KEY_PREFIX,
        ttl_seconds: int | None = 3600,
        max_checkpoints_per_thread: int = 20,
    ) -> None:
        """初始化 Redis checkpoint store。

        参数说明：
        - `client`：由启动装配层创建或测试注入的 Redis 兼容客户端；
        - `key_prefix`：Redis key 前缀，不能包含业务正文，只用于隔离环境和组件；
        - `ttl_seconds`：单个 checkpoint、thread list、sequence key 的过期时间；非正数表示不过期；
        - `max_checkpoints_per_thread`：单个恢复线程保留的最近 checkpoint 数量，非法值会回退为 1。
        """

        self._client = client
        self._key_prefix = (key_prefix or DEFAULT_REDIS_CHECKPOINT_KEY_PREFIX).rstrip(":")
        self._ttl_seconds = ttl_seconds if ttl_seconds and ttl_seconds > 0 else None
        self._max_checkpoints_per_thread = max(1, int(max_checkpoints_per_thread or 20))

    def save(
        self,
        graph_run_summary: Mapping[str, Any],
        *,
        context: Mapping[str, Any] | None = None,
    ) -> ToolActionExecutionCheckpoint:
        """保存一次低敏工具动作执行图 checkpoint。

        业务流程：
        1. 对 runner summary 重新做低敏白名单裁剪，避免调用方绕过 runner 直接传入敏感字段；
        2. 从 context 中提取租户、项目、操作者、request/run/session/thread 等控制面字段；
        3. 使用 Redis `INCR` 生成同 thread 下的递增 sequence；
        4. 写入 checkpoint item，并把 checkpointId 追加到 thread list；
        5. 按 TTL 与 per-thread 上限清理短期状态。
        """

        safe_graph_run = low_sensitive_execution_graph_summary(graph_run_summary)
        safe_context = _safe_context(context or {})
        thread_id = _checkpoint_thread_id(safe_context)
        sequence = _positive_int(self._client.incr(self._sequence_key(thread_id)), default=1)
        checkpoint = ToolActionExecutionCheckpoint(
            checkpoint_id=f"tool-action-checkpoint:{uuid4().hex}",
            thread_id=thread_id,
            sequence=sequence,
            saved_at=datetime.now(timezone.utc).isoformat(),
            source=safe_context.get("source"),
            protocol_family=safe_context.get("protocolFamily"),
            tenant_id=safe_context.get("tenantId"),
            project_id=safe_context.get("projectId"),
            actor_id=safe_context.get("actorId"),
            request_id=safe_context.get("requestId"),
            run_id=safe_context.get("runId"),
            session_id=safe_context.get("sessionId"),
            step_count=_int_or_zero(safe_graph_run.get("stepCount")),
            status_counts=_int_mapping(safe_graph_run.get("statusCounts")),
            resume_requirements=_string_tuple(safe_graph_run.get("resumeRequirements")),
            graph_run_summary=safe_graph_run,
        )
        self._set_json(self._checkpoint_key(checkpoint.checkpoint_id), self._serialize_checkpoint(checkpoint))
        self._append_thread_index(thread_id, checkpoint.checkpoint_id)
        self._expire_if_needed(self._sequence_key(thread_id))
        return checkpoint

    def get(self, checkpoint_id: str) -> ToolActionExecutionCheckpoint | None:
        """按 checkpointId 读取单个 checkpoint。

        如果 Redis 中不存在该 key，通常表示：
        - checkpoint 从未存在；
        - checkpoint 已被 TTL 清理；
        - checkpoint 所属 thread 超过保留上限后被裁剪。

        上层 API 会把这些情况统一表达为 checkpoint not found，而不会泄露 Redis key 细节。
        """

        raw = self._client.get(self._checkpoint_key(checkpoint_id))
        payload = _decode_json(raw)
        if payload is None:
            return None
        return self._deserialize_checkpoint(payload)

    def list_by_thread(self, thread_id: str, *, limit: int = 20) -> tuple[ToolActionExecutionCheckpoint, ...]:
        """按 threadId 读取最近 checkpoint 列表。

        返回顺序保持“从旧到新”，与 in-memory store 一致，便于 UI 或审计逻辑按 sequence 观察状态推进。
        如果 thread list 中存在已经过期的 checkpointId，会跳过对应空项；后续可增加后台清理任务做索引压缩。
        """

        safe_thread_id = _text(thread_id) or DEFAULT_CHECKPOINT_THREAD_ID
        safe_limit = max(1, int(limit or 20))
        raw_ids = self._client.lrange(self._thread_key(safe_thread_id), -safe_limit, -1)
        checkpoints: list[ToolActionExecutionCheckpoint] = []
        for raw_id in raw_ids or ():
            checkpoint_id = _decode_text(raw_id)
            if not checkpoint_id:
                continue
            checkpoint = self.get(checkpoint_id)
            if checkpoint is not None:
                checkpoints.append(checkpoint)
        return tuple(checkpoints)

    def _set_json(self, key: str, payload: Mapping[str, Any]) -> None:
        """把 JSON payload 写入 Redis，并按配置设置 TTL。"""

        text = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
        if self._ttl_seconds is None:
            self._client.set(key, text)
        else:
            self._client.set(key, text, ex=self._ttl_seconds)

    def _append_thread_index(self, thread_id: str, checkpoint_id: str) -> None:
        """维护 thread -> checkpointId 的最近列表索引。

        这里在 `LTRIM` 前先读取现有列表，是为了知道哪些旧 checkpointId 会被裁剪，从而同步删除对应 item key。
        对 Redis 高并发极限场景而言，读取、追加、裁剪不是一个 Lua 原子块；但 `INCR` 已经保证 sequence 不冲突，
        列表最终也会被裁剪到最近 N 个。后续如果要支撑大量 worker 对同一 thread 并发写入，可以把这段替换为 Lua。
        """

        thread_key = self._thread_key(thread_id)
        existing_ids = tuple(item for item in (_decode_text(raw) for raw in self._client.lrange(thread_key, 0, -1)) if item)
        self._client.rpush(thread_key, checkpoint_id)
        excess_count = max(0, len(existing_ids) + 1 - self._max_checkpoints_per_thread)
        for old_checkpoint_id in existing_ids[:excess_count]:
            self._client.delete(self._checkpoint_key(old_checkpoint_id))
        self._client.ltrim(thread_key, -self._max_checkpoints_per_thread, -1)
        self._expire_if_needed(thread_key)

    def _expire_if_needed(self, key: str) -> None:
        """按配置给索引或序号 key 设置 TTL。"""

        if self._ttl_seconds is not None:
            self._client.expire(key, self._ttl_seconds)

    def _checkpoint_key(self, checkpoint_id: str) -> str:
        """生成单个 checkpoint item key。"""

        return f"{self._key_prefix}:item:{_safe_key_part(checkpoint_id)}"

    def _thread_key(self, thread_id: str) -> str:
        """生成 thread checkpoint 列表 key。"""

        return f"{self._key_prefix}:thread:{_safe_key_part(thread_id)}"

    def _sequence_key(self, thread_id: str) -> str:
        """生成 thread sequence 计数器 key。"""

        return f"{self._key_prefix}:sequence:{_safe_key_part(thread_id)}"

    @staticmethod
    def _serialize_checkpoint(checkpoint: ToolActionExecutionCheckpoint) -> dict[str, Any]:
        """把 checkpoint 转换成 JSON 友好的低敏字典。"""

        return {
            "schemaVersion": CHECKPOINT_SCHEMA_VERSION,
            "checkpointId": checkpoint.checkpoint_id,
            "threadId": checkpoint.thread_id,
            "sequence": checkpoint.sequence,
            "savedAt": checkpoint.saved_at,
            "payloadPolicy": checkpoint.payload_policy,
            "source": checkpoint.source,
            "protocolFamily": checkpoint.protocol_family,
            "tenantId": checkpoint.tenant_id,
            "projectId": checkpoint.project_id,
            "actorId": checkpoint.actor_id,
            "requestId": checkpoint.request_id,
            "runId": checkpoint.run_id,
            "sessionId": checkpoint.session_id,
            "stepCount": checkpoint.step_count,
            "statusCounts": dict(checkpoint.status_counts),
            "resumeRequirements": list(checkpoint.resume_requirements),
            "graphRunSummary": dict(checkpoint.graph_run_summary),
        }

    @staticmethod
    def _deserialize_checkpoint(payload: Mapping[str, Any]) -> ToolActionExecutionCheckpoint:
        """把 Redis JSON payload 还原为 checkpoint 对象。

        反序列化时仍然只读取白名单字段；即使 Redis 被手工写入了额外字段，也不会透传到 API 响应。
        """

        return ToolActionExecutionCheckpoint(
            checkpoint_id=str(payload.get("checkpointId") or ""),
            thread_id=_text(payload.get("threadId")) or DEFAULT_CHECKPOINT_THREAD_ID,
            sequence=_positive_int(payload.get("sequence"), default=0),
            saved_at=str(payload.get("savedAt") or ""),
            payload_policy=_text(payload.get("payloadPolicy")) or CHECKPOINT_PAYLOAD_POLICY,
            source=_text(payload.get("source")),
            protocol_family=_text(payload.get("protocolFamily")),
            tenant_id=_text(payload.get("tenantId")),
            project_id=_text(payload.get("projectId")),
            actor_id=_text(payload.get("actorId")),
            request_id=_text(payload.get("requestId")),
            run_id=_text(payload.get("runId")),
            session_id=_text(payload.get("sessionId")),
            step_count=_int_or_zero(payload.get("stepCount")),
            status_counts=_int_mapping(payload.get("statusCounts")),
            resume_requirements=_string_tuple(payload.get("resumeRequirements")),
            graph_run_summary=dict(payload.get("graphRunSummary") if isinstance(payload.get("graphRunSummary"), Mapping) else {}),
        )


def _safe_key_part(value: Any) -> str:
    """把外部 ID 规范化为 Redis key 的安全片段。

    Redis key 理论上可以包含任意二进制字符，但生产排障、监控扫描和手工修复时更适合限制在可读字符集。
    这里保留字母、数字、冒号、下划线、点和短横线，其余字符统一替换为 `_`。
    """

    text = _text(value) or "unknown"
    return re.sub(r"[^A-Za-z0-9:_.-]", "_", text)


def _decode_text(value: Any) -> str | None:
    """兼容 redis-py bytes 返回值和测试 fake client 的 str 返回值。"""

    if isinstance(value, bytes):
        value = value.decode("utf-8")
    return _text(value)


def _decode_json(value: Any) -> Mapping[str, Any] | None:
    """读取并解析 Redis JSON 字符串。"""

    text = _decode_text(value)
    if not text:
        return None
    payload = json.loads(text)
    return payload if isinstance(payload, Mapping) else None


def _positive_int(value: Any, *, default: int) -> int:
    """把外部值解析为正整数；非法值回退默认值。"""

    try:
        parsed = int(value)
    except (TypeError, ValueError):
        return default
    return parsed if parsed > 0 else default
