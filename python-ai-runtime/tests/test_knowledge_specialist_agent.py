"""真实 KNOWLEDGE_AGENT specialist turn 的单元测试。

测试使用可记录调用的轻量 RAG 管线替身，而不是连接真实模型或向量库。这样既能验证专业 Agent 确实
调用了 `RagPipeline.answer()` 合同，又能稳定覆盖权限拒绝、无证据和基础设施异常等生产边界。
"""

from __future__ import annotations

import os
import sys
import unittest


ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.services.agent_gateway.session_models import AgentSessionRole
from datasmart_ai_runtime.services.multi_agent.specialist_contracts import (
    SpecialistDelegationScope,
    SpecialistTurnBudget,
    SpecialistTurnRequest,
    SpecialistTurnStatus,
)
from datasmart_ai_runtime.services.multi_agent.specialists.knowledge_agent import KnowledgeSpecialistAgent
from datasmart_ai_runtime.services.rag import RAG_TOOL_CODE, RagCitation, RagPipelineResult


class _RecordingRagPipeline:
    """记录查询并返回预设结果的 RagPipeline 测试替身。"""

    def __init__(self, result: RagPipelineResult | None = None, error: Exception | None = None) -> None:
        self.result = result
        self.error = error
        self.queries = []

    def answer(self, query):
        """模拟真实管线入口，并允许测试确认范围字段是否被正确传递。"""

        self.queries.append(query)
        if self.error is not None:
            raise self.error
        if self.result is None:
            raise AssertionError("测试替身未配置 RagPipelineResult")
        return self.result


def _request(
    *,
    role: AgentSessionRole = AgentSessionRole.KNOWLEDGE_AGENT,
    allowed_tools: tuple[str, ...] = (RAG_TOOL_CODE,),
    max_tool_calls: int = 1,
) -> SpecialistTurnRequest:
    """构造包含完整双主体审计范围的知识专业 turn 请求。"""

    return SpecialistTurnRequest(
        turn_id="knowledge-turn-1",
        session_id="session-1",
        run_id="run-1",
        role=role,
        objective="如何排查同步任务字段类型不兼容？",
        scope=SpecialistDelegationScope(
            tenant_id="tenant-1",
            application_id="datasmart-govern",
            project_id="project-101",
            actor_id="user-7",
            delegation_id="delegation-1",
            allowed_tool_names=allowed_tools,
        ),
        budget=SpecialistTurnBudget(max_tool_calls=max_tool_calls),
        context_summary={"traceId": "trace-knowledge-1"},
    )


def _grounded_result() -> RagPipelineResult:
    """构造有证据且完成模型生成的管线结果。"""

    return RagPipelineResult(
        answer="请先比较源字段与目标字段类型，并根据兼容矩阵选择转换策略。[C1]",
        citations=(
            RagCitation(
                citation_id="C1",
                document_id="runbook-7",
                chunk_id="runbook-7-chunk-2",
                title="字段类型兼容排查手册",
                source_uri="knowledge://runbooks/type-compatibility",
                snippet="这是不应进入 specialist 事件的文档正文片段。",
                final_score=0.93,
            ),
        ),
        selected_chunks=(),
        compressed_context="这是不应进入 specialist 结果的压缩文档正文。",
        retrieval_summary={
            "candidateCount": 6,
            "evidenceAcceptedCount": 2,
            "selectedCount": 1,
            "weakEvidenceRejectedCount": 4,
        },
        model_summary={
            "actualModelName": "test-governance-model",
            "providerInvoked": True,
            "providerSucceeded": True,
            "promptTokens": 120,
            "completionTokens": 36,
        },
        generated=True,
    )


def _no_evidence_result() -> RagPipelineResult:
    """构造经过证据门控后没有可用引用的管线结果。"""

    return RagPipelineResult(
        answer="当前知识库没有召回到足够证据，已拒绝无依据生成。",
        citations=(),
        selected_chunks=(),
        compressed_context="",
        retrieval_summary={
            "candidateCount": 3,
            "evidenceAcceptedCount": 0,
            "selectedCount": 0,
            "weakEvidenceRejectedCount": 3,
        },
        model_summary={"skipped": True, "reason": "no_evidence"},
        generated=False,
    )


class KnowledgeSpecialistAgentTest(unittest.TestCase):
    """验证第一批真实知识专业 Agent 的执行和安全边界。"""

    def test_executes_real_rag_turn_with_scope_citations_statistics_and_events(self) -> None:
        """有授权时应调用管线，并返回有依据答案、引用、统计和三阶段事件。"""

        pipeline = _RecordingRagPipeline(_grounded_result())
        events = []
        agent = KnowledgeSpecialistAgent(pipeline)

        result = agent.execute(_request(), events.append)

        self.assertEqual(AgentSessionRole.KNOWLEDGE_AGENT, agent.role)
        self.assertEqual(SpecialistTurnStatus.COMPLETED, result.status)
        self.assertEqual(1, len(pipeline.queries))
        query = pipeline.queries[0]
        self.assertEqual("tenant-1", query.tenant_id)
        self.assertEqual("project-101", query.project_id)
        self.assertEqual("user-7", query.actor_id)
        self.assertEqual("trace-knowledge-1", query.trace_id)
        self.assertEqual("session-1", query.session_id)
        self.assertEqual("*", query.workspace_key)
        self.assertEqual("如何排查同步任务字段类型不兼容？", query.question)

        self.assertTrue(result.structured_output["grounded"])
        self.assertTrue(result.structured_output["answerAvailable"])
        self.assertIn("[C1]", result.structured_output["answer"])
        self.assertEqual(1, len(result.structured_output["citations"]))
        self.assertNotIn("snippet", result.structured_output["citations"][0])
        self.assertEqual(6, result.structured_output["retrievalStatistics"]["candidateCount"])
        self.assertEqual(("rag:runbook-7:runbook-7-chunk-2",), result.evidence_references)
        self.assertEqual(RAG_TOOL_CODE, result.tool_activities[0].tool_name)
        self.assertEqual("test-governance-model", result.model_invocation_summary["actualModelName"])

        self.assertEqual(
            [
                "knowledge.retrieval.started",
                "knowledge.retrieval.completed",
                "knowledge.turn.completed",
            ],
            [event["action"] for event in events],
        )
        serialized_events = str(events)
        self.assertNotIn("如何排查", serialized_events)
        self.assertNotIn("文档正文片段", serialized_events)
        self.assertNotIn("请先比较源字段", serialized_events)
        self.assertTrue(all(event["payloadPolicy"].startswith("LOW_SENSITIVE") for event in events))

    def test_fails_closed_when_rag_tool_is_not_in_delegation_allowlist(self) -> None:
        """没有工具授权时不得调用管线，即使请求角色正确也必须失败关闭。"""

        pipeline = _RecordingRagPipeline(_grounded_result())
        events = []

        result = KnowledgeSpecialistAgent(pipeline).execute(_request(allowed_tools=("task.read",)), events.append)

        self.assertEqual(SpecialistTurnStatus.FAILED, result.status)
        self.assertEqual("KNOWLEDGE_RAG_TOOL_NOT_AUTHORIZED", result.error_code)
        self.assertEqual("DENIED", result.tool_activities[0].status)
        self.assertEqual([], pipeline.queries)
        self.assertEqual(["knowledge.turn.completed"], [event["action"] for event in events])

    def test_fails_closed_when_tool_call_budget_is_zero(self) -> None:
        """白名单不能绕过 turn 的工具调用预算。"""

        pipeline = _RecordingRagPipeline(_grounded_result())

        result = KnowledgeSpecialistAgent(pipeline).execute(_request(max_tool_calls=0))

        self.assertEqual(SpecialistTurnStatus.FAILED, result.status)
        self.assertEqual("KNOWLEDGE_RAG_TOOL_NOT_AUTHORIZED", result.error_code)
        self.assertEqual([], pipeline.queries)

    def test_no_evidence_completes_turn_without_exposing_fallback_as_answer(self) -> None:
        """检索成功但无证据时应明确无答案，而不是把 fallback 文案冒充业务回答。"""

        pipeline = _RecordingRagPipeline(_no_evidence_result())

        result = KnowledgeSpecialistAgent(pipeline).execute(_request())

        self.assertEqual(SpecialistTurnStatus.COMPLETED, result.status)
        self.assertFalse(result.structured_output["grounded"])
        self.assertFalse(result.structured_output["answerAvailable"])
        self.assertIsNone(result.structured_output["answer"])
        self.assertEqual((), result.evidence_references)
        self.assertEqual("NO_EVIDENCE", result.tool_activities[0].status)
        self.assertEqual("no_evidence", result.model_invocation_summary["reason"])

    def test_pipeline_error_returns_stable_low_sensitive_failure(self) -> None:
        """管线异常不得把 Provider、查询或文档细节写入结果和事件。"""

        secret_error = "provider https://secret.example failed for prompt=private-document-body"
        pipeline = _RecordingRagPipeline(error=RuntimeError(secret_error))
        events = []

        result = KnowledgeSpecialistAgent(pipeline).execute(_request(), events.append)

        self.assertEqual(SpecialistTurnStatus.FAILED, result.status)
        self.assertEqual("KNOWLEDGE_RAG_EXECUTION_FAILED", result.error_code)
        self.assertEqual("FAILED", result.tool_activities[0].status)
        self.assertNotIn(secret_error, str(result.to_summary()))
        self.assertNotIn(secret_error, str(events))
        self.assertEqual(
            ["knowledge.retrieval.started", "knowledge.turn.completed"],
            [event["action"] for event in events],
        )

    def test_rejects_role_mismatch_without_touching_rag_pipeline(self) -> None:
        """知识实现不能替其他角色工作，避免审计记录与真实执行者不一致。"""

        pipeline = _RecordingRagPipeline(_grounded_result())

        result = KnowledgeSpecialistAgent(pipeline).execute(_request(role=AgentSessionRole.DATASOURCE_AGENT))

        self.assertEqual(SpecialistTurnStatus.FAILED, result.status)
        self.assertEqual("KNOWLEDGE_AGENT_ROLE_MISMATCH", result.error_code)
        self.assertEqual([], pipeline.queries)

    def test_event_sink_failure_does_not_change_successful_rag_result(self) -> None:
        """前端断线属于旁路故障，不应让已经完成的知识检索回滚为失败。"""

        pipeline = _RecordingRagPipeline(_grounded_result())

        result = KnowledgeSpecialistAgent(pipeline).execute(
            _request(),
            lambda _: (_ for _ in ()).throw(RuntimeError("client disconnected")),
        )

        self.assertEqual(SpecialistTurnStatus.COMPLETED, result.status)
        self.assertEqual(1, len(pipeline.queries))


if __name__ == "__main__":
    unittest.main()
