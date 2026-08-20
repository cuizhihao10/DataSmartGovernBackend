"""RAG 自主路径决策与联合证据回归测试。

这些测试不连接真实模型或 Neo4j，重点验证三个稳定合同：

1. 模型能够以结构化 JSON 选择 ``hybrid_graph``；
2. 模型输出非法时会记录规则兜底，而不是把非法内容当成路径；
3. 联合检索同时返回普通文档 ``C*`` 与图路径 ``G*``，并保持关系引用链。
"""

from __future__ import annotations

import os
import sys
import unittest


ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.config import default_model_routes
from datasmart_ai_runtime.domain.contracts import ModelInvocationResult
from datasmart_ai_runtime.domain.model_gateway import ModelGatewayRequestContext
from datasmart_ai_runtime.services.model_gateway import ModelGatewayGovernanceService, ModelProviderRegistry
from datasmart_ai_runtime.services.model_gateway.model_router import ModelRouteRegistry
from datasmart_ai_runtime.services.rag import (
    GraphRagEdge,
    GraphRagEntity,
    InMemoryGraphRag,
    InMemoryRagKnowledgeBase,
    RagDocument,
    RagHybridRetriever,
    RagPipeline,
    RagQuery,
    RagRetrievalDecisionRouter,
)


class _FakeQueryResult:
    """提供 ModelQueryEngine 最小返回形状的测试替身。"""

    def __init__(self, content: str, error_code: str | None = None) -> None:
        self.result = ModelInvocationResult(
            provider_name="test-provider",
            model_name="test-model",
            content=content,
            error_code=error_code,
        )

    def to_summary(self) -> dict[str, object]:
        """返回不含原始模型文本的低敏摘要。"""

        return {
            "providerInvoked": True,
            "providerSucceeded": self.result.error_code is None,
            "actualModelName": self.result.model_name,
            "errorCode": self.result.error_code,
        }


class _FakeQueryEngine:
    """记录检索路由模型调用，并返回固定结构化结果。"""

    def __init__(self, content: str, error_code: str | None = None) -> None:
        self.content = content
        self.error_code = error_code
        self.calls: list[tuple[object, ModelGatewayRequestContext]] = []

    def invoke(self, request, *, context):
        """模拟受治理模型调用入口。"""

        self.calls.append((request, context))
        return _FakeQueryResult(self.content, self.error_code)


def _query(
    *,
    mode: str = "auto",
    generate_answer: bool = False,
    question: str = "小张的上级的上级是谁，以及这个关系的依据是什么？",
) -> RagQuery:
    """构造带完整范围的 RAG 查询。"""

    return RagQuery(
        tenant_id="tenant-a",
        project_id="project-a",
        workspace_key="workspace-a",
        actor_id="owner-a",
        question=question,
        retrieval_mode=mode,
        generate_answer=generate_answer,
    )


class RagRetrievalRouterTest(unittest.TestCase):
    """验证模型决策、规则兜底和联合证据。"""

    def test_model_selects_hybrid_graph_and_records_low_sensitive_decision(self) -> None:
        """合法 JSON 应由模型选择联合路径，并保留决策来源。"""

        routes = ModelRouteRegistry(default_model_routes())
        engine = _FakeQueryEngine(
            '{"mode":"hybrid_graph","confidence":0.93,"reason":"需要关系链和原文依据"}'
        )
        decision = RagRetrievalDecisionRouter(
            model_routes=routes,
            query_engine=engine,
            graph_available=True,
        ).decide(_query())

        self.assertEqual("hybrid_graph", decision.mode)
        self.assertEqual("MODEL", decision.decision_source)
        self.assertEqual(0.93, decision.confidence)
        self.assertEqual(1, len(engine.calls))
        self.assertNotIn("需要关系链", str(decision.to_summary()["modelInvocation"]))

    def test_invalid_model_output_falls_back_to_conservative_rule(self) -> None:
        """非法模型输出只能触发规则兜底，不能产生未知路径。"""

        routes = ModelRouteRegistry(default_model_routes())
        engine = _FakeQueryEngine("这不是 JSON")
        decision = RagRetrievalDecisionRouter(
            model_routes=routes,
            query_engine=engine,
            graph_available=True,
        ).decide(_query())

        self.assertEqual("hybrid_graph", decision.mode)
        self.assertEqual("RULE_FALLBACK", decision.decision_source)
        self.assertIn("上级", decision.rule_signals)

    def test_model_graph_choice_is_constrained_when_graph_provider_is_unavailable(self) -> None:
        """模型不能把未装配的 GraphRAG 路径伪装成可执行能力。"""

        routes = ModelRouteRegistry(default_model_routes())
        engine = _FakeQueryEngine(
            '{"mode":"hybrid_graph","confidence":0.91,"reason":"需要关系和原文"}'
        )
        decision = RagRetrievalDecisionRouter(
            model_routes=routes,
            query_engine=engine,
            graph_available=False,
        ).decide(_query())

        self.assertEqual("hybrid", decision.mode)
        self.assertEqual("MODEL_CAPABILITY_FALLBACK", decision.decision_source)
        self.assertEqual("hybrid_graph", decision.model_mode)

    def test_hybrid_graph_returns_document_and_graph_citations(self) -> None:
        """联合模式必须同时返回 C 文档证据和 G 关系证据。"""

        documents = (
            RagDocument(
                document_id="org-runbook",
                title="组织关系说明手册",
                content="小张就是张三。张三的上级是李四，李四的上级是王五。组织架构手册给出关系依据。",
                source_uri="memory://org-runbook",
                tenant_id="tenant-a",
                project_id="project-a",
                workspace_key="workspace-a",
            ),
        )
        graph = InMemoryGraphRag(
            entities=(
                GraphRagEntity("person-zhang", "张三", ("小张",), "tenant-a", "project-a", "workspace-a", "internal"),
                GraphRagEntity("person-li", "李四", (), "tenant-a", "project-a", "workspace-a", "internal"),
                GraphRagEntity("person-wang", "王五", (), "tenant-a", "project-a", "workspace-a", "internal"),
            ),
            edges=(
                GraphRagEdge(
                    "person-zhang", "person-li", "REPORTS_TO", "org-runbook", "memory://org-runbook", "org-1",
                    None, None, None, 0.94, "active", "tenant-a", "project-a", "workspace-a", "internal",
                ),
                GraphRagEdge(
                    "person-li", "person-wang", "REPORTS_TO", "org-runbook", "memory://org-runbook", "org-2",
                    None, None, None, 0.93, "active", "tenant-a", "project-a", "workspace-a", "internal",
                ),
            ),
        )
        routes = ModelRouteRegistry(default_model_routes())
        gateway = ModelGatewayGovernanceService(routes)
        pipeline = RagPipeline(
            retriever=RagHybridRetriever(InMemoryRagKnowledgeBase(documents)),
            model_routes=routes,
            model_gateway=gateway,
            model_providers=ModelProviderRegistry(),
            graph_rag_provider=graph,
        )

        result = pipeline.answer(
            _query(mode="hybrid_graph", question="小张的上级的上级是谁")
        )
        citation_ids = tuple(citation.citation_id for citation in result.citations)

        self.assertTrue(any(value.startswith("C") for value in citation_ids))
        self.assertTrue(any(value.startswith("G") for value in citation_ids))
        self.assertEqual("hybrid_graph", result.retrieval_summary["retrievalMode"])
        self.assertEqual(2, len(result.graph_path))
        self.assertEqual(("memory://org-runbook", "memory://org-runbook"), tuple(
            item["sourceUri"] for item in result.graph_citations
        ))
        self.assertIn("关系证据", result.compressed_context)
        self.assertIn("文档证据", result.compressed_context)


if __name__ == "__main__":
    unittest.main()
