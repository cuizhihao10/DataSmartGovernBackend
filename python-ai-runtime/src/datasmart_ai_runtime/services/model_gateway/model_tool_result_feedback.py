"""模型工具执行结果回填消息构建器。

前几轮已经完成“模型提出工具调用 -> 平台治理 -> 生成 ToolPlan”。但真正的多步 Agent loop 还需要
下一段契约：工具执行完成后，如何把结果安全地回填给模型，让模型基于真实工具结果继续推理。

本文件只负责“消息构建”，不负责真实工具执行。真实执行仍由 Java `agent-runtime` 负责审批、审计、
幂等、权限和业务微服务调用；Python Runtime 只消费执行结果摘要，并把它转换为 OpenAI-compatible
风格的 assistant/tool messages。这样能保证模型 loop 可替换，同时不让 Python 绕过 Java 控制面。
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field, replace
from enum import Enum
from typing import Any, Iterable

from datasmart_ai_runtime.domain.contracts import ModelMessage, ModelToolCall
from datasmart_ai_runtime.domain.resource_reference import AgentResourceReference
from datasmart_ai_runtime.services.model_gateway.model_result_context_filter import (
    ModelResultContextFilter,
    ModelResultContextFilterPolicy,
    ModelResultContextFilterReport,
    ModelResultContextFilterResult,
)
from datasmart_ai_runtime.services.resource_reference_resolver import (
    AgentResourceReferenceResolution,
    AgentResourceReferenceResolver,
)


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
    - `output_workspace_key`：输出资源所属工作空间。二轮推理时会和当前 workspace 做一致性校验；
    - `output_context_policy`：输出资源是否允许进入模型上下文。默认 `audit_only` 是安全默认值；
    - `sensitive_fields`：历史顶层敏感字段配置，等价于顶层路径，例如 `datasourceId`；
    - `model_context_include_paths`：允许进入模型的字段路径白名单，例如 `metadata.tableCount`；
    - `model_context_exclude_paths`：明确禁止进入模型的字段路径黑名单；
    - `sensitive_result_paths`：需要保留字段名但遮蔽值的字段路径，例如 `tables[].sampleValue`；
    - `model_context_max_*`：模型上下文大小保护，避免大文本、长列表或深层嵌套撑爆 token。
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
    output_workspace_key: str | None = None
    output_context_policy: str = "audit_only"
    sensitive_fields: tuple[str, ...] = ()
    model_context_include_paths: tuple[str, ...] = ()
    model_context_exclude_paths: tuple[str, ...] = ()
    sensitive_result_paths: tuple[str, ...] = ()
    model_context_max_string_length: int = 512
    model_context_max_list_items: int = 20
    model_context_max_depth: int = 8


@dataclass(frozen=True)
class ToolExecutionFeedbackMessageBundle:
    """工具结果回填消息包。

    `messages` 可以直接追加到下一轮 `ModelInvocationRequest.messages` 中。为了保持 OpenAI-compatible
    顺序，构建器会先放一条 assistant tool_calls 历史消息，再放一组 role=tool 的结果消息。
    `missing_feedback_call_ids` 用于诊断：如果模型提出了 N 个工具调用，但只回填 N-1 个结果，某些
    OpenAI-compatible 网关会拒绝下一轮请求，因此这里提前暴露缺口。
    `resource_resolution_summaries` 是资源准入诊断摘要。它只包含治理决策、问题码、引用类型和
    resolverHint，不包含工具结果原文，因此可以安全写入 runtime event、前端诊断面板和审计回放。
    `result_filter_summaries` 是字段级上下文过滤摘要，用来解释哪些路径被允许、遮蔽、删除或截断。
    """

    messages: tuple[ModelMessage, ...] = ()
    missing_feedback_call_ids: tuple[str, ...] = ()
    extra_feedback_call_ids: tuple[str, ...] = ()
    resource_resolution_summaries: tuple[dict[str, Any], ...] = ()
    result_filter_summaries: tuple[dict[str, Any], ...] = ()

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

    def __init__(
        self,
        resource_resolver: AgentResourceReferenceResolver | None = None,
        result_context_filter: ModelResultContextFilter | None = None,
    ) -> None:
        """创建工具结果回填构建器。

        `resource_resolver` 是可注入依赖，默认使用轻量治理解析器。这里不直接读取 MinIO、Java 审计表
        或长期记忆，只在消息构建阶段做“能否进入模型上下文”的准入判断。这样做的设计意图是：
        - 真实读取能力可以后续按资源类型拆分实现；
        - 模型上下文安全不必等待所有读取器完成；
        - 单元测试可以注入替身 resolver 验证边界条件。
        """

        self._resource_resolver = resource_resolver or AgentResourceReferenceResolver()
        self._result_context_filter = result_context_filter or ModelResultContextFilter()

    def build(
        self,
        tool_calls: tuple[ModelToolCall, ...],
        feedback_items: Iterable[ToolExecutionFeedback],
        *,
        current_workspace_key: str | None = None,
    ) -> ToolExecutionFeedbackMessageBundle:
        """构建下一轮模型调用所需的 assistant/tool messages。

        参数：
        - `tool_calls`：模型上一轮提出的工具调用。构建器会把它们放回 assistant message，形成上下文；
        - `feedback_items`：Java 控制面返回的执行结果、审批等待、拒绝或失败摘要。
        - `current_workspace_key`：当前 Agent 运行工作空间。传入后会强制校验输出引用是否属于同一 workspace。

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
        resource_resolution_summaries: list[dict[str, Any]] = []
        result_filter_summaries: list[dict[str, Any]] = []
        if tool_calls:
            messages.append(ModelMessage(role="assistant", content="", tool_calls=tool_calls))
        for call_id in expected_ids:
            feedback = feedback_by_id.get(call_id)
            if feedback is None:
                continue
            tool_message, resolution_summary, result_filter_summary = self._to_tool_message(
                feedback,
                current_workspace_key=current_workspace_key,
            )
            messages.append(tool_message)
            if resolution_summary:
                resource_resolution_summaries.append(resolution_summary)
            if result_filter_summary:
                result_filter_summaries.append(result_filter_summary)

        return ToolExecutionFeedbackMessageBundle(
            messages=tuple(messages),
            missing_feedback_call_ids=tuple(call_id for call_id in expected_ids if call_id not in feedback_id_set),
            extra_feedback_call_ids=tuple(sorted(feedback_id_set - expected_id_set)),
            resource_resolution_summaries=tuple(resource_resolution_summaries),
            result_filter_summaries=tuple(result_filter_summaries),
        )

    def _to_tool_message(
        self,
        feedback: ToolExecutionFeedback,
        *,
        current_workspace_key: str | None,
    ) -> tuple[ModelMessage, dict[str, Any] | None, dict[str, Any] | None]:
        """把单个执行反馈转换为 role=tool 的消息。

        这一步是模型上下文安全的关键闸口。Java 控制面返回的 `result` 即使已经是摘要，也不能脱离资源
        引用策略直接进入模型：如果输出引用属于其他 workspace，或 `contextPolicy` 明确为 `audit_only`
        / `download_only` / `forbidden_for_model`，构建器会保留摘要、审计引用和治理说明，但不会把结构化
        `result` 放进 role=tool 消息。模型能知道“有一个受控结果存在”，却不能看到不该进入上下文的内容。
        """

        resolution = self._resource_resolution(feedback, current_workspace_key=current_workspace_key)
        resolution_summary = self._resource_resolution_event_summary(feedback, resolution)
        model_context_allowed = resolution.model_context_allowed if resolution else True
        filtered_result = (
            self._filter_result(feedback)
            if model_context_allowed
            else self._resource_blocked_filter_result(feedback, resolution)
        )

        payload = {
            "toolName": feedback.tool_name,
            "status": feedback.status.value,
            "summary": feedback.summary,
            "auditId": feedback.audit_id,
            "runId": feedback.run_id,
            "outputRef": feedback.output_ref,
            "outputReference": resolution.reference.to_payload() if resolution else None,
            "outputReferenceResolution": resolution.to_summary() if resolution else None,
            "errorCode": feedback.error_code,
            "errorMessage": feedback.error_message,
            "result": filtered_result.result,
            "resultFilterReport": filtered_result.report.to_summary(),
        }
        return (
            ModelMessage(
                role="tool",
                content=json.dumps(payload, ensure_ascii=False, sort_keys=True),
                tool_call_id=feedback.tool_call_id,
                name=feedback.tool_name,
            ),
            resolution_summary,
            self._result_filter_event_summary(feedback, filtered_result.report),
        )

    def _filter_result(self, feedback: ToolExecutionFeedback) -> ModelResultContextFilterResult:
        """对允许进入模型上下文的工具结果做字段级过滤。

        资源准入回答的是“这个资源整体是否可以进入模型”，字段过滤回答的是“资源中的哪些字段可以进入
        模型”。两者叠加后，才能从粗粒度安全门进化到更接近商业化 Agent 的 context engineering。
        """

        policy = ModelResultContextFilterPolicy(
            include_paths=self._normalized_paths(feedback.model_context_include_paths),
            exclude_paths=self._normalized_paths(feedback.model_context_exclude_paths),
            sensitive_paths=self._normalized_paths(feedback.sensitive_fields + feedback.sensitive_result_paths),
            max_string_length=max(1, feedback.model_context_max_string_length),
            max_list_items=max(1, feedback.model_context_max_list_items),
            max_depth=max(1, feedback.model_context_max_depth),
        )
        return self._result_context_filter.filter(feedback.result, policy)

    @staticmethod
    def _resource_blocked_filter_result(
        feedback: ToolExecutionFeedback,
        resolution: AgentResourceReferenceResolution | None,
    ) -> ModelResultContextFilterResult:
        """构造资源准入未通过时的字段过滤报告。

        此时字段过滤并没有真正执行，因为资源级策略已经禁止结构化 result 进入模型。仍然返回报告，是为了
        让前端和审计台能区分“字段策略裁剪”与“资源策略阻断”。
        """

        reason = "resource_not_allowed_for_model"
        if resolution is not None and resolution.decision.value == "blocked":
            reason = "resource_reference_blocked"
        report = ModelResultContextFilterReport(
            mode=reason,
            include_paths=feedback.model_context_include_paths,
            exclude_paths=feedback.model_context_exclude_paths,
            sensitive_paths=feedback.sensitive_fields + feedback.sensitive_result_paths,
            output_top_level_keys=(),
        )
        return ModelResultContextFilterResult(result={}, report=report)

    def _resource_resolution(
        self,
        feedback: ToolExecutionFeedback,
        *,
        current_workspace_key: str | None,
    ) -> AgentResourceReferenceResolution | None:
        """把旧式 outputRef 字符串解析并执行模型上下文准入判断。

        旧字段 `outputRef` 继续保留，确保当前 Java/Python 测试和调用方不受影响；
        新字段 `outputReference` 让后续模型上下文治理、审计台和工具输出 resolver 能识别引用类型。

        注意：历史 `outputRef` 可能没有 workspace 信息。只有调用方传入 `current_workspace_key` 时，才把
        workspaceKey 作为必填治理条件；这让老数据迁移和本地测试仍可运行，同时让生产二轮推理路径能够
        逐步升级为强隔离模式。
        """

        if not feedback.output_ref:
            return None
        reference = AgentResourceReference.from_uri(
            feedback.output_ref,
            workspace_key=feedback.output_workspace_key,
        )
        reference = replace(
            reference,
            audit_id=feedback.audit_id,
            run_id=feedback.run_id,
            context_policy=feedback.output_context_policy,
        )
        return self._resource_resolver.resolve(
            reference,
            current_workspace_key=current_workspace_key,
            expected_workspace_required=bool(current_workspace_key),
        )

    @staticmethod
    def _resource_resolution_event_summary(
        feedback: ToolExecutionFeedback,
        resolution: AgentResourceReferenceResolution | None,
    ) -> dict[str, Any] | None:
        """生成适合 runtime event 的资源准入诊断摘要。

        role=tool 消息里会包含较完整的 `outputReferenceResolution`，但 runtime event 面向前端进度条、
        WebSocket replay、审计检索和告警规则，不应该写入完整 payload 或工具 result。因此这里提炼出
        最小但足够排障的字段：
        - 哪个 toolCall / toolName；
        - 引用类型、URI、workspaceKey 和 contextPolicy；
        - 是否允许继续解析、是否允许进入模型上下文；
        - 阻断问题码和后续 resolverHint。

        这些字段能解释“为什么 result 为空”，同时不会把样本数据、SQL、文件内容或大对象泄露到事件流。
        """

        if resolution is None:
            return None
        reference = resolution.reference
        return {
            "toolCallId": feedback.tool_call_id,
            "toolName": feedback.tool_name,
            "decision": resolution.decision.value,
            "modelContextAllowed": resolution.model_context_allowed,
            "issues": resolution.issues,
            "resolverHint": resolution.resolver_hint,
            "referenceKind": reference.kind.value,
            "referenceUri": reference.uri,
            "workspaceKey": reference.workspace_key,
            "contextPolicy": reference.context_policy,
        }

    @staticmethod
    def _result_filter_event_summary(
        feedback: ToolExecutionFeedback,
        report: ModelResultContextFilterReport,
    ) -> dict[str, Any]:
        """生成适合 runtime event 的字段级过滤摘要。

        该摘要不包含过滤后的 result，只包含路径和动作统计，便于前端解释哪些字段被遮蔽、删除或截断。
        """

        return {
            "toolCallId": feedback.tool_call_id,
            "toolName": feedback.tool_name,
            "mode": report.mode,
            "includePaths": report.include_paths,
            "excludePaths": report.exclude_paths,
            "sensitivePaths": report.sensitive_paths,
            "missingPaths": report.missing_paths,
            "maskedPaths": report.masked_paths,
            "removedPaths": report.removed_paths,
            "truncatedPaths": report.truncated_paths,
            "outputTopLevelKeys": report.output_top_level_keys,
        }

    @staticmethod
    def _normalized_paths(paths: tuple[str, ...]) -> tuple[str, ...]:
        """清理空白路径并保持顺序去重。"""

        normalized: list[str] = []
        seen: set[str] = set()
        for path in paths:
            text = str(path).strip()
            if text and text not in seen:
                normalized.append(text)
                seen.add(text)
        return tuple(normalized)
