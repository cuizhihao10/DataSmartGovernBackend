"""流式模型工具调用聚合器。

OpenAI-compatible streaming 中的 `delta.tool_calls` 不是一次性完整对象，而是按 `index` 分片返回：
首片段可能包含 id/type/name，后续片段通常只包含一小段 arguments 字符串。Provider 已经把底层
SSE 解析为 `ModelToolCallDelta`，本文件负责把这些增量聚合为完整 `ModelToolCall`。

聚合器仍然不执行工具，也不信任参数 JSON。它只是把“模型流式协议碎片”整理成“平台可校验的工具调用
候选”，后续还必须进入参数 schema 校验、权限判断、人工审批、审计落库和 Java agent-runtime 执行。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Iterable

from datasmart_ai_runtime.domain.contracts import ModelInvocationChunk, ModelToolCall, ModelToolCallDelta


@dataclass(frozen=True)
class ModelToolCallAssemblyIssue:
    """工具调用聚合过程中的问题。

    真实 OpenAI-compatible 生态并不完全一致：有些网关可能缺失 tool call id，有些会把 name 拆片，
    有些在网络中断时只返回部分 arguments。因此聚合器不应该简单抛异常，而应输出可审计的问题列表，
    让上层决定是追问、重试、丢弃候选，还是进入人工诊断。
    """

    index: int
    code: str
    message: str


@dataclass(frozen=True)
class ModelToolCallAssemblyReport:
    """工具调用聚合报告。

    字段说明：
    - `tool_calls`：已经按 index 聚合出的工具调用候选；
    - `issues`：聚合期间发现的结构问题，例如缺少 name、缺少 arguments 或缺少 call_id；
    - `source_chunk_count`：参与聚合的模型 chunk 数，用于指标和排障；
    - `source_delta_count`：参与聚合的 tool call delta 数，用于判断模型是否产生了工具调用碎片。
    """

    tool_calls: tuple[ModelToolCall, ...] = ()
    issues: tuple[ModelToolCallAssemblyIssue, ...] = ()
    source_chunk_count: int = 0
    source_delta_count: int = 0

    @property
    def has_issues(self) -> bool:
        """是否存在聚合问题。"""

        return bool(self.issues)


@dataclass
class _MutableToolCallAssembly:
    """单个 index 对应的可变聚合状态。

    内部使用可变对象，是为了按流式 delta 逐步追加 name 和 arguments；对外仍返回不可变
    `ModelToolCallAssemblyReport`，避免调用方误改聚合结果。
    """

    index: int
    call_id: str | None = None
    type: str | None = None
    name_parts: list[str] = field(default_factory=list)
    argument_parts: list[str] = field(default_factory=list)
    raw_deltas: list[dict] = field(default_factory=list)

    def append(self, delta: ModelToolCallDelta) -> None:
        """追加一个工具调用增量。

        OpenAI 官方示例通常按 `final_tool_calls[index].function.arguments += delta` 的方式拼接参数。
        这里同样按 index 聚合，但额外保留 raw_delta，方便后续审计和兼容不同模型网关行为。
        """

        if delta.call_id:
            self.call_id = self.call_id or delta.call_id
        if delta.type:
            self.type = self.type or delta.type
        if delta.name_delta:
            self.name_parts.append(delta.name_delta)
        if delta.arguments_delta:
            self.argument_parts.append(delta.arguments_delta)
        if delta.raw_delta:
            self.raw_deltas.append(delta.raw_delta)

    def to_tool_call(self) -> ModelToolCall:
        """转换为完整工具调用候选。

        注意：`arguments` 这里只做字符串拼接，不做 JSON 解析。模型生成的参数必须在执行前由
        `ToolParameterValidator` 或未来 JSON Schema 校验器处理，不能因为拼接成功就视为可信。
        """

        return ModelToolCall(
            call_id=self.call_id,
            type=self.type or "function",
            name="".join(self.name_parts),
            arguments="".join(self.argument_parts),
            raw_call={
                "source": "streaming_tool_call_delta_aggregator",
                "index": self.index,
                "rawDeltas": tuple(self.raw_deltas),
            },
        )


class ModelToolCallDeltaAggregator:
    """把 streaming tool call delta 聚合为完整工具调用候选。

    使用方式可以是两种：
    - 一次性调用 `aggregate_chunks(chunks)`，适合单元测试、同步处理或 replay；
    - 持续调用 `accept_chunk(chunk)`，最后调用 `build_report()`，适合 WebSocket/SSE 实时消费。

    这种设计能同时服务“实时 UI 展示”和“断线后 replay 聚合”：实时场景可以边收边更新聚合状态，
    replay 场景可以把历史 chunk 重新喂给聚合器得到同样结果。
    """

    def __init__(self) -> None:
        self._assemblies: dict[int, _MutableToolCallAssembly] = {}
        self._source_chunk_count = 0
        self._source_delta_count = 0

    def accept_chunk(self, chunk: ModelInvocationChunk) -> None:
        """接收一条模型流式 chunk。

        chunk 可能只包含文本 delta，也可能同时包含一个或多个 tool call delta。文本 delta 会被忽略，
        因为本聚合器只负责工具调用候选；普通文本仍由前端展示或模型响应汇总逻辑处理。
        """

        self._source_chunk_count += 1
        for delta in chunk.tool_call_deltas:
            self.accept_delta(delta)

    def accept_delta(self, delta: ModelToolCallDelta) -> None:
        """接收一条工具调用增量。"""

        self._source_delta_count += 1
        assembly = self._assemblies.setdefault(delta.index, _MutableToolCallAssembly(index=delta.index))
        assembly.append(delta)

    def build_report(self) -> ModelToolCallAssemblyReport:
        """构建当前聚合报告。"""

        tool_calls: list[ModelToolCall] = []
        issues: list[ModelToolCallAssemblyIssue] = []
        for index in sorted(self._assemblies):
            assembly = self._assemblies[index]
            tool_call = assembly.to_tool_call()
            tool_calls.append(tool_call)
            issues.extend(self._validate_tool_call(index, tool_call))
        return ModelToolCallAssemblyReport(
            tool_calls=tuple(tool_calls),
            issues=tuple(issues),
            source_chunk_count=self._source_chunk_count,
            source_delta_count=self._source_delta_count,
        )

    def aggregate_chunks(self, chunks: Iterable[ModelInvocationChunk]) -> ModelToolCallAssemblyReport:
        """一次性聚合一组模型流式 chunk。"""

        for chunk in chunks:
            self.accept_chunk(chunk)
        return self.build_report()

    @classmethod
    def from_chunks(cls, chunks: Iterable[ModelInvocationChunk]) -> ModelToolCallAssemblyReport:
        """便捷构造：从 chunk 集合直接返回聚合报告。"""

        return cls().aggregate_chunks(chunks)

    @staticmethod
    def _validate_tool_call(index: int, tool_call: ModelToolCall) -> tuple[ModelToolCallAssemblyIssue, ...]:
        """对聚合结果做结构级检查。

        这里故意只检查结构完整性，不检查业务参数合法性：
        - 缺 name：无法映射到工具注册表，必须拒绝或重试；
        - 缺 arguments：可能是无参工具，也可能是中断；当前先给 warning，由上层结合 schema 判断；
        - 缺 call_id：部分 OpenAI-compatible 网关可能缺失 id，后续回填工具结果时会受影响。
        """

        issues: list[ModelToolCallAssemblyIssue] = []
        if not tool_call.name:
            issues.append(
                ModelToolCallAssemblyIssue(
                    index=index,
                    code="MODEL_TOOL_CALL_NAME_MISSING",
                    message="模型流式工具调用缺少工具名称，无法映射到 DataSmart 工具注册表。",
                )
            )
        if not tool_call.arguments:
            issues.append(
                ModelToolCallAssemblyIssue(
                    index=index,
                    code="MODEL_TOOL_CALL_ARGUMENTS_EMPTY",
                    message="模型流式工具调用未返回参数字符串，执行前必须结合工具 schema 判断是否允许无参调用。",
                )
            )
        if not tool_call.call_id:
            issues.append(
                ModelToolCallAssemblyIssue(
                    index=index,
                    code="MODEL_TOOL_CALL_ID_MISSING",
                    message="模型流式工具调用缺少 call_id，后续工具结果回填模型时可能无法精确关联。",
                )
            )
        return tuple(issues)
