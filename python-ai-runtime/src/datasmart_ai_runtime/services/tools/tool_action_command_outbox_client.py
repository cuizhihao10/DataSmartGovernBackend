"""工具动作 command outbox 写入的 Java 控制面客户端。

本模块承接 `tool_action_command_proposal_client.py` 的下一跳：当 Java proposal 明确返回
`outboxWriteAllowedByPreflight=true` 后，Python Runtime 可以在显式启用的情况下调用 Java
`/agent-runtime/tool-action-commands/outbox/write`，把低敏 proposal 请求推进为 durable command outbox
记录。

设计边界必须非常清楚：
- 本客户端不执行工具、不读取 payloadReference、不派发 worker，也不生成 task-management 任务；
- HTTP 请求体复用 Java `AgentToolActionCommandProposalRequest` 白名单字段，绝不透传 arguments、SQL、prompt、
  模型输出、RAG 正文、endpoint、token 或 secret；
- Java 响应也只解析 writerState、commandId、record 安全视图等低敏字段，避免 Java 后续扩展内部审计字段时被
  Python 自动扩散到 API、runtime event 或 checkpoint；
- 默认 `enabled=False`，本地学习、单元测试和 preview 链路不会因为配置遗漏而误写真实 outbox。
"""

from __future__ import annotations

import json
from collections.abc import Mapping
from dataclasses import dataclass
from typing import Any, Callable
from urllib.request import Request, urlopen

from datasmart_ai_runtime.services.tools.tool_action_command_proposal_contract import (
    json_safe,
    string_tuple,
    text,
)


JAVA_OUTBOX_WRITE_ROUTE = "/agent-runtime/tool-action-commands/outbox/write"
JAVA_OUTBOX_WRITE_API_ROUTE = "/api/agent/tool-action-commands/outbox/write"


class ToolActionCommandOutboxClientError(RuntimeError):
    """Java command outbox writer 客户端错误。

    这个异常只表达“跨服务调用或响应契约异常”，不表达业务阻断。业务阻断会以
    `writerState=BLOCKED_BY_*` 的低敏结果返回，方便上层把它展示成可恢复的执行图节点。
    """


@dataclass(frozen=True)
class ToolActionCommandOutboxClientSettings:
    """Java outbox writer 客户端运行配置。

    字段说明：
    - `enabled`：是否真的发起 HTTP 写入。默认关闭，确保 preview/测试环境 fail-closed；
    - `base_url`：Java agent-runtime 地址，默认匹配本项目服务端口约定；
    - `outbox_path`：writer 路由。保留可配置是为了兼容 gateway `/api/...` 与内网 `/agent-runtime/...` 两种路径；
    - `timeout_seconds`：单次写入超时。outbox 写入属于控制面关键路径，应该短超时并交给上层重试/补偿。
    """

    enabled: bool = False
    base_url: str = "http://localhost:8091"
    outbox_path: str = JAVA_OUTBOX_WRITE_ROUTE
    timeout_seconds: int = 3


@dataclass(frozen=True)
class ToolActionCommandOutboxWriteResult:
    """Python -> Java command outbox writer 的低敏结果。

    `request_payload` 只包含 Java proposal request 白名单字段，因此可以安全进入执行图摘要和测试断言。
    `response_summary` 只包含 writer 响应白名单字段，不包含 payloadJson、真实工具参数或内部 endpoint。
    """

    attempted: bool
    written: bool
    skipped: bool
    write_state: str
    skip_reason: str | None
    target_route: str
    request_payload: dict[str, Any]
    response_summary: dict[str, Any]

    def to_summary(self) -> dict[str, Any]:
        """转换成 API/runtime event 可复用的低敏摘要。"""

        return {
            "schemaVersion": "datasmart.python-ai-runtime.tool-action-command-outbox-client.v1",
            "attempted": self.attempted,
            "written": self.written,
            "skipped": self.skipped,
            "writeState": self.write_state,
            "skipReason": self.skip_reason,
            "targetRoute": self.target_route,
            "requestPayload": self.request_payload,
            "javaOutbox": self.response_summary,
            "guardrails": (
                "outbox writer 只复用 Java proposal request 白名单字段，不接受工具 arguments、SQL、prompt 或模型输出。",
                "writer 成功只表示 durable command outbox 已写入或幂等复用，不表示 worker 已执行。",
                "后续真实副作用仍必须经过 dispatcher、task-management inbox、worker lease 和 worker receipt。",
            ),
        }


class JavaToolActionCommandOutboxClient:
    """调用 Java agent-runtime command outbox writer 的轻量客户端。

    这个类和 proposal client 保持相同风格：只做白名单 payload 传输与响应裁剪，不把业务编排逻辑塞进 HTTP
    adapter。这样 `ToolActionExecutionGraphRunner` 可以专注判断节点状态，而 mTLS、服务发现、重试、熔断等
    基础设施能力可以在未来通过 `urlopen_func` 或替换本类实现。
    """

    def __init__(
        self,
        settings: ToolActionCommandOutboxClientSettings | None = None,
        *,
        urlopen_func: Callable[..., Any] = urlopen,
    ) -> None:
        self._settings = settings or ToolActionCommandOutboxClientSettings()
        self._urlopen = urlopen_func

    def write(
        self,
        request_payload: Mapping[str, Any],
        *,
        java_proposal: Mapping[str, Any] | None = None,
        trace_id: str | None = None,
    ) -> ToolActionCommandOutboxWriteResult:
        """按需调用 Java outbox writer。

        工作流拆成三步：
        1. 先确认 proposal 响应已经允许写入 outbox，避免 Python 绕过 Java proposal 阶段；
        2. 如果客户端未启用，返回 `CLIENT_DISABLED`，让执行图清楚停在可恢复节点；
        3. 启用后 POST 到 Java writer，并只解析低敏响应白名单。
        """

        safe_payload = {key: json_safe(value) for key, value in request_payload.items()}
        # 对外摘要只展示路由 path，不展示 base_url。完整内部 endpoint 只存在于 HTTP request 对象中，
        # 避免 runtime event、测试失败输出或诊断 API 泄露 Java 服务地址。
        target_route = self._settings.outbox_path
        if not self._proposal_allows_outbox(java_proposal):
            return ToolActionCommandOutboxWriteResult(
                attempted=False,
                written=False,
                skipped=True,
                write_state="OUTBOX_WRITE_BLOCKED",
                skip_reason="PROPOSAL_DID_NOT_ALLOW_OUTBOX_WRITE",
                target_route=target_route,
                request_payload=safe_payload,
                response_summary={},
            )
        if not self._settings.enabled:
            return ToolActionCommandOutboxWriteResult(
                attempted=False,
                written=False,
                skipped=True,
                write_state="OUTBOX_CLIENT_DISABLED",
                skip_reason="CLIENT_DISABLED",
                target_route=target_route,
                request_payload=safe_payload,
                response_summary={},
            )
        response_summary = self._post(safe_payload, trace_id=trace_id)
        return ToolActionCommandOutboxWriteResult(
            attempted=True,
            written=bool(response_summary.get("enqueued") or response_summary.get("duplicate")),
            skipped=False,
            write_state=text(response_summary.get("writerState")) or "OUTBOX_WRITE_RESPONSE_RECORDED",
            skip_reason=None,
            target_route=target_route,
            request_payload=safe_payload,
            response_summary=response_summary,
        )

    @classmethod
    def parse_platform_response(cls, payload: Mapping[str, Any]) -> dict[str, Any]:
        """解析 Java `PlatformApiResponse<AgentToolActionCommandOutboxWriteResponse>`。

        注意这里不会把 `record.payloadJson` 之类字段透出。即使 Java 安全视图未来扩展更多字段，Python 也只保留
        commandId、状态、目标服务、topic、payloadReference 等低敏定位信息。
        """

        if payload.get("code") != 0:
            reason = payload.get("reason", "UNKNOWN")
            message = payload.get("message", "Java command outbox writer 接口返回失败")
            raise ToolActionCommandOutboxClientError(f"{reason}: {message}")
        data = payload.get("data")
        if not isinstance(data, Mapping):
            raise ToolActionCommandOutboxClientError("Java command outbox writer 响应 data 必须是对象")
        return outbox_write_response_summary(data)

    def _post(self, request_payload: Mapping[str, Any], *, trace_id: str | None) -> dict[str, Any]:
        """执行 HTTP POST，并把响应交给白名单解析器。"""

        body = json.dumps(request_payload, ensure_ascii=False).encode("utf-8")
        http_request = Request(
            url=self._target_url(),
            data=body,
            headers={
                "Accept": "application/json",
                "Content-Type": "application/json; charset=utf-8",
                "X-DataSmart-Trace-Id": trace_id or text(request_payload.get("requestId")) or "",
                "X-DataSmart-Tenant-Id": text(request_payload.get("tenantId")) or "",
                "X-DataSmart-Actor-Id": text(request_payload.get("actorId")) or "",
                "X-DataSmart-Source-Service": "python-ai-runtime",
            },
            method="POST",
        )
        try:
            with self._urlopen(http_request, timeout=self._settings.timeout_seconds) as response:  # noqa: S310
                payload = json.loads(response.read().decode("utf-8"))
        except Exception as exc:  # pragma: no cover - 真实网络错误由集成环境覆盖
            raise ToolActionCommandOutboxClientError(f"调用 Java command outbox writer 失败: {exc}") from exc
        return self.parse_platform_response(payload)

    def _target_url(self) -> str:
        """拼接 Java outbox writer 目标地址。"""

        return f"{self._settings.base_url.rstrip('/')}{self._settings.outbox_path}"

    @staticmethod
    def _proposal_allows_outbox(java_proposal: Mapping[str, Any] | None) -> bool:
        """判断 proposal 响应是否允许进入 outbox writer。"""

        return isinstance(java_proposal, Mapping) and java_proposal.get("outboxWriteAllowedByPreflight") is True


def outbox_write_response_summary(data: Mapping[str, Any]) -> dict[str, Any]:
    """从 Java writer 响应中裁剪低敏字段。"""

    result: dict[str, Any] = {}
    scalar_keys = (
        "writerState",
        "commandId",
        "proposalId",
        "graphId",
        "contractId",
        "runId",
        "payloadReference",
        "proposalState",
    )
    for key in scalar_keys:
        if value := text(data.get(key)):
            result[key] = value
    for key in ("enqueued", "duplicate"):
        if isinstance(data.get(key), bool):
            result[key] = data[key]
    for key in ("summaryReasons", "recommendedActions"):
        values = string_tuple(data.get(key))
        if values:
            result[key] = values
    record = data.get("record")
    if isinstance(record, Mapping):
        safe_record = _outbox_record_summary(record)
        if safe_record:
            result["record"] = safe_record
    return result


def _outbox_record_summary(record: Mapping[str, Any]) -> dict[str, Any]:
    """裁剪 Java outbox record 安全视图。

    `AgentAsyncTaskCommandOutboxRecordView` 已经是 Java 侧安全视图，但 Python 仍然再次白名单，避免未来 record
    扩展 payloadJson、错误详情或内部投递上下文时被自动透传。
    """

    result: dict[str, Any] = {}
    scalar_keys = (
        "commandId",
        "idempotencyKey",
        "commandSchemaVersion",
        "commandType",
        "topic",
        "consumerService",
        "sessionId",
        "runId",
        "auditId",
        "toolName",
        "targetService",
        "targetOperation",
        "traceId",
        "payloadReference",
        "status",
        "createdAt",
        "updatedAt",
        "nextAttemptAt",
        "lastErrorCode",
    )
    for key in scalar_keys:
        if value := text(record.get(key)):
            result[key] = value
    for key in ("id", "tenantId", "projectId", "taskId", "attemptCount", "maxAttempts", "payloadBytes"):
        value = record.get(key)
        if isinstance(value, int) and value >= 0:
            result[key] = value
    return result
