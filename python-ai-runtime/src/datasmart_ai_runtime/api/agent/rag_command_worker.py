"""RAG command worker internal API routes.

该路由面向 Java command outbox dispatcher、未来 Kafka consumer 或本地 E2E smoke，
不是给前端直接调用的知识问答接口。它与 `/agent/rag/query` 的区别是：

- `/agent/rag/query` 是产品/调试查询入口，可以按当前 API 合同返回 answer、citations、
  compressedContext 等学习友好字段；
- `/internal/agent/rag/command-worker/run` 是 outbox worker 入口，只返回低敏 receipt、
  Java 可写回 payload、LangGraph checkpoint summary 和执行计数；
- worker route 可以接收短生命周期 `arguments.question`，但绝不把 question 或 answer 正文返回。

这个拆分让 RAG 能真正进入 proposal -> outbox -> worker -> receipt 的闭环，同时不会把普通
查询 API 的“可展示正文”误当成 Java 控制面的持久化事实。
"""

from __future__ import annotations

from collections.abc import Mapping
from typing import Any

from datasmart_ai_runtime.services.agent_execution import LangGraphDurableCheckpointerService
from datasmart_ai_runtime.services.rag.command_worker import (
    RAG_COMMAND_WORKER_RUNNER_SCHEMA_VERSION,
    RagCommandWorkerRunRequest,
    RagCommandWorkerRunner,
)
from datasmart_ai_runtime.services.tools.command_worker_receipt_client import JavaCommandWorkerReceiptClient


RAG_COMMAND_WORKER_API_SCHEMA_VERSION = "datasmart.rag-command-worker-api.v1"


def register_rag_command_worker_routes(
    app: Any,
    *,
    worker_runner: RagCommandWorkerRunner,
    langgraph_checkpointer_service: LangGraphDurableCheckpointerService | None = None,
    receipt_client: JavaCommandWorkerReceiptClient | None = None,
) -> None:
    """注册 RAG command worker 内部执行路由。

    路由：
    - `POST /internal/agent/rag/command-worker/run`：Python Runtime 直连内部路径；
    - `POST /api/internal/agent/rag/command-worker/run`：预留给 gateway 反向代理后的等价路径。

    安全与治理边界：
    - 该路由要求调用方提供 Java 控制面 facts，例如 commandId、runId、sessionId；
    - `arguments.question` 只作为短生命周期 RAG 输入，不进入响应；
    - `postToJava=true` 时才把 receipt 写回 Java，默认本地测试不触发外部 HTTP；
    - 真实生产部署应由 gateway/service-account/OIDC 或 mTLS 保护本路由，本模块只固定业务合同。
    """

    def _run(payload: dict[str, Any]) -> dict[str, Any]:
        """执行一次 RAG command worker，并返回低敏结果。"""

        request = rag_command_worker_request_from_payload(payload)
        result = worker_runner.run(
            request,
            langgraph_checkpointer_service=langgraph_checkpointer_service,
            receipt_client=receipt_client,
        )
        summary = result.to_summary()
        return {
            "schemaVersion": RAG_COMMAND_WORKER_API_SCHEMA_VERSION,
            "accepted": True,
            "workerRunnerSchemaVersion": RAG_COMMAND_WORKER_RUNNER_SCHEMA_VERSION,
            "workerResult": summary,
            "receipt": summary["receipt"],
            "javaReceiptPayload": summary["javaReceiptPayload"],
            "postResult": summary["postResult"],
            "langGraphCheckpoint": summary["langGraphCheckpoint"],
            "payloadPolicy": summary["payloadPolicy"],
        }

    app.post("/internal/agent/rag/command-worker/run")(_run)
    app.post("/api/internal/agent/rag/command-worker/run")(_run)


def rag_command_worker_request_from_payload(payload: Mapping[str, Any]) -> RagCommandWorkerRunRequest:
    """把 Java dispatcher 风格 payload 转换为 RAG worker 请求。

    支持字段来源优先级：
    1. 顶层字段：便于本地 smoke 或 HTTP 调试；
    2. `controlFacts/control_facts`：Java outbox dispatcher 常用的低敏控制面事实；
    3. `arguments`：短生命周期工具参数，允许包含 question，但不会进入响应。

    这里没有把整个 payload 原样透传到 runner，是为了强制完成“正文参数”和“控制面事实”的分离。
    """

    arguments = _mapping(_first(payload, "arguments"), field_name="arguments", default={})
    control_facts = _mapping(_first(payload, "controlFacts", "control_facts"), field_name="controlFacts", default={})
    command_id = _required_text(
        _first(payload, "commandId", "command_id")
        or _first(control_facts, "commandId", "command_id")
        or _first(arguments, "commandId", "command_id"),
        "commandId",
    )
    run_id = _required_text(
        _first(payload, "runId", "run_id")
        or _first(control_facts, "runId", "run_id")
        or _first(arguments, "runId", "run_id"),
        "runId",
    )
    session_id = _required_text(
        _first(payload, "sessionId", "session_id")
        or _first(control_facts, "sessionId", "session_id")
        or _first(arguments, "sessionId", "session_id"),
        "sessionId",
    )
    question = _required_text(
        _first(payload, "question", "query", "objective")
        or _first(arguments, "question", "query", "objective"),
        "arguments.question",
    )
    return RagCommandWorkerRunRequest(
        command_id=command_id,
        session_id=session_id,
        run_id=run_id,
        question=question,
        tenant_id=_text(
            _first(payload, "tenantId", "tenant_id")
            or _first(control_facts, "tenantId", "tenant_id")
            or _first(arguments, "tenantId", "tenant_id"),
            default="*",
        ),
        project_id=_text(
            _first(payload, "projectId", "project_id")
            or _first(control_facts, "projectId", "project_id")
            or _first(arguments, "projectId", "project_id"),
            default="*",
        ),
        actor_id=_text(
            _first(payload, "actorId", "actor_id")
            or _first(control_facts, "actorId", "actor_id")
            or _first(arguments, "actorId", "actor_id"),
            default="anonymous",
        ),
        workspace_key=_text(
            _first(payload, "workspaceKey", "workspace_key")
            or _first(control_facts, "workspaceKey", "workspace_key")
            or _first(arguments, "workspaceKey", "workspace_key"),
            default="*",
        ),
        task_id=_optional_int(_first(payload, "taskId", "task_id") or _first(control_facts, "taskId", "task_id")),
        task_run_id=_optional_int(
            _first(payload, "taskRunId", "task_run_id") or _first(control_facts, "taskRunId", "task_run_id")
        ),
        query_ref=_optional_text(
            _first(payload, "queryRef", "query_ref")
            or _first(control_facts, "queryRef", "query_ref")
            or _first(arguments, "queryRef", "query_ref")
        ),
        answer_artifact_reference=_optional_text(
            _first(payload, "answerArtifactReference", "answer_artifact_reference", "artifactReference")
            or _first(control_facts, "answerArtifactReference", "answer_artifact_reference", "artifactReference")
        ),
        artifact_reference_type=_text(
            _first(payload, "artifactReferenceType", "artifact_reference_type")
            or _first(control_facts, "artifactReferenceType", "artifact_reference_type"),
            default="AGENT_RAG_ANSWER_ARTIFACT",
        ),
        retrieval_policy_version=_text(
            _first(payload, "retrievalPolicyVersion", "retrieval_policy_version")
            or _first(control_facts, "retrievalPolicyVersion", "retrieval_policy_version"),
            default="rag-policy.v1",
        ),
        top_k=_positive_int(_first(payload, "topK", "top_k") or _first(arguments, "topK", "top_k"), default=5),
        candidate_limit=_positive_int(
            _first(payload, "candidateLimit", "candidate_limit")
            or _first(arguments, "candidateLimit", "candidate_limit"),
            default=32,
        ),
        max_context_chars=_positive_int(
            _first(payload, "maxContextChars", "max_context_chars")
            or _first(arguments, "maxContextChars", "max_context_chars"),
            default=4000,
        ),
        generate_answer=_bool(
            _first(payload, "generateAnswer", "generate_answer")
            or _first(arguments, "generateAnswer", "generate_answer"),
            default=True,
        ),
        trace_id=_optional_text(
            _first(payload, "traceId", "trace_id") or _first(control_facts, "traceId", "trace_id")
        ),
        langgraph_thread_id=_optional_text(
            _first(payload, "langGraphThreadId", "langgraphThreadId", "lang_graph_thread_id")
            or _first(control_facts, "langGraphThreadId", "langgraphThreadId", "lang_graph_thread_id")
        ),
        post_to_java=_bool(
            _first(payload, "postToJava", "post_to_java") or _first(control_facts, "postToJava", "post_to_java"),
            default=False,
        ),
        idempotency_key=_optional_text(
            _first(payload, "idempotencyKey", "idempotency_key")
            or _first(control_facts, "idempotencyKey", "idempotency_key")
        ),
    )


def _first(mapping: Mapping[str, Any], *keys: str) -> Any:
    """按候选字段名读取第一个存在值。"""

    for key in keys:
        if key in mapping:
            return mapping[key]
    return None


def _mapping(value: Any, *, field_name: str, default: Mapping[str, Any] | None = None) -> dict[str, Any]:
    """读取对象字段，拒绝字符串或数组伪装成控制面事实。"""

    if value is None and default is not None:
        return dict(default)
    if not isinstance(value, Mapping):
        raise ValueError(f"{field_name} 必须是 JSON object")
    return dict(value)


def _required_text(value: Any, field_name: str) -> str:
    """读取必填文本字段。"""

    text = _optional_text(value)
    if not text:
        raise ValueError(f"{field_name} 不能为空")
    return text


def _optional_text(value: Any) -> str | None:
    """读取可选文本字段。"""

    if value is None:
        return None
    text = str(value).strip()
    return text or None


def _text(value: Any, *, default: str) -> str:
    """读取文本并提供默认值。"""

    return _optional_text(value) or default


def _optional_int(value: Any) -> int | None:
    """读取可选数字 ID。"""

    if value is None:
        return None
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def _positive_int(value: Any, *, default: int) -> int:
    """读取正整数配置。"""

    try:
        parsed = int(value)
    except (TypeError, ValueError):
        return default
    return parsed if parsed > 0 else default


def _bool(value: Any, *, default: bool) -> bool:
    """兼容 Java/Python/JSON 常见布尔表达。"""

    if value is None:
        return default
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        return bool(value)
    return str(value).strip().lower() in {"1", "true", "yes", "y", "on"}


__all__ = [
    "RAG_COMMAND_WORKER_API_SCHEMA_VERSION",
    "rag_command_worker_request_from_payload",
    "register_rag_command_worker_routes",
]
