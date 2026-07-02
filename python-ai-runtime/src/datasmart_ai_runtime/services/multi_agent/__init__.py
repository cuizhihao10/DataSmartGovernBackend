"""多智能体协作相关服务包。

这个包承接 DataSmart Python Runtime 中与 OpenClaw / LangGraph 风格多智能体协作有关的
稳定领域能力。之所以把这些能力从 `services` 根目录继续拆出一层，是为了避免 LangGraph、
产品 Agent 名册、执行计划、handoff 策略等文件继续平铺堆放，后续读代码时能够快速判断：

- `product_agent_catalog`：平台规划中的产品级 Agent 名册与运行角色映射；
- `langgraph_execution_plan`：把会话调度事实转换为执行前多 Agent 工作项和协作边；
- `controlled_execution_session`：把执行前工作项转换为可恢复、可观察、无副作用的受控多 Agent 会话；
- 后续如果补真实 handoff、checkpoint 或多 Agent runtime，也应优先放在本包下。

当前包内模块仍然只处理低敏控制面事实，不执行工具、不调用模型、不写 outbox。
"""

from datasmart_ai_runtime.services.multi_agent.controlled_execution_session import (
    ControlledMultiAgentExecutionSession,
    ControlledMultiAgentExecutionWorkItem,
    MultiAgentExecutionSessionService,
)

__all__ = [
    "ControlledMultiAgentExecutionSession",
    "ControlledMultiAgentExecutionWorkItem",
    "MultiAgentExecutionSessionService",
]
