"""RAG command worker runner.

本模块把 `knowledge.rag.query` 从“普通 HTTP 查询能力”推进为“Java command outbox
可消费的内部 worker 能力”。它解决的是闭环链路中的一个关键断点：

1. Java `agent-runtime` 通过 proposal/outbox 形成 durable command；
2. Python Runtime 领取或接收该 command 后执行 RAG；
3. Python 只把低敏 worker receipt、LangGraph checkpoint locator 和 Java 可写回 payload 返回；
4. question、answer、compressedContext、chunk text、sourceUri、模型原始响应等正文不进入控制面。

为什么这里仍然允许 `question` 出现在 `RagCommandWorkerRunRequest` 中？
真实执行 RAG 必须有查询内容，否则只能生成空 receipt。区别在于：
- question 是 worker 的短生命周期输入，只进入内存中的 `RagPipeline.answer(...)`；
- question 不会写入 `RagCommandWorkerRunResult.to_summary()`；
- question 不会写入 Java worker receipt；
- question 不会写入 LangGraph checkpoint state，`record_rag_pipeline_checkpoints(...)`
  已经只保存计数、状态、策略和 Agent role 状态。

这与 MCP durable worker 的设计一致：执行入口可以接收短生命周期 arguments，但返回给 Java
控制面的只能是经过白名单治理的低敏事实。
"""

from __future__ import annotations

import hashlib
from collections.abc import Mapping
from dataclasses import dataclass
from typing import Any

from datasmart_ai_runtime.services.agent_execution import LangGraphDurableCheckpointerService
from datasmart_ai_runtime.services.rag.artifact_writer import (
    RagAnswerArtifactWriteInput,
    RagAnswerArtifactWriteResult,
    RagAnswerArtifactWriter,
)
from datasmart_ai_runtime.services.rag.command_worker_receipt import (
    RagCommandWorkerReceipt,
    RagCommandWorkerReceiptBuilder,
    RagCommandWorkerReceiptEvidence,
)
from datasmart_ai_runtime.services.rag.langgraph_checkpoint import record_rag_pipeline_checkpoints
from datasmart_ai_runtime.services.rag.models import RagPipelineResult, RagQuery
from datasmart_ai_runtime.services.rag.pipeline import RagPipeline
from datasmart_ai_runtime.services.tools.command_worker_receipt_client import (
    CommandWorkerReceiptPostResult,
    JavaCommandWorkerReceiptClient,
)


RAG_COMMAND_WORKER_RUNNER_SCHEMA_VERSION = "datasmart.python-ai-runtime.rag-command-worker-runner.v1"
RAG_COMMAND_WORKER_API_PAYLOAD_POLICY = (
    "RAG_WORKER_SUMMARY_ONLY_NO_QUESTION_NO_ANSWER_NO_CONTEXT_NO_DOCUMENT_TEXT_NO_SOURCE_URI"
)


@dataclass(frozen=True)
class RagCommandWorkerRunRequest:
    """一次 `knowledge.rag.query` command worker 运行请求。

    字段说明：
    - `command_id`：Java command outbox 生成的 durable command 主键，是 worker receipt 回写的
      第一定位字段；
    - `session_id/run_id`：把本次 RAG worker 与 Agent 会话、LangGraph thread、Java runtime event
      串起来的低敏链路字段；
    - `question`：短生命周期查询正文，仅供当前进程执行 RAG 使用，不能出现在 summary、receipt、
      checkpoint、日志或 Java 控制面 payload；
    - `query_ref`：question 的低敏引用，通常是 `rag-query:sha256:<digest>`，用于审计和幂等；
    - `answer_artifact_reference`：未来 MinIO/受控对象存储中的答案产物引用。本阶段允许为空，
      但返回会提示 `artifactRequiredForAnswerBody=true`，表示正文必须走 artifact grant 链路；
    - `post_to_java`：是否在 Python worker 内部直接调用 Java worker receipt endpoint。默认关闭，
      便于本地测试；生产 worker 可以显式开启。
    """

    command_id: str
    session_id: str
    run_id: str
    question: str
    tenant_id: str = "*"
    project_id: str = "*"
    actor_id: str = "anonymous"
    workspace_key: str = "*"
    task_id: int | None = None
    task_run_id: int | None = None
    query_ref: str | None = None
    answer_artifact_reference: str | None = None
    artifact_reference_type: str = "AGENT_RAG_ANSWER_ARTIFACT"
    retrieval_policy_version: str = "rag-policy.v1"
    top_k: int = 5
    candidate_limit: int = 32
    max_context_chars: int = 4000
    generate_answer: bool = True
    trace_id: str | None = None
    langgraph_thread_id: str | None = None
    post_to_java: bool = False
    idempotency_key: str | None = None


@dataclass(frozen=True)
class RagCommandWorkerRunResult:
    """RAG command worker 的低敏执行结果。

    `pipeline_result` 中包含 answer、compressedContext、citations snippet 等正文级信息，所以
    `to_summary()` 绝不能直接调用 `pipeline_result.to_summary()`。这里保留对象字段是为了单元测试
    或未来 artifact writer 能在同一进程内消费答案正文；对外返回时只暴露计数和引用。
    """

    request: RagCommandWorkerRunRequest
    query_ref: str
    pipeline_result: RagPipelineResult
    receipt: RagCommandWorkerReceipt
    artifact_write: RagAnswerArtifactWriteResult | None = None
    langgraph_checkpoint: dict[str, Any] | None = None
    post_result: CommandWorkerReceiptPostResult | None = None

    def to_summary(self) -> dict[str, Any]:
        """输出 Java outbox worker 可消费的低敏摘要。

        返回内容刻意分成三层：
        - `ragExecutionSummary`：只说明 RAG 执行阶段、候选数量、引用数量和产物引用是否存在；
        - `receipt` / `javaReceiptPayload`：供 Java worker receipt controller 消费；
        - `langGraphCheckpoint`：供暂停、恢复、分支、循环和多 Agent 状态恢复定位。

        这里不会返回 answer、question、compressedContext、selectedChunks、citation snippet、
        sourceUri 或模型原始响应，因为这些内容属于业务/知识正文，应通过受控 artifact grant 读取。
        """

        retrieval_summary = self.pipeline_result.retrieval_summary
        artifact_reference = self.receipt.java_payload.get("artifactReference")
        return {
            "schemaVersion": RAG_COMMAND_WORKER_RUNNER_SCHEMA_VERSION,
            "accepted": True,
            "toolCode": "knowledge.rag.query",
            "queryRef": self.query_ref,
            "ragExecutionSummary": {
                "generated": bool(self.pipeline_result.generated),
                "candidateCount": _safe_int(retrieval_summary.get("candidateCount")),
                "evidenceAcceptedCount": _safe_int(
                    retrieval_summary.get("evidenceAcceptedCount") or retrieval_summary.get("selectedCount")
                ),
                "weakEvidenceRejectedCount": _safe_int(retrieval_summary.get("weakEvidenceRejectedCount")),
                "selectedChunkCount": len(self.pipeline_result.selected_chunks),
                "citationCount": len(self.pipeline_result.citations),
                "hasLexicalSignal": bool(retrieval_summary.get("hasLexicalSignal")),
                "hasVectorSignal": bool(retrieval_summary.get("hasVectorSignal")),
                "artifactReferencePresent": bool(artifact_reference),
                "artifactRequiredForAnswerBody": True,
                "answerBodyReturned": False,
                "payloadPolicy": RAG_COMMAND_WORKER_API_PAYLOAD_POLICY,
            },
            "receipt": self.receipt.to_summary(),
            "javaReceiptPayload": dict(self.receipt.java_payload),
            "artifactWrite": self.artifact_write.to_summary() if self.artifact_write else None,
            "postResult": self.post_result.to_summary() if self.post_result else None,
            "langGraphCheckpoint": self.langgraph_checkpoint,
            "payloadPolicy": RAG_COMMAND_WORKER_API_PAYLOAD_POLICY,
        }


class RagCommandWorkerRunner:
    """执行 `knowledge.rag.query` command 并生成低敏 worker receipt。

    该 runner 不负责 claim Java outbox 或续租 worker lease。原因是这些能力分别属于：
    - Java dispatcher / Kafka consumer：负责 command 领取、重试、死信和并发控制；
    - Java/Redis lease：负责 fencing token 与多实例互斥；
    - artifact writer：作为可选依赖注入，只负责把答案正文写入受控产物，不负责授权读取。

    本 runner 的职责保持收敛：消费可信 command payload，执行 RAG，必要时写 artifact，生成 receipt，
    必要时 POST 回 Java。artifact 读取授权仍由 Java artifact grant/权限链路处理。
    """

    def __init__(
        self,
        *,
        rag_pipeline: RagPipeline,
        receipt_builder: RagCommandWorkerReceiptBuilder | None = None,
        artifact_writer: RagAnswerArtifactWriter | None = None,
    ) -> None:
        self._rag_pipeline = rag_pipeline
        self._receipt_builder = receipt_builder or RagCommandWorkerReceiptBuilder()
        self._artifact_writer = artifact_writer

    def run(
        self,
        request: RagCommandWorkerRunRequest,
        *,
        langgraph_checkpointer_service: LangGraphDurableCheckpointerService | None = None,
        receipt_client: JavaCommandWorkerReceiptClient | None = None,
    ) -> RagCommandWorkerRunResult:
        """执行 RAG worker 并按需写回 Java。

        执行顺序是固定的：
        1. 规范化 question 并生成/校验 queryRef；
        2. 调用 `RagPipeline.answer(...)` 完成检索、门控、压缩、生成和引用绑定；
        3. 如果配置了 artifact writer，先把 answer/citations/compressedContext 写入受控 artifact；
        4. 可选写入 LangGraph checkpoint，记录 retrieve -> evidence gate -> final 节点；
        5. 构造 Java worker receipt 低敏 payload；
        6. 如果 request.post_to_java=true，则调用 Java worker receipt endpoint。

        注意：即使 `post_to_java=false`，返回中仍包含 `javaReceiptPayload`，便于 Java dispatcher
        或测试环境自行决定何时写回；这不会泄露正文，因为 payload 已由 receipt builder 白名单治理。
        """

        query = self._query_from_request(request)
        query_ref = request.query_ref or _query_ref_from_question(query)
        pipeline_result = self._rag_pipeline.answer(query)
        artifact_write = self._write_artifact_if_configured(
            request,
            query_ref=query_ref,
            result=pipeline_result,
        )
        checkpoint = self._record_checkpoint(
            request,
            query=query,
            result=pipeline_result,
            artifact_write=artifact_write,
            langgraph_checkpointer_service=langgraph_checkpointer_service,
        )
        receipt = self._receipt_for(
            request,
            query_ref=query_ref,
            result=pipeline_result,
            artifact_write=artifact_write,
        )
        post_result = None
        if request.post_to_java:
            client = receipt_client or JavaCommandWorkerReceiptClient()
            post_result = client.post_receipt(
                session_id=request.session_id,
                run_id=request.run_id,
                receipt=receipt,
                trace_id=request.trace_id,
            )
        return RagCommandWorkerRunResult(
            request=request,
            query_ref=query_ref,
            pipeline_result=pipeline_result,
            receipt=receipt,
            artifact_write=artifact_write,
            langgraph_checkpoint=checkpoint,
            post_result=post_result,
        )

    def _query_from_request(self, request: RagCommandWorkerRunRequest) -> RagQuery:
        """把 worker 请求转换为 RAG pipeline 查询对象。"""

        question = str(request.question or "").strip()
        if not question:
            raise ValueError("RAG command worker question 不能为空")
        return RagQuery(
            tenant_id=str(request.tenant_id or "*").strip() or "*",
            project_id=str(request.project_id or "*").strip() or "*",
            actor_id=str(request.actor_id or "anonymous").strip() or "anonymous",
            question=question,
            workspace_key=str(request.workspace_key or "*").strip() or "*",
            top_k=max(1, min(int(request.top_k), 20)),
            candidate_limit=max(5, min(int(request.candidate_limit), 200)),
            max_context_chars=max(500, min(int(request.max_context_chars), 12000)),
            generate_answer=bool(request.generate_answer),
            trace_id=request.trace_id,
            session_id=request.session_id,
        )

    def _record_checkpoint(
        self,
        request: RagCommandWorkerRunRequest,
        *,
        query: RagQuery,
        result: RagPipelineResult,
        artifact_write: RagAnswerArtifactWriteResult | None,
        langgraph_checkpointer_service: LangGraphDurableCheckpointerService | None,
    ) -> dict[str, Any] | None:
        """按同一 RAG LangGraph 合同记录低敏节点链路。"""

        if langgraph_checkpointer_service is None:
            return None
        payload = {
            "runId": request.run_id,
            "sessionId": request.session_id,
            "traceId": request.trace_id,
            "langGraphThreadId": request.langgraph_thread_id,
            "commandId": request.command_id,
            "source": "rag_command_worker",
        }
        return record_rag_pipeline_checkpoints(
            langgraph_checkpointer_service,
            query=query,
            result=result,
            payload={key: value for key, value in payload.items() if value},
            artifact_summary=artifact_write.to_summary() if artifact_write else None,
        )

    def _write_artifact_if_configured(
        self,
        request: RagCommandWorkerRunRequest,
        *,
        query_ref: str,
        result: RagPipelineResult,
    ) -> RagAnswerArtifactWriteResult | None:
        """在配置了 artifact writer 时，把答案正文写入受控产物。

        这里刻意放在 receipt 构造之前执行：receipt 必须记录最终可读的 artifactReference，而不是记录一个
        “未来可能存在”的占位值。若 writer 配置后写入失败，本方法会让异常向外抛出，由 Java dispatcher
        按 outbox 失败/重试/死信策略处理，避免出现 receipt 已成功但 artifact 正文丢失的不一致状态。
        """

        if self._artifact_writer is None:
            return None
        return self._artifact_writer.write(
            write_input=RagAnswerArtifactWriteInput(
                command_id=request.command_id,
                run_id=request.run_id,
                session_id=request.session_id,
                query_ref=query_ref,
                tenant_id=request.tenant_id,
                project_id=request.project_id,
                workspace_key=request.workspace_key,
                actor_id=request.actor_id,
                requested_artifact_reference=request.answer_artifact_reference,
                trace_id=request.trace_id,
            ),
            result=result,
        )

    def _receipt_for(
        self,
        request: RagCommandWorkerRunRequest,
        *,
        query_ref: str,
        result: RagPipelineResult,
        artifact_write: RagAnswerArtifactWriteResult | None,
    ) -> RagCommandWorkerReceipt:
        """根据 RAG 结果生成 Java command worker receipt。"""

        retrieval_summary = result.retrieval_summary
        answer_artifact_reference = (
            artifact_write.artifact_reference if artifact_write else request.answer_artifact_reference
        )
        return self._receipt_builder.build_receipt(
            RagCommandWorkerReceiptEvidence(
                command_id=request.command_id,
                session_id=request.session_id,
                run_id=request.run_id,
                query_ref=query_ref,
                tenant_id=_optional_int(request.tenant_id),
                project_id=_optional_int(request.project_id),
                actor_id=_optional_int(request.actor_id),
                task_id=request.task_id,
                task_run_id=request.task_run_id,
                answer_artifact_reference=answer_artifact_reference,
                artifact_reference_type=request.artifact_reference_type,
                candidate_count=_safe_int(retrieval_summary.get("candidateCount")),
                selected_chunk_count=len(result.selected_chunks),
                citation_count=len(result.citations),
                retrieval_policy_version=request.retrieval_policy_version,
                idempotency_key=request.idempotency_key,
            )
        )


def _query_ref_from_question(query: RagQuery) -> str:
    """从短生命周期 question 生成稳定低敏 queryRef。

    digest 输入包含 tenant/project/workspace/question，是为了避免不同租户或 workspace 的相同问题在审计
    上完全混同；输出只保留 hash，不泄露原文。
    """

    material = "\n".join((query.tenant_id, query.project_id, query.workspace_key, query.question))
    digest = hashlib.sha256(material.encode("utf-8")).hexdigest()
    return f"rag-query:sha256:{digest[:32]}"


def _optional_int(value: Any) -> int | None:
    """只在值确实是数字时返回 int，避免把 `tenant-a` 这类租户编码误写成 0。"""

    if value is None:
        return None
    text = str(value).strip()
    if not text or not text.isdigit():
        return None
    return int(text)


def _safe_int(value: Any) -> int:
    """把统计字段规范为非负整数。"""

    try:
        parsed = int(value)
    except (TypeError, ValueError):
        return 0
    return max(parsed, 0)


def low_sensitive_rag_worker_summary(summary: Mapping[str, Any]) -> dict[str, Any]:
    """为未来 dispatcher/diagnostics 预留的低敏摘要裁剪函数。

    当前测试主要直接消费 `RagCommandWorkerRunResult.to_summary()`；这个函数提供一个小的防御层，
    方便后续如果 Java dispatcher 把 worker summary 再投递到 runtime event，也可以只选择白名单字段。
    """

    return {
        "schemaVersion": summary.get("schemaVersion"),
        "accepted": bool(summary.get("accepted")),
        "toolCode": summary.get("toolCode"),
        "queryRef": summary.get("queryRef"),
        "ragExecutionSummary": dict(summary.get("ragExecutionSummary") or {}),
        "artifactWrite": dict(summary.get("artifactWrite") or {}),
        "payloadPolicy": RAG_COMMAND_WORKER_API_PAYLOAD_POLICY,
    }


__all__ = [
    "RAG_COMMAND_WORKER_API_PAYLOAD_POLICY",
    "RAG_COMMAND_WORKER_RUNNER_SCHEMA_VERSION",
    "RagCommandWorkerRunRequest",
    "RagCommandWorkerRunResult",
    "RagCommandWorkerRunner",
    "low_sensitive_rag_worker_summary",
]
