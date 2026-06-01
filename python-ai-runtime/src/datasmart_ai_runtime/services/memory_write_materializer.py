"""已批准长期记忆候选的正式落成服务。

候选审批和正式写入是两个不同的业务动作：
- 审批回答“这条低敏摘要是否允许进入后续写入流程”；
- materialize 回答“后台 worker 是否已经把批准结果幂等写入正式记忆 store”。

把两者拆开可以避免审批 HTTP 请求直接承担向量化、图谱更新、对象索引写入或远端存储抖动。
未来该服务可以被 Kafka consumer、定时补偿任务或 Java task-management 调用，而不需要修改审批状态机。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from enum import Enum
from typing import Any
from uuid import NAMESPACE_URL, uuid5

from datasmart_ai_runtime.domain.memory import (
    AgentMemoryRecord,
    AgentMemoryScope,
    AgentMemoryWriteCandidate,
    AgentMemoryWriteCandidateStatus,
)
from datasmart_ai_runtime.services.memory_store import (
    AgentMemoryStore,
    AgentMemoryStoreEntry,
)
from datasmart_ai_runtime.services.memory_materialization_receipt_store import (
    AgentMemoryMaterializationReceiptStore,
    InMemoryAgentMemoryMaterializationReceiptStore,
)
from datasmart_ai_runtime.services.memory_write_candidate_store import AgentMemoryWriteCandidateStore
from datasmart_ai_runtime.services.memory_write_workspace import AgentMemoryWorkspaceSupport


class AgentMemoryMaterializationOutcome(str, Enum):
    """正式记忆写入结果。

    `ALREADY_MATERIALIZED` 是正常幂等结果，不应被监控系统误判为失败。
    """

    MATERIALIZED = "materialized"
    ALREADY_MATERIALIZED = "already_materialized"


@dataclass(frozen=True)
class AgentMemoryMaterializationResult:
    """单条候选落成结果。

    返回值只包含低敏控制面信息，不返回正式记忆正文。后台任务、审计台和未来指标组件可以用它统计写入量、
    重复消费量和命名空间分布，而不会把工具结果扩散到日志或消息队列。
    """

    candidate_id: str
    memory_id: str
    namespace: tuple[str, ...]
    outcome: AgentMemoryMaterializationOutcome
    message: str
    attributes: dict[str, Any] = field(default_factory=dict)

    def to_summary(self) -> dict[str, Any]:
        """转换为任务回调或诊断接口可使用的低敏摘要。"""

        return {
            "candidateId": self.candidate_id,
            "memoryId": self.memory_id,
            "namespace": self.namespace,
            "outcome": self.outcome.value,
            "message": self.message,
            "attributes": dict(self.attributes),
        }


class AgentApprovedMemoryWriteMaterializer:
    """把 APPROVED 候选幂等落成正式长期记忆。

    第一阶段采用保守策略：
    - 只保存候选表里的 `content_summary`，不读取或复制原始 outputRef；
    - 只允许 `public/internal` 摘要进入正式 store；
    - 支持 PROJECT 和 TENANT 范围；
    - 暂不支持 SESSION，因为 session 级长期记忆还需要更明确的生命周期、过期和会话归档策略；
    - 暂不支持 GLOBAL，因为组织级共享记忆应增加独立审批、只读策略和 prompt-injection 防护。

    这些限制不是功能缺失被忽略，而是商业化安全边界。后续扩大写入范围时必须配套 workspace namespace、
    脱敏流水线、字段级策略、遗忘任务和审计导出。当前已经强制校验 workspaceKey/memoryNamespace，
    避免同项目不同工作空间把彼此的治理经验错误注入模型上下文。
    """

    SAFE_SENSITIVITY_LEVELS = {"public", "internal"}
    SUPPORTED_SCOPES = {AgentMemoryScope.PROJECT, AgentMemoryScope.TENANT}

    def __init__(
        self,
        *,
        candidate_store: AgentMemoryWriteCandidateStore,
        memory_store: AgentMemoryStore,
        receipt_store: AgentMemoryMaterializationReceiptStore | None = None,
        worker_id: str = "python-ai-runtime-inline",
    ) -> None:
        self._candidate_store = candidate_store
        self._memory_store = memory_store
        self._receipt_store = receipt_store or InMemoryAgentMemoryMaterializationReceiptStore()
        self._worker_id = worker_id

    def materialize(self, candidate_id: str) -> AgentMemoryMaterializationResult:
        """幂等处理一条已批准候选。

        状态检查必须发生在正式写入前。即使调用方来自内部 worker，也不能假设消息一定可信：
        重试消息可能已经过期，误投消息可能仍是 DRAFT，人工审批也可能尚未完成。
        """

        candidate = self._candidate_store.get(candidate_id)
        if candidate is None:
            raise KeyError(f"记忆写入候选不存在: {candidate_id}")
        workspace = AgentMemoryWorkspaceSupport.validate_candidate(candidate)
        receipt = self._receipt_store.begin(
            candidate_id=candidate.candidate_id,
            tenant_id=candidate.tenant_id,
            project_id=candidate.project_id,
            workspace_key=workspace.workspace_key,
            memory_namespace=workspace.memory_namespace,
            worker_id=self._worker_id,
        )
        try:
            self._validate_candidate(candidate)
            entry = self._entry_from_candidate(candidate)
            write_result = self._memory_store.save_if_absent(entry)
            outcome = (
                AgentMemoryMaterializationOutcome.MATERIALIZED
                if write_result.created
                else AgentMemoryMaterializationOutcome.ALREADY_MATERIALIZED
            )
            message = (
                "已把批准候选的低敏摘要写入正式长期记忆 store。"
                if write_result.created
                else "该批准候选已经落成正式长期记忆，本次按幂等语义复用已有记录。"
            )
            self._receipt_store.succeed(
                receipt_id=receipt.receipt_id,
                memory_id=write_result.entry.memory.memory_id,
                namespace=write_result.entry.namespace,
                outcome=outcome.value,
                message=message,
            )
            return self._result(
                write_result.entry,
                outcome,
                message,
                receipt_id=receipt.receipt_id,
            )
        except Exception as exc:
            self._receipt_store.fail(receipt_id=receipt.receipt_id, error_message=f"{type(exc).__name__}: {exc}")
            raise

    def materialize_approved(self, *, limit: int = 100) -> tuple[AgentMemoryMaterializationResult, ...]:
        """批量处理一个有界 APPROVED 窗口。

        当前方法适合本地补偿和后续后台 worker 调用。每轮最多处理 100 条，避免单轮无限扫描占满 Python Runtime。
        真正生产化时应增加 outbox/inbox、租约、失败退避、DLQ、批次指标和多实例竞争控制。
        """

        safe_limit = max(1, min(limit, 100))
        candidates = self._candidate_store.list(status=AgentMemoryWriteCandidateStatus.APPROVED, limit=safe_limit)
        return tuple(self.materialize(candidate.candidate_id) for candidate in candidates)

    def _validate_candidate(self, candidate: AgentMemoryWriteCandidate) -> None:
        """校验候选是否满足第一阶段正式写入边界。"""

        if candidate.status != AgentMemoryWriteCandidateStatus.APPROVED:
            raise ValueError(f"只有 approved 候选可以落成正式记忆，当前状态为: {candidate.status.value}")
        if candidate.scope not in self.SUPPORTED_SCOPES:
            raise ValueError(f"当前正式记忆 materializer 暂不支持 scope={candidate.scope.value}")
        if candidate.sensitivity_level not in self.SAFE_SENSITIVITY_LEVELS:
            raise ValueError(
                f"敏感级别 {candidate.sensitivity_level} 尚未接入脱敏流水线，不能直接写入正式长期记忆。"
            )
        if candidate.retention_days <= 0:
            raise ValueError("正式记忆 retentionDays 必须大于 0，便于后续执行遗忘或归档。")
        if not candidate.content_summary.strip():
            raise ValueError("正式记忆必须包含低敏 contentSummary，不能写入空内容。")
        AgentMemoryWorkspaceSupport.validate_candidate(candidate)

    def _entry_from_candidate(self, candidate: AgentMemoryWriteCandidate) -> AgentMemoryStoreEntry:
        """把批准候选转换为正式记忆存储信封。

        注意这里只复制治理摘要。`output_ref` 只转换成布尔标记，不把 MinIO 路径、Java audit 引用或外部 URI
        直接塞进模型可检索正文。后续需要读取原始资源时，必须继续经过资源引用 resolver 和上下文准入策略。
        """

        now = datetime.now(timezone.utc)
        idempotency_key = candidate.idempotency_key or candidate.candidate_id
        memory_id = f"memory-{uuid5(NAMESPACE_URL, idempotency_key)}"
        workspace = AgentMemoryWorkspaceSupport.validate_candidate(candidate)
        namespace = self._namespace(candidate, workspace.memory_namespace)
        record = AgentMemoryRecord(
            memory_id=memory_id,
            memory_type=candidate.memory_type,
            scope=candidate.scope,
            tenant_id=candidate.tenant_id,
            project_id=candidate.project_id,
            title=candidate.title,
            content=candidate.content_summary,
            source=candidate.source,
            sensitivity_level=candidate.sensitivity_level,
            tags=tuple(filter(None, (candidate.source_tool_name, candidate.memory_type.value, "approved-summary"))),
            created_at=now,
            attributes={
                "sourceCandidateId": candidate.candidate_id,
                "sourceAuditId": candidate.source_audit_id,
                "sourceRunId": candidate.source_run_id,
                "outputReferenceAvailable": bool(candidate.output_ref),
                "retentionDays": candidate.retention_days,
                "workspaceKey": workspace.workspace_key,
                "memoryNamespace": workspace.memory_namespace,
                "payloadPolicy": "SUMMARY_ONLY_NO_RAW_TOOL_RESULT_NO_SQL_NO_SAMPLE_DATA",
            },
        )
        return AgentMemoryStoreEntry(
            memory=record,
            workspace_key=workspace.workspace_key,
            memory_namespace=workspace.memory_namespace,
            namespace=namespace,
            idempotency_key=idempotency_key,
            source_candidate_id=candidate.candidate_id,
            expires_at=now + timedelta(days=candidate.retention_days),
            materialized_at=now,
        )

    @staticmethod
    def _namespace(candidate: AgentMemoryWriteCandidate, memory_namespace: str) -> tuple[str, ...]:
        """生成层级命名空间。

        正式 store 同时保存字符串形态的 `memory_namespace` 和 tuple 形态的 `namespace`：
        - 字符串适合 API、SQL 字段、审计日志和配置化过滤；
        - tuple 适合未来映射到 LangGraph store、向量库 collection 分层或对象存储前缀。

        即使候选 scope 是 TENANT，也仍然保留 workspace namespace。原因是企业产品中的“租户级经验”
        也可能来自某个受控工作空间，未来是否晋升为全租户共享应由独立审批策略决定，而不是写入时自动放大。
        """

        return ("memory-namespace", memory_namespace, "type", candidate.memory_type.value)

    @staticmethod
    def _result(
        entry: AgentMemoryStoreEntry,
        outcome: AgentMemoryMaterializationOutcome,
        message: str,
        receipt_id: str,
    ) -> AgentMemoryMaterializationResult:
        """构造低敏落成结果。"""

        return AgentMemoryMaterializationResult(
            candidate_id=entry.source_candidate_id,
            memory_id=entry.memory.memory_id,
            namespace=entry.namespace,
            outcome=outcome,
            message=message,
            attributes={
                "memoryType": entry.memory.memory_type.value,
                "scope": entry.memory.scope.value,
                "workspaceKey": entry.workspace_key,
                "memoryNamespace": entry.memory_namespace,
                "receiptId": receipt_id,
                "expiresAt": entry.expires_at.isoformat(),
            },
        )
