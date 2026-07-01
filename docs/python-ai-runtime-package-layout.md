# Python AI Runtime 目录层级治理规范

本文档记录 Python AI Runtime 后续目录整理的目标形态。它不是一次性重构清单，而是持续演进约束：每次新增
AI Agent、模型网关、长期记忆、工具治理、事件流或外部集成能力时，都应优先放到对应能力包中，避免继续把
几十个文件平铺到 `domain/` 或 `services/`。

## 设计参考

成熟开源项目通常不会只按“domain/services”两层堆文件，而会按能力域拆包：

- FastAPI / Starlette 类项目会把 routing、dependency、schemas、middleware 和 app bootstrap 分开；
- LangChain / LangGraph 类项目会按 memory、tools、runnables、checkpoints、stores 等能力组织；
- Ray / vLLM 类项目会区分 engine、scheduler、worker、runtime、entrypoints 和 observability；
- Kubernetes、Airflow 这类平台型项目会把 API、controller、scheduler、executor、store、provider 拆成清晰边界。

DataSmart 的 Python Runtime 也应采用“能力域优先、层次职责清晰、渐进迁移可验证”的方式，而不是追求一次性
改出漂亮目录后引入大量回归风险。

## 目标层级

```text
datasmart_ai_runtime/
  api/                    # FastAPI route、请求/响应适配、内部认证 dependency、错误映射
  domain/                 # 领域契约、枚举、纯数据结构，禁止依赖具体基础设施
  services/
    agent/                # Agent 编排、二轮推理、loop 控制、workspace 上下文
    memory/               # 长期记忆规划、检索、写入治理、正式落成、store 抽象
    model_gateway/        # 模型路由、provider、预算、缓存、结果过滤、OpenAI-compatible 适配
    multi_agent/          # 产品 Agent 名册、LangGraph 多 Agent 协作图、执行前工作项、handoff 边界
    runtime_events/       # 事件 store、session、checkpoint、outbox、WebSocket、publisher
    skills/               # Skill registry、准入策略、远程 skill 控制面客户端
    tools/                # 工具规划、schema、参数校验、工具反馈、工具调用聚合
    integrations/         # Java 控制面 HTTP/Kafka/gRPC 客户端、外部系统适配器
  config/                 # 环境变量解析、默认注册表、运行时 profile
```

## 当前已开始落地

- `api/` 已作为 HTTP/API 层能力包建立：
  - `api/app.py` 保留 FastAPI 应用装配、依赖启动和路由注册；
  - `api/agent/` 承载 Agent planning、A2A/MCP intake 预览、Skill 准入和 orchestrator 工厂；
  - `api/gateway/` 承载 gateway 签名、nonce、安全诊断、可信上下文和智能网关治理摘要；
  - `api/events/` 承载 runtime event replay/control/WebSocket payload 适配；
  - `api/memory/` 承载长期记忆候选治理、物化管理端、分页和 runtime 装配；
  - `api/model_gateway/` 承载模型网关低敏 HTTP 响应适配。
- `datasmart_ai_runtime.api` 现在是懒加载兼容入口：
  - 历史 `from datasmart_ai_runtime.api import create_app` 仍然可用；
  - 新代码应直接导入 `datasmart_ai_runtime.api.agent.routes`、`datasmart_ai_runtime.api.memory.write` 等能力包；
  - 不应再在运行时根包新增 `api_xxx.py` 平铺文件。
- `services/memory/` 已作为第一批能力包建立，承载长期记忆规划、检索、写入候选、审批治理、SQL 候选仓储、
  正式记忆 store、materializer 和 receipt store。
- `services/runtime_events/` 已作为第二批能力包建立，承载事件事实存储、订阅会话、ack/checkpoint、
  outbox/live push、replay source、publisher、transport、WebSocket frame、visibility 和 authorization。
  这些能力共同支撑智能网关时间线、断线恢复、前端实时事件流和后续 Agent 执行审计。
- `services/model_gateway/` 已作为第三批能力包建立，承载模型路由、provider、缓存、预算、模型原生
  tool-call schema/planning/aggregation/feedback、上下文过滤和 OpenAI-compatible provider 适配。
- `services/multi_agent/` 已作为第四批能力包建立，承载产品 Agent 名册、LangGraph 多智能体执行前计划、
  低敏工作项、协作边和执行边界规则。该包当前只生成控制面合同，不执行工具、不写 outbox、不创建审批。
- `services/tools/` 已开始承载工具治理闭口能力，包括 ToolPlan intake、readiness、readiness graph、
  checkpoint/resume-preview 客户端、command proposal、worker receipt 合同以及新增的
  `LangGraphExecutionGateWorkflow`、`langgraph_execution_gate_contract.py` 和 `agent_execution_gate_recorded`
  runtime event adapter。`langgraph_execution_gate_contract.py` 专门维护 Java checkpoint locator、fact bundle、
  resume gate graph、worker receipt 写入/查询字段对齐表，避免主 workflow 文件超过 500 行。该 workflow
  只做执行前条件路由，不执行工具、不写 outbox、不修改 checkpoint，后续工具治理新增能力应优先继续放入该包。
- `services/__init__.py` 继续保留对外聚合导出，但 memory、runtime event 与 model gateway 相关导出已经分别依赖
  `services.memory`、`services.runtime_events`、`services.model_gateway` 与 `services.multi_agent`，避免顶层服务包直接知道子包内部每个文件的位置。
- 其他能力域仍处于过渡状态：skills、agent orchestration 还在
  `services/` 平铺目录中，后续应按测试覆盖逐批迁移。

## 迁移原则

- 每批迁移优先选择一个能力域，不要跨多个领域同时大搬家。
- 每批迁移必须更新 import、README、路线图和 `current-repo-state.md`，并跑定向测试与全量测试。
- 迁移期间可以保留聚合导出，减少业务代码对内部路径的依赖；但不要长期保留大量兼容空壳文件，否则旧目录仍会显得杂乱。
- 新增能力默认进入目标能力包；除非是全局 bootstrap 或公共领域契约，不应再直接塞进顶层 `services/`。
- 文件命名先保持稳定，目录层级优先；后续再逐步把 `memory_write_*` 这种历史前缀整理为子包内更短名称。

## 后续推荐顺序

1. `services/tools/` 与 `services/skills/`：工具市场、Skill 准入和 MCP-style descriptor 后续会继续增长，应结合功能演进继续分包。
2. `services/agent/`：Agent 编排、二轮推理、loop control、执行图条件节点应与 Java 控制面、长期记忆和模型网关解耦。
3. `services/integrations/`：Java 控制面客户端、外部 replay source、permission-admin 客户端和未来 MCP/HTTP 连接器应逐步收口。
4. `api/`：下一阶段不建议继续只做目录移动；若继续整理，应补 `routes/`、`schemas/`、`dependencies/` 等更细层次，并绑定真实 API 能力。
5. 完成 API 分层后，应暂停纯目录治理，回到智能网关统一 intake、长期记忆持久化、Agent tool runtime 或工具能力市场等产品能力实现。
