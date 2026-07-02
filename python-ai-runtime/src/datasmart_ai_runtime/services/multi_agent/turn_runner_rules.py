"""受控多 Agent turn runner 的纯规则函数。

本文件不依赖 LangGraph，也不访问数据库、网络、文件系统或模型。它只把低敏 session/work item 状态
映射为 turn runner 状态、执行前证据缺口、manager-as-tools 描述和下一步动作。拆出这些规则后，
`controlled_turn_runner.py` 可以专注于图节点编排。
"""

from __future__ import annotations

import os
from typing import Any, Mapping

from datasmart_ai_runtime.domain.contracts import AgentPlan
from datasmart_ai_runtime.services.multi_agent.turn_runner_models import ControlledAgentTurnAttempt


def turn_status(session_status: str, state: Mapping[str, Any]) -> str:
    """把会话状态映射为 turn runner 状态。"""

    if int(state.get("currentTurnDepth") or 1) > int(state.get("maxTurnDepth") or 1):
        return "BLOCKED_TURN_DEPTH_EXCEEDED"
    normalized = session_status.upper()
    if normalized.startswith("BLOCKED"):
        return "BLOCKED_WAITING_RECOVERY"
    if normalized == "WAITING_APPROVAL_OR_HANDOFF":
        return "WAITING_APPROVAL_OR_HANDOFF_FACT"
    if normalized == "WAITING_CONTROL_PLANE_FEEDBACK":
        return "WAITING_CONTROL_PLANE_FEEDBACK"
    if normalized == "WAITING_HUMAN_TAKEOVER":
        return "WAITING_HUMAN_TAKEOVER"
    if normalized == "READY_FOR_AGENT_TURN":
        return "READY_FOR_SPECIALIST_TURN"
    if normalized == "READY_FOR_CONTROL_PLANE_HANDOFF":
        return "READY_FOR_JAVA_CONTROL_PLANE_HANDOFF"
    if normalized == "DEGRADED_DRAFT_ONLY":
        return "READY_FOR_DRAFT_ONLY_TURN"
    if normalized == "COMPLETED_OR_SUMMARIZED":
        return "COMPLETED_OR_SUMMARIZED"
    return "WAITING_SESSION_ORCHESTRATOR_REVIEW"


def required_evidence_codes(session_status: str, raw_item: Mapping[str, Any]) -> tuple[str, ...]:
    """根据 work item 状态生成真实执行前必须补齐的 host fact。"""

    normalized = session_status.upper()
    codes: list[str] = ["TURN_CHECKPOINT_REQUIRED"]
    if normalized.startswith("BLOCKED"):
        codes.append("RUNTIME_RECOVERY_FACT_REQUIRED")
    elif normalized == "WAITING_APPROVAL_OR_HANDOFF":
        codes.append("APPROVAL_DECISION_FACT_REQUIRED")
    elif normalized == "WAITING_CONTROL_PLANE_FEEDBACK":
        codes.append("CONTROL_PLANE_FEEDBACK_EVENT_REQUIRED")
    elif normalized == "WAITING_HUMAN_TAKEOVER":
        codes.append("HUMAN_TAKEOVER_FACT_REQUIRED")
    elif normalized in {"READY_FOR_CONTROL_PLANE_HANDOFF", "READY_FOR_AGENT_TURN"}:
        codes.extend(("JAVA_COMMAND_PROPOSAL_OR_OUTBOX_REQUIRED", "WORKER_RECEIPT_REQUIRED"))
    elif normalized == "DEGRADED_DRAFT_ONLY":
        codes.append("DRAFT_REVIEW_FACT_REQUIRED")
    if bool(raw_item.get("handoffRequired")) and "APPROVAL_DECISION_FACT_REQUIRED" not in codes:
        codes.append("APPROVAL_OR_HANDOFF_FACT_REQUIRED")
    return tuple(dict.fromkeys(codes))


def manager_tool_descriptor(attempt: ControlledAgentTurnAttempt) -> dict[str, Any]:
    """生成 manager-as-tools 的低敏虚拟工具描述。"""

    return {
        "toolName": attempt.manager_tool_name,
        "agentRole": attempt.agent_role,
        "deliveryTier": attempt.delivery_tier,
        "turnStatus": attempt.turn_status,
        "invocationPolicy": "MANAGER_AS_TOOLS_PREVIEW_ONLY_REQUIRES_JAVA_CONTROL_PLANE",
        "requiredEvidenceCodes": attempt.required_evidence_codes,
        "notIncluded": (
            "arguments",
            "prompt",
            "sql",
            "sampleData",
            "modelOutput",
            "credential",
            "artifactBody",
        ),
        "payloadPolicy": "LOW_SENSITIVE_MANAGER_AS_TOOLS_DESCRIPTOR_ONLY",
    }


def run_status(attempts: tuple[ControlledAgentTurnAttempt, ...], policy: Mapping[str, Any]) -> str:
    """计算整轮 turn runner 状态。"""

    if not attempts:
        return "NO_AGENT_TURN_CANDIDATES"
    if bool(policy.get("turnDepthExceeded")):
        return "BLOCKED_TURN_DEPTH_EXCEEDED"
    statuses = {attempt.turn_status for attempt in attempts}
    if any(status.startswith("BLOCKED") for status in statuses):
        return "BLOCKED_WAITING_RECOVERY"
    if any(status.startswith("WAITING_APPROVAL") for status in statuses):
        return "WAITING_APPROVAL_OR_HANDOFF_FACT"
    if "WAITING_CONTROL_PLANE_FEEDBACK" in statuses:
        return "WAITING_CONTROL_PLANE_FEEDBACK"
    if "WAITING_HUMAN_TAKEOVER" in statuses:
        return "WAITING_HUMAN_TAKEOVER"
    if "READY_FOR_JAVA_CONTROL_PLANE_HANDOFF" in statuses:
        return "READY_FOR_JAVA_CONTROL_PLANE_HANDOFF"
    if "READY_FOR_SPECIALIST_TURN" in statuses:
        return "READY_FOR_SPECIALIST_AGENT_TURNS"
    if "READY_FOR_DRAFT_ONLY_TURN" in statuses:
        return "READY_FOR_DRAFT_ONLY_TURNS"
    return "TURN_RUNNER_OBSERVED_ONLY"


def next_actions(
    runner_status: str,
    attempts: tuple[ControlledAgentTurnAttempt, ...],
    policy: Mapping[str, Any],
) -> tuple[str, ...]:
    """生成 turn runner 下一步动作建议。"""

    actions: list[str] = []
    if runner_status == "BLOCKED_TURN_DEPTH_EXCEEDED":
        actions.append("STOP_AUTONOMOUS_LOOP_AND_HAND_OFF_TO_HUMAN")
    elif runner_status == "BLOCKED_WAITING_RECOVERY":
        actions.append("WAIT_FOR_RUNTIME_RECOVERY_FACT")
    elif runner_status == "WAITING_APPROVAL_OR_HANDOFF_FACT":
        actions.append("WAIT_FOR_APPROVAL_OR_HANDOFF_FACT")
    elif runner_status == "WAITING_CONTROL_PLANE_FEEDBACK":
        actions.append("REPLAY_CONTROL_PLANE_EVENTS_AND_WORKER_RECEIPTS")
    elif runner_status == "WAITING_HUMAN_TAKEOVER":
        actions.append("HAND_OFF_TO_HUMAN_OPERATOR")
    elif runner_status == "READY_FOR_JAVA_CONTROL_PLANE_HANDOFF":
        actions.append("CALL_JAVA_COMMAND_PROPOSAL_AFTER_GRAPH_PAYLOAD_AND_POLICY_READY")
    elif runner_status == "READY_FOR_SPECIALIST_AGENT_TURNS":
        actions.append("RUN_SPECIALIST_TURN_ONLY_AFTER_CHECKPOINT_AND_BOUNDARY_CONFIRMATION")
    elif runner_status == "READY_FOR_DRAFT_ONLY_TURNS":
        actions.append("SHOW_LOW_SENSITIVE_DRAFT_AND_WAIT_FOR_REVIEW")
    else:
        actions.append("KEEP_TURN_RUNNER_OBSERVATION_AND_WAIT_FOR_NEXT_HOST_EVENT")
    if attempts:
        actions.append("PERSIST_TURN_CHECKPOINT_BEFORE_NEXT_STEP")
    if bool(policy.get("javaControlPlaneRequiredForSideEffects")):
        actions.append("KEEP_PYTHON_RUNTIME_SIDE_EFFECT_FREE")
    return tuple(dict.fromkeys(actions))


def current_turn_depth(plan: AgentPlan, durable_loop: Mapping[str, Any]) -> int:
    """读取当前 turn 深度。

    当前 Durable Loop 还没有专用 turnDepth 字段，因此默认从 1 开始。后续接入 Redis/MySQL checkpoint 后，
    可以在 `durable_loop.attributes.turnDepth` 或 Java projection 中回填真实深度，不需要改变响应合同。
    """

    attributes = durable_loop.get("attributes") if isinstance(durable_loop.get("attributes"), Mapping) else {}
    return non_negative_int(attributes.get("turnDepth")) or (2 if plan.runtime_events else 1)


def positive_env_int(name: str, *, default: int) -> int:
    """读取正整数环境变量。"""

    raw = os.getenv(name)
    if raw is None:
        return default
    try:
        parsed = int(raw.strip())
    except ValueError:
        return default
    return parsed if parsed > 0 else default


def non_negative_int(value: object | None) -> int:
    """把数量字段转换为非负整数。"""

    if isinstance(value, int):
        return max(0, value)
    if value is None:
        return 0
    try:
        return max(0, int(str(value).strip()))
    except ValueError:
        return 0


def safe_fragment(value: str) -> str:
    """把自由文本片段收敛成安全 ID 片段。"""

    fragment = "".join(char.lower() if char.isalnum() else "-" for char in value)
    return "-".join(part for part in fragment.split("-") if part)[:80] or "unknown"
