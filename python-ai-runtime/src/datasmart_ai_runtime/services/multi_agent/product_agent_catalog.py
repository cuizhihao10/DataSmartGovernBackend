"""产品级 Agent 名册与运行角色映射。

DataSmart 的 README 技术路线规划了 8 个产品 Agent，但当前 Python Runtime 中已经存在的
`MASTER_ORCHESTRATOR`、`DATASOURCE_AGENT`、`PERMISSION_AGENT` 等运行角色并不完全等价于
“已经实现完整专项 Agent”。这个模块把二者的关系集中维护，避免多个 LangGraph 工作流各自复制
一份名册，造成角色覆盖判断不一致。

当前收敛阶段还额外维护用户确认过的交付优先级：
- 必做：MASTER_ORCHESTRATOR、DATASOURCE_AGENT、DATA_QUALITY_AGENT、PERMISSION_AGENT、TASK_AGENT；
- 应做但控制范围：MEMORY_AGENT、OPS_AGENT、DATA_SYNC_AGENT；
- 暂缓或轻量化：ETL_DEVELOPMENT_AGENT、DATA_ASSET_AGENT、COMPLIANCE_MASKING_AGENT、REFLECTION_OPTIMIZATION_AGENT。

这组优先级的含义是“当前闭环要把哪些运行角色做扎实”，不是继续扩张新 Agent 数量。尤其要避免把
PERMISSION_AGENT 误认为 COMPLIANCE_MASKING_AGENT 已完整实现，或把 MEMORY_AGENT 误认为
REFLECTION_OPTIMIZATION_AGENT 已完整实现。

安全边界：
- 本模块只保存角色编码、展示名、静态职责说明和运行角色别名；
- 不保存 prompt、SQL、工具参数、样本数据、模型输出、token、内部 endpoint 或真实业务 payload；
- 对外返回的 `productScope` 是静态产品说明，用于帮助前端和运维理解 Agent 覆盖状态。
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any


@dataclass(frozen=True)
class ProductAgentDefinition:
    """技术路线中规划的产品级智能体定义。

    字段说明：
    - `role`：产品级 Agent 的稳定编码，用于能力矩阵、LangGraph 图节点和审计摘要；
    - `display_name`：面向产品界面的中文展示名，不参与权限判断；
    - `route_role_aliases`：当前运行时可用的角色别名，用于判断该产品 Agent 是否被现有调度角色覆盖；
    - `product_scope`：静态职责范围说明，帮助用户理解该 Agent 未来应该承担的业务边界。
    - `delivery_tier`：当前收敛阶段的交付层级。它不是权限字段，只用于路线治理和前端说明。
    - `implementation_policy`：说明本阶段应该做到“完整闭环”“控制范围”还是“轻量化占位”。

    注意：这里的“覆盖”只表示当前运行角色能承担一部分职责，不代表该产品 Agent 已经具备完整
    独立执行能力。真正的商业化闭环仍需要 Java 控制面事实、权限、审计、任务状态和可恢复执行链路。
    """

    role: str
    display_name: str
    route_role_aliases: tuple[str, ...]
    product_scope: str
    delivery_tier: str
    implementation_policy: str

    def to_summary(self) -> dict[str, Any]:
        """输出可放入 `/agent/plans` 的低敏产品 Agent 摘要。"""

        return {
            "role": self.role,
            "displayName": self.display_name,
            "routeRoleAliases": self.route_role_aliases,
            "productScope": self.product_scope,
            "deliveryTier": self.delivery_tier,
            "implementationPolicy": self.implementation_policy,
        }


PLANNED_PRODUCT_AGENTS: tuple[ProductAgentDefinition, ...] = (
    ProductAgentDefinition(
        role="MASTER_ORCHESTRATOR",
        display_name="总控调度智能体",
        route_role_aliases=("MASTER_ORCHESTRATOR",),
        product_scope="需求解析、任务拆解、智能体协调、进度监控、结果汇总。",
        delivery_tier="must_do",
        implementation_policy="收敛期必须形成可观测、可恢复、可审计的主控编排闭环。",
    ),
    ProductAgentDefinition(
        role="DATASOURCE_ACCESS_AGENT",
        display_name="数据源接入智能体",
        route_role_aliases=("DATASOURCE_AGENT",),
        product_scope="多源数据接入、元数据采集、连接测试、连接维护。",
        delivery_tier="must_do",
        implementation_policy="收敛期必须覆盖数据源接入、元数据上下文和下游质量/同步依赖。",
    ),
    ProductAgentDefinition(
        role="DATA_QUALITY_AGENT",
        display_name="数据质量智能体",
        route_role_aliases=("DATA_QUALITY_AGENT",),
        product_scope="质量规则生成、异常检测、清洗方案推荐、质量复盘。",
        delivery_tier="must_do",
        implementation_policy="收敛期必须覆盖质量规则、检测计划、报告闭环和数据质量微服务协作。",
    ),
    ProductAgentDefinition(
        role="ETL_DEVELOPMENT_AGENT",
        display_name="ETL 开发智能体",
        route_role_aliases=("ETL_DEVELOPMENT_AGENT",),
        product_scope="自然语言转 ETL 脚本、脚本调试、性能优化、发布执行。",
        delivery_tier="lightweight",
        implementation_policy="本阶段只保留路线与合同，不把 DATA_SYNC_AGENT 误判为完整 ETL 开发智能体。",
    ),
    ProductAgentDefinition(
        role="DATA_ASSET_AGENT",
        display_name="数据资产智能体",
        route_role_aliases=("DATA_ASSET_AGENT",),
        product_scope="数据字典、关系图谱、业务口径映射、资产检索。",
        delivery_tier="lightweight",
        implementation_policy="本阶段只保留轻量化知识上下文和后续资产图谱扩展点。",
    ),
    ProductAgentDefinition(
        role="COMPLIANCE_MASKING_AGENT",
        display_name="合规脱敏智能体",
        route_role_aliases=("COMPLIANCE_MASKING_AGENT",),
        product_scope="敏感数据识别、分级分类、脱敏方案、合规审计。",
        delivery_tier="lightweight",
        implementation_policy="本阶段不把 PERMISSION_AGENT 的权限守门等同于完整合规脱敏 Agent。",
    ),
    ProductAgentDefinition(
        role="OPS_ALERT_AGENT",
        display_name="运维告警智能体",
        route_role_aliases=("OPS_AGENT",),
        product_scope="指标监控、异常告警、自动恢复建议、运维复盘。",
        delivery_tier="controlled_scope",
        implementation_policy="本阶段聚焦观测、告警和恢复建议，不扩大为自动化自愈执行器。",
    ),
    ProductAgentDefinition(
        role="REFLECTION_OPTIMIZATION_AGENT",
        display_name="反思优化智能体",
        route_role_aliases=("REFLECTION_OPTIMIZATION_AGENT",),
        product_scope="任务复盘、规则优化、能力迭代、Skill 与参数优化建议。",
        delivery_tier="lightweight",
        implementation_policy="本阶段只保留复盘路线，不把 MEMORY_AGENT 的记忆治理等同于完整反思优化。",
    ),
)


RUNTIME_AGENT_DELIVERY_TIERS: dict[str, str] = {
    "MASTER_ORCHESTRATOR": "must_do",
    "DATASOURCE_AGENT": "must_do",
    "DATA_QUALITY_AGENT": "must_do",
    "PERMISSION_AGENT": "must_do",
    "TASK_AGENT": "must_do",
    "MEMORY_AGENT": "controlled_scope",
    "OPS_AGENT": "controlled_scope",
    "DATA_SYNC_AGENT": "controlled_scope",
    "ETL_DEVELOPMENT_AGENT": "lightweight",
    "DATA_ASSET_AGENT": "lightweight",
    "COMPLIANCE_MASKING_AGENT": "lightweight",
    "REFLECTION_OPTIMIZATION_AGENT": "lightweight",
}


def runtime_agent_delivery_tiers() -> dict[str, str]:
    """返回用户确认过的运行时 Agent 交付优先级。

    该函数给 LangGraph 图、能力矩阵和指标模块提供单一事实来源，避免后续又把“当前参与调度的治理角色”
    和“README 中规划的产品专项 Agent”混在一起。
    """

    return dict(RUNTIME_AGENT_DELIVERY_TIERS)


def planned_route_aliases() -> set[str]:
    """返回所有已经映射到产品 Agent 的运行时角色集合。

    该集合用于区分“产品专项 Agent 覆盖角色”和“运行治理辅助角色”。例如 `TASK_AGENT` 在当前系统里
    很重要，但它更像任务控制面辅助角色，不是 README 8 个产品 Agent 中单独列出的专项 Agent。
    """

    aliases: set[str] = set()
    for agent in PLANNED_PRODUCT_AGENTS:
        aliases.update(agent.route_role_aliases)
    return aliases
