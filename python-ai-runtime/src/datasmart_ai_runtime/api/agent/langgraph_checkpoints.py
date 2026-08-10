"""LangGraph durable checkpoint 控制面路由。

本模块只暴露 Agent 执行状态机的“低敏控制面”，不执行工具、不调用模型、不读取 prompt/messages、
不返回 checkpoint state_json 正文。它的定位类似生产系统里的运行时恢复控制台：

- `latest/events` 帮助网关、运维或 Java 控制面确认某个 LangGraph thread 当前停在哪里；
- `pause/resume` 用于人工介入、容量保护、审批完成后的恢复；
- `fork` 用于从某个 checkpoint 派生重试/修复分支，避免覆盖主线现场；
- `recover/multi-agent` 用于恢复 MASTER_ORCHESTRATOR、DATA_QUALITY_AGENT、DATASOURCE_AGENT 等角色状态。

为什么独立成文件：
- `api/app.py` 只负责依赖装配，避免继续长成巨型启动文件；
- LangGraph checkpoint 是 Agent 执行层能力，不应混在 MCP worker 或 memory API 中；
- 后续真实多 Agent runner 接入时，可以继续复用这里的查询与控制面合同。
"""

from __future__ import annotations

from typing import Any, Callable, Mapping

from datasmart_ai_runtime.api.gateway.signature import GatewaySignatureVerificationError
from datasmart_ai_runtime.api.gateway.trusted_context import (
    runtime_event_access_context_from_gateway_headers,
)
from datasmart_ai_runtime.services.agent_execution import LangGraphDurableCheckpointerService
from datasmart_ai_runtime.services.runtime_events.runtime_event_authorization import (
    RuntimeEventAccessContext,
)


CheckpointAccessContextResolver = Callable[[Mapping[str, str]], RuntimeEventAccessContext]


def register_langgraph_checkpoint_routes(
    app: Any,
    *,
    checkpointer_service: LangGraphDurableCheckpointerService,
    error_factory: Any | None = None,
    request_type: Any | None = None,
    gateway_signature_nonce_store: Any | None = None,
    gateway_signature_error_factory: Any | None = None,
    access_error_factory: Any | None = None,
    access_context_resolver: CheckpointAccessContextResolver | None = None,
) -> None:
    """注册 LangGraph durable checkpoint 查询与控制路由。

    路由安全边界：
    - 当前 Python Runtime 只负责业务合同；生产入口必须由 gateway/OIDC/service-account/mTLS 保护；
    - 所有返回值都使用 `to_summary()`，只展示 checkpointId、threadId、节点名、状态、key 列表等低敏摘要；
    - `pause/resume/fork` 是状态控制动作，但不会执行模型、工具、文件写入或外部网络访问；
    - `resumeFacts` 只保存 key/类型摘要，不保存审批正文、人工输入全文或工具结果正文。
    """

    def _raise(status_code: int, detail: str) -> None:
        """以 FastAPI HTTPException 或测试环境异常抛出稳定错误。"""

        if error_factory is not None:
            raise error_factory(status_code, detail)
        raise ValueError(detail)

    def _deny(detail: str) -> None:
        """把对象范围或控制动作越权转换成稳定的 403。"""

        if access_error_factory is not None:
            raise access_error_factory(403, detail)
        raise PermissionError(detail)

    def _access_context(http_request: Any) -> RuntimeEventAccessContext:
        """只从通过 HMAC 验证的 Gateway Header 解析访问主体。"""

        headers = getattr(http_request, "headers", {}) if http_request is not None else {}
        try:
            if access_context_resolver is not None:
                return access_context_resolver(headers)
            return runtime_event_access_context_from_gateway_headers(
                headers,
                nonce_store=gateway_signature_nonce_store,
            )
        except GatewaySignatureVerificationError as exc:
            detail = {
                "code": "LANGGRAPH_CHECKPOINT_GATEWAY_SIGNATURE_INVALID",
                "message": "Gateway 内部签名校验失败，LangGraph checkpoint 访问已拒绝。",
                "reason": exc.reason,
            }
            if gateway_signature_error_factory is not None:
                raise gateway_signature_error_factory(detail) from exc
            raise

    def latest_langgraph_checkpoint(
        threadId: str | None = None,
        thread_id: str | None = None,
        http_request: Any = None,
    ) -> dict[str, Any]:
        """查询指定 thread 的最新 checkpoint 摘要。

        该接口用于“恢复前先看现场”：调用方能知道图停在哪个节点、当前状态是什么、下一批候选节点有哪些，
        但看不到 `state` 里的具体值，避免 prompt、工具参数或模型输出被误暴露。
        """

        thread = _required_text(threadId or thread_id, "threadId", _raise)
        context = _access_context(http_request)
        checkpoint = checkpointer_service.latest_for_thread(thread)
        if checkpoint is None:
            return {
                "found": False,
                "threadId": thread,
                "checkpoint": None,
                "payloadPolicy": "LOW_SENSITIVE_LANGGRAPH_CHECKPOINT_SUMMARY_ONLY",
            }
        _ensure_checkpoint_visible(checkpoint, context, _deny)
        return {
            "found": True,
            "threadId": thread,
            "checkpoint": checkpoint.to_summary(),
            "payloadPolicy": "LOW_SENSITIVE_LANGGRAPH_CHECKPOINT_SUMMARY_ONLY",
        }

    def langgraph_checkpoint_events(
        threadId: str | None = None,
        thread_id: str | None = None,
        http_request: Any = None,
    ) -> dict[str, Any]:
        """查询指定 thread 的低敏 checkpoint 事件流。

        事件流回答“状态为什么变成这样”：保存、暂停、恢复、分支、循环、二轮模型结束等动作都会有事件。
        返回的 event 仍是 summary，只包含 eventType、sequence、node/edge 和 attributes key，不返回正文。
        """

        thread = _required_text(threadId or thread_id, "threadId", _raise)
        context = _access_context(http_request)
        checkpoint = checkpointer_service.latest_for_thread(thread)
        if checkpoint is not None:
            _ensure_checkpoint_visible(checkpoint, context, _deny)
        events = tuple(event.to_summary() for event in checkpointer_service.events_for_thread(thread))
        return {
            "threadId": thread,
            "eventCount": len(events),
            "events": events,
            "payloadPolicy": "LOW_SENSITIVE_LANGGRAPH_CHECKPOINT_EVENT_ONLY",
        }

    def pause_langgraph_checkpoint(payload: dict[str, Any], http_request: Any = None) -> dict[str, Any]:
        """暂停某个 LangGraph thread。

        典型使用场景：
        - permission-admin 需要等待人工审批；
        - 模型网关容量紧张，需要暂停一批低优先级 Agent；
        - 运维发现下游工具异常，先冻结执行现场再排障。
        """

        data = _mapping(payload, _raise)
        thread_id_value = _required_text(_first(data, "threadId", "thread_id"), "threadId", _raise)
        context = _access_context(http_request)
        current = checkpointer_service.latest_for_thread(thread_id_value)
        if current is None:
            _raise(404, "LangGraph thread 不存在或尚未写入 checkpoint。")
        _ensure_checkpoint_visible(current, context, _deny)
        _ensure_checkpoint_control_role(context, _deny)
        checkpoint = checkpointer_service.pause(
            thread_id=thread_id_value,
            reason_code=_required_text(_first(data, "reasonCode", "reason_code"), "reasonCode", _raise),
            resume_requirements=_optional_mapping(_first(data, "resumeRequirements", "resume_requirements")),
        )
        return {
            "paused": True,
            "checkpoint": checkpoint.to_summary(),
            "payloadPolicy": "LOW_SENSITIVE_LANGGRAPH_CHECKPOINT_SUMMARY_ONLY",
        }

    def resume_langgraph_checkpoint(payload: dict[str, Any], http_request: Any = None) -> dict[str, Any]:
        """恢复某个暂停/等待中的 LangGraph thread。

        `resumeFacts` 只用于证明“恢复条件已经满足”，例如 approvalFact、workerReceipt、operatorDecision。
        服务层会把事实压成 key/类型摘要写入 checkpoint，不保存真实审批意见、工单正文或工具返回内容。
        """

        data = _mapping(payload, _raise)
        thread_id_value = _required_text(_first(data, "threadId", "thread_id"), "threadId", _raise)
        context = _access_context(http_request)
        current = checkpointer_service.latest_for_thread(thread_id_value)
        if current is None:
            _raise(404, "LangGraph thread 不存在或尚未写入 checkpoint。")
        _ensure_checkpoint_visible(current, context, _deny)
        _ensure_checkpoint_control_role(context, _deny)
        checkpoint = checkpointer_service.resume(
            thread_id=thread_id_value,
            resume_facts=_optional_mapping(_first(data, "resumeFacts", "resume_facts")),
        )
        return {
            "resumed": True,
            "checkpoint": checkpoint.to_summary(),
            "payloadPolicy": "LOW_SENSITIVE_LANGGRAPH_CHECKPOINT_SUMMARY_ONLY",
        }

    def fork_langgraph_checkpoint(payload: dict[str, Any], http_request: Any = None) -> dict[str, Any]:
        """从指定 checkpoint 创建分支 thread。

        分支能力是 LangGraph 比固定流水线更强的关键点：主线现场不被覆盖，修复路径、重试路径和人工确认
        路径都可以拥有自己的事件序列。后续多 Agent runner 可以把不同专家 Agent 的尝试放入不同分支。
        """

        data = _mapping(payload, _raise)
        parent_checkpoint_id = _required_text(
            _first(data, "parentCheckpointId", "parent_checkpoint_id"),
            "parentCheckpointId",
            _raise,
        )
        context = _access_context(http_request)
        parent = checkpointer_service.checkpoint_by_id(parent_checkpoint_id)
        if parent is None:
            _raise(404, "LangGraph checkpoint 不存在。")
        _ensure_checkpoint_visible(parent, context, _deny)
        _ensure_checkpoint_control_role(context, _deny)
        checkpoint = checkpointer_service.fork_branch(
            parent_checkpoint_id=parent_checkpoint_id,
            branch_name=_required_text(_first(data, "branchName", "branch_name"), "branchName", _raise),
            next_nodes=_optional_text_tuple(_first(data, "nextNodes", "next_nodes")),
        )
        return {
            "forked": True,
            "checkpoint": checkpoint.to_summary(),
            "payloadPolicy": "LOW_SENSITIVE_LANGGRAPH_CHECKPOINT_SUMMARY_ONLY",
        }

    def recover_multi_agent_state(payload: dict[str, Any], http_request: Any = None) -> dict[str, Any]:
        """恢复某个 thread 最新 checkpoint 中的多 Agent 状态摘要。

        该接口是后续真实多 Agent 执行闭环的重要门面：网关或 Java 控制面可以先读取角色状态，再决定是让
        MASTER_ORCHESTRATOR 继续调度，还是把任务交给 DATA_QUALITY_AGENT、DATASOURCE_AGENT、PERMISSION_AGENT
        等专项 Agent 接续处理。
        """

        data = _mapping(payload, _raise)
        thread_id_value = _required_text(_first(data, "threadId", "thread_id"), "threadId", _raise)
        context = _access_context(http_request)
        current = checkpointer_service.latest_for_thread(thread_id_value)
        if current is not None:
            _ensure_checkpoint_visible(current, context, _deny)
        recovered = checkpointer_service.recover_multi_agent_state(
            thread_id_value
        )
        return {
            "recovered": recovered.to_summary(),
            "payloadPolicy": "LOW_SENSITIVE_MULTI_AGENT_RECOVERY_SUMMARY_ONLY",
        }

    handlers = (
        latest_langgraph_checkpoint,
        langgraph_checkpoint_events,
        pause_langgraph_checkpoint,
        resume_langgraph_checkpoint,
        fork_langgraph_checkpoint,
        recover_multi_agent_state,
    )
    if request_type is not None:
        for handler in handlers:
            handler.__annotations__["http_request"] = request_type

    app.get("/agent/langgraph/checkpoints/latest")(latest_langgraph_checkpoint)
    app.get("/api/agent/langgraph/checkpoints/latest")(latest_langgraph_checkpoint)
    app.get("/agent/langgraph/checkpoints/events")(langgraph_checkpoint_events)
    app.get("/api/agent/langgraph/checkpoints/events")(langgraph_checkpoint_events)
    app.post("/agent/langgraph/checkpoints/pause")(pause_langgraph_checkpoint)
    app.post("/api/agent/langgraph/checkpoints/pause")(pause_langgraph_checkpoint)
    app.post("/agent/langgraph/checkpoints/resume")(resume_langgraph_checkpoint)
    app.post("/api/agent/langgraph/checkpoints/resume")(resume_langgraph_checkpoint)
    app.post("/agent/langgraph/checkpoints/fork")(fork_langgraph_checkpoint)
    app.post("/api/agent/langgraph/checkpoints/fork")(fork_langgraph_checkpoint)
    app.post("/agent/langgraph/checkpoints/recover/multi-agent")(recover_multi_agent_state)
    app.post("/api/agent/langgraph/checkpoints/recover/multi-agent")(recover_multi_agent_state)


def _ensure_checkpoint_visible(
    checkpoint: Any,
    context: RuntimeEventAccessContext,
    deny: Any,
) -> None:
    """执行 checkpoint 的租户、项目与主体级二次校验。"""

    if _context_is_empty(context):
        return
    if context.is_platform_admin:
        return
    if not context.tenant_id or checkpoint.tenant_id != context.tenant_id:
        deny("LangGraph checkpoint tenant scope mismatch")
    if checkpoint.project_id and checkpoint.project_id != "*":
        if not context.project_id or checkpoint.project_id != context.project_id:
            deny("LangGraph checkpoint project scope mismatch")
    workspace_id = context.attributes.get("workspaceId")
    if workspace_id and checkpoint.workspace_key and checkpoint.workspace_key != "*":
        if checkpoint.workspace_key != workspace_id:
            deny("LangGraph checkpoint workspace scope mismatch")
    roles = _normalized_roles(context)
    if roles & {"OPERATOR", "AUDITOR", "TENANT_ADMIN", "TENANT_ADMINISTRATOR"}:
        return
    if not context.actor_id or checkpoint.actor_id != context.actor_id:
        deny("LangGraph checkpoint actor scope mismatch")


def _ensure_checkpoint_control_role(context: RuntimeEventAccessContext, deny: Any) -> None:
    """限制直接图状态控制，避免交互用户绕过 Java 审批/执行闭环。"""

    if _context_is_empty(context):
        return
    if context.is_platform_admin or context.is_tenant_admin:
        return
    if not (_normalized_roles(context) & {"OPERATOR", "PLATFORM_ADMIN", "PLATFORM_ADMINISTRATOR"}):
        deny("LangGraph checkpoint control requires an operational administrator role")


def _context_is_empty(context: RuntimeEventAccessContext) -> bool:
    return not any((context.tenant_id, context.project_id, context.actor_id, context.roles))


def _normalized_roles(context: RuntimeEventAccessContext) -> set[str]:
    return {str(role).strip().upper() for role in context.roles if str(role).strip()}


def _required_text(value: Any, field_name: str, raise_error: Any) -> str:
    """读取必填文本。"""

    text = _optional_text(value)
    if not text:
        raise_error(400, f"{field_name} 不能为空。")
    return text


def _optional_text(value: Any) -> str | None:
    """读取可选非空文本。"""

    if value is None:
        return None
    text = str(value).strip()
    return text or None


def _mapping(value: Any, raise_error: Any) -> dict[str, Any]:
    """读取请求 JSON object，拒绝数组、字符串或空 payload。"""

    if not isinstance(value, Mapping):
        raise_error(400, "请求体必须是 JSON object。")
    return dict(value)


def _optional_mapping(value: Any) -> dict[str, Any] | None:
    """读取可选对象字段。"""

    return dict(value) if isinstance(value, Mapping) else None


def _optional_text_tuple(value: Any) -> tuple[str, ...] | None:
    """读取 nextNodes 这类可选字符串数组。"""

    if value is None:
        return None
    if not isinstance(value, (list, tuple)):
        return None
    return tuple(text for item in value if (text := _optional_text(item)))


def _first(mapping: Mapping[str, Any], *keys: str) -> Any:
    """按多个兼容字段名读取第一个存在的值。"""

    for key in keys:
        if key in mapping:
            return mapping[key]
    return None


__all__ = ["register_langgraph_checkpoint_routes"]
