"""专业 Agent 生产适配器的定向契约测试。"""

from __future__ import annotations

import json
import unittest
from unittest.mock import patch

from datasmart_ai_runtime.config import default_model_routes
from datasmart_ai_runtime.domain.contracts import ModelInvocationResult
from datasmart_ai_runtime.services.model_gateway.model_router import ModelRouteRegistry
from datasmart_ai_runtime.services.multi_agent.specialist_runtime_adapters import (
    GovernedDatasourceDisambiguationModel,
    GovernedMonitoringSummaryModel,
    GovernedPrecheckExplanationModel,
    GovernedRecoveryPlanningModel,
    GovernedSpecialistJsonModel,
    GovernedSyncPlanningModel,
    HttpDatasourceDiscoveryTool,
    SpecialistRuntimeAdapterError,
)
from datasmart_ai_runtime.services.multi_agent.specialist_contracts import SpecialistAuditScope
from datasmart_ai_runtime.services.multi_agent.specialists.data_sync_agent import SyncPlanningModelInput
from datasmart_ai_runtime.services.multi_agent.specialists.datasource_agent import (
    DatasourceDirection,
    DatasourceDiscoveryRequest,
    DatasourceDisambiguationRequest,
)
from datasmart_ai_runtime.services.multi_agent.specialists.monitor_agent import (
    MonitoringModelInput,
    TaskKind,
)
from datasmart_ai_runtime.services.multi_agent.specialists.precheck_agent import (
    PrecheckExplanationModelInput,
)
from datasmart_ai_runtime.services.multi_agent.specialists.recovery_agent import (
    RecoveryPlanningModelInput,
)


class _QueryResult:
    """模拟 ModelQueryEngine 的最小低敏结果。"""

    def __init__(self, content: str, *, error_code: str | None = None) -> None:
        self.result = ModelInvocationResult(
            provider_name="test-provider",
            model_name="test-specialist-model",
            content=content,
            error_code=error_code,
        )

    def to_summary(self) -> dict[str, object]:
        return {
            "selectedProviderName": "test-provider",
            "selectedModelName": "test-specialist-model",
            "providerInvoked": True,
            "providerSucceeded": self.result.error_code is None,
        }


class _QueryEngine:
    """记录专业模型是否携带正确租户/项目/会话治理上下文。"""

    def __init__(self, content: str, *, error_code: str | None = None) -> None:
        self._content = content
        self._error_code = error_code
        self.requests = []
        self.contexts = []

    def invoke(self, request, *, context):  # noqa: ANN001 - 测试替身故意复用生产协议。
        self.requests.append(request)
        self.contexts.append(context)
        return _QueryResult(self._content, error_code=self._error_code)


class _HttpResponse:
    """支持 ``with urlopen(...)`` 的内存 HTTP 响应。"""

    def __init__(self, payload: dict) -> None:
        self._body = json.dumps(payload).encode("utf-8")

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, traceback):
        return False

    def read(self) -> bytes:
        return self._body


class SpecialistRuntimeAdapterTest(unittest.TestCase):
    """验证真实适配层不会丢失治理范围或泄露数据源连接信息。"""

    @staticmethod
    def _json_model(query_engine: _QueryEngine) -> GovernedSpecialistJsonModel:
        """使用真实路由合同和内存 QueryEngine 构造模型适配器。"""

        return GovernedSpecialistJsonModel(
            model_routes=ModelRouteRegistry(default_model_routes()),
            model_gateway=object(),
            model_providers=object(),
            query_engine=query_engine,
        )

    def test_datasource_model_uses_governed_identity_context(self) -> None:
        engine = _QueryEngine(
            '{"clear":true,"selectedDatasourceId":"23","publicReason":"名称和类型均唯一匹配"}'
        )
        model = GovernedDatasourceDisambiguationModel(self._json_model(engine))

        decision = model.disambiguate(
            DatasourceDisambiguationRequest(
                direction=DatasourceDirection.SOURCE,
                requested_connector_type="MYSQL",
                requested_name="订单源库",
                candidate_summaries=(
                    {"datasourceId": "23", "name": "订单源库", "connectorType": "MYSQL"},
                ),
                max_output_tokens=512,
                tenant_id="10",
                project_id="101",
                actor_id="37",
                session_id="session-1",
                run_id="run-1",
                trace_id="trace-1",
            )
        )

        self.assertTrue(decision.clear)
        self.assertEqual("23", decision.selected_datasource_id)
        self.assertEqual("10", engine.contexts[0].tenant_id)
        self.assertEqual("101", engine.contexts[0].project_id)
        self.assertEqual("37", engine.contexts[0].actor_id)
        self.assertEqual("session-1", engine.contexts[0].attributes["sessionId"])
        self.assertIsNone(engine.requests[0].tool_choice)

    def test_sync_model_returns_configuration_and_safe_invocation_summary(self) -> None:
        engine = _QueryEngine(
            "```json\n"
            '{"configuration":{"taskName":"客户同步","syncMode":"FULL"},'
            '"publicSummary":"已形成草案","requestedToolNames":[],"requestedActions":[]}'
            "\n```"
        )
        model = GovernedSyncPlanningModel(self._json_model(engine))

        output = model.plan(
            SyncPlanningModelInput(
                objective="创建全量同步任务",
                context={"sourceDatasourceId": 1, "targetDatasourceId": 2},
                allowed_tool_names=(),
                max_output_tokens=1024,
                tenant_id="10",
                project_id="101",
                actor_id="37",
                session_id="session-2",
                run_id="run-2",
                trace_id="trace-2",
            )
        )

        self.assertEqual("客户同步", output.configuration["taskName"])
        self.assertTrue(output.invocation_summary["structuredJsonParsed"])
        self.assertFalse(output.invocation_summary["responseContentStored"])

    def test_sync_model_preserves_inert_execution_summary_for_specialist_quarantine(self) -> None:
        """适配器不应在模型层静默吞掉边界摘要，由 DATA_SYNC_AGENT 统一隔离并审计。"""

        engine = _QueryEngine(
            json.dumps(
                {
                    "configuration": {
                        "taskName": "客户同步",
                        "syncMode": "FULL",
                        "execution": {
                            "status": "NOT_STARTED",
                            "executed": False,
                        },
                    },
                    "publicSummary": "已形成草案",
                    "requestedToolNames": [],
                    "requestedActions": [],
                },
                ensure_ascii=False,
            )
        )
        model = GovernedSyncPlanningModel(self._json_model(engine))

        output = model.plan(
            SyncPlanningModelInput(
                objective="创建全量同步任务",
                context={"sourceDatasourceId": 1, "targetDatasourceId": 2},
                allowed_tool_names=(),
                max_output_tokens=1024,
                tenant_id="10",
                project_id="101",
                actor_id="37",
                session_id="session-execution-summary",
                run_id="run-execution-summary",
                trace_id="trace-execution-summary",
            )
        )

        self.assertEqual("NOT_STARTED", output.configuration["execution"]["status"])
        self.assertFalse(output.configuration["execution"]["executed"])

    def test_sync_model_preserves_active_execution_for_specialist_fail_closed(self) -> None:
        """适配器保留配置候选，后续 DATA_SYNC_AGENT 安全门负责拒绝真实副作用声明。"""

        engine = _QueryEngine(
            '{"configuration":{"taskName":"客户同步","execution":{"taskId":9001}},'
            '"publicSummary":"已形成草案","requestedToolNames":[],"requestedActions":[]}'
        )
        output = GovernedSyncPlanningModel(self._json_model(engine)).plan(
            SyncPlanningModelInput(
                objective="创建任务",
                context={},
                allowed_tool_names=(),
                max_output_tokens=512,
                tenant_id="10",
                actor_id="37",
                session_id="session-active-execution",
                run_id="run-active-execution",
            )
        )
        self.assertEqual(9001, output.configuration["execution"]["taskId"])

    def test_model_error_and_invalid_json_fail_closed(self) -> None:
        with self.assertRaises(SpecialistRuntimeAdapterError) as invalid_json:
            GovernedSyncPlanningModel(self._json_model(_QueryEngine("not-json"))).plan(
                SyncPlanningModelInput(
                    objective="创建任务",
                    context={},
                    allowed_tool_names=(),
                    max_output_tokens=512,
                    tenant_id="10",
                    actor_id="37",
                    session_id="session-3",
                    run_id="run-3",
                )
            )
        self.assertEqual("MODEL_RESPONSE_INVALID_JSON", invalid_json.exception.reason_code)
        self.assertEqual("MODEL_RESPONSE_PARSER", invalid_json.exception.reason_source)

        with self.assertRaises(SpecialistRuntimeAdapterError) as provider_timeout:
            GovernedSyncPlanningModel(
                self._json_model(_QueryEngine("{}", error_code="provider_request_timeout"))
            ).plan(
                SyncPlanningModelInput(
                    objective="创建任务",
                    context={},
                    allowed_tool_names=(),
                    max_output_tokens=512,
                    tenant_id="10",
                    actor_id="37",
                    session_id="session-4",
                    run_id="run-4",
                )
            )
        self.assertEqual("MODEL_TIMEOUT", provider_timeout.exception.reason_code)
        self.assertEqual("MODEL_PROVIDER_RESPONSE", provider_timeout.exception.reason_source)

    @staticmethod
    def _audit_scope(
        *,
        tenant_id: str = "tenant-bound",
        project_id: str = "project-bound",
        actor_id: str = "actor-bound",
        session_id: str = "session-bound",
        trace_id: str = "trace-bound",
    ) -> SpecialistAuditScope:
        """构造显式 turn 审计范围，验证模型适配器不再依赖静态 provider。"""

        return SpecialistAuditScope(
            tenant_id=tenant_id,
            project_id=project_id,
            actor_id=actor_id,
            session_id=session_id,
            trace_id=trace_id,
        )

    @staticmethod
    def _payload(engine: _QueryEngine) -> dict[str, object]:
        """读取测试 QueryEngine 收到的用户消息，验证适配器没有扩大 Protocol 字段。"""

        return json.loads(engine.requests[0].messages[1].content)

    def test_precheck_adapter_maps_only_explanation_fields_and_uses_dynamic_summary(self) -> None:
        """PRECHECK 只发送低敏检查摘要，模型自带调用统计和事实字段不会被采信。"""

        engine = _QueryEngine(
            json.dumps(
                {
                    "publicSummary": "后端检查摘要已整理",
                    "problems": ["请确认失败项"],
                    "suggestions": ["修正配置后重试"],
                    "configurationSteps": ["补充目标配置"],
                    "detailsReferences": ["precheck://run-1/summary"],
                    "invocationSummary": {
                        "modelName": "forged-model",
                        "rawPrompt": "must-not-be-stored",
                    },
                },
                ensure_ascii=False,
            )
        )
        model = GovernedPrecheckExplanationModel(self._json_model(engine))

        output = model.explain(
            PrecheckExplanationModelInput(
                objective="目标描述不能成为租户或项目身份",
                audit_scope=self._audit_scope(),
                task_id="task-1",
                precheck_status="FAILED",
                can_start_execution=False,
                checks=(
                    {
                        "code": "TARGET_CHECK",
                        "status": "FAILED",
                        "problem": "目标检查未通过",
                        "suggestion": "补充配置",
                        "configurationSteps": ("选择目标对象",),
                        "detailsReference": "precheck://run-1/target",
                        "password": "must-not-reach-model",
                        "rawSql": "SELECT secret FROM rows",
                    },
                ),
                issue_codes=("TARGET_CHECK",),
                max_output_tokens=512,
            )
        )

        self.assertEqual("后端检查摘要已整理", output.public_summary)
        self.assertEqual(("修正配置后重试",), output.suggestions)
        payload = self._payload(engine)
        self.assertEqual(
            {
                "objective",
                "taskId",
                "precheckStatus",
                "canStartExecution",
                "checks",
                "issueCodes",
                "maxOutputTokens",
            },
            set(payload),
        )
        self.assertNotIn("must-not-reach-model", str(payload))
        self.assertNotIn("SELECT secret", str(payload))
        self.assertEqual("tenant-bound", engine.contexts[0].tenant_id)
        self.assertEqual("project-bound", engine.contexts[0].project_id)
        self.assertEqual("session-bound", engine.contexts[0].attributes["sessionId"])
        self.assertEqual((), engine.requests[0].available_tools)
        self.assertIsNone(engine.requests[0].tool_choice)
        self.assertEqual("test-specialist-model", output.invocation_summary["selectedModelName"])
        self.assertNotIn("forged-model", str(output.invocation_summary))
        self.assertNotIn("rawPrompt", output.invocation_summary)
        self.assertFalse(output.invocation_summary["responseContentStored"])

    def test_recovery_adapter_maps_low_sensitive_suggestions_without_native_tools(self) -> None:
        """RECOVERY 只返回建议动作，诊断原文和模型伪造的调用摘要不会进入合同。"""

        engine = _QueryEngine(
            json.dumps(
                {
                    "actions": [
                        {
                            "actionType": "READ_ONLY_DIAGNOSIS",
                            "toolName": "recovery.failure.diagnose",
                            "reason": "核对失败阶段",
                            "arguments": {
                                "taskId": "task-1",
                                "password": "must-not-be-stored",
                                "sql": "SELECT secret FROM rows",
                            },
                        }
                    ],
                    "publicSummary": "已生成待审核恢复建议",
                    "failureReason": "下游阶段失败",
                    "nextStep": "请由控制面审核",
                    "invocationSummary": {"rawPrompt": "must-not-be-stored"},
                },
                ensure_ascii=False,
            )
        )
        model = GovernedRecoveryPlanningModel(self._json_model(engine))

        output = model.plan(
            RecoveryPlanningModelInput(
                objective="恢复任务",
                audit_scope=self._audit_scope(),
                diagnostic_facts={
                    "failureCode": "SYNC_FAILED",
                    "rawSql": "SELECT secret FROM rows",
                    "password": "must-not-reach-model",
                },
                case_evidence={"failedStage": "SUBMIT", "snippet": "raw document body"},
                knowledge_summary={"summary": "低敏运行手册摘要"},
                evidence_references=("case://run-1",),
                allowed_tool_names=("recovery.failure.diagnose",),
                max_output_tokens=512,
                failure_code="SYNC_FAILED",
                failure_reason="任务在提交阶段失败",
            )
        )

        self.assertEqual("已生成待审核恢复建议", output.public_summary)
        self.assertEqual("READ_ONLY_DIAGNOSIS", output.actions[0]["actionType"])
        self.assertNotIn("password", str(output.actions[0]))
        self.assertNotIn("SELECT secret", str(output.actions[0]))
        payload = self._payload(engine)
        self.assertEqual(
            {
                "objective",
                "diagnosticFacts",
                "caseEvidence",
                "knowledgeSummary",
                "monitoringSummary",
                "evidenceAudit",
                "evidenceReferences",
                "allowedToolNames",
                "maxOutputTokens",
                "failureCode",
                "failureReason",
                "canonicalActionTypes",
            },
            set(payload),
        )
        self.assertIn("RETRY_FAILED_OBJECTS", payload["canonicalActionTypes"])
        self.assertIn("PREVIEW_QUARANTINE", payload["canonicalActionTypes"])
        self.assertIn("PREVIEW_CREATE_TARGET_TABLE", payload["canonicalActionTypes"])
        self.assertNotIn("must-not-reach-model", str(payload))
        self.assertNotIn("SELECT secret", str(payload))
        self.assertEqual({}, payload["monitoringSummary"])
        self.assertEqual({}, payload["evidenceAudit"])
        self.assertEqual((), engine.requests[0].available_tools)
        self.assertIsNone(engine.requests[0].tool_choice)
        system_instruction = engine.requests[0].messages[0].content
        self.assertIn("每一轮最多返回一个", system_instruction)
        self.assertIn("不能在同一轮同时建议 preview", system_instruction)
        self.assertEqual("test-specialist-model", output.invocation_summary["selectedModelName"])
        self.assertFalse(output.invocation_summary["rawModelOutputStored"])

    def test_recovery_adapter_falls_back_to_one_read_only_dirty_record_preview(self) -> None:
        """模型主动 abstain 时，只能把 Java 脏数据建议收敛成一个只读预览。

        Recovery 模型可能因为证据边界严格而返回空 ``actions``。这并不应让闭环停在一个没有
        后续入口的解释结果上，但也绝不能把“模型没有选择动作”提升成写操作授权。生产适配器
        因此只采信 Java 诊断里的稳定建议编码，并只生成没有副作用的 ``PREVIEW_QUARANTINE``；
        retry、隔离、重放仍必须由后续 Java ToolPlan、权限和审批链决定。
        """

        engine = _QueryEngine(
            json.dumps(
                {
                    "actions": [],
                    "publicSummary": "模型选择先核对脏数据范围",
                    "nextStep": "先生成只读预览",
                },
                ensure_ascii=False,
            )
        )
        model = GovernedRecoveryPlanningModel(self._json_model(engine))

        output = model.plan(
            RecoveryPlanningModelInput(
                objective="诊断失败任务并给出安全的下一步",
                audit_scope=self._audit_scope(),
                diagnostic_facts={
                    "failureCode": "DIRTY_RECORD_THRESHOLD_EXCEEDED",
                    "recommendedRepairActions": (
                        "PREVIEW_DIRTY_RECORD_QUARANTINE",
                        "REPLAY_DIRTY_RECORDS",
                    ),
                },
                case_evidence={"matchedCaseCount": 1},
                knowledge_summary={"grounded": True},
                evidence_references=("case://dirty-record/1",),
                allowed_tool_names=("recovery.dirty-record.quarantine.preview",),
                max_output_tokens=512,
                failure_code="DIRTY_RECORD_THRESHOLD_EXCEEDED",
                failure_reason="目标写入阶段发现不满足约束的数据",
            )
        )

        self.assertEqual(1, len(output.actions))
        self.assertEqual("PREVIEW_QUARANTINE", output.actions[0]["actionType"])
        self.assertTrue(str(output.actions[0]["actionId"]).startswith("deterministic-preview-"))
        self.assertEqual(1, output.invocation_summary["deterministicPreviewFallbackCount"])
        self.assertNotIn("REPLAY_DIRTY_RECORDS", str(output.actions))
        self.assertNotIn("RETRY_FAILED_OBJECTS", str(output.actions))

    def test_recovery_adapter_never_promotes_unknown_or_high_risk_java_recommendations(self) -> None:
        """空模型动作不能把未知或高风险 Java 建议偷偷转换为可执行恢复动作。

        这个反例固定 fail-closed 边界：即使诊断事实建议 retry、apply、replay、create 或 alter，
        Python 适配层也必须保持空动作。只有明确列入只读预览映射表的建议才允许作为低风险
        handoff 候选，避免将确定性诊断建议错误等同于用户审批或业务执行授权。
        """

        engine = _QueryEngine('{"actions":[],"publicSummary":"证据不足，暂不建议动作"}')
        model = GovernedRecoveryPlanningModel(self._json_model(engine))

        output = model.plan(
            RecoveryPlanningModelInput(
                objective="恢复失败任务",
                audit_scope=self._audit_scope(),
                diagnostic_facts={
                    "failureCode": "WRITE_FAILED",
                    "recommendedRepairActions": (
                        "RETRY_FAILED_OBJECTS",
                        "APPLY_QUARANTINE",
                        "REPLAY_DIRTY_RECORDS",
                        "CREATE_TARGET_TABLE",
                        "ALTER_TARGET_SCHEMA",
                        "UNKNOWN_AUTOMATIC_FIX",
                    ),
                },
                case_evidence={"matchedCaseCount": 0},
                knowledge_summary={"grounded": False},
                evidence_references=(),
                allowed_tool_names=("sync.execution.failed-objects.retry",),
                max_output_tokens=512,
                failure_code="WRITE_FAILED",
                failure_reason="目标写入失败",
            )
        )

        self.assertEqual((), output.actions)
        self.assertEqual(0, output.invocation_summary["deterministicPreviewFallbackCount"])

    def test_monitor_adapter_maps_facts_and_recommendations_only(self) -> None:
        """MONITOR 只把事实摘要交给模型，模型不得以状态字段改写确定性结果。"""

        engine = _QueryEngine(
            json.dumps(
                {
                    "publicSummary": "任务运行正常",
                    "recommendedActions": ["继续按计划轮询"],
                    "invocationSummary": {"modelName": "forged-model", "chainOfThought": "hidden"},
                },
                ensure_ascii=False,
            )
        )
        model = GovernedMonitoringSummaryModel(self._json_model(engine))

        output = model.summarize(
            MonitoringModelInput(
                objective="监控任务",
                audit_scope=self._audit_scope(),
                task_id="task-1",
                task_kind=TaskKind.LONG_RUNNING,
                facts={
                    "status": "RUNNING",
                    "progress": {"percent": 50},
                    "checkpoint": {"offset": 7, "token": "must-not-reach-model"},
                },
                anomalies=({"code": "NONE", "sampleRows": ("must-not-reach-model",)},),
                allowed_tool_names=("task.monitor.read",),
                max_output_tokens=512,
            )
        )

        self.assertEqual("任务运行正常", output.public_summary)
        self.assertEqual(("继续按计划轮询",), output.recommended_actions)
        payload = self._payload(engine)
        self.assertEqual(
            {
                "objective",
                "taskId",
                "taskKind",
                "facts",
                "anomalies",
                "allowedToolNames",
                "maxOutputTokens",
            },
            set(payload),
        )
        self.assertNotIn("must-not-reach-model", str(payload))
        self.assertEqual("LONG_RUNNING", payload["taskKind"])
        self.assertEqual((), engine.requests[0].available_tools)
        self.assertIsNone(engine.requests[0].tool_choice)
        self.assertEqual("test-specialist-model", output.invocation_summary["selectedModelName"])
        self.assertNotIn("forged-model", str(output.invocation_summary))

    def test_missing_scope_fails_closed_before_query_engine_for_all_new_adapters(self) -> None:
        """缺少显式 ModelInput 审计范围时，三个适配器都不能调用模型或猜测身份。"""

        precheck_request = PrecheckExplanationModelInput(
            objective="tenant-from-objective-must-not-be-used",
            audit_scope=self._audit_scope(),
            task_id="task-1",
            precheck_status="PASSED",
            can_start_execution=True,
            checks=(),
            issue_codes=(),
            max_output_tokens=512,
        )
        recovery_request = RecoveryPlanningModelInput(
            objective="project-from-objective-must-not-be-used",
            audit_scope=self._audit_scope(),
            diagnostic_facts={"failureCode": "FAILED"},
            case_evidence={},
            knowledge_summary={"summary": "safe"},
            evidence_references=(),
            allowed_tool_names=(),
            max_output_tokens=512,
        )
        monitor_request = MonitoringModelInput(
            objective="session-from-objective-must-not-be-used",
            audit_scope=self._audit_scope(),
            task_id="task-1",
            task_kind=TaskKind.LONG_RUNNING,
            facts={"status": "RUNNING"},
            anomalies=(),
            allowed_tool_names=(),
            max_output_tokens=512,
        )
        cases = (
            (GovernedPrecheckExplanationModel, "explain", precheck_request),
            (GovernedRecoveryPlanningModel, "plan", recovery_request),
            (GovernedMonitoringSummaryModel, "summarize", monitor_request),
        )
        for adapter_type, method_name, request in cases:
            with self.subTest(adapter=adapter_type.__name__):
                engine = _QueryEngine("{}")
                # 通过 object.__setattr__ 模拟不完整的旧/伪造输入，绕过 dataclass 构造校验，
                # 专门验证适配器自身仍会在 QueryEngine 之前做第二道 fail-closed 检查。
                object.__setattr__(request, "audit_scope", None)
                adapter = adapter_type(self._json_model(engine))
                with self.assertRaises(SpecialistRuntimeAdapterError):
                    getattr(adapter, method_name)(request)
                self.assertEqual([], engine.requests)

    def test_one_adapter_uses_each_turn_scope_without_cross_tenant_leak(self) -> None:
        """同一个适配器连续处理两个租户/项目时，每次调用都读取当前输入的范围。"""

        cases = (
            (
                GovernedPrecheckExplanationModel,
                "explain",
                PrecheckExplanationModelInput(
                    objective="解释当前检查",
                    audit_scope=self._audit_scope(
                        tenant_id="tenant-a", project_id="project-a", actor_id="actor-a",
                        session_id="session-a", trace_id="turn-a",
                    ),
                    task_id="task-a",
                    precheck_status="PASSED",
                    can_start_execution=True,
                    checks=(),
                    issue_codes=(),
                    max_output_tokens=512,
                ),
                PrecheckExplanationModelInput(
                    objective="解释当前检查",
                    audit_scope=self._audit_scope(
                        tenant_id="tenant-b", project_id="project-b", actor_id="actor-b",
                        session_id="session-b", trace_id="turn-b",
                    ),
                    task_id="task-b",
                    precheck_status="PASSED",
                    can_start_execution=True,
                    checks=(),
                    issue_codes=(),
                    max_output_tokens=512,
                ),
            ),
            (
                GovernedRecoveryPlanningModel,
                "plan",
                RecoveryPlanningModelInput(
                    objective="生成恢复建议",
                    audit_scope=self._audit_scope(
                        tenant_id="tenant-a", project_id="project-a", actor_id="actor-a",
                        session_id="session-a", trace_id="turn-a",
                    ),
                    diagnostic_facts={"failureCode": "FAILED"},
                    case_evidence={},
                    knowledge_summary={"summary": "safe"},
                    evidence_references=(),
                    allowed_tool_names=(),
                    max_output_tokens=512,
                ),
                RecoveryPlanningModelInput(
                    objective="生成恢复建议",
                    audit_scope=self._audit_scope(
                        tenant_id="tenant-b", project_id="project-b", actor_id="actor-b",
                        session_id="session-b", trace_id="turn-b",
                    ),
                    diagnostic_facts={"failureCode": "FAILED"},
                    case_evidence={},
                    knowledge_summary={"summary": "safe"},
                    evidence_references=(),
                    allowed_tool_names=(),
                    max_output_tokens=512,
                ),
            ),
            (
                GovernedMonitoringSummaryModel,
                "summarize",
                MonitoringModelInput(
                    objective="总结当前监控事实",
                    audit_scope=self._audit_scope(
                        tenant_id="tenant-a", project_id="project-a", actor_id="actor-a",
                        session_id="session-a", trace_id="turn-a",
                    ),
                    task_id="task-a",
                    task_kind=TaskKind.LONG_RUNNING,
                    facts={"status": "RUNNING"},
                    anomalies=(),
                    allowed_tool_names=(),
                    max_output_tokens=512,
                ),
                MonitoringModelInput(
                    objective="总结当前监控事实",
                    audit_scope=self._audit_scope(
                        tenant_id="tenant-b", project_id="project-b", actor_id="actor-b",
                        session_id="session-b", trace_id="turn-b",
                    ),
                    task_id="task-b",
                    task_kind=TaskKind.LONG_RUNNING,
                    facts={"status": "RUNNING"},
                    anomalies=(),
                    allowed_tool_names=(),
                    max_output_tokens=512,
                ),
            ),
        )
        for adapter_type, method_name, first_request, second_request in cases:
            with self.subTest(adapter=adapter_type.__name__):
                engine = _QueryEngine(
                    '{"publicSummary":"safe"}'
                )
                adapter = adapter_type(self._json_model(engine))
                getattr(adapter, method_name)(first_request)
                getattr(adapter, method_name)(second_request)
                self.assertEqual(2, len(engine.contexts))
                self.assertEqual(("tenant-a", "project-a", "actor-a", "session-a", "turn-a"), (
                    engine.contexts[0].tenant_id,
                    engine.contexts[0].project_id,
                    engine.contexts[0].actor_id,
                    engine.contexts[0].attributes["sessionId"],
                    engine.contexts[0].trace_id,
                ))
                self.assertEqual(("tenant-b", "project-b", "actor-b", "session-b", "turn-b"), (
                    engine.contexts[1].tenant_id,
                    engine.contexts[1].project_id,
                    engine.contexts[1].actor_id,
                    engine.contexts[1].attributes["sessionId"],
                    engine.contexts[1].trace_id,
                ))

    def test_fact_or_side_effect_output_fails_closed_instead_of_becoming_a_contract_field(self) -> None:
        """模型返回状态、执行或工具字段时直接拒绝，而不是让它们影响 specialist 事实。"""

        cases = (
            (
                GovernedPrecheckExplanationModel,
                "explain",
                PrecheckExplanationModelInput(
                    objective="explain",
                    audit_scope=self._audit_scope(),
                    task_id="task-1",
                    precheck_status="FAILED",
                    can_start_execution=False,
                    checks=(),
                    issue_codes=(),
                    max_output_tokens=512,
                ),
                '{"publicSummary":"safe","precheckStatus":"PASSED"}',
            ),
            (
                GovernedRecoveryPlanningModel,
                "plan",
                RecoveryPlanningModelInput(
                    objective="plan",
                    audit_scope=self._audit_scope(),
                    diagnostic_facts={"failureCode": "FAILED"},
                    case_evidence={},
                    knowledge_summary={"summary": "safe"},
                    evidence_references=(),
                    allowed_tool_names=(),
                    max_output_tokens=512,
                ),
                '{"execute":true,"actions":[]}',
            ),
            (
                GovernedMonitoringSummaryModel,
                "summarize",
                MonitoringModelInput(
                    objective="summarize",
                    audit_scope=self._audit_scope(),
                    task_id="task-1",
                    task_kind=TaskKind.LONG_RUNNING,
                    facts={"status": "RUNNING"},
                    anomalies=(),
                    allowed_tool_names=(),
                    max_output_tokens=512,
                ),
                '{"status":"SUCCEEDED","publicSummary":"unsafe"}',
            ),
        )
        for adapter_type, method_name, request, content in cases:
            with self.subTest(adapter=adapter_type.__name__):
                engine = _QueryEngine(content)
                adapter = adapter_type(self._json_model(engine))
                with self.assertRaises(SpecialistRuntimeAdapterError):
                    getattr(adapter, method_name)(request)


    @patch("datasmart_ai_runtime.services.multi_agent.specialist_runtime_adapters.urlopen")
    def test_datasource_discovery_only_returns_usable_low_sensitive_candidates(self, mocked_urlopen) -> None:
        mocked_urlopen.return_value = _HttpResponse(
            {
                "code": 0,
                "data": {
                    "records": [
                        {
                            "id": 23,
                            "ownerId": 11,
                            "name": "订单源库",
                            "type": "MYSQL",
                            "status": "ACTIVE",
                            "effectiveActions": ["VIEW", "USE"],
                            "jdbcUrl": "jdbc:mysql://secret-host:3306/orders",
                            "username": "secret-user",
                            "password": "must-not-leak",
                        },
                        {
                            "id": 24,
                            "ownerId": 12,
                            "name": "仅查看库",
                            "type": "MYSQL",
                            "status": "ACTIVE",
                            "effectiveActions": ["VIEW"],
                        },
                    ]
                },
            }
        )
        tool = HttpDatasourceDiscoveryTool("http://datasource-management:8082")

        result = tool.discover(
            DatasourceDiscoveryRequest(
                tenant_id="10",
                application_id="123",
                project_id="101",
                actor_id="37",
                delegation_id="delegation-1",
                turn_id="turn-1",
                run_id="run-1",
                direction=DatasourceDirection.SOURCE,
                connector_type="MYSQL",
                name="订单源库",
            )
        )

        self.assertEqual(("23",), tuple(candidate.datasource_id for candidate in result.candidates))
        public_candidate = result.candidates[0].to_public_summary()
        self.assertNotIn("jdbcUrl", public_candidate)
        self.assertNotIn("username", public_candidate)
        self.assertIn("datasource-discovery:run-1:source:", result.evidence_reference)
        sent_request = mocked_urlopen.call_args.args[0]
        self.assertEqual("10", sent_request.headers["X-datasmart-tenant-id"])
        self.assertEqual("101", sent_request.headers["X-datasmart-project-id"])

    @patch("datasmart_ai_runtime.services.multi_agent.specialist_runtime_adapters.urlopen")
    def test_datasource_discovery_explicit_id_is_not_invalidated_by_stale_display_name(
        self,
        mocked_urlopen,
    ) -> None:
        """已确认的数据源 ID 必须优先于可能因重命名而过期的显示名称。

        页面常会同时提交 ``datasourceId`` 和当时看到的名称。资源在计划真正执行前可能被管理员
        重命名；若适配器仍把旧名称作为服务端 keyword，分页接口会先返回空集合，使一个仍在相同
        tenant/project、方向和连接器范围内的授权 ID 被错误判成不存在。显式 ID 场景因此不发送
        keyword，随后仍由返回记录、USE/MANAGE/owner 关系和客户端 ID 精确匹配共同完成校验。
        """

        mocked_urlopen.return_value = _HttpResponse(
            {
                "code": 0,
                "data": {
                    "records": [
                        {
                            "id": 55,
                            "ownerId": 1001,
                            "name": "E2E MySQL source renamed",
                            "type": "MYSQL",
                            "status": "ACTIVE",
                            "effectiveActions": ["VIEW", "USE"],
                        }
                    ]
                },
            }
        )
        tool = HttpDatasourceDiscoveryTool("http://datasource-management:8082")

        result = tool.discover(
            DatasourceDiscoveryRequest(
                tenant_id="10",
                application_id="10010",
                project_id="101",
                actor_id="1001",
                delegation_id="delegation-1",
                turn_id="turn-1",
                run_id="run-1",
                direction=DatasourceDirection.SOURCE,
                connector_type="MYSQL",
                name="FlashSync MySQL 源",
                datasource_id="55",
            )
        )

        self.assertEqual(("55",), tuple(candidate.datasource_id for candidate in result.candidates))
        sent_request = mocked_urlopen.call_args.args[0]
        self.assertNotIn("keyword=", sent_request.full_url)
        self.assertIn("projectId=101", sent_request.full_url)
        self.assertIn("usagePurpose=SOURCE", sent_request.full_url)


if __name__ == "__main__":
    unittest.main()
