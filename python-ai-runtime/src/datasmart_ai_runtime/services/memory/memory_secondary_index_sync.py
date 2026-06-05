"""长期记忆二级索引同步任务。

正式长期记忆 store 证明“这条低敏记忆已经可以被检索”，但并不等于 Chroma、Neo4j、MinIO 资源索引
等二级索引都已经同步成功。商业化 Agent 平台需要把这两个事实拆开：

- materializer 负责把 APPROVED 候选幂等落成正式记忆；
- secondary index sync 负责把正式记忆派发到 vector/graph/resource/keyword 等二级索引；
- retriever 根据二级索引可用性做路由和 fallback。

本模块先实现内存任务 store、调度器和 no-op worker，固定状态机、幂等 key、退避和诊断语义。
后续接 Chroma/Neo4j 时，只需要替换 `AgentMemorySecondaryIndexSyncAdapter`，不需要重写 materializer。
"""

from __future__ import annotations

from dataclasses import dataclass, field, replace
from datetime import datetime, timedelta, timezone
from enum import Enum
from threading import RLock
from typing import Protocol
from uuid import NAMESPACE_URL, uuid5

from datasmart_ai_runtime.domain.memory import AgentMemoryType
from datasmart_ai_runtime.services.memory.memory_secondary_index import (
    AgentMemorySecondaryIndexKind,
    AgentMemorySecondaryIndexRouter,
)
from datasmart_ai_runtime.services.memory.memory_store import AgentMemoryStoreEntry


class AgentMemorySecondaryIndexSyncAction(str, Enum):
    """二级索引同步动作。

    当前先落地 `UPSERT`，为正式记忆新增或幂等更新索引记录；同时预留 `DELETE/EXPIRE`，用于后续遗忘、
    归档、租户清退、敏感记忆撤回和索引重建。
    """

    UPSERT = "upsert"
    DELETE = "delete"
    EXPIRE = "expire"


class AgentMemorySecondaryIndexSyncStatus(str, Enum):
    """二级索引同步任务状态。"""

    PENDING = "pending"
    SYNCED = "synced"
    FAILED = "failed"
    DEAD_LETTER = "dead_letter"


@dataclass(frozen=True)
class AgentMemorySecondaryIndexSyncTask:
    """一条二级索引同步任务。

    任务只保存控制面字段和索引定位信息，不保存记忆正文。真实 adapter 需要正文时，应按 `memory_id`
    从正式 store 或受控资源服务读取，并重新执行 namespace 和敏感级别校验。
    """

    task_id: str
    memory_id: str
    source_candidate_id: str
    memory_type: AgentMemoryType
    index_kind: AgentMemorySecondaryIndexKind
    action: AgentMemorySecondaryIndexSyncAction
    tenant_id: str | None
    project_id: str | None
    session_id: str | None
    workspace_key: str
    memory_namespace: str
    status: AgentMemorySecondaryIndexSyncStatus = AgentMemorySecondaryIndexSyncStatus.PENDING
    attempt_count: int = 0
    next_retry_at: datetime | None = None
    last_error: str | None = None
    created_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    updated_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))

    def to_summary(self) -> dict[str, object]:
        """转换为低敏诊断摘要。"""

        return {
            "taskId": self.task_id,
            "memoryId": self.memory_id,
            "sourceCandidateId": self.source_candidate_id,
            "memoryType": self.memory_type.value,
            "indexKind": self.index_kind.value,
            "action": self.action.value,
            "tenantId": self.tenant_id,
            "projectId": self.project_id,
            "sessionId": self.session_id,
            "workspaceKey": self.workspace_key,
            "memoryNamespace": self.memory_namespace,
            "status": self.status.value,
            "attemptCount": self.attempt_count,
            "nextRetryAt": self.next_retry_at.isoformat() if self.next_retry_at else None,
            "lastError": self.last_error,
            "createdAt": self.created_at.isoformat(),
            "updatedAt": self.updated_at.isoformat(),
        }


@dataclass(frozen=True)
class AgentMemorySecondaryIndexSyncStoreSaveResult:
    """同步任务幂等保存结果。"""

    task: AgentMemorySecondaryIndexSyncTask
    created: bool


class AgentMemorySecondaryIndexSyncTaskStore(Protocol):
    """二级索引同步任务 store 协议。"""

    def save_if_absent(self, task: AgentMemorySecondaryIndexSyncTask) -> AgentMemorySecondaryIndexSyncStoreSaveResult:
        """按 taskId 幂等保存任务。"""

    def list_ready(
        self,
        *,
        limit: int = 50,
        now: datetime | None = None,
    ) -> tuple[AgentMemorySecondaryIndexSyncTask, ...]:
        """列出 ready 的 pending/failed 任务。"""

    def mark_synced(self, task_id: str) -> AgentMemorySecondaryIndexSyncTask:
        """标记任务同步成功。"""

    def mark_failed(
        self,
        task_id: str,
        *,
        error_message: str,
        max_attempts: int,
        retry_base_seconds: int,
        retry_max_seconds: int,
    ) -> AgentMemorySecondaryIndexSyncTask:
        """标记任务失败，并按退避策略计算下一次重试时间。"""


class InMemoryAgentMemorySecondaryIndexSyncTaskStore:
    """线程安全的二级索引同步任务内存 store。"""

    def __init__(self) -> None:
        self._lock = RLock()
        self._tasks_by_id: dict[str, AgentMemorySecondaryIndexSyncTask] = {}

    def save_if_absent(self, task: AgentMemorySecondaryIndexSyncTask) -> AgentMemorySecondaryIndexSyncStoreSaveResult:
        """幂等保存任务，防止 materializer 重试制造重复索引任务。"""

        with self._lock:
            current = self._tasks_by_id.get(task.task_id)
            if current is not None:
                return AgentMemorySecondaryIndexSyncStoreSaveResult(task=current, created=False)
            self._tasks_by_id[task.task_id] = task
            return AgentMemorySecondaryIndexSyncStoreSaveResult(task=task, created=True)

    def list_ready(
        self,
        *,
        limit: int = 50,
        now: datetime | None = None,
    ) -> tuple[AgentMemorySecondaryIndexSyncTask, ...]:
        """读取可执行任务窗口。"""

        safe_limit = max(1, min(limit, 100))
        current_time = now or datetime.now(timezone.utc)
        with self._lock:
            tasks = sorted(self._tasks_by_id.values(), key=lambda item: item.created_at)
            return tuple(
                task
                for task in tasks
                if task.status in {
                    AgentMemorySecondaryIndexSyncStatus.PENDING,
                    AgentMemorySecondaryIndexSyncStatus.FAILED,
                }
                and (task.next_retry_at is None or task.next_retry_at <= current_time)
            )[:safe_limit]

    def mark_synced(self, task_id: str) -> AgentMemorySecondaryIndexSyncTask:
        """标记同步成功。"""

        now = datetime.now(timezone.utc)
        with self._lock:
            current = self._required(task_id)
            updated = replace(
                current,
                status=AgentMemorySecondaryIndexSyncStatus.SYNCED,
                last_error=None,
                next_retry_at=None,
                updated_at=now,
            )
            self._tasks_by_id[task_id] = updated
            return updated

    def mark_failed(
        self,
        task_id: str,
        *,
        error_message: str,
        max_attempts: int,
        retry_base_seconds: int,
        retry_max_seconds: int,
    ) -> AgentMemorySecondaryIndexSyncTask:
        """标记任务失败或进入 DLQ。"""

        now = datetime.now(timezone.utc)
        with self._lock:
            current = self._required(task_id)
            next_attempt = current.attempt_count + 1
            dead_letter = next_attempt >= max(1, max_attempts)
            delay_seconds = min(max(1, retry_base_seconds) * (2 ** max(0, next_attempt - 1)), retry_max_seconds)
            updated = replace(
                current,
                status=(
                    AgentMemorySecondaryIndexSyncStatus.DEAD_LETTER
                    if dead_letter
                    else AgentMemorySecondaryIndexSyncStatus.FAILED
                ),
                attempt_count=next_attempt,
                next_retry_at=None if dead_letter else now + timedelta(seconds=delay_seconds),
                last_error=error_message[:1000],
                updated_at=now,
            )
            self._tasks_by_id[task_id] = updated
            return updated

    def _required(self, task_id: str) -> AgentMemorySecondaryIndexSyncTask:
        """读取必须存在的任务。"""

        task = self._tasks_by_id.get(task_id)
        if task is None:
            raise KeyError(f"二级索引同步任务不存在: {task_id}")
        return task


class AgentMemorySecondaryIndexSyncScheduler:
    """根据正式记忆落成结果创建二级索引同步任务。"""

    def __init__(
        self,
        *,
        store: AgentMemorySecondaryIndexSyncTaskStore,
        router: AgentMemorySecondaryIndexRouter | None = None,
    ) -> None:
        self._store = store
        self._router = router or AgentMemorySecondaryIndexRouter()

    def schedule_for_entry(
        self,
        entry: AgentMemoryStoreEntry,
        *,
        action: AgentMemorySecondaryIndexSyncAction = AgentMemorySecondaryIndexSyncAction.UPSERT,
    ) -> tuple[AgentMemorySecondaryIndexSyncStoreSaveResult, ...]:
        """为一条正式记忆创建需要同步的二级索引任务。

        当前调度策略会读取路由表中该记忆类型声明的索引顺序，并为每个索引生成任务。这样 semantic memory
        可以同时进入 vector 和 keyword fallback 索引；procedural memory 可以进入 graph 和 keyword。
        """

        index_kinds = self._router.DEFAULT_ROUTE.get(entry.memory.memory_type, (AgentMemorySecondaryIndexKind.KEYWORD,))
        return tuple(self._store.save_if_absent(self._task(entry, index_kind, action)) for index_kind in index_kinds)

    @staticmethod
    def _task(
        entry: AgentMemoryStoreEntry,
        index_kind: AgentMemorySecondaryIndexKind,
        action: AgentMemorySecondaryIndexSyncAction,
    ) -> AgentMemorySecondaryIndexSyncTask:
        """构造稳定 taskId。"""

        raw_id = f"{entry.memory.memory_id}|{index_kind.value}|{action.value}"
        return AgentMemorySecondaryIndexSyncTask(
            task_id=f"memory-secondary-index-sync-{uuid5(NAMESPACE_URL, raw_id)}",
            memory_id=entry.memory.memory_id,
            source_candidate_id=entry.source_candidate_id,
            memory_type=entry.memory.memory_type,
            index_kind=index_kind,
            action=action,
            tenant_id=entry.memory.tenant_id,
            project_id=entry.memory.project_id,
            session_id=entry.memory.session_id,
            workspace_key=entry.workspace_key,
            memory_namespace=entry.memory_namespace,
        )


@dataclass(frozen=True)
class AgentMemorySecondaryIndexSyncAdapterResult:
    """单个二级索引 adapter 同步结果。"""

    synced: bool
    message: str = "二级索引同步完成。"


class AgentMemorySecondaryIndexSyncAdapter(Protocol):
    """真实二级索引 adapter 协议。"""

    def sync(self, task: AgentMemorySecondaryIndexSyncTask) -> AgentMemorySecondaryIndexSyncAdapterResult:
        """同步一条任务到对应二级索引。"""


class NoopAgentMemorySecondaryIndexSyncAdapter:
    """默认 no-op adapter。

    本地学习环境不应因为没有 Chroma、Neo4j 或 MinIO 而无法启动。no-op adapter 用于验证任务状态机、
    批处理和诊断；生产环境必须按 indexKind 替换为真实 adapter。
    """

    def sync(self, task: AgentMemorySecondaryIndexSyncTask) -> AgentMemorySecondaryIndexSyncAdapterResult:
        """模拟同步成功。"""

        return AgentMemorySecondaryIndexSyncAdapterResult(
            synced=True,
            message=f"{task.index_kind.value} 二级索引使用 no-op adapter 标记为已同步。",
        )


@dataclass(frozen=True)
class AgentMemorySecondaryIndexSyncWorkerReport:
    """二级索引同步 worker 单轮报告。"""

    requested_limit: int
    scanned_count: int
    succeeded_count: int
    failed_count: int
    items: tuple[dict[str, object], ...]


class AgentMemorySecondaryIndexSyncWorker:
    """执行二级索引同步任务的有界 worker。"""

    def __init__(
        self,
        *,
        store: AgentMemorySecondaryIndexSyncTaskStore,
        adapters: dict[AgentMemorySecondaryIndexKind, AgentMemorySecondaryIndexSyncAdapter] | None = None,
        default_adapter: AgentMemorySecondaryIndexSyncAdapter | None = None,
        max_attempts: int = 5,
        retry_base_seconds: int = 30,
        retry_max_seconds: int = 3600,
    ) -> None:
        self._store = store
        self._adapters = adapters or {}
        self._default_adapter = default_adapter or NoopAgentMemorySecondaryIndexSyncAdapter()
        self._max_attempts = max(1, max_attempts)
        self._retry_base_seconds = max(1, retry_base_seconds)
        self._retry_max_seconds = max(self._retry_base_seconds, retry_max_seconds)

    def run_once(self, *, limit: int = 50) -> AgentMemorySecondaryIndexSyncWorkerReport:
        """执行一轮同步任务。"""

        tasks = self._store.list_ready(limit=limit)
        items: list[dict[str, object]] = []
        succeeded_count = 0
        failed_count = 0
        for task in tasks:
            adapter = self._adapters.get(task.index_kind, self._default_adapter)
            try:
                result = adapter.sync(task)
                if not result.synced:
                    raise RuntimeError(result.message)
                synced = self._store.mark_synced(task.task_id)
                succeeded_count += 1
                items.append({**synced.to_summary(), "message": result.message})
            except Exception as exc:
                failed = self._store.mark_failed(
                    task.task_id,
                    error_message=f"{type(exc).__name__}: {exc}",
                    max_attempts=self._max_attempts,
                    retry_base_seconds=self._retry_base_seconds,
                    retry_max_seconds=self._retry_max_seconds,
                )
                failed_count += 1
                items.append(failed.to_summary())
        return AgentMemorySecondaryIndexSyncWorkerReport(
            requested_limit=limit,
            scanned_count=len(tasks),
            succeeded_count=succeeded_count,
            failed_count=failed_count,
            items=tuple(items),
        )


def secondary_index_sync_diagnostics(store: AgentMemorySecondaryIndexSyncTaskStore) -> dict[str, object]:
    """生成二级索引同步任务诊断。

    该诊断只统计 ready 窗口，不扫描所有历史任务，避免未来持久化 store 在管理页产生全表压力。
    """

    ready = store.list_ready(limit=100)
    return {
        "component": "memory-secondary-index-sync",
        "readyTaskCount": len(ready),
        "readyByIndexKind": _count_by(ready, "index_kind"),
        "readyByAction": _count_by(ready, "action"),
        "notes": (
            "当前诊断只统计 ready 窗口；生产环境应增加持久化 store、低基数指标和管理员重排入口。",
            "同步任务不保存记忆正文，真实 adapter 需要按 memoryId 回查正式 store 并重新执行 namespace 校验。",
        ),
    }


def _count_by(tasks: tuple[AgentMemorySecondaryIndexSyncTask, ...], field_name: str) -> dict[str, int]:
    """按枚举字段统计任务数量。"""

    counts: dict[str, int] = {}
    for task in tasks:
        value = getattr(task, field_name)
        key = value.value if hasattr(value, "value") else str(value)
        counts[key] = counts.get(key, 0) + 1
    return counts
