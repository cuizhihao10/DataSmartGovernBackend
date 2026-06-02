"""长期记忆物化管理员补偿服务。

这个文件承接 `memory_materialization_runner`、`memory_materialization_lease_store` 与未来管理台之间的
业务规则。它的职责不是直接执行长期记忆落成，而是回答三个运维问题：

- 哪些候选已经失败或进入 DLQ，需要管理员关注；
- 如果管理员点击“重排”，系统会如何改变 lease 状态，是否会绕过审批；
- 管理员确认后，如何把失败候选重新安排到下一次 Runner 可领取窗口。

为什么要单独抽成 service，而不是直接写在 FastAPI 路由里：
- 路由层只应该处理 HTTP 参数和错误码映射，不应该承担业务状态机；
- store 层只应该提供原子读写，不应该理解“dry-run、管理员原因、产品文案”；
- service 层可以被 HTTP API、CLI、后台补偿任务和未来 Java 管理台适配器复用。
"""

from __future__ import annotations

from dataclasses import dataclass, replace
from datetime import datetime, timedelta
from typing import Any

from datasmart_ai_runtime.services.memory.memory_materialization_lease_store import (
    AgentMemoryMaterializationLease,
    AgentMemoryMaterializationLeaseStatus,
    AgentMemoryMaterializationLeaseStore,
    _admin_requeue_message,
    _default_compensation_statuses,
    _require_requeueable_status,
    _utc,
    _validate_compensation_operator,
)


@dataclass(frozen=True)
class AgentMemoryMaterializationLeaseQuery:
    """管理员补偿列表查询条件。

    字段说明：
    - `tenant_id/project_id/workspace_key`：低敏数据范围过滤条件。当前 Python Runtime 只执行过滤，不决定权限；
      生产环境应由 gateway/permission-admin 根据操作者角色注入或收紧这些范围；
    - `statuses`：待查询状态集合。默认只查 failed/dead_letter，避免把成功或执行中的 lease 误展示为待补偿；
    - `limit`：最大返回条数。管理台列表需要分页与上限，不能因为一个租户堆积大量失败候选就拖垮 Runtime。
    """

    tenant_id: str | None = None
    project_id: str | None = None
    workspace_key: str | None = None
    statuses: tuple[AgentMemoryMaterializationLeaseStatus, ...] | None = None
    limit: int = 100


@dataclass(frozen=True)
class AgentMemoryMaterializationRequeueRequest:
    """管理员重排失败/DLQ 候选的请求对象。

    字段说明：
    - `candidate_id`：要重排的长期记忆写入候选 ID；
    - `operator_id`：执行补偿的人或服务账号，后续会进入审计事件；
    - `reason`：补偿原因，必须填写，避免“谁也说不清为什么重放”的黑箱运维；
    - `dry_run`：是否只预览不落库。默认由 API 层设置为 True 更安全，service 层也完整支持；
    - `delay_seconds/next_retry_at`：重排时间。可以立即重试，也可以安排到下游存储恢复之后再重试。
    """

    candidate_id: str
    operator_id: str
    reason: str
    dry_run: bool = True
    delay_seconds: int | None = 0
    next_retry_at: datetime | None = None


@dataclass(frozen=True)
class AgentMemoryMaterializationRequeueResult:
    """一次补偿重排的低敏结果。

    `before/after` 都是 lease 快照。它们不会泄露 lease token 原文、候选正文、正式记忆正文或工具原始输出。
    返回两份快照的原因是：管理台需要在 dry-run 与真实执行后展示“本次会改变什么/已经改变什么”，
    这比只返回 success=true 更适合生产排障与学习理解。
    """

    candidate_id: str
    dry_run: bool
    action: str
    operator_id: str
    before: AgentMemoryMaterializationLease
    after: AgentMemoryMaterializationLease
    notes: tuple[str, ...]

    def to_summary(self) -> dict[str, Any]:
        """转换为 API 可直接返回的低敏结构。"""

        return {
            "candidateId": self.candidate_id,
            "dryRun": self.dry_run,
            "action": self.action,
            "operatorId": self.operator_id,
            "before": self.before.to_summary(),
            "after": self.after.to_summary(),
            "notes": self.notes,
        }


class AgentMemoryMaterializationAdminService:
    """长期记忆物化管理员补偿服务。

    当前补偿策略保持克制：只允许 failed/dead_letter 重新安排为 failed，并设置 `next_retry_at`。
    下一轮 Runner 到达该时间后，仍然要重新领取 lease、生成新 fencing token、调用 materializer，再由 formal
    memory store 与 receipt store 证明真实执行结果。

    这条边界非常重要：管理员补偿入口不是“强行写入长期记忆”的后门，而是“把已审批但执行失败的候选重新放回
    受控执行链路”的入口。
    """

    MAX_REQUEUE_DELAY_SECONDS = 7 * 24 * 60 * 60

    def __init__(self, lease_store: AgentMemoryMaterializationLeaseStore) -> None:
        self._lease_store = lease_store

    def list_leases(
        self,
        query: AgentMemoryMaterializationLeaseQuery | None = None,
    ) -> tuple[AgentMemoryMaterializationLease, ...]:
        """查询可补偿 lease 列表。

        service 层会对 limit 和默认状态做一次规范化，然后委托给 store。这样 HTTP API、CLI 或未来 Java
        管理台适配器都能得到相同的列表语义。
        """

        resolved = query or AgentMemoryMaterializationLeaseQuery()
        safe_limit = max(1, min(int(resolved.limit), 500))
        return self._lease_store.list_for_compensation(
            tenant_id=resolved.tenant_id,
            project_id=resolved.project_id,
            workspace_key=resolved.workspace_key,
            statuses=resolved.statuses or _default_compensation_statuses(),
            limit=safe_limit,
        )

    def requeue(
        self,
        request: AgentMemoryMaterializationRequeueRequest,
        *,
        now: datetime | None = None,
    ) -> AgentMemoryMaterializationRequeueResult:
        """预览或执行一次管理员补偿重排。

        业务流程：
        1. 校验 operator/reason，确保补偿具备最小审计语义；
        2. 读取当前 lease，确认它存在且处于 failed/dead_letter；
        3. 计算新的 `next_retry_at`；
        4. 如果 dry-run，只构造 after 快照，不写 store；
        5. 如果真实执行，通过 store 的条件更新写回，避免覆盖并发状态变化。
        """

        current_time = _utc(now)
        _validate_compensation_operator(operator_id=request.operator_id, reason=request.reason)
        before = self._lease_store.get_by_candidate_id(request.candidate_id)
        if before is None:
            raise KeyError(f"长期记忆物化 lease 不存在: {request.candidate_id}")
        _require_requeueable_status(before)
        next_retry_at = self._resolve_next_retry_at(request, now=current_time)
        preview_after = replace(
            before,
            status=AgentMemoryMaterializationLeaseStatus.FAILED,
            next_retry_at=next_retry_at,
            message=_admin_requeue_message(operator_id=request.operator_id, reason=request.reason),
            updated_at=current_time,
        )
        if request.dry_run:
            return AgentMemoryMaterializationRequeueResult(
                candidate_id=request.candidate_id,
                dry_run=True,
                action="dry_run_requeue",
                operator_id=request.operator_id,
                before=before,
                after=preview_after,
                notes=(
                    "dry-run 只预览 lease 状态变化，不会写入 store。",
                    "重排不会绕过候选审批；后续仍需 Runner 领取 lease 后执行 materializer。",
                ),
            )
        after = self._lease_store.schedule_retry(
            candidate_id=request.candidate_id,
            operator_id=request.operator_id,
            reason=request.reason,
            next_retry_at=next_retry_at,
            now=current_time,
        )
        return AgentMemoryMaterializationRequeueResult(
            candidate_id=request.candidate_id,
            dry_run=False,
            action="scheduled_retry",
            operator_id=request.operator_id,
            before=before,
            after=after,
            notes=(
                "lease 已重新安排到 failed 状态，Runner 会在 nextRetryAt 到达后重新领取。",
                "attemptCount 会保留为故障证据，不在普通补偿动作中清零。",
            ),
        )

    def _resolve_next_retry_at(
        self,
        request: AgentMemoryMaterializationRequeueRequest,
        *,
        now: datetime,
    ) -> datetime:
        """解析补偿重排时间。

        `next_retry_at` 适合运维明确选择未来时间点；`delay_seconds` 适合按钮式“5 分钟后重试/立即重试”。
        两者不能同时表达不同含义，否则管理台和审计记录会出现歧义。
        """

        if request.next_retry_at is not None:
            if request.delay_seconds not in (None, 0):
                raise ValueError("nextRetryAt 与 delaySeconds 不能同时指定不同重排时间。")
            return _utc(request.next_retry_at)
        delay = int(request.delay_seconds or 0)
        if delay < 0:
            raise ValueError("delaySeconds 不能为负数。")
        if delay > self.MAX_REQUEUE_DELAY_SECONDS:
            raise ValueError("delaySeconds 不能超过 7 天，过长的补偿窗口应使用独立排程任务管理。")
        return now + timedelta(seconds=delay)
