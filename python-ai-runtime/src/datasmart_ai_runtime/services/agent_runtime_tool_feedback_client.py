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

from datasmart_ai_runtime.services.model_gateway.model_tool_result_feedback import (
    ToolExecutionFeedback,
    ToolExecutionFeedbackStatus,
)
from datasmart_ai_runtime.services.agent_runtime_tool_execution_contracts import (
    AgentRuntimeToolAutoExecutionSummary,
    AgentRuntimeToolExecutionContractError,
    AgentRuntimeToolExecutionPolicy,
    AgentRuntimeToolExecutionPolicyItem,
    parse_auto_execution_response,
    parse_execution_policy_response,
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
        run_results_path_template: str = "/agent-runtime/sessions/{sessionId}/runs/{runId}/tool-executions/results",
        execution_policy_path_template: str = "/agent-runtime/sessions/{sessionId}/runs/{runId}/tool-executions/execution-policy",
        auto_execute_sync_path_template: str = "/agent-runtime/sessions/{sessionId}/runs/{runId}/tool-executions/auto-execute-sync",
    ) -> None:
        self._base_url = base_url.rstrip("/")
        self._timeout_seconds = timeout_seconds
        self._result_path_template = result_path_template
        self._run_results_path_template = run_results_path_template
        self._execution_policy_path_template = execution_policy_path_template
        self._auto_execute_sync_path_template = auto_execute_sync_path_template

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

    def list_run_tool_execution_feedback(
        self,
        *,
        session_id: str,
        run_id: str,
        tool_call_ids_by_audit_id: dict[str, str],
        trace_id: str | None = None,
    ) -> tuple[ToolExecutionFeedback, ...]:
        """批量查询某个 Java Run 下的工具结果并映射为模型反馈。

        批量接口服务多工具 Agent 的生产性能：一次 Run 可能有多个 tool_call，如果逐个 auditId 查询，
        Python Runtime 会产生 N 次 HTTP 往返。这里按 run 一次性读取全部结果，再用 `auditId -> tool_call_id`
        映射恢复 OpenAI-compatible tool result message 所需的关联键。
        """

        url = self._build_run_results_url(session_id=session_id, run_id=run_id)
        headers = {
            "Accept": "application/json",
            "X-DataSmart-Trace-Id": trace_id or "",
            "X-DataSmart-Source-Service": "python-ai-runtime",
        }
        request = Request(url=url, headers={k: v for k, v in headers.items() if v}, method="GET")
        try:
            with urlopen(request, timeout=self._timeout_seconds) as response:  # noqa: S310 - URL 来自受控配置
                payload = json.loads(response.read().decode("utf-8"))
        except Exception as exc:  # pragma: no cover - 网络错误在集成环境覆盖
            raise AgentRuntimeToolFeedbackClientError(f"批量查询 Java Agent 工具执行结果失败：{exc}") from exc
        return self.parse_platform_batch_response(payload, tool_call_ids_by_audit_id=tool_call_ids_by_audit_id)

    def get_run_tool_execution_policy(
        self,
        *,
        session_id: str,
        run_id: str,
        trace_id: str | None = None,
    ) -> AgentRuntimeToolExecutionPolicy:
        """查询 Java Run 级工具执行策略。

        该接口只读，不会执行工具。Python 在真实执行前先读 policy，可以提前知道是否还有
        `AUTO_EXECUTABLE` 候选、是否被审批/参数/失败阻断，避免盲目进入二轮推理。
        """

        url = self._build_execution_policy_url(session_id=session_id, run_id=run_id)
        headers = {
            "Accept": "application/json",
            "X-DataSmart-Trace-Id": trace_id or "",
            "X-DataSmart-Source-Service": "python-ai-runtime",
        }
        request = Request(url=url, headers={k: v for k, v in headers.items() if v}, method="GET")
        try:
            with urlopen(request, timeout=self._timeout_seconds) as response:  # noqa: S310 - URL 来自受控配置
                payload = json.loads(response.read().decode("utf-8"))
        except Exception as exc:  # pragma: no cover - 网络错误在集成环境覆盖
            raise AgentRuntimeToolFeedbackClientError(f"查询 Java Agent 工具执行策略失败：{exc}") from exc
        try:
            return self.parse_platform_policy_response(payload)
        except AgentRuntimeToolExecutionContractError as exc:
            raise AgentRuntimeToolFeedbackClientError(str(exc)) from exc

    def auto_execute_sync_tools(
        self,
        *,
        session_id: str,
        run_id: str,
        audit_ids: tuple[str, ...] = (),
        max_executions: int | None = None,
        dry_run: bool = False,
        trace_id: str | None = None,
    ) -> AgentRuntimeToolAutoExecutionSummary:
        """请求 Java 受控执行当前 Run 中的安全同步工具候选。

        Python 只传递 auditId 白名单、批次数量上限和 dryRun 标记；最终是否执行仍由 Java 服务端
        根据 policy、LOW/readOnly/idempotent/requiresApproval=false 等规则决定。这样即使 Python
        侧 bug 传入了高风险 auditId，也不能绕过 Java 控制面。
        """

        url = self._build_auto_execute_sync_url(session_id=session_id, run_id=run_id)
        body: dict[str, Any] = {"dryRun": dry_run}
        if audit_ids:
            body["auditIds"] = list(audit_ids)
        if max_executions is not None:
            body["maxExecutions"] = max_executions
        headers = {
            "Accept": "application/json",
            "Content-Type": "application/json",
            "X-DataSmart-Trace-Id": trace_id or "",
            "X-DataSmart-Source-Service": "python-ai-runtime",
        }
        request = Request(
            url=url,
            data=json.dumps(body).encode("utf-8"),
            headers={k: v for k, v in headers.items() if v},
            method="POST",
        )
        try:
            with urlopen(request, timeout=self._timeout_seconds) as response:  # noqa: S310 - URL 来自受控配置
                payload = json.loads(response.read().decode("utf-8"))
        except Exception as exc:  # pragma: no cover - 网络错误在集成环境覆盖
            raise AgentRuntimeToolFeedbackClientError(f"请求 Java Agent 自动执行同步工具失败：{exc}") from exc
        try:
            return self.parse_platform_auto_execution_response(payload)
        except AgentRuntimeToolExecutionContractError as exc:
            raise AgentRuntimeToolFeedbackClientError(str(exc)) from exc

    def _build_result_url(self, *, session_id: str, run_id: str, audit_id: str) -> str:
        """构建结果查询 URL，并对路径参数做安全转义。"""

        path = self._result_path_template.format(
            sessionId=quote(session_id, safe=""),
            runId=quote(run_id, safe=""),
            auditId=quote(audit_id, safe=""),
        )
        return f"{self._base_url}{path}"

    def _build_run_results_url(self, *, session_id: str, run_id: str) -> str:
        """构建按 Run 批量查询工具结果的 URL。"""

        path = self._run_results_path_template.format(
            sessionId=quote(session_id, safe=""),
            runId=quote(run_id, safe=""),
        )
        return f"{self._base_url}{path}"

    def _build_execution_policy_url(self, *, session_id: str, run_id: str) -> str:
        """构建 Run 级工具执行策略查询 URL。"""

        path = self._execution_policy_path_template.format(
            sessionId=quote(session_id, safe=""),
            runId=quote(run_id, safe=""),
        )
        return f"{self._base_url}{path}"

    def _build_auto_execute_sync_url(self, *, session_id: str, run_id: str) -> str:
        """构建受控同步自动执行 URL。"""

        path = self._auto_execute_sync_path_template.format(
            sessionId=quote(session_id, safe=""),
            runId=quote(run_id, safe=""),
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
    def parse_platform_batch_response(
        cls,
        payload: dict[str, Any],
        *,
        tool_call_ids_by_audit_id: dict[str, str],
    ) -> tuple[ToolExecutionFeedback, ...]:
        """解析 Java 批量结果响应。

        Java 返回的是 `List<AgentToolExecutionResultView>`，其中每个元素包含 audit 和 output。
        Python 还需要补 `tool_call_id`，因此调用方必须传入 auditId 到 model tool_call_id 的映射。
        """

        if payload.get("code") != 0:
            reason = payload.get("reason", "UNKNOWN")
            message = payload.get("message", "Java 工具结果批量接口返回失败")
            raise AgentRuntimeToolFeedbackClientError(f"{reason}: {message}")
        data = payload.get("data")
        if not isinstance(data, list):
            raise AgentRuntimeToolFeedbackClientError("Java 工具结果批量响应 data 必须是数组")
        feedback_items: list[ToolExecutionFeedback] = []
        for item in data:
            if not isinstance(item, dict):
                continue
            audit = item.get("audit") or {}
            if not isinstance(audit, dict):
                continue
            audit_id = str(audit.get("auditId") or "")
            tool_call_id = tool_call_ids_by_audit_id.get(audit_id)
            if not tool_call_id:
                continue
            feedback_items.append(cls._map_result(item, tool_call_id=tool_call_id))
        return tuple(feedback_items)

    @classmethod
    def parse_platform_policy_response(cls, payload: dict[str, Any]) -> AgentRuntimeToolExecutionPolicy:
        """解析 Java `PlatformApiResponse<AgentRunToolExecutionPolicyView>`。"""

        try:
            return parse_execution_policy_response(payload)
        except AgentRuntimeToolExecutionContractError as exc:
            raise AgentRuntimeToolFeedbackClientError(str(exc)) from exc

    @classmethod
    def parse_platform_auto_execution_response(
        cls,
        payload: dict[str, Any],
    ) -> AgentRuntimeToolAutoExecutionSummary:
        """解析 Java `PlatformApiResponse<AgentRunToolAutoExecutionResponse>`。"""

        try:
            return parse_auto_execution_response(payload)
        except AgentRuntimeToolExecutionContractError as exc:
            raise AgentRuntimeToolFeedbackClientError(str(exc)) from exc

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
        output_workspace_key = cls._governance_text(audit, "outputWorkspaceKey", "workspaceKey", "workspace_key")
        output_context_policy = cls._governance_text(audit, "outputContextPolicy", "contextPolicy", "context_policy") or "audit_only"
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
            output_workspace_key=output_workspace_key,
            output_context_policy=output_context_policy,
            error_code=str(error_code) if error_code else None,
            error_message=str(audit.get("message") or "") if status == ToolExecutionFeedbackStatus.FAILED else None,
            sensitive_fields=sensitive_fields,
            model_context_include_paths=cls._governance_tuple(
                audit,
                "modelContextIncludePaths",
                "model_context_include_paths",
            ),
            model_context_exclude_paths=cls._governance_tuple(
                audit,
                "modelContextExcludePaths",
                "model_context_exclude_paths",
            ),
            sensitive_result_paths=cls._governance_tuple(audit, "sensitiveResultPaths", "sensitive_result_paths"),
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

        return JavaAgentRuntimeToolFeedbackClient._governance_tuple(audit, "sensitiveFields", "sensitive_fields")

    @staticmethod
    def _governance_text(audit: dict[str, Any], *keys: str) -> str | None:
        """从 Java governanceHints 中读取单个文本配置。"""

        hints = audit.get("governanceHints") or {}
        if not isinstance(hints, dict):
            return None
        for key in keys:
            value = hints.get(key)
            if value is not None and str(value).strip():
                return str(value).strip()
        return None

    @staticmethod
    def _governance_tuple(audit: dict[str, Any], *keys: str) -> tuple[str, ...]:
        """从 Java governanceHints 中读取路径列表配置。"""

        hints = audit.get("governanceHints") or {}
        if not isinstance(hints, dict):
            return ()
        for key in keys:
            raw_value = hints.get(key)
            if raw_value is None:
                continue
            if isinstance(raw_value, str):
                return (raw_value,)
            return tuple(str(item) for item in raw_value if str(item).strip())
        return ()

    @staticmethod
    def _non_success_result(audit: dict[str, Any], state: str) -> dict[str, Any]:
        """为非成功状态构造安全结果。"""

        return {
            "state": state or "UNKNOWN",
            "errorCode": audit.get("errorCode"),
            "message": audit.get("message"),
            "outputSummary": audit.get("outputSummary"),
        }


# 兼容旧导入路径：历史代码从本模块直接导入 Provider。
# 真正实现已拆到 `agent_runtime_tool_feedback_provider.py`，避免本文件继续膨胀成“客户端 + Provider + 策略”的大文件。
from datasmart_ai_runtime.services.agent_runtime_tool_feedback_provider import (  # noqa: E402
    JavaAgentRuntimeToolFeedbackProvider,
)
