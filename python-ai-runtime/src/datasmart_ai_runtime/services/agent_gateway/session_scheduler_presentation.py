"""多 Agent 会话调度视图的展示与建议组装。"""

from __future__ import annotations

from typing import Any


def display_summary(status: Any, agents: tuple[Any, ...]) -> str:
    """生成低敏调度摘要，不暴露 prompt、工具参数或模型输出。"""

    value = status.value.lower()
    if value == "blocked":
        return "智能网关已生成多 Agent 调度视图，但关键模型网关或预算能力不可用，当前不应自动推进。"
    if value == "degraded":
        return f"智能网关已调度 {len(agents)} 个 Agent，但存在权限、预算、记忆或工具治理降级。"
    if value == "approval_required":
        return f"智能网关已调度 {len(agents)} 个 Agent，其中部分动作需要人工审批后才能执行。"
    return f"智能网关已调度 {len(agents)} 个 Agent，当前可进入控制面执行或继续会话。"


def recommended_actions(
    status: Any,
    degraded_reasons: tuple[str, ...],
    agents: tuple[Any, ...],
    a2a_context: Any,
) -> tuple[str, ...]:
    """根据状态生成建议；建议本身不会执行 handoff 或写 Java 控制面。"""

    actions: list[str] = []
    if status.value.lower() == "blocked":
        actions.append("优先恢复模型网关路由、Provider 健康或租户预算，再允许主控 Agent 推进下一步。")
    if "SKILL_ADMISSION_REJECTED" in degraded_reasons:
        actions.append("将被拒绝 Skill 的权限包、租户开关和审批模板同步到 permission-admin 控制面。")
    if "MODEL_TOOL_CALL_BUDGET_BLOCKED" in degraded_reasons:
        actions.append("把本轮工具调用拆成多轮计划，或提高高并发/批处理场景下的工具预算策略。")
    if "MEMORY_TARGETS_WITHOUT_RETRIEVAL_RESULT" in degraded_reasons:
        actions.append("检查长期记忆写入、二级索引同步和 workspace 过滤条件，避免专家 Agent 缺少历史经验。")
    if any(agent.requires_handoff for agent in agents):
        actions.append("把需要 handoff 的 Agent 决策写入 Java 控制面审批单，等待项目负责人或管理员确认。")
    actions.extend(a2a_context.recommended_actions())
    if not actions:
        actions.append("下一阶段可接入真实多 Agent runtime，把当前策略视图升级为可执行 handoff 图。")
    return tuple(actions)
