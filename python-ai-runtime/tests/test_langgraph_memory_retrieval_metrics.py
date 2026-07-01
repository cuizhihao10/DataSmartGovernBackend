import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.services.memory import LangGraphMemoryRetrievalMetrics


class LangGraphMemoryRetrievalMetricsTest(unittest.TestCase):
    """LangGraph 长期记忆检索指标测试。

    这组测试保护的是生产可观测边界：记忆检索图可以被 Prometheus 观察，但指标只能输出聚合趋势。
    memoryId、memory namespace、tenant/project、queryHint、记忆正文、用户目标和模型输出都不能进入指标文本。
    """

    def test_summary_updates_low_cardinality_prometheus_metrics(self) -> None:
        """记忆检索 workflow summary 应转换为状态、目标类型、scope 和 MEMORY_AGENT 指标。"""

        metrics = LangGraphMemoryRetrievalMetrics()

        recorded = metrics.record_summary(
            {
                "status": "LANGGRAPH_MEMORY_RETRIEVAL_OBSERVED",
                "retrievalStatus": "RETRIEVAL_AVAILABLE",
                "fallbackUsed": False,
                "retrievalScope": {
                    "memoryTypeCounts": {"semantic": 1, "episodic": 1},
                    "memoryScopeCounts": {"project": 2},
                },
                "retrievalReport": {
                    "retrievalResultCount": 2,
                    "retrievedCount": 1,
                    "emptyResultCount": 1,
                    "skippedResultCount": 0,
                    "retriever": "secret-retriever-endpoint",
                },
                "multiAgentMemoryContext": {
                    "memoryAgentScheduled": True,
                    "memoryAgentRequired": True,
                    "consumerAgentRoles": (
                        "MASTER_ORCHESTRATOR",
                        "DATA_QUALITY_AGENT",
                        "PERMISSION_AGENT",
                    ),
                },
                "globalState": {
                    "workspaceMemoryNamespaceAvailable": True,
                    "memoryNamespace": "tenant-secret/project-secret/session-secret",
                },
                "queryHint": "secret query hint should not leak",
                "memoryId": "secret-memory-id",
                "memoryContent": "secret memory content",
            }
        )
        text = metrics.render_prometheus()

        self.assertTrue(recorded)
        self.assertIn(
            'datasmart_ai_langgraph_memory_retrieval_workflows_total{fallback="false",'
            'result="observed",retrieval_status="retrieval_available",workflow_status="observed"} 1',
            text,
        )
        self.assertIn(
            'datasmart_ai_langgraph_memory_retrieval_targets_total{memory_type="semantic"} 1',
            text,
        )
        self.assertIn(
            'datasmart_ai_langgraph_memory_retrieval_targets_total{memory_type="episodic"} 1',
            text,
        )
        self.assertIn(
            'datasmart_ai_langgraph_memory_retrieval_scopes_total{memory_scope="project"} 2',
            text,
        )
        self.assertIn(
            'datasmart_ai_langgraph_memory_retrieval_results_total{result_type="retrieved",'
            'retrieval_status="retrieval_available"} 1',
            text,
        )
        self.assertIn(
            'datasmart_ai_langgraph_memory_retrieval_agent_context_total{memory_agent_required="true",'
            'memory_agent_scheduled="true",retrieval_status="retrieval_available"} 1',
            text,
        )
        self.assertIn(
            'datasmart_ai_langgraph_memory_retrieval_consumer_agents_total{agent_role="permission_agent",'
            'delivery_tier="must_do",retrieval_status="retrieval_available"} 1',
            text,
        )
        self.assertNotIn("tenant-secret", text)
        self.assertNotIn("project-secret", text)
        self.assertNotIn("session-secret", text)
        self.assertNotIn("secret-memory-id", text)
        self.assertNotIn("secret query hint", text)
        self.assertNotIn("secret memory content", text)
        self.assertNotIn("secret-retriever-endpoint", text)

    def test_unknown_values_are_bounded_to_other_labels(self) -> None:
        """未知记忆类型、scope 或角色只能进入 `other`，不能制造动态高基数标签。"""

        metrics = LangGraphMemoryRetrievalMetrics()

        metrics.record_summary(
            {
                "status": "CUSTOM_STATUS_WITH_SECRET",
                "retrievalStatus": "CUSTOM_RETRIEVAL_STATUS_WITH_SECRET",
                "fallbackUsed": True,
                "retrievalScope": {
                    "memoryTypeCounts": {"semantic-secret-type": 3},
                    "memoryScopeCounts": {"secret-scope": 2},
                },
                "retrievalReport": {
                    "retrievalResultCount": 1,
                },
                "multiAgentMemoryContext": {
                    "consumerAgentRoles": ("SECRET_AGENT_ROLE",),
                },
            }
        )
        text = metrics.render_prometheus()

        self.assertIn('workflow_status="other"', text)
        self.assertIn('retrieval_status="other"', text)
        self.assertIn('fallback="true"', text)
        self.assertIn('memory_type="other"', text)
        self.assertIn('memory_scope="other"', text)
        self.assertIn('agent_role="other"', text)
        self.assertNotIn("SECRET", text)
        self.assertNotIn("secret-scope", text)

    def test_missing_summary_is_ignored_but_help_text_is_rendered(self) -> None:
        """指标器挂到可选链路后，应安全忽略空 summary。"""

        metrics = LangGraphMemoryRetrievalMetrics()

        self.assertFalse(metrics.record_summary(None))
        self.assertEqual(0, metrics.snapshot()["metricCount"])
        self.assertIn(
            "# HELP datasmart_ai_langgraph_memory_retrieval_workflows_total",
            metrics.render_prometheus(),
        )


if __name__ == "__main__":
    unittest.main()
