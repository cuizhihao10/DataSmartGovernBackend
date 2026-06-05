import os
import sys
import unittest
from datetime import datetime, timedelta, timezone

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.domain.contracts import AgentRequest
from datasmart_ai_runtime.domain.memory import (
    AgentMemoryPlan,
    AgentMemoryRecord,
    AgentMemoryRetrievalTarget,
    AgentMemoryScope,
    AgentMemoryType,
)
from datasmart_ai_runtime.services.memory.memory_secondary_index import (
    AgentMemorySecondaryIndexKind,
    StoreBackedAgentMemorySecondaryIndex,
)
from datasmart_ai_runtime.services.memory.memory_store import AgentMemoryStoreEntry, InMemoryAgentMemoryStore
from datasmart_ai_runtime.services.memory.memory_store_retriever import StoreBackedAgentMemoryRetriever


class AgentMemorySecondaryIndexTest(unittest.TestCase):
    """长期记忆二级索引路由测试。

    这些测试不验证真实 Chroma 或 Neo4j 的召回质量，而是固定主链路的产品语义：不同记忆类型应该走不同
    索引通道；索引不可用时必须有可解释 fallback；所有通道仍要保留 tenant/project/workspace 隔离。
    """

    def test_semantic_memory_uses_vector_secondary_index_by_default(self) -> None:
        """语义记忆默认应走 vector 通道，再由 store-backed 适配器读取候选窗口。"""

        store = InMemoryAgentMemoryStore()
        store.save_if_absent(self._entry(memory_type=AgentMemoryType.SEMANTIC, title="客户主数据字段含义"))
        report = StoreBackedAgentMemoryRetriever(store).retrieve(
            self._request("请解释客户主数据字段含义"),
            self._plan(AgentMemoryType.SEMANTIC, "客户 主数据 字段"),
        )

        self.assertEqual(1, report.total_retrieved)
        self.assertEqual("vector", report.results[0].attributes["secondaryIndexKind"])
        self.assertFalse(report.results[0].attributes["secondaryIndexFallbackUsed"])

    def test_missing_vector_index_falls_back_to_keyword_with_reason(self) -> None:
        """当向量索引未启用时，语义记忆应显式回退 keyword，而不是静默假装仍在用向量库。"""

        store = InMemoryAgentMemoryStore()
        store.save_if_absent(self._entry(memory_type=AgentMemoryType.SEMANTIC, title="客户主数据字段含义"))
        keyword_only = {
            AgentMemorySecondaryIndexKind.KEYWORD: StoreBackedAgentMemorySecondaryIndex(
                kind=AgentMemorySecondaryIndexKind.KEYWORD,
                store=store,
            )
        }
        report = StoreBackedAgentMemoryRetriever(store, secondary_indexes=keyword_only).retrieve(
            self._request("请解释客户主数据字段含义"),
            self._plan(AgentMemoryType.SEMANTIC, "客户 主数据 字段"),
        )

        self.assertEqual(1, report.total_retrieved)
        self.assertEqual("keyword", report.results[0].attributes["secondaryIndexKind"])
        self.assertEqual("vector", report.results[0].attributes["preferredSecondaryIndexKind"])
        self.assertTrue(report.results[0].attributes["secondaryIndexFallbackUsed"])
        self.assertIn("vector 索引不可用", report.results[0].attributes["secondaryIndexFallbackReason"])

    def test_procedural_memory_prefers_graph_index(self) -> None:
        """程序记忆描述的是流程和依赖关系，默认应优先走 graph 通道。"""

        store = InMemoryAgentMemoryStore()
        store.save_if_absent(self._entry(memory_type=AgentMemoryType.PROCEDURAL, title="质量规则生成流程"))
        report = StoreBackedAgentMemoryRetriever(store).retrieve(
            self._request("请按历史流程生成质量规则"),
            self._plan(AgentMemoryType.PROCEDURAL, "质量 规则 流程"),
        )

        self.assertEqual(1, report.total_retrieved)
        self.assertEqual("graph", report.results[0].attributes["secondaryIndexKind"])

    @staticmethod
    def _request(objective: str) -> AgentRequest:
        return AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="analyst-a",
            objective=objective,
        )

    @staticmethod
    def _plan(memory_type: AgentMemoryType, query_hint: str) -> AgentMemoryPlan:
        return AgentMemoryPlan(
            retrieval_targets=(
                AgentMemoryRetrievalTarget(
                    memory_type=memory_type,
                    scope=AgentMemoryScope.PROJECT,
                    query_hint=query_hint,
                    reason="验证长期记忆二级索引路由。",
                ),
            )
        )

    @staticmethod
    def _entry(memory_type: AgentMemoryType, title: str) -> AgentMemoryStoreEntry:
        now = datetime.now(timezone.utc)
        return AgentMemoryStoreEntry(
            memory=AgentMemoryRecord(
                memory_id=f"memory-{memory_type.value}",
                memory_type=memory_type,
                scope=AgentMemoryScope.PROJECT,
                tenant_id="tenant-a",
                project_id="project-a",
                title=title,
                content=f"{title}，可作为后续 Agent 推理的低敏经验摘要。",
                source="unit-test",
                importance_score=0.8,
                tags=("质量", "客户", "规则", "流程"),
                created_at=now,
            ),
            workspace_key="tenant:tenant-a:project:project-a",
            memory_namespace="memory:tenant:tenant-a:project:project-a",
            namespace=("memory-namespace", "memory:tenant:tenant-a:project:project-a", "type", memory_type.value),
            idempotency_key=f"tenant-a|project-a|{memory_type.value}",
            source_candidate_id=f"candidate-{memory_type.value}",
            expires_at=now + timedelta(days=30),
            materialized_at=now,
        )


if __name__ == "__main__":
    unittest.main()
