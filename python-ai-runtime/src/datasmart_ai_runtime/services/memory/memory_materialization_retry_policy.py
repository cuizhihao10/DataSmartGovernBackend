"""长期记忆物化失败的自动重试与死信策略。

策略与 lease store 分离后，内存和 SQL 实现共享同一套指数退避语义。本模块只计算状态和时间，
不读取候选正文、不连接数据库，也不执行向量库或图谱写入。
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone


def decide_materialization_retry(
    *,
    attempt_count: int,
    now: datetime | None = None,
    max_attempts: int = 5,
    retry_base_seconds: int = 30,
    retry_max_seconds: int = 3600,
):
    """根据尝试次数决定进入冷却重试还是 DEAD_LETTER。"""

    from .memory_materialization_lease_store import (
        AgentMemoryMaterializationLeaseStatus,
        AgentMemoryMaterializationRetryDecision,
    )

    current_time = _utc(now)
    safe_attempts = max(1, max_attempts)
    safe_base = max(1, retry_base_seconds)
    safe_max = max(safe_base, retry_max_seconds)
    if attempt_count >= safe_attempts:
        return AgentMemoryMaterializationRetryDecision(
            status=AgentMemoryMaterializationLeaseStatus.DEAD_LETTER,
            next_retry_at=None,
            retry_delay_seconds=0,
            max_attempts=safe_attempts,
            dead_lettered=True,
        )
    retry_delay = min(safe_max, safe_base * (2 ** max(0, attempt_count - 1)))
    return AgentMemoryMaterializationRetryDecision(
        status=AgentMemoryMaterializationLeaseStatus.FAILED,
        next_retry_at=current_time + timedelta(seconds=retry_delay),
        retry_delay_seconds=retry_delay,
        max_attempts=safe_attempts,
        dead_lettered=False,
    )


def _utc(value: datetime | None) -> datetime:
    current = value or datetime.now(timezone.utc)
    return current.replace(tzinfo=timezone.utc) if current.tzinfo is None else current.astimezone(timezone.utc)
