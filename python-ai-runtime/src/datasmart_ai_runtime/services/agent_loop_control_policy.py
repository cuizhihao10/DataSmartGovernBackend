"""受控 Agent loop 策略。

Codex / Claude Code 类 Agent 的体验看起来像“模型自己连续思考并调用工具”，但真实商业化系统不能把
这种循环做成无边界自动递归。尤其在 DataSmart Govern 这类数据治理平台中，工具可能读取元数据、生成
规则、创建任务草稿、触发同步或接触敏感字段，因此每一轮 loop 都必须受策略保护。

本文件把“是否允许继续下一轮模型推理/工具调用”的判断从 API 和模型节点中拆出来，形成独立策略对象。
它当前只做轻量内存判断，但语义已经为后续扩展预留空间：
- 租户套餐可以下发不同最大步数、预算和超时；
- 高风险行业可以强制等待人工审批；
- 运维可以按事故级别临时关闭自动二轮；
- Java 控制面可以把审批、取消、失败重试等状态同步给 Python 后由同一策略判断。

注意：该策略不执行工具、不调用模型、不查询 Java。它只读取 4.07 的控制面反馈快照和当前 loop 状态，
输出一份可审计的决策摘要。这样后续真正接二轮推理时，可以先问策略“是否允许继续”，而不是把保护逻辑
散落到多个调用点。
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from typing import Any

from datasmart_ai_runtime.services.agent_control_plane_feedback import AgentControlPlaneFeedbackSnapshot
from datasmart_ai_runtime.services.model_gateway.model_tool_result_feedback import ToolExecutionFeedbackStatus


class AgentLoopControlAction(str, Enum):
    """Agent loop 的下一步动作。

    每个动作都代表一个产品语义，而不是单纯技术分支：
    - `ALLOW_SECOND_TURN`：允许在当前快照基础上进入二轮模型推理；
    - `WAIT_FOR_CONTROL_PLANE`：控制面反馈尚不完整，应该等待事件或稍后 replay；
    - `WAIT_FOR_APPROVAL`：工具正在等待人工审批，自动 loop 必须停住；
    - `REQUIRE_HUMAN_TAKEOVER`：策略要求人工接管，例如用户取消、运维熔断或审批场景强制人工处理；
    - `STOP_STEP_LIMIT`：超过工具步数或二轮次数限制，防止无限循环；
    - `STOP_BUDGET_EXCEEDED`：超过 token/成本预算；
    - `STOP_TIMEOUT`：超过单轮等待或全局耗时上限；
    - `STOP_NO_WORK`：没有可推进的工具反馈，通常用于纯文本回答或空计划。
    """

    ALLOW_SECOND_TURN = "allow_second_turn"
    WAIT_FOR_CONTROL_PLANE = "wait_for_control_plane"
    WAIT_FOR_APPROVAL = "wait_for_approval"
    REQUIRE_HUMAN_TAKEOVER = "require_human_takeover"
    STOP_STEP_LIMIT = "stop_step_limit"
    STOP_BUDGET_EXCEEDED = "stop_budget_exceeded"
    STOP_TIMEOUT = "stop_timeout"
    STOP_NO_WORK = "stop_no_work"


@dataclass(frozen=True)
class AgentLoopControlPolicy:
    """受控 Agent loop 的策略配置。

    字段说明：
    - `max_tool_steps`：一次用户请求最多允许经历多少轮“模型提出工具 -> 控制面反馈 -> 模型继续推理”；
    - `max_second_turns`：当前阶段最多允许多少次二轮模型推理，默认 1，避免尚未完整状态机化时继续扩散；
    - `max_tool_calls_per_turn`：单轮最多允许多少个工具调用，防止模型一次性提出过多工具造成审批和执行洪峰；
    - `max_total_tokens`：本次 loop 允许累计消耗的 token 上限，生产环境可映射租户套餐或项目预算；
    - `global_timeout_seconds`：一次 Agent loop 的全局耗时上限，防止连接、模型或控制面异常拖住用户会话；
    - `tool_wait_timeout_seconds`：等待 Java 控制面反馈的单工具等待上限；
    - `require_human_takeover_on_approval`：遇到审批等待时是否强制人工接管。当前默认开启，符合治理产品安全边界；
    - `allow_failed_feedback_second_turn`：失败反馈是否允许进入二轮解释。默认允许，因为模型可以基于失败原因给出修复建议；
    - `allow_skipped_feedback_second_turn`：跳过反馈是否允许进入二轮解释。默认允许，便于模型说明为什么没有结果。
    """

    max_tool_steps: int = 4
    max_second_turns: int = 4
    max_tool_calls_per_turn: int = 8
    max_total_tokens: int = 16000
    global_timeout_seconds: int = 180
    tool_wait_timeout_seconds: int = 30
    require_human_takeover_on_approval: bool = True
    allow_failed_feedback_second_turn: bool = True
    allow_skipped_feedback_second_turn: bool = True


@dataclass(frozen=True)
class AgentLoopControlState:
    """当前 Agent loop 的运行态快照。

    这些字段未来可以来自 Python Runtime 内存状态、Java run 状态、Redis session、Kafka replay 或前端会话。
    当前先作为参数显式传入，原因是我们还没有实现完整 loop 引擎，不应过早绑定某个存储方案。
    """

    tool_step_index: int = 0
    completed_second_turns: int = 0
    consumed_tokens: int = 0
    estimated_next_turn_tokens: int = 0
    elapsed_seconds: int = 0
    waiting_control_plane_seconds: int = 0
    human_takeover_requested: bool = False
    cancelled: bool = False


@dataclass(frozen=True)
class AgentLoopControlDecision:
    """受控 loop 策略的决策结果。

    `allowed` 只表示“是否允许继续自动推进”，不表示工具是否成功。`action` 给出更精确的下一步语义；
    `reasons` 适合写入审计或调试日志；`recommended_actions` 适合返回给 API 或前端展示。
    """

    allowed: bool
    action: AgentLoopControlAction
    reasons: tuple[str, ...]
    recommended_actions: tuple[str, ...]

    def to_summary(self) -> dict[str, Any]:
        """转换为 API 友好的摘要。"""

        return {
            "allowed": self.allowed,
            "action": self.action.value,
            "reasons": self.reasons,
            "recommendedActions": self.recommended_actions,
        }


class AgentLoopControlPolicyEvaluator:
    """评估 Agent loop 是否可以继续自动推进。

    评估顺序采用“先硬阻断，再业务状态，再允许”的方式：
    1. 用户取消、人工接管、全局超时、步数/预算上限属于硬阻断；
    2. 控制面反馈缺失、等待审批属于业务等待；
    3. 失败/跳过是否允许继续由策略开关决定；
    4. 所有条件都满足时才允许进入二轮模型推理。
    """

    def __init__(self, policy: AgentLoopControlPolicy | None = None) -> None:
        self._policy = policy or AgentLoopControlPolicy()

    def evaluate(
        self,
        snapshot: AgentControlPlaneFeedbackSnapshot,
        state: AgentLoopControlState | None = None,
    ) -> AgentLoopControlDecision:
        """根据控制面反馈快照和当前 loop 状态生成决策。"""

        current_state = state or AgentLoopControlState()
        hard_stop = self._hard_stop_decision(current_state)
        if hard_stop is not None:
            return hard_stop

        if snapshot.expected_tool_call_count == 0:
            return AgentLoopControlDecision(
                allowed=False,
                action=AgentLoopControlAction.STOP_NO_WORK,
                reasons=("当前 AgentPlan 没有可推进的模型工具调用。",),
                recommended_actions=("保留当前文本/规则式计划结果，不进入工具反馈二轮。",),
            )

        if snapshot.expected_tool_call_count > self._policy.max_tool_calls_per_turn:
            return AgentLoopControlDecision(
                allowed=False,
                action=AgentLoopControlAction.STOP_STEP_LIMIT,
                reasons=(
                    f"单轮工具调用数量 {snapshot.expected_tool_call_count} 超过上限 "
                    f"{self._policy.max_tool_calls_per_turn}。",
                ),
                recommended_actions=("要求模型或用户缩小任务范围，或拆分为多个受控任务批次。",),
            )

        if snapshot.missing_tool_call_ids:
            return self._wait_control_plane_decision(snapshot, current_state)

        waiting_approval_count = snapshot.status_counts.get(ToolExecutionFeedbackStatus.WAITING_APPROVAL.value, 0)
        if waiting_approval_count > 0:
            return self._approval_decision(waiting_approval_count)

        unsupported_status_decision = self._unsupported_status_decision(snapshot)
        if unsupported_status_decision is not None:
            return unsupported_status_decision

        if snapshot.second_turn_eligible:
            return AgentLoopControlDecision(
                allowed=True,
                action=AgentLoopControlAction.ALLOW_SECOND_TURN,
                reasons=("控制面反馈完整，且未发现等待审批或策略阻断。",),
                recommended_actions=(
                    "允许在当前预算、步数和超时保护下进入二轮模型推理。",
                    "二轮推理仍应关闭无限工具循环，下一轮继续由本策略重新评估。",
                ),
            )

        return AgentLoopControlDecision(
            allowed=False,
            action=AgentLoopControlAction.WAIT_FOR_CONTROL_PLANE,
            reasons=("控制面反馈尚未满足二轮推理条件。",),
            recommended_actions=("等待 Java 控制面状态更新，或通过事件 replay 重新评估。",),
        )

    def _hard_stop_decision(self, state: AgentLoopControlState) -> AgentLoopControlDecision | None:
        """评估取消、人工接管、超时、步数和预算等硬阻断条件。"""

        if state.cancelled:
            return AgentLoopControlDecision(
                allowed=False,
                action=AgentLoopControlAction.REQUIRE_HUMAN_TAKEOVER,
                reasons=("用户或控制面已经取消当前 Agent loop。",),
                recommended_actions=("停止自动推进，并在前端展示取消状态与已完成审计记录。",),
            )
        if state.human_takeover_requested:
            return AgentLoopControlDecision(
                allowed=False,
                action=AgentLoopControlAction.REQUIRE_HUMAN_TAKEOVER,
                reasons=("当前 loop 已被标记为需要人工接管。",),
                recommended_actions=("暂停自动模型推理，等待项目负责人、审批人或运维人员处理。",),
            )
        if state.elapsed_seconds >= self._policy.global_timeout_seconds:
            return AgentLoopControlDecision(
                allowed=False,
                action=AgentLoopControlAction.STOP_TIMEOUT,
                reasons=(
                    f"Agent loop 已运行 {state.elapsed_seconds}s，达到全局超时 "
                    f"{self._policy.global_timeout_seconds}s。",
                ),
                recommended_actions=("停止自动推进，保留当前进度并建议用户稍后重试或转人工处理。",),
            )
        if state.tool_step_index >= self._policy.max_tool_steps:
            return AgentLoopControlDecision(
                allowed=False,
                action=AgentLoopControlAction.STOP_STEP_LIMIT,
                reasons=(
                    f"工具步数 {state.tool_step_index} 已达到上限 {self._policy.max_tool_steps}。",
                ),
                recommended_actions=("停止继续调用工具，要求模型总结当前已知结果并交由用户确认。",),
            )
        if state.completed_second_turns >= self._policy.max_second_turns:
            return AgentLoopControlDecision(
                allowed=False,
                action=AgentLoopControlAction.STOP_STEP_LIMIT,
                reasons=(
                    f"二轮推理次数 {state.completed_second_turns} 已达到上限 {self._policy.max_second_turns}。",
                ),
                recommended_actions=("停止自动二轮推理，避免当前最小闭环阶段进入不可控循环。",),
            )
        if state.consumed_tokens + state.estimated_next_turn_tokens > self._policy.max_total_tokens:
            return AgentLoopControlDecision(
                allowed=False,
                action=AgentLoopControlAction.STOP_BUDGET_EXCEEDED,
                reasons=(
                    f"预计 token 消耗 {state.consumed_tokens + state.estimated_next_turn_tokens} "
                    f"超过预算 {self._policy.max_total_tokens}。",
                ),
                recommended_actions=("改用更小上下文、低成本模型、批处理模式，或等待租户预算提升。",),
            )
        return None

    def _wait_control_plane_decision(
        self,
        snapshot: AgentControlPlaneFeedbackSnapshot,
        state: AgentLoopControlState,
    ) -> AgentLoopControlDecision:
        """处理 Java 控制面反馈缺失场景。"""

        if state.waiting_control_plane_seconds >= self._policy.tool_wait_timeout_seconds:
            return AgentLoopControlDecision(
                allowed=False,
                action=AgentLoopControlAction.STOP_TIMEOUT,
                reasons=(
                    f"等待控制面反馈 {state.waiting_control_plane_seconds}s，达到单工具等待上限 "
                    f"{self._policy.tool_wait_timeout_seconds}s。",
                    f"缺失反馈的 toolCallIds：{', '.join(snapshot.missing_tool_call_ids)}。",
                ),
                recommended_actions=("停止本轮自动推进，建议通过事件 replay、结果查询或人工排障恢复。",),
            )
        return AgentLoopControlDecision(
            allowed=False,
            action=AgentLoopControlAction.WAIT_FOR_CONTROL_PLANE,
            reasons=(f"仍有 {len(snapshot.missing_tool_call_ids)} 个工具调用缺少 Java 控制面反馈。",),
            recommended_actions=("等待 Java 执行事件、WebSocket 推送或稍后按 runId replay。",),
        )

    def _approval_decision(self, waiting_approval_count: int) -> AgentLoopControlDecision:
        """处理等待审批场景。"""

        if self._policy.require_human_takeover_on_approval:
            return AgentLoopControlDecision(
                allowed=False,
                action=AgentLoopControlAction.REQUIRE_HUMAN_TAKEOVER,
                reasons=(f"存在 {waiting_approval_count} 个工具等待人工审批。",),
                recommended_actions=("展示审批入口并停止自动 loop，等待授权审批人处理。",),
            )
        return AgentLoopControlDecision(
            allowed=False,
            action=AgentLoopControlAction.WAIT_FOR_APPROVAL,
            reasons=(f"存在 {waiting_approval_count} 个工具等待审批。",),
            recommended_actions=("等待审批状态变更事件后重新评估。",),
        )

    def _unsupported_status_decision(
        self,
        snapshot: AgentControlPlaneFeedbackSnapshot,
    ) -> AgentLoopControlDecision | None:
        """根据策略开关判断失败或跳过反馈是否允许进入二轮解释。"""

        failed_count = snapshot.status_counts.get(ToolExecutionFeedbackStatus.FAILED.value, 0)
        skipped_count = snapshot.status_counts.get(ToolExecutionFeedbackStatus.SKIPPED.value, 0)
        if failed_count > 0 and not self._policy.allow_failed_feedback_second_turn:
            return AgentLoopControlDecision(
                allowed=False,
                action=AgentLoopControlAction.WAIT_FOR_CONTROL_PLANE,
                reasons=(f"存在 {failed_count} 个失败反馈，当前策略禁止失败结果进入二轮模型。",),
                recommended_actions=("由 Java 控制面按重试策略处理失败，或转人工诊断。",),
            )
        if skipped_count > 0 and not self._policy.allow_skipped_feedback_second_turn:
            return AgentLoopControlDecision(
                allowed=False,
                action=AgentLoopControlAction.WAIT_FOR_CONTROL_PLANE,
                reasons=(f"存在 {skipped_count} 个跳过反馈，当前策略禁止跳过结果进入二轮模型。",),
                recommended_actions=("等待更明确的工具状态，或将该工具调用标记为人工处理。",),
            )
        return None
