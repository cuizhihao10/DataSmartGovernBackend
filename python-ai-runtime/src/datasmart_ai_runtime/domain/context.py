"""RAG/GraphRAG 上下文领域契约。

Agent 做工具规划时，不能只依赖用户一句自然语言。真实数据治理产品里，模型需要同时看到：
数据源元数据、权限事实、质量规则案例、历史事故、任务状态、租户策略等上下文。

本文件先定义统一的 `ContextBlock`，它是后续向量检索、知识图谱检索、Java 微服务查询和人工输入
汇聚到模型提示词之前的标准载体。先统一上下文契约，再接具体检索技术，可以减少后续重构成本。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum
from typing import Any


class ContextSourceType(str, Enum):
    """上下文来源类型。

    这里不是为了枚举所有未来来源，而是先把最核心的治理上下文分层。不同来源的可信度、刷新策略、
    脱敏要求和可审计要求不同，统一放进 source_type 后，后续可以按类型做排序、截断和合规处理。
    """

    USER_OBJECTIVE = "user_objective"
    DATASOURCE_METADATA = "datasource_metadata"
    PERMISSION_FACT = "permission_fact"
    QUALITY_RULE_CASE = "quality_rule_case"
    SYSTEM_POLICY = "system_policy"
    USER_PROFILE = "user_profile"


class ContextSensitivityLevel(str, Enum):
    """上下文敏感级别。

    RAG/GraphRAG 不是把所有检索内容直接塞进 prompt。真实企业环境里，上下文可能包含字段名、
    业务口径、权限事实、异常样本、敏感数据摘要等内容。敏感级别用于决定：
    - 是否允许进入模型；
    - 是否需要脱敏；
    - 是否允许跨租户缓存；
    - 是否需要更严格的审计与审批。
    """

    PUBLIC = "public"
    INTERNAL = "internal"
    CONFIDENTIAL = "confidential"
    RESTRICTED = "restricted"


@dataclass(frozen=True)
class ContextBlock:
    """Agent 可消费的上下文块。

    字段设计说明：
    - `source_type`：说明上下文来自哪里，便于后续做 RAG/GraphRAG 分层检索和审计。
    - `title`：给人读和模型读的短标题，适合展示在调试面板或 prompt 分段标题中。
    - `content`：真正给模型或规则规划器消费的文本内容，当前先使用文本，未来可扩展结构化 payload。
    - `relevance_score`：相关性分数，后续向量召回、图谱路径和规则命中都可以映射到该字段。
    - `metadata`：保存 datasourceId、projectId、actorId、ruleType 等机器可读字段，便于追溯。
    - `sensitivity_level`：上下文敏感级别，后续用于脱敏、模型准入、审计和跨租户缓存策略。
    - `source_id`：上下文来源 ID，例如数据源 ID、规则案例 ID、权限事实 ID，便于回放和排障。
    - `expires_at`：上下文过期时间。元数据快照、权限事实、系统策略都有时效性，不能永久信任。
    - `token_estimate`：粗略 token 估算值，后续用于 prompt 预算、截断和成本预估。
    """

    source_type: ContextSourceType
    title: str
    content: str
    relevance_score: float = 1.0
    metadata: dict[str, Any] = field(default_factory=dict)
    sensitivity_level: ContextSensitivityLevel = ContextSensitivityLevel.INTERNAL
    source_id: str | None = None
    expires_at: datetime | None = None
    token_estimate: int = 0
