"""长期记忆物化 lease 的 SQL 行映射。

映射器不持有连接、不提交事务，也不执行 SQL，让 SQL store 专注 CAS/fencing 与事务语义。
"""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Any

from .memory_materialization_lease_store import (
    AgentMemoryMaterializationLease,
    AgentMemoryMaterializationLeaseStatus,
)

LEASE_COLUMNS = (
    "lease_id,candidate_id,tenant_id,project_id,workspace_key,memory_namespace,status,attempt_count,"
    "worker_id,lease_token,leased_until,next_retry_at,memory_id,outcome,message,error_message,"
    "started_at,finished_at,update_time"
)


def lease_from_row(row: Any) -> AgentMemoryMaterializationLease:
    """把字典行或位置行还原为 UTC aware lease。"""

    values = dict(row) if hasattr(row, "keys") else dict(zip(LEASE_COLUMNS.split(","), row))
    now = datetime.now(timezone.utc)
    return AgentMemoryMaterializationLease(
        lease_id=values["lease_id"],
        candidate_id=values["candidate_id"],
        tenant_id=values["tenant_id"],
        project_id=values["project_id"],
        workspace_key=values["workspace_key"],
        memory_namespace=values["memory_namespace"],
        status=AgentMemoryMaterializationLeaseStatus(values["status"]),
        attempt_count=int(values["attempt_count"]),
        worker_id=values["worker_id"],
        lease_token=values["lease_token"],
        leased_until=_parse_datetime(values["leased_until"]) or now,
        next_retry_at=_parse_datetime(values["next_retry_at"]),
        memory_id=values["memory_id"],
        outcome=values["outcome"],
        message=values["message"],
        error_message=values["error_message"],
        started_at=_parse_datetime(values["started_at"]) or now,
        finished_at=_parse_datetime(values["finished_at"]),
        updated_at=_parse_datetime(values["update_time"]) or now,
    )


def _parse_datetime(value: str | datetime | None) -> datetime | None:
    if value is None:
        return None
    parsed = value if isinstance(value, datetime) else datetime.fromisoformat(value)
    return parsed.replace(tzinfo=timezone.utc) if parsed.tzinfo is None else parsed.astimezone(timezone.utc)
