"""Java agent-runtime 工具动作恢复事实包客户端。

本模块承接 Java Agent Runtime 5.70 新增的恢复事实包 API：
`/agent-runtime/tool-action-resume-facts/bundles/query`。它让 Python Runtime 不再只向
permission-admin 单独校验 approvalFactId，而是优先向 Java agent-runtime 查询一个低敏 fact bundle，
由 Java 控制面统一聚合审批事实、command outbox 写入事实和 worker/dry-run receipt 投影事实。

设计边界：
- Python 仍然只做 resume-preview，不执行工具、不写 outbox、不派发 worker；
- Java 返回的 fact bundle 只被转换为事实类型集合，不把 approvalFactId、payloadReference、payloadJson、
  receipt message、SQL、prompt 或工具参数回显到 Python API；
- 如果 Java 控制面明确拒绝某个事实类型，provider 会把它放入 `rejected_fact_types`，从而覆盖请求自报事实；
- 如果 Java 控制面不可用，provider 采用 fail-closed，避免恢复预检因为远程失败而误认为事实齐备。
"""

from __future__ import annotations

import json
from collections.abc import Mapping
from dataclasses import dataclass
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from datasmart_ai_runtime.services.tools.tool_action_resume_fact_bundle_payload import (
    SERVER_BACKED_FACT_TYPES,
    append_once,
    build_resume_fact_bundle_payload as build_resume_fact_bundle_payload_request,
    first_text,
    optional_int,
    text_value,
)
from datasmart_ai_runtime.services.tools.tool_action_resume_fact_provider import (
    ToolActionResumeFactSnapshot,
    resume_fact_types_from_mapping,
)


DEFAULT_AGENT_RUNTIME_RESUME_FACT_BUNDLE_PATH = "/agent-runtime/tool-action-resume-facts/bundles/query"
AGENT_RUNTIME_RESUME_FACT_BUNDLE_SOURCE = "AGENT_RUNTIME_RESUME_FACT_BUNDLE_PROVIDER"
AGENT_RUNTIME_RESUME_FACT_BUNDLE_DISABLED_SOURCE = "AGENT_RUNTIME_RESUME_FACT_BUNDLE_PROVIDER_DISABLED"


class AgentRuntimeResumeFactBundleClientError(RuntimeError):
    """Java fact bundle 客户端异常。

    异常只携带低敏机器码，不携带 URL、响应正文、Header、token 或 Java message。checkpoint API 捕获异常后
    也只能展示 `code`，不能把内部网络或部署信息透传给外部调用方。
    """

    def __init__(self, code: str) -> None:
        self.code = code
        super().__init__(code)


@dataclass(frozen=True)
class AgentRuntimeResumeFactBundleClientSettings:
    """Java agent-runtime 恢复事实包客户端配置。

    字段说明：
    - `enabled`：默认关闭，避免本地学习、CI 或未启动 Java 服务时发生网络副作用；
    - `base_url`：agent-runtime 服务根地址，通常复用 `DATASMART_AGENT_RUNTIME_BASE_URL`；
    - `bundle_path`：恢复事实包路由，保留可配置是为了兼容 gateway 前缀、灰度路由或内部网关；
    - `timeout_seconds`：resume-preview 关键路径网络超时，应短而明确；
    - `service_token`：可选 Bearer token，仅写入 Header，不进入摘要、日志或异常；
    - `service_account_actor_id/role/data_scope_level`：服务间调用 Java 控制面时使用的 Header 身份。
    """

    enabled: bool = False
    base_url: str = "http://localhost:8091"
    bundle_path: str = DEFAULT_AGENT_RUNTIME_RESUME_FACT_BUNDLE_PATH
    timeout_seconds: int = 3
    service_token: str | None = None
    service_account_actor_id: str | None = "900001"
    service_account_role: str = "SERVICE_ACCOUNT"
    data_scope_level: str = "PLATFORM"
    authorized_project_ids: tuple[str, ...] = ()


class JavaAgentRuntimeToolActionResumeFactBundleClient:
    """通过 Java agent-runtime 查询工具动作恢复事实包。

    该类实现 `ToolActionResumeFactProvider` 协议。相比 5.69 的 permission-admin 单点审批校验，它把多个
    控制面事实统一委托给 Java agent-runtime：
    - 审批事实由 agent-runtime 再去调用 permission-admin；
    - outbox 写入事实由 agent-runtime 查询自己的 command outbox；
    - worker/dry-run receipt 由 agent-runtime 查询 runtime event projection；
    - Python 只消费最终事实类型和低敏 issue code。
    """

    def __init__(
        self,
        settings: AgentRuntimeResumeFactBundleClientSettings | None = None,
        *,
        urlopen_func: Any = urlopen,
    ) -> None:
        self._settings = settings or AgentRuntimeResumeFactBundleClientSettings()
        self._urlopen = urlopen_func

    def collect(
        self,
        *,
        checkpoint: Any,
        request_payload: Mapping[str, Any] | None = None,
    ) -> ToolActionResumeFactSnapshot:
        """收集 Java 控制面恢复事实。

        执行流程：
        1. 未启用时返回空事实源，保持本地默认行为；
        2. 从 checkpoint 与请求 payload 中提取低敏定位字段；
        3. 调用 Java fact bundle API；
        4. 把 Java `available/missing/rejectedFactTypes` 转换为 Python snapshot；
        5. 对“调用方自报但 Java 未采信”的服务端事实执行 fail-closed 拒绝。
        """

        if not self._settings.enabled:
            return ToolActionResumeFactSnapshot(source=AGENT_RUNTIME_RESUME_FACT_BUNDLE_DISABLED_SOURCE)

        payload = request_payload if isinstance(request_payload, Mapping) else {}
        request_body = self.build_resume_fact_bundle_payload(checkpoint=checkpoint, request_payload=payload)
        request_claimed_facts = set(resume_fact_types_from_mapping(payload))
        try:
            data = self._post_platform_request(
                request_body,
                trace_id=_trace_id_from_payload(payload, checkpoint),
            )
        except AgentRuntimeResumeFactBundleClientError as exc:
            rejected = tuple(sorted(request_claimed_facts.intersection(SERVER_BACKED_FACT_TYPES)))
            return ToolActionResumeFactSnapshot(
                source=AGENT_RUNTIME_RESUME_FACT_BUNDLE_SOURCE,
                missing_fact_types=tuple(request_body.get("requiredFactTypes") or ()),
                rejected_fact_types=rejected,
                error_codes=(exc.code,),
            )

        available = _safe_code_tuple(data.get("availableFactTypes"), maximum=16)
        missing = _safe_code_tuple(data.get("missingFactTypes"), maximum=16)
        java_rejected = _safe_code_tuple(data.get("rejectedFactTypes"), maximum=16)
        # Java 返回 missing 代表“服务端没有采信该事实”。如果同一个事实类型又来自请求自报，
        # Python 必须把它提升为 rejected，避免 `outboxConfirmationId` 这类任意字符串绕过服务端查询。
        request_rejected = tuple(
            item for item in missing if item in request_claimed_facts and item in SERVER_BACKED_FACT_TYPES
        )
        rejected = _merge_codes(java_rejected, request_rejected)
        return ToolActionResumeFactSnapshot(
            source=AGENT_RUNTIME_RESUME_FACT_BUNDLE_SOURCE,
            available_fact_types=available,
            missing_fact_types=missing,
            rejected_fact_types=rejected,
            fact_reference_count=_fact_reference_count(data),
            error_codes=_issue_codes_from_bundle(data),
        )

    def build_resume_fact_bundle_payload(
        self,
        *,
        checkpoint: Any,
        request_payload: Mapping[str, Any],
    ) -> dict[str, Any]:
        """构造 Java 恢复事实查询请求体。

        该方法保留在客户端类上是为了兼容已有测试和 gate graph provider 的历史调用方式；真正实现已经拆到
        `tool_action_resume_fact_bundle_payload.py`。这样新旧 provider 能复用同一套低敏 DTO 规则，同时避免本
        客户端继续膨胀。
        """

        return build_resume_fact_bundle_payload_request(
            checkpoint=checkpoint,
            request_payload=request_payload,
        )

    def _post_platform_request(self, request_body: Mapping[str, Any], *, trace_id: str | None) -> Mapping[str, Any]:
        """发送 Java fact bundle 请求并解析统一响应信封。"""

        request = Request(
            self._bundle_url(),
            data=json.dumps(request_body, ensure_ascii=False).encode("utf-8"),
            headers=self._headers(request_body=request_body, trace_id=trace_id),
            method="POST",
        )
        try:
            with self._urlopen(request, timeout=max(1, int(self._settings.timeout_seconds))) as response:
                payload = json.loads(response.read().decode("utf-8"))
        except HTTPError as exc:
            raise AgentRuntimeResumeFactBundleClientError("AGENT_RUNTIME_FACT_BUNDLE_HTTP_ERROR") from exc
        except URLError as exc:
            raise AgentRuntimeResumeFactBundleClientError("AGENT_RUNTIME_FACT_BUNDLE_NETWORK_ERROR") from exc
        except TimeoutError as exc:
            raise AgentRuntimeResumeFactBundleClientError("AGENT_RUNTIME_FACT_BUNDLE_TIMEOUT") from exc
        except (OSError, ValueError, json.JSONDecodeError) as exc:
            raise AgentRuntimeResumeFactBundleClientError("AGENT_RUNTIME_FACT_BUNDLE_RESPONSE_INVALID") from exc

        if not isinstance(payload, Mapping):
            raise AgentRuntimeResumeFactBundleClientError("AGENT_RUNTIME_FACT_BUNDLE_RESPONSE_INVALID")
        if int(payload.get("code") or 0) != 0:
            raise AgentRuntimeResumeFactBundleClientError("AGENT_RUNTIME_FACT_BUNDLE_PLATFORM_REJECTED")
        data = payload.get("data")
        if not isinstance(data, Mapping):
            raise AgentRuntimeResumeFactBundleClientError("AGENT_RUNTIME_FACT_BUNDLE_DATA_MISSING")
        return data

    def _bundle_url(self) -> str:
        """拼接 Java fact bundle URL，兼容 base/path 斜杠差异。"""

        base_url = str(self._settings.base_url or "").rstrip("/")
        path = str(self._settings.bundle_path or "").strip()
        if not base_url:
            raise AgentRuntimeResumeFactBundleClientError("AGENT_RUNTIME_BASE_URL_MISSING")
        return f"{base_url}/{path.lstrip('/')}"

    def _headers(self, *, request_body: Mapping[str, Any], trace_id: str | None) -> dict[str, str]:
        """构造 Java 控制面 Header。

        Java fact bundle Controller 复用 runtime event 的访问上下文解析器，因此 Header 中必须有租户、actor、
        角色和数据范围。生产环境应由 gateway 或服务账号签名保护这些 Header；当前客户端只负责按配置装配，
        不把 Header 原文写入任何 API 响应。
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
        authorized_projects = _authorized_project_header(request_body, self._settings)
        _put_header(headers, "X-DataSmart-Authorized-Project-Ids", authorized_projects)
        if self._settings.service_token:
            headers["Authorization"] = f"Bearer {self._settings.service_token}"
        return headers


def _issue_codes_from_bundle(data: Mapping[str, Any]) -> tuple[str, ...]:
    """从 Java facts 列表中提取低敏 issue code。"""

    issues: list[str] = []
    facts = data.get("facts")
    if isinstance(facts, (list, tuple)):
        for fact in facts:
            if isinstance(fact, Mapping):
                for code in _safe_code_tuple(fact.get("issueCodes"), maximum=6):
                    append_once(issues, code)
            if len(issues) >= 12:
                break
    return tuple(issues)


def _fact_reference_count(data: Mapping[str, Any]) -> int:
    """计算 Java 返回的事实引用数量。"""

    facts = data.get("facts")
    if isinstance(facts, (list, tuple)):
        return len(facts)
    return len(_safe_code_tuple(data.get("availableFactTypes"), maximum=16))


def _trace_id_from_payload(payload: Mapping[str, Any], checkpoint: Any) -> str | None:
    context = payload.get("context")
    context_mapping = context if isinstance(context, Mapping) else {}
    return first_text(
        payload.get("traceId"),
        payload.get("requestId"),
        context_mapping.get("traceId"),
        context_mapping.get("requestId"),
        getattr(checkpoint, "request_id", None),
        getattr(checkpoint, "checkpoint_id", None),
    )


def _actor_id_for_header(
    request_body: Mapping[str, Any],
    settings: AgentRuntimeResumeFactBundleClientSettings,
) -> str | None:
    actor_id = text_value(request_body.get("actorId"))
    if actor_id and optional_int(actor_id) is not None:
        return actor_id
    return settings.service_account_actor_id


def _authorized_project_header(
    request_body: Mapping[str, Any],
    settings: AgentRuntimeResumeFactBundleClientSettings,
) -> str | None:
    if settings.authorized_project_ids:
        return ",".join(settings.authorized_project_ids)
    project_id = request_body.get("projectId")
    return str(project_id) if project_id is not None and str(project_id).strip() else None


def _put_header(headers: dict[str, str], name: str, value: Any) -> None:
    text = text_value(value)
    if text:
        headers[name] = text


def _safe_code_tuple(value: Any, *, maximum: int) -> tuple[str, ...]:
    if not isinstance(value, (list, tuple)):
        return ()
    result: list[str] = []
    for item in value:
        text = text_value(item)
        if text and text not in result:
            result.append(text[:100])
        if len(result) >= maximum:
            break
    return tuple(result)


def _merge_codes(*groups: tuple[str, ...]) -> tuple[str, ...]:
    merged: list[str] = []
    for group in groups:
        for item in group:
            append_once(merged, item)
    return tuple(merged)
