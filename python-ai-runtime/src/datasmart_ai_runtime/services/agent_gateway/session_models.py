"""智能网关会话调度领域模型。

本文件只保存枚举和低敏视图 dataclass，避免 `session_scheduler.py` 同时承担“模型定义 + 调度规则”
两类职责导致文件膨胀。拆分后调度器可以专注于业务规则，本文件则作为 API 响应、测试和未来 Java
投影共同依赖的稳定契约。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Any


class AgentSessionRole(str, Enum):
    """DataSmart 智能网关当前认识的 Agent 角色。

    角色枚举是会话调度的稳定语言，不直接等于某个 Python 类或模型名称。未来即使底层切换到
    OpenClaw 风格 runtime、A2A 远程 Agent、MCP server 或 Java 插件，前端和审计系统仍可以继续
    消费这些角色编码。
    """

    MASTER_ORCHESTRATOR = "MASTER_ORCHESTRATOR"
    DATASOURCE_AGENT = "DATASOURCE_AGENT"
    DATA_QUALITY_AGENT = "DATA_QUALITY_AGENT"
    DATA_SYNC_AGENT = "DATA_SYNC_AGENT"
    TASK_AGENT = "TASK_AGENT"
    PERMISSION_AGENT = "PERMISSION_AGENT"
    MEMORY_AGENT = "MEMORY_AGENT"
    OPS_AGENT = "OPS_AGENT"
    KNOWLEDGE_AGENT = "KNOWLEDGE_AGENT"


class AgentParticipationMode(str, Enum):
    """Agent 在本轮会话中的参与方式。

    - `PRIMARY`：主控 Agent，负责目标理解、计划整合和最终交付摘要；
    - `SPECIALIST`：专家 Agent，负责某个治理域的工具、规则或诊断；
    - `GUARDRAIL`：防护型 Agent，主要提供权限、审计、预算、运维或记忆边界控制；
    - `OBSERVER`：观察型 Agent，只提供诊断建议，不应参与工具执行。
    """

    PRIMARY = "PRIMARY"
    SPECIALIST = "SPECIALIST"
    GUARDRAIL = "GUARDRAIL"
    OBSERVER = "OBSERVER"


class AgentSchedulingStatus(str, Enum):
    """会话调度状态。

    状态用于产品界面和控制面判断“本轮是否能继续自动推进”：
    - `READY`：可按计划进入下一步；
    - `DEGRADED`：可返回解释或草案，但有模型、预算、Skill、记忆等能力缺口；
    - `APPROVAL_REQUIRED`：需要人工审批后才能执行高风险动作；
    - `BLOCKED`：关键能力不可用，不应继续自动执行。
    """

    READY = "READY"
    DEGRADED = "DEGRADED"
    APPROVAL_REQUIRED = "APPROVAL_REQUIRED"
    BLOCKED = "BLOCKED"


@dataclass(frozen=True)
class ScheduledAgentView:
    """单个参与 Agent 的低敏调度视图。

    该对象只保存控制面需要的摘要，不保存 prompt、工具参数、模型消息、记忆正文或样本数据。
    真实产品中这类对象可以写入审计日志、前端会话卡片或 Java Runtime Event 投影。
    """

    role: AgentSessionRole
    display_name: str
    participation_mode: AgentParticipationMode
    activation_reasons: tuple[str, ...]
    governed_domains: tuple[str, ...] = ()
    visible_skill_codes: tuple[str, ...] = ()
    planned_tool_names: tuple[str, ...] = ()
    memory_dependencies: tuple[str, ...] = ()
    status: AgentSchedulingStatus = AgentSchedulingStatus.READY
    degradation_reasons: tuple[str, ...] = ()
    requires_handoff: bool = False

    def to_summary(self) -> dict[str, Any]:
        """转换为 API 响应友好的字典。

        这里显式列字段，而不是直接 `asdict`，是为了控制输出稳定性：未来 dataclass 增加内部字段时，
        不会意外暴露给外部调用方。
        """

        return {
            "role": self.role.value,
            "displayName": self.display_name,
            "participationMode": self.participation_mode.value,
            "activationReasons": self.activation_reasons,
            "governedDomains": self.governed_domains,
            "visibleSkillCodes": self.visible_skill_codes,
            "plannedToolNames": self.planned_tool_names,
            "memoryDependencies": self.memory_dependencies,
            "status": self.status.value,
            "degradationReasons": self.degradation_reasons,
            "requiresHandoff": self.requires_handoff,
        }


@dataclass(frozen=True)
class AgentSessionSchedulingPolicyView:
    """一次 Agent 会话的统一调度策略视图。

    `participating_agents` 是面向产品和审计的“谁参与了本轮会话”的答案；`policy_axes` 则说明调度依据
    来自哪些治理维度。这个视图不会替代 `AgentPlan`，而是把复杂计划压缩为智能网关可展示、可回放、
    可后续接真实多 Agent runtime 的控制面摘要。
    """

    available: bool
    status: AgentSchedulingStatus
    primary_agent_role: str
    participating_agents: tuple[ScheduledAgentView, ...]
    policy_axes: dict[str, Any] = field(default_factory=dict)
    handoff_required: bool = False
    display_summary: str = ""
    recommended_actions: tuple[str, ...] = ()

    def to_summary(self) -> dict[str, Any]:
        """输出低敏策略摘要。"""

        return {
            "available": self.available,
            "status": self.status.value,
            "primaryAgentRole": self.primary_agent_role,
            "participatingAgentCount": len(self.participating_agents),
            "participatingAgents": tuple(agent.to_summary() for agent in self.participating_agents),
            "policyAxes": self.policy_axes,
            "handoffRequired": self.handoff_required,
            "displaySummary": self.display_summary,
            "recommendedActions": self.recommended_actions,
        }
