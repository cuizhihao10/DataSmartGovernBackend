"""Agent Skill 描述领域契约。

Skill 在 DataSmart Govern 中不是简单 prompt 片段，而是“可发现、可治理、可审计的能力包”。
一个 Skill 应该说明自己适合什么治理域、依赖哪些工具、需要哪些权限、风险等级如何、是否需要
审批、会读写哪些记忆、有哪些示例工作流。这样后续无论 Skill 来自本地文件、Java 控制面、
插件市场、MCP server 还是租户自定义配置，Python Runtime 都能用同一套契约选择和解释它。
"""

from __future__ import annotations

from dataclasses import dataclass, field

from datasmart_ai_runtime.domain.intent import GovernanceDomain
from datasmart_ai_runtime.domain.memory import AgentMemoryType


@dataclass(frozen=True)
class AgentSkillDescriptor:
    """Agent Skill 描述符。

    字段说明：
    - `skill_code`：稳定编码，后续可作为 Java 控制面、Skill 市场和审计日志的主键。
    - `domain`：Skill 所属治理域，用于从意图分析结果中快速筛选候选能力。
    - `required_tools`：该 Skill 通常需要哪些工具；工具仍由 Java `agent-runtime` 统一治理。
    - `memory_dependencies`：该 Skill 依赖哪些记忆类型，例如质量规则设计依赖语义和情节记忆。
    - `approval_policy`：审批策略说明，当前先用字符串保持轻量，后续可升级为枚举或策略对象。
    - `trigger_keywords`：规则式降级选择时使用的关键词；未来可替换为 embedding/LLM 选择器。
    """

    skill_code: str
    display_name: str
    description: str
    domain: GovernanceDomain
    required_tools: tuple[str, ...] = ()
    required_permissions: tuple[str, ...] = ()
    memory_dependencies: tuple[AgentMemoryType, ...] = ()
    risk_level: str = "low"
    approval_policy: str = "NONE"
    enabled: bool = True
    trigger_keywords: tuple[str, ...] = ()
    examples: tuple[str, ...] = ()
    attributes: dict[str, object] = field(default_factory=dict)


@dataclass(frozen=True)
class AgentSkillSelection:
    """一次请求命中的 Skill。

    选择结果保留 `score` 与 `reason`，是为了让 Agent 计划可解释。真实产品里用户需要知道系统为什么
    选择“质量规则设计 Skill”而不是“同步任务诊断 Skill”，审计人员也需要看到选择依据。
    """

    skill_code: str
    display_name: str
    domain: GovernanceDomain
    score: float
    reason: str
    required_tools: tuple[str, ...] = ()
    memory_dependencies: tuple[AgentMemoryType, ...] = ()
    approval_policy: str = "NONE"


@dataclass(frozen=True)
class AgentSkillPlan:
    """Agent Skill 选择计划。

    `selected_skills` 代表当前请求推荐启用的能力包；`available_skill_count` 记录可用 Skill 总量，
    便于后续监控“为什么没有命中 Skill”。如果没有命中，前端或运营后台可以提示补充 Skill 或优化
    意图识别，而不是让 Agent 静默退化成纯工具规划。
    """

    selected_skills: tuple[AgentSkillSelection, ...] = ()
    available_skill_count: int = 0
    rationale: str = ""
