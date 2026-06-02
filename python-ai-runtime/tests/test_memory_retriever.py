import os
import sys
import unittest

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
from datasmart_ai_runtime.services.memory.memory_retriever import InMemoryAgentMemoryRetriever


class InMemoryAgentMemoryRetrieverTest(unittest.TestCase):
    def test_retriever_respects_project_scope_before_keyword_matching(self) -> None:
        """项目级记忆必须先按 tenant/project 隔离，再做关键词召回。

        这个用例模拟真实生产风险：另一个项目里可能存在更相似、更高分的历史异常记录，但它不属于
        当前项目，绝不能因为关键词更匹配就进入 Agent 上下文。
        """

        retriever = InMemoryAgentMemoryRetriever(
            records=(
                AgentMemoryRecord(
                    memory_id="allowed-quality-case",
                    memory_type=AgentMemoryType.EPISODIC,
                    scope=AgentMemoryScope.PROJECT,
                    tenant_id="tenant-a",
                    project_id="project-a",
                    title="客户主数据手机号质量异常处理记录",
                    content="历史规则发现手机号格式异常，最终采用正则校验和空值兜底。",
                    source="quality-report",
                    importance_score=0.8,
                    tags=("质量", "异常", "手机号"),
                ),
                AgentMemoryRecord(
                    memory_id="blocked-other-project",
                    memory_type=AgentMemoryType.EPISODIC,
                    scope=AgentMemoryScope.PROJECT,
                    tenant_id="tenant-a",
                    project_id="project-b",
                    title="客户主数据手机号质量异常处理记录",
                    content="这个记录虽然关键词也匹配，但属于另一个项目，必须被过滤。",
                    source="quality-report",
                    importance_score=1.0,
                    tags=("质量", "异常", "手机号"),
                ),
            )
        )
        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="analyst-a",
            objective="请参考历史质量异常生成手机号质量规则",
        )
        plan = AgentMemoryPlan(
            retrieval_targets=(
                AgentMemoryRetrievalTarget(
                    memory_type=AgentMemoryType.EPISODIC,
                    scope=AgentMemoryScope.PROJECT,
                    query_hint="历史质量异常、手机号",
                    reason="质量规则生成需要参考历史异常经验。",
                ),
            )
        )

        report = retriever.retrieve(request, plan)

        self.assertEqual(1, report.total_retrieved)
        self.assertEqual("allowed-quality-case", report.results[0].memories[0].memory_id)

    def test_session_scope_is_skipped_without_session_id(self) -> None:
        """没有 sessionId 时不检索会话级记忆，避免把临时状态错误复用到其他会话。"""

        retriever = InMemoryAgentMemoryRetriever(
            records=(
                AgentMemoryRecord(
                    memory_id="session-memory",
                    memory_type=AgentMemoryType.SHORT_TERM,
                    scope=AgentMemoryScope.SESSION,
                    tenant_id="tenant-a",
                    project_id="project-a",
                    session_id="session-a",
                    title="当前会话选择的数据源",
                    content="用户已经选择 datasourceId=ds-001。",
                ),
            )
        )
        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="user-a",
            objective="继续分析刚才的数据源",
        )
        plan = AgentMemoryPlan(
            retrieval_targets=(
                AgentMemoryRetrievalTarget(
                    memory_type=AgentMemoryType.SHORT_TERM,
                    scope=AgentMemoryScope.SESSION,
                    query_hint="当前会话上下文",
                    reason="多轮对话需要继承会话状态。",
                ),
            )
        )

        report = retriever.retrieve(request, plan)

        self.assertEqual(0, report.total_retrieved)
        self.assertIn("没有 sessionId", report.results[0].skipped_reason)

    def test_session_scope_matches_current_session_only(self) -> None:
        """会话级记忆必须同时匹配 tenant、project 和 sessionId。"""

        retriever = InMemoryAgentMemoryRetriever(
            records=(
                AgentMemoryRecord(
                    memory_id="current-session",
                    memory_type=AgentMemoryType.SHORT_TERM,
                    scope=AgentMemoryScope.SESSION,
                    tenant_id="tenant-a",
                    project_id="project-a",
                    session_id="session-a",
                    title="当前会话数据源选择",
                    content="用户在当前会话选择了客户主数据源。",
                    tags=("会话", "数据源"),
                ),
                AgentMemoryRecord(
                    memory_id="other-session",
                    memory_type=AgentMemoryType.SHORT_TERM,
                    scope=AgentMemoryScope.SESSION,
                    tenant_id="tenant-a",
                    project_id="project-a",
                    session_id="session-b",
                    title="另一个会话数据源选择",
                    content="另一个会话的临时状态不能复用。",
                    tags=("会话", "数据源"),
                ),
            )
        )
        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="user-a",
            objective="继续分析当前会话的数据源",
            variables={"sessionId": "session-a"},
        )
        plan = AgentMemoryPlan(
            retrieval_targets=(
                AgentMemoryRetrievalTarget(
                    memory_type=AgentMemoryType.SHORT_TERM,
                    scope=AgentMemoryScope.SESSION,
                    query_hint="当前会话 数据源",
                    reason="多轮对话需要继承会话状态。",
                ),
            )
        )

        report = retriever.retrieve(request, plan)

        self.assertEqual(1, report.total_retrieved)
        self.assertEqual("current-session", report.results[0].memories[0].memory_id)


if __name__ == "__main__":
    unittest.main()
