"""Agent 记忆写入候选存储协议与内存实现。

上一个阶段已经有了记忆写入候选和审批状态机，但候选暂时保存在
`AgentMemoryWriteGovernanceService` 内部字典中。这样虽然能验证领域模型，却不利于后续扩展：
API 路由、审批中心、异步写入 worker、审计回放都需要通过同一个存储边界读取候选。

本文件先定义一个很小的 store 协议，并提供线程安全的内存实现。它不是最终生产存储，
而是为了把“候选生命周期”和“候选保存介质”解耦。未来替换成 MySQL、Redis、Java
memory-service 或审批中心客户端时，只需要实现同一组方法，不必推翻治理服务。
"""

from __future__ import annotations

from dataclasses import replace
from threading import RLock
from typing import Protocol

from datasmart_ai_runtime.domain.memory import (
    AgentMemoryWriteCandidate,
    AgentMemoryWriteCandidateStatus,
)


class AgentMemoryWriteCandidateStore(Protocol):
    """记忆写入候选存储协议。

    方法说明：
    - `save`：保存或覆盖一条候选。当前候选 ID 使用稳定 UUID5，重复生成同一候选时允许覆盖；
    - `get`：按 ID 查询候选，用于详情页、审批操作和异步 worker；
    - `list`：按租户、项目、状态做轻量过滤，用于审批台和调试页面；
    - `update`：条件化更新候选，避免审批时直接绕过读取和状态检查。

    这里暂不设计分页游标、排序字段和复杂搜索，是为了先固定最小闭环。真实生产 API
    后续应增加 `createdAt` 倒序、分页 cursor、状态/类型/scope 组合筛选和数据权限条件。
    """

    def save(self, candidate: AgentMemoryWriteCandidate) -> AgentMemoryWriteCandidate:
        """保存候选并返回最终存储对象。"""

    def get(self, candidate_id: str) -> AgentMemoryWriteCandidate | None:
        """按候选 ID 查询。"""

    def list(
        self,
        *,
        tenant_id: str | None = None,
        project_id: str | None = None,
        status: AgentMemoryWriteCandidateStatus | None = None,
        limit: int = 100,
    ) -> tuple[AgentMemoryWriteCandidate, ...]:
        """查询候选列表。"""

    def update(self, candidate: AgentMemoryWriteCandidate) -> AgentMemoryWriteCandidate:
        """更新候选并返回最终存储对象。"""


class InMemoryAgentMemoryWriteCandidateStore:
    """线程安全的内存候选存储。

    该实现适合单进程本地开发、单元测试和最小 API 联调。它使用 `RLock` 保护字典读写，
    避免 FastAPI 多线程测试或未来本地并发请求时出现半写入状态。

    当前边界：
    - 进程重启后数据丢失；
    - 多实例之间不共享；
    - 没有分页游标和持久化审计；
    - 不做租户级加密。

    这些限制是刻意保留的，因为现阶段目标是固定 DataSmart 的领域契约。生产化时应替换为
    MySQL 表或 Java 控制面服务，并补充行级权限、操作审计和幂等版本号。
    """

    def __init__(self) -> None:
        self._lock = RLock()
        self._candidates: dict[str, AgentMemoryWriteCandidate] = {}

    def save(self, candidate: AgentMemoryWriteCandidate) -> AgentMemoryWriteCandidate:
        """保存候选。

        内存实现保存的是不可变 dataclass 对象本身；后续如果换成数据库实现，应把枚举、时间、
        tuple 和 attributes 序列化为表字段或 JSON 字段。
        """

        with self._lock:
            self._candidates[candidate.candidate_id] = candidate
            return candidate

    def get(self, candidate_id: str) -> AgentMemoryWriteCandidate | None:
        """查询单条候选。"""

        with self._lock:
            return self._candidates.get(candidate_id)

    def list(
        self,
        *,
        tenant_id: str | None = None,
        project_id: str | None = None,
        status: AgentMemoryWriteCandidateStatus | None = None,
        limit: int = 100,
    ) -> tuple[AgentMemoryWriteCandidate, ...]:
        """按常用审批台条件查询候选。

        过滤顺序先做租户/项目隔离，再做状态过滤。即使是内存实现，也保持这个顺序，
        是为了让后续数据库 SQL 的设计自然继承“先数据范围、后业务筛选”的安全习惯。
        """

        safe_limit = max(1, min(limit, 500))
        with self._lock:
            items = sorted(
                self._candidates.values(),
                key=lambda candidate: candidate.created_at,
                reverse=True,
            )
            filtered: list[AgentMemoryWriteCandidate] = []
            for candidate in items:
                if tenant_id and candidate.tenant_id != tenant_id:
                    continue
                if project_id and candidate.project_id != project_id:
                    continue
                if status and candidate.status != status:
                    continue
                filtered.append(candidate)
                if len(filtered) >= safe_limit:
                    break
            return tuple(filtered)

    def update(self, candidate: AgentMemoryWriteCandidate) -> AgentMemoryWriteCandidate:
        """更新候选。

        如果候选不存在则抛出 `KeyError`，避免审批接口对一个不存在的候选产生“看似成功”的响应。
        """

        with self._lock:
            if candidate.candidate_id not in self._candidates:
                raise KeyError(f"记忆写入候选不存在: {candidate.candidate_id}")
            # replace 本身不是必须的，但这里显式复制一份，表达更新后的对象是新的不可变快照。
            stored = replace(candidate)
            self._candidates[stored.candidate_id] = stored
            return stored
