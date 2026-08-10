"""六个 Specialist Agent 的受治理闭环黑盒契约测试。

本文件刻意不测试某一个 Agent 的私有辅助函数，而是从公开合同拼出一条接近生产的链路：

* Coordinator 实际调度 KNOWLEDGE、DATASOURCE、DATA_SYNC、PRECHECK、RECOVERY、MONITOR；
* 上游结果只能通过 dependencyResults 低敏 handoff 进入下游；
* DATA_SYNC 的结果仍然是 draft，必须经过 SpecialistToolPlanBridge 和既有
  AgentFollowUpToolPlanner 才能形成 Java Durable ToolPlan；
* Recovery 只能根据诊断事实和 RAG/案例证据提出需要审批的动作；
* Java 成功回执中的 taskId/executionId 经过信任边界后，才能触发 PRECHECK/MONITOR 复核；
* 租户、项目、用户、delegation、工具白名单和 checkpoint 任一不满足时都 fail-closed；
* 专业 Agent 过程事件只能以低敏投影进入统一 replay envelope。

测试只在 RAG、数据源目录、元数据、预检查、失败诊断、监控和 Java feedback 这些外部边界使用
确定性替身。替身没有写数据库、执行任务、修改表或恢复数据的方法，因此如果本文件通过，证明的是
真实编排/治理代码完成了契约闭环，而不是 Python 测试替身替系统完成了业务动作。
"""

from __future__ import annotations

import sys
import unittest
from dataclasses import replace
from pathlib import Path
from typing import Any, Mapping


SRC_ROOT = Path(__file__).resolve().parents[1] / "src"
if str(SRC_ROOT) not in sys.path:
    sys.path.insert(0, str(SRC_ROOT))


from datasmart_ai_runtime.api import build_event_replay_response
from datasmart_ai_runtime.api.agent.post_bridge_finalization import (
    control_plane_resource_fingerprint,
    run_post_bridge_verification_wave,
)
from datasmart_ai_runtime.config import default_tool_registry
from datasmart_ai_runtime.domain.contracts import AgentPlan, AgentRequest, ToolPlan
from datasmart_ai_runtime.domain.event_transport import RuntimeEventSubscriptionRequest
from datasmart_ai_runtime.domain.events import (
    AgentRuntimeEvent,
    AgentRuntimeEventSeverity,
    AgentRuntimeEventType,
)
from datasmart_ai_runtime.domain.intent import GovernanceDomain, IntentAnalysis
from datasmart_ai_runtime.services.agent_control_plane_feedback import (
    AgentControlPlaneFeedbackItem,
    AgentControlPlaneFeedbackSnapshot,
)
from datasmart_ai_runtime.services.agent_follow_up_tool_planner import AgentFollowUpToolPlanner
from datasmart_ai_runtime.services.agent_gateway.session_models import AgentSessionRole
from datasmart_ai_runtime.services.model_gateway.model_tool_result_feedback import (
    ToolExecutionFeedbackStatus,
)
from datasmart_ai_runtime.services.multi_agent.specialist_contracts import (
    SpecialistDelegationScope,
    SpecialistTurnRequest,
    SpecialistTurnResult,
    SpecialistTurnStatus,
)
from datasmart_ai_runtime.services.multi_agent.specialist_coordinator import SpecialistAgentCoordinator
from datasmart_ai_runtime.services.multi_agent.specialist_events import build_specialist_runtime_events
from datasmart_ai_runtime.services.multi_agent.specialist_toolplan_bridge import (
    SpecialistBridgeStatus,
    SpecialistToolPlanBridge,
)
from datasmart_ai_runtime.services.multi_agent.specialist_registry import SpecialistAgentRegistry
from datasmart_ai_runtime.services.multi_agent.specialists.data_sync_agent import (
    DataSyncSpecialistAgent,
    SyncMetadataDiscoveryRequest,
    SyncMetadataDiscoveryResult,
    SyncPlanningModelInput,
    SyncPlanningModelOutput,
)
from datasmart_ai_runtime.services.multi_agent.specialists.datasource_agent import (
    DatasourceCandidate,
    DatasourceDirection,
    DatasourceDiscoveryRequest,
    DatasourceDiscoveryResult,
    DatasourceSpecialistAgent,
)
from datasmart_ai_runtime.services.multi_agent.specialists.knowledge_agent import KnowledgeSpecialistAgent
from datasmart_ai_runtime.services.multi_agent.specialists.monitor_agent import (
    MonitorSpecialistAgent,
    MonitoringModelInput,
    MonitoringModelOutput,
    TaskMonitoringSnapshot,
)
from datasmart_ai_runtime.services.multi_agent.specialists.precheck_agent import (
    PrecheckControlPlaneRequest,
    PrecheckControlPlaneResult,
    PrecheckExplanationModelInput,
    PrecheckExplanationModelOutput,
    PrecheckSpecialistAgent,
)
from datasmart_ai_runtime.services.multi_agent.specialists.recovery_agent import (
    FailureDiagnosticRequest,
    FailureDiagnosticResult,
    RecoveryPlanningModelInput,
    RecoveryPlanningModelOutput,
    RecoverySpecialistAgent,
)
from datasmart_ai_runtime.services.rag import RagCitation, RagPipelineResult
from datasmart_ai_runtime.services.tool_planner import ToolPlanner


class _RecordingRagPipeline:
    """RAG 外部边界替身：记录授权查询并返回带 citation 的真实管线结果。"""

    def __init__(self) -> None:
        """准备查询记录；正文标记只用于验证事件脱敏，不能被 Agent 过程事件带出。"""

        self.queries: list[Any] = []

    def answer(self, query: Any) -> RagPipelineResult:
        """模拟 RAG 管线的公开 answer 入口，而不是在测试里直接伪造知识 Agent 结果。"""

        self.queries.append(query)
        return RagPipelineResult(
            answer="历史案例显示应先读取失败事实，再由受治理重试工具处理。 [C1]",
            citations=(
                RagCitation(
                    citation_id="C1",
                    document_id="case-retry-001",
                    chunk_id="chunk-7",
                    title="同步失败重试案例",
                    source_uri="knowledge://cases/retry-001",
                    # 这个正文不应进入 specialist runtime event 或 replay envelope。
                    snippet="RAG_SECRET_SNIPPET: 原始事故正文和敏感样本不得进入低敏事件。",
                    final_score=0.96,
                ),
            ),
            selected_chunks=(),
            compressed_context="RAG_SECRET_COMPRESSED_CONTEXT",
            retrieval_summary={
                "candidateCount": 4,
                "evidenceAcceptedCount": 1,
                "selectedCount": 1,
            },
            model_summary={
                "actualModelName": "knowledge-test-model",
                "providerInvoked": True,
                "providerSucceeded": True,
                "promptTokens": 80,
                "completionTokens": 24,
                # 验证模型原始 prompt 不会进入专业 Agent 事件。
                "rawPrompt": "RAG_SECRET_RAW_PROMPT",
            },
            generated=True,
        )


class _RecordingDatasourceDiscovery:
    """数据源目录外部边界替身：只返回已经按权限过滤的低敏候选。"""

    def __init__(self) -> None:
        """保存每次发现请求，后续断言租户、项目、用户和 delegation 没有丢失。"""

        self.requests: list[DatasourceDiscoveryRequest] = []

    def discover(self, request: DatasourceDiscoveryRequest) -> DatasourceDiscoveryResult:
        """依据方向返回唯一候选，确保 Agent 不需要随机选择或猜测数据源 ID。"""

        self.requests.append(request)
        if request.direction is DatasourceDirection.SOURCE:
            candidates = (
                DatasourceCandidate(
                    datasource_id="27",
                    name="FlashSync MySQL 源",
                    connector_type="MYSQL",
                    supported_directions=(DatasourceDirection.SOURCE,),
                    display_status="可用",
                ),
            )
        else:
            candidates = (
                DatasourceCandidate(
                    datasource_id="28",
                    name="FlashSync PostgreSQL 目标",
                    connector_type="POSTGRESQL",
                    supported_directions=(DatasourceDirection.TARGET,),
                    display_status="可用",
                ),
            )
        return DatasourceDiscoveryResult(
            candidates=candidates,
            evidence_reference=f"datasource://catalog/{request.direction.value.lower()}/audit-1",
        )


class _RecordingSyncMetadata:
    """同步元数据外部边界替身：提供两端真实表和字段，不提供任何写方法。"""

    def __init__(self) -> None:
        """记录源端和目标端元数据查询，以验证 DATA_SYNC 的项目范围透传。"""

        self.requests: list[SyncMetadataDiscoveryRequest] = []

    def discover(self, request: SyncMetadataDiscoveryRequest) -> SyncMetadataDiscoveryResult:
        """返回同名 customer 表的低敏结构，模拟 data-sync 元数据控制面。"""

        self.requests.append(request)
        schema_name = None if request.side == "SOURCE" else "public"
        return SyncMetadataDiscoveryResult(
            datasource_id=request.datasource_id,
            side=request.side,
            connector_type=request.connector_type,
            metadata={
                "objects": [
                    {
                        "schemaName": schema_name,
                        "tableName": "customer",
                        "columns": [
                            {"columnName": "id", "dataTypeName": "BIGINT"},
                            {"columnName": "name", "dataTypeName": "VARCHAR"},
                        ],
                    }
                ],
                "datasourceId": request.datasource_id,
            },
            object_count=1,
            field_count=2,
            evidence_reference=f"metadata://{request.side.lower()}/audit-{request.datasource_id}",
        )


class _RecordingSyncPlanner:
    """DATA_SYNC 模型边界替身：只返回配置建议，绝不保存或执行任务。"""

    def __init__(self) -> None:
        """记录模型输入，验证它收到的是 DATASOURCE handoff 和低敏元数据上下文。"""

        self.requests: list[SyncPlanningModelInput] = []

    def plan(self, request: SyncPlanningModelInput) -> SyncPlanningModelOutput:
        """返回完整的全量同名表规划，字段映射交给真实 Agent 按元数据推断。"""

        self.requests.append(request)
        return SyncPlanningModelOutput(
            configuration={
                "taskName": "customer-full-closed-loop",
                "sourceDatasourceId": 27,
                "targetDatasourceId": 28,
                "syncMode": "FULL",
                "writeStrategy": "INSERT",
                "objectMappings": [
                    {
                        "sourceObjectName": "customer",
                        "targetSchemaName": "public",
                        "targetObjectName": "customer",
                        # 不填写 fieldMappings，证明默认同名映射来自真实元数据而不是 Python 直写任务。
                    }
                ],
            },
            public_summary="已依据两端元数据生成同步配置草案。",
            invocation_summary={
                "providerInvoked": True,
                "providerSucceeded": True,
                "providerName": "test-provider",
                "modelName": "sync-planner-test-model",
                "latencyMs": 12,
                "rawPrompt": "SYNC_SECRET_RAW_PROMPT",
            },
        )


class _RecordingPrecheckClient:
    """预检查控制面替身：返回确定性检查事实，不能保存、发布或执行任务。"""

    def __init__(self, *, passed: bool = True) -> None:
        """保存是否通过的开关和每次受控预检查请求。"""

        self.passed = passed
        self.requests: list[PrecheckControlPlaneRequest] = []

    def precheck(self, request: PrecheckControlPlaneRequest) -> PrecheckControlPlaneResult:
        """返回低敏检查项；完整任务配置只留在控制面请求，不进入模型事件。"""

        self.requests.append(request)
        status = "PASSED" if self.passed else "FAILED"
        return PrecheckControlPlaneResult(
            status=status,
            task_id=request.task_id or "701",
            can_start_execution=self.passed,
            checks=(
                {
                    "code": "TARGET_OBJECT_EXISTS",
                    "status": status,
                    "problem": "目标表已由控制面确认存在。" if self.passed else "目标表不存在。",
                    "suggestion": "可以进入下一阶段。" if self.passed else "返回对象映射重新选择目标表。",
                    "detailsReference": "precheck://701/target-object",
                },
                {
                    "code": "FIELD_MAPPING_COMPATIBLE",
                    "status": status,
                    "problem": "字段映射和类型兼容性已确认。" if self.passed else "字段映射存在冲突。",
                    "suggestion": "保持当前映射。" if self.passed else "返回字段映射步骤修正字段。",
                    "detailsReference": "precheck://701/fields",
                },
            ),
            details_references=("precheck://701/summary",),
            invocation_summary={"providerName": "sync-control-plane", "providerSucceeded": True},
        )


class _RecordingPrecheckModel:
    """预检查解释模型替身，只解释后端事实，不生成检查结论。"""

    def __init__(self) -> None:
        """记录模型输入，用于断言没有把任务配置或原始模型提示词交给解释模型。"""

        self.requests: list[PrecheckExplanationModelInput] = []

    def explain(self, request: PrecheckExplanationModelInput) -> PrecheckExplanationModelOutput:
        """返回一段不改变检查状态的用户说明。"""

        self.requests.append(request)
        return PrecheckExplanationModelOutput(
            public_summary="预检查结果已整理，最终状态以控制面事实为准。",
            invocation_summary={
                "modelName": "precheck-explainer-test-model",
                "providerInvoked": True,
                "providerSucceeded": True,
                "rawPrompt": "PRECHECK_SECRET_RAW_PROMPT",
            },
        )


class _RecordingFailureDiagnostic:
    """失败诊断外部边界替身：提供真实 execution 的低敏失败事实。"""

    def __init__(self) -> None:
        """保存所有诊断请求，验证 Recovery 使用 task/execution 受控定位。"""

        self.requests: list[FailureDiagnosticRequest] = []

    def diagnose(self, request: FailureDiagnosticRequest) -> FailureDiagnosticResult:
        """返回可重试失败事实和审计引用，不提供任何恢复执行器。"""

        self.requests.append(request)
        return FailureDiagnosticResult(
            failure_code="SYNC_DIRTY_RECORDS",
            failure_reason="同步执行发现部分记录不符合目标约束。",
            facts={"failedStage": "WRITE", "retryable": True, "failedObjectCount": 2},
            log_references=("log://execution/9001",),
            evidence_references=("failure://execution/9001",),
            log_summary={"entryCount": 5, "level": "ERROR"},
        )


class _RecordingRecoveryPlanner:
    """Recovery 模型边界替身：用 RAG/案例证据提出需审批的重试动作。"""

    def __init__(self) -> None:
        """记录模型输入，验证案例和知识摘要确实完成 handoff。"""

        self.requests: list[RecoveryPlanningModelInput] = []

    def plan(self, request: RecoveryPlanningModelInput) -> RecoveryPlanningModelOutput:
        """只返回动作建议，不返回 execute、approvalFact 或任何直接执行字段。"""

        self.requests.append(request)
        return RecoveryPlanningModelOutput(
            actions=(
                {
                    "actionId": "retry-failed-objects-1",
                    "actionType": "RETRY_FAILED_OBJECTS",
                    "toolName": "sync.execution.failed-objects.retry",
                    "reason": "诊断和历史案例都表明失败对象可重试。",
                },
            ),
            public_summary="已根据失败事实和历史案例提出重试建议。",
            next_step="请审批后由 Java 控制面提交受治理重试。",
            invocation_summary={
                "modelName": "recovery-test-model",
                "providerInvoked": True,
                "providerSucceeded": True,
                "rawPrompt": "RECOVERY_SECRET_RAW_PROMPT",
            },
        )


class _RecordingMonitorClient:
    """监控外部边界替身：返回任务状态、进度和失败计数等聚合事实。"""

    def __init__(self, *, status: str = "FAILED") -> None:
        """保存状态和查询记录，接口本身没有停止、重试或修改能力。"""

        self.status = status
        self.queries: list[Any] = []

    def get_snapshot(self, query: Any) -> TaskMonitoringSnapshot:
        """返回一个可用于长任务/失败任务观察的低敏快照。"""

        self.queries.append(query)
        return TaskMonitoringSnapshot(
            task_id=query.task_id,
            status=self.status,
            task_kind="LONG_RUNNING",
            phase="WRITE",
            rows_total=100,
            rows_processed=40,
            success_count=38,
            failure_count=2,
            throughput_rows_per_second=20.0,
            latency_ms=40.0,
            heartbeat_age_seconds=3.0,
            heartbeat_present=True,
            captured_at="2026-08-05T12:00:00Z",
            tenant_id=query.tenant_id,
            project_id=query.project_id,
            actor_id=query.actor_id,
            delegation_id=query.delegation_id,
        )


class _RecordingMonitorModel:
    """监控总结模型替身，只生成解释文字，不改变快照状态。"""

    def __init__(self) -> None:
        """记录模型输入，用于验证状态和进度仍由监控事实计算。"""

        self.requests: list[MonitoringModelInput] = []

    def summarize(self, request: MonitoringModelInput) -> MonitoringModelOutput:
        """返回低敏建议，不返回 status/progress/health 等事实字段。"""

        self.requests.append(request)
        return MonitoringModelOutput(
            public_summary="监控发现本次执行存在失败对象，建议进入受治理恢复流程。",
            recommended_actions=("查看失败对象并等待 Recovery 审批。",),
            invocation_summary={
                "modelName": "monitor-test-model",
                "providerInvoked": True,
                "providerSucceeded": True,
                "rawPrompt": "MONITOR_SECRET_RAW_PROMPT",
            },
        )


def _request() -> AgentRequest:
    """构造所有测试复用的可信请求，应用标识只放在 trustedControlPlane。"""

    return AgentRequest(
        tenant_id="tenant-10",
        project_id="project-101",
        actor_id="ordinary-user-7",
        objective=(
            "将 MySQL 中的 customer 全量同步到 PostgreSQL public schema 的同名表，"
            "失败后根据案例提出受治理恢复建议。"
        ),
        request_id="request-six-agent-1",
        variables={
            "trustedControlPlane": {"applicationId": "datasmart-govern"},
            "agentRuntimeSessionId": "session-six-agent-1",
            "agentRuntimeRunId": "run-six-agent-1",
        },
    )


def _turn_runner() -> dict[str, Any]:
    """构造六个真实 turn attempt；状态必须是 Coordinator 认可的可执行状态。"""

    return {
        "maxConcurrentAgentTurns": 2,
        "turnAttempts": tuple(
            {
                "turnId": f"turn-{role.lower()}",
                "agentRole": role,
                "turnStatus": "READY_FOR_SPECIALIST_TURN",
            }
            for role in (
                "KNOWLEDGE_AGENT",
                "DATASOURCE_AGENT",
                "DATA_SYNC_AGENT",
                "PRECHECK_AGENT",
                "RECOVERY_AGENT",
                "MONITOR_AGENT",
            )
        ),
    }


def _execution_session() -> dict[str, Any]:
    """构造明确的五波次依赖图，避免测试只验证注册表存在而没有验证 handoff。"""

    return {
        "sessionId": "session-six-agent-1",
        "runId": "run-six-agent-1",
        "workItems": (
            {"agentRole": "KNOWLEDGE_AGENT", "dependsOnRoles": ("MASTER_ORCHESTRATOR",)},
            {"agentRole": "DATASOURCE_AGENT", "dependsOnRoles": ("MASTER_ORCHESTRATOR", "KNOWLEDGE_AGENT")},
            {"agentRole": "DATA_SYNC_AGENT", "dependsOnRoles": ("MASTER_ORCHESTRATOR", "DATASOURCE_AGENT")},
            {"agentRole": "PRECHECK_AGENT", "dependsOnRoles": ("MASTER_ORCHESTRATOR", "DATA_SYNC_AGENT")},
            {"agentRole": "RECOVERY_AGENT", "dependsOnRoles": ("MASTER_ORCHESTRATOR", "PRECHECK_AGENT")},
            {"agentRole": "MONITOR_AGENT", "dependsOnRoles": ("MASTER_ORCHESTRATOR", "PRECHECK_AGENT")},
        ),
    }


def _base_context() -> dict[str, Any]:
    """提供六 Agent 恢复闭环所需的低敏规划、案例和真实失败执行事实。

    该夹具专门服务于“六个 Specialist 全部执行”的恢复合同，因此不能只放一个历史案例或
    taskId。RECOVERY_AGENT 的生产准入要求同一受控 carrier 同时包含 taskId、executionId 和
    明确失败标记，避免把健康执行或尚未落库的规划误诊为故障。普通成功创建链路使用独立夹具，
    不会因为这里的测试数据而触发 Recovery。
    """

    return {
        "source": {"connectorType": "MYSQL", "datasourceName": "FlashSync MySQL 源"},
        "target": {"connectorType": "POSTGRESQL", "datasourceName": "FlashSync PostgreSQL 目标"},
        # 这是恢复合同测试预置的可信失败定位；生产中由 Java 失败回执/控制面事实提供。
        "failureContext": {
            "taskId": "701",
            "executionId": "9001",
            "failureCode": "SYNC_DIRTY_RECORDS",
        },
        "caseEvidence": {
            "failureCode": "SYNC_DIRTY_RECORDS",
            "failedStage": "WRITE",
            "evidenceReferences": ("case://sync-dirty-records-1",),
            "summary": "历史案例建议先诊断失败对象，再审批重试。",
        },
    }


def _all_agent_registry(
    rag: _RecordingRagPipeline,
    discovery: _RecordingDatasourceDiscovery,
    metadata: _RecordingSyncMetadata,
    sync_model: _RecordingSyncPlanner,
    precheck_client: _RecordingPrecheckClient,
    precheck_model: _RecordingPrecheckModel,
    diagnostic: _RecordingFailureDiagnostic,
    recovery_model: _RecordingRecoveryPlanner,
    monitor_client: _RecordingMonitorClient,
    monitor_model: _RecordingMonitorModel,
) -> SpecialistAgentRegistry:
    """用真实六个 Agent 类创建生产形态注册表，只替换它们的外部边界依赖。"""

    return SpecialistAgentRegistry(
        (
            KnowledgeSpecialistAgent(rag),
            DatasourceSpecialistAgent(discovery),
            DataSyncSpecialistAgent(sync_model, metadata_discovery_tool=metadata),
            PrecheckSpecialistAgent(precheck_client, precheck_model),
            RecoverySpecialistAgent(diagnostic, recovery_model),
            MonitorSpecialistAgent(monitor_client, monitor_model),
        )
    )


def _allowed_tools() -> dict[str, tuple[str, ...]]:
    """返回每个 specialist 的最小工具委派白名单，不把主 Agent 写工具下发给专业 Agent。"""

    return {
        "KNOWLEDGE_AGENT": ("knowledge.rag.query",),
        "DATASOURCE_AGENT": ("datasource.discovery.read",),
        "DATA_SYNC_AGENT": (
            "datasource.source.metadata.read",
            "datasource.target.metadata.read",
        ),
        "PRECHECK_AGENT": ("sync.task.precheck",),
        "RECOVERY_AGENT": ("recovery.failure.diagnose",),
        "MONITOR_AGENT": ("task.monitor.read",),
    }


def _parent_plan(request: AgentRequest, *tool_plans: ToolPlan) -> AgentPlan:
    """构造带真实工具注册候选的主 Agent 父计划，供 Bridge 复用现有治理器。"""

    return AgentPlan(
        request_id=request.request_id or "request-six-agent-1",
        selected_route=None,
        state_trace=("receive_goal", "specialist_agent"),
        tool_plans=tuple(tool_plans),
        requires_human_approval=False,
        response_summary="六 Specialist 闭环测试父计划",
        intent_analysis=IntentAnalysis(
            summary="数据同步治理意图",
            governance_domains=(GovernanceDomain.DATA_SYNC,),
            candidate_tools=(
                "sync.task.draft.save",
                "sync.task.precheck",
                "sync.task.publish",
                "sync.task.run",
                "sync.execution.status",
                "sync.execution.diagnose",
                "sync.execution.rag.lookup",
                "sync.execution.failed-objects.retry",
            ),
            confidence=1.0,
        ),
    )


def _feedback_item(
    *,
    tool_name: str,
    result: Mapping[str, Any],
    audit_id: str,
    run_id: str,
    status: ToolExecutionFeedbackStatus = ToolExecutionFeedbackStatus.SUCCEEDED,
    output_ref: str | None = None,
) -> AgentControlPlaneFeedbackItem:
    """构造带审计/运行/输出引用的 Java 控制面反馈条目。"""

    return AgentControlPlaneFeedbackItem(
        model_tool_call_id=f"call-{audit_id}",
        tool_name=tool_name,
        status=status,
        summary=f"{tool_name} 控制面反馈",
        result=dict(result),
        audit_id=audit_id,
        run_id=run_id,
        output_ref=output_ref or f"agent-runtime://tool-results/{audit_id}",
    )


def _feedback(*items: AgentControlPlaneFeedbackItem) -> AgentControlPlaneFeedbackSnapshot:
    """把反馈条目组成既有 Collector 输出快照。"""

    return AgentControlPlaneFeedbackSnapshot(
        expected_tool_call_count=len(items),
        feedback_items=tuple(items),
        missing_tool_call_ids=(),
        status_counts={"succeeded": sum(item.status is ToolExecutionFeedbackStatus.SUCCEEDED for item in items)},
        second_turn_eligible=True,
        recommended_actions=(),
    )


def _metadata_feedback() -> AgentControlPlaneFeedbackSnapshot:
    """构造两端真实元数据成功事实，供 DATA_SYNC Bridge 做确定性映射校验。"""

    source_object = {
        "schemaName": None,
        "tableName": "customer",
        "columns": [
            {"columnName": "id", "dataTypeName": "BIGINT"},
            {"columnName": "name", "dataTypeName": "VARCHAR"},
        ],
    }
    target_object = {
        "schemaName": "public",
        "tableName": "customer",
        "columns": [
            {"columnName": "id", "dataTypeName": "BIGINT"},
            {"columnName": "name", "dataTypeName": "VARCHAR"},
        ],
    }
    return _feedback(
        _feedback_item(
            tool_name="datasource.source.metadata.read",
            result={"datasourceId": 27, "summary": {"datasourceId": 27, "truncated": False, "objects": [source_object]}},
            audit_id="audit-source-metadata",
            run_id="java-metadata-run",
        ),
        _feedback_item(
            tool_name="datasource.target.metadata.read",
            result={"datasourceId": 28, "summary": {"datasourceId": 28, "truncated": False, "objects": [target_object]}},
            audit_id="audit-target-metadata",
            run_id="java-metadata-run",
        ),
    )


def _java_resource_feedback(*, execution_id: int = 9001, run_id: str = "java-run-1") -> AgentControlPlaneFeedbackSnapshot:
    """构造 Java 成功回执；只有这种受绑定 output_ref 才能触发 post-bridge 复核。"""

    output_ref = f"agent-runtime://sessions/session-six-agent-1/runs/{run_id}/tool-executions/audit-draft/result"
    return _feedback(
        _feedback_item(
            tool_name="sync.task.draft.save",
            result={"taskId": 701, "executionId": execution_id},
            audit_id="audit-draft",
            run_id=run_id,
            output_ref=output_ref,
        )
    )


class SixAgentGovernedClosedLoopContractTest(unittest.TestCase):
    """从公开边界验证六 Specialist Agent 的受治理协作闭环。"""

    def test_coordinator_calls_six_agents_in_dependency_waves_and_handoffs_results(self) -> None:
        """证明六个真实 Agent 都被调用，并验证每一波只消费已完成的上游 handoff。

        这里没有用一个“万能假 Agent”替代六个实现，而是把真实类注册到真实 Coordinator。
        这样可以同时检查：第一波知识检索、第二波数据源消歧、第三波元数据驱动同步规划、
        第四波预检查，以及第五波 Recovery/MONITOR 并行执行。每个外部替身都记录收到的
        结构化请求，断言依赖事实确实从上游结果进入下游，而不是只看一个静态角色列表。
        """

        rag = _RecordingRagPipeline()
        discovery = _RecordingDatasourceDiscovery()
        metadata = _RecordingSyncMetadata()
        sync_model = _RecordingSyncPlanner()
        precheck_client = _RecordingPrecheckClient()
        precheck_model = _RecordingPrecheckModel()
        diagnostic = _RecordingFailureDiagnostic()
        recovery_model = _RecordingRecoveryPlanner()
        monitor_client = _RecordingMonitorClient()
        monitor_model = _RecordingMonitorModel()
        registry = _all_agent_registry(
            rag,
            discovery,
            metadata,
            sync_model,
            precheck_client,
            precheck_model,
            diagnostic,
            recovery_model,
            monitor_client,
            monitor_model,
        )
        coordinator = SpecialistAgentCoordinator(registry)
        request = _request()
        events: list[Mapping[str, Any]] = []
        recorded_turns: list[tuple[SpecialistTurnRequest, SpecialistTurnResult]] = []

        # checkpoint_recorded=True 是真实持久 checkpoint 的测试事实；如果改成 False，下面的
        # 六个 Agent 都不能被调用，避免测试把“有计划”误认为“已进入 durable 执行轮”。
        batch = coordinator.run(
            request=request,
            turn_runner=_turn_runner(),
            execution_session=_execution_session(),
            allowed_tools_by_role=_allowed_tools(),
            base_context=_base_context(),
            checkpoint_recorded=True,
            event_sink=events.append,
            result_sink=lambda turn_request, result: recorded_turns.append((turn_request, result)),
        )

        result_by_role = {result.role.value: result for result in batch.results}
        self.assertEqual("COMPLETED", batch.status)
        self.assertEqual(
            {
                "KNOWLEDGE_AGENT",
                "DATASOURCE_AGENT",
                "DATA_SYNC_AGENT",
                "PRECHECK_AGENT",
                "RECOVERY_AGENT",
                "MONITOR_AGENT",
            },
            set(result_by_role),
        )
        self.assertEqual(
            (
                ("KNOWLEDGE_AGENT",),
                ("DATASOURCE_AGENT",),
                ("DATA_SYNC_AGENT",),
                ("PRECHECK_AGENT",),
                ("MONITOR_AGENT", "RECOVERY_AGENT"),
            ),
            batch.execution_waves,
        )

        # DATASOURCE_AGENT 的真实结果必须进入 DATA_SYNC 模型输入；模型不能用“MySQL”文本
        # 自己生成 27/28，ID 只能来自当前项目已授权的目录结果。
        self.assertEqual(2, len(discovery.requests))
        self.assertTrue(all(item.project_id == request.project_id for item in discovery.requests))
        self.assertTrue(all(item.actor_id == request.actor_id for item in discovery.requests))
        self.assertEqual(1, len(sync_model.requests))
        sync_context = sync_model.requests[0].context
        dependency_results = sync_context["dependencyResults"]
        self.assertEqual(
            "COMPLETED",
            dependency_results[AgentSessionRole.DATASOURCE_AGENT.value]["status"],
        )
        self.assertEqual("27", dependency_results[AgentSessionRole.DATASOURCE_AGENT.value]["structuredOutput"]["sourceDatasourceId"])
        self.assertEqual([27, 28], [item.datasource_id for item in metadata.requests])
        self.assertTrue(all(item.project_id == request.project_id for item in metadata.requests))
        self.assertTrue(all(item.scope_level == "PROJECT" for item in metadata.requests))

        sync_result = result_by_role[AgentSessionRole.DATA_SYNC_AGENT.value]
        self.assertEqual(SpecialistTurnStatus.COMPLETED, sync_result.status)
        self.assertTrue(sync_result.structured_output["draftOnly"])
        self.assertFalse(sync_result.structured_output["persisted"])
        self.assertFalse(sync_result.structured_output["published"])
        self.assertFalse(sync_result.structured_output["executed"])
        fields = sync_result.structured_output["objectMappings"][0]["fieldMappings"]
        self.assertEqual(["id", "name"], [item["sourceField"] for item in fields])
        self.assertTrue(all(item["inferred"] for item in fields))

        # PRECHECK 必须接收 DATA_SYNC 的完整结构化配置；Recovery 则必须同时拿到案例事实
        # 和 KNOWLEDGE_AGENT 的 grounded 结果。下面通过真实外部客户端/模型收到的输入验证 handoff，
        # 不读取 Coordinator 的私有属性，也不把某个结果直接塞进下游测试替身。
        self.assertEqual(1, len(precheck_client.requests))
        self.assertEqual("701", precheck_client.requests[0].task_id)
        self.assertEqual("customer-full-closed-loop", precheck_client.requests[0].configuration["taskName"])
        self.assertEqual(1, len(recovery_model.requests))
        self.assertTrue(recovery_model.requests[0].case_evidence)
        self.assertTrue(recovery_model.requests[0].knowledge_summary)
        self.assertTrue(recovery_model.requests[0].evidence_references)
        recovery_result = result_by_role[AgentSessionRole.RECOVERY_AGENT.value]
        self.assertTrue(recovery_result.structured_output["requiresApproval"])
        self.assertTrue(recovery_result.structured_output["javaToolPlanPending"])
        self.assertFalse(recovery_result.structured_output["executed"])
        self.assertTrue(recovery_result.structured_output["approvalRequest"]["required"])
        self.assertEqual(
            "RETRY_FAILED_OBJECTS",
            recovery_result.structured_output["repairActions"][0]["actionType"],
        )

        # MONITOR 的事实必须来自只读客户端，而不是模型臆测；失败的长任务仍然是一次完成的
        # 监控 turn，Recovery/MONITOR 同波次并行不会互相伪造状态。
        monitor_result = result_by_role[AgentSessionRole.MONITOR_AGENT.value]
        self.assertEqual(SpecialistTurnStatus.COMPLETED, monitor_result.status)
        self.assertEqual("FAILED", monitor_result.structured_output["status"])
        self.assertEqual(40.0, monitor_result.structured_output["progressPercent"])
        self.assertTrue(monitor_result.structured_output["readOnly"])
        self.assertFalse(monitor_result.structured_output["sideEffectsPerformed"])
        self.assertEqual(1, len(monitor_client.queries))
        self.assertEqual(request.project_id, monitor_client.queries[0].project_id)

        # 所有实际调用结果都只登记一次；这是 durable specialist fact 的基本审计契约。
        self.assertEqual(6, len(recorded_turns))
        self.assertEqual(
            {role: 1 for role in result_by_role},
            {turn_request.role.value: 1 for turn_request, _ in recorded_turns},
        )

        event_text = str(events)
        self.assertNotIn("RAG_SECRET_SNIPPET", event_text)
        self.assertNotIn("RAG_SECRET_COMPRESSED_CONTEXT", event_text)
        self.assertNotIn("RAW_PROMPT", event_text)
        self.assertNotIn("password", event_text.lower())
        self.assertTrue(events)
        self.assertTrue(all(event.get("payloadPolicy", "").startswith("LOW_SENSITIVE") for event in events))

    def test_bridge_governs_draft_and_recovery_then_java_receipt_reverifies_resources(self) -> None:
        """证明 specialist 输出不会直写业务，而是经 Bridge/Java 回执形成完整治理闭环。

        第一部分把真实 DATA_SYNC 结果交给生产 Bridge，并要求真实两端元数据成功反馈；预期只会
        产生带治理来源的 `sync.task.draft.save` 及平台生命周期 ToolPlan。第二部分把真实 Recovery
        结果交给同一个 Bridge，验证 RAG/诊断证据不足时不能交接，证据完整时才生成需要 Java 审批的
        重试计划。最后使用 post-bridge 验证器消费带绑定的 Java task/execution 回执，实际调用真实
        PRECHECK_AGENT 和 MONITOR_AGENT，并验证相同资源指纹幂等、新 execution 可开启新一轮。
        """

        # 先运行真实 DATA_SYNC，避免手工拼一个看似完整但没有经过元数据校验的结果。
        sync_model = _RecordingSyncPlanner()
        metadata = _RecordingSyncMetadata()
        data_sync_request = SpecialistTurnRequest(
            turn_id="turn-bridge-sync",
            session_id="session-six-agent-1",
            run_id="run-six-agent-1",
            role=AgentSessionRole.DATA_SYNC_AGENT,
            objective=_request().objective,
            scope=SpecialistDelegationScope(
                tenant_id="tenant-10",
                application_id="datasmart-govern",
                project_id="project-101",
                actor_id="ordinary-user-7",
                delegation_id="delegation-bridge-sync",
                allowed_tool_names=(
                    "datasource.source.metadata.read",
                    "datasource.target.metadata.read",
                ),
            ),
            context_summary={
                "sourceDatasourceId": 27,
                "targetDatasourceId": 28,
                "sourceConnectorType": "MYSQL",
                "targetConnectorType": "POSTGRESQL",
            },
        )
        sync_result = DataSyncSpecialistAgent(
            sync_model,
            metadata_discovery_tool=metadata,
        ).execute(data_sync_request)
        self.assertEqual(SpecialistTurnStatus.COMPLETED, sync_result.status)
        # 即使没有把 DATASOURCE_AGENT 结果直接塞进这个独立请求，可信数据源 ID 仍足以让真实
        # DATA_SYNC_AGENT 通过只读元数据工具补齐事实；用 Coordinator 结果作为 Bridge 输入，
        # 证明下方 Bridge 接收的是已经经过元数据校验的完整草案。
        sync_result = self._run_data_sync_result_for_bridge()
        request = _request()
        parent = _parent_plan(request)
        # Bridge 构造器要求 ToolPlanner 与 follow-up planner 来自同一注册表实例；真实实现中这
        # 个身份一致性很重要，因此只创建一个 planner 并把同一实例注入两个治理层。
        planner = ToolPlanner(default_tool_registry())
        bridge = SpecialistToolPlanBridge(
            tool_planner=planner,
            follow_up_tool_planner=AgentFollowUpToolPlanner(tool_planner=planner),
        )
        data_sync_bridge = bridge.bridge(
            request=request,
            plan=parent,
            specialist_result=sync_result,
            control_plane_feedback=_metadata_feedback(),
        )
        self.assertIs(SpecialistBridgeStatus.ACCEPTED, data_sync_bridge.status)
        self.assertTrue(data_sync_bridge.accepted_tool_plans)
        self.assertEqual("sync.task.draft.save", data_sync_bridge.accepted_tool_plans[0].tool_name)
        self.assertTrue(
            all(item.governance_hints.get("specialistBridgeSource") == "specialist_result_bridge"
                for item in data_sync_bridge.accepted_tool_plans)
        )
        self.assertTrue(
            all(item.governance_hints.get("specialistAgentRole") == "DATA_SYNC_AGENT"
                for item in data_sync_bridge.accepted_tool_plans)
        )
        self.assertFalse(sync_result.structured_output["persisted"])
        self.assertFalse(sync_result.structured_output["executed"])
        self.assertFalse(hasattr(bridge, "execute"))

        # Recovery 结果必须有诊断和 RAG 成功反馈；只有模型写出的 evidenceReferences 不能替代
        # Java 控制面事实，否则模型可以伪造“已经查过案例”来解锁高风险动作。
        recovery_diagnostic = _RecordingFailureDiagnostic()
        recovery_model = _RecordingRecoveryPlanner()
        recovery_request = SpecialistTurnRequest(
            turn_id="turn-bridge-recovery",
            session_id="session-six-agent-1",
            run_id="run-six-agent-1",
            role=AgentSessionRole.RECOVERY_AGENT,
            objective="根据失败日志和历史案例重试失败对象",
            scope=SpecialistDelegationScope(
                tenant_id="tenant-10",
                application_id="datasmart-govern",
                project_id="project-101",
                actor_id="ordinary-user-7",
                delegation_id="delegation-bridge-recovery",
                allowed_tool_names=("recovery.failure.diagnose",),
            ),
            context_summary={
                "taskId": 701,
                "executionId": 9001,
                "caseEvidence": {"summary": "失败对象可重试", "evidenceReferences": ("case://1",)},
                "knowledgeSummary": {"grounded": True, "citations": ({"documentId": "case", "chunkId": "1"},)},
            },
        )
        recovery_result = RecoverySpecialistAgent(recovery_diagnostic, recovery_model).execute(recovery_request)
        recovery_bridge_request = replace(
            request,
            variables={
                **request.variables,
                "trustedControlPlane": {
                    **request.variables["trustedControlPlane"],
                    # Bridge 的 delegation 绑定来自可信请求上下文；这里让独立构造的 Recovery
                    # turn 与同一条主 Agent 运行链使用同一个委派事实。
                    "delegationId": "delegation-bridge-recovery",
                },
            },
        )
        recovery_plan = bridge.bridge(
            request=recovery_bridge_request,
            plan=_parent_plan(recovery_bridge_request),
            specialist_result=recovery_result,
            control_plane_feedback=_feedback(
                _feedback_item(
                    tool_name="sync.execution.diagnose",
                    result={"failureCode": "SYNC_DIRTY_RECORDS"},
                    audit_id="audit-diagnosis",
                    run_id="java-recovery-run",
                ),
                _feedback_item(
                    tool_name="sync.execution.rag.lookup",
                    result={"evidenceCount": 1},
                    audit_id="audit-rag",
                    run_id="java-recovery-run",
                ),
            ),
        )
        self.assertIs(SpecialistBridgeStatus.ACCEPTED, recovery_plan.status)
        self.assertEqual(("sync.execution.failed-objects.retry",), tuple(item.tool_name for item in recovery_plan.accepted_tool_plans))
        self.assertTrue(recovery_plan.accepted_tool_plans[0].requires_human_approval)
        self.assertIsNotNone(recovery_plan.recovery_handoff)
        self.assertFalse(recovery_plan.recovery_handoff.direct_execution)
        self.assertEqual("JAVA_AGENT_RUNTIME_INGESTION_OUTBOX", recovery_plan.recovery_handoff.execution_boundary)

        # Java 回执必须同时包含受控工具名、成功状态、audit/run 和 agent-runtime session 引用；
        # post-bridge 只信任这条边界，不接受模型正文中的 taskId/executionId。
        verification_precheck_client = _RecordingPrecheckClient()
        verification_precheck_model = _RecordingPrecheckModel()
        verification_monitor_client = _RecordingMonitorClient(status="RUNNING")
        verification_monitor_model = _RecordingMonitorModel()
        verification_registry = SpecialistAgentRegistry(
            (
                PrecheckSpecialistAgent(verification_precheck_client, verification_precheck_model),
                MonitorSpecialistAgent(verification_monitor_client, verification_monitor_model),
            )
        )
        verification_coordinator = SpecialistAgentCoordinator(verification_registry)
        resource_feedback = _java_resource_feedback(execution_id=9001, run_id="java-run-1")
        first_fingerprint = control_plane_resource_fingerprint(resource_feedback)
        verification_events: list[Mapping[str, Any]] = []
        first_batch, first_summary = run_post_bridge_verification_wave(
            request=request,
            plan=data_sync_bridge.plan or parent,
            control_plane_feedback=resource_feedback,
            previous_resource_fingerprint=None,
            specialist_agent_coordinator=verification_coordinator,
            specialist_allowed_tools_by_role={
                "PRECHECK_AGENT": ("sync.task.precheck",),
                "MONITOR_AGENT": ("task.monitor.read",),
            },
            checkpoint_recorded=True,
            event_sink=verification_events.append,
            base_context={"base": "java-receipt"},
            execution_session={"sessionId": "session-six-agent-1", "runId": "python-run", "workItems": ()},
        )
        self.assertIsNotNone(first_fingerprint)
        self.assertIsNotNone(first_batch)
        self.assertEqual("EXECUTED", first_summary["status"])
        self.assertEqual("701", first_summary["taskId"])
        self.assertEqual("9001", first_summary["executionId"])
        self.assertEqual({"PRECHECK_AGENT", "MONITOR_AGENT"}, {item.role.value for item in first_batch.results})
        self.assertEqual(1, len(verification_precheck_client.requests))
        self.assertEqual(1, len(verification_monitor_client.queries))

        same_batch, same_summary = run_post_bridge_verification_wave(
            request=request,
            plan=data_sync_bridge.plan or parent,
            control_plane_feedback=resource_feedback,
            previous_resource_fingerprint=first_fingerprint,
            specialist_agent_coordinator=verification_coordinator,
            specialist_allowed_tools_by_role={
                "PRECHECK_AGENT": ("sync.task.precheck",),
                "MONITOR_AGENT": ("task.monitor.read",),
            },
            checkpoint_recorded=True,
            event_sink=verification_events.append,
            base_context={"base": "java-receipt"},
            execution_session={"sessionId": "session-six-agent-1", "runId": "python-run", "workItems": ()},
        )
        self.assertIsNone(same_batch)
        self.assertEqual("SKIPPED_RESOURCE_FACT_UNCHANGED", same_summary["status"])
        self.assertEqual(1, len(verification_precheck_client.requests))
        self.assertEqual(1, len(verification_monitor_client.queries))

        # 同一 task 的新 execution 是新的可观察资源事实，应该触发下一轮只读复核，而不是被旧指纹
        # 永久去重；这覆盖长任务、定期任务和实时任务持续观察所需的增量语义。
        next_feedback = _java_resource_feedback(execution_id=9002, run_id="java-run-2")
        next_batch, next_summary = run_post_bridge_verification_wave(
            request=request,
            plan=data_sync_bridge.plan or parent,
            control_plane_feedback=next_feedback,
            previous_resource_fingerprint=first_fingerprint,
            specialist_agent_coordinator=verification_coordinator,
            specialist_allowed_tools_by_role={
                "PRECHECK_AGENT": ("sync.task.precheck",),
                "MONITOR_AGENT": ("task.monitor.read",),
            },
            checkpoint_recorded=True,
            event_sink=verification_events.append,
            base_context={"base": "java-receipt"},
            execution_session={"sessionId": "session-six-agent-1", "runId": "python-run", "workItems": ()},
        )
        self.assertIsNotNone(next_batch)
        self.assertEqual("EXECUTED", next_summary["status"])
        self.assertEqual("9002", next_summary["executionId"])
        self.assertEqual(2, len(verification_precheck_client.requests))
        self.assertEqual(2, len(verification_monitor_client.queries))

    def _run_data_sync_result_for_bridge(self) -> SpecialistTurnResult:
        """通过真实 Coordinator 生成一份可供 Bridge 使用的 DATA_SYNC 草案结果。"""

        rag = _RecordingRagPipeline()
        discovery = _RecordingDatasourceDiscovery()
        metadata = _RecordingSyncMetadata()
        sync_model = _RecordingSyncPlanner()
        precheck_client = _RecordingPrecheckClient()
        precheck_model = _RecordingPrecheckModel()
        diagnostic = _RecordingFailureDiagnostic()
        recovery_model = _RecordingRecoveryPlanner()
        monitor_client = _RecordingMonitorClient()
        monitor_model = _RecordingMonitorModel()
        coordinator = SpecialistAgentCoordinator(
            _all_agent_registry(
                rag,
                discovery,
                metadata,
                sync_model,
                precheck_client,
                precheck_model,
                diagnostic,
                recovery_model,
                monitor_client,
                monitor_model,
            )
        )
        batch = coordinator.run(
            request=_request(),
            turn_runner={
                "maxConcurrentAgentTurns": 1,
                "turnAttempts": (
                    {"turnId": "turn-knowledge-bridge", "agentRole": "KNOWLEDGE_AGENT", "turnStatus": "READY_FOR_SPECIALIST_TURN"},
                    {"turnId": "turn-datasource-bridge", "agentRole": "DATASOURCE_AGENT", "turnStatus": "READY_FOR_SPECIALIST_TURN"},
                    {"turnId": "turn-sync-bridge", "agentRole": "DATA_SYNC_AGENT", "turnStatus": "READY_FOR_SPECIALIST_TURN"},
                ),
            },
            execution_session={
                "sessionId": "session-six-agent-1",
                "runId": "run-six-agent-1",
                "workItems": (
                    {"agentRole": "KNOWLEDGE_AGENT", "dependsOnRoles": ("MASTER_ORCHESTRATOR",)},
                    {"agentRole": "DATASOURCE_AGENT", "dependsOnRoles": ("MASTER_ORCHESTRATOR", "KNOWLEDGE_AGENT")},
                    {"agentRole": "DATA_SYNC_AGENT", "dependsOnRoles": ("MASTER_ORCHESTRATOR", "DATASOURCE_AGENT")},
                ),
            },
            allowed_tools_by_role={
                "KNOWLEDGE_AGENT": ("knowledge.rag.query",),
                "DATASOURCE_AGENT": ("datasource.discovery.read",),
                "DATA_SYNC_AGENT": (
                    "datasource.source.metadata.read",
                    "datasource.target.metadata.read",
                ),
            },
            base_context=_base_context(),
            checkpoint_recorded=True,
        )
        self.assertEqual("COMPLETED", batch.status)
        return next(item for item in batch.results if item.role is AgentSessionRole.DATA_SYNC_AGENT)

    def test_permission_checkpoint_and_runtime_event_replay_fail_closed(self) -> None:
        """验证缺 checkpoint、缺工具、跨项目上下文和事件 replay 的安全边界。

        这一组断言专门覆盖“前端看得到过程”不能以牺牲权限为代价的要求：没有 durable checkpoint
        时 Coordinator 不调用任何 specialist；没有工具白名单时实际 Agent 不访问外部边界；
        Monitor 发现上下文项目和 delegation 项目不一致时在查询前失败；最后把真实 specialist
        sink 事件转换为统一 replay envelope，确认前端可以按 session/tenant/project/actor 重放，
        但拿不到 prompt、SQL、凭据或原始模型正文。
        """

        rag = _RecordingRagPipeline()
        registry = SpecialistAgentRegistry((KnowledgeSpecialistAgent(rag),))
        coordinator = SpecialistAgentCoordinator(registry)
        blocked = coordinator.run(
            request=_request(),
            turn_runner={
                "maxConcurrentAgentTurns": 1,
                "turnAttempts": (
                    {"turnId": "turn-blocked", "agentRole": "KNOWLEDGE_AGENT", "turnStatus": "READY_FOR_SPECIALIST_TURN"},
                ),
            },
            execution_session={"sessionId": "session-six-agent-1", "runId": "run-six-agent-1", "workItems": ()},
            allowed_tools_by_role={"KNOWLEDGE_AGENT": ("knowledge.rag.query",)},
            checkpoint_recorded=False,
        )
        self.assertEqual("BLOCKED_CHECKPOINT_REQUIRED", blocked.status)
        self.assertEqual([], rag.queries)

        # 只给 Knowledge Agent 一个错误工具名时，真实 Agent 必须在调用 RAG 前拒绝；这证明
        # Coordinator 的角色白名单和 specialist 自身白名单是两道独立闸门。
        request = _request()
        direct_knowledge_request = SpecialistTurnRequest(
            turn_id="turn-denied-knowledge",
            session_id="session-six-agent-1",
            run_id="run-six-agent-1",
            role=AgentSessionRole.KNOWLEDGE_AGENT,
            objective=request.objective,
            scope=SpecialistDelegationScope(
                tenant_id=request.tenant_id,
                application_id="datasmart-govern",
                project_id=request.project_id,
                actor_id=request.actor_id,
                delegation_id="delegation-denied",
                allowed_tool_names=("task.read",),
            ),
        )
        denied = KnowledgeSpecialistAgent(rag).execute(direct_knowledge_request)
        self.assertEqual(SpecialistTurnStatus.FAILED, denied.status)
        self.assertEqual("KNOWLEDGE_RAG_TOOL_NOT_AUTHORIZED", denied.error_code)
        self.assertEqual([], rag.queries)

        # Monitor 的项目不一致检测发生在 get_snapshot 之前；因此即使 query 仍带有正确用户，
        # 也不能因为客户端“看起来有权限”而跨 project 读取另一个任务。
        monitor_client = _RecordingMonitorClient()
        monitor = MonitorSpecialistAgent(monitor_client, _RecordingMonitorModel())
        mismatched_request = SpecialistTurnRequest(
            turn_id="turn-mismatch-monitor",
            session_id="session-six-agent-1",
            run_id="run-six-agent-1",
            role=AgentSessionRole.MONITOR_AGENT,
            objective="观察任务进度",
            scope=SpecialistDelegationScope(
                tenant_id="tenant-10",
                application_id="datasmart-govern",
                project_id="project-101",
                actor_id="ordinary-user-7",
                delegation_id="delegation-monitor",
                allowed_tool_names=("task.monitor.read",),
            ),
            context_summary={"taskId": "701", "projectId": "project-900"},
        )
        mismatch = monitor.execute(mismatched_request)
        self.assertEqual(SpecialistTurnStatus.FAILED, mismatch.status)
        self.assertEqual("MONITOR_SCOPE_MISMATCH", mismatch.error_code)
        self.assertEqual([], monitor_client.queries)

        # 事件转换器只读取公开摘要和标量 attributes；先放入一个会话锚点事件，让转换后的专业动作
        # 事件能稳定继承 sessionId，随后用真实 API replay builder 验证前端可按 session 重放。
        plan = AgentPlan(
            request_id=request.request_id or "request-six-agent-1",
            selected_route=None,
            state_trace=(),
            tool_plans=(),
            requires_human_approval=False,
            response_summary="replay anchor",
            runtime_events=(
                AgentRuntimeEvent(
                    event_type=AgentRuntimeEventType.AGENT_PLAN_STARTED,
                    stage="anchor",
                    message="低敏 replay anchor",
                    severity=AgentRuntimeEventSeverity.INFO,
                    tenant_id=request.tenant_id,
                    project_id=request.project_id,
                    actor_id=request.actor_id,
                    request_id=request.request_id,
                    run_id="run-six-agent-1",
                    session_id="session-six-agent-1",
                    sequence=0,
                ),
            ),
        )
        raw_action_events = (
            {
                "eventType": "SPECIALIST_AGENT_ACTION",
                "agentId": "knowledge-specialist-agent-v1",
                "agentRole": "KNOWLEDGE_AGENT",
                "turnId": "turn-knowledge-replay",
                "action": "knowledge.retrieval.completed",
                "status": "COMPLETED",
                "publicSummary": "已检索到 1 条可引用案例。",
                "runId": "run-six-agent-1",
                "sessionId": "session-six-agent-1",
                "statistics": {"citationCount": 1, "prompt": {"body": "REPLAY_SECRET_PROMPT"}},
                "attributes": {"modelName": "knowledge-test-model", "sql": "SELECT SECRET"},
            },
        )
        replay_events = build_specialist_runtime_events(
            request=request,
            plan=plan,
            action_events=raw_action_events,
        )
        self.assertEqual(1, len(replay_events))
        self.assertEqual("session-six-agent-1", replay_events[0].session_id)
        self.assertNotIn("REPLAY_SECRET_PROMPT", str(replay_events))
        # 低敏事件在进入可见性策略前也不应携带 SQL。否则管理员或其他未来的 FULL 视图会绕过
        # 二次遮蔽，直接从 Python Runtime 的事件事实中读到原始查询内容。
        self.assertNotIn("SELECT SECRET", str(replay_events))
        self.assertEqual("LOW_SENSITIVE_SPECIALIST_RUNTIME_EVENT_ONLY", replay_events[0].attributes["payloadPolicy"])

        envelope = build_event_replay_response(
            RuntimeEventSubscriptionRequest(
                client_id="frontend-six-agent",
                tenant_id=request.tenant_id,
                project_id=request.project_id,
                actor_id=request.actor_id,
                # 这里使用已授权的项目治理角色，确保事件先通过可见性筛选，再验证专业动作
                # 生产为“低敏事件”时是否真的没有把 SQL/Prompt 等敏感载荷带入 replay。
                roles=("PROJECT_OWNER",),
                session_id="session-six-agent-1",
                run_id="run-six-agent-1",
                after_sequence=0,
                event_types=(AgentRuntimeEventType.SPECIALIST_AGENT_ACTION_RECORDED,),
            ),
            replay_events,
        )["eventEnvelope"]
        self.assertEqual(1, len(envelope["events"]))
        self.assertEqual("session-six-agent-1", envelope["events"][0]["session_id"])
        self.assertEqual(request.project_id, envelope["events"][0]["project_id"])
        self.assertNotIn("REPLAY_SECRET_PROMPT", str(envelope))
        self.assertNotIn("SELECT SECRET", str(envelope))


if __name__ == "__main__":
    unittest.main()
