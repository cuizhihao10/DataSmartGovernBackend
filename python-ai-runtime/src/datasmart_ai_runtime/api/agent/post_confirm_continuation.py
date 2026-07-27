"""Internal HTTP route for resuming an Agent after Java tool confirmation."""

import hmac
from typing import Any

from datasmart_ai_runtime.services.agent_execution.post_confirm_continuation import (
    AgentPostConfirmContinuationCoordinator,
)


def register_post_confirm_continuation_routes(
    app: Any,
    *,
    request_type: Any,
    coordinator: AgentPostConfirmContinuationCoordinator | None,
    service_account_token: str | None,
    error_factory: Any,
) -> None:
    """Register the Java-to-Python continuation endpoint.

    This path is intentionally separate from the browser planning API.  It accepts
    structured Java execution facts rather than user-supplied claims and requires
    a service-account bearer token whenever one is configured.
    """

    @app.post("/internal/agent/continuations/post-confirm")
    @app.post("/api/internal/agent/continuations/post-confirm")
    def continue_after_confirmed_tools(
        payload: dict[str, Any],
        http_request: request_type,
    ) -> dict[str, Any]:
        if coordinator is None:
            raise error_factory(
                503,
                {
                    "code": "POST_CONFIRM_CONTINUATION_DISABLED",
                    "message": "Agent 确认后续跑能力未启用，请检查模型二轮与 durable loop 配置。",
                },
            )
        _verify_service_account(
            http_request.headers.get("Authorization"),
            service_account_token,
            error_factory=error_factory,
        )
        try:
            return coordinator.continue_after_confirmed_tools(payload).to_summary()
        except ValueError as exc:
            raise error_factory(
                400,
                {
                    "code": "POST_CONFIRM_CONTINUATION_REQUEST_INVALID",
                    "message": "确认后续跑请求缺少完整的 Java 工具结果或会话边界。",
                    "reason": str(exc)[:200],
                },
            ) from exc


def _verify_service_account(
    authorization: str | None,
    configured_token: str | None,
    *,
    error_factory: Any,
) -> None:
    expected = str(configured_token or "").strip()
    if not expected:
        # Local source-level tests may intentionally omit the token.  Compose and
        # production configurations set it explicitly; this avoids embedding a
        # credential in Python source while preserving testability.
        return
    actual = str(authorization or "").strip()
    if actual.lower().startswith("bearer "):
        actual = actual[7:].strip()
    if not actual or not hmac.compare_digest(actual, expected):
        raise error_factory(
            401,
            {
                "code": "POST_CONFIRM_CONTINUATION_UNAUTHORIZED",
                "message": "Agent 确认后续跑内部调用未通过服务账号认证。",
            },
        )


__all__ = ["register_post_confirm_continuation_routes"]
