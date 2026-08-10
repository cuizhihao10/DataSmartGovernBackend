from __future__ import annotations

import unittest
from typing import Any, Mapping

from datasmart_ai_runtime.services.agent_gateway.session_models import AgentSessionRole
from datasmart_ai_runtime.services.multi_agent.specialist_contracts import (
    SpecialistDelegationScope,
    SpecialistTurnBudget,
    SpecialistTurnRequest,
    SpecialistTurnStatus,
)
from datasmart_ai_runtime.services.multi_agent.specialists.precheck_agent import (
    PRECHECK_TOOL_CODE,
    PrecheckExplanationModelInput,
    PrecheckExplanationModelOutput,
    PrecheckSpecialistAgent,
)


class _ControlPlane:
    """确定性控制面测试替身，只返回预置事实并记录受控请求。"""

    def __init__(self, result: Mapping[str, Any] | Exception) -> None:
        self.result = result
        self.requests: list[Any] = []

    def precheck(self, request: Any) -> Mapping[str, Any]:
        """模拟一次只读后端预检查，不提供保存、发布或执行方法。"""

        self.requests.append(request)
        if isinstance(self.result, Exception):
            raise self.result
        return self.result


class _ExplanationModel:
    """只接收结构化检查摘要的模型测试替身。"""

    def __init__(self, output: PrecheckExplanationModelOutput | Mapping[str, Any] | Exception) -> None:
        self.output = output
        self.requests: list[PrecheckExplanationModelInput] = []

    def explain(self, request: PrecheckExplanationModelInput) -> Any:
        """记录模型输入，验证任务配置和敏感正文没有越过解释边界。"""

        self.requests.append(request)
        if isinstance(self.output, Exception):
            raise self.output
        return self.output


def _request(
    *,
    context: Mapping[str, Any] | None = None,
    allowed_tools: tuple[str, ...] = (PRECHECK_TOOL_CODE,),
    role: AgentSessionRole = AgentSessionRole.PRECHECK_AGENT,
    budget: SpecialistTurnBudget | None = None,
) -> SpecialistTurnRequest:
    """创建带有租户、项目和操作者审计范围的 PRECHECK_AGENT 委派。"""

    return SpecialistTurnRequest(
        turn_id="turn-precheck-1",
        session_id="session-precheck-1",
        run_id="run-precheck-1",
        role=role,
        objective="检查 customer 同步任务是否具备执行条件",
        scope=SpecialistDelegationScope(
            tenant_id="tenant-1",
            application_id="datasmart",
            project_id="project-1",
            actor_id="user-1",
            delegation_id="delegation-precheck-1",
            allowed_tool_names=allowed_tools,
        ),
        budget=budget or SpecialistTurnBudget(),
        context_summary=context or {},
        evidence_references=("control-plane://run-precheck-1",),
    )


def _task_context() -> dict[str, Any]:
    """构造包含真实任务定位和敏感配置的上下文，验证敏感配置只到控制面。"""

    return {
        "taskId": "9001",
        "taskConfig": {
            "sourceTable": "customer",
            "targetTable": "customer_archive",
            "customSqlText": "SELECT id, name FROM customer WHERE tenant_id = 1",
            "password": "do-not-leak",
        },
    }


def _passed_result() -> dict[str, Any]:
    """构造后端已经验证通过的结构化检查结果。"""

    return {
        "taskId": "9001",
        "precheckStatus": "PASSED",
        "canStartExecution": True,
        "checks": [
            {
                "code": "CONNECTOR_COMPATIBLE",
                "status": "PASSED",
                "problem": "连接器组合通过后端能力矩阵检查。",
                "detailsReference": "precheck://9001/connector",
            },
            {
                "code": "TARGET_PRIMARY_KEY",
                "status": "PASSED",
                "problem": "目标主键事实已由后端检查返回。",
                "detailsReference": "precheck://9001/primary-key",
            },
        ],
        "detailsReferences": ["precheck://9001/summary"],
    }


class PrecheckSpecialistAgentTest(unittest.TestCase):
    """验证真实预检查的权限、事实来源、状态语义和低敏输出边界。"""

    def test_rejects_missing_tool_permission_without_calling_control_plane_or_model(self) -> None:
        """缺少精确预检查工具权限时不能用其他读工具替代。"""

        control_plane = _ControlPlane(_passed_result())
        model = _ExplanationModel(PrecheckExplanationModelOutput(public_summary="不应被调用"))
        result = PrecheckSpecialistAgent(control_plane, model).execute(
            _request(allowed_tools=("sync.task.publish", "datasource.target.metadata.read"))
        )

        self.assertEqual(SpecialistTurnStatus.FAILED, result.status)
        self.assertEqual("PRECHECK_TOOL_NOT_AUTHORIZED", result.error_code)
        self.assertEqual([], control_plane.requests)
        self.assertEqual([], model.requests)
        self.assertEqual("DENIED", result.tool_activities[0].status)
        self.assertFalse(result.structured_output["persisted"])
        self.assertFalse(result.structured_output["published"])
        self.assertFalse(result.structured_output["executed"])

    def test_waits_for_task_or_configuration_without_guessing(self) -> None:
        """缺少 task/config 时只等待补参，绝不向后端发送空任务。"""

        control_plane = _ControlPlane(_passed_result())
        model = _ExplanationModel(PrecheckExplanationModelOutput(public_summary="不应被调用"))
        result = PrecheckSpecialistAgent(control_plane, model).execute(_request())

        self.assertEqual(SpecialistTurnStatus.WAITING_FOR_INPUT, result.status)
        self.assertEqual(("taskId", "taskConfig"), result.required_input_fields)
        self.assertEqual([], control_plane.requests)
        self.assertEqual([], model.requests)
        self.assertFalse(result.structured_output["canStartExecution"])
        self.assertTrue(
            any("补充 taskId 或完整 taskConfig" in step for step in result.structured_output["configurationSteps"])
        )

    def test_uses_completed_data_sync_specialist_configuration_for_precheck(self) -> None:
        """同步规划 Agent 的结构化结果应直接成为预检查输入，不能再次要求用户填写同一配置。"""

        control_plane = _ControlPlane(_passed_result())
        model = _ExplanationModel(PrecheckExplanationModelOutput(public_summary="预检查通过。"))
        context = {
            "dependencyResults": {
                AgentSessionRole.DATA_SYNC_AGENT.value: {
                    "status": "COMPLETED",
                    "structuredOutput": {
                        "taskName": "customer-sync",
                        "sourceDatasourceId": 11,
                        "targetDatasourceId": 12,
                        "objectMappings": ({"sourceTable": "customer", "targetTable": "customer"},),
                    },
                }
            }
        }

        result = PrecheckSpecialistAgent(control_plane, model).execute(_request(context=context))

        self.assertEqual(SpecialistTurnStatus.COMPLETED, result.status)
        self.assertEqual(1, len(control_plane.requests))
        self.assertEqual("customer-sync", control_plane.requests[0].configuration["taskName"])

    def test_rejects_invalid_budget_before_external_calls(self) -> None:
        """工具预算为零时必须在控制面调用前 fail-closed。"""

        control_plane = _ControlPlane(_passed_result())
        model = _ExplanationModel(PrecheckExplanationModelOutput(public_summary="不应被调用"))
        result = PrecheckSpecialistAgent(control_plane, model).execute(
            _request(budget=SpecialistTurnBudget(max_tool_calls=0))
        )

        self.assertEqual(SpecialistTurnStatus.FAILED, result.status)
        self.assertEqual("PRECHECK_BUDGET_INVALID", result.error_code)
        self.assertEqual([], control_plane.requests)
        self.assertEqual([], model.requests)

    def test_rejects_role_mismatch_without_touching_control_plane(self) -> None:
        """实例不能冒充其他 specialist 角色执行预检查。"""

        control_plane = _ControlPlane(_passed_result())
        model = _ExplanationModel(PrecheckExplanationModelOutput(public_summary="不应被调用"))
        result = PrecheckSpecialistAgent(control_plane, model).execute(
            _request(role=AgentSessionRole.DATA_SYNC_AGENT, context=_task_context())
        )

        self.assertEqual(SpecialistTurnStatus.FAILED, result.status)
        self.assertEqual("PRECHECK_AGENT_ROLE_MISMATCH", result.error_code)
        self.assertEqual([], control_plane.requests)
        self.assertEqual([], model.requests)

    def test_passed_control_plane_facts_are_explained_without_leaking_configuration(self) -> None:
        """通过项来自控制面，模型只能补充解释，结果仍然是只读的。"""

        control_plane = _ControlPlane(_passed_result())
        model = _ExplanationModel(
            PrecheckExplanationModelOutput(
                public_summary="已整理后端返回的检查摘要。",
                suggestions=("确认配置步骤后再由受控流程继续。",),
                invocation_summary={
                    "modelName": "test-precheck-model",
                    "providerName": "openai-compatible",
                    "latencyMs": 12,
                    "rawPrompt": "must-not-leak",
                },
            )
        )
        events: list[Mapping[str, Any]] = []

        result = PrecheckSpecialistAgent(control_plane, model).execute(
            _request(context=_task_context()), events.append
        )

        self.assertEqual(SpecialistTurnStatus.COMPLETED, result.status)
        self.assertEqual("PASSED", result.structured_output["precheckStatus"])
        self.assertTrue(result.structured_output["precheckPassed"])
        self.assertTrue(result.structured_output["canStartExecution"])
        self.assertEqual(("precheck://9001/summary", "precheck://9001/connector", "precheck://9001/primary-key"),
                         result.structured_output["detailsReferences"])
        self.assertFalse(result.structured_output["persisted"])
        self.assertFalse(result.structured_output["published"])
        self.assertFalse(result.structured_output["executed"])
        self.assertTrue(result.structured_output["readOnly"])
        self.assertEqual("9001", control_plane.requests[0].task_id)
        self.assertEqual("do-not-leak", control_plane.requests[0].configuration["password"])
        self.assertEqual(1, len(model.requests))
        self.assertEqual("tenant-1", model.requests[0].audit_scope.tenant_id)
        self.assertEqual("project-1", model.requests[0].audit_scope.project_id)
        self.assertEqual("user-1", model.requests[0].audit_scope.actor_id)
        self.assertEqual("session-precheck-1", model.requests[0].audit_scope.session_id)
        self.assertEqual("turn-precheck-1", model.requests[0].audit_scope.trace_id)
        self.assertNotIn("do-not-leak", str(model.requests[0]))
        self.assertNotIn("SELECT id, name", str(model.requests[0]))
        self.assertNotIn("rawPrompt", result.model_invocation_summary)
        self.assertFalse(result.model_invocation_summary["reasoningStored"])
        self.assertTrue(all(event["payloadPolicy"].startswith("LOW_SENSITIVE") for event in events))
        self.assertNotIn("do-not-leak", str(events))
        self.assertNotIn("SELECT id, name", str(events))

    def test_failed_items_wait_for_input_and_close_all_lifecycle_side_effects(self) -> None:
        """控制面发现失败项时返回用户可修复的 WAITING_FOR_INPUT，而不是假装通过。"""

        control_plane = _ControlPlane(
            {
                "taskId": "9001",
                "precheckStatus": "FAILED",
                "canStartExecution": False,
                "checks": [
                    {
                        "code": "TARGET_OBJECT_NOT_FOUND",
                        "status": "FAILED",
                        "problem": "目标表不存在或当前授权范围不可见。",
                        "suggestion": "返回配置步骤选择真实目标表后重新预检查。",
                        "configurationSteps": ["确认目标 schema 和表名。"],
                        "detailsReference": "precheck://9001/target",
                    },
                    {
                        "code": "TARGET_PRIMARY_KEY",
                        "status": "BLOCKED",
                        "problem": "目标主键事实未通过后端检查。",
                        "suggestion": "补充或修正目标主键配置。",
                        "detailsReference": "precheck://9001/key",
                    },
                ],
                "issueCodes": ["TARGET_OBJECT_NOT_FOUND", "TARGET_PRIMARY_KEY"],
            }
        )
        model = _ExplanationModel(
            PrecheckExplanationModelOutput(
                public_summary="后端返回了需要修复的检查项。",
                configuration_steps=("修正配置后重新执行预检查。",),
            )
        )

        result = PrecheckSpecialistAgent(control_plane, model).execute(_request(context=_task_context()))

        self.assertEqual(SpecialistTurnStatus.WAITING_FOR_INPUT, result.status)
        self.assertEqual("BLOCKED", result.structured_output["precheckStatus"])
        self.assertFalse(result.structured_output["precheckPassed"])
        self.assertFalse(result.structured_output["canStartExecution"])
        self.assertEqual(1, result.structured_output["failedCount"])
        self.assertEqual(1, result.structured_output["blockedCount"])
        self.assertIn("目标表不存在", " ".join(result.structured_output["problems"]))
        self.assertIn("确认目标 schema 和表名。", result.structured_output["configurationSteps"])
        self.assertEqual(
            ("precheck://9001/target", "precheck://9001/key"),
            result.structured_output["detailsReferences"],
        )
        self.assertFalse(result.structured_output["persisted"])
        self.assertFalse(result.structured_output["published"])
        self.assertFalse(result.structured_output["executed"])

    def test_warning_items_complete_but_keep_warning_and_backend_gate(self) -> None:
        """警告不是技术失败，但必须原样保留并提示用户确认。"""

        control_plane = _ControlPlane(
            {
                "taskId": "9001",
                "precheckStatus": "WARNING",
                "canStartExecution": True,
                "checks": [
                    {"code": "TARGET_NOT_EMPTY", "status": "WARNING", "problem": "目标表已有数据。", "suggestion": "确认写入策略。"},
                    {"code": "CONNECTOR_COMPATIBLE", "status": "PASSED", "problem": "连接器能力已返回。"},
                ],
            }
        )
        model = _ExplanationModel(PrecheckExplanationModelOutput(public_summary="已整理警告说明。"))

        result = PrecheckSpecialistAgent(control_plane, model).execute(_request(context=_task_context()))

        self.assertEqual(SpecialistTurnStatus.COMPLETED, result.status)
        self.assertEqual("WARNING", result.structured_output["precheckStatus"])
        self.assertEqual(1, result.structured_output["warningCount"])
        self.assertTrue(result.structured_output["canStartExecution"])
        self.assertIn("确认写入策略。", result.structured_output["suggestions"])
        self.assertFalse(result.structured_output["persisted"])

    def test_accepts_legacy_top_level_pass_without_inventing_a_blocking_item(self) -> None:
        """兼容只返回 READY_TO_EXECUTE 和闸门字段的真实后端 DTO。"""

        control_plane = _ControlPlane(
            {"taskId": "9001", "precheckStatus": "READY_TO_EXECUTE", "canStartExecution": True}
        )
        model = _ExplanationModel(PrecheckExplanationModelOutput(public_summary="已整理顶层预检查状态。"))

        result = PrecheckSpecialistAgent(control_plane, model).execute(_request(context=_task_context()))

        self.assertEqual(SpecialistTurnStatus.COMPLETED, result.status)
        self.assertEqual("PASSED", result.structured_output["precheckStatus"])
        self.assertEqual((), result.structured_output["checks"])
        self.assertTrue(result.structured_output["canStartExecution"])

    def test_control_plane_exception_is_technical_failure_without_raw_exception(self) -> None:
        """控制面异常只返回稳定错误码，不把 URL、SQL 或凭据写入结果和事件。"""

        secret_error = RuntimeError("https://secret.example failed password=hidden SELECT * FROM private_rows")
        control_plane = _ControlPlane(secret_error)
        model = _ExplanationModel(PrecheckExplanationModelOutput(public_summary="不应被调用"))
        events: list[Mapping[str, Any]] = []

        result = PrecheckSpecialistAgent(control_plane, model).execute(
            _request(context=_task_context()), events.append
        )

        self.assertEqual(SpecialistTurnStatus.FAILED, result.status)
        self.assertEqual("PRECHECK_CONTROL_PLANE_FAILED", result.error_code)
        self.assertEqual([], model.requests)
        self.assertNotIn("secret.example", str(result.to_summary()))
        self.assertNotIn("password=hidden", str(result.to_summary()))
        self.assertNotIn("private_rows", str(events))
        self.assertEqual("FAILED", result.tool_activities[0].status)

    def test_model_exception_falls_back_to_the_successful_control_plane_result(self) -> None:
        """解释模型异常只关闭模型文本，不能推翻已经通过的确定性控制面检查。"""

        control_plane = _ControlPlane(_passed_result())
        model = _ExplanationModel(RuntimeError("hidden chain and provider token"))

        result = PrecheckSpecialistAgent(control_plane, model).execute(_request(context=_task_context()))

        self.assertEqual(SpecialistTurnStatus.COMPLETED, result.status)
        self.assertIsNone(result.error_code)
        self.assertEqual("SUCCEEDED", result.tool_activities[0].status)
        self.assertNotIn("hidden chain", str(result.to_summary()))
        self.assertNotIn("provider token", str(result.to_summary()))
        self.assertTrue(result.structured_output["canStartExecution"])
        self.assertEqual("FALLBACK_TO_CONTROL_PLANE_FACTS", result.structured_output["modelExplanationStatus"])
        self.assertIn(
            "PRECHECK_MODEL_EXPLANATION_FAILED",
            result.structured_output["modelExplanationIssueCodes"],
        )
        self.assertFalse(result.structured_output["persisted"])

    def test_rejects_model_claims_about_table_field_or_primary_key_status(self) -> None:
        """模型不能通过解释文本自行声称表、字段和主键已经通过。"""

        control_plane = _ControlPlane(_passed_result())
        model = _ExplanationModel(PrecheckExplanationModelOutput(public_summary="目标表、字段和主键均已通过。"))

        result = PrecheckSpecialistAgent(control_plane, model).execute(_request(context=_task_context()))

        self.assertEqual(SpecialistTurnStatus.COMPLETED, result.status)
        self.assertIsNone(result.error_code)
        self.assertTrue(result.structured_output["canStartExecution"])
        self.assertIsNone(result.structured_output["modelExplanation"])
        self.assertIn(
            "PRECHECK_MODEL_UNTRUSTED_FACT_CLAIM",
            result.structured_output["modelExplanationIssueCodes"],
        )

    def test_rejects_model_requested_publish_or_run_action(self) -> None:
        """解释模型返回工具或副作用动作时必须被拒绝，即使本轮工具白名单包含写工具。"""

        control_plane = _ControlPlane(_passed_result())
        model = _ExplanationModel(
            PrecheckExplanationModelOutput(
                public_summary="检查结果已整理。",
                requested_actions=("sync.task.publish", "sync.task.run"),
            )
        )

        result = PrecheckSpecialistAgent(control_plane, model).execute(
            _request(allowed_tools=(PRECHECK_TOOL_CODE, "sync.task.publish", "sync.task.run"), context=_task_context())
        )

        self.assertEqual(SpecialistTurnStatus.COMPLETED, result.status)
        self.assertIsNone(result.error_code)
        self.assertTrue(result.structured_output["canStartExecution"])
        self.assertIn(
            "PRECHECK_MODEL_UNAUTHORIZED_ACTION",
            result.structured_output["modelExplanationIssueCodes"],
        )
        self.assertFalse(result.structured_output["persisted"])
        self.assertFalse(result.structured_output["published"])
        self.assertFalse(result.structured_output["executed"])


if __name__ == "__main__":
    unittest.main()
