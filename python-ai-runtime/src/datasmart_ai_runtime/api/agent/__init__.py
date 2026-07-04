"""Agent HTTP 能力包。

这里承载 `/agent/plans`、A2A 规划预览、MCP tools/call intake 预检、AgentPlan 响应组装
和启动期 orchestrator 工厂。它们都属于“HTTP 边界如何把请求转换成 Agent 控制面事实”，
不应继续散落在运行时根包。
"""

from datasmart_ai_runtime.api.agent.a2a_task_planning import build_a2a_task_planning_preview_response
from datasmart_ai_runtime.api.agent.langgraph_checkpoints import register_langgraph_checkpoint_routes
from datasmart_ai_runtime.api.agent.mcp_tool_call_intake import build_mcp_tool_call_intake_preview_response
from datasmart_ai_runtime.api.agent.mcp_worker import register_mcp_durable_worker_routes
from datasmart_ai_runtime.api.agent.orchestrator_factory import (
    build_context_selection_policy,
    build_default_orchestrator,
    build_tool_action_resume_fact_provider,
    build_tool_call_budget_policy_provider,
    build_tool_execution_readiness_policy_provider,
    load_skill_registry,
    load_tool_registry,
)
from datasmart_ai_runtime.api.agent.plan_response import build_plan_response
from datasmart_ai_runtime.api.agent.routes import register_agent_runtime_routes
from datasmart_ai_runtime.api.agent.skill_admission import build_skill_admission_policy

__all__ = [
    "build_a2a_task_planning_preview_response",
    "build_context_selection_policy",
    "build_default_orchestrator",
    "build_mcp_tool_call_intake_preview_response",
    "build_plan_response",
    "build_skill_admission_policy",
    "build_tool_action_resume_fact_provider",
    "build_tool_call_budget_policy_provider",
    "build_tool_execution_readiness_policy_provider",
    "load_skill_registry",
    "load_tool_registry",
    "register_langgraph_checkpoint_routes",
    "register_mcp_durable_worker_routes",
    "register_agent_runtime_routes",
]
