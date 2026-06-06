"""智能网关 Agent 会话调度服务包。

该包承接“智能网关如何把一次用户会话分配给多个治理 Agent”的策略视图。
它不直接执行工具、不调用模型，也不创建后台任务；它只根据已经存在的计划事实生成低敏调度摘要，
让 API、前端、Java 控制面和审计系统能用同一套口径理解本轮会话的 Agent 协作边界。
"""

from datasmart_ai_runtime.services.agent_gateway.session_scheduler import (
    AgentSessionScheduler,
    build_agent_session_scheduling_policy_view,
)
from datasmart_ai_runtime.services.agent_gateway.session_events import (
    build_agent_session_scheduling_runtime_event,
)
from datasmart_ai_runtime.services.agent_gateway.a2a_task_planning_adapter import (
    A2aTaskPlanningAdapter,
    build_a2a_task_planning_decision,
)

__all__ = (
    "A2aTaskPlanningAdapter",
    "AgentSessionScheduler",
    "build_agent_session_scheduling_policy_view",
    "build_agent_session_scheduling_runtime_event",
    "build_a2a_task_planning_decision",
)
