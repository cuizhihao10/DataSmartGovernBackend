"""Agent 能力闭口验收计算器。

这个模块不是新的 Agent 能力清单，而是复用 `agent_capability_matrix.py` 里已经维护的能力域与子能力，
把它们转换成“项目能不能进入最终收口”的验收视图。

为什么要单独拆文件：
- 能力矩阵负责描述 tools、skills、memory、LLM 等能力本身，已经接近 400 行；
- 闭口验收负责计算分数、门禁、P0 阻塞项和下一步收敛动作，属于另一个关注点；
- 拆出来可以避免主矩阵文件继续膨胀，也让后续把验收规则迁移到配置中心或管理台时更容易替换。

低敏边界：
- 这里只读取 capabilityId、displayName、status、ownerModule、closureGap、nextAction 等治理元数据；
- 不读取 prompt、SQL、工具参数、记忆正文、模型输出、文件正文、内部 URL 或凭据；
- 即使未来能力矩阵接入数据库，本计算器也应继续只消费低敏治理字段。
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from typing import Any


class AgentClosureGateDecision(str, Enum):
    """Agent 能力闭口门禁结果。

    这里的门禁不是“能不能启动服务”，而是“能不能把 Agent 部分视为商业化闭环已完成”：
    - 仍有 P0 planned/blocked 时，不能进入最终收尾；
    - P0 只剩 control-plane-ready 时，说明合同已有但真实副作用闭环不足，需要先转实；
    - P0 只剩 partial-closed-loop 时，可以进入最终加固，但仍要带着生产补强清单；
    - 全部 P0 operational 才是真正的 Agent 核心闭口。
    """

    NOT_READY_FOR_PROJECT_CLOSURE = "NOT_READY_FOR_PROJECT_CLOSURE"
    CLOSE_CONTROL_PLANE_GAPS_FIRST = "CLOSE_CONTROL_PLANE_GAPS_FIRST"
    FINAL_HARDENING_WITH_P0_REMEDIATION = "FINAL_HARDENING_WITH_P0_REMEDIATION"
    READY_FOR_FINAL_CLOSURE = "READY_FOR_FINAL_CLOSURE"


@dataclass(frozen=True)
class _ClosureGap:
    """闭口缺口的内部低敏表示。

    该对象只保存用于验收、管理台展示和路线图决策的元数据。它刻意不保存 `currentEvidence`，
    因为 evidence 往往更长、更接近实现细节，放进高频 `/agent/plans` 响应会增加噪声。
    """

    domain_id: str
    capability_id: str
    display_name: str
    status: str
    owner_module: str
    closure_gap: str
    next_action: str
    risk_type: str

    def to_summary(self) -> dict[str, Any]:
        """输出给 API/计划响应的低敏缺口摘要。"""

        return {
            "domainId": self.domain_id,
            "capabilityId": self.capability_id,
            "displayName": self.display_name,
            "status": self.status,
            "ownerModule": self.owner_module,
            "closureGap": self.closure_gap,
            "nextAction": self.next_action,
            "riskType": self.risk_type,
        }


_STATUS_SCORES = {
    "operational": 100,
    "partial_closed_loop": 70,
    "control_plane_ready": 45,
    "planned": 10,
    "blocked": 0,
}


def build_agent_capability_closure_readiness(domains: tuple[Any, ...]) -> dict[str, Any]:
    """基于能力矩阵生成 Agent 最终闭口验收视图。

    参数说明：
    - `domains`：来自 `AgentCapabilityMatrixService.domains` 的能力域元组。这里使用结构化读取而非强类型导入，
      是为了避免闭口计算器与矩阵声明形成循环依赖。

    返回值说明：
    - `readinessScore`：加权成熟度分数，P0 能力权重更高，用于快速观察趋势；
    - `gateDecision`：当前是否允许进入最终收口，以及应该先修哪类缺口；
    - `hardBlockers`：P0 中 planned/blocked 的能力，代表“还没形成可验收闭环”的硬阻塞；
    - `controlPlaneGaps`：P0 中只有控制面合同但真实副作用闭环不足的能力；
    - `operationalizationGaps`：已有部分真实闭环，但还需要生产化加固、持久化、告警或演练的能力。
    """

    sub_capabilities = tuple((domain, item) for domain in domains for item in getattr(domain, "sub_capabilities", ()))
    hard_blockers = _collect_gaps(sub_capabilities, {"planned", "blocked"}, "P0_HARD_GAP")
    control_plane_gaps = _collect_gaps(sub_capabilities, {"control_plane_ready"}, "P0_CONTROL_PLANE_GAP")
    operationalization_gaps = _collect_gaps(sub_capabilities, {"partial_closed_loop"}, "P0_OPERATIONALIZATION_GAP")
    gate_decision = _gate_decision(
        hard_blockers=hard_blockers,
        control_plane_gaps=control_plane_gaps,
        operationalization_gaps=operationalization_gaps,
    )
    next_actions = tuple(
        gap.next_action
        for gap in (hard_blockers + control_plane_gaps + operationalization_gaps)[:8]
        if gap.next_action
    )
    return {
        "schemaVersion": "datasmart.agent-capability-closure-readiness.v1",
        "snapshotType": "AGENT_CAPABILITY_CLOSURE_READINESS",
        "payloadPolicy": "LOW_SENSITIVE_CAPABILITY_METADATA_ONLY",
        "readinessScore": _readiness_score(domains),
        "gateDecision": gate_decision.value,
        "canStartFinalProjectClosure": gate_decision == AgentClosureGateDecision.READY_FOR_FINAL_CLOSURE,
        "recommendedDeliveryMode": _recommended_delivery_mode(gate_decision),
        "p0HardBlockerCount": len(hard_blockers),
        "p0ControlPlaneGapCount": len(control_plane_gaps),
        "p0OperationalizationGapCount": len(operationalization_gaps),
        "hardBlockers": tuple(gap.to_summary() for gap in hard_blockers[:8]),
        "controlPlaneGaps": tuple(gap.to_summary() for gap in control_plane_gaps[:8]),
        "operationalizationGaps": tuple(gap.to_summary() for gap in operationalization_gaps[:8]),
        "nextClosureActions": next_actions,
        "stageExitCriteria": (
            "P0 能力域不允许继续存在 planned 或 blocked 子能力。",
            "P0 control_plane_ready 子能力必须升级为至少 partial_closed_loop，不能只停留在诊断或合同。",
            "高风险工具必须具备 readiness、审批/澄清、outbox/worker receipt、审计和恢复事实。",
            "模型层只做成熟推理服务接入、缓存、限流、健康与 fallback，不在本项目内发散到训练或推理内核。",
        ),
    }


def _collect_gaps(
    sub_capabilities: tuple[tuple[Any, Any], ...],
    statuses: set[str],
    risk_type: str,
) -> list[_ClosureGap]:
    """收集指定状态集合下的 P0 缺口。

    只统计 P0 是为了服务“项目收口”这个决策场景。P1/P2 能力当然也重要，但它们不应在当前阶段继续拖住
    主链路闭环；否则项目会重新陷入无限扩展。
    """

    gaps: list[_ClosureGap] = []
    for domain, item in sub_capabilities:
        if getattr(domain, "priority", "") != "P0":
            continue
        status = _status_value(getattr(item, "status", "unknown"))
        if status not in statuses:
            continue
        gaps.append(
            _ClosureGap(
                domain_id=str(getattr(domain, "domain_id", "unknown")),
                capability_id=str(getattr(item, "capability_id", "unknown")),
                display_name=str(getattr(item, "display_name", "")),
                status=status,
                owner_module=str(getattr(item, "owner_module", "")),
                closure_gap=str(getattr(item, "closure_gap", "")),
                next_action=str(getattr(item, "next_action", "")),
                risk_type=risk_type,
            )
        )
    return gaps


def _gate_decision(
    *,
    hard_blockers: list[_ClosureGap],
    control_plane_gaps: list[_ClosureGap],
    operationalization_gaps: list[_ClosureGap],
) -> AgentClosureGateDecision:
    """把缺口集合转换成单一门禁决策。"""

    if hard_blockers:
        return AgentClosureGateDecision.NOT_READY_FOR_PROJECT_CLOSURE
    if control_plane_gaps:
        return AgentClosureGateDecision.CLOSE_CONTROL_PLANE_GAPS_FIRST
    if operationalization_gaps:
        return AgentClosureGateDecision.FINAL_HARDENING_WITH_P0_REMEDIATION
    return AgentClosureGateDecision.READY_FOR_FINAL_CLOSURE


def _recommended_delivery_mode(gate_decision: AgentClosureGateDecision) -> str:
    """给出当前阶段的研发模式建议，避免继续发散新增功能。"""

    if gate_decision == AgentClosureGateDecision.NOT_READY_FOR_PROJECT_CLOSURE:
        return "STOP_FEATURE_EXPANSION_AND_CLOSE_P0_HARD_GAPS"
    if gate_decision == AgentClosureGateDecision.CLOSE_CONTROL_PLANE_GAPS_FIRST:
        return "CONVERT_CONTROL_PLANE_CONTRACTS_TO_REAL_CLOSED_LOOPS"
    if gate_decision == AgentClosureGateDecision.FINAL_HARDENING_WITH_P0_REMEDIATION:
        return "FINAL_HARDENING_WITH_PRODUCTION_REMEDIATION"
    return "FINAL_PROJECT_CLOSURE_ALLOWED"


def _readiness_score(domains: tuple[Any, ...]) -> int:
    """计算加权成熟度分数。

    P0 能力权重设为 2，P1/P2 权重设为 1。这样一个 P0 planned 缺口会比 P1 planned 更明显地拉低总分，
    符合当前“先闭核心链路，再做增强能力”的收敛策略。
    """

    achieved = 0
    possible = 0
    for domain in domains:
        weight = 2 if getattr(domain, "priority", "") == "P0" else 1
        for item in getattr(domain, "sub_capabilities", ()):
            status = _status_value(getattr(item, "status", "unknown"))
            achieved += _STATUS_SCORES.get(status, 0) * weight
            possible += 100 * weight
    if possible <= 0:
        return 0
    return round(achieved * 100 / possible)


def _status_value(status: Any) -> str:
    """兼容 Enum 和字符串两种状态来源。"""

    value = getattr(status, "value", status)
    return str(value)


__all__ = [
    "AgentClosureGateDecision",
    "build_agent_capability_closure_readiness",
]
