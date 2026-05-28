"""模型工具执行结果回填消息构建器。

前几轮已经完成“模型提出工具调用 -> 平台治理 -> 生成 ToolPlan”。但真正的多步 Agent loop 还需要
下一段契约：工具执行完成后，如何把结果安全地回填给模型，让模型基于真实工具结果继续推理。

本文件只负责“消息构建”，不负责真实工具执行。真实执行仍由 Java `agent-runtime` 负责审批、审计、
幂等、权限和业务微服务调用；Python Runtime 只消费执行结果摘要，并把它转换为 OpenAI-compatible
风格的 assistant/tool messages。这样能保证模型 loop 可替换，同时不让 Python 绕过 Java 控制面。
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Iterable

from datasmart_ai_runtime.domain.contracts import ModelMessage, ModelToolCall
from datasmart_ai_runtime.domain.resource_reference import AgentResourceReference


class ToolExecutionFeedbackStatus(str, Enum):
    """工具执行结果回填状态。

    状态设计不只覆盖成功/失败，还显式保留审批等待和跳过场景：
    - `SUCCEEDED`：工具已经由 Java 控制面执行成功，结果可用于模型继续推理；
    - `FAILED`：工具执行失败，模型可以基于错误码解释原因或提出重试/降级方案；
    - `REJECTED`：工具在权限、参数、风险或策略层被拒绝，模型应停止假设该工具可用；
    - `WAITING_APPROVAL`：工具还在人工审批中，模型不应伪造结果，只能解释等待状态；
    - `SKIPPED`：平台主动跳过某个候选，例如被同名计划替代或本轮不需要执行。
    """

    SUCCEEDED = "succeeded"
    FAILED = "failed"
    REJECTED = "rejected"
    WAITING_APPROVAL = "waiting_approval"
    SKIPPED = "skipped"


@dataclass(frozen=True)
class ToolExecutionFeedback:
    """单个工具执行结果的模型回填摘要。

    字段说明：
    - `tool_call_id`：必须对应模型上一轮 `tool_calls` 的 id，是多步工具 loop 的关联键；
    - `tool_name`：DataSmart 原始工具名，例如 `quality.rule.suggest`，用于审计和人类可读解释；
    - `status`：执行或治理结果状态；
    - `summary`：给模型和用户阅读的简短中文摘要；
    - `result`：允许回填给模型的结构化结果摘要。这里不应放完整大结果、敏感样本行或原始 SQL；
    - `error_code/error_message`：失败或拒绝时的稳定错误码与说明；
    - `audit_id/run_id/output_ref`：Java 控制面事实引用，让模型和审计系统知道结果来自哪次受控执行；
    - `sensitive_fields`：标记 result 中哪些字段不应原样进入模型；构建器会把这些字段脱敏。
    """

    tool_call_id: str
    tool_name: str
    status: ToolExecutionFeedbackStatus
    summary: str
    result: dict[str, Any] = field(default_factory=dict)
    error_code: str | None = None
    error_message: str | None = None
    audit_id: str | None = None
    run_id: str | None = None
    output_ref: str | None = None
    sensitive_fields: tuple[str, ...] = ()


@dataclass(frozen=True)
class ToolExecutionFeedbackMessageBundle:
    """工具结果回填消息包。

    `messages` 可以直接追加到下一轮 `ModelInvocationRequest.messages` 中。为了保持 OpenAI-compatible
    顺序，构建器会先放一条 assistant tool_calls 历史消息，再放一组 role=tool 的结果消息。
    `missing_feedback_call_ids` 用于诊断：如果模型提出了 N 个工具调用，但只回填 N-1 个结果，某些
    OpenAI-compatible 网关会拒绝下一轮请求，因此这里提前暴露缺口。
    """

    messages: tuple[ModelMessage, ...] = ()
    missing_feedback_call_ids: tuple[str, ...] = ()
    extra_feedback_call_ids: tuple[str, ...] = ()

    @property
    def complete(self) -> bool:
        """所有模型工具调用是否都有对应回填结果。"""

        return not self.missing_feedback_call_ids and not self.extra_feedback_call_ids


class ModelToolResultFeedbackBuilder:
    """把 Java 工具执行结果转换为下一轮模型消息。

    多步 Agent loop 的关键不是“把工具结果原样塞回模型”，而是做三件事：
    1. 保留 `tool_call_id` 关联关系，保证模型和 Provider 都能理解结果归属；
    2. 只回填摘要、引用和允许进入模型的结构化字段，避免泄露样本数据、密钥、SQL 或大 payload；
    3. 对失败、拒绝、审批等待也生成 tool message，让模型能解释当前状态，而不是凭空继续执行。
    """

    MASKED_VALUE = "***MASKED***"

    def build(
        self,
        tool_calls: tuple[ModelToolCall, ...],
        feedback_items: Iterable[ToolExecutionFeedback],
    ) -> ToolExecutionFeedbackMessageBundle:
        """构建下一轮模型调用所需的 assistant/tool messages。

        参数：
        - `tool_calls`：模型上一轮提出的工具调用。构建器会把它们放回 assistant message，形成上下文；
        - `feedback_items`：Java 控制面返回的执行结果、审批等待、拒绝或失败摘要。

        返回：
        - assistant message：携带上一轮 `tool_calls`；
        - tool messages：每个 tool_call_id 对应一条结果消息；
        - 完整性诊断：缺少结果或多余结果的 callId。
        """

        feedback_by_id = {item.tool_call_id: item for item in feedback_items}
        expected_ids = tuple(call.call_id for call in tool_calls if call.call_id)
        expected_id_set = set(expected_ids)
        feedback_id_set = set(feedback_by_id)

        messages: list[ModelMessage] = []
        if tool_calls:
            messages.append(ModelMessage(role="assistant", content="", tool_calls=tool_calls))
        for call_id in expected_ids:
            feedback = feedback_by_id.get(call_id)
            if feedback is None:
                continue
            messages.append(self._to_tool_message(feedback))

        return ToolExecutionFeedbackMessageBundle(
            messages=tuple(messages),
            missing_feedback_call_ids=tuple(call_id for call_id in expected_ids if call_id not in feedback_id_set),
            extra_feedback_call_ids=tuple(sorted(feedback_id_set - expected_id_set)),
        )

    def _to_tool_message(self, feedback: ToolExecutionFeedback) -> ModelMessage:
        """把单个执行反馈转换为 role=tool 的消息。"""

        payload = {
            "toolName": feedback.tool_name,
            "status": feedback.status.value,
            "summary": feedback.summary,
            "auditId": feedback.audit_id,
            "runId": feedback.run_id,
            "outputRef": feedback.output_ref,
            "outputReference": self._output_reference_payload(feedback),
            "errorCode": feedback.error_code,
            "errorMessage": feedback.error_message,
            "result": self._mask_result(feedback.result, feedback.sensitive_fields),
        }
        return ModelMessage(
            role="tool",
            content=json.dumps(payload, ensure_ascii=False, sort_keys=True),
            tool_call_id=feedback.tool_call_id,
            name=feedback.tool_name,
        )

    def _mask_result(self, result: dict[str, Any], sensitive_fields: tuple[str, ...]) -> dict[str, Any]:
        """对允许回填给模型的结果摘要做字段级脱敏。

        这里按顶层字段名脱敏，是当前最小可行策略。真实生产环境后续应升级为：
        - 工具 schema 中的 `sensitive=true`；
        - permission-admin 字段级策略；
        - JSONPath 级别脱敏；
        - 大结果只回填对象存储引用和摘要。
        """

        if not result:
            return {}
        sensitive = {field.strip() for field in sensitive_fields if field.strip()}
        masked: dict[str, Any] = {}
        for key, value in result.items():
            masked[key] = self.MASKED_VALUE if key in sensitive else value
        return masked

    @staticmethod
    def _output_reference_payload(feedback: ToolExecutionFeedback) -> dict[str, Any] | None:
        """把旧式 outputRef 字符串补充为统一资源引用结构。

        旧字段 `outputRef` 继续保留，确保当前 Java/Python 测试和调用方不受影响；
        新字段 `outputReference` 让后续模型上下文治理、审计台和工具输出 resolver 能识别引用类型。
        """

        if not feedback.output_ref:
            return None
        reference = AgentResourceReference.from_uri(feedback.output_ref)
        return reference.to_payload()
