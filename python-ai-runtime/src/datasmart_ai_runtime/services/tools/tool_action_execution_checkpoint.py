"""工具动作执行前图的低敏检查点存储。

本模块承接 `tool_action_execution_graph_runner.py` 的结果，但它刻意不直接依赖 runner 类本身：
runner 只需要传入一份低敏 `graph_run_summary`，store 负责再次做白名单裁剪、生成线程序号和保存检查点。

为什么要单独拆出 checkpoint 模块：
- LangGraph / OpenClaw 风格的 Agent 执行图需要“暂停、恢复、回放、人工审批后继续”的能力；
- 但 DataSmart 作为企业数据治理产品，不能把 prompt、SQL、工具参数、样本数据或模型输出塞进检查点；
- 因此这里先实现一个低敏、进程内、有容量上限的检查点契约，用于固定 API 形态和安全边界；
- 后续如果要替换成 Redis / MySQL / workflow engine checkpoint，只需要实现同一个 store 协议。

本模块里的“检查点”是短期线程级执行状态，不是长期记忆：
- 检查点服务于同一 run/thread 的暂停恢复，例如等待审批、等待澄清、等待 outbox 确认；
- 长期记忆服务于跨会话经验沉淀，例如治理经验、用户偏好、项目知识、可召回事实；
- 两者如果混用，会导致敏感上下文扩散，也会让恢复语义和记忆语义互相污染。
"""

from __future__ import annotations

from collections import OrderedDict, defaultdict, deque
from collections.abc import Mapping
from dataclasses import dataclass, field
from datetime import datetime, timezone
from threading import RLock
from typing import Any, Protocol
from uuid import uuid4


CHECKPOINT_SCHEMA_VERSION = "datasmart.python-ai-runtime.tool-action-execution-checkpoint.v1"
CHECKPOINT_PAYLOAD_POLICY = "LOW_SENSITIVE_EXECUTION_GRAPH_CHECKPOINT_ONLY"
DEFAULT_CHECKPOINT_THREAD_ID = "tool-action-execution-preview"


@dataclass(frozen=True)
class ToolActionExecutionCheckpoint:
    """一次工具动作执行前图检查点。

    字段说明：
    - `checkpoint_id`：检查点全局唯一 ID，用于后续 resume/query 时定位某次暂停状态；
    - `thread_id`：线程级恢复边界，通常对应 runId、sessionId 或 requestId；
    - `sequence`：同一 thread 内递增序号，用于前端、审计和后续 replay 判断新旧状态；
    - `saved_at`：UTC ISO 时间，便于跨时区运维和日志对齐；
    - `payload_policy`：固定低敏策略，提醒调用方不要把它当作完整执行上下文；
    - `tenant_id/project_id/actor_id`：只保存控制面定位信息，不保存用户输入正文；
    - `status_counts/resume_requirements`：用于快速判断当前图卡在哪些恢复条件上；
    - `graph_run_summary`：经过白名单裁剪后的执行图摘要，不包含 prompt、SQL、arguments、模型输出等正文。
    """

    checkpoint_id: str
    thread_id: str
    sequence: int
    saved_at: str
    payload_policy: str = CHECKPOINT_PAYLOAD_POLICY
    source: str | None = None
    protocol_family: str | None = None
    tenant_id: str | None = None
    project_id: str | None = None
    actor_id: str | None = None
    request_id: str | None = None
    run_id: str | None = None
    session_id: str | None = None
    step_count: int = 0
    status_counts: Mapping[str, int] = field(default_factory=dict)
    resume_requirements: tuple[str, ...] = ()
    graph_run_summary: Mapping[str, Any] = field(default_factory=dict)

    def to_summary(self, *, include_graph_run: bool = False) -> dict[str, Any]:
        """转换为 API 或 runner 响应可直接返回的低敏摘要。

        默认不返回完整 `graphRunSummary`，是为了让主响应只暴露“已持久化到哪个检查点”。
        管理端如果后续需要查看检查点详情，应通过受控只读接口再次按权限读取，而不是让每次 preview 都回显完整状态。
        """

        summary: dict[str, Any] = {
            "schemaVersion": CHECKPOINT_SCHEMA_VERSION,
            "checkpointId": self.checkpoint_id,
            "threadId": self.thread_id,
            "sequence": self.sequence,
            "savedAt": self.saved_at,
            "payloadPolicy": self.payload_policy,
            "source": self.source,
            "protocolFamily": self.protocol_family,
            "tenantId": self.tenant_id,
            "projectId": self.project_id,
            "actorId": self.actor_id,
            "requestId": self.request_id,
            "runId": self.run_id,
            "sessionId": self.session_id,
            "stepCount": self.step_count,
            "statusCounts": dict(self.status_counts),
            "resumeRequirements": self.resume_requirements,
            "sensitiveDataPolicy": {
                "rawPromptStored": False,
                "rawArgumentsStored": False,
                "sqlStored": False,
                "sampleDataStored": False,
                "modelOutputStored": False,
                "credentialStored": False,
                "internalEndpointStored": False,
                "meaning": "检查点只保存执行前图的低敏恢复线索，不保存可直接复原用户请求或工具载荷的正文。",
            },
        }
        if include_graph_run:
            summary["graphRunSummary"] = dict(self.graph_run_summary)
        return summary


class ToolActionExecutionCheckpointStore(Protocol):
    """工具动作执行前图检查点存储协议。

    这个协议是后续替换 Redis/MySQL 持久化实现的扩展点：
    - `save(...)`：写入一次低敏检查点，返回可暴露给调用方的定位摘要；
    - `get(...)`：按 checkpointId 读取单个检查点，未来 resume 时可用；
    - `list_by_thread(...)`：按 threadId 查看最近检查点，未来 UI 或审计排障可用。

    当前 in-memory 实现适合本地学习、单元测试和 API 契约稳定，不保证跨进程、跨实例或重启恢复。
    """

    def save(
        self,
        graph_run_summary: Mapping[str, Any],
        *,
        context: Mapping[str, Any] | None = None,
    ) -> ToolActionExecutionCheckpoint:
        """保存一次执行前图摘要。"""

    def get(self, checkpoint_id: str) -> ToolActionExecutionCheckpoint | None:
        """按检查点 ID 读取单个检查点。"""

    def list_by_thread(self, thread_id: str, *, limit: int = 20) -> tuple[ToolActionExecutionCheckpoint, ...]:
        """按恢复线程读取最近的检查点。"""


class InMemoryToolActionExecutionCheckpointStore:
    """进程内工具动作执行前图检查点存储。

    设计意图：
    - 先用内存实现固定“低敏检查点”数据契约，让 API、runner 和测试可以真实消费；
    - 通过 per-thread 和 global 两级容量上限避免本地调试时无限增长；
    - 使用 `RLock` 保证同进程并发请求下保存、读取、驱逐操作的一致性；
    - 仍明确声明它不是生产级 durable store，服务重启后检查点会丢失。

    后续生产化建议：
    - Redis 适合短 TTL、会话级恢复和多实例共享；
    - MySQL 适合审计、长期 replay、管理员查询和跨重启恢复；
    - 如果执行图复杂到需要补偿、超时、跨服务事务，可再评估专用 durable workflow engine。
    """

    def __init__(self, *, max_checkpoints_per_thread: int = 20, max_total_checkpoints: int = 2_000) -> None:
        self._max_checkpoints_per_thread = max(1, max_checkpoints_per_thread)
        self._max_total_checkpoints = max(self._max_checkpoints_per_thread, max_total_checkpoints)
        self._lock = RLock()
        self._by_id: OrderedDict[str, ToolActionExecutionCheckpoint] = OrderedDict()
        self._ids_by_thread: dict[str, deque[str]] = defaultdict(deque)
        self._sequence_by_thread: dict[str, int] = defaultdict(int)

    def save(
        self,
        graph_run_summary: Mapping[str, Any],
        *,
        context: Mapping[str, Any] | None = None,
    ) -> ToolActionExecutionCheckpoint:
        """保存一次低敏检查点。

        保存流程刻意分成三段，方便学习和排障：
        1. 先把 graph run 做白名单裁剪，防止调用方误传敏感字段；
        2. 再从 context 中提取 tenant/project/run/session 等控制面定位信息；
        3. 最后在线程锁内分配 sequence、写入索引并执行容量驱逐。
        """

        safe_graph_run = low_sensitive_execution_graph_summary(graph_run_summary)
        safe_context = _safe_context(context or {})
        thread_id = _checkpoint_thread_id(safe_context)
        with self._lock:
            self._sequence_by_thread[thread_id] += 1
            checkpoint = ToolActionExecutionCheckpoint(
                checkpoint_id=f"tool-action-checkpoint:{uuid4().hex}",
                thread_id=thread_id,
                sequence=self._sequence_by_thread[thread_id],
                saved_at=datetime.now(timezone.utc).isoformat(),
                source=safe_context.get("source"),
                protocol_family=safe_context.get("protocolFamily"),
                tenant_id=safe_context.get("tenantId"),
                project_id=safe_context.get("projectId"),
                actor_id=safe_context.get("actorId"),
                request_id=safe_context.get("requestId"),
                run_id=safe_context.get("runId"),
                session_id=safe_context.get("sessionId"),
                step_count=_int_or_zero(safe_graph_run.get("stepCount")),
                status_counts=_int_mapping(safe_graph_run.get("statusCounts")),
                resume_requirements=_string_tuple(safe_graph_run.get("resumeRequirements")),
                graph_run_summary=safe_graph_run,
            )
            self._by_id[checkpoint.checkpoint_id] = checkpoint
            self._ids_by_thread[thread_id].append(checkpoint.checkpoint_id)
            self._trim_thread(thread_id)
            self._trim_total()
            return checkpoint

    def get(self, checkpoint_id: str) -> ToolActionExecutionCheckpoint | None:
        """读取单个检查点。

        读取时不刷新 LRU 顺序，是因为这里的顺序表达“写入时间线”，不是缓存热度。
        后续如果替换为 Redis/MySQL，也应优先保持时间线语义，避免审计视图因为读取行为改变顺序。
        """

        with self._lock:
            return self._by_id.get(checkpoint_id)

    def list_by_thread(self, thread_id: str, *, limit: int = 20) -> tuple[ToolActionExecutionCheckpoint, ...]:
        """读取某个恢复线程最近的检查点。

        返回顺序为从旧到新，便于调用方按 sequence 观察状态如何推进。
        如果 UI 想展示“最新在上”，应在展示层反转，而不是改变 store 的时间线语义。
        """

        safe_thread_id = _text(thread_id) or DEFAULT_CHECKPOINT_THREAD_ID
        safe_limit = max(1, int(limit or 20))
        with self._lock:
            ids = list(self._ids_by_thread.get(safe_thread_id, ()))
            selected = ids[-safe_limit:]
            return tuple(self._by_id[item] for item in selected if item in self._by_id)

    def _trim_thread(self, thread_id: str) -> None:
        """限制单个 thread 的检查点数量。

        单线程无限保存会让一次异常循环快速耗尽内存，所以保留最近 N 个检查点即可。
        被驱逐的检查点同时从全局索引删除，避免悬挂引用。
        """

        ids = self._ids_by_thread[thread_id]
        while len(ids) > self._max_checkpoints_per_thread:
            old_id = ids.popleft()
            self._by_id.pop(old_id, None)

    def _trim_total(self) -> None:
        """限制全局检查点数量。

        全局驱逐按写入顺序删除最旧检查点，同时同步清理 thread 索引。
        这里不根据 tenant/project 分配配额，因为当前只是本地 memory store；生产版应加入租户级配额和 TTL。
        """

        while len(self._by_id) > self._max_total_checkpoints:
            old_id, old_checkpoint = self._by_id.popitem(last=False)
            ids = self._ids_by_thread.get(old_checkpoint.thread_id)
            if ids is not None:
                try:
                    ids.remove(old_id)
                except ValueError:
                    pass
                if not ids:
                    self._ids_by_thread.pop(old_checkpoint.thread_id, None)


def low_sensitive_execution_graph_summary(graph_run_summary: Mapping[str, Any]) -> dict[str, Any]:
    """对白名单字段进行二次裁剪，生成可保存的低敏 graph run 摘要。

    这一步非常关键：即使当前 runner 的 `to_summary()` 已经是低敏的，store 仍然不能盲信调用方。
    未来可能有新的调用路径、测试夹具或第三方 adapter 误把原始 arguments/prompt/SQL 放进摘要，
    因此 checkpoint store 必须在保存前再做一次 fail-safe 白名单。
    """

    if not isinstance(graph_run_summary, Mapping):
        return {}
    steps = _safe_steps(graph_run_summary.get("steps"))
    return {
        "schemaVersion": _text(graph_run_summary.get("schemaVersion")),
        "previewOnly": bool(graph_run_summary.get("previewOnly", True)),
        "executionBoundary": _text(graph_run_summary.get("executionBoundary")),
        "stepCount": _int_or_zero(graph_run_summary.get("stepCount")),
        "truncatedCount": _int_or_zero(graph_run_summary.get("truncatedCount")),
        "statusCounts": _int_mapping(graph_run_summary.get("statusCounts")),
        "steps": steps,
        "sideEffectBoundary": _safe_side_effect_boundary(graph_run_summary.get("sideEffectBoundary")),
        "resumeRequirements": _string_tuple(graph_run_summary.get("resumeRequirements")),
        "payloadPolicy": CHECKPOINT_PAYLOAD_POLICY,
    }


def _safe_steps(value: Any) -> tuple[dict[str, Any], ...]:
    """裁剪执行图步骤列表。

    step 只允许保存节点状态和低敏 proposal 提交摘要，不允许保存 `arguments`、`payload`、`sql`、`prompt` 等未知字段。
    """

    if not isinstance(value, (list, tuple)):
        return ()
    return tuple(_safe_step(item) for item in value if isinstance(item, Mapping))


def _safe_step(step: Mapping[str, Any]) -> dict[str, Any]:
    """裁剪单个执行图节点。"""

    return {
        "nodeType": _text(step.get("nodeType")),
        "templateId": _text(step.get("templateId")),
        "toolName": _text(step.get("toolName")),
        "planIndex": _optional_int(step.get("planIndex")),
        "decision": _text(step.get("decision")),
        "outboxPreflightCandidate": bool(step.get("outboxPreflightCandidate")),
        "payloadPolicy": _text(step.get("payloadPolicy")),
        "stepStatus": _text(step.get("stepStatus")),
        "proposalSubmission": _safe_proposal_submission(step.get("proposalSubmission")),
        "nextAction": _text(step.get("nextAction")),
    }


def _safe_proposal_submission(value: Any) -> dict[str, Any]:
    """裁剪 Java proposal client 摘要。

    requestPayload 只保留控制面 ID、payloadReference 和策略字段；
    javaProposal 只保留 Java 响应白名单字段；未知字段全部丢弃。
    """

    if not isinstance(value, Mapping):
        return {}
    return {
        "schemaVersion": _text(value.get("schemaVersion")),
        "submitted": bool(value.get("submitted")),
        "skipped": bool(value.get("skipped")),
        "submissionState": _text(value.get("submissionState")),
        "skipReason": _text(value.get("skipReason")),
        "templateId": _text(value.get("templateId")),
        "targetRoute": _text(value.get("targetRoute")),
        "payloadPolicy": _text(value.get("payloadPolicy")),
        "requestPayload": _safe_request_payload(value.get("requestPayload")),
        "javaProposal": _safe_java_proposal(value.get("javaProposal")),
        "missingEvidence": _string_tuple(value.get("missingEvidence")),
        "rejectedEvidence": _string_tuple(value.get("rejectedEvidence")),
        "errorType": _text(value.get("errorType")),
        "errorCode": _text(value.get("errorCode")),
    }


def _safe_request_payload(value: Any) -> dict[str, Any]:
    """裁剪 proposal 请求体摘要。

    这些字段只能用于恢复定位和幂等判断；工具真实参数必须保存在受控 payload store 中，并通过 payloadReference 间接引用。
    """

    if not isinstance(value, Mapping):
        return {}
    return {
        "graphId": _text(value.get("graphId")),
        "contractId": _text(value.get("contractId")),
        "tenantId": _text(value.get("tenantId")),
        "projectId": _text(value.get("projectId")),
        "actorId": _text(value.get("actorId")),
        "requestId": _text(value.get("requestId")),
        "runId": _text(value.get("runId")),
        "sessionId": _text(value.get("sessionId")),
        "afterSequence": _optional_int(value.get("afterSequence")),
        "limit": _optional_int(value.get("limit")),
        "payloadReference": _text(value.get("payloadReference")),
        "approvalConfirmationId": _text(value.get("approvalConfirmationId")),
        "clarificationFactId": _text(value.get("clarificationFactId")),
        "policyVersion": _text(value.get("policyVersion")),
        "commandSchemaVersion": _text(value.get("commandSchemaVersion")),
        "workerReceiptMode": _text(value.get("workerReceiptMode")),
        "clientRequestId": _text(value.get("clientRequestId")),
    }


def _safe_java_proposal(value: Any) -> dict[str, Any]:
    """裁剪 Java proposal 响应摘要。"""

    if not isinstance(value, Mapping):
        return {}
    return {
        "proposalId": _text(value.get("proposalId")),
        "proposalState": _text(value.get("proposalState")),
        "outboxWriteAllowedByPreflight": bool(value.get("outboxWriteAllowedByPreflight")),
        "graphId": _text(value.get("graphId")),
        "contractId": _text(value.get("contractId")),
        "toolName": _text(value.get("toolName")),
        "commandType": _text(value.get("commandType")),
        "payloadReference": _text(value.get("payloadReference")),
        "payloadPolicy": _text(value.get("payloadPolicy")),
        "workerReceiptRequired": bool(value.get("workerReceiptRequired")),
        "workerReceiptMode": _text(value.get("workerReceiptMode")),
        "graphState": _text(value.get("graphState")),
        "terminalState": bool(value.get("terminalState")),
        "recommendedActions": _string_tuple(value.get("recommendedActions")),
    }


def _safe_side_effect_boundary(value: Any) -> dict[str, Any]:
    """裁剪副作用边界说明，固定不保存真实副作用正文。"""

    if not isinstance(value, Mapping):
        return {}
    return {
        "toolExecuted": bool(value.get("toolExecuted")),
        "outboxWritten": bool(value.get("outboxWritten")),
        "workerDispatched": bool(value.get("workerDispatched")),
        "approvalCreated": bool(value.get("approvalCreated")),
        "checkpointPersisted": bool(value.get("checkpointPersisted")),
        "meaning": _text(value.get("meaning")),
    }


def _safe_context(context: Mapping[str, Any]) -> dict[str, str | None]:
    """从请求上下文中提取可保存的控制面字段。"""

    return {
        "source": _text(context.get("source")),
        "protocolFamily": _text(context.get("protocolFamily")),
        "tenantId": _text(context.get("tenantId")),
        "projectId": _text(context.get("projectId")),
        "actorId": _text(context.get("actorId")),
        "requestId": _text(context.get("requestId")),
        "runId": _text(context.get("runId")),
        "sessionId": _text(context.get("sessionId")),
        "threadId": _text(context.get("threadId") or context.get("checkpointThreadId")),
    }


def _checkpoint_thread_id(context: Mapping[str, Any]) -> str:
    """选择检查点恢复线程 ID。

    优先级说明：
    - 显式 threadId/checkpointThreadId 最清晰，适合未来 durable graph runner 传入；
    - runId/sessionId/requestId 可兼容当前 API 预览场景；
    - 都没有时回退到固定默认值，保证本地调试不会因为缺 ID 直接失败。
    """

    return (
        _text(context.get("threadId"))
        or _text(context.get("runId"))
        or _text(context.get("sessionId"))
        or _text(context.get("requestId"))
        or DEFAULT_CHECKPOINT_THREAD_ID
    )


def _int_mapping(value: Any) -> dict[str, int]:
    """把低基数字典安全转换为 `str -> int`。"""

    if not isinstance(value, Mapping):
        return {}
    result: dict[str, int] = {}
    for key, item in value.items():
        text_key = _text(key)
        if text_key:
            result[text_key] = _int_or_zero(item)
    return result


def _string_tuple(value: Any) -> tuple[str, ...]:
    """把外部列表安全转换为字符串 tuple，并过滤空值。"""

    if not isinstance(value, (list, tuple)):
        return ()
    return tuple(item for item in (_text(item) for item in value) if item)


def _int_or_zero(value: Any) -> int:
    """解析整数，非法值按 0 处理。"""

    try:
        return int(value)
    except (TypeError, ValueError):
        return 0


def _optional_int(value: Any) -> int | None:
    """解析可选整数，非法值返回 None。"""

    if value is None or str(value).strip() == "":
        return None
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def _text(value: Any) -> str | None:
    """解析非空字符串。"""

    if value is None:
        return None
    text = str(value).strip()
    return text or None
