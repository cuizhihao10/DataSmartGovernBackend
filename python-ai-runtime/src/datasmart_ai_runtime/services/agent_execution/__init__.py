"""Agent 执行闭环服务包。

这个包承接“Agent 请求从计划生成走向真实可恢复执行”的收敛类能力。之所以单独建包，而不是继续把
文件平铺到 `services` 根目录，是为了让 Python Runtime 的目录结构逐步向成熟开源项目靠齐：
- `model_gateway` 负责模型路由、Provider 和预算；
- `tools` 负责工具 intake、readiness、resume gate、checkpoint 和 proposal；
- `agent_execution` 负责把一次 Agent plan 的阶段事实汇总成可运营、可审计、可继续推进的闭环视图。

当前包只提供请求级闭环报告，不执行工具、不写 outbox、不调用 Java，也不读取敏感 payload。后续如果
要落地真正的 OpenClaw/LangGraph-style durable runner，可以继续在本包下新增 runner、state machine
和恢复调度器，而不是回到根目录继续堆散文件。
"""

from datasmart_ai_runtime.services.agent_execution.agent_execution_closure import (
    AgentExecutionClosureReport,
    AgentExecutionClosureService,
)

__all__ = (
    "AgentExecutionClosureReport",
    "AgentExecutionClosureService",
)
