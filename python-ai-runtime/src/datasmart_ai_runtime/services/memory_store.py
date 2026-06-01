"""Agent 正式长期记忆存储协议与内存实现。

长期记忆写入候选通过审批后，不能继续永远停留在候选表里。平台需要一个独立的正式记忆 store，
让后续 Agent 请求能够按租户、项目、会话和记忆类型安全检索已经沉淀的经验。

本文件刻意不直接绑定 Chroma、Neo4j、MySQL 或 MinIO：
- 语义记忆未来适合进入向量索引；
- 情节记忆适合进入关系型事件表或可搜索 checkpoint；
- 程序记忆适合进入 Skill/流程库；
- 资源记忆适合保存对象存储引用和受控摘要。

先固定 store 协议和内存实现，可以让 materializer、retriever、单元测试和后续持久化适配器共享同一条边界。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
from threading import RLock
from typing import Protocol

from datasmart_ai_runtime.domain.memory import AgentMemoryRecord, AgentMemoryScope, AgentMemoryType


@dataclass(frozen=True)
class AgentMemoryStoreEntry:
    """一条正式长期记忆的存储信封。

    `AgentMemoryRecord` 描述可以进入检索链路的业务内容；本信封补充持久化与治理元数据：
    - `namespace`：层级命名空间，未来可映射到 LangGraph store namespace、向量库 collection 或数据库分区键；
    - `idempotency_key`：防止审批回调、worker 重试或消息重复消费制造重复记忆；
    - `source_candidate_id`：保留从正式记忆回查审批候选的稳定锚点；
    - `expires_at`：为后续遗忘、归档和 TTL 清理任务提供统一时间边界。
    """

    memory: AgentMemoryRecord
    namespace: tuple[str, ...]
    idempotency_key: str
    source_candidate_id: str
    expires_at: datetime
    materialized_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))


@dataclass(frozen=True)
class AgentMemoryStoreWriteResult:
    """正式记忆幂等写入结果。

    `created=false` 不代表失败，而是说明相同候选或相同幂等键已经被 worker 成功处理过。
    调用方应把它视为可安全确认的重复消费结果。
    """

    entry: AgentMemoryStoreEntry
    created: bool


class AgentMemoryStore(Protocol):
    """正式长期记忆存储协议。

    生产实现必须在存储层保留 tenant/project/session 过滤能力，不能只依赖调用方在内存里二次筛选。
    向量数据库即使召回了高度相似内容，也必须先满足范围隔离，再允许进入模型上下文。
    """

    def save_if_absent(self, entry: AgentMemoryStoreEntry) -> AgentMemoryStoreWriteResult:
        """按幂等键保存正式记忆；重复写入返回已有记录。"""

    def get_by_candidate_id(self, candidate_id: str) -> AgentMemoryStoreEntry | None:
        """按审批候选 ID 反查正式记忆，用于补偿、审计和重复消费判断。"""

    def search(
        self,
        *,
        memory_type: AgentMemoryType,
        scope: AgentMemoryScope,
        tenant_id: str | None,
        project_id: str | None,
        session_id: str | None,
        limit: int = 100,
    ) -> tuple[AgentMemoryStoreEntry, ...]:
        """按治理范围读取一个有界候选窗口，供上层相关性排序。"""


class InMemoryAgentMemoryStore:
    """线程安全的正式记忆内存 store。

    该实现服务于本地学习、单元测试和第一阶段闭环验证，不是最终生产存储。它仍然严格执行范围过滤、
    幂等写入、过期排除和读取窗口限制，为未来 Chroma/MySQL/Neo4j 适配器固定安全语义。
    """

    def __init__(self) -> None:
        self._lock = RLock()
        self._entries_by_memory_id: dict[str, AgentMemoryStoreEntry] = {}
        self._memory_id_by_idempotency_key: dict[str, str] = {}
        self._memory_id_by_candidate_id: dict[str, str] = {}

    def save_if_absent(self, entry: AgentMemoryStoreEntry) -> AgentMemoryStoreWriteResult:
        """幂等保存正式记忆。

        如果 worker 因网络超时、进程恢复或消息重复投递再次处理同一个候选，直接返回首次写入结果。
        如果出现极低概率 memoryId 冲突但幂等键不同，则快速失败，避免静默覆盖另一条治理知识。
        """

        with self._lock:
            existing_id = self._memory_id_by_idempotency_key.get(entry.idempotency_key)
            if existing_id:
                return AgentMemoryStoreWriteResult(self._entries_by_memory_id[existing_id], created=False)
            if entry.memory.memory_id in self._entries_by_memory_id:
                raise ValueError(f"正式记忆 ID 冲突: {entry.memory.memory_id}")
            self._entries_by_memory_id[entry.memory.memory_id] = entry
            self._memory_id_by_idempotency_key[entry.idempotency_key] = entry.memory.memory_id
            self._memory_id_by_candidate_id[entry.source_candidate_id] = entry.memory.memory_id
            return AgentMemoryStoreWriteResult(entry, created=True)

    def get_by_candidate_id(self, candidate_id: str) -> AgentMemoryStoreEntry | None:
        """按候选 ID 查询已落成的正式记忆。"""

        with self._lock:
            memory_id = self._memory_id_by_candidate_id.get(candidate_id)
            return self._entries_by_memory_id.get(memory_id) if memory_id else None

    def search(
        self,
        *,
        memory_type: AgentMemoryType,
        scope: AgentMemoryScope,
        tenant_id: str | None,
        project_id: str | None,
        session_id: str | None,
        limit: int = 100,
    ) -> tuple[AgentMemoryStoreEntry, ...]:
        """按范围过滤正式记忆并排除过期记录。

        当前只做治理过滤和时间倒序，不做向量相似度。上层 retriever 会继续执行关键词排序；
        未来向量 store 可以把相关性搜索下沉，但不能删除这里表达的隔离约束。
        """

        safe_limit = max(1, min(limit, 500))
        now = datetime.now(timezone.utc)
        with self._lock:
            entries = sorted(
                self._entries_by_memory_id.values(),
                key=lambda item: item.materialized_at,
                reverse=True,
            )
            return tuple(
                entry
                for entry in entries
                if entry.expires_at > now
                and entry.memory.memory_type == memory_type
                and _scope_matches(entry.memory, scope, tenant_id, project_id, session_id)
            )[:safe_limit]


def _scope_matches(
    memory: AgentMemoryRecord,
    scope: AgentMemoryScope,
    tenant_id: str | None,
    project_id: str | None,
    session_id: str | None,
) -> bool:
    """执行正式记忆 store 的第一层范围隔离。"""

    if memory.scope != scope:
        return False
    if scope == AgentMemoryScope.SESSION:
        return memory.tenant_id == tenant_id and memory.project_id == project_id and memory.session_id == session_id
    if scope == AgentMemoryScope.PROJECT:
        return memory.tenant_id == tenant_id and memory.project_id == project_id
    if scope == AgentMemoryScope.TENANT:
        return memory.tenant_id == tenant_id
    return scope == AgentMemoryScope.GLOBAL
