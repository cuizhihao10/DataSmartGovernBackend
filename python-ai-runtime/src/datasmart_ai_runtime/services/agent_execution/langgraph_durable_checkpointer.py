"""LangGraph Durable Checkpointer 领域模型与内存实现。

DataSmart 的 Agent 执行层要从“固定流水线”收敛为真正的状态机：节点是可替换工作单元，边决定分支、
循环和人工介入，状态让执行现场可以暂停、恢复和跨 Agent 协作。PostgreSQL 表结构已经在
`ai_memory.langgraph_thread_checkpoint` 与 `ai_memory.langgraph_checkpoint_event` 中固定，本模块先把
这些语义抽象成稳定 Python 合同：

- checkpoint 保存图状态、当前节点、下一批候选节点和恢复条件；
- event 记录节点、边、暂停、恢复、分支、循环等低敏审计事件；
- service 提供暂停、恢复、分支、循环和多 Agent 状态恢复方法；
- 默认内存实现服务单测和本地学习，PostgreSQL 实现放在独立文件中，避免领域层绑定 psycopg。

重要安全边界：checkpoint state 只能保存恢复所需的低敏摘要，禁止保存完整 prompt、工具参数、SQL、
模型输出、凭据、token、远端 endpoint 或大对象正文。
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field, replace
from datetime import datetime, timezone
from enum import Enum
from threading import RLock
from typing import Any, Mapping, Protocol
from uuid import uuid4


LANGGRAPH_DURABLE_CHECKPOINT_SCHEMA_VERSION = "datasmart.langgraph-durable-checkpoint.v1"
LANGGRAPH_CHECKPOINT_EVENT_SCHEMA_VERSION = "datasmart.langgraph-checkpoint-event.v1"


class LangGraphCheckpointStatus(str, Enum):
    """LangGraph checkpoint 状态。

    状态与 PostgreSQL check constraint 对齐：
    - `RUNNING`：图仍可继续执行；
    - `WAITING_HUMAN`：等待审批、澄清或人工接管；
    - `WAITING_TOOL`：等待 Java outbox、worker receipt 或外部工具结果；
    - `PAUSED`：主动暂停，通常用于人审、限流、容量保护或用户暂停；
    - `COMPLETED`：图线程已完成；
    - `FAILED`：图执行失败，需要重试、分支或人工恢复；
    - `CANCELLED`：用户或控制面取消。
    """

    RUNNING = "running"
    WAITING_HUMAN = "waiting_human"
    WAITING_TOOL = "waiting_tool"
    PAUSED = "paused"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"


@dataclass(frozen=True)
class LangGraphDurableCheckpoint:
    """LangGraph 图线程的低敏状态快照。

    字段说明：
    - `checkpoint_id/thread_id/parent_checkpoint_id`：恢复、分支和审计定位字段；
    - `tenant_id/project_id/actor_id/workspace_key`：权限、隔离和缓存边界；
    - `graph_name/graph_version/node_name`：说明当前状态属于哪张图、哪个版本、停在哪个节点；
    - `status/checkpoint_version`：状态机阶段和同一 thread 内的递增版本；
    - `state`：恢复状态摘要，必须低敏；
    - `next_nodes`：后续候选节点，支持分支和循环恢复；
    - `resume_requirements`：恢复前必须满足的 host facts，例如审批、worker receipt、预算或人工确认；
    - `low_sensitive_summary`：给前端/运维看的短摘要，不保存敏感正文。
    """

    checkpoint_id: str
    thread_id: str
    graph_name: str
    graph_version: str
    node_name: str
    status: LangGraphCheckpointStatus
    state: dict[str, Any]
    next_nodes: tuple[str, ...] = ()
    resume_requirements: dict[str, Any] = field(default_factory=dict)
    parent_checkpoint_id: str | None = None
    tenant_id: str | None = None
    project_id: str | None = None
    actor_id: str | None = None
    workspace_key: str | None = None
    run_id: str | None = None
    session_id: str | None = None
    checkpoint_version: int = 1
    low_sensitive_summary: str | None = None
    expires_at: datetime | None = None
    created_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    updated_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))

    def to_summary(self) -> dict[str, Any]:
        """输出可进入 API、诊断或事件的低敏摘要。"""

        return {
            "schemaVersion": LANGGRAPH_DURABLE_CHECKPOINT_SCHEMA_VERSION,
            "checkpointId": self.checkpoint_id,
            "threadId": self.thread_id,
            "parentCheckpointId": self.parent_checkpoint_id,
            "tenantId": self.tenant_id,
            "projectId": self.project_id,
            "workspaceKey": self.workspace_key,
            "runId": self.run_id,
            "sessionId": self.session_id,
            "graphName": self.graph_name,
            "graphVersion": self.graph_version,
            "nodeName": self.node_name,
            "status": self.status.value,
            "checkpointVersion": self.checkpoint_version,
            "nextNodes": self.next_nodes,
            "resumeRequirementKeys": tuple(sorted(self.resume_requirements.keys())),
            "stateTopLevelKeys": tuple(sorted(self.state.keys())),
            "lowSensitiveSummary": self.low_sensitive_summary,
            "createdAt": self.created_at.isoformat(),
            "updatedAt": self.updated_at.isoformat(),
            "payloadPolicy": "LOW_SENSITIVE_LANGGRAPH_CHECKPOINT_SUMMARY_ONLY",
        }


@dataclass(frozen=True)
class LangGraphCheckpointEvent:
    """LangGraph checkpoint 事件。

    事件用于解释“图为什么停在这里、从哪条边走到这里、是否发生分支/循环/恢复”。它不保存状态正文，
    只保存低敏 attributes，便于 Prometheus、审计回放、WebSocket replay 或 Java projection 使用。
    """

    event_id: str
    checkpoint_id: str
    thread_id: str
    event_type: str
    sequence_number: int
    tenant_id: str | None = None
    project_id: str | None = None
    run_id: str | None = None
    node_name: str | None = None
    edge_name: str | None = None
    attributes: dict[str, Any] = field(default_factory=dict)
    created_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))

    def to_summary(self) -> dict[str, Any]:
        """输出低敏事件摘要。"""

        return {
            "schemaVersion": LANGGRAPH_CHECKPOINT_EVENT_SCHEMA_VERSION,
            "eventId": self.event_id,
            "checkpointId": self.checkpoint_id,
            "threadId": self.thread_id,
            "eventType": self.event_type,
            "sequenceNumber": self.sequence_number,
            "nodeName": self.node_name,
            "edgeName": self.edge_name,
            "attributeKeys": tuple(sorted(self.attributes.keys())),
            "createdAt": self.created_at.isoformat(),
            "payloadPolicy": "LOW_SENSITIVE_LANGGRAPH_CHECKPOINT_EVENT_ONLY",
        }


@dataclass(frozen=True)
class MultiAgentRecoveredState:
    """从 checkpoint 中恢复出的多 Agent 低敏状态。

    `agent_roles` 和 `agent_statuses` 用于快速判断哪些 Agent 已完成、等待、失败或需要 handoff；真实 prompt、
    工具参数和模型输出仍然必须通过受控审计/事件/工件系统查询，不能放进这里。
    """

    found: bool
    thread_id: str
    checkpoint_id: str | None = None
    graph_name: str | None = None
    status: str | None = None
    agent_roles: tuple[str, ...] = ()
    agent_statuses: dict[str, str] = field(default_factory=dict)
    collaboration_edges: tuple[dict[str, Any], ...] = ()
    handoff_required: bool = False

    def to_summary(self) -> dict[str, Any]:
        """输出多 Agent 恢复摘要。"""

        return {
            "found": self.found,
            "threadId": self.thread_id,
            "checkpointId": self.checkpoint_id,
            "graphName": self.graph_name,
            "status": self.status,
            "agentRoles": self.agent_roles,
            "agentStatuses": dict(self.agent_statuses),
            "collaborationEdgeCount": len(self.collaboration_edges),
            "handoffRequired": self.handoff_required,
            "payloadPolicy": "LOW_SENSITIVE_MULTI_AGENT_RECOVERY_SUMMARY_ONLY",
        }


class LangGraphCheckpointStore(Protocol):
    """LangGraph checkpoint 仓储协议。"""

    def save_checkpoint(self, checkpoint: LangGraphDurableCheckpoint) -> LangGraphDurableCheckpoint:
        """保存 checkpoint。"""

    def get_checkpoint(self, checkpoint_id: str) -> LangGraphDurableCheckpoint | None:
        """按 checkpointId 查询。"""

    def latest_for_thread(self, thread_id: str) -> LangGraphDurableCheckpoint | None:
        """查询某个 thread 最新 checkpoint。"""

    def append_event(self, event: LangGraphCheckpointEvent) -> LangGraphCheckpointEvent:
        """追加低敏 checkpoint event。"""

    def events_for_thread(self, thread_id: str) -> tuple[LangGraphCheckpointEvent, ...]:
        """按 thread 查询事件。"""

    def next_sequence(self, thread_id: str) -> int:
        """返回 thread 下一条事件序号。"""

    def diagnostics(self) -> dict[str, Any]:
        """返回仓储诊断。"""


class InMemoryLangGraphCheckpointStore:
    """线程安全内存版 LangGraph checkpoint store。

    该实现用于本地学习和单元测试。它与 PostgreSQL store 使用同一协议，因此上层 service 不需要知道底层
    存储差异。生产环境应使用 PostgreSQL，以支持跨实例、跨重启和审计留存。
    """

    def __init__(self) -> None:
        self._lock = RLock()
        self._checkpoints_by_id: dict[str, LangGraphDurableCheckpoint] = {}
        self._thread_index: dict[str, list[str]] = {}
        self._events_by_thread: dict[str, list[LangGraphCheckpointEvent]] = {}

    def save_checkpoint(self, checkpoint: LangGraphDurableCheckpoint) -> LangGraphDurableCheckpoint:
        """保存 checkpoint，并维护 thread 最新版本索引。"""

        with self._lock:
            existing = self._checkpoints_by_id.get(checkpoint.checkpoint_id)
            if existing:
                checkpoint = replace(
                    checkpoint,
                    created_at=existing.created_at,
                    updated_at=datetime.now(timezone.utc),
                )
            self._checkpoints_by_id[checkpoint.checkpoint_id] = checkpoint
            ids = self._thread_index.setdefault(checkpoint.thread_id, [])
            if checkpoint.checkpoint_id not in ids:
                ids.append(checkpoint.checkpoint_id)
            ids.sort(key=lambda item: self._checkpoints_by_id[item].checkpoint_version)
            return checkpoint

    def get_checkpoint(self, checkpoint_id: str) -> LangGraphDurableCheckpoint | None:
        """按 checkpointId 精确读取。"""

        with self._lock:
            return self._checkpoints_by_id.get(checkpoint_id)

    def latest_for_thread(self, thread_id: str) -> LangGraphDurableCheckpoint | None:
        """读取 thread 最新 checkpoint。"""

        with self._lock:
            ids = self._thread_index.get(thread_id) or []
            if not ids:
                return None
            return max((self._checkpoints_by_id[item] for item in ids), key=lambda item: item.checkpoint_version)

    def append_event(self, event: LangGraphCheckpointEvent) -> LangGraphCheckpointEvent:
        """追加低敏事件。"""

        with self._lock:
            events = self._events_by_thread.setdefault(event.thread_id, [])
            events.append(event)
            events.sort(key=lambda item: item.sequence_number)
            return event

    def events_for_thread(self, thread_id: str) -> tuple[LangGraphCheckpointEvent, ...]:
        """按 thread 读取事件序列。"""

        with self._lock:
            return tuple(self._events_by_thread.get(thread_id) or ())

    def next_sequence(self, thread_id: str) -> int:
        """返回下一条事件序号。"""

        with self._lock:
            return len(self._events_by_thread.get(thread_id) or ()) + 1

    def diagnostics(self) -> dict[str, Any]:
        """返回低敏诊断。"""

        with self._lock:
            status_counts: dict[str, int] = {}
            for checkpoint in self._checkpoints_by_id.values():
                status_counts[checkpoint.status.value] = status_counts.get(checkpoint.status.value, 0) + 1
            return {
                "storeType": "in_memory",
                "checkpointCount": len(self._checkpoints_by_id),
                "threadCount": len(self._thread_index),
                "eventCount": sum(len(items) for items in self._events_by_thread.values()),
                "statusCounts": dict(sorted(status_counts.items())),
                "payloadPolicy": "LOW_SENSITIVE_LANGGRAPH_CHECKPOINT_ONLY",
            }


class LangGraphDurableCheckpointerService:
    """LangGraph durable checkpoint 业务服务。

    服务层负责状态机语义，仓储层只负责保存。当前提供五类闭环能力：
    - `record_checkpoint`：记录任意节点状态；
    - `pause`：把最新状态停在可恢复暂停点；
    - `resume`：恢复到 running 并记录恢复事实摘要；
    - `fork_branch`：从任意 checkpoint 派生分支 thread；
    - `record_loop_iteration`：在同一 thread 内推进循环版本；
    - `recover_multi_agent_state`：从 checkpoint state 中恢复多 Agent 角色状态。
    """

    def __init__(self, store: LangGraphCheckpointStore | None = None) -> None:
        self._store = store or InMemoryLangGraphCheckpointStore()

    def record_checkpoint(self, checkpoint: LangGraphDurableCheckpoint, *, event_type: str = "checkpoint_saved") -> LangGraphDurableCheckpoint:
        """保存节点 checkpoint，并追加可观测事件。"""

        saved = self._store.save_checkpoint(checkpoint)
        self._append_event(saved, event_type=event_type, node_name=saved.node_name)
        return saved

    def pause(
        self,
        *,
        thread_id: str,
        reason_code: str,
        resume_requirements: Mapping[str, Any] | None = None,
    ) -> LangGraphDurableCheckpoint:
        """暂停某个 thread 的最新 checkpoint。

        暂停不会修改业务状态正文，只会把状态切换为 `paused` 并记录恢复条件。这样 UI、Java 控制面或
        运维可以明确知道当前不是失败，而是在等待外部事实。
        """

        latest = self._require_latest(thread_id)
        paused = replace(
            latest,
            checkpoint_id=_checkpoint_id(latest.thread_id, "pause"),
            parent_checkpoint_id=latest.checkpoint_id,
            status=LangGraphCheckpointStatus.PAUSED,
            checkpoint_version=latest.checkpoint_version + 1,
            resume_requirements=_safe_mapping(resume_requirements),
            low_sensitive_summary=f"LangGraph thread paused: {_safe_text(reason_code)}",
            updated_at=datetime.now(timezone.utc),
        )
        saved = self._store.save_checkpoint(paused)
        self._append_event(saved, event_type="paused", node_name=saved.node_name, attributes={"reasonCode": _safe_text(reason_code)})
        return saved

    def resume(
        self,
        *,
        thread_id: str,
        resume_facts: Mapping[str, Any] | None = None,
    ) -> LangGraphDurableCheckpoint:
        """恢复某个暂停/等待状态的 thread。

        恢复事实只保存 key 和低敏摘要，不保存审批正文、工具结果正文或人工输入全文。
        """

        latest = self._require_latest(thread_id)
        if latest.status in {LangGraphCheckpointStatus.COMPLETED, LangGraphCheckpointStatus.CANCELLED}:
            raise ValueError("已完成或已取消的 LangGraph thread 不能恢复。")
        state = dict(latest.state)
        state["resumeFactsSummary"] = _resume_facts_summary(resume_facts)
        resumed = replace(
            latest,
            checkpoint_id=_checkpoint_id(latest.thread_id, "resume"),
            parent_checkpoint_id=latest.checkpoint_id,
            status=LangGraphCheckpointStatus.RUNNING,
            checkpoint_version=latest.checkpoint_version + 1,
            state=state,
            resume_requirements={},
            low_sensitive_summary="LangGraph thread resumed from durable checkpoint.",
            updated_at=datetime.now(timezone.utc),
        )
        saved = self._store.save_checkpoint(resumed)
        self._append_event(saved, event_type="resumed", node_name=saved.node_name, attributes={"resumeFactKeys": tuple(sorted((resume_facts or {}).keys()))})
        return saved

    def fork_branch(
        self,
        *,
        parent_checkpoint_id: str,
        branch_name: str,
        next_nodes: tuple[str, ...] | None = None,
    ) -> LangGraphDurableCheckpoint:
        """从指定 checkpoint 派生分支 thread。

        分支能力让 Agent 可以保留主线状态，同时尝试另一条修复、重试或人工确认路径。新分支继承低敏 state，
        但 threadId 独立，避免后续事件序列与主线混在一起。
        """

        parent = self._require_checkpoint(parent_checkpoint_id)
        branch_thread_id = f"{parent.thread_id}:branch:{_safe_key_part(branch_name)}"
        state = dict(parent.state)
        state["branch"] = {
            "name": _safe_text(branch_name),
            "parentThreadId": parent.thread_id,
            "parentCheckpointId": parent.checkpoint_id,
        }
        branch = LangGraphDurableCheckpoint(
            checkpoint_id=_checkpoint_id(branch_thread_id, "branch"),
            thread_id=branch_thread_id,
            parent_checkpoint_id=parent.checkpoint_id,
            tenant_id=parent.tenant_id,
            project_id=parent.project_id,
            actor_id=parent.actor_id,
            workspace_key=parent.workspace_key,
            run_id=parent.run_id,
            session_id=parent.session_id,
            graph_name=parent.graph_name,
            graph_version=parent.graph_version,
            node_name=parent.node_name,
            status=LangGraphCheckpointStatus.RUNNING,
            checkpoint_version=1,
            state=state,
            next_nodes=next_nodes if next_nodes is not None else parent.next_nodes,
            low_sensitive_summary=f"LangGraph branch created from {parent.checkpoint_id}.",
        )
        saved = self._store.save_checkpoint(branch)
        self._append_event(parent, event_type="branch_created", node_name=parent.node_name, attributes={"branchThreadId": branch_thread_id})
        self._append_event(saved, event_type="branch_started", node_name=saved.node_name, attributes={"parentCheckpointId": parent.checkpoint_id})
        return saved

    def record_loop_iteration(
        self,
        *,
        thread_id: str,
        node_name: str,
        edge_name: str,
        state_patch: Mapping[str, Any] | None = None,
    ) -> LangGraphDurableCheckpoint:
        """记录同一 thread 的一次循环迭代。

        循环不是递归函数调用，而是显式 checkpoint 版本推进：每一轮都有 parent、edge、node 和事件序号，
        出问题后可以恢复到任意一次迭代前的状态。
        """

        latest = self._require_latest(thread_id)
        state = {**latest.state, **_safe_mapping(state_patch)}
        state["loopIteration"] = int(state.get("loopIteration") or 0) + 1
        looped = replace(
            latest,
            checkpoint_id=_checkpoint_id(latest.thread_id, "loop"),
            parent_checkpoint_id=latest.checkpoint_id,
            node_name=_safe_text(node_name),
            status=LangGraphCheckpointStatus.RUNNING,
            checkpoint_version=latest.checkpoint_version + 1,
            state=state,
            low_sensitive_summary=f"LangGraph loop iteration via edge {_safe_text(edge_name)}.",
            updated_at=datetime.now(timezone.utc),
        )
        saved = self._store.save_checkpoint(looped)
        self._append_event(saved, event_type="loop_iteration", node_name=saved.node_name, edge_name=_safe_text(edge_name))
        return saved

    def recover_multi_agent_state(self, thread_id: str) -> MultiAgentRecoveredState:
        """从最新 checkpoint 恢复多 Agent 状态摘要。"""

        latest = self._store.latest_for_thread(thread_id)
        if latest is None:
            return MultiAgentRecoveredState(found=False, thread_id=thread_id)
        state = latest.state
        raw_agents = state.get("multiAgentState") or state.get("agents") or {}
        agent_statuses = _agent_statuses(raw_agents)
        edges = tuple(item for item in state.get("collaborationEdges") or () if isinstance(item, Mapping))[:64]
        return MultiAgentRecoveredState(
            found=True,
            thread_id=thread_id,
            checkpoint_id=latest.checkpoint_id,
            graph_name=latest.graph_name,
            status=latest.status.value,
            agent_roles=tuple(sorted(agent_statuses)),
            agent_statuses=agent_statuses,
            collaboration_edges=edges,
            handoff_required=bool(state.get("handoffRequired")),
        )

    def latest_for_thread(self, thread_id: str) -> LangGraphDurableCheckpoint | None:
        """读取 thread 最新 checkpoint。"""

        return self._store.latest_for_thread(thread_id)

    def events_for_thread(self, thread_id: str) -> tuple[LangGraphCheckpointEvent, ...]:
        """读取 thread 事件流。"""

        return self._store.events_for_thread(thread_id)

    def diagnostics(self) -> dict[str, Any]:
        """返回 checkpointer 低敏诊断。"""

        return {
            "component": "langgraph-durable-checkpointer",
            "schemaVersion": LANGGRAPH_DURABLE_CHECKPOINT_SCHEMA_VERSION,
            "store": self._store.diagnostics(),
            "capabilities": {
                "pause": True,
                "resume": True,
                "branch": True,
                "loop": True,
                "multiAgentStateRecovery": True,
                "postgresqlDurableStateTarget": True,
            },
            "payloadPolicy": "LOW_SENSITIVE_LANGGRAPH_CHECKPOINT_ONLY",
        }

    def _append_event(
        self,
        checkpoint: LangGraphDurableCheckpoint,
        *,
        event_type: str,
        node_name: str | None = None,
        edge_name: str | None = None,
        attributes: Mapping[str, Any] | None = None,
    ) -> LangGraphCheckpointEvent:
        event = LangGraphCheckpointEvent(
            event_id=_checkpoint_id(checkpoint.thread_id, event_type),
            checkpoint_id=checkpoint.checkpoint_id,
            thread_id=checkpoint.thread_id,
            tenant_id=checkpoint.tenant_id,
            project_id=checkpoint.project_id,
            run_id=checkpoint.run_id,
            event_type=_safe_text(event_type),
            node_name=node_name,
            edge_name=edge_name,
            sequence_number=self._store.next_sequence(checkpoint.thread_id),
            attributes=_safe_mapping(attributes),
        )
        return self._store.append_event(event)

    def _require_latest(self, thread_id: str) -> LangGraphDurableCheckpoint:
        latest = self._store.latest_for_thread(thread_id)
        if latest is None:
            raise ValueError(f"LangGraph thread 不存在或尚未写入 checkpoint: {thread_id}")
        return latest

    def _require_checkpoint(self, checkpoint_id: str) -> LangGraphDurableCheckpoint:
        checkpoint = self._store.get_checkpoint(checkpoint_id)
        if checkpoint is None:
            raise ValueError(f"LangGraph checkpoint 不存在: {checkpoint_id}")
        return checkpoint


def _checkpoint_id(thread_id: str, marker: str) -> str:
    """生成稳定前缀 + 随机后缀 checkpoint/event id。"""

    return f"lgcp:{_safe_key_part(thread_id)}:{_safe_key_part(marker)}:{uuid4().hex[:12]}"


def _safe_key_part(value: Any) -> str:
    """把外部 ID 压成可用于 checkpoint/event id 的安全片段。"""

    return re.sub(r"[^A-Za-z0-9_.:-]", "_", _safe_text(value))[:96] or "unknown"


def _safe_text(value: Any) -> str:
    """读取低敏短文本。"""

    if value is None:
        return ""
    return str(value).strip()[:256]


def _safe_mapping(value: Mapping[str, Any] | None) -> dict[str, Any]:
    """保留适合 checkpoint attributes 的低敏 JSON 值。"""

    if not isinstance(value, Mapping):
        return {}
    result: dict[str, Any] = {}
    for key, item in value.items():
        text_key = _safe_text(key)
        if not text_key:
            continue
        if isinstance(item, (str, int, float, bool)) or item is None:
            result[text_key] = item if not isinstance(item, str) else item[:512]
        elif isinstance(item, (list, tuple)):
            result[text_key] = tuple(_safe_text(element) for element in item[:32])
        elif isinstance(item, Mapping):
            result[text_key] = {str(nested_key)[:128]: _safe_text(nested_value) for nested_key, nested_value in list(item.items())[:32]}
    return result


def _resume_facts_summary(value: Mapping[str, Any] | None) -> dict[str, Any]:
    """把恢复事实压缩成 key/类型摘要。"""

    if not isinstance(value, Mapping):
        return {}
    return {
        str(key)[:128]: {"present": item is not None, "type": type(item).__name__}
        for key, item in value.items()
    }


def _agent_statuses(value: Any) -> dict[str, str]:
    """从多 Agent state 中提取角色状态。"""

    statuses: dict[str, str] = {}
    if isinstance(value, Mapping):
        items = value.items()
    elif isinstance(value, (list, tuple)):
        items = ((item.get("role") if isinstance(item, Mapping) else None, item) for item in value)
    else:
        return {}
    for role, item in items:
        role_text = _safe_text(role)
        if not role_text:
            continue
        status = "UNKNOWN"
        if isinstance(item, Mapping):
            status = _safe_text(item.get("status") or item.get("phase") or "UNKNOWN")
        statuses[role_text] = status or "UNKNOWN"
    return statuses


__all__ = [
    "LANGGRAPH_CHECKPOINT_EVENT_SCHEMA_VERSION",
    "LANGGRAPH_DURABLE_CHECKPOINT_SCHEMA_VERSION",
    "InMemoryLangGraphCheckpointStore",
    "LangGraphCheckpointEvent",
    "LangGraphCheckpointStatus",
    "LangGraphCheckpointStore",
    "LangGraphDurableCheckpoint",
    "LangGraphDurableCheckpointerService",
    "MultiAgentRecoveredState",
]
