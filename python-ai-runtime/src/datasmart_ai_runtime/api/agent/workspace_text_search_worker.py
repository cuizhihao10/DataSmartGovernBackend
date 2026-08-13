"""Internal HTTP route for the governed workspace text-search worker."""

from collections.abc import Mapping
import hmac
from typing import Any

from datasmart_ai_runtime.services.tools.workspace_text_search_worker import (
    WORKSPACE_TEXT_SEARCH_TOOL_CODE,
    WORKSPACE_TEXT_SEARCH_WORKER_SCHEMA_VERSION,
    WorkspaceTextSearchCommandWorker,
    WorkspaceTextSearchWorkerRequest,
)


WORKSPACE_TEXT_SEARCH_WORKER_API_SCHEMA_VERSION = "datasmart.workspace-text-search-worker-api.v1"


def register_workspace_text_search_worker_routes(
    app: Any,
    *,
    request_type: Any,
    worker: WorkspaceTextSearchCommandWorker,
    service_account_token: str | None,
    error_factory: Any,
) -> None:
    """Register the internal command-worker endpoints.

    Java calls the ``/internal`` route from the service network.  The
    ``/api/internal`` alias is kept for a gateway deployment that rewrites an
    internal prefix.  Neither route is a public product search endpoint; the
    deployment must protect them with service authentication and network
    isolation.  ``service_account_token`` is deliberately required at
    registration time: an empty deployment setting is rejected by the route
    rather than silently turning a filesystem-search capability into a public
    endpoint.
    """

    @app.post("/internal/agent/workspace-text/command-worker/run")
    @app.post("/api/internal/agent/workspace-text/command-worker/run")
    def run(
        payload: dict[str, Any],
        http_request: request_type,
    ) -> dict[str, Any]:
        """Authenticate a Java worker call before parsing or touching the filesystem.

        The check is intentionally the first operation.  That ordering prevents an unauthenticated caller from
        learning request-validation behavior, allowed-root state, or search-result metadata.  The credential stays in
        HTTP headers only; this route neither places it in the typed request nor includes it in a response or error.
        """

        _verify_bearer_service_account(
            http_request.headers.get("Authorization"),
            service_account_token,
            error_factory=error_factory,
        )

        # Only an authenticated request reaches this parser.  It then preserves the existing second boundary: trusted
        # Java control facts supply identities and the real root, while model arguments only supply bounded search
        # parameters such as the literal query and relative prefix.
        result = worker.run(workspace_text_search_worker_request_from_payload(payload))
        return {
            **result,
            "schemaVersion": WORKSPACE_TEXT_SEARCH_WORKER_API_SCHEMA_VERSION,
            "accepted": True,
            "toolCode": WORKSPACE_TEXT_SEARCH_TOOL_CODE,
            "workerRunnerSchemaVersion": WORKSPACE_TEXT_SEARCH_WORKER_SCHEMA_VERSION,
        }


def _verify_bearer_service_account(
    authorization: str | None,
    configured_token: str | None,
    *,
    error_factory: Any,
) -> None:
    """Fail closed unless ``Authorization: Bearer <configured service token>`` is valid.

    This worker intentionally does not accept the legacy internal-token header or a raw token.  Having one
    credential transport makes the Java-to-Python contract explicit and prevents a proxy/header rewrite from
    accidentally creating an alternate authentication path.  Both absent configuration and invalid caller input use
    the same generic 401 response so callers cannot infer whether a deployment secret exists.  ``compare_digest``
    avoids an ordinary early-exit string comparison for the secret itself.
    """

    expected = str(configured_token or "").strip()
    actual = _bearer_token(authorization)
    # Compare encoded bytes rather than Unicode strings.  ``compare_digest`` rejects non-ASCII strings on some Python
    # runtimes; an arbitrary malformed HTTP header must still receive the same 401 instead of causing a 500.
    is_valid = bool(expected and actual) and hmac.compare_digest(
        actual.encode("utf-8"),
        expected.encode("utf-8"),
    )
    if not is_valid:
        raise error_factory(
            401,
            {
                "code": "WORKSPACE_TEXT_SEARCH_WORKER_UNAUTHORIZED",
                "message": "Workspace text search worker internal request is unauthorized.",
            },
        )


def _bearer_token(authorization: str | None) -> str | None:
    """Extract exactly one Bearer credential without preserving malformed header text.

    HTTP authentication schemes are case-insensitive, so ``bearer`` and ``Bearer`` are equivalent.  Requiring two
    whitespace-separated fields rejects raw tokens, empty credentials, and values with additional segments before a
    secret comparison is attempted.
    """

    parts = str(authorization or "").strip().split()
    if len(parts) != 2 or parts[0].lower() != "bearer":
        return None
    return parts[1]


def workspace_text_search_worker_request_from_payload(
    payload: Mapping[str, Any],
) -> WorkspaceTextSearchWorkerRequest:
    """Convert Java control facts and model arguments into a typed request.

    Root, session and run identity come from the trusted control-plane section;
    the query and relative scope come from the model's selected tool call.  The
    filesystem service performs the final root/path validation before reading.
    """

    arguments = _mapping(_first(payload, "arguments", "toolArguments"), "arguments")
    control_facts = _mapping(_first(payload, "controlFacts", "control_facts"), "controlFacts")

    def trusted(*keys: str, default: Any = None) -> Any:
        """只从 Java 重建的 controlFacts 读取身份和真实仓库边界。

        模型参数即使伪造 repositoryRoot/workspaceRoot，也不能覆盖服务端值。这里不再从顶层 payload 或
        arguments 回退，是为了让内部 HTTP 路由同样具备 fail-closed 边界，而不是只依赖 Java 调用方自律。
        """

        for key in keys:
            if key in control_facts:
                return control_facts[key]
        return default

    def model_argument(*keys: str, default: Any = None) -> Any:
        """只读取模型可选择的短生命周期检索参数，并保留显式 ``False`` 与零。"""

        for key in keys:
            if key in arguments:
                return arguments[key]
        return default

    return WorkspaceTextSearchWorkerRequest(
        command_id=_required(trusted("commandId", "command_id"), "commandId"),
        session_id=_required(trusted("sessionId", "session_id"), "sessionId"),
        run_id=_required(trusted("runId", "run_id"), "runId"),
        repository_root=_required(
            trusted("repositoryRoot", "repository_root", "workspaceRoot", "workspace_root"),
            "repositoryRoot",
        ),
        repository_reference=_required(
            trusted(
                "repositoryReference",
                "repository_reference",
                "workspaceReference",
                "workspace_reference",
            ),
            "repositoryReference",
        ),
        query=_required(model_argument("query"), "arguments.query"),
        tenant_id=str(trusted("tenantId", "tenant_id", default="*") or "*"),
        application_id=str(trusted("applicationId", "application_id", default="*") or "*"),
        project_id=str(trusted("projectId", "project_id", default="*") or "*"),
        actor_id=str(trusted("actorId", "actor_id", default="anonymous") or "anonymous"),
        relative_path_prefix=_optional(model_argument("relativePathPrefix", "relative_path_prefix")),
        case_sensitive=_bool(model_argument("caseSensitive", "case_sensitive"), True),
        search_mode=_optional(model_argument("searchMode", "search_mode")) or "LITERAL",
        max_results=_optional_int(model_argument("maxResults", "max_results")),
        task_id=_optional_int(trusted("taskId", "task_id")),
        task_run_id=_optional_int(trusted("taskRunId", "task_run_id")),
        trace_id=_optional(trusted("traceId", "trace_id")),
        idempotency_key=_optional(trusted("idempotencyKey", "idempotency_key")),
    )


def _first(mapping: Mapping[str, Any], *keys: str) -> Any:
    """Return the first present payload field without merging untrusted objects."""

    for key in keys:
        if key in mapping:
            return mapping[key]
    return None


def _mapping(value: Any, field_name: str) -> dict[str, Any]:
    """Require a JSON object for nested control facts and tool arguments."""

    if value is None:
        return {}
    if not isinstance(value, Mapping):
        raise ValueError(f"{field_name} must be a JSON object")
    return dict(value)


def _required(value: Any, field_name: str) -> str:
    """Require a non-empty identity or query field."""

    text = _optional(value)
    if not text:
        raise ValueError(f"{field_name} is required")
    return text


def _optional(value: Any) -> str | None:
    """Normalize an optional scalar without preserving surrounding whitespace."""

    if value is None:
        return None
    text = str(value).strip()
    return text or None


def _optional_int(value: Any) -> int | None:
    """Parse an optional integer while leaving invalid values for safe omission."""

    text = _optional(value)
    if text is None:
        return None
    try:
        return int(text)
    except ValueError:
        return None


def _bool(value: Any, default: bool) -> bool:
    """Accept common JSON and Java boolean representations."""

    if value is None:
        return default
    if isinstance(value, bool):
        return value
    return str(value).strip().lower() in {"1", "true", "yes", "y", "on"}


__all__ = [
    "WORKSPACE_TEXT_SEARCH_WORKER_API_SCHEMA_VERSION",
    "register_workspace_text_search_worker_routes",
    "workspace_text_search_worker_request_from_payload",
]
