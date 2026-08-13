"""Specialist ToolPlan bridge 的严格边界回归测试。

这些测试不调用真实数据库，也不替代 Java agent-runtime 的集成测试；它们专门验证
Python 桥接层是否把“不可信专业 Agent 建议”正确收敛成“可交给 Java 的 ToolPlan”。
测试中的 metadata 反馈都带有审计 ID、Run ID、数据源 ID 和两端 objects，模拟真实
控制面成功事实，而不是把模型输出直接当成数据库元数据。
"""

from __future__ import annotations

import inspect
from dataclasses import replace
from typing import Any, Mapping

import pytest

from datasmart_ai_runtime.config import default_tool_registry
from datasmart_ai_runtime.domain.contracts import (
    AgentPlan,
    AgentRequest,
    ToolExecutionMode,
    ToolPlan,
    ToolRiskLevel,
)
from datasmart_ai_runtime.domain.intent import GovernanceDomain, IntentAnalysis
from datasmart_ai_runtime.services.agent_control_plane_feedback import (
    AgentControlPlaneFeedbackItem,
    AgentControlPlaneFeedbackSnapshot,
)
from datasmart_ai_runtime.services.agent_follow_up_tool_planner import AgentFollowUpToolPlanner
from datasmart_ai_runtime.services.agent_follow_up_tool_planner import AgentFollowUpToolPlanningResult
from datasmart_ai_runtime.services.agent_gateway.session_models import AgentSessionRole
from datasmart_ai_runtime.services.multi_agent.specialist_contracts import (
    SpecialistTurnResult,
    SpecialistTurnStatus,
)
from datasmart_ai_runtime.services.multi_agent.specialist_toolplan_bridge import (
    RECOVERY_ACTION_TOOL_MAP,
    RECOVERY_MINIMAL_READ_ONLY_DELEGATION_TOOL_NAMES,
    RECOVERY_TOOL_REQUIRED_ACTION,
    SpecialistBridgeStatus,
    SpecialistToolPlanBridge,
)
from datasmart_ai_runtime.services.model_gateway.model_tool_result_feedback import (
    ToolExecutionFeedbackStatus,
)
from datasmart_ai_runtime.services.tool_planner import ToolPlanner


def _request(
    *,
    actor_id: str = "user-1001",
    application_id: str = "datasmart",
    session_id: str = "session-bridge-1",
    run_id: str = "run-bridge-1",
) -> AgentRequest:
    """构造带完整可信作用域的请求，避免测试意外覆盖生产边界。"""

    return AgentRequest(
        tenant_id="tenant-10",
        project_id="project-101",
        actor_id=actor_id,
        objective="创建一个全量同步任务并提交到控制面。",
        request_id="request-bridge-1",
        variables={
            # applicationId 只能通过 gateway 重建的 trustedControlPlane 进入桥接，
            # 不能从普通用户可编辑的顶层变量读取。
            "trustedControlPlane": {"applicationId": application_id},
            "agentRuntimeSessionId": session_id,
            "agentRuntimeRunId": run_id,
        },
    )


def _plan(request: AgentRequest, *tool_plans: ToolPlan) -> AgentPlan:
    """构造带同步候选工具可见性的父计划。"""

    candidate_tools = (
        "sync.task.draft.save",
        "sync.task.precheck",
        "sync.task.publish",
        "sync.task.run",
        "sync.execution.status",
        "sync.execution.diagnose",
        "sync.execution.rag.lookup",
        "sync.execution.failed-objects.retry",
        "sync.dirty-record.quarantine.preview",
        "sync.dirty-record.quarantine.apply",
        "sync.dirty-record.replay",
        "datasource.schema.repair.preview",
        "datasource.schema.repair.apply",
    )
    return AgentPlan(
        request_id=request.request_id or "request-bridge-1",
        selected_route=None,
        state_trace=("receive_goal", "specialist_agent",),
        tool_plans=tuple(tool_plans),
        requires_human_approval=False,
        response_summary="测试父计划",
        intent_analysis=IntentAnalysis(
            summary="数据同步测试意图",
            governance_domains=(GovernanceDomain.DATA_SYNC,),
            candidate_tools=candidate_tools,
            confidence=1.0,
        ),
    )


def _plan_without_recovery_preview_tools(request: AgentRequest) -> AgentPlan:
    """Build a parent plan that reached Recovery before predicting a specific repair preview.

    This is the production-shaped regression setup for execution ``1805``: the main Agent legitimately
    planned diagnosis and RAG, then the Recovery Specialist identified a safe preview.  The helper keeps
    the parent plan free of both preview and all mutating recovery tools so each test can prove that the
    bridge adds only an explicitly generated read-only preview, never a broader recovery capability.
    """

    base = _plan(request)
    assert base.intent_analysis is not None
    return replace(
        base,
        intent_analysis=replace(
            base.intent_analysis,
            candidate_tools=(
                "sync.execution.diagnose",
                "sync.execution.rag.lookup",
            ),
        ),
    )


def _bridge() -> SpecialistToolPlanBridge:
    """使用生产默认注册表创建桥接，确保测试不会使用一份私有工具白名单。"""

    tool_planner = ToolPlanner(default_tool_registry())
    return SpecialistToolPlanBridge(
        tool_planner=tool_planner,
        follow_up_tool_planner=AgentFollowUpToolPlanner(tool_planner=tool_planner),
    )


def _metadata_feedback(
    *,
    source_objects: list[dict[str, Any]] | None = None,
    target_objects: list[dict[str, Any]] | None = None,
    source_datasource_id: int = 27,
    target_datasource_id: int = 28,
    include_old_failed_source: bool = False,
) -> AgentControlPlaneFeedbackSnapshot:
    """构造两端真实元数据成功事实，并可选加入旧失败事实验证“最新优先”。"""

    source_objects = source_objects or [_object_metadata(None, "customer")]
    target_objects = target_objects or [_object_metadata("public", "customer")]
    items: list[AgentControlPlaneFeedbackItem] = []
    if include_old_failed_source:
        items.append(AgentControlPlaneFeedbackItem(
            model_tool_call_id="call-source-old",
            tool_name="datasource.source.metadata.read",
            status=ToolExecutionFeedbackStatus.FAILED,
            summary="旧的源端元数据读取失败",
            error_code="TEMPORARY_METADATA_ERROR",
            run_id="run-old",
        ))
    items.extend((
        AgentControlPlaneFeedbackItem(
            model_tool_call_id="call-source-metadata",
            tool_name="datasource.source.metadata.read",
            status=ToolExecutionFeedbackStatus.SUCCEEDED,
            summary="源端元数据读取成功",
            result={
                "datasourceId": source_datasource_id,
                "summary": {
                    "datasourceId": source_datasource_id,
                    "truncated": False,
                    "objects": source_objects,
                },
            },
            audit_id="audit-source-metadata",
            run_id="run-metadata",
            output_ref="agent-runtime://tool-results/audit-source-metadata",
        ),
        AgentControlPlaneFeedbackItem(
            model_tool_call_id="call-target-metadata",
            tool_name="datasource.target.metadata.read",
            status=ToolExecutionFeedbackStatus.SUCCEEDED,
            summary="目标端元数据读取成功",
            result={
                "datasourceId": target_datasource_id,
                "summary": {
                    "datasourceId": target_datasource_id,
                    "truncated": False,
                    "objects": target_objects,
                },
            },
            audit_id="audit-target-metadata",
            run_id="run-metadata",
            output_ref="agent-runtime://tool-results/audit-target-metadata",
        ),
    ))
    return AgentControlPlaneFeedbackSnapshot(
        expected_tool_call_count=len(items),
        feedback_items=tuple(items),
        missing_tool_call_ids=(),
        status_counts={"succeeded": 2, **({"failed": 1} if include_old_failed_source else {})},
        second_turn_eligible=True,
        recommended_actions=(),
    )


def _object_metadata(schema_name: str | None, table_name: str) -> dict[str, Any]:
    """返回包含两个兼容字段的最小表元数据。"""

    return {
        "schemaName": schema_name,
        "tableName": table_name,
        "columns": [
            {"columnName": "id", "dataTypeName": "BIGINT"},
            {"columnName": "name", "dataTypeName": "VARCHAR"},
        ],
    }


def _data_sync_result(*, task_name: str = "customer-full") -> SpecialistTurnResult:
    """构造真实 DATA_SYNC_AGENT 完成结果，但明确声明它仍然只是草案。"""

    return SpecialistTurnResult(
        agent_id="data-sync-agent-1",
        role=AgentSessionRole.DATA_SYNC_AGENT,
        turn_id="turn-data-sync-1",
        status=SpecialistTurnStatus.COMPLETED,
        public_summary="同步草案已完成",
        structured_output={
            "draftOnly": True,
            "persisted": False,
            "published": False,
            "executed": False,
            "taskName": task_name,
            "syncMode": "FULL",
            "writeStrategy": "INSERT",
            "sourceDatasourceId": 27,
            "targetDatasourceId": 28,
            "objectMappings": [{
                "sourceObjectName": "customer",
                "targetSchemaName": "public",
                "targetObjectName": "customer",
                "fieldMappings": [
                    {"sourceField": "id", "targetField": "id", "syncEnabled": True},
                    {"sourceField": "name", "targetField": "name", "syncEnabled": True},
                ],
                "whereCondition": "",
            }],
            "validationIssueCodes": (),
        },
    )


def _recovery_result(
    action_type: str,
    *,
    tool_name: str | None = None,
    proposed_values: Mapping[str, Any] | None = None,
) -> SpecialistTurnResult:
    """构造 Recovery 的低敏动作建议，不携带任何可直接执行的业务参数。"""

    action: dict[str, Any] = {
        "actionId": "recovery-action-1",
        "actionType": action_type,
        "reason": "根据真实失败诊断和案例证据提出恢复建议。",
    }
    if tool_name is not None:
        action["toolName"] = tool_name
    if proposed_values is not None:
        action["proposedValues"] = dict(proposed_values)
    return SpecialistTurnResult(
        agent_id="recovery-agent-1",
        role=AgentSessionRole.RECOVERY_AGENT,
        turn_id="turn-recovery-1",
        status=SpecialistTurnStatus.COMPLETED,
        public_summary="已生成恢复建议",
        structured_output={
            "actionFingerprint": "sha256:recovery-action-fingerprint",
            "repairActions": [action],
            "executed": False,
            "evidenceReferences": ("case://sync-failure-1",),
        },
    )


def _recovery_result_with_diagnostic_binding(
    request: AgentRequest,
    action_type: str = "PREVIEW_QUARANTINE",
    *,
    project_id: str | None = None,
) -> SpecialistTurnResult:
    """构造带非公开 Java 诊断事实绑定的 Recovery 结果。

    公开 ``structured_output`` 仍只包含动作建议；taskId/executionId 只存在于进程内绑定，
    用来验证 Bridge 不会从模型文本猜资源定位。``project_id`` 可故意制造跨项目事实，固定
    fail-closed 反例。
    """

    delegated = {
        "tenantId": request.tenant_id,
        "applicationId": "datasmart",
        "projectId": request.project_id,
        "actorId": request.actor_id,
        "userId": request.actor_id,
        "sessionId": "session-bridge-1",
        "runId": "run-bridge-1",
        "delegationId": "delegation-recovery-1",
    }
    fact = {
        "source": "data-sync-control-plane",
        "factType": "SYNC_EXECUTION_DIAGNOSIS",
        "tenantId": request.tenant_id,
        "applicationId": "datasmart",
        "projectId": project_id or request.project_id,
        "actorId": request.actor_id,
        "sessionId": "session-bridge-1",
        "runId": "run-bridge-1",
        "delegationId": "delegation-recovery-1",
        "taskId": "76",
        "executionId": "1805",
    }
    return replace(
        _recovery_result(action_type),
        delegated_scope_binding=delegated,
        control_plane_fact_binding=fact,
    )


def _diagnosis_feedback(
    *,
    include_rag: bool = False,
    include_preview_tool: str | None = None,
) -> AgentControlPlaneFeedbackSnapshot:
    """构造恢复所需的真实事实，并按需加入 RAG 或 preview 成功记录。

    这里故意把 preview 作为控制面成功事实，而不是直接把 preview 参数交给
    bridge。这样测试的是生产链路的真实边界：bridge 只能把模型的 RecoveryAction
    转成候选 ToolPlan，具体的 diagnosis、RAG 和 preview 前置条件仍由
    ``AgentFollowUpToolPlanner`` 根据已审计的执行反馈决定是否放行。
    """

    items = [AgentControlPlaneFeedbackItem(
        model_tool_call_id="call-diagnosis",
        tool_name="sync.execution.diagnose",
        status=ToolExecutionFeedbackStatus.SUCCEEDED,
        summary="执行失败诊断成功",
        result={"failureCode": "DIRTY_RECORDS"},
        audit_id="audit-diagnosis",
        run_id="run-recovery",
        output_ref="agent-runtime://tool-results/audit-diagnosis",
    )]
    if include_rag:
        items.append(AgentControlPlaneFeedbackItem(
            model_tool_call_id="call-rag",
            tool_name="sync.execution.rag.lookup",
            status=ToolExecutionFeedbackStatus.SUCCEEDED,
            summary="已检索历史故障案例",
            result={"evidenceCount": 2},
            audit_id="audit-rag",
            run_id="run-recovery",
            output_ref="agent-runtime://tool-results/audit-rag",
        ))
    if include_preview_tool:
        items.append(AgentControlPlaneFeedbackItem(
            model_tool_call_id="call-preview",
            tool_name=include_preview_tool,
            status=ToolExecutionFeedbackStatus.SUCCEEDED,
            summary="恢复动作 preview 已由控制面成功完成",
            result={"previewId": "preview-recovery-1"},
            audit_id="audit-recovery-preview",
            run_id="run-recovery",
            output_ref="agent-runtime://tool-results/audit-recovery-preview",
        ))
    return AgentControlPlaneFeedbackSnapshot(
        expected_tool_call_count=len(items),
        feedback_items=tuple(items),
        missing_tool_call_ids=(),
        status_counts={"succeeded": len(items)},
        second_turn_eligible=True,
        recommended_actions=(),
    )


def test_data_sync_requires_real_two_sided_metadata_and_latest_fact() -> None:
    """只有两端带审计绑定的真实 metadata 才能进入同步生命周期，旧失败不能覆盖新成功。"""

    request = _request()
    bridge = _bridge()
    result = bridge.bridge(
        request=request,
        plan=_plan(request),
        specialist_result=_data_sync_result(),
        control_plane_feedback=_metadata_feedback(include_old_failed_source=True),
    )

    assert result.status is SpecialistBridgeStatus.ACCEPTED
    assert tuple(item.tool_name for item in result.accepted_tool_plans) == (
        "sync.task.draft.save",
        "sync.task.precheck",
        "sync.task.publish",
        "sync.task.run",
        "sync.execution.status",
    )
    assert all(item.arguments.get("sourceMetadataRef") for item in result.accepted_tool_plans[:1])
    assert result.accepted_tool_plans[0].arguments["targetMetadataRef"]["fromAuditId"] == "audit-target-metadata"


def test_data_sync_without_one_real_metadata_side_is_waiting_and_has_no_toolplan() -> None:
    """缺少任意一端真实 metadata 时只能等待控制面，不能使用 specialist 摘要补造计划。"""

    feedback = _metadata_feedback()
    feedback = AgentControlPlaneFeedbackSnapshot(
        expected_tool_call_count=1,
        feedback_items=(feedback.feedback_items[0],),
        missing_tool_call_ids=("call-target-metadata",),
        status_counts={"succeeded": 1},
        second_turn_eligible=False,
        recommended_actions=("读取目标端元数据",),
    )
    result = _bridge().bridge(
        request=_request(),
        plan=_plan(_request()),
        specialist_result=_data_sync_result(),
        control_plane_feedback=feedback,
    )

    assert result.status is SpecialistBridgeStatus.WAITING_FOR_CONTROL_PLANE_EVIDENCE
    assert result.accepted_tool_plans == ()
    assert any(issue.code == "SPECIALIST_METADATA_EVIDENCE_MISSING" for issue in result.issues)


def test_data_sync_mapping_must_match_both_metadata_sides() -> None:
    """真实元数据存在但目标表不存在时，桥接仍必须由确定性治理拒绝映射。"""

    feedback = _metadata_feedback(target_objects=[_object_metadata("public", "other_table")])
    result = _bridge().bridge(
        request=_request(),
        plan=_plan(_request()),
        specialist_result=_data_sync_result(),
        control_plane_feedback=feedback,
    )

    assert result.status is SpecialistBridgeStatus.REJECTED
    assert result.accepted_tool_plans == ()
    assert any(
        issue.code == "MODEL_TOOL_CALL_TARGET_OBJECT_NOT_IN_METADATA"
        for issue in result.issues
    )


def test_recovery_supported_actions_are_registered_and_governed() -> None:
    """Recovery 每个支持动作都必须映射到默认注册表中的受治理工具。"""

    bridge = _bridge()
    registered = {tool.name: tool for tool in ToolPlanner(default_tool_registry()).registered_tools()}
    for action_type, expected_tool in RECOVERY_ACTION_TOOL_MAP.items():
        assert bridge._map_recovery_action({"actionType": action_type}) == expected_tool
        tool = registered[expected_tool]
        assert tool.tenant_scoped is True
        assert tool.project_scoped is True
        assert tool.allowed_actions
        assert RECOVERY_TOOL_REQUIRED_ACTION[expected_tool] in tool.allowed_actions
        assert tool.target_service and tool.target_endpoint


def test_recovery_unregistered_or_unsupported_action_fails_closed() -> None:
    """未知动作和注册表中不存在的工具都不能退化成通用 recovery 执行入口。"""

    full_registry = default_tool_registry()
    reduced_registry = tuple(
        tool for tool in full_registry
        if tool.name != "sync.dirty-record.quarantine.preview"
    )
    reduced_planner = ToolPlanner(reduced_registry)
    reduced_bridge = SpecialistToolPlanBridge(
        tool_planner=reduced_planner,
        follow_up_tool_planner=AgentFollowUpToolPlanner(tool_planner=reduced_planner),
    )
    assert reduced_bridge._map_recovery_action({"actionType": "PREVIEW_QUARANTINE"}) is None

    # 即使工具名字仍然存在，allowed_actions 被错误配置时也必须拒绝；只检查“工具已注册”
    # 不足以证明它真的是这个 Recovery 动作的受治理入口。
    malformed_registry = tuple(
        replace(tool, allowed_actions=("UNRELATED_ACTION",))
        if tool.name == "sync.dirty-record.quarantine.preview"
        else tool
        for tool in full_registry
    )
    malformed_planner = ToolPlanner(malformed_registry)
    malformed_bridge = SpecialistToolPlanBridge(
        tool_planner=malformed_planner,
        follow_up_tool_planner=AgentFollowUpToolPlanner(tool_planner=malformed_planner),
    )
    assert malformed_bridge._map_recovery_action({"actionType": "PREVIEW_QUARANTINE"}) is None
    # 注册表的真实 schema repair 权限是 ALTER_TARGET_SCHEMA；不能把不存在的
    # APPLY_SCHEMA_REPAIR 当成可执行权限，也不能因为名字相似而放宽映射。
    assert _bridge()._map_recovery_action({"actionType": "APPLY_SCHEMA_REPAIR"}) is None

    result = _bridge().bridge(
        request=_request(),
        plan=_plan(_request()),
        specialist_result=_recovery_result("DROP_DATABASE", tool_name="recovery.execute"),
        control_plane_feedback=_diagnosis_feedback(),
    )
    assert result.status is SpecialistBridgeStatus.REJECTED
    assert result.accepted_tool_plans == ()
    assert any(issue.code == "RECOVERY_ACTION_UNSUPPORTED" for issue in result.issues)


def test_recovery_trusted_specialist_diagnosis_bootstraps_java_audit_before_preview() -> None:
    """缺少主循环 diagnosis audit 时，应先提交一个只读 Java 诊断而不是断链。

    Specialist 已经通过受保护 HTTP 接口读取过诊断，但后续工具要求正式 auditId/runId。
    第一次 Bridge 因此只能提交 ``sync.execution.diagnose``；预览动作必须等该工具成功反馈后
    再由同一结果二次桥接，不能在本步骤直接执行或伪造 diagnosisRef。
    """

    request = _request()
    specialist_result = _recovery_result_with_diagnostic_binding(request)
    result = _bridge().bridge(
        request=request,
        plan=_plan(request),
        specialist_result=specialist_result,
        control_plane_feedback=None,
    )

    assert result.status is SpecialistBridgeStatus.ACCEPTED
    assert tuple(item.tool_name for item in result.accepted_tool_plans) == (
        "sync.execution.diagnose",
    )
    diagnose = result.accepted_tool_plans[0]
    assert diagnose.arguments == {"taskId": 76, "executionId": 1805}
    assert diagnose.requires_human_approval is False
    diagnose_definition = next(
        definition
        for definition in default_tool_registry()
        if definition.name == "sync.execution.diagnose"
    )
    assert diagnose_definition.read_only is True
    assert result.recovery_handoff is None
    assert "controlPlaneFactBinding" not in str(specialist_result.to_summary())
    assert "taskId" not in specialist_result.structured_output
    assert "executionId" not in specialist_result.structured_output


def test_recovery_diagnosis_bootstrap_reuses_matching_parent_plan() -> None:
    """主 Agent 已计划诊断时，Recovery 应复用节点并继续获取 Java audit。

    自然语言模型可能在 Specialist 执行前已经选择 ``sync.execution.diagnose``。如果该
    ToolPlan 尚无正式成功回执，Recovery 仍需要把它交给 Durable runner；重复保护只阻止
    创建第二份计划，不能把第一次计划也误判为失败。
    """

    request = _request()
    parent_diagnosis = ToolPlan(
        tool_name="sync.execution.diagnose",
        reason="主 Agent 已根据失败执行定位计划只读诊断。",
        arguments={"taskId": 76, "executionId": 1805},
    )
    result = _bridge().bridge(
        request=request,
        plan=_plan(request, parent_diagnosis),
        specialist_result=_recovery_result_with_diagnostic_binding(request),
        control_plane_feedback=None,
    )

    assert result.status is SpecialistBridgeStatus.ACCEPTED
    assert tuple(item.tool_name for item in result.accepted_tool_plans) == (
        "sync.execution.diagnose",
    )
    assert result.recovery_handoff is None
    assert result.can_submit_durable_loop is True


def test_recovery_cross_project_diagnostic_binding_cannot_bootstrap_java_audit() -> None:
    """跨项目或伪造的内部诊断绑定必须继续停在控制面证据等待态。"""

    request = _request()
    result = _bridge().bridge(
        request=request,
        plan=_plan(request),
        specialist_result=_recovery_result_with_diagnostic_binding(
            request,
            project_id="project-900",
        ),
        control_plane_feedback=None,
    )

    assert result.status is SpecialistBridgeStatus.WAITING_FOR_CONTROL_PLANE_EVIDENCE
    assert result.accepted_tool_plans == ()
    assert any(issue.code == "RECOVERY_DIAGNOSIS_EVIDENCE_REQUIRED" for issue in result.issues)


def test_recovery_quarantine_preview_defaults_to_bounded_retryable_scope() -> None:
    """模型看不到坏行 ID 时，隔离预览应使用 data-sync 有界的全可重试样本选择器。

    该默认值只用于只读 preview。后续 apply 仍必须引用 preview 回执中的精确 ID 与确认摘要，
    因而不能把这个便利默认值扩展为“自动隔离全部坏数据”。
    """

    request = _request()
    result = _bridge().bridge(
        request=request,
        plan=_plan(request),
        specialist_result=_recovery_result("PREVIEW_QUARANTINE"),
        control_plane_feedback=_diagnosis_feedback(include_rag=True),
    )

    assert result.status is SpecialistBridgeStatus.ACCEPTED
    assert tuple(item.tool_name for item in result.accepted_tool_plans) == (
        "sync.dirty-record.quarantine.preview",
    )
    assert result.accepted_tool_plans[0].arguments["quarantineAllRetryableInExecution"] is True
    assert result.accepted_tool_plans[0].arguments["diagnosisRef"]["fromAuditId"] == "audit-diagnosis"


def test_recovery_reuses_identical_read_only_preview_after_diagnosis_phase() -> None:
    """两阶段 Recovery 可复用既有只读预览，但不能因此关闭整条恢复链路。

    生产链路先通过 Java 创建 ``sync.execution.diagnose`` 审计，再以同一个 Specialist
    结果生成预览。Durable runner 可能已经把该预览写入父计划；第二次桥接时重复保护
    会阻止重新生成相同节点。此时正确语义是复用完全相同的只读幂等 ToolPlan，并继续
    返回 Recovery handoff，而不是把整次恢复标成 ``REJECTED``。
    """

    request = _request()
    specialist_result = _recovery_result("PREVIEW_QUARANTINE")
    first = _bridge().bridge(
        request=request,
        plan=_plan_without_recovery_preview_tools(request),
        specialist_result=specialist_result,
        control_plane_feedback=_diagnosis_feedback(include_rag=True),
    )
    assert first.status is SpecialistBridgeStatus.ACCEPTED
    assert first.plan is not None

    repeated = _bridge().bridge(
        request=request,
        plan=first.plan,
        specialist_result=specialist_result,
        control_plane_feedback=_diagnosis_feedback(include_rag=True),
    )

    assert repeated.status is SpecialistBridgeStatus.ACCEPTED
    assert tuple(item.tool_name for item in repeated.accepted_tool_plans) == (
        "sync.dirty-record.quarantine.preview",
    )
    assert repeated.recovery_handoff is not None
    assert repeated.can_submit_durable_loop is True


def test_recovery_reuses_read_only_preview_that_issues_a_new_preview_reference() -> None:
    """只读结构预览即使不声明业务幂等，也应复用同一个父计划节点。

    ``datasource.schema.repair.preview`` 每次真实调用可能签发新的 previewRef，所以注册表
    不把它标为业务幂等。不过这里处理的是同一个 ToolPlan 的二阶段重入，Java 会沿用原
    ToolPlan 的审计和幂等标识，不会再次调用下游；因此它与写入型结构修复的边界不同。
    """

    request = _request()
    specialist_result = _recovery_result(
        "PREVIEW_SCHEMA_REPAIR",
        proposed_values={
            "operation": "WIDEN_VARCHAR",
            "schemaName": "public",
            "tableName": "customer",
            "columnName": "display_name",
            "requestedLength": 512,
        },
    )
    first = _bridge().bridge(
        request=request,
        plan=_plan(request),
        specialist_result=specialist_result,
        control_plane_feedback=_diagnosis_feedback(include_rag=True),
    )
    assert first.status is SpecialistBridgeStatus.ACCEPTED
    assert first.plan is not None

    repeated = _bridge().bridge(
        request=request,
        plan=first.plan,
        specialist_result=specialist_result,
        control_plane_feedback=_diagnosis_feedback(include_rag=True),
    )

    assert repeated.status is SpecialistBridgeStatus.ACCEPTED
    assert tuple(item.tool_name for item in repeated.accepted_tool_plans) == (
        "datasource.schema.repair.preview",
    )
    assert repeated.recovery_handoff is not None


def test_recovery_explicitly_delegates_only_generated_registered_read_only_preview() -> None:
    """A safe Recovery preview may bridge even when the earlier main plan did not predict it.

    The regression fixes the real ``RECOVERY_TOOL_NOT_VISIBLE`` gap without weakening the general tool
    frontier.  The Specialist's mapped action is the source of the one-item delegation; the result must
    not incidentally expose schema preview, retry, replay, quarantine apply, or schema apply.
    """

    request = _request()
    plan = _plan_without_recovery_preview_tools(request)
    bridge = _bridge()
    baseline_names = {
        tool.name
        for tool in bridge._follow_up.visible_tools(request, plan)
    }
    # RAG is already visible in this fixture; the preview delegation test only
    # requires that no unrelated recovery preview leaked into the parent frontier.
    preview_only_names = RECOVERY_MINIMAL_READ_ONLY_DELEGATION_TOOL_NAMES - {"sync.execution.rag.lookup"}
    assert preview_only_names.isdisjoint(baseline_names)

    result = bridge.bridge(
        request=request,
        plan=plan,
        specialist_result=_recovery_result("PREVIEW_QUARANTINE"),
        control_plane_feedback=_diagnosis_feedback(include_rag=True),
    )

    assert result.status is SpecialistBridgeStatus.ACCEPTED
    assert tuple(item.tool_name for item in result.accepted_tool_plans) == (
        "sync.dirty-record.quarantine.preview",
    )
    assert "sync.dirty-record.quarantine.preview" in result.visible_tool_names
    assert "datasource.schema.repair.preview" not in result.visible_tool_names
    assert "sync.dirty-record.quarantine.apply" not in result.visible_tool_names
    assert "datasource.schema.repair.apply" not in result.visible_tool_names


def test_recovery_model_search_decision_bridges_only_read_only_rag_lookup() -> None:
    """A model SEARCH decision becomes one scoped RAG lookup, never a repair mutation."""

    request = _request()
    result = _bridge().bridge(
        request=request,
        plan=_plan_without_recovery_preview_tools(request),
        specialist_result=_recovery_result("SEARCH_RECOVERY_KNOWLEDGE"),
        control_plane_feedback=_diagnosis_feedback(include_rag=False),
    )

    assert result.status is SpecialistBridgeStatus.ACCEPTED
    assert tuple(item.tool_name for item in result.accepted_tool_plans) == (
        "sync.execution.rag.lookup",
    )
    rag_plan = result.accepted_tool_plans[0]
    assert rag_plan.risk_level is ToolRiskLevel.LOW
    assert rag_plan.execution_mode is ToolExecutionMode.SYNC
    assert rag_plan.requires_human_approval is False
    assert rag_plan.arguments["diagnosisRef"]["fromTool"] == "sync.execution.diagnose"


def test_recovery_delegates_each_explicit_complete_read_only_preview_without_parent_prediction() -> None:
    """Two independently grounded previews may coexist without granting either apply tool.

    The real Recovery turn can diagnose both dirty records and a target schema mismatch.  The parent plan
    still contains neither preview, so this test confirms delegation is calculated per generated action
    rather than hard-coding a one-tool fallback.  Schema values are intentionally concrete and limited to
    the registered model-optional allowlist; missing values would remain in the existing input-required
    branch instead of receiving an empty Java ToolPlan.
    """

    request = _request()
    base = _recovery_result("PREVIEW_QUARANTINE")
    specialist_result = replace(
        base,
        structured_output={
            **dict(base.structured_output),
            "repairActions": [
                dict(base.structured_output["repairActions"][0]),
                {
                    "actionId": "schema-preview-complete",
                    "actionType": "PREVIEW_SCHEMA_REPAIR",
                    "reason": "Preview a bounded target-column compatibility repair.",
                    "proposedValues": {
                        "operation": "WIDEN_VARCHAR",
                        "schemaName": "public",
                        "tableName": "customer",
                        "columnName": "display_name",
                        "requestedLength": 512,
                    },
                },
            ],
        },
    )

    result = _bridge().bridge(
        request=request,
        plan=_plan_without_recovery_preview_tools(request),
        specialist_result=specialist_result,
        control_plane_feedback=_diagnosis_feedback(include_rag=True),
    )

    assert result.status is SpecialistBridgeStatus.ACCEPTED
    assert tuple(item.tool_name for item in result.accepted_tool_plans) == (
        "sync.dirty-record.quarantine.preview",
        "datasource.schema.repair.preview",
    )
    assert set(RECOVERY_MINIMAL_READ_ONLY_DELEGATION_TOOL_NAMES).issubset(
        result.visible_tool_names
    )
    assert "sync.dirty-record.quarantine.apply" not in result.visible_tool_names
    assert "datasource.schema.repair.apply" not in result.visible_tool_names


def test_recovery_minimal_preview_delegation_does_not_expose_unplanned_write_tool() -> None:
    """A generated high-risk Recovery action still needs a parent-plan grant and Java approval.

    Supplying a successful preview/RAG fact only satisfies the mutation's evidence prerequisites.  It must
    not cause the read-only Recovery exception to append a write tool, otherwise a model could convert a
    safe preview delegation into a mutation simply by changing ``actionType``.
    """

    request = _request()
    result = _bridge().bridge(
        request=request,
        plan=_plan_without_recovery_preview_tools(request),
        specialist_result=_recovery_result("APPLY_QUARANTINE"),
        control_plane_feedback=_diagnosis_feedback(
            include_rag=True,
            include_preview_tool="sync.dirty-record.quarantine.preview",
        ),
    )

    assert result.status is SpecialistBridgeStatus.REJECTED
    assert result.accepted_tool_plans == ()
    assert any(issue.code == "RECOVERY_TOOL_NOT_VISIBLE" for issue in result.issues)


def test_recovery_minimal_preview_delegation_rejects_registry_that_lost_read_only_contract() -> None:
    """A stale or malformed registry cannot use the minimal delegation path to gain visibility.

    The test intentionally keeps the fixed action mapping valid while changing only the registered tool's
    ``read_only`` contract.  This verifies that the bridge trusts neither the model action nor its own
    mapping table alone; the registry remains the final capability source.
    """

    registry = tuple(
        replace(tool, read_only=False)
        if tool.name == "sync.dirty-record.quarantine.preview"
        else tool
        for tool in default_tool_registry()
    )
    planner = ToolPlanner(registry)
    bridge = SpecialistToolPlanBridge(
        tool_planner=planner,
        follow_up_tool_planner=AgentFollowUpToolPlanner(tool_planner=planner),
    )
    request = _request()
    result = bridge.bridge(
        request=request,
        plan=_plan_without_recovery_preview_tools(request),
        specialist_result=_recovery_result("PREVIEW_QUARANTINE"),
        control_plane_feedback=_diagnosis_feedback(include_rag=True),
    )

    assert result.status is SpecialistBridgeStatus.REJECTED
    assert result.accepted_tool_plans == ()
    assert any(issue.code == "RECOVERY_TOOL_NOT_VISIBLE" for issue in result.issues)


def test_recovery_skips_incomplete_schema_preview_but_keeps_complete_read_only_action() -> None:
    """同一批建议中，缺少表/字段定位的结构预览不得阻塞完整的隔离预览。"""

    request = _request()
    base = _recovery_result("PREVIEW_QUARANTINE")
    specialist_result = replace(
        base,
        structured_output={
            **dict(base.structured_output),
            "repairActions": [
                dict(base.structured_output["repairActions"][0]),
                {
                    "actionId": "schema-preview-without-target",
                    "actionType": "PREVIEW_SCHEMA_REPAIR",
                    "reason": "诊断建议先检查结构修复，但没有给出可验证的表字段定位。",
                },
            ],
        },
    )
    result = _bridge().bridge(
        request=request,
        plan=_plan(request),
        specialist_result=specialist_result,
        control_plane_feedback=_diagnosis_feedback(include_rag=True),
    )

    assert result.status is SpecialistBridgeStatus.ACCEPTED
    assert tuple(item.tool_name for item in result.accepted_tool_plans) == (
        "sync.dirty-record.quarantine.preview",
    )
    assert any(issue.code == "RECOVERY_ACTION_INPUT_INCOMPLETE" for issue in result.issues)
    assert "datasource.schema.repair.preview" not in {
        item.tool_name for item in result.accepted_tool_plans
    }


def test_recovery_only_incomplete_schema_preview_waits_for_specialist_input() -> None:
    """没有任何完整动作时应显式等待补参，而不是创建永久 PLANNED 的 Java 审计。"""

    request = _request()
    result = _bridge().bridge(
        request=request,
        plan=_plan(request),
        specialist_result=_recovery_result("PREVIEW_SCHEMA_REPAIR"),
        control_plane_feedback=_diagnosis_feedback(include_rag=True),
    )

    assert result.status is SpecialistBridgeStatus.WAITING_FOR_SPECIALIST_INPUT
    assert result.accepted_tool_plans == ()
    assert any(issue.code == "RECOVERY_ACTION_INPUT_INCOMPLETE" for issue in result.issues)


def test_recovery_complete_schema_preview_keeps_model_grounded_allowlist_values() -> None:
    """完整的白名单结构参数仍可进入治理，避免缺参防线误伤自主修复预览。"""

    request = _request()
    result = _bridge().bridge(
        request=request,
        plan=_plan(request),
        specialist_result=_recovery_result(
            "PREVIEW_SCHEMA_REPAIR",
            proposed_values={
                "operation": "WIDEN_VARCHAR",
                "schemaName": "public",
                "tableName": "customer",
                "columnName": "display_name",
                "requestedLength": 512,
            },
        ),
        control_plane_feedback=_diagnosis_feedback(include_rag=True),
    )

    assert result.status is SpecialistBridgeStatus.ACCEPTED
    assert tuple(item.tool_name for item in result.accepted_tool_plans) == (
        "datasource.schema.repair.preview",
    )
    arguments = result.accepted_tool_plans[0].arguments
    assert arguments["operation"] == "WIDEN_VARCHAR"
    assert arguments["tableName"] == "customer"
    assert arguments["columnName"] == "display_name"


@pytest.mark.parametrize(
    ("action_type", "expected_preview_tool", "expected_issue_code"),
    (
        (
            "APPLY_QUARANTINE",
            "sync.dirty-record.quarantine.preview",
            "MODEL_TOOL_CALL_QUARANTINE_PREVIEW_REQUIRED",
        ),
        (
            "ALTER_TARGET_SCHEMA",
            "datasource.schema.repair.preview",
            "MODEL_TOOL_CALL_SCHEMA_REPAIR_PREVIEW_REQUIRED",
        ),
    ),
)
def test_recovery_apply_without_preview_is_rejected_by_follow_up_governance(
    action_type: str,
    expected_preview_tool: str,
    expected_issue_code: str,
) -> None:
    """apply 没有对应 preview 成功事实时，必须由统一 follow-up 闸门拒绝。

    测试不直接调用 bridge 内部的 preview 判断，而是让 bridge 正常生成 apply
    候选，再观察 ``AgentFollowUpToolPlanner`` 是否依据控制面事实拒绝它。这样能
    防止未来有人为了让 bridge 测试通过，把安全前置条件错误地复制到 bridge 中，
    造成两套治理逻辑不一致。
    """

    request = _request()
    feedback = _diagnosis_feedback(include_rag=True)
    result = _bridge().bridge(
        request=request,
        plan=_plan(request),
        specialist_result=_recovery_result(action_type),
        # RAG 已成功，故本测试只隔离“缺少对应 preview”这一阻断条件。
        control_plane_feedback=feedback,
    )

    assert expected_preview_tool not in {
        item.tool_name
        for item in feedback.feedback_items
    }
    assert result.status is SpecialistBridgeStatus.REJECTED
    assert result.accepted_tool_plans == ()
    assert any(issue.code == expected_issue_code for issue in result.issues)


@pytest.mark.parametrize(
    ("action_type", "apply_tool", "preview_tool"),
    (
        (
            "APPLY_QUARANTINE",
            "sync.dirty-record.quarantine.apply",
            "sync.dirty-record.quarantine.preview",
        ),
        (
            "ALTER_TARGET_SCHEMA",
            "datasource.schema.repair.apply",
            "datasource.schema.repair.preview",
        ),
    ),
)
def test_recovery_apply_with_preview_and_rag_creates_approval_toolplan(
    action_type: str,
    apply_tool: str,
    preview_tool: str,
) -> None:
    """preview 与 RAG 都有真实成功事实后，apply 才能生成待人工审批 ToolPlan。

    apply 工具仍然不会在 Python 中执行。测试只检查受治理计划、派生 previewRef
    和 ``requires_human_approval``，实际写操作必须继续交给 Java 控制面审批和执行。
    """

    request = _request()
    result = _bridge().bridge(
        request=request,
        plan=_plan(request),
        specialist_result=_recovery_result(action_type),
        control_plane_feedback=_diagnosis_feedback(
            include_rag=True,
            include_preview_tool=preview_tool,
        ),
    )

    assert result.status is SpecialistBridgeStatus.ACCEPTED
    assert tuple(item.tool_name for item in result.accepted_tool_plans) == (apply_tool,)
    apply_plan = result.accepted_tool_plans[0]
    assert apply_plan.requires_human_approval is True
    assert apply_plan.arguments["previewRef"] == {
        "fromTool": preview_tool,
        "fromAuditId": "audit-recovery-preview",
        "fromRunId": "run-recovery",
        "path": None,
    }
    assert result.recovery_handoff is not None
    assert result.recovery_handoff.approval_fact_accepted is False


def test_recovery_supported_action_becomes_governed_toolplan_with_java_handoff() -> None:
    """恢复动作只产生 Java 可审批的 ToolPlan，且 handoff 保留完整作用域绑定。"""

    request = _request()
    result = _bridge().bridge(
        request=request,
        plan=_plan(request),
        specialist_result=_recovery_result("RETRY_FAILED_OBJECTS"),
        control_plane_feedback=_diagnosis_feedback(include_rag=True),
    )

    assert result.status is SpecialistBridgeStatus.ACCEPTED
    assert tuple(item.tool_name for item in result.accepted_tool_plans) == (
        "sync.execution.failed-objects.retry",
    )
    plan = result.accepted_tool_plans[0]
    assert plan.arguments["diagnosisRef"]["fromAuditId"] == "audit-diagnosis"
    assert plan.requires_human_approval is True
    assert result.recovery_handoff is not None
    handoff = result.recovery_handoff
    assert handoff.direct_execution is False
    assert handoff.tenant_id == request.tenant_id
    assert handoff.application_id == "datasmart"
    assert handoff.project_id == request.project_id
    assert handoff.actor_id == request.actor_id
    assert handoff.user_id == request.actor_id
    assert handoff.delegation_id == result.scope_binding["delegationId"]
    assert handoff.to_summary()["requiredApprovalBindings"] == (
        "tenantId",
        "applicationId",
        "projectId",
        "userId",
        "delegationId",
        "runId",
        "actionFingerprint",
    )


def test_recovery_keeps_specialist_approval_run_separate_from_java_feedback_run() -> None:
    """Recovery 的建议来源 Run 与 Java 工具 Run 不同也必须形成安全 handoff。

    Coordinator 创建的 specialist run/delegation 绑定用户实际审核的恢复建议；Java feedback
    run/session 只定位诊断结果。若把两者直接比较，所有真实 Recovery 都会被误判为跨范围；
    若完全忽略两者，又会丢失审批来源或让 outputRef 在错误 session 中解析。
    """

    request = _request()
    delegated_scope = {
        "tenantId": request.tenant_id,
        "applicationId": "datasmart",
        "projectId": request.project_id,
        "actorId": request.actor_id,
        "userId": request.actor_id,
        "sessionId": "multi-agent-session-recovery-1234",
        "runId": "multi-agent-run-recovery-1234",
        "delegationId": "delegation-recovery-1234",
    }
    specialist = _recovery_result("RETRY_FAILED_OBJECTS")
    specialist = replace(
        specialist,
        structured_output={
            **dict(specialist.structured_output),
            "approvalRequest": {
                "runId": delegated_scope["runId"],
                "delegationId": delegated_scope["delegationId"],
                "actionFingerprint": specialist.structured_output["actionFingerprint"],
            },
        },
        delegated_scope_binding=delegated_scope,
    )
    feedback = _diagnosis_feedback(include_rag=True)
    feedback = replace(
        feedback,
        feedback_items=tuple(
            replace(
                item,
                run_id="agr_recovery_feedback_1234",
                output_ref=(
                    "agent-runtime://sessions/ags_recovery_session_1234/"
                    "runs/agr_recovery_feedback_1234/tool-executions/"
                    f"atea_recovery_{index}_1234/result"
                ),
                result={
                    **dict(item.result),
                    "scopeBinding": {
                        "tenantId": request.tenant_id,
                        "applicationId": "datasmart",
                        "projectId": request.project_id,
                        "actorId": request.actor_id,
                        "userId": request.actor_id,
                        "sessionId": "ags_recovery_session_1234",
                        "runId": "agr_recovery_feedback_1234",
                    },
                },
            )
            for index, item in enumerate(feedback.feedback_items, start=1)
        ),
    )

    result = _bridge().bridge(
        request=request,
        plan=_plan(request),
        specialist_result=specialist,
        control_plane_feedback=feedback,
    )

    assert result.status is SpecialistBridgeStatus.ACCEPTED
    assert result.scope_binding["sessionId"] == delegated_scope["sessionId"]
    assert result.scope_binding["runId"] == delegated_scope["runId"]
    assert result.scope_binding["delegationId"] == delegated_scope["delegationId"]
    assert result.scope_binding["controlPlaneSessionId"] == "ags_recovery_session_1234"
    assert result.scope_binding["controlPlaneRunId"] == "agr_recovery_feedback_1234"
    assert result.recovery_handoff is not None
    assert result.recovery_handoff.run_id == delegated_scope["runId"]
    assert result.recovery_handoff.delegation_id == delegated_scope["delegationId"]
    assert result.accepted_tool_plans[0].governance_hints["agentRuntimeSessionId"] == (
        "ags_recovery_session_1234"
    )


def test_duplicate_task_name_uses_existing_recovery_planner_and_requires_confirmation() -> None:
    """任务重名必须复用 DuplicateTaskNameRecoveryPlanner，而不是伪造 rename 工具。"""

    request = _request()
    original_draft = ToolPlan(
        tool_name="sync.task.draft.save",
        reason="已经通过审核的同步草案",
        arguments={
            "taskName": "customer-full",
            "syncMode": "FULL",
            "sourceDatasourceId": 27,
            "targetDatasourceId": 28,
            "objectMappings": [{
                "sourceObjectName": "customer",
                "targetSchemaName": "public",
                "targetObjectName": "customer",
                "fieldMappings": [],
            }],
        },
        governance_hints={"modelToolCallId": "call-draft"},
    )
    failed = AgentControlPlaneFeedbackItem(
        model_tool_call_id="call-draft",
        tool_name="sync.task.draft.save",
        status=ToolExecutionFeedbackStatus.FAILED,
        summary="任务名称已存在",
        error_code="DUPLICATE_OPERATION",
        error_message="当前项目已经存在同名任务",
        audit_id="audit-draft-failed",
        run_id="run-draft-failed",
    )
    feedback = AgentControlPlaneFeedbackSnapshot(
        expected_tool_call_count=1,
        feedback_items=(failed,),
        missing_tool_call_ids=(),
        status_counts={"failed": 1},
        second_turn_eligible=True,
        recommended_actions=(),
    )
    result = _bridge().bridge(
        request=request,
        plan=_plan(request, original_draft),
        specialist_result=_recovery_result("RENAME_TASK", tool_name="task.recovery.rename"),
        control_plane_feedback=feedback,
    )

    assert result.status is SpecialistBridgeStatus.ACCEPTED
    assert tuple(item.tool_name for item in result.accepted_tool_plans) == (
        "sync.task.draft.save",
        "sync.task.precheck",
        "sync.task.publish",
        "sync.task.run",
        "sync.execution.status",
    )
    repaired_draft = result.accepted_tool_plans[0]
    assert repaired_draft.arguments["taskName"] != "customer-full"
    assert repaired_draft.governance_hints["failureRecoveryKind"] == "DUPLICATE_TASK_NAME"
    assert repaired_draft.requires_human_approval is True
    assert repaired_draft.governance_hints["agentScopeBinding"] == result.scope_binding


def test_bridge_preserves_scope_and_is_idempotent_without_python_execution() -> None:
    """相同输入重复桥接只返回相同计划，不注入执行器，也不直接执行业务写操作。"""

    request = _request()
    parent = _plan(request)
    feedback = _metadata_feedback()
    bridge = _bridge()
    first = bridge.bridge(
        request=request,
        plan=parent,
        specialist_result=_data_sync_result(),
        control_plane_feedback=feedback,
    )
    second = bridge.bridge(
        request=request,
        plan=parent,
        specialist_result=_data_sync_result(),
        control_plane_feedback=feedback,
    )

    assert first.status is SpecialistBridgeStatus.ACCEPTED
    assert second.status is SpecialistBridgeStatus.ACCEPTED
    assert first.scope_binding == second.scope_binding
    assert tuple((item.tool_name, item.arguments) for item in first.accepted_tool_plans) == tuple(
        (item.tool_name, item.arguments) for item in second.accepted_tool_plans
    )
    assert first.specialist_result_fingerprint == second.specialist_result_fingerprint
    assert all("agentScopeBinding" in item.governance_hints for item in first.accepted_tool_plans)
    assert all(
        item.governance_hints["agentRuntimeSessionId"] == "session-bridge-1"
        for item in first.accepted_tool_plans
    )
    assert all("agentRuntimeRunId" not in item.governance_hints for item in first.accepted_tool_plans)
    assert not any("executor" in name.lower() for name in inspect.signature(
        SpecialistToolPlanBridge.__init__
    ).parameters)
    assert all(not getattr(item, "executed", False) for item in first.accepted_tool_plans)


def test_bridge_recovers_java_session_from_trusted_feedback_output_reference() -> None:
    """A new lifecycle Run must stay in the metadata Run's Java session.

    The public Agent request does not carry a Java session on its first turn.  The
    only trustworthy locator available after datasource discovery is therefore the
    output URI returned by Java.  Keeping the same session lets the draft adapter
    resolve explicit metadata audit references without allowing cross-session reads.
    """

    request = replace(
        _request(),
        variables={"trustedControlPlane": {"applicationId": "datasmart"}},
    )
    feedback = _metadata_feedback()
    feedback = replace(
        feedback,
        feedback_items=tuple(
            replace(
                item,
                output_ref=(
                    "agent-runtime://sessions/ags_metadata_session_1234/"
                    f"runs/agr_metadata_run_1234/tool-executions/atea_{index}_metadata_1234/result"
                ),
            )
            for index, item in enumerate(feedback.feedback_items, start=1)
        ),
    )

    result = _bridge().bridge(
        request=request,
        plan=_plan(request),
        specialist_result=_data_sync_result(),
        control_plane_feedback=feedback,
    )

    assert result.status is SpecialistBridgeStatus.ACCEPTED
    assert result.scope_binding["sessionId"] == "ags_metadata_session_1234"
    assert all(
        item.governance_hints["agentRuntimeSessionId"] == "ags_metadata_session_1234"
        for item in result.accepted_tool_plans
    )


def test_first_specialist_handoff_does_not_reuse_python_analysis_session_as_java_session() -> None:
    """A Specialist turn ID is not an existing Java session locator.

    Recovery and sync Specialists can produce a governed first ToolPlan before Java feedback exists.
    That handoff must omit ``agentRuntimeSessionId`` so Java creates the authoritative session. The
    Python analysis session remains in ``agentScopeBinding`` for audit correlation, but can never be
    used to claim that a Java session already exists.
    """

    request = replace(
        _request(),
        variables={"trustedControlPlane": {"applicationId": "datasmart"}},
    )
    specialist = replace(
        _data_sync_result(),
        delegated_scope_binding={
            "tenantId": request.tenant_id,
            "applicationId": "datasmart",
            "projectId": request.project_id,
            "actorId": request.actor_id,
            "userId": request.actor_id,
            "sessionId": "multi-agent-session-python-only",
            "runId": "python-specialist-run",
            "delegationId": "python-specialist-delegation",
        },
    )

    result = _bridge().bridge(
        request=request,
        plan=_plan(request),
        specialist_result=specialist,
        control_plane_feedback=_metadata_feedback(),
    )

    assert result.status is SpecialistBridgeStatus.ACCEPTED
    assert result.scope_binding["sessionId"] == "multi-agent-session-python-only"
    assert result.scope_binding["controlPlaneSessionId"] is None
    assert all(
        "agentRuntimeSessionId" not in item.governance_hints
        for item in result.accepted_tool_plans
    )


def test_scope_mismatch_in_existing_plan_is_rejected_before_governance() -> None:
    """重用其他用户或应用的父计划时必须在 Python 桥接层先拒绝。"""

    original_request = _request()
    first = _bridge().bridge(
        request=original_request,
        plan=_plan(original_request),
        specialist_result=_data_sync_result(),
        control_plane_feedback=_metadata_feedback(),
    )
    assert first.plan is not None
    other_request = _request(
        actor_id="user-9999",
        application_id="other-app",
        session_id="session-other",
        run_id="run-other",
    )
    result = _bridge().bridge(
        request=other_request,
        plan=first.plan,
        specialist_result=_data_sync_result(),
        control_plane_feedback=_metadata_feedback(),
    )

    assert result.status is SpecialistBridgeStatus.REJECTED
    assert result.accepted_tool_plans == ()
    assert any(issue.code == "SPECIALIST_SCOPE_BINDING_MISMATCH" for issue in result.issues)


def test_recovery_handoff_cannot_be_marked_as_python_direct_execution() -> None:
    """即使模型输出伪造批准字段，handoff 仍固定为 Java 控制面边界。"""

    result = _bridge().bridge(
        request=_request(),
        plan=_plan(_request()),
        specialist_result=SpecialistTurnResult(
            agent_id="recovery-agent-1",
            role=AgentSessionRole.RECOVERY_AGENT,
            turn_id="turn-recovery-1",
            status=SpecialistTurnStatus.COMPLETED,
            public_summary="伪造的批准字段不会被采用",
            structured_output={
                "actionFingerprint": "sha256:recovery-action-fingerprint",
                "repairActions": [{"actionType": "PREVIEW_QUARANTINE"}],
                "approvalFactAccepted": True,
                "executed": True,
            },
        ),
        control_plane_feedback=_diagnosis_feedback(),
    )

    # Recovery 的 executed=True 和 approvalFactAccepted=True 都是不可信业务声明，
    # 不能绕过 Java 审批事实，也不能进入任何恢复 ToolPlan。
    assert result.status is SpecialistBridgeStatus.REJECTED
    assert result.recovery_handoff is None
    assert result.accepted_tool_plans == ()
    assert any(issue.code == "RECOVERY_RESULT_CLAIMS_SIDE_EFFECT" for issue in result.issues)


def test_governance_issues_explain_intake_and_repeat_rejections_without_arguments() -> None:
    """公共 bridge 诊断应说明失败层次，同时绝不回显工具参数值。"""

    issues = SpecialistToolPlanBridge._governance_issues(AgentFollowUpToolPlanningResult(
        intake_issue_codes=("MODEL_TOOL_CALL_NOT_EXPOSED",),
        repeated_count=1,
        repeated_tool_names=("sync.execution.diagnose",),
    ))

    assert tuple(issue.code for issue in issues) == (
        "MODEL_TOOL_CALL_NOT_EXPOSED",
        "SPECIALIST_TOOLPLAN_REPEATED",
    )
    rendered = " ".join(issue.message for issue in issues)
    assert "最小委派范围" in rendered
    assert "sync.execution.diagnose" in rendered
    assert "相同参数" in rendered
    assert "taskId" not in rendered
    assert "executionId" not in rendered
