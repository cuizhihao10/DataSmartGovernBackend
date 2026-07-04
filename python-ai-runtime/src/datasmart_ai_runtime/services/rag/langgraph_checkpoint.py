"""RAG 管线到 LangGraph durable checkpoint 的适配层。

RAG V1 已经把检索、证据门控、上下文压缩、生成和引用绑定拆成了可解释管线；本模块继续把这些阶段
投影到 LangGraph durable state 中，让 RAG 不再只是一次 HTTP 调用结果，而是 Agent 图中的可观测节点。

重要边界：
- 这里不重新执行检索、不重新调用模型，只消费 `RagPipelineResult` 的低敏摘要；
- checkpoint state 不保存用户问题、生成答案、compressedContext、文档正文、sourceUri、prompt 或模型原始响应；
- 状态只保存恢复和观测所需的计数、状态码、策略、角色状态与边；
- 后续替换为 PostgreSQL/pgvector、Neo4j GraphRAG 或专用 reranker 时，只要 `RagPipelineResult` 合同稳定，
  LangGraph 节点化逻辑就不需要重写。
"""

from __future__ import annotations

from typing import Any, Mapping
from uuid import uuid4

from datasmart_ai_runtime.services.agent_execution import (
    LangGraphCheckpointStatus,
    LangGraphDurableCheckpoint,
    LangGraphDurableCheckpointerService,
)
from datasmart_ai_runtime.services.rag.models import RagPipelineResult, RagQuery


LANGGRAPH_RAG_GRAPH_NAME = "datasmart.agent.governance-rag"
LANGGRAPH_RAG_GRAPH_VERSION = "v1"


def record_rag_pipeline_checkpoints(
    service: LangGraphDurableCheckpointerService,
    *,
    query: RagQuery,
    result: RagPipelineResult,
    payload: Mapping[str, Any] | None = None,
    artifact_summary: Mapping[str, Any] | None = None,
) -> dict[str, Any]:
    """把一次 RAG 查询投影成 LangGraph 节点链路。

    节点链路：
    1. `rag_retrieve_knowledge`：知识召回已完成，记录候选数、lexical/vector 信号和 scope；
    2. `rag_evidence_gate`：证据门控已执行，记录接受/拒绝数量和 fail-closed 决策；
    3. `rag_grounded_answer_completed` 或 `rag_no_evidence_completed`：生成或安全 fallback 已结束。

    返回值直接供 HTTP API 放进 `langGraphCheckpoint` 字段。它只包含 checkpoint summary 和多 Agent 恢复摘要，
    不返回 state 正文，因此不会把问题、答案或证据正文泄露给控制面日志。
    """

    safe_payload = payload or {}
    initial = _record_retrieval_checkpoint(service, query=query, result=result, payload=safe_payload)
    gate = _record_evidence_gate_checkpoint(service, query=query, result=result, parent=initial)
    final = _record_final_checkpoint(
        service,
        query=query,
        result=result,
        parent=gate,
        artifact_summary=artifact_summary,
    )
    recovered = service.recover_multi_agent_state(final.thread_id)
    return {
        "threadId": final.thread_id,
        "initial": initial.to_summary(),
        "evidenceGate": gate.to_summary(),
        "final": final.to_summary(),
        "multiAgentRecovery": recovered.to_summary(),
        "payloadPolicy": "LOW_SENSITIVE_RAG_LANGGRAPH_CHECKPOINT_SUMMARY_ONLY",
    }


def _record_retrieval_checkpoint(
    service: LangGraphDurableCheckpointerService,
    *,
    query: RagQuery,
    result: RagPipelineResult,
    payload: Mapping[str, Any],
) -> LangGraphDurableCheckpoint:
    """记录 RAG 知识召回节点。

    该节点只说明“检索已经发生，以及检索通道大致表现如何”。用户问题和文档正文不进入 state，因为它们
    属于 prompt/知识正文，应通过受控 RAG 响应或未来 MinIO/知识库权限系统访问，而不是写入 durable state。
    """

    thread_id = _rag_thread_id(query, payload)
    retrieval_summary = result.retrieval_summary
    checkpoint = LangGraphDurableCheckpoint(
        checkpoint_id=_checkpoint_id(thread_id, "rag-retrieve"),
        thread_id=thread_id,
        tenant_id=query.tenant_id,
        project_id=query.project_id,
        actor_id=query.actor_id,
        workspace_key=query.workspace_key,
        run_id=_optional_text(_first(payload, "runId", "run_id")) or query.trace_id,
        session_id=query.session_id or _optional_text(_first(payload, "sessionId", "session_id")),
        graph_name=LANGGRAPH_RAG_GRAPH_NAME,
        graph_version=LANGGRAPH_RAG_GRAPH_VERSION,
        node_name="rag_retrieve_knowledge",
        status=LangGraphCheckpointStatus.RUNNING,
        state={
            "source": "governance_rag_api",
            "currentAgent": "KNOWLEDGE_AGENT",
            "queryStored": False,
            "answerStored": False,
            "compressedContextStored": False,
            "rawDocumentBodyStored": False,
            "retrieval": _retrieval_state(retrieval_summary),
            "multiAgentState": _rag_multi_agent_state(
                knowledge_status="RETRIEVAL_COMPLETED",
                master_status="WAITING_RAG_EVIDENCE_GATE",
                permission_status="SCOPE_FILTER_APPLIED",
            ),
            "collaborationEdges": (
                {
                    "fromRole": "MASTER_ORCHESTRATOR",
                    "toRole": "KNOWLEDGE_AGENT",
                    "edgeType": "retrieve_governance_knowledge",
                },
            ),
            "securityPolicies": (
                "RAG_QUERY_NOT_STORED_IN_LANGGRAPH_STATE",
                "RAG_ANSWER_NOT_STORED_IN_LANGGRAPH_STATE",
                "RAG_COMPRESSED_CONTEXT_NOT_STORED_IN_LANGGRAPH_STATE",
                "RAG_DOCUMENT_BODY_NOT_STORED_IN_LANGGRAPH_STATE",
            ),
        },
        next_nodes=("rag_evidence_gate",),
        low_sensitive_summary="RAG 知识召回已完成，等待证据门控节点确认是否允许生成。",
    )
    return service.record_checkpoint(checkpoint, event_type="rag_retrieval_completed")


def _record_evidence_gate_checkpoint(
    service: LangGraphDurableCheckpointerService,
    *,
    query: RagQuery,
    result: RagPipelineResult,
    parent: LangGraphDurableCheckpoint,
) -> LangGraphDurableCheckpoint:
    """记录 RAG 从召回节点进入证据门控节点的显式边。

    这里复用 `record_loop_iteration`，表达“同一个 thread 通过一条边推进到下一节点”。它不是业务上的无限
    循环，而是让 LangGraph 事件流清晰出现 `retrieve -> evidence_gate` 这条边，便于暂停、恢复、分支和审计。
    """

    accepted = int(result.retrieval_summary.get("evidenceAcceptedCount") or result.retrieval_summary.get("selectedCount") or 0)
    rejected = int(result.retrieval_summary.get("weakEvidenceRejectedCount") or 0)
    fail_closed = accepted <= 0 and not result.generated
    return service.record_loop_iteration(
        thread_id=parent.thread_id,
        node_name="rag_evidence_gate",
        edge_name="retrieval_to_evidence_gate",
        state_patch={
            "currentAgent": "KNOWLEDGE_AGENT",
            "evidenceGate": {
                "acceptedEvidenceCount": accepted,
                "weakEvidenceRejectedCount": rejected,
                "citationCount": len(result.citations),
                "failClosed": fail_closed,
                "generationRequested": bool(query.generate_answer),
                "generationExecuted": bool(result.generated),
                "citationRequired": bool(result.retrieval_summary.get("citationRequired")),
                "policy": "MIN_LEXICAL_OR_VECTOR_EVIDENCE_REQUIRED",
            },
            "multiAgentState": _rag_multi_agent_state(
                knowledge_status="EVIDENCE_ACCEPTED" if accepted > 0 else "EVIDENCE_REJECTED_FAIL_CLOSED",
                master_status="RUNNING_RAG_GENERATION" if accepted > 0 and query.generate_answer else "RAG_EVIDENCE_REVIEWED",
                permission_status="SCOPE_FILTER_APPLIED",
            ),
            "collaborationEdges": (
                {
                    "fromRole": "KNOWLEDGE_AGENT",
                    "toRole": "MASTER_ORCHESTRATOR",
                    "edgeType": "evidence_gate_decision",
                },
            ),
        },
    )


def _record_final_checkpoint(
    service: LangGraphDurableCheckpointerService,
    *,
    query: RagQuery,
    result: RagPipelineResult,
    parent: LangGraphDurableCheckpoint,
    artifact_summary: Mapping[str, Any] | None,
) -> LangGraphDurableCheckpoint:
    """记录 RAG 生成或安全 fallback 的最终节点。"""

    model_summary = result.model_summary
    error_code = _optional_text(model_summary.get("errorCode") or model_summary.get("resultErrorCode"))
    status = LangGraphCheckpointStatus.FAILED if error_code else LangGraphCheckpointStatus.COMPLETED
    no_evidence = len(result.citations) == 0 and not result.generated
    node_name = "rag_no_evidence_completed" if no_evidence else "rag_grounded_answer_completed"
    artifact_state = _artifact_state(artifact_summary)
    state = dict(parent.state)
    state.update(
        {
            "currentAgent": "MASTER_ORCHESTRATOR",
            "generation": {
                "generated": bool(result.generated),
                "modelSkipped": bool(model_summary.get("skipped")),
                "modelErrorCode": error_code,
                "citationCount": len(result.citations),
                "selectedEvidenceCount": len(result.selected_chunks),
                "answerStored": bool(artifact_state),
                "compressedContextStored": bool(artifact_state and result.compressed_context),
                "artifact": artifact_state,
                "payloadPolicy": "RAG_GENERATION_SUMMARY_ONLY_NO_ANSWER_NO_CONTEXT",
            },
            "multiAgentState": _rag_multi_agent_state(
                knowledge_status="GROUNDING_COMPLETED" if result.citations else "NO_EVIDENCE_AVAILABLE",
                master_status=_master_status(status, no_evidence=no_evidence),
                permission_status="SCOPE_FILTER_APPLIED",
            ),
            "collaborationEdges": (
                {
                    "fromRole": "KNOWLEDGE_AGENT",
                    "toRole": "MASTER_ORCHESTRATOR",
                    "edgeType": "grounded_answer_or_fail_closed",
                },
            ),
        }
    )
    checkpoint = LangGraphDurableCheckpoint(
        checkpoint_id=_checkpoint_id(parent.thread_id, "rag-final"),
        thread_id=parent.thread_id,
        parent_checkpoint_id=parent.checkpoint_id,
        tenant_id=parent.tenant_id,
        project_id=parent.project_id,
        actor_id=parent.actor_id,
        workspace_key=parent.workspace_key,
        run_id=parent.run_id,
        session_id=parent.session_id,
        graph_name=parent.graph_name,
        graph_version=parent.graph_version,
        node_name=node_name,
        status=status,
        checkpoint_version=parent.checkpoint_version + 1,
        state=state,
        next_nodes=() if status == LangGraphCheckpointStatus.COMPLETED else ("retry_rag_generation", "human_review"),
        resume_requirements=_resume_requirements(error_code),
        low_sensitive_summary=_final_summary(status, no_evidence=no_evidence, error_code=error_code),
    )
    return service.record_checkpoint(checkpoint, event_type=node_name)


def _retrieval_state(summary: Mapping[str, Any]) -> dict[str, Any]:
    """把 RAG retrievalSummary 压缩成 checkpoint 可保存的低敏状态。"""

    return {
        "candidateCount": int(summary.get("candidateCount") or 0),
        "evidenceAcceptedCount": int(summary.get("evidenceAcceptedCount") or summary.get("selectedCount") or 0),
        "weakEvidenceRejectedCount": int(summary.get("weakEvidenceRejectedCount") or 0),
        "selectedCount": int(summary.get("selectedCount") or 0),
        "topK": int(summary.get("topK") or 0),
        "candidateLimit": int(summary.get("candidateLimit") or 0),
        "compressedContextChars": int(summary.get("compressedContextChars") or 0),
        "maxContextChars": int(summary.get("maxContextChars") or 0),
        "hasLexicalSignal": bool(summary.get("hasLexicalSignal")),
        "hasVectorSignal": bool(summary.get("hasVectorSignal")),
        "payloadPolicy": "LOW_SENSITIVE_RAG_RETRIEVAL_NUMERIC_SUMMARY_ONLY",
    }


def _artifact_state(summary: Mapping[str, Any] | None) -> dict[str, Any]:
    """把 artifact 写入结果裁剪成 checkpoint 可保存的低敏状态。

    checkpoint 只需要知道“正文是否已经落到受控 artifact、hash 是什么、大小是多少、后端是什么”，不需要知道
    answer、compressedContext、citation snippet、sourceUri、真实本地路径或对象存储 bucket/key。后续恢复时，
    Agent 可以先根据 artifactReference 走 Java grant，而不是从 checkpoint state 里读取正文。
    """

    if not summary:
        return {}
    return {
        "artifactReference": _optional_text(summary.get("artifactReference")),
        "artifactReferenceType": _optional_text(summary.get("artifactReferenceType")),
        "storageBackend": _optional_text(summary.get("storageBackend")),
        "byteSize": int(summary.get("byteSize") or 0),
        "contentSha256": _optional_text(summary.get("contentSha256")),
        "citationCount": int(summary.get("citationCount") or 0),
        "selectedChunkCount": int(summary.get("selectedChunkCount") or 0),
        "objectKeyDigest": _optional_text(summary.get("objectKeyDigest")),
        "payloadPolicy": "RAG_ARTIFACT_CHECKPOINT_METADATA_ONLY_NO_BODY_NO_PATH",
    }


def _rag_multi_agent_state(
    *,
    knowledge_status: str,
    master_status: str,
    permission_status: str,
) -> dict[str, dict[str, str]]:
    """构造 RAG 图节点涉及的多 Agent 状态。

    RAG 在当前产品里由总控 Agent 发起，知识 Agent 执行检索和证据归因，权限 Agent 观察 scope filter 是否
    生效。其他核心 Agent 保持 `NOT_SCHEDULED`，这样恢复 API 可以看到完整产品角色集合，但不会误以为所有
    Agent 都参与了本次问答。
    """

    return {
        "MASTER_ORCHESTRATOR": {"status": master_status},
        "KNOWLEDGE_AGENT": {"status": knowledge_status},
        "PERMISSION_AGENT": {"status": permission_status},
        "MEMORY_AGENT": {"status": "AVAILABLE_FOR_PROFILE_CONTEXT"},
        "DATA_QUALITY_AGENT": {"status": "NOT_SCHEDULED"},
        "DATASOURCE_AGENT": {"status": "NOT_SCHEDULED"},
        "DATA_SYNC_AGENT": {"status": "NOT_SCHEDULED"},
        "TASK_AGENT": {"status": "NOT_SCHEDULED"},
        "OPS_AGENT": {"status": "OBSERVING_RUNTIME"},
    }


def _rag_thread_id(query: RagQuery, payload: Mapping[str, Any]) -> str:
    """解析 RAG LangGraph threadId，避免把问题正文放进 ID。"""

    explicit = _optional_text(_first(payload, "langGraphThreadId", "langgraphThreadId", "lang_graph_thread_id"))
    if explicit:
        return explicit
    stable = query.trace_id or query.session_id or _optional_text(_first(payload, "runId", "run_id"))
    if stable:
        return f"rag:{_safe_key_part(stable)}"
    return f"rag:{uuid4().hex[:12]}"


def _checkpoint_id(thread_id: str, marker: str) -> str:
    """生成可读但不含敏感正文的 checkpoint id。"""

    return f"lgcp:rag:{_safe_key_part(thread_id)}:{_safe_key_part(marker)}:{uuid4().hex[:12]}"


def _master_status(
    status: LangGraphCheckpointStatus,
    *,
    no_evidence: bool,
) -> str:
    """把最终 checkpoint 状态转成总控 Agent 的低敏状态。"""

    if status == LangGraphCheckpointStatus.FAILED:
        return "RAG_GENERATION_FAILED_NEEDS_RETRY_OR_BRANCH"
    if no_evidence:
        return "RAG_FAIL_CLOSED_NO_EVIDENCE"
    return "RAG_GROUNDED_ANSWER_READY"


def _resume_requirements(error_code: str | None) -> dict[str, Any]:
    """为模型错误生成恢复条件。"""

    if not error_code:
        return {}
    return {"retryModelProvider": "recommended", "operatorReview": "recommended", "errorCode": error_code}


def _final_summary(
    status: LangGraphCheckpointStatus,
    *,
    no_evidence: bool,
    error_code: str | None,
) -> str:
    """生成最终节点低敏摘要。"""

    if status == LangGraphCheckpointStatus.FAILED:
        return f"RAG 生成节点失败，errorCode={error_code or 'unknown'}。"
    if no_evidence:
        return "RAG 未找到足够证据，已按 fail-closed 策略结束。"
    return "RAG 已完成有证据约束的回答或证据摘要。"


def _first(mapping: Mapping[str, Any], *keys: str) -> Any:
    """读取第一个存在的兼容字段。"""

    for key in keys:
        if key in mapping:
            return mapping[key]
    return None


def _optional_text(value: Any) -> str | None:
    """读取可选非空文本。"""

    if value is None:
        return None
    text = str(value).strip()
    return text or None


def _safe_key_part(value: Any) -> str:
    """把外部 ID 压成 checkpoint id 可用片段。"""

    text = _optional_text(value) or "unknown"
    return "".join(ch if ch.isalnum() or ch in "_.:-" else "_" for ch in text)[:96] or "unknown"


__all__ = [
    "LANGGRAPH_RAG_GRAPH_NAME",
    "LANGGRAPH_RAG_GRAPH_VERSION",
    "record_rag_pipeline_checkpoints",
]
