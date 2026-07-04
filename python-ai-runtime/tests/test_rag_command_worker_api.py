"""RAG command worker API tests."""

import json
import os
import sys
import tempfile
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.api.agent.rag_command_worker import (
    RAG_COMMAND_WORKER_API_SCHEMA_VERSION,
    rag_command_worker_request_from_payload,
    register_rag_command_worker_routes,
)
from datasmart_ai_runtime.config import default_model_routes
from datasmart_ai_runtime.services.agent_execution import LangGraphDurableCheckpointerService
from datasmart_ai_runtime.services.model_gateway import ModelGatewayGovernanceService, ModelProviderRegistry
from datasmart_ai_runtime.services.model_gateway.model_router import ModelRouteRegistry
from datasmart_ai_runtime.services.rag import (
    RAG_COMMAND_WORKER_API_PAYLOAD_POLICY,
    RAG_COMMAND_WORKER_RUNNER_SCHEMA_VERSION,
    LocalFileRagAnswerArtifactWriter,
    RagCommandWorkerRunner,
    build_default_governance_rag_pipeline,
)
from datasmart_ai_runtime.services.tools.command_worker_receipt_client import CommandWorkerReceiptPostResult


class RagCommandWorkerApiTest(unittest.TestCase):
    """验证 `knowledge.rag.query` 已进入内部 command worker 闭环。"""

    def test_request_builder_reads_control_facts_and_short_lived_arguments(self) -> None:
        """payload builder 应区分控制面 facts 和短生命周期 RAG question。"""

        request = rag_command_worker_request_from_payload(
            {
                "arguments": {
                    "question": "DataSmart RAG 管线有哪些阶段？",
                    "generateAnswer": False,
                },
                "controlFacts": {
                    "commandId": "taoc_rag_worker_001",
                    "runId": "run-rag-worker",
                    "sessionId": "session-rag-worker",
                    "tenantId": "10",
                    "projectId": "20",
                    "actorId": "30",
                    "queryRef": "rag-query:sha256:explicitqueryref",
                },
            }
        )

        self.assertEqual("taoc_rag_worker_001", request.command_id)
        self.assertEqual("run-rag-worker", request.run_id)
        self.assertEqual("session-rag-worker", request.session_id)
        self.assertEqual("10", request.tenant_id)
        self.assertEqual("rag-query:sha256:explicitqueryref", request.query_ref)
        self.assertFalse(request.generate_answer)

    def test_internal_route_executes_rag_and_returns_only_low_sensitive_worker_result(self) -> None:
        """内部 worker route 应执行 RAG，但响应不能返回 question、answer 或 evidence 正文。"""

        app = FakeApp()
        runner = self._runner()
        checkpointer = LangGraphDurableCheckpointerService()
        register_rag_command_worker_routes(
            app,
            worker_runner=runner,
            langgraph_checkpointer_service=checkpointer,
        )

        raw_question_marker = "INTERNAL_CUSTOMER_SECRET_ALPHA"
        response = app.post_routes["/internal/agent/rag/command-worker/run"](
            {
                "arguments": {
                    "question": f"DataSmart RAG 管线有哪些阶段？{raw_question_marker}",
                    "generateAnswer": False,
                    "topK": 3,
                },
                "controlFacts": {
                    "commandId": "taoc_rag_worker_002",
                    "runId": "run-rag-worker",
                    "sessionId": "session-rag-worker",
                    "tenantId": "10",
                    "projectId": "20",
                    "actorId": "30",
                    "answerArtifactReference": "agent-artifact:run-rag-worker/taoc_rag_worker_002/rag-answer",
                    "langGraphThreadId": "rag-worker-thread-test",
                },
            }
        )
        serialized = json.dumps(response, ensure_ascii=False)

        self.assertEqual(RAG_COMMAND_WORKER_API_SCHEMA_VERSION, response["schemaVersion"])
        self.assertEqual(RAG_COMMAND_WORKER_RUNNER_SCHEMA_VERSION, response["workerRunnerSchemaVersion"])
        self.assertEqual("knowledge.rag.query", response["workerResult"]["toolCode"])
        self.assertTrue(response["workerResult"]["queryRef"].startswith("rag-query:sha256:"))
        self.assertEqual(RAG_COMMAND_WORKER_API_PAYLOAD_POLICY, response["payloadPolicy"])
        self.assertEqual("RAG_QUERY_COMPLETED", response["javaReceiptPayload"]["outcome"])
        self.assertEqual("knowledge.rag.query", response["javaReceiptPayload"]["toolCode"])
        self.assertEqual("READ_ONLY_QUERY_SUMMARY", response["javaReceiptPayload"]["workerReceiptMode"])
        self.assertFalse(response["javaReceiptPayload"]["sideEffectExecuted"])
        self.assertEqual("rag-worker-thread-test", response["langGraphCheckpoint"]["threadId"])
        self.assertIn("KNOWLEDGE_AGENT", response["langGraphCheckpoint"]["multiAgentRecovery"]["agentRoles"])
        self.assertNotIn(raw_question_marker, serialized)
        self.assertNotIn('"compressedContext":', serialized)
        self.assertNotIn("selectedChunks", serialized)
        self.assertNotIn("sourceUri", serialized)
        self.assertNotIn("answer\":", serialized)

    def test_route_can_post_low_sensitive_receipt_to_injected_java_client(self) -> None:
        """postToJava=true 时，应只把低敏 javaReceiptPayload 交给注入的 Java client。"""

        app = FakeApp()
        fake_client = FakeReceiptClient()
        register_rag_command_worker_routes(
            app,
            worker_runner=self._runner(),
            receipt_client=fake_client,
        )

        response = app.post_routes["/internal/agent/rag/command-worker/run"](
            {
                "arguments": {
                    "question": "DataSmart RAG 管线有哪些阶段？INTERNAL_POST_SECRET",
                    "generateAnswer": False,
                },
                "controlFacts": {
                    "commandId": "taoc_rag_worker_003",
                    "runId": "run-rag-worker-post",
                    "sessionId": "session-rag-worker-post",
                    "queryRef": "rag-query:sha256:postreceipt",
                    "answerArtifactReference": "agent-artifact:run-rag-worker-post/taoc_rag_worker_003/rag-answer",
                },
                "postToJava": True,
            }
        )

        self.assertEqual("session-rag-worker-post", fake_client.session_id)
        self.assertEqual("run-rag-worker-post", fake_client.run_id)
        self.assertEqual("taoc_rag_worker_003", fake_client.receipt.java_payload["commandId"])
        self.assertEqual("rag-query:sha256:postreceipt", fake_client.receipt.java_payload["auditId"])
        self.assertTrue(response["postResult"]["posted"])
        self.assertNotIn("INTERNAL_POST_SECRET", json.dumps(fake_client.receipt.java_payload, ensure_ascii=False))

    def test_internal_route_writes_answer_artifact_and_keeps_response_metadata_only(self) -> None:
        """启用 artifact writer 后，worker route 应只返回 artifact 元数据，不返回答案正文。"""

        app = FakeApp()
        checkpointer = LangGraphDurableCheckpointerService()
        with tempfile.TemporaryDirectory() as temp_dir:
            register_rag_command_worker_routes(
                app,
                worker_runner=self._runner(artifact_root=temp_dir),
                langgraph_checkpointer_service=checkpointer,
            )

            response = app.post_routes["/internal/agent/rag/command-worker/run"](
                {
                    "arguments": {
                        "question": "DataSmart RAG 管线有哪些阶段？INTERNAL_ARTIFACT_SECRET",
                        "generateAnswer": False,
                    },
                    "controlFacts": {
                        "commandId": "taoc_rag_worker_artifact",
                        "runId": "run-rag-worker-artifact",
                        "sessionId": "session-rag-worker-artifact",
                        "tenantId": "10",
                        "projectId": "20",
                        "actorId": "30",
                        "langGraphThreadId": "rag-worker-artifact-thread",
                    },
                }
            )
            serialized = json.dumps(response, ensure_ascii=False)
            artifact_files = [
                path for path in os.listdir(temp_dir)
            ]

            artifact_write = response["artifactWrite"]
            self.assertIsNotNone(artifact_write)
            self.assertEqual(artifact_write["artifactReference"], response["javaReceiptPayload"]["artifactReference"])
            self.assertTrue(response["javaReceiptPayload"]["artifactAvailable"])
            self.assertTrue(response["workerResult"]["ragExecutionSummary"]["artifactReferencePresent"])
            self.assertNotIn("INTERNAL_ARTIFACT_SECRET", serialized)
            self.assertNotIn('"compressedContext":', serialized)
            self.assertNotIn('"answer":', serialized)
            self.assertGreaterEqual(len(artifact_files), 1)
            latest = checkpointer.latest_for_thread("rag-worker-artifact-thread")
            self.assertIsNotNone(latest)
            self.assertTrue(latest.state["generation"]["answerStored"])
            self.assertIn("artifact", latest.state["generation"])
            self.assertEqual(
                artifact_write["artifactReference"],
                latest.state["generation"]["artifact"]["artifactReference"],
            )

    def _runner(self, *, artifact_root: str | None = None) -> RagCommandWorkerRunner:
        routes = ModelRouteRegistry(default_model_routes())
        pipeline = build_default_governance_rag_pipeline(
            model_routes=routes,
            model_gateway=ModelGatewayGovernanceService(routes),
            model_providers=ModelProviderRegistry(),
        )
        return RagCommandWorkerRunner(
            rag_pipeline=pipeline,
            artifact_writer=LocalFileRagAnswerArtifactWriter(artifact_root) if artifact_root else None,
        )


class FakeReceiptClient:
    """记录 Python worker 是否只向 Java client 提交了低敏 receipt。"""

    def __init__(self) -> None:
        self.session_id = None
        self.run_id = None
        self.receipt = None

    def post_receipt(self, *, session_id, run_id, receipt, trace_id=None):
        self.session_id = session_id
        self.run_id = run_id
        self.receipt = receipt
        return CommandWorkerReceiptPostResult(
            attempted=True,
            posted=True,
            skipped=False,
            duplicate=False,
            status_code=200,
            identity_key=f"worker-receipt:{run_id}:{receipt.java_payload['commandId']}",
            outcome=receipt.outcome.value,
            error_code=None,
            endpoint_configured=True,
            message="fake java receipt accepted",
        )


class FakeApp:
    """极简 FastAPI 替身。"""

    def __init__(self) -> None:
        self.post_routes = {}

    def post(self, path):
        """模拟 FastAPI post decorator。"""

        def decorator(handler):
            self.post_routes[path] = handler
            return handler

        return decorator


if __name__ == "__main__":
    unittest.main()
