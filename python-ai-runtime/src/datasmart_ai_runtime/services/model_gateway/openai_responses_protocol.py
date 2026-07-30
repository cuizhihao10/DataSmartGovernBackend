"""OpenAI-compatible Responses API protocol adapter.

Responses and Chat Completions describe the same model interaction, but their tool
schemas, tool history, and output structures differ. This module only translates
protocol data. It never stores credentials or executes a tool.
"""

from __future__ import annotations

import json
from typing import Any

from datasmart_ai_runtime.domain.contracts import (
    ModelInvocationChunk,
    ModelInvocationRequest,
    ModelInvocationResult,
    ModelMessage,
    ModelToolCall,
    ModelToolCallDelta,
)
from datasmart_ai_runtime.services.model_gateway.model_identity import (
    provider_reported_model_name,
)


class OpenAIResponsesProtocolAdapter:
    """Translate between DataSmart model contracts and the Responses API."""

    def build_body(
        self,
        request: ModelInvocationRequest,
        chat_completion_tools: list[dict[str, Any]],
        *,
        reasoning_effort: str | None,
        store_response: bool,
        tool_call_mode: str,
        stream: bool = False,
    ) -> dict[str, Any]:
        """Build a Responses request body for complete or SSE delivery.

        Streaming only changes transport. DataSmart still aggregates every function
        call argument delta and performs schema, permission, approval, and budget
        checks before a tool candidate can enter the Java execution plane.
        """

        input_items = self._messages_to_input(request.messages)
        tools = self._tools_to_responses(chat_completion_tools)
        if tools and tool_call_mode == "json_fallback":
            input_items.insert(
                0,
                {
                    "role": "system",
                    "content": self._json_tool_call_instruction(chat_completion_tools),
                },
            )

        body: dict[str, Any] = {
            "model": request.route.model_name,
            "input": input_items,
            "max_output_tokens": request.max_output_tokens,
            "stream": stream,
            # Commercial governance deployments default to no provider-side response
            # storage. Provider security logs remain governed by its own DPA.
            "store": store_response,
        }
        if reasoning_effort:
            body["reasoning"] = {"effort": reasoning_effort}
        else:
            # Some reasoning models reject temperature when reasoning is explicit.
            body["temperature"] = request.temperature

        if tools:
            if tool_call_mode == "json_fallback":
                body["text"] = {"format": {"type": "json_object"}}
            else:
                body["tools"] = tools
                if request.tool_choice is not None:
                    body["tool_choice"] = self._tool_choice_to_responses(request.tool_choice)
        return body

    def to_result(
        self,
        request: ModelInvocationRequest,
        payload: dict[str, Any],
        latency_ms: int,
        name_aliases: dict[str, str],
    ) -> ModelInvocationResult:
        """Parse message text, function calls, and token usage."""

        text_parts: list[str] = []
        tool_calls: list[ModelToolCall] = []
        for item in payload.get("output") or ():
            if not isinstance(item, dict):
                continue
            item_type = str(item.get("type") or "")
            if item_type == "message":
                for content in item.get("content") or ():
                    if isinstance(content, dict) and content.get("type") == "output_text":
                        text_parts.append(str(content.get("text") or ""))
                continue
            if item_type == "function_call":
                model_name = str(item.get("name") or "")
                tool_calls.append(
                    ModelToolCall(
                        call_id=item.get("call_id") or item.get("id"),
                        type="function",
                        name=name_aliases.get(model_name, model_name),
                        arguments=str(item.get("arguments") or ""),
                        raw_call=dict(item),
                    )
                )

        usage = payload.get("usage") or {}
        input_token_details = usage.get("input_tokens_details") or {}
        return ModelInvocationResult(
            provider_name=request.route.provider_name,
            model_name=provider_reported_model_name(payload, request.route.model_name),
            content="".join(text_parts),
            latency_ms=latency_ms,
            prompt_tokens=usage.get("input_tokens"),
            completion_tokens=usage.get("output_tokens"),
            cached_prompt_tokens=input_token_details.get("cached_tokens"),
            tool_calls=tuple(tool_calls),
        )

    def to_stream_chunk(
        self,
        request: ModelInvocationRequest,
        payload: dict[str, Any],
        sequence: int,
        name_aliases: dict[str, str],
    ) -> ModelInvocationChunk:
        """Translate one Responses SSE event into a governed provider chunk.

        Only ``response.output_text.delta`` is treated as public assistant text.
        Reasoning summary events, encrypted reasoning content, provider diagnostics,
        and raw error bodies are never copied into ``content_delta``. Function call
        fragments remain inert protocol data until the upper-layer aggregator has
        assembled and admitted the complete call.
        """

        event_type = str(payload.get("type") or "")
        response = payload.get("response") if isinstance(payload.get("response"), dict) else {}
        model_name = provider_reported_model_name(response or payload, request.route.model_name)
        usage = response.get("usage") if isinstance(response.get("usage"), dict) else {}
        input_token_details = (
            usage.get("input_tokens_details")
            if isinstance(usage.get("input_tokens_details"), dict)
            else {}
        )
        content_delta = ""
        finish_reason: str | None = None
        error_code: str | None = None
        tool_call_deltas: tuple[ModelToolCallDelta, ...] = ()

        if event_type == "response.output_text.delta":
            content_delta = str(payload.get("delta") or "")
        elif event_type == "response.output_item.added":
            item = payload.get("item") if isinstance(payload.get("item"), dict) else {}
            if item.get("type") == "function_call":
                model_visible_name = str(item.get("name") or "")
                tool_call_deltas = (
                    ModelToolCallDelta(
                        index=self._safe_int(payload.get("output_index")),
                        call_id=item.get("call_id") or item.get("id"),
                        type="function",
                        name_delta=name_aliases.get(model_visible_name, model_visible_name),
                        arguments_delta=str(item.get("arguments") or ""),
                        raw_delta={"eventType": event_type},
                    ),
                )
        elif event_type == "response.function_call_arguments.delta":
            tool_call_deltas = (
                ModelToolCallDelta(
                    index=self._safe_int(payload.get("output_index")),
                    call_id=payload.get("call_id"),
                    type="function",
                    arguments_delta=str(payload.get("delta") or ""),
                    raw_delta={"eventType": event_type},
                ),
            )
        elif event_type == "response.completed":
            finish_reason = "stop"
        elif event_type in {"response.incomplete", "response.cancelled"}:
            finish_reason = "length" if event_type == "response.incomplete" else "cancelled"
        elif event_type in {"response.failed", "error"}:
            error_code = "MODEL_PROVIDER_RESPONSE_FAILED"
            content_delta = "[MODEL_PROVIDER_STREAM_ERROR] 模型 Provider 返回失败状态。"

        return ModelInvocationChunk(
            provider_name=request.route.provider_name,
            model_name=model_name,
            content_delta=content_delta,
            finish_reason=finish_reason,
            sequence=sequence,
            error_code=error_code,
            prompt_tokens=self._optional_int(usage.get("input_tokens")),
            completion_tokens=self._optional_int(usage.get("output_tokens")),
            cached_prompt_tokens=self._optional_int(input_token_details.get("cached_tokens")),
            tool_call_deltas=tool_call_deltas,
            # Do not retain the provider payload. Public text and low-sensitive tool
            # fragments above are the only data this boundary permits downstream.
            raw_event={"type": event_type},
        )

    @staticmethod
    def _safe_int(value: Any) -> int:
        try:
            return int(value)
        except (TypeError, ValueError):
            return 0

    @staticmethod
    def _optional_int(value: Any) -> int | None:
        if value is None:
            return None
        try:
            return int(value)
        except (TypeError, ValueError):
            return None

    @classmethod
    def _messages_to_input(cls, messages: tuple[ModelMessage, ...]) -> list[dict[str, Any]]:
        """Convert messages and tool history to Responses input items.

        Full history is sent by DataSmart, so a second turn remains possible with
        ``store=false`` and does not depend on provider-side response retention.
        """

        items: list[dict[str, Any]] = []
        for message in messages:
            if message.role == "tool":
                if message.tool_call_id:
                    items.append(
                        {
                            "type": "function_call_output",
                            "call_id": message.tool_call_id,
                            "output": message.content,
                        }
                    )
                else:
                    # Never invent a call ID for legacy history. Preserve the content
                    # as an explicitly identified, controlled user-context message.
                    items.append({"role": "user", "content": f"DataSmart controlled tool result: {message.content}"})
                continue

            if message.content:
                items.append({"role": message.role, "content": message.content})
            for tool_call in message.tool_calls:
                items.append(cls._tool_call_to_input(tool_call))
        return items

    @staticmethod
    def _tool_call_to_input(tool_call: ModelToolCall) -> dict[str, Any]:
        """Restore a previous model candidate as a function_call input item."""

        raw_call = tool_call.raw_call if isinstance(tool_call.raw_call, dict) else {}
        raw_function = raw_call.get("function") if isinstance(raw_call.get("function"), dict) else {}
        raw_custom = raw_call.get("custom") if isinstance(raw_call.get("custom"), dict) else {}
        model_visible_name = raw_call.get("name") or raw_function.get("name") or raw_custom.get("name")
        return {
            "type": "function_call",
            "call_id": tool_call.call_id,
            "name": model_visible_name or tool_call.name,
            "arguments": tool_call.arguments,
        }

    @staticmethod
    def _tools_to_responses(chat_completion_tools: list[dict[str, Any]]) -> list[dict[str, Any]]:
        """Flatten Chat Completions function schemas to Responses schemas."""

        converted: list[dict[str, Any]] = []
        for tool in chat_completion_tools:
            function = tool.get("function") if isinstance(tool, dict) else None
            if isinstance(function, dict):
                converted.append({"type": "function", **function})
        return converted

    @staticmethod
    def _tool_choice_to_responses(tool_choice: str | dict[str, Any]) -> str | dict[str, Any]:
        """Support both strategy strings and Chat Completions function objects."""

        if not isinstance(tool_choice, dict):
            return tool_choice
        function = tool_choice.get("function")
        if tool_choice.get("type") == "function" and isinstance(function, dict):
            return {"type": "function", "name": function.get("name")}
        return tool_choice

    @staticmethod
    def _json_tool_call_instruction(tools: list[dict[str, Any]]) -> str:
        """Retain a controlled JSON fallback for non-standard relays."""

        schema = json.dumps(tools, ensure_ascii=False, separators=(",", ":"))
        return (
            "You are DataSmart's governed tool planner. Return only a JSON object shaped as "
            '{"assistantMessage":"safe explanation","toolCalls":[{"name":"exact name","arguments":{}}]}. '
            "Use an empty toolCalls array when parameters are missing. Never invent IDs, SQL, or secrets. "
            f"Allowed tools: {schema}"
        )

    @staticmethod
    def responses_url(endpoint: str) -> str:
        """Normalize a base URL or complete endpoint to ``/responses``."""

        normalized = endpoint.rstrip("/")
        if normalized.endswith("/responses"):
            return normalized
        if normalized.endswith("/chat/completions"):
            return f"{normalized.removesuffix('/chat/completions')}/responses"
        if normalized.endswith("/v1"):
            return f"{normalized}/responses"
        return f"{normalized}/v1/responses"
