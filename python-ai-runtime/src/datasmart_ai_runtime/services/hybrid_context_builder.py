"""混合上下文构建器。

`DefaultContextBuilder` 只负责生成本地规则式上下文；未来还会出现 Java 控制面上下文、GraphRAG
上下文、向量检索上下文、历史事故上下文等多个来源。`HybridContextBuilder` 的职责不是亲自检索，
而是把多个来源的结果做统一治理：过滤过期内容、控制敏感级别、去重、排序和 token 预算截断。

这层非常关键，因为商业化 Agent 真正接入 RAG/GraphRAG 后，最容易失控的不是“检索不到内容”，
而是“检索太多、太旧、太敏感、重复或超预算的内容”，最终导致模型成本升高、泄露风险增加、
回答质量下降和审计不可解释。
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone

from datasmart_ai_runtime.domain.context import ContextBlock, ContextSensitivityLevel
from datasmart_ai_runtime.domain.contracts import AgentRequest
from datasmart_ai_runtime.domain.events import (
    AgentRuntimeEvent,
    AgentRuntimeEventSeverity,
    AgentRuntimeEventType,
)
from datasmart_ai_runtime.services.context_builder import ContextBuilder, DefaultContextBuilder
from datasmart_ai_runtime.services.runtime_events.runtime_event_recorder import RuntimeEventRecorder


@dataclass(frozen=True)
class ContextSelectionPolicy:
    """上下文选择策略。

    字段说明：
    - `max_tokens`：本轮最多允许进入 Agent 的上下文 token 预算。后续可按模型上下文长度动态调整。
    - `allowed_sensitivity_levels`：允许进入模型上下文的敏感级别集合。默认不允许 `RESTRICTED`。
    - `keep_at_least_one`：预算太小时是否至少保留一个最高优先级上下文块，避免完全空上下文。
    """

    max_tokens: int = 2048
    allowed_sensitivity_levels: tuple[ContextSensitivityLevel, ...] = (
        ContextSensitivityLevel.PUBLIC,
        ContextSensitivityLevel.INTERNAL,
        ContextSensitivityLevel.CONFIDENTIAL,
    )
    keep_at_least_one: bool = True


class HybridContextBuilder:
    """组合多个上下文来源并应用治理策略。

    该类实现 `ContextBuilder` 协议，可直接注入 `AgentOrchestrator`。当前默认只包装
    `DefaultContextBuilder`，但构造函数已经支持多个 builder，后续接入 Java/GraphRAG 时不需要
    改编排器，只要把新的 builder 加进来。
    """

    def __init__(
        self,
        builders: tuple[ContextBuilder, ...] | None = None,
        policy: ContextSelectionPolicy | None = None,
    ) -> None:
        self._builders = builders or (DefaultContextBuilder(),)
        self._policy = policy or ContextSelectionPolicy()
        self._last_events: tuple[AgentRuntimeEvent, ...] = ()

    def build(
        self,
        request: AgentRequest,
        event_recorder: RuntimeEventRecorder | None = None,
    ) -> tuple[ContextBlock, ...]:
        """构建并治理上下文块。

        处理顺序：
        1. 从多个 builder 收集上下文；
        2. 丢弃过期块和不允许进入模型的敏感级别；
        3. 按 sourceId 或内容指纹去重；
        4. 按相关性、敏感级别、token 大小排序；
        5. 按 token 预算截断。
        """

        events: list[AgentRuntimeEvent] = []
        collected: list[ContextBlock] = []
        for builder in self._builders:
            collected.extend(self._build_from_source(builder, request, event_recorder))

        self._record(
            events,
            request,
            event_recorder,
            AgentRuntimeEventType.CONTEXT_COLLECTED,
            "已从上下文来源收集候选上下文块。",
            attributes={
                "builderCount": len(self._builders),
                "collectedCount": len(collected),
            },
        )

        filtered = self._filter_contexts(collected, request, events, event_recorder)
        deduplicated = self._deduplicate(filtered, request, events, event_recorder)
        ordered = sorted(deduplicated, key=self._sort_key)
        selected = self._apply_token_budget(ordered, request, events, event_recorder)
        self._record(
            events,
            request,
            event_recorder,
            AgentRuntimeEventType.CONTEXT_SELECTED,
            "已完成上下文选择，生成本轮可进入 Agent 计划的上下文集合。",
            attributes={
                "selectedCount": len(selected),
                "selectedSourceIds": tuple(block.source_id for block in selected if block.source_id),
                "tokenEstimate": sum(self._token_cost(block) for block in selected),
            },
        )
        self._last_events = tuple(events)
        return tuple(selected)

    def last_events(self) -> tuple[AgentRuntimeEvent, ...]:
        """返回最近一次上下文构建产生的事件。

        当前 Python Runtime 还没有真正的请求级事件总线，因此先保留“最近一次构建事件”用于测试和
        API 调试。生产环境更推荐把事件写入请求级 `EventRecorder`，避免单例对象并发调用时互相覆盖。
        """

        return self._last_events

    @staticmethod
    def _build_from_source(
        builder: ContextBuilder,
        request: AgentRequest,
        event_recorder: RuntimeEventRecorder | None,
    ) -> tuple[ContextBlock, ...]:
        """调用单个上下文来源，并尽量把请求级 recorder 传递下去。

        这里保留兼容逻辑：旧 builder 只实现 `build(request)` 时仍然可以工作；新 builder 可以实现
        `build(request, event_recorder)` 并写入自己的检索事件。这样后续接 Java 控制面上下文或
        GraphRAG 上下文时，不需要一次性重写所有已有构建器。
        """

        try:
            return builder.build(request, event_recorder)
        except TypeError:
            return builder.build(request)

    def _filter_contexts(
        self,
        blocks: list[ContextBlock],
        request: AgentRequest,
        events: list[AgentRuntimeEvent],
        event_recorder: RuntimeEventRecorder | None,
    ) -> list[ContextBlock]:
        """过滤过期或敏感级别不允许的上下文，并记录原因。

        过去过滤动作是一个列表推导式，虽然简洁，但无法解释“为什么某些上下文没有进入模型”。这里
        显式拆出方法并产生事件，是为了让前端调试面板和审计回放能看到上下文治理决策。
        """

        selected: list[ContextBlock] = []
        for block in blocks:
            if self._is_expired(block):
                self._record_context_decision(
                    events,
                    request,
                    event_recorder,
                    AgentRuntimeEventType.CONTEXT_FILTERED,
                    block,
                    "上下文已过期，已从本轮 Agent 输入中移除。",
                    "expired",
                )
                continue
            if block.sensitivity_level not in self._policy.allowed_sensitivity_levels:
                self._record_context_decision(
                    events,
                    request,
                    event_recorder,
                    AgentRuntimeEventType.CONTEXT_FILTERED,
                    block,
                    "上下文敏感级别不在当前模型准入范围内，已被过滤。",
                    "sensitivity_not_allowed",
                )
                continue
            selected.append(block)
        return selected

    def _deduplicate(
        self,
        blocks: list[ContextBlock],
        request: AgentRequest,
        events: list[AgentRuntimeEvent],
        event_recorder: RuntimeEventRecorder | None,
    ) -> list[ContextBlock]:
        """按来源 ID 或内容指纹去重。

        多个检索源可能返回同一条权限事实或同一份规则案例。这里先用稳定 key 去重，并保留排序前
        相关性更高、token 更小的块。后续真实场景可把更新时间、来源可信度也纳入选择。
        """

        selected: dict[str, ContextBlock] = {}
        for block in blocks:
            key = self._dedupe_key(block)
            current = selected.get(key)
            if current is None or self._prefer(block, current):
                if current is not None:
                    self._record_context_decision(
                        events,
                        request,
                        event_recorder,
                        AgentRuntimeEventType.CONTEXT_DEDUPLICATED,
                        current,
                        "检测到重复上下文，已保留相关性更高或 token 成本更低的版本。",
                        "duplicate_replaced",
                    )
                selected[key] = block
            else:
                self._record_context_decision(
                    events,
                    request,
                    event_recorder,
                    AgentRuntimeEventType.CONTEXT_DEDUPLICATED,
                    block,
                    "检测到重复上下文，当前版本优先级较低，已被去重移除。",
                    "duplicate_lower_priority",
                )
        return list(selected.values())

    def _apply_token_budget(
        self,
        blocks: list[ContextBlock],
        request: AgentRequest,
        events: list[AgentRuntimeEvent],
        event_recorder: RuntimeEventRecorder | None,
    ) -> list[ContextBlock]:
        """按 token 预算截断上下文。

        预算控制不能简单按条数截断，因为一个块可能很长，一个块可能很短。当前用 `token_estimate`
        做粗略预算；未来接真实 tokenizer 后，可以把估算值替换为模型族精确 token。
        """

        selected: list[ContextBlock] = []
        used_tokens = 0
        for block in blocks:
            token_cost = self._token_cost(block)
            if used_tokens + token_cost <= self._policy.max_tokens:
                selected.append(block)
                used_tokens += token_cost
            elif self._policy.keep_at_least_one and not selected:
                selected.append(block)
                self._record_context_decision(
                    events,
                    request,
                    event_recorder,
                    AgentRuntimeEventType.CONTEXT_TRUNCATED,
                    block,
                    "上下文超过 token 预算，但策略要求至少保留一个最高优先级上下文。",
                    "kept_over_budget",
                )
                break
            else:
                self._record_context_decision(
                    events,
                    request,
                    event_recorder,
                    AgentRuntimeEventType.CONTEXT_TRUNCATED,
                    block,
                    "上下文超过本轮 token 预算，已从模型输入中截断。",
                    "token_budget_exceeded",
                )
        return selected

    def _record_context_decision(
        self,
        events: list[AgentRuntimeEvent],
        request: AgentRequest,
        event_recorder: RuntimeEventRecorder | None,
        event_type: AgentRuntimeEventType,
        block: ContextBlock,
        message: str,
        reason: str,
    ) -> None:
        """记录单个上下文块的治理决策。

        attributes 中保留 sourceId、sourceType、sensitivityLevel、tokenEstimate 等机器字段，后续前端
        可以做筛选，审计系统也可以根据 reason 聚合“为什么上下文被过滤或截断”。
        """

        self._record(
            events,
            request,
            event_recorder,
            event_type,
            message,
            severity=AgentRuntimeEventSeverity.WARNING,
            attributes={
                "reason": reason,
                "sourceId": block.source_id,
                "sourceType": block.source_type.value,
                "title": block.title,
                "sensitivityLevel": block.sensitivity_level.value,
                "tokenEstimate": self._token_cost(block),
                "relevanceScore": block.relevance_score,
            },
        )

    @staticmethod
    def _record(
        events: list[AgentRuntimeEvent],
        request: AgentRequest,
        event_recorder: RuntimeEventRecorder | None,
        event_type: AgentRuntimeEventType,
        message: str,
        severity: AgentRuntimeEventSeverity = AgentRuntimeEventSeverity.INFO,
        attributes: dict[str, object] | None = None,
    ) -> None:
        """写入上下文构建事件。

        这里暂时写入内存列表。等智能网关事件总线落地后，可以把这个方法替换为注入式 recorder，
        将事件同步推给 WebSocket、Kafka 或审计表。
        """

        if event_recorder is not None:
            events.append(
                event_recorder.record(
                    event_type=event_type,
                    stage="build_context",
                    message=message,
                    severity=severity,
                    attributes=dict(attributes or {}),
                )
            )
            return
        events.append(
            AgentRuntimeEvent(
                event_type=event_type,
                stage="build_context",
                message=message,
                severity=severity,
                tenant_id=request.tenant_id,
                project_id=request.project_id,
                actor_id=request.actor_id,
                attributes=dict(attributes or {}),
            )
        )

    @staticmethod
    def _dedupe_key(block: ContextBlock) -> str:
        """生成去重 key。"""

        if block.source_id:
            return block.source_id
        return f"{block.source_type}:{block.title}:{block.content}"

    @staticmethod
    def _prefer(candidate: ContextBlock, current: ContextBlock) -> bool:
        """判断候选块是否优于当前块。"""

        if candidate.relevance_score != current.relevance_score:
            return candidate.relevance_score > current.relevance_score
        return HybridContextBuilder._token_cost(candidate) < HybridContextBuilder._token_cost(current)

    @staticmethod
    def _sort_key(block: ContextBlock) -> tuple[float, int, int]:
        """排序 key。

        Python 默认升序，所以相关性使用负数。敏感级别越低越优先，token 越小越优先。
        这不是说高敏感上下文不重要，而是当相关性接近时，优先选择更安全、更短的上下文。
        """

        return (-block.relevance_score, HybridContextBuilder._sensitivity_rank(block), HybridContextBuilder._token_cost(block))

    @staticmethod
    def _sensitivity_rank(block: ContextBlock) -> int:
        """敏感级别排序权重。"""

        ranks = {
            ContextSensitivityLevel.PUBLIC: 0,
            ContextSensitivityLevel.INTERNAL: 1,
            ContextSensitivityLevel.CONFIDENTIAL: 2,
            ContextSensitivityLevel.RESTRICTED: 3,
        }
        return ranks[block.sensitivity_level]

    @staticmethod
    def _token_cost(block: ContextBlock) -> int:
        """返回上下文块 token 成本，确保最小为 1。"""

        return max(1, block.token_estimate)

    @staticmethod
    def _is_expired(block: ContextBlock) -> bool:
        """判断上下文是否过期。

        如果 `expires_at` 为空，表示该上下文没有明确过期时间，当前先视为可用。若出现 naive
        datetime，则按 UTC 解释，避免本地时区导致测试和部署行为不一致。
        """

        if block.expires_at is None:
            return False
        expires_at = block.expires_at
        if expires_at.tzinfo is None:
            expires_at = expires_at.replace(tzinfo=timezone.utc)
        return expires_at <= datetime.now(timezone.utc)
