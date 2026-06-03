"""长期记忆物化管理员补偿 API 路由。

本文件只负责 HTTP 层职责：解析 query/body、调用 service、把领域异常映射为结构化错误。
真正的业务状态判断在 `AgentMemoryMaterializationAdminService`，真正的原子状态写入在 lease store。

生产权限边界说明：
- 当前 Python Runtime 仍不直接做用户鉴权；
- `/agent/memory/materialization/leases` 与 requeue 路由必须由 gateway/permission-admin 保护；
- 普通用户不应直接访问该路由，推荐权限语义是 `ADMIN_MEMORY_MATERIALIZATION_COMPENSATION`；
- gateway 应把 tenant/project/workspace 数据范围收紧后再转发，避免跨租户枚举失败候选。
"""

from __future__ import annotations

from datetime import datetime, timezone
from enum import Enum
from typing import Any

from datasmart_ai_runtime.domain.events import AgentRuntimeEvent
from datasmart_ai_runtime.services.memory.memory_materialization_admin import (
    AgentMemoryMaterializationAdminService,
    AgentMemoryMaterializationLeaseQuery,
    AgentMemoryMaterializationRequeueRequest,
)
from datasmart_ai_runtime.services.memory.memory_materialization_events import (
    AgentMemoryMaterializationEventContext,
    memory_materialization_requeue_event,
)
from datasmart_ai_runtime.services.memory.memory_materialization_lease_store import (
    AgentMemoryMaterializationLeaseStatus,
)


def register_memory_materialization_admin_routes(
    app: Any,
    service: AgentMemoryMaterializationAdminService,
    *,
    event_store: Any | None = None,
    event_publisher: Any | None = None,
) -> None:
    """注册长期记忆物化补偿路由。

    路由设计：
    - `GET /agent/memory/materialization/leases`：查询 failed/dead_letter 等待补偿的 lease；
    - `POST /agent/memory/materialization/leases/{candidate_id}/requeue`：预览或执行重排。

    当前先提供“单候选重排”，而不是批量重排，是为了降低误操作风险。真实产品里可以再增加批量 dry-run、
    批量确认、审批流、审计导出和按错误类型分组处理，但这些都应建立在单条语义稳定之后。

    `event_store/event_publisher` 是可选旁路：
    - 本地学习环境可以不传，补偿能力仍可运行；
    - API 启动态会传入 RuntimeEventStore/RuntimeEventPublisher，让补偿动作进入 replay 与异步事件总线；
    - 事件发布失败不会回滚补偿状态，因为当前还没有事务 outbox。后续若企业环境要求审计 fail-closed，
      应增加配置开关和数据库事务边界，而不是在这里隐式阻断所有本地开发。
    """

    from fastapi import HTTPException

    @app.get("/agent/memory/materialization/leases")
    def list_materialization_leases(
        tenantId: str | None = None,
        projectId: str | None = None,
        workspaceKey: str | None = None,
        status: str | None = None,
        limit: int = 100,
    ) -> dict[str, Any]:
        """查询长期记忆物化失败/DLQ lease。

        参数说明：
        - `tenantId/projectId/workspaceKey`：数据范围过滤条件，生产环境应由网关按权限注入或收紧；
        - `status`：可传单个状态或逗号分隔状态，例如 `failed,dead_letter`；
        - `limit`：返回条数，API 层裁剪到 1-100，store/service 层仍有 500 的硬上限。

        返回结果只包含 lease 低敏摘要，不包含候选正文、正式记忆正文、lease token 原文或工具输出。
        """

        try:
            statuses = _parse_statuses(status)
            safe_limit = _safe_limit(limit)
            leases = service.list_leases(
                AgentMemoryMaterializationLeaseQuery(
                    tenant_id=tenantId,
                    project_id=projectId,
                    workspace_key=workspaceKey,
                    statuses=statuses,
                    limit=safe_limit,
                )
            )
        except ValueError as exc:
            raise _http_error(
                HTTPException,
                status_code=400,
                error_code="MEMORY_MATERIALIZATION_LEASE_QUERY_INVALID",
                message=str(exc),
            ) from exc
        return {
            "leaseCount": len(leases),
            "leases": tuple(lease.to_summary() for lease in leases),
            "filters": {
                "tenantId": tenantId,
                "projectId": projectId,
                "workspaceKey": workspaceKey,
                "status": tuple(item.value for item in statuses) if statuses else None,
                "limit": safe_limit,
            },
            "notes": (
                "该列表面向管理员补偿，不应暴露给普通业务用户。",
                "默认只查询 failed/dead_letter，避免误处理 succeeded 或 leased 状态。",
            ),
        }

    @app.post("/agent/memory/materialization/leases/{candidate_id}/requeue")
    def requeue_materialization_lease(candidate_id: str, payload: dict[str, Any]) -> dict[str, Any]:
        """预览或执行长期记忆物化补偿重排。

        请求体字段：
        - `operatorId`：必填，执行补偿的人或服务账号；
        - `reason`：必填，说明为什么要重排；
        - `dryRun`：默认 True，只预览不写库；真正执行时显式传 `false`；
        - `delaySeconds`：可选，多少秒后允许 Runner 重新领取；
        - `nextRetryAt`：可选，ISO-8601 时间点。不能和非零 delaySeconds 同时使用。

        重要边界：该路由只改变 lease 重试窗口，不会绕过候选审批，也不会同步写正式记忆。
        """

        try:
            result = service.requeue(
                AgentMemoryMaterializationRequeueRequest(
                    candidate_id=candidate_id,
                    operator_id=str(payload.get("operatorId") or payload.get("operator_id") or "").strip(),
                    reason=str(payload.get("reason") or "").strip(),
                    dry_run=_optional_bool(payload.get("dryRun", payload.get("dry_run", True))),
                    delay_seconds=_optional_int(payload.get("delaySeconds", payload.get("delay_seconds", 0))),
                    next_retry_at=_optional_datetime(payload.get("nextRetryAt", payload.get("next_retry_at"))),
                )
            )
        except KeyError as exc:
            raise _http_error(
                HTTPException,
                status_code=404,
                error_code="MEMORY_MATERIALIZATION_LEASE_NOT_FOUND",
                message=str(exc),
            ) from exc
        except ValueError as exc:
            status_code = _value_error_status_code(exc)
            raise _http_error(
                HTTPException,
                status_code=status_code,
                error_code=(
                    "MEMORY_MATERIALIZATION_REQUEUE_INVALID"
                    if status_code == 400
                    else "MEMORY_MATERIALIZATION_REQUEUE_CONFLICT"
                ),
                message=str(exc),
            ) from exc
        except RuntimeError as exc:
            raise _http_error(
                HTTPException,
                status_code=409,
                error_code="MEMORY_MATERIALIZATION_REQUEUE_RACE",
                message=str(exc),
            ) from exc
        event = memory_materialization_requeue_event(
            result,
            _event_context_from_payload(payload, operator_id=result.operator_id),
        )
        delivery = _record_runtime_event(event, event_store=event_store, event_publisher=event_publisher)
        response = result.to_summary()
        response["runtimeEvent"] = _runtime_event_payload(event)
        response["runtimeEventDelivery"] = delivery
        return response


def _parse_statuses(status: str | None) -> tuple[AgentMemoryMaterializationLeaseStatus, ...] | None:
    """解析状态过滤参数。

    允许逗号分隔是为了让管理台用一个 query 参数表达多个状态；后续如果前端使用 repeat 参数，也可以在
    route 层继续扩展，但 service/store 的状态集合语义不需要改变。
    """

    if status is None or not str(status).strip():
        return None
    parsed: list[AgentMemoryMaterializationLeaseStatus] = []
    for item in str(status).split(","):
        value = item.strip()
        if not value:
            continue
        try:
            parsed.append(AgentMemoryMaterializationLeaseStatus(value))
        except ValueError as exc:
            allowed = ", ".join(option.value for option in AgentMemoryMaterializationLeaseStatus)
            raise ValueError(f"不支持的 lease 状态: {value}，可选值: {allowed}") from exc
    return tuple(parsed) or None


def _safe_limit(limit: int) -> int:
    """规范化管理台列表页大小。"""

    return max(1, min(int(limit), 100))


def _optional_int(value: Any) -> int | None:
    """解析可选整数。"""

    if value is None or value == "":
        return None
    return int(value)


def _optional_bool(value: Any) -> bool:
    """解析布尔值。

    FastAPI 通常会把 JSON boolean 解析成 `bool`，但联调脚本、curl 或某些前端表单可能传字符串。
    这里显式识别 `"false"`，避免 Python 的 `bool("false") == True` 导致真实重排被误当成 dry-run。
    """

    if isinstance(value, bool):
        return value
    return str(value).strip().lower() not in {"0", "false", "no", "off"}


def _value_error_status_code(exc: ValueError) -> int:
    """把业务校验错误映射成更准确的 HTTP 状态。

    缺少操作者、原因或时间参数非法，属于请求本身不合法，返回 400；当前 lease 状态不允许重排，属于资源状态冲突，
    返回 409。这样前端可以区分“表单要改”与“对象状态已经不适合操作”。
    """

    message = str(exc)
    if any(marker in message for marker in ("operatorId", "reason", "delaySeconds", "nextRetryAt")):
        return 400
    return 409


def _optional_datetime(value: Any) -> datetime | None:
    """解析可选 ISO-8601 时间。

    Python `datetime.fromisoformat` 在部分版本中不接受 `Z`，因此这里把常见 UTC 写法规范化为 `+00:00`。
    """

    if value is None or value == "":
        return None
    parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    return parsed.replace(tzinfo=timezone.utc) if parsed.tzinfo is None else parsed.astimezone(timezone.utc)


def _event_context_from_payload(
    payload: dict[str, Any],
    *,
    operator_id: str,
) -> AgentMemoryMaterializationEventContext:
    """从补偿请求中解析 runtime event 上下文。

    补偿路由不是普通 `/agent/plans` 请求，不一定有 requestId/runId/sessionId。这里允许调用方显式传入这些
    字段，便于未来 Java 管理台、任务中心或审计控制面把补偿动作挂到某个管理任务上。
    """

    return AgentMemoryMaterializationEventContext(
        actor_id=str(payload.get("actorId") or payload.get("actor_id") or operator_id).strip() or operator_id,
        request_id=_optional_text(payload.get("requestId", payload.get("request_id"))),
        run_id=_optional_text(payload.get("runId", payload.get("run_id"))),
        session_id=_optional_text(payload.get("sessionId", payload.get("session_id"))),
        sequence=_optional_int(payload.get("sequence")),
    )


def _record_runtime_event(
    event: AgentRuntimeEvent,
    *,
    event_store: Any | None,
    event_publisher: Any | None,
) -> dict[str, Any]:
    """把补偿事件写入 replay store 并尝试发布到异步总线。

    事件链路当前是旁路能力：它提升可观测性和审计可见性，但不应该让本地学习环境因为没有 Redis/Kafka 而
    无法执行补偿。这里会捕获 store/publisher 异常并返回低敏错误摘要，方便管理台提示“补偿已完成但事件旁路失败”。
    """

    errors: list[dict[str, str]] = []
    stored = False
    published_count = 0
    if event_store is not None:
        try:
            event_store.append_many((event,))
            stored = True
        except Exception as exc:  # pragma: no cover - 依赖真实外部存储故障时触发
            errors.append(_delivery_error("event_store", exc))
    if event_publisher is not None:
        try:
            published_count = int(event_publisher.publish((event,)))
        except Exception as exc:  # pragma: no cover - 依赖真实 Kafka 故障时触发
            errors.append(_delivery_error("event_publisher", exc))
    return {
        "storeEnabled": event_store is not None,
        "publisherEnabled": event_publisher is not None,
        "stored": stored,
        "publishedCount": published_count,
        "errors": tuple(errors),
    }


def _runtime_event_payload(event: AgentRuntimeEvent) -> dict[str, Any]:
    """把 AgentRuntimeEvent 转成 API JSON 友好的 lowerCamelCase payload。"""

    return {
        "eventType": _enum_value(event.event_type),
        "stage": event.stage,
        "message": event.message,
        "severity": _enum_value(event.severity),
        "tenantId": event.tenant_id,
        "projectId": event.project_id,
        "actorId": event.actor_id,
        "requestId": event.request_id,
        "runId": event.run_id,
        "sessionId": event.session_id,
        "sequence": event.sequence,
        "attributes": dict(event.attributes),
        "createdAt": event.created_at.isoformat(),
    }


def _optional_text(value: Any) -> str | None:
    """解析可选字符串。"""

    if value is None:
        return None
    text = str(value).strip()
    return text or None


def _enum_value(value: Any) -> Any:
    """把 Enum 转为 JSON 友好值。"""

    return value.value if isinstance(value, Enum) else value


def _delivery_error(component: str, exc: Exception) -> dict[str, str]:
    """构造低敏事件投递错误摘要。"""

    return {
        "component": component,
        "errorType": type(exc).__name__,
        "message": str(exc)[:300],
    }


def _http_error(
    http_exception_type: Any,
    *,
    status_code: int,
    error_code: str,
    message: str,
) -> Exception:
    """构造结构化 HTTP 错误，便于前端、网关与日志统计统一识别。"""

    return http_exception_type(
        status_code=status_code,
        detail={
            "errorCode": error_code,
            "message": message,
            "statusCode": status_code,
        },
    )
