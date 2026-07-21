import json
import os
import sys
import unittest
from datetime import datetime, timezone

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.domain.contracts import (
    AgentPlan,
    AgentRequest,
    ToolExecutionMode,
    ToolParameterIssue,
    ToolParameterIssueAction,
    ToolParameterValidationResult,
    ToolPlan,
    ToolRiskLevel,
)
from datasmart_ai_runtime.services.agent_plan_ingestion_client import (
    AgentPlanIngestionClientError,
    JavaAgentPlanIngestionClient,
)


class JavaAgentPlanIngestionClientTest(unittest.TestCase):
    """验证 Python AgentPlan 与 Java agent-runtime 控制面之间的接入契约。

    这些测试不是为了模拟 HTTP 细节，而是固定跨语言数据契约：
    - Python 侧生成的 ToolPlan 必须能转换成 Java `IngestAgentPlanRequest`；
    - 参数不完整、只能草案、可上下文补齐等治理语义必须进入 Java 控制面；
    - Java 创建 session/run/toolAudit 后，Python 必须能把真实 auditId 回写到 ToolPlan，
      否则 4.05 的真实工具结果 Provider 就无法查询 Java 执行结果。
    """

    def test_build_payload_serializes_agent_plan_for_java_ingestion(self) -> None:
        request = self._request()
        plan = self._plan(
            ToolPlan(
                tool_name="datasource.metadata.read",
                reason="模型判断需要先读取数据源元数据，再规划后续治理动作。",
                arguments={"datasourceId": 1001},
                risk_level=ToolRiskLevel.LOW,
                execution_mode=ToolExecutionMode.SYNC,
                parameter_validation=ToolParameterValidationResult(can_execute=True),
                governance_hints={
                    "modelToolCallId": "call-001",
                    "planNodeId": "datasource-metadata-read",
                    "dependsOn": (),
                    "parallelGroup": "read-only-probe",
                    "failurePolicy": "BLOCK_RUN",
                    "resultAlias": "metadata",
                    "sensitiveFields": ("datasourceId",),
                },
            )
        )

        payload = JavaAgentPlanIngestionClient.build_payload(request, plan)

        self.assertEqual(10, payload["tenantId"])
        self.assertEqual(20, payload["projectId"])
        self.assertEqual("user-a", payload["actorId"])
        self.assertEqual("agent_reasoning", payload["workloadType"])
        self.assertEqual("PROJECT", payload["isolationLevel"])
        self.assertEqual("req-001", payload["pythonRequestId"])
        self.assertEqual("ags-existing", payload["sessionId"])
        self.assertEqual("ORDINARY_USER", payload["actorRole"])
        self.assertEqual("USER", payload["actorType"])
        self.assertEqual("20:MANAGER", payload["authorizedProjectRoles"])
        self.assertEqual(1, len(payload["toolPlans"]))
        tool_payload = payload["toolPlans"][0]
        self.assertEqual("datasource.metadata.read", tool_payload["toolCode"])
        self.assertEqual(1001, tool_payload["targetResourceId"])
        self.assertEqual("low", tool_payload["riskLevel"])
        self.assertEqual("sync", tool_payload["executionMode"])
        self.assertEqual("call-001", tool_payload["governanceHints"]["modelToolCallId"])
        self.assertEqual("datasource-metadata-read", tool_payload["governanceHints"]["planNodeId"])
        self.assertEqual((), tool_payload["governanceHints"]["dependsOn"])
        self.assertEqual("read-only-probe", tool_payload["governanceHints"]["parallelGroup"])
        self.assertEqual("metadata", tool_payload["governanceHints"]["resultAlias"])
        self.assertEqual(("datasourceId",), tool_payload["governanceHints"]["sensitiveFields"])

    def test_conversation_session_id_is_not_misused_as_existing_java_session(self) -> None:
        """对话/缓存 sessionId 不得让 Java 控制面误走不存在会话的恢复路径。"""

        request = self._request()
        request.variables.pop("agentRuntimeSessionId")

        payload = JavaAgentPlanIngestionClient.build_payload(request, self._plan())

        self.assertIsNone(payload["sessionId"])
        self.assertEqual("ags-existing", request.variables["sessionId"])

    def test_build_payload_includes_parameter_issues_for_java_execution_guard(self) -> None:
        issue = ToolParameterIssue(
            parameter_name="datasourceId",
            expected_type="long",
            action=ToolParameterIssueAction.MUST_CLARIFY,
            message="缺少数据源 ID，不能直接读取元数据。",
        )
        plan = self._plan(
            ToolPlan(
                tool_name="datasource.metadata.read",
                reason="模型想读取元数据，但用户没有明确指定目标数据源。",
                arguments={},
                risk_level=ToolRiskLevel.LOW,
                execution_mode=ToolExecutionMode.SYNC,
                parameter_validation=ToolParameterValidationResult(
                    can_execute=False,
                    can_create_draft=False,
                    issues=(issue,),
                ),
                governance_hints={"modelToolCallId": "call-missing-param"},
            )
        )

        payload = JavaAgentPlanIngestionClient.build_payload(self._request(), plan)

        validation = payload["toolPlans"][0]["parameterValidation"]
        self.assertFalse(validation["canExecute"])
        self.assertFalse(validation["canCreateDraft"])
        self.assertEqual(("datasourceId",), validation["missingFields"])
        self.assertEqual("must_clarify", validation["issues"][0]["action"])

    def test_parse_response_and_attach_control_plane_refs_to_matching_tool_plan(self) -> None:
        plan = self._plan(
            ToolPlan(
                tool_name="datasource.metadata.read",
                reason="模型请求读取元数据。",
                arguments={"datasourceId": 1001},
                governance_hints={"modelToolCallId": "call-001"},
            )
        )
        result = JavaAgentPlanIngestionClient.parse_platform_response(
            {
                "code": 0,
                "data": {
                    "session": {"sessionId": "ags-001"},
                    "run": {"runId": "agr-001"},
                    "toolAudits": [
                        {
                            "auditId": "atea-001",
                            "toolCode": "datasource.metadata.read",
                            "state": "PLANNED",
                            "governanceHints": {"modelToolCallId": "call-001"},
                        },
                    ],
                },
            }
        )

        attached_plan = result.attach_to_plan(plan)
        hints = attached_plan.tool_plans[0].governance_hints

        self.assertEqual("ags-001", result.session_id)
        self.assertEqual("agr-001", result.run_id)
        self.assertEqual("atea-001", hints["agentRuntimeAuditId"])
        self.assertEqual("ags-001", hints["agentRuntimeSessionId"])
        self.assertEqual("agr-001", hints["agentRuntimeRunId"])
        self.assertEqual("PLANNED", hints["agentRuntimeAuditState"])
        self.assertEqual("call-001", hints["modelToolCallId"])

    def test_parse_response_accepts_empty_tool_audit_array_during_clarification(self) -> None:
        """A clarification-only plan creates a governed run without executable tool audits yet."""

        result = JavaAgentPlanIngestionClient.parse_platform_response(
            {
                "code": 0,
                "data": {
                    "session": {"sessionId": "ags-clarification"},
                    "run": {"runId": "agr-clarification"},
                    "toolAudits": [],
                },
            }
        )

        self.assertEqual("ags-clarification", result.session_id)
        self.assertEqual("agr-clarification", result.run_id)
        self.assertEqual((), result.tool_audit_references)

    def test_parse_response_still_rejects_non_array_tool_audits(self) -> None:
        """Malformed Java responses must remain fail-closed instead of being silently normalized."""

        with self.assertRaises(AgentPlanIngestionClientError):
            JavaAgentPlanIngestionClient.parse_platform_response(
                {
                    "code": 0,
                    "data": {
                        "session": {"sessionId": "ags-invalid"},
                        "run": {"runId": "agr-invalid"},
                        "toolAudits": None,
                    },
                }
            )

    def test_deterministic_dag_nodes_attach_audits_without_model_tool_call_id(self) -> None:
        plan = self._plan(
            ToolPlan(
                tool_name="datasource.source.connection.test",
                reason="确定性同步 DAG 先验证源端连接。",
                arguments={"datasourceId": 27},
                governance_hints={"planNodeId": "sourceConnectionTest"},
            ),
            ToolPlan(
                tool_name="datasource.target.connection.test",
                reason="确定性同步 DAG 再验证目标端连接。",
                arguments={"datasourceId": 28},
                governance_hints={"planNodeId": "targetConnectionTest"},
            ),
        )
        result = JavaAgentPlanIngestionClient.parse_platform_response(
            {
                "code": 0,
                "data": {
                    "session": {"sessionId": "ags-sync"},
                    "run": {"runId": "agr-sync"},
                    "toolAudits": [
                        {
                            "auditId": "audit-source",
                            "bindingId": "plan:agr-sync:1",
                            "toolCode": "datasource.source.connection.test",
                            "state": "PLANNED",
                            "governanceHints": {"planNodeId": "sourceConnectionTest"},
                        },
                        {
                            "auditId": "audit-target",
                            "bindingId": "plan:agr-sync:2",
                            "toolCode": "datasource.target.connection.test",
                            "state": "PLANNED",
                            "governanceHints": {},
                        },
                    ],
                },
            }
        )

        attached = result.attach_to_plan(plan)

        self.assertEqual(2, result.to_summary()["toolAuditCount"])
        self.assertEqual("audit-source", attached.tool_plans[0].governance_hints["agentRuntimeAuditId"])
        self.assertEqual("audit-target", attached.tool_plans[1].governance_hints["agentRuntimeAuditId"])

    def test_parse_platform_error_raises_client_error(self) -> None:
        with self.assertRaises(AgentPlanIngestionClientError):
            JavaAgentPlanIngestionClient.parse_platform_response(
                {
                    "code": 500,
                    "reason": "BUSINESS_STATE_CONFLICT",
                    "message": "当前会话状态不允许接入新的 AgentPlan",
                }
            )

    def test_build_payload_rejects_non_numeric_tenant_or_project_for_java_long_contract(self) -> None:
        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="20",
            actor_id="user-a",
            objective="请分析数据源结构。",
        )

        with self.assertRaises(AgentPlanIngestionClientError):
            JavaAgentPlanIngestionClient.build_payload(request, self._plan())

    def test_json_safe_serializes_datetime_in_nested_governance_snapshot(self) -> None:
        snapshot_time = datetime(2026, 7, 11, 4, 30, tzinfo=timezone.utc)
        converted = JavaAgentPlanIngestionClient._json_safe(
            {"providerHealth": {"checkedAt": snapshot_time}}
        )

        self.assertEqual("2026-07-11T04:30:00+00:00", converted["providerHealth"]["checkedAt"])
        self.assertIn("2026-07-11T04:30:00+00:00", json.dumps(converted))

    def _request(self) -> AgentRequest:
        return AgentRequest(
            tenant_id="10",
            project_id="20",
            actor_id="user-a",
            objective="请分析数据源结构，并判断是否需要生成质量规则。",
            variables={
                "workspaceId": "30",
                "sessionId": "ags-existing",
                "agentRuntimeSessionId": "ags-existing",
                "channel": "WEB_CHAT",
                "isolationLevel": "project",
                "trustedControlPlane": {
                    "requestContext": {
                        "actorRole": "ORDINARY_USER",
                        "actorType": "USER",
                        "authorizedProjectRoles": "20:MANAGER",
                    }
                },
            },
        )

    def _plan(self, *tool_plans: ToolPlan) -> AgentPlan:
        return AgentPlan(
            request_id="req-001",
            selected_route=None,
            state_trace=("INTENT_ANALYZED", "TOOL_PLANNED"),
            tool_plans=tool_plans,
            requires_human_approval=False,
            response_summary="已生成受控工具计划，等待 Java 控制面接入审计。",
            next_actions=("提交 Java agent-runtime 创建 session/run/toolAudit。",),
        )


if __name__ == "__main__":
    unittest.main()
