"""记忆写入候选列表分页工具。

长期记忆候选未来会进入审批台、审计台和运维排障台。真实用户不会只看“最新 100 条”，
而是需要持续翻页、按状态筛选、定位历史候选。因此 API 需要尽早固定分页响应契约。

本文件只处理 API 层分页协议，不直接访问数据库：
- 当前 store 协议仍是最小 `list(...)`，所以这里先对已读取候选做游标切片；
- 游标使用 `createdAt + candidateId`，避免只用时间戳时同一秒/同一毫秒创建的候选排序不稳定；
- 游标用 urlsafe base64(JSON) 编码，便于前端原样传回，也避免把分页内部结构暴露成多个查询参数。

后续当 MySQL store 扩展原生 cursor 查询时，可以继续复用本文件的 cursor 编解码契约，
只是把“内存切片”下沉到 SQL `WHERE (created_at, candidate_id) < (?, ?)`。
"""

from __future__ import annotations

import base64
import json
from dataclasses import dataclass
from datetime import datetime
from typing import Any

from datasmart_ai_runtime.domain.memory import AgentMemoryWriteCandidate


@dataclass(frozen=True)
class MemoryWriteCandidateCursor:
    """候选列表分页游标。

    字段说明：
    - `created_at`：上一页最后一条候选的创建时间；
    - `candidate_id`：上一页最后一条候选 ID，用作同一时间下的稳定次级排序键。

    列表排序约定为 `createdAt DESC, candidateId DESC`。下一页查询应返回严格排在该游标之后的候选，
    这样即使新候选在翻页过程中插入列表顶部，也不会破坏用户继续向后翻页的体验。
    """

    created_at: datetime
    candidate_id: str


@dataclass(frozen=True)
class MemoryWriteCandidatePage:
    """候选列表分页结果。

    `items` 是本页候选；`next_cursor` 为下一页入口；`has_more` 方便前端直接决定是否显示“加载更多”。
    """

    items: tuple[AgentMemoryWriteCandidate, ...]
    next_cursor: str | None
    has_more: bool


def paginate_memory_write_candidates(
    candidates: tuple[AgentMemoryWriteCandidate, ...],
    *,
    limit: int,
    cursor: str | None = None,
) -> MemoryWriteCandidatePage:
    """按稳定游标切分页。

    参数说明：
    - `candidates`：已经按创建时间倒序读取出的候选集合；
    - `limit`：前端希望返回的页大小，实际会限制在 1-100，避免审批台一次拉取过多；
    - `cursor`：上一页返回的 nextCursor，为空表示第一页。

    当前实现会先把候选重新按 `(created_at, candidate_id)` 倒序排序，防止不同 store 返回顺序略有差异。
    """

    safe_limit = max(1, min(limit, 100))
    parsed_cursor = decode_memory_write_candidate_cursor(cursor) if cursor else None
    ordered = tuple(
        sorted(
            candidates,
            key=lambda item: (item.created_at, item.candidate_id),
            reverse=True,
        )
    )
    visible_items = tuple(item for item in ordered if _is_after_cursor(item, parsed_cursor))
    page_items = visible_items[:safe_limit]
    has_more = len(visible_items) > safe_limit
    next_cursor = encode_memory_write_candidate_cursor(page_items[-1]) if has_more and page_items else None
    return MemoryWriteCandidatePage(items=page_items, next_cursor=next_cursor, has_more=has_more)


def encode_memory_write_candidate_cursor(candidate: AgentMemoryWriteCandidate) -> str:
    """把候选位置编码成前端可传回的 cursor 字符串。"""

    payload = {
        "createdAt": candidate.created_at.isoformat(),
        "candidateId": candidate.candidate_id,
    }
    raw = json.dumps(payload, ensure_ascii=False, sort_keys=True).encode("utf-8")
    return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


def decode_memory_write_candidate_cursor(cursor: str) -> MemoryWriteCandidateCursor:
    """解析 cursor，并对非法输入给出可映射为 400 的错误。"""

    try:
        padded = cursor + "=" * (-len(cursor) % 4)
        payload: dict[str, Any] = json.loads(base64.urlsafe_b64decode(padded.encode("ascii")).decode("utf-8"))
        created_at = datetime.fromisoformat(str(payload["createdAt"]))
        candidate_id = str(payload["candidateId"]).strip()
    except Exception as exc:
        raise ValueError("cursor 格式无效，请使用上一页响应中的 pageInfo.nextCursor。") from exc
    if not candidate_id:
        raise ValueError("cursor 缺少 candidateId，无法稳定定位下一页。")
    return MemoryWriteCandidateCursor(created_at=created_at, candidate_id=candidate_id)


def _is_after_cursor(
    candidate: AgentMemoryWriteCandidate,
    cursor: MemoryWriteCandidateCursor | None,
) -> bool:
    """判断候选是否位于游标之后。

    因为排序是倒序，所以“之后”意味着：
    - 创建时间更早；
    - 或创建时间相同但 candidateId 字典序更小。
    """

    if cursor is None:
        return True
    return (candidate.created_at, candidate.candidate_id) < (cursor.created_at, cursor.candidate_id)
