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

from typing import Any, Mapping

from datasmart_ai_runtime.services.agent_execution import LangGraphDurableCheckpointerService


def register_langgraph_checkpoint_routes(
    app: Any,
    *,
    checkpointer_service: LangGraphDurableCheckpointerService,
    error_factory: Any | None = None,
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

    @app.get("/agent/langgraph/checkpoints/latest")
    @app.get("/api/agent/langgraph/checkpoints/latest")
    def latest_langgraph_checkpoint(threadId: str | None = None, thread_id: str | None = None) -> dict[str, Any]:
        """查询指定 thread 的最新 checkpoint 摘要。

        该接口用于“恢复前先看现场”：调用方能知道图停在哪个节点、当前状态是什么、下一批候选节点有哪些，
        但看不到 `state` 里的具体值，避免 prompt、工具参数或模型输出被误暴露。
        """

        thread = _required_text(threadId or thread_id, "threadId", _raise)
        checkpoint = checkpointer_service.latest_for_thread(thread)
        if checkpoint is None:
            return {
                "found": False,
                "threadId": thread,
                "checkpoint": None,
                "payloadPolicy": "LOW_SENSITIVE_LANGGRAPH_CHECKPOINT_SUMMARY_ONLY",
            }
        return {
            "found": True,
            "threadId": thread,
            "checkpoint": checkpoint.to_summary(),
            "payloadPolicy": "LOW_SENSITIVE_LANGGRAPH_CHECKPOINT_SUMMARY_ONLY",
        }

    @app.get("/agent/langgraph/checkpoints/events")
    @app.get("/api/agent/langgraph/checkpoints/events")
    def langgraph_checkpoint_events(threadId: str | None = None, thread_id: str | None = None) -> dict[str, Any]:
        """查询指定 thread 的低敏 checkpoint 事件流。

        事件流回答“状态为什么变成这样”：保存、暂停、恢复、分支、循环、二轮模型结束等动作都会有事件。
        返回的 event 仍是 summary，只包含 eventType、sequence、node/edge 和 attributes key，不返回正文。
        """

        thread = _required_text(threadId or thread_id, "threadId", _raise)
        events = tuple(event.to_summary() for event in checkpointer_service.events_for_thread(thread))
        return {
            "threadId": thread,
            "eventCount": len(events),
            "events": events,
            "payloadPolicy": "LOW_SENSITIVE_LANGGRAPH_CHECKPOINT_EVENT_ONLY",
        }

    @app.post("/agent/langgraph/checkpoints/pause")
    @app.post("/api/agent/langgraph/checkpoints/pause")
    def pause_langgraph_checkpoint(payload: dict[str, Any]) -> dict[str, Any]:
        """暂停某个 LangGraph thread。

        典型使用场景：
        - permission-admin 需要等待人工审批；
        - 模型网关容量紧张，需要暂停一批低优先级 Agent；
        - 运维发现下游工具异常，先冻结执行现场再排障。
        """

        data = _mapping(payload, _raise)
        checkpoint = checkpointer_service.pause(
            thread_id=_required_text(_first(data, "threadId", "thread_id"), "threadId", _raise),
            reason_code=_required_text(_first(data, "reasonCode", "reason_code"), "reasonCode", _raise),
            resume_requirements=_optional_mapping(_first(data, "resumeRequirements", "resume_requirements")),
        )
        return {
            "paused": True,
            "checkpoint": checkpoint.to_summary(),
            "payloadPolicy": "LOW_SENSITIVE_LANGGRAPH_CHECKPOINT_SUMMARY_ONLY",
        }

    @app.post("/agent/langgraph/checkpoints/resume")
    @app.post("/api/agent/langgraph/checkpoints/resume")
    def resume_langgraph_checkpoint(payload: dict[str, Any]) -> dict[str, Any]:
        """恢复某个暂停/等待中的 LangGraph thread。

        `resumeFacts` 只用于证明“恢复条件已经满足”，例如 approvalFact、workerReceipt、operatorDecision。
        服务层会把事实压成 key/类型摘要写入 checkpoint，不保存真实审批意见、工单正文或工具返回内容。
        """

        data = _mapping(payload, _raise)
        checkpoint = checkpointer_service.resume(
            thread_id=_required_text(_first(data, "threadId", "thread_id"), "threadId", _raise),
            resume_facts=_optional_mapping(_first(data, "resumeFacts", "resume_facts")),
        )
        return {
            "resumed": True,
            "checkpoint": checkpoint.to_summary(),
            "payloadPolicy": "LOW_SENSITIVE_LANGGRAPH_CHECKPOINT_SUMMARY_ONLY",
        }

    @app.post("/agent/langgraph/checkpoints/fork")
    @app.post("/api/agent/langgraph/checkpoints/fork")
    def fork_langgraph_checkpoint(payload: dict[str, Any]) -> dict[str, Any]:
        """从指定 checkpoint 创建分支 thread。

        分支能力是 LangGraph 比固定流水线更强的关键点：主线现场不被覆盖，修复路径、重试路径和人工确认
        路径都可以拥有自己的事件序列。后续多 Agent runner 可以把不同专家 Agent 的尝试放入不同分支。
        """

        data = _mapping(payload, _raise)
        checkpoint = checkpointer_service.fork_branch(
            parent_checkpoint_id=_required_text(
                _first(data, "parentCheckpointId", "parent_checkpoint_id"),
                "parentCheckpointId",
                _raise,
            ),
            branch_name=_required_text(_first(data, "branchName", "branch_name"), "branchName", _raise),
            next_nodes=_optional_text_tuple(_first(data, "nextNodes", "next_nodes")),
        )
        return {
            "forked": True,
            "checkpoint": checkpoint.to_summary(),
            "payloadPolicy": "LOW_SENSITIVE_LANGGRAPH_CHECKPOINT_SUMMARY_ONLY",
        }

    @app.post("/agent/langgraph/checkpoints/recover/multi-agent")
    @app.post("/api/agent/langgraph/checkpoints/recover/multi-agent")
    def recover_multi_agent_state(payload: dict[str, Any]) -> dict[str, Any]:
        """恢复某个 thread 最新 checkpoint 中的多 Agent 状态摘要。

        该接口是后续真实多 Agent 执行闭环的重要门面：网关或 Java 控制面可以先读取角色状态，再决定是让
        MASTER_ORCHESTRATOR 继续调度，还是把任务交给 DATA_QUALITY_AGENT、DATASOURCE_AGENT、PERMISSION_AGENT
        等专项 Agent 接续处理。
        """

        data = _mapping(payload, _raise)
        recovered = checkpointer_service.recover_multi_agent_state(
            _required_text(_first(data, "threadId", "thread_id"), "threadId", _raise)
        )
        return {
            "recovered": recovered.to_summary(),
            "payloadPolicy": "LOW_SENSITIVE_MULTI_AGENT_RECOVERY_SUMMARY_ONLY",
        }


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
