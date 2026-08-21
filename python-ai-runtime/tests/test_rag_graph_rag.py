"""内存 GraphRAG 核心的治理和路径回归测试。"""

from __future__ import annotations

import os
import sys
import unittest
from datetime import datetime, timezone


ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.services.rag.graph_rag import (
    GraphRagEdge,
    GraphRagEntity,
    GraphRagQuery,
    GraphRagReasonCode,
    GraphRagRelation,
    GraphRagResultStatus,
    InMemoryGraphRag,
    parse_graph_rag_question,
)


AS_OF = datetime(2026, 8, 20, 12, 0, tzinfo=timezone.utc)


def entity(
    standard_id: str,
    name: str,
    *,
    aliases: tuple[str, ...] = (),
    tenant: str = "tenant-a",
    project: str = "project-a",
    workspace: str = "workspace-a",
    application: str = "*",
) -> GraphRagEntity:
    """构造测试用的同范围实体。"""

    return GraphRagEntity(
        standard_id=standard_id,
        canonical_name=name,
        aliases=aliases,
        tenant=tenant,
        project=project,
        workspace=workspace,
        application=application,
        sensitivity="internal",
    )


def edge(
    source: str,
    target: str,
    document_id: str,
    *,
    uri: str | None = None,
    chunk_id: str | None = None,
    effective_at: datetime = datetime(2026, 1, 1, tzinfo=timezone.utc),
    expires_at: datetime | None = None,
    confidence: float = 0.9,
    tenant: str = "tenant-a",
    project: str = "project-a",
    workspace: str = "workspace-a",
    application: str = "*",
    relation: str = "REPORTS_TO",
) -> GraphRagEdge:
    """构造具有完整来源链的当前关系边。"""

    return GraphRagEdge(
        source_entity_id=source,
        target_entity_id=target,
        relation=relation,
        source_document_id=document_id,
        source_uri=uri or f"memory://{document_id}",
        source_chunk_id=chunk_id or f"{document_id}-chunk-1",
        asserted_at=datetime(2026, 1, 1, tzinfo=timezone.utc),
        effective_at=effective_at,
        expires_at=expires_at,
        confidence=confidence,
        status="active",
        tenant=tenant,
        project=project,
        workspace=workspace,
        application=application,
        sensitivity="internal",
    )


class InMemoryGraphRagTest(unittest.TestCase):
    """验证 GraphRAG 的最小关系推理和拒答边界。"""

    def test_parser_accepts_compound_question_with_evidence_tail(self) -> None:
        """联合检索问法追加依据要求时，图侧仍应识别前面的关系链。"""

        parsed = parse_graph_rag_question("小张的上级的上级是谁，以及关系依据是什么？")

        self.assertIsNotNone(parsed)
        self.assertEqual("小张", parsed.subject)
        self.assertEqual(2, parsed.hops)

    def test_application_scope_is_the_public_graph_contract(self) -> None:
        """新图查询只需 tenant/application/project，序列化结果不再暴露 workspace。"""

        graph = InMemoryGraphRag(
            entities=(
                entity("person", "张三", application="app-a"),
                entity("manager", "李四", application="app-a"),
            ),
            edges=(edge("person", "manager", "doc-app", application="app-a"),),
        )
        result = graph.query(
            GraphRagQuery(
                question="张三的上级是谁",
                tenant="tenant-a",
                application_id="app-a",
                project="project-a",
                sensitivity="internal",
                as_of=AS_OF,
            )
        )

        self.assertEqual(GraphRagResultStatus.SUCCESS.value, result.status)
        self.assertNotIn("workspace", result.path[0].to_dict())
        self.assertEqual("app-a", result.path[0].to_dict()["application_id"])

    def test_alias_two_hop_success_contains_complete_provenance_chain(self) -> None:
        """别名起点可以完成两跳，并返回每一跳的来源、时间和可信度。"""

        graph = InMemoryGraphRag(
            entities=(
                entity("person-zhang", "张三", aliases=("小张",)),
                entity("person-li", "李四", aliases=("李经理",)),
                entity("person-wang", "王五", aliases=("王总",)),
            ),
            edges=(
                edge("person-zhang", "person-li", "doc-report-1", confidence=0.91),
                edge("person-li", "person-wang", "doc-report-2", confidence=0.87),
            ),
        )

        result = graph.query(
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
        self.assertEqual("person-wang", result.entity_id)
        self.assertEqual(2, result.hop_count)
        self.assertEqual(
            ("person-zhang", "person-li", "person-wang"),
            (result.path[0].source_entity_id, result.path[0].target_entity_id, result.path[1].target_entity_id),
        )
        self.assertEqual(("doc-report-1", "doc-report-2"), tuple(step.source_document_id for step in result.path))
        self.assertEqual(("memory://doc-report-1", "memory://doc-report-2"), tuple(step.source_uri for step in result.path))
        self.assertEqual(("doc-report-1-chunk-1", "doc-report-2-chunk-1"), tuple(step.source_chunk_id for step in result.path))
        self.assertEqual((0.91, 0.87), tuple(step.confidence for step in result.path))
        self.assertTrue(all(step.effective_at == datetime(2026, 1, 1, tzinfo=timezone.utc) for step in result.path))
        serialized = result.to_dict()
        self.assertEqual("doc-report-1", serialized["path"][0]["source_document_id"])
        self.assertEqual("doc-report-2", serialized["path"][1]["source_document_id"])
        self.assertEqual("2026-01-01T00:00:00+00:00", serialized["path"][0]["time"]["effective_at"])

    def test_controlled_semantic_alias_resolution_handles_token_order_change(self) -> None:
        """实体简称或词序变化只在高置信且唯一时进入图遍历。"""

        graph = InMemoryGraphRag(
            entities=(
                entity("task-orders", "订单同步任务", aliases=("订单数据同步",)),
                entity("source-orders", "订单库"),
            ),
            edges=(edge("task-orders", "source-orders", "doc-task-source", relation="TASK_USES_DATASOURCE"),),
        )

        result = graph.query(
            GraphRagQuery(
                question="同步订单任务使用哪些数据源",
                tenant="tenant-a",
                project="project-a",
                workspace="workspace-a",
                sensitivity="internal",
                as_of=AS_OF,
            )
        )

        self.assertEqual(GraphRagResultStatus.SUCCESS.value, result.status)
        self.assertEqual("semantic", result.entity_resolution)
        self.assertEqual("订单库", result.answer)

    def test_semantic_alias_tie_is_refused_instead_of_guessing(self) -> None:
        """两个实体拥有同等语义候选时必须返回歧义，而不是按插入顺序选一个。"""

        graph = InMemoryGraphRag(
            entities=(
                entity("task-a", "订单同步任务", aliases=("订单数据同步",)),
                entity("task-b", "订单同步作业", aliases=("订单数据同步",)),
                entity("source", "订单库"),
            ),
            edges=(
                edge("task-a", "source", "doc-a", relation="TASK_USES_DATASOURCE"),
                edge("task-b", "source", "doc-b", relation="TASK_USES_DATASOURCE"),
            ),
        )

        result = graph.query(
            GraphRagQuery(
                question="订单数据同步使用哪些数据源",
                tenant="tenant-a",
                project="project-a",
                workspace="workspace-a",
                sensitivity="internal",
                as_of=AS_OF,
            )
        )

        self.assertEqual(GraphRagResultStatus.REFUSAL.value, result.status)
        self.assertEqual(GraphRagReasonCode.AMBIGUOUS_ALIAS.value, result.reason_code)

    def test_cross_tenant_entity_is_invisible_before_alias_resolution(self) -> None:
        """其他租户的实体和边不能参与别名解析，也不能泄露其存在。"""

        graph = InMemoryGraphRag(
            entities=(
                entity("person-b", "张三", aliases=("共同别名",), tenant="tenant-b"),
                entity("manager-b", "李四", tenant="tenant-b"),
            ),
            edges=(edge("person-b", "manager-b", "tenant-b-doc", tenant="tenant-b"),),
        )

        result = graph.query(
            GraphRagQuery(
                question="共同别名的上级是谁",
                tenant="tenant-a",
                project="project-a",
                workspace="workspace-a",
                sensitivity="internal",
                as_of=AS_OF,
            )
        )

        self.assertEqual(GraphRagResultStatus.NOT_APPLICABLE.value, result.status)
        self.assertEqual(GraphRagReasonCode.ALIAS_NOT_FOUND.value, result.reason_code)
        self.assertIsNone(result.answer)
        self.assertEqual((), result.path)

    def test_same_alias_in_visible_scope_is_refused_as_ambiguous(self) -> None:
        """同一治理范围内的同名别名不能被猜测解析。"""

        graph = InMemoryGraphRag(
            entities=(
                entity("person-1", "张三甲", aliases=("张三",)),
                entity("person-2", "张三乙", aliases=("张三",)),
                entity("manager", "李四"),
            ),
            edges=(edge("person-1", "manager", "doc-1"), edge("person-2", "manager", "doc-2")),
        )

        result = graph.query(
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
        self.assertEqual(GraphRagReasonCode.AMBIGUOUS_ALIAS.value, result.reason_code)
        self.assertIsNone(result.answer)
        self.assertEqual((), result.path)

    def test_conflicting_current_edges_on_one_hop_are_refused(self) -> None:
        """同一源实体到两个不同当前上级的矛盾关系必须拒答。"""

        graph = InMemoryGraphRag(
            entities=(entity("person", "张三"), entity("manager-1", "李四"), entity("manager-2", "王五")),
            edges=(edge("person", "manager-1", "doc-1"), edge("person", "manager-2", "doc-2")),
        )

        result = graph.query(
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
        self.assertEqual(GraphRagReasonCode.CONFLICTING_CURRENT_EDGES.value, result.reason_code)
        self.assertEqual(("manager-1", "manager-2"), result.conflicting_target_ids)
        self.assertIsNone(result.answer)

    def test_more_than_three_hops_is_refused(self) -> None:
        """超过三跳的自然问句不能通过扩大搜索范围来回答。"""

        entities = tuple(entity(f"person-{index}", f"人员{index}") for index in range(5))
        edges = tuple(edge(f"person-{index}", f"person-{index + 1}", f"doc-{index}") for index in range(4))
        graph = InMemoryGraphRag(entities=entities, edges=edges)

        result = graph.query(
            GraphRagQuery(
                question="人员0的上级的上级的上级的上级是谁",
                tenant="tenant-a",
                project="project-a",
                workspace="workspace-a",
                sensitivity="internal",
                as_of=AS_OF,
            )
        )

        self.assertEqual(GraphRagResultStatus.REFUSAL.value, result.status)
        self.assertEqual(GraphRagReasonCode.MAX_HOPS_EXCEEDED.value, result.reason_code)
        self.assertEqual(4, result.requested_hops)
        self.assertEqual((), result.path)

    def test_expired_edge_does_not_participate(self) -> None:
        """已过期的边不能形成当前路径，也不能被当作冲突证据。"""

        graph = InMemoryGraphRag(
            entities=(entity("person", "张三"), entity("manager", "李四")),
            edges=(
                edge(
                    "person",
                    "manager",
                    "expired-doc",
                    expires_at=datetime(2026, 8, 1, tzinfo=timezone.utc),
                ),
            ),
        )

        result = graph.query(
            GraphRagQuery(
                question="张三的上级是谁",
                tenant="tenant-a",
                project="project-a",
                workspace="workspace-a",
                sensitivity="internal",
                as_of=AS_OF,
            )
        )

        self.assertEqual(GraphRagResultStatus.NOT_APPLICABLE.value, result.status)
        self.assertEqual(GraphRagReasonCode.NO_CURRENT_PATH.value, result.reason_code)
        self.assertEqual((), result.path)
        self.assertIsNone(result.answer)

    def test_business_graph_structured_queries_cover_mapping_constraints_and_logs(self) -> None:
        """业务图谱关系必须可通过结构化查询读取，而不是只支持组织关系。"""

        graph = InMemoryGraphRag(
            entities=(
                entity("source-field", "源客户编号"),
                entity("target-field", "目标客户编号"),
                entity("not-null", "NOT NULL"),
                entity("execution", "执行 2714"),
                entity("error", "NOT_NULL_VIOLATION"),
                entity("log", "失败日志 2714"),
            ),
            edges=(
                edge("source-field", "target-field", "mapping-doc", relation=GraphRagRelation.FIELD_MAPS_TO.value),
                edge("source-field", "not-null", "constraint-doc", relation=GraphRagRelation.FIELD_HAS_CONSTRAINT.value),
                edge("execution", "error", "error-doc", relation=GraphRagRelation.EXECUTION_FAILED_WITH.value),
                edge("execution", "log", "log-doc", relation=GraphRagRelation.EXECUTION_HAS_LOG.value),
            ),
        )
        mapping = graph.query(GraphRagQuery(
            tenant="tenant-a", project="project-a", workspace="workspace-a", sensitivity="internal",
            start_entity="源客户编号", relation=GraphRagRelation.FIELD_MAPS_TO, hops=1,
        ))
        self.assertEqual(GraphRagResultStatus.SUCCESS.value, mapping.status)
        self.assertEqual("目标客户编号", mapping.answer)
        self.assertEqual(GraphRagRelation.FIELD_MAPS_TO.value, mapping.relation)

        constraints = graph.query(GraphRagQuery(
            tenant="tenant-a", project="project-a", workspace="workspace-a", sensitivity="internal",
            question="源客户编号有哪些约束",
        ))
        self.assertEqual(GraphRagResultStatus.SUCCESS.value, constraints.status)
        self.assertIn("NOT NULL", constraints.answer or "")

        logs = graph.query(GraphRagQuery(
            tenant="tenant-a", project="project-a", workspace="workspace-a", sensitivity="internal",
            start_entity="执行 2714", relation=GraphRagRelation.EXECUTION_HAS_LOG, hops=1,
        ))
        self.assertEqual(GraphRagResultStatus.SUCCESS.value, logs.status)
        self.assertEqual("失败日志 2714", logs.answer)


if __name__ == "__main__":
    unittest.main()
