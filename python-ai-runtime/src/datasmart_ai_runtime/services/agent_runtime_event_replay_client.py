"""Java Agent Runtime 事件投影 replay 客户端。

DataSmart 的 Agent 主链现在同时产生两类关键事件：

- Python AI Runtime：模型路由、上下文选择、工具计划、二轮推理等“智能编排事件”；
- Java agent-runtime：工具审批、执行中、成功、失败、跳过等“控制面执行事实”。

如果 WebSocket 断线重连或运行详情页只读取 Python 本地事件，用户就会看不到真实工具状态；如果只读取
Java 投影，又会丢失模型为什么这样规划。该客户端把 Java
`/agent-runtime/runtime-events` 投影查询适配成 Python 的 `RuntimeEventReplaySource`，让订阅状态机
能够把两边事件合并进同一个 replay envelope。
"""

from __future__ import annotations

import json
from datetime import datetime, timezone
from typing import Any
from urllib.parse import urlencode
from urllib.request import Request, urlopen

from datasmart_ai_runtime.domain.event_transport import RuntimeEventSubscriptionRequest
from datasmart_ai_runtime.domain.events import (
    AgentRuntimeEvent,
    AgentRuntimeEventSeverity,
    AgentRuntimeEventType,
)


class AgentRuntimeEventReplayClientError(RuntimeError):
    """Java runtime-event replay 客户端错误。

    该异常表示外部 Java 控制面查询失败或响应契约不符合预期。上层 replay coordinator 会把它降级成
    `externalReplayErrors`，不直接打断 WebSocket subscribe/reconnect。
    """


class JavaAgentRuntimeEventReplayClient:
    """读取 Java agent-runtime 事件投影并转换为 Python runtime event。

    当前实现使用标准库 `urllib`，保持 Python Runtime 在本地学习环境中的轻依赖。生产环境如果需要
    连接池、mTLS、服务发现、重试、熔断或 OpenTelemetry，可以在不修改状态机的前提下替换该客户端。
    """

    source_name = "java-agent-runtime-event-projection"

    def __init__(
        self,
        base_url: str,
        timeout_seconds: int = 3,
        replay_path: str = "/agent-runtime/runtime-events",
        default_limit: int = 200,
    ) -> None:
        """初始化 Java replay 客户端。

        参数说明：
        - `base_url`：Java `agent-runtime` 服务地址，例如 `http://localhost:8086`；
        - `timeout_seconds`：单次查询超时。WebSocket replay 属于用户交互链路，不能无限等待；
        - `replay_path`：Java 投影查询路径，保留配置点便于 gateway 或服务前缀变化；
        - `default_limit`：单次最多读取多少条投影事件，防止一个 run 的热窗口过大拖慢 subscribe。
        """

        self._base_url = base_url.rstrip("/")
        self._timeout_seconds = max(1, int(timeout_seconds))
        self._replay_path = replay_path if replay_path.startswith("/") else f"/{replay_path}"
        self._default_limit = max(1, int(default_limit))

    def replay(self, request: RuntimeEventSubscriptionRequest) -> tuple[AgentRuntimeEvent, ...]:
        """按订阅请求从 Java 投影查询事件。

        该方法只负责查询与 DTO 转换，不做最终可见性脱敏。原因是 Java 查询入口已经做了一层权限收口，
        Python envelope 构建前还会再走 `RuntimeEventVisibilityPolicy`，形成跨服务双层保护。
        """

        url = self._build_query_url(request)
        headers = self._build_headers(request)
        http_request = Request(url=url, headers=headers, method="GET")
        try:
            with urlopen(http_request, timeout=self._timeout_seconds) as response:  # noqa: S310 - base_url 来自受控运行配置
                payload = json.loads(response.read().decode("utf-8"))
        except Exception as exc:  # pragma: no cover - 单元测试重点覆盖解析与请求构造
            raise AgentRuntimeEventReplayClientError(f"查询 Java Agent runtime event replay 失败：{exc}") from exc
        return self.parse_platform_response(payload)

    def _build_query_url(self, request: RuntimeEventSubscriptionRequest) -> str:
        """把订阅请求转换为 Java 查询 URL。

        多个 ID 条件是“同时满足”关系：sessionId、runId、requestId 同时出现时，Java 只返回同时匹配的
        投影事件。eventTypes 当前只在单一类型时下推，因为 Java 第一版查询接口只支持一个 eventType；
        多类型过滤会交给 Python transport builder 再做一次。
        """

        params: dict[str, str | int] = {
            "limit": self._default_limit,
        }
        source_cursor = request.source_cursors.get(self.source_name)
        if source_cursor is not None and source_cursor > 0:
            # 这里刻意不把 request.after_sequence 直接下推给 Java。afterSequence 是前端看到的
            # envelope 级展示序号；Java 查询接口需要的是 Java 投影自己的 replaySequence。
            # 两套坐标混用会导致“前端已经 ack 到 50，但 Java 只写到 7”时误跳过新工具事件。
            params["afterSequence"] = source_cursor
        self._put_if_present(params, "tenantId", request.tenant_id)
        self._put_if_present(params, "projectId", request.project_id)
        self._put_if_present(params, "actorId", request.actor_id)
        self._put_if_present(params, "requestId", request.request_id)
        self._put_if_present(params, "runId", request.run_id)
        self._put_if_present(params, "sessionId", request.session_id)
        if len(request.event_types) == 1:
            self._put_if_present(params, "eventType", _event_type_value(request.event_types[0]))
        return f"{self._base_url}{self._replay_path}?{urlencode(params)}"

    def _build_headers(self, request: RuntimeEventSubscriptionRequest) -> dict[str, str]:
        """构造 Java 控制面需要的平台上下文 Header。

        真实生产路径里这些 Header 应由 gateway 根据认证结果写入；Python Runtime 作为服务间调用方时，
        需要把订阅请求中已经过授权合并的 tenant/actor/role 继续透传给 Java 查询服务。
        """

        role = self._primary_role(request)
        headers = {
            "Accept": "application/json",
            "X-DataSmart-Source-Service": "python-ai-runtime",
        }
        # requestId 在 DataSmart Agent 主链中经常承载网关 trace 或一次请求的关联 ID。
        # 透传给 Java 控制面后，Java 响应、日志和 runtime-event 查询可以继续串起同一条排障链路。
        self._put_if_present(headers, "X-DataSmart-Trace-Id", request.request_id)
        self._put_if_present(headers, "X-DataSmart-Tenant-Id", request.tenant_id)
        self._put_if_present(headers, "X-DataSmart-Actor-Id", request.actor_id)
        self._put_if_present(headers, "X-DataSmart-Actor-Role", role)
        scope = self._scope_for_role(role)
        if scope:
            headers["X-DataSmart-Data-Scope-Level"] = scope
        if scope == "PROJECT" and request.project_id:
            headers["X-DataSmart-Authorized-Project-Ids"] = request.project_id
        return headers

    @classmethod
    def parse_platform_response(cls, payload: dict[str, Any]) -> tuple[AgentRuntimeEvent, ...]:
        """解析 Java `PlatformApiResponse<AgentRuntimeEventProjectionQueryResponse>`。

        该方法单独暴露给单元测试，确保 Python/Java 契约字段变动时可以尽早失败，而不是等真实 WebSocket
        联调时才发现 replay envelope 为空。
        """

        if payload.get("code") != 0:
            reason = payload.get("reason", "UNKNOWN")
            message = payload.get("message", "Java runtime-event 投影查询失败")
            raise AgentRuntimeEventReplayClientError(f"{reason}: {message}")
        data = payload.get("data")
        if not isinstance(data, dict):
            raise AgentRuntimeEventReplayClientError("Java runtime-event replay 响应 data 必须是对象。")
        raw_events = data.get("events", ())
        if not isinstance(raw_events, list):
            raise AgentRuntimeEventReplayClientError("Java runtime-event replay 响应 data.events 必须是数组。")
        return tuple(cls._event_from_projection(item) for item in raw_events if isinstance(item, dict))

    @classmethod
    def _event_from_projection(cls, item: dict[str, Any]) -> AgentRuntimeEvent:
        """把 Java 投影视图转换为 Python 运行时事件。

        Java 视图中的 `attributes` 已经过 Java 可见性策略处理，这里仍会补充少量来源字段，便于 replay
        诊断与前端去重。完整工具入参、审批备注原文和工具完整输出不应出现在这里。
        """

        attributes = dict(item.get("attributes") or {})
        producer_sequence = _optional_int(item.get("sequence"))
        replay_sequence = _optional_int(item.get("replaySequence"))
        attributes.setdefault("javaProjectionIdentityKey", item.get("identityKey"))
        attributes.setdefault("javaProjectionSchemaVersion", item.get("schemaVersion"))
        attributes.setdefault("javaProjectionSource", item.get("source"))
        attributes.setdefault("javaProjectionPublishedAt", item.get("publishedAt"))
        attributes.setdefault("javaProjectionConsumedAt", item.get("consumedAt"))
        attributes.setdefault("javaProjectionProducerSequence", producer_sequence)
        attributes.setdefault("javaProjectionReplaySequence", replay_sequence)
        attributes.setdefault("javaProjectionCursor", replay_sequence)
        return AgentRuntimeEvent(
            event_type=_event_type_from_value(item.get("eventType")),
            stage=str(item.get("stage") or "java_runtime_event"),
            message=str(item.get("message") or "Java agent-runtime 已产生控制面事件。"),
            severity=_severity_from_value(item.get("severity")),
            tenant_id=_optional_text(item.get("tenantId")),
            project_id=_optional_text(item.get("projectId")),
            actor_id=_optional_text(item.get("actorId")),
            request_id=_optional_text(item.get("requestId")),
            run_id=_optional_text(item.get("runId")),
            session_id=_optional_text(item.get("sessionId")),
            # AgentRuntimeEvent.sequence 在进入 replay coordinator 前临时承载“该 source 的稳定游标”。
            # coordinator 会在需要时把它重映射为 envelope sequence，同时把原始游标写入 sourceCursors。
            # 优先使用 Java replaySequence，是因为 producer sequence 可能为空，也可能只在某次 Python plan
            # 内局部递增，不能直接作为跨服务断线续传的稳定依据。
            sequence=replay_sequence if replay_sequence is not None else producer_sequence,
            attributes=attributes,
            created_at=_datetime_from_value(item.get("createdAt")),
        )

    @staticmethod
    def _primary_role(request: RuntimeEventSubscriptionRequest) -> str:
        """从订阅请求中选择一个主要角色透传给 Java 控制面。"""

        if not request.roles:
            return "ORDINARY_USER"
        return _normalize_role(request.roles[0])

    @staticmethod
    def _scope_for_role(role: str) -> str:
        """根据角色推断 Java 查询需要的数据范围 Header。

        这不是替代 permission-admin，而是服务间调用的安全默认值：在 gateway 尚未把完整数据范围透传进
        Python 订阅消息之前，Python 只会按角色收紧范围，不会主动扩大查询权限。
        """

        normalized = _normalize_role(role)
        if normalized in {"PLATFORM_ADMINISTRATOR", "PLATFORM_ADMIN", "SERVICE_ACCOUNT"}:
            return "PLATFORM"
        if normalized in {"TENANT_ADMINISTRATOR", "TENANT_ADMIN", "OPERATOR", "AUDITOR"}:
            return "TENANT"
        if normalized == "PROJECT_OWNER":
            return "PROJECT"
        return "SELF"

    @staticmethod
    def _put_if_present(target: dict[str, Any], key: str, value: Any) -> None:
        """仅在值非空时写入请求参数或 Header。"""

        if value is None:
            return
        text = str(value).strip()
        if text:
            target[key] = text


def _event_type_from_value(value: Any) -> AgentRuntimeEventType | str:
    """解析事件类型，未知类型保留原始字符串。

    保留未知字符串比强行丢弃更适合快速演进的 Agent 事件体系：Java 可能先增加新事件类型，Python
    仍能透传给前端；后续再补 enum、可见性和 UI 展示策略。
    """

    raw_value = str(value or AgentRuntimeEventType.TOOL_EXECUTION_STATE_CHANGED.value)
    try:
        return AgentRuntimeEventType(raw_value)
    except ValueError:
        return raw_value


def _severity_from_value(value: Any) -> AgentRuntimeEventSeverity:
    """解析事件严重级别，未知值降级为 INFO。"""

    try:
        return AgentRuntimeEventSeverity(str(value or AgentRuntimeEventSeverity.INFO.value).lower())
    except ValueError:
        return AgentRuntimeEventSeverity.INFO


def _datetime_from_value(value: Any) -> datetime:
    """解析 Java Instant/ISO 时间，失败时使用当前 UTC 时间。"""

    if not value:
        return datetime.now(timezone.utc)
    text = str(value).replace("Z", "+00:00")
    try:
        parsed = datetime.fromisoformat(text)
    except ValueError:
        return datetime.now(timezone.utc)
    if parsed.tzinfo is None:
        return parsed.replace(tzinfo=timezone.utc)
    return parsed


def _optional_text(value: Any) -> str | None:
    """把 Java 可能返回的 null/空字符串转换为 Python None。"""

    if value is None:
        return None
    text = str(value).strip()
    return text or None


def _optional_int(value: Any) -> int | None:
    """把 Java sequence 转成 int，空值保持 None。"""

    if value is None or str(value).strip() == "":
        return None
    return int(value)


def _event_type_value(value: Any) -> str:
    """读取 enum 或字符串事件类型值。"""

    return str(getattr(value, "value", value))


def _normalize_role(role: Any) -> str:
    """统一角色命名，兼容前端、gateway 和测试中常见的大小写/分隔符差异。"""

    return str(role or "").strip().replace("-", "_").upper()
