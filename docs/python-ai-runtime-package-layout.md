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
    runtime_events/       # 事件 store、session、checkpoint、outbox、WebSocket、publisher
    skills/               # Skill registry、准入策略、远程 skill 控制面客户端
    tools/                # 工具规划、schema、参数校验、工具反馈、工具调用聚合
    integrations/         # Java 控制面 HTTP/Kafka/gRPC 客户端、外部系统适配器
  config/                 # 环境变量解析、默认注册表、运行时 profile
```

## 当前已开始落地

- `services/memory/` 已作为第一批能力包建立，承载长期记忆规划、检索、写入候选、审批治理、SQL 候选仓储、
  正式记忆 store、materializer 和 receipt store。
- `services/runtime_events/` 已作为第二批能力包建立，承载事件事实存储、订阅会话、ack/checkpoint、
  outbox/live push、replay source、publisher、transport、WebSocket frame、visibility 和 authorization。
  这些能力共同支撑智能网关时间线、断线恢复、前端实时事件流和后续 Agent 执行审计。
- `services/model_gateway/` 已作为第三批能力包建立，承载模型路由、provider、缓存、预算、模型原生
  tool-call schema/planning/aggregation/feedback、上下文过滤和 OpenAI-compatible provider 适配。
- `services/__init__.py` 继续保留对外聚合导出，但 memory、runtime event 与 model gateway 相关导出已经分别依赖
  `services.memory`、`services.runtime_events` 与 `services.model_gateway`，避免顶层服务包直接知道子包内部每个文件的位置。
- 其他能力域仍处于过渡状态：tools、skills、agent orchestration 还在
  `services/` 平铺目录中，后续应按测试覆盖逐批迁移。

## 迁移原则

- 每批迁移优先选择一个能力域，不要跨多个领域同时大搬家。
- 每批迁移必须更新 import、README、路线图和 `current-repo-state.md`，并跑定向测试与全量测试。
- 迁移期间可以保留聚合导出，减少业务代码对内部路径的依赖；但不要长期保留大量兼容空壳文件，否则旧目录仍会显得杂乱。
- 新增能力默认进入目标能力包；除非是全局 bootstrap 或公共领域契约，不应再直接塞进顶层 `services/`。
- 文件命名先保持稳定，目录层级优先；后续再逐步把 `memory_write_*` 这种历史前缀整理为子包内更短名称。

## 后续推荐顺序

1. `services/tools/` 与 `services/skills/`：工具市场、Skill 准入和 MCP-style descriptor 后续会继续增长，应尽早分包。
2. `api/`：当前仍有多个 `api_*.py` 平铺在包根，后续应迁移为 `api/routes/`、`api/schemas/`、`api/dependencies/`。
3. `services/agent/`：Agent 编排和 loop control 应与 Java 控制面集成、长期记忆和模型网关解耦。
4. `services/integrations/`：Java 控制面客户端、外部 replay source、permission-admin 客户端和未来 MCP/HTTP 连接器应逐步收口。
5. 完成一批 tools/skills 或 API 分层后，应暂停纯目录治理，回到智能网关认证、长期记忆持久化或工具能力市场等产品能力实现。
