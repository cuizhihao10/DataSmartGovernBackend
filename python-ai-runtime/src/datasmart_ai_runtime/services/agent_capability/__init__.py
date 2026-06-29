"""Agent 能力完备度服务包。

这个包服务于项目最后阶段的“Agent 能力闭环收敛”：它不执行工具、不调用模型、不读取业务数据，而是把
一个商业化 Agent Host 至少需要具备的 tools、skills、memory、query engine、context、permission、
sub-agent、sessions、command、hook、tech stack、LLM 等能力拆成可追踪子项。

为什么需要单独建包：
- `platform_convergence` 关注全平台模块收敛，粒度较粗；
- `agent_capability` 关注 Agent Host 自身的能力完备度，粒度更细；
- 后续我们可以按这个矩阵逐项关闭缺口，而不是继续在某个局部 preview 上无限扩写。
"""

from datasmart_ai_runtime.services.agent_capability.agent_capability_matrix import (
    AgentCapabilityMatrixService,
    AgentCapabilityStatus,
    default_agent_capability_matrix_service,
)
from datasmart_ai_runtime.services.agent_capability.agent_capability_closure import (
    AgentClosureGateDecision,
    build_agent_capability_closure_readiness,
)

__all__ = (
    "AgentClosureGateDecision",
    "AgentCapabilityMatrixService",
    "AgentCapabilityStatus",
    "build_agent_capability_closure_readiness",
    "default_agent_capability_matrix_service",
)
