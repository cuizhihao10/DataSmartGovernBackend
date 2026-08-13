"""Autopilot Recovery 内部 HTTP 路由合同测试。"""

from datetime import datetime, timedelta, timezone
from typing import Any

import pytest

from datasmart_ai_runtime.api.agent.autopilot_recovery import (
    register_autopilot_recovery_routes,
)
from datasmart_ai_runtime.services.agent_execution.autopilot_recovery import (
    AutopilotRecoveryDurableFactError,
)


class _FixedRecoveryResult:
    """返回 Java 能消费的最小低敏规划摘要。"""

    def to_summary(self) -> dict[str, Any]:
        """生成固定候选，避免 API 合同测试调用真实模型、RAG 或持久化。"""

        return {
            "schemaVersion": "datasmart.autopilot.recovery-candidate.v1",
            "eventId": "autopilot-trigger:api-contract",
            "status": "ATTENTION_REQUIRED",
            "reasonCode": "API_CONTRACT_PROVED",
        }


class _RecordingCoordinator:
    """记录路由转换后的领域请求，证明 JSON body 没有被误当成查询参数。"""

    def __init__(self) -> None:
        self.requests: list[Any] = []

    def plan(self, request: Any) -> _FixedRecoveryResult:
        """保存一次调用并返回固定结果；本方法没有任何恢复副作用。"""

        self.requests.append(request)
        return _FixedRecoveryResult()


class _UnavailableDurableFactCoordinator:
    """Model a Recovery planner whose Java durable-fact dependency is temporarily unavailable."""

    def plan(self, request: Any) -> _FixedRecoveryResult:
        """Raise the same low-sensitive technical error used by the real coordinator."""

        raise AutopilotRecoveryDurableFactError()


class _FixedPostRecoveryVerificationResult:
    """Return the exact low-sensitive contract Java requires before Kafka ACK."""

    def to_summary(self) -> dict[str, Any]:
        """Expose two completed roles without any tool output or model text."""

        return {
            "schemaVersion": "datasmart.autopilot.post-recovery-verification.v1",
            "status": "VERIFIED",
            "eventId": "autopilot-trigger:api-contract",
            "taskId": "93",
            "executionId": "2452",
            "executedRoles": ("MONITOR_AGENT", "PRECHECK_AGENT"),
            "completedRoles": ("MONITOR_AGENT", "PRECHECK_AGENT"),
            "batchStatus": "COMPLETED",
            "checkpointThreadId": "autopilot-post-recovery:api-contract",
            "replayed": False,
            "payloadPolicy": "LOW_SENSITIVE_AUTOPILOT_POST_RECOVERY_VERIFICATION_ONLY",
        }


class _RecordingPostRecoveryCoordinator:
    """Record the parsed receipt-bound request passed by the internal route."""

    def __init__(self) -> None:
        """Create an empty request recorder for one focused API test."""

        self.requests: list[Any] = []

    def verify(self, request: Any) -> _FixedPostRecoveryVerificationResult:
        """Record one request and return the fixed durable-fact acknowledgement."""

        self.requests.append(request)
        return _FixedPostRecoveryVerificationResult()


def _valid_payload() -> dict[str, Any]:
    """构造满足 Python 第二道边界的可信 Java 恢复请求。"""

    return {
        "eventId": "autopilot-trigger:api-contract",
        "rootSessionId": "session-api-contract",
        "rootRunId": "run-api-contract",
        "tenantId": "10",
        "applicationId": "10010",
        "projectId": "101",
        "userId": "1001",
        "actorId": "1001",
        "agentId": "main-agent",
        "delegationId": "delegation-api-contract",
        "workspaceKey": "tenant:10:project:101",
        "syncTaskId": "93",
        "rootExecutionId": "2452",
        "currentExecutionId": "2452",
        "cycle": 1,
        "maxRecoveryCycles": 3,
        "deadlineAt": (datetime.now(timezone.utc) + timedelta(hours=1)).isoformat(),
        "errorFingerprint": "a" * 64,
        "repeatedErrorCount": 0,
        "issueCodes": ["OBJECT_TRANSFER_FAILED"],
        "triggeredAt": datetime.now(timezone.utc).isoformat(),
    }


def _valid_post_recovery_payload() -> dict[str, Any]:
    """Extend the trusted trigger projection with a real data-sync retry receipt."""

    payload = _valid_payload()
    payload.update(
        {
            "taskId": "93",
            "executionId": "2452",
            "caseId": "81",
            "recoveryAction": "RETRY_EXECUTION",
        }
    )
    return payload


def test_internal_recovery_route_injects_request_without_query_parameter() -> None:
    """内部调用只需要 token 与 JSON body，不能额外要求名为 http_request 的 query 参数。

    FastAPI 依赖函数签名中的真实 ``Request`` 类型来注入请求对象。若路由模块把局部类型别名保留成
    延迟字符串注解，FastAPI 会把 ``http_request`` 误判为普通 query 字段，Java 的每次 Kafka 消费都会
    收到 422 并最终进入 DLT。本测试从 HTTP 边界复现该条件，确保服务令牌可从 Header 正常读取。
    """

    fastapi = pytest.importorskip("fastapi")
    testclient = pytest.importorskip("fastapi.testclient")
    coordinator = _RecordingCoordinator()
    app = fastapi.FastAPI()
    register_autopilot_recovery_routes(
        app,
        request_type=fastapi.Request,
        coordinator=coordinator,  # type: ignore[arg-type]
        post_recovery_verification_coordinator=None,
        service_account_token="service-token",
        error_factory=lambda status_code, detail: fastapi.HTTPException(
            status_code=status_code,
            detail=detail,
        ),
    )

    response = testclient.TestClient(app).post(
        "/internal/agent/autopilot/recovery/plan",
        headers={"X-DataSmart-Internal-Service-Token": "service-token"},
        json=_valid_payload(),
    )

    assert response.status_code == 200
    assert response.json()["reasonCode"] == "API_CONTRACT_PROVED"
    assert len(coordinator.requests) == 1
    assert coordinator.requests[0].sync_task_id == "93"


def test_internal_recovery_route_rejects_request_when_service_token_is_unconfigured() -> None:
    """Keep the recovery endpoint closed when deployment authentication is absent.

    The internal-header value belongs to the caller, so it must never become an
    implicit replacement for a missing deployment secret.  This HTTP-level
    regression deliberately supplies a distinctive token while the route has
    no configured token.  It proves three boundary properties together:
    unauthenticated traffic is rejected before payload parsing, the coordinator
    receives no recovery work, and the generic error body does not reflect a
    credential back to the caller.
    """

    fastapi = pytest.importorskip("fastapi")
    testclient = pytest.importorskip("fastapi.testclient")
    coordinator = _RecordingCoordinator()
    app = fastapi.FastAPI()
    register_autopilot_recovery_routes(
        app,
        request_type=fastapi.Request,
        coordinator=coordinator,  # type: ignore[arg-type]
        post_recovery_verification_coordinator=None,
        service_account_token=None,
        error_factory=lambda status_code, detail: fastapi.HTTPException(
            status_code=status_code,
            detail=detail,
        ),
    )

    supplied_token = "caller-token-must-not-appear-in-response"
    response = testclient.TestClient(app).post(
        "/internal/agent/autopilot/recovery/plan",
        headers={"X-DataSmart-Internal-Service-Token": supplied_token},
        json=_valid_payload(),
    )

    assert response.status_code == 401
    assert response.json() == {
        "detail": {
            "code": "AUTOPILOT_RECOVERY_UNAUTHORIZED",
            "message": "Autopilot recovery 内部调用未通过服务账号认证。",
        },
    }
    assert supplied_token not in response.text
    assert coordinator.requests == []


def test_internal_recovery_route_returns_503_when_durable_fact_is_unavailable() -> None:
    """Keep Kafka unacknowledged when Recovery cannot prove Specialist facts were persisted.

    A model response is not enough for unattended recovery.  This HTTP regression fixes the cross-process contract:
    the Python route exposes only a stable 503 code, so the Java consumer can use its existing bounded retry/DLT
    policy instead of committing an unauditable autonomous decision.
    """

    fastapi = pytest.importorskip("fastapi")
    testclient = pytest.importorskip("fastapi.testclient")
    app = fastapi.FastAPI()
    register_autopilot_recovery_routes(
        app,
        request_type=fastapi.Request,
        coordinator=_UnavailableDurableFactCoordinator(),  # type: ignore[arg-type]
        post_recovery_verification_coordinator=None,
        service_account_token="service-token",
        error_factory=lambda status_code, detail: fastapi.HTTPException(
            status_code=status_code,
            detail=detail,
        ),
    )

    response = testclient.TestClient(app).post(
        "/internal/agent/autopilot/recovery/plan",
        headers={"X-DataSmart-Internal-Service-Token": "service-token"},
        json=_valid_payload(),
    )

    assert response.status_code == 503
    assert response.json()["detail"]["code"] == "AUTOPILOT_RECOVERY_DURABLE_FACT_UNAVAILABLE"
    assert response.json()["detail"]["reason"] == "AUTOPILOT_RECOVERY_SPECIALIST_FACT_NOT_DURABLE"


def test_internal_post_recovery_route_accepts_real_receipt_contract() -> None:
    """The fixed endpoint must parse task/execution scope and invoke verification once."""

    fastapi = pytest.importorskip("fastapi")
    testclient = pytest.importorskip("fastapi.testclient")
    planning = _RecordingCoordinator()
    verification = _RecordingPostRecoveryCoordinator()
    app = fastapi.FastAPI()
    register_autopilot_recovery_routes(
        app,
        request_type=fastapi.Request,
        coordinator=planning,  # type: ignore[arg-type]
        post_recovery_verification_coordinator=verification,  # type: ignore[arg-type]
        service_account_token="service-token",
        error_factory=lambda status_code, detail: fastapi.HTTPException(
            status_code=status_code,
            detail=detail,
        ),
    )

    response = testclient.TestClient(app).post(
        "/internal/agent/autopilot/recovery/post-action-verification",
        headers={"X-DataSmart-Internal-Service-Token": "service-token"},
        json=_valid_post_recovery_payload(),
    )

    assert response.status_code == 200
    assert response.json()["completedRoles"] == ["MONITOR_AGENT", "PRECHECK_AGENT"]
    assert len(verification.requests) == 1
    assert verification.requests[0].task_id == "93"
    assert verification.requests[0].execution_id == "2452"
