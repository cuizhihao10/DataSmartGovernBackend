"""Java agent-runtime 调用的 Autopilot Recovery 内部 HTTP 路由。"""

import hmac
from typing import Any

from datasmart_ai_runtime.services.agent_execution.autopilot_recovery import (
    AutopilotRecoveryCoordinator,
    AutopilotRecoveryDurableFactError,
    AutopilotRecoveryRequest,
)
from datasmart_ai_runtime.services.agent_execution.autopilot_post_recovery_verification import (
    AutopilotPostRecoveryVerificationCoordinator,
    AutopilotPostRecoveryVerificationError,
    AutopilotPostRecoveryVerificationRequest,
)


_INTERNAL_TOKEN_HEADER = "X-DataSmart-Internal-Service-Token"


def register_autopilot_recovery_routes(
    app: Any,
    *,
    request_type: Any,
    coordinator: AutopilotRecoveryCoordinator,
    post_recovery_verification_coordinator: (
        AutopilotPostRecoveryVerificationCoordinator | None
    ) = None,
    service_account_token: str | None,
    error_factory: Any,
) -> None:
    """注册仅供 Java 控制面使用的自动恢复规划入口。

    浏览器不能直接调用该路由，因为 payload 中的 session、run、授权和循环事实必须由 Java 从
    PostgreSQL/Kafka 可信边界重建。路由只负责认证、领域 DTO 转换和错误码收敛，不执行 data-sync
    写操作，也不把内部异常、模型响应或检索正文返回给调用方。

    ``request_type`` 是应用装配层传入的 FastAPI ``Request`` 类型。这个模块故意不启用延迟注解：路由函数
    定义在注册函数的闭包中，FastAPI 必须在注册当下看到真实类型才能注入请求对象；若把
    ``http_request: request_type`` 保存成字符串，框架会把它误判为名为 ``http_request`` 的普通 query
    参数，Java 消费 Kafka 事件时便会持续收到 422 并在有限重试后进入 DLT。
    """

    @app.post("/internal/agent/autopilot/recovery/plan")
    @app.post("/api/internal/agent/autopilot/recovery/plan")
    def plan_autopilot_recovery(
        payload: dict[str, Any],
        http_request: request_type,
    ) -> dict[str, Any]:
        """验证服务身份并执行一次有界 Recovery/RAG 决策。"""

        _verify_service_account(
            http_request.headers.get(_INTERNAL_TOKEN_HEADER),
            http_request.headers.get("Authorization"),
            service_account_token,
            error_factory=error_factory,
        )
        try:
            request = AutopilotRecoveryRequest.from_payload(payload)
            return coordinator.plan(request).to_summary()
        except (TypeError, ValueError) as exc:
            raise error_factory(
                400,
                {
                    "code": "AUTOPILOT_RECOVERY_REQUEST_INVALID",
                    "message": "Autopilot recovery 请求缺少可信范围、循环预算或指纹事实。",
                    "reason": str(exc)[:240],
                },
            ) from exc
        except LookupError as exc:
            raise error_factory(
                503,
                {
                    "code": "AUTOPILOT_RECOVERY_SPECIALIST_UNAVAILABLE",
                    "message": "当前 Python Runtime 没有装配 RECOVERY_AGENT。",
                    "reason": str(exc)[:160],
                },
            ) from exc
        except AutopilotRecoveryDurableFactError as exc:
            # A missing or rejected specialist fact is retryable infrastructure failure, not a business
            # ``ATTENTION_REQUIRED`` result.  Returning 503 keeps the Kafka record unacknowledged so the existing
            # bounded retry/DLT policy can restore the audit boundary after the Java control plane recovers.
            raise error_factory(
                503,
                {
                    "code": "AUTOPILOT_RECOVERY_DURABLE_FACT_UNAVAILABLE",
                    "message": "Autopilot recovery Specialist 事实尚未持久化，系统将执行有界重试。",
                    "reason": exc.code,
                },
            ) from exc

    @app.post("/internal/agent/autopilot/recovery/post-action-verification")
    @app.post("/api/internal/agent/autopilot/recovery/post-action-verification")
    def verify_autopilot_recovery_post_action(
        payload: dict[str, Any],
        http_request: request_type,
    ) -> dict[str, Any]:
        """Run durable PRECHECK/MONITOR verification after a real retry receipt.

        Java invokes this endpoint only after data-sync has accepted the
        receipt-bound quarantine/retry action.  The route authenticates the
        service before parsing the body and delegates every role, tool,
        checkpoint and fact-persistence decision to the execution coordinator.
        A missing coordinator or technical verification failure returns 503;
        Java therefore leaves the Kafka record unacknowledged for bounded retry.
        """

        _verify_service_account(
            http_request.headers.get(_INTERNAL_TOKEN_HEADER),
            http_request.headers.get("Authorization"),
            service_account_token,
            error_factory=error_factory,
        )
        if post_recovery_verification_coordinator is None:
            raise error_factory(
                503,
                {
                    "code": "AUTOPILOT_POST_RECOVERY_VERIFICATION_UNAVAILABLE",
                    "message": "当前 Python Runtime 未装配恢复后 Specialist 复核。",
                },
            )
        try:
            request = AutopilotPostRecoveryVerificationRequest.from_payload(payload)
            return post_recovery_verification_coordinator.verify(request).to_summary()
        except (TypeError, ValueError) as exc:
            raise error_factory(
                400,
                {
                    "code": "AUTOPILOT_POST_RECOVERY_VERIFICATION_REQUEST_INVALID",
                    "message": "恢复后复核请求缺少可信范围或真实 retry receipt。",
                    "reason": str(exc)[:240],
                },
            ) from exc
        except AutopilotPostRecoveryVerificationError as exc:
            raise error_factory(
                503,
                {
                    "code": "AUTOPILOT_POST_RECOVERY_VERIFICATION_FAILED",
                    "message": "恢复后 Specialist 复核尚未形成完整持久事实。",
                    "reason": str(exc)[:160],
                },
            ) from exc
        except RuntimeError as exc:
            # The fact client and specialist HTTP adapters intentionally raise
            # low-sensitive runtime codes.  Never expose response bodies,
            # credentials or stack traces through this internal contract.
            raise error_factory(
                503,
                {
                    "code": "AUTOPILOT_POST_RECOVERY_DEPENDENCY_FAILED",
                    "message": "恢复后复核依赖暂不可用，请由 Kafka 有界重试。",
                    "reason": type(exc).__name__,
                },
            ) from exc


def _verify_service_account(
    internal_token: str | None,
    authorization: str | None,
    configured_token: str | None,
    *,
    error_factory: Any,
) -> None:
    """Fail closed while authenticating the Java-to-Python recovery boundary.

    ``configured_token`` is the deployment secret and therefore the trust
    anchor for both recovery endpoints.  An absent or whitespace-only secret
    is a configuration failure, not a reason to disable authentication: the
    route must return the same generic ``401`` as an invalid caller.  Keeping
    those cases indistinguishable avoids revealing whether a deployment has a
    credential configured.

    The caller may use the established internal header or an
    ``Authorization`` value containing ``Bearer <token>``.  The token is
    consumed only for this check.  Once a non-empty deployment secret exists,
    both values are normalized to UTF-8 bytes before ``hmac.compare_digest``
    performs the secret comparison.  Byte comparison keeps the operation on a
    single compatible type even for malformed or non-ASCII header input, so a
    bad credential produces a controlled ``401`` rather than an internal
    error.  The exception detail is deliberately fixed and never includes
    either the configured secret or the supplied credential.
    """

    expected = str(configured_token or "").strip()
    actual = str(internal_token or "").strip()
    if not actual:
        actual = str(authorization or "").strip()
        if actual.lower().startswith("bearer "):
            actual = actual[7:].strip()

    is_valid = bool(expected and actual) and hmac.compare_digest(
        actual.encode("utf-8"),
        expected.encode("utf-8"),
    )
    if not is_valid:
        raise error_factory(
            401,
            {
                "code": "AUTOPILOT_RECOVERY_UNAUTHORIZED",
                "message": "Autopilot recovery 内部调用未通过服务账号认证。",
            },
        )


__all__ = ["register_autopilot_recovery_routes"]
