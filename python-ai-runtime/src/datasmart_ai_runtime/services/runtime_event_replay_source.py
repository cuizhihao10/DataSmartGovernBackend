"""Agent Runtime replay 外部事件源协调器。

Python AI Runtime 当前已经有本地 `RuntimeEventStore`，用于保存模型编排、上下文选择、工具规划和
二轮推理等 Python 侧事件；Java `agent-runtime` 也已经有 runtime-event 投影，用于保存工具审批、
执行中、成功、失败等 Java 控制面事件。真实产品里的“Agent 运行详情页”和 WebSocket 断线重连不应该
让用户看到两条割裂时间线，因此这里引入一个非常小的外部 replay source 协议：

- Python 本地 store 仍然负责 Python 侧短期事件；
- Java runtime-event 投影通过外部 source 接入；
- 后续 Redis Stream、Kafka compacted topic、ClickHouse 审计查询或对象存储归档，也可以按同一协议接入。

该模块只做“聚合与安全降级”，不做 HTTP、Kafka 或数据库细节。这样 WebSocket、HTTP replay 和单元测试
都可以复用同一套聚合规则。
"""

from __future__ import annotations

from dataclasses import dataclass, replace
from typing import Protocol

from datasmart_ai_runtime.domain.event_transport import RuntimeEventSubscriptionRequest
from datasmart_ai_runtime.domain.events import AgentRuntimeEvent


class RuntimeEventReplaySource(Protocol):
    """可插拔 runtime event replay 事件源协议。

    协议故意只保留一个 `replay(...)` 方法，是为了让上层状态机不关心下游到底来自 Java HTTP、Redis、
    Kafka、审计库还是测试 fake。对商业化 Agent 系统来说，这个抽象非常关键：事件来源会随着规模、
    成本和合规要求演进，但 WebSocket/replay 的用户协议不应该频繁变化。
    """

    @property
    def source_name(self) -> str:
        """返回事件源名称，用于诊断、脱敏后的 attributes 标记和错误定位。"""

    def replay(self, request: RuntimeEventSubscriptionRequest) -> tuple[AgentRuntimeEvent, ...]:
        """按订阅请求回放事件。

        实现方应尽量在源头按 tenant/project/actor/session/run/request/eventType 做范围过滤；
        协调器仍会在构建 envelope 前再次走统一过滤和可见性策略，形成“双保险”。
        """


@dataclass(frozen=True)
class RuntimeEventReplayCollection:
    """一次 replay 聚合结果。

    - `events`：本地事件与所有外部事件源合并后的候选事件；
    - `external_errors`：外部源失败摘要。失败不会阻断 subscribe/reconnect，这是为了让实时连接遵循
      fail-open 体验：Java replay 暂时不可用时，用户至少还能看到 Python 本地事件和连接状态。
    """

    events: tuple[AgentRuntimeEvent, ...]
    external_errors: tuple[dict[str, str], ...] = ()


class RuntimeEventReplayCoordinator:
    """聚合本地事件存储和外部 replay source。

    这里不直接构建 envelope，而是只返回候选事件集合。原因是 envelope 构建还需要走
    `RuntimeEventTransportBuilder` 的订阅过滤、角色可见性和字段脱敏逻辑；如果协调器自己提前打包，
    就容易出现某个入口绕过统一脱敏策略。
    """

    def __init__(self, external_sources: tuple[RuntimeEventReplaySource, ...] = ()) -> None:
        """初始化 replay 协调器。

        `external_sources` 可以为空。空值表示只使用 Python 本地事件 store，完全兼容当前测试和本地
        学习环境；生产或集成环境通过配置打开 Java source 即可进入“Python + Java 控制面”合并 replay。
        """

        self._external_sources = tuple(external_sources or ())

    @property
    def external_source_names(self) -> tuple[str, ...]:
        """返回已装配的外部 source 名称，供 diagnostics 展示。"""

        return tuple(source.source_name for source in self._external_sources)

    def collect(
        self,
        local_events: tuple[AgentRuntimeEvent, ...],
        request: RuntimeEventSubscriptionRequest,
    ) -> RuntimeEventReplayCollection:
        """收集本地与外部 replay 事件。

        设计要点：
        1. 外部 source 失败时记录错误摘要但不抛出，避免一个 Java 查询故障直接断开 WebSocket；
        2. 外部事件会被标记 `_datasmartReplaySource`，方便前端、诊断和后续审计判断事件来源；
        3. 外部事件如果没有 sequence，会生成临时 replay sequence。当前这是过渡策略，生产级方案应
           建立跨 Python/Java 的统一事件序列或持久化 outbox 游标。
        """

        if not self._external_sources:
            return RuntimeEventReplayCollection(events=tuple(local_events))

        merged_events: list[AgentRuntimeEvent] = list(local_events)
        external_errors: list[dict[str, str]] = []
        used_sequences = {event.sequence for event in local_events if event.sequence is not None}
        sequence_cursor = max(used_sequences | {max(0, request.after_sequence)})

        for source_index, source in enumerate(self._external_sources):
            try:
                external_events = source.replay(request)
            except Exception as exc:  # pragma: no cover - 具体网络异常由 source 单测覆盖
                external_errors.append(
                    {
                        "source": source.source_name,
                        "message": str(exc),
                    }
                )
                continue

            for ordinal, event in enumerate(external_events, start=1):
                rebound_event, sequence_cursor = self._normalize_external_event(
                    event=event,
                    source_name=source.source_name,
                    source_index=source_index,
                    ordinal=ordinal,
                    used_sequences=used_sequences,
                    sequence_cursor=sequence_cursor,
                )
                merged_events.append(rebound_event)
                if rebound_event.sequence is not None:
                    used_sequences.add(rebound_event.sequence)

        return RuntimeEventReplayCollection(
            events=tuple(merged_events),
            external_errors=tuple(external_errors),
        )

    @staticmethod
    def _normalize_external_event(
        *,
        event: AgentRuntimeEvent,
        source_name: str,
        source_index: int,
        ordinal: int,
        used_sequences: set[int],
        sequence_cursor: int,
    ) -> tuple[AgentRuntimeEvent, int]:
        """给外部事件补充来源标记和临时 replay sequence。

        Java 投影第一版里工具状态事件通常没有跨服务统一 sequence；如果直接把 sequence=None 的事件
        交给 transport builder，会被 replay 过滤掉。这里生成临时 sequence 是为了让前端能先看到工具
        状态进度。attributes 中保留原始 sequence 和 synthetic 标记，提醒后续生产化时需要统一序列。
        """

        original_sequence = event.sequence
        normalized_sequence = original_sequence
        if normalized_sequence is None or normalized_sequence in used_sequences:
            sequence_cursor += 1
            normalized_sequence = sequence_cursor
        else:
            sequence_cursor = max(sequence_cursor, normalized_sequence)

        attributes = dict(event.attributes)
        attributes.setdefault("_datasmartReplaySource", source_name)
        attributes.setdefault("_datasmartExternalReplayOrdinal", ordinal)
        attributes.setdefault("_datasmartExternalReplaySourceIndex", source_index)
        if original_sequence != normalized_sequence:
            attributes.setdefault("_datasmartOriginalSequence", original_sequence)
            attributes.setdefault("_datasmartSyntheticReplaySequence", True)

        return (
            replace(
                event,
                sequence=normalized_sequence,
                attributes=attributes,
            ),
            sequence_cursor,
        )
