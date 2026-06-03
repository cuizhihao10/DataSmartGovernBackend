"""长期记忆物化 runtime event 构建器。

长期记忆物化不是一次普通函数调用，而是一条具备审批、租约、退避、DLQ、补偿和未来二级索引同步的
后台副作用链路。生产环境里，管理员和运维人员需要回答这些问题：

- 本轮 Runner 扫描了多少候选、成功多少、失败多少、跳过多少；
- 失败是否进入 DLQ，是否因为 retry cooldown 暂缓；
- 管理员是否执行过补偿重排，重排是 dry-run 还是已写入；
- 这些事实能否进入 replay、Kafka、审计和后续指标系统。

本文件只负责“把领域对象转换成低敏 AgentRuntimeEvent”。它不直接写 store，也不直接发布 Kafka，
这样 Runner、API、CLI 和未来常驻 worker 都可以复用同一套事件语义。
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any

from datasmart_ai_runtime.domain.events import (
    AgentRuntimeEvent,
    AgentRuntimeEventSeverity,
    AgentRuntimeEventType,
)
from datasmart_ai_runtime.services.memory.memory_materialization_admin import (
    AgentMemoryMaterializationRequeueResult,
)
from datasmart_ai_runtime.services.memory.memory_materialization_runner import (
    AgentMemoryMaterializationRunnerReport,
)


@dataclass(frozen=True)
class AgentMemoryMaterializationEventContext:
    """构建长期记忆物化事件时使用的外部上下文。

    字段说明：
    - `tenant_id/project_id`：事件归属的数据治理范围。若未显式传入，requeue 事件会从 lease 快照中推导；
    - `actor_id`：触发动作的人或服务账号。requeue 默认使用 operatorId；
    - `request_id/run_id/session_id`：用于 replay、Kafka 分区和未来任务详情页串联。长期记忆补偿通常不是
      一次普通用户会话，因此这些字段允许为空；
    - `sequence`：事件在当前 run/session 内的展示顺序。对于后台补偿或 worker 事件，如果没有现成 run，
      会用事件发生时间生成一个可比较的毫秒级序号，保证 event store replay 的 afterSequence 过滤可用。
    """

    tenant_id: str | None = None
    project_id: str | None = None
    actor_id: str | None = None
    request_id: str | None = None
    run_id: str | None = None
    session_id: str | None = None
    sequence: int | None = None


def memory_materialization_requeue_event(
    result: AgentMemoryMaterializationRequeueResult,
    context: AgentMemoryMaterializationEventContext | None = None,
) -> AgentRuntimeEvent:
    """把管理员补偿重排结果转换为 runtime event。

    事件只记录低敏控制面事实：候选 ID、lease ID、状态变化、attemptCount、nextRetryAt、operatorId 和 dryRun。
    不记录候选正文、正式记忆正文、lease token、工具原始输出、SQL、样本数据或完整异常堆栈。
    """

    ctx = context or AgentMemoryMaterializationEventContext()
    event_time = result.after.updated_at
    return AgentRuntimeEvent(
        event_type=AgentRuntimeEventType.MEMORY_MATERIALIZATION_REQUEUE_RECORDED,
        stage="materialize_memory_compensation",
        message=(
            "长期记忆物化补偿已完成 dry-run 预览。"
            if result.dry_run
            else "长期记忆物化补偿已重新安排重试。"
        ),
        severity=AgentRuntimeEventSeverity.INFO if result.dry_run else AgentRuntimeEventSeverity.AUDIT,
        tenant_id=ctx.tenant_id or result.after.tenant_id,
        project_id=ctx.project_id or result.after.project_id,
        actor_id=ctx.actor_id or result.operator_id,
        request_id=ctx.request_id,
        run_id=ctx.run_id or f"memory-materialization:{result.candidate_id}",
        session_id=ctx.session_id,
        sequence=ctx.sequence or _event_sequence(event_time),
        attributes={
            "candidateId": result.candidate_id,
            "leaseId": result.after.lease_id,
            "action": result.action,
            "dryRun": result.dry_run,
            "operatorId": result.operator_id,
            "beforeStatus": result.before.status.value,
            "afterStatus": result.after.status.value,
            "attemptCount": result.after.attempt_count,
            "beforeNextRetryAt": _iso_or_none(result.before.next_retry_at),
            "afterNextRetryAt": _iso_or_none(result.after.next_retry_at),
            "workspaceKey": result.after.workspace_key,
            "memoryNamespace": result.after.memory_namespace,
            "eventPurpose": "memory_materialization_compensation_audit",
        },
        created_at=_aware(event_time),
    )


def memory_materialization_runner_event(
    report: AgentMemoryMaterializationRunnerReport,
    context: AgentMemoryMaterializationEventContext | None = None,
) -> AgentRuntimeEvent:
    """把 Runner 批次报告转换为单条汇总 runtime event。

    为什么只生成一条汇总事件：
    - Runner 后续可能每隔数秒执行一次，如果每条候选都写事件，事件流和指标都会膨胀；
    - 生产告警更关心本轮成功、失败、DLQ、retry cooldown、fencing finalize error 等聚合事实；
    - 单条候选排障仍可以通过 lease/receipt/candidateId 在管理台继续查询。
    """

    ctx = context or AgentMemoryMaterializationEventContext()
    attrs = dict(report.attributes)
    skipped_reasons = dict(attrs.get("skippedReasons") or {})
    lease_finalize_error_count = sum(
        1
        for item in report.items
        if item.attributes.get("leaseFinalizeError")
    )
    dead_letter_count = int(attrs.get("deadLetterCount") or 0)
    severity = _runner_event_severity(
        failed_count=report.failed_count,
        dead_letter_count=dead_letter_count,
        lease_finalize_error_count=lease_finalize_error_count,
    )
    return AgentRuntimeEvent(
        event_type=AgentRuntimeEventType.MEMORY_MATERIALIZATION_RUN_COMPLETED,
        stage="materialize_memory_runner",
        message=(
            "长期记忆物化批次完成："
            f"成功 {report.succeeded_count} 条，失败 {report.failed_count} 条，跳过 {report.skipped_count} 条。"
        ),
        severity=severity,
        tenant_id=ctx.tenant_id,
        project_id=ctx.project_id,
        actor_id=ctx.actor_id,
        request_id=ctx.request_id,
        run_id=ctx.run_id or f"memory-materialization-runner:{report.worker_id}",
        session_id=ctx.session_id,
        sequence=ctx.sequence or _event_sequence(report.finished_at),
        attributes={
            "requestedLimit": report.requested_limit,
            "scannedCount": report.scanned_count,
            "succeededCount": report.succeeded_count,
            "failedCount": report.failed_count,
            "skippedCount": report.skipped_count,
            "workerId": report.worker_id,
            "claimedCount": attrs.get("claimedCount", len(report.items)),
            "deadLetterCount": dead_letter_count,
            "retryCooldownSkippedCount": int(skipped_reasons.get("retry_cooldown") or 0),
            "activeLeaseSkippedCount": int(skipped_reasons.get("active_lease") or 0),
            "deadLetterSkippedCount": int(skipped_reasons.get("dead_letter") or 0),
            "alreadySucceededSkippedCount": int(skipped_reasons.get("already_succeeded") or 0),
            "leaseFinalizeErrorCount": lease_finalize_error_count,
            "executionPolicy": attrs.get("executionPolicy"),
            "maxAttempts": attrs.get("maxAttempts"),
            "retryBaseSeconds": attrs.get("retryBaseSeconds"),
            "retryMaxSeconds": attrs.get("retryMaxSeconds"),
            "durationMillis": _duration_millis(report.started_at, report.finished_at),
            "eventPurpose": "memory_materialization_batch_observability",
        },
        created_at=_aware(report.finished_at),
    )


def _runner_event_severity(
    *,
    failed_count: int,
    dead_letter_count: int,
    lease_finalize_error_count: int,
) -> AgentRuntimeEventSeverity:
    """根据批次结果选择事件严重级别。"""

    if lease_finalize_error_count > 0:
        return AgentRuntimeEventSeverity.ERROR
    if failed_count > 0 or dead_letter_count > 0:
        return AgentRuntimeEventSeverity.WARNING
    return AgentRuntimeEventSeverity.INFO


def _event_sequence(value: datetime | None) -> int:
    """生成可用于 replay afterSequence 的毫秒级序号。"""

    return int(_aware(value).timestamp() * 1000)


def _duration_millis(started_at: datetime, finished_at: datetime) -> int:
    """计算批次耗时毫秒数。"""

    return max(0, int((_aware(finished_at) - _aware(started_at)).total_seconds() * 1000))


def _aware(value: datetime | None) -> datetime:
    """把时间统一为 UTC aware datetime。"""

    current = value or datetime.now(timezone.utc)
    return current.replace(tzinfo=timezone.utc) if current.tzinfo is None else current.astimezone(timezone.utc)


def _iso_or_none(value: datetime | None) -> str | None:
    """把可选时间转换为 ISO 字符串。"""

    return _aware(value).isoformat() if value else None
