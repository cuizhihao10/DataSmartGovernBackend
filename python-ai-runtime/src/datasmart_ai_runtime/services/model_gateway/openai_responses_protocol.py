"""OpenAI-compatible Responses API protocol adapter.

Responses and Chat Completions describe the same model interaction, but their tool
schemas, tool history, and output structures differ. This module only translates
protocol data. It never stores credentials or executes a tool.
"""

from __future__ import annotations

import json
from typing import Any

from datasmart_ai_runtime.domain.contracts import (
    ModelInvocationRequest,
    ModelInvocationResult,
    ModelMessage,
    ModelToolCall,
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
    ) -> dict[str, Any]:
        """Build a non-streaming Responses request body.

        DataSmart admits a tool only after the complete candidate has arrived. The
        provider therefore returns one complete compatibility chunk when callers use
        the unified streaming API, rather than exposing partial JSON to execution.
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
            model_name=request.route.model_name,
            content="".join(text_parts),
            latency_ms=latency_ms,
            prompt_tokens=usage.get("input_tokens"),
            completion_tokens=usage.get("output_tokens"),
            cached_prompt_tokens=input_token_details.get("cached_tokens"),
            tool_calls=tuple(tool_calls),
        )

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
