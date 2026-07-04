"""RAG API 路由测试。

不启动真实 FastAPI，只用 FakeApp 捕获 decorator，验证路由合同和低敏诊断。
"""

import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.api.rag import rag_query_from_payload, register_rag_routes
from datasmart_ai_runtime.config import default_model_routes
from datasmart_ai_runtime.services.agent_execution import LangGraphDurableCheckpointerService
from datasmart_ai_runtime.services.model_gateway import ModelGatewayGovernanceService, ModelProviderRegistry
from datasmart_ai_runtime.services.model_gateway.model_router import ModelRouteRegistry
from datasmart_ai_runtime.services.rag import build_default_governance_rag_pipeline


class RagApiTest(unittest.TestCase):
    """验证 RAG HTTP 路由。"""

    def test_payload_builder_accepts_java_style_fields(self) -> None:
        """payload builder 应兼容 Java/gateway 常用 camelCase 字段。"""

        query = rag_query_from_payload(
            {
                "tenantId": "tenant-a",
                "projectId": "project-a",
                "actorId": "owner-a",
                "workspaceKey": "workspace-a",
                "question": "RAG 如何减少幻觉？",
                "topK": 3,
                "candidateLimit": 20,
                "maxContextChars": 1200,
                "generateAnswer": False,
                "traceId": "trace-rag",
                "sessionId": "session-rag",
            }
        )

        self.assertEqual("tenant-a", query.tenant_id)
        self.assertEqual("project-a", query.project_id)
        self.assertEqual("workspace-a", query.workspace_key)
        self.assertEqual(3, query.top_k)
        self.assertFalse(query.generate_answer)

    def test_route_returns_rag_answer_and_low_sensitive_diagnostics(self) -> None:
        """路由应返回引用证据，诊断不应返回完整文档正文。"""

        app = FakeApp()
        routes = ModelRouteRegistry(default_model_routes())
        pipeline = build_default_governance_rag_pipeline(
            model_routes=routes,
            model_gateway=ModelGatewayGovernanceService(routes),
            model_providers=ModelProviderRegistry(),
        )
        register_rag_routes(app, rag_pipeline=pipeline)

        response = app.post_routes["/agent/rag/query"](
            {
                "tenantId": "tenant-a",
                "projectId": "project-a",
                "actorId": "owner-a",
                "question": "DataSmart RAG 管线有哪些阶段？",
                "generateAnswer": False,
            }
        )
        diagnostics = app.get_routes["/agent/rag/diagnostics"]()

        self.assertEqual("datasmart.rag-pipeline.v1", response["schemaVersion"])
        self.assertGreaterEqual(len(response["citations"]), 1)
        self.assertIn("scope_filter", diagnostics["algorithmStages"])
        self.assertNotIn("DataSmart 的 RAG 管线采用", str(diagnostics))

    def test_route_records_langgraph_rag_nodes_without_storing_query_or_context(self) -> None:
        """RAG 查询应写入 LangGraph 节点链路，但 checkpoint state 不能保存问题或证据正文。"""

        app = FakeApp()
        routes = ModelRouteRegistry(default_model_routes())
        pipeline = build_default_governance_rag_pipeline(
            model_routes=routes,
            model_gateway=ModelGatewayGovernanceService(routes),
            model_providers=ModelProviderRegistry(),
        )
        checkpointer = LangGraphDurableCheckpointerService()
        register_rag_routes(app, rag_pipeline=pipeline, langgraph_checkpointer_service=checkpointer)

        response = app.post_routes["/agent/rag/query"](
            {
                "tenantId": "tenant-a",
                "projectId": "project-a",
                "actorId": "owner-a",
                "workspaceKey": "workspace-a",
                "question": "DataSmart RAG 管线有哪些阶段？",
                "generateAnswer": False,
                "langGraphThreadId": "rag-thread-test",
                "traceId": "trace-rag-checkpoint",
            }
        )

        checkpoint = response["langGraphCheckpoint"]
        self.assertIsNotNone(checkpoint)
        self.assertEqual("rag-thread-test", checkpoint["threadId"])
        self.assertEqual("rag_retrieve_knowledge", checkpoint["initial"]["nodeName"])
        self.assertEqual("rag_evidence_gate", checkpoint["evidenceGate"]["nodeName"])
        self.assertEqual("rag_grounded_answer_completed", checkpoint["final"]["nodeName"])
        self.assertIn("KNOWLEDGE_AGENT", checkpoint["multiAgentRecovery"]["agentRoles"])
        events = checkpointer.events_for_thread("rag-thread-test")
        self.assertEqual(
            ("rag_retrieval_completed", "loop_iteration", "rag_grounded_answer_completed"),
            tuple(event.event_type for event in events),
        )
        latest = checkpointer.latest_for_thread("rag-thread-test")
        self.assertIsNotNone(latest)
        state_text = str(latest.state)
        self.assertNotIn("DataSmart RAG 管线有哪些阶段", state_text)
        self.assertNotIn(response["compressedContext"], state_text)
        self.assertFalse(latest.state["generation"]["answerStored"])
        self.assertFalse(latest.state["generation"]["compressedContextStored"])


class FakeApp:
    """极简 FastAPI 替身。"""

    def __init__(self) -> None:
        self.get_routes = {}
        self.post_routes = {}

    def get(self, path):
        """模拟 FastAPI get decorator。"""

        def decorator(handler):
            self.get_routes[path] = handler
            return handler

        return decorator

    def post(self, path):
        """模拟 FastAPI post decorator。"""

        def decorator(handler):
            self.post_routes[path] = handler
            return handler

        return decorator


if __name__ == "__main__":
    unittest.main()
