"""Neo4j GraphRAG 适配器测试。

测试使用只读的 fake Driver，不需要启动 Neo4j，也不记录任何连接凭据。
它重点固定“参数化查询、逐跳读取、范围过滤、冲突拒答和 Provider 工厂”
这些比具体 Driver 版本更重要的合同。
"""

from __future__ import annotations

import os
import sys
import unittest
from datetime import datetime, timezone


ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.services.rag.graph_rag import GraphRagQuery, GraphRagResultStatus
from datasmart_ai_runtime.services.rag.neo4j_graph_rag import (
    GraphRagNeo4jSettings,
    Neo4jGraphRagProvider,
    UnavailableGraphRagProvider,
    graph_rag_provider_from_env,
)


AS_OF = datetime(2026, 8, 20, 12, 0, tzinfo=timezone.utc)


def _entity(standard_id: str, name: str, aliases: tuple[str, ...] = ()) -> dict[str, object]:
    """构造 fake Neo4j 节点属性。"""

    return {
        "standard_id": standard_id,
        "canonical_name": name,
        "aliases": list(aliases),
        "tenant": "tenant-a",
        "project": "project-a",
        "workspace": "workspace-a",
        "sensitivity": "internal",
        "sensitivity_rank": 1,
        "metadata": {},
    }


def _edge(source_id: str, target_id: str, document_id: str, chunk_id: str) -> dict[str, object]:
    """构造具有完整引用链的 fake Neo4j 关系属性。"""

    return {
        "source_entity_id": source_id,
        "target_entity_id": target_id,
        "relation": "REPORTS_TO",
        "source_document_id": document_id,
        "source_uri": f"memory://{document_id}",
        "source_chunk_id": chunk_id,
        "asserted_at": "2026-01-01T00:00:00+00:00",
        "effective_at": "2026-01-01T00:00:00+00:00",
        "expires_at": None,
        "confidence": 0.9,
        "status": "active",
        "tenant": "tenant-a",
        "project": "project-a",
        "workspace": "workspace-a",
        "sensitivity": "internal",
        "sensitivity_rank": 1,
    }


class _FakeSession:
    """按参数响应两类参数化 Cypher 的最小 fake session。"""

    def __init__(self, entities: dict[str, dict[str, object]], edges: list[dict[str, object]]) -> None:
        self.entities = entities
        self.edges = edges
        self.calls: list[tuple[str, dict[str, object]]] = []

    def run(self, statement: str, **parameters: object):
        """记录查询并返回模拟 Record 映射。"""

        self.calls.append((statement, parameters))
        if "RETURN properties(entity) AS entity" in statement:
            lookup = str(parameters["lookup_alias"])
            return [
                {"entity": value}
                for value in self.entities.values()
                if lookup in {
                    str(value["standard_id"]).casefold(),
                    str(value["canonical_name"]).casefold(),
                    *(str(alias).casefold() for alias in value["aliases"]),
                }
            ]
        if "RETURN properties(source) AS source" in statement:
            source_id = str(parameters["source_id"])
            return [
                {
                    "source": self.entities[
                        edge.get("source_entity_id") or edge["_source_entity_id"]
                    ],
                    "target": self.entities[
                        edge.get("target_entity_id") or edge["_target_entity_id"]
                    ],
                    "relationship": edge,
                }
                for edge in self.edges
                if (edge.get("source_entity_id") or edge.get("_source_entity_id")) == source_id
                and edge["relation"] == parameters["relation"]
            ]
        return []

    def close(self) -> None:
        """模拟关闭会话。"""


class _FakeDriver:
    """把同一份图数据提供给每个 Neo4j session。"""

    def __init__(self, entities: dict[str, dict[str, object]], edges: list[dict[str, object]]) -> None:
        self.session_instance = _FakeSession(entities, edges)
        self.closed = False

    def session(self, **kwargs: object) -> _FakeSession:
        """忽略 database 参数，返回可检查调用记录的会话。"""

        return self.session_instance

    def close(self) -> None:
        """记录 Driver 关闭。"""

        self.closed = True


class Neo4jGraphRagProviderTest(unittest.TestCase):
    """验证 Neo4j 适配器与内存 GraphRAG 使用同一治理语义。"""

    def test_new_neo4j_contract_filters_by_application_without_workspace(self) -> None:
        """新 Cypher 只使用应用范围；旧 workspace 属性不得再成为查询条件。"""

        self.assertIn("entity.application IN", Neo4jGraphRagProvider._ENTITY_BY_ALIAS_CYPHER)
        self.assertIn("relationship.application IN", Neo4jGraphRagProvider._OUTGOING_EDGE_CYPHER)
        self.assertNotIn("workspace", Neo4jGraphRagProvider._ENTITY_BY_ALIAS_CYPHER.lower())
        self.assertNotIn("workspace", Neo4jGraphRagProvider._OUTGOING_EDGE_CYPHER.lower())

    def test_two_hop_query_returns_complete_path(self) -> None:
        """别名起点应逐跳读取并返回每条边的来源和时间。"""

        entities = {
            "person-zhang": _entity("person-zhang", "张三", ("小张",)),
            "person-li": _entity("person-li", "李四"),
            "person-wang": _entity("person-wang", "王五"),
        }
        edges = [
            _edge("person-zhang", "person-li", "doc-zhang-li", "chunk-1"),
            _edge("person-li", "person-wang", "doc-li-wang", "chunk-2"),
        ]
        driver = _FakeDriver(entities, edges)
        provider = Neo4jGraphRagProvider(
            driver,
            GraphRagNeo4jSettings(provider_type="neo4j", uri="bolt://neo4j:7687", username="neo4j", password="secret"),
        )

        result = provider.query(
            GraphRagQuery(
                question="小张的上级的上级是谁",
                tenant="tenant-a",
                project="project-a",
                workspace="workspace-a",
                sensitivity="internal",
                as_of=AS_OF,
            )
        )

        self.assertEqual(GraphRagResultStatus.SUCCESS.value, result.status)
        self.assertEqual("王五", result.answer)
        self.assertEqual(("doc-zhang-li", "doc-li-wang"), tuple(step.source_document_id for step in result.path))
        self.assertEqual(("memory://doc-zhang-li", "memory://doc-li-wang"), tuple(step.source_uri for step in result.path))
        self.assertEqual(3, len(driver.session_instance.calls))
        self.assertTrue(all("$tenant" in statement for statement, _ in driver.session_instance.calls[:3]))

    def test_relationship_endpoint_ids_can_come_from_returned_nodes(self) -> None:
        """真实写入的关系属性不重复端点 ID 时，读取仍应形成完整路径。"""

        entities = {
            "person-zhang": _entity("person-zhang", "张三"),
            "person-li": _entity("person-li", "李四"),
        }
        edge = _edge("person-zhang", "person-li", "doc-zhang-li", "chunk-1")
        edge.pop("source_entity_id")
        edge.pop("target_entity_id")
        edge["_source_entity_id"] = "person-zhang"
        edge["_target_entity_id"] = "person-li"
        driver = _FakeDriver(entities, [edge])
        provider = Neo4jGraphRagProvider(
            driver,
            GraphRagNeo4jSettings(
                provider_type="neo4j",
                uri="bolt://neo4j:7687",
                username="neo4j",
                password="secret",
            ),
        )

        result = provider.query(
            GraphRagQuery(
                question="张三的上级是谁",
                tenant="tenant-a",
                project="project-a",
                workspace="workspace-a",
                sensitivity="internal",
                as_of=AS_OF,
            )
        )

        self.assertEqual(GraphRagResultStatus.SUCCESS.value, result.status)
        self.assertEqual("李四", result.answer)
        self.assertEqual("person-zhang", result.path[0].source_entity_id)
        self.assertEqual("person-li", result.path[0].target_entity_id)

    def test_conflicting_edges_are_refused_before_answer(self) -> None:
        """同一跳出现两个当前目标时不能靠 confidence 猜一个。"""

        entities = {
            "person": _entity("person", "张三"),
            "manager-1": _entity("manager-1", "李四"),
            "manager-2": _entity("manager-2", "王五"),
        }
        driver = _FakeDriver(
            entities,
            [
                _edge("person", "manager-1", "doc-1", "chunk-1"),
                _edge("person", "manager-2", "doc-2", "chunk-2"),
            ],
        )
        provider = Neo4jGraphRagProvider(
            driver,
            GraphRagNeo4jSettings(provider_type="neo4j", uri="bolt://neo4j:7687", username="neo4j", password="secret"),
        )

        result = provider.query(
            GraphRagQuery(
                question="张三的上级是谁",
                tenant="tenant-a",
                project="project-a",
                workspace="workspace-a",
                sensitivity="internal",
                as_of=AS_OF,
            )
        )

        self.assertEqual(GraphRagResultStatus.REFUSAL.value, result.status)
        self.assertEqual("CONFLICTING_CURRENT_EDGES", result.reason_code)
        self.assertIsNone(result.answer)

    def test_provider_factory_is_disabled_by_default_and_fail_closed_when_incomplete(self) -> None:
        """未显式启用时不连接 Neo4j，启用但缺配置时返回稳定拒答 Provider。"""

        self.assertIsNone(graph_rag_provider_from_env({}))
        unavailable = graph_rag_provider_from_env({"DATASMART_GRAPH_RAG_PROVIDER": "neo4j"})
        self.assertIsInstance(unavailable, UnavailableGraphRagProvider)
        result = unavailable.query(GraphRagQuery(question="张三的上级是谁", tenant="t", project="p", workspace="w"))
        self.assertEqual(GraphRagResultStatus.REFUSAL.value, result.status)
        self.assertEqual("GRAPH_PROVIDER_CONFIGURATION_INVALID", result.reason_code)

    def test_provider_factory_passes_credentials_only_to_driver_factory(self) -> None:
        """工厂应把凭据传给 Driver，但诊断对象和 Provider 诊断不能回显密码。"""

        captured: dict[str, object] = {}

        def driver_factory(uri: str, **kwargs: object) -> _FakeDriver:
            captured["uri"] = uri
            captured.update(kwargs)
            return _FakeDriver({}, [])

        provider = graph_rag_provider_from_env(
            {
                "DATASMART_GRAPH_RAG_PROVIDER": "neo4j",
                "DATASMART_GRAPH_RAG_NEO4J_URI": "bolt://neo4j:7687",
                "DATASMART_GRAPH_RAG_NEO4J_USERNAME": "neo4j",
                "DATASMART_GRAPH_RAG_NEO4J_PASSWORD": "secret-value",
            },
            driver_factory=driver_factory,
        )

        self.assertIsInstance(provider, Neo4jGraphRagProvider)
        self.assertEqual(("neo4j", "secret-value"), captured["auth"])
        self.assertNotIn("secret-value", str(provider.diagnostics()))


if __name__ == "__main__":
    unittest.main()
