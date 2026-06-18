"""工具动作恢复事实源抽象。

本模块服务于 `/agent/tool-actions/checkpoints/resume-preview` 的下一阶段演进：checkpoint 已经可以保存执行前图的
低敏暂停点，但“是否可以继续执行”不能长期依赖调用方把 approval、clarification、outbox receipt 等事实重新
传入 Python。真实商业化产品里，这些事实应来自 permission-admin、任务 outbox、worker receipt projection、
澄清表单结果、预算控制面等服务端事实源。

这里先实现一个很小但边界清晰的抽象：
- API 层仍然只做 preview，不执行工具、不写 outbox、不派发 worker；
- provider 只返回“事实类型是否存在”，不返回事实值；
- provider 失败时，上层可以降级为缺事实，而不是把内部连接串、异常消息或敏感 payload 暴露给调用方；
- 默认 provider 为空实现，保证当前本地测试和未装配外部控制面时行为稳定。
"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any, Protocol


FACT_PAYLOAD_POLICY = "FACT_TYPE_PRESENCE_ONLY_VALUES_NOT_ECHOED"


@dataclass(frozen=True)
class ToolActionResumeFactSnapshot:
    """服务端恢复事实快照。

    字段说明：
    - `source`：事实来源标识，例如空实现、本地静态实现、permission-admin 查询器或 worker receipt 投影；
    - `available_fact_types`：当前已经确认存在的事实类型，只能是枚举式低敏名称，不能包含事实值；
    - `missing_fact_types`：provider 自己能判断但暂缺的事实类型，当前静态实现不主动推断，未来远程实现可填充；
    - `rejected_fact_types`：provider 已明确判定不能采信的事实类型，例如调用方传了 approvalId 但 permission-admin
      校验为 REJECTED/EXPIRED/SCOPE_MISMATCH；API 层会把这些类型从 acceptedFactTypes 中剔除，避免“客户端自报事实”
      覆盖服务端控制面裁决；
    - `fact_reference_count`：事实引用数量，用来帮助排障“服务端确实查到了几个事实”，但不暴露引用正文；
    - `checked_at`：检查时间，使用 UTC ISO 字符串，方便 Java 控制面、日志和事件回放做时间对齐；
    - `error_codes`：低敏错误码集合，只记录错误类型，不记录异常 message、URL、SQL、Header 或原始响应；
    - `payload_policy`：固定声明事实值不回显，避免后续调用者误以为可以从该响应中取回 approvalId 等值；
    - `resume_gate_graph`：可选 Java host-controlled 门控图摘要，只保存图状态、计数和低敏 code，
      不保存节点正文、事实 ID、payloadReference、prompt、SQL 或工具参数。
    """

    source: str
    available_fact_types: tuple[str, ...] = ()
    missing_fact_types: tuple[str, ...] = ()
    rejected_fact_types: tuple[str, ...] = ()
    fact_reference_count: int = 0
    checked_at: str = field(default_factory=lambda: datetime.now(timezone.utc).isoformat())
    error_codes: tuple[str, ...] = ()
    payload_policy: str = FACT_PAYLOAD_POLICY
    resume_gate_graph: Mapping[str, Any] | None = None

    def to_summary(self) -> dict[str, Any]:
        """生成可返回给 API 调用方的低敏摘要。

        注意这里故意不提供任何 `facts`、`values`、`references`、`payload` 字段。对恢复流程而言，preview 响应
        只需要说明“哪些事实类型已经具备”，真正的事实值应留在服务端受控存储中，由后续 durable runner 在同一
        信任边界内消费。
        """

        summary = {
            "source": self.source,
            "availableFactTypes": self.available_fact_types,
            "missingFactTypes": self.missing_fact_types,
            "rejectedFactTypes": self.rejected_fact_types,
            "factReferenceCount": self.fact_reference_count,
            "checkedAt": self.checked_at,
            "errorCodes": self.error_codes,
            "payloadPolicy": self.payload_policy,
        }
        if self.resume_gate_graph:
            summary["resumeGateGraph"] = dict(self.resume_gate_graph)
        return summary


class ToolActionResumeFactProvider(Protocol):
    """恢复事实 provider 协议。

    provider 的职责是“查询或汇总服务端事实是否存在”，而不是执行恢复动作。后续可以按这个协议接入：
    - permission-admin：审批通过/拒绝事实；
    - clarification store：人工补充字段、业务确认结果；
    - task-management/outbox：outbox 已写入或 operator 已确认事实；
    - worker backlog/预算控制面：预算恢复、worker 容量恢复事实。
    """

    def collect(
        self,
        *,
        checkpoint: Any,
        request_payload: Mapping[str, Any] | None = None,
    ) -> ToolActionResumeFactSnapshot:
        """收集指定 checkpoint 对应的服务端恢复事实。"""


class EmptyToolActionResumeFactProvider:
    """默认空事实源。

    该实现刻意返回空集合，用来表达“当前环境尚未装配服务端事实源”。它比 `None` 更有价值，因为 API 响应可以
    明确告诉调用方本次预检只使用了请求内事实，没有消耗 permission-admin/outbox/worker 等受控来源。
    """

    def collect(
        self,
        *,
        checkpoint: Any,
        request_payload: Mapping[str, Any] | None = None,
    ) -> ToolActionResumeFactSnapshot:
        """返回空事实快照，保持旧行为兼容。"""

        return ToolActionResumeFactSnapshot(source="NO_SERVER_SIDE_FACT_PROVIDER")


class StaticToolActionResumeFactProvider:
    """测试与本地联调用静态事实源。

    真实生产实现会通过 checkpointId/threadId 去查询受控服务；本实现只从构造函数传入的 dict 中读取事实，方便
    单元测试验证“服务端事实 + 请求事实”合并逻辑。它仍然只读取字段是否存在，不会把字段值带入响应。
    """

    def __init__(
        self,
        *,
        facts_by_checkpoint_id: Mapping[str, Mapping[str, Any]] | None = None,
        facts_by_thread_id: Mapping[str, Mapping[str, Any]] | None = None,
        source: str = "STATIC_RESUME_FACT_PROVIDER",
    ) -> None:
        """创建静态事实源。

        参数说明：
        - `facts_by_checkpoint_id`：按 checkpointId 精确命中的事实映射，优先级高；
        - `facts_by_thread_id`：按 threadId 命中的事实映射，适合断线恢复或本地联调；
        - `source`：返回给调用方的低敏来源标签，便于测试断言和后续运维诊断。
        """

        self._facts_by_checkpoint_id = dict(facts_by_checkpoint_id or {})
        self._facts_by_thread_id = dict(facts_by_thread_id or {})
        self._source = source

    def collect(
        self,
        *,
        checkpoint: Any,
        request_payload: Mapping[str, Any] | None = None,
    ) -> ToolActionResumeFactSnapshot:
        """按 checkpointId 优先、threadId 兜底收集事实类型。"""

        raw_facts = self._facts_for_checkpoint(checkpoint)
        fact_types = resume_fact_types_from_mapping(raw_facts)
        return ToolActionResumeFactSnapshot(
            source=self._source,
            available_fact_types=fact_types,
            fact_reference_count=len(fact_types),
        )

    def _facts_for_checkpoint(self, checkpoint: Any) -> Mapping[str, Any]:
        """从静态映射中取出与 checkpoint 对应的原始事实包装。"""

        checkpoint_id = str(getattr(checkpoint, "checkpoint_id", "") or "")
        thread_id = str(getattr(checkpoint, "thread_id", "") or "")
        if checkpoint_id and checkpoint_id in self._facts_by_checkpoint_id:
            return self._facts_by_checkpoint_id[checkpoint_id]
        if thread_id and thread_id in self._facts_by_thread_id:
            return self._facts_by_thread_id[thread_id]
        return {}


def resume_fact_types_from_mapping(value: Mapping[str, Any] | None) -> tuple[str, ...]:
    """从请求或服务端事实包装中提取低敏事实类型。

    字段映射原则：
    - `graphId`/`contractId` 只说明“有执行图或控制面合同证据”，不回显具体 ID；
    - `payloadReference` 只说明“有外部 payload 引用”，不回显引用值；
    - `approvalConfirmationId`、`clarificationFactId`、`outboxConfirmationId` 等只映射为事实类型；
    - `resumeFacts` 内层对象与顶层字段合并，兼容当前 API payload 和未来控制面 provider 返回格式；
    - 所有结果去重并保持稳定顺序，便于测试、审计和前端 diff。
    """

    if not isinstance(value, Mapping):
        return ()
    merged = _merge_fact_mapping(value)
    accepted: list[str] = []
    if _text(merged.get("graphId")) or _text(merged.get("contractId")):
        _append_once(accepted, "GRAPH_OR_CONTRACT_EVIDENCE")
    if _text(merged.get("payloadReference")):
        _append_once(accepted, "PAYLOAD_REFERENCE")
    if _text(merged.get("policyVersion")):
        _append_once(accepted, "POLICY_VERSION")
    if _text(merged.get("approvalConfirmationId")):
        _append_once(accepted, "APPROVAL_CONFIRMATION_FACT")
    if _text(merged.get("clarificationFactId")):
        _append_once(accepted, "CLARIFICATION_FACT")
    if _text(merged.get("outboxConfirmationId")) or _text(merged.get("operatorConfirmationId")):
        _append_once(accepted, "OUTBOX_WRITE_CONFIRMATION")
    if _truthy(merged.get("controlPlaneClientEnabled")):
        _append_once(accepted, "CONTROL_PLANE_CLIENT_ENABLEMENT")
    if _truthy(merged.get("toolBudgetRecovered")) or _truthy(merged.get("workerCapacityRecovered")):
        _append_once(accepted, "TOOL_BUDGET_OR_WORKER_CAPACITY_RECOVERY")
    return tuple(accepted)


def merge_resume_fact_types(*groups: tuple[str, ...]) -> tuple[str, ...]:
    """合并多个事实类型集合并保持稳定去重顺序。"""

    merged: list[str] = []
    for group in groups:
        for item in group:
            _append_once(merged, item)
    return tuple(merged)


def _merge_fact_mapping(value: Mapping[str, Any]) -> dict[str, Any]:
    """把顶层事实字段和 `resumeFacts` 字段合并为统一读取视图。"""

    facts = value.get("resumeFacts")
    merged: dict[str, Any] = dict(facts) if isinstance(facts, Mapping) else {}
    for key in (
        "graphId",
        "contractId",
        "payloadReference",
        "policyVersion",
        "approvalConfirmationId",
        "clarificationFactId",
        "outboxConfirmationId",
        "operatorConfirmationId",
        "controlPlaneClientEnabled",
        "toolBudgetRecovered",
        "workerCapacityRecovered",
    ):
        if key in value and key not in merged:
            merged[key] = value[key]
    return merged


def _append_once(items: list[str], value: str) -> None:
    """追加去重后的事实类型，保证响应顺序稳定。"""

    if value not in items:
        items.append(value)


def _truthy(value: Any) -> bool:
    """解析布尔型事实开关，兼容字符串和真实布尔值。"""

    if isinstance(value, bool):
        return value
    return str(value or "").strip().lower() in {"1", "true", "yes", "y", "on"}


def _text(value: Any) -> str | None:
    """解析非空字符串。"""

    if value is None:
        return None
    text = str(value).strip()
    return text or None
