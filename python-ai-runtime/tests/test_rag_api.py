"""RAG API 路由测试。

不启动真实 FastAPI，只用 FakeApp 捕获 decorator，验证路由合同和低敏诊断。
"""

import os
import sys
import time
import unittest
from datetime import datetime, timezone
from unittest.mock import patch

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.api.rag import rag_query_from_payload, register_rag_routes
from datasmart_ai_runtime.api.gateway.signature import sign_gateway_payload
from datasmart_ai_runtime.config import default_model_routes
from datasmart_ai_runtime.domain.contracts import ModelInvocationResult
from datasmart_ai_runtime.services.agent_execution import LangGraphDurableCheckpointerService
from datasmart_ai_runtime.services.model_gateway import ModelGatewayGovernanceService, ModelProviderRegistry
from datasmart_ai_runtime.services.model_gateway.model_router import ModelRouteRegistry
from datasmart_ai_runtime.services.rag import (
    GraphRagEdge,
    GraphRagEntity,
    InMemoryGraphRag,
    InMemoryRagKnowledgeBase,
    RagDocument,
    RagHybridRetriever,
    RagKnowledgeBaseSettings,
    RagPipeline,
    RagRetrievalDecisionRouter,
    build_default_governance_rag_pipeline,
)


class _AutoRouteFakeQueryResult:
    """为 API 回归提供只返回路由 JSON 的模型替身。"""

    def __init__(self) -> None:
        self.result = ModelInvocationResult(
            provider_name="test-provider",
            model_name="test-router",
            content='{"mode":"hybrid_graph","confidence":0.94,"reason":"需要关系链和原文依据"}',
        )

    def to_summary(self) -> dict[str, object]:
        """返回不含模型原始文本的低敏摘要。"""

        return {
            "providerInvoked": True,
            "providerSucceeded": True,
            "actualModelName": self.result.model_name,
        }


class _AutoRouteFakeQueryEngine:
    """只记录自主路径决策调用，不生成最终答案。"""

    def invoke(self, request, *, context):
        """模拟受治理模型调用入口。"""

        return _AutoRouteFakeQueryResult()


class RagApiTest(unittest.TestCase):
    """验证 RAG HTTP 路由。"""

    def test_auto_route_api_records_model_decision_and_combined_evidence(self) -> None:
        """HTTP auto 路径必须把模型选择和 C/G 两类证据一起返回。"""

        app = FakeApp()
        routes = ModelRouteRegistry(default_model_routes())
        gateway = ModelGatewayGovernanceService(routes)
        document = RagDocument(
            document_id="org-runbook",
            title="组织关系说明手册",
            content="小张就是张三。张三的上级是李四，李四的上级是王五。这里同时给出关系依据。",
            source_uri="memory://org-runbook",
            tenant_id="tenant-a",
            project_id="project-a",
            workspace_key="workspace-a",
        )
        graph = InMemoryGraphRag(
            entities=(
                GraphRagEntity("person-zhang", "张三", ("小张",), "tenant-a", "project-a", "workspace-a", "internal"),
                GraphRagEntity("person-li", "李四", (), "tenant-a", "project-a", "workspace-a", "internal"),
                GraphRagEntity("person-wang", "王五", (), "tenant-a", "project-a", "workspace-a", "internal"),
            ),
            edges=(
                GraphRagEdge("person-zhang", "person-li", "REPORTS_TO", "org-runbook", "memory://org-runbook", "org-1", None, None, None, 0.94, "active", "tenant-a", "project-a", "workspace-a", "internal"),
                GraphRagEdge("person-li", "person-wang", "REPORTS_TO", "org-runbook", "memory://org-runbook", "org-2", None, None, None, 0.93, "active", "tenant-a", "project-a", "workspace-a", "internal"),
            ),
        )
        query_engine = _AutoRouteFakeQueryEngine()
        pipeline = RagPipeline(
            retriever=RagHybridRetriever(InMemoryRagKnowledgeBase((document,))),
            model_routes=routes,
            model_gateway=gateway,
            model_providers=ModelProviderRegistry(),
            query_engine=query_engine,
            graph_rag_provider=graph,
            retrieval_decision_router=RagRetrievalDecisionRouter(
                model_routes=routes,
                query_engine=query_engine,
                graph_available=True,
            ),
        )
        register_rag_routes(app, rag_pipeline=pipeline)

        response = app.post_routes["/agent/rag/query"](
            {
                "tenantId": "tenant-a",
                "projectId": "project-a",
                "workspaceKey": "workspace-a",
                "actorId": "owner-a",
                "question": "小张的上级的上级是谁，以及关系依据是什么？",
                "generateAnswer": False,
                # 真实前端会显式提交 auto；这里同时覆盖 API 默认值和显式 auto 合同。
                "retrievalMode": "auto",
            }
        )

        self.assertEqual("MODEL", response["retrievalSummary"]["decisionSource"])
        self.assertEqual("hybrid_graph", response["retrievalSummary"]["decisionMode"])
        self.assertTrue(any(item["citationId"].startswith("C") for item in response["citations"]))
        graph_citations = response.get("graphCitations") or ()
        self.assertTrue(graph_citations)
        self.assertTrue(any(str(item.get("citationId", "")).startswith("G") for item in graph_citations))

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
                "retrievalMode": "lexical",
                "sourceTypes": "exact_search",
                "graphMaxHops": 2,
                "graphStartEntity": "person-zhang",
                "graphRelation": "REPORTS_TO",
                "graphHops": 2,
                "graphAsOf": "2026-08-20T12:00:00+00:00",
            }
        )

        self.assertEqual("tenant-a", query.tenant_id)
        self.assertEqual("project-a", query.project_id)
        self.assertEqual("workspace-a", query.workspace_key)
        self.assertEqual(3, query.top_k)
        self.assertFalse(query.generate_answer)
        self.assertEqual("lexical", query.retrieval_mode)
        self.assertEqual(("exact_search",), query.source_types)
        self.assertEqual(2, query.graph_max_hops)
        self.assertEqual("person-zhang", query.graph_start_entity)
        self.assertEqual("REPORTS_TO", query.graph_relation)
        self.assertEqual(2, query.graph_hops)
        self.assertEqual("2026-08-20T12:00:00+00:00", query.graph_as_of)

    def test_payload_builder_accepts_multiple_source_types(self) -> None:
        query = rag_query_from_payload(
            {
                "question": "find a matching recovery case",
                "sourceTypes": ["wiki", "git_history"],
            }
        )

        self.assertEqual(("wiki", "git_history"), query.source_types)

    def test_route_returns_rag_answer_and_low_sensitive_diagnostics(self) -> None:
        """路由应返回引用证据，诊断不应返回完整文档正文。"""

        app = FakeApp()
        routes = ModelRouteRegistry(default_model_routes())
        pipeline = build_default_governance_rag_pipeline(
            model_routes=routes,
            model_gateway=ModelGatewayGovernanceService(routes),
            model_providers=ModelProviderRegistry(),
            knowledge_base_settings=RagKnowledgeBaseSettings(
                runtime_mode="test",
                store_type="in-memory",
            ),
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
            knowledge_base_settings=RagKnowledgeBaseSettings(
                runtime_mode="test",
                store_type="in-memory",
            ),
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

    def test_graph_route_returns_two_hop_path_and_complete_citations(self) -> None:
        """GraphRAG API 应返回实体路径、每跳来源和关系边时间，而不是普通 chunk 假答案。"""

        app = FakeApp()
        routes = ModelRouteRegistry(default_model_routes())
        graph = InMemoryGraphRag(
            entities=(
                GraphRagEntity("person-zhang", "张三", ("小张",), "tenant-a", "project-a", "workspace-a", "internal"),
                GraphRagEntity("person-li", "李四", (), "tenant-a", "project-a", "workspace-a", "internal"),
                GraphRagEntity("person-wang", "王五", (), "tenant-a", "project-a", "workspace-a", "internal"),
            ),
            edges=(
                GraphRagEdge(
                    "person-zhang", "person-li", "REPORTS_TO", "doc-zhang-li", "memory://doc-zhang-li", "chunk-1",
                    datetime(2026, 1, 1, tzinfo=timezone.utc), datetime(2026, 1, 1, tzinfo=timezone.utc), None,
                    0.91, "active", "tenant-a", "project-a", "workspace-a", "internal",
                ),
                GraphRagEdge(
                    "person-li", "person-wang", "REPORTS_TO", "doc-li-wang", "memory://doc-li-wang", "chunk-2",
                    datetime(2026, 1, 1, tzinfo=timezone.utc), datetime(2026, 1, 1, tzinfo=timezone.utc), None,
                    0.87, "active", "tenant-a", "project-a", "workspace-a", "internal",
                ),
            ),
        )
        pipeline = build_default_governance_rag_pipeline(
            model_routes=routes,
            model_gateway=ModelGatewayGovernanceService(routes),
            model_providers=ModelProviderRegistry(),
            graph_rag_provider=graph,
            knowledge_base_settings=RagKnowledgeBaseSettings(runtime_mode="test", store_type="in-memory"),
        )
        register_rag_routes(app, rag_pipeline=pipeline)

        response = app.post_routes["/agent/rag/query"](
            {
                "tenantId": "tenant-a",
                "projectId": "project-a",
                "workspaceKey": "workspace-a",
                "actorId": "owner-a",
                "question": "小张的上级的上级是谁",
                "retrievalMode": "graph",
                "graphAsOf": "2026-08-20T12:00:00+00:00",
            }
        )

        self.assertEqual("王五", response["answer"])
        self.assertEqual(("doc-zhang-li", "doc-li-wang"), tuple(item["sourceDocumentId"] for item in response["graphCitations"]))
        self.assertEqual(("memory://doc-zhang-li", "memory://doc-li-wang"), tuple(item["sourceUri"] for item in response["graphCitations"]))
        self.assertEqual((1, 2), tuple(item["hop"] for item in response["graphPath"]))
        self.assertEqual((), tuple(response["selectedChunks"]), "图路径应通过 graphPath/graphCitations 表达，不应伪造 chunk")

    def test_graph_route_refuses_conflict_and_does_not_fallback_to_plain_rag(self) -> None:
        """图关系冲突时必须拒答，不能借普通文档相似度给出一个猜测的上级。"""

        app = FakeApp()
        routes = ModelRouteRegistry(default_model_routes())
        graph = InMemoryGraphRag(
            entities=(
                GraphRagEntity("person", "张三", (), "tenant-a", "project-a", "workspace-a", "internal"),
                GraphRagEntity("manager-1", "李四", (), "tenant-a", "project-a", "workspace-a", "internal"),
                GraphRagEntity("manager-2", "王五", (), "tenant-a", "project-a", "workspace-a", "internal"),
            ),
            edges=(
                GraphRagEdge("person", "manager-1", "REPORTS_TO", "doc-1", "memory://doc-1", "chunk-1", None, None, None, 0.9, "active", "tenant-a", "project-a", "workspace-a", "internal"),
                GraphRagEdge("person", "manager-2", "REPORTS_TO", "doc-2", "memory://doc-2", "chunk-2", None, None, None, 0.9, "active", "tenant-a", "project-a", "workspace-a", "internal"),
            ),
        )
        pipeline = build_default_governance_rag_pipeline(
            model_routes=routes,
            model_gateway=ModelGatewayGovernanceService(routes),
            model_providers=ModelProviderRegistry(),
            graph_rag_provider=graph,
            knowledge_base_settings=RagKnowledgeBaseSettings(runtime_mode="test", store_type="in-memory"),
        )
        register_rag_routes(app, rag_pipeline=pipeline)

        response = app.post_routes["/agent/rag/query"](
            {
                "tenantId": "tenant-a",
                "projectId": "project-a",
                "workspaceKey": "workspace-a",
                "actorId": "owner-a",
                "question": "张三的上级是谁",
                "retrievalMode": "graph",
            }
        )

        self.assertEqual("CONFLICTING_CURRENT_EDGES", response["graphRefusalReason"])
        self.assertEqual((), tuple(response["citations"]))
        self.assertEqual((), tuple(response["graphCitations"]))

    def test_fastapi_route_reads_signed_gateway_headers_instead_of_forged_body_scope(self) -> None:
        """真实 FastAPI 路由必须拿到 Request Header，不能退化为无可信来源的 500 或信任正文。"""

        try:
            from fastapi import FastAPI, Request
            from fastapi.testclient import TestClient
        except ImportError:  # pragma: no cover - 本地最小 Python 环境可跳过可选 API 依赖
            self.skipTest("FastAPI test dependency is not installed")

        app = FastAPI()
        routes = ModelRouteRegistry(default_model_routes())
        pipeline = build_default_governance_rag_pipeline(
            model_routes=routes,
            model_gateway=ModelGatewayGovernanceService(routes),
            model_providers=ModelProviderRegistry(),
            knowledge_base_settings=RagKnowledgeBaseSettings(
                runtime_mode="test",
                store_type="in-memory",
            ),
        )
        register_rag_routes(app, rag_pipeline=pipeline, request_type=Request)

        timestamp = str(int(time.time() * 1000))
        headers = {
            "X-DataSmart-Source-Service": "datasmart-govern-gateway",
            "X-Gateway-Original-Path": "/api/agent/rag/query",
            "X-Gateway-Route-Prefix": "/api/agent",
            "X-DataSmart-Trace-Id": "trace-rag-fastapi",
            "X-DataSmart-Tenant-Id": "10",
            "X-DataSmart-Application-Id": "10010",
            "X-DataSmart-Project-Id": "20",
            "X-DataSmart-Actor-Id": "1001",
            "X-DataSmart-Actor-Role": "PROJECT_OWNER",
            "X-DataSmart-Actor-Type": "USER",
            "X-DataSmart-Workspace-Id": "workspace-a",
            "X-DataSmart-Authorized-Project-Ids": "20",
            "X-DataSmart-Gateway-Signature-Version": "v1",
            "X-DataSmart-Gateway-Signature-Timestamp": timestamp,
            "X-DataSmart-Gateway-Signature-Nonce": "nonce-rag-fastapi",
            "X-DataSmart-Gateway-Signature-Key-Id": "gateway-local-v1",
        }
        headers["X-DataSmart-Gateway-Signature"] = sign_gateway_payload(
            headers,
            timestamp=timestamp,
            nonce="nonce-rag-fastapi",
            key_id="gateway-local-v1",
            secret="secret-rag-fastapi",
        )

        with patch.dict(
            os.environ,
            {
                "DATASMART_GATEWAY_SIGNATURE_REQUIRED": "true",
                "DATASMART_GATEWAY_SIGNATURE_SECRET": "secret-rag-fastapi",
                "DATASMART_GATEWAY_SIGNATURE_KEY_ID": "gateway-local-v1",
            },
            clear=False,
        ):
            response = TestClient(app).post(
                "/api/agent/rag/query",
                headers=headers,
                json={
                    "tenantId": "999",
                    "projectId": "999",
                    "actorId": "forged",
                    "workspaceKey": "forged-workspace",
                    "question": "DataSmart RAG 管线有哪些阶段？",
                    "generateAnswer": False,
                },
            )

        self.assertEqual(200, response.status_code, response.text)
        body = response.json()
        self.assertGreaterEqual(len(body["citations"]), 1)


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
