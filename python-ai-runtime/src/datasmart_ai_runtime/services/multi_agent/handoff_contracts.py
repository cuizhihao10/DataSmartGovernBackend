"""多 Agent specialist handoff 合同生成规则。

执行计划图里的 work item 和 collaboration edge 已经能表达“谁参与”和“谁依赖谁”，但商业化 Agent Host
还需要更明确的 handoff 合同：当某个专项 Agent 不能继续前进时，到底是交给权限守门、人审、运维恢复，
还是交给 Java 控制面做 outbox/worker receipt 预检。

本模块只生成低敏控制面合同，不执行任何副作用：
- 不创建审批单；
- 不写 outbox；
- 不恢复 checkpoint；
- 不派发 worker；
- 不读取用户 prompt、SQL、工具参数、样本数据或模型输出。

真实执行仍必须由 Java 控制面、permission-admin、task-management/data-quality/data-sync 等服务完成。
"""

from __future__ import annotations

from collections.abc import Iterable

from datasmart_ai_runtime.services.multi_agent.execution_plan_models import (
    MultiAgentExecutionWorkItem,
    MultiAgentHandoffContract,
)


JAVA_CONTROL_PLANE_ROLE = "JAVA_CONTROL_PLANE"
"""伪角色：表示 handoff 已经越过 Python Runtime，需要进入 Java host/control plane。"""


def build_specialist_handoff_contracts(
    work_items: Iterable[MultiAgentExecutionWorkItem],
    *,
    plan_status: str,
) -> tuple[MultiAgentHandoffContract, ...]:
    """根据执行前 work item 生成低敏 specialist handoff 合同。

    生成原则：
    - `WAITING_HUMAN_OR_PERMISSION_HANDOFF`：优先交给 `PERMISSION_AGENT` 守门；如果权限 Agent 不在场，
      则交给 Java 控制面等待 host fact；
    - `BLOCKED_BEFORE_EXECUTION`：优先交给 `OPS_AGENT` 做恢复诊断；如果运维 Agent 不在场，则交给 Java 控制面；
    - `READY_FOR_CONTROL_PLANE_HANDOFF`：生成一条总控到 Java 控制面的执行预检合同，说明仍需等待 outbox 与
      worker receipt 等服务端事实。

    合同最多返回 40 条，避免异常调度视图导致响应膨胀。真实生产环境如果需要更多明细，应进入 Java
    projection 或审计查询，而不是让 `/agent/plans` 同步响应无限变大。
    """

    items = tuple(work_items)
    roles = {item.agent_role for item in items}
    contracts: list[MultiAgentHandoffContract] = []

    if plan_status == "READY_FOR_CONTROL_PLANE_HANDOFF":
        source_role = "MASTER_ORCHESTRATOR" if "MASTER_ORCHESTRATOR" in roles else _first_role(items)
        contracts.append(_control_plane_preflight_contract(source_role))
        return tuple(contracts)

    for index, item in enumerate(items):
        if _requires_handoff_contract(item, plan_status):
            contracts.append(_contract_for_work_item(index, item, roles=roles, plan_status=plan_status))
        if len(contracts) >= 40:
            break
    return tuple(contracts)


def _requires_handoff_contract(item: MultiAgentExecutionWorkItem, plan_status: str) -> bool:
    """判断单个 work item 是否需要生成 handoff 合同。"""

    return (
        item.handoff_required
        or item.status.startswith("WAITING")
        or item.status.startswith("BLOCKED")
        or plan_status in {"WAITING_HUMAN_OR_PERMISSION_HANDOFF", "BLOCKED_BEFORE_EXECUTION"}
    )


def _contract_for_work_item(
    index: int,
    item: MultiAgentExecutionWorkItem,
    *,
    roles: set[str],
    plan_status: str,
) -> MultiAgentHandoffContract:
    """把单个 work item 转换为 handoff 合同。"""

    if item.status.startswith("BLOCKED") or plan_status == "BLOCKED_BEFORE_EXECUTION":
        target_role = "OPS_AGENT" if "OPS_AGENT" in roles and item.agent_role != "OPS_AGENT" else JAVA_CONTROL_PLANE_ROLE
        handoff_type = "RUNTIME_RECOVERY_HANDOFF"
        status = "BLOCKED_WAITING_RECOVERY_FACT"
        required_facts = ("RUNTIME_RECOVERY_FACT",)
        missing_codes = ("CONTROL_PLANE_RECOVERY_FACT_REQUIRED",)
        next_action = "WAIT_FOR_RUNTIME_RECOVERY_FACT"
    else:
        target_role = (
            "PERMISSION_AGENT"
            if "PERMISSION_AGENT" in roles and item.agent_role != "PERMISSION_AGENT"
            else JAVA_CONTROL_PLANE_ROLE
        )
        handoff_type = "PERMISSION_OR_HUMAN_APPROVAL_HANDOFF"
        status = "WAITING_HOST_HANDOFF_FACTS"
        required_facts = ("APPROVAL_DECISION_FACT", "PERMISSION_BOUNDARY_CONFIRMATION")
        missing_codes = ("APPROVAL_FACT_REQUIRED", "PERMISSION_BOUNDARY_FACT_REQUIRED")
        next_action = "MATERIALIZE_HANDOFF_FACT_IN_JAVA_CONTROL_PLANE"

    return MultiAgentHandoffContract(
        contract_id=f"handoff-{index + 1}-{_role_fragment(item.agent_role)}",
        source_agent_role=item.agent_role,
        target_agent_role=target_role,
        handoff_type=handoff_type,
        status=status,
        reason_codes=item.blocked_by or ("HUMAN_OR_PERMISSION_HANDOFF_REQUIRED",),
        required_host_fact_types=required_facts,
        missing_evidence_codes=missing_codes,
        next_action=next_action,
        source_work_item_status=item.status,
        side_effect_boundary=_side_effect_boundary(human_in_the_loop=handoff_type.endswith("APPROVAL_HANDOFF")),
    )


def _control_plane_preflight_contract(source_role: str) -> MultiAgentHandoffContract:
    """生成总控到 Java 控制面的执行预检合同。"""

    return MultiAgentHandoffContract(
        contract_id=f"handoff-control-plane-{_role_fragment(source_role)}",
        source_agent_role=source_role,
        target_agent_role=JAVA_CONTROL_PLANE_ROLE,
        handoff_type="CONTROL_PLANE_EXECUTION_PREFLIGHT",
        status="READY_FOR_CONTROL_PLANE_PREFLIGHT",
        reason_codes=("PLAN_READY_FOR_HOST_CONTROL_PLANE",),
        required_host_fact_types=("OUTBOX_WRITE_CONFIRMATION", "WORKER_RECEIPT_PROJECTION"),
        missing_evidence_codes=(),
        next_action="WAIT_FOR_OUTBOX_AND_WORKER_RECEIPT_FACTS",
        source_work_item_status="PLANNED_READY",
        side_effect_boundary=_side_effect_boundary(human_in_the_loop=False),
    )


def _side_effect_boundary(*, human_in_the_loop: bool) -> dict[str, bool | str]:
    """返回统一副作用边界，防止 handoff contract 被误解为执行命令。"""

    return {
        "toolExecutedByPython": False,
        "outboxWrittenByPython": False,
        "approvalCreatedByPython": False,
        "checkpointMutatedByPython": False,
        "workerDispatchedByPython": False,
        "javaControlPlaneRequired": True,
        "humanInTheLoopRequired": human_in_the_loop,
        "payloadPolicy": "LOW_SENSITIVE_MULTI_AGENT_HANDOFF_CONTRACT_ONLY",
    }


def _first_role(items: tuple[MultiAgentExecutionWorkItem, ...]) -> str:
    """读取第一个可用角色；异常空计划时回退为总控角色。"""

    return items[0].agent_role if items else "MASTER_ORCHESTRATOR"


def _role_fragment(role: str) -> str:
    """把角色名转换为稳定、低敏、URL/日志友好的 contract id 片段。"""

    return role.lower().replace("_", "-") or "unknown"
