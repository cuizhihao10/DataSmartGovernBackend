import os
import sys
import unittest
import json
from urllib.error import HTTPError

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.config import default_model_routes, model_routes_from_env
from datasmart_ai_runtime.domain.contracts import (
    ModelRoute,
    ModelInvocationRequest,
    ModelMessage,
    ProviderType,
    WorkloadType,
)
from datasmart_ai_runtime.services.model_gateway.model_provider import (
    ModelProviderRegistry,
    OpenAICompatibleModelProvider,
    OpenAICompatibleProviderSettings,
    model_provider_registry_from_env,
)
from datasmart_ai_runtime.services.model_gateway.model_router import ModelRouteRegistry


class ModelProviderRegistryTest(unittest.TestCase):
    def test_dry_run_provider_returns_explainable_result(self) -> None:
        routes = ModelRouteRegistry(default_model_routes())
        route = routes.route_for(WorkloadType.AGENT_REASONING)

        result = ModelProviderRegistry().invoke(
            ModelInvocationRequest(
                route=route,
                messages=(ModelMessage(role="user", content="帮我规划一个数据质量检查任务"),),
                trace_id="trace-001",
            )
        )

        self.assertEqual(ProviderType.DRY_RUN, route.provider_type)
        self.assertIn("DRY_RUN", result.content)
        self.assertIn(route.model_name, result.content)
        self.assertIsNone(result.error_code)

    def test_openai_compatible_provider_sends_chat_completion_request_with_auth(self) -> None:
        """OpenAI-compatible Provider 应能真正构造 Chat Completions 请求。

        这条测试不依赖真实模型服务，而是用 fake transport 验证请求形状。它保护的是未来接 vLLM、
        SGLang、LiteLLM 或企业内部模型网关时最关键的协议边界：URL、Authorization、traceId、
        messages、temperature、max_tokens 和 usage 解析。
        """

        captured: dict[str, object] = {}

        def transport(request, timeout: int):
            captured["url"] = request.full_url
            captured["timeout"] = timeout
            captured["headers"] = dict(request.header_items())
            captured["body"] = request.data.decode("utf-8")
            return FakeHttpResponse(
                {
                    "choices": [{"message": {"content": "模型已经完成治理任务规划。"}}],
                    "usage": {"prompt_tokens": 11, "completion_tokens": 7},
                }
            )

        provider = OpenAICompatibleModelProvider(
            OpenAICompatibleProviderSettings(api_key="sk-test", max_retries=0),
            transport=transport,
        )
        route = _openai_route(endpoint="http://model-gateway.local/v1")

        result = provider.invoke(
            ModelInvocationRequest(
                route=route,
                messages=(
                    ModelMessage(role="system", content="你是治理 Agent。"),
                    ModelMessage(role="user", content="帮我生成质量规则。"),
                ),
                temperature=0.1,
                max_output_tokens=512,
                trace_id="trace-model-001",
                provider_metadata={
                    "tenantId": "tenant-a",
                    "projectId": "project-a",
                    "cachePlan": {
                        "enabled": True,
                        "scope": "session_only",
                        "namespace": "model-cache:session_only:tenant:tenant-a:project:project-a:session:s-001",
                        "keyPrefix": "model-cache:session_only:tenant:tenant-a:project:project-a:session:s-001:route:p:m",
                        "ttlSeconds": 1800,
                    },
                },
            )
        )

        self.assertEqual("http://model-gateway.local/v1/chat/completions", captured["url"])
        self.assertEqual(30, captured["timeout"])
        headers = {str(key).lower(): value for key, value in captured["headers"].items()}
        self.assertEqual("Bearer sk-test", headers["authorization"])
        self.assertEqual("trace-model-001", headers["x-datasmart-trace-id"])
        self.assertEqual("true", headers["x-datasmart-cache-enabled"])
        self.assertEqual("session_only", headers["x-datasmart-cache-scope"])
        self.assertEqual("1800", headers["x-datasmart-cache-ttl-seconds"])
        self.assertEqual("tenant-a", headers["x-datasmart-tenant-id"])
        self.assertIn("\"max_tokens\": 512", captured["body"])
        body = json.loads(captured["body"])
        self.assertEqual("tenant-a", body["metadata"]["datasmart"]["tenantId"])
        self.assertTrue(body["metadata"]["datasmart"]["cachePlan"]["enabled"])
        self.assertEqual("模型已经完成治理任务规划。", result.content)
        self.assertEqual(11, result.prompt_tokens)
        self.assertEqual(7, result.completion_tokens)

    def test_openai_compatible_provider_retries_transient_http_error(self) -> None:
        """模型服务短暂 503 时应重试，避免一次抖动直接让 Agent 降级。"""

        calls = 0

        def transport(request, timeout: int):
            nonlocal calls
            calls += 1
            if calls == 1:
                raise HTTPError(
                    url=request.full_url,
                    code=503,
                    msg="service unavailable",
                    hdrs=None,
                    fp=FakeErrorBody("temporary unavailable"),
                )
            return FakeHttpResponse({"choices": [{"message": {"content": "retry ok"}}], "usage": {}})

        provider = OpenAICompatibleModelProvider(
            OpenAICompatibleProviderSettings(max_retries=1, retry_backoff_seconds=0),
            transport=transport,
        )

        result = provider.invoke(
            ModelInvocationRequest(
                route=_openai_route(endpoint="http://model-gateway.local"),
                messages=(ModelMessage(role="user", content="hello"),),
            )
        )

        self.assertEqual(2, calls)
        self.assertEqual("retry ok", result.content)
        self.assertIsNone(result.error_code)

    def test_openai_compatible_provider_streams_sse_chunks(self) -> None:
        """OpenAI-compatible Provider 应能解析 SSE token 流。

        这是 Codex/Claude Code 类 Agent 体验的基础：上层不必等待完整回答，可以边接收模型 delta，
        边更新前端状态或后续 Agent loop。
        """

        captured: dict[str, object] = {}

        def transport(request, timeout: int):
            captured["body"] = request.data.decode("utf-8")
            return FakeHttpResponse(
                lines=(
                    b"data: {\"choices\":[{\"delta\":{\"content\":\"A\"},\"finish_reason\":null}]}\n",
                    b"data: {\"choices\":[{\"delta\":{\"content\":\"B\"},\"finish_reason\":null}]}\n",
                    b"data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n",
                    b"data: [DONE]\n",
                )
            )

        provider = OpenAICompatibleModelProvider(
            OpenAICompatibleProviderSettings(max_retries=0),
            transport=transport,
        )

        chunks = tuple(
            provider.stream(
                ModelInvocationRequest(
                    route=_openai_route(endpoint="http://model-gateway.local/v1"),
                    messages=(ModelMessage(role="user", content="hello"),),
                )
            )
        )

        self.assertIn("\"stream\": true", captured["body"])
        self.assertEqual(("A", "B", ""), tuple(chunk.content_delta for chunk in chunks))
        self.assertEqual((1, 2, 3), tuple(chunk.sequence for chunk in chunks))
        self.assertEqual("stop", chunks[-1].finish_reason)
        self.assertIsNone(chunks[-1].error_code)

    def test_provider_registry_stream_falls_back_to_single_chunk_for_dry_run(self) -> None:
        """即使 Provider 不是真实流式，上层也可以统一消费 Registry.stream。"""

        routes = ModelRouteRegistry(default_model_routes())
        route = routes.route_for(WorkloadType.AGENT_REASONING)

        chunks = tuple(
            ModelProviderRegistry().stream(
                ModelInvocationRequest(
                    route=route,
                    messages=(ModelMessage(role="user", content="帮我规划质量规则"),),
                )
            )
        )

        self.assertEqual(1, len(chunks))
        self.assertIn("DRY_RUN", chunks[0].content_delta)
        self.assertEqual("dry_run", chunks[0].finish_reason)

    def test_openai_compatible_stream_returns_error_chunk_for_malformed_sse(self) -> None:
        """流式响应格式异常时应返回错误 chunk，而不是抛异常打断 Agent loop。"""

        provider = OpenAICompatibleModelProvider(
            OpenAICompatibleProviderSettings(max_retries=0),
            transport=lambda request, timeout: FakeHttpResponse(lines=(b"data: {not-json}\n",)),
        )

        chunks = tuple(
            provider.stream(
                ModelInvocationRequest(
                    route=_openai_route(endpoint="http://model-gateway.local/v1"),
                    messages=(ModelMessage(role="user", content="hello"),),
                )
            )
        )

        self.assertEqual(1, len(chunks))
        self.assertEqual("MODEL_PROVIDER_STREAM_MALFORMED", chunks[0].error_code)

    def test_openai_compatible_provider_returns_low_sensitive_error_result_after_non_retryable_error(self) -> None:
        """不可重试错误应归一化为低敏 ModelInvocationResult。

        上游 OpenAI-compatible 网关的错误 body 可能包含 API Key、内部 endpoint、prompt、SQL、工具参数
        或供应商私有调试信息。模型 Provider 可以保留稳定 error_code，供预算、健康回写和 fallback 使用；
        但不能把原始错误正文塞进 `content`，否则前端实时输出、runtime event 或 Java 控制面都会成为新的
        敏感信息扩散面。
        """

        def transport(request, timeout: int):
            raise HTTPError(
                url=request.full_url,
                code=401,
                msg="unauthorized",
                hdrs=None,
                fp=FakeErrorBody(
                    "invalid api key sk-secret; http://internal-model-gateway/v1; "
                    "SELECT * FROM sensitive_table"
                ),
            )

        provider = OpenAICompatibleModelProvider(
            OpenAICompatibleProviderSettings(max_retries=1, retry_backoff_seconds=0),
            transport=transport,
        )

        result = provider.invoke(
            ModelInvocationRequest(
                route=_openai_route(endpoint="http://model-gateway.local/v1/chat/completions"),
                messages=(ModelMessage(role="user", content="hello"),),
            )
        )

        self.assertEqual("MODEL_PROVIDER_HTTP_401", result.error_code)
        self.assertIn("状态码 401", result.content)
        self.assertIn("原始响应正文已按低敏策略隐藏", result.content)
        self.assertNotIn("sk-secret", result.content)
        self.assertNotIn("internal-model-gateway", result.content)
        self.assertNotIn("sensitive_table", result.content)

    def test_openai_compatible_stream_error_chunk_hides_sensitive_provider_body(self) -> None:
        """流式 HTTP 错误 chunk 也不能回显 Provider 原始错误正文。"""

        def transport(request, timeout: int):
            raise HTTPError(
                url=request.full_url,
                code=503,
                msg="service unavailable",
                hdrs=None,
                fp=FakeErrorBody("upstream prompt=secret toolArguments={'sql':'select * from raw_table'}"),
            )

        provider = OpenAICompatibleModelProvider(
            OpenAICompatibleProviderSettings(max_retries=0),
            transport=transport,
        )

        chunks = tuple(
            provider.stream(
                ModelInvocationRequest(
                    route=_openai_route(endpoint="http://model-gateway.local/v1"),
                    messages=(ModelMessage(role="user", content="hello"),),
                )
            )
        )

        self.assertEqual(1, len(chunks))
        self.assertEqual("MODEL_PROVIDER_HTTP_503", chunks[0].error_code)
        self.assertIn("状态码 503", chunks[0].content_delta)
        self.assertNotIn("prompt=secret", chunks[0].content_delta)
        self.assertNotIn("toolArguments", chunks[0].content_delta)
        self.assertNotIn("raw_table", chunks[0].content_delta)

    def test_model_provider_registry_from_env_injects_openai_settings(self) -> None:
        registry = model_provider_registry_from_env(
            {
                "DATASMART_AI_OPENAI_COMPATIBLE_API_KEY": "sk-env",
                "DATASMART_AI_OPENAI_COMPATIBLE_MAX_RETRIES": "0",
            }
        )

        self.assertIsInstance(registry, ModelProviderRegistry)

    def test_model_routes_from_env_switches_agent_reasoning_to_openai_compatible(self) -> None:
        routes = model_routes_from_env(
            {
                "DATASMART_AI_OPENAI_COMPATIBLE_BASE_URL": "http://model-gateway.local/v1",
                "DATASMART_AI_AGENT_REASONING_MODEL": "agent-model",
                "DATASMART_AI_AGENT_REASONING_TIMEOUT_SECONDS": "45",
            }
        )

        agent_route = next(route for route in routes if route.workload == WorkloadType.AGENT_REASONING)
        self.assertEqual(ProviderType.OPENAI_COMPATIBLE, agent_route.provider_type)
        self.assertEqual("agent-model", agent_route.model_name)
        self.assertEqual("http://model-gateway.local/v1", agent_route.endpoint)
        self.assertEqual(45, agent_route.timeout_seconds)


class FakeHttpResponse:
    def __init__(self, payload: dict | None = None, lines: tuple[bytes, ...] = ()) -> None:
        self._payload = payload
        self._lines = lines

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, traceback) -> None:
        return None

    def read(self) -> bytes:
        import json

        return json.dumps(self._payload or {}).encode("utf-8")

    def __iter__(self):
        return iter(self._lines)


class FakeErrorBody:
    def __init__(self, body: str) -> None:
        self._body = body

    def read(self) -> bytes:
        return self._body.encode("utf-8")


def _openai_route(endpoint: str) -> ModelRoute:
    return ModelRoute(
        workload=WorkloadType.AGENT_REASONING,
        provider_name="openai-compatible-test",
        provider_type=ProviderType.OPENAI_COMPATIBLE,
        model_name="agent-test-model",
        endpoint=endpoint,
        timeout_seconds=30,
    )


if __name__ == "__main__":
    unittest.main()
