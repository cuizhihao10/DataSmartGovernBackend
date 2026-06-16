import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.api.agent.routes import register_agent_runtime_routes
from datasmart_ai_runtime.api.agent.tool_action_checkpoint_routes import register_tool_action_checkpoint_routes
from datasmart_ai_runtime.api.agent.tool_action_execution_checkpoint import (
    build_tool_action_execution_checkpoint_query_response,
    build_tool_action_execution_checkpoint_resume_preview_response,
)
from datasmart_ai_runtime.services.tools import (
    InMemoryToolActionExecutionCheckpointStore,
    StaticToolActionResumeFactProvider,
    ToolActionResumeFactSnapshot,
)


class FakeApp:
    """极简路由注册器，用于验证 checkpoint 子路由被主 Agent API 注册。"""

    def __init__(self) -> None:
        self.post_routes: dict[str, object] = {}
        self.websocket_routes: dict[str, object] = {}

    def post(self, path: str):
        """模拟 FastAPI 的 `@app.post(...)` 装饰器。"""

        def decorator(func):
            self.post_routes[path] = func
            return func

        return decorator

    def websocket(self, path: str):
        """模拟 FastAPI 的 `@app.websocket(...)` 装饰器。"""

        def decorator(func):
            self.websocket_routes[path] = func
            return func

        return decorator


class RejectingApprovalResumeFactProvider:
    """模拟 permission-admin 判定审批事实不可采信的 provider。"""

    def collect(self, *, checkpoint, request_payload=None):
        """返回对 approval 事实的服务端否决。"""

        return ToolActionResumeFactSnapshot(
            source="TEST_REJECTING_APPROVAL_PROVIDER",
            missing_fact_types=("APPROVAL_CONFIRMATION_FACT",),
            rejected_fact_types=("APPROVAL_CONFIRMATION_FACT",),
            error_codes=("APPROVAL_FACT_NOT_APPROVED",),
        )


class ToolActionExecutionCheckpointApiTest(unittest.TestCase):
    """工具动作 checkpoint 查询与恢复预检 API 测试。

    这组测试刻意不验证“真实恢复执行”，因为当前接口的产品定位是恢复预检：
    - 查询接口只能返回低敏 checkpoint；
    - resume-preview 只能判断事实类型是否齐备；
    - 真正 outbox 写入、worker 派发和工具执行仍必须交给 Java 控制面。
    """

    def setUp(self) -> None:
        self.store = InMemoryToolActionExecutionCheckpointStore(max_checkpoints_per_thread=5, max_total_checkpoints=20)

    def test_query_by_checkpoint_id_returns_low_sensitive_checkpoint(self) -> None:
        """按 checkpointId 查询时，可返回低敏 graphRunSummary，但不能泄露敏感正文。"""

        checkpoint = self.store.save(
            _graph_run_for_resume(),
            context={
                "source": "MCP_TOOLS_CALL",
                "protocolFamily": "MCP",
                "tenantId": "tenant-api",
                "projectId": "project-api",
                "actorId": "actor-api",
                "runId": "run-api",
            },
        )

        response = build_tool_action_execution_checkpoint_query_response(
            {
                "checkpointId": checkpoint.checkpoint_id,
                "tenantId": "tenant-api",
                "projectId": "project-api",
                "actorId": "actor-api",
                "includeGraphRun": True,
                "prompt": "raw prompt should not leak",
                "sql": "select * from hidden_table",
            },
            checkpoint_store=self.store,
        )
        serialized = str(response)

        self.assertEqual(1, response["checkpointCount"])
        self.assertEqual(checkpoint.checkpoint_id, response["checkpoints"][0]["checkpointId"])
        self.assertEqual("run-api", response["checkpoints"][0]["threadId"])
        self.assertIn("graphRunSummary", response["checkpoints"][0])
        self.assertFalse(response["queryPolicy"]["globalScanAllowed"])
        self.assertNotIn("raw prompt should not leak", serialized)
        self.assertNotIn("hidden_table", serialized)
        self.assertNotIn("ds-api-secret", serialized)

    def test_query_requires_locator_and_filters_scope(self) -> None:
        """查询必须有 checkpointId/threadId，并且作用域不匹配时不返回检查点。"""

        checkpoint = self.store.save(
            _graph_run_for_resume(),
            context={"tenantId": "tenant-a", "projectId": "project-a", "runId": "run-scope"},
        )

        missing_locator = build_tool_action_execution_checkpoint_query_response({}, checkpoint_store=self.store)
        wrong_scope = build_tool_action_execution_checkpoint_query_response(
            {
                "checkpointId": checkpoint.checkpoint_id,
                "tenantId": "tenant-b",
            },
            checkpoint_store=self.store,
        )

        self.assertEqual(0, missing_locator["checkpointCount"])
        self.assertEqual("CHECKPOINT_ID_OR_THREAD_ID_REQUIRED", missing_locator["accessIssues"][0]["code"])
        self.assertEqual(0, wrong_scope["checkpointCount"])
        self.assertEqual("CHECKPOINT_SCOPE_MISMATCH", wrong_scope["accessIssues"][0]["code"])

    def test_resume_preview_reports_missing_and_ready_facts_without_echoing_values(self) -> None:
        """resume-preview 只返回事实类型是否齐备，不回显事实 ID 或 payloadReference 值。"""

        checkpoint = self.store.save(
            _graph_run_for_resume(),
            context={"tenantId": "tenant-resume", "runId": "run-resume"},
        )

        waiting = build_tool_action_execution_checkpoint_resume_preview_response(
            {"checkpointId": checkpoint.checkpoint_id, "tenantId": "tenant-resume"},
            checkpoint_store=self.store,
        )
        ready = build_tool_action_execution_checkpoint_resume_preview_response(
            {
                "checkpointId": checkpoint.checkpoint_id,
                "tenantId": "tenant-resume",
                "resumeFacts": {
                    "graphId": "graph-sensitive-value",
                    "payloadReference": "agent-payload:resume/secret-reference",
                    "policyVersion": "tool-readiness-policy.v1",
                    "arguments": {"datasourceId": "ds-api-secret"},
                    "sql": "select * from hidden_table",
                },
            },
            checkpoint_store=self.store,
        )
        serialized = str(ready)

        self.assertFalse(waiting["resumeDecision"]["readyToResume"])
        self.assertIn("PAYLOAD_REFERENCE", waiting["resumeFacts"]["missingFactTypes"])
        self.assertTrue(ready["resumeDecision"]["readyToResume"])
        self.assertEqual("READY_FOR_DURABLE_RUNNER_RESUME", ready["resumeDecision"]["decision"])
        self.assertFalse(ready["resumeFacts"]["factValuesEchoed"])
        self.assertGreaterEqual(ready["resumeFacts"]["ignoredSensitiveFieldCount"], 1)
        self.assertFalse(ready["sideEffectBoundary"]["toolExecuted"])
        self.assertFalse(ready["sideEffectBoundary"]["outboxWritten"])
        self.assertNotIn("graph-sensitive-value", serialized)
        self.assertNotIn("agent-payload:resume/secret-reference", serialized)
        self.assertNotIn("ds-api-secret", serialized)
        self.assertNotIn("hidden_table", serialized)

    def test_resume_preview_merges_server_side_fact_provider_without_echoing_values(self) -> None:
        """服务端事实源可以补齐恢复条件，但响应仍然只能展示事实类型。"""

        checkpoint = self.store.save(
            _graph_run_for_human_resume(),
            context={"tenantId": "tenant-provider", "runId": "run-provider"},
        )
        provider = StaticToolActionResumeFactProvider(
            facts_by_checkpoint_id={
                checkpoint.checkpoint_id: {
                    "approvalConfirmationId": "approval-sensitive-value",
                    "clarificationFactId": "clarification-sensitive-value",
                    "graphId": "graph-sensitive-value",
                    "payloadReference": "payload-sensitive-reference",
                    "policyVersion": "policy-sensitive-value",
                    "sql": "select * from provider_hidden_table",
                    "arguments": {"secret": "provider-secret"},
                }
            }
        )

        response = build_tool_action_execution_checkpoint_resume_preview_response(
            {"checkpointId": checkpoint.checkpoint_id, "tenantId": "tenant-provider"},
            checkpoint_store=self.store,
            resume_fact_provider=provider,
        )
        serialized = str(response)

        self.assertTrue(response["resumeDecision"]["readyToResume"])
        self.assertEqual("STATIC_RESUME_FACT_PROVIDER", response["serverSideResumeFacts"]["source"])
        self.assertEqual(5, response["serverSideResumeFacts"]["factReferenceCount"])
        self.assertEqual((), response["resumeFacts"]["requestAcceptedFactTypes"])
        self.assertIn("GRAPH_OR_CONTRACT_EVIDENCE", response["resumeFacts"]["serverAcceptedFactTypes"])
        self.assertIn("PAYLOAD_REFERENCE", response["resumeFacts"]["serverAcceptedFactTypes"])
        self.assertIn("POLICY_VERSION", response["resumeFacts"]["serverAcceptedFactTypes"])
        self.assertIn("APPROVAL_CONFIRMATION_FACT", response["resumeFacts"]["serverAcceptedFactTypes"])
        self.assertIn("CLARIFICATION_FACT", response["resumeFacts"]["serverAcceptedFactTypes"])
        self.assertNotIn("approval-sensitive-value", serialized)
        self.assertNotIn("clarification-sensitive-value", serialized)
        self.assertNotIn("graph-sensitive-value", serialized)
        self.assertNotIn("payload-sensitive-reference", serialized)
        self.assertNotIn("policy-sensitive-value", serialized)
        self.assertNotIn("provider_hidden_table", serialized)
        self.assertNotIn("provider-secret", serialized)

    def test_resume_preview_removes_request_approval_fact_when_server_rejects_it(self) -> None:
        """服务端否决 approval 事实时，请求自报 approvalConfirmationId 不能让预检通过。"""

        checkpoint = self.store.save(
            _graph_run_for_human_resume(),
            context={"tenantId": "tenant-reject", "runId": "run-reject"},
        )

        response = build_tool_action_execution_checkpoint_resume_preview_response(
            {
                "checkpointId": checkpoint.checkpoint_id,
                "tenantId": "tenant-reject",
                "resumeFacts": {
                    "graphId": "graph-sensitive-value",
                    "payloadReference": "payload-sensitive-reference",
                    "policyVersion": "policy-sensitive-value",
                    "approvalConfirmationId": "approval-sensitive-value",
                    "clarificationFactId": "clarification-sensitive-value",
                },
            },
            checkpoint_store=self.store,
            resume_fact_provider=RejectingApprovalResumeFactProvider(),
        )
        serialized = str(response)

        self.assertFalse(response["resumeDecision"]["readyToResume"])
        self.assertIn("APPROVAL_CONFIRMATION_FACT", response["resumeFacts"]["requestAcceptedFactTypes"])
        self.assertIn("APPROVAL_CONFIRMATION_FACT", response["resumeFacts"]["rejectedFactTypes"])
        self.assertIn("APPROVAL_CONFIRMATION_FACT", response["resumeFacts"]["missingFactTypes"])
        self.assertNotIn("APPROVAL_CONFIRMATION_FACT", response["resumeFacts"]["acceptedFactTypes"])
        self.assertNotIn("approval-sensitive-value", serialized)
        self.assertNotIn("clarification-sensitive-value", serialized)

    def test_routes_register_checkpoint_query_and_resume_preview(self) -> None:
        """主 Agent API 注册函数应挂载 checkpoint 子路由。"""

        app = FakeApp()
        register_agent_runtime_routes(
            app,
            request_type=object,
            orchestrator=object(),
            event_store=None,
            session_manager=None,
            live_push_hub=None,
            event_publisher=None,
            runtime_event_replay_sources=(),
            plan_ingestion_client=None,
            control_plane_feedback_collector=None,
            runtime_event_feedback_bridge=None,
            loop_control_evaluator=None,
            second_turn_orchestrator=None,
            memory_write_governance=None,
        )

        self.assertIn("/agent/tool-actions/checkpoints/query", app.post_routes)
        self.assertIn("/agent/tool-actions/checkpoints/resume-preview", app.post_routes)


    def test_checkpoint_routes_use_injected_store(self) -> None:
        """checkpoint 子路由必须使用启动层注入的共享 store。

        这个测试看起来只是一次查询，但它保护的是生产恢复链路的关键装配关系：
        control-flow-preview 保存 checkpoint、query 查询 checkpoint、resume-preview 预检 checkpoint 必须读写同一个
        store。否则在 Redis 多实例模式接入前，API 表面看起来都存在，实际恢复时却会因为各自创建内存实例而查不到状态。
        """

        app = FakeApp()
        checkpoint = self.store.save(
            _graph_run_for_resume(),
            context={"tenantId": "tenant-route", "runId": "run-route"},
        )
        register_tool_action_checkpoint_routes(app, checkpoint_store=self.store)

        query_response = app.post_routes["/agent/tool-actions/checkpoints/query"](
            {
                "checkpointId": checkpoint.checkpoint_id,
                "tenantId": "tenant-route",
                "includeGraphRun": True,
            }
        )

        self.assertEqual(1, query_response["checkpointCount"])
        self.assertEqual(checkpoint.checkpoint_id, query_response["checkpoints"][0]["checkpointId"])
        self.assertEqual("run-route", query_response["checkpoints"][0]["threadId"])


def _graph_run_for_resume() -> dict[str, object]:
    """生成一个等待 proposal evidence 的低敏执行前图摘要。"""

    return {
        "schemaVersion": "datasmart.python-ai-runtime.tool-action-execution-graph-runner.v1",
        "previewOnly": True,
        "executionBoundary": "PRE_EXECUTION_GRAPH_RUNNER_ONLY",
        "stepCount": 1,
        "truncatedCount": 0,
        "statusCounts": {"WAITING_COMMAND_PROPOSAL_EVIDENCE": 1},
        "steps": [
            {
                "nodeType": "TOOL_ACTION_COMMAND_PROPOSAL",
                "templateId": "template-api-001",
                "toolName": "datasource.metadata.read",
                "decision": "ready",
                "outboxPreflightCandidate": True,
                "payloadPolicy": "REFERENCE_ONLY",
                "stepStatus": "WAITING_COMMAND_PROPOSAL_EVIDENCE",
                "proposalSubmission": {
                    "submissionState": "VALIDATION_FAILED",
                    "missingEvidence": ["PAYLOAD_REFERENCE_REQUIRED"],
                    "requestPayload": {
                        "graphId": "graph-api",
                        "arguments": {"datasourceId": "ds-api-secret"},
                    },
                },
                "nextAction": "COMPLETE_GRAPH_PAYLOAD_POLICY_OR_APPROVAL_EVIDENCE_THEN_RESUME",
            }
        ],
        "sideEffectBoundary": {
            "toolExecuted": False,
            "outboxWritten": False,
            "workerDispatched": False,
            "approvalCreated": False,
            "checkpointPersisted": False,
        },
        "resumeRequirements": ["GRAPH_OR_PAYLOAD_REFERENCE_OR_POLICY_EVIDENCE"],
        "arguments": {"datasourceId": "ds-api-secret"},
        "sql": "select * from hidden_table",
    }


def _graph_run_for_human_resume() -> dict[str, object]:
    """生成一个需要审批、澄清和 payload 引用共同补齐的恢复场景。"""

    data = dict(_graph_run_for_resume())
    data["statusCounts"] = {
        "WAITING_APPROVAL_FACT": 1,
        "WAITING_CLARIFICATION_FACT": 1,
        "WAITING_COMMAND_PROPOSAL_EVIDENCE": 1,
    }
    data["resumeRequirements"] = [
        "GRAPH_OR_PAYLOAD_REFERENCE_OR_POLICY_EVIDENCE",
        "APPROVAL_CONFIRMATION_FACT",
        "CLARIFICATION_FACT",
    ]
    return data


if __name__ == "__main__":
    unittest.main()
