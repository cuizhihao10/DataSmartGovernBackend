"""智能网关统一治理摘要 API 适配器。

DataSmart 的智能网关不是单一“模型代理”，而是模型路由、工具调用预算、缓存边界、长期记忆召回、
workspace 隔离和审计事件的组合治理层。前端或 Java gateway 如果分别解析这些字段，会很快形成
脆弱耦合：模型网关字段在一个位置，工具预算事件在 runtimeEvents 里，workspace 又在响应顶层。

本模块提供一份轻量统一摘要：
- 不重新做治理决策，只汇总已经发生的治理事实；
- 不暴露工具参数、prompt、SQL 或样本数据；
- 保持字段稳定，方便后续前端治理卡片、Java 审计记录和智能网关诊断接口复用。
"""

from __future__ import annotations

from typing import Any

from datasmart_ai_runtime.api_model_gateway import build_model_gateway_governance_response
from datasmart_ai_runtime.domain.contracts import AgentPlan
from datasmart_ai_runtime.domain.events import AgentRuntimeEvent, AgentRuntimeEventType
from datasmart_ai_runtime.services.agent_workspace import AgentWorkspaceContext


def build_intelligent_gateway_governance_response(
    plan: AgentPlan,
    workspace_context: AgentWorkspaceContext,
) -> dict[str, Any]:
    """构建本次 Agent 计划的智能网关统一治理摘要。

    输入说明：
    - `plan`：编排器已经生成的计划，包含模型网关决策、工具计划、记忆计划、检索报告和 runtime events；
    - `workspace_context`：响应组装层统一生成的工作空间上下文，用于说明缓存、记忆和产物隔离边界。

    输出说明：
    - `available` 不是“模型可用”的同义词，而是“本次智能网关没有出现阻断性治理问题”；
    - `toolBudget` 汇总预算守卫结果，若本轮没有模型工具调用预算事件，则返回默认 allowed；
    - `memory` 只返回目标数量、召回数量和 retriever 标识，不返回任何记忆正文；
    - `recommendedActions` 给出后续产品动作，例如等待审批、缩小工具调用批次或补充 sessionId。
    """

    model_gateway = build_model_gateway_governance_response(plan.model_gateway_decision)
    skill_admission = _skill_admission_summary(plan)
    tool_budget = _tool_budget_summary(plan.runtime_events)
    memory = _memory_summary(plan)
    workspace = workspace_context.to_summary()
    approval_required = bool(plan.requires_human_approval)
    available = bool(model_gateway.get("available")) and skill_admission["allowed"] and tool_budget["allowed"]
    return {
        "available": available,
        "approvalRequired": approval_required,
        "workspace": {
            "workspaceKey": workspace["workspaceKey"],
            "isolationLevel": workspace["isolationLevel"],
            "cacheNamespace": workspace["cacheNamespace"],
            "memoryNamespace": workspace["memoryNamespace"],
            "artifactNamespace": workspace["artifactNamespace"],
        },
        "modelGateway": model_gateway,
        "skillAdmission": skill_admission,
        "toolBudget": tool_budget,
        "memory": memory,
        "plannedToolCount": len(plan.tool_plans),
        "plannedToolNames": tuple(tool.tool_name for tool in plan.tool_plans),
        "displaySummary": _display_summary(model_gateway, skill_admission, tool_budget, approval_required),
        "recommendedActions": _recommended_actions(model_gateway, skill_admission, tool_budget, memory, approval_required),
    }


def _skill_admission_summary(plan: AgentPlan) -> dict[str, Any]:
    """构建 Agent Skill 准入治理摘要。

    Skill admission 是工具 schema 暴露前的能力包级防线。这里优先从 `AgentSkillPlan` 读取，而不是重新
    解析 runtime event，是为了保持摘要构建简单可靠：runtime event 适合 replay 和审计，plan 字段适合
    同步响应摘要。两者来自同一份编排结果，不做二次决策。
    """

    selected = tuple(
        {
            "skillCode": item.skill_code,
            "displayName": item.display_name,
            "domain": item.domain.value,
            "riskLevel": item.risk_level,
            "admissionStatus": item.admission_status,
            "admissionReasons": item.admission_reasons,
            "requiredPermissions": item.required_permissions,
        }
        for item in plan.skill_plan.selected_skills
    )
    rejected = tuple(
        {
            "skillCode": item.skill_code,
            "displayName": item.display_name,
            "domain": item.domain.value,
            "riskLevel": item.risk_level,
            "admissionStatus": item.admission_status,
            "admissionReasons": item.admission_reasons,
            "requiredPermissions": item.required_permissions,
        }
        for item in plan.skill_plan.rejected_skills
    )
    conditional_count = sum(1 for item in plan.skill_plan.selected_skills if item.admission_status == "CONDITIONAL")
    return {
        "allowed": not rejected,
        "availableSkillCount": plan.skill_plan.available_skill_count,
        "selectedSkillCount": len(selected),
        "rejectedSkillCount": len(rejected),
        "conditionalSkillCount": conditional_count,
        "selectedSkills": selected,
        "rejectedSkills": rejected,
        "rationale": plan.skill_plan.rationale,
        "displaySummary": _skill_admission_display_summary(selected, rejected, conditional_count),
    }


def _skill_admission_display_summary(
    selected: tuple[dict[str, Any], ...],
    rejected: tuple[dict[str, Any], ...],
    conditional_count: int,
) -> str:
    """生成 Skill 准入卡片摘要。"""

    if rejected:
        return "部分 Agent Skill 命中但未通过权限、角色或风险准入。"
    if conditional_count > 0:
        return "Agent Skill 已条件性启用；生产环境应补充可信权限和角色事实。"
    if selected:
        return "Agent Skill 已完成选择与准入评估。"
    return "本轮未命中明确 Agent Skill。"


def _tool_budget_summary(events: tuple[AgentRuntimeEvent, ...]) -> dict[str, Any]:
    """从 runtime events 中提取工具调用预算守卫摘要。

    新版本优先读取 `MODEL_TOOL_CALL_BUDGET_GUARDED`。同时保留对历史
    stage=`guard_model_tool_call_budget` 的兼容，是为了让旧事件回放、测试夹具或外部 Java replay
    在迁移期间仍能生成治理摘要。这里读取最后一条，是为了兼容未来多模型节点或多轮推理：最后一次
    预算守卫通常最接近最终 ToolPlan。
    """

    budget_events = tuple(
        event
        for event in events
        if event.event_type == AgentRuntimeEventType.MODEL_TOOL_CALL_BUDGET_GUARDED
        or event.stage == "guard_model_tool_call_budget"
    )
    if not budget_events:
        return {
            "allowed": True,
            "guarded": False,
            "budgetIssueCodes": (),
            "displaySummary": "本轮未触发模型工具调用预算阻断。",
        }
    attributes = dict(budget_events[-1].attributes)
    issue_codes = tuple(attributes.get("budgetIssueCodes") or ())
    return {
        "allowed": not issue_codes,
        "guarded": True,
        "proposedCount": attributes.get("proposedCount", 0),
        "acceptedCountBeforeGuard": attributes.get("acceptedCountBeforeGuard", 0),
        "acceptedCountAfterGuard": attributes.get("acceptedCountAfterGuard", 0),
        "autoExecutableCountBeforeGuard": attributes.get("autoExecutableCountBeforeGuard", 0),
        "highRiskCountBeforeGuard": attributes.get("highRiskCountBeforeGuard", 0),
        "totalArgumentsBytes": attributes.get("totalArgumentsBytes", 0),
        "budgetIssueCodes": issue_codes,
        "policy": attributes.get("policy", {}),
        "displaySummary": (
            "智能网关已按工具调用预算阻断部分候选。"
            if issue_codes
            else "智能网关已评估工具调用预算，未发现阻断问题。"
        ),
    }


def _memory_summary(plan: AgentPlan) -> dict[str, Any]:
    """构建长期记忆治理摘要，不返回任何记忆正文。"""

    report = plan.memory_retrieval_report
    return {
        "retrievalTargetCount": len(plan.memory_plan.retrieval_targets),
        "writableMemoryTypeCount": len(plan.memory_plan.writable_memory_types),
        "totalRetrieved": report.total_retrieved,
        "retriever": report.attributes.get("retriever", "custom"),
        "defaultScope": plan.memory_plan.default_scope.value,
        "retentionDays": plan.memory_plan.retention_days,
    }


def _display_summary(
    model_gateway: dict[str, Any],
    skill_admission: dict[str, Any],
    tool_budget: dict[str, Any],
    approval_required: bool,
) -> str:
    """生成智能网关卡片的一句话摘要。"""

    if not model_gateway.get("available"):
        return "模型网关不可用或预算不足，Agent 已进入降级治理路径。"
    if not skill_admission["allowed"]:
        return "模型路由可用，但部分 Agent Skill 未通过准入治理。"
    if not tool_budget["allowed"]:
        return "模型路由可用，但工具调用预算已阻断部分候选。"
    if approval_required:
        return "模型路由和工具预算均已通过，但部分工具仍需人工审批。"
    return "模型路由、工具预算、workspace 隔离和记忆检索治理均已完成。"


def _recommended_actions(
    model_gateway: dict[str, Any],
    skill_admission: dict[str, Any],
    tool_budget: dict[str, Any],
    memory: dict[str, Any],
    approval_required: bool,
) -> tuple[str, ...]:
    """汇总智能网关下一步建议。"""

    actions: list[str] = []
    actions.extend(model_gateway.get("recommendedActions") or ())
    if not skill_admission["allowed"]:
        actions.append("检查被拒绝 Skill 的权限、角色、租户开关或风险策略，并由 permission-admin 提供可信准入事实。")
    elif skill_admission["conditionalSkillCount"] > 0:
        actions.append("当前存在条件性启用的 Skill；生产环境应补充 grantedPermissions、actorRole 和策略版本。")
    if not tool_budget["allowed"]:
        actions.append("缩小本轮模型工具调用批次，或把高风险/长耗时工具拆到后续确认步骤。")
    if approval_required:
        actions.append("将需审批工具计划提交 Java agent-runtime，等待项目负责人或管理员确认。")
    if memory["retrievalTargetCount"] > 0 and memory["totalRetrieved"] == 0:
        actions.append("本轮没有召回长期记忆；如需要复用历史经验，可先确认 workspace 和记忆写入闭环。")
    if not actions:
        actions.append("可以继续把工具计划提交 Java 控制面生成审计记录。")
    return tuple(actions)
