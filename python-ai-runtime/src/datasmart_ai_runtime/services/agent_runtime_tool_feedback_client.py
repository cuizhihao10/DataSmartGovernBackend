"""Java Agent Runtime 工具执行结果反馈客户端。

DataSmart 的 Agent 工具闭环采用“Python 编排 + Java 控制面”的边界：
- Python AI Runtime 负责模型调用、tool_calls 治理、工具结果消息回填和二轮推理；
- Java agent-runtime 负责工具审计、审批、幂等、真实执行和结构化输出保存。

因此，Python 要进入真正可商用的多步 Agent loop，不能长期依赖本地模拟反馈。这个文件提供
Java 结果查询客户端与 Provider 适配器，让 Python 可以按 sessionId/runId/auditId 查询 Java
控制面已经固化的工具执行事实，再转换为 `ToolExecutionFeedback` 回填给模型。
"""

from __future__ import annotations

import json
from typing import Any, Protocol
from urllib.parse import quote
from urllib.request import Request, urlopen

from datasmart_ai_runtime.domain.contracts import ModelToolCall, ToolPlan
from datasmart_ai_runtime.services.model_tool_feedback_provider import (
    ModelToolExecutionFeedbackProvider,
    SimulatedModelToolExecutionFeedbackProvider,
)
from datasmart_ai_runtime.services.model_tool_result_feedback import (
    ToolExecutionFeedback,
    ToolExecutionFeedbackStatus,
)


class AgentRuntimeToolFeedbackClientError(RuntimeError):
    """Java Agent Runtime 工具反馈客户端错误。

    单独定义异常类型，是为了让 Provider 能区分“Java 控制面暂时不可用”和“模型工具治理失败”。
    前者可以回退模拟或等待下一次轮询，后者通常说明 Agent loop 自身存在契约问题。
    """


class AgentRuntimeToolFeedbackClient(Protocol):
    """工具执行结果查询客户端协议。

    该协议让 Provider 不直接绑定 `urllib` 实现。单元测试可以注入内存 fake，未来生产可以替换为：
    - 带鉴权和 mTLS 的企业 HTTP client；
    - Kafka request/reply；
    - gRPC；
    - 或直接消费 agent-runtime 的工具结果事件流。
    """

    def get_tool_execution_feedback(
        self,
        *,
        session_id: str,
        run_id: str,
        audit_id: str,
        tool_call_id: str,
        trace_id: str | None = None,
    ) -> ToolExecutionFeedback:
        """按 Java 控制面三元组查询工具执行反馈。"""


class JavaAgentRuntimeToolFeedbackClient:
    """Java agent-runtime 工具执行结果查询客户端。

    当前使用标准库 `urllib`，保持 Python Runtime 轻量。客户端调用的 Java 路由为：
    `/agent-runtime/sessions/{sessionId}/runs/{runId}/tool-executions/{auditId}/result`。

    返回值映射原则：
    - `SUCCEEDED`：允许把 Java 返回的结构化 output 作为 safe result 回填模型；
    - `FAILED`：只回填错误码和错误摘要，避免模型假设工具成功；
    - `WAITING_APPROVAL`：告诉模型该工具仍需人工确认；
    - `SKIPPED/PLANNED/EXECUTING`：不伪造成成功，而是用 SKIPPED 表达“当前没有可用结果”。

    这个设计与 OpenAI-compatible tool result message 和 MCP tool result 的共同方向一致：
    工具结果可以是结构化 JSON，但业务错误应作为工具结果语义返回，而不是让模型把协议异常当作成功输出。
    """

    def __init__(
        self,
        base_url: str,
        timeout_seconds: int = 3,
        result_path_template: str = "/agent-runtime/sessions/{sessionId}/runs/{runId}/tool-executions/{auditId}/result",
    ) -> None:
        self._base_url = base_url.rstrip("/")
        self._timeout_seconds = timeout_seconds
        self._result_path_template = result_path_template

    def get_tool_execution_feedback(
        self,
        *,
        session_id: str,
        run_id: str,
        audit_id: str,
        tool_call_id: str,
        trace_id: str | None = None,
    ) -> ToolExecutionFeedback:
        """查询 Java 控制面的工具结果并转换为模型可消费反馈。"""

        url = self._build_result_url(session_id=session_id, run_id=run_id, audit_id=audit_id)
        headers = {
            "Accept": "application/json",
            # 与 Java `PlatformContextHeaders.TRACE_ID` 对齐，便于排查 Python -> Java -> 下游工具链路。
            "X-DataSmart-Trace-Id": trace_id or "",
            "X-DataSmart-Source-Service": "python-ai-runtime",
        }
        request = Request(url=url, headers={k: v for k, v in headers.items() if v}, method="GET")
        try:
            with urlopen(request, timeout=self._timeout_seconds) as response:  # noqa: S310 - URL 来自受控配置
                payload = json.loads(response.read().decode("utf-8"))
        except Exception as exc:  # pragma: no cover - 网络错误在集成环境覆盖
            raise AgentRuntimeToolFeedbackClientError(f"查询 Java Agent 工具执行结果失败：{exc}") from exc
        return self.parse_platform_response(payload, tool_call_id=tool_call_id)

    def _build_result_url(self, *, session_id: str, run_id: str, audit_id: str) -> str:
        """构建结果查询 URL，并对路径参数做安全转义。"""

        path = self._result_path_template.format(
            sessionId=quote(session_id, safe=""),
            runId=quote(run_id, safe=""),
            auditId=quote(audit_id, safe=""),
        )
        return f"{self._base_url}{path}"

    @classmethod
    def parse_platform_response(cls, payload: dict[str, Any], *, tool_call_id: str) -> ToolExecutionFeedback:
        """解析 Java `PlatformApiResponse<AgentToolExecutionResultView>`。

        该方法单独暴露给单元测试，确保 Python/Java 契约字段变更时能尽早失败。
        """

        if payload.get("code") != 0:
            reason = payload.get("reason", "UNKNOWN")
            message = payload.get("message", "Java 工具结果接口返回失败")
            raise AgentRuntimeToolFeedbackClientError(f"{reason}: {message}")
        data = payload.get("data")
        if not isinstance(data, dict):
            raise AgentRuntimeToolFeedbackClientError("Java 工具结果响应 data 必须是对象")
        return cls._map_result(data, tool_call_id=tool_call_id)

    @classmethod
    def _map_result(cls, data: dict[str, Any], *, tool_call_id: str) -> ToolExecutionFeedback:
        """把 Java 执行结果视图映射为模型工具反馈。"""

        audit = data.get("audit") or {}
        if not isinstance(audit, dict):
            raise AgentRuntimeToolFeedbackClientError("Java 工具结果响应 audit 必须是对象")
        output = data.get("output") or {}
        if not isinstance(output, dict):
            output = {}

        state = str(audit.get("state") or "").upper()
        status = cls._map_status(state)
        tool_name = str(audit.get("toolCode") or "")
        audit_id = str(audit.get("auditId") or "")
        run_id = str(audit.get("runId") or "")
        summary = cls._build_summary(audit=audit, state=state)
        error_code = audit.get("errorCode")
        output_ref = cls._build_output_ref(audit)
        sensitive_fields = cls._sensitive_fields_from_audit(audit)
        result = output if status == ToolExecutionFeedbackStatus.SUCCEEDED else cls._non_success_result(audit, state)
        return ToolExecutionFeedback(
            tool_call_id=tool_call_id,
            tool_name=tool_name,
            status=status,
            summary=summary,
            result=result,
            audit_id=audit_id or None,
            run_id=run_id or None,
            output_ref=output_ref,
            error_code=str(error_code) if error_code else None,
            error_message=str(audit.get("message") or "") if status == ToolExecutionFeedbackStatus.FAILED else None,
            sensitive_fields=sensitive_fields,
        )

    @staticmethod
    def _map_status(state: str) -> ToolExecutionFeedbackStatus:
        """把 Java 审计状态转换为模型反馈状态。"""

        if state == "SUCCEEDED":
            return ToolExecutionFeedbackStatus.SUCCEEDED
        if state == "FAILED":
            return ToolExecutionFeedbackStatus.FAILED
        if state == "WAITING_APPROVAL":
            return ToolExecutionFeedbackStatus.WAITING_APPROVAL
        if state in {"SKIPPED", "REJECTED"}:
            return ToolExecutionFeedbackStatus.SKIPPED
        return ToolExecutionFeedbackStatus.SKIPPED

    @staticmethod
    def _build_summary(*, audit: dict[str, Any], state: str) -> str:
        """生成回填模型的短摘要，避免把完整 Java DTO 直接塞给模型。"""

        tool_name = audit.get("toolCode") or "unknown-tool"
        message = audit.get("message") or audit.get("outputSummary") or ""
        if state == "SUCCEEDED":
            return audit.get("outputSummary") or f"`{tool_name}` 已由 Java agent-runtime 成功执行。"
        if state == "FAILED":
            return f"`{tool_name}` 执行失败：{message or audit.get('errorCode') or '未提供错误详情'}"
        if state == "WAITING_APPROVAL":
            return f"`{tool_name}` 正在等待人工审批，尚未执行真实业务动作。"
        return f"`{tool_name}` 当前状态为 {state or 'UNKNOWN'}，暂无可回填的成功结果。"

    @staticmethod
    def _build_output_ref(audit: dict[str, Any]) -> str | None:
        """构造稳定输出引用，供模型和审计系统知道结果来自哪里。"""

        session_id = audit.get("sessionId")
        run_id = audit.get("runId")
        audit_id = audit.get("auditId")
        if not session_id or not run_id or not audit_id:
            return None
        return f"agent-runtime://sessions/{session_id}/runs/{run_id}/tool-executions/{audit_id}/result"

    @staticmethod
    def _sensitive_fields_from_audit(audit: dict[str, Any]) -> tuple[str, ...]:
        """从 Java governanceHints 中读取敏感字段声明。"""

        hints = audit.get("governanceHints") or {}
        if not isinstance(hints, dict):
            return ()
        raw_fields = hints.get("sensitiveFields") or ()
        if isinstance(raw_fields, str):
            return (raw_fields,)
        return tuple(str(item) for item in raw_fields)

    @staticmethod
    def _non_success_result(audit: dict[str, Any], state: str) -> dict[str, Any]:
        """为非成功状态构造安全结果。"""

        return {
            "state": state or "UNKNOWN",
            "errorCode": audit.get("errorCode"),
            "message": audit.get("message"),
            "outputSummary": audit.get("outputSummary"),
        }


class JavaAgentRuntimeToolFeedbackProvider:
    """基于 Java agent-runtime 查询结果的工具反馈 Provider。

    Provider 会尝试从 `ToolPlan.governance_hints` 中读取 Java 控制面引用：
    - `agentRuntimeSessionId` / `javaSessionId` / `sessionId`
    - `agentRuntimeRunId` / `javaRunId` / `runId`
    - `agentRuntimeAuditId` / `javaAuditId` / `auditId`

    如果引用缺失或 Java 查询失败，会回退到模拟 Provider。这样当前 Python 主链仍可运行，而后续当
    Java AgentPlan ingestion 把 auditId 回写到 ToolPlan 或事件中时，可以无缝切换到真实反馈。
    """

    def __init__(
        self,
        client: AgentRuntimeToolFeedbackClient,
        fallback_provider: ModelToolExecutionFeedbackProvider | None = None,
        trace_id: str | None = None,
    ) -> None:
        self._client = client
        self._fallback_provider = fallback_provider or SimulatedModelToolExecutionFeedbackProvider()
        self._trace_id = trace_id

    def feedback_for(
        self,
        tool_calls: tuple[ModelToolCall, ...],
        tool_plans: tuple[ToolPlan, ...],
    ) -> tuple[ToolExecutionFeedback, ...]:
        """优先读取 Java 真实反馈，无法读取时回退模拟反馈。"""

        plan_by_call_id = {
            str(plan.governance_hints.get("modelToolCallId")): plan
            for plan in tool_plans
            if plan.governance_hints.get("modelToolCallId")
        }
        feedback_items: list[ToolExecutionFeedback] = []
        for tool_call in tool_calls:
            if not tool_call.call_id:
                continue
            plan = plan_by_call_id.get(tool_call.call_id)
            if plan is None:
                continue
            feedback_items.append(self._feedback_for_call(tool_call, plan))
        return tuple(feedback_items)

    def _feedback_for_call(self, tool_call: ModelToolCall, plan: ToolPlan) -> ToolExecutionFeedback:
        """读取单个工具调用的 Java 控制面反馈。"""

        refs = self._resolve_refs(plan)
        if refs is None:
            return self._fallback(tool_call, plan)
        try:
            return self._client.get_tool_execution_feedback(
                session_id=refs["session_id"],
                run_id=refs["run_id"],
                audit_id=refs["audit_id"],
                tool_call_id=str(tool_call.call_id),
                trace_id=self._trace_id or self._hint(plan, "traceId", "trace_id"),
            )
        except AgentRuntimeToolFeedbackClientError:
            return self._fallback(tool_call, plan)

    def _fallback(self, tool_call: ModelToolCall, plan: ToolPlan) -> ToolExecutionFeedback:
        """对单个工具调用执行模拟回退，避免一个 Java 查询失败拖垮整轮 Agent loop。"""

        feedback = self._fallback_provider.feedback_for((tool_call,), (plan,))
        if feedback:
            return feedback[0]
        return ToolExecutionFeedback(
            tool_call_id=str(tool_call.call_id),
            tool_name=plan.tool_name,
            status=ToolExecutionFeedbackStatus.SKIPPED,
            summary="未找到可用的 Java 工具执行反馈，也无法生成模拟反馈。",
        )

    def _resolve_refs(self, plan: ToolPlan) -> dict[str, str] | None:
        """从 ToolPlan 治理提示中解析 Java 控制面引用。"""

        session_id = self._hint(plan, "agentRuntimeSessionId", "javaSessionId", "sessionId", "session_id")
        run_id = self._hint(plan, "agentRuntimeRunId", "javaRunId", "runId", "run_id")
        audit_id = self._hint(plan, "agentRuntimeAuditId", "javaAuditId", "auditId", "audit_id")
        if not session_id or not run_id or not audit_id:
            return None
        return {"session_id": session_id, "run_id": run_id, "audit_id": audit_id}

    @staticmethod
    def _hint(plan: ToolPlan, *keys: str) -> str | None:
        """按多个兼容字段名读取治理提示。"""

        for key in keys:
            value = plan.governance_hints.get(key)
            if value is not None and str(value).strip():
                return str(value).strip()
        return None
