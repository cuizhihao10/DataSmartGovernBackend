"""Small unit tests for post-bridge resource-locator pairing."""

from __future__ import annotations

import sys
from pathlib import Path
from types import SimpleNamespace


SRC_ROOT = Path(__file__).resolve().parents[1] / "src"
if str(SRC_ROOT) not in sys.path:
    sys.path.insert(0, str(SRC_ROOT))

from datasmart_ai_runtime.services.agent_execution.post_resource_specialist_verification import (
    _feedback_resource_locators,
    _resolve_resource_locator,
)
from datasmart_ai_runtime.services.model_gateway.model_tool_result_feedback import (
    ToolExecutionFeedbackStatus,
)


def _receipt(
    *,
    result: dict[str, object],
    audit_id: str,
    run_id: str,
    tool_name: str = "sync.task.draft.save",
) -> SimpleNamespace:
    output_ref = f"agent-runtime://sessions/session-1/runs/{run_id}/tool-executions/{audit_id}/result"
    return SimpleNamespace(
        tool_name=tool_name,
        status=ToolExecutionFeedbackStatus.SUCCEEDED,
        audit_id=audit_id,
        run_id=run_id,
        output_ref=output_ref,
        result=result,
    )


def test_locator_resolution_requires_one_receipt_pair_and_binds_its_run() -> None:
    mismatched = (
        _receipt(result={"taskId": 101}, audit_id="task-receipt", run_id="run-task"),
        _receipt(
            result={"executionId": 202},
            audit_id="execution-receipt",
            run_id="run-execution",
            tool_name="sync.execution.status",
        ),
    )
    assert _resolve_resource_locator(_feedback_resource_locators(SimpleNamespace(feedback_items=mismatched))) is None

    paired = (
        _receipt(
            result={"taskId": 101, "executionId": 202},
            audit_id="paired-receipt",
            run_id="run-paired",
        ),
        _receipt(result={"taskId": 101}, audit_id="later-receipt", run_id="run-unrelated"),
    )
    resolved = _resolve_resource_locator(_feedback_resource_locators(SimpleNamespace(feedback_items=paired)))

    assert resolved is not None
    assert resolved.task_id == "101"
    assert resolved.execution_id == "202"
    assert resolved.run_id == "run-paired"
