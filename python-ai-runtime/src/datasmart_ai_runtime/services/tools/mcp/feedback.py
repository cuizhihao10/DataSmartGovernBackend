"""MCP worker 结果到模型二轮 tool feedback 的安全适配器。

本模块位于 MCP durable worker 与通用模型二轮反馈之间，解决的是 Agent 闭环中非常关键的一步：
外部 MCP 工具已经真实执行后，哪些内容可以进入模型下一轮上下文，哪些内容只能以审计摘要或
artifactReference 的形式保留。

设计原则：
- MCP `runtime_result` 属于短生命周期运行时正文，只能在当前 Agent loop 中被受控消费；
- Java worker receipt、runtime event、checkpoint、日志和指标只能保存低敏摘要；
- 模型二轮推理可以看到小而安全的结果摘要，但不能看到凭据、远端错误正文、大 payload、样本行、
  SQL、prompt、HTTP header 或外部服务内部细节；
- 本适配器输出项目既有的 `ToolExecutionFeedback`，复用统一的 workspace 校验、字段过滤和
  OpenAI-compatible tool message 构建逻辑，避免 MCP 形成一条孤立的“旁路反馈协议”。
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from typing import Any, Mapping

from datasmart_ai_runtime.services.model_gateway.model_tool_result_feedback import (
    ToolExecutionFeedback,
    ToolExecutionFeedbackStatus,
)
from datasmart_ai_runtime.services.tools.controlled_command_worker_runner import (
    CommandWorkerReceiptOutcome,
)
from datasmart_ai_runtime.services.tools.mcp.execution import (
    McpDurableExecutionStatus,
)
from datasmart_ai_runtime.services.tools.mcp.worker import (
    McpDurableWorkerRunResult,
)


MCP_TOOL_FEEDBACK_SCHEMA_VERSION = "datasmart.mcp-tool-feedback.v1"

_SENSITIVE_KEY_PATTERN = re.compile(
    r"(password|passwd|secret|token|api[_-]?key|credential|private[_-]?key|authorization|cookie|set[_-]?cookie)",
    re.IGNORECASE,
)
_SENSITIVE_TEXT_PATTERN = re.compile(
    r"(authorization\s*:|bearer\s+[a-z0-9._~+/=-]{8,}|api[_-]?key\s*[=:]|password\s*[=:]|secret\s*[=:]|token\s*[=:])",
    re.IGNORECASE,
)


@dataclass(frozen=True)
class McpToolFeedbackAdapterSettings:
    """MCP 工具结果进入模型上下文前的安全预算。

    字段说明：
    - `max_inline_result_bytes`：允许内联给模型的最大 UTF-8 字节数。这里默认 8KB，明显小于 MCP
      Runtime 的 `max_result_bytes`，因为 Runtime 预算是“本轮内存可承载”，而模型反馈预算是
      “是否适合进入下一轮 prompt”；
    - `max_inline_text_chars`：文本块进入模型前的字符上限。即使总字节数较小，也避免一段长文本
      独占下一轮上下文；
    - `include_content_blocks`：是否允许安全 text content blocks 进入模型。生产环境如果希望更保守，
      可以关闭该开关，只允许结构化摘要进入模型；
    - `include_structured_content`：是否允许结构化结果进入模型。适配器会先检查敏感 key，再交给
      `ModelToolResultFeedbackBuilder` 做字段级过滤；
    - `artifact_context_policy`：当结果过大、截断、失败或疑似敏感时，输出引用的上下文策略。默认
      `audit_only`，表示模型只能看到摘要和引用，不能读取正文。
    """

    max_inline_result_bytes: int = 8 * 1024
    max_inline_text_chars: int = 2000
    include_content_blocks: bool = True
    include_structured_content: bool = True
    artifact_context_policy: str = "audit_only"


@dataclass(frozen=True)
class McpToolFeedbackBuildResult:
    """MCP feedback 适配结果。

    `feedback` 是可以交给 `ModelToolResultFeedbackBuilder` 的统一对象；`summary` 是低敏诊断，
    可进入 runtime event 或测试断言，但不包含 MCP 工具正文。
    """

    feedback: ToolExecutionFeedback
    summary: dict[str, Any]


class McpToolFeedbackAdapter:
    """把 MCP durable worker 结果转换为模型二轮 tool feedback。

    这个类不负责调用 MCP，也不负责调用模型。它只做“结果准入与形状转换”：
    1. 从 worker receipt 中读取 runId、commandId、toolCode、artifactReference 等低敏事实；
    2. 检查 `execution_result.runtime_result` 是否满足进入模型上下文的条件；
    3. 成功且安全的小结果进入 `ToolExecutionFeedback.result`；
    4. 失败、预检失败、截断、大结果或疑似敏感结果只返回摘要和 artifactReference；
    5. 输出统一 `ToolExecutionFeedback`，让既有 builder 继续执行 workspace 与字段级过滤。
    """

    def __init__(self, settings: McpToolFeedbackAdapterSettings | None = None) -> None:
        self._settings = settings or McpToolFeedbackAdapterSettings()

    def build(
        self,
        worker_result: McpDurableWorkerRunResult,
        *,
        tool_call_id: str | None = None,
        workspace_key: str | None = None,
        current_workspace_key: str | None = None,
    ) -> McpToolFeedbackBuildResult:
        """构建单条 MCP 工具反馈。

        参数说明：
        - `worker_result`：`McpDurableWorkerAdapter.run(...)` 的结果，可能包含运行时正文，也一定包含
          低敏 receipt；
        - `tool_call_id`：可选覆盖值。模型上一轮 tool_call id 与 Java commandId 不完全一致时，
          调用方应显式传入模型的 callId；
        - `workspace_key/current_workspace_key`：输出资源所属工作空间与当前运行工作空间。当前方法
          不直接执行 workspace 校验，而是把 workspace 写入 `ToolExecutionFeedback`，由统一 builder
          在二轮消息构建阶段校验，保持所有工具类型的治理入口一致。
        """

        java_payload = worker_result.receipt.java_payload
        call_id = _first_text(tool_call_id, java_payload.get("commandId"), _execution_call_id(worker_result))
        tool_name = _first_text(java_payload.get("toolCode"), _execution_tool_name(worker_result), "mcp.unknown")
        run_id = _first_text(java_payload.get("runId"), _execution_run_id(worker_result))
        artifact_ref = _optional_text(java_payload.get("artifactReference"))
        status = self._feedback_status(worker_result)
        result, inline_decision = self._result_for_model(worker_result)
        output_context_policy = "model_summary_allowed" if inline_decision["inlineResultAllowed"] else self._settings.artifact_context_policy
        output_ref = (
            f"agent-runtime://tool-results/{call_id}"
            if inline_decision["inlineResultAllowed"]
            else artifact_ref or f"agent-runtime://tool-results/{call_id}"
        )

        feedback = ToolExecutionFeedback(
            tool_call_id=call_id,
            tool_name=tool_name,
            status=status,
            summary=self._summary_for(worker_result, inline_decision),
            result=result,
            error_code=_error_code(worker_result),
            error_message=self._error_message_for(worker_result),
            audit_id=_optional_text(java_payload.get("auditId")),
            run_id=run_id,
            output_ref=output_ref,
            output_workspace_key=workspace_key or current_workspace_key,
            output_context_policy=output_context_policy,
            sensitive_result_paths=(
                "structuredContent.password",
                "structuredContent.token",
                "structuredContent.secret",
                "structuredContent.authorization",
            ),
            model_context_max_string_length=self._settings.max_inline_text_chars,
            model_context_max_list_items=20,
            model_context_max_depth=6,
        )
        return McpToolFeedbackBuildResult(
            feedback=feedback,
            summary={
                "schemaVersion": MCP_TOOL_FEEDBACK_SCHEMA_VERSION,
                "toolCallId": call_id,
                "toolName": tool_name,
                "status": status.value,
                "runId": run_id,
                "artifactReferencePresent": bool(artifact_ref),
                "workspaceKeyPresent": bool(workspace_key or current_workspace_key),
                **inline_decision,
                "payloadPolicy": "MODEL_TOOL_FEEDBACK_LOW_SENSITIVE_SUMMARY",
            },
        )

    def _feedback_status(self, worker_result: McpDurableWorkerRunResult) -> ToolExecutionFeedbackStatus:
        """把 MCP/worker 状态映射为模型反馈状态。

        模型不需要理解所有 worker outcome 细节，但必须知道“成功、失败、被治理拒绝”的区别：
        - `FAILED_PRECHECK` 表示权限、readiness、approval 或参数边界未满足，映射为 `REJECTED`；
        - `EXECUTION_FAILED` 表示进入执行后失败，映射为 `FAILED`；
        - `SUCCEEDED` 且 MCP `is_error=false` 才映射为 `SUCCEEDED`。
        """

        if worker_result.admission_error is not None:
            return ToolExecutionFeedbackStatus.REJECTED
        if worker_result.receipt.outcome == CommandWorkerReceiptOutcome.FAILED_PRECHECK:
            return ToolExecutionFeedbackStatus.REJECTED
        execution_result = worker_result.execution_result
        if execution_result is None:
            return ToolExecutionFeedbackStatus.FAILED
        if execution_result.status == McpDurableExecutionStatus.FAILED_PRECHECK:
            return ToolExecutionFeedbackStatus.REJECTED
        if execution_result.status != McpDurableExecutionStatus.SUCCEEDED:
            return ToolExecutionFeedbackStatus.FAILED
        if execution_result.runtime_result and execution_result.runtime_result.is_error:
            return ToolExecutionFeedbackStatus.FAILED
        return ToolExecutionFeedbackStatus.SUCCEEDED

    def _result_for_model(self, worker_result: McpDurableWorkerRunResult) -> tuple[dict[str, Any], dict[str, Any]]:
        """决定 MCP 正文是否允许进入模型上下文。

        这里先做 MCP 专属的粗粒度准入：状态、大小、截断、敏感 key/文本。通过后仍然交给通用
        `ModelToolResultFeedbackBuilder` 做 workspace 与字段级过滤，因此这是双层防护，不是唯一安全门。
        """

        runtime_result = worker_result.execution_result.runtime_result if worker_result.execution_result else None
        base_decision: dict[str, Any] = {
            "inlineResultAllowed": False,
            "inlineDecisionReason": "runtime_result_missing",
            "runtimeResultPresent": runtime_result is not None,
            "runtimeResultByteCount": 0,
            "runtimeResultTruncated": False,
        }
        if runtime_result is None:
            return {}, base_decision

        base_decision.update(
            {
                "runtimeResultByteCount": runtime_result.result_byte_count,
                "runtimeResultTruncated": runtime_result.truncated,
                "runtimeResultDigest": runtime_result.result_digest,
                "runtimeResultIsError": runtime_result.is_error,
            }
        )
        if worker_result.execution_result and worker_result.execution_result.status != McpDurableExecutionStatus.SUCCEEDED:
            base_decision["inlineDecisionReason"] = "execution_not_succeeded"
            return {}, base_decision
        if runtime_result.is_error:
            base_decision["inlineDecisionReason"] = "mcp_result_marked_error"
            return {}, base_decision
        if runtime_result.truncated:
            base_decision["inlineDecisionReason"] = "runtime_result_truncated"
            return {}, base_decision
        if runtime_result.result_byte_count > max(1, self._settings.max_inline_result_bytes):
            base_decision["inlineDecisionReason"] = "runtime_result_too_large_for_model"
            return {}, base_decision

        result: dict[str, Any] = {
            "schemaVersion": MCP_TOOL_FEEDBACK_SCHEMA_VERSION,
            "resultDigest": runtime_result.result_digest,
            "resultByteCount": runtime_result.result_byte_count,
        }
        if self._settings.include_content_blocks:
            content_blocks = self._safe_content_blocks(runtime_result.content_blocks)
            if content_blocks is None:
                base_decision["inlineDecisionReason"] = "content_block_looks_sensitive"
                return {}, base_decision
            if content_blocks:
                result["contentBlocks"] = content_blocks
        if self._settings.include_structured_content and runtime_result.structured_content is not None:
            if _contains_sensitive_key(runtime_result.structured_content):
                base_decision["inlineDecisionReason"] = "structured_content_contains_sensitive_key"
                return {}, base_decision
            result["structuredContent"] = runtime_result.structured_content

        base_decision["inlineResultAllowed"] = True
        base_decision["inlineDecisionReason"] = "safe_small_result"
        return result, base_decision

    def _safe_content_blocks(self, blocks: tuple[dict[str, Any], ...]) -> tuple[dict[str, Any], ...] | None:
        """筛选可进入模型的小型 text content block。

        MCP content block 可能是 text、image、resource 等多种类型。当前只允许短 text 进入模型；
        其他类型应先落 artifact，再通过受控 resolver 读取摘要，避免模型直接接触二进制、URL 或外部资源。
        """

        selected: list[dict[str, Any]] = []
        for block in blocks:
            block_type = str(block.get("type") or "").strip().lower()
            if block_type != "text":
                continue
            text = str(block.get("text") or "")
            if _SENSITIVE_TEXT_PATTERN.search(text):
                return None
            selected.append({"type": "text", "text": text[: self._settings.max_inline_text_chars]})
        return tuple(selected)

    @staticmethod
    def _summary_for(worker_result: McpDurableWorkerRunResult, inline_decision: Mapping[str, Any]) -> str:
        """生成给模型和用户阅读的低敏中文摘要。"""

        if worker_result.admission_error is not None:
            return "MCP 工具未执行：平台 admission/readiness/permission 校验未通过。"
        execution_result = worker_result.execution_result
        if execution_result is None:
            return "MCP 工具没有返回可用于二轮推理的执行结果。"
        if execution_result.status == McpDurableExecutionStatus.FAILED_PRECHECK:
            return "MCP 工具在执行前校验阶段被阻断，未调用外部 MCP Server。"
        if execution_result.status == McpDurableExecutionStatus.FAILED_TOOL_CALL:
            return "MCP 工具调用失败，远端错误正文未进入模型上下文。"
        if execution_result.runtime_result and execution_result.runtime_result.is_error:
            return "MCP 工具返回错误结果，正文未进入模型上下文。"
        if inline_decision.get("inlineResultAllowed"):
            return "MCP 工具已受控执行成功，安全短结果已允许进入模型二轮推理。"
        return "MCP 工具已受控执行成功，但结果过大、被截断或疑似敏感，仅返回摘要与 artifactReference。"

    @staticmethod
    def _error_message_for(worker_result: McpDurableWorkerRunResult) -> str | None:
        """生成稳定、低敏的错误说明，不回显远端 MCP 错误正文。"""

        if worker_result.admission_error is not None:
            return "MCP admission 构造失败，缺少权限、readiness、allowlist 或范围字段。"
        execution_result = worker_result.execution_result
        if execution_result is None or execution_result.status == McpDurableExecutionStatus.SUCCEEDED:
            return None
        return "MCP 工具执行未成功，详细远端错误正文已按安全策略隐藏。"


def _contains_sensitive_key(value: Any) -> bool:
    """递归检查结构化结果 key 是否疑似凭据字段。"""

    if isinstance(value, Mapping):
        for key, nested in value.items():
            if _SENSITIVE_KEY_PATTERN.search(str(key)):
                return True
            if _contains_sensitive_key(nested):
                return True
    elif isinstance(value, (list, tuple)):
        return any(_contains_sensitive_key(item) for item in value)
    return False


def _execution_call_id(worker_result: McpDurableWorkerRunResult) -> str | None:
    """从 durable execution receipt draft 中读取 callId。"""

    draft = worker_result.execution_result.worker_receipt_draft if worker_result.execution_result else None
    return draft.call_id if draft else None


def _execution_run_id(worker_result: McpDurableWorkerRunResult) -> str | None:
    """从 durable execution receipt draft 中读取 runId。"""

    draft = worker_result.execution_result.worker_receipt_draft if worker_result.execution_result else None
    return draft.run_id if draft else None


def _execution_tool_name(worker_result: McpDurableWorkerRunResult) -> str | None:
    """从 durable execution result 中读取 MCP 内部工具名。"""

    return worker_result.execution_result.internal_tool_name if worker_result.execution_result else None


def _error_code(worker_result: McpDurableWorkerRunResult) -> str | None:
    """读取低敏稳定错误码。"""

    if worker_result.admission_error is not None:
        return "MCP_ADMISSION_BUILD_FAILED"
    if worker_result.execution_result and worker_result.execution_result.error_code:
        return worker_result.execution_result.error_code
    if worker_result.execution_result and worker_result.execution_result.runtime_result and worker_result.execution_result.runtime_result.is_error:
        return "MCP_TOOL_RESULT_MARKED_ERROR"
    return None


def _first_text(*values: Any) -> str:
    """读取第一个非空字符串。"""

    for value in values:
        text = _optional_text(value)
        if text:
            return text
    return "unknown"


def _optional_text(value: Any) -> str | None:
    """把可选值规范化为非空字符串。"""

    if value is None:
        return None
    text = str(value).strip()
    return text or None


def mcp_feedback_summary_json(result: McpToolFeedbackBuildResult) -> str:
    """生成可用于诊断输出的低敏 JSON。

    该函数主要服务 smoke/debug 场景，明确只序列化 `summary`，不序列化 `feedback.result`，避免开发者
    在排障时误把安全短结果或未来 artifact 摘要打印到普通日志。
    """

    return json.dumps(result.summary, ensure_ascii=False, sort_keys=True)


__all__ = [
    "MCP_TOOL_FEEDBACK_SCHEMA_VERSION",
    "McpToolFeedbackAdapter",
    "McpToolFeedbackAdapterSettings",
    "McpToolFeedbackBuildResult",
    "mcp_feedback_summary_json",
]
