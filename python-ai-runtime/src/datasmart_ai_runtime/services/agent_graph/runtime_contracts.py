"""LangGraph Agent Runtime 合同模型。

本项目后续的 Agent 不能只把 LangGraph 当成“顺序调用几个 Python 方法”的包装层。
在商业级 Agent Runtime 中，图本身应当是可审计的产品能力：

- 节点是可复用、可观测、可替换的工作单元；
- 边是条件分支、受控循环和等待恢复的状态机控制线；
- 状态是低敏、可序列化、可 checkpoint、可从中断点恢复的执行现场。

本文件不直接依赖 LangGraph 包，也不执行任何 Agent 业务逻辑。它提供的是一套轻量合同对象，
让不同 workflow 能用统一结构声明“我是不是一个合格的 Agent 图”。这样 API 响应、测试、
Prometheus 指标、Java 投影和后续文档都可以读取同一份低敏图说明，而不是从源码里猜测拓扑。
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from typing import Any


class AgentGraphEdgeKind(str, Enum):
    """Agent 图边类型。

    - `DIRECT`：普通顺序边，适合不可分支的准备步骤；
    - `CONDITIONAL`：条件边，必须由 state 中的低敏字段决定下一跳；
    - `LOOP`：受控循环边，必须有最大深度、退出条件和 checkpoint/resume 约束；
    - `TERMINAL`：终止边，表示进入 END 或最终摘要节点。
    """

    DIRECT = "direct"
    CONDITIONAL = "conditional"
    LOOP = "loop"
    TERMINAL = "terminal"


@dataclass(frozen=True)
class AgentGraphNodeContract:
    """单个 LangGraph 节点的可学习、可审计描述。

    节点合同不是为了替代代码注释，而是为了让运行时也能说明一个节点为什么存在。
    对真实 Agent 来说，节点应具备三个基本特征：

    - 可复用：节点职责足够单一，未来可以在别的 graph 中复用；
    - 可观测：节点执行结果能进入 trace、runtime event 或指标；
    - 可替换：节点输入输出边界清楚，可以替换成新的实现、远程能力或更强模型。
    """

    node_id: str
    responsibility: str
    input_policy: str
    output_policy: str
    side_effect_policy: str
    observable: bool = True
    reusable: bool = True
    replaceable: bool = True

    def to_summary(self) -> dict[str, Any]:
        """输出低敏节点摘要，供 API/事件/测试复用。"""

        return {
            "nodeId": self.node_id,
            "responsibility": self.responsibility,
            "inputPolicy": self.input_policy,
            "outputPolicy": self.output_policy,
            "sideEffectPolicy": self.side_effect_policy,
            "observable": self.observable,
            "reusable": self.reusable,
            "replaceable": self.replaceable,
        }


@dataclass(frozen=True)
class AgentGraphEdgeContract:
    """LangGraph 边合同。

    边决定 Agent 是否只是固定流水线，还是具备状态机能力。尤其是条件边和循环边，必须把控制含义
    讲清楚，否则后续很难判断一次执行为什么等待、重试、阻塞、委派给子 Agent 或安全退出。
    """

    source: str
    target: str
    kind: AgentGraphEdgeKind
    control_meaning: str
    route_key: str | None = None
    condition_field: str | None = None
    max_loop_depth_field: str | None = None

    def to_summary(self) -> dict[str, Any]:
        """输出低敏边摘要，避免暴露任何租户、请求或业务正文。"""

        return {
            "source": self.source,
            "target": self.target,
            "kind": self.kind.value,
            "routeKey": self.route_key,
            "conditionField": self.condition_field,
            "maxLoopDepthField": self.max_loop_depth_field,
            "controlMeaning": self.control_meaning,
        }


@dataclass(frozen=True)
class AgentGraphStateContract:
    """Agent 图状态合同。

    状态是 Agent 的“现场”。如果状态只是一堆临时变量，Agent 就无法可靠恢复；如果状态塞入 prompt、
    SQL、工具参数或模型输出正文，又会产生安全和审计风险。因此这里显式声明哪些字段是恢复所需、
    哪些正文永远不能进入图状态。
    """

    schema_name: str
    schema_version: str
    low_sensitive: bool
    serializable: bool
    checkpointable: bool
    resumable: bool
    identity_fields: tuple[str, ...]
    progress_fields: tuple[str, ...]
    control_fields: tuple[str, ...]
    forbidden_payloads: tuple[str, ...]

    def to_summary(self) -> dict[str, Any]:
        """输出图状态能力摘要。"""

        return {
            "schemaName": self.schema_name,
            "schemaVersion": self.schema_version,
            "lowSensitive": self.low_sensitive,
            "serializable": self.serializable,
            "checkpointable": self.checkpointable,
            "resumable": self.resumable,
            "identityFields": self.identity_fields,
            "progressFields": self.progress_fields,
            "controlFields": self.control_fields,
            "forbiddenPayloads": self.forbidden_payloads,
        }


@dataclass(frozen=True)
class AgentGraphRuntimeContract:
    """一个 Agent graph 的总合同。

    该合同把节点、边、状态、副作用边界和 Java 控制面关系集中起来。后续我们可以对每个 LangGraph
    workflow 做自动审计：哪些图只是观察型顺序图，哪些图已经满足真实 Durable Agent Loop 标准。
    """

    graph_id: str
    graph_name: str
    graph_kind: str
    purpose: str
    nodes: tuple[AgentGraphNodeContract, ...]
    edges: tuple[AgentGraphEdgeContract, ...]
    state: AgentGraphStateContract
    java_control_plane_required: bool
    side_effect_policy: str
    observability_policy: str
    replacement_policy: str
    requires_conditional_edges: bool = True
    requires_loop_edges: bool = True

    def to_summary(self) -> dict[str, Any]:
        """输出完整图合同摘要。"""

        review = review_agent_graph_contract(self)
        return {
            "graphId": self.graph_id,
            "graphName": self.graph_name,
            "graphKind": self.graph_kind,
            "purpose": self.purpose,
            "nodes": tuple(node.to_summary() for node in self.nodes),
            "edges": tuple(edge.to_summary() for edge in self.edges),
            "state": self.state.to_summary(),
            "javaControlPlaneRequired": self.java_control_plane_required,
            "sideEffectPolicy": self.side_effect_policy,
            "observabilityPolicy": self.observability_policy,
            "replacementPolicy": self.replacement_policy,
            "requirements": {
                "conditionalEdgesRequired": self.requires_conditional_edges,
                "loopEdgesRequired": self.requires_loop_edges,
            },
            "capabilities": review.to_summary(),
        }


@dataclass(frozen=True)
class AgentGraphContractReview:
    """图合同审计结果。

    这个对象用于回答用户关心的核心问题：当前 LangGraph 实现到底是不是“节点/边/状态”三件套都合格。
    审计只看合同，不读取敏感运行数据，因此可以安全进入 API 响应和测试断言。
    """

    node_contract_ready: bool
    conditional_edges_ready: bool
    loop_edges_ready: bool
    resumable_state_ready: bool
    low_sensitive_state_ready: bool
    java_control_plane_boundary_ready: bool
    missing_capabilities: tuple[str, ...]

    def to_summary(self) -> dict[str, Any]:
        """输出机器可读的能力审计摘要。"""

        return {
            "nodeContractReady": self.node_contract_ready,
            "conditionalEdgesReady": self.conditional_edges_ready,
            "loopEdgesReady": self.loop_edges_ready,
            "resumableStateReady": self.resumable_state_ready,
            "lowSensitiveStateReady": self.low_sensitive_state_ready,
            "javaControlPlaneBoundaryReady": self.java_control_plane_boundary_ready,
            "missingCapabilities": self.missing_capabilities,
            "runtimeReady": not self.missing_capabilities,
        }


def review_agent_graph_contract(contract: AgentGraphRuntimeContract) -> AgentGraphContractReview:
    """审计一个 Agent graph 是否满足项目定义的 Runtime 基线。

    审计规则通过 `requires_conditional_edges/requires_loop_edges` 区分执行型图与观察型图：
    - 执行型 Agent 图默认必须具备条件边和受控循环；
    - 只负责读取、压缩或观测的图可以显式关闭对应要求，但仍会如实暴露实际是否存在这些边。

    这样既不会把固定流水线误报成完整 Durable Agent Runtime，也不会强迫单次观察图制造没有业务意义的假循环。
    """

    edge_kinds = {edge.kind for edge in contract.edges}
    node_contract_ready = all(
        node.node_id and node.responsibility and node.input_policy and node.output_policy for node in contract.nodes
    )
    conditional_edges_ready = AgentGraphEdgeKind.CONDITIONAL in edge_kinds
    loop_edges_ready = AgentGraphEdgeKind.LOOP in edge_kinds
    resumable_state_ready = (
        contract.state.serializable
        and contract.state.checkpointable
        and contract.state.resumable
        and bool(contract.state.identity_fields)
        and bool(contract.state.progress_fields)
    )
    low_sensitive_state_ready = contract.state.low_sensitive and bool(contract.state.forbidden_payloads)
    java_boundary_ready = contract.java_control_plane_required and "NO_DIRECT_SIDE_EFFECT" in contract.side_effect_policy

    missing: list[str] = []
    if not node_contract_ready:
        missing.append("NODE_CONTRACT_INCOMPLETE")
    if contract.requires_conditional_edges and not conditional_edges_ready:
        missing.append("CONDITIONAL_EDGE_REQUIRED_FOR_AGENT_STATE_MACHINE")
    if contract.requires_loop_edges and not loop_edges_ready:
        missing.append("LOOP_EDGE_REQUIRED_FOR_DURABLE_AGENT_LOOP")
    if not resumable_state_ready:
        missing.append("RESUMABLE_STATE_CONTRACT_INCOMPLETE")
    if not low_sensitive_state_ready:
        missing.append("LOW_SENSITIVE_STATE_POLICY_INCOMPLETE")
    if not java_boundary_ready:
        missing.append("JAVA_CONTROL_PLANE_SIDE_EFFECT_BOUNDARY_INCOMPLETE")

    return AgentGraphContractReview(
        node_contract_ready=node_contract_ready,
        conditional_edges_ready=conditional_edges_ready,
        loop_edges_ready=loop_edges_ready,
        resumable_state_ready=resumable_state_ready,
        low_sensitive_state_ready=low_sensitive_state_ready,
        java_control_plane_boundary_ready=java_boundary_ready,
        missing_capabilities=tuple(missing),
    )
