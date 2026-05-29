"""Java Agent Runtime 工具执行控制契约。

该模块承载 Python 对 Java `execution-policy` 与 `auto-execute-sync` 响应的轻量解析。
拆分原因很朴素：HTTP client 负责请求，Provider 负责编排，契约模块负责结构化响应；三者分开后，
每个文件都更接近单一职责，也更符合本项目单文件规模控制要求。
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any


@dataclass(frozen=True)
class AgentRuntimeToolExecutionPolicyItem:
    """Java Run 级工具执行策略中的单工具条目。"""

    audit_id: str
    tool_code: str
    state: str
    decision: str
    auto_executable: bool
    requires_human_action: bool
    blocks_run: bool
    reasons: tuple[str, ...] = ()
    recommended_actions: tuple[str, ...] = ()


@dataclass(frozen=True)
class AgentRuntimeToolExecutionPolicy:
    """Java Run 级工具执行策略摘要。"""

    session_id: str
    run_id: str
    run_state: str
    run_terminal: bool
    auto_executable_count: int
    human_action_count: int
    blocking_count: int
    summary_reasons: tuple[str, ...]
    recommended_actions: tuple[str, ...]
    items: tuple[AgentRuntimeToolExecutionPolicyItem, ...]


@dataclass(frozen=True)
class AgentRuntimeToolAutoExecutionSummary:
    """Java 同步自动执行批次摘要。"""

    session_id: str
    run_id: str
    dry_run: bool
    requested_limit: int
    effective_limit: int
    executed_count: int
    failed_count: int
    skipped_count: int
    item_actions: tuple[dict[str, Any], ...]


class AgentRuntimeToolExecutionContractError(RuntimeError):
    """Java 工具执行控制契约解析错误。"""


def parse_execution_policy_response(payload: dict[str, Any]) -> AgentRuntimeToolExecutionPolicy:
    """解析 Java `PlatformApiResponse<AgentRunToolExecutionPolicyView>`。"""

    if payload.get("code") != 0:
        reason = payload.get("reason", "UNKNOWN")
        message = payload.get("message", "Java 工具执行策略接口返回失败")
        raise AgentRuntimeToolExecutionContractError(f"{reason}: {message}")
    data = payload.get("data")
    if not isinstance(data, dict):
        raise AgentRuntimeToolExecutionContractError("Java 工具执行策略响应 data 必须是对象")
    items: list[AgentRuntimeToolExecutionPolicyItem] = []
    for item in data.get("items") or ():
        if not isinstance(item, dict):
            continue
        items.append(
            AgentRuntimeToolExecutionPolicyItem(
                audit_id=str(item.get("auditId") or ""),
                tool_code=str(item.get("toolCode") or ""),
                state=str(item.get("state") or ""),
                decision=str(item.get("decision") or ""),
                auto_executable=bool(item.get("autoExecutable")),
                requires_human_action=bool(item.get("requiresHumanAction")),
                blocks_run=bool(item.get("blocksRun")),
                reasons=_string_tuple(item.get("reasons")),
                recommended_actions=_string_tuple(item.get("recommendedActions")),
            )
        )
    return AgentRuntimeToolExecutionPolicy(
        session_id=str(data.get("sessionId") or ""),
        run_id=str(data.get("runId") or ""),
        run_state=str(data.get("runState") or ""),
        run_terminal=bool(data.get("runTerminal")),
        auto_executable_count=int(data.get("autoExecutableCount") or 0),
        human_action_count=int(data.get("humanActionCount") or 0),
        blocking_count=int(data.get("blockingCount") or 0),
        summary_reasons=_string_tuple(data.get("summaryReasons")),
        recommended_actions=_string_tuple(data.get("recommendedActions")),
        items=tuple(items),
    )


def parse_auto_execution_response(payload: dict[str, Any]) -> AgentRuntimeToolAutoExecutionSummary:
    """解析 Java `PlatformApiResponse<AgentRunToolAutoExecutionResponse>`。"""

    if payload.get("code") != 0:
        reason = payload.get("reason", "UNKNOWN")
        message = payload.get("message", "Java 同步自动执行接口返回失败")
        raise AgentRuntimeToolExecutionContractError(f"{reason}: {message}")
    data = payload.get("data")
    if not isinstance(data, dict):
        raise AgentRuntimeToolExecutionContractError("Java 同步自动执行响应 data 必须是对象")
    item_actions: list[dict[str, Any]] = []
    for item in data.get("items") or ():
        if not isinstance(item, dict):
            continue
        item_actions.append(
            {
                "auditId": item.get("auditId"),
                "toolCode": item.get("toolCode"),
                "policyDecision": item.get("policyDecision"),
                "action": item.get("action"),
                "reason": item.get("reason"),
            }
        )
    return AgentRuntimeToolAutoExecutionSummary(
        session_id=str(data.get("sessionId") or ""),
        run_id=str(data.get("runId") or ""),
        dry_run=bool(data.get("dryRun")),
        requested_limit=int(data.get("requestedLimit") or 0),
        effective_limit=int(data.get("effectiveLimit") or 0),
        executed_count=int(data.get("executedCount") or 0),
        failed_count=int(data.get("failedCount") or 0),
        skipped_count=int(data.get("skippedCount") or 0),
        item_actions=tuple(item_actions),
    )


def _string_tuple(value: Any) -> tuple[str, ...]:
    """把 Java 返回的字符串或数组字段归一化为 tuple。"""

    if value is None:
        return ()
    if isinstance(value, str):
        return (value,)
    return tuple(str(item) for item in value if str(item).strip())
