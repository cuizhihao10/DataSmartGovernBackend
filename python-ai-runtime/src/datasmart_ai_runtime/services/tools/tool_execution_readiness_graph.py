"""工具执行准备度图谱构建器。

这个模块把 `ToolExecutionReadinessReport` 进一步转换成“执行图条件节点”。

为什么需要单独的图谱层：
- `ToolExecutionReadinessService` 负责回答“每个 ToolPlan 当前是否具备执行准备度”；
- 本模块负责回答“编排器看到这些准备度结果后，下一步应该走哪条图分支”；
- 后续接入 LangGraph/OpenClaw 风格运行时时，readiness gate 可以直接成为图里的条件节点；
- MCP `tools/call`、A2A action 和模型 tool_call 也可以先汇入同一个图谱入口，再由 Java outbox、
  worker receipt、审批流和人工澄清节点继续承接。

安全边界：
- 本模块不执行工具、不调用 Java、不写数据库、不创建审批单、不投递 outbox；
- 图谱只保留工具名、决策、风险等级、执行模式、reason/issue code、低敏计数和字段名；
- 不保存或返回工具参数真实值、prompt、SQL、样本数据、模型输出、凭证、内部 endpoint 或 artifact 正文。
"""

from __future__ import annotations

from collections import Counter
from dataclasses import dataclass
from enum import Enum
from typing import Any, Mapping

from datasmart_ai_runtime.services.tools.tool_execution_readiness import (
    ToolExecutionReadinessDecision,
    ToolExecutionReadinessItem,
    ToolExecutionReadinessReport,
)


class ToolExecutionReadinessGraphBranch(str, Enum):
    """工具执行准备度图谱中的稳定分支名称。

    这些分支不是最终任务状态，而是执行前编排器可消费的路由语义：
    - `POLICY_GATE`：所有工具计划先进入的统一策略闸口；
    - `READY_TO_EXECUTE`：低风险、参数完整、预算允许，可进入真实执行前的最后检查；
    - `WAITING_APPROVAL`：需要人工审批或权限授权，不能自动执行；
    - `NEEDS_CLARIFICATION`：参数或上下文不足，需要用户补充信息；
    - `SHOW_DRAFT_FOR_REVIEW`：可以展示草稿，但不能产生真实副作用；
    - `QUEUE_ASYNC_COMMAND`：应进入异步命令队列，而不是阻塞当前 HTTP 请求；
    - `WAIT_FOR_TOOL_BUDGET`：预算或队列容量不足，需要等待控制面恢复；
    - `BLOCKED_BEFORE_EXECUTION`：策略明确阻断，不能进入执行器；
    - `NO_TOOL_PLAN`：本轮没有工具计划，Agent 可以继续文本回答或结束。
    """

    POLICY_GATE = "POLICY_GATE"
    READY_TO_EXECUTE = "READY_TO_EXECUTE"
    WAITING_APPROVAL = "WAITING_APPROVAL"
    NEEDS_CLARIFICATION = "NEEDS_CLARIFICATION"
    SHOW_DRAFT_FOR_REVIEW = "SHOW_DRAFT_FOR_REVIEW"
    QUEUE_ASYNC_COMMAND = "QUEUE_ASYNC_COMMAND"
    WAIT_FOR_TOOL_BUDGET = "WAIT_FOR_TOOL_BUDGET"
    BLOCKED_BEFORE_EXECUTION = "BLOCKED_BEFORE_EXECUTION"
    NO_TOOL_PLAN = "NO_TOOL_PLAN"


@dataclass(frozen=True)
class ToolExecutionReadinessGraphNode:
    """图谱节点。

    字段设计说明：
    - `node_id`：图内稳定节点 ID，便于前端、Java projection 或后续 LangGraph checkpoint 引用；
    - `node_type`：节点职责，例如策略闸口、工具条件节点、空计划终止节点；
    - `branch`：该节点所在的执行前分支，是后续编排器做条件跳转的核心字段；
    - `status`：面向展示的低敏状态，不代表真实工具执行状态；
    - `tool_name`：工具注册名，允许展示，因为现有 readiness/event 已把工具名视为低敏元数据；
    - `decision`：原始 readiness decision，保留给 Java 投影和前端做兼容判断；
    - `reason_codes`/`issue_codes`：机器可读原因码，替代自由文本，便于统计、告警和本地化展示；
    - `sensitive_argument_names`：只返回敏感字段名，不返回字段值，方便确认页提示用户哪些输入会被脱敏。
    """

    node_id: str
    node_type: str
    label: str
    branch: ToolExecutionReadinessGraphBranch
    status: str
    next_action: str
    plan_index: int | None = None
    tool_name: str | None = None
    decision: str | None = None
    execution_mode: str | None = None
    risk_level: str | None = None
    target_service: str | None = None
    executable: bool = False
    queue_required: bool = False
    requires_human_approval: bool = False
    reason_codes: tuple[str, ...] = ()
    issue_codes: tuple[str, ...] = ()
    sensitive_argument_names: tuple[str, ...] = ()
    payload_policy: str = "LOW_SENSITIVE_GRAPH_METADATA_ONLY"

    def to_response(self) -> dict[str, Any]:
        """转换成 HTTP/事件都可复用的低敏字典。

        这里不直接使用 `dataclasses.asdict`，是为了显式控制字段命名和白名单。
        如果未来节点对象增加了内部诊断字段，只有写进这个方法的字段才会暴露到响应中。
        """

        return {
            "nodeId": self.node_id,
            "nodeType": self.node_type,
            "label": self.label,
            "branch": self.branch.value,
            "status": self.status,
            "nextAction": self.next_action,
            "planIndex": self.plan_index,
            "toolName": self.tool_name,
            "decision": self.decision,
            "executionMode": self.execution_mode,
            "riskLevel": self.risk_level,
            "targetService": self.target_service,
            "executable": self.executable,
            "queueRequired": self.queue_required,
            "requiresHumanApproval": self.requires_human_approval,
            "reasonCodes": self.reason_codes,
            "issueCodes": self.issue_codes,
            "sensitiveArgumentNames": self.sensitive_argument_names,
            "payloadPolicy": self.payload_policy,
        }


@dataclass(frozen=True)
class ToolExecutionReadinessGraphEdge:
    """图谱边。

    图谱边只表达“为什么从策略闸口走向某个条件节点”，不表达真实执行依赖。
    真正的工具依赖、DAG 顺序、worker lease、outbox ack 和 artifact 产出仍应由后续 durable action
    链路负责。本阶段先把执行前分支显式化，避免在 API 响应层用隐式 if/else 难以追踪。
    """

    from_node_id: str
    to_node_id: str
    condition_code: str
    meaning: str
    payload_policy: str = "LOW_SENSITIVE_GRAPH_METADATA_ONLY"

    def to_response(self) -> dict[str, Any]:
        """转换成低敏响应字段。"""

        return {
            "fromNodeId": self.from_node_id,
            "toNodeId": self.to_node_id,
            "conditionCode": self.condition_code,
            "meaning": self.meaning,
            "payloadPolicy": self.payload_policy,
        }


@dataclass(frozen=True)
class ToolExecutionReadinessGraph:
    """一次 Agent plan 的工具执行准备度图谱。

    这个对象是未来“计划 -> 策略信封 -> readiness gate -> 审批/澄清/执行/outbox”的桥接模型。
    它当前只存在于同步响应中，后续可以逐步被 runtime event、Java projection 或 LangGraph checkpoint 消费。
    """

    policy: Mapping[str, Any]
    total_count: int
    has_blocking_decision: bool
    next_actions: tuple[str, ...]
    branch_counts: Mapping[str, int]
    nodes: tuple[ToolExecutionReadinessGraphNode, ...]
    edges: tuple[ToolExecutionReadinessGraphEdge, ...]
    graph_version: str = "tool-readiness-graph:v1"
    snapshot_type: str = "TOOL_EXECUTION_READINESS_GRAPH"
    payload_policy: str = "LOW_SENSITIVE_GRAPH_METADATA_ONLY"

    def to_response(self) -> dict[str, Any]:
        """输出给 `/agent/plans` 的图谱摘要。

        `executionBoundary` 用来提醒调用方：这张图只代表执行前控制面判断，不代表真实工具已经执行。
        `durableActionBoundary` 则提前固定 outbox/worker 语义，避免后续前端或调用方把 READY 节点误认为
        “已经产生副作用”。
        """

        branches = tuple(self.branch_counts.keys())
        return {
            "snapshotType": self.snapshot_type,
            "payloadPolicy": self.payload_policy,
            "graphVersion": self.graph_version,
            "executionBoundary": "PRE_EXECUTION_CONDITION_GRAPH_ONLY",
            "policy": dict(self.policy or {}),
            "totalCount": self.total_count,
            "nodeCount": len(self.nodes),
            "edgeCount": len(self.edges),
            "branches": branches,
            "branchCounts": dict(self.branch_counts),
            "hasBlockingDecision": self.has_blocking_decision,
            "nextActions": self.next_actions,
            "durableActionBoundary": {
                "toolExecuted": False,
                "outboxWritten": False,
                "approvalCreated": False,
                "workerReceiptRequiredForSideEffects": True,
            },
            "nodes": tuple(node.to_response() for node in self.nodes),
            "edges": tuple(edge.to_response() for edge in self.edges),
        }


class ToolExecutionReadinessGraphBuilder:
    """把 readiness report 转换成执行前条件图。

    构建原则：
    - 所有工具计划都先连接到统一的 `readiness-gate`，表示宿主平台必须先做执行前治理；
    - 每个工具计划转换为一个 `TOOL_CONDITION` 节点，节点只记录低敏元数据；
    - 没有工具计划时也生成 `NO_TOOL_PLAN` 节点，避免调用方误以为图谱构建失败；
    - 分支映射集中在 `_branch_for_decision`，后续新增策略时不会散落在 API 层。
    """

    ROOT_NODE_ID = "readiness-gate"

    def build(self, readiness: ToolExecutionReadinessReport) -> ToolExecutionReadinessGraph:
        """构建本轮工具准备度图谱。"""

        nodes: list[ToolExecutionReadinessGraphNode] = [self._root_node()]
        edges: list[ToolExecutionReadinessGraphEdge] = []
        branch_counter: Counter[str] = Counter()

        if not readiness.items:
            empty_node = self._no_tool_plan_node()
            nodes.append(empty_node)
            edges.append(self._edge_to(empty_node, condition_code="NO_TOOL_PLAN"))
            branch_counter[empty_node.branch.value] += 1
        else:
            for item in readiness.items:
                node = self._tool_node(item)
                nodes.append(node)
                edges.append(self._edge_to(node, condition_code=f"DECISION_{item.decision.value.upper()}"))
                branch_counter[node.branch.value] += 1

        return ToolExecutionReadinessGraph(
            policy=dict(readiness.policy_metadata or {}),
            total_count=readiness.total_count,
            has_blocking_decision=readiness.has_blocking_decision,
            next_actions=readiness.next_actions,
            branch_counts=dict(sorted(branch_counter.items())),
            nodes=tuple(nodes),
            edges=tuple(edges),
        )

    def _root_node(self) -> ToolExecutionReadinessGraphNode:
        """生成统一策略闸口节点。"""

        return ToolExecutionReadinessGraphNode(
            node_id=self.ROOT_NODE_ID,
            node_type="POLICY_GATE",
            label="工具执行准备度策略闸口",
            branch=ToolExecutionReadinessGraphBranch.POLICY_GATE,
            status="COMPLETED",
            next_action="ROUTE_BY_READINESS_DECISION",
        )

    def _no_tool_plan_node(self) -> ToolExecutionReadinessGraphNode:
        """生成空工具计划节点。

        真实产品里，空工具计划可能意味着本轮只需要文本回答，也可能意味着模型没有找到合适工具。
        先用单独节点表达该事实，后续可以继续接入“工具发现失败”“权限不可见”“Skill 不匹配”等更细分原因。
        """

        return ToolExecutionReadinessGraphNode(
            node_id="no-tool-plan",
            node_type="NO_TOOL_PLAN",
            label="本轮没有工具计划",
            branch=ToolExecutionReadinessGraphBranch.NO_TOOL_PLAN,
            status="COMPLETED",
            next_action="CONTINUE_TEXT_RESPONSE",
        )

    def _tool_node(self, item: ToolExecutionReadinessItem) -> ToolExecutionReadinessGraphNode:
        """把单个 readiness item 转换为工具条件节点。"""

        branch = self._branch_for_decision(item.decision)
        return ToolExecutionReadinessGraphNode(
            node_id=f"tool-{item.plan_index}",
            node_type="TOOL_CONDITION",
            label=f"工具执行条件：{item.tool_name}",
            branch=branch,
            status=self._status_for_branch(branch),
            next_action=self._next_action_for_branch(branch),
            plan_index=item.plan_index,
            tool_name=item.tool_name,
            decision=item.decision.value,
            execution_mode=item.execution_mode,
            risk_level=item.risk_level,
            target_service=item.target_service,
            executable=item.executable,
            queue_required=item.queue_required,
            requires_human_approval=item.requires_human_approval,
            reason_codes=item.reason_codes[:10],
            issue_codes=item.issue_codes[:10],
            sensitive_argument_names=item.sensitive_argument_names[:10],
        )

    def _edge_to(self, node: ToolExecutionReadinessGraphNode, *, condition_code: str) -> ToolExecutionReadinessGraphEdge:
        """生成从策略闸口到目标节点的条件边。"""

        return ToolExecutionReadinessGraphEdge(
            from_node_id=self.ROOT_NODE_ID,
            to_node_id=node.node_id,
            condition_code=condition_code,
            meaning=f"策略闸口将该计划路由到 {node.branch.value} 分支",
        )

    @staticmethod
    def _branch_for_decision(decision: ToolExecutionReadinessDecision) -> ToolExecutionReadinessGraphBranch:
        """把 readiness decision 映射为图谱分支。"""

        mapping = {
            ToolExecutionReadinessDecision.READY_TO_EXECUTE: ToolExecutionReadinessGraphBranch.READY_TO_EXECUTE,
            ToolExecutionReadinessDecision.DRAFT_ONLY: ToolExecutionReadinessGraphBranch.SHOW_DRAFT_FOR_REVIEW,
            ToolExecutionReadinessDecision.WAITING_APPROVAL: ToolExecutionReadinessGraphBranch.WAITING_APPROVAL,
            ToolExecutionReadinessDecision.NEEDS_CLARIFICATION: ToolExecutionReadinessGraphBranch.NEEDS_CLARIFICATION,
            ToolExecutionReadinessDecision.QUEUED_ASYNC: ToolExecutionReadinessGraphBranch.QUEUE_ASYNC_COMMAND,
            ToolExecutionReadinessDecision.THROTTLED: ToolExecutionReadinessGraphBranch.WAIT_FOR_TOOL_BUDGET,
            ToolExecutionReadinessDecision.BLOCKED: ToolExecutionReadinessGraphBranch.BLOCKED_BEFORE_EXECUTION,
        }
        return mapping.get(decision, ToolExecutionReadinessGraphBranch.BLOCKED_BEFORE_EXECUTION)

    @staticmethod
    def _status_for_branch(branch: ToolExecutionReadinessGraphBranch) -> str:
        """生成便于前端展示的节点状态。"""

        mapping = {
            ToolExecutionReadinessGraphBranch.READY_TO_EXECUTE: "READY",
            ToolExecutionReadinessGraphBranch.SHOW_DRAFT_FOR_REVIEW: "WAITING_REVIEW",
            ToolExecutionReadinessGraphBranch.WAITING_APPROVAL: "INTERRUPTED_WAITING_APPROVAL",
            ToolExecutionReadinessGraphBranch.NEEDS_CLARIFICATION: "INTERRUPTED_WAITING_INPUT",
            ToolExecutionReadinessGraphBranch.QUEUE_ASYNC_COMMAND: "READY_TO_QUEUE",
            ToolExecutionReadinessGraphBranch.WAIT_FOR_TOOL_BUDGET: "WAITING_CAPACITY",
            ToolExecutionReadinessGraphBranch.BLOCKED_BEFORE_EXECUTION: "BLOCKED",
            ToolExecutionReadinessGraphBranch.NO_TOOL_PLAN: "COMPLETED",
        }
        return mapping.get(branch, "UNKNOWN")

    @staticmethod
    def _next_action_for_branch(branch: ToolExecutionReadinessGraphBranch) -> str:
        """生成分支对应的下一步动作建议。"""

        mapping = {
            ToolExecutionReadinessGraphBranch.READY_TO_EXECUTE: "EXECUTE_READY_TOOLS",
            ToolExecutionReadinessGraphBranch.SHOW_DRAFT_FOR_REVIEW: "SHOW_DRAFT_FOR_REVIEW",
            ToolExecutionReadinessGraphBranch.WAITING_APPROVAL: "CREATE_OR_WAIT_APPROVAL",
            ToolExecutionReadinessGraphBranch.NEEDS_CLARIFICATION: "REQUEST_USER_CLARIFICATION",
            ToolExecutionReadinessGraphBranch.QUEUE_ASYNC_COMMAND: "SUBMIT_ASYNC_COMMAND",
            ToolExecutionReadinessGraphBranch.WAIT_FOR_TOOL_BUDGET: "WAIT_FOR_TOOL_BUDGET",
            ToolExecutionReadinessGraphBranch.BLOCKED_BEFORE_EXECUTION: "ESCALATE_TO_OPERATOR",
            ToolExecutionReadinessGraphBranch.NO_TOOL_PLAN: "CONTINUE_TEXT_RESPONSE",
        }
        return mapping.get(branch, "ESCALATE_TO_OPERATOR")


def build_tool_execution_readiness_graph_response(readiness: ToolExecutionReadinessReport) -> dict[str, Any]:
    """构建 `/agent/plans` 使用的工具执行准备度图谱响应。"""

    return ToolExecutionReadinessGraphBuilder().build(readiness).to_response()
