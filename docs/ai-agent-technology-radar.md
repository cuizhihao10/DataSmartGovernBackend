# DataSmart Govern AI Agent 技术雷达

## 2026-06-03 落地补充：memory materialization needs traceable recovery events

- 本阶段把长期记忆物化从“管理员可以恢复”继续推进到“恢复与后台批次事实可以进入 runtime event 时间线”。新增的 `memory_materialization_run_completed` 和 `memory_materialization_requeue_recorded` 分别覆盖 Runner 批次汇总和管理员补偿重排。
- 这对应当前 Agent 平台的 durable execution / tracing / human-in-the-loop 趋势：LangGraph persistence 强调 checkpoint 支撑 human-in-the-loop、memory、time travel 和 fault-tolerant execution；OpenAI Agents SDK tracing 强调 agent run 中的步骤、工具调用和自定义事件应可追踪；MCP Tools 规范也强调工具调用需要清晰的可见性和受控交互。
- DataSmart 当前选择先进入 Runtime Event，而不是直接上常驻 worker 或 Prometheus，是为了先稳定事实契约：哪些字段低敏、哪些计数可聚合、哪些事件属于审计、哪些失败只影响旁路投递而不回滚补偿主流程。
- Runner 事件聚合 `DLQ/retry cooldown/active lease/fencing finalize error`，避免未来 Prometheus 直接使用 candidateId、tenantId、traceId 等高基数字段；管理员重排事件只记录 operatorId、状态变化和 namespace，不记录候选正文、正式记忆正文、SQL、工具输出或 lease token。
- 下一步趋势落地建议：把这些事件进一步映射为低基数 Prometheus 指标，并引入事务 outbox 或审计 fail-closed 选项；随后再启动受控常驻 worker、批量补偿、租户级恢复 SLA 和 Chroma/Neo4j 二级索引同步。
- 参考资料：LangGraph persistence：`https://docs.langchain.com/oss/python/langgraph/persistence`；OpenAI Agents SDK tracing：`https://openai.github.io/openai-agents-python/tracing/`；MCP Tools specification：`https://modelcontextprotocol.io/specification/2025-06-18/server/tools`。

## 2026-06-03 落地补充：long-term memory needs operator recovery loops

- 本阶段把长期记忆物化从“失败退避 + DLQ”推进到“管理员可 dry-run、可查询、可重排”的恢复闭环。
- 这对应当前 Codex、Claude Code 类 Agent 工程中的 durable action recovery 思路：工具调用、记忆写入、索引构建和后台副作用不能只依赖一次进程内执行，必须具备失败证据、可解释状态、人工补偿和后续自动恢复窗口。
- DataSmart 当前选择单候选补偿而不是立即做批量重放，是为了先稳定安全语义：只允许 failed/dead_letter，保留 attemptCount，不绕过审批，不直接写正式记忆，不暴露 lease token 或候选正文。
- 这一步也把“管理员操作”纳入 Agent 产品主线：成熟 Agent 平台不仅要会调用工具和记忆，还要能被运维人员安全地恢复、审计和解释。
- 后续趋势落地建议：把补偿动作接入 runtime event / Prometheus / 审计表，再启动受控常驻 worker；批量补偿、错误类型聚合、租户级恢复 SLA 和 Chroma/Neo4j 二级索引 worker 应建立在这些可观测事实之上。

## 2026-06-02 落地补充：model gateway should own provider and tool-call governance

- 本阶段把模型网关能力迁入 `services/model_gateway/`，让 provider、route registry、budget guard、tool-call schema/planner/aggregator、feedback、result context filter 和 OpenAI-compatible provider 形成独立包边界。
- 这对应当前 Agent 工程趋势：模型调用不应只是 `call_llm(prompt)`，而应具备 provider abstraction、routing policy、tool-call governance、context safety、cache planning、budget control 和未来 provider health/tenant package 策略。
- DataSmart 当前仍采用渐进迁移，不改类名、不重命名测试，只先把模型网关能力域从大 `services` 目录中拆出来，降低结构治理对功能稳定性的影响。
- 后续趋势落地建议优先把智能网关服务间认证、长期记忆持久化和工具能力市场继续落到产品主线；如果继续结构治理，则迁移 `tools/skills` 与 `api/routes`，不要让目录整理变成新的无限循环。

## 2026-06-02 落地补充：runtime event pipelines need their own package boundary

- 本阶段把 runtime event 能力迁入 `services/runtime_events/`，让事件 store、session、ack/checkpoint、outbox、live push、publisher、replay source、WebSocket frame、visibility 和 authorization 形成独立包边界。
- 这对应前沿 Agent 产品的核心工程事实：用户看到的“正在思考、调用工具、等待确认、执行失败、恢复连接后继续显示历史事件”不是日志附属品，而是一条独立的 runtime event pipeline。
- DataSmart 当前选择渐进迁移，不改类名、不重命名测试，只先把能力域从大 `services` 目录中拆出来。这样既改善可读性，也避免结构治理破坏已经稳定的事件流测试。
- 后续趋势落地建议把智能网关服务间认证、事件审计导出、分布式 WebSocket 连接管理和低敏 display policy 都继续放在这个能力域下演进，而不是重新散落到 API 或 Agent orchestrator 文件中。

## 2026-06-02 落地补充：AI Runtime package layout should follow capability domains

- 本阶段把 Python Runtime 的长期记忆相关文件迁入 `services/memory/`，开始从“按技术层 services 平铺”转向“按 Agent 能力域分包”。
- 这对应成熟 Agent 工程项目的常见趋势：memory、tools、runtime events、model gateway、skills、integrations 应该各自形成可理解、可测试、可替换的包边界，而不是在一个 services 目录中靠文件名前缀维持秩序。
- DataSmart 当前采用渐进迁移：先移动 memory 能力域并保持文件名稳定，避免目录治理变成高风险大重构；后续再迁移 runtime events、model gateway、tools、skills 和 API routes。
- 对 Codex/Claude Code 类 Agent 目标而言，这不是“好看一点”的整理，而是未来支持长期记忆、MCP 工具、模型 provider、事件回放、服务间认证、策略审计和多租户运营时必须提前建立的工程秩序。

## 2026-06-02 落地补充：Agent API surface needs modular route ownership

- 本阶段没有盲目继续叠加长期记忆或工具执行新功能，而是先治理 Python Runtime 的 API surface，避免 Agent 规划、事件回放、WebSocket 和诊断能力全部堆在一个 bootstrap 文件中。
- 这对应 Codex、Claude Code 类 Agent 平台的工程趋势：随着工具调用、长期记忆、会话事件、权限策略、人工确认和模型网关不断增长，API 入口必须按能力面拆分，否则每次扩展都会造成局部文件膨胀和隐性耦合。
- DataSmart 当前新增 `api_agent_routes.py` 作为 Agent 规划与 runtime event 路由注册模块，`api.py` 继续作为可选 FastAPI app 装配入口。这个边界为后续智能网关服务间认证、WebSocket 会话治理、事件审计导出和长期记忆上下文注入留下了更干净的挂点。
- 后续趋势落地建议：继续把 memory-write API、diagnostics API、model-gateway API 拆成独立 route module，并引入统一的内部认证/审计 dependency，避免在每个 handler 里重复实现权限和观测逻辑。

## 2026-06-02 落地补充：long-term memory needs governed materialization

- 本阶段从 Java worker 收口切换到长期记忆主线，把已存在的“候选生成、审批、SQL 候选仓储、分页 API”继续推进为“APPROVED 候选可以幂等落成正式记忆，并被后续请求召回”。
- 这对应当前 Agent memory 的重要工程趋势：长期记忆不是无差别聊天历史，也不是工具成功后直接写向量库；它需要 namespace、范围隔离、写入治理、幂等、TTL、检索边界和遗忘策略。
- DataSmart 当前只把低敏 `contentSummary` 写入正式记忆正文。原始工具输出、SQL、样本和 outputRef 不会直接进入模型上下文；敏感候选即使审批通过，也会继续等待脱敏流水线。
- 正式 store 与 retriever 都保留 tenant/project/session 隔离。后续接 Chroma、Neo4j 或 MySQL 时，相关性搜索可以下沉，但范围过滤不能删除。
- 当前 materializer 支持 PROJECT/TENANT，暂缓 SESSION 与 GLOBAL：SESSION 需要稳定 session 字段和短期记忆 TTL 策略，GLOBAL 需要更严格的组织级审批、prompt-injection 防护和只读发布流程。
- 参考资料：LangGraph Memory：`https://docs.langchain.com/oss/python/langgraph/memory`；LangGraph Persistence：`https://docs.langchain.com/oss/python/langgraph/persistence`。
- 下一步趋势落地建议在两条路线中择一：一是补正式记忆持久化 receipt、workspace namespace、后台 outbox worker 和遗忘任务；二是切入智能网关会话编排，把正式记忆召回接入上下文预算和模型 provider 路由。

## 2026-06-02 落地补充：guardrail metrics need bounded cardinality

- 本阶段把 worker-side guardrail 从“时间线可解释”推进到“可聚合告警”：claim 前容量不足、claim 后执行复核阻断、confirmation 或 permission-admin 暂不可用退避，都进入 Micrometer 计数指标。
- 这对应生产级 Agent 平台的 observability / admission control / action guardrail 思路：系统不仅要能解释单个工具为什么没有执行，还要能判断某类阻断是否在实例范围内持续升高。
- DataSmart 刻意没有把 tenantId、projectId、sessionId、taskId、traceId 和原始 toolCode 直接用作指标标签。Agent 会话和工具任务增长很快，直接使用业务主键会造成 Prometheus 高基数时序，反过来损害监控平台可靠性。
- 当前 outcome、scope、decision、reasonCode 都采用有限白名单；未知动态值归并为 `OTHER`。单条任务定位继续走 runtime event、任务审计和结构化日志。
- 后续若要建设租户运营看板，应使用受控租户分组、工具类别、Top-N 聚合、日志分析或 exemplar 关联 trace，而不是无限扩展基础指标标签。
- 这一批完成后，不再继续围绕 Java worker 做无止境局部优化。下一轮趋势落地应进入更大的 AI Agent 能力面，例如长期记忆写入闭环、智能网关会话调度、Skill/工具注册市场或可替换模型 provider 网关。

## 2026-06-01 落地补充：guardrail events must be explainable

- 本阶段把 worker-side guardrail 从“能阻断”继续推进到“能解释”：权限拒绝、策略漂移、确认不可用、权限中心不可用等不再只是一段日志或泛化错误码，而是进入 Agent runtime event display。
- 这对应 Codex、Claude Code 类 Agent 工具链路里的 action trace / failure taxonomy / explainable guardrail 思路：用户和运维需要知道工具为什么没有执行，是被安全策略阻断、等待审批、控制面不可用，还是下游业务失败。
- DataSmart 当前采用低敏摘要：只写错误码、状态、工具编码、目标服务和建议动作，不写 prompt、SQL、token、完整工具参数或样本数据。
- 下一步趋势落地应把同类 guardrail 事件接入指标维度：按 tenant、project、toolCode、reasonCode 聚合阻断率、退避次数、平均恢复时间和高频误配置。

## 2026-06-01 落地补充：local admission control before tool execution

- 本阶段把 Agent worker-side guardrail 从“确认与权限复核”继续推进到“容量入场保护”：worker 在 claim 任务前先检查本地并发和调度节流。
- 这对应前沿 Agent 工具平台中的 admission control / rate limiting / backpressure 思路：工具执行不仅要被允许，还必须在系统容量允许时执行。
- DataSmart 当前选择先落本地 JVM 级保护，是为了快速防止手动 dispatch、后台 scheduler 或未来多线程 worker 在单实例内过度并发；这不是最终形态。
- 后续趋势落地应演进到多维度配额：tenant、project、workspace、toolCode、targetService、queue backlog、downstream health，并与 runtime event stream、指标告警和管理员控制台联动。

## 2026-06-01 落地补充：delegated authorization re-check before side effects

- 本阶段把 Agent worker-side guardrail 继续推进到 permission-admin 实时授权复核：即使 command 已入箱、任务已创建、confirmation 已回查通过，worker 在真实工具副作用前仍要重新 evaluate。
- 这对应企业 Agent 平台里的 delegated authorization / capability lease renewal 思路：服务账号不是永久通行证，机器身份代表用户执行工具时，需要在执行瞬间重新确认策略仍然有效。
- DataSmart 当前把入箱动作 `ENQUEUE_SELECTED_ASYNC_TOOL` 与执行动作 `EXECUTE_CONFIRMED_ASYNC_TOOL` 拆开，避免“能确认节点入箱”被误解释为“能执行所有后续副作用”。
- 下一步趋势落地应进入 quota/rate/concurrency guardrail：Agent 工具执行不仅要被授权，还要受租户配额、项目配额、工具级限流、下游容量与队列积压保护约束。

## 2026-06-01 落地补充：worker-side confirmation revalidation

- 本阶段把 worker-side guardrail 从本地字段校验推进到跨服务确认回查：task-management worker 在调用真实工具适配器前，会按 `confirmationId` 查询 agent-runtime 的 DAG selected-node confirmation。
- 这对应 Codex、Claude Code 等 Agent 工具体系中越来越重要的 action confirmation / capability lease / durable approval evidence 思路：确认不是一行日志，而是 worker 执行前可以重新读取和核对的行动凭证。
- 当前已核对 confirmation 所属的 session/run、选中 auditId、关联 commandId、策略版本和委托证据摘要；如果控制面暂时不可用，任务退避重试，而不是执行副作用或永久失败。
- 这仍不是完整智能网关执行治理。下一步要把 permission-admin 最新策略 evaluate、租户/项目配额、工具级限流、payloadReference 版本和运行时指标纳入同一条 preflight 链路。

## 2026-06-01 落地补充：worker-side guardrail 先于真实工具副作用

- 本阶段继续把“工具执行不是函数直调，而是受控行动链路”的 Agent 产品原则落到 task-management worker。
- worker 当前在调用真实工具适配器前新增本地二次复核：任务必须已被认领为 RUNNING，Agent 审计状态必须仍可执行或可补偿，payloadReference 必须仍是受控 plan-arguments，toolCode 必须存在白名单适配器，confirmation/policy/delegation 证据必须保持低敏短文本。
- 这对应前沿 Agent 平台常见的 worker-side guardrail / execution preflight / capability lease 思路：即使计划已确认、命令已入箱、任务已创建，真正副作用发生前仍要做最后一跳安全判断。
- 后续应把本地 pre-check 升级为跨服务 pre-check：远端反查 confirmation、permission-admin 最新策略、租户/项目配额、工具级限流、payloadReference 版本和幂等键。

## 2026-06-01 落地补充：执行证据进入任务消费预检链路

- 本阶段继续把类 Codex / Claude Code Agent 的“工具执行必须可解释、可复核、可审计”原则落到 Java 控制面，而不是贸然扩大自动执行范围。
- `agent-runtime` 已把 selected-node confirmation 的 `confirmationId`、权限策略版本和服务账号委托摘要写入异步 command payload；`task-management` 消费命令时会先做低敏证据预检，再把安全摘要写入任务参数。
- 这对应前沿 Agent 工具体系中的 policy snapshot、approval evidence、capability lease 和 worker-side guardrail 思路：模型提出计划，控制面确认计划，任务中心保留证据，真正 worker 执行前还要再次复核。
- 后续雷达落地应优先推进“worker 执行前二次复核”的完整闭环：回查 agent-runtime confirmation、permission-admin 最新策略、工具 schema、payloadReference、租户配额、任务状态和幂等键，而不是让任务创建成功就等价于工具可执行。

## 2026-06-01 落地补充：工具执行确认需要绑定授权证据版本

- 本阶段没有新增外部趋势扫描，而是把前期“类 Codex/Claude Code Agent 必须可审计地执行工具”的路线继续落到 Java 控制面。
- `agent-runtime` selected-node outbox enqueue 现在要求调用方带回 dry-run 时看到的 `policyVersion`，服务端确认前重新 dry-run 并对比当前 permission-admin 策略版本。
- 这对应前沿 Agent 工具执行治理中的一个核心原则：模型或人类确认的是“某个时间点的行动预案”，真实副作用发生前必须重新绑定权限、策略和审计证据。
- 后续技术雷达应继续关注 MCP/A2A/tool-use 生态里的 tool approval、capability lease、policy snapshot、signed tool plan 等方向，并优先转化为 DataSmart 的租户安全、确认记录、worker pre-check 和可回放事件能力。

## 2026-06-01 落地补充：工具确认事实需要可恢复持久化

- 本阶段把 selected-node confirmation 增加 MySQL/JDBC 仓储，继续贴近前沿 Agent 产品中的 durable action、checkpoint、human-in-the-loop 和 tool trace 思路。
- 对 Codex/Claude Code 类 Agent 来说，“用户确认过”不能只存在于一次 HTTP 响应或进程内存里；它应能在服务重启、网关重试、多实例切换和审计回放时被恢复。
- DataSmart 当前把确认事实落成独立 evidence store，而不是直接扩大自动执行范围，这符合企业工具执行治理的路线：先可追踪、可恢复、可证明，再逐步提高自动化。
- 后续应继续把 confirmation evidence 接入 runtime event display、审计查询 API、worker pre-check 和权限中心策略版本复核，形成端到端行动证据链。

## 2026-05-25 智能网关 WebSocket 路由校准

本次在继续推进 Agent 实时事件能力时，先按项目 skill 要求校准了 Spring Cloud Gateway 的 WebSocket 转发方式。
结论是：网关侧应优先使用 Spring Cloud Gateway 原生 WebSocket Routing Filter，而不是手写 Java WebSocket 代理。
原因是 Gateway 已经能根据 `ws://`、`wss://` 或 `lb:ws://` URI 识别 WebSocket Upgrade 并转发，下游 Python Runtime 只需要暴露
`/agent/events/ws` 协议入口即可。

已转化为 DataSmart 能力：
- Java gateway 新增 `/api/agent/events/ws` 对外统一入口，默认转发到 `ws://localhost:8090/agent/events/ws`。
- 该路由放在通配 `/api/agent/**` 之前，避免被 Java `agent-runtime` 普通 HTTP 控制面误接收。
- 授权语义固定为 `AI_RUNTIME + SUBSCRIBE`，不把 WebSocket 握手误解释成普通 `GET + VIEW`。
- Python AI Runtime 默认端口建议使用 `8090`，避免与本地 Chroma 的 `8000` 冲突。

后续路线：
- 短期补连接配额、租户/项目级订阅限制、心跳超时清理、关闭审计。
- 中期把会话状态和 live outbox 从内存升级为 Redis/Kafka 支撑，解决多实例和断线续传窗口问题。
- 长期对齐 A2A streaming/push notification 思路，把 Agent 运行进度、工具审批、任务状态变化统一成可审计事件流。

> 本文档用于记录 DataSmart Govern 后续在 AI Agent、工具调用、Skill、记忆、模型网关、推理缓存和多智能体协作方向上的持续跟踪结果。
> 它不是一次性的调研报告，而是项目长期演进的“趋势雷达 + 落地路线”。

## 1. 维护原则

- **持续跟踪，而不是一次性选型。** AI Agent 生态变化非常快，后续涉及 `agent-runtime`、`python-ai-runtime`、智能网关、模型 provider、工具调用、记忆、推理服务时，都应先做一次最新趋势校准。
- **优先参考一手来源。** 包括官方文档、协议规范、GitHub 开源项目、Hugging Face Papers、arXiv、主流框架文档；二手文章只作为线索，不作为最终技术依据。
- **不盲目追热点。** 新技术必须映射到本项目的商业目标：数据治理、权限审计、长任务可恢复、工具调用可控、模型可替换、可观测、可部署。
- **所有趋势必须转成架构能力。** 例如 MCP 不只是“接入协议”，要转成工具注册、权限审批、审计回放和服务隔离；KV Cache 不只是推理优化，要转成模型网关的成本与延迟治理。
- **每次重要落地都更新本文档与 `current-repo-state.md`。** 这样长期任务不会只依赖聊天历史。

## 2. 2026-05-23 趋势扫描摘要

本次扫描结合了官方文档、GitHub 开源项目、Hugging Face Papers 和本项目当前实现状态。

### 2.1 工具调用协议正在走向标准化：MCP

**趋势判断：**

MCP 已经从“某个客户端的插件机制”演进为 AI 应用连接外部系统、工具、数据源和工作流的开放标准。官方文档把 MCP 类比为 AI 应用的 USB-C 接口，强调用统一协议连接数据库、文件、搜索、工具和工作流。

**对 DataSmart 的意义：**

- `agent-runtime` 不应该只维护一套内部工具调用 DTO，后续应设计成“内部工具注册表 + MCP 兼容适配层”。
- 数据源元数据读取、质量规则生成、同步任务创建、权限查询、审计导出都可以逐步变成受控工具。
- 工具调用必须绑定租户、项目、角色、审批、风险等级和审计流水，不能只做“函数名 + 参数”。

**建议落地：**

- 近期：扩展 `AgentToolRegistryService` 的工具 schema，增加 `required`、`sensitive`、`approvalRequired`、`riskLevel`、`tenantScoped`、`projectScoped`。
- 中期：新增 MCP-style tool descriptor 导出接口，让 Java 工具注册表可以被 Python Runtime 或智能网关消费。
- 后期：评估是否实现 MCP server，把 DataSmart 的治理能力暴露给兼容 MCP 的客户端。

### 2.2 Agent-to-Agent 协作正在协议化：A2A

**趋势判断：**

A2A 关注的是不同框架、不同公司、不同服务器上的 agent 之间如何发现能力、协商交互方式、处理长任务和协作，而不是把对方当作普通工具调用。官方仓库强调 agent discovery、task lifecycle、streaming、push notification、安全和可观测性。

**对 DataSmart 的意义：**

- DataSmart 的多个治理智能体不应长期写成一个巨大的 orchestrator。
- 数据接入 Agent、质量 Agent、合规 Agent、运维 Agent、审计 Agent 可以逐步具备独立能力描述、任务协议和协作边界。
- A2A 的“Agent Card”思想很适合 DataSmart：每个 Agent 暴露能力、输入输出、风险等级、权限要求、支持的任务状态。

**建议落地：**

- 近期：为 `agent-runtime` 增加 `AgentCapabilityDescriptor` / `AgentCard` 风格的领域模型。
- 中期：把 Agent 会话、run、tool execution、runtime event 和 task-management 的任务状态做协议对齐。
- 后期：评估 A2A 兼容网关，让外部 agent 或企业内部 agent 能安全调用 DataSmart agent。

### 2.3 记忆能力正在从“聊天历史”演进为多层记忆系统

**趋势判断：**

最新 agent memory 方向已经明显超出传统 conversation summary / vector RAG。Hugging Face Papers 中 MIRIX 将记忆拆为 Core、Episodic、Semantic、Procedural、Resource Memory、Knowledge Vault 等类型，并用多 agent 协调更新和检索。LangGraph 文档也将 short-term memory、long-term memory、checkpoint/store 区分开。

**对 DataSmart 的意义：**

- DataSmart 需要的不是“把所有聊天记录塞进向量库”，而是面向治理任务的分层记忆。
- 短期记忆：当前会话、当前任务、当前工具调用参数、最近事件。
- 语义记忆：数据源元数据、业务术语、质量规则、血缘、指标定义。
- 情节记忆：某次同步失败、某次质量异常处理、某次审批链路。
- 程序记忆：成功执行过的治理流程、常用修复步骤、客户环境偏好。
- 资源记忆：报告、SQL、截图、配置文件、运行日志引用。

**建议落地：**

- 近期：在 `agent-runtime` 中明确 `AgentSessionMemoryStore` 的记忆类型枚举，不只保存 session 文本。
- 中期：为 Python AI Runtime 增加 `MemoryRetrievalPlan`，根据意图选择短期、语义、情节、程序记忆。
- 后期：引入记忆压缩、过期策略、重要性评分、隐私级别和审计可回放。

### 2.4 Skill 正在成为 Agent 的“程序记忆”和能力封装方式

**趋势判断：**

Agent skill 的主流方向是把复杂工作流、工具使用说明、领域偏好和操作原则封装成可发现、可按需加载的能力包，而不是把所有提示词塞进一个大 prompt。

**对 DataSmart 的意义：**

- 数据治理领域天然适合 Skill 化：数据源接入 Skill、质量规则 Skill、同步诊断 Skill、审计导出 Skill、合规脱敏 Skill。
- Skill 不应只是 prompt 文件，还应包含工具依赖、权限需求、输入输出 schema、示例、风险说明和回滚策略。

**建议落地：**

- 近期：设计 `AgentSkillDescriptor`，字段包含 `skillCode`、`domain`、`requiredTools`、`riskLevel`、`memoryPolicy`、`approvalPolicy`。
- 中期：把项目 skill 机制与 Java `AgentToolRegistryService` 对齐，形成“Skill 选择工具，工具执行业务”的链路。
- 后期：提供 Skill 市场或租户级 Skill 开关，支持企业客户按需启用。

### 2.5 模型网关正在从“转发请求”演进为推理治理层

**趋势判断：**

OpenAI Agents SDK、LiteLLM、vLLM、SGLang 等生态都说明模型访问层正在承载更多治理能力：工具调用、handoff、guardrails、tracing、虚拟 key、budget、fallback、routing、prefix cache、KV cache。

**对 DataSmart 的意义：**

- `ModelGatewayService` 不应只是 provider 枚举和 URL 转发。
- 它应该逐步承担模型路由、成本预算、延迟监控、fallback、熔断、prompt/response 审计、缓存策略、租户配额。
- 对数据治理产品来说，模型网关还要理解“任务类型”：意图识别、SQL 生成、规则生成、异常解释、报告摘要、Agent 编排。

**建议落地：**

- 近期：扩展 `ModelRouteRegistry` 的路由维度，增加 workload、latencyTier、costTier、privacyLevel、cachePolicy。
- 中期：在 Python Runtime 的 `model_router.py` 中补充 provider health、fallback、超时和模型能力标签。
- 后期：引入 prefix cache / KV cache 观测指标，尤其是多轮 Agent 和重复系统提示场景。

### 2.6 KV Cache / Prefix Cache 正在成为 Agent 推理性能关键点

**趋势判断：**

vLLM 官方文档已经把 automatic prefix caching 作为明确特性；近两年的论文和服务框架也在围绕多 agent 工作流的 KV cache 复用、预测式缓存管理和跨上下文缓存通信进行优化。

**对 DataSmart 的意义：**

- Agent 系统提示、工具 schema、租户策略、项目上下文往往在一次长任务中重复出现，非常适合 prefix cache。
- 如果不做缓存策略，工具 schema 越丰富，prompt 越长，成本和延迟越容易失控。
- 缓存策略必须与权限边界绑定，不能把一个租户或项目的上下文缓存错误复用到另一个租户。

**建议落地：**

- 近期：为模型请求增加 `cacheKeyScope` 概念，例如 `GLOBAL_SAFE`、`TENANT_SAFE`、`PROJECT_SAFE`、`SESSION_ONLY`。
- 中期：在模型网关指标中加入 prompt token、cache hit、prefill latency、decode latency。
- 后期：按工作负载拆分缓存策略：工具 schema 和系统提示可跨会话复用，用户数据和项目上下文只能在严格范围内复用。

## 3. DataSmart 下一阶段落地路线

### P0：技术雷达制度化

- 新增本文档并持续维护。
- 在 skill 中加入“前沿 AI Agent 趋势跟踪”工作规则。
- 后续涉及 agent、模型、工具、记忆、智能网关时，默认先做最新资料校准。

### P1：Agent 工具与 Skill 治理

- 扩展 Java `AgentToolRegistryService` 的工具 schema。
- 新增 Skill 描述模型，绑定工具、权限、风险、审批和记忆策略。
- Python Runtime 的 tool planner 使用统一 schema，不再依赖自由字典。

**2026-05-23 落地进展：**

- Java `agent-runtime` 已新增 `/agent-runtime/tools/descriptors`，输出 DataSmart 自定义的 MCP-style tool descriptor。
- Python AI Runtime 已优先消费 descriptor，并兼容旧 `list_tools(...)` 工具目录。
- `ToolDefinition` 已纳入 `protocol_hint`、`tenant_scoped`、`project_scoped`、`sensitive_fields`、`memory_write_policy`、`cache_policy`。
- `ToolPlan.governance_hints` 已能把敏感字段、项目范围、记忆策略和缓存策略透传给审批、审计、前端确认页和后续模型网关。
- 当前仍不实现完整 MCP Server；下一步先把 Skill、记忆和模型网关治理字段内部打稳，再决定对外协议兼容深度。

**2026-05-24 追加落地进展：**

- Java `agent-runtime` 已新增 AgentPlan 接入口 `/agent-runtime/plan-ingestions`，开始承接 Python AI Runtime 生成的 `AgentPlan`。
- Python ToolPlan 进入 Java 后会生成受控工具审计计划，而不是直接执行工具，符合 MCP/A2A 趋势中“能力描述、计划、执行控制分离”的企业治理思路。
- Java 工具目录成为工具白名单事实源，未知 toolCode 会在接入阶段被拒绝，避免模型幻觉工具进入真实执行链路。
- 工具审计已保存 `planReason`、`planArguments`、`governanceHints`、`parameterValidation`，为后续审批解释、参数 schema 强化、Agent eval 和事故回放提供数据基础。
- 高风险计划会同步推动 Run 进入 `WAITING_HUMAN`、工具审计进入 `WAITING_APPROVAL`，让“人类在环”不只是前端文案，而是控制面状态机事实。

**2026-05-24 入口治理追加落地：**

- `/api/agent/plan-ingestions` 已被 gateway 明确纳入内部服务端点保护，默认只允许 `SERVICE_ACCOUNT` 调用。

**2026-05-24 工具链上下文与任务草稿追加落地：**

- Java `agent-runtime` 已从单点工具执行推进到多工具链编排：成功工具输出会进入 Run 内上下文仓储，后续工具可以读取前序工具结果。
- `quality.rule.suggest` 已能消费 `datasource.metadata.read` 的元数据输出，减少模型在 ToolPlan 中复制大 JSON 的需求。
- `task.create.draft` 已实现为无副作用草稿工具，可以消费质量规则草案并生成可审批任务草稿，但不调用 task-management 创建真实 `PENDING` 任务。
- 这对应 Agent 生态中“工具调用可审计、结果可引用、写操作需审批”的趋势，也为后续显式 `outputRef/jsonPath`、MCP-style resource 引用、A2A 长任务状态对齐打基础。

**2026-05-24 显式工具输出引用追加落地：**

- Java `agent-runtime` 已新增 `AgentToolOutputReference` 与 `AgentToolOutputReferenceResolver`，把工具链上下文从“最近一次输出”推进到“显式来源 + 审计 ID + 路径”的可复现引用。
- 当前引用语法支持 `toolCode/fromTool`、`auditId/fromAuditId`、`jsonPath/path`，并支持 `metadata.tables[0]` 这类轻量路径。
- 该设计贴近 MCP resource reference 和工作流 DAG node output 的思想，但先保持 DataSmart 内部协议，避免过早实现完整外部 MCP Server。
- 下一步应让 Python ToolPlan 主动生成 `metadataRef/suggestionRef`，并让前端审批页展示引用关系图。
- gateway 在调用 permission-admin 前先执行本地角色白名单、可选内部 Token 和固定窗口限流，普通用户直连会被快速拒绝。
- permission-admin 已新增 `INGEST_PLAN` 动作，服务账号允许提交计划，人类角色默认拒绝直接提交计划。
- 这一步对应 Agent 平台商业化趋势中的“工具/计划入口必须经过企业网关治理”：模型或 Python Runtime 可以生成计划，但不能绕过服务账号、限流、权限策略和审计事实源。
- 当前本地限流仍是 MVP 形态，后续需要演进为 Redis/分布式限流，并配合 mTLS、JWT client credential 或服务账号密钥轮换。

**2026-05-24 幂等与可回放追加落地：**

- `AgentPlan` 接入请求已新增 `idempotencyKey`，并兼容 `pythonRequestId` 作为兜底去重键。
- Java `agent-runtime` 已能在重复提交相同计划时回放首次 run/audit 结果，而不是创建重复审批单。
- 同一个幂等键提交不同 payload 会被拒绝，避免审计事实、审批单和真实工具执行链路被混淆。
- 这对应长任务 Agent 系统的可靠性趋势：HTTP 重试、Kafka 重复投递、ack 丢失、consumer rebalance 都必须通过幂等键和可回放结果治理，而不能假设上游永远只调用一次。

**2026-05-24 工具执行安全追加落地：**

- Java `agent-runtime` 已新增工具执行前置守卫，在真实调用适配器前校验 session/run/audit 链路、租户/项目/actor 边界、参数缺失和非只读审批事实。
- 工具执行顺序已调整为先守卫、再进入 `EXECUTING`，避免校验失败时留下错误执行中状态。
- 这对应 MCP/Agent 工具体系的企业化落地方向：工具 descriptor 和 ToolPlan 只是计划层，真正执行前必须有平台控制面做二次治理。

**2026-05-24 真实工具契约追加落地：**

- `datasource.metadata.read` 已从裸 `Map` 调用升级为“本地请求契约 + 参数裁剪 + 响应摘要 + 稳定错误码”的受控工具适配模式。
- Agent Runtime 侧会读取 Python ToolPlan 的 `planArguments`，但不会盲目信任模型参数；`maxTables`、`maxColumnsPerTable` 会被 Java 控制面裁剪，`includeSampleRows` 当前强制关闭。
- 工具输出区分 `summary` 与 `metadata`，让前端、审计、后续编排和模型上下文可以优先消费摘要，避免所有场景都解析完整元数据。
- 这一步把 MCP-style 工具治理从“工具描述符与计划审计”推进到了“真实业务工具调用契约”，后续 `data-quality`、`task-management`、`data-sync` 工具都应沿用该模式。

**2026-05-24 草案型业务工具追加落地：**

- `quality.rule.suggest` 已接入真实 data-quality 草案接口，Agent 可以基于元数据生成质量规则建议。
- 当前工具被定位为 DRAFT_ONLY：只生成草案，不落库、不启用、不调度任务，避免模型直接影响生产规则。
- data-quality 第一版使用确定性元数据规则引擎作为兜底，后续 Python AI Runtime 可以叠加模型语义、数据画像、历史质量报告和业务术语。
- 这对应企业 Agent 工具趋势中的“模型给建议，控制面管边界，业务域保事实”：Agent 生成建议，但规则生命周期仍属于 data-quality。

**2026-05-24 工具链上下文追加落地：**

- Java `agent-runtime` 已新增 Run 内工具输出仓储，工具成功输出可以被后续工具读取。
- `quality.rule.suggest` 已能在 ToolPlan 不显式携带 metadata 时，读取同一 Run 中 `datasource.metadata.read` 的成功输出。
- 这把工具调用从“单个函数调用”推进到“可编排工作流上下文”，对应 MCP/Agent Runtime 生态中 tool result、resource reference、工作流 DAG 和事件回放的趋势。
- 当前仍是内存实现和隐式最近输出引用，后续应升级为显式 `outputRef/jsonPath`、对象存储引用和可回放事件日志。

### P2：Agent 记忆分层

- 明确短期记忆、语义记忆、情节记忆、程序记忆、资源记忆的存储和检索职责。
- 将任务执行事件、质量异常、同步事故、审批历史沉淀为可检索记忆。
- 增加记忆写入审批、隐私级别、过期和压缩策略。

**2026-05-23 落地进展：**

- Python AI Runtime 已新增 `AgentMemoryType`，把短期、语义、情节、程序、资源记忆写成稳定枚举。
- `AgentMemoryPlan` 已进入 `AgentPlan`，用于表达本次请求应该检索哪些记忆、允许写入哪些记忆、默认范围、保留期、审批要求和隐私说明。
- `AgentMemoryPlanner` 已根据治理域、风险标签、上下文和工具 descriptor 的 `memoryWritePolicy` 生成记忆计划。
- 敏感数据、跨范围、跨租户、导出类风险会把默认记忆范围收紧为 `SESSION`，并要求写入审批保护。
- 这一步暂不绑定 Chroma/Neo4j/Redis，先保留存储可替换抽象。

**2026-05-24 追加落地进展：**

- Python AI Runtime 已新增 `AgentMemoryRecord`、`AgentMemoryRetrievalResult`、`AgentMemoryRetrievalReport`，把“应该检索什么”和“实际检索到了什么”拆成两个契约。
- 新增 `AgentMemoryRetriever` 协议与 `InMemoryAgentMemoryRetriever`，先用内存实现验证 tenant/project/session 隔离、关键词排序和 `maxItems` 行为。
- `AgentOrchestrator` 已新增 `retrieve_memory` 状态节点，并通过 `MEMORY_RETRIEVED` 事件记录目标数量、实际召回数量和检索器类型。
- 当前设计继续遵守“先接口、后存储”的原则：短期记忆可接 Redis，会话/事件可接 MySQL 或 Kafka 审计，语义记忆可接 Chroma，关系记忆可接 Neo4j，资源记忆可接 MinIO 索引。

### P2.5：Agent Skill 能力包治理

- Skill 已被建模为 `AgentSkillDescriptor`，包含工具依赖、权限要求、记忆依赖、风险等级、审批策略、触发关键词和示例。
- Python AI Runtime 已新增 `AgentSkillRegistry`，根据治理域、候选工具和关键词选择 Skill。
- `AgentPlan` 已返回 `skill_plan`，为前端解释、Java 控制面审批、未来 Skill 市场和 A2A Agent Card 映射预留字段。
- 下一步建议把 Skill descriptor 同步到 Java `agent-runtime`，让 Java 成为 Skill 治理事实源。

**2026-05-23 追加落地进展：**

- Java `agent-runtime` 已新增 `AgentSkillRegistryProperties`，独立配置 Skill 注册表。
- Java 已新增 `/agent-runtime/skills/descriptors` 与 `/api/agent/skills/descriptors`，输出 `datasmart.agent.skill.v1` descriptor。
- Skill descriptor 已包含工具依赖、权限依赖、记忆依赖、风险等级、审批策略、租户/项目范围、审计要求、示例目标和触发关键词。
- 默认 Skill 已覆盖数据源画像分析、质量规则设计、受控任务创建、权限边界解释。
- 当前采用 `AGENT_CARD_STYLE` 协议提示，先形成内部 Agent Card 风格描述，不急于实现完整 A2A Server。

**2026-05-24 追加落地进展：**

- Python AI Runtime 已新增 Java Skill descriptor 客户端，优先消费 `/agent-runtime/skills/descriptors`，失败后回退本地默认 Skill。
- `build_default_orchestrator(...)` 已可注入远程 Skill registry client，说明 Skill 治理链路已形成“Java 控制面事实源 -> Python 编排层消费”的闭环。
- 当前仍不急于实现完整 Skill 市场或 A2A Server，下一步更适合补模型网关治理和记忆写入审批边界。

### P3：模型网关与推理缓存治理

- 将模型 provider、workload、fallback、budget、latency、cachePolicy 纳入统一路由模型。
- 为 prefix cache / KV cache 预留指标与配置，不直接绑定单一推理框架。
- 未来在 vLLM/SGLang 部署路径中验证 prefix cache 对长 agent 工作流的收益。

**2026-05-24 落地进展：**

- Python AI Runtime 已新增模型网关治理契约，覆盖 Provider 健康状态、预算策略、预算决策、路由决策和缓存范围。
- `ModelRoute` 已纳入 `fallback_group`、`latency_tier`、`cost_tier`、`cache_key_scope`、`health_check_path`，默认模型路由开始具备延迟/成本/缓存治理元数据。
- 新增 `ModelGatewayGovernanceService`，支持预算预评估、跳过不可用 Provider、fallback 候选选择、交互式延迟偏好和显式缓存范围覆盖。
- 当前实现为内存健康表与内存预算台账，主要用于固定契约和测试；后续可替换为 Prometheus/Redis/MySQL/Java 控制面。
- 设计继续保持供应商中立，不直接锁定 LiteLLM、vLLM、SGLang 或某个云厂商，先让 DataSmart 自己的治理语义稳定。

**2026-05-24 追加落地进展：**

- 模型网关治理已接入 `AgentOrchestrator` 主链，Agent 状态流新增 `route_model_gateway`，并在模型调用前完成预算、健康、fallback 与缓存范围决策。
- `AgentPlan` 已返回 `model_gateway_decision`，运行事件新增 `MODEL_GATEWAY_ROUTED`，为前端展示、智能网关观测和审计回放提供模型治理依据。
- 模型调用成功后已可通过 `record_invocation_usage(...)` 回写 usage，形成“调用前预算评估、调用后 token 记账”的最小成本闭环。
- 模型网关上下文构建已拆到独立 helper，避免 Agent 编排器继续膨胀。

**2026-05-24 API 展示闭环：**

- `build_plan_response(...)` 顶层响应已新增 `modelGatewayGovernance`，把内部模型网关决策转换为前端/Java 控制面可直接消费的扁平摘要。
- 摘要已覆盖预算状态、fallback、健康状态、缓存范围、候选 Provider、展示摘要和推荐动作，适合进入 Agent 计划确认页与审计流水。
- 模型网关阶段当前可以暂时收口，后续重点转向 Agent 工具执行安全与 Java 控制面联动。

**2026-05-27 OpenAI-compatible tool_calls 契约追加落地：**

- 参考 OpenAI Chat Completions 当前 tool/function calling 语义，Python AI Runtime 已支持解析非流式 `message.tool_calls` 与流式 `delta.tool_calls`。
- streaming tool call 会按 `index` 输出 `ModelToolCallDelta`，保留 `id/type/name/arguments` 增量，符合“参数分片返回、运行时再聚合”的 Agent 执行模式。
- Provider 只负责解析模型意图，不直接执行工具；权限、审批、参数 schema 校验、工具沙箱和审计仍由 Agent loop 与 Java 控制面治理。
- `model_provider.py` 已拆出 `openai_compatible_provider.py`，避免后续模型网关、工具调用、指标和连接池能力堆进同一个文件。
- 这一步让 DataSmart 从“模型能输出文本/流式文本”推进到“模型能提出结构化工具调用意图”，是对齐 Codex、Claude Code 类 Agent 工具能力的关键前置。

**2026-05-27 工具 schema 暴露治理追加落地：**

- Python AI Runtime 新增 `model_tool_schema.py`，把 DataSmart `ToolDefinition` 转换为 OpenAI-compatible `tools` 请求体。
- 工具暴露不再是“把全量工具塞给模型”，而是先经过 `ModelToolSchemaExposurePolicy` 控制工具数量、CRITICAL 风险隐藏、审批工具提示和 strict schema 开关。
- 工具函数名会从 DataSmart 原始点号命名转换为 OpenAI-compatible 更稳妥的下划线命名，例如 `quality.rule.suggest` -> `quality_rule_suggest`；模型回传 tool_calls 时再映射回原始工具名，避免 Java 工具执行链路失配。
- 工具描述中会写入风险等级、执行模式、只读/草案/审批、租户/项目边界、敏感字段等治理提示，让模型选择工具时能看到边界。
- 这一步补上了“模型看见哪些工具”的治理面，后续才能安全进入 tool call 聚合、参数校验、审批和执行闭环。

**2026-05-27 Agent 编排器候选工具暴露追加落地：**

- `ToolPlanner` 新增 `model_visible_tools(...)`，按 IntentAnalysis、Skill required tools 和规则式计划结果合并本轮模型可见工具。
- `AgentOrchestrator` 的 `invoke_model_intent` 节点已把候选工具写入 `ModelInvocationRequest.available_tools`，让模型节点不再只看自然语言 messages。
- 工具暴露仍分两层：编排器先按意图/Skill 裁剪候选工具，Provider 前再按风险策略转换为 OpenAI-compatible `tools`。
- `test_model_provider.py` 已拆分，工具 schema 和 tool_calls 测试移入 `test_model_tool_schema.py`，为后续 tool call 聚合器与执行闭环测试预留空间。
- 这一步让工具 schema 从“Provider 单测能力”进入“Agent 主编排链路”，下一步可以做 streaming tool call delta 聚合和工具执行前置治理。

**2026-05-27 streaming tool call delta 聚合追加落地：**

- Python AI Runtime 新增 `model_tool_call_aggregator.py`，把 OpenAI-compatible streaming `ModelToolCallDelta` 按 `index` 聚合为完整 `ModelToolCall`。
- 聚合器支持一次性 replay 聚合，也支持实时 `accept_chunk(...)` 增量聚合，适配 WebSocket/SSE 实时 UI 与断线回放。
- 聚合报告会输出 `tool_calls`、`issues`、source chunk/delta 数量；缺少 name、arguments、call_id 时不抛异常，而是形成可审计 issue。
- 这一步对齐当前 function calling streaming 模式：模型参数片段按 index 累加，但执行前仍必须做参数 schema 校验、权限判断、审批和审计。
- 下一步可把聚合后的 `ModelToolCall` 映射到 DataSmart `ToolPlan` 或独立“模型工具调用候选”，再接 Java agent-runtime 执行链路。

**2026-05-27 模型工具调用候选治理追加落地：**

- Python AI Runtime 新增 `model_tool_call_planner.py`，把聚合后的 `ModelToolCall` 转换为 DataSmart `ToolPlan` 或治理拒绝候选。
- 治理问题覆盖未知工具、未在本轮暴露的工具、非法 JSON 参数、非 object 参数、需审批工具、CRITICAL 风险工具。
- 模型回传的下划线函数名会通过工具 schema alias 映射回 DataSmart 点号工具名，继续复用 Java agent-runtime 工具注册命名。
- 通过治理的候选会生成 `ToolPlan`，并复用 `ToolParameterValidator` 做参数完整性校验；参数缺失时不会执行，只保留可解释问题。
- 这一步把“模型提出工具调用”接入了 DataSmart 平台治理语义，为后续 runtime event、审批和 Java 工具执行链路打通前置桥梁。

**2026-05-27 模型工具调用治理事件追加落地：**

- Python AI Runtime 新增 `model_tool_call_events.py`，把 `ModelToolCallPlanningReport` 转换为 runtime events。
- 事件契约新增 `MODEL_TOOL_CALL_PROPOSED`、`MODEL_TOOL_CALL_ACCEPTED`、`MODEL_TOOL_CALL_REJECTED`、`MODEL_TOOL_CALL_APPROVAL_REQUIRED`。
- 事件只记录工具名、callId、风险等级、执行模式、参数字段名和 issue code，不记录真实 arguments 值，避免 WebSocket/replay/audit 扩大敏感参数泄露面。
- 需审批候选会生成 `AUDIT` 级事件，被治理拒绝候选会生成 `WARNING` 级事件，便于前端实时展示、审计回放和运维诊断区分不同状态。
- `RuntimeEventVisibilityPolicy` 已把模型工具调用事件纳入 BASIC 可见进度集合；普通用户只能看到脱敏进度，管理员/审计员可按角色看到更完整治理摘要。
- 这一步让“模型提出工具调用 -> 平台治理判断”不仅停留在内存对象里，而是进入实时事件通道，为后续 Agent loop、审批单、Java 工具执行和回放诊断提供统一轨迹。

**2026-05-27 Agent 主链非流式 tool_calls 治理接入：**

- Python AI Runtime 新增 `agent_model_intent_node.py`，把模型意图调用从 `AgentOrchestrator` 中拆出，避免编排器继续膨胀。
- `AgentModelIntentNode` 负责构造模型消息、传入候选工具、调用 Provider、接收非流式 `tool_calls`、治理候选并写入 runtime events。
- `AgentOrchestrator` 现在会把模型生成的 `ToolPlan` 与规则式安全基线计划合并；合并策略为“规则顺序保依赖、模型参数优先”。
- `ToolPlanner` 新增 `registered_tools()`，让模型工具调用治理能够同时检查“平台注册工具全集”和“本轮模型可见工具集”。
- 主链状态新增 `govern_model_tool_calls`，表示模型确实返回了工具调用并已经进入平台治理。
- 这一步让 DataSmart 从“Provider 可以解析 tool_calls”推进为“Agent 主流程能接住并治理 tool_calls”，但仍不直接执行工具，继续把执行、审批、审计和幂等留给 Java agent-runtime。

**2026-05-27 Agent 主链 streaming tool_call_deltas 聚合治理接入：**

- `AgentModelIntentNode` 已优先尝试 Provider `stream(...)`，并在流式路径中聚合 `ModelInvocationChunk.tool_call_deltas`。
- streaming 工具调用复用 `ModelToolCallDeltaAggregator`，按 `index` 聚合 name、arguments、callId 和 type，符合 OpenAI-compatible streaming tool calls 的分片返回模式。
- 聚合后的 `ModelToolCall` 继续走与非流式相同的 `_govern_model_tool_calls(...)`，确保流式和非流式不会产生两套权限、参数、审批或事件规则。
- `AgentModelIntentNodeResult` 新增 streaming chunk/delta/assembly issue 计数，为后续模型工具调用可观测性指标预留入口。
- 支持通过请求变量 `streamModelIntent` / `stream_model_intent` 显式开关 streaming，便于灰度、排障和私有模型网关兼容。
- 这一步让 DataSmart 的 Agent 主链从“只能治理完整 tool_calls”升级为“能治理 streaming 片段聚合后的 tool_calls”，更接近实时 Agent 产品形态。

**2026-05-27 工具执行结果回填模型消息契约追加落地：**

- `ModelMessage` 已扩展 `tool_call_id`、`name`、`tool_calls`，支持 assistant tool_calls 历史消息与 role=tool 工具结果消息。
- Python AI Runtime 新增 `model_tool_result_feedback.py`，定义 `ToolExecutionFeedbackStatus`、`ToolExecutionFeedback`、`ToolExecutionFeedbackMessageBundle` 和 `ModelToolResultFeedbackBuilder`。
- 构建器会生成 `assistant(tool_calls)` + `tool(tool_call_id=result)` 的下一轮模型消息包，并检测缺失或多余的 tool_call_id。
- 工具结果回填只使用 Java 控制面返回的摘要、审计 ID、runId、outputRef 和允许进入模型的 result 字段；敏感字段会被脱敏。
- OpenAI-compatible Provider 已支持把 assistant `tool_calls` 与 tool `tool_call_id` 序列化进 Chat Completions 请求体。
- 这一步固定了 `tool_call_id -> tool result message -> next model turn` 的协议基础，但仍不在 Python 内执行工具；真实执行继续由 Java agent-runtime 控制。

**2026-05-27 Agent 主链模拟工具反馈二轮推理闭环追加落地：**

- Python AI Runtime 新增 `model_tool_feedback_provider.py`，把“工具执行反馈来源”抽象为可替换 Provider，并提供 `SimulatedModelToolExecutionFeedbackProvider` 作为最小闭环占位。
- `AgentModelIntentNode` 已在模型提出 tool_calls 并完成平台治理后，构造模拟的 Java 控制面反馈，再通过 `ModelToolResultFeedbackBuilder` 生成 assistant/tool 消息包进入第二轮模型调用。
- 第二轮模型调用显式设置 `tool_choice="none"` 且不暴露工具列表，避免当前最小闭环阶段出现无限工具调用循环；后续如果要支持多轮工具链，应升级为有最大步数、预算、状态机和人工接管策略的 Agent loop。
- runtime events 新增 `TOOL_RESULT_FEEDBACK_BUILT` 与 `MODEL_SECOND_TURN_COMPLETED`，用于让 WebSocket、审计回放和调试面板看见“工具结果已回填、二轮推理已完成”的关键节点。
- 模拟反馈遵守治理边界：需要人工审批的工具只返回 `WAITING_APPROVAL`，参数非法的工具返回 `REJECTED`，只有低风险且参数有效的候选才返回模拟成功摘要；Python 仍不直接执行真实业务工具。
- 这一步让 DataSmart 从“模型能提出工具调用并生成回填消息契约”推进到“Agent 主链具备最小二轮推理 loop”，更接近 Codex、Claude Code 类 Agent 的工具使用体验，同时保留 Java agent-runtime 作为真实执行、审批、审计和幂等的控制面事实源。

**2026-05-27 Java 控制面工具结果查询与 Python 反馈 Provider 追加落地：**

- Java `agent-runtime` 新增按 `sessionId/runId/auditId` 查询工具执行结果快照的只读入口：`GET /agent-runtime/sessions/{sessionId}/runs/{runId}/tool-executions/{auditId}/result`。
- 查询结果返回 `audit + output`，不触发执行、不推进状态，适合 Python Runtime 二轮模型推理、前端刷新、审计回放和轮询补偿。
- Python AI Runtime 新增 `agent_runtime_tool_feedback_client.py`，将 Java `AgentToolExecutionResultView` 映射为 `ToolExecutionFeedback`，并区分 `SUCCEEDED/FAILED/WAITING_APPROVAL/SKIPPED`。
- `JavaAgentRuntimeToolFeedbackProvider` 会在 ToolPlan 带有 Java 控制面引用时优先查询真实结果；缺少引用或 Java 暂不可用时回退模拟反馈，支持渐进式集成。
- 这一步贴合 OpenAI-compatible tool result 与 MCP tool result 的共同方向：工具结果可以是结构化 JSON，但业务失败、审批等待和跳过状态必须作为工具结果语义返回给模型，而不是伪造成成功输出。
- 当前仍未实现完整“Python Plan -> Java ingestion -> Java execute -> Python poll/callback -> multi-step loop”的自动闭环，但真实结果查询和 Provider 替换点已经具备。

### P4：A2A / 多智能体协作协议

- 为内部治理 agent 建立 Agent Card 风格的能力描述。
- 将 DataSmart 的长任务、runtime event、WebSocket replay 与 agent task lifecycle 对齐。
- 后续再评估外部 A2A 兼容，而不是现在就过早实现完整协议。

## 4. 当前参考来源

- MCP 官方文档：<https://modelcontextprotocol.io/docs/getting-started/intro>
- A2A 官方 GitHub：<https://github.com/a2aproject/A2A>
- LangGraph Memory 文档：<https://docs.langchain.com/oss/python/langgraph/memory>
- OpenAI Agents SDK 文档：<https://openai.github.io/openai-agents-python/>
- vLLM Automatic Prefix Caching：<https://docs.vllm.ai/en/latest/features/automatic_prefix_caching/>
- LiteLLM Virtual Keys / Proxy：<https://docs.litellm.ai/docs/proxy/virtual_keys>
- Hugging Face Paper - MIRIX：<https://huggingface.co/papers/2507.07957>

## 5. 后续扫描清单

- GitHub：LangGraph、AutoGen、CrewAI、MCP servers、A2A、mem0、vLLM、SGLang、LiteLLM、OpenHands、OpenDevin 类项目。
- Hugging Face Papers：agent memory、tool learning、multi-agent workflow、KV cache、inference serving、agent evaluation。
- 协议与标准：MCP、A2A、AGENTS.md、Agent Skill、tool schema、workflow event protocol。
- 推理栈：vLLM、SGLang、TensorRT-LLM、LMDeploy、llama.cpp、TGI。
- 模型栈：Qwen、DeepSeek、Mistral、embedding、reranker、vision-language、small agent model。

## 6. 最新落地记录

**2026-05-28 AgentPlan 接入 Java 控制面与 auditId 映射：**

- Python AI Runtime 新增 AgentPlan ingestion client，可以把模型规划出的 `AgentPlan` 提交到 Java `agent-runtime`，并解析 Java 返回的 session/run/toolAudits。
- 通过 `modelToolCallId -> auditId` 映射，Python 可以把 `agentRuntimeSessionId`、`agentRuntimeRunId`、`agentRuntimeAuditId` 写回 ToolPlan governance hints。
- 这一步让 DataSmart 的 Agent 架构更贴近当前主流工具型 Agent：模型只提出工具调用意图，控制面负责审计、审批、幂等和真实执行，工具结果再以结构化消息回到模型上下文。
- 当前仍不实现完整 MCP Server 或外部 A2A 协议，而是优先把内部控制面契约做稳，避免为了追热点牺牲产品闭环。
- 下一步应把计划接入、Java 执行结果查询、Kafka/WebSocket 状态事件和受控多步 loop 串起来，并持续补充最大步数、预算、超时、循环检测和人工接管等商业化保护。

**2026-05-28 Java 控制面反馈快照追加落地：**

- Python AI Runtime 新增 `AgentControlPlaneFeedbackCollector`，在 AgentPlan 接入 Java 并拿到 auditId 后，可以读取 Java 当前工具反馈状态。
- 反馈快照会输出成功、失败、等待审批、跳过、缺失反馈等状态统计，并给出 `secondTurnEligible`，用于判断是否具备进入二轮模型推理的基础条件。
- 这一步吸收的是当前工具型 Agent 的关键经验：工具调用不是一次函数执行，而是有状态、有审批、有失败、有回放的控制面工作流。
- DataSmart 目前仍保持克制：反馈快照只读取事实，不触发执行、不推进审批、不自动继续模型循环；后续再把它接入受控多步 loop 和实时事件流。

**2026-05-28 受控 Agent loop 策略追加落地：**

- Python AI Runtime 新增 `AgentLoopControlPolicyEvaluator`，在控制面反馈快照之后判断是否允许自动进入二轮模型推理。
- 策略覆盖最大工具步数、最大二轮次数、工具数量上限、token 预算、全局超时、控制面等待超时、等待审批人工接管、失败/跳过反馈是否允许进入二轮等保护。
- API 响应新增 `agentLoopControl` 摘要，输出 allowed、action、reasons、recommendedActions，让前端和审计能看到 Agent 为什么继续、等待、停止或转人工。
- 这一步对齐当前 Agent 工具体系的发展方向：自治能力必须和 budget、guardrail、approval、human takeover、traceability 绑定，而不是把工具循环写成模型自由递归。

**2026-05-28 AgentPlan 响应组装解耦追加落地：**

- Python AI Runtime 新增 `api_plan_response.py`，把 AgentPlan HTTP 响应组装从 FastAPI 路由层拆出。
- 拆分后的响应组装模块集中处理事件 envelope、事件存储/推送、Java plan ingestion 摘要、控制面反馈快照和 loop 控制决策摘要。
- `api.py` 只保留路由与依赖装配职责，避免后续二轮推理、长期记忆写入、实时事件或智能网关能力继续挤进单个 API 文件。
- 这一步对应当前 Agent Runtime 工程化趋势：真实 Agent 系统需要把 route、orchestrator、control-plane adapter、event protocol、loop policy 分层，而不是把所有副作用藏在一个 HTTP handler 里。

**2026-05-28 受控二轮推理编排器追加落地：**

- Python AI Runtime 新增 `AgentSecondTurnOrchestrator`，只有在 Java 控制面反馈完整且 `AgentLoopControlDecision.action=allow_second_turn` 时才调用模型二轮。
- 二轮请求基于 assistant(tool_calls) + tool(tool_call_id=result) 消息契约构造，但不再暴露工具，并强制 `tool_choice=none`，避免当前阶段出现无限工具循环。
- 新增 `agent_loop_control_decided` 与 `model_second_turn_skipped` 事件，二轮允许、跳过和完成都进入可 replay 的 runtime event 流。
- 这一步进一步贴近 Codex / Claude Code 类 Agent 的关键体验：模型可以基于工具结果继续推理，但每次继续都必须经过预算、审批、状态和完整性守卫。

**2026-05-28 Java 工具执行状态事件契约追加落地：**

- Java `agent-runtime` 新增 `AgentToolExecutionEventPublisher` 端口与 `AgentToolExecutionStateChangedEvent` 契约，开始把工具执行状态变化沉淀为可订阅事实流。
- 状态事件覆盖初始计划、审批通过、审批拒绝、执行中、成功、失败等关键节点，为 Python Runtime、智能网关、前端 WebSocket、审计中心和 observability 提供统一上游事实。
- 默认发布器为 Noop，Kafka 发布器通过 `datasmart.agent-runtime.tool-execution-events.enabled=true` 显式启用，避免本地无 Kafka 时破坏开发体验。
- 事件 payload 默认不发布完整工具入参和审批备注原文，只发布参数键、状态、摘要、是否存在审批备注和治理元数据，贴合当前 Agent 工具事件“可观察但不扩散敏感上下文”的安全趋势。
- 这一步把 DataSmart 从同步反馈快照继续推向事件驱动 Agent loop：后续可以按 runId 订阅/replay 工具状态，再驱动二轮推理、前端进度和人类审批体验。

**2026-05-28 Java 工具状态事件投影/replay 追加落地：**

- Java `agent-runtime` 将工具状态事件发布改造成 `DefaultAgentToolExecutionEventPublisher + AgentToolExecutionEventSink` 组合模式，同一条状态事实可以同时进入 Kafka、runtime-event 投影、未来 outbox、WebSocket 和审计中心。
- 新增 `AgentToolExecutionEventProjectionSink`，把 Java 工具状态事件写入已有 `AgentRuntimeEventProjectionStore`，让 Python runtime event 与 Java tool execution event 可以共享同一套查询、脱敏、replay 和诊断入口。
- Kafka 发布器被重构为 `KafkaAgentToolExecutionEventSink`，Kafka 不再是唯一发布实现，而是可选下游出口；这更贴近真实 Agent 平台的多通道事件总线设计。
- runtime-event 投影只保留工具状态、风险、审批、参数键名、治理提示键名和参数校验摘要，不写完整工具入参、审批备注或完整输出，继续坚持“事件可回放但敏感上下文不扩散”的安全边界。
- 这一步为后续智能网关 `/api/agent/events/ws`、断线 replay、Python 事件驱动 loop、长期审计 outbox 和前端实时工具进度提供了统一事件底座。

**2026-05-28 Python WebSocket/HTTP replay 接入 Java runtime-event 投影：**

- 本次实现前再次对照了当前 Agent 工程趋势：MCP 强调 AI 应用与工具/数据/工作流的标准化连接，OpenAI Agents SDK 强调 agent loop、tool invocation、sessions、human-in-the-loop 与 tracing，A2A 强调跨 agent 应用互操作。这些方向都指向一个共同结论：工具型 Agent 必须有可恢复、可追踪、可治理的事件时间线。
- Python AI Runtime 新增 `RuntimeEventReplaySource` 与 `RuntimeEventReplayCoordinator`，将 Java runtime-event 投影视为可插拔外部 replay source，而不是硬编码进 WebSocket handler。
- 新增 `JavaAgentRuntimeEventReplayClient`，把 Java `AgentRuntimeEventProjectionView` 映射为 Python `AgentRuntimeEvent`，使 WebSocket subscribe/reconnect 和 HTTP replay 都能合并 Python 编排事件与 Java 工具状态事件。
- `create_app()` 通过 `DATASMART_AGENT_RUNTIME_EVENT_REPLAY_ENABLED` 控制是否启用 Java replay，保持本地开发默认轻量，同时给集成/生产环境预留 timeout、path、limit 配置。
- 对 DataSmart 的产品意义：智能网关的实时事件通道不再只是“Python 自己讲述模型做了什么”，而开始恢复“模型如何规划 + Java 工具如何真实审批/执行/失败”的统一运行轨迹。
- 当前仍保持克制：没有盲目实现完整 MCP Server、A2A 协议或复杂 agent framework，而是先把 DataSmart 内部事件可恢复链路做稳。下一阶段应把 replay 事件用于驱动受控二轮 loop，并继续补全统一 sequence、事务 outbox、长期记忆和模型网关缓存治理。

**2026-05-28 runtime-event 参与受控 Agent loop 决策：**

- 当前主流 Agent Runtime 正在把 “agent loop + tool invocation + sessions/tracing” 组合成可观测执行轨迹，而不只是一次模型调用。DataSmart 本次落地选择把 replay 到的 Java 工具状态事件接入 loop policy 前置快照，正是对这个趋势的产品化吸收。
- 新增 `AgentRuntimeEventFeedbackBridge`，让 Java runtime-event replay 能补齐同步结果查询缺失的反馈，或刷新等待审批/跳过等较旧状态，再由 `AgentLoopControlPolicyEvaluator` 判断是否继续二轮推理。
- 执行中的工具事件不会被转换成工具结果，避免模型提前基于未完成工具继续推理；这与企业 Agent 的安全趋势一致：自治 loop 必须受状态、审批、预算和人工接管保护。
- API 新增 `runtimeEventFeedback` 摘要，帮助前端和运维判断本次二轮允许/阻断是否来自 replay 事件，而不是黑盒策略。
- 这一步仍然没有追完整 MCP Server 或 A2A task runtime，而是先把内部事件事实、工具反馈和 loop 策略串稳。下一阶段更适合补事务 outbox、统一 sequence 和后台事件触发 loop 恢复，然后再切到长期记忆、模型网关 KV/prefix cache 和 Skill 工具市场。

**2026-05-28 Java 工具执行事件 outbox 底座追加落地：**

- 本次落地对齐的是工具型 Agent 的可靠运行趋势：agent loop、tool invocation、tracing、human-in-the-loop 只有在事件可恢复、可诊断、可补偿时，才适合进入商业化生产场景。
- Java `agent-runtime` 新增工具执行事件 outbox 配置、领域记录、状态枚举、内存 store、优先级 sink、查询服务和诊断 API，让工具状态事件先进入可查询事件箱，再面向 Kafka/WebSocket/审计中心等下游演进。
- outbox payload 复用统一的 `AgentToolExecutionStateChangedEvent`，继续坚持不发布完整工具入参、审批备注原文和完整输出；超大 payload 会进入 `BLOCKED`，避免通用事件通道扩散异常上下文。
- 新增 MySQL 迁移脚本 `20260528_agent_runtime_tool_event_outbox.sql`，明确后续生产路线：工具审计状态更新与 outbox INSERT 同事务提交，后台 dispatcher 负责异步投递与失败补偿。
- 当前仍保持克制，没有急着实现完整 dispatcher 或数据库 store；原因是工具审计本身仍是内存仓储。下一阶段应先把工具审计状态落 MySQL，再把 outbox 从热窗口升级为真正事务 outbox。

**2026-05-28 Java 工具执行审计持久化契约追加落地：**

- 本次落地把工具执行审计从 `AgentToolExecutionAuditMemoryStore` 具体实现抽象为 `AgentToolExecutionAuditStore` 端口，开始为生产级可恢复工具状态机做存储解耦。
- `AgentToolExecutionAuditService` 现在在审批、拒绝、执行中、成功、失败等状态变更后，先保存审计状态，再发布工具状态事件，避免下游看到系统事实库中不存在的“幽灵事件”。
- 新增 `agent_tool_execution_audit` MySQL 表迁移脚本，覆盖租户、项目、工作空间、工具、风险、审批、状态、参数摘要、治理提示、输出摘要和错误码，支撑后续审计中心、WebSocket replay、长期记忆追溯和 outbox 对账。
- 配置层新增 `datasmart.agent-runtime.persistence`，默认继续使用 memory，避免本地开发被数据库依赖卡住；生产化路线则预留 `audit-store=mysql + database-enabled=true` 的双开关灰度策略。
- 这一阶段仍然没有直接接完整数据库 store，是有意控制节奏：先固定仓储端口、保存顺序、SQL 表和测试语义，再实现 MySQL store 与事务 outbox，降低重构风险。

**2026-05-28 Java MySQL 工具审计仓储追加落地：**

- Java `agent-runtime` 新增条件化 Hikari/JDBC 持久化配置，只有 `audit-store=mysql` 且 `database-enabled=true` 时才创建连接池，默认 memory 模式不受影响。
- 新增 `JdbcAgentToolExecutionAuditStore`，用 `audit_id` 做幂等 upsert，支持单条保存、批量事务保存、按 auditId 查询和按 session/run 查询。
- 本次刻意没有引入 MyBatis-Plus starter，避免默认本地环境触发 DataSource 自动配置；这是一种面向快速演进阶段的稳妥过渡，后续可在 agent-runtime 整体数据库化后再迁移 mapper。
- 该能力让工具执行审计开始具备真实可恢复路径，进一步靠近当前 Agent 工程趋势中的 durable tool invocation、traceable human-in-the-loop 和 event replay。
- 下一步应补数据库版 outbox store 与同事务边界，否则审计状态虽然可落库，但事件箱仍不能保证与状态同提交。

**2026-05-28 Java MySQL 工具事件 outbox 仓储追加落地：**

- Java `agent-runtime` 新增 `outbox-store` 持久化配置与 `JdbcAgentToolExecutionEventOutboxStore`，让工具状态事件箱具备 MySQL 可恢复路径。
- 数据库 outbox store 支持唯一键幂等 append、按 run/status 查询、按 retry 时间查询可投递记录、PUBLISHING/PUBLISHED/FAILED 状态流转和 diagnostics 聚合。
- 默认仍使用 memory，不会影响本地学习；只有 `outbox-store=mysql + database-enabled=true` 才启用数据库 store 和 Hikari 连接池。
- 这一步使 DataSmart 的 Agent 工具链路更接近 durable event replay：工具状态可落库，待投递事件也可落库，下一步可以进入真正事务 outbox。
- 仍未实现 dispatcher 和同事务提交，因此还不能把它称作完整生产闭环；下一阶段应优先补事务边界与后台投递器。

**2026-05-28 Java 工具审计与 outbox 同事务边界追加落地：**

- Java `agent-runtime` 新增轻量 JDBC 连接管理器，让 MySQL audit store 与 MySQL outbox store 可以在同一同步调用链内复用同一条事务连接。
- `AgentToolExecutionAuditService` 在 `database-enabled=true + audit-store=mysql + outbox-store=mysql` 同时满足时，把审计状态保存与状态事件发布包进同一个事务；outbox sink 写入失败会触发 rollback。
- 新增必达 sink 语义：普通 Kafka/WebSocket/projection 仍保持 fail-open，事务 outbox 在双 MySQL 条件下自动 fail-closed，避免“状态已提交但事件凭据丢失”。
- 这一步把 DataSmart 的工具型 Agent 可靠性推进到 durable tool invocation 的关键工程点：工具状态、事件凭据、后续 replay/恢复不再依赖脆弱的内存窗口。
- 当前仍未实现 dispatcher 和统一 sequence；下一步应把 outbox 中的 PENDING/FAILED 事件投递到 Kafka/WebSocket/审计中心，并开始设计跨 Python/Java 的稳定游标。

**2026-05-28 Java outbox dispatcher 最小闭环追加落地：**

- Java `agent-runtime` 新增 outbox dispatcher，从 PENDING/FAILED 记录中领取可投递事件，投递成功后标记 PUBLISHED，失败后写回 FAILED 和 nextRetryAt。
- dispatcher 使用独立 `AgentToolExecutionEventOutboxDispatchTarget`，不复用同步 publisher/sink 链路，避免从 outbox 读出的事件再次写回 outbox。
- 新增 Kafka dispatch target，直接投递 outbox 中已持久化的安全 payloadJson，并等待 broker ack 后才允许标记发布成功。
- 本阶段参考 Spring 官方 scheduling 模型，采用 fixedDelay 的周期轮询语义，适合“上一轮投递完成后再启动下一轮”的补偿投递任务。
- 当前仍是最小单实例闭环，尚未实现多实例抢占锁、stale PUBLISHING 恢复、dead-letter 和统一 sequence/cursor；这些是下一阶段把工具型 Agent 事件链路推向生产的重点。

**2026-05-28 长期记忆写入候选与审批治理追加落地：**

- 本阶段再次对齐当前 Agent 工程趋势：MCP 等协议强调工具/资源/上下文的标准化连接，OpenAI Agents SDK 等运行时强调 sessions、tool invocation、human-in-the-loop 与 tracing，LangGraph 等框架强调短期/长期记忆分层。共同结论是：长期记忆不应只是向量库写入，而应是带权限、审批、审计、保留期和遗忘策略的治理资产。
- Python AI Runtime 新增 `AgentMemoryWriteGovernanceService`，把工具结果沉淀拆成“候选 -> 审批/拒绝 -> 后续持久化”的分层流程。
- 记忆候选只记录摘要、scope、memoryType、auditId/runId/outputRef、resultKeys 和敏感字段名称，不把完整工具结果扩散到 runtime event 或 API 摘要中。
- 高风险工具、敏感字段、人工审批工具和收紧 scope 的场景默认进入 `PENDING_APPROVAL`，低风险结果也只进入 `DRAFT`，不会绕过控制面直接写长期存储。
- API 响应层通过显式注入返回 `memoryWriteProposal`，保持默认路径无隐藏副作用；候选生成会进入 runtime events，便于未来 WebSocket、审计回放和前端审批面板消费。
- 下一步不应急于“接一个向量库就算长期记忆完成”，而应优先补持久化候选表、permission-admin 审批权限、遗忘/归档策略，再分别接 Chroma 语义记忆、Neo4j 关系记忆、MySQL 事件记忆和 MinIO 资源记忆。

**2026-05-28 记忆写入候选存储与审批 API 追加落地：**

- 当前 Agent 生态里，长期记忆越来越接近“可治理的运行时资源”，而不是模型供应商或框架内部的临时功能。DataSmart 本阶段把记忆候选抽成 store 协议，并补最小查询/审批 API，正是为了让候选可以进入审批台、审计台、异步写入 worker 和未来 Java 控制面。
- Python AI Runtime 新增 `AgentMemoryWriteCandidateStore` 与内存实现，先固定 `save/get/list/update` 最小接口，避免治理服务直接依赖内存字典。
- 新增 `api_memory_write.py`，把候选列表、详情、审批通过、拒绝接口从 `api.py` 拆出，防止 FastAPI 入口重新膨胀。
- 当前路由仍不直接承担最终权限判断，生产访问应由 gateway/permission-admin 注入数据范围和操作权限；这与企业 Agent 的趋势一致：模型运行时负责能力和状态，企业控制面负责权限、审计和策略。
- 下一步应把该 API 契约映射到 Java gateway route metadata 与 permission-admin action，再补 MySQL 候选表和操作审计表。
**2026-05-28 记忆写入候选权限治理追加落地：**

- 当前主流 Agent 产品正在把 memory 从“模型上下文增强”升级为“可治理资产”：需要 scope、审批、保留期、遗忘、审计和跨工具来源追踪。
- DataSmart 本阶段将长期记忆候选 API 接入 gateway/permission-admin 权限语义，新增 `VIEW_MEMORY_WRITE_CANDIDATES`、`APPROVE_MEMORY_WRITE`、`REJECT_MEMORY_WRITE` 三类动作。
- 这一步刻意先做权限闸口，而不是直接把候选写入 Chroma/Neo4j。原因是企业数据治理场景中的记忆可能包含工具结果、事故经验、字段线索和审批上下文，一旦进入长期存储就会影响后续 Agent 决策。
- 默认策略采用“审计/运营可查看、项目负责人可决策、服务账号不可人工决策”的责任链设计，贴近 human-in-the-loop Agent 的安全趋势。
- 下一步技术路线应继续关注可持久化 memory store、memory write worker、retrieval permission、forgetting policy，以及与 KV/prefix cache 的边界隔离。

**2026-05-28 记忆写入候选持久化契约追加落地：**

- 当前 Agent 记忆趋势正在从“向量库检索能力”演进为“可审批、可恢复、可遗忘、可审计的记忆治理系统”。
- DataSmart 本阶段新增候选主表、候选审计表、候选版本号和幂等键，先保证长期记忆写入前的治理事实可恢复。
- Python Runtime 新增 DB-API SQL store，并用 sqlite3 验证语义；生产方向仍是 MySQL/Java memory-service 可替换实现。
- 这一步避免了常见误区：直接把工具结果写入 Chroma 看似完成了长期记忆，实际缺少审批、审计、并发保护和遗忘入口。
- 下一步应跟进 MySQL store 启用配置、写入 worker、遗忘策略、检索权限，以及 semantic/episodic/procedural/resource 分层落库。

**2026-05-28 记忆写入候选 Store 运行时配置追加落地：**

- 当前 Agent 记忆工程正在从“框架内部 memory”走向“可部署、可审计、可切换的企业记忆基础设施”。DataSmart 本阶段把候选 SQL store 接入 Runtime 配置，避免长期记忆治理只停留在单测或 demo 层。
- Python AI Runtime 现在支持 `in-memory`、`sqlite`、`mysql` 三种候选 store 模式，默认仍为内存，保持本地学习和离线规划零依赖。
- MySQL 驱动采用动态导入，生产环境可启用 PyMySQL/mysqlclient，本地环境不被数据库客户端强绑定。
- fail-open / fail-closed 被显式建模：开发环境可回退内存继续联调，生产环境可选择快速失败，避免“配置了持久化但实际未持久化”的隐性事故。
- 新增诊断接口只返回 store 类型、实现、持久化状态、fallback 原因和脱敏 DSN，不返回候选内容。这延续了 Agent 运行时“可观察但不扩散敏感上下文”的安全原则。
- 下一步应补候选 API 错误语义、分页 cursor 和 APPROVED 候选异步写入 worker；同时保持全局节奏，继续推进智能网关模型路由、工具/Skill 执行闭环和工作区隔离，不让长期记忆局部吞掉全部演进资源。

**2026-05-28 记忆写入候选 API 分页与结构化错误追加落地：**

- 企业 Agent 的长期记忆管理界面需要像审计系统一样可翻页、可筛选、可诊断，而不是只暴露一个临时列表。DataSmart 本阶段为候选列表增加 cursor 分页契约。
- cursor 采用 `createdAt + candidateId`，先保证 API 语义稳定；后续 MySQL store 可把相同契约下沉到 SQL 条件，避免深分页性能问题。
- 错误响应增加 `errorCode/message/statusCode`，让前端、gateway、日志统计和告警规则可以基于机器可读码工作。
- 这一步继续把 memory 当作治理资产处理：候选不存在、非法游标、审批冲突和版本冲突都必须可解释、可观测、可自动化处理。
- 长期记忆候选链路至此已有候选生成、审批/拒绝、权限动作、SQL 持久化地基、运行时配置、诊断、分页和错误语义。下一阶段应在异步写入 worker 与智能网关/工具 Skill 主线之间做节奏切换，不宜继续只围绕候选 API 做局部扩展。

**2026-05-28 Agent 工作空间上下文追加落地：**

- 当前 Agent 工程趋势正在把 sessions、tracing、tool invocation、MCP 工具/资源连接和 A2A 互操作组合成可持续运行的 Agent Runtime。对 DataSmart 来说，落地这些能力前必须先有稳定 workspace 边界。
- Python Runtime 新增 `AgentWorkspaceContextBuilder`，把 tenant/project/workspace/session 转换为统一 `workspaceKey`，并派生 cache、memory、artifact namespace。
- `/agent/plans` 响应新增 `agentWorkspace`，使前端、gateway、Java 控制面和后续 Python 工具/Skill 执行都能看到同一隔离语义。
- 当前只生成逻辑 namespace，不创建真实目录、MinIO bucket、Chroma collection 或 Neo4j 子图，是为了先固定契约，再渐进接入真实资源。
- 下一步应把 workspace 写入 ToolPlan governance hints，并设计 `workspace://`、`memory://`、`minio://` 等输出引用规范；随后再把 workspace namespace 用于模型 prefix/KV cache、长期记忆写入和工具执行沙箱。

**2026-05-28 ToolPlan 工作空间治理提示追加落地：**

- DataSmart 本阶段把 workspace 从顶层响应字段推进到每个 `ToolPlan.governance_hints`，让工具执行链路不必依赖顶层响应回查。
- 这符合当前 Agent Runtime 趋势：tool invocation、sessions、tracing 和人类审批都需要在单条工具审计记录上保留足够上下文，否则 replay、恢复、审批和二轮推理会丢失隔离边界。
- workspace hints 只保留机器字段，例如 `workspaceKey`、`cacheNamespace`、`memoryNamespace`、`artifactNamespace`，不扩散人读推荐动作。
- 下一步应设计工具输出引用协议，并把 `cacheNamespace` 用于 prefix/KV cache key，把 `memoryNamespace` 用于长期记忆写入边界，把 `artifactNamespace` 用于 MinIO 或本地沙箱产物路径。

**2026-05-28 Agent 资源引用协议追加落地：**

- 当前 Agent 生态中的工具/资源连接趋势强调资源不能只是文本：MCP 的 resources、工具输出、运行事件和长期记忆都需要可定位、可审计、可授权的引用结构。
- DataSmart 新增 `AgentResourceReference`，把工具输出、workspace 产物、memory、MinIO 对象和 agent-runtime 审计引用统一成结构化协议。
- 规则式 ToolPlan 仍保留 Java 兼容的 `fromTool/path`，同时附加 `resourceReference`；模型工具结果回填仍保留 `outputRef`，同时附加 `outputReference`。
- 这一步为后续资源 resolver、模型上下文准入过滤、工作空间产物管理和长期记忆写入边界打基础。
- 下一步应把 `contextPolicy` 接入二轮推理和模型回填，防止审计专用或下载专用资源被错误放进模型上下文。

**2026-05-28 Agent 资源引用治理解析器追加落地：**

- 当前 Codex、Claude Code、MCP 生态和企业 Agent 平台都在把“工具结果”升级为“受治理资源”：资源可以被引用、下载、审计、摘要进模型或进入长期记忆，但这些路径必须有明确准入条件。
- DataSmart 新增轻量 `AgentResourceReferenceResolver`，先执行 workspace 边界、资源类型和 `contextPolicy` 校验，而不是直接读取 MinIO、Chroma、Neo4j、Java audit 或外部 URL。
- 这与主流 Agent 工程趋势一致：把 resource discovery、permission gate、context selection 和 physical fetch 解耦。模型上下文选择器只应该看到被治理准入的摘要或安全结果，不能直接根据 URI 自行读取。
- Resolver 输出 `resolverHint`，为后续 `java_tool_output_store`、`workspace_artifact_store`、`agent_memory_service`、`minio_object_store` 等读取器预留扩展点，避免形成一个巨大且高耦合的“全能资源读取类”。
- 下一步应把 `model_context_allowed` 接入二轮推理消息构建，再逐步支持 Java `resourceReference/outputReference` 与真实资源读取器。资源读取器落地前，应优先补齐权限、脱敏、大小限制、超时、缓存和审计事件策略。

**2026-05-28 模型工具结果回填接入资源准入过滤：**

- 当前前沿工具型 Agent 的一个核心趋势是“context engineering”：工具结果、文件、记忆和事件不是越多越好，而是必须按权限、任务、token 预算和安全策略选择进入模型上下文。
- DataSmart 本阶段把 `AgentResourceReferenceResolver` 接入 `ModelToolResultFeedbackBuilder`，让 role=tool 消息在二轮推理前执行 workspace 和 `contextPolicy` 判断。
- 默认 `audit_only` 的策略符合企业 Agent 安全实践：如果 Java 控制面或资源生产方没有明确声明“这个摘要可以给模型看”，Python Runtime 就不会把结构化 result 放进模型。
- 该设计也贴近 MCP resources 和现代 Agent Runtime 的分层思想：引用可以进入消息，真实内容是否进入模型要经过独立 context gate，后续还可以叠加字段级脱敏、大小限制、引用摘要、缓存和审计。
- 下一步应让 Java 控制面在工具结果查询中返回 `workspaceKey/contextPolicy`，并把资源准入决策写入 runtime event，形成可解释、可审计、可调试的上下文选择链路。

**2026-05-29 资源准入决策事件化追加落地：**

- 当前 Agent 工程趋势不只强调“能不能调用工具”，也强调工具上下文选择过程要可解释、可追踪、可回放。Codex/Claude Code 类产品里的工具调用体验，背后需要稳定事件流来解释每一步为什么发生。
- DataSmart 本阶段把资源准入判断写入 `TOOL_RESULT_FEEDBACK_BUILT` runtime event，记录 decision、issue code、modelContextAllowed、resolverHint、referenceKind 和 contextPolicy。
- 这让 context engineering 从“内部策略”变成“可观察事实”：如果模型没有看到某个工具 result，前端和审计台可以知道是 `audit_only`、workspace 越界、外部引用阻断，还是 URI 缺失。
- 事件摘要不包含 result 原文，只包含引用和治理结论。这符合企业 Agent 的安全趋势：trace 要足够可诊断，但不能把敏感工具输出扩散到 tracing/event 系统。
- 下一步应继续向字段级/JSONPath 级 context selection 演进，并让 Java 控制面返回真实 `outputReference/contextPolicy`，再把这类事件接入 WebSocket/Kafka replay。

**2026-05-29 工具结果字段级上下文过滤追加落地：**

- 当前前沿 Agent 产品的重点正在从“长上下文堆更多内容”转向“context engineering”：选择哪些工具结果、哪些字段、以什么粒度进入模型，比盲目塞入全部结果更重要。
- DataSmart 本阶段新增 `ModelResultContextFilter`，支持 include/exclude/sensitive 路径、列表通配、字符串长度限制、列表长度限制和深度限制。
- 这让工具结果可以按字段进入模型：例如表数量、字段名、规则数量可见，样本值、连接串、原始 SQL、大日志和长列表被遮蔽、删除或截断。
- `resultFilterReport` 和 runtime event 的 `resultFilters` 只记录路径和过滤动作，不记录字段值，符合企业 Agent tracing 的安全实践。
- 下一步应让 Java 控制面、工具 schema、permission-admin 字段权限和数据分级分类共同生成字段级策略；同时把过滤事件接入 WebSocket/Kafka replay，使上下文选择过程可回放、可审计。

**2026-05-29 智能网关 prefix/KV cache 治理计划追加落地：**

- 当前 Agent 基础设施趋势正在把模型网关从“Provider 转发器”升级为“路由、预算、fallback、上下文、缓存和审计的策略中枢”。对长上下文 Agent 来说，prefix/KV cache 能明显降低 prefill 成本，但如果没有租户、项目、工作空间和会话边界，就会变成新的上下文泄露风险。
- DataSmart 本阶段新增 `ModelGatewayCachePlan` 与 `ModelGatewayCachePlanner`，在模型网关路由决策中输出缓存治理计划，而不是直接把 prompt 哈希交给推理服务复用。
- 缓存范围显式区分 `GLOBAL_SAFE`、`TENANT_SAFE`、`PROJECT_SAFE`、`SESSION_ONLY`、`NO_CACHE`。其中会话级缓存必须携带 `sessionId`，缺失时禁用缓存并给出诊断。
- `cachePlan` 已进入 API 响应和 `MODEL_GATEWAY_ROUTED` runtime event，前端、Java 控制面、审计台和运维面板后续都可以解释缓存启停原因、namespace、TTL 和治理 issue。
- 下一步不建议继续在缓存本身无限深挖；更合理的节奏是把 `cachePlan` 透传给 Provider metadata，并并行推进工具执行闭环、Skill/Tool 市场、工作空间沙箱和长期记忆写入 worker，形成更接近 Codex/Claude Code 类 Agent 的整体能力。

**2026-05-29 模型 Provider metadata 透传 cachePlan 追加落地：**

- 当前前沿 Agent 网关正在从“模型 API 代理”演进为“上下文工程控制面”：网关不仅要知道请求发往哪个模型，还要知道租户、工作负载、缓存边界、trace、预算和审计标签。
- DataSmart 本阶段新增 `provider_metadata` 契约，并把 `cachePlan` 从 Agent 主链透传到 OpenAI-compatible Provider 的请求体 `metadata.datasmart` 与 `X-DataSmart-*` Header。
- 这一步让 LiteLLM/vLLM/SGLang/企业内部智能网关可以在不理解 Python 内部 dataclass 的情况下读取缓存策略，例如是否启用、namespace、keyPrefix、TTL 和 scope。
- metadata 使用白名单裁剪，不携带 prompt、工具结果、样本数据、SQL 或密钥，符合企业 Agent tracing 与 context engineering 的安全原则。
- 下一步应补“受信内部网关开关”和模型网关指标，而不是直接把复杂缓存存储塞进 Python Runtime；同时应把开发重心转向工具执行闭环、Skill/Tool 市场和工作空间沙箱，形成更完整的类 Codex/Claude Code Agent 能力。

**2026-05-29 Java 工具结果批量反馈查询追加落地：**

- 当前 Codex/Claude Code 类 Agent 的关键能力不是只调用一个工具，而是多工具链路、状态可见、失败可恢复、结果可回填。多工具链路如果每个工具结果都单独轮询，会很快形成 N+1 控制面请求。
- DataSmart 本阶段新增 Java run 级批量结果查询接口，并让 Python Java-feedback Provider 优先批量读取同一 session/run 的工具反馈。
- 这一步保持“查询不执行”的边界：批量接口只返回 audit/output 快照，不审批、不执行、不推进状态，避免反馈收集成为隐藏副作用。
- 批量优先、失败回退逐个查询的策略更适合渐进式生产部署：Java 新旧版本混合、网络波动或部分引用缺失时，Python Agent loop 不会直接中断。
- 下一步应把批量反馈与 ToolPlan DAG、runtime-event replay 和 run 级执行策略结合，而不是简单做“自动执行所有工具”。真正商业化 Agent 需要明确哪些工具能自动执行、哪些等待审批、失败后是否继续、并行组如何回滚。

**2026-05-29 Run 级工具执行策略预检追加落地：**

- 当前类 Codex、Claude Code、OpenClaw 方向的 Agent 产品越来越强调“工具调用不是函数调用，而是受治理的行动”。模型可以提出工具计划，但执行前必须有策略层判断审批、人类介入、参数完整性、执行模式、幂等性和失败恢复。
- DataSmart 本阶段新增 Java Run 级 execution policy preflight，把审计事实翻译成 `AUTO_EXECUTABLE`、`WAITING_APPROVAL`、`WAITING_PARAMETER_COMPLETION`、`WAITING_ASYNC_EXECUTOR`、`FAILED_BLOCKS_RUN` 等决策。
- 这一步符合前沿 Agent runtime 的趋势：先建立 tool invocation control plane，再做自动执行器；否则一旦把模型计划直接变成下游写操作，后续再补审批、回滚、审计和限流会非常痛苦。
- 策略接口保持只读，没有隐藏副作用。它可以被前端、Python Runtime、自动执行 worker、审计台和运维面板共同使用，成为工具行动的统一解释层。
- 下一步应沿两条线推进：一条是低风险同步工具的受控自动执行器，另一条是 `ASYNC_TASK` 到 task-management/Kafka command 的任务化执行。与此同时，需要尽快补 ToolPlan DAG，让多工具计划具备依赖、并发组、失败策略和结果回填顺序。

**2026-05-29 受控同步工具自动执行器追加落地：**

- 当前前沿 Agent 产品的工具执行能力正在从“人工点击每个工具”走向“可治理的自动行动”。但成熟产品不会直接把模型计划全部执行，而是先开放低风险、只读、幂等、可审计的同步工具。
- DataSmart 本阶段新增 `auto-execute-sync`，在 Run 级 policy 之后再次筛选 LOW/readOnly/idempotent/requiresApproval=false 的工具，形成第一条安全自动执行路径。
- `dryRun`、auditId 白名单和单批次数量上限，是面向生产的必要控制点：前端可以先预览，Python Runtime 可以缩小范围，平台可以防止一次请求打爆下游服务。
- 这一步仍然复用统一 `AgentToolExecutionService` 执行和审计状态推进，没有复制第二套状态机，符合 Agent Runtime 控制面解耦趋势。
- 下一步应让 Python Runtime 接入该闭环，并尽快设计异步工具任务化、ToolPlan DAG、权限动作、租户开关和多实例幂等执行表。否则自动执行能力会停留在单机低风险同步工具，无法支撑真实复杂 Agent 工作流。

**2026-05-29 Python 接入 Java 工具策略与自动执行追加落地：**

- 当前类 Codex/Claude Code Agent 的核心体验是“模型规划、工具执行、结果回填、继续推理”形成闭环，而不是让用户手工逐个触发工具。DataSmart 本阶段让 Python Runtime 在二轮推理前具备触发 Java 安全同步候选的能力。
- Python Provider 现在可以按需执行 `policy -> auto-execute-sync -> batch results`，这让低风险只读工具可以进入自动行动路径，同时仍由 Java 控制面掌握最终执行边界。
- 自动执行默认关闭，必须通过环境变量灰度启用。这符合企业 Agent 落地趋势：先可观测、再 dryRun、再小范围自动执行，最后才进入租户级策略自动化。
- 本阶段也拆分了 client/provider/contracts，避免工具反馈能力膨胀成单文件巨石。Agent 工具链后续会继续增加 policy、DAG、async、event、permission，如果现在不拆，后续会很难演进。
- 下一步应把 auto execution 摘要事件化，并启动异步工具任务化与 ToolPlan DAG。否则同步自动执行会变成新的局部极限，无法承载长耗时、多依赖、可恢复的真实 Agent 工作流。

**2026-05-29 同步自动执行事件化追加落地：**

- 当前前沿 Agent Runtime 的关键趋势之一是“行动可见性”：Agent 不只是执行工具，还要让用户、审计员和运维系统知道它什么时候、为什么、执行了哪些动作。
- DataSmart 本阶段将 Java `auto-execute-sync` 的批次摘要写入 Python runtime event，新增 `tool_auto_execution_sync_completed`，记录 dryRun、limit、executed/failed/skipped 计数和每个工具 action/reason。
- 这让自动执行从隐藏副作用变成可审计事件。对类 Codex/Claude Code 的交互体验来说，用户需要看到“我刚才让 Agent 继续，它自动跑了哪些安全工具”，否则工具行动会变成黑盒。
- 事件不记录工具 output 原文，只记录动作摘要，符合企业 Agent 的安全实践：trace 要解释动作，不应复制敏感数据。
- 下一步技术重心应从同步低风险工具转向 `ASYNC_TASK` 任务化、ToolPlan DAG、WebSocket replay 和 permission-admin 策略来源，避免同步自动执行链路继续无限细化。

**2026-05-31 ASYNC_TASK 异步命令草案规划追加落地：**

- 当前成熟 Agent Runtime 不会让长耗时工具继续占用同步请求线程，而是把它们转换为可恢复、可暂停、可重试、可回放的任务。DataSmart 本阶段开始建立 `agent-runtime -> Kafka/outbox -> task-management` 的异步行动边界。
- Java 新增只读 `async-command-plans` preflight：它只为 `ASYNC_TASK + WAITING_ASYNC_EXECUTOR` 工具生成稳定 commandId、幂等键、topic、消费者、隔离边界和审计上下文，不会偷偷投递消息或创建任务。
- Kafka 通常是至少一次投递，因此默认要求异步工具声明幂等。非幂等工具会保守阻断，避免重复同步、重复导出或重复写入等副作用。
- 草案只暴露参数名和敏感参数名，不暴露连接密钥、SQL、文件路径或参数值。未来 dispatcher 应按 auditId 在服务内部重新读取受控参数快照，再完成权限、schema、脱敏和密钥引用校验。
- 下一步应切换到 task-management 消费侧，落地 command 幂等表和任务创建契约，再回到 agent-runtime 做 outbox + Kafka dispatcher。这样可以避免继续在单一模块里局部优化，也降低跨服务契约反复推翻的成本。

**2026-05-31 Task Management Agent Command Inbox 追加落地：**

- 当前前沿 Agent 工具执行正在从“同步函数调用”演进为“可靠行动流水线”：计划、审批、投递、任务化、执行、回写、可见性和回放都要有明确边界。
- DataSmart 本阶段在 task-management 侧新增 Agent async command Inbox，用 commandId 与 idempotencyKey 唯一索引承接 Kafka 至少一次投递风险，避免同一 Agent 工具重复创建多个任务。
- 消费侧只把合法 command 转成通用 `AGENT_ASYNC_TOOL` 任务，复用任务中心已有队列、租约、重试、死信和运营干预能力，避免 agent-runtime 变成第二个任务系统。
- Inbox 和 task.params 只保存 payloadReference、参数名和敏感参数名，不保存原始工具参数值。这符合企业 Agent 的“引用优先、按需解析、权限复核”趋势。
- 下一步应回到 agent-runtime 增加 outbox + dispatcher，并在 task-management 增加 Kafka listener 传输适配器；业务去重和任务创建逻辑继续保持在同一个 ConsumerService 中。

**2026-05-31 Agent Command Outbox 与 Dispatcher 追加落地：**

- 当前成熟 Agent 平台越来越强调 durable action：模型规划出的行动不能只停留在内存对象或同步请求里，而要进入可恢复、可诊断、可重放、可人工补偿的执行轨道。
- DataSmart 本阶段在 agent-runtime 侧新增 ASYNC_TASK command outbox，把“准备下发异步工具命令”固定为 outbox record，再由 dispatcher 投递到 task-management。这样与 task-management Inbox 形成了“生产者 outbox + 消费者 inbox”的双端可靠性组合。
- command payload 继续坚持引用优先，只传播 `payloadReference`、参数名、敏感参数名和治理上下文，不传播真实工具参数值。这与当前 Agent 安全趋势一致：工具执行上下文应在执行边界按权限和 schema 重新解析，而不是在消息总线上到处复制。
- dispatcher 支持 PENDING/PUBLISHING/PUBLISHED/FAILED/BLOCKED 状态、失败退避、最大尝试阻断、无投递目标防误吞和 stale publishing 恢复；这些能力是未来 Kafka、WebSocket replay、死信治理和运维补偿台的基础。
- 当前仍然保持克制：HTTP target 默认关闭，后台 dispatcher 默认关闭，MySQL 表已准备但默认使用内存 store。下一步应补 Kafka target、task-management Kafka listener、MySQL command outbox store 和 payloadReference resolver，再把任务状态回写到 agent-runtime 工具审计。

**2026-05-31 Task Management Kafka Listener 追加落地：**

- 当前 Agent 工具执行趋势不是简单“发一个 HTTP 请求”，而是把行动变成可恢复的消息流水线。DataSmart 本阶段在 task-management 侧新增 Agent async command Kafka listener，开始承接 `agent-runtime outbox -> Kafka -> task-management Inbox` 的真实消息入口。
- listener 只做传输适配，真正的协议校验、幂等去重和任务创建继续复用 `AgentAsyncTaskCommandConsumerService`。这符合商业化系统的关键原则：多入口共享同一业务事实源，避免 HTTP、Kafka、重放工具各写一套逻辑。
- Kafka payload 明确采用字符串 JSON，而不是 Java 类型序列化，降低跨语言消费和运维排障成本。未来 Python Runtime、审计服务或命令行工具都可以直接查看同一消息契约。
- 默认关闭 listener、默认非法消息 fail-fast，是当前没有 DLQ 阶段的安全选择：本地不被 Kafka 依赖拖住，生产也不会静默确认坏消息。下一步应尽快补 DLQ、消费指标和积压告警。
- 到这里，异步工具任务化链路已经有 command plan、producer outbox、consumer inbox 和 Kafka listener。下一段最关键的是补 agent-runtime Kafka dispatch target 与 MySQL command outbox store，然后进入 payloadReference resolver、异步 worker、状态回写和 ToolPlan DAG。

**2026-05-31 Agent Command Kafka Dispatch Target 追加落地：**

- DataSmart 本阶段补齐 agent-runtime 侧 Kafka dispatch target，让 command outbox 可以把 ASYNC_TASK 命令真正投递到 task-management Kafka listener。异步工具链路从“生产者 outbox 与消费者 listener 分别存在”推进到“传输通道可以闭合”。
- Kafka target 仍然由 outbox dispatcher 驱动，不把发送动作塞回业务线程。这与当前 Agent 平台的 durable action 趋势一致：行动先进入可恢复 outbox，再由后台投递器完成至少一次传输。
- 发送时等待 broker ack，失败、超时和中断都会让 dispatcher 写回 FAILED 并按退避重试。命令消息会触发下游任务创建，因此不能像普通日志一样 fire-and-forget。
- topic、partitionKey 和 payload 都来自 outbox record，说明路由决策仍在 command plan/outbox 层，Kafka target 只是传输适配器。后续按租户、风险等级或工具类型拆 topic 时，不需要改投递器主流程。
- 下一步应优先把 command outbox 从内存升级为 MySQL store，并补 task-management DLQ/指标；否则传输通道虽然打通，但生产事故恢复仍会受限于单实例内存窗口。

**2026-05-31 Agent Command MySQL Outbox Store 追加落地：**

- 当前 Agent 平台的 durable action 趋势强调“行动事实先持久化，再异步投递”。DataSmart 本阶段新增 MySQL command outbox store，让 ASYNC_TASK 命令不再只停留在 JVM 内存窗口。
- Store 使用唯一索引承接 outboxId、commandId、idempotencyKey 幂等，重复写入返回既有事实，而不是制造重复任务。这与 Kafka 至少一次投递和 Agent retry 行为天然匹配。
- `PENDING/FAILED -> PUBLISHING` 通过条件 UPDATE 领取，`PUBLISHING` stale 恢复为 FAILED，形成最小可用的多实例恢复语义。后续可以进一步演进为 workerId/lockedAt/lockExpireAt 显式租约模型。
- payload 仍坚持引用优先，只保存 payloadReference、参数名和治理上下文，不在 outbox 表中复制真实工具参数值。这是企业 Agent 安全边界的关键设计。
- 下一步不宜继续只优化 outbox 内部机制，应尽快进入 payloadReference resolver、异步 worker、状态回写和 ToolPlan DAG，让“命令能可靠到达”升级为“工具能安全执行、进度能回放、失败能补偿”。

**2026-05-31 Agent payloadReference Resolver 与执行预检追加落地：**

- 当前 Codex/Claude Code 类 Agent 的工具执行链路强调“引用优先、按需解析、执行前再校验”。模型计划和消息队列不应到处复制真实参数，尤其是 SQL、密钥引用、文件路径、同步配置和数据样本。
- DataSmart 本阶段新增 Agent Runtime 内部 `plan-arguments` 快照接口，并在 task-management 侧新增 payloadReference parser、Agent Runtime client、payload resolver 和 worker 预检接口。
- resolver 会校验 task.params 摘要与 Agent 审计快照是否一致，包括 session/run/audit/tool/target/tenant/project/workspace、参数名、敏感参数名和 payload 大小。这让任务执行前具备“命令没有错配、参数没有漂移”的安全闸门。
- 预检仍然 dry-run，不直接调用 targetEndpoint。这个边界符合成熟 Agent 平台趋势：先做 tool invocation control plane，再做工具适配器和执行器；否则自动行动会变成无法审计、无法回滚的黑盒。
- 下一步应把 resolver 连接到真正 worker 的认领/心跳/执行/回写流程，并接 permission-admin 服务间策略、工具 schema 校验、密钥引用解析和 ToolPlan DAG。这样 DataSmart 的 Agent 才能从“消息能到”升级为“行动可信、进度可见、失败可恢复”。
**2026-05-31 Agent 异步工具白名单执行与 data-sync.execute 受控适配追加落地：**

- 当前类 Codex/Claude Code Agent 的工具执行能力不是“模型输出 URL，系统直接调用”，而是“模型提出工具计划，控制面按白名单、权限、幂等、审计和状态机执行”。DataSmart 本阶段把这个原则落到 `task-management` worker：只允许明确适配器执行 `data-sync.execute`。
- `data-sync.execute` 没有复用公开两步 API，而是新增内部幂等入口，把创建同步任务和提交入队合并为一个可重试动作。这对生产 Agent 很关键：worker 网络超时、Kafka 重放或手动补偿都不能产生重复同步任务。
- `dispatch-once` 先作为手动入口而不是后台无限循环，符合渐进式自动化策略：先把执行边界、幂等、失败语义和结果摘要跑通，再开启并发 worker、租户配额、心跳续租和调度器。
- 下一步应把 task-management 的执行结果回写到 agent-runtime 工具审计与 runtime event，让 Python Runtime、前端和审计台看到 TASK_RUNNING/TASK_SUCCEEDED/TASK_FAILED；随后再扩展 ToolPlan DAG、permission-admin 服务间授权和更多工具适配器。
**2026-05-31 Agent 异步工具状态回写追加落地：**

- 当前 Codex/Claude Code 类 Agent 的工具能力趋势已经不是“能调用工具”这么简单，而是要求 durable action 具备状态可见性、trace 可回放、失败可补偿、人类可审计和模型二轮推理可消费。
- DataSmart 本阶段新增 agent-runtime 内部 `async-task-status` 回调，把 task-management worker 的 RUNNING、SUCCEEDED、FAILED、DEFERRED 映射回工具审计状态和既有 runtime event。
- 这一步没有让 task-management 复制 Agent event 模型，也没有跨库写 agent-runtime 状态；所有 Agent 侧事实仍由 agent-runtime 发布，符合成熟 Agent 控制面的边界设计。
- 回写失败时 worker 会 defer 当前任务，特别是业务成功但 SUCCEEDED 回写失败时，不会静默 complete。这对应前沿 Agent 工程里的可靠行动原则：实际副作用、审计事实和用户可见状态必须最终一致。
- DEFERRED 暂不扩展状态枚举，而是作为 EXECUTING 的进度说明处理。后续等 worker 调度、DLQ、指标和前端状态视图稳定后，再引入 RETRYING/DEFERRED 细分状态更稳妥。
- 下一步技术重点应转向后台 worker 调度、DLQ/指标、ToolPlan DAG 和 permission-admin 服务间授权，避免只在单个 data-sync 适配器上继续局部深挖。

**2026-05-31 Agent 后台 worker 调度骨架追加落地：**

- 当前前沿 Agent 平台正在把 tool invocation 从同步函数调用升级为 durable action pipeline：计划、审批、入队、后台执行、状态回写、trace、重试、补偿和指标都必须成为平台能力。
- DataSmart 本阶段新增 task-management 后台 worker scheduler，但默认关闭，并要求 `enabled=true + dryRunOnly=false + schedulerEnabled=true` 三个条件同时满足才会自动消费任务。
- 这符合成熟 Agent 产品的渐进自动化思路：先手动验证执行链路，再小流量自动调度，最后才进入并发池、租户配额、工具限流和自愈补偿。
- 调度器采用 fixed-delay 和单轮上限，避免长耗时工具导致调度堆积，也避免单实例一次性吞掉过多任务压垮 data-sync 等下游服务。
- 下一步需要把 worker 变成可观测组件：Micrometer 指标、DLQ、积压告警、坏消息处理台和权限策略，比继续新增更多工具适配器更关键。

**2026-05-31 Agent Kafka 命令消费诊断与 DLQ 基础追加落地：**

- 当前类 Codex/Claude Code 的 Agent 行动链路越来越接近“可恢复消息流水线”：模型计划不是一次性函数调用，而是进入 outbox、Kafka、Inbox、worker、状态回写和事件回放的可靠轨道。
- DataSmart 本阶段补 task-management Kafka listener 的失败诊断，按 `EMPTY_PAYLOAD`、`PAYLOAD_TOO_LARGE`、`INVALID_JSON`、`CONSUMER_REJECTED`、`CONSUMER_EXCEPTION` 分类记录坏消息。
- 这一步对齐 Agent runtime 的生产趋势：工具行动失败不能只靠日志猜测，必须能被指标、告警、DLQ、人工补偿台和审计系统理解。
- 当前只做 DLQ 候选标记，不直接写真实 DLQ topic，是安全的渐进路线。真实 DLQ 必须同时包含 payload 脱敏、重放幂等、权限审批、审计记录和租户隔离，否则会变成另一个危险执行入口。
- 下一步应把诊断能力接入 Micrometer 和 Kafka record 元数据，再进入 ToolPlan DAG 与服务间授权。这样 Agent 从“能执行单个异步工具”继续升级为“多工具可编排、失败可解释、行动可恢复”的商业化平台能力。

**2026-05-31 Agent Kafka 指标与 record 元数据诊断追加落地：**

- 当前前沿 Agent 平台越来越强调 durable action observability：工具行动不只是“被调用”，还必须具备指标、trace、事件、失败分类、可定位消息和人工补偿入口。
- DataSmart 本阶段让 task-management Kafka command 入口具备 Micrometer 指标，记录 accepted/rejected、failureType、duplicate、taskCreated、DLQ candidate 和 handle duration。
- 同时 listener 开始读取 Kafka `ConsumerRecord`，把 topic、partition、offset、timestamp、keyHash 和 traceId 写入诊断样本与安全日志。
- 这一步保持了指标低基数原则：offset、traceId、commandId 不进入指标标签，避免 Prometheus 时序爆炸；单条定位交给诊断快照。
- 下一步不应继续只优化 listener，而应进入 ToolPlan DAG、服务间授权和 worker 指标。类 Codex/Claude Code Agent 的核心竞争力来自多工具可编排、行动可审计、失败可恢复，而不是单个 Kafka 消费器越来越复杂。

**2026-05-31 ToolPlan DAG 只读预检追加落地：**

- 当前前沿 Agent Runtime 的主线不是“更多工具函数”，而是“可恢复的图执行”：工具节点需要依赖边、拓扑顺序、人工中断、状态检查点、失败策略和结果回填。LangGraph 的持久化/检查点文档强调图状态、human-in-the-loop、fault-tolerance 与 replay，这与 DataSmart 先做只读 DAG preflight 再做真实执行器的路线一致。
- MCP 工具规范强调工具是模型可发现、可调用的外部能力，但也明确需要 trust & safety 与 human-in-the-loop。DataSmart 的 ToolPlan DAG 预检继续坚持“模型可规划，控制面解释与治理，执行前再授权”的边界，而不是让模型直接变成任意 HTTP 调用器。
- 本阶段新增 `dag-plan` 视图，用节点、边、并行组、失败策略、ready/blocked 解释，把 Agent 行动从线性列表提升为可审计图谱。
- `LEGACY_SEQUENCE` 兼容旧线性计划，`EXPLICIT` 支持 `dependsOn` 显式依赖，这让 Python Runtime 可以渐进升级，不需要一次性推翻现有 ingestion 契约。
- 参考资料：LangGraph Persistence / checkpointing：`https://docs.langchain.com/oss/python/langgraph/persistence`；MCP Tools specification：`https://modelcontextprotocol.io/specification/2025-06-18/server/tools`。
- 下一步应把 DAG hints 前移到 Python AgentPlan schema，并把 Java ready 节点选择接入现有 execution-policy、permission-admin、worker 指标和 runtime event replay。否则 DAG 只会停留在展示层，无法形成类 Codex/Claude Code 的真实多工具行动闭环。

**2026-05-31 Python ToolPlan DAG hints 追加落地：**

- 当前前沿 Agent 工具链正在从“模型调用单个函数”转向“计划图 + 检查点 + 工具治理 + 人类介入”的组合。DataSmart 本阶段让 Python Runtime 在计划阶段就输出 DAG hints，而不是让 Java 运行时事后猜测依赖。
- `ToolPlanDagAnnotator` 会从 `fromTool/resourceReference` 中解析依赖，并对模型生成计划补最小业务依赖兜底。这样即使模型只生成了某个业务工具调用，平台也能保留“先读元数据、再生成规则、再创建任务草稿”的治理链条。
- DAG hints 仍放在 `governanceHints`，属于兼容式演进。短期有利于快速贯通 Python -> Java；中期应升级为强类型 ToolPlan DAG schema，避免工具越来越多后 hint key 失控。
- 下一步应优先做 DAG-aware execution preview 与 permission-admin 授权，而不是直接打开并发自动执行。类 Codex/Claude Code 的体验看似自动，底层必须有权限、审计、状态、重放和失败补偿托底。

**2026-05-31 Java DAG-aware execution preview 追加落地：**

- 当前 Agent 工程趋势强调“自动行动前必须可解释”。DataSmart 本阶段新增 DAG execution preview，不执行工具，只解释每个 ready/blocked 节点下一步应走同步自动执行、异步 command、人工审批、参数补齐还是等待依赖。
- Preview 的价值是把前端、人类审批、Python Runtime loop policy 和未来 DAG worker 对齐到同一套行动语义。没有这层，真实 worker 很容易变成隐藏副作用黑盒。
- 同步候选继续要求 LOW/readOnly/idempotent，异步候选继续要求 command plan dispatchable，说明系统并没有因为 DAG ready 就放弃工具治理。
- 下一步技术重点应转向 permission-admin 服务间授权和执行 dry-run request。真正进入并发 DAG worker 之前，还需要租户配额、工具限流、worker 指标、失败补偿和 runtime event 可视化。

**2026-05-31 Agent 服务间授权预检追加落地：**

- 当前 Codex、Claude Code、MCP 工具生态和企业 Agent 平台都在强化“工具调用不是模型自由行动，而是受控委托执行”。DataSmart 本阶段在 DAG execution preview 中新增 `serviceAuthorization`，把 SERVICE_ACCOUNT 代表 actor 推进工具节点这件事显式建模。
- 预检支持 `LOCAL_PREVIEW` 与 `PERMISSION_ADMIN_EVALUATE` 两条路线：前者适合本地学习和结构检查，后者面向生产权限中心。这个分层符合成熟 Agent 平台的落地节奏：先让行动可解释，再接真实授权，再进入自动执行。
- 远端权限中心不可用被建模为 `PERMISSION_ADMIN_UNAVAILABLE`，而不是简单当作 false 或吞掉异常。这样前端、审计台和运维可以区分“权限真的拒绝”和“权限基础设施故障”。
- 该能力保持微服务边界，agent-runtime 通过本地客户端契约调用 permission-admin，而不是编译期依赖权限模块 DTO。后续可以平滑替换为 OpenFeign、gRPC、服务网格或带缓存的授权代理。
- 下一步应把 permission-admin evaluate 契约升级为支持 representedActorId、serviceAccountId、delegationReason 和 policyVersion，并在真实 DAG worker 前打开 fail-closed enforcement、租户配额、工具限流和执行指标。
**2026-05-31 DAG-aware execution dry-run 追加入地：**

- 当前前沿 Agent 平台正在把“工具调用”从一次性函数调用演进为可观察、可审批、可恢复的行动流水线。LangGraph 文档强调持久化 checkpoint 能支撑 human-in-the-loop、time travel debugging 和 fault-tolerant execution；OpenAI Agents SDK 文档也把 tool calls、handoffs、guardrails 和 custom events 纳入 trace。DataSmart 本阶段新增 DAG execution dry-run，正是把 Agent 行动先变成可解释预案，再进入受控执行。
- dry-run 与 preview 的职责分离很重要：preview 解释全量 DAG 节点当前状态，dry-run 解释“本次选择的节点准备走哪条执行入口”。这比直接让模型输出 `targetEndpoint` 后由系统调用更接近商业级 Agent：模型负责提出计划，控制面负责选择入口、批量上限、权限、审计和副作用边界。
- MCP 工具生态强调工具可发现和可调用，但真正落地到企业内部系统时，还需要额外的权限、审计、幂等、重放、限流和人类确认层。DataSmart 的 dry-run 不写 outbox、不投递 Kafka、不创建任务，保留了“工具协议层”和“企业执行治理层”的边界。
- 下一步应把 dry-run 结果进入 runtime event/WebSocket，并让智能网关展示“Agent 即将执行哪些节点、为什么阻断、需要谁确认”。这比立即做高并发 DAG worker 更稳，因为真实自动化前用户必须先信任行动路径。
- 参考资料：LangGraph Persistence / checkpointing：`https://docs.langchain.com/oss/python/langgraph/persistence`；LangGraph human-in-the-loop：`https://docs.langchain.com/oss/python/langgraph/human-in-the-loop`；OpenAI Agents SDK tracing：`https://openai.github.io/openai-agents-js/guides/tracing`；MCP Tools specification：`https://modelcontextprotocol.io/specification/2025-06-18/server/tools`。

**2026-05-31 DAG dry-run runtime event 摘要追加入地：**

- 当前前沿 Agent 产品的关键体验不是单纯“模型能调工具”，而是工具行动必须能被观察、回放、审批和补偿。OpenAI Agents SDK tracing 文档把 tool calls、handoffs、guardrails 和 custom events 纳入 trace；LangGraph persistence 文档强调 checkpoint 支撑 human-in-the-loop、time travel debugging 与 fault-tolerant execution；MCP tools 规范也明确工具调用需要 trust & safety 和人类介入能力。DataSmart 本阶段把 DAG dry-run 结果写入 runtime event，正是在补“行动预案可见性”这一层。
- 本阶段没有把 dry-run 事件设计成完整工具日志，而是只写摘要：选择器、计数、actionCounts、节点安全摘要和 payload 策略。这样做是为了避免把 SQL、密钥引用、业务参数、样本数据或工具结果扩散到事件流，继续坚持企业级 Agent 的最小暴露原则。
- `agent.dag_execution.dry_run.completed` 事件把“本次 Agent 准备如何推进 DAG”变成可查询事实，为智能网关动作审批面板、WebSocket replay、审计回放和二轮推理反馈奠定基础。
- 当前仍不直接进入真实 DAG worker，是刻意的节奏控制：先让行动透明，再补授权和确认，再进入可恢复执行。否则自动化能力越强，越容易形成用户看不见、审计追不回的黑盒副作用。
- 下一步趋势跟进重点应落在三条线上：一是 WebSocket/replay 的人机可见性；二是 permission-admin 的服务账号委托授权；三是 selected-node outbox dispatcher 与 checkpoint/replay。三者合在一起，才接近 Codex/Claude Code 类 Agent 的“可解释计划 -> 受控工具调用 -> 可恢复后台行动”闭环。
- 参考资料：LangGraph Persistence / checkpointing：`https://docs.langchain.com/oss/python/langgraph/persistence`；LangGraph fault tolerance：`https://docs.langchain.com/oss/python/langgraph/timeout-and-error-handling`；OpenAI Agents SDK tracing：`https://openai.github.io/openai-agents-js/guides/tracing`；MCP Tools specification：`https://modelcontextprotocol.io/specification/2024-11-05/server/tools`。

**2026-05-31 runtime event display 展示解释层追加入地：**

- 当前 Agent 工程趋势正在从“系统内部有 trace”升级为“用户可理解的行动时间线”。OpenAI Agents SDK tracing 强调完整记录 tool calls、handoffs、guardrails 与 custom events；LangGraph 的 checkpoint/replay 思路强调状态可以恢复、可以人工介入；MCP tools 规范强调工具调用需要 human-in-the-loop。DataSmart 本阶段在 runtime event 查询响应中加入 `display`，就是把机器 trace 向人类可读行动时间线推进。
- 这一步刻意没有直接做复杂 WebSocket，因为通道不是最难的，最容易长期变乱的是“事件应该如何被解释”。先固定 category/title/status/replayPolicy/recommendedActions，可以让未来 HTTP replay、WebSocket replay、审计导出和智能网关审批面板共用同一套语义。
- `display` 在权限脱敏之后生成，这一点非常关键。成熟 Agent 产品不能出现 attributes 已脱敏、但 UI 摘要又泄露 prompt、SQL、payload 或工具参数的情况。
- dry-run display 把同步候选、异步预案、阻断项、未命中 selector 与批量上限变成低风险指标，帮助用户先信任行动路径，再进入授权确认和后台执行。
- 下一步应进入 WebSocket/replay 最小通道，但仍要保持节奏：只做 session/run 订阅、afterSequence 增量回放和 ack cursor，不急着做复杂 UI；同时继续推进 permission-admin 服务账号委托授权和 selected-node outbox dispatcher。
- 参考资料：OpenAI Agents SDK tracing：`https://openai.github.io/openai-agents-python/tracing/`；OpenAI Agents SDK running agents tracing/sensitive data：`https://openai.github.io/openai-agents-python/running_agents/`；LangGraph human-in-the-loop：`https://docs.langchain.com/oss/python/langgraph/human-in-the-loop`；MCP Tools specification：`https://modelcontextprotocol.io/specification/2024-11-05/server/tools`。

**2026-05-31 runtime event replay 与 ack cursor 契约追加入地：**

- 当前 Agent 工程趋势不是只把事件实时推给前端，而是要求事件、状态、工具行动和审批点都能恢复、回放、确认和排障。OpenAI Agents SDK tracing 文档强调 Agent run 中的 LLM generation、tool call、handoff、guardrail 与 custom event 都应进入 trace；LangGraph persistence 文档强调 checkpoint 支撑 human-in-the-loop、memory、time travel 和 fault-tolerant execution；LangGraph streaming 文档也把 updates、custom、checkpoints、tasks、debug 等多种流模式作为运行时可见性能力；MCP tools 规范强调工具调用需要清晰 UI、调用提示和 human-in-the-loop。
- DataSmart 本阶段新增 HTTP replay 与 ack cursor，是对这些趋势的工程化收敛：先让 Java 控制面成为可信 replay source，再让 WebSocket 或前端用 `clientId + run/session + replaySequence` 做恢复，而不是只依赖一次长连接不掉线。
- 这一步刻意不把 WebSocket 连接管理塞进 Java，因为 gateway 目前已经有 `/api/agent/events/ws -> Python Runtime` 路由。更稳妥的路线是：Java 先提供 replay/ack 控制面，Python 桥接和未来 Java WebSocket 都复用它，避免两个实时通道各自定义 cursor。
- `ACK_EVENTS` 独立于 `VIEW_EVENTS` 体现了企业 Agent 的权限细分趋势：事件查看、消费确认、诊断、审批、执行和补偿应该逐步拆成独立动作，不能长期用一个泛化读权限覆盖所有行为。
- 下一步应把 Python Runtime WebSocket 桥接接入 Java replay/ack，并准备 Redis/MySQL cursor store、慢消费者诊断和 live push 协议。这样 DataSmart 的事件层才能从“能展示”继续走向“可恢复、可确认、可运营”。
- 参考资料：OpenAI Agents SDK tracing：`https://openai.github.io/openai-agents-python/tracing/`；LangGraph persistence：`https://docs.langchain.com/oss/python/langgraph/persistence`；LangGraph streaming：`https://docs.langchain.com/oss/python/langgraph/streaming`；MCP Tools specification：`https://modelcontextprotocol.io/specification/2025-06-18/server/tools`。

**2026-06-01 Python WebSocket 接入 Java replay/ack 追加入地：**

- 当前 Agent 平台的实时体验正在从“服务端持续推消息”升级为“可追踪、可恢复、可确认的运行时间线”。OpenAI Agents SDK tracing 文档强调一次 agent run 中 LLM generation、tool call、handoff、guardrail 和 custom event 都应进入 trace；LangGraph streaming 文档把 updates、custom、messages、checkpoints、tasks、debug 等模式作为运行可见性接口；LangGraph persistence 则强调 checkpoint 对 human-in-the-loop、memory、time travel 和 fault-tolerant execution 的支撑。
- DataSmart 本阶段让 Python `/agent/events/ws` 使用 Java 4.64 的 replay/ack 契约：subscribe/reconnect 可以按 Java source cursor 补拉控制面事件，ack/heartbeat 可以把 Java `replaySequence` 回写到 Java 控制面。
- 这一步的关键不是又加一个 WebSocket，而是统一两套坐标系：前端看到的是 envelope `lastSequence`，Java 控制面保存的是 `replaySequence`，未来 Redis Stream/Kafka 还会有自己的 offset。把这些放进 `sourceCursors`，比强行维护一个临时全局序号更稳。
- 外部 ack 失败不打断本地 ack，是贴近生产体验的折中：用户连接不能因为 Java cursor 短暂写失败就卡住，但诊断必须暴露 `externalAckErrors`，否则运维会以为消费位置已经可靠落库。
- 下一步趋势跟进应从“replay/ack 协议”进入“主动 live bridge 与持久 checkpoint”：Java runtime event 应通过 Kafka/bridge 进入 Python live hub，cursor store 应迁移到 Redis/MySQL，ack 失败应有重试与慢消费者指标。
- 参考资料：OpenAI Agents SDK tracing：`https://openai.github.io/openai-agents-python/tracing/`；LangGraph streaming：`https://docs.langchain.com/oss/python/langgraph/streaming`；LangGraph persistence：`https://docs.langchain.com/oss/python/langgraph/persistence`；MCP Tools specification：`https://modelcontextprotocol.io/specification/2025-06-18/server/tools`。

**2026-06-01 DAG selected-node outbox 确认入箱追加入地：**

- 当前 Agent 工具治理正在从“模型可调用工具”演进为“模型提出动作、控制面解释动作、用户或策略确认动作、后台可靠执行动作”。LangGraph 官方 human-in-the-loop 文档强调 interrupt 会暂停图执行、保存状态并等待外部输入；LangChain HITL middleware 也明确支持 approve、edit、reject 等决策。DataSmart 的 selected-node 入箱正是在 Java 控制面落地确认边界。
- MCP Tools specification 明确建议工具调用具备清晰 UI、调用可见性、敏感操作确认，同时要求服务端校验输入、实现访问控制和限流。DataSmart 本阶段没有让模型回传 `targetEndpoint`，而是只接收节点选择、指纹和确认标志，再由服务端按 auditId 重新读取可信路由，这与该安全原则一致。
- `selectionFingerprint` 不是为了做复杂密码协议，而是作为执行预案乐观锁：用户看到 dry-run 后，如果依赖、权限、审批、幂等声明或候选集合变化，旧确认必须失效。未来可以把它与 checkpoint、interrupt payload、审批记录和 runtime event timeline 关联。
- 整批拒绝是刻意的产品决策。面向普通用户的动作确认不应该悄悄部分成功；运营后台如果需要大批量修复，应单独提供带逐项结果、权限审批、配额和补偿能力的管理接口。
- gateway 已把推荐 selected-node 入箱与兼容 Run 级批量入箱拆成 `ENQUEUE_SELECTED_ASYNC_TOOL`、`ENQUEUE_RUN_ASYNC_TOOLS` 两个动作，避免真实副作用入口退化为通用 `CREATE`。下一步需要在 permission-admin 给两种动作配置不同角色、服务账号和审计策略。
- 下一步趋势跟进应进入 delegated authorization 与 durable execution policy：SERVICE_ACCOUNT 代表 actor 的委托授权、策略版本、租户配额、工具限流、并发池和积压保护，比继续扩展更多工具适配器更重要。
- 参考资料：LangGraph human-in-the-loop：`https://docs.langchain.com/oss/python/langgraph/human-in-the-loop`；LangChain HITL middleware：`https://docs.langchain.com/oss/python/langchain/human-in-the-loop`；MCP Tools specification：`https://modelcontextprotocol.io/specification/2025-06-18/server/tools`。
**2026-06-01 SERVICE_ACCOUNT 委托授权契约追加落地：**

- 当前前沿 Agent 工具链正在从“模型可以调用工具”演进为“模型提出动作，控制面按身份、权限、审批、审计和恢复策略托管动作”。MCP Tools specification 强调工具调用可见、输入校验、访问控制、限流和敏感操作确认；LangGraph / LangChain HITL 强调 interrupt、approve/edit/reject 与 checkpoint；OAuth 2.0 Token Exchange/RFC 8693 所代表的 delegated authorization 思路也说明生产系统需要明确“代表谁执行”。
- DataSmart 本阶段把这种趋势落到 `permission-admin + agent-runtime` 契约：Agent Runtime 不再只以 `SERVICE_ACCOUNT` 身份询问“能不能执行”，而是携带 `serviceAccountCode`、`representedActorId`、`delegationType` 和 `delegationReason`，让权限中心记录机器身份与上游主体之间的责任链。
- `policyVersion` 和 `delegationEvidence` 是后续 durable agent action 的关键拼图：dry-run、human confirmation、outbox enqueue、worker execute 和 runtime event timeline 都可以引用同一份授权证据，避免事后只看到“服务账号执行了某动作”，却无法解释用户、策略和确认链。
- 本阶段刻意没有把 SERVICE_ACCOUNT 设计为超级权限。它仍然必须命中具体 route policy，Run 级粗粒度入箱也默认拒绝服务账号，仅 selected-node 入口按 dry-run 指纹和选中节点收口开放。这符合企业 Agent 安全趋势：机器身份需要最小权限、可审计委托、可撤销策略，而不是全局万能 token。
- 下一步趋势跟进应聚焦四件事：确认记录持久化、策略版本执行前复核、租户/工具配额与限流、以及把 delegated authorization evidence 接入 runtime event / WebSocket 时间线。这样 DataSmart 的 Agent 能继续朝 Codex/Claude Code 类“可解释计划、受控工具、可恢复后台行动”的产品形态演进。
- 参考资料：MCP Tools specification：`https://modelcontextprotocol.io/specification/2025-06-18/server/tools`；LangGraph human-in-the-loop：`https://docs.langchain.com/oss/python/langgraph/human-in-the-loop`；LangChain HITL middleware：`https://docs.langchain.com/oss/python/langchain/human-in-the-loop`；OAuth 2.0 Token Exchange RFC 8693：`https://www.rfc-editor.org/rfc/rfc8693`。
**2026-06-01 selected-node 确认记录持久化契约追加落地：**

- 当前前沿 Agent 工具链正在把“工具调用”升级为“可暂停、可审批、可恢复、可审计的行动事务”。LangGraph human-in-the-loop 与 persistence/checkpointing 强调在执行图中暂停、保存状态并等待外部确认；MCP Tools specification 也强调工具调用可见性、敏感操作确认、输入校验、访问控制和限流。DataSmart 本阶段把 selected-node 确认事实独立持久化，正是把这些趋势转成企业数据治理产品里的执行证据层。
- 本阶段新增的 `confirmationId` 采用稳定摘要而不是随机 ID，这一点贴近 durable agent action 的工程需求：用户刷新、网关重试或 Python Runtime 重放同一确认时，系统应识别为同一确认事实，而不是把一次决策膨胀成多次人工审批。
- 确认记录刻意不保存 prompt、SQL、工具参数或样本数据，只保存 nodeId、auditId、outboxId、commandId 和治理边界。这延续了 Agent tracing 与 tool safety 的重要原则：trace 和审计必须足够可解释，但不能成为敏感上下文的二次扩散通道。
- 下一步应把 permission-admin 的 `policyVersion` 与 `delegationEvidence` 接入 confirmation，再在真实 worker 执行前复核策略版本。如果策略版本变化，就要求重新 dry-run/重新确认。这样才能把“human confirmation”从一次 UI 操作升级为可验证、可恢复、可撤销的企业执行契约。
- 参考资料：LangGraph human-in-the-loop：`https://docs.langchain.com/oss/python/langgraph/human-in-the-loop`；LangGraph persistence：`https://docs.langchain.com/oss/python/langgraph/persistence`；MCP Tools specification：`https://modelcontextprotocol.io/specification/2025-06-18/server/tools`。

**2026-06-01 selected-node durable action 同事务边界追加落地：**

- 当前 Codex、Claude Code、LangGraph HITL、MCP 工具调用等前沿 Agent 工程趋势都在强调“工具调用不是一次普通函数调用，而是可确认、可恢复、可审计的 durable action”。DataSmart 本阶段把 selected-node confirmation 与 async command outbox 在双 MySQL 配置下收敛到同一 JDBC 事务边界，正是把这一趋势落到企业数据治理场景中。
- 这一步没有追求分布式事务，而是选择本地事务 + outbox/inbox 的工程路线：模型或用户确认某批 DAG 节点后，Java 控制面先把 command 与 confirmation 作为同一服务内的事实提交，再由 dispatcher、Kafka、task-management Inbox 和 worker pre-check 承接跨服务可靠性。这比把 Kafka、下游任务和确认记录强塞进一个大事务更符合微服务演进。
- 事务边界刻意避开 dry-run 和 policyVersion 校验，只包裹真正产生副作用的两次写入。这个选择贴近高并发 Agent 产品的现实要求：预检可能被频繁刷新、重放和并发查看，而数据库事务应尽量短，避免连接池成为 Agent 行动确认链路的瓶颈。
- 下一步趋势跟进不应继续无限细化 selected-node 内部实现，而应走向三条商业化主线：confirmation 审计查询与权限动作、租户/工具配额与并发保护、worker 执行前二次复核。这样 DataSmart 的 Agent 能力才能从“能确认入箱”演进到“能被审计、能被限流、能被恢复、能被安全执行”。

**2026-06-01 selected-node confirmation 审计查询与权限动作追加落地：**

- 当前 Agent 工具调用的前沿方向不是只强调“模型能调工具”，而是强调工具行动必须可见、可确认、可恢复、可审计。DataSmart 本阶段把 selected-node confirmation 暴露为只读审计 API，并用 `VIEW_TOOL_CONFIRMATIONS` 独立权限保护，正是在把 durable action 证据从内部状态推进到产品可运营界面。
- confirmation 与 runtime event 被刻意拆成两类读模型：event 解释执行时间线，confirmation 解释 human-in-the-loop 确认事实和授权证据。这个拆分可以避免权限过粗，也方便未来智能网关、审计导出、管理员补偿台从不同角度复用同一证据。
- 查询 DTO 继续遵循 tool safety 原则：只返回 nodeId、auditId、outboxId、commandId、policyVersion、delegationEvidence 和范围字段，不返回工具参数、SQL、prompt 或样本数据。成熟 Agent 产品需要 trace 和审计，但不能让 trace 变成敏感上下文扩散通道。
- 下一步趋势跟进应转向“执行前复核 + 配额限流”：worker 在真正执行前必须检查确认是否存在、策略版本是否仍有效、服务账号委托是否匹配、租户/工具配额是否足够、outbox 是否重复消费。否则确认查询做得再好，也只能解释历史，不能防止未来高并发执行风险。

**2026-06-01 Agent outbox 入箱前容量保护追加落地：**

- 前沿 Agent 产品正在从“可以自动调用工具”走向“可以安全地持续调用工具”。安全不只包括权限和确认，也包括资源治理：一个 Agent loop 不能无限制造后台任务，单租户也不能占满全平台 worker。DataSmart 本阶段在 command outbox append 前增加 run/tenant backlog 保护，正是把工具调用治理从授权证据推进到执行容量。
- 这一步没有直接引入复杂分布式 quota-center，而是先在本服务内用 outbox store 做可验证保护：单次批量、单 run 活跃积压、单租户活跃积压。这个选择适合当前项目阶段，既能阻止明显的压力放大，又不会让 Java 控制面陷入过度平台化。
- 活跃积压只统计 PENDING/PUBLISHING/FAILED，体现了 outbox 状态机的工程语义：这些记录仍会消耗 dispatcher 和下游处理能力；PUBLISHED 已交给下游，BLOCKED/IGNORED 则进入人工治理，不应被混入自动执行压力。
- 下一步趋势跟进应把容量保护推到 worker 执行前：按 toolCode、targetService、tenant、project、priority 做并发池和租约，避免 data-sync 大任务、data-quality 扫描任务、导出任务和记忆写入任务互相抢资源。

**2026-06-02 长期记忆 workspace namespace 隔离追加落地：**

- 当前 Agent 记忆工程正在从“跨会话保存一些内容”升级为“按 namespace、key、权限和安全边界组织长期上下文”。LangChain 长期记忆文档说明其长期记忆基于 LangGraph store，以 namespace/key 组织 JSON 文档；LangGraph memory 文档也区分短期记忆和跨 session 的长期记忆。DataSmart 本阶段把 `workspaceKey/memoryNamespace` 贯穿候选、正式 store 和检索，就是把这一趋势转成企业数据治理场景里的可审计隔离边界。
- MCP Tools specification 继续强调工具调用的可见性、访问控制、限流和 human-in-the-loop。长期记忆本质上也是“工具结果对未来模型上下文的延迟注入”，因此它不能只靠项目 ID 粗过滤；候选写入、人工审批、正式落成和后续召回都必须携带同一份 workspace 证据。
- 本阶段选择 fail-closed 处理历史空 workspace 候选：没有命名空间证据就不落成正式记忆。这个策略比自动猜测更适合商业化产品，因为一旦错误记忆进入模型上下文，后续 Agent 可能在任务创建、数据同步、质量规则生成或权限审批中持续复用错误经验。
- 下一步趋势跟进应补 materialization receipt/outbox 和向量库 metadata filter。长期记忆不只是 store.save，还需要记录 worker 尝试、失败原因、重试次数、耗时、命名空间、召回命中和遗忘结果，才能变成可运营的企业 Agent 记忆系统。
- 参考资料：LangChain long-term memory：`https://docs.langchain.com/oss/python/langchain/long-term-memory`；LangGraph memory：`https://docs.langchain.com/oss/javascript/langgraph/add-memory`；MCP Tools specification：`https://modelcontextprotocol.io/specification/2025-06-18/server/tools`。

**2026-06-02 长期记忆 materialization receipt 追加落地：**

- 前沿 Agent 记忆能力的生产化重点不是“能把内容存起来”这一句，而是能解释内容从哪里来、为什么允许存、哪个 worker 何时写入、失败后如何重试、后续如何遗忘。LangGraph persistence/checkpointing 强调可恢复执行和 time travel，MCP 工具规范强调工具调用需要可见、可控、可审计；长期记忆写入本质上是工具结果对未来模型上下文的延迟影响，因此同样需要执行证据。
- DataSmart 本阶段新增 materialization receipt，把 approval fact、materialization fact、retrieval fact 拆开。这个拆分比给候选 status 增加 `MATERIALIZED/FAILED` 更稳，因为审批台、worker 补偿台、审计导出和 observability 未来会有不同查询模型。
- receipt 的 `attemptCount/workerId/errorMessage` 是面向真实生产事故的字段：它能回答是 Chroma/MySQL 抖动、worker 重启、消息重复投递，还是候选本身不满足安全边界导致失败。
- 下一步不宜继续无限细化长期记忆内部。更有产品价值的方向是智能网关：把模型路由、工具调用预算、provider fallback、workspace 上下文预算、runtime event 和 memory retrieval 接到一个统一治理入口，让 Agent 能像 Codex/Claude Code 一样在受控边界里持续行动。
- 参考资料：LangGraph persistence：`https://docs.langchain.com/oss/python/langgraph/persistence`；LangChain long-term memory：`https://docs.langchain.com/oss/python/langchain/long-term-memory`；MCP Tools specification：`https://modelcontextprotocol.io/specification/2025-06-18/server/tools`。

**2026-06-02 智能网关工具调用预算守卫追加落地：**

- 当前前沿 Agent 产品越来越强调“模型可以提出工具调用，但平台必须在执行前做可见性、审批、限流、预算和审计”。OpenAI Agents SDK tracing 把 tool calls、handoffs、guardrails、custom events 纳入 trace；LangGraph human-in-the-loop 强调在敏感动作前暂停并等待人工决策；MCP tools 规范也要求工具调用具备清晰 UI、访问控制、输入校验和限流。DataSmart 本阶段新增 tool-call budget guard，就是把这些趋势转成智能网关的第一层准入策略。
- `ModelToolCallPlanner` 已经能校验工具是否存在、是否本轮可见、arguments 是否 JSON object、参数是否满足 schema；但这仍不足以应对商业化场景。一个合法工具调用集合仍然可能太多、太大、太高风险。budget guard 用 `maxProposedToolCalls/maxAutoExecutableToolCalls/maxHighRiskToolCalls/maxArgumentsBytes` 把容量与风险前置到模型工具调用阶段。
- 本阶段选择不直接改 Java gateway，也不把 guard 融入模型路由服务，是为了保持智能网关的职责分层：模型路由管 provider 与成本，工具 planner 管工具意图合法性，tool budget guard 管同轮动作规模。后续可以由统一 gateway response 汇总这三类治理结果。
- 下一步应把 guarded report 写入 runtime event，并接入 AgentOrchestrator 主流程。这样前端和审计能看到“模型提出 5 个工具，网关因预算只允许 3 个”的过程，而不是只看到最终少了几个工具。
- 参考资料：OpenAI Agents SDK tracing：`https://openai.github.io/openai-agents-python/tracing/`；LangGraph human-in-the-loop：`https://docs.langchain.com/oss/python/langgraph/human-in-the-loop`；MCP Tools specification：`https://modelcontextprotocol.io/specification/2025-06-18/server/tools`。

**2026-06-02 工具调用预算守卫接入 Agent 主链追加落地：**

- 前沿 Agent 工具治理的关键不只是“有 guardrail 组件”，而是 guardrail 必须位于真实执行链路上。DataSmart 本阶段把 tool-call budget guard 接入 `AgentModelIntentNode`，让 streaming 和 non-streaming tool_calls 都先经过预算守卫，再生成最终 ToolPlan。
- 这一步对 Codex/Claude Code 类体验很重要：用户需要看到模型的行动建议被平台治理过，而不是黑盒地消失。runtime event 现在会记录 `guard_model_tool_call_budget`，说明 proposed/accepted before/after、预算策略和阻断 issue codes。
- 当前仍复用 `MODEL_TOOL_CALL_REJECTED` 事件类型承载预算 guard summary，这是小步快跑的折中。后续更成熟的智能网关应新增独立事件类型和 gateway governance response，把模型路由、工具预算、缓存计划、记忆召回和 workspace namespace 放在同一张治理视图中。

**2026-06-02 智能网关统一治理摘要追加落地：**

- 前沿 Agent 平台的治理体验正在从“内部有多个 guardrail”走向“用户和控制面能看到统一治理解释”。OpenAI Agents SDK tracing 强调 tool calls、guardrails 和 handoffs 的可追踪性；LangGraph human-in-the-loop 强调动作暂停与恢复；MCP Tools specification 强调工具调用可见、输入校验、访问控制和限流。DataSmart 本阶段新增 `intelligentGatewayGovernance`，正是把这些分散治理事实合成一个响应视图。
- 该摘要不重新做决策，而是汇总已经发生的模型路由、工具预算、workspace 和记忆检索事实。这个边界很重要：API 展示层不应绕过模型网关、预算守卫、记忆检索器或 Java 控制面，避免“展示结果”和“真实治理结果”出现两套逻辑。
- 下一步更适合进入 Agent skill 能力或策略来源抽象：让 tool budget policy 可以来自租户套餐、项目等级、角色和实时 backlog，而不是长期停留在静态默认值。

**2026-06-02 工具调用预算策略来源抽象追加落地：**

- 当前前沿 Agent 工程趋势已经不满足于“模型能调用工具”，而是要求工具调用在 trace、guardrail、human-in-the-loop、访问控制和限流策略下运行。OpenAI Agents SDK tracing 把 tool calls、guardrails、handoffs 和 custom events 纳入端到端追踪；LangGraph interrupts/human-in-the-loop 强调敏感动作前的暂停、恢复和外部决策；MCP Tools specification 明确提到工具调用需要可见性、访问控制、输入校验和限流。DataSmart 本阶段把 `ModelToolCallBudgetPolicyProvider` 抽象出来，就是为了让预算不再是 Python 代码里的固定常量，而能逐步接入企业控制面策略。
- 这次落地选择“环境变量 + 请求变量”作为最小策略来源，并不是最终生产形态。它的价值在于先固定 provider 契约：部署级默认值由环境决定，本次请求可由可信上游控制面覆盖，非法配置自动忽略，预算执行仍由 guard 统一完成。后续替换为 Java permission-admin、tenant plan、Redis quota 或实时 backlog provider 时，不需要重写预算守卫算法。
- 商业化场景里，工具预算应该按租户套餐、项目等级、角色、workspace 敏感级别、工具成本权重、worker backlog 和审批压力动态变化。例如只读元数据分析可以允许更多低风险工具；数据同步、质量修复、权限变更、导出下载等高风险动作应收紧自动推进数量，并要求人工确认或服务间授权证据。
- 下一步趋势跟进应优先补独立 `MODEL_TOOL_CALL_BUDGET_GUARDED` 事件类型和 Java 策略中心接入，然后再推进 Agent skill 能力。这样 DataSmart 的 Agent 形态会更接近 Codex/Claude Code 类产品：模型可以提出行动，但平台用可追踪、可授权、可限流、可审计的智能网关决定行动边界。
- 参考资料：OpenAI Agents SDK tracing：`https://openai.github.io/openai-agents-python/tracing/`；LangGraph human-in-the-loop/interrupts：`https://docs.langchain.com/oss/python/langgraph/human-in-the-loop`；MCP Tools specification：`https://modelcontextprotocol.io/specification/2025-06-18/server/tools`。

**2026-06-02 工具调用预算治理事件一等化追加落地：**

- 前沿 Agent 平台的 trace 设计正在把工具调用、guardrail、handoff、人工确认和自定义事件拆成可检索的结构化事实，而不是混在一条普通日志里。DataSmart 本阶段新增 `MODEL_TOOL_CALL_BUDGET_GUARDED`，把“预算收缩动作”从普通 rejected 事件中拆出来，贴近这种可追踪、可回放、可审计的工程趋势。
- 这个拆分的产品意义很实际：模型候选因未知工具、不可见工具或非法 JSON 被拒绝，和模型候选本身合法但因租户/项目/角色/容量预算被收缩，是两类不同事实。前者更多指向模型工具生成质量或工具 schema 暴露问题，后者更多指向智能网关策略、容量保护和运营配额。
- 统一治理摘要仍兼容旧 stage，是为了让历史运行记录、旧 replay 事件和测试夹具在迁移期可读。商业化产品做事件契约演进时，向后兼容比“一次性干净重命名”更重要，因为审计回放、客户工单和运营看板通常会跨版本查询。
- 下一步应从两条线推进：短期接 Java permission-admin/tenant plan，让预算 policy 具备真实控制面来源；中期把该事件写入 Java replay/index，使前端治理卡片、告警规则、审计导出和运维指标都能直接消费 `model_tool_call_budget_guarded`。
- 参考资料：OpenAI Agents SDK tracing：`https://openai.github.io/openai-agents-python/tracing/`；LangGraph human-in-the-loop：`https://docs.langchain.com/oss/python/langgraph/human-in-the-loop`；MCP Tools specification：`https://modelcontextprotocol.io/specification/2025-06-18/server/tools`。

**2026-06-02 permission-admin Agent 工具预算策略控制面追加落地：**

- 前沿 Agent 平台的工具治理正在从“运行时内部 guardrail”走向“企业控制面可配置策略”。这意味着工具预算不应只在 Python Runtime 里以默认常量存在，而应逐步由租户套餐、项目等级、角色、workspace 风险、实时容量和审批压力共同决定。DataSmart 本阶段在 `permission-admin` 新增 Agent 工具预算策略评估接口，就是把智能网关预算从 Python 局部能力推向 Java 企业控制面的第一步。

**2026-06-02 Python Runtime 远程接入 permission-admin 工具预算策略：**

- DataSmart 本阶段把 Java 控制面的预算策略接回 Python Runtime：`JavaPermissionAdminToolBudgetPolicyClient` 负责 HTTP 契约，`RemoteThenLocalModelToolCallBudgetPolicyProvider` 负责远程优先和本地回退，`AgentModelIntentNode` 继续负责执行预算守卫。这个分层与当前主流 Agent 产品演进一致：模型运行时负责推理与工具候选，企业控制面负责策略、审计和容量保护。
- 这一步让“智能网关”开始具备真实跨服务治理闭环：同一个 Agent 请求在不同租户套餐、角色、workspace 风险或 worker backlog 下，可以得到不同 `toolCallBudget`，而不是所有环境共用一套默认阈值。它为后续服务间认证、策略版本、指标驱动降载、审计回放和前端治理卡片打基础。
- 当前仍应控制扩展节奏。预算策略已经具备远程来源后，下一步更适合进入 Agent skill 能力或 Java 业务 worker 串联，而不是继续只在预算阈值上无限细化。真正类 Codex/Claude Code 的能力还需要 skill registry、tool permission、memory、workspace、runtime event replay 和后台可恢复执行共同推进。
- 这次实现没有直接做数据库策略表，而是先用内存规则稳定 API 契约。这个顺序更稳：先让 gateway/agent-runtime/Python Runtime 都知道 `toolCallBudget` 如何由 Java 控制面生成，再把规则迁移到 tenant plan、策略发布版本、审计记录和缓存失效。否则过早建表容易把尚未稳定的策略维度固化成数据库债务。
- 策略维度同时包含 actorRole、tenantPlanCode、workspaceRiskLevel、workerBacklogLevel 和 requestedToolRiskLevel，体现了商业化 Agent 的关键现实：同一个模型工具调用，在普通用户、项目负责人、服务账号、平台管理员之间，在低风险 workspace 与高敏 workspace 之间，在 worker 空闲与积压之间，应该得到不同预算。
- 下一步趋势跟进应接 Python 远程 provider 或 gateway 注入链路，让 Java 评估结果真正进入 Python Runtime；再接入真实 worker backlog 和租户套餐表。完成这两步后，DataSmart 的智能网关会更接近 Codex/Claude Code 类产品的受控行动模型：模型提出动作，企业控制面决定预算、权限和恢复边界。
- 参考资料：OpenAI Agents SDK tracing：`https://openai.github.io/openai-agents-python/tracing/`；LangGraph human-in-the-loop：`https://docs.langchain.com/oss/python/langgraph/human-in-the-loop`；MCP Tools specification：`https://modelcontextprotocol.io/specification/2025-06-18/server/tools`。

**2026-06-02 Agent Skill 准入治理追加落地：**

- 前沿 Agent 工具正在从“函数调用列表”升级为“能力包 + 工具 + 记忆 + 权限 + 审计”的组合。Codex/Claude Code 类体验看起来像模型自主行动，但底层必须知道哪些 Skill 可以被当前用户、workspace、租户和风险策略启用。DataSmart 本阶段把 Skill 选择拆成语义命中与准入判断两步，正是为了避免“模型理解了需求”被误当成“平台允许执行能力”。
- `selected_skills/rejected_skills` 的拆分有明显产品价值：前端可以解释“已推荐启用质量规则设计 Skill”，也可以解释“任务创建 Skill 命中但因普通用户角色被拒绝”。这比简单不返回 Skill 更接近企业级 Agent 的治理体验，因为用户和管理员能看到被挡住的原因。
- 当前准入策略仍是 Python 本地基线，生产化方向应是 permission-admin 远程 evaluate、gateway 注入可信权限事实、runtime event 记录 Skill admission，以及 Skill Marketplace 的租户级启停和版本发布。不要急着堆更多默认 Skill；先把 Skill 能力包的权限、工具 schema 暴露、记忆依赖和审计闭环做稳。

**2026-06-02 Skill Admission 事件化与智能网关摘要追加落地：**

- Agent 的可解释性不能只停留在最终工具计划。真正商业化的 Agent 平台需要解释“为什么选择这个能力包、为什么另一个能力包被挡住”。DataSmart 本阶段新增 `SKILL_ADMISSION_EVALUATED` runtime event，并把 `skillAdmission` 纳入 `intelligentGatewayGovernance`，让 Skill 准入成为可订阅、可回放、可审计的一等事实。
- 这一步与当前 Agent 工程趋势一致：OpenAI Agents SDK tracing 强调 guardrails 与 tool calls 的 trace，LangGraph 强调 interrupt/human-in-the-loop 的状态可恢复，MCP 强调工具调用的访问控制和可见性。Skill admission 位于工具 schema 暴露之前，是比单个 tool call 更高一层的能力包级 guardrail。
- 下一步不宜继续扩大 Python 本地规则，而应设计 permission-admin Skill evaluate 契约，把 `actorRole/grantedPermissions/tenantSkillSwitch/policyVersion` 变成可信控制面事实。随后再补 Java replay/index 和前端治理卡片，使 Skill Marketplace 不只是“列表页”，而是可授权、可灰度、可审计的企业能力市场。

**2026-06-02 permission-admin Skill Admission evaluate 契约追加落地：**

- DataSmart 本阶段把 Skill admission 从 Python 本地 policy 推进到 Java permission-admin 控制面：`/permissions/agent/skill-admissions/evaluate` 能返回 allowed、admissionStatus、policyVersion、matchedPolicy、rejectionReason 和权限摘要。这让 Skill Marketplace 的关键安全问题先有统一控制面答案，而不是让 Python Runtime 和前端各自解释“为什么这个 Skill 能不能用”。
- 这与前沿 Agent 产品趋势一致：能力包不只是 prompt 或函数集合，而是带权限、记忆、工具 schema、风险等级和审计语义的组合资产。真正类 Codex/Claude Code 的企业形态，需要在模型看到工具之前，就完成 Skill 级准入。
- 当前实现仍是内存规则，刻意不先建表。下一步应把 Python Skill Registry 接入远程 provider，再补 gateway 可信注入、Java replay/index 和前端治理卡片。等字段语义稳定后，再建设租户 Skill 开关、Marketplace 版本发布、灰度与审计表。

**2026-06-02 Python Runtime 远程接入 permission-admin Skill Admission：**

- DataSmart 本阶段把 Skill admission 远程 provider 接入 Python Runtime：Skill 仍由 Python 根据意图语义命中，但准入结果可以来自 Java permission-admin。这使能力包治理从“Python 本地判断”升级为“企业控制面可替换策略”，更符合 Agent 工具/Skill 快速演化下的商业化边界。
- 该能力的架构价值在于分层：Skill Registry 负责适配目标，permission-admin 负责准入策略，runtime event 和 `intelligentGatewayGovernance.skillAdmission` 负责解释。这个模式比把权限规则直接写进 prompt 或工具 planner 更稳，也更适合后续接 Skill Marketplace、租户开关、策略版本和审计。
- 下一步重点不是继续加本地 Skill，而是补可信事实链路：gateway/agent-runtime 注入 `actorRole/grantedPermissions/tenantSkillEnabled/policyVersion`，远程调用增加服务间认证和 fail-closed 策略，再把 `policyVersion/matchedPolicy` 结构化进入 Skill selection 与 Java replay/index。
**2026-06-02 Skill Admission 可信控制面命名空间追加落地：**

- 企业 Agent 的准入事实不能和用户输入混放。DataSmart 新增 `trustedControlPlane.skillAdmission` 保留命名空间，远程 Skill admission 默认只从该命名空间读取角色、权限集合、租户开关和 workspace 风险。
- 这一步不是完整认证方案，而是 Python Runtime 的安全收口：普通 variables 不再能伪造管理员角色或扩大权限。下一步仍需由 gateway/agent-runtime 根据 JWT、服务账号和 permission-admin 结果注入可信快照，并用服务间认证保护内部调用。
- 该设计与工具调用、长期记忆和 runtime replay 的治理方向一致：模型和终端可以提出目标，但影响授权、隔离和执行边界的事实必须来自受控控制面，而不是 prompt 或客户端自报字段。
**2026-06-02 工具预算可信控制面命名空间追加落地：**

- 智能网关预算不是客户端偏好，而是资源治理策略。DataSmart 将远程 tool budget provider 迁移到 `trustedControlPlane.toolBudget`，避免终端自报管理员、企业套餐或空闲 backlog 来放宽自动执行额度。
- Skill admission 与 tool budget 现在共享保留根命名空间，但保持独立快照对象。这样后续可以分别演进权限版本、容量版本、审计事件和降载策略，而不是把所有控制面事实揉成一个难以治理的大字典。
- 下一步需要完成 gateway/agent-runtime 注入桥接：身份 Header 来自认证链路，权限集合来自 permission-admin，backlog 来自运行时指标或容量服务，Python Runtime 只消费受控快照。
**2026-06-02 Gateway Agent Plan 可信 Header 桥接追加落地：**

- DataSmart 新增 `/api/agent/plans` 专用 gateway 路由与 Python API 边界装配器。终端提交的 `trustedControlPlane` 会先被删除，只有 gateway 转发 Header 可以重建最小可信快照。
- 该分层保持了 OpenClaw 风格智能网关的职责：gateway 管统一入口与身份上下文，Python Runtime 管模型规划与 Skill/tool budget 消费，Java agent-runtime 管可审计执行控制面。
- 当前 Header 来源标记仍不是密码学认证。下一步应增加内部服务凭证，并把权限快照、策略版本、容量快照时间戳与过期策略纳入可信上下文。
**2026-06-02 Gateway 到 Python Runtime 签名信任链追加落地：**

- DataSmart 本阶段把 `/api/agent/plans` 的 gateway Header 桥接升级为 HMAC-SHA256 可验证信任链。Java gateway 在权限、开发期身份和数据范围 Header 都写入后，对可信 Header 快照生成版本、timestamp、nonce、keyId 和签名；Python Runtime 启用后会复算签名，拒绝只伪造 `X-DataSmart-Source-Service` 的直连请求。
- 这符合当前企业 Agent 网关演进方向：模型运行时不直接相信客户端自报身份，影响工具预算、Skill 准入、workspace 隔离和长期记忆写入的事实必须来自可验证控制面。它让 DataSmart 从“迁移期来源标记”迈向“智能网关服务间认证”的第一阶段。
- 当前签名保护 Header 快照而不是 body，是为了避免 Spring Cloud Gateway reactive 链路缓存请求体导致背压和大 payload 风险；请求体中的 `trustedControlPlane` 仍由 Python API 边界删除。后续更成熟形态应叠加 mTLS、Secret Manager 密钥轮换、Redis nonce 去重、服务网格策略和统一 401/403 异常映射。
**2026-06-02 Gateway 签名失败 API 错误语义追加落地：**

- DataSmart 本阶段继续收口智能网关服务间认证体验：Python Runtime 的 `/agent/plans` 在 gateway 签名失败时返回 401 + `GATEWAY_SIGNATURE_INVALID`，而不是让内部异常表现为 500。对企业 Agent 产品来说，这个细节很重要，因为 500 会误导运维去排查模型服务故障，而 401 能明确指向内部调用凭证、签名、时钟或网关路径问题。
- 路由层新增安全日志，但只记录 code、reason、traceId、sourceService 和 path，不记录密钥、签名值、签名原文或完整 Header。这个边界贴近真实安全运营：日志要足够定位误配置和攻击尝试，又不能成为新的凭证泄漏面。
- 下一步应把这类安全失败从日志升级为一等审计事件和指标：例如按 reason 统计 missing-signature、signature-mismatch、timestamp-out-of-window，接入 Prometheus/告警和 Java replay/index，帮助平台管理员区分攻击、时钟漂移、密钥不一致和灰度发布异常。
**2026-06-02 Gateway 签名 nonce 去重与安全诊断追加落地：**

- DataSmart 本阶段把 gateway HMAC 签名链补上 nonce 短 TTL 去重。HMAC 能证明请求来源，但不能单独阻止窗口期重放；现在同一个 keyId + nonce 只能登记一次，重复请求会得到 `nonce-replayed`，这让智能网关信任链具备第一层防重放能力。
- 工程上保持轻依赖：本地默认进程内 store，生产多实例显式切 Redis，并通过 `SET NX EX` 原子语义共享 nonce。这个选择符合企业 Agent 平台渐进式落地：开发体验不被外部中间件绑死，生产安全边界又能通过配置升级。
- 新增安全诊断快照，把签名失败 reason 聚合为进程内统计，展示 nonce store 类型、TTL 和集群安全提示。后续应继续把这些 reason 指标接入 Prometheus、告警和 Java 审计链路，而不是长期停留在 Python 进程内快照。

**2026-06-02 Agent 正式长期记忆 SQL Store 追加落地：**

- 当前 Agent 长期记忆趋势正在从“把聊天历史塞回上下文”走向“跨会话、跨任务、按 namespace 隔离、可搜索、可更新、可过期的记忆资产”。LangChain/LangGraph 长期记忆文档强调 long-term memory 会跨 conversations/sessions 保留，并通过 namespace/key 组织；OpenAI Agents sandbox memory 文档也把 memory 与会话消息历史区分开，强调从历史运行中沉淀经验、偏好和可用记忆。
- DataSmart 本阶段新增 `SqlAgentMemoryStore`，不是为了把 SQL 当成最终语义检索引擎，而是为了给长期记忆建立可审计事实源：候选审批通过后，低敏摘要、workspace namespace、幂等键、来源候选、过期时间和 materializedAt 必须落到可恢复存储。向量库、图谱和对象存储后续可以围绕同一 `memoryId` 做二级索引，但不能替代控制面事实。
- namespace 隔离是商业化 Agent 长期记忆的关键。一个企业租户内可能同时存在项目默认空间、客户现场专题空间、临时诊断 session、敏感质量治理空间和跨项目知识空间。如果没有 `memoryNamespace` fail-closed，模型很容易把 A 工作空间的治理经验错误注入 B 工作空间。
- 这一步也延续了 Agent tracing 与敏感数据治理原则：正式记忆表只保存低敏 `contentSummary`，不保存完整工具输出、样本数据、原始 SQL、文件正文或敏感日志。长期记忆要能帮助未来 run 降低探索成本，但不能成为敏感上下文的二次扩散仓库。
- 下一步趋势跟进不应只是接向量库，而是建立“记忆写入后台 worker -> SQL receipt/outbox -> 二级索引 -> namespace 检索 -> 过期/遗忘 -> 审计导出”的完整闭环。这样 DataSmart 才能从“能记住一点东西”走向 Codex/Claude Code 类 Agent 所需要的可恢复经验层。
- 参考资料：LangChain long-term memory：`https://docs.langchain.com/oss/python/langchain/long-term-memory`；LangGraph memory overview：`https://docs.langchain.com/oss/javascript/langgraph/memory`；OpenAI Agents sandbox memory：`https://openai.github.io/openai-agents-python/sandbox/memory/`；OpenAI Agents tracing：`https://openai.github.io/openai-agents-python/tracing/`。

**2026-06-03 Agent 正式长期记忆 Runtime Builder 追加落地：**

- LangChain/LangGraph 的长期记忆文档把 memory store 作为 Agent 运行时可读写组件，而不是单独存在的离线表；OpenAI Agents sandbox memory 也强调 memory 与 conversational session memory 分离，用于让未来 run 减少重复探索。DataSmart 本阶段把正式记忆 store 注入 `StoreBackedAgentMemoryRetriever`，让 `/agent/plans` 真正消费正式 store，而不是只停留在建表和持久化类。
- 本阶段新增 runtime builder 的意义在于“部署形态可替换”：本地可以继续 in-memory，联调可以 SQLite，生产可以 MySQL，并用 fail-open/fail-fast 控制开发体验与生产安全之间的取舍。这与成熟 Agent 平台的渐进式落地一致：能力默认可学习、生产可收紧、故障可诊断。
- 共享 `memory_sql_connection.py` 也是一个重要工程信号。长期记忆后续会有候选 store、正式 store、receipt store、outbox store、二级索引同步任务，如果每个组件各自解析 DSN 和脱敏密码，后续接 Secret Manager、TLS、连接池或 PostgreSQL 时会快速失控。
- 新增 `/agent/memory/diagnostics` 把候选 store、正式 store、retriever 和 materializer 放在同一张低敏诊断视图中，帮助运维判断“配置的 MySQL 是否真的参与召回”。当前不做 schema 探测，是为了避免诊断接口引入额外数据库副作用；后续可以做管理员受控 health check。
- 下一步趋势跟进应进入后台 materialization worker：长期记忆不能只靠同步调用落成，必须具备 outbox、租约、失败退避、DLQ、补偿重放和指标。完成后再接向量库/图谱二级索引，才接近 Codex/Claude Code 类 Agent 的可恢复经验层。
- 参考资料：LangChain long-term memory：`https://docs.langchain.com/oss/python/langchain/long-term-memory`；OpenAI Agents sandbox memory：`https://openai.github.io/openai-agents-python/sandbox/memory/`；LangGraph/Deep Agents memory：`https://docs.langchain.com/oss/python/deepagents/long-term-memory`。

**2026-06-03 长期记忆落成 Receipt SQL Store 追加落地：**

- 当前 Agent 工程趋势越来越强调 durable action：模型提出动作只是开始，真正生产化还需要执行证据、重试、补偿、审计和可观测性。长期记忆写入也是一种 durable action，不能只在内存里“写过就算”；DataSmart 本阶段把 materialization receipt 推进到 SQL store，就是给长期记忆后台写入建立执行证据。
- 这一步把三类事实拆开：candidate 表示审批决策，formal memory 表示可召回经验，receipt 表示写入尝试。这个拆分贴近成熟 Agent 平台的控制面设计：审批、执行、结果三件事各有状态机，避免一个 status 字段同时承担多种含义。
- receipt store 的 attempt_count、worker_id、status、error_message 是后续 outbox worker 的基本观测面。没有这些字段，平台无法回答“为什么这条记忆没写进去”“是不是重复消费”“哪个 worker 一直失败”“是否需要管理员补偿”。
- 本阶段刻意没有直接启动 worker，是为了保持节奏：先把执行证据持久化，再做有界扫描和租约，再做失败退避和 DLQ，最后再接向量库二级索引。否则向量库先上线、写入链路不可靠，会让长期记忆出现难以补偿的幽灵状态。
- 下一步趋势跟进应做最小 materialization runner：从 APPROVED 候选中有界取数，调用 materializer，receipt 记录成功/失败，并输出低敏指标。之后再扩展租约、多实例竞争控制、管理员补偿和审计导出。
- 参考资料：LangChain long-term memory：`https://docs.langchain.com/oss/python/langchain/long-term-memory`；OpenAI Agents tracing：`https://openai.github.io/openai-agents-python/tracing/`；LangGraph persistence：`https://docs.langchain.com/oss/python/langgraph/persistence`。

**2026-06-03 长期记忆最小 Materialization Runner 追加落地：**

- 当前 Agent 工程中的 durable action 不只是“状态持久化”，还要求可恢复执行：动作被批准后，需要有一个可重复运行、失败可隔离、结果可观测的执行器。DataSmart 本阶段新增最小 materialization runner，把 APPROVED 候选从静态审批事实推进到可批次落成的执行事实。
- Runner 的设计刻意采用有界窗口和至少一次语义：每轮只取有限数量候选，重复执行依赖 formal memory store 的幂等键与 receipt store 的 attempt_count 证明。这与 LangGraph durable execution/persistence 强调的“重放时不重复副作用”方向一致，也与 OpenAI Agents tracing 对 tool/action 过程可追踪的趋势一致。
- 单条失败不阻塞同批其他候选，是商业化 Agent 的必要行为。真实环境中坏候选、历史配置缺字段、外部存储抖动都很常见；如果 worker 因一条数据失败而整体中断，长期记忆会产生隐性 backlog，用户会感觉 agent “没有学习能力”。
- 当前没有自动后台循环，是有意的产品节奏控制。下一步应先补 lease/outbox claim、失败退避、DLQ、管理员补偿和指标，再考虑自动调度。否则多实例环境下可能重复抢同一候选，反而让长期记忆链路不可解释。
- 参考资料：LangGraph durable execution：`https://docs.langchain.com/oss/python/langgraph/durable-execution`；LangChain long-term memory：`https://docs.langchain.com/oss/python/langchain/long-term-memory`；OpenAI Agents tracing：`https://openai.github.io/openai-agents-python/tracing/`。

**2026-06-03 长期记忆 Materialization Lease Store 与 token fencing 追加落地：**

- DataSmart 本阶段把长期记忆落成从“有界 Runner 可用”推进到“多 worker 领取语义可验证”。这与当前 Agent 平台的 durable action 趋势一致：长期记忆写入、工具副作用、异步任务和二级索引同步都不能只靠“调用过一次”证明成功，而要有 claim、receipt、幂等键、审计和补偿事实。
- 独立 lease store 的意义在于分层清晰：candidate 负责审批，formal memory 负责可召回经验，receipt 负责执行证据，lease 负责短时并发控制。把这些事实拆开，后续才能分别演进审批台、补偿台、Prometheus 指标、DLQ、管理员重放和向量/图谱二级索引，而不是把所有语义塞进一个候选状态字段。
- token fencing 是多实例 worker 的关键保护。慢 worker、旧 worker 或网络抖动后的迟到回写，如果没有 token 条件更新，很容易覆盖新 worker 的结果。现在 `succeed/fail` 都必须携带当前领取 token，过期 worker 会被拒绝，这让长期记忆链路更接近真实生产队列的安全语义。
- 当前 SQL 实现优先采用跨 SQLite/MySQL 可验证的条件 UPDATE + INSERT 冲突恢复；高吞吐阶段可以再按压测结果引入 MySQL `SELECT ... FOR UPDATE SKIP LOCKED` 风格的批量 claim。也就是说，本阶段先保证语义正确和测试可覆盖，再做数据库专用性能优化。
- 下一步趋势跟进不应急着启动常驻后台线程，而应先补失败退避、最大尝试次数、DLQ、管理员补偿重放和指标。等“坏候选不会热循环、好候选不会重复消费、失败原因可观测”之后，再把 Runner 包成后台 worker，并接 Chroma/Neo4j 二级索引同步。
- 参考资料：LangGraph durable execution：`https://docs.langchain.com/oss/python/langgraph/durable-execution`；LangChain long-term memory：`https://docs.langchain.com/oss/python/langchain/long-term-memory`；MySQL locking reads：`https://dev.mysql.com/doc/refman/8.0/en/innodb-locking-reads.html`；OpenAI Agents tracing：`https://openai.github.io/openai-agents-python/tracing/`。

**2026-06-03 长期记忆失败退避与 DLQ 基础语义追加落地：**

- 成熟 Agent 平台的 durable action 不只是“能重试”，而是要能控制重试节奏、隔离毒性任务、解释跳过原因并给管理员补偿入口。DataSmart 本阶段把长期记忆 materialization 失败从“立即可重试”升级为 `nextRetryAt` 冷却窗口与 `dead_letter` 终态，避免坏候选在多 worker 环境里热循环。
- 这一步让长期记忆链路更接近真实生产队列治理：失败不再只是日志，而会变成可查询、可统计、可告警的状态事实。`retry_cooldown`、`dead_letter`、`active_lease`、`already_succeeded` 等跳过原因，后续可以直接映射到 Prometheus 指标、runtime event 和管理员控制台。
- DLQ 对企业 Agent 尤其重要。工具输出可能缺字段，历史候选可能来自旧版本 schema，下游向量库或图谱可能暂时不可用；如果没有 DLQ，自动 worker 会把这些异常伪装成“系统一直很忙”，用户和运维很难判断 agent 到底是没学会、没权限、没写入，还是一直在失败重试。
- 当前退避策略采用简单指数退避，是刻意的最小闭环：先让行为稳定、可解释、可测试，再引入更复杂的错误分类、租户套餐、下游容量和维护窗口策略。对 DataSmart 来说，下一步不是马上启动常驻 worker，而是把 DLQ 查询、dry-run 重放、解除 dead_letter 和补偿审计接起来。
- 参考资料：LangGraph durable execution：`https://docs.langchain.com/oss/python/langgraph/durable-execution`；LangGraph persistence：`https://docs.langchain.com/oss/python/langgraph/persistence`；OpenAI Agents tracing：`https://openai.github.io/openai-agents-python/tracing/`；MySQL locking reads：`https://dev.mysql.com/doc/refman/8.0/en/innodb-locking-reads.html`。
