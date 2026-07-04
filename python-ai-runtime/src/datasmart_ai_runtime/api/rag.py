"""RAG HTTP 路由。

本模块提供治理知识问答入口。它不是通用“把任何文档塞给模型”的接口，而是 DataSmart Agent Runtime 的
受控 RAG 查询面：请求必须携带 tenant/project/actor/workspace，服务层先做范围过滤和证据引用，再调用
模型生成。
"""

from __future__ import annotations

from typing import Any, Mapping

from datasmart_ai_runtime.services.agent_execution import LangGraphDurableCheckpointerService
from datasmart_ai_runtime.services.rag import (
    LANGGRAPH_RAG_GRAPH_NAME,
    RagPipeline,
    RagQuery,
    record_rag_pipeline_checkpoints,
)


def register_rag_routes(
    app: Any,
    *,
    rag_pipeline: RagPipeline,
    langgraph_checkpointer_service: LangGraphDurableCheckpointerService | None = None,
) -> None:
    """注册 RAG 查询与诊断路由。

    路由：
    - `POST /agent/rag/query`：Python Runtime 直连；
    - `POST /api/agent/rag/query`：统一 gateway 路径；
    - `GET /agent/rag/diagnostics`：低敏运行诊断；
    - `GET /api/agent/rag/diagnostics`：统一 gateway 路径。

    `langgraph_checkpointer_service` 是可选的：
    - 本地单测或只想验证 RAG 算法时可以不传，响应中 `langGraphCheckpoint=None`；
    - 应用启动时传入统一 checkpointer 后，每次 RAG 查询都会写入低敏 LangGraph 节点链路；
    - 这样 RAG 能逐步纳入 Agent 状态机，而不是作为旁路 HTTP 能力游离在 durable loop 外。
    """

    @app.post("/agent/rag/query")
    @app.post("/api/agent/rag/query")
    def query_governance_rag(payload: dict[str, Any]) -> dict[str, Any]:
        """执行一次治理 RAG 查询。

        响应包含：
        - `answer`：模型或证据 fallback 生成的回答；
        - `citations`：引用证据；
        - `selectedChunks`：低敏分数摘要；
        - `compressedContext`：压缩后的证据上下文；
        - `retrievalSummary/modelSummary`：检索和模型调用治理摘要。
        """

        query = rag_query_from_payload(payload)
        result = rag_pipeline.answer(query)
        response = result.to_summary()
        response["langGraphCheckpoint"] = (
            record_rag_pipeline_checkpoints(
                langgraph_checkpointer_service,
                query=query,
                result=result,
                payload=payload,
            )
            if langgraph_checkpointer_service is not None
            else None
        )
        return response

    @app.get("/agent/rag/diagnostics")
    @app.get("/api/agent/rag/diagnostics")
    def governance_rag_diagnostics() -> dict[str, Any]:
        """查询 RAG 管线诊断，不返回问题或文档正文。"""

        diagnostics = rag_pipeline.diagnostics()
        diagnostics["langGraphCheckpointing"] = {
            "enabled": langgraph_checkpointer_service is not None,
            "graphName": LANGGRAPH_RAG_GRAPH_NAME,
            "nodes": (
                "rag_retrieve_knowledge",
                "rag_evidence_gate",
                "rag_grounded_answer_completed",
                "rag_no_evidence_completed",
            ),
            "payloadPolicy": "LOW_SENSITIVE_RAG_LANGGRAPH_CHECKPOINT_SUMMARY_ONLY",
        }
        return diagnostics


def rag_query_from_payload(payload: Mapping[str, Any]) -> RagQuery:
    """把 HTTP payload 转换为 RAG 查询对象。

    兼容 camelCase 与 snake_case，方便 Java/gateway/Python 测试复用。
    """

    return RagQuery(
        tenant_id=_text(_first(payload, "tenantId", "tenant_id"), default="*"),
        project_id=_text(_first(payload, "projectId", "project_id"), default="*"),
        actor_id=_text(_first(payload, "actorId", "actor_id"), default="anonymous"),
        question=_required_text(_first(payload, "question", "query", "objective")),
        workspace_key=_text(_first(payload, "workspaceKey", "workspace_key"), default="*"),
        top_k=_positive_int(_first(payload, "topK", "top_k"), default=5),
        candidate_limit=_positive_int(_first(payload, "candidateLimit", "candidate_limit"), default=32),
        max_context_chars=_positive_int(_first(payload, "maxContextChars", "max_context_chars"), default=4000),
        generate_answer=_bool(_first(payload, "generateAnswer", "generate_answer"), default=True),
        trace_id=_optional_text(_first(payload, "traceId", "trace_id")),
        session_id=_optional_text(_first(payload, "sessionId", "session_id")),
    )


def _first(mapping: Mapping[str, Any], *keys: str) -> Any:
    """读取第一个存在的兼容字段。"""

    for key in keys:
        if key in mapping:
            return mapping[key]
    return None


def _required_text(value: Any) -> str:
    """读取必填文本。"""

    text = _optional_text(value)
    if not text:
        raise ValueError("RAG question/query/objective 不能为空。")
    return text


def _optional_text(value: Any) -> str | None:
    """读取可选文本。"""

    if value is None:
        return None
    text = str(value).strip()
    return text or None


def _text(value: Any, *, default: str) -> str:
    """读取文本并提供默认值。"""

    return _optional_text(value) or default


def _positive_int(value: Any, *, default: int) -> int:
    """读取正整数。"""

    try:
        parsed = int(value)
    except (TypeError, ValueError):
        return default
    return parsed if parsed > 0 else default


def _bool(value: Any, *, default: bool) -> bool:
    """读取布尔配置。"""

    if value is None:
        return default
    if isinstance(value, bool):
        return value
    return str(value).strip().lower() in {"1", "true", "yes", "on", "y"}


__all__ = ["rag_query_from_payload", "register_rag_routes"]
