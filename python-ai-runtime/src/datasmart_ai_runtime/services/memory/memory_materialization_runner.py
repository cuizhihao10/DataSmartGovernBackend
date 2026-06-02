"""Agent 长期记忆 APPROVED 候选落成 Runner。

本文件位于长期记忆能力域的“执行层”：
- `memory_write_candidate_store` 保存“是否允许写入长期记忆”的治理候选；
- `memory_write_materializer` 负责把单条 APPROVED 候选幂等写入正式长期记忆 store；
- 本 Runner 负责把一批候选串起来执行，并把每条执行结果汇总成低敏批次报告。

为什么不直接继续扩展 `AgentApprovedMemoryWriteMaterializer.materialize_approved()`？
原因是生产型 agent 平台中的后台动作必须具备“单条失败不拖垮整批”的执行语义。长期记忆写入属于有副作用动作，
未来会接 Kafka/outbox worker、失败退避、DLQ、管理员补偿重放和 Prometheus 指标。如果批量方法遇到异常就
整体中断，那么一个坏候选会长期阻塞同一窗口中的其他已审批记忆，最终表现为 agent “明明审批通过却记不住”。

当前 Runner 已接入可替换 lease store，但暂不内置线程、调度器、失败退避或 DLQ。它可以被：
- 本地 CLI 或测试直接调用；
- FastAPI 管理路由显式触发；
- 未来后台 worker 的单轮处理函数复用；
- Java task-management 通过 HTTP/gRPC/Kafka 触发后作为 Python Runtime 内部执行器复用。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
from enum import Enum
from typing import Any

from datasmart_ai_runtime.domain.memory import AgentMemoryWriteCandidateStatus
from datasmart_ai_runtime.services.memory.memory_materialization_lease_store import (
    AgentMemoryMaterializationLease,
    AgentMemoryMaterializationLeaseStatus,
    AgentMemoryMaterializationLeaseStore,
    InMemoryAgentMemoryMaterializationLeaseStore,
)
from datasmart_ai_runtime.services.memory.memory_write_candidate_store import AgentMemoryWriteCandidateStore
from datasmart_ai_runtime.services.memory.memory_write_materializer import (
    AgentApprovedMemoryWriteMaterializer,
    AgentMemoryMaterializationResult,
)


class AgentMemoryMaterializationRunnerItemStatus(str, Enum):
    """Runner 单条处理结果状态。

    `SUCCEEDED` 和 materializer 的 `MATERIALIZED/ALREADY_MATERIALIZED` 不是同一层含义：
    - Runner 只关心“本轮处理是否成功完成”；
    - materializer outcome 关心“正式记忆 store 是新建还是幂等复用”。

    保留这一层区分后，监控系统可以同时回答两个问题：
    1. 本轮 worker 是否健康；
    2. 本轮到底新增了多少记忆、复用了多少记忆。
    """

    SUCCEEDED = "succeeded"
    FAILED = "failed"


@dataclass(frozen=True)
class AgentMemoryMaterializationRunnerItem:
    """Runner 对单条候选的低敏执行摘要。

    字段说明：
    - `candidate_id`：被处理的候选 ID，是补偿、审计和问题定位的主锚点；
    - `status`：本轮处理是否成功，不等同于候选审批状态；
    - `outcome`：materializer 返回的正式写入结果，失败时为空；
    - `memory_id`：成功后对应的正式记忆 ID，失败时为空；
    - `message/error_message`：给运维、管理员和测试阅读的短信息，不能写入 prompt、SQL、样本数据或原始工具输出；
    - `attributes`：低敏机器可读扩展字段，方便后续指标、管理后台和批次事件复用。
    """

    candidate_id: str
    status: AgentMemoryMaterializationRunnerItemStatus
    outcome: str | None = None
    memory_id: str | None = None
    message: str = ""
    error_message: str | None = None
    attributes: dict[str, Any] = field(default_factory=dict)

    def to_summary(self) -> dict[str, Any]:
        """转换为可直接用于 API 响应、日志或 runtime event 的低敏摘要。

        这里刻意不返回候选正文、正式记忆正文、审批理由全文或异常堆栈。长期记忆本身会影响后续模型上下文，
        因此任何批次报告都必须默认遵守“控制面信息可见、数据面内容不可扩散”的原则。
        """

        return {
            "candidateId": self.candidate_id,
            "status": self.status.value,
            "outcome": self.outcome,
            "memoryId": self.memory_id,
            "message": self.message,
            "errorMessage": self.error_message,
            "attributes": dict(self.attributes),
        }


@dataclass(frozen=True)
class AgentMemoryMaterializationRunnerReport:
    """Runner 单轮批次报告。

    该报告是后续商业化运维能力的基础数据结构。即使当前只在测试中使用，也提前保留生产需要的字段：
    - `requested_limit`：调用方希望处理多少条；
    - `scanned_count`：本轮实际扫描到多少条 APPROVED 候选；
    - `succeeded_count/failed_count`：本轮执行健康度；
    - `skipped_count`：当前记录 lease 未领取成功，未来还可扩展冷却期、DLQ、限流或权限策略跳过；
    - `worker_id`：未来多实例 worker 排障时用于定位具体处理者；
    - `started_at/finished_at`：用于计算批次耗时、吞吐和告警阈值。
    """

    requested_limit: int
    scanned_count: int
    succeeded_count: int
    failed_count: int
    skipped_count: int
    items: tuple[AgentMemoryMaterializationRunnerItem, ...]
    worker_id: str
    started_at: datetime
    finished_at: datetime
    attributes: dict[str, Any] = field(default_factory=dict)

    def to_summary(self) -> dict[str, Any]:
        """转换为管理接口或事件总线可使用的摘要结构。"""

        return {
            "requestedLimit": self.requested_limit,
            "scannedCount": self.scanned_count,
            "succeededCount": self.succeeded_count,
            "failedCount": self.failed_count,
            "skippedCount": self.skipped_count,
            "workerId": self.worker_id,
            "startedAt": self.started_at.isoformat(),
            "finishedAt": self.finished_at.isoformat(),
            "items": tuple(item.to_summary() for item in self.items),
            "attributes": dict(self.attributes),
        }


class AgentMemoryMaterializationRunner:
    """执行一个有界窗口内 APPROVED 长期记忆候选的落成动作。

    设计原则：
    1. 有界扫描：每轮最多处理固定窗口，避免 Python Runtime 因一次补偿扫描占满 CPU 或数据库连接；
    2. 至少一次：同一候选可能被重复处理，正式 store 和 receipt store 负责幂等确认；
    3. 失败隔离：单条候选失败只写入该条失败摘要，不阻塞同批其他候选；
    4. 低敏报告：Runner 报告只携带控制面 ID、状态和短错误，不泄露候选正文或工具原始输出；
    5. 租约抢占：执行前先领取候选，执行后按 token 完成或失败，避免多实例同时处理同一条候选；
    6. 扫描放大：如果窗口前部候选被其他实例持有，继续向后扫描，减少可运行候选饥饿；
    7. 失败退避：失败候选不会立即热循环，达到最大尝试次数后进入 DLQ 等待管理员补偿。

    这类 Runner 是“类 Codex/Claude Code agent”走向生产化时非常关键的一块：agent 不能只会计划和调用工具，
    还要能把经过治理的经验可靠沉淀，并在失败后可补偿、可解释、可审计。
    """

    MAX_BATCH_SIZE = 100
    SCAN_WINDOW_MULTIPLIER = 2
    MAX_SCAN_WINDOW_SIZE = 500

    def __init__(
        self,
        *,
        candidate_store: AgentMemoryWriteCandidateStore,
        materializer: AgentApprovedMemoryWriteMaterializer,
        lease_store: AgentMemoryMaterializationLeaseStore | None = None,
        worker_id: str = "python-ai-runtime-memory-runner",
        lease_seconds: int = 60,
        max_attempts: int = 5,
        retry_base_seconds: int = 30,
        retry_max_seconds: int = 3600,
    ) -> None:
        self._candidate_store = candidate_store
        self._materializer = materializer
        self._lease_store = lease_store or InMemoryAgentMemoryMaterializationLeaseStore()
        self._worker_id = worker_id
        self._lease_seconds = max(1, lease_seconds)
        self._max_attempts = max(1, max_attempts)
        self._retry_base_seconds = max(1, retry_base_seconds)
        self._retry_max_seconds = max(self._retry_base_seconds, retry_max_seconds)

    def run_once(self, *, limit: int = 50) -> AgentMemoryMaterializationRunnerReport:
        """执行一轮 APPROVED 候选落成。

        参数：
        - `limit`：调用方希望本轮最多处理的候选数。Runner 会把它裁剪到 `[1, MAX_BATCH_SIZE]`，
          这是为了防止管理接口误传极大值造成一次性扫描过多数据。

        返回：
        - `AgentMemoryMaterializationRunnerReport`：包含本轮扫描数、成功数、失败数和每条低敏结果。

        副作用：
        - 对成功候选，materializer 会写入正式长期记忆 store，并把 receipt 标记为 succeeded；
        - 对失败候选，materializer 通常会把 receipt 标记为 failed；如果失败发生在 receipt begin 之前
          （例如候选缺失 workspace 证据），Runner 仍会在批次报告中记录失败项，便于后续人工排查。

        注意：当前 Runner 不修改候选审批状态。APPROVED 代表“允许进入落成流程”，不代表“已经落成”。
        是否已落成由 formal memory store 和 receipt store 证明。这样可以避免候选状态机同时承担审批事实和执行事实。
        """

        started_at = datetime.now(timezone.utc)
        safe_limit = max(1, min(limit, self.MAX_BATCH_SIZE))
        scan_window_limit = min(safe_limit * self.SCAN_WINDOW_MULTIPLIER, self.MAX_SCAN_WINDOW_SIZE)
        candidates = self._candidate_store.list(
            status=AgentMemoryWriteCandidateStatus.APPROVED,
            limit=scan_window_limit,
        )

        items: list[AgentMemoryMaterializationRunnerItem] = []
        skipped_count = 0
        skipped_reasons: dict[str, int] = {}
        scanned_count = 0
        for candidate in candidates:
            if len(items) >= safe_limit:
                break
            scanned_count += 1
            lease = self._lease_store.try_acquire(
                candidate_id=candidate.candidate_id,
                tenant_id=candidate.tenant_id,
                project_id=candidate.project_id,
                workspace_key=candidate.workspace_key or "",
                memory_namespace=candidate.memory_namespace or "",
                worker_id=self._worker_id,
                lease_seconds=self._lease_seconds,
            )
            if lease is None:
                skipped_count += 1
                skip_reason = self._skip_reason(candidate.candidate_id)
                skipped_reasons[skip_reason] = skipped_reasons.get(skip_reason, 0) + 1
                continue
            items.append(self._materialize_candidate(candidate.candidate_id, lease))

        succeeded_count = sum(1 for item in items if item.status == AgentMemoryMaterializationRunnerItemStatus.SUCCEEDED)
        failed_count = sum(1 for item in items if item.status == AgentMemoryMaterializationRunnerItemStatus.FAILED)
        dead_letter_count = sum(1 for item in items if item.attributes.get("leaseStatus") == "dead_letter")
        finished_at = datetime.now(timezone.utc)
        return AgentMemoryMaterializationRunnerReport(
            requested_limit=limit,
            scanned_count=scanned_count,
            succeeded_count=succeeded_count,
            failed_count=failed_count,
            skipped_count=skipped_count,
            items=tuple(items),
            worker_id=self._worker_id,
            started_at=started_at,
            finished_at=finished_at,
            attributes={
                "safeLimit": safe_limit,
                "scanWindowLimit": scan_window_limit,
                "claimedCount": len(items),
                "deadLetterCount": dead_letter_count,
                "skippedReasons": skipped_reasons,
                "scanStatus": AgentMemoryWriteCandidateStatus.APPROVED.value,
                "executionPolicy": "BOUNDED_AT_LEAST_ONCE_WITH_LEASE_TOKEN_FENCING",
                "leaseSeconds": self._lease_seconds,
                "maxAttempts": self._max_attempts,
                "retryBaseSeconds": self._retry_base_seconds,
                "retryMaxSeconds": self._retry_max_seconds,
            },
        )

    def _materialize_candidate(
        self,
        candidate_id: str,
        lease: AgentMemoryMaterializationLease,
    ) -> AgentMemoryMaterializationRunnerItem:
        """处理单条候选，并把异常压缩为低敏失败项。

        捕获 `Exception` 不是为了吞错，而是为了保护批次处理语义：生产 worker 常见失败包括单条数据不合法、
        MySQL 短暂抖动、向量库写入超时、历史候选缺字段等。一个候选失败不应该让同一窗口内的其他候选全部饥饿。
        失败详情仍通过 receipt store、Runner report 和未来 runtime event 暴露给运维与管理员。
        """

        try:
            result = self._materializer.materialize(candidate_id)
            self._lease_store.succeed(
                candidate_id=candidate_id,
                lease_token=lease.lease_token,
                memory_id=result.memory_id,
                outcome=result.outcome.value,
                message=result.message,
            )
            return self._success_item(result, lease.lease_id)
        except Exception as exc:
            lease_finalize_error: str | None = None
            failed_lease: AgentMemoryMaterializationLease | None = None
            try:
                failed_lease = self._lease_store.fail(
                    candidate_id=candidate_id,
                    lease_token=lease.lease_token,
                    error_message=f"{type(exc).__name__}: {exc}",
                    max_attempts=self._max_attempts,
                    retry_base_seconds=self._retry_base_seconds,
                    retry_max_seconds=self._retry_max_seconds,
                )
            except Exception as finalize_exc:
                # 如果租约已过期并被其他 worker 接管，旧 worker 的 fail 回写会被 fencing 拒绝。
                # 这里保留低敏 finalize 错误，方便运维识别“业务失败”和“租约竞争”两类问题。
                lease_finalize_error = f"{type(finalize_exc).__name__}: {finalize_exc}"[:1000]
            failed_status = failed_lease.status if failed_lease else None
            is_dead_letter = failed_status == AgentMemoryMaterializationLeaseStatus.DEAD_LETTER
            return AgentMemoryMaterializationRunnerItem(
                candidate_id=candidate_id,
                status=AgentMemoryMaterializationRunnerItemStatus.FAILED,
                message=(
                    "长期记忆候选落成失败，已达到最大尝试次数并进入 DLQ，等待管理员补偿。"
                    if is_dead_letter
                    else "长期记忆候选落成失败，已写入退避窗口并在批次报告中保留低敏错误摘要。"
                ),
                error_message=f"{type(exc).__name__}: {exc}"[:1000],
                attributes={
                    "errorType": type(exc).__name__,
                    "failurePolicy": "CONTINUE_BATCH_WITH_BACKOFF_AND_DLQ",
                    "leaseId": lease.lease_id,
                    "leaseStatus": failed_status.value if failed_status else None,
                    "nextRetryAt": failed_lease.next_retry_at.isoformat() if failed_lease and failed_lease.next_retry_at else None,
                    "attemptCount": failed_lease.attempt_count if failed_lease else lease.attempt_count,
                    "maxAttempts": self._max_attempts,
                    "deadLettered": is_dead_letter,
                    "leaseFinalizeError": lease_finalize_error,
                },
            )

    def _skip_reason(self, candidate_id: str) -> str:
        """解释候选本轮未被领取的原因。

        `try_acquire(...)` 只返回领取成功或失败，不强制暴露原因，这是为了保持 store 协议简单。
        Runner 作为管理报告的生产者，可以在跳过后读取一次低敏租约快照，汇总为稳定 reason：
        - `active_lease`：其他 worker 仍持有有效租约；
        - `retry_cooldown`：失败候选还没到 nextRetryAt；
        - `dead_letter`：候选已进入 DLQ，等待管理员补偿；
        - `already_succeeded`：候选已经成功落成；
        - `not_claimable`：没有读取到明确原因，通常表示并发窗口或未来 store 扩展。
        """

        lease = self._lease_store.get_by_candidate_id(candidate_id)
        now = datetime.now(timezone.utc)
        if lease is None:
            return "not_claimable"
        if lease.status == AgentMemoryMaterializationLeaseStatus.SUCCEEDED:
            return "already_succeeded"
        if lease.status == AgentMemoryMaterializationLeaseStatus.DEAD_LETTER:
            return "dead_letter"
        if lease.status == AgentMemoryMaterializationLeaseStatus.LEASED and lease.leased_until > now:
            return "active_lease"
        if (
            lease.status == AgentMemoryMaterializationLeaseStatus.FAILED
            and lease.next_retry_at
            and lease.next_retry_at > now
        ):
            return "retry_cooldown"
        return "not_claimable"

    @staticmethod
    def _success_item(
        result: AgentMemoryMaterializationResult,
        lease_id: str,
    ) -> AgentMemoryMaterializationRunnerItem:
        """把 materializer 的成功结果转换为 Runner 单条摘要。"""

        return AgentMemoryMaterializationRunnerItem(
            candidate_id=result.candidate_id,
            status=AgentMemoryMaterializationRunnerItemStatus.SUCCEEDED,
            outcome=result.outcome.value,
            memory_id=result.memory_id,
            message=result.message,
            attributes={
                **dict(result.attributes),
                "runnerResult": "success",
                "leaseId": lease_id,
            },
        )
