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
    agent_graph/          # LangGraph 节点/边/状态标准、图合同审计与跨请求恢复语义
    agent/                # Agent 编排、二轮推理、workspace 上下文
    agent_execution/      # Durable Loop checkpoint、执行闭环与控制面 handoff
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
  正式记忆 store、materializer 和 receipt store。新增 `langgraph_memory_retrieval_workflow.py` 与
  `langgraph_memory_retrieval_models.py` 后，`retrieve_memory` 已从编排器内部步骤推进为可观察 LangGraph
  节点；新增 `langgraph_memory_retrieval_metrics.py` 后，该观察节点也能通过 `/agent/metrics` 输出低基数
  Prometheus 指标。workflow 文件只负责编排节点流转，models 文件承载低敏 state/diagnostics，metrics
  文件承载指标转换，避免 memory workflow 超过单文件 500 行约束。
- `services/runtime_events/` 已作为第二批能力包建立，承载事件事实存储、订阅会话、ack/checkpoint、
  outbox/live push、replay source、publisher、transport、WebSocket frame、visibility 和 authorization。
  这些能力共同支撑智能网关时间线、断线恢复、前端实时事件流和后续 Agent 执行审计。
- `services/model_gateway/` 已作为第三批能力包建立，承载模型路由、provider、缓存、预算、模型原生
  tool-call schema/planning/aggregation/feedback、上下文过滤和 OpenAI-compatible provider 适配。
- `services/multi_agent/` 已作为第四批能力包建立，承载产品 Agent 名册、LangGraph 多智能体执行前计划、
  低敏工作项、协作边和执行边界规则。`product_agent_catalog.py` 现在同时维护运行时 Agent 交付优先级：
  必做角色、控制范围角色和轻量化角色分层明确，避免把 PERMISSION_AGENT/MEMORY_AGENT 等治理角色误判为
  合规脱敏/反思优化等完整产品专项 Agent。该包当前只生成控制面合同，不执行工具、不写 outbox、不创建审批。
- `services/multi_agent/knowledge_agent_capability.py` 已新增为 `KNOWLEDGE_AGENT` 的 RAG 能力合同模块：
  - 它只声明 `knowledge.rag.query` 的图名、节点、证据门控、checkpoint、可调用角色和副作用边界；
  - 它不执行 RAG、不读取用户问题、不保存文档正文、不调用模型；
  - 这样 `controlled_turn_runner.py` 可以继续专注于 LangGraph turn 状态机，而 RAG 能力细节保持在独立文件中。
- `services/agent_graph/` 已作为跨领域 LangGraph 运行标准包建立：
  - `runtime_contracts.py` 统一定义可复用/可观测/可替换节点、固定/条件/循环/终止边、低敏可恢复状态
    以及图合同自动审计；
  - `multi_agent_turn_runner_contract.py` 声明受控多 Agent Turn Runner 的状态机拓扑、条件路由和
    跨请求 Durable Loop 恢复边；
  - 该包只定义图运行标准，不承载 datasource、quality、memory 或 tool 的具体业务逻辑。具体 workflow
    仍保留在 `services/multi_agent/`、`services/memory/`、`services/tools/` 等能力域，避免出现新的
    “所有 LangGraph 文件都塞进一个目录”的技术型平铺问题；
  - Turn Runner 已从固定流水线升级为依据 `runnerRoute` 分支的条件状态机，并通过
    `requestId/runId/sessionId`、`runnerStatus`、`loopDecision` 和 node trace 描述恢复现场。
- `services/agent_execution/` 承载 Agent 执行闭环与 Durable Loop 状态：
  - `durable_agent_loop.py` 定义阶段、恢复动作、checkpoint 和 store 协议；
  - `durable_agent_loop_redis.py` 提供跨实例、跨重启的 Redis 低敏 checkpoint 实现；
  - `durable_agent_loop_components.py` 集中环境变量、延迟 Redis client 装配和凭据脱敏诊断；
  - 本地默认使用 in-memory，应用 Compose 默认使用 Redis DB 2，避免“Durable”能力在容器重启后失效。
- `services/tools/` 已开始承载工具治理闭口能力，包括 ToolPlan intake、readiness、readiness graph、
  checkpoint/resume-preview 客户端、command proposal、worker receipt 合同以及新增的
  `LangGraphExecutionGateWorkflow`、`langgraph_execution_gate_contract.py` 和 `agent_execution_gate_recorded`
  runtime event adapter。`langgraph_execution_gate_contract.py` 专门维护 Java checkpoint locator、fact bundle、
  resume gate graph、worker receipt 写入/查询字段对齐表，避免主 workflow 文件超过 500 行。该 workflow
  只做执行前条件路由，不执行工具、不写 outbox、不修改 checkpoint，后续工具治理新增能力应优先继续放入该包。
- `services/__init__.py` 继续保留对外聚合导出，但 memory、runtime event、model gateway 与 multi-agent 相关导出已经分别依赖
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
2. `services/agent/`：Agent 编排、二轮推理和主控 loop 应与 Java 控制面、长期记忆和模型网关解耦；
   新增执行图必须复用 `services/agent_graph/` 的节点、边、状态合同，而不是再定义一套隐式约定。
3. `services/integrations/`：Java 控制面客户端、外部 replay source、permission-admin 客户端和未来 MCP/HTTP 连接器应逐步收口。
4. `api/`：下一阶段不建议继续只做目录移动；若继续整理，应补 `routes/`、`schemas/`、`dependencies/` 等更细层次，并绑定真实 API 能力。
5. 完成 API 分层后，应暂停纯目录治理，回到智能网关统一 intake、长期记忆持久化、Agent tool runtime 或工具能力市场等产品能力实现。
## 2026-07-01 目录治理补充：Metrics route and execution gate metrics

- `api/metrics.py` 已作为 Prometheus 指标路由注册模块建立，避免继续把 `/agent/metrics` 的响应拼装逻辑堆在 `api/app.py`。
- `api/agent/plan_response_events.py` 已承接 plan response 中的 runtime event 追加、发布和 execution gate 指标旁路，避免 `plan_response.py` 再次膨胀。
- `services/tools/langgraph_execution_gate_metrics.py` 已归入 `services/tools/`，因为它消费的是工具执行前 LangGraph gate 事件，属于工具治理链路的可观测闭环。
- 后续如果继续新增 Agent Runtime 指标，应优先判断指标属于 memory、model_gateway、tools、runtime_events 还是 multi_agent，而不是直接塞回 `api/app.py`。
## 2026-07-01 目录治理补充：Multi-agent handoff contracts

- `services/multi_agent/handoff_contracts.py` 已新增为多 Agent handoff 合同生成规则模块。
- `services/multi_agent/knowledge_agent_capability.py` 已新增为 KNOWLEDGE_AGENT RAG 能力合同模块。
- 该模块放在 `multi_agent/` 而不是 `tools/` 或 `runtime_events/` 下，是因为它描述的是 Agent 之间以及 Agent 到 Java 控制面的交接合同，不是工具执行器，也不是事件存储实现。
- 这两个模块都放在 `multi_agent/` 而不是 `tools/` 或 `runtime_events/` 下，是因为它们描述的是 Agent 协作与宿主控制面合同，不是工具执行器，也不是事件存储实现。
- `langgraph_execution_plan.py` 继续只负责编排 LangGraph 节点流转；handoff 与 knowledge capability 规则拆到独立文件后，主 workflow 更容易保持可读。
- 后续如果继续补 handoff 指标、Java projection adapter、RAG turn fact 或 durable checkpoint 对齐，应优先在 `multi_agent/` 下继续分文件扩展，避免把多 Agent 逻辑重新堆回 `services/` 根目录。
