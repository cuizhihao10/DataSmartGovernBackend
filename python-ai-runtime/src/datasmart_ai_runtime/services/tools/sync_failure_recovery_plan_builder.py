"""Initial tool planning for an existing data-sync execution failure."""

from __future__ import annotations

from collections.abc import Callable

from datasmart_ai_runtime.domain.contracts import AgentRequest, ToolDefinition, ToolPlan


class SyncFailureRecoveryPlanBuilder:
    """Start recovery from durable diagnosis instead of creating a new task draft.

    An existing failed execution is durable business evidence, not a request to
    create another synchronization task. Keeping this first step read-only also
    guarantees that schema repair, quarantine, retry, and replay remain behind
    their own validation and approval boundaries.
    """

    _TOOL_NAME = "sync.execution.diagnose"

    def build(
        self,
        *,
        request: AgentRequest,
        candidate_tools: set[str],
        tools: dict[str, ToolDefinition],
        plan_factory: Callable[[ToolDefinition, str, dict[str, object]], ToolPlan],
    ) -> tuple[ToolPlan, ...]:
        """Plan the read-only diagnosis node when recovery intent is explicit."""

        requested = self._TOOL_NAME in candidate_tools or bool(
            request.variables.get("diagnoseSyncExecution")
            or request.variables.get("diagnose_sync_execution")
        )
        tool = tools.get(self._TOOL_NAME)
        if not requested or tool is None:
            return ()

        arguments: dict[str, object] = {}
        task_id = request.variables.get("taskId") or request.variables.get("task_id")
        execution_id = request.variables.get("executionId") or request.variables.get("execution_id")
        if task_id is not None:
            arguments["taskId"] = task_id
        if execution_id is not None:
            arguments["executionId"] = execution_id

        return (
            plan_factory(
                tool,
                (
                    "The existing synchronization execution failed, so recovery must start "
                    "by reading its durable execution, object, shard, and structured-error "
                    "ledgers. This step only produces a low-sensitive diagnosis; it does not "
                    "create a new task or apply schema repair, quarantine, retry, or replay."
                ),
                arguments,
            ),
        )


__all__ = ["SyncFailureRecoveryPlanBuilder"]
