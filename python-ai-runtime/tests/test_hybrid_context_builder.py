import os
import sys
import unittest
from datetime import datetime, timedelta, timezone

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.domain.context import ContextBlock, ContextSensitivityLevel, ContextSourceType
from datasmart_ai_runtime.domain.contracts import AgentRequest
from datasmart_ai_runtime.domain.events import AgentRuntimeEventType
from datasmart_ai_runtime.services.hybrid_context_builder import ContextSelectionPolicy, HybridContextBuilder


class FixedContextBuilder:
    def __init__(self, blocks: tuple[ContextBlock, ...]) -> None:
        self._blocks = blocks

    def build(self, request: AgentRequest) -> tuple[ContextBlock, ...]:
        return self._blocks


class HybridContextBuilderTest(unittest.TestCase):
    def test_filters_expired_and_restricted_context_by_default(self) -> None:
        now = datetime.now(timezone.utc)
        blocks = (
            self._block("valid", 0.9, ContextSensitivityLevel.INTERNAL, now + timedelta(minutes=5), 20),
            self._block("expired", 1.0, ContextSensitivityLevel.INTERNAL, now - timedelta(minutes=1), 10),
            self._block("restricted", 1.0, ContextSensitivityLevel.RESTRICTED, now + timedelta(minutes=5), 10),
        )

        builder = HybridContextBuilder(builders=(FixedContextBuilder(blocks),))
        selected = builder.build(self._request())

        self.assertEqual(("valid",), tuple(block.source_id for block in selected))
        event_types = tuple(event.event_type for event in builder.last_events())
        self.assertIn(AgentRuntimeEventType.CONTEXT_COLLECTED, event_types)
        self.assertIn(AgentRuntimeEventType.CONTEXT_FILTERED, event_types)
        self.assertIn(AgentRuntimeEventType.CONTEXT_SELECTED, event_types)
        filter_reasons = {
            event.attributes.get("reason")
            for event in builder.last_events()
            if event.event_type == AgentRuntimeEventType.CONTEXT_FILTERED
        }
        self.assertEqual({"expired", "sensitivity_not_allowed"}, filter_reasons)

    def test_deduplicates_by_source_id_and_keeps_higher_relevance(self) -> None:
        now = datetime.now(timezone.utc)
        blocks = (
            self._block("same", 0.7, ContextSensitivityLevel.INTERNAL, now + timedelta(minutes=5), 20),
            self._block("same", 0.95, ContextSensitivityLevel.INTERNAL, now + timedelta(minutes=5), 50),
        )

        builder = HybridContextBuilder(builders=(FixedContextBuilder(blocks),))
        selected = builder.build(self._request())

        self.assertEqual(1, len(selected))
        self.assertEqual(0.95, selected[0].relevance_score)
        self.assertIn(
            AgentRuntimeEventType.CONTEXT_DEDUPLICATED,
            {event.event_type for event in builder.last_events()},
        )

    def test_applies_token_budget_after_sorting(self) -> None:
        now = datetime.now(timezone.utc)
        blocks = (
            self._block("top", 1.0, ContextSensitivityLevel.INTERNAL, now + timedelta(minutes=5), 60),
            self._block("second", 0.9, ContextSensitivityLevel.INTERNAL, now + timedelta(minutes=5), 60),
            self._block("third", 0.8, ContextSensitivityLevel.INTERNAL, now + timedelta(minutes=5), 60),
        )
        policy = ContextSelectionPolicy(max_tokens=120)

        builder = HybridContextBuilder(builders=(FixedContextBuilder(blocks),), policy=policy)
        selected = builder.build(self._request())

        self.assertEqual(("top", "second"), tuple(block.source_id for block in selected))
        self.assertIn(
            AgentRuntimeEventType.CONTEXT_TRUNCATED,
            {event.event_type for event in builder.last_events()},
        )

    def test_prefers_lower_sensitivity_when_relevance_is_equal(self) -> None:
        now = datetime.now(timezone.utc)
        blocks = (
            self._block("confidential", 0.9, ContextSensitivityLevel.CONFIDENTIAL, now + timedelta(minutes=5), 10),
            self._block("public", 0.9, ContextSensitivityLevel.PUBLIC, now + timedelta(minutes=5), 10),
        )

        selected = HybridContextBuilder(builders=(FixedContextBuilder(blocks),)).build(self._request())

        self.assertEqual(("public", "confidential"), tuple(block.source_id for block in selected))

    @staticmethod
    def _request() -> AgentRequest:
        return AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="user-a",
            objective="测试上下文组合",
        )

    @staticmethod
    def _block(
        source_id: str,
        relevance: float,
        sensitivity: ContextSensitivityLevel,
        expires_at: datetime,
        token_estimate: int,
    ) -> ContextBlock:
        return ContextBlock(
            source_type=ContextSourceType.SYSTEM_POLICY,
            title=source_id,
            content=f"{source_id} content",
            relevance_score=relevance,
            sensitivity_level=sensitivity,
            source_id=source_id,
            expires_at=expires_at,
            token_estimate=token_estimate,
        )


if __name__ == "__main__":
    unittest.main()
