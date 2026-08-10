"""Run deterministic specialist verification after Java creates sync resources.

This module is deliberately located in the execution layer rather than the API
layer.  Both the initial ``/agent/plans`` bridge and the later Java
``confirm-and-execute`` callback need the exact same trust boundary:

* only successful, allow-listed Java tool feedback is considered;
* the feedback must carry audit ID, run ID and a matching
  ``agent-runtime://`` output reference;
* only positive Java ``Long`` task/execution identifiers are extracted;
* a task/execution pair is admitted only when one trusted receipt proves both
  sides; ambiguous cross-receipt combinations fail closed;
* PRECHECK_AGENT and MONITOR_AGENT receive those locators in a separate,
  read-only specialist wave whose result is written by the configured durable
  fact sink.

Keeping this policy in a dependency-light service prevents the execution layer
from importing FastAPI response assembly and avoids a circular import through
the large ``api.agent`` and ``services.multi_agent`` package initializers.
"""

from __future__ import annotations

import hashlib
import json
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from typing import Any

from datasmart_ai_runtime.domain.contracts import AgentPlan, AgentRequest
from datasmart_ai_runtime.services.model_gateway.model_tool_result_feedback import (
    ToolExecutionFeedbackStatus,
)


_RESOURCE_FACT_TOOL_NAMES = frozenset(
    {
        "sync.task.draft.save",
        "sync.task.precheck",
        "sync.task.publish",
        "sync.task.run",
        "sync.execution.status",
        "sync.execution.diagnose",
        "sync.execution.failed-objects.retry",
        "sync.dirty-record.quarantine.preview",
        "sync.dirty-record.quarantine.apply",
        "sync.dirty-record.replay",
    }
)
_RESOURCE_LOCATOR_NAMES = frozenset(
    {"taskId", "task_id", "executionId", "execution_id"}
)
_RESOURCE_CONTEXT_LOCATOR_NAMES = _RESOURCE_LOCATOR_NAMES | frozenset(
    {
        "taskReference",
        "task_reference",
        "executionReference",
        "execution_reference",
        "monitorTaskId",
        "monitor_task_id",
        "resourceReference",
        "resource_reference",
        "resourceLocator",
        "resource_locator",
        "resourceFingerprint",
        "resource_fingerprint",
        "runId",
        "run_id",
        "auditId",
        "audit_id",
        "outputRef",
        "output_ref",
    }
)
_TRUSTED_RESULT_CONTAINER_NAMES = frozenset(
    {"data", "output", "receipt", "result"}
)
_JAVA_OUTPUT_REFERENCE_PREFIX = "agent-runtime://sessions/"
_JAVA_LONG_MAX = 9_223_372_036_854_775_807


@dataclass(frozen=True)
class _TrustedResourceLocatorReceipt:
    """Keep normalized locators and their receipt run bound together."""

    task_id: str | None
    execution_id: str | None
    run_id: str | None
    invalid: bool = False


@dataclass(frozen=True)
class _ResolvedResourceLocator:
    """Represent the one resource locator that the post-bridge wave may use."""

    task_id: str | None
    execution_id: str | None
    run_id: str

    def fingerprint_payload(self) -> dict[str, str]:
        """Return only the resource identity, excluding receipt/audit metadata."""

        payload: dict[str, str] = {}
        if self.task_id is not None:
            payload["taskId"] = self.task_id
        if self.execution_id is not None:
            payload["executionId"] = self.execution_id
        return payload


def control_plane_resource_fingerprint(feedback: Any | None) -> str | None:
    """Return a stable fingerprint for trusted Java task/execution facts.

    The fingerprint is calculated only after one coherent locator has been
    resolved.  It contains no receipt metadata, SQL, task configuration, prompt,
    model output, datasource details or user data.
    """

    resolved_locator = _resolve_resource_locator(_feedback_resource_locators(feedback))
    if resolved_locator is None:
        return None
    return _resource_locator_fingerprint(resolved_locator)


def run_post_bridge_verification_wave(
    *,
    request: AgentRequest,
    plan: AgentPlan,
    control_plane_feedback: Any | None,
    previous_resource_fingerprint: str | None,
    specialist_agent_coordinator: Any,
    specialist_allowed_tools_by_role: Mapping[str, tuple[str, ...]],
    checkpoint_recorded: bool,
    event_sink: Callable[[Mapping[str, Any]], None] | None,
    base_context: Mapping[str, Any],
    execution_session: Mapping[str, Any],
) -> tuple[Any | None, dict[str, Any]]:
    """Run PRECHECK_AGENT and MONITOR_AGENT for a newly created resource.

    The caller supplies the specialist coordinator as a structural dependency
    to keep this low-level module independent of the multi-agent package
    initializer.  The coordinator still enforces registration, role/tool
    allow-lists, project delegation and durable result-sink behavior.

    No coherent resource locator means no wave.  An unchanged fingerprint also
    skips the wave, which makes Java retries and replay idempotent rather than
    generating duplicate specialist facts for the same task/execution pair.
    """

    resolved_locator = _resolve_resource_locator(_feedback_resource_locators(control_plane_feedback))
    current_fingerprint = _resource_locator_fingerprint(resolved_locator)
    task_id = resolved_locator.task_id if resolved_locator is not None else None
    execution_id = resolved_locator.execution_id if resolved_locator is not None else None
    resource_reference = task_id or execution_id
    if current_fingerprint is None or resource_reference is None:
        return None, _verification_summary(
            status="SKIPPED_NO_TRUSTED_TASK_FACT",
            resource_fingerprint=current_fingerprint,
            previous_resource_fingerprint=previous_resource_fingerprint,
            task_id=None,
            execution_id=None,
            batch=None,
        )
    if current_fingerprint == previous_resource_fingerprint:
        return None, _verification_summary(
            status="SKIPPED_RESOURCE_FACT_UNCHANGED",
            resource_fingerprint=current_fingerprint,
            previous_resource_fingerprint=previous_resource_fingerprint,
            task_id=task_id,
            execution_id=execution_id,
            batch=None,
        )

    role_names = ("PRECHECK_AGENT", "MONITOR_AGENT")
    attempts = tuple(
        {
            "turnId": _verification_turn_id(request, current_fingerprint, role),
            "agentRole": role,
            "turnStatus": "READY_FOR_SPECIALIST_TURN",
        }
        for role in role_names
    )
    verification_runner = {
        "maxConcurrentAgentTurns": 2,
        "turnAttempts": attempts,
    }
    session = dict(execution_session)
    session["runId"] = (
        (resolved_locator.run_id if resolved_locator is not None else None)
        or session.get("runId")
        or request.request_id
    )
    session["workItems"] = tuple(
        {
            "agentRole": role,
            "dependsOnRoles": ("MASTER_ORCHESTRATOR",),
        }
        for role in role_names
    )
    context = {
        **_sanitize_post_bridge_context(base_context),
        "resourceReference": resource_reference,
        "postBridgeVerification": True,
        "resourceFingerprint": current_fingerprint,
    }
    if task_id is not None:
        context["taskId"] = task_id
        context["taskReference"] = task_id
    if execution_id is not None:
        context["executionId"] = execution_id
        context["executionReference"] = execution_id

    batch = specialist_agent_coordinator.run(
        request=request,
        turn_runner=verification_runner,
        execution_session=session,
        allowed_tools_by_role=specialist_allowed_tools_by_role,
        base_context=context,
        checkpoint_recorded=checkpoint_recorded,
        event_sink=event_sink,
    )
    return batch, _verification_summary(
        status="EXECUTED",
        resource_fingerprint=current_fingerprint,
        previous_resource_fingerprint=previous_resource_fingerprint,
        task_id=task_id,
        execution_id=execution_id,
        batch=batch,
    )


def _feedback_resource_locators(
    feedback: Any | None,
) -> tuple[_TrustedResourceLocatorReceipt, ...]:
    """Extract locator evidence without losing the receipt that carried it.

    A malformed locator-bearing receipt is retained as an invalid evidence
    marker.  Silently dropping it could let another receipt supply the missing
    side and recreate the same cross-receipt pairing bug.
    """

    collected: list[_TrustedResourceLocatorReceipt] = []
    for item in tuple(getattr(feedback, "feedback_items", ()) or ()):
        if not _is_trusted_java_feedback_item(item):
            continue
        extracted: dict[str, set[str]] = {}
        invalid_names: set[str] = set()
        present_names: set[str] = set()
        _collect_locator_values(
            getattr(item, "result", None),
            extracted,
            invalid_names,
            present_names,
            depth=0,
        )
        if not present_names:
            continue
        run_id = _non_empty_text(getattr(item, "run_id", None))
        if invalid_names or any(len(values) != 1 for values in extracted.values()) or run_id is None:
            collected.append(
                _TrustedResourceLocatorReceipt(
                    task_id=None,
                    execution_id=None,
                    run_id=run_id,
                    invalid=True,
                )
            )
            continue
        collected.append(
            _TrustedResourceLocatorReceipt(
                task_id=_single_extracted_value(extracted, "taskId"),
                execution_id=_single_extracted_value(extracted, "executionId"),
                run_id=run_id,
            )
        )

    unique: list[_TrustedResourceLocatorReceipt] = []
    seen: set[_TrustedResourceLocatorReceipt] = set()
    for item in collected:
        if item in seen:
            continue
        seen.add(item)
        unique.append(item)
    return tuple(unique)


def _is_trusted_java_feedback_item(item: Any) -> bool:
    """Require the complete Java audit/run/output-reference evidence tuple."""

    tool_name = str(getattr(item, "tool_name", "") or "").strip()
    if tool_name not in _RESOURCE_FACT_TOOL_NAMES:
        return False
    status = getattr(item, "status", None)
    normalized_status = str(getattr(status, "value", status) or "").strip().lower()
    if normalized_status != ToolExecutionFeedbackStatus.SUCCEEDED.value:
        return False
    audit_id = _non_empty_text(getattr(item, "audit_id", None))
    run_id = _non_empty_text(getattr(item, "run_id", None))
    # Real Java identifiers use prefixes such as ``ags_``, ``agr_`` and ``atea_`` plus a UUID-sized
    # digest. Their complete agent-runtime URI is currently 164 characters, so the generic 160-character
    # scalar limit would remove the trailing ``/result`` and reject an otherwise valid receipt. Keep a
    # separate bounded URI allowance and reject oversize input instead of truncating security evidence.
    output_ref = _bounded_text(getattr(item, "output_ref", None), max_length=512)
    if not audit_id or not run_id or not output_ref:
        return False
    if not output_ref.startswith(_JAVA_OUTPUT_REFERENCE_PREFIX):
        return False
    return (
        f"/runs/{run_id}/" in output_ref
        and f"/tool-executions/{audit_id}/result" in output_ref
    )


def _collect_locator_values(
    value: Any,
    output: dict[str, set[str]],
    invalid_names: set[str],
    present_names: set[str],
    *,
    depth: int,
) -> None:
    """Read locators through a small allow-list of result wrapper nodes."""

    if depth > 2 or not isinstance(value, Mapping):
        return
    for raw_name, raw_value in value.items():
        name = str(raw_name)
        if name in _RESOURCE_LOCATOR_NAMES:
            normalized_name = "taskId" if "task" in name.lower() else "executionId"
            # Java may omit the optional execution locator with JSON null.  A
            # concrete but invalid value is different: it makes the receipt
            # ambiguous and must invalidate the whole evidence unit.
            if raw_value is None:
                continue
            present_names.add(normalized_name)
            normalized_value = _positive_identifier(raw_value)
            if normalized_value is not None:
                output.setdefault(normalized_name, set()).add(normalized_value)
            else:
                invalid_names.add(normalized_name)
        elif name in _TRUSTED_RESULT_CONTAINER_NAMES and isinstance(raw_value, Mapping):
            _collect_locator_values(
                raw_value,
                output,
                invalid_names,
                present_names,
                depth=depth + 1,
            )


def _positive_identifier(value: Any) -> str | None:
    """Normalize a positive Java Long and reject every other representation."""

    if isinstance(value, bool) or value is None:
        return None
    if isinstance(value, int):
        number = value
    elif isinstance(value, str):
        text = value.strip()
        if not text or not text.isascii() or not text.isdecimal():
            return None
        number = int(text)
    else:
        return None
    if number <= 0 or number > _JAVA_LONG_MAX:
        return None
    return str(number)


def _sanitize_post_bridge_context(value: Mapping[str, Any]) -> dict[str, Any]:
    """Remove stale locator carriers before adding the resolved receipt identity."""

    def sanitize(item: Any) -> Any:
        if isinstance(item, Mapping):
            return {
                key: sanitize(nested)
                for key, nested in item.items()
                if str(key) not in _RESOURCE_CONTEXT_LOCATOR_NAMES
            }
        if isinstance(item, tuple):
            return tuple(sanitize(nested) for nested in item)
        if isinstance(item, list):
            return [sanitize(nested) for nested in item]
        return item

    sanitized = sanitize(value)
    return sanitized if isinstance(sanitized, dict) else {}


def _non_empty_text(value: Any) -> str | None:
    """Read an audit/run identifier using the compact scalar length contract."""

    return _bounded_text(value, max_length=160)


def _bounded_text(value: Any, *, max_length: int) -> str | None:
    """Validate one scalar reference without truncating identity-bearing evidence.

    Truncation is unsafe for audit IDs and output references because the resulting text no longer names
    the Java fact that was actually persisted. Returning ``None`` for oversized input keeps the trust
    boundary fail-closed while allowing each reference family to define an explicit, documented limit.
    """

    if value is None or isinstance(value, (bool, Mapping, list, tuple, set)):
        return None
    text = str(value).strip()
    if not text or len(text) > max_length:
        return None
    return text


def _single_extracted_value(
    extracted: Mapping[str, set[str]],
    name: str,
) -> str | None:
    """Return a value only when one normalized value exists for a field."""

    values = extracted.get(name, set())
    return next(iter(values)) if len(values) == 1 else None


def _resolve_resource_locator(
    locators: tuple[_TrustedResourceLocatorReceipt, ...],
) -> _ResolvedResourceLocator | None:
    """Resolve one coherent locator, never by independently selecting fields.

    A complete task/execution pair proves its relationship because both values
    came from one trusted receipt.  Standalone receipts remain useful for the
    task-before-execution and execution-only monitoring states, but a snapshot
    containing both standalone sides is ambiguous and is rejected.  Multiple
    different candidates are also rejected instead of choosing an arrival-order
    winner.
    """

    if not locators or any(item.invalid for item in locators):
        return None
    usable = tuple(item for item in locators if item.task_id or item.execution_id)
    if not usable:
        return None

    complete = tuple(
        item
        for item in usable
        if item.task_id is not None and item.execution_id is not None
    )
    pair_keys = {(item.task_id, item.execution_id) for item in complete}
    if len(pair_keys) > 1:
        return None
    if complete:
        task_id, execution_id = next(iter(pair_keys))
        if task_id is None or execution_id is None:
            return None
        # Any additional locator must agree with the proven pair. This allows
        # a task-only draft receipt to accompany its later complete execution
        # receipt, but never lets it contribute a different ID.
        if any(
            (item.task_id is not None and item.task_id != task_id)
            or (item.execution_id is not None and item.execution_id != execution_id)
            for item in usable
        ):
            return None
        run_ids = {item.run_id for item in complete if item.run_id}
        if len(run_ids) != 1:
            return None
        return _ResolvedResourceLocator(
            task_id=task_id,
            execution_id=execution_id,
            run_id=next(iter(run_ids)),
        )

    task_ids = {item.task_id for item in usable if item.task_id is not None}
    execution_ids = {item.execution_id for item in usable if item.execution_id is not None}
    # Two independent one-sided receipts do not prove a parent/child relation.
    if task_ids and execution_ids:
        return None
    values = task_ids or execution_ids
    if len(values) != 1:
        return None
    selected_value = next(iter(values))
    selected_receipts = tuple(
        item
        for item in usable
        if item.task_id == selected_value or item.execution_id == selected_value
    )
    run_ids = {item.run_id for item in selected_receipts if item.run_id}
    if len(run_ids) != 1:
        return None
    return _ResolvedResourceLocator(
        task_id=selected_value if task_ids else None,
        execution_id=selected_value if execution_ids else None,
        run_id=next(iter(run_ids)),
    )


def _resource_locator_fingerprint(
    locator: _ResolvedResourceLocator | None,
) -> str | None:
    """Hash one already-resolved locator for idempotent post-bridge replay."""

    if locator is None:
        return None
    canonical = json.dumps(
        locator.fingerprint_payload(),
        ensure_ascii=True,
        sort_keys=True,
        separators=(",", ":"),
    )
    return "sha256:" + hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def _verification_turn_id(request: AgentRequest, fingerprint: str, role: str) -> str:
    """Create an idempotent turn ID for one resource fingerprint and role."""

    material = f"{request.request_id}|{fingerprint}|{role}|post-bridge-verification"
    return "post-bridge-" + hashlib.sha256(material.encode("utf-8")).hexdigest()[:24]


def _verification_summary(
    *,
    status: str,
    resource_fingerprint: str | None,
    previous_resource_fingerprint: str | None,
    task_id: str | None,
    execution_id: str | None,
    batch: Any | None,
) -> dict[str, Any]:
    """Expose only low-sensitive resource, role and batch status evidence."""

    results = tuple(getattr(batch, "results", ()) or ()) if batch is not None else ()
    return {
        "status": status,
        "resourceChanged": bool(
            resource_fingerprint
            and resource_fingerprint != previous_resource_fingerprint
        ),
        "resourceFingerprint": resource_fingerprint,
        "taskId": task_id,
        "executionId": execution_id,
        "executedRoles": tuple(item.role.value for item in results),
        "batchStatus": getattr(batch, "status", None) if batch is not None else None,
        "payloadPolicy": "LOW_SENSITIVE_POST_BRIDGE_VERIFICATION_ONLY",
    }


__all__ = (
    "control_plane_resource_fingerprint",
    "run_post_bridge_verification_wave",
)
