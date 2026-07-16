"""面向自然语言 Agent 会话的稳定响应合同。

模型供应商、规则解析器和 LangGraph 节点都属于运行时实现细节，前端不应根据某个模型的原始文本
猜测当前处于“追问、确认还是执行”阶段。本模块把已有 ``IntentAnalysis``、参数校验结果和工具
准备度压缩成低敏、可版本化的 ``agentConversation``。未来接入真实 LLM 时，只需让模型产出同一份
结构化意图，前端会话协议和 Java 工具控制面都无需重写。
"""

from __future__ import annotations

from typing import Any

from datasmart_ai_runtime.domain.contracts import AgentPlan, AgentRequest, ToolParameterIssueAction
from datasmart_ai_runtime.services.tools.tool_execution_readiness import ToolExecutionReadinessReport


_QUESTION_DEFINITIONS: dict[str, dict[str, str]] = {
    "sourceDatasourceId": {
        "label": "源端数据源",
        "question": "请选择本项目中已授权、用途为源端的数据源。",
        "inputType": "SOURCE_DATASOURCE_SELECT",
        "fieldPath": "dataSyncRequest.sourceDatasourceId",
    },
    "targetDatasourceId": {
        "label": "目标端数据源",
        "question": "请选择本项目中已授权、用途为目标端的数据源。",
        "inputType": "TARGET_DATASOURCE_SELECT",
        "fieldPath": "dataSyncRequest.targetDatasourceId",
    },
    "objectMappings": {
        "label": "对象映射",
        "question": "请确认要同步的源表、目标 schema、目标表以及可选的 WHERE 条件。",
        "inputType": "OBJECT_MAPPING_EDITOR",
        "fieldPath": "dataSyncRequest.objectMappings",
    },
    "datasourceId": {
        "label": "数据源",
        "question": "请选择本项目中允许 Agent 使用的数据源。",
        "inputType": "DATASOURCE_SELECT",
        "fieldPath": "datasourceId",
    },
    "remediationScope": {
        "label": "治理范围",
        "question": "请指定需要治理的数据对象或异常范围。",
        "inputType": "TEXT",
        "fieldPath": "remediationScope",
    },
    "workspaceFilePath": {
        "label": "文件路径",
        "question": "请提供当前 Agent 工作区内的相对文件路径。",
        "inputType": "TEXT",
        "fieldPath": "workspaceFilePath",
    },
    "workspaceFileContentRef": {
        "label": "文件内容引用",
        "question": "请提供已安全保存的文件内容引用，不要直接提交密钥。",
        "inputType": "TEXT",
        "fieldPath": "workspaceFileContentRef",
    },
    "exportFormat": {
        "label": "导出格式",
        "question": "请选择导出文件格式。",
        "inputType": "EXPORT_FORMAT_SELECT",
        "fieldPath": "exportFormat",
    },
}


def build_agent_conversation_response(
    request: AgentRequest,
    plan: AgentPlan,
    readiness: ToolExecutionReadinessReport,
    *,
    control_plane_ingested: bool,
) -> dict[str, Any]:
    """构建“自由文本 -> 追问 -> 确认”的前端会话快照。

    响应只暴露意图类别、配置是否已选择、缺失字段名等低敏事实，不回显 SQL、WHERE、数据源凭据
    或完整工具参数。真实参数继续保留在受权限保护的计划和 Java 控制面中。
    """

    missing_parameters = _collect_missing_parameters(plan)
    has_clarification_gate = bool(missing_parameters) or readiness.clarification_required_count > 0
    # THROTTLED 约束的是无人值守自动调用预算，不阻止用户查看并显式确认完整 DAG。只有缺参或
    # CRITICAL 阻断会让计划失去确认资格；确认后的实际并发仍由 Java 执行策略控制。
    has_executable_plan = (
        bool(plan.tool_plans)
        and readiness.clarification_required_count == 0
        and readiness.blocked_count == 0
    )

    if has_clarification_gate:
        phase = "WAITING_CLARIFICATION"
        next_action = "ANSWER_CLARIFICATIONS"
        assistant_message = _clarification_message(plan, missing_parameters)
    elif has_executable_plan:
        phase = "READY_FOR_CONFIRMATION"
        next_action = "CONFIRM_AND_EXECUTE"
        assistant_message = (
            f"参数已经齐全，我已生成 {len(plan.tool_plans)} 个受控工具节点。"
            "请核对执行计划，确认后才会调用真实业务工具。"
        )
    else:
        phase = "NO_EXECUTABLE_PLAN"
        next_action = "REFINE_REQUEST"
        assistant_message = (
            "我已经完成结构化意图识别，但当前没有生成可安全执行的工具计划。"
            "请补充更明确的业务目标；开放式推理模型接口已预留但默认未启用。"
        )

    return {
        "schemaVersion": "1.0",
        "turnId": plan.request_id,
        "phase": phase,
        "assistantMessage": assistant_message,
        "structuredIntent": _build_structured_intent(request, plan),
        "missingParameters": list(missing_parameters),
        "clarificationQuestions": [_build_question(name) for name in missing_parameters],
        "canExecute": has_executable_plan and control_plane_ingested,
        "controlPlaneIngested": control_plane_ingested,
        "nextAction": next_action,
        "intentResolver": {
            "mode": "DETERMINISTIC_FALLBACK",
            "modelProvider": "RESERVED",
            "providerRequiredForCurrentTurn": False,
            "contract": "PROVIDER_NEUTRAL_STRUCTURED_INTENT_V1",
        },
        "payloadPolicy": "LOW_SENSITIVE_CONVERSATION_METADATA_ONLY",
    }


def _collect_missing_parameters(plan: AgentPlan) -> tuple[str, ...]:
    """合并意图层和工具参数层的必答字段，并保持稳定顺序。"""

    names: list[str] = []
    if plan.intent_analysis is not None:
        names.extend(plan.intent_analysis.missing_parameters)
    for tool_plan in plan.tool_plans:
        for issue in tool_plan.parameter_validation.issues:
            if issue.action == ToolParameterIssueAction.MUST_CLARIFY and issue.parameter_name not in names:
                names.append(issue.parameter_name)
    return tuple(dict.fromkeys(name for name in names if name))


def _build_question(parameter_name: str) -> dict[str, Any]:
    """把内部参数名转换成前端可以直接渲染的追问定义。"""

    definition = _QUESTION_DEFINITIONS.get(
        parameter_name,
        {
            "label": parameter_name,
            "question": f"请补充 {parameter_name}。",
            "inputType": "TEXT",
            "fieldPath": parameter_name,
        },
    )
    return {
        "parameterName": parameter_name,
        **definition,
        "required": True,
        "sensitive": False,
    }


def _build_structured_intent(request: AgentRequest, plan: AgentPlan) -> dict[str, Any]:
    """返回可解释的结构化意图，不复制工具参数正文。"""

    analysis = plan.intent_analysis
    domains = [domain.value for domain in analysis.governance_domains] if analysis else []
    risk_tags = [tag.value for tag in analysis.risk_tags] if analysis else []
    candidate_tools = list(analysis.candidate_tools) if analysis else []
    sync_payload = request.variables.get("dataSyncRequest") or request.variables.get("data_sync_request")
    sync_payload = sync_payload if isinstance(sync_payload, dict) else {}
    sync_mode = _resolve_sync_mode(request.objective, sync_payload)
    write_strategy = str(sync_payload.get("writeStrategy") or "").strip().upper()
    if write_strategy not in {"INSERT", "UPDATE"}:
        write_strategy = "UPDATE" if sync_mode == "REAL_TIME" else "INSERT"
    mappings = sync_payload.get("objectMappings") or sync_payload.get("object_mappings")

    return {
        "intentType": _resolve_intent_type(domains, candidate_tools),
        "domains": domains,
        "candidateTools": candidate_tools,
        "riskTags": risk_tags,
        "confidence": analysis.confidence if analysis else 0.0,
        "summary": analysis.summary if analysis else plan.model_intent_summary,
        "syncMode": sync_mode if "data_sync" in domains else None,
        "writeStrategy": write_strategy if "data_sync" in domains else None,
        "sourceDatasourceSelected": bool(sync_payload.get("sourceDatasourceId")),
        "targetDatasourceSelected": bool(sync_payload.get("targetDatasourceId")),
        "objectMappingCount": len(mappings) if isinstance(mappings, list) else 0,
    }


def _resolve_intent_type(domains: list[str], candidate_tools: list[str]) -> str:
    if "data_sync" in domains and "task.create.draft" in candidate_tools:
        return "CREATE_DATA_SYNC_TASK"
    if "data_quality" in domains:
        return "DATA_QUALITY_ASSISTANCE"
    if "datasource" in domains:
        return "DATASOURCE_ASSISTANCE"
    if "permission_admin" in domains:
        return "PERMISSION_ASSISTANCE"
    if "knowledge_qa" in domains:
        return "KNOWLEDGE_QUESTION"
    return "GENERAL_GOVERNANCE_REQUEST"


def _resolve_sync_mode(objective: str, sync_payload: dict[str, Any]) -> str:
    """按产品已收敛的五种同步模式解析自由文本，结构化值优先。"""

    configured = str(sync_payload.get("syncMode") or sync_payload.get("sync_mode") or "").strip().upper()
    allowed = {"FULL", "SCHEDULED_BATCH", "SCHEDULED_FULL", "CUSTOM_SQL_QUERY", "REAL_TIME"}
    if configured in allowed:
        return configured

    normalized = objective.lower()
    if any(keyword in normalized for keyword in ("定期批量", "定时批量", "scheduled batch")):
        return "SCHEDULED_BATCH"
    if any(keyword in normalized for keyword in ("定期全量", "定时全量", "scheduled full")):
        return "SCHEDULED_FULL"
    if any(keyword in normalized for keyword in ("sql 语句", "sql语句", "自定义 sql", "custom sql")):
        return "CUSTOM_SQL_QUERY"
    if any(keyword in normalized for keyword in ("实时", "cdc", "real-time", "real time")):
        return "REAL_TIME"
    return "FULL"


def _clarification_message(plan: AgentPlan, missing_parameters: tuple[str, ...]) -> str:
    labels = [_QUESTION_DEFINITIONS.get(name, {}).get("label", name) for name in missing_parameters]
    detail = "、".join(labels) if labels else "必要业务参数"
    domain = "数据同步任务" if plan.intent_analysis and any(
        item.value == "data_sync" for item in plan.intent_analysis.governance_domains
    ) else "业务目标"
    return f"我已识别出你要处理{domain}。为避免猜测数据范围或误执行，还需要补充：{detail}。"
