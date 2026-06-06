"""外部 Agent 协议领域契约子包。

DataSmart 的 Python Runtime 后续会同时消费 Java 控制面的内部合同、MCP 工具/资源/Prompt 投影、
A2A Agent Card 与 A2A Task 合同。如果继续把这些对象全部平铺在 `domain/` 根目录下，领域层会很快
变成“所有协议对象的大杂烩”，后续维护者也很难判断某个 dataclass 到底属于模型网关、记忆、Skill
还是外部 Agent 互联。

因此这里单独建立 `domain.protocols` 子包：它只放“协议层稳定数据结构”，不放网络调用、不放 API
路由、不放真实执行逻辑。服务层可以把 Java/HTTP/Kafka 输入转换成这些对象，编排层再基于这些对象
做安全规划。
"""

from datasmart_ai_runtime.domain.protocols.a2a_task import (
    A2aTaskArtifactReference,
    A2aTaskControlPlaneSnapshot,
    A2aTaskHistoryEvent,
    A2aTaskState,
    AgentTaskPlanningDecision,
    AgentTaskPlanningMode,
    AgentTaskPlanningStatus,
    AgentTaskSuggestedAction,
)

__all__ = (
    "A2aTaskArtifactReference",
    "A2aTaskControlPlaneSnapshot",
    "A2aTaskHistoryEvent",
    "A2aTaskState",
    "AgentTaskPlanningDecision",
    "AgentTaskPlanningMode",
    "AgentTaskPlanningStatus",
    "AgentTaskSuggestedAction",
)
