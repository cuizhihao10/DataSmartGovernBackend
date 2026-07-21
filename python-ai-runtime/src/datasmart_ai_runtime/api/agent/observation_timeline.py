"""面向 Agent 会话界面的低敏可观测时间线投影。

Codex、Claude Code 一类 Agent 产品会展示模型、状态机、工具和命令的执行事实，但这不等于暴露模型
隐藏思维链。本模块只投影可验证事实：Provider 是否真实调用、规则意图摘要、LangGraph 状态、工具计划、
Java command proposal 以及控制面接入结果。prompt、模型原文、工具参数、SQL、凭据和内部异常均不进入时间线。
"""

from __future__ import annotations

from enum import Enum
from typing import Any, Mapping

from datasmart_ai_runtime.domain.contracts import AgentPlan


def build_agent_observation_timeline(
    plan: AgentPlan,
    *,
    control_plane_handoff: Mapping[str, Any] | None = None,
    control_plane_ingestion: Any | None = None,
) -> dict[str, Any]:
    """把本轮模型、图、工具和命令事实组装成前端可直接渲染的稳定合同。"""

    items: list[dict[str, Any]] = []
    _append_model_item(items, plan)
    _append_intent_item(items, plan)
    _append_graph_items(items, plan)
    _append_tool_items(items, plan)
    _append_command_items(items, control_plane_handoff)
    _append_control_plane_item(items, control_plane_ingestion)
    return {
        "schemaVersion": "datasmart.agent-observation-timeline.v1",
        "payloadPolicy": "LOW_SENSITIVE_EXECUTION_FACTS_NO_CHAIN_OF_THOUGHT",
        "requestId": plan.request_id,
        "itemCount": len(items),
        "items": items,
        "hiddenByDesign": (
            "chainOfThought",
            "systemPrompt",
            "rawModelOutput",
            "toolArguments",
            "sql",
            "credentials",
            "providerEndpoint",
        ),
    }


def _append_model_item(items: list[dict[str, Any]], plan: AgentPlan) -> None:
    summary = dict(plan.model_invocation_summary or {})
    invoked = bool(summary.get("providerInvoked"))
    succeeded = bool(summary.get("providerSucceeded"))
    status = "SUCCEEDED" if succeeded else ("FALLBACK" if invoked else "SKIPPED")
    if succeeded:
        message = "真实模型已完成意图辅助与工具候选决策，最终可执行性仍由平台规则和权限门禁决定。"
    elif invoked:
        message = "模型调用未成功，本轮已明确降级为确定性规则规划。"
    else:
        message = "本轮未调用真实模型，使用确定性规则规划。"
    items.append(
        _item(
            "model-invocation",
            "MODEL",
            "invoke_model_intent",
            status,
            "模型调用",
            message,
            {
                "provider": summary.get("selectedProviderName"),
                "model": summary.get("selectedModelName"),
                "latencyMs": summary.get("latencyMs"),
                "promptTokens": summary.get("promptTokens"),
                "completionTokens": summary.get("completionTokens"),
                "totalTokens": summary.get("totalTokens"),
                "toolCallCount": summary.get("toolCallCount", 0),
                "proposedToolNames": summary.get("proposedToolNames") or (),
                "attemptCount": summary.get("attemptCount", 0),
                "cacheHit": bool(summary.get("cacheHit")),
                "fallbackUsed": bool(summary.get("fallbackUsed")),
                "errorCode": summary.get("resultErrorCode"),
            },
        )
    )


def _append_intent_item(items: list[dict[str, Any]], plan: AgentPlan) -> None:
    analysis = plan.intent_analysis
    if analysis is None:
        return
    items.append(
        _item(
            "structured-intent",
            "DECISION",
            "analyze_intent",
            "SUCCEEDED",
            "结构化意图与安全基线",
            analysis.summary,
            {
                "ruleConfidence": analysis.confidence,
                "domains": tuple(domain.value for domain in analysis.governance_domains),
                "candidateTools": analysis.candidate_tools,
                "riskTags": tuple(tag.value for tag in analysis.risk_tags),
            },
        )
    )


def _append_graph_items(items: list[dict[str, Any]], plan: AgentPlan) -> None:
    for index, stage in enumerate(plan.state_trace):
        items.append(
            _item(
                f"graph-{index + 1}",
                "GRAPH",
                stage,
                "SUCCEEDED",
                f"LangGraph 节点 {index + 1}",
                _stage_message(stage),
                {"sequence": index + 1},
            )
        )


def _append_tool_items(items: list[dict[str, Any]], plan: AgentPlan) -> None:
    for index, tool in enumerate(plan.tool_plans):
        items.append(
            _item(
                f"tool-{index + 1}",
                "TOOL",
                "plan_tools",
                "PLANNED",
                tool.tool_name,
                tool.reason or "工具已进入受控执行计划。",
                {
                    "sequence": index + 1,
                    "riskLevel": _enum_value(tool.risk_level),
                    "executionMode": _enum_value(tool.execution_mode),
                    "requiresHumanApproval": tool.requires_human_approval,
                    "parameterValidationPassed": tool.parameter_validation.can_execute,
                },
            )
        )


def _append_command_items(items: list[dict[str, Any]], handoff: Mapping[str, Any] | None) -> None:
    if not isinstance(handoff, Mapping):
        return
    templates = handoff.get("templateSummaries")
    if not isinstance(templates, (list, tuple)):
        return
    for index, template in enumerate(templates):
        if not isinstance(template, Mapping):
            continue
        candidate = bool(template.get("outboxPreflightCandidate"))
        items.append(
            _item(
                f"command-{index + 1}",
                "COMMAND",
                "prepare_control_plane_command",
                "READY" if candidate else "WAITING",
                str(template.get("toolName") or f"控制面命令 {index + 1}"),
                "已生成 Java 控制面 command proposal 导航。" if candidate else "命令仍在等待执行证据或门禁。",
                {
                    "templateId": template.get("templateId"),
                    "decision": template.get("decision"),
                    "proposalState": template.get("proposalStateHint"),
                    "nextAction": template.get("nextAction"),
                    "missingEvidenceCodes": template.get("missingBeforeJavaProposal") or (),
                },
            )
        )


def _append_control_plane_item(items: list[dict[str, Any]], ingestion: Any | None) -> None:
    if ingestion is None:
        return
    summary = ingestion.to_summary() if callable(getattr(ingestion, "to_summary", None)) else ingestion
    if not isinstance(summary, Mapping):
        return
    items.append(
        _item(
            "control-plane-ingestion",
            "COMMAND",
            "ingest_java_control_plane",
            "SUCCEEDED" if summary.get("ingested", True) else "FAILED",
            "Java Agent 控制面接入",
            "工具计划已写入 Java Agent Runtime，可在确认后执行并回传审计。",
            {
                "sessionId": summary.get("sessionId"),
                "runId": summary.get("runId"),
                "toolAuditCount": summary.get("toolAuditCount"),
            },
        )
    )


def _item(
    item_id: str,
    category: str,
    stage: str,
    status: str,
    title: str,
    summary: str,
    details: Mapping[str, Any],
) -> dict[str, Any]:
    return {
        "id": item_id,
        "category": category,
        "stage": stage,
        "status": status,
        "title": title,
        "summary": summary,
        "details": {key: value for key, value in details.items() if value is not None},
    }


def _enum_value(value: Any) -> str:
    return str(value.value if isinstance(value, Enum) else value)


def _stage_message(stage: str) -> str:
    messages = {
        "receive_goal": "已接收用户目标并创建本轮运行上下文。",
        "select_model_route": "已进入模型路由选择节点。",
        "build_context": "已构建受权限约束的低敏上下文。",
        "route_model_gateway": "已完成模型 Provider、预算与 fallback 路由决策。",
        "analyze_intent": "已生成可校验的结构化业务意图。",
        "select_skills": "已按业务域和权限选择可用 Skill。",
        "invoke_model_intent": "已完成模型意图辅助节点或明确执行规则降级。",
        "govern_model_tool_calls": "已校验模型提出的工具调用并应用权限、参数和风险门禁。",
        "plan_tools": "已合并模型建议与确定性规则，生成受控工具 DAG。",
        "plan_memory": "已生成本轮记忆读取计划。",
        "retrieve_memory": "已完成受控记忆检索，不向模型暴露未授权内容。",
        "clarify_missing_parameters": "缺少执行参数，状态机已暂停并等待用户补充。",
        "prepare_draft_with_missing_parameters": "已生成草案，但仍有参数需要补齐。",
        "wait_human_approval": "高风险动作已暂停，等待人工确认。",
        "ready_for_control_plane_execution": "计划已通过执行前门禁，可交接 Java 控制面。",
    }
    return messages.get(stage, f"状态机已完成节点：{stage}")
