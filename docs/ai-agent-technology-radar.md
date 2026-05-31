# DataSmart Govern AI Agent 技术雷达

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
