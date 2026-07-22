import json
import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.domain.contracts import (
    ModelInvocationRequest,
    ModelMessage,
    ModelRoute,
    ModelToolCall,
    ProviderType,
    ToolDefinition,
    ToolExecutionMode,
    ToolRiskLevel,
    WorkloadType,
)
from datasmart_ai_runtime.services.model_gateway.model_provider import (
    OpenAICompatibleModelProvider,
    OpenAICompatibleProviderSettings,
)


class ModelToolSchemaTest(unittest.TestCase):
    def test_openai_compatible_provider_parses_non_stream_tool_calls(self) -> None:
        """非流式响应中的 tool_calls 应转换为统一工具调用意图。

        这条测试保护的是“模型提出动作”与“平台执行动作”的边界：Provider 只解析模型返回的工具调用
        名称与原始参数字符串，不在这里校验 JSON、不执行工具、不绕过 DataSmart 的权限和审批链路。
        """

        def transport(request, timeout: int):
            return FakeHttpResponse(
                {
                    "choices": [
                        {
                            "message": {
                                "content": None,
                                "tool_calls": [
                                    {
                                        "id": "call_quality_001",
                                        "type": "function",
                                        "function": {
                                            "name": "quality_rule_suggest",
                                            "arguments": "{\"datasourceId\":\"ds_1001\",\"tableName\":\"orders\"}",
                                        },
                                    }
                                ],
                            }
                        }
                    ],
                    "usage": {"prompt_tokens": 20, "completion_tokens": 12},
                }
            )

        provider = OpenAICompatibleModelProvider(
            OpenAICompatibleProviderSettings(max_retries=0),
            transport=transport,
        )

        result = provider.invoke(
            ModelInvocationRequest(
                route=_openai_route(endpoint="http://model-gateway.local/v1"),
                messages=(ModelMessage(role="user", content="为订单表生成质量规则"),),
                available_tools=(
                    _tool(
                        name="quality.rule.suggest",
                        description="根据数据源元数据生成质量规则草案。",
                        risk_level=ToolRiskLevel.MEDIUM,
                        execution_mode=ToolExecutionMode.DRAFT_ONLY,
                    ),
                ),
            )
        )

        self.assertEqual("", result.content)
        self.assertEqual(1, len(result.tool_calls))
        self.assertEqual("call_quality_001", result.tool_calls[0].call_id)
        self.assertEqual("function", result.tool_calls[0].type)
        self.assertEqual("quality.rule.suggest", result.tool_calls[0].name)
        self.assertIn("\"datasourceId\":\"ds_1001\"", result.tool_calls[0].arguments)

    def test_openai_compatible_provider_includes_curated_tools_in_request(self) -> None:
        """Provider 应把已裁剪的 DataSmart 工具转换为 OpenAI-compatible tools。

        注意这里测试的是“暴露给模型的工具 schema”，不是工具执行。模型看到 tools 后只能返回
        tool_calls；真正执行仍要经过 Java agent-runtime 的权限、审批、审计和参数校验。
        """

        captured: dict[str, object] = {}

        def transport(request, timeout: int):
            captured["body"] = json.loads(request.data.decode("utf-8"))
            return FakeHttpResponse({"choices": [{"message": {"content": "ok"}}], "usage": {}})

        provider = OpenAICompatibleModelProvider(
            OpenAICompatibleProviderSettings(max_retries=0),
            transport=transport,
        )

        provider.invoke(
            ModelInvocationRequest(
                route=_openai_route(endpoint="http://model-gateway.local/v1"),
                messages=(ModelMessage(role="user", content="为数据源生成质量规则"),),
                available_tools=(
                    _tool(
                        name="quality.rule.suggest",
                        description="根据数据源元数据生成质量规则草案。",
                        risk_level=ToolRiskLevel.MEDIUM,
                        execution_mode=ToolExecutionMode.DRAFT_ONLY,
                        input_schema={
                            "datasourceId": {
                                "type": "string",
                                "required": True,
                                "description": "数据源 ID。",
                                "sensitive": True,
                                "resolution": "must_clarify",
                            },
                            "businessGoal": {
                                "type": "string",
                                "required": False,
                                "description": "业务质量目标。",
                            },
                        },
                    ),
                ),
                strict_tool_schema=True,
            )
        )

        body = captured["body"]
        tools = body["tools"]
        self.assertEqual("auto", body["tool_choice"])
        self.assertEqual(1, len(tools))
        function = tools[0]["function"]
        self.assertEqual("quality_rule_suggest", function["name"])
        self.assertTrue(function["strict"])
        self.assertIn("draft_only", function["description"])
        self.assertEqual(["datasourceId"], function["parameters"]["required"])
        self.assertFalse(function["parameters"]["additionalProperties"])
        self.assertIn("敏感", function["parameters"]["properties"]["datasourceId"]["description"])

    def test_openai_compatible_provider_hides_critical_tools_by_default(self) -> None:
        """CRITICAL 工具默认不应暴露给模型，避免模型直接规划破坏性动作。"""

        captured: dict[str, object] = {}

        def transport(request, timeout: int):
            captured["body"] = json.loads(request.data.decode("utf-8"))
            return FakeHttpResponse({"choices": [{"message": {"content": "ok"}}], "usage": {}})

        provider = OpenAICompatibleModelProvider(
            OpenAICompatibleProviderSettings(max_retries=0),
            transport=transport,
        )

        provider.invoke(
            ModelInvocationRequest(
                route=_openai_route(endpoint="http://model-gateway.local/v1"),
                messages=(ModelMessage(role="user", content="执行危险操作"),),
                available_tools=(
                    _tool(
                        name="system.destructive.operation",
                        description="高危破坏性操作。",
                        risk_level=ToolRiskLevel.CRITICAL,
                        execution_mode=ToolExecutionMode.APPROVAL_REQUIRED,
                    ),
                    _tool(
                        name="datasource.metadata.read",
                        description="读取数据源元数据。",
                        risk_level=ToolRiskLevel.LOW,
                        execution_mode=ToolExecutionMode.SYNC,
                        read_only=True,
                    ),
                ),
            )
        )

        tools = captured["body"]["tools"]
        self.assertEqual(1, len(tools))
        self.assertEqual("datasource_metadata_read", tools[0]["function"]["name"])

    def test_model_tool_schema_hides_system_and_derived_parameters(self) -> None:
        """模型只能看到应由它提供的字段，不能伪造系统范围、证据策略或执行引用。"""

        captured: dict[str, object] = {}

        def transport(request, timeout: int):
            captured["body"] = json.loads(request.data.decode("utf-8"))
            return FakeHttpResponse({"choices": [{"message": {"content": "ok"}}], "usage": {}})

        provider = OpenAICompatibleModelProvider(
            OpenAICompatibleProviderSettings(max_retries=0),
            transport=transport,
        )
        provider.invoke(
            ModelInvocationRequest(
                route=_openai_route(endpoint="http://model-gateway.local/v1"),
                messages=(ModelMessage(role="user", content="诊断任务导入错误"),),
                available_tools=(
                    _tool(
                        name="knowledge.rag.query",
                        description="查询治理知识库。",
                        risk_level=ToolRiskLevel.LOW,
                        execution_mode=ToolExecutionMode.SYNC,
                        input_schema={
                            "queryRef": {"type": "object", "required": True, "resolution": "derived"},
                            "scopePolicy": {"type": "object", "required": True, "resolution": "system_injected"},
                            "userHint": {"type": "string", "required": True, "resolution": "user_required"},
                            "artifactRef": {
                                "type": "string",
                                "required": True,
                                "resolution": "can_fill_from_context",
                            },
                        },
                    ),
                ),
            )
        )

        parameters = captured["body"]["tools"][0]["function"]["parameters"]
        self.assertNotIn("queryRef", parameters["properties"])
        self.assertNotIn("scopePolicy", parameters["properties"])
        self.assertIn("userHint", parameters["properties"])
        self.assertIn("artifactRef", parameters["properties"])
        self.assertEqual(["userHint"], parameters["required"])

    def test_openai_compatible_provider_serializes_tool_result_messages(self) -> None:
        """下一轮模型请求应能携带 assistant tool_calls 与 role=tool 结果消息。

        这条测试保护多步 Agent loop 的关键协议：工具执行结果必须通过 `tool_call_id` 回填给模型，
        而不是作为普通用户文本拼接。否则模型无法可靠知道哪个结果对应哪个工具调用。
        """

        captured: dict[str, object] = {}

        def transport(request, timeout: int):
            captured["body"] = json.loads(request.data.decode("utf-8"))
            return FakeHttpResponse({"choices": [{"message": {"content": "已基于工具结果继续分析。"}}], "usage": {}})

        provider = OpenAICompatibleModelProvider(
            OpenAICompatibleProviderSettings(max_retries=0),
            transport=transport,
        )
        tool_call = ModelToolCall(
            call_id="call_quality_003",
            type="function",
            name="quality.rule.suggest",
            arguments="{\"datasourceId\":\"ds_1003\"}",
            raw_call={
                "id": "call_quality_003",
                "type": "function",
                "function": {
                    "name": "quality_rule_suggest",
                    "arguments": "{\"datasourceId\":\"ds_1003\"}",
                },
            },
        )

        provider.invoke(
            ModelInvocationRequest(
                route=_openai_route(endpoint="http://model-gateway.local/v1"),
                messages=(
                    ModelMessage(role="user", content="请生成质量规则。"),
                    ModelMessage(role="assistant", content="", tool_calls=(tool_call,)),
                    ModelMessage(
                        role="tool",
                        content="{\"status\":\"succeeded\",\"summary\":\"已生成规则草案\"}",
                        tool_call_id="call_quality_003",
                        name="quality.rule.suggest",
                    ),
                ),
            )
        )

        messages = captured["body"]["messages"]
        self.assertEqual("assistant", messages[1]["role"])
        self.assertEqual("call_quality_003", messages[1]["tool_calls"][0]["id"])
        self.assertEqual("quality_rule_suggest", messages[1]["tool_calls"][0]["function"]["name"])
        self.assertEqual("tool", messages[2]["role"])
        self.assertEqual("call_quality_003", messages[2]["tool_call_id"])
        self.assertEqual("quality.rule.suggest", messages[2]["name"])

    def test_openai_compatible_provider_streams_tool_call_deltas(self) -> None:
        """流式响应中的 delta.tool_calls 应保留为可聚合的工具调用增量。

        官方协议里同一个工具调用的参数会按 `index` 分片返回，且 `id/name/type` 可能只在首片段出现。
        因此 Provider 只输出 delta，后续 Agent loop 再负责按 index 拼装、校验参数并进入审批/执行流程。
        """

        def sse(payload: dict) -> bytes:
            return f"data: {json.dumps(payload)}\n".encode("utf-8")

        provider = OpenAICompatibleModelProvider(
            OpenAICompatibleProviderSettings(max_retries=0),
            transport=lambda request, timeout: FakeHttpResponse(
                lines=(
                    sse(
                        {
                            "choices": [
                                {
                                    "delta": {
                                        "tool_calls": [
                                            {
                                                "index": 0,
                                                "id": "call_quality_002",
                                                "type": "function",
                                                "function": {"name": "quality_rule_suggest", "arguments": ""},
                                            }
                                        ]
                                    },
                                    "finish_reason": None,
                                }
                            ]
                        }
                    ),
                    sse(
                        {
                            "choices": [
                                {
                                    "delta": {
                                        "tool_calls": [
                                            {
                                                "index": 0,
                                                "function": {"arguments": "{\"datasourceId\":\"ds_1002\""},
                                            }
                                        ]
                                    },
                                    "finish_reason": None,
                                }
                            ]
                        }
                    ),
                    sse({"choices": [{"delta": {}, "finish_reason": "tool_calls"}]}),
                    b"data: [DONE]\n",
                )
            ),
        )

        chunks = tuple(
            provider.stream(
                ModelInvocationRequest(
                    route=_openai_route(endpoint="http://model-gateway.local/v1"),
                    messages=(ModelMessage(role="user", content="为客户表生成质量规则"),),
                    available_tools=(
                        _tool(
                            name="quality.rule.suggest",
                            description="根据数据源元数据生成质量规则草案。",
                            risk_level=ToolRiskLevel.MEDIUM,
                            execution_mode=ToolExecutionMode.DRAFT_ONLY,
                        ),
                    ),
                )
            )
        )

        self.assertEqual(3, len(chunks))
        self.assertEqual("tool_calls", chunks[-1].finish_reason)
        self.assertEqual(1, len(chunks[0].tool_call_deltas))
        self.assertEqual(0, chunks[0].tool_call_deltas[0].index)
        self.assertEqual("call_quality_002", chunks[0].tool_call_deltas[0].call_id)
        self.assertEqual("function", chunks[0].tool_call_deltas[0].type)
        self.assertEqual("quality.rule.suggest", chunks[0].tool_call_deltas[0].name_delta)
        self.assertEqual("{\"datasourceId\":\"ds_1002\"", chunks[1].tool_call_deltas[0].arguments_delta)


class FakeHttpResponse:
    def __init__(self, payload: dict | None = None, lines: tuple[bytes, ...] = ()) -> None:
        self._payload = payload
        self._lines = lines

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, traceback) -> None:
        return None

    def read(self) -> bytes:
        return json.dumps(self._payload or {}).encode("utf-8")

    def __iter__(self):
        return iter(self._lines)


def _openai_route(endpoint: str) -> ModelRoute:
    return ModelRoute(
        workload=WorkloadType.AGENT_REASONING,
        provider_name="openai-compatible-test",
        provider_type=ProviderType.OPENAI_COMPATIBLE,
        model_name="agent-test-model",
        endpoint=endpoint,
        timeout_seconds=30,
    )


def _tool(
    name: str,
    description: str,
    risk_level: ToolRiskLevel,
    execution_mode: ToolExecutionMode,
    input_schema: dict | None = None,
    read_only: bool = False,
) -> ToolDefinition:
    return ToolDefinition(
        name=name,
        description=description,
        risk_level=risk_level,
        execution_mode=execution_mode,
        input_schema=input_schema or {},
        read_only=read_only,
    )


if __name__ == "__main__":
    unittest.main()
