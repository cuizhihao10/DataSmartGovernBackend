"""产品级 Agent 名册与运行角色映射。

DataSmart 的 README 技术路线规划了 8 个产品 Agent，但当前 Python Runtime 中已经存在的
`MASTER_ORCHESTRATOR`、`DATASOURCE_AGENT`、`PERMISSION_AGENT` 等运行角色并不完全等价于
“已经实现完整专项 Agent”。这个模块把二者的关系集中维护，避免多个 LangGraph 工作流各自复制
一份名册，造成角色覆盖判断不一致。

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

    注意：这里的“覆盖”只表示当前运行角色能承担一部分职责，不代表该产品 Agent 已经具备完整
    独立执行能力。真正的商业化闭环仍需要 Java 控制面事实、权限、审计、任务状态和可恢复执行链路。
    """

    role: str
    display_name: str
    route_role_aliases: tuple[str, ...]
    product_scope: str

    def to_summary(self) -> dict[str, Any]:
        """输出可放入 `/agent/plans` 的低敏产品 Agent 摘要。"""

        return {
            "role": self.role,
            "displayName": self.display_name,
            "routeRoleAliases": self.route_role_aliases,
            "productScope": self.product_scope,
        }


PLANNED_PRODUCT_AGENTS: tuple[ProductAgentDefinition, ...] = (
    ProductAgentDefinition(
        role="MASTER_ORCHESTRATOR",
        display_name="总控调度智能体",
        route_role_aliases=("MASTER_ORCHESTRATOR",),
        product_scope="需求解析、任务拆解、智能体协调、进度监控、结果汇总。",
    ),
    ProductAgentDefinition(
        role="DATASOURCE_ACCESS_AGENT",
        display_name="数据源接入智能体",
        route_role_aliases=("DATASOURCE_AGENT",),
        product_scope="多源数据接入、元数据采集、连接测试、连接维护。",
    ),
    ProductAgentDefinition(
        role="DATA_QUALITY_AGENT",
        display_name="数据质量智能体",
        route_role_aliases=("DATA_QUALITY_AGENT",),
        product_scope="质量规则生成、异常检测、清洗方案推荐、质量复盘。",
    ),
    ProductAgentDefinition(
        role="ETL_DEVELOPMENT_AGENT",
        display_name="ETL 开发智能体",
        route_role_aliases=("DATA_SYNC_AGENT",),
        product_scope="自然语言转 ETL 脚本、脚本调试、性能优化、发布执行。",
    ),
    ProductAgentDefinition(
        role="DATA_ASSET_AGENT",
        display_name="数据资产智能体",
        route_role_aliases=("KNOWLEDGE_AGENT",),
        product_scope="数据字典、关系图谱、业务口径映射、资产检索。",
    ),
    ProductAgentDefinition(
        role="COMPLIANCE_MASKING_AGENT",
        display_name="合规脱敏智能体",
        route_role_aliases=("PERMISSION_AGENT",),
        product_scope="敏感数据识别、分级分类、脱敏方案、合规审计。",
    ),
    ProductAgentDefinition(
        role="OPS_ALERT_AGENT",
        display_name="运维告警智能体",
        route_role_aliases=("OPS_AGENT",),
        product_scope="指标监控、异常告警、自动恢复建议、运维复盘。",
    ),
    ProductAgentDefinition(
        role="REFLECTION_OPTIMIZATION_AGENT",
        display_name="反思优化智能体",
        route_role_aliases=("MEMORY_AGENT",),
        product_scope="任务复盘、规则优化、能力迭代、Skill 与参数优化建议。",
    ),
)


def planned_route_aliases() -> set[str]:
    """返回所有已经映射到产品 Agent 的运行时角色集合。

    该集合用于区分“产品专项 Agent 覆盖角色”和“运行治理辅助角色”。例如 `TASK_AGENT` 在当前系统里
    很重要，但它更像任务控制面辅助角色，不是 README 8 个产品 Agent 中单独列出的专项 Agent。
    """

    aliases: set[str] = set()
    for agent in PLANNED_PRODUCT_AGENTS:
        aliases.update(agent.route_role_aliases)
    return aliases
