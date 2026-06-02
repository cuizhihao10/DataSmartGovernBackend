import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.domain.contracts import AgentRequest
from datasmart_ai_runtime.domain.memory import (
    AgentMemoryPlan,
    AgentMemoryRetrievalTarget,
    AgentMemoryScope,
    AgentMemoryType,
    AgentMemoryWriteCandidate,
    AgentMemoryWriteCandidateStatus,
)
from datasmart_ai_runtime.services.memory.memory_store import InMemoryAgentMemoryStore
from datasmart_ai_runtime.services.memory.memory_store_retriever import StoreBackedAgentMemoryRetriever
from datasmart_ai_runtime.services.memory.memory_materialization_receipt_store import (
    AgentMemoryMaterializationReceiptStatus,
    InMemoryAgentMemoryMaterializationReceiptStore,
)
from datasmart_ai_runtime.services.memory.memory_write_candidate_store import InMemoryAgentMemoryWriteCandidateStore
from datasmart_ai_runtime.services.memory.memory_write_materializer import (
    AgentApprovedMemoryWriteMaterializer,
    AgentMemoryMaterializationOutcome,
)


class AgentApprovedMemoryWriteMaterializerTest(unittest.TestCase):
    """已批准候选落成正式长期记忆的闭环测试。

    该测试覆盖“候选审批完成 -> materializer 幂等写入 -> 后续请求按项目范围召回”主链。
    第一阶段使用内存 store，不代表生产环境选择内存；它用于先固定安全边界和可替换协议。
    """

    def test_approved_summary_can_materialize_and_be_retrieved_in_same_project(self) -> None:
        """已批准的低敏项目摘要应落成正式记忆，并被同项目后续请求召回。"""

        candidate_store, memory_store, materializer, receipt_store = self._runtime()
        candidate_store.save(self._candidate())

        result = materializer.materialize("candidate-a")
        receipt = receipt_store.get_by_candidate_id("candidate-a")
        report = StoreBackedAgentMemoryRetriever(memory_store).retrieve(
            AgentRequest(
                tenant_id="tenant-a",
                project_id="project-a",
                actor_id="analyst-b",
                objective="请参考历史手机号质量异常处理经验生成规则",
            ),
            AgentMemoryPlan(
                retrieval_targets=(
                    AgentMemoryRetrievalTarget(
                        memory_type=AgentMemoryType.EPISODIC,
                        scope=AgentMemoryScope.PROJECT,
                        query_hint="手机号 质量异常 处理经验",
                        reason="质量规则生成需要复用已批准的项目经验。",
                    ),
                )
            ),
        )

        self.assertEqual(AgentMemoryMaterializationOutcome.MATERIALIZED, result.outcome)
        self.assertEqual(result.attributes["receiptId"], receipt.receipt_id)
        self.assertEqual(AgentMemoryMaterializationReceiptStatus.SUCCEEDED, receipt.status)
        self.assertEqual(result.memory_id, receipt.memory_id)
        self.assertEqual(1, report.total_retrieved)
        memory = report.results[0].memories[0]
        self.assertEqual(result.memory_id, memory.memory_id)
        self.assertIn("手机号", memory.content)
        self.assertEqual("SUMMARY_ONLY_NO_RAW_TOOL_RESULT_NO_SQL_NO_SAMPLE_DATA", memory.attributes["payloadPolicy"])
        self.assertNotIn("minio://", memory.content)

    def test_repeated_materialization_reuses_existing_memory(self) -> None:
        """worker 重试同一候选时必须返回幂等结果，不能制造重复记忆。"""

        candidate_store, memory_store, materializer, receipt_store = self._runtime()
        candidate_store.save(self._candidate())

        first = materializer.materialize("candidate-a")
        second = materializer.materialize("candidate-a")

        self.assertEqual(AgentMemoryMaterializationOutcome.MATERIALIZED, first.outcome)
        self.assertEqual(AgentMemoryMaterializationOutcome.ALREADY_MATERIALIZED, second.outcome)
        self.assertEqual(first.memory_id, second.memory_id)
        self.assertEqual(first.memory_id, memory_store.get_by_candidate_id("candidate-a").memory.memory_id)
        receipt = receipt_store.get_by_candidate_id("candidate-a")
        self.assertEqual(2, receipt.attempt_count)
        self.assertEqual("already_materialized", receipt.outcome)

    def test_same_project_different_workspace_cannot_retrieve_materialized_memory(self) -> None:
        """同项目不同 workspace 不能共享已经落成的长期记忆。

        这是长期记忆商业化最关键的安全边界之一：项目级 scope 只说明租户/项目一致，
        workspace namespace 才能表达“本次 Agent 上下文到底属于哪个协作空间或会话沙箱”。
        """

        candidate_store, memory_store, materializer, _receipt_store = self._runtime()
        candidate_store.save(
            self._candidate(
                workspace_key="tenant:tenant-a:project:project-a:workspace:workspace-a",
                memory_namespace="memory:tenant:tenant-a:project:project-a:workspace:workspace-a",
            )
        )

        materializer.materialize("candidate-a")
        report = StoreBackedAgentMemoryRetriever(memory_store).retrieve(
            AgentRequest(
                tenant_id="tenant-a",
                project_id="project-a",
                actor_id="analyst-b",
                objective="请参考历史手机号质量异常处理经验生成规则",
                variables={"isolationLevel": "WORKSPACE", "workspaceId": "workspace-b"},
            ),
            AgentMemoryPlan(
                retrieval_targets=(
                    AgentMemoryRetrievalTarget(
                        memory_type=AgentMemoryType.EPISODIC,
                        scope=AgentMemoryScope.PROJECT,
                        query_hint="手机号 质量异常 处理经验",
                        reason="验证同项目不同 workspace 的长期记忆隔离。",
                    ),
                )
            ),
        )

        self.assertEqual(0, report.total_retrieved)

    def test_unapproved_candidate_is_blocked_before_formal_write(self) -> None:
        """DRAFT 候选即使被内部 worker 误投，也不能绕过审批直接写入正式记忆。"""

        candidate_store, memory_store, materializer, receipt_store = self._runtime()
        candidate_store.save(self._candidate(status=AgentMemoryWriteCandidateStatus.DRAFT))

        with self.assertRaisesRegex(ValueError, "只有 approved 候选"):
            materializer.materialize("candidate-a")

        receipt = receipt_store.get_by_candidate_id("candidate-a")
        self.assertEqual(AgentMemoryMaterializationReceiptStatus.FAILED, receipt.status)
        self.assertIn("只有 approved 候选", receipt.error_message)
        self.assertIsNone(memory_store.get_by_candidate_id("candidate-a"))

    def test_candidate_without_workspace_evidence_is_blocked(self) -> None:
        """历史候选缺少 workspace 证据时不能被猜测性写入正式记忆。"""

        candidate_store, memory_store, materializer, receipt_store = self._runtime()
        candidate_store.save(self._candidate(workspace_key=None, memory_namespace=None))

        with self.assertRaisesRegex(ValueError, "缺少 workspaceKey"):
            materializer.materialize("candidate-a")

        self.assertIsNone(receipt_store.get_by_candidate_id("candidate-a"))
        self.assertIsNone(memory_store.get_by_candidate_id("candidate-a"))

    def test_sensitive_candidate_waits_for_masking_pipeline_even_after_approval(self) -> None:
        """敏感摘要批准后仍需先接入脱敏流水线，不能直接进入可检索正文。"""

        candidate_store, memory_store, materializer, receipt_store = self._runtime()
        candidate_store.save(self._candidate(sensitivity_level="sensitive"))

        with self.assertRaisesRegex(ValueError, "尚未接入脱敏流水线"):
            materializer.materialize("candidate-a")

        receipt = receipt_store.get_by_candidate_id("candidate-a")
        self.assertEqual(AgentMemoryMaterializationReceiptStatus.FAILED, receipt.status)
        self.assertIn("尚未接入脱敏流水线", receipt.error_message)
        self.assertIsNone(memory_store.get_by_candidate_id("candidate-a"))

    @staticmethod
    def _runtime():
        """创建候选 store、正式记忆 store 和 materializer。"""

        candidate_store = InMemoryAgentMemoryWriteCandidateStore()
        memory_store = InMemoryAgentMemoryStore()
        receipt_store = InMemoryAgentMemoryMaterializationReceiptStore()
        materializer = AgentApprovedMemoryWriteMaterializer(
            candidate_store=candidate_store,
            memory_store=memory_store,
            receipt_store=receipt_store,
        )
        return candidate_store, memory_store, materializer, receipt_store

    @staticmethod
    def _candidate(
        *,
        status: AgentMemoryWriteCandidateStatus = AgentMemoryWriteCandidateStatus.APPROVED,
        sensitivity_level: str = "internal",
        workspace_key: str | None = "tenant:tenant-a:project:project-a",
        memory_namespace: str | None = "memory:tenant:tenant-a:project:project-a",
    ) -> AgentMemoryWriteCandidate:
        """构造一条已完成治理审批的低敏项目记忆候选。"""

        return AgentMemoryWriteCandidate(
            candidate_id="candidate-a",
            memory_type=AgentMemoryType.EPISODIC,
            scope=AgentMemoryScope.PROJECT,
            status=status,
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="analyst-a",
            title="手机号质量异常处理经验",
            content_summary="历史质量检测发现手机号格式异常，建议复用正则校验和空值兜底规则。",
            source="agent-runtime-tool-feedback",
            workspace_key=workspace_key,
            memory_namespace=memory_namespace,
            source_tool_name="quality.rule.suggest",
            source_status="succeeded",
            source_audit_id="audit-a",
            source_run_id="run-a",
            output_ref="minio://quality/private-result.json",
            retention_days=30,
            sensitivity_level=sensitivity_level,
            idempotency_key="tenant-a|project-a|quality.rule.suggest|audit-a",
        )


if __name__ == "__main__":
    unittest.main()
