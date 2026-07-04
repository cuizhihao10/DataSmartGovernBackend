"""多智能体执行前计划的低敏规则函数。

这些函数被拆出 LangGraph workflow，是为了让图文件只描述“节点如何流转”，而把角色职责、阻塞原因、
工作泳道、下一步动作等可复用规则集中维护。所有规则只处理角色、状态、工具名、Skill 编码、记忆类型
等低敏字段，不读取 prompt、SQL、工具参数值或样本数据。
"""

from __future__ import annotations

from typing import Any, Mapping

from datasmart_ai_runtime.services.multi_agent.execution_plan_models import (
    MultiAgentExecutionEdge,
)


def sanitize_agent_view(agent: Mapping[str, Any]) -> dict[str, Any]:
    """裁剪会话调度中的 Agent 字段，只保留执行计划允许消费的低敏白名单。"""

    return {
        "role": string_value(agent.get("role")),
        "participationMode": string_value(agent.get("participationMode")),
        "status": string_value(agent.get("status")),
        "plannedToolNames": string_tuple(agent.get("plannedToolNames")),
        "visibleSkillCodes": string_tuple(agent.get("visibleSkillCodes")),
        "memoryDependencies": string_tuple(agent.get("memoryDependencies")),
        "requiresHandoff": bool(agent.get("requiresHandoff")),
    }


def append_edge(
    edges: list[MultiAgentExecutionEdge],
    from_role: str,
    to_role: str,
    edge_type: str,
    reason_code: str,
) -> None:
    """追加去重后的协作边，避免一个角色因为多条规则产生重复边。"""

    candidate = MultiAgentExecutionEdge(from_role, to_role, edge_type, reason_code)
    if candidate not in edges:
        edges.append(candidate)


def work_item_status(global_status: str, agent_status: str, handoff_required: bool) -> str:
    """把会话状态和单 Agent 状态压缩成执行前工作项状态。"""

    if global_status == "BLOCKED" or agent_status == "BLOCKED":
        return "BLOCKED_BY_CONTROL_PLANE"
    if handoff_required or agent_status == "APPROVAL_REQUIRED":
        return "WAITING_HUMAN_OR_PERMISSION_HANDOFF"
    if global_status == "DEGRADED" or agent_status == "DEGRADED":
        return "DEGRADED_CAN_PREPARE_DRAFT"
    return "PLANNED_READY"


def responsibility_for_role(role: str) -> str:
    """返回角色的静态职责说明，避免把用户目标文本塞进执行计划。"""

    return {
        "MASTER_ORCHESTRATOR": "统一拆解目标、整合专家计划、维护最终交付摘要。",
        "DATASOURCE_AGENT": "准备数据源元数据读取、连接诊断和接入边界说明。",
        "DATA_QUALITY_AGENT": "准备质量规则、异常检测和治理建议的低敏草案。",
        "DATA_SYNC_AGENT": "准备同步/ETL 路径、执行模式和一致性策略草案。",
        "TASK_AGENT": "准备任务草案、状态流转和可恢复执行控制面衔接。",
        "PERMISSION_AGENT": "校验权限、审批、租户/项目边界和高风险动作守门。",
        "MEMORY_AGENT": "提供短期/长期记忆依赖说明和低敏上下文边界。",
        "OPS_AGENT": "观察降级、预算、队列和运行风险并给出运维建议。",
        "KNOWLEDGE_AGENT": "准备治理知识 RAG 证据、资产口径、业务术语和规则说明上下文。",
    }.get(role, "承接当前会话调度指定的低敏协作职责。")


def execution_lane(role: str, mode: str) -> str:
    """根据角色和参与模式选择执行前工作泳道。"""

    if role == "MASTER_ORCHESTRATOR" or mode == "PRIMARY":
        return "PRIMARY_COORDINATION"
    if mode == "GUARDRAIL" or role in {"PERMISSION_AGENT", "MEMORY_AGENT"}:
        return "GOVERNANCE_GUARDRAIL"
    if mode == "OBSERVER" or role == "OPS_AGENT":
        return "OBSERVABILITY_DIAGNOSTIC"
    return "DOMAIN_SPECIALIST_DRAFT"


def depends_on_roles(role: str, all_roles: tuple[str, ...]) -> tuple[str, ...]:
    """生成 Agent 工作项的低敏依赖角色。"""

    dependencies: list[str] = []
    if role != "MASTER_ORCHESTRATOR" and "MASTER_ORCHESTRATOR" in all_roles:
        dependencies.append("MASTER_ORCHESTRATOR")
    if role == "DATA_QUALITY_AGENT" and "DATASOURCE_AGENT" in all_roles:
        dependencies.append("DATASOURCE_AGENT")
    if role == "DATA_SYNC_AGENT":
        for dependency in ("DATASOURCE_AGENT", "DATA_QUALITY_AGENT"):
            if dependency in all_roles:
                dependencies.append(dependency)
    if role in {"DATASOURCE_AGENT", "DATA_QUALITY_AGENT", "DATA_SYNC_AGENT", "TASK_AGENT", "PERMISSION_AGENT"}:
        if "KNOWLEDGE_AGENT" in all_roles:
            dependencies.append("KNOWLEDGE_AGENT")
    return tuple(dict.fromkeys(dependencies))


def blocked_by(global_status: str, agent_status: str, handoff_required: bool) -> tuple[str, ...]:
    """输出机器可读阻塞原因码，不暴露自由文本或敏感上下文。"""

    reasons: list[str] = []
    if global_status == "BLOCKED" or agent_status == "BLOCKED":
        reasons.append("CONTROL_PLANE_BLOCKED")
    if global_status == "DEGRADED" or agent_status == "DEGRADED":
        reasons.append("DEGRADED_DEPENDENCY")
    if handoff_required or agent_status == "APPROVAL_REQUIRED":
        reasons.append("HUMAN_OR_PERMISSION_HANDOFF_REQUIRED")
    return tuple(reasons)


def next_action_for_work_item(role: str, mode: str, status: str) -> str:
    """为单个工作项生成下一步动作码。"""

    if status == "BLOCKED_BY_CONTROL_PLANE":
        return "WAIT_FOR_CONTROL_PLANE_RECOVERY"
    if status == "WAITING_HUMAN_OR_PERMISSION_HANDOFF":
        return "WAIT_FOR_APPROVAL_OR_HANDOFF_FACT"
    if role == "MASTER_ORCHESTRATOR" or mode == "PRIMARY":
        return "COORDINATE_SPECIALIST_RESULTS"
    if mode == "GUARDRAIL":
        return "VERIFY_BOUNDARY_BEFORE_SIDE_EFFECTS"
    if mode == "OBSERVER":
        return "OBSERVE_AND_REPORT_RUNTIME_RISK"
    return "PREPARE_LOW_SENSITIVE_DOMAIN_DRAFT"


def next_actions_for_plan_status(plan_status: str) -> tuple[str, ...]:
    """根据全局计划状态生成可执行前下一步建议。"""

    mapping = {
        "BLOCKED_BEFORE_EXECUTION": (
            "RECOVER_MODEL_GATEWAY_PERMISSION_OR_RUNTIME_DEPENDENCY",
            "DO_NOT_EXECUTE_TOOLS",
        ),
        "WAITING_HUMAN_OR_PERMISSION_HANDOFF": (
            "CREATE_OR_WAIT_HOST_APPROVAL_FACT",
            "RESUME_AFTER_PERMISSION_BOUNDARY_CONFIRMED",
        ),
        "DEGRADED_CAN_PREPARE_DRAFT": (
            "PREPARE_DRAFT_ONLY",
            "SYNC_DEGRADED_REASON_TO_OBSERVABILITY",
        ),
        "READY_FOR_CONTROL_PLANE_HANDOFF": (
            "HANDOFF_TO_JAVA_CONTROL_PLANE",
            "WAIT_FOR_OUTBOX_OR_WORKER_RECEIPT_BEFORE_SIDE_EFFECTS",
        ),
    }
    return mapping.get(plan_status, ("REVIEW_MULTI_AGENT_PLAN_STATUS",))


def string_tuple(value: object | None) -> tuple[str, ...]:
    """把列表、集合或单值转换为字符串元组，并去除空值。"""

    if value is None:
        return ()
    if isinstance(value, str):
        return (value,) if value else ()
    if isinstance(value, (tuple, list, set, frozenset)):
        return tuple(str(item).strip() for item in value if str(item).strip())
    text = str(value).strip()
    return (text,) if text else ()


def string_value(value: object | None) -> str | None:
    """读取非空字符串值。"""

    if value is None:
        return None
    text = str(value).strip()
    return text or None


def truthy_env(name: str, *, default: bool) -> bool:
    """读取布尔环境变量。"""

    import os

    raw = os.getenv(name)
    if raw is None:
        return default
    return raw.strip().lower() in {"1", "true", "yes", "y", "on"}
