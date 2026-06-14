import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.services.tools import (
    InMemoryToolActionExecutionCheckpointStore,
    low_sensitive_execution_graph_summary,
)


class ToolActionExecutionCheckpointStoreTest(unittest.TestCase):
    """工具动作执行前图 checkpoint store 测试。

    这组测试保护的是“可恢复控制面状态”的安全契约，而不是工具执行结果：
    - checkpoint 可以保存 runner 的低敏步骤、状态计数和恢复要求；
    - checkpoint 不能保存 prompt、SQL、工具真实参数、模型输出、凭证或内部端点；
    - in-memory store 必须有容量上限，避免本地学习或压测时无限占用内存。
    """

    def test_save_sanitizes_graph_run_and_returns_resume_locator(self) -> None:
        """保存检查点时应只保留低敏恢复线索。"""

        store = InMemoryToolActionExecutionCheckpointStore(max_checkpoints_per_thread=5, max_total_checkpoints=10)
        checkpoint = store.save(
            _graph_run_with_sensitive_fields(),
            context={
                "source": "MCP_TOOLS_CALL",
                "protocolFamily": "MCP",
                "tenantId": "tenant-checkpoint",
                "projectId": "project-checkpoint",
                "actorId": "actor-checkpoint",
                "requestId": "request-checkpoint",
                "runId": "run-checkpoint",
                "sessionId": "session-checkpoint",
            },
        )
        summary = checkpoint.to_summary(include_graph_run=True)
        serialized = str(summary)

        self.assertEqual("run-checkpoint", summary["threadId"])
        self.assertEqual(1, summary["sequence"])
        self.assertEqual("LOW_SENSITIVE_EXECUTION_GRAPH_CHECKPOINT_ONLY", summary["payloadPolicy"])
        self.assertEqual({"WAITING_COMMAND_PROPOSAL_EVIDENCE": 1}, summary["statusCounts"])
        self.assertIn("GRAPH_OR_PAYLOAD_REFERENCE_OR_POLICY_EVIDENCE", summary["resumeRequirements"])
        self.assertEqual("MCP_TOOLS_CALL", summary["source"])
        self.assertEqual("MCP", summary["protocolFamily"])
        self.assertFalse(summary["sensitiveDataPolicy"]["rawArgumentsStored"])
        self.assertFalse(summary["sensitiveDataPolicy"]["sqlStored"])
        self.assertNotIn("ds-checkpoint-secret", serialized)
        self.assertNotIn("select * from hidden_table", serialized)
        self.assertNotIn("raw prompt should not be stored", serialized)
        self.assertNotIn("http://internal.service.local", serialized)
        self.assertNotIn("model output body", serialized)

    def test_low_sensitive_summary_drops_unknown_sensitive_fields(self) -> None:
        """低敏摘要函数即使单独使用，也不能把未知字段原样透传。"""

        safe = low_sensitive_execution_graph_summary(_graph_run_with_sensitive_fields())
        serialized = str(safe)

        self.assertEqual("PRE_EXECUTION_GRAPH_RUNNER_ONLY", safe["executionBoundary"])
        self.assertEqual(1, safe["stepCount"])
        self.assertEqual("datasource.metadata.read", safe["steps"][0]["toolName"])
        self.assertNotIn("arguments", safe["steps"][0])
        self.assertNotIn("prompt", serialized)
        self.assertNotIn("hidden_table", serialized)
        self.assertNotIn("ds-checkpoint-secret", serialized)
        self.assertNotIn("apiKey", serialized)

    def test_per_thread_and_global_limits_evict_old_checkpoints(self) -> None:
        """内存 store 应同时限制单线程数量和全局数量。"""

        store = InMemoryToolActionExecutionCheckpointStore(max_checkpoints_per_thread=2, max_total_checkpoints=3)
        first = store.save(_minimal_graph_run("WAITING_APPROVAL_FACT"), context={"runId": "thread-a"})
        second = store.save(_minimal_graph_run("WAITING_CLARIFICATION_FACT"), context={"runId": "thread-a"})
        third = store.save(_minimal_graph_run("WAITING_OUTBOX_CONFIRMATION"), context={"runId": "thread-a"})

        self.assertIsNone(store.get(first.checkpoint_id))
        thread_a = store.list_by_thread("thread-a")
        self.assertEqual((second.checkpoint_id, third.checkpoint_id), tuple(item.checkpoint_id for item in thread_a))
        self.assertEqual((2, 3), tuple(item.sequence for item in thread_a))

        fourth = store.save(_minimal_graph_run("COMMAND_PROPOSAL_CLIENT_DISABLED"), context={"runId": "thread-b"})
        fifth = store.save(_minimal_graph_run("WAITING_TOOL_BUDGET"), context={"runId": "thread-c"})

        self.assertIsNone(store.get(second.checkpoint_id))
        self.assertIsNotNone(store.get(third.checkpoint_id))
        self.assertIsNotNone(store.get(fourth.checkpoint_id))
        self.assertIsNotNone(store.get(fifth.checkpoint_id))
        self.assertEqual((third.checkpoint_id,), tuple(item.checkpoint_id for item in store.list_by_thread("thread-a")))


def _minimal_graph_run(status: str) -> dict[str, object]:
    """生成最小 runner 摘要，便于测试检查点序列与驱逐。"""

    return {
        "schemaVersion": "datasmart.python-ai-runtime.tool-action-execution-graph-runner.v1",
        "previewOnly": True,
        "executionBoundary": "PRE_EXECUTION_GRAPH_RUNNER_ONLY",
        "stepCount": 1,
        "truncatedCount": 0,
        "statusCounts": {status: 1},
        "steps": [
            {
                "nodeType": "TOOL_ACTION_COMMAND_PROPOSAL",
                "toolName": "datasource.metadata.read",
                "stepStatus": status,
                "proposalSubmission": {},
            }
        ],
        "sideEffectBoundary": {
            "toolExecuted": False,
            "outboxWritten": False,
            "workerDispatched": False,
            "approvalCreated": False,
            "checkpointPersisted": False,
        },
        "resumeRequirements": ["APPROVAL_CONFIRMATION_FACT"],
    }


def _graph_run_with_sensitive_fields() -> dict[str, object]:
    """生成包含故意敏感字段的 runner 摘要，用来证明 store 会二次裁剪。"""

    graph = _minimal_graph_run("WAITING_COMMAND_PROPOSAL_EVIDENCE")
    graph["statusCounts"] = {"WAITING_COMMAND_PROPOSAL_EVIDENCE": 1}
    graph["resumeRequirements"] = ["GRAPH_OR_PAYLOAD_REFERENCE_OR_POLICY_EVIDENCE"]
    graph["prompt"] = "raw prompt should not be stored"
    graph["sql"] = "select * from hidden_table"
    graph["modelOutput"] = "model output body"
    graph["steps"][0].update(
        {
            "templateId": "template-checkpoint-001",
            "decision": "ready",
            "outboxPreflightCandidate": True,
            "payloadPolicy": "REFERENCE_ONLY",
            "arguments": {"datasourceId": "ds-checkpoint-secret"},
            "targetEndpoint": "http://internal.service.local/tools",
            "proposalSubmission": {
                "schemaVersion": "datasmart.python-ai-runtime.tool-action-command-proposal-client.v1",
                "submitted": False,
                "skipped": True,
                "submissionState": "VALIDATION_FAILED",
                "requestPayload": {
                    "graphId": "graph-checkpoint",
                    "payloadReference": "agent-payload:checkpoint/datasource-metadata-read",
                    "arguments": {"datasourceId": "ds-checkpoint-secret"},
                    "sql": "select * from hidden_table",
                    "apiKey": "secret-token",
                },
                "javaProposal": {
                    "proposalId": "proposal-checkpoint",
                    "toolName": "datasource.metadata.read",
                    "outboxWriteAllowedByPreflight": False,
                    "arguments": {"datasourceId": "ds-checkpoint-secret"},
                },
                "missingEvidence": ["POLICY_VERSION_REQUIRED"],
                "guardrails": ["guardrail body is low sensitivity but not needed in checkpoint"],
            },
            "nextAction": "COMPLETE_GRAPH_PAYLOAD_POLICY_OR_APPROVAL_EVIDENCE_THEN_RESUME",
        }
    )
    return graph


if __name__ == "__main__":
    unittest.main()
