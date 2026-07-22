"""Build the first governed node for an uploaded task-import artifact.

The browser uploads CSV/XLSX content to data-sync and gives the Agent only the
opaque artifact reference.  The deterministic first node is a real dry-run; all
later branches (RAG lookup, repair proposal, re-validation and commit) are chosen
by the model from real tool feedback and still pass through Java governance.
"""

from __future__ import annotations

from collections.abc import Callable

from datasmart_ai_runtime.domain.contracts import AgentRequest, ToolDefinition, ToolPlan


class SyncTaskImportToolPlanBuilder:
    """Create a fail-closed dry-run plan when an artifact reference is present."""

    TOOL_NAME = "sync.task.import.dry-run"

    def build(
        self,
        *,
        request: AgentRequest,
        candidate_tools: set[str],
        tools: dict[str, ToolDefinition],
        plan_factory: Callable[[ToolDefinition, str, dict[str, object]], ToolPlan],
    ) -> tuple[ToolPlan, ...]:
        artifact_ref = self._artifact_ref(request)
        requested = self.TOOL_NAME in candidate_tools or artifact_ref is not None
        tool = tools.get(self.TOOL_NAME)
        if not requested or tool is None:
            return ()
        return (
            plan_factory(
                tool,
                (
                    "用户已上传任务定义文件。先按不可变制品引用执行真实 dry-run，"
                    "把解析、唯一键、模板与调度问题结构化后再由模型选择检索、修复或提交。"
                ),
                {
                    "artifactRef": artifact_ref,
                    "runImmediately": bool(
                        request.variables.get("taskImportRunImmediately")
                        or request.variables.get("task_import_run_immediately")
                    ),
                },
            ),
        )

    @staticmethod
    def _artifact_ref(request: AgentRequest) -> str | None:
        raw = (
            request.variables.get("taskImportArtifactRef")
            or request.variables.get("task_import_artifact_ref")
            or request.variables.get("artifactRef")
        )
        normalized = str(raw or "").strip()
        return normalized or None


__all__ = ["SyncTaskImportToolPlanBuilder"]
