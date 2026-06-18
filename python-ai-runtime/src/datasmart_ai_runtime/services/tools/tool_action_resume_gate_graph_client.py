"""Java agent-runtime 恢复门控图客户端。

本模块承接 Java Agent Runtime 5.85 的只读接口：
`/agent-runtime/tool-action-resume-gates/graphs/preview`。它让 Python Runtime 的
`/agent/tool-actions/checkpoints/resume-preview` 不再只消费 fact bundle 列表，而是优先读取 Java
host-controlled gate graph：

- Java 负责把 checkpoint/locator、security scope、approval、clarification、outbox、worker receipt
  和最终 resume gate 统一解释为条件图；
- Python 只消费图级状态、事实类型集合和低敏计数；
- 即使图状态为 READY，也只表示可以进入 resume-preview，不代表工具已执行或 outbox 已派发；
- 响应摘要不回显 approvalFactId、clarificationFactId、outboxId、payloadReference、SQL、prompt、
  工具 arguments、样本数据、模型输出、凭证或内部 endpoint。
"""

from __future__ import annotations

import json
from collections.abc import Mapping
from dataclasses import dataclass
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from datasmart_ai_runtime.services.tools.tool_action_resume_fact_bundle_client import (
    AgentRuntimeResumeFactBundleClientSettings,
    JavaAgentRuntimeToolActionResumeFactBundleClient,
    SERVER_BACKED_FACT_TYPES,
)
from datasmart_ai_runtime.services.tools.tool_action_resume_fact_provider import (
    ToolActionResumeFactSnapshot,
    resume_fact_types_from_mapping,
)


DEFAULT_AGENT_RUNTIME_RESUME_GATE_GRAPH_PATH = "/agent-runtime/tool-action-resume-gates/graphs/preview"
AGENT_RUNTIME_RESUME_GATE_GRAPH_SOURCE = "AGENT_RUNTIME_RESUME_GATE_GRAPH_PROVIDER"
AGENT_RUNTIME_RESUME_GATE_GRAPH_DISABLED_SOURCE = "AGENT_RUNTIME_RESUME_GATE_GRAPH_PROVIDER_DISABLED"


class AgentRuntimeResumeGateGraphClientError(RuntimeError):
    """Java gate graph 客户端异常。

    异常只携带低敏机器码。它不会包含 URL、Header、token、Java 响应正文、SQL、prompt 或工具参数，
    避免 resume-preview 把控制面部署细节扩散给外部调用方。
    """

    def __init__(self, code: str) -> None:
        self.code = code
        super().__init__(code)


@dataclass(frozen=True)
class AgentRuntimeResumeGateGraphClientSettings:
    """Java agent-runtime 恢复门控图客户端配置。

    字段说明：
    - `enabled`：默认关闭，避免本地学习和 CI 在未启动 Java 服务时发生网络副作用；
    - `base_url`：agent-runtime 根地址，通常来自 `DATASMART_AGENT_RUNTIME_BASE_URL`；
    - `graph_path`：5.85 恢复门控图路由，可被 gateway 前缀或灰度路由覆盖；
    - `timeout_seconds`：resume-preview 关键路径网络超时，生产环境应短而明确；
    - `service_token`：可选服务间 Bearer token，只进入 Header，不进入摘要；
    - `service_account_*`：Python 以服务账号身份查询 Java host facts 时使用的低敏 Header。
    """

    enabled: bool = False
    base_url: str = "http://localhost:8091"
    graph_path: str = DEFAULT_AGENT_RUNTIME_RESUME_GATE_GRAPH_PATH
    timeout_seconds: int = 3
    service_token: str | None = None
    service_account_actor_id: str | None = "900001"
    service_account_role: str = "SERVICE_ACCOUNT"
    data_scope_level: str = "PLATFORM"
    authorized_project_ids: tuple[str, ...] = ()


class JavaAgentRuntimeToolActionResumeGateGraphClient:
    """通过 Java agent-runtime 查询恢复门控图。

    本类实现 `ToolActionResumeFactProvider` 协议。它和旧 fact bundle provider 的关系是：
    - 请求体仍复用 fact bundle DTO，避免 checkpoint hint 提取规则分叉；
    - 响应优先读取 Java 5.85 gate graph，获得 `READY/WAITING/REJECTED` 图级状态；
    - Python 继续只返回事实类型和图摘要，不把 Java 节点详情当成执行命令。
    """

    def __init__(
        self,
        settings: AgentRuntimeResumeGateGraphClientSettings | None = None,
        *,
        urlopen_func: Any = urlopen,
    ) -> None:
        self._settings = settings or AgentRuntimeResumeGateGraphClientSettings()
        self._urlopen = urlopen_func
        # 复用旧客户端的 public payload builder，保证 checkpoint hint、request alias、requiredFactTypes
        # 推断语义一致；这里只拿请求体构造能力，不调用旧 fact bundle endpoint。
        self._payload_builder = JavaAgentRuntimeToolActionResumeFactBundleClient(
            AgentRuntimeResumeFactBundleClientSettings(
                enabled=True,
                base_url=self._settings.base_url,
                timeout_seconds=self._settings.timeout_seconds,
                service_token=self._settings.service_token,
                service_account_actor_id=self._settings.service_account_actor_id,
                service_account_role=self._settings.service_account_role,
                data_scope_level=self._settings.data_scope_level,
                authorized_project_ids=self._settings.authorized_project_ids,
            )
        )

    def collect(
        self,
        *,
        checkpoint: Any,
        request_payload: Mapping[str, Any] | None = None,
    ) -> ToolActionResumeFactSnapshot:
        """收集 Java 恢复门控图并转换为 Python 低敏事实快照。

        执行流程：
        1. 未启用时不访问网络，返回 disabled source；
        2. 复用 fact bundle payload builder 构造 Java 查询 DTO；
        3. 调用 Java gate graph preview；
        4. 将 graph.available/missing/rejectedFactTypes 转成 Python snapshot；
        5. 如果 Java 缺失或拒绝调用方自报事实，则 fail-closed，防止任意字符串绕过恢复预检。
        """

        if not self._settings.enabled:
            return ToolActionResumeFactSnapshot(source=AGENT_RUNTIME_RESUME_GATE_GRAPH_DISABLED_SOURCE)

        payload = request_payload if isinstance(request_payload, Mapping) else {}
        request_body = self._payload_builder.build_resume_fact_bundle_payload(
            checkpoint=checkpoint,
            request_payload=payload,
        )
        request_claimed_facts = set(resume_fact_types_from_mapping(payload))
        try:
            data = self._post_platform_request(request_body, trace_id=_trace_id_from_payload(payload, checkpoint))
        except AgentRuntimeResumeGateGraphClientError as exc:
            rejected = tuple(sorted(request_claimed_facts.intersection(SERVER_BACKED_FACT_TYPES)))
            return ToolActionResumeFactSnapshot(
                source=AGENT_RUNTIME_RESUME_GATE_GRAPH_SOURCE,
                missing_fact_types=tuple(request_body.get("requiredFactTypes") or ()),
                rejected_fact_types=rejected,
                error_codes=(exc.code,),
                resume_gate_graph=_error_graph_summary(exc.code),
            )

        graph = data.get("graph") if isinstance(data.get("graph"), Mapping) else {}
        available = _safe_code_tuple(graph.get("availableFactTypes"), maximum=16)
        missing = _safe_code_tuple(graph.get("missingFactTypes"), maximum=16)
        java_rejected = _safe_code_tuple(graph.get("rejectedFactTypes"), maximum=16)
        request_rejected = tuple(
            item for item in missing if item in request_claimed_facts and item in SERVER_BACKED_FACT_TYPES
        )
        rejected = _merge_codes(java_rejected, request_rejected)
        return ToolActionResumeFactSnapshot(
            source=AGENT_RUNTIME_RESUME_GATE_GRAPH_SOURCE,
            available_fact_types=available,
            missing_fact_types=missing,
            rejected_fact_types=rejected,
            fact_reference_count=_fact_reference_count(graph),
            error_codes=_issue_codes_from_graph(graph),
            resume_gate_graph=_graph_summary(data, graph),
        )

    def _post_platform_request(self, request_body: Mapping[str, Any], *, trace_id: str | None) -> Mapping[str, Any]:
        """发送 Java gate graph 请求并解析统一响应信封。"""

        request = Request(
            self._graph_url(),
            data=json.dumps(request_body, ensure_ascii=False).encode("utf-8"),
            headers=self._headers(request_body=request_body, trace_id=trace_id),
            method="POST",
        )
        try:
            with self._urlopen(request, timeout=max(1, int(self._settings.timeout_seconds))) as response:
                payload = json.loads(response.read().decode("utf-8"))
        except HTTPError as exc:
            raise AgentRuntimeResumeGateGraphClientError("AGENT_RUNTIME_RESUME_GATE_GRAPH_HTTP_ERROR") from exc
        except URLError as exc:
            raise AgentRuntimeResumeGateGraphClientError("AGENT_RUNTIME_RESUME_GATE_GRAPH_NETWORK_ERROR") from exc
        except TimeoutError as exc:
            raise AgentRuntimeResumeGateGraphClientError("AGENT_RUNTIME_RESUME_GATE_GRAPH_TIMEOUT") from exc
        except (OSError, ValueError, json.JSONDecodeError) as exc:
            raise AgentRuntimeResumeGateGraphClientError("AGENT_RUNTIME_RESUME_GATE_GRAPH_RESPONSE_INVALID") from exc

        if not isinstance(payload, Mapping):
            raise AgentRuntimeResumeGateGraphClientError("AGENT_RUNTIME_RESUME_GATE_GRAPH_RESPONSE_INVALID")
        if int(payload.get("code") or 0) != 0:
            raise AgentRuntimeResumeGateGraphClientError("AGENT_RUNTIME_RESUME_GATE_GRAPH_PLATFORM_REJECTED")
        data = payload.get("data")
        if not isinstance(data, Mapping):
            raise AgentRuntimeResumeGateGraphClientError("AGENT_RUNTIME_RESUME_GATE_GRAPH_DATA_MISSING")
        return data

    def _graph_url(self) -> str:
        """拼接 Java gate graph URL，兼容 base/path 斜杠差异。"""

        base_url = str(self._settings.base_url or "").rstrip("/")
        path = str(self._settings.graph_path or "").strip()
        if not base_url:
            raise AgentRuntimeResumeGateGraphClientError("AGENT_RUNTIME_BASE_URL_MISSING")
        return f"{base_url}/{path.lstrip('/')}"

    def _headers(self, *, request_body: Mapping[str, Any], trace_id: str | None) -> dict[str, str]:
        """构造 Java 控制面 Header。

        Header 与 fact bundle provider 保持一致，便于 Java 复用同一套 tenant/project/actor 范围收口逻辑。
        生产环境应由 gateway 或服务账号签名保护这些 Header；客户端不把 Header 原文写入响应。
        """

        headers = {
            "Content-Type": "application/json; charset=utf-8",
            "Accept": "application/json",
            "X-DataSmart-Source-Service": "python-ai-runtime",
        }
        _put_header(headers, "X-DataSmart-Trace-Id", trace_id)
        _put_header(headers, "X-DataSmart-Tenant-Id", request_body.get("tenantId"))
        _put_header(headers, "X-DataSmart-Actor-Id", _actor_id_for_header(request_body, self._settings))
        _put_header(headers, "X-DataSmart-Actor-Role", self._settings.service_account_role)
        _put_header(headers, "X-DataSmart-Data-Scope-Level", self._settings.data_scope_level)
        _put_header(headers, "X-DataSmart-Authorized-Project-Ids", _authorized_project_header(request_body, self._settings))
        if self._settings.service_token:
            headers["Authorization"] = f"Bearer {self._settings.service_token}"
        return headers


def _graph_summary(data: Mapping[str, Any], graph: Mapping[str, Any]) -> dict[str, Any]:
    """提取 Java gate graph 的低敏摘要。

    不返回 `requestedLocator` 和 `nodes/edges` 原文，因为这些字段可能包含 commandId 或更细的内部诊断；
    Python resume-preview 只需要图级状态、计数和事实类型集合。
    """

    return {
        "source": AGENT_RUNTIME_RESUME_GATE_GRAPH_SOURCE,
        "schemaVersion": _text(data.get("schemaVersion") or graph.get("schemaVersion")),
        "graphState": _text(data.get("graphState") or graph.get("graphState")),
        "terminalState": _text(data.get("terminalState") or graph.get("terminalState")),
        "resumePreviewReady": bool(data.get("resumePreviewReady") or graph.get("resumePreviewReady")),
        "requiredFactTypes": _safe_code_tuple(graph.get("requiredFactTypes"), maximum=16),
        "availableFactTypes": _safe_code_tuple(graph.get("availableFactTypes"), maximum=16),
        "missingFactTypes": _safe_code_tuple(graph.get("missingFactTypes"), maximum=16),
        "rejectedFactTypes": _safe_code_tuple(graph.get("rejectedFactTypes"), maximum=16),
        "nodeCount": _optional_int(data.get("nodeCount") or graph.get("nodeCount")),
        "edgeCount": _optional_int(data.get("edgeCount") or graph.get("edgeCount")),
        "blockedNodeCount": _optional_int(graph.get("blockedNodeCount")),
        "executableNodeCount": _optional_int(graph.get("executableNodeCount")),
        "payloadPolicy": _text(data.get("payloadPolicy") or graph.get("payloadPolicy")),
        "recommendedActions": _safe_code_tuple(data.get("recommendedActions") or graph.get("recommendedActions"), maximum=8),
    }


def _error_graph_summary(code: str) -> dict[str, Any]:
    """远程失败时返回低敏图摘要。"""

    return {
        "source": AGENT_RUNTIME_RESUME_GATE_GRAPH_SOURCE,
        "graphState": "REMOTE_GATE_GRAPH_UNAVAILABLE",
        "terminalState": "WAIT_FOR_JAVA_CONTROL_PLANE",
        "resumePreviewReady": False,
        "errorCodes": (code,),
    }


def _issue_codes_from_graph(graph: Mapping[str, Any]) -> tuple[str, ...]:
    """从 Java graph 节点中提取低敏问题码。"""

    issues: list[str] = []
    nodes = graph.get("nodes")
    if isinstance(nodes, (list, tuple)):
        for node in nodes:
            if isinstance(node, Mapping):
                for code in _safe_code_tuple(node.get("missingRequirements"), maximum=6):
                    _append_once(issues, code)
            if len(issues) >= 12:
                break
    return tuple(issues)


def _fact_reference_count(graph: Mapping[str, Any]) -> int:
    """估算 Java gate graph 中参与门控的事实数量。"""

    required = _safe_code_tuple(graph.get("requiredFactTypes"), maximum=32)
    return len(required) if required else len(_safe_code_tuple(graph.get("availableFactTypes"), maximum=32))


def _trace_id_from_payload(payload: Mapping[str, Any], checkpoint: Any) -> str | None:
    context = payload.get("context")
    context_mapping = context if isinstance(context, Mapping) else {}
    return _first_text(
        payload.get("traceId"),
        payload.get("requestId"),
        context_mapping.get("traceId"),
        context_mapping.get("requestId"),
        getattr(checkpoint, "request_id", None),
        getattr(checkpoint, "checkpoint_id", None),
    )


def _actor_id_for_header(
    request_body: Mapping[str, Any],
    settings: AgentRuntimeResumeGateGraphClientSettings,
) -> str | None:
    actor_id = _text(request_body.get("actorId"))
    if actor_id and _optional_int(actor_id) is not None:
        return actor_id
    return settings.service_account_actor_id


def _authorized_project_header(
    request_body: Mapping[str, Any],
    settings: AgentRuntimeResumeGateGraphClientSettings,
) -> str | None:
    if settings.authorized_project_ids:
        return ",".join(settings.authorized_project_ids)
    project_id = request_body.get("projectId")
    return str(project_id) if project_id is not None and str(project_id).strip() else None


def _put_header(headers: dict[str, str], name: str, value: Any) -> None:
    text = _text(value)
    if text:
        headers[name] = text


def _safe_code_tuple(value: Any, *, maximum: int) -> tuple[str, ...]:
    if not isinstance(value, (list, tuple)):
        return ()
    result: list[str] = []
    for item in value:
        text = _text(item)
        if text and text not in result:
            result.append(text[:100])
        if len(result) >= maximum:
            break
    return tuple(result)


def _merge_codes(*groups: tuple[str, ...]) -> tuple[str, ...]:
    merged: list[str] = []
    for group in groups:
        for item in group:
            _append_once(merged, item)
    return tuple(merged)


def _append_once(items: list[str], value: str) -> None:
    if value not in items:
        items.append(value)


def _first_text(*values: Any) -> str | None:
    for value in values:
        text = _text(value)
        if text:
            return text
    return None


def _optional_int(value: Any) -> int | None:
    text = _text(value)
    if not text:
        return None
    try:
        return int(text)
    except ValueError:
        return None


def _text(value: Any) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    return text or None
