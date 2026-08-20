"""RAG 检索路径的运行时决策器。

普通 RAG、GraphRAG 和两者联合并不是三个需要部署人员手工切换的系统。
它们是同一个受治理知识工具的三种执行路径：

* ``hybrid``：适合普通说明、手册、事故和任务案例检索；
* ``graph``：适合实体关系、血缘、组织上下级和有限跳数问题；
* ``hybrid_graph``：同时需要关系推理和原文依据时，联合返回文档引用与图路径。

本模块只负责回答“本次查询应该走哪条路径”，不负责读取知识库，也不负责执行图数据库查询。
这样可以把模型决策、证据召回、权限过滤和最终回答分别测试，并保证图数据库不可用时不会被
悄悄伪装成普通语义检索成功。
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from typing import Any

from datasmart_ai_runtime.domain.contracts import (
    ModelInvocationRequest,
    ModelMessage,
    WorkloadType,
)
from datasmart_ai_runtime.domain.model_gateway import ModelGatewayRequestContext
from datasmart_ai_runtime.services.model_gateway.model_provider_metadata import (
    build_model_provider_metadata,
)
from datasmart_ai_runtime.services.model_gateway.model_query_engine import (
    ModelQueryEngine,
    estimate_prompt_tokens,
)
from datasmart_ai_runtime.services.model_gateway.model_router import ModelRouteRegistry
from datasmart_ai_runtime.services.rag.models import RagQuery


RAG_RETRIEVAL_DECISION_MODES = frozenset({"hybrid", "graph", "hybrid_graph"})

# 这些词不是答案规则，只是模型不可用时的最后一道保守兜底。
# 关系问题如果没有图能力，不能硬猜成 GraphRAG 已经可用；后面管线仍会执行证据门禁。
_GRAPH_INTENT_TERMS = (
    "上级",
    "上司",
    "汇报",
    "下属",
    "直属",
    "负责人",
    "组织关系",
    "血缘",
    "依赖关系",
    "关系链",
    "链路",
    "父级",
    "子级",
    "第几跳",
    "上级的上级",
)
_DOCUMENT_SUPPORT_TERMS = (
    "文档",
    "手册",
    "案例",
    "事故",
    "日志",
    "原因",
    "处理",
    "配置",
    "依据",
    "证据",
    "引用",
    "说明",
)
_JSON_OBJECT_PATTERN = re.compile(r"\{.*\}", re.DOTALL)


@dataclass(frozen=True)
class RagRetrievalDecision:
    """一次检索路径决策及其可审计摘要。

    ``decision_source`` 只表示决策来自模型、规则兜底还是模型结果被能力约束调整；
    它不是模型可信度，也不是回答正确率。真正的答案仍然必须经过召回、重排、证据门禁和引用绑定。
    """

    mode: str
    decision_source: str
    reason: str
    confidence: float
    requested_mode: str = "auto"
    model_mode: str | None = None
    rule_signals: tuple[str, ...] = ()
    model_summary: dict[str, Any] = field(default_factory=dict)

    def to_summary(self) -> dict[str, Any]:
        """输出不包含模型原始输出的低敏决策摘要。"""

        return {
            "requestedRetrievalMode": self.requested_mode,
            "decisionMode": self.mode,
            "decisionSource": self.decision_source,
            "decisionReason": self.reason,
            "decisionConfidence": round(max(0.0, min(1.0, self.confidence)), 6),
            "modelDecisionMode": self.model_mode,
            "ruleSignals": self.rule_signals,
            "modelInvocation": dict(self.model_summary),
        }


class RagRetrievalDecisionRouter:
    """让模型在单次 RAG 查询内自主选择检索路径。

    设计要点：

    1. 只有请求显式使用 ``retrievalMode=auto`` 时才调用这个决策器；显式 ``graph``、``hybrid``
       等模式保持向后兼容，也方便黄金评测精确复现某条路径。
    2. 决策模型只返回很小的 JSON，不直接执行工具、不接触未授权文档，也不接收隐藏推理要求。
    3. Provider 失败、预算阻断、限流或 JSON 非法时，使用同一套中文规则信号兜底并记录来源。
    4. 图能力是否已装配会进入决策提示。模型不应选择当前运行时不存在的路径；若仍然选择，
       管线会保留原始模型选择并由 GraphRAG 的 fail-closed 分支处理，避免静默误答。
    """

    def __init__(
        self,
        *,
        model_routes: ModelRouteRegistry,
        query_engine: ModelQueryEngine | None,
        graph_available: bool,
        max_output_tokens: int = 160,
    ) -> None:
        """保存模型调用依赖与运行时能力快照。

        ``query_engine`` 可以为空，主要用于最小单元测试或极简离线环境；生产组合根会注入真实的
        DataSmart ``ModelQueryEngine``，因此模型调用仍统一经过预算、限流、Provider 健康和审计治理。
        """

        self._model_routes = model_routes
        self._query_engine = query_engine
        self._graph_available = bool(graph_available)
        self._max_output_tokens = max(64, min(int(max_output_tokens), 512))

    def decide(self, query: RagQuery) -> RagRetrievalDecision:
        """先让模型决策，失败时再执行保守规则兜底。

        该方法只在 ``auto`` 模式下被调用。它不会改变查询的租户、项目、操作者或敏感级别；
        这些字段仍由上游可信上下文和 RAG 管线负责校验。
        """

        rule_decision = self._rule_decision(query)
        if self._query_engine is None:
            return rule_decision

        try:
            route = self._model_routes.route_for(WorkloadType.AGENT_REASONING)
            messages = self._decision_messages(query)
            context = ModelGatewayRequestContext(
                tenant_id=query.tenant_id,
                project_id=query.project_id,
                actor_id=query.actor_id,
                workload=WorkloadType.AGENT_REASONING,
                estimated_prompt_tokens=estimate_prompt_tokens(messages),
                estimated_completion_tokens=self._max_output_tokens,
                trace_id=query.trace_id,
                attributes={
                    "source": "rag_retrieval_decision_router",
                    "graphAvailable": self._graph_available,
                    "decisionOnly": True,
                },
            )
            request = ModelInvocationRequest(
                route=route,
                messages=messages,
                temperature=0.0,
                max_output_tokens=self._max_output_tokens,
                trace_id=query.trace_id,
                tool_choice="none",
                available_tools=(),
                provider_metadata=build_model_provider_metadata(context),
            )
            result = self._query_engine.invoke(request, context=context)
            model_summary = result.to_summary()
            if result.result.error_code:
                return rule_decision_with_model_summary(
                    rule_decision,
                    model_summary,
                    reason="模型检索路径决策调用失败，已使用保守规则兜底。",
                )
            parsed = _parse_decision_json(result.result.content)
            if parsed is None:
                return rule_decision_with_model_summary(
                    rule_decision,
                    model_summary,
                    reason="模型检索路径决策不是合法 JSON，已使用保守规则兜底。",
                )
            model_mode = str(parsed.get("mode") or "").strip().lower()
            if model_mode not in RAG_RETRIEVAL_DECISION_MODES:
                return rule_decision_with_model_summary(
                    rule_decision,
                    model_summary,
                    reason="模型返回了不支持的检索路径，已使用保守规则兜底。",
                )
            if model_mode in {"graph", "hybrid_graph"} and not self._graph_available:
                # 模型只能在当前运行时已经装配的工具能力中做选择。提示词会告诉模型 GraphRAG 不可用，
                # 但外部 Provider 仍可能忽略这个事实；此时必须在执行前收敛到普通 Hybrid RAG，
                # 不能让管线进入一个不存在的图分支后再把不可用误报成成功。
                return RagRetrievalDecision(
                    mode="hybrid",
                    requested_mode="auto",
                    decision_source="MODEL_CAPABILITY_FALLBACK",
                    reason="模型选择了图检索，但当前运行时未装配 GraphRAG，已降级为普通混合检索。",
                    confidence=_bounded_float(parsed.get("confidence"), default=0.0),
                    model_mode=model_mode,
                    rule_signals=rule_decision.rule_signals,
                    model_summary=model_summary,
                )
            confidence = _bounded_float(parsed.get("confidence"), default=0.0)
            reason = _bounded_reason(parsed.get("reason")) or "模型根据问题结构选择了检索路径。"
            return RagRetrievalDecision(
                mode=model_mode,
                requested_mode="auto",
                decision_source="MODEL",
                reason=reason,
                confidence=confidence,
                model_mode=model_mode,
                rule_signals=rule_decision.rule_signals,
                model_summary=model_summary,
            )
        except Exception:
            # 这里不能把 endpoint、prompt 或 Provider 原始异常带到 API。模型调用的详细堆栈由上层
            # 受控日志记录，而对 RAG 结果只保留“已规则兜底”这一稳定事实。
            return rule_decision_with_model_summary(
                rule_decision,
                {},
                reason="模型检索路径决策发生受控异常，已使用保守规则兜底。",
            )

    def _rule_decision(self, query: RagQuery) -> RagRetrievalDecision:
        """根据问题中的关系与文档支持信号生成可解释兜底路径。"""

        question = str(query.question or "").strip().casefold()
        graph_signals = tuple(term for term in _GRAPH_INTENT_TERMS if term.casefold() in question)
        document_signals = tuple(term for term in _DOCUMENT_SUPPORT_TERMS if term.casefold() in question)
        signals = tuple(dict.fromkeys(graph_signals + document_signals))
        if graph_signals and self._graph_available:
            mode = "hybrid_graph" if document_signals else "graph"
            reason = "问题包含关系链信号，并根据是否需要原文依据选择图检索或联合检索。"
            confidence = 0.86 if mode == "graph" else 0.78
        else:
            mode = "hybrid"
            reason = (
                "问题没有明确关系链信号，或当前未装配 GraphRAG，因此使用普通混合检索。"
            )
            confidence = 0.64
        return RagRetrievalDecision(
            mode=mode,
            requested_mode="auto",
            decision_source="RULE_FALLBACK",
            reason=reason,
            confidence=confidence,
            rule_signals=signals,
        )

    def _decision_messages(self, query: RagQuery) -> tuple[ModelMessage, ...]:
        """构造最小决策提示，禁止模型输出隐藏推理或直接调用工具。"""

        return (
            ModelMessage(
                role="system",
                content=(
                    "你是 DataSmart Govern 的 RAG 路由决策节点。"
                    "只根据用户问题选择最合适的检索路径，不回答业务问题，不调用工具，不输出隐藏推理。"
                    "只能返回一个 JSON 对象："
                    '{"mode":"hybrid|graph|hybrid_graph","confidence":0到1之间的数字,'
                    '"reason":"不超过120字的公开理由"}。'
                    "hybrid 适合普通文档、日志、手册和案例；graph 适合实体关系、血缘和有限跳数；"
                    "hybrid_graph 适合既要关系推理又要原文依据。"
                ),
            ),
            ModelMessage(
                role="user",
                content=(
                    f"GraphRAG 当前可用：{'是' if self._graph_available else '否'}。\n"
                    f"用户问题：{query.question[:4000]}"
                ),
            ),
        )


def rule_decision_with_model_summary(
    decision: RagRetrievalDecision,
    model_summary: dict[str, Any],
    *,
    reason: str,
) -> RagRetrievalDecision:
    """把模型调用低敏摘要附加到规则兜底决策上。"""

    return RagRetrievalDecision(
        mode=decision.mode,
        requested_mode=decision.requested_mode,
        decision_source=decision.decision_source,
        reason=reason,
        confidence=decision.confidence,
        model_mode=None,
        rule_signals=decision.rule_signals,
        model_summary=model_summary,
    )


def _parse_decision_json(content: Any) -> dict[str, Any] | None:
    """从模型文本中提取一个 JSON 对象，兼容代码围栏和少量前后解释文字。"""

    text = str(content or "").strip()
    if not text:
        return None
    candidates = [text]
    match = _JSON_OBJECT_PATTERN.search(text)
    if match is not None:
        candidates.append(match.group(0))
    for candidate in candidates:
        try:
            parsed = json.loads(candidate)
        except (TypeError, ValueError):
            continue
        if isinstance(parsed, dict):
            return parsed
    return None


def _bounded_float(value: Any, *, default: float) -> float:
    """读取并限制模型返回的置信度。"""

    try:
        parsed = float(value)
    except (TypeError, ValueError):
        return default
    if parsed != parsed or parsed in {float("inf"), float("-inf")}:
        return default
    return max(0.0, min(1.0, parsed))


def _bounded_reason(value: Any) -> str:
    """限制模型公开理由长度，防止把长篇隐藏推理带入治理摘要。"""

    return str(value or "").strip()[:120]


__all__ = [
    "RAG_RETRIEVAL_DECISION_MODES",
    "RagRetrievalDecision",
    "RagRetrievalDecisionRouter",
]
