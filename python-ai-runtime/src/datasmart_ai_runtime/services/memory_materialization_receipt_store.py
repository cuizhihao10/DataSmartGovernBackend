"""长期记忆正式落成 receipt 存储协议与内存实现。

`AgentMemoryWriteCandidate` 只回答“某条工具结果摘要是否允许进入长期记忆写入流程”。
它不应该继续承载 worker 执行事实，否则候选状态机会被迫混入 `MATERIALIZING`、`FAILED`、
`RETRYING`、`MATERIALIZED` 等执行态，最终让审批台、补偿台、监控台读同一个字段却表达不同含义。

本文件引入 materialization receipt：
- receipt 是“后台写入尝试”的证据；
- candidate 是“审批与治理决策”的证据；
- formal memory store 是“模型可检索记忆已经存在”的证据。

把三者拆开后，未来可以自然扩展 Kafka consumer、outbox/inbox、失败重试、DLQ、管理员补偿、
指标采集和审计导出，而不必推翻候选审批状态机。
"""

from __future__ import annotations

from dataclasses import dataclass, field, replace
from datetime import datetime, timezone
from enum import Enum
from threading import RLock
from typing import Protocol
from uuid import NAMESPACE_URL, uuid5


class AgentMemoryMaterializationReceiptStatus(str, Enum):
    """长期记忆落成 receipt 生命周期。

    状态语义：
    - `STARTED`：worker 已领取或同步调用已经开始处理该候选；
    - `SUCCEEDED`：正式记忆 store 已确认写入或幂等复用已有记录；
    - `FAILED`：本次尝试失败，错误摘要已记录，后续可由补偿任务或管理员重新触发。

    这里暂不加入 `RETRYING`，因为重试本质上是下一次 `STARTED` 尝试或同一 receipt 的
    attempt_count 递增。先保持状态机小而清晰，有利于测试和后续 SQL 迁移稳定。
    """

    STARTED = "started"
    SUCCEEDED = "succeeded"
    FAILED = "failed"


@dataclass(frozen=True)
class AgentMemoryMaterializationReceipt:
    """一条长期记忆落成尝试的低敏执行证据。

    字段说明：
    - `receipt_id`：稳定 receipt ID，当前按 candidateId 生成，后续多批次/多 outbox 时可扩展批次维度；
    - `candidate_id`：关联候选审批事实；
    - `tenant_id/project_id/workspace_key/memory_namespace`：执行范围证据，用于审计、补偿和指标分组；
    - `status`：本次落成执行状态，独立于候选 approval status；
    - `attempt_count`：同一 receipt 被重复处理的次数，用于发现 worker 抖动或外部 store 不稳定；
    - `worker_id`：处理者标识，本地同步调用可以是 `python-ai-runtime-inline`，Kafka worker 可填实例 ID；
    - `memory_id/namespace`：成功后回填正式记忆定位信息；
    - `error_message`：失败摘要，只保存异常类型和短消息，不写 prompt、SQL、样本数据或原始工具输出。
    """

    receipt_id: str
    candidate_id: str
    tenant_id: str
    project_id: str
    workspace_key: str
    memory_namespace: str
    status: AgentMemoryMaterializationReceiptStatus
    attempt_count: int = 1
    worker_id: str = "python-ai-runtime-inline"
    memory_id: str | None = None
    namespace: tuple[str, ...] = ()
    outcome: str | None = None
    message: str | None = None
    error_message: str | None = None
    started_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    finished_at: datetime | None = None
    updated_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))

    def to_summary(self) -> dict[str, object]:
        """转换为 API、测试和诊断面板可读取的低敏摘要。"""

        return {
            "receiptId": self.receipt_id,
            "candidateId": self.candidate_id,
            "tenantId": self.tenant_id,
            "projectId": self.project_id,
            "workspaceKey": self.workspace_key,
            "memoryNamespace": self.memory_namespace,
            "status": self.status.value,
            "attemptCount": self.attempt_count,
            "workerId": self.worker_id,
            "memoryId": self.memory_id,
            "namespace": self.namespace,
            "outcome": self.outcome,
            "message": self.message,
            "errorMessage": self.error_message,
            "startedAt": self.started_at.isoformat(),
            "finishedAt": self.finished_at.isoformat() if self.finished_at else None,
            "updatedAt": self.updated_at.isoformat(),
        }


class AgentMemoryMaterializationReceiptStore(Protocol):
    """长期记忆落成 receipt 存储协议。"""

    def begin(
        self,
        *,
        candidate_id: str,
        tenant_id: str,
        project_id: str,
        workspace_key: str,
        memory_namespace: str,
        worker_id: str,
    ) -> AgentMemoryMaterializationReceipt:
        """记录一次落成尝试开始，并返回当前 receipt 快照。"""

    def succeed(
        self,
        *,
        receipt_id: str,
        memory_id: str,
        namespace: tuple[str, ...],
        outcome: str,
        message: str,
    ) -> AgentMemoryMaterializationReceipt:
        """记录落成成功或幂等复用已有正式记忆。"""

    def fail(self, *, receipt_id: str, error_message: str) -> AgentMemoryMaterializationReceipt:
        """记录落成失败摘要。"""

    def get_by_candidate_id(self, candidate_id: str) -> AgentMemoryMaterializationReceipt | None:
        """按候选 ID 查询最近 receipt，供补偿台和测试断言使用。"""


class InMemoryAgentMemoryMaterializationReceiptStore:
    """线程安全的内存 receipt store。

    该实现不是生产最终形态，但它刻意保留生产语义：
    - begin 使用稳定 receiptId，让重复 worker 调用不会无限制造 receipt；
    - 重复 begin 会递增 attempt_count，并把状态重新置为 STARTED；
    - succeed/fail 会覆盖同一 receipt 的最终执行结果，便于补偿任务反复处理。

    生产 MySQL 实现应在 `receipt_id` 上做唯一索引，并把 begin/succeed/fail 放入短事务。
    如果未来改成 outbox/inbox，receipt 仍然可以作为 worker 处理结果表，而不是替代消息队列。
    """

    def __init__(self) -> None:
        self._lock = RLock()
        self._receipts_by_id: dict[str, AgentMemoryMaterializationReceipt] = {}
        self._receipt_id_by_candidate_id: dict[str, str] = {}

    def begin(
        self,
        *,
        candidate_id: str,
        tenant_id: str,
        project_id: str,
        workspace_key: str,
        memory_namespace: str,
        worker_id: str,
    ) -> AgentMemoryMaterializationReceipt:
        """记录处理开始。

        重复 begin 不报错，而是作为新一次尝试处理。这符合 worker 至少一次投递语义：
        同一候选可能因为超时、进程重启、Kafka rebalance 或管理员补偿而被再次处理。
        """

        now = datetime.now(timezone.utc)
        receipt_id = self._receipt_id(candidate_id)
        with self._lock:
            current = self._receipts_by_id.get(receipt_id)
            if current is None:
                receipt = AgentMemoryMaterializationReceipt(
                    receipt_id=receipt_id,
                    candidate_id=candidate_id,
                    tenant_id=tenant_id,
                    project_id=project_id,
                    workspace_key=workspace_key,
                    memory_namespace=memory_namespace,
                    status=AgentMemoryMaterializationReceiptStatus.STARTED,
                    worker_id=worker_id,
                    started_at=now,
                    updated_at=now,
                )
            else:
                receipt = replace(
                    current,
                    tenant_id=tenant_id,
                    project_id=project_id,
                    workspace_key=workspace_key,
                    memory_namespace=memory_namespace,
                    status=AgentMemoryMaterializationReceiptStatus.STARTED,
                    attempt_count=current.attempt_count + 1,
                    worker_id=worker_id,
                    error_message=None,
                    started_at=now,
                    finished_at=None,
                    updated_at=now,
                )
            self._receipts_by_id[receipt_id] = receipt
            self._receipt_id_by_candidate_id[candidate_id] = receipt_id
            return receipt

    def succeed(
        self,
        *,
        receipt_id: str,
        memory_id: str,
        namespace: tuple[str, ...],
        outcome: str,
        message: str,
    ) -> AgentMemoryMaterializationReceipt:
        """把 receipt 标记为成功。"""

        now = datetime.now(timezone.utc)
        with self._lock:
            current = self._required(receipt_id)
            receipt = replace(
                current,
                status=AgentMemoryMaterializationReceiptStatus.SUCCEEDED,
                memory_id=memory_id,
                namespace=namespace,
                outcome=outcome,
                message=message,
                error_message=None,
                finished_at=now,
                updated_at=now,
            )
            self._receipts_by_id[receipt_id] = receipt
            return receipt

    def fail(self, *, receipt_id: str, error_message: str) -> AgentMemoryMaterializationReceipt:
        """把 receipt 标记为失败，并保留短错误摘要。"""

        now = datetime.now(timezone.utc)
        with self._lock:
            current = self._required(receipt_id)
            receipt = replace(
                current,
                status=AgentMemoryMaterializationReceiptStatus.FAILED,
                error_message=error_message[:1000],
                finished_at=now,
                updated_at=now,
            )
            self._receipts_by_id[receipt_id] = receipt
            return receipt

    def get_by_candidate_id(self, candidate_id: str) -> AgentMemoryMaterializationReceipt | None:
        """按候选 ID 查询 receipt。"""

        with self._lock:
            receipt_id = self._receipt_id_by_candidate_id.get(candidate_id)
            return self._receipts_by_id.get(receipt_id) if receipt_id else None

    def _required(self, receipt_id: str) -> AgentMemoryMaterializationReceipt:
        """读取必然存在的 receipt，避免 succeed/fail 对未知 ID 静默成功。"""

        receipt = self._receipts_by_id.get(receipt_id)
        if receipt is None:
            raise KeyError(f"长期记忆落成 receipt 不存在: {receipt_id}")
        return receipt

    @staticmethod
    def _receipt_id(candidate_id: str) -> str:
        """生成稳定 receipt ID。

        当前按 candidateId 一对一生成 receipt，后续如果需要按批次或 worker run 记录多条尝试，
        可以把 batchId/outboxMessageId 加入 UUID5 输入，同时保留 candidateId 查询索引。
        """

        return f"memory-materialization-{uuid5(NAMESPACE_URL, candidate_id)}"
