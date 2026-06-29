import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.domain.context import ContextBlock, ContextSensitivityLevel, ContextSourceType
from datasmart_ai_runtime.services.context_micro_compactor import (
    ContextMicroCompactionDecision,
    ContextMicroCompactionPolicy,
    ContextMicroCompactor,
)


class ContextMicroCompactorTest(unittest.TestCase):
    """上下文微压缩服务测试。

    这里重点保护三件事：
    - 长上下文确实会被压缩，而不是只能被整体截断；
    - 压缩事件摘要只包含低敏统计，不把上下文正文扩散到 runtime event；
    - 摘要内容进入模型前会对明显密钥、长 token 和 SQL 片段做保守脱敏。
    """

    def test_compacts_long_block_and_redacts_high_risk_fragments(self) -> None:
        """长上下文应压缩为确定性摘要，并避免传播明显敏感片段。"""

        secret_value = "sk_abcdefghijklmnopqrstuvwxyz1234567890"
        block = self._long_block(secret_value)
        compactor = ContextMicroCompactor(
            ContextMicroCompactionPolicy(
                trigger_token_threshold=80,
                target_token_budget=60,
                max_segments=4,
                max_segment_chars=120,
                minimum_saved_tokens=16,
            )
        )

        report = compactor.compact((block,))
        compacted_block = report.blocks[0]
        event_attributes = report.to_event_attributes()
        serialized_event = str(event_attributes).lower()

        self.assertEqual(1, report.compacted_count)
        self.assertEqual(ContextMicroCompactionDecision.COMPACTED, report.items[0].decision)
        self.assertLess(report.compacted_token_estimate, report.original_token_estimate)
        self.assertIn("上下文微压缩摘要", compacted_block.content)
        self.assertTrue(compacted_block.metadata["microCompact"]["applied"])
        self.assertNotIn(secret_value, compacted_block.content)
        self.assertNotIn("select * from", compacted_block.content.lower())
        self.assertNotIn(secret_value.lower(), serialized_event)
        self.assertNotIn("客户手机号", serialized_event)
        self.assertEqual("LOW_SENSITIVE_CONTEXT_COMPACTION_METADATA_ONLY", event_attributes["payloadPolicy"])

    def test_policy_disabled_keeps_original_block(self) -> None:
        """关闭微压缩策略时，应保留原始上下文并在报告里说明原因。"""

        block = self._long_block("disabled-secret-value-abcdefghijklmnopqrstuvwxyz")
        report = ContextMicroCompactor(
            ContextMicroCompactionPolicy(enabled=False, trigger_token_threshold=1)
        ).compact((block,))

        self.assertEqual(block, report.blocks[0])
        self.assertEqual(ContextMicroCompactionDecision.DISABLED, report.items[0].decision)
        self.assertIn("policy_disabled", report.items[0].reason_codes)

    @staticmethod
    def _long_block(secret_value: str) -> ContextBlock:
        """构造包含治理事实、SQL 和密钥样式文本的长上下文。"""

        content = "\n".join(
            (
                "当前请求涉及数据源元数据、质量规则、审批策略和租户项目边界。",
                "必须先校验项目范围与工具权限，禁止绕过人工审批直接执行高风险工具。",
                f"api_key={secret_value}",
                "select * from customer_profile where phone = '客户手机号';",
                "如果模型网关发生限流或 Provider 错误，需要进入重试、fallback 和低敏告警流程。",
                "任务失败后应保留 checkpoint、receipt、retry policy 和 operator note，便于审计回放。",
                "该上下文块被故意拉长，用于模拟真实 RAG/GraphRAG 返回了大量策略、状态和运行提示。",
            )
            * 8
        )
        return ContextBlock(
            source_type=ContextSourceType.SYSTEM_POLICY,
            title="长策略上下文",
            content=content,
            relevance_score=0.99,
            sensitivity_level=ContextSensitivityLevel.CONFIDENTIAL,
            source_id="system-policy-long",
            token_estimate=900,
        )


if __name__ == "__main__":
    unittest.main()
