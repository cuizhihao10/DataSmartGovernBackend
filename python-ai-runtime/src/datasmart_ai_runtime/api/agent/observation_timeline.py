"""面向 Agent 会话界面的低敏工作过程投影。

Codex、Claude Code 一类 Agent 产品展示的是“公开决策摘要 + Skill/工具/命令事实 + 等待用户操作”，
而不是把十几个内部状态节点和隐藏思维链原样倾倒给用户。本模块将内部运行事实聚合为产品化过程流：
模型目标理解、受控策略、Skill 加载、LangGraph 编排摘要、工具计划、权限确认、缺参追问和控制面执行。
Provider 内部 reasoning token、系统提示词、原始参数、SQL、凭据和异常正文始终不进入该合同。
"""

from __future__ import annotations

from enum import Enum
import re
from typing import Any, Mapping

from datasmart_ai_runtime.domain.contracts import AgentPlan


def build_agent_observation_timeline(
    plan: AgentPlan,
    *,
    conversation: Mapping[str, Any] | None = None,
    control_plane_handoff: Mapping[str, Any] | None = None,
    control_plane_ingestion: Any | None = None,
) -> dict[str, Any]:
    """把本轮公开决策和执行事实组装成前端可直接渲染的稳定合同。"""

    items: list[dict[str, Any]] = []
    _append_model_item(items, plan)
    _append_intent_item(items, plan)
    _append_skill_items(items, plan)
    _append_orchestration_item(items, plan, conversation)
    _append_tool_items(items, plan)
    _append_approval_item(items, plan, conversation)
    _append_user_action_items(items, conversation)
    _append_command_items(items, control_plane_handoff)
    _append_control_plane_item(items, control_plane_ingestion)
    return {
        "schemaVersion": "datasmart.agent-work-process.v2",
        "payloadPolicy": "PUBLIC_DECISION_SUMMARIES_AND_LOW_SENSITIVE_EXECUTION_FACTS",
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
    public_summary = _public_model_summary(plan.model_decision_summary)
    if succeeded and public_summary:
        message = public_summary
    elif succeeded:
        message = "真实模型已完成目标理解和工具候选决策，最终可执行性仍由平台规则和权限门禁决定。"
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
            "理解目标并形成决策摘要",
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
            "形成受控执行策略",
            analysis.reasoning or analysis.summary,
            {
                "strategySummary": analysis.summary,
                "ruleConfidence": analysis.confidence,
                "domains": tuple(domain.value for domain in analysis.governance_domains),
                "candidateTools": analysis.candidate_tools,
                "riskTags": tuple(tag.value for tag in analysis.risk_tags),
                "missingInformation": analysis.missing_parameters,
            },
        )
    )


def _append_skill_items(items: list[dict[str, Any]], plan: AgentPlan) -> None:
    """展示真正被加载或被权限门禁拒绝的 Skill，而不是只显示 select_skills 内部节点。"""

    for index, skill in enumerate(plan.skill_plan.selected_skills):
        items.append(
            _item(
                f"skill-loaded-{index + 1}",
                "SKILL",
                "select_skills",
                "LOADED",
                f"加载 Skill：{skill.display_name}",
                skill.reason or "该能力包与当前目标匹配，并已通过本轮权限和风险准入。",
                {
                    "skillCode": skill.skill_code,
                    "domain": skill.domain.value,
                    "matchScore": skill.score,
                    "requiredTools": skill.required_tools,
                    "requiredPermissions": skill.required_permissions,
                    "memoryDependencies": tuple(item.value for item in skill.memory_dependencies),
                    "riskLevel": skill.risk_level,
                    "approvalPolicy": skill.approval_policy,
                    "admissionStatus": skill.admission_status,
                },
            )
        )
    for index, skill in enumerate(plan.skill_plan.rejected_skills):
        items.append(
            _item(
                f"skill-blocked-{index + 1}",
                "PERMISSION",
                "select_skills",
                "BLOCKED",
                f"Skill 未获准：{skill.display_name}",
                "；".join(skill.admission_reasons) or "当前用户、项目范围或风险策略不允许加载该能力包。",
                {
                    "skillCode": skill.skill_code,
                    "requiredPermissions": skill.required_permissions,
                    "riskLevel": skill.risk_level,
                    "approvalPolicy": skill.approval_policy,
                    "admissionStatus": skill.admission_status,
                },
            )
        )


def _append_orchestration_item(
    items: list[dict[str, Any]],
    plan: AgentPlan,
    conversation: Mapping[str, Any] | None,
) -> None:
    """将内部图节点聚合为一条可恢复编排摘要，避免把实现细节伪装成用户过程。"""

    stages = tuple(stage for stage in plan.state_trace if not stage.startswith("workflow:"))
    phase = str((conversation or {}).get("phase") or "")
    if phase == "WAITING_CLARIFICATION":
        status = "PAUSED"
        summary = "LangGraph 已完成目标理解、上下文、Skill 与工具规划，当前安全暂停，等待你补充执行信息。"
    elif phase == "READY_FOR_CONFIRMATION":
        status = "READY"
        summary = "LangGraph 已生成可恢复执行计划，当前等待你确认后交给 Java 控制面执行。"
    else:
        status = "SUCCEEDED"
        summary = "LangGraph 已完成本轮受控编排，并保存可用于后续恢复和审计的状态。"
    items.append(
        _item(
            "orchestration-summary",
            "ORCHESTRATION",
            "langgraph_orchestration",
            status,
            "编排任务并选择下一步",
            summary,
            {
                "completedStepCount": len(stages),
                "completedSteps": tuple(_stage_label(stage) for stage in stages),
                "currentPhase": phase or "PLANNED",
                "nextAction": (conversation or {}).get("nextAction"),
                "resumeSupported": True,
            },
        )
    )


def _append_tool_items(items: list[dict[str, Any]], plan: AgentPlan) -> None:
    for index, tool in enumerate(plan.tool_plans):
        missing_fields = tuple(
            issue.parameter_name
            for issue in tool.parameter_validation.issues
            if issue.parameter_name
        )
        if not tool.parameter_validation.can_execute:
            status = "WAITING_INPUT"
        elif tool.requires_human_approval:
            status = "WAITING_APPROVAL"
        else:
            status = "PLANNED"
        items.append(
            _item(
                f"tool-{index + 1}",
                "TOOL",
                "plan_tools",
                status,
                f"准备工具：{tool.tool_name}",
                tool.reason or "工具已进入受控执行计划。",
                {
                    "riskLevel": _enum_value(tool.risk_level),
                    "executionMode": _enum_value(tool.execution_mode),
                    "requiresHumanApproval": tool.requires_human_approval,
                    "parameterValidationPassed": tool.parameter_validation.can_execute,
                    "missingFields": missing_fields,
                },
            )
        )


def _append_approval_item(
    items: list[dict[str, Any]],
    plan: AgentPlan,
    conversation: Mapping[str, Any] | None,
) -> None:
    if not plan.requires_human_approval:
        return
    phase = str((conversation or {}).get("phase") or "")
    waiting_for_input = phase == "WAITING_CLARIFICATION"
    items.append(
        _item(
            "human-confirmation",
            "PERMISSION",
            "wait_human_approval",
            "WAITING_INPUT" if waiting_for_input else "WAITING_APPROVAL",
            "需要你确认状态变更操作",
            (
                "先补齐执行信息；计划生成后，系统会再次向你展示真实工具与影响范围，只有确认后才会执行。"
                if waiting_for_input
                else "计划包含创建任务或运行任务等状态变更操作，请核对工具与影响范围后确认执行。"
            ),
            {
                "requiredAction": "ANSWER_CLARIFICATIONS" if waiting_for_input else "CONFIRM_AND_EXECUTE",
                "protectedToolCount": sum(1 for tool in plan.tool_plans if tool.requires_human_approval),
                "automaticExecutionBlocked": True,
            },
        )
    )


def _append_user_action_items(items: list[dict[str, Any]], conversation: Mapping[str, Any] | None) -> None:
    questions = (conversation or {}).get("clarificationQuestions")
    if not isinstance(questions, (list, tuple)):
        return
    for index, question in enumerate(questions):
        if not isinstance(question, Mapping):
            continue
        label = str(question.get("label") or "执行信息")
        items.append(
            _item(
                f"user-input-{index + 1}",
                "USER_ACTION",
                "clarify_missing_parameters",
                "WAITING_INPUT",
                f"需要你补充：{label}",
                str(question.get("question") or f"请补充{label}。"),
                {
                    "inputType": question.get("inputType"),
                    "required": bool(question.get("required", True)),
                    "sensitive": bool(question.get("sensitive", False)),
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
        # 尚未形成可发送命令时，用户真正需要处理的是前面的缺参或确认项；重复展示 WAITING 模板只会制造噪声。
        if not candidate:
            continue
        items.append(
            _item(
                f"command-{index + 1}",
                "COMMAND",
                "prepare_control_plane_command",
                "READY",
                str(template.get("toolName") or f"控制面命令 {index + 1}"),
                "已生成 Java 控制面 command proposal，等待受控发送。",
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


def _stage_label(stage: str) -> str:
    labels = {
        "receive_goal": "接收目标",
        "select_model_route": "选择模型",
        "build_context": "构建项目上下文",
        "route_model_gateway": "模型路由与预算治理",
        "analyze_intent": "形成结构化意图",
        "select_skills": "加载 Skill",
        "invoke_model_intent": "模型辅助决策",
        "govern_model_tool_calls": "校验模型工具建议",
        "plan_tools": "生成工具计划",
        "plan_memory": "规划记忆读取",
        "retrieve_memory": "检索受控记忆",
        "clarify_missing_parameters": "等待补充信息",
        "prepare_draft_with_missing_parameters": "准备任务草案",
        "wait_human_approval": "等待用户确认",
        "ready_for_control_plane_execution": "准备控制面执行",
    }
    return labels.get(stage, stage)


def _public_model_summary(value: str) -> str:
    """压缩模型专门生成的公开摘要，并兜底遮蔽意外出现的密钥型片段。"""

    text = " ".join(str(value or "").split())
    if not text:
        return ""
    text = re.sub(
        r"(?i)\b(api[_ -]?key|access[_ -]?token|refresh[_ -]?token|password|secret)\b\s*[:=]\s*\S+",
        r"\1=[已隐藏]",
        text,
    )
    return text[:900] + ("…" if len(text) > 900 else "")
