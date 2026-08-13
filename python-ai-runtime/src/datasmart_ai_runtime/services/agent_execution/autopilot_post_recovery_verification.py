"""Verify a real Autopilot retry with PRECHECK_AGENT and MONITOR_AGENT.

Python recovery planning deliberately stops before business side effects.  Java
and data-sync own authorization, policy evaluation, idempotency, quarantine and
the actual failed-object retry.  This module starts only after data-sync has
returned a receipt for that real action.  It then runs the two read-only
specialists against the receipt-bound task/execution and persists their turn
facts through the existing Java fact sink.

The workflow is replay safe:

* one stable checkpoint thread is derived from the recovery event and receipt;
* one stable turn ID is derived for each specialist role;
* a completed checkpoint is replayed without invoking either specialist again;
* an interrupted attempt may rerun, but Java receives the same turn IDs and
  idempotency keys, so it updates the same two facts instead of inserting new
  facts.

No method in this module can retry a task, apply quarantine, run SQL or widen a
delegation.  It is a post-action observation boundary only.
"""

from __future__ import annotations

import hashlib
import re
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Callable, Mapping

from datasmart_ai_runtime.domain.contracts import AgentRequest
from datasmart_ai_runtime.services.agent_execution.langgraph_durable_checkpointer import (
    LangGraphCheckpointStatus,
    LangGraphDurableCheckpoint,
    LangGraphDurableCheckpointerService,
)
from datasmart_ai_runtime.services.multi_agent.specialist_contracts import (
    SpecialistTurnRequest,
    SpecialistTurnResult,
    SpecialistTurnStatus,
)
from datasmart_ai_runtime.services.multi_agent.specialist_coordinator import (
    SpecialistAgentCoordinator,
)


AUTOPILOT_POST_RECOVERY_VERIFICATION_SCHEMA_VERSION = (
    "datasmart.autopilot.post-recovery-verification.v1"
)
_PAYLOAD_POLICY = "LOW_SENSITIVE_AUTOPILOT_POST_RECOVERY_VERIFICATION_ONLY"
_REQUIRED_ROLES = ("PRECHECK_AGENT", "MONITOR_AGENT")
_SAFE_CODE = re.compile(r"[A-Z0-9_.:-]{1,96}")


class AutopilotPostRecoveryVerificationError(RuntimeError):
    """Signal a retryable post-recovery verification infrastructure failure."""


@dataclass(frozen=True)
class AutopilotPostRecoveryVerificationRequest:
    """Trusted Java projection of one real data-sync retry receipt.

    Identity and scope fields are copied from the already verified recovery
    trigger.  ``task_id`` and ``execution_id`` come from the data-sync retry
    response and must equal the trigger scope.  The equality check prevents a
    malformed or compromised downstream response from redirecting specialist
    reads to another resource.
    """

    event_id: str
    root_session_id: str
    root_run_id: str
    tenant_id: str
    application_id: str
    project_id: str
    user_id: str
    actor_id: str
    agent_id: str
    delegation_id: str
    workspace_key: str
    sync_task_id: str
    current_execution_id: str
    task_id: str
    execution_id: str
    case_id: str
    recovery_action: str
    cycle: int

    def __post_init__(self) -> None:
        """Normalize the internal contract before any checkpoint or HTTP call.

        This is defense in depth, not a replacement for Java authorization.
        Free-form references are length and character bounded, database IDs are
        positive decimal numbers, and the receipt locators must remain inside
        the original trigger scope.
        """

        reference_fields = (
            "event_id",
            "root_session_id",
            "root_run_id",
            "user_id",
            "actor_id",
            "agent_id",
            "delegation_id",
        )
        for field_name in reference_fields:
            value = _bounded_reference(getattr(self, field_name))
            if value is None:
                raise ValueError(f"{field_name} 不是受支持的内部引用")
            object.__setattr__(self, field_name, value)

        workspace_key = str(self.workspace_key or "").strip()
        if not workspace_key or len(workspace_key) > 240:
            raise ValueError("workspace_key 缺失或过长")
        object.__setattr__(self, "workspace_key", workspace_key)

        numeric_fields = (
            "tenant_id",
            "application_id",
            "project_id",
            "sync_task_id",
            "current_execution_id",
            "task_id",
            "execution_id",
            "case_id",
        )
        for field_name in numeric_fields:
            value = str(getattr(self, field_name) or "").strip()
            if not value.isascii() or not value.isdecimal() or int(value) <= 0:
                raise ValueError(f"{field_name} 必须是正整数标识")
            object.__setattr__(self, field_name, value)

        action = str(self.recovery_action or "").strip().upper()
        if not _SAFE_CODE.fullmatch(action):
            raise ValueError("recovery_action 不是受支持的动作代码")
        object.__setattr__(self, "recovery_action", action)
        if self.cycle < 1:
            raise ValueError("cycle 必须大于零")
        if self.task_id != self.sync_task_id:
            raise ValueError("data-sync retry receipt 的 taskId 与恢复触发范围不一致")
        if self.execution_id != self.current_execution_id:
            raise ValueError("data-sync retry receipt 的 executionId 与恢复触发范围不一致")

    @classmethod
    def from_payload(
        cls,
        payload: Mapping[str, Any],
    ) -> "AutopilotPostRecoveryVerificationRequest":
        """Map the fixed Java JSON body without inventing security defaults."""

        if not isinstance(payload, Mapping):
            raise ValueError("Autopilot post-recovery payload 必须是 JSON object")
        return cls(
            event_id=str(payload.get("eventId") or ""),
            root_session_id=str(payload.get("rootSessionId") or ""),
            root_run_id=str(payload.get("rootRunId") or ""),
            tenant_id=str(payload.get("tenantId") or ""),
            application_id=str(payload.get("applicationId") or ""),
            project_id=str(payload.get("projectId") or ""),
            user_id=str(payload.get("userId") or ""),
            actor_id=str(payload.get("actorId") or ""),
            agent_id=str(payload.get("agentId") or ""),
            delegation_id=str(payload.get("delegationId") or ""),
            workspace_key=str(payload.get("workspaceKey") or ""),
            sync_task_id=str(payload.get("syncTaskId") or ""),
            current_execution_id=str(payload.get("currentExecutionId") or ""),
            task_id=str(payload.get("taskId") or ""),
            execution_id=str(payload.get("executionId") or ""),
            case_id=str(payload.get("caseId") or ""),
            recovery_action=str(payload.get("recoveryAction") or ""),
            cycle=int(payload.get("cycle") or 0),
        )

    def binding(self) -> dict[str, str | int]:
        """Return the low-sensitive immutable identity stored in checkpoints."""

        return {
            "eventId": self.event_id,
            "tenantId": self.tenant_id,
            "applicationId": self.application_id,
            "projectId": self.project_id,
            "actorId": self.actor_id,
            "userId": self.user_id,
            "agentId": self.agent_id,
            "sessionId": self.root_session_id,
            "runId": self.root_run_id,
            "delegationId": self.delegation_id,
            "workspaceKey": self.workspace_key,
            "taskId": self.task_id,
            "executionId": self.execution_id,
            "caseId": self.case_id,
            "recoveryAction": self.recovery_action,
            "cycle": self.cycle,
        }


@dataclass(frozen=True)
class AutopilotPostRecoveryVerificationResult:
    """Low-sensitive success contract returned to Java after both facts persist."""

    event_id: str
    task_id: str
    execution_id: str
    executed_roles: tuple[str, ...]
    completed_roles: tuple[str, ...]
    batch_status: str
    checkpoint_thread_id: str
    replayed: bool = False

    def to_summary(self) -> dict[str, Any]:
        """Serialize only stable role and resource evidence, never tool output."""

        return {
            "schemaVersion": AUTOPILOT_POST_RECOVERY_VERIFICATION_SCHEMA_VERSION,
            "status": "VERIFIED",
            "eventId": self.event_id,
            "taskId": self.task_id,
            "executionId": self.execution_id,
            "executedRoles": self.executed_roles,
            "completedRoles": self.completed_roles,
            "batchStatus": self.batch_status,
            "checkpointThreadId": self.checkpoint_thread_id,
            "replayed": self.replayed,
            "payloadPolicy": _PAYLOAD_POLICY,
        }

    @classmethod
    def from_summary(
        cls,
        summary: Mapping[str, Any],
        *,
        replayed: bool,
    ) -> "AutopilotPostRecoveryVerificationResult":
        """Rebuild and validate a terminal checkpoint response for replay."""

        if (
            not isinstance(summary, Mapping)
            or summary.get("schemaVersion")
            != AUTOPILOT_POST_RECOVERY_VERIFICATION_SCHEMA_VERSION
            or summary.get("status") != "VERIFIED"
            or summary.get("payloadPolicy") != _PAYLOAD_POLICY
        ):
            raise AutopilotPostRecoveryVerificationError(
                "AUTOPILOT_POST_RECOVERY_CHECKPOINT_RESULT_INVALID"
            )
        return cls(
            event_id=str(summary.get("eventId") or ""),
            task_id=str(summary.get("taskId") or ""),
            execution_id=str(summary.get("executionId") or ""),
            executed_roles=tuple(str(item) for item in summary.get("executedRoles") or ()),
            completed_roles=tuple(str(item) for item in summary.get("completedRoles") or ()),
            batch_status=str(summary.get("batchStatus") or ""),
            checkpoint_thread_id=str(summary.get("checkpointThreadId") or ""),
            replayed=replayed,
        )


class AutopilotPostRecoveryVerificationCoordinator:
    """Run and durably register the two post-action read-only specialists."""

    def __init__(
        self,
        *,
        specialist_coordinator: SpecialistAgentCoordinator,
        allowed_tools_by_role: Mapping[str, tuple[str, ...]],
        checkpointer: LangGraphDurableCheckpointerService,
        result_sink: Callable[[SpecialistTurnRequest, SpecialistTurnResult], Any],
    ) -> None:
        """Capture shared runtime dependencies and the durable Java fact sink.

        The sink is intentionally mandatory.  A verification response without
        durable facts would let Java acknowledge Kafka while the audit evidence
        was still only in Python memory.
        """

        if specialist_coordinator is None or checkpointer is None or result_sink is None:
            raise ValueError("Autopilot post-recovery verification dependencies are required")
        self._specialist_coordinator = specialist_coordinator
        self._allowed_tools_by_role = {
            role: tuple(allowed_tools_by_role.get(role) or ())
            for role in _REQUIRED_ROLES
        }
        self._checkpointer = checkpointer
        self._result_sink = result_sink

    def verify(
        self,
        request: AutopilotPostRecoveryVerificationRequest,
    ) -> AutopilotPostRecoveryVerificationResult:
        """Verify one receipt, persist two facts, and checkpoint the outcome.

        A completed checkpoint is returned directly.  An interrupted checkpoint
        reruns the same two turn IDs; the Java fact store then performs an
        identity-preserving upsert.  Every missing role, specialist failure,
        fact-sink failure or checkpoint failure raises a technical error so the
        Java Kafka listener remains unacknowledged and uses bounded retry/DLT.
        """

        if not isinstance(request, AutopilotPostRecoveryVerificationRequest):
            raise TypeError("request 必须是 AutopilotPostRecoveryVerificationRequest")
        thread_id = _verification_thread_id(request)
        binding = request.binding()
        latest = self._checkpointer.latest_for_thread(thread_id)
        if latest is not None:
            if dict(latest.state.get("requestBinding") or {}) != binding:
                raise AutopilotPostRecoveryVerificationError(
                    "AUTOPILOT_POST_RECOVERY_CHECKPOINT_SCOPE_CONFLICT"
                )
            terminal = latest.state.get("terminalResult")
            if (
                latest.node_name == "autopilot_post_recovery_verification_completed"
                and latest.status == LangGraphCheckpointStatus.COMPLETED
                and isinstance(terminal, Mapping)
            ):
                replay = AutopilotPostRecoveryVerificationResult.from_summary(
                    terminal,
                    replayed=True,
                )
                self._validate_result_binding(request, replay)
                return replay
        else:
            self._record_started_checkpoint(request, thread_id, binding)

        agent_request = self._agent_request(request)
        turn_runner = {
            "maxConcurrentAgentTurns": 2,
            "turnAttempts": tuple(
                {
                    "turnId": _verification_turn_id(request, role),
                    "agentRole": role,
                    "turnStatus": "READY_FOR_SPECIALIST_TURN",
                }
                for role in _REQUIRED_ROLES
            ),
        }
        execution_session = {
            "sessionId": request.root_session_id,
            "runId": request.root_run_id,
            "workItems": tuple(
                {
                    "agentRole": role,
                    "dependsOnRoles": ("MASTER_ORCHESTRATOR",),
                }
                for role in _REQUIRED_ROLES
            ),
        }
        batch = self._specialist_coordinator.run(
            request=agent_request,
            turn_runner=turn_runner,
            execution_session=execution_session,
            allowed_tools_by_role=self._allowed_tools_by_role,
            base_context={
                "taskId": request.task_id,
                "executionId": request.execution_id,
                "resourceReference": request.task_id,
                "resourceFingerprint": _resource_fingerprint(request),
                "postRecoveryVerification": True,
                "recoveryAction": request.recovery_action,
                "recoveryCaseId": request.case_id,
            },
            checkpoint_recorded=True,
            event_sink=None,
            result_sink=self._register_durable_fact,
        )
        results = tuple(batch.results or ())
        executed_roles = tuple(sorted(item.role.value for item in results))
        completed_roles = tuple(
            sorted(
                item.role.value
                for item in results
                if item.status == SpecialistTurnStatus.COMPLETED
            )
        )
        if (
            executed_roles != tuple(sorted(_REQUIRED_ROLES))
            or completed_roles != tuple(sorted(_REQUIRED_ROLES))
            or batch.status != "COMPLETED"
        ):
            raise AutopilotPostRecoveryVerificationError(
                "AUTOPILOT_POST_RECOVERY_SPECIALIST_VERIFICATION_INCOMPLETE"
            )

        result = AutopilotPostRecoveryVerificationResult(
            event_id=request.event_id,
            task_id=request.task_id,
            execution_id=request.execution_id,
            executed_roles=executed_roles,
            completed_roles=completed_roles,
            batch_status=batch.status,
            checkpoint_thread_id=thread_id,
        )
        self._record_completed_checkpoint(request, thread_id, binding, result)
        return result

    def _register_durable_fact(
        self,
        request: SpecialistTurnRequest,
        result: SpecialistTurnResult,
    ) -> Any:
        """Require the configured sink to acknowledge a durable registration.

        ``JavaSpecialistTurnFactClient`` may be configured fail-open for local
        development.  Autopilot cannot use that weaker behavior: unattended
        recovery must not commit Kafka unless each specialist fact is actually
        accepted.  Therefore a skipped or non-registered receipt becomes a
        retryable technical failure even when the shared client did not throw.
        """

        receipt = self._result_sink(request, result)
        registered = bool(
            receipt.get("registered")
            if isinstance(receipt, Mapping)
            else getattr(receipt, "registered", False)
        )
        duplicate = bool(
            receipt.get("duplicate")
            if isinstance(receipt, Mapping)
            else getattr(receipt, "duplicate", False)
        )
        if not registered and not duplicate:
            raise AutopilotPostRecoveryVerificationError(
                "AUTOPILOT_POST_RECOVERY_SPECIALIST_FACT_NOT_DURABLE"
            )
        return receipt

    def _agent_request(
        self,
        request: AutopilotPostRecoveryVerificationRequest,
    ) -> AgentRequest:
        """Build the minimal trusted request consumed by the coordinator."""

        return AgentRequest(
            tenant_id=request.tenant_id,
            project_id=request.project_id,
            actor_id=request.actor_id,
            objective="复核自动恢复后的同步任务预检查与运行状态。",
            request_id=f"autopilot-post-recovery:{request.event_id}",
            variables={
                "workspaceKey": request.workspace_key,
                "autopilotPostRecoveryVerification": True,
                "trustedControlPlane": {
                    "tenantId": request.tenant_id,
                    "applicationId": request.application_id,
                    "projectId": request.project_id,
                    "actorId": request.actor_id,
                    "delegationId": request.delegation_id,
                },
            },
        )

    def _record_started_checkpoint(
        self,
        request: AutopilotPostRecoveryVerificationRequest,
        thread_id: str,
        binding: Mapping[str, Any],
    ) -> None:
        """Persist admission before setting ``checkpoint_recorded=True``."""

        self._checkpointer.record_checkpoint(
            LangGraphDurableCheckpoint(
                checkpoint_id=_checkpoint_id(request, "started"),
                thread_id=thread_id,
                graph_name="autopilot-post-recovery-verification",
                graph_version="v1",
                node_name="autopilot_post_recovery_verification_started",
                status=LangGraphCheckpointStatus.RUNNING,
                state={"requestBinding": dict(binding)},
                next_nodes=("precheck_agent", "monitor_agent"),
                tenant_id=request.tenant_id,
                project_id=request.project_id,
                actor_id=request.actor_id,
                workspace_key=request.workspace_key,
                run_id=request.root_run_id,
                session_id=request.root_session_id,
                checkpoint_version=1,
                low_sensitive_summary=(
                    "Autopilot retry receipt admitted for read-only specialist verification."
                ),
            ),
            event_type="autopilot_post_recovery_verification_started",
        )

    def _record_completed_checkpoint(
        self,
        request: AutopilotPostRecoveryVerificationRequest,
        thread_id: str,
        binding: Mapping[str, Any],
        result: AutopilotPostRecoveryVerificationResult,
    ) -> None:
        """Persist the replayable low-sensitive terminal response."""

        self._checkpointer.record_checkpoint(
            LangGraphDurableCheckpoint(
                checkpoint_id=_checkpoint_id(request, "completed"),
                thread_id=thread_id,
                graph_name="autopilot-post-recovery-verification",
                graph_version="v1",
                node_name="autopilot_post_recovery_verification_completed",
                status=LangGraphCheckpointStatus.COMPLETED,
                state={
                    "requestBinding": dict(binding),
                    "terminalResult": result.to_summary(),
                },
                next_nodes=(),
                tenant_id=request.tenant_id,
                project_id=request.project_id,
                actor_id=request.actor_id,
                workspace_key=request.workspace_key,
                run_id=request.root_run_id,
                session_id=request.root_session_id,
                checkpoint_version=2,
                low_sensitive_summary=(
                    "PRECHECK_AGENT and MONITOR_AGENT facts were durably registered."
                ),
                created_at=datetime.now(timezone.utc),
                updated_at=datetime.now(timezone.utc),
            ),
            event_type="autopilot_post_recovery_verification_completed",
        )

    @staticmethod
    def _validate_result_binding(
        request: AutopilotPostRecoveryVerificationRequest,
        result: AutopilotPostRecoveryVerificationResult,
    ) -> None:
        """Reject a malformed terminal checkpoint before replaying it to Java."""

        if (
            result.event_id != request.event_id
            or result.task_id != request.task_id
            or result.execution_id != request.execution_id
            or result.executed_roles != tuple(sorted(_REQUIRED_ROLES))
            or result.completed_roles != tuple(sorted(_REQUIRED_ROLES))
            or result.batch_status != "COMPLETED"
        ):
            raise AutopilotPostRecoveryVerificationError(
                "AUTOPILOT_POST_RECOVERY_CHECKPOINT_BINDING_INVALID"
            )


def _resource_fingerprint(request: AutopilotPostRecoveryVerificationRequest) -> str:
    """Hash the receipt-bound resource without embedding user data."""

    material = f"{request.task_id}|{request.execution_id}"
    return "sha256:" + hashlib.sha256(material.encode("utf-8")).hexdigest()


def _bounded_reference(value: Any) -> str | None:
    """Accept ordinary enterprise identities while rejecting header controls.

    Actor and user identifiers may legitimately be emails, external subject
    names or other printable text rather than database numbers.  Restricting
    them to an identifier regex would reject valid tenants.  This helper keeps
    the actual trust boundary: non-empty, at most 160 characters, and no ASCII
    control characters that could corrupt logs or downstream HTTP headers.
    """

    text = str(value or "").strip()
    if not text or len(text) > 160:
        return None
    if any(ord(character) < 32 or ord(character) == 127 for character in text):
        return None
    return text


def _verification_thread_id(request: AutopilotPostRecoveryVerificationRequest) -> str:
    """Create one durable thread per event and real retry receipt."""

    material = f"{request.event_id}|{request.task_id}|{request.execution_id}"
    digest = hashlib.sha256(material.encode("utf-8")).hexdigest()[:24]
    return f"autopilot-post-recovery:{digest}"


def _verification_turn_id(
    request: AutopilotPostRecoveryVerificationRequest,
    role: str,
) -> str:
    """Create the stable Java fact identity for one specialist role."""

    material = (
        f"{request.event_id}|{request.task_id}|{request.execution_id}|{role}|"
        "autopilot-post-recovery-verification"
    )
    return "autopilot-verify-" + hashlib.sha256(material.encode("utf-8")).hexdigest()[:24]


def _checkpoint_id(
    request: AutopilotPostRecoveryVerificationRequest,
    phase: str,
) -> str:
    """Create a deterministic checkpoint ID so lost responses are upserts."""

    material = (
        f"{request.event_id}|{request.task_id}|{request.execution_id}|{phase}|"
        "autopilot-post-recovery-verification"
    )
    return "aprv-" + hashlib.sha256(material.encode("utf-8")).hexdigest()[:32]


__all__ = (
    "AUTOPILOT_POST_RECOVERY_VERIFICATION_SCHEMA_VERSION",
    "AutopilotPostRecoveryVerificationCoordinator",
    "AutopilotPostRecoveryVerificationError",
    "AutopilotPostRecoveryVerificationRequest",
    "AutopilotPostRecoveryVerificationResult",
)
