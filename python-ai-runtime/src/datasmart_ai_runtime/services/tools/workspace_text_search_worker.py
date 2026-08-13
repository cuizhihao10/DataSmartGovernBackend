"""Durable worker adapter for the model-visible workspace text search tool.

The search implementation is intentionally kept separate from HTTP routing.  The
service performs filesystem I/O, while this module translates a Java command
payload into a low-sensitive worker receipt.  Keeping those responsibilities
separate makes the security boundary easy to test and prevents query text or
file content from being accidentally copied into durable control-plane facts.
"""

from __future__ import annotations

import hashlib
from dataclasses import dataclass
from typing import Any

from datasmart_ai_runtime.services.tools.workspace_text_search_tool import (
    WorkspaceTextSearchMode,
    WorkspaceTextSearchRequest,
    WorkspaceTextSearchService,
    WorkspaceTextSearchSettings,
    WorkspaceTextSearchStatus,
)


WORKSPACE_TEXT_SEARCH_WORKER_SCHEMA_VERSION = "datasmart.python-ai-runtime.workspace-text-search-worker.v1"
WORKSPACE_TEXT_SEARCH_WORKER_PAYLOAD_POLICY = (
    "WORKSPACE_TEXT_SEARCH_SUMMARY_ONLY_NO_RAW_QUERY_FIELD_NO_ABSOLUTE_PATH_NO_FILE_BODY_NO_CREDENTIAL"
)
WORKSPACE_TEXT_SEARCH_TOOL_CODE = "workspace.text.search"


@dataclass(frozen=True)
class WorkspaceTextSearchWorkerRequest:
    """Controlled input for one search command.

    ``workspace_root`` is supplied by the trusted Java worker or deployment
    configuration.  It is never taken from a model tool call.  The model may
    provide only the short literal query and an optional relative scope.
    """

    command_id: str
    session_id: str
    run_id: str
    repository_root: str
    repository_reference: str
    query: str
    tenant_id: str = "*"
    application_id: str = "*"
    project_id: str = "*"
    actor_id: str = "anonymous"
    relative_path_prefix: str | None = None
    case_sensitive: bool = True
    search_mode: WorkspaceTextSearchMode | str = WorkspaceTextSearchMode.LITERAL
    max_results: int | None = None
    task_id: int | None = None
    task_run_id: int | None = None
    trace_id: str | None = None
    idempotency_key: str | None = None


class WorkspaceTextSearchCommandWorker:
    """Execute a bounded search and create a Java-consumable receipt.

    The worker is read-only.  It performs no shell execution, network search,
    database write, or model call.  Its only side effect is the optional HTTP
    callback performed by the route layer after a low-sensitive result has been
    built.  This is the same separation used by the RAG command worker.
    """

    def __init__(self, service: WorkspaceTextSearchService) -> None:
        """Store the already-configured search service used by this worker.

        Configuration is created once during application startup so every
        command shares the same allowlist and hard budgets.  A request cannot
        enlarge those limits because the search service clamps all model-facing
        values against its immutable settings.
        """

        self._service = service

    def run(self, request: WorkspaceTextSearchWorkerRequest) -> dict[str, Any]:
        """Run one controlled search and return only low-sensitive facts.

        The returned mapping contains counts, status codes, digests and bounded
        snippets.  It deliberately excludes the original query, absolute root,
        full file content and credential material.  The Java receipt service
        can therefore persist the result in the Agent timeline without turning
        the control plane into a document or secret store.
        """

        search_result = self._service.search(
            WorkspaceTextSearchRequest(
                session_id=request.session_id,
                run_id=request.run_id,
                operation_id=request.command_id,
                # 底层文件 guard 沿用历史 workspace 参数名；传入值实际是部署层只读 repository mount，
                # 与产品业务层级中的项目、应用无关。
                workspace_root=request.repository_root,
                workspace_reference=request.repository_reference,
                query=request.query,
                relative_path_prefix=request.relative_path_prefix,
                case_sensitive=request.case_sensitive,
                search_mode=request.search_mode,
                max_results=request.max_results,
            )
        )
        query_digest = search_result.query_digest
        outcome = self._outcome(search_result.status)
        java_payload = self._java_receipt_payload(request, search_result, outcome)
        return {
            "schemaVersion": WORKSPACE_TEXT_SEARCH_WORKER_SCHEMA_VERSION,
            "accepted": True,
            "toolCode": WORKSPACE_TEXT_SEARCH_TOOL_CODE,
            "workerResult": {
                "status": search_result.status.value,
                "processPerformed": search_result.process_performed,
                "queryDigest": query_digest,
                "pathScopeDigest": search_result.path_scope_digest,
                "filesConsidered": search_result.files_considered,
                "filesScanned": search_result.files_scanned,
                "scannedBytes": search_result.scanned_bytes,
                "matchCount": len(search_result.matches),
                "matches": tuple(match.to_summary() for match in search_result.matches),
                "skippedByType": search_result.skipped_by_type,
                "skippedOversized": search_result.skipped_oversized,
                "skippedBinary": search_result.skipped_binary,
                "skippedProtected": search_result.skipped_protected,
                "skippedSymlink": search_result.skipped_symlink,
                "truncated": search_result.truncated,
                "issueCodes": search_result.issue_codes,
                "evidenceCodes": search_result.evidence_codes,
                "recommendedActions": search_result.recommended_actions,
                "payloadPolicy": WORKSPACE_TEXT_SEARCH_WORKER_PAYLOAD_POLICY,
            },
            "receipt": {
                "outcome": outcome,
                "queryDigest": query_digest,
                "matchCount": len(search_result.matches),
                "payloadPolicy": WORKSPACE_TEXT_SEARCH_WORKER_PAYLOAD_POLICY,
            },
            "javaReceiptPayload": java_payload,
            "payloadPolicy": WORKSPACE_TEXT_SEARCH_WORKER_PAYLOAD_POLICY,
        }

    def _java_receipt_payload(
        self,
        request: WorkspaceTextSearchWorkerRequest,
        result: Any,
        outcome: str,
    ) -> dict[str, Any]:
        """Build the allow-listed fields accepted by Java receipt ingestion.

        Only integer resource identifiers, enum-like codes and hashes cross the
        service boundary.  The Java side performs a second validation, so this
        method is not treated as the sole authorization or data-loss barrier.
        """

        return {
            "commandId": request.command_id,
            "taskId": request.task_id,
            "taskRunId": request.task_run_id,
            "executorId": "python-text-search-worker",
            "tenantId": self._optional_int(request.tenant_id),
            "projectId": self._optional_int(request.project_id),
            "actorId": self._optional_int(request.actor_id),
            "taskStatus": "SUCCEEDED" if result.status in {
                WorkspaceTextSearchStatus.SUCCEEDED,
                WorkspaceTextSearchStatus.NO_MATCH,
            } else "FAILED",
            "outcome": outcome,
            "preCheckPassed": result.status is not WorkspaceTextSearchStatus.BLOCKED,
            "sideEffectStarted": False,
            "sideEffectExecuted": False,
            "workerLeaseRequired": False,
            "commandSafetyDecision": "ALLOW_READ_ONLY_TEXT_SEARCH",
            "commandSafetyPolicyVersion": "text-search-policy.v1",
            "commandSafetyIssueCodes": tuple(result.issue_codes),
            "normalizedTimeoutSeconds": 0,
            "normalizedOutputByteLimitBytes": result.scanned_bytes,
            "artifactAvailable": False,
            "errorCode": self._error_code(result.status),
            "auditId": f"text-search:sha256:{result.query_digest}",
            "toolCode": WORKSPACE_TEXT_SEARCH_TOOL_CODE,
            "targetService": "python-ai-runtime-text-search",
            "workerReceiptMode": "READ_ONLY_TEXT_SEARCH_SUMMARY",
            "message": self._message(result.status),
            "recommendedActions": self._receipt_actions(result.status),
            "idempotencyKey": request.idempotency_key or self._idempotency_key(request, result),
        }

    @staticmethod
    def _outcome(status: WorkspaceTextSearchStatus) -> str:
        """Translate the search state into a stable worker outcome code."""

        if status is WorkspaceTextSearchStatus.BLOCKED:
            return "WORKSPACE_TEXT_SEARCH_BLOCKED"
        if status is WorkspaceTextSearchStatus.FAILED:
            return "WORKSPACE_TEXT_SEARCH_FAILED"
        return "WORKSPACE_TEXT_SEARCH_COMPLETED"

    @staticmethod
    def _error_code(status: WorkspaceTextSearchStatus) -> str:
        """Return a low-cardinality error code without exposing I/O details."""

        return {
            WorkspaceTextSearchStatus.SUCCEEDED: "AGENT_WORKSPACE_TEXT_SEARCH_COMPLETED",
            WorkspaceTextSearchStatus.NO_MATCH: "AGENT_WORKSPACE_TEXT_SEARCH_NO_MATCH",
            WorkspaceTextSearchStatus.BLOCKED: "AGENT_WORKSPACE_TEXT_SEARCH_BLOCKED",
            WorkspaceTextSearchStatus.FAILED: "AGENT_WORKSPACE_TEXT_SEARCH_FAILED",
        }[status]

    @staticmethod
    def _message(status: WorkspaceTextSearchStatus) -> str:
        """Return a safe operator message containing no query or path."""

        return {
            WorkspaceTextSearchStatus.SUCCEEDED: "Read-only text search completed.",
            WorkspaceTextSearchStatus.NO_MATCH: "Read-only text search completed with no match.",
            WorkspaceTextSearchStatus.BLOCKED: "Read-only text search was blocked by a policy boundary.",
            WorkspaceTextSearchStatus.FAILED: "Read-only text search failed before a usable result was produced.",
        }[status]

    @staticmethod
    def _receipt_actions(status: WorkspaceTextSearchStatus) -> tuple[str, ...]:
        """Return Java-safe actions without query, path or protected marker text."""

        if status is WorkspaceTextSearchStatus.BLOCKED:
            return ("Review the approved root and relative-scope policy before retrying.",)
        if status is WorkspaceTextSearchStatus.FAILED:
            return ("Retry after the read-only worker is healthy.",)
        return ("Use a matched relative reference only when more context is required.",)

    @staticmethod
    def _optional_int(value: object) -> int | None:
        """Convert a trusted numeric scope identifier without coercing labels to zero."""

        text = str(value or "").strip()
        if not text or not text.isdigit():
            return None
        return int(text)

    @staticmethod
    def _idempotency_key(request: WorkspaceTextSearchWorkerRequest, result: Any) -> str:
        """Create a stable receipt key from command identity and query digest."""

        return f"text-search:{request.run_id}:{request.command_id}:{result.query_digest}"


def workspace_text_search_settings_from_env(getenv: Any) -> WorkspaceTextSearchSettings:
    """Load bounded search settings from deployment environment variables.

    An empty allowlist disables real filesystem access even when the feature flag
    is accidentally enabled.  This fail-closed default prevents a container
    startup typo from exposing its entire host filesystem to an Agent.
    """

    def truthy(name: str) -> bool:
        return str(getenv(name, "") or "").strip().lower() in {"1", "true", "yes", "on"}

    def integer(name: str, default: int) -> int:
        try:
            return max(1, int(getenv(name, default)))
        except (TypeError, ValueError):
            return default

    raw_allowlist = getenv("DATASMART_AGENT_REPOSITORY_ROOT_ALLOWLIST", "") or getenv(
        "DATASMART_AGENT_WORKSPACE_ROOT_ALLOWLIST", ""
    )
    allowlist = tuple(
        item.strip()
        for item in str(raw_allowlist or "").replace(";", ",").split(",")
        if item.strip()
    )
    return WorkspaceTextSearchSettings(
        enabled=truthy("DATASMART_AGENT_WORKSPACE_TEXT_SEARCH_ENABLED") and bool(allowlist),
        workspace_root_allowlist=allowlist,
        max_query_chars=integer("DATASMART_AGENT_WORKSPACE_TEXT_SEARCH_MAX_QUERY_CHARS", 256),
        max_files=integer("DATASMART_AGENT_WORKSPACE_TEXT_SEARCH_MAX_FILES", 200),
        max_file_bytes=integer("DATASMART_AGENT_WORKSPACE_TEXT_SEARCH_MAX_FILE_BYTES", 256 * 1024),
        max_total_scan_bytes=integer("DATASMART_AGENT_WORKSPACE_TEXT_SEARCH_MAX_SCAN_BYTES", 2 * 1024 * 1024),
        max_matches=integer("DATASMART_AGENT_WORKSPACE_TEXT_SEARCH_MAX_MATCHES", 40),
        max_line_chars=integer("DATASMART_AGENT_WORKSPACE_TEXT_SEARCH_MAX_LINE_CHARS", 800),
        max_snippet_chars=integer("DATASMART_AGENT_WORKSPACE_TEXT_SEARCH_MAX_SNIPPET_CHARS", 240),
    )


__all__ = [
    "WORKSPACE_TEXT_SEARCH_TOOL_CODE",
    "WORKSPACE_TEXT_SEARCH_WORKER_PAYLOAD_POLICY",
    "WORKSPACE_TEXT_SEARCH_WORKER_SCHEMA_VERSION",
    "WorkspaceTextSearchCommandWorker",
    "WorkspaceTextSearchWorkerRequest",
    "workspace_text_search_settings_from_env",
]
