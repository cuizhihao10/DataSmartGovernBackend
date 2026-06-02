"""Agent Runtime 结构化事件契约。

智能网关、前端会话窗口、Java 控制面和审计系统都需要理解 Agent 在一次请求中“经历了什么”。
如果只返回字符串形式的 `stateTrace`，前端很难做实时进度条、过滤、告警、回放和问题定位。

本文件先定义一个足够小但可扩展的事件契约：每个事件都有类型、阶段、严重级别、中文说明、租户
上下文和机器可读属性。后续无论事件通过 WebSocket 推给前端、通过 Kafka 写入审计，还是落到
数据库做回放，都可以围绕同一个数据结构演进。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
from enum import Enum
from typing import Any


class AgentRuntimeEventType(str, Enum):
    """Agent 运行时事件类型。

    事件类型描述“发生了什么”。这里优先覆盖当前 Python AI Runtime 已经具备的上下文构建、意图
    分析、工具规划、参数校验和审批等待。后续接入真实模型流式输出、工具执行、Kafka 状态同步时，
    可以继续追加新的类型，而不需要改动已有事件消费者。
    """

    CONTEXT_COLLECTED = "context_collected"
    CONTEXT_FILTERED = "context_filtered"
    CONTEXT_DEDUPLICATED = "context_deduplicated"
    CONTEXT_TRUNCATED = "context_truncated"
    CONTEXT_SELECTED = "context_selected"
    MODEL_GATEWAY_ROUTED = "model_gateway_routed"
    INTENT_ANALYZED = "intent_analyzed"
    TOOL_PLANNED = "tool_planned"
    TOOL_PARAMETER_VALIDATED = "tool_parameter_validated"
    MODEL_TOOL_CALL_PROPOSED = "model_tool_call_proposed"
    MODEL_TOOL_CALL_ACCEPTED = "model_tool_call_accepted"
    MODEL_TOOL_CALL_REJECTED = "model_tool_call_rejected"
    MODEL_TOOL_CALL_APPROVAL_REQUIRED = "model_tool_call_approval_required"
    MODEL_TOOL_CALL_BUDGET_GUARDED = "model_tool_call_budget_guarded"
    TOOL_EXECUTION_STATE_CHANGED = "agent.tool_execution.state_changed"
    TOOL_AUTO_EXECUTION_SYNC_COMPLETED = "tool_auto_execution_sync_completed"
    TOOL_RESULT_FEEDBACK_BUILT = "tool_result_feedback_built"
    AGENT_LOOP_CONTROL_DECIDED = "agent_loop_control_decided"
    MODEL_SECOND_TURN_COMPLETED = "model_second_turn_completed"
    MODEL_SECOND_TURN_SKIPPED = "model_second_turn_skipped"
    MEMORY_RETRIEVED = "memory_retrieved"
    MEMORY_WRITE_CANDIDATE_PROPOSED = "memory_write_candidate_proposed"
    MEMORY_WRITE_DECISION_RECORDED = "memory_write_decision_recorded"
    APPROVAL_WAITING = "approval_waiting"


class AgentRuntimeEventSeverity(str, Enum):
    """Agent 运行时事件严重级别。

    严重级别不是为了替代日志级别，而是帮助产品侧区分展示方式：
    - `INFO`：正常进度，例如已收集上下文、已完成工具规划；
    - `WARNING`：需要用户关注但不一定失败，例如上下文被截断、参数缺失；
    - `ERROR`：节点失败或无法继续；
    - `AUDIT`：对合规和审计有价值的关键节点，例如等待审批。
    """

    INFO = "info"
    WARNING = "warning"
    ERROR = "error"
    AUDIT = "audit"


@dataclass(frozen=True)
class AgentRuntimeEvent:
    """单条 Agent 运行时事件。

    字段说明：
    - `event_type`：机器可读事件类型，供前端、网关、审计系统做过滤和路由。
    - `stage`：事件所属阶段，例如 `build_context`、`plan_tools`，用于和现有 `stateTrace` 对齐。
    - `message`：中文人读说明，适合直接展示在会话窗口或调试面板。
    - `severity`：事件严重级别，帮助产品侧决定是否高亮、告警或进入审计。
    - `tenant_id/project_id/actor_id`：多租户和权限审计的基础上下文。
    - `request_id/run_id/session_id/sequence`：事件关联与排序字段。同步 HTTP 可以只使用 requestId，
      长任务和多 Agent 协作应进一步使用 runId/sessionId；sequence 用于前端断线重连后恢复顺序。
    - `attributes`：机器可读扩展信息，例如 sourceId、tokenEstimate、filterReason。
    - `created_at`：事件产生时间，默认使用 UTC，避免多时区部署时出现排序歧义。
    """

    event_type: AgentRuntimeEventType
    stage: str
    message: str
    severity: AgentRuntimeEventSeverity = AgentRuntimeEventSeverity.INFO
    tenant_id: str | None = None
    project_id: str | None = None
    actor_id: str | None = None
    request_id: str | None = None
    run_id: str | None = None
    session_id: str | None = None
    sequence: int | None = None
    attributes: dict[str, Any] = field(default_factory=dict)
    created_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
