"""Agent 记忆分层领域契约。

本文件只定义“记忆应该如何被描述与规划”，暂不直接连接 Chroma、Neo4j、Redis 或 MySQL。
这样做的原因是：记忆系统属于 Agent 平台的长期核心能力，如果一开始就把业务语义绑定到某个
存储实现，后续更换向量库、图数据库、事件表或压缩策略时会产生大量重构。

DataSmart Govern 面向的是企业数据治理场景，记忆不等同于聊天历史。它至少要区分：
- 当前会话和当前工具参数这种短期记忆；
- 数据源元数据、业务术语、质量规则、指标定义这种语义记忆；
- 某次同步失败、某次质量异常、某次审批链路这种情节记忆；
- 常用治理流程、修复步骤、客户环境偏好这种程序记忆；
- 报告、SQL、日志、截图、配置文件引用这种资源记忆。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
from enum import Enum


class AgentMemoryType(str, Enum):
    """Agent 记忆类型。

    这里参考当前主流 Agent 记忆分层思路，但转换成 DataSmart 的业务语义：
    - `SHORT_TERM`：当前会话、当前 run、最近工具参数和临时状态，通常存 Redis 或内存。
    - `SEMANTIC`：稳定知识，例如数据源元数据、业务术语、质量规则、资产血缘，适合向量库/图谱。
    - `EPISODIC`：历史事件，例如失败执行、异常处理、审批记录，适合事件表或审计表。
    - `PROCEDURAL`：可复用流程，例如“质量规则生成步骤”“同步事故排查步骤”，适合 Skill/流程库。
    - `RESOURCE`：外部资源引用，例如报告文件、SQL、日志片段、截图、配置文件，适合对象存储索引。
    """

    SHORT_TERM = "short_term"
    SEMANTIC = "semantic"
    EPISODIC = "episodic"
    PROCEDURAL = "procedural"
    RESOURCE = "resource"


class AgentMemoryScope(str, Enum):
    """记忆可见范围。

    记忆范围是企业级 Agent 的安全边界。尤其在数据治理平台中，同一模型或同一 Agent 不能把
    一个项目的数据源元数据、错误样本或审批历史错误复用到另一个项目。
    """

    SESSION = "session"
    PROJECT = "project"
    TENANT = "tenant"
    GLOBAL = "global"


@dataclass(frozen=True)
class AgentMemoryRecord:
    """一条可被 Agent 检索的记忆记录。

    这个对象是后续 Chroma、Neo4j、Redis、MySQL 或 MinIO 索引层共同遵守的最小领域契约。
    它不是某个具体存储表结构，而是“进入 Agent 推理上下文前必须携带的治理信息”：
    - `memory_id`：全局唯一记录 ID，生产环境可来自数据库主键、事件 ID 或对象存储索引 ID。
    - `memory_type`：记忆分层类型，决定它是知识、事件、流程、资源还是会话状态。
    - `scope`：可见范围，检索器必须用它和请求中的 tenant/project/session 做隔离过滤。
    - `tenant_id/project_id/session_id`：企业级隔离字段。即使底层向量库召回了相似内容，也必须先过这些边界。
    - `title/content`：人读摘要和主体内容。当前内存实现会直接匹配它们；未来向量库可把 content 向量化。
    - `source`：来源说明，例如 `quality-report`、`sync-incident`、`manual-note`，用于审计和可信度展示。
    - `importance_score`：重要性分，便于把高价值经验排在前面。生产环境可由人工、规则或模型评估写入。
    - `sensitivity_level`：敏感级别说明，后续可和脱敏策略、审批策略、模型上下文准入策略联动。
    - `tags`：领域标签，检索时可做轻量关键词匹配，也方便后续 UI 筛选。
    - `created_at`：创建时间，当前用于可读性和未来排序扩展，统一使用 UTC 避免跨时区歧义。
    """

    memory_id: str
    memory_type: AgentMemoryType
    scope: AgentMemoryScope
    tenant_id: str | None
    project_id: str | None
    title: str
    content: str
    source: str = ""
    session_id: str | None = None
    importance_score: float = 0.5
    sensitivity_level: str = "internal"
    tags: tuple[str, ...] = ()
    created_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    attributes: dict[str, object] = field(default_factory=dict)


@dataclass(frozen=True)
class AgentMemoryRetrievalTarget:
    """一次 Agent 请求需要检索的记忆目标。

    字段说明：
    - `memory_type`：要检索哪类记忆，例如语义记忆或情节记忆。
    - `scope`：检索范围，默认越靠近当前会话/项目越安全。
    - `query_hint`：给后续检索器的人读/机器读提示，例如“数据源元数据”“质量异常案例”。
    - `reason`：解释为什么需要这类记忆，便于前端、审计和学习阅读。
    """

    memory_type: AgentMemoryType
    scope: AgentMemoryScope
    query_hint: str
    reason: str
    max_items: int = 5


@dataclass(frozen=True)
class AgentMemoryRetrievalResult:
    """某个检索目标对应的实际召回结果。

    `target` 保留原始检索目标，方便前端和审计系统解释“为什么查这些记忆”。
    `memories` 是经过租户/项目/会话隔离和相关性排序后的记录集合。
    `skipped_reason` 用来表达为什么没有检索，例如缺少 sessionId、目标类型暂未实现或策略主动跳过。
    """

    target: AgentMemoryRetrievalTarget
    memories: tuple[AgentMemoryRecord, ...] = ()
    skipped_reason: str = ""
    attributes: dict[str, object] = field(default_factory=dict)


@dataclass(frozen=True)
class AgentMemoryRetrievalReport:
    """一次 Agent 请求的记忆检索报告。

    这个报告与 `AgentMemoryPlan` 分开建模，是为了区分“应该检索什么”和“实际检索到了什么”。
    商业化 Agent 平台需要这层区别：计划可以进入审批和审计，结果可以进入上下文、解释面板和
    召回质量评估。未来接入 Chroma/Neo4j/Redis 时也应返回同样的报告结构。
    """

    results: tuple[AgentMemoryRetrievalResult, ...] = ()
    total_retrieved: int = 0
    retrieval_notes: tuple[str, ...] = ()
    attributes: dict[str, object] = field(default_factory=dict)


@dataclass(frozen=True)
class AgentMemoryPlan:
    """Agent 记忆计划。

    该对象回答两个问题：
    1. 本次请求在规划/推理前应该尝试检索哪些记忆；
    2. 本次工具执行结果未来允许写入哪些记忆。

    注意：这里仍然只是“计划”，不是“实际写入”。真实写入必须由 Java 控制面或后续 Memory Service
    结合权限、审批、脱敏、保留期和审计策略再做最终裁决。
    """

    retrieval_targets: tuple[AgentMemoryRetrievalTarget, ...] = ()
    writable_memory_types: tuple[AgentMemoryType, ...] = ()
    default_scope: AgentMemoryScope = AgentMemoryScope.PROJECT
    retention_days: int = 30
    approval_required_for_write: bool = False
    audit_required: bool = True
    privacy_notes: tuple[str, ...] = ()
    rationale: str = ""
    attributes: dict[str, object] = field(default_factory=dict)
