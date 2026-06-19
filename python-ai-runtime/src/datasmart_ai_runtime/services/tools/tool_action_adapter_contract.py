"""工具动作统一 Adapter Contract 注册表。

本文件把 5.89 平台收敛诊断中的下一步路线落到代码契约：MCP `tools/call`、A2A task/action 和模型
tool_call 都可以表达“我想推进一个动作”，但它们绝不能各自直连下游微服务。DataSmart 必须先把这些
入口映射到同一套 Agent Host 治理链路，再由权限、审批、readiness、resume gate、outbox、worker
receipt、runtime event 和 task-management 决定是否继续。

为什么要有单独的 contract registry：
- `ToolActionIntakeService` 已经能把入口请求归一为 ToolPlan 或 A2A 控制面决策，但它更偏运行时逻辑；
- `ToolActionControlFlowService` 能返回本次预检结果，但它只描述“这一次请求如何被处理”；
- 本文件描述“每一种入口来源应该长期遵守什么协议边界”，让 Java agent-runtime、gateway、Python
  Runtime、测试和文档都能引用同一份低敏契约。

低敏边界：
- 只返回协议族、输入形态、标准化目标、必经门禁、生产退出条件和下一步动作；
- 不返回 endpoint、真实 tool arguments、业务 payload、prompt、SQL、样本数据、模型输出、凭证或内部服务地址；
- 不执行工具、不写 outbox、不创建审批、不触发 runtime event。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Any

from datasmart_ai_runtime.services.tools.tool_action_intake import (
    ToolActionIntakeBoundary,
    ToolActionIntakeSource,
)


class ToolActionAdapterStage(str, Enum):
    """统一工具动作进入真实副作用前必须经过的治理阶段。

    这些阶段不是一次 HTTP 调用里的内部步骤，而是跨 Python Runtime、Java agent-runtime、permission-admin、
    task-management 和 worker 的端到端闭环节点。把它们做成枚举，是为了后续 runtime event、Java projection、
    测试和文档都能用稳定名字对齐，而不是每个入口各写一套中文说明。
    """

    INTAKE = "INTAKE"
    TOOL_PLAN_NORMALIZATION = "TOOL_PLAN_NORMALIZATION"
    A2A_CONTROL_PLANE_DECISION = "A2A_CONTROL_PLANE_DECISION"
    READINESS_POLICY = "READINESS_POLICY"
    READINESS_GRAPH = "READINESS_GRAPH"
    HUMAN_APPROVAL = "HUMAN_APPROVAL"
    CLARIFICATION = "CLARIFICATION"
    RESUME_GATE = "RESUME_GATE"
    COMMAND_PROPOSAL = "COMMAND_PROPOSAL"
    DURABLE_OUTBOX = "DURABLE_OUTBOX"
    WORKER_RECEIPT = "WORKER_RECEIPT"
    RUNTIME_EVENT_REPLAY = "RUNTIME_EVENT_REPLAY"
    TASK_MANAGEMENT_BINDING = "TASK_MANAGEMENT_BINDING"


@dataclass(frozen=True)
class ToolActionAdapterContract:
    """某一种工具动作来源的长期 Adapter Contract。

    字段说明：
    - `source`：与 `ToolActionIntakeSource` 对齐的入口来源；
    - `protocol_family`：用于网关、管理台和文档展示的协议族；
    - `adapter_id`：稳定契约标识，后续 Java projection 或 API 响应可以引用；
    - `supported_input_shapes`：该入口支持的低敏输入形态，例如 OpenAI-compatible tool_calls 或 MCP JSON-RPC；
    - `normalization_target`：入口归一化后应该进入 ToolPlan readiness，还是 A2A 控制面决策；
    - `required_stages`：真实副作用前必须经过的治理阶段；
    - `host_owned_responsibilities`：DataSmart Host 必须承担的职责，不能交给模型或外部协议自行决定；
    - `external_protocol_responsibilities`：外部调用方或模型侧只负责提供什么事实；
    - `side_effect_boundary`：当前 contract 的副作用边界，明确 adapter 本身不执行工具；
    - `production_exit_criteria`：达到这些条件后，才允许从 preview/contract 进入真实执行链路；
    - `next_implementation_steps`：面向近期开发的收敛动作。
    """

    source: ToolActionIntakeSource
    protocol_family: str
    adapter_id: str
    supported_input_shapes: tuple[str, ...]
    normalization_target: ToolActionIntakeBoundary
    required_stages: tuple[ToolActionAdapterStage, ...]
    host_owned_responsibilities: tuple[str, ...] = field(default_factory=tuple)
    external_protocol_responsibilities: tuple[str, ...] = field(default_factory=tuple)
    side_effect_boundary: tuple[str, ...] = field(default_factory=tuple)
    production_exit_criteria: tuple[str, ...] = field(default_factory=tuple)
    next_implementation_steps: tuple[str, ...] = field(default_factory=tuple)
    observability_contract: tuple[str, ...] = field(default_factory=tuple)

    def to_low_sensitive_summary(self) -> dict[str, Any]:
        """输出可进入 API、控制流响应和文档的低敏契约摘要。

        该方法按白名单组装字段，不直接序列化 dataclass。这样未来如果 contract 内部为了实现执行器而加入
        运行时配置，也不会自动泄露到外部响应中。
        """

        return {
            "adapterId": self.adapter_id,
            "source": self.source.value,
            "protocolFamily": self.protocol_family,
            "supportedInputShapes": self.supported_input_shapes,
            "normalizationTarget": self.normalization_target.value,
            "requiredStages": tuple(stage.value for stage in self.required_stages),
            "hostOwnedResponsibilities": self.host_owned_responsibilities,
            "externalProtocolResponsibilities": self.external_protocol_responsibilities,
            "sideEffectBoundary": self.side_effect_boundary,
            "productionExitCriteria": self.production_exit_criteria,
            "nextImplementationSteps": self.next_implementation_steps,
            "observabilityContract": self.observability_contract,
            "payloadPolicy": "LOW_SENSITIVE_ADAPTER_CONTRACT_METADATA_ONLY",
        }


@dataclass(frozen=True)
class ToolActionAdapterContractRegistry:
    """工具动作 Adapter Contract 注册表。

    当前实现是内置只读表，适合作为项目收敛阶段的基线。后续如果 Java agent-runtime 或管理台需要动态配置，
    也应该保持同样的响应结构，不要让每个协议入口重新定义自己的边界。
    """

    contracts: tuple[ToolActionAdapterContract, ...] = field(default_factory=tuple)

    def get(self, source: ToolActionIntakeSource | str) -> ToolActionAdapterContract:
        """按来源读取 contract。

        找不到时抛出 `ValueError`，这是编程错误而不是业务拒绝：DataSmart 已知的入口来源必须都有显式契约，
        否则后续真实执行时无法判断哪些门禁是必需的。
        """

        source_enum = source if isinstance(source, ToolActionIntakeSource) else ToolActionIntakeSource(str(source))
        contract = next((item for item in self.contracts if item.source == source_enum), None)
        if contract is None:
            raise ValueError(f"未维护工具动作 Adapter Contract: {source_enum.value}")
        return contract

    def diagnostics(self) -> dict[str, Any]:
        """输出注册表级诊断，供智能网关、Java 控制面或管理台查询。"""

        return {
            "schemaVersion": "datasmart.tool-action-adapter-contract-registry.v1",
            "diagnosticType": "TOOL_ACTION_ADAPTER_CONTRACTS",
            "contractCount": len(self.contracts),
            "sensitiveDataPolicy": (
                "只返回入口来源、协议族、归一化目标、必经门禁、生产退出条件和下一步动作；"
                "不返回工具参数值、业务正文、用户输入、模型响应、凭证或内部服务地址。"
            ),
            "executionBoundary": (
                "Adapter Contract 只定义入口如何进入 DataSmart Host 治理链路；"
                "真实副作用必须等待 permission-admin、resume gate、Java outbox、worker receipt 和 runtime event 闭环。"
            ),
            "contracts": tuple(contract.to_low_sensitive_summary() for contract in self.contracts),
        }


def default_tool_action_adapter_contract_registry() -> ToolActionAdapterContractRegistry:
    """构建默认工具动作 Adapter Contract 注册表。

    默认 contract 覆盖当前项目已经接入或规划中的三类入口：
    - 模型原生 tool_call；
    - MCP `tools/call`；
    - A2A task/action。
    """

    return ToolActionAdapterContractRegistry(
        contracts=(
            _model_tool_call_contract(),
            _mcp_tools_call_contract(),
            _a2a_task_action_contract(),
        )
    )


def _model_tool_call_contract() -> ToolActionAdapterContract:
    """模型 tool_call 来源的契约。

    模型只能提出候选动作，不能因为输出了 tool_call 就获得执行权。DataSmart Host 必须重新校验工具目录、
    可见性、参数、预算、审批和恢复事实。
    """

    return ToolActionAdapterContract(
        source=ToolActionIntakeSource.MODEL_TOOL_CALL,
        protocol_family="MODEL",
        adapter_id="datasmart.adapter.model-tool-call.v1",
        supported_input_shapes=(
            "OpenAI-compatible message.tool_calls",
            "DataSmart local ModelToolCall tuple",
        ),
        normalization_target=ToolActionIntakeBoundary.TOOL_PLAN_READINESS_GRAPH,
        required_stages=(
            ToolActionAdapterStage.INTAKE,
            ToolActionAdapterStage.TOOL_PLAN_NORMALIZATION,
            ToolActionAdapterStage.READINESS_POLICY,
            ToolActionAdapterStage.READINESS_GRAPH,
            ToolActionAdapterStage.HUMAN_APPROVAL,
            ToolActionAdapterStage.CLARIFICATION,
            ToolActionAdapterStage.RESUME_GATE,
            ToolActionAdapterStage.COMMAND_PROPOSAL,
            ToolActionAdapterStage.DURABLE_OUTBOX,
            ToolActionAdapterStage.WORKER_RECEIPT,
            ToolActionAdapterStage.RUNTIME_EVENT_REPLAY,
        ),
        host_owned_responsibilities=(
            "校验模型提出的工具名是否存在、是否对当前 actor/workspace 可见。",
            "重新执行参数 schema 校验和敏感字段识别，不信任模型自报参数完整性。",
            "根据 permission-admin/readiness policy 决定审批、澄清、预算等待或阻断。",
            "将可执行候选转成 Java command proposal，再由 outbox/worker receipt 承接真实副作用。",
        ),
        external_protocol_responsibilities=(
            "模型只负责输出结构化 tool_call 候选。",
            "模型不得决定是否跳过审批、是否写 outbox 或是否读取工具结果正文。",
        ),
        side_effect_boundary=(
            "adapter 不执行工具。",
            "adapter 不写 outbox。",
            "adapter 不创建审批单。",
            "adapter 不保存 prompt、工具参数值或模型输出正文。",
        ),
        production_exit_criteria=(
            "模型 tool_call 聚合、schema repair、重试和拒绝原因进入低敏 runtime event。",
            "READY 分支能进入 Java command proposal/outbox，并获得 worker receipt。",
            "审批、澄清、预算阻断和恢复预检都有可回放事件。",
        ),
        next_implementation_steps=(
            "把当前 control-flow-preview 的 READY 分支接入 Java command proposal 客户端。",
            "为模型 tool_call 增加低敏 adapter contract 事件，便于 Java timeline 展示来源与门禁。",
        ),
        observability_contract=(
            "记录 source、adapterId、boundaryCounts、readiness branch、blockingIssueCount。",
            "不把 tool arguments、prompt、model output、tenantId/runId 放入 Prometheus 高基数 label。",
        ),
    )


def _mcp_tools_call_contract() -> ToolActionAdapterContract:
    """MCP `tools/call` 来源的契约。

    MCP 规范让模型或 Host 可以调用外部工具，但企业产品不能把协议调用等同于授权执行。DataSmart 的 MCP
    入口必须先进入 ToolPlan/readiness/resume gate，而不是直接调用业务微服务。
    """

    return ToolActionAdapterContract(
        source=ToolActionIntakeSource.MCP_TOOLS_CALL,
        protocol_family="MCP",
        adapter_id="datasmart.adapter.mcp-tools-call.v1",
        supported_input_shapes=(
            "JSON-RPC 2.0 method=tools/call params={name, arguments}",
            "DataSmart compatible call/toolCall wrapper",
        ),
        normalization_target=ToolActionIntakeBoundary.TOOL_PLAN_READINESS_GRAPH,
        required_stages=(
            ToolActionAdapterStage.INTAKE,
            ToolActionAdapterStage.TOOL_PLAN_NORMALIZATION,
            ToolActionAdapterStage.READINESS_POLICY,
            ToolActionAdapterStage.READINESS_GRAPH,
            ToolActionAdapterStage.HUMAN_APPROVAL,
            ToolActionAdapterStage.CLARIFICATION,
            ToolActionAdapterStage.RESUME_GATE,
            ToolActionAdapterStage.COMMAND_PROPOSAL,
            ToolActionAdapterStage.DURABLE_OUTBOX,
            ToolActionAdapterStage.WORKER_RECEIPT,
            ToolActionAdapterStage.RUNTIME_EVENT_REPLAY,
            ToolActionAdapterStage.TASK_MANAGEMENT_BINDING,
        ),
        host_owned_responsibilities=(
            "校验 MCP 客户端身份、工具 scope、租户/项目/actor 数据范围和幂等键。",
            "将 params.name/arguments 归一为 ToolPlan，但低敏响应只展示字段名和计数。",
            "高风险、写入、导出、任务创建类工具必须进入审批或确认链路。",
            "所有真实执行都必须经 Java outbox/worker receipt，不允许 MCP handler 内直连业务服务。",
        ),
        external_protocol_responsibilities=(
            "MCP 客户端只提交 JSON-RPC 请求和低敏关联 ID。",
            "MCP 客户端不能通过 arguments 声明自己已获审批、已写 outbox 或已完成 worker receipt。",
        ),
        side_effect_boundary=(
            "adapter 不执行 MCP 工具。",
            "adapter 不返回原始 arguments。",
            "adapter 不返回工具结果正文。",
            "adapter 不暴露 MCP server 内部连接地址。",
        ),
        production_exit_criteria=(
            "MCP 会话生命周期、认证、工具列表分页和 tools/list changed 事件具备统一治理。",
            "tools/call READY 分支能进入 Java command proposal/outbox。",
            "MCP intake、readiness、approval、worker receipt 和结果引用能按同一 trace/task/run 回放。",
        ),
        next_implementation_steps=(
            "把 MCP preview 的 contract 摘要写入 response 和 runtime event。",
            "实现 MCP tools/call 到 Java command proposal 的 fail-closed 真实提交路径。",
        ),
        observability_contract=(
            "记录 adapterId、protocolFamily、methodAccepted、acceptedToolPlanCount、rejectedBeforeReadinessCount。",
            "不记录 JSON-RPC params.arguments、工具结果、内部 endpoint 或用户原文。",
        ),
    )


def _a2a_task_action_contract() -> ToolActionAdapterContract:
    """A2A task/action 来源的契约。

    A2A 是 Agent 间协作协议，不等同于工具调用协议。它可能表达一个长任务处于 submitted、input-required、
    auth-required、working 或 terminal 状态，因此默认进入 A2A 控制面决策，而不是强行生成 ToolPlan。
    """

    return ToolActionAdapterContract(
        source=ToolActionIntakeSource.A2A_TASK_ACTION,
        protocol_family="A2A",
        adapter_id="datasmart.adapter.a2a-task-action.v1",
        supported_input_shapes=(
            "DataSmart Java A2A task-query-preview contract",
            "A2A task/action control-plane envelope",
        ),
        normalization_target=ToolActionIntakeBoundary.A2A_TASK_CONTROL_PLANE_DECISION,
        required_stages=(
            ToolActionAdapterStage.INTAKE,
            ToolActionAdapterStage.A2A_CONTROL_PLANE_DECISION,
            ToolActionAdapterStage.READINESS_GRAPH,
            ToolActionAdapterStage.HUMAN_APPROVAL,
            ToolActionAdapterStage.RESUME_GATE,
            ToolActionAdapterStage.TASK_MANAGEMENT_BINDING,
            ToolActionAdapterStage.RUNTIME_EVENT_REPLAY,
        ),
        host_owned_responsibilities=(
            "把 A2A task 状态映射为 PRECHECK、WAIT_FOR_INPUT、WAIT_FOR_AUTHORIZATION、TERMINAL 等规划模式。",
            "保留 taskPublicId/contextPublicId 等低敏引用，真实 task fact 由 Java/task-management 承接。",
            "A2A artifact 只返回 metadata/ref，正文读取必须二次鉴权。",
            "当 A2A task 后续需要工具执行时，再显式派生 ToolPlan，不在 adapter 中隐式转换。",
        ),
        external_protocol_responsibilities=(
            "外部 Agent 只提供 task 状态、上下文引用和低敏事件历史。",
            "外部 Agent 不暴露内部记忆、私有工具实现或未脱敏 artifact 正文。",
        ),
        side_effect_boundary=(
            "adapter 不创建或取消 A2A task。",
            "adapter 不订阅 stream 或投递 push notification。",
            "adapter 不读取 artifact 正文。",
            "adapter 不把 A2A task 直接伪装成 ToolPlan。",
        ),
        production_exit_criteria=(
            "A2A task fact store、状态历史、stream replay 和 push delivery 具备低敏持久化。",
            "A2A auth-required/input-required 能接 permission-admin 和人工输入链路。",
            "A2A task 与 task-management durable task fact 可关联查询。",
        ),
        next_implementation_steps=(
            "将 A2A control-plane decision 写入统一 runtime event。",
            "设计 A2A task fact 到 task-management 的最小持久化模型。",
        ),
        observability_contract=(
            "记录 adapterId、task state、planningMode、terminal/interrupted、sequence 和 suggestedActions。",
            "不记录 A2A message 正文、artifact 正文、内部 Agent 状态或私有工具目录。",
        ),
    )


__all__ = [
    "ToolActionAdapterContract",
    "ToolActionAdapterContractRegistry",
    "ToolActionAdapterStage",
    "default_tool_action_adapter_contract_registry",
]
