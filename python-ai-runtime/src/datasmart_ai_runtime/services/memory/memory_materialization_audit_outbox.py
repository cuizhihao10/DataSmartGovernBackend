"""长期记忆物化审计 outbox 领域抽象。

长期记忆物化已经具备 Runtime Event、Prometheus 指标和告警规则，但这些能力更偏“可观测性”：

- Runtime Event 适合前端时间线、replay 和问题定位；
- Prometheus 适合聚合指标、趋势判断和告警；
- 审计 outbox 则回答“某个后台批次或管理员补偿动作是否已经形成可持久化、可转交 Java 审计中心的审计事实”。

本文件刻意放在 `services.memory` 能力包内，而不是复用 WebSocket runtime-event outbox。原因是两者的业务语义不同：

- WebSocket outbox 解决“事件帧如何在多实例间等待客户端消费”；
- 记忆物化审计 outbox 解决“高合规环境下，worker/管理员动作是否留下可追责证据”。

当前实现提供内存 store 和统一 recorder。内存 store 只适合本地学习和单测；生产环境应使用 SQL outbox，
并进一步演进为与 lease 状态变更同库事务提交的 transactional outbox。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
from enum import Enum
from threading import RLock
from typing import Any, Protocol
from uuid import uuid4

from datasmart_ai_runtime.domain.events import AgentRuntimeEvent


class AgentMemoryMaterializationAuditDeliveryStatus(str, Enum):
    """审计 outbox 投递状态。

    当前阶段只负责把审计事实写入 outbox，所以新记录默认是 `pending`。
    后续如果增加 Java audit bridge、Kafka dispatcher 或对象归档 worker，可以在同一张表上继续推进：

    - `pending`：已写入 outbox，等待后续派发到 Java 审计中心或归档系统；
    - `dispatched`：下游已确认接收；
    - `failed`：多次投递失败，等待告警或人工补偿。
    """

    PENDING = "pending"
    DISPATCHED = "dispatched"
    FAILED = "failed"


@dataclass(frozen=True)
class AgentMemoryMaterializationAuditOutboxRecord:
    """长期记忆物化审计 outbox 记录。

    字段说明：
    - `outbox_id`：outbox 主键，使用随机 UUID，避免同一候选多次补偿或同一 worker 多轮批次发生冲突；
    - `event_type/event_purpose`：来自 Runtime Event 的机器语义，便于后续 Java 审计中心路由；
    - `aggregate_id`：本条审计事实关联的核心对象。补偿事件优先使用 candidateId，批次事件优先使用 workerId/runId；
    - `tenant_id/project_id/actor_id`：多租户审计和操作者追责基础字段；
    - `action/dry_run`：管理员补偿特别需要区分“预览”和“真实重排”，避免 dry-run 被误当作已恢复；
    - `payload`：低敏扩展字段，只能保存计数、状态、ID、namespace 等控制面事实，不能保存候选正文、SQL、样本数据或工具原始输出；
    - `delivery_status/attempt_count/next_delivery_attempt_at`：为未来 outbox dispatcher 预留，不在本批直接派发。
    """

    outbox_id: str
    event_type: str
    event_purpose: str
    aggregate_id: str
    tenant_id: str | None = None
    project_id: str | None = None
    actor_id: str | None = None
    request_id: str | None = None
    run_id: str | None = None
    session_id: str | None = None
    severity: str = "info"
    action: str | None = None
    dry_run: bool = False
    payload: dict[str, Any] = field(default_factory=dict)
    delivery_status: AgentMemoryMaterializationAuditDeliveryStatus = AgentMemoryMaterializationAuditDeliveryStatus.PENDING
    attempt_count: int = 0
    next_delivery_attempt_at: datetime | None = None
    created_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    updated_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))

    def to_summary(self) -> dict[str, Any]:
        """返回可暴露给诊断接口或单元测试的低敏摘要。

        摘要不展开完整 payload，避免未来 payload 增加更多审计上下文后被诊断接口误暴露。
        """

        return {
            "outboxId": self.outbox_id,
            "eventType": self.event_type,
            "eventPurpose": self.event_purpose,
            "aggregateId": self.aggregate_id,
            "tenantId": self.tenant_id,
            "projectId": self.project_id,
            "actorId": self.actor_id,
            "severity": self.severity,
            "action": self.action,
            "dryRun": self.dry_run,
            "deliveryStatus": self.delivery_status.value,
            "attemptCount": self.attempt_count,
            "createdAt": _iso(self.created_at),
            "updatedAt": _iso(self.updated_at),
        }


class AgentMemoryMaterializationAuditOutboxStore(Protocol):
    """审计 outbox 存储协议。

    协议当前只要求 `append` 和 `list_recent`：

    - `append` 用于 worker/API 写入审计事实；
    - `list_recent` 用于诊断、单测和未来管理台观察最近 outbox 状态。

    后续 dispatcher 需要 claim/ack/retry 时，应在这个协议上继续增量扩展，而不是让 worker 直接理解 SQL 表字段。
    """

    def append(self, record: AgentMemoryMaterializationAuditOutboxRecord) -> AgentMemoryMaterializationAuditOutboxRecord:
        """追加一条审计记录。"""

    def list_recent(self, *, limit: int = 100) -> tuple[AgentMemoryMaterializationAuditOutboxRecord, ...]:
        """查询最近写入的审计记录。"""


class InMemoryAgentMemoryMaterializationAuditOutboxStore:
    """内存版审计 outbox。

    它适合本地学习、单元测试和不需要持久审计的开发环境。需要特别注意：

    - 进程重启会丢失记录；
    - 多实例之间不会共享；
    - 不能满足真实客户对审计不可丢失、可归档、可导出的要求。
    """

    def __init__(self) -> None:
        self._records: list[AgentMemoryMaterializationAuditOutboxRecord] = []
        self._lock = RLock()

    def append(self, record: AgentMemoryMaterializationAuditOutboxRecord) -> AgentMemoryMaterializationAuditOutboxRecord:
        """追加记录并保持写入顺序。"""

        with self._lock:
            self._records.append(record)
        return record

    def list_recent(self, *, limit: int = 100) -> tuple[AgentMemoryMaterializationAuditOutboxRecord, ...]:
        """按写入时间倒序读取最近记录。"""

        safe_limit = max(1, min(int(limit), 500))
        with self._lock:
            return tuple(reversed(self._records[-safe_limit:]))


class AgentMemoryMaterializationAuditOutboxError(RuntimeError):
    """审计 outbox 写入失败。

    当 recorder 配置为 `required=True` 时，写入失败会抛出该异常。调用方可以把它映射成 worker 失败、
    HTTP 503 或 future task retry，而不是把审计失败静默吞掉。
    """

    def __init__(self, message: str, delivery: dict[str, Any]) -> None:
        super().__init__(message)
        self.delivery = delivery


class AgentMemoryMaterializationAuditOutboxRecorder:
    """把 Runtime Event 转换并写入审计 outbox 的小型协调器。

    设计意图：
    - worker 和 API route 不直接依赖具体 store 类型；
    - fail-open/fail-closed 策略集中在 recorder；
    - 返回统一 delivery 摘要，便于诊断接口、响应体和单测使用。

    `required=True` 的语义需要谨慎理解：当前它能阻止调用方把审计失败当作成功，但不能自动回滚已经写入的
    lease/formal memory 状态。真正严格的生产终态应把业务状态更新和 outbox append 放入同一个 SQL 事务。
    """

    def __init__(
        self,
        *,
        store: AgentMemoryMaterializationAuditOutboxStore | None = None,
        enabled: bool = False,
        required: bool = False,
    ) -> None:
        self._store = store
        self.enabled = enabled
        self.required = required

    def record_runtime_event(self, event: AgentRuntimeEvent) -> dict[str, Any]:
        """记录一条 Runtime Event 对应的审计 outbox。

        返回 delivery 摘要：
        - `enabled=False` 时不会写入，适合本地默认环境；
        - `enabled=True` 且写入成功时返回 outboxId；
        - 写入失败时，如果 `required=False` 返回错误摘要；如果 `required=True` 抛出异常。
        """

        if not self.enabled:
            return {"enabled": False, "required": self.required, "stored": False, "outboxId": None, "errors": ()}
        if self._store is None:
            return self._handle_error("audit_outbox_store", RuntimeError("审计 outbox 已启用但未配置 store。"))
        try:
            record = self._store.append(audit_record_from_runtime_event(event))
            return {
                "enabled": True,
                "required": self.required,
                "stored": True,
                "outboxId": record.outbox_id,
                "errors": (),
            }
        except Exception as exc:
            return self._handle_error("audit_outbox_store", exc)

    def _handle_error(self, component: str, exc: Exception) -> dict[str, Any]:
        """按 required 策略处理写入失败。"""

        delivery = {
            "enabled": self.enabled,
            "required": self.required,
            "stored": False,
            "outboxId": None,
            "errors": (_delivery_error(component, exc),),
        }
        if self.required:
            raise AgentMemoryMaterializationAuditOutboxError(
                "长期记忆物化审计 outbox 写入失败，当前配置要求 fail-closed。",
                delivery,
            ) from exc
        return delivery


def audit_record_from_runtime_event(event: AgentRuntimeEvent) -> AgentMemoryMaterializationAuditOutboxRecord:
    """从长期记忆物化 Runtime Event 构造审计 outbox 记录。

    这里复用 Runtime Event 的低敏属性，而不是重新拼一套字段，是为了保持“前端 replay、Prometheus 指标、
    审计 outbox”三条链路对同一事实的解释一致。
    """

    attributes = dict(event.attributes or {})
    now = datetime.now(timezone.utc)
    return AgentMemoryMaterializationAuditOutboxRecord(
        outbox_id=f"memory-materialization-audit-{uuid4()}",
        event_type=str(_enum_value(event.event_type)),
        event_purpose=str(attributes.get("eventPurpose") or "memory_materialization_audit"),
        aggregate_id=_aggregate_id(event, attributes),
        tenant_id=event.tenant_id,
        project_id=event.project_id,
        actor_id=event.actor_id,
        request_id=event.request_id,
        run_id=event.run_id,
        session_id=event.session_id,
        severity=str(_enum_value(event.severity)),
        action=_optional_text(attributes.get("action")) or _default_action(event),
        dry_run=bool(attributes.get("dryRun")),
        payload={
            "stage": event.stage,
            "message": event.message,
            "sequence": event.sequence,
            "createdAt": _iso(event.created_at),
            "attributes": _json_safe(attributes),
        },
        delivery_status=AgentMemoryMaterializationAuditDeliveryStatus.PENDING,
        created_at=now,
        updated_at=now,
    )


def _aggregate_id(event: AgentRuntimeEvent, attributes: dict[str, Any]) -> str:
    """提取审计聚合对象 ID。

    补偿动作优先 candidateId，因为管理员通常围绕单个候选操作；worker 批次优先 workerId/runId，因为它是
    一个批次汇总事件，不应伪装成某一条候选的明细审计。
    """

    for key in ("candidateId", "leaseId", "workerId"):
        value = _optional_text(attributes.get(key))
        if value:
            return value
    return event.run_id or str(_enum_value(event.event_type))


def _default_action(event: AgentRuntimeEvent) -> str:
    """为没有 action 字段的批次事件生成稳定动作名。"""

    event_type = str(_enum_value(event.event_type))
    if event_type == "memory_materialization_run_completed":
        return "batch_completed"
    return event_type


def _delivery_error(component: str, exc: Exception) -> dict[str, str]:
    """构造低敏错误摘要。"""

    return {
        "component": component,
        "errorType": type(exc).__name__,
        "message": str(exc)[:300],
    }


def _optional_text(value: Any) -> str | None:
    """把可选值规范化为非空字符串。"""

    if value is None:
        return None
    text = str(value).strip()
    return text or None


def _enum_value(value: Any) -> Any:
    """兼容 Enum 与普通字符串。"""

    return getattr(value, "value", value)


def _iso(value: datetime | None) -> str | None:
    """把时间转为 ISO 字符串。"""

    if value is None:
        return None
    aware = value.replace(tzinfo=timezone.utc) if value.tzinfo is None else value.astimezone(timezone.utc)
    return aware.isoformat()


def _json_safe(value: Any) -> Any:
    """递归转换为 JSON 友好结构。

    审计 payload 后续可能写入 MySQL JSON、Kafka 或对象存储。提前把 Enum/datetime/tuple 转好，可以让不同
    存储实现共享同一份低敏 payload 语义。
    """

    if isinstance(value, Enum):
        return value.value
    if isinstance(value, datetime):
        return _iso(value)
    if isinstance(value, dict):
        return {str(key): _json_safe(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_json_safe(item) for item in value]
    return value
