"""多智能体协作相关服务包。

这个包承接 DataSmart Python Runtime 中与 OpenClaw / LangGraph 风格多智能体协作有关的
稳定领域能力。之所以把这些能力从 `services` 根目录继续拆出一层，是为了避免 LangGraph、
产品 Agent 名册、执行计划、handoff 策略等文件继续平铺堆放，后续读代码时能够快速判断：

- `product_agent_catalog`：平台规划中的产品级 Agent 名册与运行角色映射；
- `langgraph_execution_plan`：把会话调度事实转换为执行前多 Agent 工作项和协作边；
- 后续如果补真实 handoff、checkpoint 或多 Agent runtime，也应优先放在本包下。

当前包内模块仍然只处理低敏控制面事实，不执行工具、不调用模型、不写 outbox。
"""
