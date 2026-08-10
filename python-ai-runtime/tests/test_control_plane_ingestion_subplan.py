"""Regression tests for the Java control-plane ingestion frontier.

The synchronization planner builds source and target metadata branches as one
deterministic evidence stage.  These tests ensure a conservative per-turn tool
budget cannot silently turn that stage into source-only metadata, while still
proving that clarification and dependency gates remain fail-closed.
"""

from __future__ import annotations

import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.api.agent.plan_response import (
    _control_plane_ready_subplan,
    _delegate_legacy_knowledge_rag_to_specialist,
)
from datasmart_ai_runtime.domain.contracts import (
    AgentPlan,
    ToolParameterIssue,
    ToolParameterIssueAction,
    ToolParameterValidationResult,
    ToolPlan,
)
from datasmart_ai_runtime.services.tools.tool_execution_readiness import (
    ToolExecutionReadinessPolicy,
    ToolExecutionReadinessService,
)


class ControlPlaneIngestionSubplanTest(unittest.TestCase):
    """Verify the paired metadata evidence exception and its safety bounds."""

    def test_target_metadata_joins_when_only_the_sync_budget_throttles_it(self) -> None:
        """Both datasource metadata reads must reach Java in the first evidence run."""

        plan = self._sync_evidence_plan()
        readiness = ToolExecutionReadinessService().evaluate(
            plan.tool_plans,
            policy=ToolExecutionReadinessPolicy(max_auto_sync_tools=3),
        )

        subplan = _control_plane_ready_subplan(plan, readiness)

        self.assertEqual(
            (
                "datasource.source.connection.test",
                "datasource.target.connection.test",
                "datasource.source.metadata.read",
                "datasource.target.metadata.read",
            ),
            tuple(item.tool_name for item in subplan.tool_plans),
        )

    def test_metadata_does_not_bypass_a_connection_budget_gate(self) -> None:
        """A metadata read cannot run when its matching connection test is throttled."""

        plan = self._sync_evidence_plan()
        readiness = ToolExecutionReadinessService().evaluate(
            plan.tool_plans,
            policy=ToolExecutionReadinessPolicy(max_auto_sync_tools=1),
        )

        subplan = _control_plane_ready_subplan(plan, readiness)

        self.assertEqual(
            ("datasource.source.connection.test",),
            tuple(item.tool_name for item in subplan.tool_plans),
        )

    def test_metadata_does_not_bypass_parameter_clarification(self) -> None:
        """Missing metadata parameters stay outside Java until the user clarifies them."""

        plan = self._sync_evidence_plan(
            target_validation=ToolParameterValidationResult(
                can_execute=False,
                can_create_draft=False,
                issues=(
                    ToolParameterIssue(
                        parameter_name="datasourceId",
                        expected_type="integer",
                        action=ToolParameterIssueAction.MUST_CLARIFY,
                        message="目标数据源尚未选择。",
                    ),
                ),
            )
        )
        readiness = ToolExecutionReadinessService().evaluate(
            plan.tool_plans,
            policy=ToolExecutionReadinessPolicy(max_auto_sync_tools=3),
        )

        subplan = _control_plane_ready_subplan(plan, readiness)

        self.assertNotIn(
            "datasource.target.metadata.read",
            tuple(item.tool_name for item in subplan.tool_plans),
        )

    def test_knowledge_specialist_owns_rag_without_suppressing_other_java_tools(self) -> None:
        """Legacy RAG must not block Specialist turns, while Java metadata evidence stays intact."""

        plan = AgentPlan(
            request_id="request-specialist-rag-ownership",
            selected_route=None,
            state_trace=("plan_tools",),
            tool_plans=(
                ToolPlan(tool_name="knowledge.rag.query", reason="retrieve evidence", arguments={}),
                ToolPlan(
                    tool_name="datasource.source.metadata.read",
                    reason="read authoritative metadata",
                    arguments={"datasourceId": 51},
                ),
            ),
            requires_human_approval=False,
            response_summary="Delegate knowledge retrieval and retain metadata evidence.",
        )

        delegated = _delegate_legacy_knowledge_rag_to_specialist(
            plan,
            specialist_agent_coordinator=object(),
            specialist_allowed_tools_by_role={"KNOWLEDGE_AGENT": ("knowledge.rag.query",)},
        )

        self.assertEqual(
            ("datasource.source.metadata.read",),
            tuple(item.tool_name for item in delegated.tool_plans),
        )
        ownership = delegated.workflow_diagnostics["specialistToolOwnership"]
        self.assertEqual("KNOWLEDGE_AGENT", ownership["ownerRole"])
        self.assertEqual(1, ownership["delegatedPlanCount"])
        self.assertTrue(ownership["javaLegacyPlanSuppressed"])

    def test_legacy_rag_is_retained_without_explicit_specialist_capability(self) -> None:
        """Deployments that do not expose Knowledge Agent keep the durable Java RAG worker path."""

        plan = AgentPlan(
            request_id="request-legacy-rag",
            selected_route=None,
            state_trace=("plan_tools",),
            tool_plans=(ToolPlan(tool_name="knowledge.rag.query", reason="retrieve evidence"),),
            requires_human_approval=False,
            response_summary="Use legacy RAG.",
        )

        unchanged = _delegate_legacy_knowledge_rag_to_specialist(
            plan,
            specialist_agent_coordinator=object(),
            specialist_allowed_tools_by_role={"KNOWLEDGE_AGENT": ()},
        )

        self.assertIs(plan, unchanged)
        self.assertEqual(("knowledge.rag.query",), tuple(item.tool_name for item in unchanged.tool_plans))

    @staticmethod
    def _sync_evidence_plan(
        *,
        target_validation: ToolParameterValidationResult | None = None,
    ) -> AgentPlan:
        """Build the four-node read-only shape emitted by the real sync planner."""

        return AgentPlan(
            request_id="request-paired-metadata",
            selected_route=None,
            state_trace=("plan_tools",),
            tool_plans=(
                ToolPlan(
                    tool_name="datasource.source.connection.test",
                    reason="test source",
                    arguments={"datasourceId": 51},
                ),
                ToolPlan(
                    tool_name="datasource.target.connection.test",
                    reason="test target",
                    arguments={"datasourceId": 52},
                ),
                ToolPlan(
                    tool_name="datasource.source.metadata.read",
                    reason="read source metadata",
                    arguments={"datasourceId": 51},
                ),
                ToolPlan(
                    tool_name="datasource.target.metadata.read",
                    reason="read target metadata",
                    arguments={"datasourceId": 52},
                    parameter_validation=target_validation or ToolParameterValidationResult(),
                ),
            ),
            requires_human_approval=False,
            response_summary="Collect both datasource metadata facts.",
        )


if __name__ == "__main__":
    unittest.main()
