"""OpenAI-compatible 模型 Provider 实现。

本文件专门承载 OpenAI-compatible Chat Completions 协议细节，包括：
- HTTP 请求构造、认证 Header 注入和 endpoint 规范化；
- transient HTTP/网络/超时错误的有限重试；
- 非流式 `message.tool_calls` 解析；
- 流式 `delta.content` 与 `delta.tool_calls` 解析。

把它从 `model_provider.py` 拆出来，是为了让模型 Provider 总入口保持轻量，也避免后续继续加入
tool calling、JSON schema、provider health、连接池和指标时形成一个难维护的“大文件”。
"""

from __future__ import annotations

import json
import time
from dataclasses import dataclass
from http import HTTPStatus
from typing import Iterator, Protocol
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from datasmart_ai_runtime.domain.contracts import (
    ModelInvocationChunk,
    ModelInvocationRequest,
    ModelInvocationResult,
    ModelToolCall,
    ModelToolCallDelta,
)
from datasmart_ai_runtime.services.model_gateway.model_tool_schema import (
    ModelToolSchemaExposurePolicy,
    OpenAICompatibleToolSchemaBuilder,
)


class ModelProviderHttpTransport(Protocol):
    """模型 Provider 使用的 HTTP 传输协议。

    OpenAI-compatible Provider 默认使用 `urllib.request.urlopen`，但测试和生产治理都不应该把 HTTP
    细节写死在方法内部。抽出这个 Protocol 后：
    - 单元测试可以注入 fake transport，不需要启动真实模型服务；
    - 后续可以替换为带连接池、熔断、OpenTelemetry span 的 httpx/aiohttp transport；
    - Provider 本身仍只关注请求/响应契约，不负责底层网络库选择。
    """

    def __call__(self, request: Request, timeout: int):
        """发送 HTTP 请求并返回类文件响应对象。"""


@dataclass(frozen=True)
class OpenAICompatibleProviderSettings:
    """OpenAI-compatible Provider 运行配置。

    这里保存的是 Provider 层配置，不是模型路由本身。路由决定“用哪个模型、哪个 endpoint”，Provider
    配置决定“如何安全可靠地调用 OpenAI-compatible 服务”。

    字段说明：
    - `api_key`：模型服务访问密钥，只允许来自环境变量、密钥中心或运行时注入，不应写进路由表；
    - `organization`：兼容 OpenAI/Azure/企业网关的组织或租户标识 Header；
    - `max_retries`：对 429、5xx、网络抖动、超时等短暂故障的额外重试次数；
    - `retry_backoff_seconds`：重试前等待时间，当前先用固定退避，后续可替换为指数退避和抖动；
    - `extra_headers`：预留给企业内部模型网关的额外 Header，例如网关租户、灰度路由或调用来源。
    """

    api_key: str | None = None
    organization: str | None = None
    max_retries: int = 1
    retry_backoff_seconds: float = 0.05
    extra_headers: dict[str, str] | None = None


class OpenAICompatibleModelProvider:
    """OpenAI-compatible Chat Completions Provider。

    vLLM、SGLang、LiteLLM、很多企业内部模型网关都可以暴露 OpenAI-compatible 接口。本类不是最终的
    “智能网关”，但它是 DataSmart AI Runtime 接入真实模型服务的基础适配层。

    设计意图：
    - 认证、endpoint、重试和响应解析集中在 Provider 内，不污染 Agent 编排器；
    - 返回统一的 `ModelInvocationResult` / `ModelInvocationChunk`，上层不需要绑定某个 SDK；
    - tool/function calling 只在这里解析为“模型提出的工具调用意图”，不在 Provider 内直接执行工具；
    - 工具执行、审批、权限、审计、状态流转留给后续 Agent loop 与 Java 控制面处理。
    """

    _TRANSIENT_STATUS_CODES = {
        HTTPStatus.TOO_MANY_REQUESTS,
        HTTPStatus.INTERNAL_SERVER_ERROR,
        HTTPStatus.BAD_GATEWAY,
        HTTPStatus.SERVICE_UNAVAILABLE,
        HTTPStatus.GATEWAY_TIMEOUT,
    }

    def __init__(
        self,
        settings: OpenAICompatibleProviderSettings | None = None,
        transport: ModelProviderHttpTransport | None = None,
    ) -> None:
        """初始化 OpenAI-compatible Provider。

        `settings` 可以由环境变量、密钥中心或测试手动注入；`transport` 默认使用标准库 `urlopen`。
        当前保持同步调用，是为了先稳定领域契约；后续如果接 FastAPI streaming 或异步 Agent loop，
        可以把 transport 换成 async 版本，而不影响上层 `ModelProviderRegistry` 的使用方式。
        """

        self._settings = settings or OpenAICompatibleProviderSettings()
        self._transport = transport or urlopen
        self._tool_schema_builder = OpenAICompatibleToolSchemaBuilder()

    def invoke(self, request: ModelInvocationRequest) -> ModelInvocationResult:
        """执行一次非流式 Chat Completions 调用。

        非流式调用适合后台批处理、摘要生成、规则草案生成等不需要实时 token 输出的场景。这里会把
        OpenAI-compatible 响应中的 `message.content` 和 `message.tool_calls` 同时转换为统一契约：
        - `content` 给普通文本回答或规划说明使用；
        - `tool_calls` 给后续 Agent loop 判断是否需要进入工具执行、审批或参数校验流程使用。
        """

        if not request.route.endpoint:
            raise ValueError("OpenAI-compatible Provider 需要在 ModelRoute.endpoint 中配置接口地址")

        started_at = time.perf_counter()
        http_request = self._build_http_request(request, stream=False)
        attempts = max(1, self._settings.max_retries + 1)
        last_error_code = "MODEL_PROVIDER_FAILED"
        last_error_message = "模型 Provider 调用失败。"
        for attempt in range(1, attempts + 1):
            try:
                payload = self._send(http_request, request.route.timeout_seconds)
                latency_ms = int((time.perf_counter() - started_at) * 1000)
                return self._to_result(request, payload, latency_ms)
            except HTTPError as exc:
                last_error_code = f"MODEL_PROVIDER_HTTP_{exc.code}"
                last_error_message = self._read_http_error(exc)
                if not self._should_retry(exc.code, attempt, attempts):
                    break
            except URLError as exc:
                last_error_code = "MODEL_PROVIDER_NETWORK_ERROR"
                last_error_message = str(exc.reason)
                if not self._should_retry(None, attempt, attempts):
                    break
            except TimeoutError as exc:
                last_error_code = "MODEL_PROVIDER_TIMEOUT"
                last_error_message = str(exc)
                if not self._should_retry(None, attempt, attempts):
                    break
            if attempt < attempts:
                time.sleep(self._settings.retry_backoff_seconds)

        latency_ms = int((time.perf_counter() - started_at) * 1000)
        return ModelInvocationResult(
            provider_name=request.route.provider_name,
            model_name=request.route.model_name,
            content=f"[MODEL_PROVIDER_ERROR] {last_error_message}",
            latency_ms=latency_ms,
            error_code=last_error_code,
        )

    def stream(self, request: ModelInvocationRequest) -> Iterator[ModelInvocationChunk]:
        """执行 OpenAI-compatible SSE 流式调用。

        OpenAI-compatible streaming 返回的是 Server-Sent Events：每行形如 `data: {...}`，最后以
        `data: [DONE]` 结束。普通文本增量在 `choices[0].delta.content`，工具调用增量在
        `choices[0].delta.tool_calls`。

        这里不尝试在 Provider 内把多个 `tool_call_delta` 聚合成最终 JSON，因为聚合涉及 Agent 状态机：
        需要知道何时进入工具审批、何时等待更多参数片段、何时把工具执行结果回填给模型。Provider 的职责
        只是把底层协议片段转成可审计、可回放、可测试的统一 chunk。
        """

        if not request.route.endpoint:
            raise ValueError("OpenAI-compatible Provider 需要在 ModelRoute.endpoint 中配置接口地址")

        http_request = self._build_http_request(request, stream=True)
        sequence = 0
        try:
            with self._transport(http_request, timeout=request.route.timeout_seconds) as response:  # noqa: S310
                for payload in self._iter_sse_payloads(response):
                    if payload == "[DONE]":
                        break
                    sequence += 1
                    event = json.loads(payload)
                    yield self._to_chunk(request, event, sequence)
        except HTTPError as exc:
            yield self._error_chunk(request, f"MODEL_PROVIDER_HTTP_{exc.code}", self._read_http_error(exc), sequence + 1)
        except URLError as exc:
            yield self._error_chunk(request, "MODEL_PROVIDER_NETWORK_ERROR", str(exc.reason), sequence + 1)
        except TimeoutError as exc:
            yield self._error_chunk(request, "MODEL_PROVIDER_TIMEOUT", str(exc), sequence + 1)
        except json.JSONDecodeError as exc:
            yield self._error_chunk(request, "MODEL_PROVIDER_STREAM_MALFORMED", str(exc), sequence + 1)

    def _build_http_request(self, request: ModelInvocationRequest, stream: bool) -> Request:
        """把统一模型请求转换为 OpenAI-compatible Chat Completions HTTP 请求。

        当前请求体先只放 messages、temperature、max_tokens 和 stream。工具 schema 还没有从
        `ToolDefinition` 反向组装进请求体，是因为“模型可调用哪些工具”需要结合租户权限、项目范围、
        风险等级和审批策略动态裁剪；这一步应由后续 Agent loop / 智能网关统一处理，而不是在 Provider
        中盲目暴露所有工具。
        """

        body = {
            "model": request.route.model_name,
            "messages": [self._message_to_payload(message) for message in request.messages],
            "temperature": request.temperature,
            "max_tokens": request.max_output_tokens,
            "stream": stream,
        }
        if request.provider_metadata:
            # OpenAI-compatible 生态里的模型网关通常允许透传 metadata，LiteLLM、企业内部网关或审计代理
            # 可以据此做缓存、追踪、限流和成本归因。这里不放 prompt，不放工具结果，只放治理策略摘要。
            body["metadata"] = {"datasmart": request.provider_metadata}
        tools = self._tool_schema_builder.build(
            request.available_tools,
            ModelToolSchemaExposurePolicy(strict=request.strict_tool_schema),
        )
        if tools:
            body["tools"] = tools
            if request.tool_choice is not None:
                body["tool_choice"] = request.tool_choice
        headers = {
            "Content-Type": "application/json",
            "Accept": "application/json",
        }
        if self._settings.api_key:
            headers["Authorization"] = f"Bearer {self._settings.api_key}"
        if self._settings.organization:
            headers["OpenAI-Organization"] = self._settings.organization
        if request.trace_id:
            headers["X-DataSmart-Trace-Id"] = request.trace_id
        self._apply_datasmart_metadata_headers(headers, request.provider_metadata)
        headers.update(self._settings.extra_headers or {})
        return Request(
            url=self._chat_completions_url(request.route.endpoint or ""),
            data=json.dumps(body).encode("utf-8"),
            headers=headers,
            method="POST",
        )

    @staticmethod
    def _apply_datasmart_metadata_headers(headers: dict[str, str], metadata: dict) -> None:
        """把 DataSmart 治理 metadata 中最关键的字段同步为 HTTP Header。

        为什么既写请求体 metadata，又写 Header：
        - 一些 OpenAI-compatible 推理服务会忽略未知 body 字段，但企业网关/Nginx/Envoy/LiteLLM middleware
          可以更容易读取 Header；
        - Header 不承载完整策略，只放少量非敏感路由标签，便于网关在不解析 JSON body 的情况下执行
          prefix/KV cache、限流或审计；
        - 所有值都来自白名单字段，不包含 prompt、tool result、SQL、样本数据或密钥。
        """

        if not metadata:
            return
        cache_plan = metadata.get("cachePlan")
        if isinstance(cache_plan, dict):
            headers["X-DataSmart-Cache-Enabled"] = str(bool(cache_plan.get("enabled"))).lower()
            if cache_plan.get("scope") is not None:
                headers["X-DataSmart-Cache-Scope"] = str(cache_plan.get("scope"))
            if cache_plan.get("namespace") is not None:
                headers["X-DataSmart-Cache-Namespace"] = str(cache_plan.get("namespace"))
            if cache_plan.get("keyPrefix") is not None:
                headers["X-DataSmart-Cache-Key-Prefix"] = str(cache_plan.get("keyPrefix"))
            if cache_plan.get("ttlSeconds") is not None:
                headers["X-DataSmart-Cache-Ttl-Seconds"] = str(cache_plan.get("ttlSeconds"))
        if metadata.get("tenantId") is not None:
            headers["X-DataSmart-Tenant-Id"] = str(metadata.get("tenantId"))
        if metadata.get("projectId") is not None:
            headers["X-DataSmart-Project-Id"] = str(metadata.get("projectId"))

    @staticmethod
    def _message_to_payload(message) -> dict:
        """把统一 `ModelMessage` 转换为 OpenAI-compatible message。

        普通 system/user/assistant 文本消息只需要 role/content；工具结果回填消息还必须携带
        `tool_call_id`。如果要让模型继续处理上一轮工具调用，assistant 消息也要能带回 `tool_calls`。
        这里集中转换，可以避免 Provider 其他位置理解 DataSmart 的领域对象结构。
        """

        payload = {"role": message.role, "content": message.content}
        if message.name:
            payload["name"] = message.name
        if message.tool_call_id:
            payload["tool_call_id"] = message.tool_call_id
        if message.tool_calls:
            payload["tool_calls"] = tuple(OpenAICompatibleModelProvider._tool_call_to_payload(item) for item in message.tool_calls)
        return payload

    @staticmethod
    def _tool_call_to_payload(tool_call: ModelToolCall) -> dict:
        """把 DataSmart `ModelToolCall` 还原为 assistant message 中的 tool_call 片段。

        该 payload 用于“下一轮模型调用的历史消息”，不是新请求中的 tools schema。它告诉模型：
        上一轮 assistant 曾提出过某个工具调用，随后 role=tool 的消息会用同一个 id 返回结果。
        """

        raw_function = tool_call.raw_call.get("function") if isinstance(tool_call.raw_call, dict) else None
        raw_custom = tool_call.raw_call.get("custom") if isinstance(tool_call.raw_call, dict) else None
        model_visible_name = (
            raw_function.get("name")
            if isinstance(raw_function, dict)
            else raw_custom.get("name")
            if isinstance(raw_custom, dict)
            else None
        )
        return {
            "id": tool_call.call_id,
            "type": tool_call.type,
            "function": {
                "name": model_visible_name or tool_call.name,
                "arguments": tool_call.arguments,
            },
        }

    def _send(self, http_request: Request, timeout_seconds: int) -> dict:
        """发送 HTTP 请求并解析 JSON 响应。"""

        with self._transport(http_request, timeout=timeout_seconds) as response:  # noqa: S310 - endpoint 来自受控模型路由配置
            return json.loads(response.read().decode("utf-8"))

    def _to_result(self, request: ModelInvocationRequest, payload: dict, latency_ms: int) -> ModelInvocationResult:
        """把 OpenAI-compatible 非流式响应转换为统一调用结果。

        `message.tool_calls` 是模型“希望调用工具”的结构化意图。Provider 只解析为 `ModelToolCall`：
        不校验参数 JSON 是否完整、不直接执行工具、不做权限判断。这样后续可以在 Agent loop 中统一执行
        参数校验、审批、工具沙箱、审计落库和错误恢复。
        """

        choice = (payload.get("choices") or [{}])[0]
        message = choice.get("message") or {}
        usage = payload.get("usage") or {}
        name_aliases = self._tool_schema_builder.build_name_aliases(request.available_tools)
        tool_calls = tuple(
            self._to_tool_call(item, name_aliases) for item in message.get("tool_calls") or () if isinstance(item, dict)
        )
        return ModelInvocationResult(
            provider_name=request.route.provider_name,
            model_name=request.route.model_name,
            content=message.get("content") or "",
            latency_ms=latency_ms,
            prompt_tokens=usage.get("prompt_tokens"),
            completion_tokens=usage.get("completion_tokens"),
            tool_calls=tool_calls,
        )

    def _to_chunk(self, request: ModelInvocationRequest, payload: dict, sequence: int) -> ModelInvocationChunk:
        """把一条 SSE JSON payload 转换为统一流式 chunk。"""

        choice = (payload.get("choices") or [{}])[0]
        delta = choice.get("delta") or {}
        name_aliases = self._tool_schema_builder.build_name_aliases(request.available_tools)
        tool_call_deltas = tuple(
            self._to_tool_call_delta(item, name_aliases) for item in delta.get("tool_calls") or () if isinstance(item, dict)
        )
        return ModelInvocationChunk(
            provider_name=request.route.provider_name,
            model_name=request.route.model_name,
            content_delta=delta.get("content") or "",
            finish_reason=choice.get("finish_reason"),
            sequence=sequence,
            tool_call_deltas=tool_call_deltas,
            raw_event=payload,
        )

    @staticmethod
    def _to_tool_call(item: dict, name_aliases: dict[str, str]) -> ModelToolCall:
        """解析非流式完整工具调用。

        OpenAI Chat Completions 主流形式是 `type=function`，字段为 `function.name` 与
        `function.arguments`。官方文档也已经出现 custom tool call 形态，因此这里做轻量兼容：
        - function tool：arguments 来自 `function.arguments`；
        - custom tool：arguments 来自 `custom.input`；
        - 未知类型：尽量保留 name/arguments 为空，并把原始结构放进 `raw_call` 供诊断。
        """

        tool_type = str(item.get("type") or "function")
        function = item.get("function") or {}
        custom = item.get("custom") or {}
        model_name = function.get("name") or custom.get("name") or ""
        return ModelToolCall(
            call_id=item.get("id"),
            type=tool_type,
            name=name_aliases.get(model_name, model_name),
            arguments=function.get("arguments") or custom.get("input") or "",
            raw_call=dict(item),
        )

    @staticmethod
    def _to_tool_call_delta(item: dict, name_aliases: dict[str, str]) -> ModelToolCallDelta:
        """解析流式工具调用增量。

        streaming tool call 的参数通常会被拆成很多很小的字符串片段。`index` 是聚合同一工具调用的关键，
        `id/type/name` 往往只在第一个 delta 出现，后续片段可能只有 `function.arguments`。因此本契约
        使用 `*_delta` 字段提醒调用方：这里是增量，不是最终完整参数。
        """

        function = item.get("function") or {}
        custom = item.get("custom") or {}
        model_name = function.get("name") or custom.get("name") or ""
        return ModelToolCallDelta(
            index=_safe_int(item.get("index"), default=0),
            call_id=item.get("id"),
            type=item.get("type"),
            name_delta=name_aliases.get(model_name, model_name),
            arguments_delta=function.get("arguments") or custom.get("input") or "",
            raw_delta=dict(item),
        )

    @staticmethod
    def _error_chunk(
        request: ModelInvocationRequest,
        error_code: str,
        message: str,
        sequence: int,
    ) -> ModelInvocationChunk:
        """把流式调用错误转换成 chunk，避免上层 Agent loop 因异常中断。"""

        return ModelInvocationChunk(
            provider_name=request.route.provider_name,
            model_name=request.route.model_name,
            content_delta=f"[MODEL_PROVIDER_STREAM_ERROR] {message}",
            sequence=sequence,
            error_code=error_code,
        )

    @staticmethod
    def _iter_sse_payloads(response) -> Iterator[str]:
        """从 SSE 响应中提取 data payload。

        标准 SSE 可能包含空行、注释行、event/id/retry 字段。模型网关最关键的是 `data:` 行，所以
        这里保持最小解析，既兼容 OpenAI-compatible 服务，也避免为了一个 streaming 能力引入额外依赖。
        """

        for raw_line in response:
            line = raw_line.decode("utf-8").strip() if isinstance(raw_line, bytes) else str(raw_line).strip()
            if not line or line.startswith(":"):
                continue
            if not line.startswith("data:"):
                continue
            yield line.removeprefix("data:").strip()

    def _should_retry(self, status_code: int | None, attempt: int, attempts: int) -> bool:
        """判断本次失败是否应该重试。"""

        if attempt >= attempts:
            return False
        if status_code is None:
            return True
        try:
            return HTTPStatus(status_code) in self._TRANSIENT_STATUS_CODES
        except ValueError:
            return False

    @staticmethod
    def _read_http_error(exc: HTTPError) -> str:
        """读取 HTTPError body，失败时返回状态文本。"""

        try:
            body = exc.read().decode("utf-8")
            if body:
                return body
        except Exception:
            pass
        return f"HTTP {exc.code} {exc.reason}"

    @staticmethod
    def _chat_completions_url(endpoint: str) -> str:
        """把 base URL 或完整 endpoint 规范化为 chat completions 地址。

        部署 vLLM/SGLang/LiteLLM 时，用户常见配置有三种：
        - `http://host:8000`
        - `http://host:8000/v1`
        - `http://host:8000/v1/chat/completions`
        这里统一兼容，避免配置层因为少写路径导致真实模型调用失败。
        """

        normalized = endpoint.rstrip("/")
        if normalized.endswith("/chat/completions"):
            return normalized
        if normalized.endswith("/v1"):
            return f"{normalized}/chat/completions"
        return f"{normalized}/v1/chat/completions"


def _safe_int(value: object, default: int) -> int:
    """把外部协议中的数字字段安全转换为 int。

    OpenAI-compatible 服务来自不同实现，`index` 理论上是数字，但某些网关可能把它序列化成字符串。
    这里做宽松转换，避免单个异常字段让整个 streaming 中断。
    """

    try:
        return int(value)
    except (TypeError, ValueError):
        return default
