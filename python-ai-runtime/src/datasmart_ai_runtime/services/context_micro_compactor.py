"""上下文微压缩服务。

`HybridContextBuilder` 已经能做过滤、去重和 token 预算截断，但真实 Agent 产品还需要一个更细的
中间层：当上下文块很长时，不能只有“完整塞进模型”和“整块丢弃”两种选择。微压缩的目标是把长块
压缩成可解释、可审计、可控 token 的摘要，再交给后续模型意图节点。

本实现刻意不调用 LLM，而是使用确定性抽取策略，原因有三点：
1. 微压缩发生在模型调用之前，如果它本身依赖模型，就会形成额外成本、延迟和失败点；
2. 确定性抽取更容易写单元测试，也更容易向用户解释“为什么这段上下文被保留”；
3. 当前收敛阶段优先关闭工程闭环，后续如果接入专门的 summary model，也应替换这个服务背后的策略，
   而不是把摘要逻辑散落到编排器或 Provider 适配器中。
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from enum import Enum
from typing import Iterable

from datasmart_ai_runtime.domain.context import ContextBlock


class ContextMicroCompactionDecision(str, Enum):
    """单个上下文块的微压缩决策。"""

    KEPT = "kept"
    COMPACTED = "compacted"
    DISABLED = "disabled"


@dataclass(frozen=True)
class ContextMicroCompactionPolicy:
    """上下文微压缩策略。

    字段说明：
    - `enabled`：是否启用微压缩。保留开关是为了灰度和排障；如果某个租户发现摘要质量不够稳定，
      可以先回退到只做 token 截断。
    - `trigger_token_threshold`：单块上下文 token 估算超过该阈值时触发压缩。它不是最终模型窗口，
      而是“单块太长，应先摘要”的阈值。
    - `target_token_budget`：压缩后希望单块上下文控制在多少 token 左右。最终总预算仍由
      `ContextSelectionPolicy.max_tokens` 统一控制。
    - `max_segments`：最多保留多少个关键片段。片段越多，摘要越完整，但模型输入越长。
    - `max_segment_chars`：单个片段最长字符数，防止一行日志、SQL 或工具输出把摘要撑爆。
    - `minimum_saved_tokens`：只有预计节省 token 达到该值时才替换原块，避免短上下文被“压缩”后反而
      增加解释头和学习成本。
    """

    enabled: bool = True
    trigger_token_threshold: int = 256
    target_token_budget: int = 160
    max_segments: int = 6
    max_segment_chars: int = 220
    minimum_saved_tokens: int = 32


@dataclass(frozen=True)
class ContextMicroCompactionItem:
    """单个上下文块的微压缩结果摘要。

    该对象只保存低敏治理事实，不保存上下文正文。它可以安全进入 runtime event，用于解释本轮是否发生
    压缩、压缩前后 token 估算、可信度和原因码。
    """

    decision: ContextMicroCompactionDecision
    source_type: str
    sensitivity_level: str
    original_token_estimate: int
    compacted_token_estimate: int
    original_char_count: int
    compacted_char_count: int
    confidence: float
    reason_codes: tuple[str, ...]


@dataclass(frozen=True)
class ContextMicroCompactionReport:
    """一次上下文集合微压缩报告。

    `blocks` 是真正交给后续 token budget 选择和模型消息构造的上下文块集合；其他字段是低敏摘要，
    用于事件、诊断和能力矩阵说明。
    """

    blocks: tuple[ContextBlock, ...]
    items: tuple[ContextMicroCompactionItem, ...]
    original_token_estimate: int
    compacted_token_estimate: int
    original_char_count: int
    compacted_char_count: int

    @property
    def compacted_count(self) -> int:
        """返回真正发生内容压缩的块数量。"""

        return sum(1 for item in self.items if item.decision == ContextMicroCompactionDecision.COMPACTED)

    @property
    def kept_count(self) -> int:
        """返回保留原文的块数量。"""

        return sum(1 for item in self.items if item.decision == ContextMicroCompactionDecision.KEPT)

    @property
    def token_saved(self) -> int:
        """返回本轮预计节省的 token 数。"""

        return max(0, self.original_token_estimate - self.compacted_token_estimate)

    def should_record_event(self) -> bool:
        """判断是否值得写入 runtime event。

        只有真正发生压缩或明显节省 token 时才记录事件，避免短请求的事件流被无意义的“未压缩”事件刷屏。
        """

        return self.compacted_count > 0 or self.token_saved > 0

    def to_event_attributes(self) -> dict[str, object]:
        """输出低敏事件属性。

        事件不包含上下文正文、片段文本、prompt、SQL、样本数据、工具参数或模型输出；只包含计数、
        sourceType 分布、决策分布和 token/字符统计，适合进入前端时间线和 Java 控制面投影。
        """

        return {
            "snapshotType": "CONTEXT_MICRO_COMPACTION",
            "payloadPolicy": "LOW_SENSITIVE_CONTEXT_COMPACTION_METADATA_ONLY",
            "blockCount": len(self.blocks),
            "compactedCount": self.compacted_count,
            "keptCount": self.kept_count,
            "originalTokenEstimate": self.original_token_estimate,
            "compactedTokenEstimate": self.compacted_token_estimate,
            "tokenSaved": self.token_saved,
            "originalCharCount": self.original_char_count,
            "compactedCharCount": self.compacted_char_count,
            "decisionCounts": self._decision_counts(),
            "sourceTypeCounts": self._source_type_counts(),
            "reasonCodes": self._reason_codes(),
            "averageConfidence": self._average_confidence(),
        }

    def _decision_counts(self) -> dict[str, int]:
        """按决策聚合数量。"""

        counts: dict[str, int] = {}
        for item in self.items:
            counts[item.decision.value] = counts.get(item.decision.value, 0) + 1
        return counts

    def _source_type_counts(self) -> dict[str, int]:
        """按上下文来源类型聚合数量。"""

        counts: dict[str, int] = {}
        for item in self.items:
            counts[item.source_type] = counts.get(item.source_type, 0) + 1
        return counts

    def _reason_codes(self) -> tuple[str, ...]:
        """汇总去重后的原因码。"""

        codes: list[str] = []
        for item in self.items:
            for code in item.reason_codes:
                if code not in codes:
                    codes.append(code)
        return tuple(codes)

    def _average_confidence(self) -> float:
        """计算摘要平均可信度。"""

        if not self.items:
            return 1.0
        total = sum(item.confidence for item in self.items)
        return round(total / len(self.items), 4)


class ContextMicroCompactor:
    """确定性上下文微压缩器。

    该类只负责“把过长上下文压成更短的上下文块”。它不决定上下文是否允许进入模型，也不决定最终总
    token 预算，这些仍由 `HybridContextBuilder` 负责。这样职责边界比较清楚：
    - HybridContextBuilder：来源治理、过滤、去重、排序、总体预算；
    - ContextMicroCompactor：单块长文本的压缩与低敏压缩报告；
    - AgentModelIntentNode：只消费已经治理好的上下文，不关心压缩细节。
    """

    _IMPORTANT_KEYWORDS = (
        "必须",
        "禁止",
        "权限",
        "审批",
        "风险",
        "失败",
        "错误",
        "重试",
        "租户",
        "项目",
        "数据源",
        "质量",
        "任务",
        "工具",
        "模型",
        "缓存",
        "限流",
        "token",
        "policy",
        "status",
        "error",
        "retry",
        "approval",
    )

    _SQL_KEYWORDS = re.compile(r"\b(select|insert|update|delete|merge|drop|alter|truncate)\b", re.IGNORECASE)
    _SECRET_ASSIGNMENT = re.compile(
        r"(?i)\b(api[_-]?key|secret|password|passwd|token|authorization|bearer|private[_ -]?key)\b\s*[:=]\s*([^\s,;]+)"
    )
    _LONG_SECRET_LIKE_TOKEN = re.compile(r"\b[A-Za-z0-9_\-]{36,}\b")

    def __init__(self, policy: ContextMicroCompactionPolicy | None = None) -> None:
        self._policy = policy or ContextMicroCompactionPolicy()

    def compact(
        self,
        blocks: Iterable[ContextBlock],
        policy: ContextMicroCompactionPolicy | None = None,
    ) -> ContextMicroCompactionReport:
        """压缩上下文集合。

        输入输出说明：
        - 输入是已经通过过滤和去重的 `ContextBlock` 集合；
        - 输出中的 `blocks` 仍然是 `ContextBlock`，因此下游无需适配新类型；
        - 如果某块没有达到触发阈值，会原样保留；
        - 如果策略关闭，也会原样保留，但报告中会标记 `disabled`。
        """

        effective_policy = policy or self._policy
        compacted_blocks: list[ContextBlock] = []
        items: list[ContextMicroCompactionItem] = []
        original_token_total = 0
        compacted_token_total = 0
        original_char_total = 0
        compacted_char_total = 0

        for block in tuple(blocks):
            original_tokens = self._token_cost(block)
            original_chars = len(block.content)
            original_token_total += original_tokens
            original_char_total += original_chars

            if not effective_policy.enabled:
                compacted_blocks.append(block)
                compacted_token_total += original_tokens
                compacted_char_total += original_chars
                items.append(self._item(block, ContextMicroCompactionDecision.DISABLED, original_tokens, original_tokens, original_chars, original_chars, 1.0, ("policy_disabled",)))
                continue

            if original_tokens < effective_policy.trigger_token_threshold:
                compacted_blocks.append(block)
                compacted_token_total += original_tokens
                compacted_char_total += original_chars
                items.append(self._item(block, ContextMicroCompactionDecision.KEPT, original_tokens, original_tokens, original_chars, original_chars, 1.0, ("below_trigger_threshold",)))
                continue

            compacted_block = self._compact_block(block, effective_policy)
            compacted_tokens = self._token_cost(compacted_block)
            compacted_chars = len(compacted_block.content)
            saved_tokens = original_tokens - compacted_tokens
            if saved_tokens < effective_policy.minimum_saved_tokens:
                compacted_blocks.append(block)
                compacted_token_total += original_tokens
                compacted_char_total += original_chars
                items.append(self._item(block, ContextMicroCompactionDecision.KEPT, original_tokens, original_tokens, original_chars, original_chars, 0.9, ("saving_too_small",)))
                continue

            compacted_blocks.append(compacted_block)
            compacted_token_total += compacted_tokens
            compacted_char_total += compacted_chars
            items.append(
                self._item(
                    block,
                    ContextMicroCompactionDecision.COMPACTED,
                    original_tokens,
                    compacted_tokens,
                    original_chars,
                    compacted_chars,
                    self._confidence(original_tokens, compacted_tokens, compacted_chars),
                    ("block_over_threshold", "deterministic_extract_summary"),
                )
            )

        return ContextMicroCompactionReport(
            blocks=tuple(compacted_blocks),
            items=tuple(items),
            original_token_estimate=original_token_total,
            compacted_token_estimate=compacted_token_total,
            original_char_count=original_char_total,
            compacted_char_count=compacted_char_total,
        )

    def _compact_block(self, block: ContextBlock, policy: ContextMicroCompactionPolicy) -> ContextBlock:
        """压缩单个上下文块。

        压缩策略是“抽取式摘要”：先拆成片段，再按治理关键词、首尾位置和长度评分，最后按原文顺序
        拼回摘要。这样不会凭空生成新事实，适合权限、策略、任务状态和工具结果等需要保守解释的场景。
        """

        segments = self._select_segments(block.content, policy)
        compacted_content = self._build_compacted_content(block, segments, policy)
        compacted_tokens = self._estimate_tokens(block.title, compacted_content)
        metadata = dict(block.metadata)
        metadata["microCompact"] = {
            "applied": True,
            "policy": "deterministic_extract_summary",
            "originalTokenEstimate": self._token_cost(block),
            "compactedTokenEstimate": compacted_tokens,
            "confidence": self._confidence(self._token_cost(block), compacted_tokens, len(compacted_content)),
            "reasonCodes": ("block_over_threshold", "deterministic_extract_summary"),
        }
        return ContextBlock(
            source_type=block.source_type,
            title=f"{block.title}（微压缩）",
            content=compacted_content,
            relevance_score=block.relevance_score,
            metadata=metadata,
            sensitivity_level=block.sensitivity_level,
            source_id=block.source_id,
            expires_at=block.expires_at,
            token_estimate=compacted_tokens,
        )

    def _select_segments(self, content: str, policy: ContextMicroCompactionPolicy) -> tuple[str, ...]:
        """选择摘要片段。

        片段选择不是按原文前 N 行截断，而是优先保留治理关键词命中的句子。这样长上下文里真正影响
        权限、审批、风险、重试、限流、状态流转的内容更容易留下来。
        """

        raw_segments = [segment.strip() for segment in re.split(r"[\r\n]+|(?<=[。！？!?；;])", content) if segment.strip()]
        if not raw_segments:
            return ("原始上下文为空或只有空白字符。",)

        scored: list[tuple[int, int, str]] = []
        for index, segment in enumerate(raw_segments):
            clean_segment = self._truncate_segment(self._redact_sensitive_markers(segment), policy.max_segment_chars)
            score = self._segment_score(clean_segment, index, len(raw_segments))
            scored.append((score, index, clean_segment))

        selected = sorted(scored, key=lambda item: (-item[0], item[1]))[: policy.max_segments]
        selected_in_order = [segment for _, _, segment in sorted(selected, key=lambda item: item[1])]
        return tuple(selected_in_order)

    def _build_compacted_content(
        self,
        block: ContextBlock,
        segments: tuple[str, ...],
        policy: ContextMicroCompactionPolicy,
    ) -> str:
        """构建模型可读的微压缩内容。

        摘要头保留来源、敏感级别和压缩策略，让模型知道这不是原始全文；摘要尾提醒模型不要把摘要当成
        已执行事实，真实工具执行仍要回到 Java 控制面。
        """

        header = (
            "[上下文微压缩摘要]"
            f" 来源={block.source_type.value};"
            f" 敏感级别={block.sensitivity_level.value};"
            f" 原始token约={self._token_cost(block)};"
            f" 目标token约={policy.target_token_budget};"
            " 策略=deterministic_extract_summary。"
        )
        body = "\n".join(f"- {segment}" for segment in segments)
        footer = "说明：该摘要由确定性抽取生成，不新增业务事实；真实执行、审批和权限仍以控制面事实为准。"
        return f"{header}\n{body}\n{footer}"

    def _segment_score(self, segment: str, index: int, total: int) -> int:
        """给候选片段评分。

        首尾片段常常包含背景和结论，因此获得基础分；治理关键词命中会显著加分；过长片段即使重要也会
        被轻微扣分，鼓励摘要保留更清晰的短句。
        """

        score = 0
        if index == 0:
            score += 3
        if index == total - 1:
            score += 2
        normalized = segment.lower()
        for keyword in self._IMPORTANT_KEYWORDS:
            if keyword.lower() in normalized:
                score += 3
        if len(segment) <= 120:
            score += 1
        return score

    def _redact_sensitive_markers(self, text: str) -> str:
        """对明显敏感片段做保守脱敏。

        微压缩摘要仍会进入模型上下文，因此这里先处理最典型的高风险模式：密钥赋值、Bearer token、
        长随机串和显式 SQL 语句。它不是完整 DLP，但可以避免摘要阶段把最危险的正文继续扩散。
        """

        redacted = self._SECRET_ASSIGNMENT.sub(lambda match: f"{match.group(1)}=<redacted>", text)
        redacted = self._LONG_SECRET_LIKE_TOKEN.sub("<redacted-token>", redacted)
        if self._SQL_KEYWORDS.search(redacted):
            return "[SQL语句已省略：微压缩只保留存在 SQL/变更风险的事实，不传播原始语句。]"
        return redacted

    @staticmethod
    def _truncate_segment(segment: str, max_chars: int) -> str:
        """限制单个摘要片段长度。"""

        if len(segment) <= max_chars:
            return segment
        return f"{segment[: max_chars - 1]}…"

    @staticmethod
    def _item(
        block: ContextBlock,
        decision: ContextMicroCompactionDecision,
        original_tokens: int,
        compacted_tokens: int,
        original_chars: int,
        compacted_chars: int,
        confidence: float,
        reason_codes: tuple[str, ...],
    ) -> ContextMicroCompactionItem:
        """创建单块低敏压缩摘要。"""

        return ContextMicroCompactionItem(
            decision=decision,
            source_type=block.source_type.value,
            sensitivity_level=block.sensitivity_level.value,
            original_token_estimate=original_tokens,
            compacted_token_estimate=compacted_tokens,
            original_char_count=original_chars,
            compacted_char_count=compacted_chars,
            confidence=confidence,
            reason_codes=reason_codes,
        )

    @staticmethod
    def _confidence(original_tokens: int, compacted_tokens: int, compacted_chars: int) -> float:
        """估算摘要可信度。

        这里的可信度不是语义正确率，而是“确定性摘要足以代表原上下文的保守程度”。压缩越激进，
        分数越低；摘要过短也会降低分数，提醒下游和用户不要过度依赖摘要细节。
        """

        if original_tokens <= 0:
            return 1.0
        ratio = compacted_tokens / original_tokens
        length_bonus = min(0.15, compacted_chars / 2000)
        confidence = 0.95 - max(0.0, 1 - ratio) * 0.35 + length_bonus
        return round(max(0.5, min(0.98, confidence)), 4)

    @staticmethod
    def _token_cost(block: ContextBlock) -> int:
        """读取上下文 token 估算，缺失时按内容长度兜底。"""

        if block.token_estimate > 0:
            return block.token_estimate
        return ContextMicroCompactor._estimate_tokens(block.title, block.content)

    @staticmethod
    def _estimate_tokens(title: str, content: str) -> int:
        """使用与默认上下文构建器一致的启发式 token 估算。"""

        total_chars = len(title) + len(content)
        return max(1, (total_chars + 3) // 4)
