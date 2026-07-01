"""Agent plan 响应中的事件追加、发布与指标旁路工具。

`plan_response.py` 负责把同步 `/agent/plans` 响应组装成一个完整的业务返回体，但随着 runtime event、
WebSocket replay、Kafka publisher、Skill 可见性、多 Agent 调度和 LangGraph execution gate 都进入
响应链路，如果所有事件处理细节继续留在主文件里，会再次突破单文件 500 行约束。

本模块专门承接“事件旁路”职责：
- 把 readiness、execution gate、Skill 可见性、多 Agent 会话调度追加为低敏 Runtime Event；
- 把计划事件批量写入 event store、live push hub 和异步 publisher；
- 把 execution gate 事件旁路记录为低基数 Prometheus 指标。

重要边界：
- 这里不执行工具、不写 outbox、不创建审批、不修改 checkpoint、不派发 worker；
- 这里也不读取 prompt、SQL、工具参数真实值、样本数据、模型输出、token 或内部 endpoint；
- 所有函数都以显式参数接收依赖，避免隐藏全局单例。
"""

from __future__ import annotations

from dataclasses import replace
from typing import Any

from datasmart_ai_runtime.domain.contracts import AgentPlan, AgentRequest
from datasmart_ai_runtime.domain.events import AgentRuntimeEventType
from datasmart_ai_runtime.services.agent_gateway import build_agent_session_scheduling_runtime_event
from datasmart_ai_runtime.services.runtime_events.runtime_event_live_push import RuntimeEventLivePushHub
from datasmart_ai_runtime.services.runtime_events.runtime_event_publisher import RuntimeEventPublisher
from datasmart_ai_runtime.services.runtime_events.runtime_event_store import RuntimeEventStore
from datasmart_ai_runtime.services.skills import build_session_skill_visibility_runtime_event
from datasmart_ai_runtime.services.tools import (
    build_langgraph_execution_gate_runtime_event,
    build_tool_execution_readiness_runtime_event,
)


def attach_tool_execution_readiness_event(
    plan: AgentPlan,
    *,
    request: AgentRequest,
    tool_execution_readiness: Any,
) -> AgentPlan:
    """把工具执行准备度快照追加到运行时事件流。

    readiness event 说明“每个工具当前为什么可执行、需审批、需澄清、只能草案或被阻断”。它必须进入
    事件流，否则同步 HTTP 响应能看到 readiness，WebSocket 断线恢复、Kafka 消费和 Java projection
    却无法回放同一事实。
    """

    if any(
        event.event_type == AgentRuntimeEventType.TOOL_EXECUTION_READINESS_RECORDED
        for event in plan.runtime_events
    ):
        return plan
    readiness_event = build_tool_execution_readiness_runtime_event(plan, request, tool_execution_readiness)
    return replace(plan, runtime_events=plan.runtime_events + (readiness_event,))


def attach_agent_execution_gate_event(
    plan: AgentPlan,
    *,
    request: AgentRequest,
    execution_gate_summary: dict[str, Any],
) -> AgentPlan:
    """把 LangGraph execution gate 快照追加到运行时事件流。

    readiness event 说明“工具计划被判定为什么状态”；execution gate event 说明“LangGraph 条件图把本轮
    路由到哪个 dominant gate”。这两条事件服务不同视角，不能互相替代。
    """

    if any(
        event.event_type == AgentRuntimeEventType.AGENT_EXECUTION_GATE_RECORDED
        for event in plan.runtime_events
    ):
        return plan
    gate_event = build_langgraph_execution_gate_runtime_event(plan, request, execution_gate_summary)
    return replace(plan, runtime_events=plan.runtime_events + (gate_event,))


def record_agent_execution_gate_metrics(
    plan: AgentPlan,
    *,
    metrics_recorder: Any | None,
) -> None:
    """把 execution gate runtime event 旁路记录为 Prometheus 低基数指标。

    指标记录是观测旁路，不应改变 Agent 计划响应，因此这里采用 fail-open。真实生产环境可在这里进一步接入
    结构化日志或自监控告警，但不应该因为 Prometheus 指标器异常把 `/agent/plans` 主链路打成 500。
    """

    if metrics_recorder is None:
        return
    try:
        metrics_recorder.record_runtime_events(plan.runtime_events)
    except Exception:
        return


def attach_skill_visibility_event(
    plan: AgentPlan,
    *,
    request: AgentRequest,
    intelligent_gateway_governance: dict[str, Any],
) -> AgentPlan:
    """把会话级 Skill 可见性快照追加到运行时事件流。

    响应字段服务前端治理卡片；事件字段服务 replay、Kafka、Java projection 和审计。两者都来自同一份
    `skillVisibility` 摘要，避免“前端看到过，但事件系统无法回放”的漂移。
    """

    if any(
        event.event_type == AgentRuntimeEventType.SKILL_VISIBILITY_SNAPSHOT_RECORDED
        for event in plan.runtime_events
    ):
        return plan
    skill_visibility = intelligent_gateway_governance.get("skillVisibility")
    if not isinstance(skill_visibility, dict):
        return plan
    visibility_event = build_session_skill_visibility_runtime_event(plan, request, skill_visibility)
    return replace(plan, runtime_events=plan.runtime_events + (visibility_event,))


def attach_agent_session_scheduling_event(
    plan: AgentPlan,
    *,
    request: AgentRequest,
    intelligent_gateway_governance: dict[str, Any],
) -> AgentPlan:
    """把多 Agent 会话调度视图追加到运行时事件流。

    该事件与响应里的 `agentSessionScheduling` 同源，但比同步响应更紧凑：它只保留角色、状态、参与模式、
    handoff 和策略轴，供断线恢复、审计回放、Java projection 与告警消费。
    """

    if any(
        event.event_type == AgentRuntimeEventType.AGENT_SESSION_SCHEDULING_RECORDED
        for event in plan.runtime_events
    ):
        return plan
    scheduling = intelligent_gateway_governance.get("agentSessionScheduling")
    if not isinstance(scheduling, dict):
        return plan
    scheduling_event = build_agent_session_scheduling_runtime_event(plan, request, scheduling)
    return replace(plan, runtime_events=plan.runtime_events + (scheduling_event,))


def publish_plan_events(
    plan: Any,
    *,
    event_store: RuntimeEventStore | None,
    live_push_hub: RuntimeEventLivePushHub | None,
    event_publisher: RuntimeEventPublisher | None,
) -> None:
    """处理 AgentPlan 运行事件的同步存储、实时推送和异步发布。

    三类旁路服务不同产品场景：
    - event store：供 replay、断线恢复和审计回放使用；
    - live push hub：供当前 Python Runtime 内部 WebSocket 连接即时读取；
    - event publisher：供 Kafka、Java 控制面、告警和观测系统异步消费。
    """

    if event_store is not None:
        event_store.append_many(plan.runtime_events)
    if live_push_hub is not None:
        live_push_hub.publish(plan.runtime_events)
    if event_publisher is not None:
        event_publisher.publish(plan.runtime_events)
