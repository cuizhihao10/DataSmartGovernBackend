"""面向自然语言 Agent 会话的稳定响应合同。

模型供应商、规则解析器和 LangGraph 节点都属于运行时实现细节，前端不应根据某个模型的原始文本
猜测当前处于“追问、确认还是执行”阶段。本模块把已有 ``IntentAnalysis``、参数校验结果和工具
准备度压缩成低敏、可版本化的 ``agentConversation``。未来接入真实 LLM 时，只需让模型产出同一份
结构化意图，前端会话协议和 Java 工具控制面都无需重写。
"""

from __future__ import annotations

import re
from typing import Any

from datasmart_ai_runtime.domain.contracts import AgentPlan, AgentRequest, ProviderType, ToolParameterIssueAction
from datasmart_ai_runtime.services.sync_configuration_corrections import (
    apply_explicit_sync_corrections,
)
from datasmart_ai_runtime.services.tools.tool_execution_readiness import ToolExecutionReadinessReport


_QUESTION_DEFINITIONS: dict[str, dict[str, Any]] = {
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
        "question": "请确认要同步的源表、目标 schema 和目标表。",
        "inputType": "OBJECT_MAPPING_EDITOR",
        "fieldPath": "dataSyncRequest.objectMappings",
    },
    "fieldMappings": {
        "label": "字段映射",
        "question": "请为每条对象映射确认至少一个真实源字段到真实目标字段。",
        "inputType": "FIELD_MAPPING_EDITOR",
        "fieldPath": "dataSyncRequest.objectMappings[].fieldMappings",
    },
    "mappingDefaultsConfirmation": {
        "label": "默认字段映射与 WHERE 范围确认",
        "question": (
            "我已根据两端真实元数据默认映射全部同名字段；当前 WHERE 为空，"
            "将同步对象映射范围内的全部数据。请确认接受，或直接修改字段映射和 WHERE 条件。"
        ),
        "inputType": "MAPPING_DEFAULTS_CONFIRMATION",
        "fieldPath": "dataSyncRequest.mappingDefaultsConfirmed",
        "options": [
            {"value": True, "label": "接受同名字段映射和无 WHERE"},
            {"value": False, "label": "修改字段映射或 WHERE"},
        ],
    },
    "scheduleFrequency": {
        "label": "执行频率",
        "question": "请选择定期任务的执行频率。",
        "inputType": "SCHEDULE_FREQUENCY_SELECT",
        "fieldPath": "dataSyncRequest.scheduleFrequency",
        "options": [
            {"value": "HOURLY", "label": "每小时"},
            {"value": "DAILY", "label": "每天"},
            {"value": "WEEKLY", "label": "每周"},
            {"value": "CUSTOM_CRON", "label": "自定义 Cron"},
        ],
    },
    "scheduleStartTime": {
        "label": "首次执行时间",
        "question": "请选择首次执行时间；系统将按项目时区生成调度配置。",
        "inputType": "DATETIME",
        "fieldPath": "dataSyncRequest.scheduleStartTime",
    },
    "customSqlText": {
        "label": "只读 SQL",
        "question": "请提供查询 SQL，或进入高级编辑器让 Agent 基于真实元数据生成。",
        "inputType": "SQL_EDITOR",
        "fieldPath": "dataSyncRequest.customSqlText",
        "sensitive": True,
    },
    "customSqlConfirmation": {
        "label": "确认 SQL",
        "question": "Agent 已生成只读 SQL。请核对完整 SQL，确认后才能保存任务草稿。",
        "inputType": "SQL_CONFIRMATION",
        "fieldPath": "dataSyncRequest.customSqlConfirmed",
        "sensitive": True,
        "options": [
            {"value": True, "label": "确认使用此 SQL"},
            {"value": False, "label": "返回修改 SQL"},
        ],
    },
    "targetTableResolution": {
        "label": "目标表处理方式",
        "question": "目标表不存在。请选择创建目标表，或改为项目中已有的目标表。",
        "inputType": "TARGET_TABLE_RESOLUTION",
        "fieldPath": "dataSyncRequest.targetTableResolution",
        "options": [
            {"value": "CREATE_FROM_SOURCE", "label": "按源表结构创建目标表"},
            {"value": "SELECT_EXISTING", "label": "选择其他已有目标表"},
        ],
    },
    "fieldMappingConversions": {
        "label": "字段类型转换",
        "question": "字段类型不兼容。请确认建议转换，关闭该字段同步，或返回修改映射。",
        "inputType": "FIELD_CONVERSION_EDITOR",
        "fieldPath": "dataSyncRequest.fieldMappingConversions",
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
    control_plane_feedback: Any | None = None,
    autonomous_resolution_stopped: bool = False,
) -> dict[str, Any]:
    """构建“自由文本 -> 追问 -> 确认”的前端会话快照。

    响应默认只暴露意图类别、配置是否已选择、缺失字段名等低敏事实。唯一例外是 SQL 模式中
    等待用户确认的查询文本：它只返回给当前已认证、已授权会话，不写入日志、指标或公开观察事件。
    """

    declared_missing_parameters = list(_collect_missing_parameters(plan))
    catalog_clarification_parameters = _catalog_clarification_parameters(control_plane_feedback)
    for parameter_name in catalog_clarification_parameters:
        if parameter_name not in declared_missing_parameters:
            declared_missing_parameters.append(parameter_name)
    autonomous_sync_requires_repair = _autonomous_sync_requires_repair(
        plan,
        control_plane_feedback,
        autonomous_resolution_stopped=autonomous_resolution_stopped,
    )
    repair_parameter = _repair_clarification_parameter(plan) if autonomous_sync_requires_repair else None
    if repair_parameter and "objectMappings" in declared_missing_parameters:
        declared_missing_parameters.remove("objectMappings")
    if (
        autonomous_sync_requires_repair
        and not catalog_clarification_parameters
        and (repair_parameter or "objectMappings") not in declared_missing_parameters
    ):
        # A complete natural-language request can legitimately contain every
        # business field and therefore produce no rule-level missing parameter.
        # If the model later discovers an invalid table/field after reading real
        # metadata and deliberately declines to save the draft, that is still a
        # correction turn, not an executable plan.  Surface the canonical mapping
        # editor without asking the user for internal datasource IDs again.
        declared_missing_parameters.append(repair_parameter or "objectMappings")
    autonomously_resolved = _autonomously_resolved_parameters(
        request,
        plan,
        control_plane_feedback,
        autonomous_resolution_stopped=autonomous_resolution_stopped,
    )
    currently_unresolved = tuple(
        name for name in declared_missing_parameters if name not in autonomously_resolved
    )
    for parameter_name in _mode_aware_missing_parameters(
        request,
        plan,
        control_plane_feedback=control_plane_feedback,
        currently_unresolved=currently_unresolved,
        autonomous_resolution_stopped=autonomous_resolution_stopped,
    ):
        if parameter_name not in declared_missing_parameters:
            declared_missing_parameters.append(parameter_name)
    resolved_configuration = _build_resolved_configuration(
        request,
        plan,
        control_plane_feedback,
    )
    missing_parameters = tuple(
        name for name in declared_missing_parameters if name not in autonomously_resolved
    )
    # A full plan may already contain a draft node whose first-pass validation
    # reports objectMappings as missing while the catalog/metadata branch is
    # actively resolving that same field. Treating the raw readiness count as a
    # user question creates an empty "必要业务参数" prompt even though no user
    # input is needed. Only unresolved, user-facing parameters open a
    # clarification gate; the readiness count still prevents premature execution.
    has_clarification_gate = bool(missing_parameters)
    # THROTTLED 约束的是无人值守自动调用预算，不阻止用户查看并显式确认完整 DAG。只有缺参或
    # CRITICAL 阻断会让计划失去确认资格；确认后的实际并发仍由 Java 执行策略控制。
    has_executable_plan = (
        bool(plan.tool_plans)
        and readiness.clarification_required_count == 0
        and readiness.blocked_count == 0
        and not autonomous_sync_requires_repair
    )

    autonomous_tool_names = {
        "datasource.source.catalog.search",
        "datasource.target.catalog.search",
        "datasource.source.connection.test",
        "datasource.target.connection.test",
        "datasource.source.metadata.read",
        "datasource.target.metadata.read",
    }
    autonomous_resolution_in_progress = (
        not autonomous_resolution_stopped
        and not has_clarification_gate
        and any(item.tool_name in autonomous_tool_names for item in plan.tool_plans)
        and (
            readiness.clarification_required_count > 0
            or all(item.tool_name in autonomous_tool_names for item in plan.tool_plans)
        )
    )

    if has_clarification_gate:
        phase = "WAITING_CLARIFICATION"
        next_action = "ANSWER_CLARIFICATIONS"
        assistant_message = _clarification_message(plan, missing_parameters)
        if autonomous_resolution_stopped and str(plan.response_summary or "").strip():
            assistant_message = (
                f"{str(plan.response_summary).strip()} "
                f"{assistant_message}"
            )
    elif autonomous_resolution_in_progress:
        phase = "RESOLVING_AUTONOMOUSLY"
        next_action = "CONTINUE_AUTONOMOUSLY"
        assistant_message = (
            "我正在当前项目的授权范围内定位数据源、测试连接并读取真实表字段元数据。"
            "只有唯一精确匹配才会继续；如名称存在歧义、对象不存在或配置冲突，我会只追问对应问题。"
        )
    elif has_executable_plan:
        phase = "READY_FOR_CONFIRMATION"
        next_action = "CONFIRM_AND_EXECUTE"
        task_name_change_notice = _task_name_change_notice(request, resolved_configuration)
        assistant_message = (
            task_name_change_notice
            + f"参数已经齐全，我已生成 {len(plan.tool_plans)} 个受控工具节点。"
            "请核对执行计划，确认后才会调用真实业务工具。"
        )
    else:
        phase = "NO_EXECUTABLE_PLAN"
        next_action = "REFINE_REQUEST"
        assistant_message = _no_executable_plan_message(plan)

    return {
        "schemaVersion": "1.0",
        "turnId": plan.request_id,
        "phase": phase,
        "assistantMessage": assistant_message,
        "structuredIntent": _build_structured_intent(request, plan),
        "resolvedConfiguration": resolved_configuration,
        "missingParameters": list(missing_parameters),
        "clarificationQuestions": [
            _build_question(
                name,
                control_plane_feedback,
                repair_guidance=(
                    str(plan.response_summary or "").strip()
                    if autonomous_sync_requires_repair
                    and name in {"objectMappings", "targetTableResolution", "fieldMappingConversions"}
                    else None
                ),
                configuration_preview=_configuration_preview(name, request, plan),
            )
            for name in missing_parameters
        ],
        # A read-only discovery batch can be executable from the control plane's
        # perspective while the user's business task is still incomplete.  The
        # conversation contract therefore exposes confirmation only after the
        # Agent has produced a complete governed draft, never while resolving
        # resources or waiting for clarification.
        "canExecute": (
            phase == "READY_FOR_CONFIRMATION"
            and has_executable_plan
            and control_plane_ingested
        ),
        "controlPlaneIngested": control_plane_ingested,
        "nextAction": next_action,
        "intentResolver": build_intent_resolver_summary(plan),
        "payloadPolicy": "LOW_SENSITIVE_CONVERSATION_AND_RESOLVED_CONFIGURATION",
    }


def _task_name_change_notice(
    request: AgentRequest,
    resolved_configuration: dict[str, Any],
) -> str:
    """Tell the user exactly when a follow-up message renamed the task.

    The structured configuration is authoritative, but a generic "parameters are
    complete" reply made successful natural-language corrections look ignored.
    Only emit this notice when the current message actually changed an existing
    task name; initial naming remains part of ordinary configuration review.
    """

    raw = request.variables.get("dataSyncRequest") or request.variables.get("data_sync_request")
    if not isinstance(raw, dict):
        return ""
    original_name = str(raw.get("taskName") or "").strip()
    resolved_name = str(resolved_configuration.get("taskName") or "").strip()
    latest_message = str(request.variables.get("latestUserMessage") or "").strip()
    if not original_name or not resolved_name or original_name == resolved_name or not latest_message:
        return ""
    corrected = apply_explicit_sync_corrections(dict(raw), latest_message)
    if str(corrected.get("taskName") or "").strip() != resolved_name:
        return ""
    return f"已按你的要求将任务名称从“{original_name}”修改为“{resolved_name}”。"


def _no_executable_plan_message(plan: AgentPlan) -> str:
    """按真实 Provider 调用结果解释为何没有可执行计划，避免误报“模型未启用”。"""

    invocation = dict(plan.model_invocation_summary or {})
    provider_invoked = bool(invocation.get("providerInvoked"))
    provider_succeeded = bool(invocation.get("providerSucceeded"))
    error_code = str(invocation.get("resultErrorCode") or "").strip().upper()
    if provider_invoked and not provider_succeeded:
        if error_code == "MODEL_PROVIDER_TIMEOUT":
            return (
                "真实模型本轮调用超时，系统已保留结构化规则分析，但没有生成可安全执行的工具计划。"
                "请稍后重试；若持续发生，请让管理员检查模型超时与输出预算配置。"
            )
        return (
            "真实模型本轮调用失败，系统已保留结构化规则分析，但没有生成可安全执行的工具计划。"
            "请稍后重试；若持续发生，请让管理员检查模型 Provider 健康状态。"
        )
    if provider_succeeded:
        return (
            "真实模型已完成本轮理解，但没有提出能够通过权限、参数和安全校验的工具调用。"
            "请补充更明确的对象、范围或期望结果后重试。"
        )
    if plan.selected_route is None or plan.selected_route.provider_type == ProviderType.DRY_RUN:
        return (
            "系统已完成结构化规则识别，但当前没有可用的真实模型路由，也没有生成可安全执行的工具计划。"
            "请补充更明确的业务目标，或由管理员检查模型路由配置。"
        )
    return (
        "系统已完成结构化意图识别，但本轮模型没有被实际调用，也没有生成可安全执行的工具计划。"
        "请重试；若持续发生，请让管理员检查模型路由与调用策略。"
    )


def _catalog_clarification_parameters(control_plane_feedback: Any | None) -> tuple[str, ...]:
    """Map the latest non-exact catalog facts back to user-facing fields.

    Complete natural-language requests often have no deterministic missing fields.
    If autonomous discovery later proves a datasource name ambiguous or absent, the
    correction belongs to the datasource selector, not the object-mapping editor.
    Only the latest result for each direction is authoritative so a later explicit
    user choice can replace an earlier ambiguous search.
    """

    feedback_items = tuple(getattr(control_plane_feedback, "feedback_items", ()) or ())
    tool_to_parameter = {
        "datasource.source.catalog.search": "sourceDatasourceId",
        "datasource.target.catalog.search": "targetDatasourceId",
    }
    seen_tools: set[str] = set()
    missing: list[str] = []
    for item in reversed(feedback_items):
        tool_name = str(getattr(item, "tool_name", "") or "")
        parameter_name = tool_to_parameter.get(tool_name)
        if parameter_name is None or tool_name in seen_tools:
            continue
        seen_tools.add(tool_name)
        status = getattr(getattr(item, "status", None), "value", "")
        if status != "succeeded":
            continue
        result = dict(getattr(item, "result", {}) or {})
        match_status = str(result.get("matchStatus") or "").strip().upper()
        if match_status and match_status != "EXACT":
            missing.append(parameter_name)
    return tuple(missing)


def _mode_aware_missing_parameters(
    request: AgentRequest,
    plan: AgentPlan,
    *,
    control_plane_feedback: Any | None,
    currently_unresolved: tuple[str, ...],
    autonomous_resolution_stopped: bool,
) -> tuple[str, ...]:
    """Return only the configuration gaps implied by the selected sync mode.

    The generic intent analyzer intentionally knows only datasource and mapping
    prerequisites.  Mode-specific fields are evaluated here because the model may
    have already produced a governed ``sync.task.draft.save`` call after reading
    metadata.  User-provided values remain authoritative; model-produced SQL is
    accepted only as a preview and receives its own confirmation gate.
    """

    if not _is_data_sync_plan(plan):
        return ()
    payload = _sync_payload(request)
    effective = _effective_sync_configuration(request, plan)
    sync_mode = _resolve_sync_mode(request.objective, effective)
    missing: list[str] = []
    only_discovery_tools = bool(plan.tool_plans) and all(
        item.tool_name in {
            "datasource.source.catalog.search",
            "datasource.target.catalog.search",
            "datasource.source.connection.test",
            "datasource.target.connection.test",
            "datasource.source.metadata.read",
            "datasource.target.metadata.read",
        }
        for item in plan.tool_plans
    )

    if sync_mode in {"SCHEDULED_BATCH", "SCHEDULED_FULL"}:
        schedule_config = str(effective.get("scheduleConfig") or "").strip()
        if not schedule_config:
            # Product UI asks for business time concepts rather than exposing the
            # internal JSON schedule contract.  The frontend converts these two
            # values into a timezone-bound scheduleConfig before resubmission.
            missing.extend(("scheduleFrequency", "scheduleStartTime"))

    if sync_mode == "CUSTOM_SQL_QUERY":
        user_sql = str(payload.get("customSqlText") or "").strip()
        effective_sql = str(effective.get("customSqlText") or "").strip()
        if not effective_sql:
            # Give the model one metadata-backed generation turn before asking the
            # user to write SQL manually.  If that turn stops without SQL, the
            # dedicated editor becomes the only safe continuation.
            if autonomous_resolution_stopped or not only_discovery_tools:
                missing.append("customSqlText")
        elif not user_sql and not _truthy(payload.get("customSqlConfirmed")):
            missing.append("customSqlConfirmation")

    progressive_blockers = {
        "sourceDatasourceId",
        "targetDatasourceId",
        "objectMappings",
        "customSqlText",
        "customSqlConfirmation",
        "targetTableResolution",
        "fieldMappingConversions",
    }
    if progressive_blockers.intersection(currently_unresolved) or progressive_blockers.intersection(missing):
        return tuple(missing)

    mappings = effective.get("objectMappings") or effective.get("object_mappings")
    configured_mappings = [item for item in mappings if isinstance(item, dict)] \
        if isinstance(mappings, (list, tuple)) else []
    has_confirmed_fields = bool(configured_mappings) and all(
        _has_enabled_field_mapping(item)
        for item in configured_mappings
    )
    payload_mappings = payload.get("objectMappings") or payload.get("object_mappings")
    user_confirmed_mappings = [item for item in payload_mappings if isinstance(item, dict)] \
        if isinstance(payload_mappings, (list, tuple)) else []
    user_supplied_confirmed_fields = bool(user_confirmed_mappings) and all(
        _has_enabled_field_mapping(item)
        for item in user_confirmed_mappings
    )
    inferred_mappings = _infer_same_name_object_mappings(request, control_plane_feedback)
    has_verified_defaults = bool(inferred_mappings) and all(
        _has_enabled_field_mapping(item)
        for item in inferred_mappings
    )
    if has_confirmed_fields and not user_supplied_confirmed_fields:
        if not _truthy(effective.get("mappingDefaultsConfirmed")):
            missing.append("mappingDefaultsConfirmation")
    elif not has_confirmed_fields:
        if has_verified_defaults:
            if not _truthy(effective.get("mappingDefaultsConfirmed")):
                missing.append("mappingDefaultsConfirmation")
        elif configured_mappings or autonomous_resolution_stopped:
            missing.append("fieldMappings")
    return tuple(missing)


def _has_enabled_field_mapping(mapping: dict[str, Any]) -> bool:
    fields = mapping.get("fieldMappings")
    if not isinstance(fields, (list, tuple)):
        return False
    return any(
        isinstance(field, dict)
        and field.get("syncEnabled") is not False
        and bool(str(field.get("sourceField") or "").strip())
        and bool(str(field.get("targetField") or "").strip())
        for field in fields
    )


def _repair_clarification_parameter(plan: AgentPlan) -> str | None:
    """Classify a metadata-backed repair into the smallest user decision.

    The model summary is presentation text, not execution evidence.  It is safe to
    use here only to choose a form control; the subsequent tool call is still
    validated against fresh metadata by Python and Java guards.
    """

    summary = str(plan.response_summary or "").strip().lower()
    if not summary:
        return None
    target_missing_markers = (
        "目标表不存在",
        "目标对象不存在",
        "target table does not exist",
        "target object does not exist",
        "target table is missing",
    )
    type_conflict_markers = (
        "类型不兼容",
        "字段类型冲突",
        "无法安全转换",
        "incompatible type",
        "type mismatch",
    )
    if any(marker in summary for marker in target_missing_markers):
        return "targetTableResolution"
    if any(marker in summary for marker in type_conflict_markers):
        return "fieldMappingConversions"
    return None


def _configuration_preview(
    parameter_name: str,
    request: AgentRequest,
    plan: AgentPlan,
) -> dict[str, Any] | None:
    """Build an authorized preview for configuration that requires confirmation."""

    if parameter_name != "customSqlConfirmation":
        return None
    payload = _sync_payload(request)
    arguments = _sync_draft_arguments(plan)
    sql = str(arguments.get("customSqlText") or payload.get("customSqlText") or "").strip()
    if not sql:
        return None
    return {
        "kind": "CUSTOM_SQL_QUERY",
        "customSqlText": sql,
        "generatedByAgent": not bool(str(payload.get("customSqlText") or "").strip()),
        "requiresExplicitConfirmation": True,
        "payloadPolicy": "AUTHORIZED_SESSION_ONLY_NO_LOGGING",
    }


def _sync_payload(request: AgentRequest) -> dict[str, Any]:
    raw = request.variables.get("dataSyncRequest") or request.variables.get("data_sync_request")
    payload = dict(raw) if isinstance(raw, dict) else {}
    return apply_explicit_sync_corrections(
        payload,
        str(request.variables.get("latestUserMessage") or ""),
    )


def _sync_draft_arguments(plan: AgentPlan) -> dict[str, Any]:
    for tool_plan in reversed(plan.tool_plans):
        if tool_plan.tool_name == "sync.task.draft.save":
            return dict(tool_plan.arguments or {})
    return {}


def _build_resolved_configuration(
    request: AgentRequest,
    plan: AgentPlan,
    control_plane_feedback: Any | None,
) -> dict[str, Any]:
    """Return the authoritative task fields already resolved in this turn.

    ``missingParameters`` only tells the UI what is still blocked. It cannot be
    used to reconstruct fields that were resolved by catalog and metadata tools,
    which caused a follow-up turn to blank the selected datasources. This compact
    snapshot lets every client merge confirmed values into its current draft.
    """

    payload = _sync_payload(request)
    effective = _effective_sync_configuration(request, plan)
    source_catalog = _catalog_resolution_for("sourceDatasourceId", control_plane_feedback)
    target_catalog = _catalog_resolution_for("targetDatasourceId", control_plane_feedback)

    source_id = _positive_int(effective.get("sourceDatasourceId"))
    target_id = _positive_int(effective.get("targetDatasourceId"))
    if str(source_catalog.get("matchStatus") or "").strip().upper() == "EXACT":
        source_id = _positive_int(source_catalog.get("resolvedDatasourceId")) or source_id
    if str(target_catalog.get("matchStatus") or "").strip().upper() == "EXACT":
        target_id = _positive_int(target_catalog.get("resolvedDatasourceId")) or target_id

    mappings = effective.get("objectMappings") or effective.get("object_mappings")
    mapping_source = "USER_OR_MODEL_DRAFT"
    if not isinstance(mappings, (list, tuple)) or not mappings:
        mappings = _infer_same_name_object_mappings(request, control_plane_feedback)
        mapping_source = "VERIFIED_METADATA_SAME_NAME_MATCH"
    if not isinstance(mappings, (list, tuple)) or not mappings:
        mappings = _infer_user_stated_same_name_object_mappings(request)
        mapping_source = "USER_STATED_SAME_NAME_MAPPING"

    resolved: dict[str, Any] = {
        "taskName": str(effective.get("taskName") or "Agent 创建的数据同步任务").strip(),
        "syncMode": _resolve_sync_mode(request.objective, effective),
        "writeStrategy": str(effective.get("writeStrategy") or "").strip().upper() or "INSERT",
        "autoFilledFields": [],
        "payloadPolicy": "AUTHORIZED_PROJECT_METADATA_NO_CREDENTIALS",
    }
    if resolved["syncMode"] == "CDC_STREAMING":
        resolved["writeStrategy"] = "UPDATE"
    if source_id is not None:
        resolved["sourceDatasourceId"] = source_id
        resolved["autoFilledFields"].append("sourceDatasourceId")
    if target_id is not None:
        resolved["targetDatasourceId"] = target_id
        resolved["autoFilledFields"].append("targetDatasourceId")
    source_name = str(source_catalog.get("resolvedDatasourceName") or "").strip()
    target_name = str(target_catalog.get("resolvedDatasourceName") or "").strip()
    if source_name:
        resolved["sourceDatasourceName"] = source_name
    if target_name:
        resolved["targetDatasourceName"] = target_name
    if isinstance(mappings, (list, tuple)) and mappings:
        normalized_mappings: list[dict[str, Any]] = []
        for item in mappings:
            if not isinstance(item, dict):
                continue
            normalized = dict(item)
            if resolved["syncMode"] != "CUSTOM_SQL_QUERY":
                normalized["whereCondition"] = str(normalized.get("whereCondition") or "")
            normalized_mappings.append(normalized)
        resolved["objectMappings"] = normalized_mappings
        resolved["objectMappingSource"] = mapping_source
        resolved["autoFilledFields"].append("objectMappings")
        if mapping_source == "VERIFIED_METADATA_SAME_NAME_MATCH":
            resolved["fieldMappingSource"] = "VERIFIED_METADATA_SAME_NAME_FIELDS"
        elif all(_has_enabled_field_mapping(item) for item in normalized_mappings):
            payload_mappings = payload.get("objectMappings") or payload.get("object_mappings")
            user_supplied_fields = bool(payload_mappings) and all(
                isinstance(item, dict) and _has_enabled_field_mapping(item)
                for item in payload_mappings
            ) if isinstance(payload_mappings, (list, tuple)) else False
            resolved["fieldMappingSource"] = (
                "USER_CONFIRMED_FIELDS" if user_supplied_fields
                else "MODEL_PROPOSED_METADATA_FIELDS"
            )
    resolved["mappingDefaultsConfirmed"] = _truthy(effective.get("mappingDefaultsConfirmed"))
    for key in ("scheduleConfig", "customSqlText", "customSqlConfirmed", "targetTableResolution"):
        if effective.get(key) not in (None, ""):
            resolved[key] = effective[key]
    return resolved


def _effective_sync_configuration(
    request: AgentRequest,
    plan: AgentPlan,
) -> dict[str, Any]:
    """Merge a model draft with the exact user-controlled task snapshot.

    Explicit conversational edits have already been applied by ``_sync_payload``.
    Keeping that snapshot authoritative prevents a later generic model draft from
    undoing an exact task name, mapping or WHERE expression supplied by the user.
    """

    payload = _sync_payload(request)
    draft_arguments = _sync_draft_arguments(plan)
    return {**draft_arguments, **payload}


def _infer_user_stated_same_name_object_mappings(
    request: AgentRequest,
) -> list[dict[str, Any]]:
    """Preserve an explicit same-name table request before metadata is available.

    This is a proposed configuration, not evidence that either table exists.
    The Java/Python metadata path and the normal sync precheck remain authoritative.
    Keeping the proposal in ``resolvedConfiguration`` lets the UI fetch the two
    selected datasource schemas, verify each pair and continue automatically
    instead of asking the user to re-enter table names they already supplied.
    """

    user_text = _user_controlled_conversation_text(request)
    if not re.search(r"(?:同名(?:表|目标表)?|same[\s_-]*name)", user_text, re.IGNORECASE):
        return []

    explicit_datasource_names = {
        match.group("name").strip().lower()
        for match in re.finditer(
            r"(?:源端数据源|源数据源|源库|source\s+datasource|"
            r"目标端数据源|目标数据源|目标库|target\s+datasource)"
            r"\s*(?:名称)?\s*(?:为|是|使用|采用|=|:|：)?\s*"
            r"(?P<name>[A-Za-z_][A-Za-z0-9_$.-]{1,127})",
            user_text,
            re.IGNORECASE,
        )
    }
    target_schema = _target_schema_from_user_text(user_text)
    segments: list[str] = []
    segment_patterns = (
        r"(?:MySQL|PostgreSQL|Postgres|PGSQL|Oracle|SQL\s*Server|源端|源库)"
        r"\s*(?:中|中的|内|里的)\s*(?P<objects>.+?)"
        r"(?=\s*(?:全量|定期|实时|增量|同步|迁移|传输))",
        r"(?:将|把)\s*(?P<objects>.+?)\s*(?:分别)?"
        r"(?:映射|同步|迁移|传输)\s*(?:到|至|给)",
        r"(?:from)\s+(?P<objects>.+?)\s+(?:to)\s+",
    )
    for pattern in segment_patterns:
        segments.extend(
            match.group("objects")
            for match in re.finditer(pattern, user_text, re.IGNORECASE | re.DOTALL)
        )

    excluded_identifiers = {
        "mysql",
        "postgresql",
        "postgres",
        "pgsql",
        "oracle",
        "sql",
        "server",
        "source",
        "target",
        "schema",
        "table",
        "tables",
        "full",
        "sync",
        "same",
        "name",
        "public",
    } | explicit_datasource_names
    if target_schema:
        excluded_identifiers.add(target_schema.lower())

    source_names: list[str] = []
    for segment in segments:
        for identifier in re.findall(r"(?<![A-Za-z0-9_$])([A-Za-z_][A-Za-z0-9_$]{1,127})(?![A-Za-z0-9_$])", segment):
            normalized = identifier.lower()
            if normalized in excluded_identifiers or normalized in {
                name.lower() for name in source_names
            }:
                continue
            source_names.append(identifier)
            if len(source_names) >= 50:
                break
        if len(source_names) >= 50:
            break

    return [
        {
            "objectKey": f"agent-user-same-name-{index}",
            "sourceSchemaName": None,
            "sourceObjectName": source_name,
            "targetSchemaName": target_schema,
            "targetObjectName": source_name,
            "fieldMappings": [],
        }
        for index, source_name in enumerate(source_names, start=1)
    ]


def _target_schema_from_user_text(user_text: str) -> str | None:
    patterns = (
        r"(?<![A-Za-z0-9_$])(?P<schema>[A-Za-z_][A-Za-z0-9_$]{0,127})"
        r"\s+schema(?![A-Za-z0-9_$])",
        r"\bschema\s*(?:为|是|使用|采用|=|:|：)?\s*"
        r"(?P<schema>[A-Za-z_][A-Za-z0-9_$]{0,127})",
    )
    for pattern in patterns:
        matches = tuple(re.finditer(pattern, user_text, re.IGNORECASE))
        if not matches:
            continue
        schema = matches[-1].group("schema").strip()
        if schema.lower() not in {"source", "target", "same", "name"}:
            return schema
    return None


def _user_controlled_conversation_text(request: AgentRequest) -> str:
    history = request.variables.get("conversationMessages")
    prior_user_messages = [
        str(item.get("content") or "").strip()
        for item in history[-12:]
        if isinstance(item, dict)
        and str(item.get("role") or "").strip().lower() == "user"
        and str(item.get("content") or "").strip()
    ] if isinstance(history, (list, tuple)) else []
    return "\n".join(
        value
        for value in (
            str(request.objective or "").strip(),
            *prior_user_messages,
            str(request.variables.get("latestUserMessage") or "").strip(),
        )
        if value
    )


def _infer_same_name_object_mappings(
    request: AgentRequest,
    control_plane_feedback: Any | None,
) -> list[dict[str, Any]]:
    """Infer only mappings that are uniquely grounded in both metadata snapshots.

    The deterministic fallback may be active when a model route is unavailable.
    It is still safe to help with the common "these tables -> same-name tables"
    request when every table name occurs in user-controlled text and both sides'
    real metadata contain a unique match. Ambiguity deliberately returns no
    mapping and leaves the normal clarification editor in control.
    """

    source_objects = _metadata_objects(control_plane_feedback, "datasource.source.metadata.read")
    target_objects = _metadata_objects(control_plane_feedback, "datasource.target.metadata.read")
    if not source_objects or not target_objects:
        return []

    user_text = _user_controlled_conversation_text(request)
    mappings: list[dict[str, Any]] = []
    for source in source_objects:
        source_name = str(source.get("tableName") or source.get("objectName") or "").strip()
        if not source_name or not _identifier_mentioned(user_text, source_name):
            continue
        candidates = [
            target
            for target in target_objects
            if str(target.get("tableName") or target.get("objectName") or "").strip().lower()
            == source_name.lower()
        ]
        target = _unique_target_for_user_text(candidates, user_text)
        if target is None:
            continue
        target_name = str(target.get("tableName") or target.get("objectName") or "").strip()
        field_mappings = _same_name_field_mappings(source, target)
        mappings.append({
            "objectKey": f"agent-verified-{len(mappings) + 1}",
            "sourceSchemaName": _optional_text(source.get("schemaName")),
            "sourceObjectName": source_name,
            "targetSchemaName": _optional_text(target.get("schemaName")),
            "targetObjectName": target_name,
            "whereCondition": "",
            "fieldMappings": field_mappings,
        })
    return mappings


def _metadata_objects(control_plane_feedback: Any | None, tool_name: str) -> list[dict[str, Any]]:
    objects: list[dict[str, Any]] = []
    for item in tuple(getattr(control_plane_feedback, "feedback_items", ()) or ()):
        if str(getattr(item, "tool_name", "") or "") != tool_name:
            continue
        if getattr(getattr(item, "status", None), "value", "") != "succeeded":
            continue
        result = dict(getattr(item, "result", {}) or {})
        summary = result.get("summary")
        summary = dict(summary) if isinstance(summary, dict) else {}
        raw_objects = summary.get("objects")
        if not isinstance(raw_objects, (list, tuple)):
            continue
        objects.extend(dict(value) for value in raw_objects if isinstance(value, dict))
    return objects


def _identifier_mentioned(user_text: str, identifier: str) -> bool:
    return re.search(
        rf"(?<![A-Za-z0-9_]){re.escape(identifier)}(?![A-Za-z0-9_])",
        user_text,
        re.IGNORECASE,
    ) is not None


def _unique_target_for_user_text(
    candidates: list[dict[str, Any]],
    user_text: str,
) -> dict[str, Any] | None:
    if len(candidates) == 1:
        return candidates[0]
    schema_matches = [
        candidate
        for candidate in candidates
        if (
            (schema := str(candidate.get("schemaName") or "").strip())
            and re.search(
                rf"(?:(?<![A-Za-z0-9_]){re.escape(schema)}(?![A-Za-z0-9_])\s*schema"
                rf"|schema\s*(?<![A-Za-z0-9_]){re.escape(schema)}(?![A-Za-z0-9_]))",
                user_text,
                re.IGNORECASE,
            )
        )
    ]
    return schema_matches[0] if len(schema_matches) == 1 else None


def _same_name_field_mappings(
    source: dict[str, Any],
    target: dict[str, Any],
) -> list[dict[str, Any]]:
    source_columns = source.get("columns")
    target_columns = target.get("columns")
    if not isinstance(source_columns, (list, tuple)) or not isinstance(target_columns, (list, tuple)):
        return []
    target_by_name = {
        str(column.get("columnName") or column.get("fieldName") or "").strip().lower(): column
        for column in target_columns
        if isinstance(column, dict)
    }
    mappings: list[dict[str, Any]] = []
    for raw_source in source_columns:
        if not isinstance(raw_source, dict):
            continue
        source_name = str(raw_source.get("columnName") or raw_source.get("fieldName") or "").strip()
        target_column = target_by_name.get(source_name.lower())
        if not source_name or not isinstance(target_column, dict):
            continue
        target_name = str(
            target_column.get("columnName") or target_column.get("fieldName") or ""
        ).strip()
        mappings.append({
            "sourceField": source_name,
            "sourceType": _optional_text(raw_source.get("dataTypeName")),
            "targetField": target_name,
            "targetType": _optional_text(target_column.get("dataTypeName")),
            "nullable": bool(raw_source.get("nullable", True)),
            "primaryKey": bool(raw_source.get("primaryKey", False)),
            "syncEnabled": True,
            "typeCompatible": True,
        })
    return mappings


def _positive_int(value: object) -> int | None:
    try:
        parsed = int(str(value))
    except (TypeError, ValueError):
        return None
    return parsed if parsed > 0 else None


def _optional_text(value: object) -> str | None:
    text = str(value or "").strip()
    return text or None


def _truthy(value: object) -> bool:
    if isinstance(value, bool):
        return value
    return str(value or "").strip().lower() in {"1", "true", "yes", "confirmed"}


def _autonomously_resolved_parameters(
    request: AgentRequest,
    plan: AgentPlan,
    control_plane_feedback: Any | None,
    *,
    autonomous_resolution_stopped: bool,
) -> set[str]:
    """Return missing fields currently delegated to an evidence-backed tool path.

    A rule analyzer cannot safely convert natural-language datasource names into
    database IDs.  When the real model has proposed a catalog/metadata workflow,
    those fields are no longer immediate form questions: the Java control plane
    first resolves them against the current project's authorized resources.
    Ambiguous or missing catalog results deliberately remove that delegation and
    return the field to the user clarification list.
    """

    tool_names = {item.tool_name for item in plan.tool_plans}
    feedback_items = tuple(getattr(control_plane_feedback, "feedback_items", ()) or ())
    catalog_results: dict[str, dict[str, Any]] = {}
    succeeded_tools: set[str] = set()
    for item in feedback_items:
        tool_name = str(getattr(item, "tool_name", "") or "")
        status = getattr(getattr(item, "status", None), "value", "")
        if status == "succeeded":
            succeeded_tools.add(tool_name)
        if tool_name in {
            "datasource.source.catalog.search",
            "datasource.target.catalog.search",
        }:
            catalog_results[tool_name] = dict(getattr(item, "result", {}) or {})

    resolved: set[str] = set()
    source_path = {
        "datasource.source.catalog.search",
        "datasource.source.connection.test",
        "datasource.source.metadata.read",
    }
    target_path = {
        "datasource.target.catalog.search",
        "datasource.target.connection.test",
        "datasource.target.metadata.read",
    }
    if (tool_names | succeeded_tools) & source_path:
        resolved.add("sourceDatasourceId")
    if (tool_names | succeeded_tools) & target_path:
        resolved.add("targetDatasourceId")

    for catalog_tool, parameter_name in (
        ("datasource.source.catalog.search", "sourceDatasourceId"),
        ("datasource.target.catalog.search", "targetDatasourceId"),
    ):
        result = catalog_results.get(catalog_tool)
        if result is not None and str(result.get("matchStatus") or "").upper() != "EXACT":
            resolved.discard(parameter_name)

    catalog_choice_required = any(
        str(result.get("matchStatus") or "").strip().upper()
        in {"AMBIGUOUS", "NOT_FOUND", "TYPE_CANDIDATES"}
        for result in catalog_results.values()
    )
    if catalog_choice_required:
        # Object mappings cannot be validated against real metadata until both
        # datasource identities are stable.  Ask only for the ambiguous/missing
        # datasource now; the durable resume turn will read its metadata and then
        # surface a mapping repair only if the user's original mapping is invalid.
        resolved.add("objectMappings")

    sync_payload = _sync_payload(request)
    datasource_ids_already_selected = bool(
        str(sync_payload.get("sourceDatasourceId") or "").strip()
        and str(sync_payload.get("targetDatasourceId") or "").strip()
    )
    metadata_path = {
        "datasource.source.metadata.read",
        "datasource.target.metadata.read",
    }
    draft_plan = next(
        (item for item in plan.tool_plans if item.tool_name == "sync.task.draft.save"),
        None,
    )
    if "sync.task.draft.save" in succeeded_tools:
        resolved.update({"sourceDatasourceId", "targetDatasourceId", "objectMappings"})
    elif draft_plan is not None:
        # A planned draft is not a saved draft. Fields that passed validation may
        # be supplied through durable tool references, but a MUST_CLARIFY issue
        # must remain visible. Object mappings additionally require real content.
        draft_arguments = dict(draft_plan.arguments or {})
        must_clarify = {
            issue.parameter_name
            for issue in draft_plan.parameter_validation.issues
            if issue.action == ToolParameterIssueAction.MUST_CLARIFY
        }
        if "sourceDatasourceId" not in must_clarify:
            resolved.add("sourceDatasourceId")
        if "targetDatasourceId" not in must_clarify:
            resolved.add("targetDatasourceId")
        mappings = draft_arguments.get("objectMappings")
        if (
            "objectMappings" not in must_clarify
            and isinstance(mappings, (list, tuple))
            and bool(mappings)
        ):
            resolved.add("objectMappings")
    elif not autonomous_resolution_stopped and (
        bool((tool_names | succeeded_tools) & metadata_path)
        or (
            not datasource_ids_already_selected
            and (
                bool((tool_names | succeeded_tools) & (source_path | target_path))
                or {
                    "sourceDatasourceId",
                    "targetDatasourceId",
                }.issubset(resolved)
            )
        )
    ):
        # Mapping details remain in the user's original objective while the model
        # waits for real metadata or is still resolving datasource identities.
        # Once both datasource IDs were explicitly selected, connection tests by
        # themselves cannot resolve mappings; the UI must show the real mapping
        # editor unless a metadata-read path or saved draft actually exists.
        resolved.add("objectMappings")
    if _infer_same_name_object_mappings(request, control_plane_feedback):
        resolved.add("objectMappings")
    return resolved


def _autonomous_sync_requires_repair(
    plan: AgentPlan,
    control_plane_feedback: Any | None,
    *,
    autonomous_resolution_stopped: bool,
) -> bool:
    """Detect a metadata-backed sync request that stopped before draft creation.

    Discovery tools are intentionally executable without user confirmation, but
    their success does not make the requested sync task executable.  The hard
    boundary is a governed ``sync.task.draft.save`` node.  When the model stops
    after seeing real metadata because a table, field, mapping, SQL projection or
    other configuration is invalid, the conversation must return to correction.
    """

    if not autonomous_resolution_stopped or not _is_data_sync_plan(plan):
        return False

    tool_names = {item.tool_name for item in plan.tool_plans}
    feedback_items = tuple(getattr(control_plane_feedback, "feedback_items", ()) or ())
    succeeded_tools = {
        str(getattr(item, "tool_name", "") or "")
        for item in feedback_items
        if getattr(getattr(item, "status", None), "value", "") == "succeeded"
    }
    observed_tools = tool_names | succeeded_tools
    if "sync.task.draft.save" in observed_tools:
        return False
    return bool(
        observed_tools
        & {
            "datasource.source.metadata.read",
            "datasource.target.metadata.read",
        }
    )


def _is_data_sync_plan(plan: AgentPlan) -> bool:
    """Return whether the stable intent contract classifies this as data sync."""

    return bool(
        plan.intent_analysis
        and any(item.value == "data_sync" for item in plan.intent_analysis.governance_domains)
    )


def build_intent_resolver_summary(plan: AgentPlan) -> dict[str, Any]:
    """返回真实模型参与状态，但不暴露 endpoint、API Key、prompt 或模型原始输出。

    真实 Provider 启用后，模型负责语义理解、候选工具意图和回答生成；DataSmart 的规则分析、
    ToolActionIntakeService 和 Java 控制面仍然是可执行性的最终依据。因此这里使用
    `MODEL_ASSISTED_WITH_DETERMINISTIC_FALLBACK`，而不是误导性的“全部由模型决定”。
    """

    route = plan.selected_route
    invocation = dict(plan.model_invocation_summary or {})
    actual_model_name = invocation.get("actualModelName") or invocation.get("selectedModelName")
    requested_model_name = invocation.get("requestedModelName") or (route.model_name if route else None)
    if route is None or route.provider_type == ProviderType.DRY_RUN:
        return {
            "mode": "DETERMINISTIC_FALLBACK",
            "modelProvider": "RESERVED",
            "modelName": None,
            "providerUsedForCurrentTurn": False,
            "deterministicFallbackAvailable": True,
            "contract": "PROVIDER_NEUTRAL_STRUCTURED_INTENT_V1",
        }
    provider_invoked = bool(invocation.get("providerInvoked"))
    provider_succeeded = bool(invocation.get("providerSucceeded"))
    if not provider_succeeded:
        return {
            "mode": "MODEL_FAILED_WITH_DETERMINISTIC_FALLBACK" if provider_invoked else "DETERMINISTIC_FALLBACK",
            "modelProvider": route.provider_name,
            "modelName": actual_model_name or requested_model_name,
            "requestedModelName": requested_model_name,
            "providerInvokedForCurrentTurn": provider_invoked,
            "providerUsedForCurrentTurn": False,
            "providerSucceededForCurrentTurn": False,
            "fallbackReasonCode": invocation.get("resultErrorCode") or "MODEL_NOT_INVOKED",
            "deterministicFallbackAvailable": True,
            "contract": "PROVIDER_NEUTRAL_STRUCTURED_INTENT_V1",
        }
    return {
        "mode": "MODEL_ASSISTED_WITH_DETERMINISTIC_FALLBACK",
        "modelProvider": route.provider_name,
        "modelName": actual_model_name or requested_model_name,
        "requestedModelName": requested_model_name,
        "providerInvokedForCurrentTurn": True,
        "providerUsedForCurrentTurn": True,
        "providerSucceededForCurrentTurn": True,
        "latencyMs": invocation.get("latencyMs"),
        "promptTokens": invocation.get("promptTokens"),
        "completionTokens": invocation.get("completionTokens"),
        "totalTokens": invocation.get("totalTokens"),
        "toolCallCount": invocation.get("toolCallCount", 0),
        "cacheHit": bool(invocation.get("cacheHit")),
        "fallbackUsed": bool(invocation.get("fallbackUsed")),
        "deterministicFallbackAvailable": True,
        "contract": "PROVIDER_NEUTRAL_STRUCTURED_INTENT_V1",
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


def _build_question(
    parameter_name: str,
    control_plane_feedback: Any | None = None,
    repair_guidance: str | None = None,
    configuration_preview: dict[str, Any] | None = None,
) -> dict[str, Any]:
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
    question = {
        "parameterName": parameter_name,
        **definition,
        "required": True,
        "sensitive": bool(definition.get("sensitive", False)),
    }
    catalog_result = _catalog_resolution_for(parameter_name, control_plane_feedback)
    candidates = _catalog_candidates_for(parameter_name, control_plane_feedback)
    if catalog_result:
        match_basis = str(catalog_result.get("matchBasis") or "").strip().upper()
        match_status = str(catalog_result.get("matchStatus") or "").strip().upper()
        usage_label = "源端" if parameter_name == "sourceDatasourceId" else "目标端"
        if match_basis == "CONNECTOR_TYPE_ONLY":
            datasource_type = str(catalog_result.get("requestedDatasourceType") or "").strip().upper()
            question.update({
                "reasonCode": "DATASOURCE_CONNECTOR_TYPE_REQUIRES_INSTANCE_SELECTION",
                "ambiguityType": "CONNECTOR_TYPE_ONLY",
                "requestedDatasourceType": datasource_type or None,
                "allowsNaturalLanguageCorrection": True,
                "question": (
                    f"你说明的是{usage_label}数据库类型 {datasource_type or '未识别类型'}，"
                    "还没有指定实际的数据源实例。"
                    + (
                        "请选择下列当前项目已授权的候选，或直接用自然语言补充/纠正数据源名称。"
                        if candidates
                        else "当前项目没有符合类型和用途的已授权数据源；请先创建/授权，或用自然语言更正类型或名称。"
                    )
                ),
            })
        elif match_status == "NOT_FOUND":
            keyword = str(catalog_result.get("keyword") or "").strip()
            question.update({
                "reasonCode": "DATASOURCE_INSTANCE_NOT_FOUND",
                "ambiguityType": "INSTANCE_NOT_FOUND",
                "allowsNaturalLanguageCorrection": True,
                "question": (
                    f"当前项目中没有找到名称为“{keyword or '未识别名称'}”且用途为{usage_label}的数据源。"
                    "请先创建/授权，或直接用自然语言更正名称。"
                ),
            })
    if candidates:
        question["candidates"] = candidates
        if not catalog_result or str(catalog_result.get("matchBasis") or "").upper() != "CONNECTOR_TYPE_ONLY":
            question["question"] = (
                f"{definition['question']} 当前名称存在多个候选，请明确选择其中一个，"
                "或直接用自然语言补充/纠正。"
            )
            question["allowsNaturalLanguageCorrection"] = True
    if repair_guidance:
        question["reasonCode"] = "MODEL_CONFIGURATION_REPAIR_REQUIRED"
        question["repairGuidance"] = repair_guidance
    if configuration_preview:
        question["configurationPreview"] = configuration_preview
    return question


def _catalog_candidates_for(
    parameter_name: str,
    control_plane_feedback: Any | None,
) -> list[dict[str, Any]]:
    """Return low-sensitive datasource candidates for an ambiguous clarification."""

    result = _catalog_resolution_for(parameter_name, control_plane_feedback)
    if not result:
        return []
    if str(result.get("matchStatus") or "").upper() == "EXACT":
        return []
    raw_candidates = result.get("candidates")
    if not isinstance(raw_candidates, (list, tuple)):
        return []
    return [
        {
            "datasourceId": candidate.get("datasourceId"),
            "name": candidate.get("name"),
            "type": candidate.get("type"),
            "usagePurpose": candidate.get("usagePurpose"),
        }
        for candidate in raw_candidates[:20]
        if isinstance(candidate, dict)
    ]


def _catalog_resolution_for(
    parameter_name: str,
    control_plane_feedback: Any | None,
) -> dict[str, Any]:
    """Return the latest low-sensitive catalog result for one datasource side."""

    tool_name = {
        "sourceDatasourceId": "datasource.source.catalog.search",
        "targetDatasourceId": "datasource.target.catalog.search",
    }.get(parameter_name)
    if tool_name is None:
        return {}
    for item in reversed(tuple(getattr(control_plane_feedback, "feedback_items", ()) or ())):
        if str(getattr(item, "tool_name", "") or "") != tool_name:
            continue
        result = dict(getattr(item, "result", {}) or {})
        return result
    return {}


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
        write_strategy = "UPDATE" if sync_mode == "CDC_STREAMING" else "INSERT"
    if sync_mode == "CDC_STREAMING":
        write_strategy = "UPDATE"
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
    if "data_sync" in domains and any(
        tool in candidate_tools
        for tool in (
            "datasource.source.catalog.search",
            "datasource.target.catalog.search",
            "datasource.source.connection.test",
            "datasource.target.connection.test",
            "sync.task.draft.save",
        )
    ):
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
    if configured == "REAL_TIME":
        # REAL_TIME was the early Agent-only name. The data-sync service and the
        # manual wizard persist the product contract as CDC_STREAMING.
        return "CDC_STREAMING"
    allowed = {"FULL", "SCHEDULED_BATCH", "SCHEDULED_FULL", "CUSTOM_SQL_QUERY", "CDC_STREAMING"}
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
        return "CDC_STREAMING"
    return "FULL"


def _clarification_message(plan: AgentPlan, missing_parameters: tuple[str, ...]) -> str:
    if missing_parameters == ("mappingDefaultsConfirmation",):
        return (
            "我已依据两端真实元数据映射全部同名字段，当前没有设置 WHERE，默认同步全部数据。"
            "请确认接受该默认配置；也可以直接说明要排除或修改的字段，或为某张表添加 WHERE 条件。"
        )
    labels = [_QUESTION_DEFINITIONS.get(name, {}).get("label", name) for name in missing_parameters]
    detail = "、".join(labels) if labels else "必要业务参数"
    domain = "数据同步任务" if plan.intent_analysis and any(
        item.value == "data_sync" for item in plan.intent_analysis.governance_domains
    ) else "业务目标"
    return f"我已识别出你要处理{domain}。为避免猜测数据范围或误执行，还需要补充：{detail}。"
