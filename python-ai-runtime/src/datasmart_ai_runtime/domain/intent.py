"""Agent 意图分析领域契约。

`modelIntentSummary` 这种字符串摘要适合给人阅读，但不适合后续程序决策。商业化 Agent 需要把
用户目标解析成结构化信息：涉及哪个治理域、候选工具是什么、有哪些风险、还缺哪些参数、置信度
是多少。这样工具规划、审批策略、前端提示和审计系统才能稳定消费，而不是从自然语言里二次猜测。
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum


class GovernanceDomain(str, Enum):
    """治理域分类。

    治理域用于回答“这次用户目标属于平台的哪类业务能力”。它不是工具名，也不是微服务名，
    而是更接近产品语义的分类，后续可用于 Agent 路由、多 Agent 协作和前端场景提示。
    """

    DATASOURCE = "datasource"
    DATA_QUALITY = "data_quality"
    DATA_SYNC = "data_sync"
    TASK_MANAGEMENT = "task_management"
    PERMISSION_ADMIN = "permission_admin"
    KNOWLEDGE_QA = "knowledge_qa"
    GENERAL_GOVERNANCE = "general_governance"


class IntentRiskTag(str, Enum):
    """意图风险标签。

    风险标签比工具风险等级更早出现：在还没生成具体工具计划之前，系统就可以先判断用户目标是否
    可能涉及只读、草稿生成、状态变更、审批、敏感数据或跨项目边界。后续审批策略可结合标签决策。
    """

    READ_ONLY = "read_only"
    DRAFT_GENERATION = "draft_generation"
    STATE_CHANGE = "state_change"
    APPROVAL_REQUIRED = "approval_required"
    SENSITIVE_DATA = "sensitive_data"
    CROSS_SCOPE = "cross_scope"
    DATA_EXPORT = "data_export"
    WRITE_SQL = "write_sql"
    CROSS_TENANT = "cross_tenant"


@dataclass(frozen=True)
class IntentAnalysis:
    """结构化意图分析结果。

    字段说明：
    - `summary`：面向用户和调试面板的人读摘要。
    - `governance_domains`：涉及的治理域，支持一次请求跨数据源、质量、同步、任务等多个域。
    - `candidate_tools`：可能需要的工具名称，供规划器或前端解释“为什么会选择这些工具”。
    - `risk_tags`：意图级风险标签，早于具体工具执行审批。
    - `missing_parameters`：缺失但后续执行工具可能必须补齐的参数。
    - `confidence`：规则或模型对本次意图判断的置信度，0 到 1 之间。
    - `reasoning`：简短说明分析依据，便于学习、审计和排障。
    """

    summary: str
    governance_domains: tuple[GovernanceDomain, ...]
    candidate_tools: tuple[str, ...] = ()
    risk_tags: tuple[IntentRiskTag, ...] = ()
    missing_parameters: tuple[str, ...] = ()
    confidence: float = 0.5
    reasoning: str = ""
