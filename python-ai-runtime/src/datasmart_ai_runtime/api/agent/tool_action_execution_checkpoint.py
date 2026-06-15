"""工具动作执行前图 checkpoint 查询与恢复预检 API 组装器。

本模块是 5.66 checkpoint store 的“消费侧”第一步，但它仍然是只读/预览能力：
- 查询接口用于按 checkpointId 或 threadId 找回低敏检查点；
- resume-preview 接口用于判断审批、澄清、预算恢复、outbox 确认等事实是否足够继续执行图；
- 两个接口都不重新执行工具、不写 outbox、不创建审批、不派发 worker。

为什么先做预检而不是直接做真实 resume：
- 当前 checkpoint 只保存执行前图的低敏状态，不保存原始 tool arguments 或完整 payload；
- 真正 resume 需要 Java graph/proposal/outbox/worker receipt 等控制面事实参与；
- 如果此时让 Python 直接恢复执行，容易绕过 Java 控制面，破坏商业化产品必须具备的审计和幂等边界。
"""

from __future__ import annotations

from collections.abc import Mapping
from typing import Any

from datasmart_ai_runtime.api.agent.tool_action_control_flow import default_tool_action_execution_checkpoint_store
from datasmart_ai_runtime.services.agent_gateway.a2a_task_mapping_support import count_forbidden_fields
from datasmart_ai_runtime.services.tools import (
    EmptyToolActionResumeFactProvider,
    ToolActionExecutionCheckpoint,
    ToolActionExecutionCheckpointStore,
    ToolActionResumeFactProvider,
    ToolActionResumeFactSnapshot,
    merge_resume_fact_types,
    resume_fact_types_from_mapping,
)


QUERY_SCHEMA_VERSION = "datasmart.python-ai-runtime.tool-action-execution-checkpoint-query.v1"
RESUME_SCHEMA_VERSION = "datasmart.python-ai-runtime.tool-action-execution-checkpoint-resume-preview.v1"


def build_tool_action_execution_checkpoint_query_response(
    payload: Mapping[str, Any] | None,
    *,
    checkpoint_store: ToolActionExecutionCheckpointStore | None = None,
) -> dict[str, Any]:
    """构建 checkpoint 查询响应。

    查询方式：
    - `checkpointId`：读取单个检查点，适合前端从 graph runner 响应中的 checkpoint 定位恢复状态；
    - `threadId`：读取某个 run/session/request 的最近检查点，适合断线后恢复当前暂停点；
    - `limit`：限制 thread 查询数量，避免把查询接口变成无界审计导出。

    安全边界：
    - 不支持全局扫描 checkpoint，避免调用方枚举其他租户或其他会话的运行状态；
    - scope 过滤基于调用方传入的 tenantId/projectId/actorId 做“预览级过滤”，不替代真实身份认证；
    - 默认不返回 graphRunSummary，除非调用方显式传 `includeGraphRun=true`，且即便返回也是 store 裁剪后的低敏摘要。
    """

    store = checkpoint_store or default_tool_action_execution_checkpoint_store()
    query = _query_from_payload(payload)
    if not query["checkpointId"] and not query["threadId"]:
        return _blocked_query_response("CHECKPOINT_ID_OR_THREAD_ID_REQUIRED")

    checkpoints, access_issues = _load_checkpoints(store, query)
    include_graph_run = bool(query["includeGraphRun"])
    return {
        "schemaVersion": QUERY_SCHEMA_VERSION,
        "previewOnly": True,
        "queryBoundary": "LOW_SENSITIVE_CHECKPOINT_QUERY_ONLY",
        "route": {
            "method": "POST",
            "path": "/agent/tool-actions/checkpoints/query",
            "intent": "按 checkpointId 或 threadId 查询工具动作执行前图的低敏检查点。",
        },
        "queryPolicy": {
            "globalScanAllowed": False,
            "requiresCheckpointIdOrThreadId": True,
            "includeGraphRun": include_graph_run,
            "scopeFilterApplied": _has_scope_filter(query),
            "scopeFilterMeaning": "当前阶段只做预览级租户/项目/操作者过滤，生产环境仍必须由 gateway 或服务账号认证兜底。",
        },
        "checkpointCount": len(checkpoints),
        "checkpoints": tuple(item.to_summary(include_graph_run=include_graph_run) for item in checkpoints),
        "accessIssues": tuple(access_issues),
        "productionReadiness": _checkpoint_query_production_readiness(),
    }


def build_tool_action_execution_checkpoint_resume_preview_response(
    payload: Mapping[str, Any] | None,
    *,
    checkpoint_store: ToolActionExecutionCheckpointStore | None = None,
    resume_fact_provider: ToolActionResumeFactProvider | None = None,
) -> dict[str, Any]:
    """构建 checkpoint resume 预检响应。

    resume-preview 的职责是回答：“如果现在拿这些服务端事实去恢复执行图，是否已经具备继续条件？”
    它不会执行任何真实恢复动作，原因有三点：
    - 审批事实必须来自 permission-admin 或受控审批台；
    - payloadReference、graphId、contractId 必须由 Java 控制面复核；
    - outbox 写入和 worker 派发必须由 Java/task-management 的幂等链路承接。
    """

    store = checkpoint_store or default_tool_action_execution_checkpoint_store()
    query = _query_from_payload(payload)
    checkpoint, access_issue = _load_checkpoint_for_resume(store, query)
    if checkpoint is None:
        return _resume_not_available_response(query, access_issue)

    request_resume_facts = _resume_fact_presence(payload or {})
    server_fact_snapshot = _collect_server_resume_facts(
        resume_fact_provider or EmptyToolActionResumeFactProvider(),
        checkpoint=checkpoint,
        request_payload=payload or {},
    )
    accepted_fact_types = merge_resume_fact_types(
        request_resume_facts["acceptedFactTypes"],
        server_fact_snapshot.available_fact_types,
    )
    accepted_fact_types = _without_rejected_fact_types(
        accepted_fact_types,
        server_fact_snapshot.rejected_fact_types,
    )
    required_fact_types = _required_fact_types(checkpoint.resume_requirements)
    missing_fact_types = tuple(item for item in required_fact_types if item not in accepted_fact_types)
    ready_to_resume = not missing_fact_types
    return {
        "schemaVersion": RESUME_SCHEMA_VERSION,
        "previewOnly": True,
        "resumeBoundary": "CHECKPOINT_RESUME_PREFLIGHT_ONLY",
        "route": {
            "method": "POST",
            "path": "/agent/tool-actions/checkpoints/resume-preview",
            "intent": "检查审批、澄清、预算或 outbox 确认事实是否足够恢复执行前图。",
        },
        "checkpoint": checkpoint.to_summary(include_graph_run=bool(query["includeGraphRun"])),
        "resumeFacts": {
            "factValuesEchoed": False,
            "acceptedFactTypes": accepted_fact_types,
            "requestAcceptedFactTypes": request_resume_facts["acceptedFactTypes"],
            "serverAcceptedFactTypes": server_fact_snapshot.available_fact_types,
            "serverRejectedFactTypes": server_fact_snapshot.rejected_fact_types,
            "rejectedFactTypes": server_fact_snapshot.rejected_fact_types,
            "requiredFactTypes": required_fact_types,
            "missingFactTypes": missing_fact_types,
            "ignoredSensitiveFieldCount": request_resume_facts["ignoredSensitiveFieldCount"],
            "payloadPolicy": "FACT_TYPE_PRESENCE_ONLY_VALUES_NOT_ECHOED",
        },
        "serverSideResumeFacts": server_fact_snapshot.to_summary(),
        "resumeDecision": {
            "readyToResume": ready_to_resume,
            "decision": "READY_FOR_DURABLE_RUNNER_RESUME" if ready_to_resume else "WAITING_FOR_RESUME_FACTS",
            "nextAction": (
                "CALL_DURABLE_GRAPH_RUNNER_WITH_SERVER_SIDE_FACTS"
                if ready_to_resume
                else "COMPLETE_REQUIRED_FACTS_THEN_RETRY_RESUME_PREFLIGHT"
            ),
            "meaning": "该结果只表示恢复条件预检通过或未通过，不代表已经执行工具、写 outbox 或派发 worker。",
        },
        "sideEffectBoundary": _resume_side_effect_boundary(),
        "productionReadiness": _resume_preview_production_readiness(),
    }


def _query_from_payload(payload: Mapping[str, Any] | None) -> dict[str, Any]:
    """从请求中提取 checkpoint 查询字段。

    这里只解析控制面 ID 和布尔/数字选项，不解析 arguments、prompt、SQL、sample data 或 tool output。
    如果调用方把这些字段传进来，它们只会被 `count_forbidden_fields` 计数，不会进入响应。
    """

    data = payload if isinstance(payload, Mapping) else {}
    context = data.get("context")
    merged: dict[str, Any] = dict(context) if isinstance(context, Mapping) else {}
    for key in (
        "checkpointId",
        "threadId",
        "tenantId",
        "projectId",
        "actorId",
        "includeGraphRun",
        "limit",
    ):
        if key in data and key not in merged:
            merged[key] = data[key]
    return {
        "checkpointId": _text(merged.get("checkpointId")),
        "threadId": _text(merged.get("threadId")),
        "tenantId": _text(merged.get("tenantId")),
        "projectId": _text(merged.get("projectId")),
        "actorId": _text(merged.get("actorId")),
        "includeGraphRun": _truthy(merged.get("includeGraphRun")),
        "limit": _positive_int(merged.get("limit"), default=20, maximum=100),
    }


def _load_checkpoints(
    store: ToolActionExecutionCheckpointStore,
    query: Mapping[str, Any],
) -> tuple[tuple[ToolActionExecutionCheckpoint, ...], list[dict[str, str]]]:
    """按 checkpointId 或 threadId 读取检查点，并应用预览级 scope 过滤。"""

    checkpoints: tuple[ToolActionExecutionCheckpoint, ...]
    issues: list[dict[str, str]] = []
    if query["checkpointId"]:
        checkpoint = store.get(str(query["checkpointId"]))
        checkpoints = (checkpoint,) if checkpoint is not None else ()
        if checkpoint is None:
            issues.append(_access_issue("CHECKPOINT_NOT_FOUND", "没有找到指定 checkpointId。"))
    else:
        checkpoints = store.list_by_thread(str(query["threadId"]), limit=int(query["limit"]))
        if not checkpoints:
            issues.append(_access_issue("THREAD_CHECKPOINTS_NOT_FOUND", "该 threadId 下没有可查询的检查点。"))

    visible: list[ToolActionExecutionCheckpoint] = []
    for checkpoint in checkpoints:
        allowed, issue = _scope_allows(checkpoint, query)
        if allowed:
            visible.append(checkpoint)
        elif issue is not None:
            issues.append(issue)
    return tuple(visible), issues


def _load_checkpoint_for_resume(
    store: ToolActionExecutionCheckpointStore,
    query: Mapping[str, Any],
) -> tuple[ToolActionExecutionCheckpoint | None, dict[str, str] | None]:
    """为 resume-preview 读取一个明确检查点。

    如果只传 threadId，则选择该 thread 的最新 checkpoint。真实生产 resume 也应优先使用明确 checkpointId；
    thread 最新值适合断线恢复或本地联调，但跨实例场景需要配合 sequence/etag 做并发保护。
    """

    if query["checkpointId"]:
        checkpoint = store.get(str(query["checkpointId"]))
        if checkpoint is None:
            return None, _access_issue("CHECKPOINT_NOT_FOUND", "没有找到指定 checkpointId。")
    elif query["threadId"]:
        items = store.list_by_thread(str(query["threadId"]), limit=1)
        checkpoint = items[-1] if items else None
        if checkpoint is None:
            return None, _access_issue("THREAD_CHECKPOINTS_NOT_FOUND", "该 threadId 下没有可恢复的检查点。")
    else:
        return None, _access_issue("CHECKPOINT_ID_OR_THREAD_ID_REQUIRED", "恢复预检需要 checkpointId 或 threadId。")

    allowed, issue = _scope_allows(checkpoint, query)
    return (checkpoint, None) if allowed else (None, issue)


def _scope_allows(
    checkpoint: ToolActionExecutionCheckpoint,
    query: Mapping[str, Any],
) -> tuple[bool, dict[str, str] | None]:
    """执行预览级 scope 过滤。

    这不是最终权限系统，只是避免测试、联调或未来网关误用时轻易跨租户/跨项目读取。
    生产环境仍应在 gateway 或 Java/Python 内部服务账号层加入签名、JWT、RBAC 和审计。
    """

    for field, checkpoint_value in (
        ("tenantId", checkpoint.tenant_id),
        ("projectId", checkpoint.project_id),
        ("actorId", checkpoint.actor_id),
    ):
        query_value = _text(query.get(field))
        if query_value and checkpoint_value and query_value != checkpoint_value:
            return False, _access_issue("CHECKPOINT_SCOPE_MISMATCH", f"{field} 与检查点作用域不匹配。")
    return True, None


def _resume_fact_presence(payload: Mapping[str, Any]) -> dict[str, Any]:
    """解析恢复事实是否存在，但不回显事实值。

    resume facts 可以放在顶层，也可以放在 `resumeFacts` 对象里。这里仅返回事实类型集合：
    - ID 或引用值本身不进入响应；
    - prompt/SQL/arguments 等敏感字段只计数；
    - 真正事实值应由后续 durable runner 在服务端内存或受控 store 中消费。
    """

    return {
        "acceptedFactTypes": resume_fact_types_from_mapping(payload),
        "ignoredSensitiveFieldCount": count_forbidden_fields(payload),
    }


def _collect_server_resume_facts(
    provider: ToolActionResumeFactProvider,
    *,
    checkpoint: ToolActionExecutionCheckpoint,
    request_payload: Mapping[str, Any],
) -> ToolActionResumeFactSnapshot:
    """调用服务端恢复事实源，并在异常时安全降级。

    provider 后续会连接 permission-admin、澄清事实库、Java outbox writer 或 worker receipt projection。任何一个
    外部事实源都有可能因为网络、权限、超时或依赖服务异常而失败。preview 接口不能因此泄漏内部异常详情，也不
    应误判为“事实齐备”；所以这里采用 fail-closed：
    - 返回空事实集合；
    - 只暴露异常类型作为低敏错误码；
    - 不暴露异常 message、URL、SQL、token、Header 或原始响应。
    """

    try:
        return provider.collect(checkpoint=checkpoint, request_payload=request_payload)
    except Exception as exc:  # pragma: no cover - 真实远程事实源故障时触发
        return ToolActionResumeFactSnapshot(
            source="RESUME_FACT_PROVIDER_ERROR",
            error_codes=(exc.__class__.__name__,),
        )


def _without_rejected_fact_types(
    accepted_fact_types: tuple[str, ...],
    rejected_fact_types: tuple[str, ...],
) -> tuple[str, ...]:
    """应用服务端事实源的否决结果。

    这是 5.69 引入的关键安全语义：请求 payload 里的 `approvalConfirmationId`、`clarificationFactId`
    等字段只能表达“调用方声称有这个事实”，不能天然等价于“服务端已采信这个事实”。如果 permission-admin
    这类受控 provider 已经确认审批事实不存在、过期、被拒绝或作用域不匹配，就必须把对应事实类型从最终
    acceptedFactTypes 中移除，避免恢复预检被客户端自报字段绕过。
    """

    rejected = set(rejected_fact_types)
    if not rejected:
        return accepted_fact_types
    return tuple(item for item in accepted_fact_types if item not in rejected)


def _required_fact_types(resume_requirements: tuple[str, ...]) -> tuple[str, ...]:
    """把 checkpoint 的 resumeRequirements 映射为更细的事实类型。"""

    required: list[str] = []
    mapping = {
        "GRAPH_OR_PAYLOAD_REFERENCE_OR_POLICY_EVIDENCE": (
            "GRAPH_OR_CONTRACT_EVIDENCE",
            "PAYLOAD_REFERENCE",
            "POLICY_VERSION",
        ),
        "APPROVAL_CONFIRMATION_FACT": ("APPROVAL_CONFIRMATION_FACT",),
        "CLARIFICATION_FACT": ("CLARIFICATION_FACT",),
        "OUTBOX_WRITE_CONFIRMATION": ("OUTBOX_WRITE_CONFIRMATION",),
        "CONTROL_PLANE_CLIENT_ENABLEMENT": ("CONTROL_PLANE_CLIENT_ENABLEMENT",),
        "TOOL_BUDGET_OR_WORKER_CAPACITY_RECOVERY": ("TOOL_BUDGET_OR_WORKER_CAPACITY_RECOVERY",),
    }
    for requirement in resume_requirements:
        for fact_type in mapping.get(requirement, (requirement,)):
            if fact_type not in required:
                required.append(fact_type)
    return tuple(required)


def _blocked_query_response(reason: str) -> dict[str, Any]:
    """构造缺少定位条件时的查询响应。"""

    return {
        "schemaVersion": QUERY_SCHEMA_VERSION,
        "previewOnly": True,
        "queryBoundary": "LOW_SENSITIVE_CHECKPOINT_QUERY_ONLY",
        "checkpointCount": 0,
        "checkpoints": (),
        "accessIssues": (_access_issue(reason, "查询 checkpoint 必须提供 checkpointId 或 threadId。"),),
        "queryPolicy": {
            "globalScanAllowed": False,
            "requiresCheckpointIdOrThreadId": True,
        },
        "productionReadiness": _checkpoint_query_production_readiness(),
    }


def _resume_not_available_response(
    query: Mapping[str, Any],
    access_issue: dict[str, str] | None,
) -> dict[str, Any]:
    """构造无法进行 resume 预检时的响应。"""

    return {
        "schemaVersion": RESUME_SCHEMA_VERSION,
        "previewOnly": True,
        "resumeBoundary": "CHECKPOINT_RESUME_PREFLIGHT_ONLY",
        "checkpoint": {},
        "requestedLocator": {
            "checkpointId": query.get("checkpointId"),
            "threadId": query.get("threadId"),
        },
        "resumeDecision": {
            "readyToResume": False,
            "decision": "CHECKPOINT_NOT_AVAILABLE",
            "nextAction": "QUERY_VALID_CHECKPOINT_OR_RECREATE_CONTROL_FLOW_PREVIEW",
        },
        "accessIssues": (access_issue,) if access_issue else (),
        "sideEffectBoundary": _resume_side_effect_boundary(),
        "productionReadiness": _resume_preview_production_readiness(),
    }


def _resume_side_effect_boundary() -> dict[str, Any]:
    """resume-preview 的副作用边界。"""

    return {
        "toolExecuted": False,
        "outboxWritten": False,
        "workerDispatched": False,
        "approvalCreated": False,
        "checkpointMutated": False,
        "meaning": "本接口只判断恢复事实是否齐备，不执行图、不修改 checkpoint、不触发任何真实副作用。",
    }


def _checkpoint_query_production_readiness() -> dict[str, Any]:
    """checkpoint 查询能力距离生产可用仍缺的部分。"""

    return {
        "currentStore": "IN_MEMORY_PROCESS_LOCAL",
        "missingProductionRequirements": (
            "REDIS_OR_MYSQL_DURABLE_CHECKPOINT_STORE",
            "GATEWAY_OR_SERVICE_ACCOUNT_AUTHORIZATION",
            "TENANT_QUOTA_AND_TTL",
            "AUDIT_EVENT_FOR_CHECKPOINT_QUERY",
            "PROMETHEUS_LOW_CARDINALITY_METRICS",
        ),
    }


def _resume_preview_production_readiness() -> dict[str, Any]:
    """resume 预检距离真实恢复执行仍缺的部分。"""

    return {
        "currentMode": "PREFLIGHT_ONLY_WITH_OPTIONAL_SERVER_FACT_PROVIDER",
        "missingProductionRequirements": (
            "REMOTE_PERMISSION_ADMIN_RESUME_FACT_PROVIDER",
            "REMOTE_CLARIFICATION_FACT_STORE_PROVIDER",
            "JAVA_GRAPH_PROPOSAL_REVALIDATION",
            "OUTBOX_WRITER_CONFIRMATION_PROVIDER",
            "WORKER_RECEIPT_PROJECTION_PROVIDER",
            "IDEMPOTENCY_AND_REPLAY_PROTECTION",
        ),
    }


def _has_scope_filter(query: Mapping[str, Any]) -> bool:
    """判断调用方是否提供了预览级作用域过滤字段。"""

    return bool(query.get("tenantId") or query.get("projectId") or query.get("actorId"))


def _access_issue(code: str, message: str) -> dict[str, str]:
    """构造低敏访问问题。"""

    return {"code": code, "message": message}


def _positive_int(value: Any, *, default: int, maximum: int) -> int:
    """解析正整数并限制最大值。"""

    try:
        parsed = int(value)
    except (TypeError, ValueError):
        return default
    if parsed <= 0:
        return default
    return min(parsed, maximum)


def _truthy(value: Any) -> bool:
    """解析布尔开关，兼容字符串和真实布尔值。"""

    if isinstance(value, bool):
        return value
    return str(value or "").strip().lower() in {"1", "true", "yes", "y", "on"}


def _text(value: Any) -> str | None:
    """解析非空字符串。"""

    if value is None:
        return None
    text = str(value).strip()
    return text or None
