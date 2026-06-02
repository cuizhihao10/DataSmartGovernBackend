# DataSmart Govern Python AI Runtime

这个目录是 DataSmart Govern 的 Python 智能运行时初始骨架，定位不是替代 Java 微服务，而是承接后续 `Agent 编排`、`模型路由`、`RAG/GraphRAG 检索`、`工具计划生成`、`OpenClaw/LangGraph 风格状态流转` 等 AI 能力。

## 当前边界

- Java 微服务继续负责权限、任务、数据源、数据同步、质量治理等可审计业务控制面。
- Python AI Runtime 负责“如何理解用户目标、选择模型、组织上下文、规划工具调用、识别是否需要人工审批”。
- 两层之间后续优先通过 HTTP/Kafka/gRPC 等明确契约交互，避免 Python 层直接访问 Java 模块内部数据库导致耦合过高。

## 当前已落地能力

- `ModelRouteRegistry`：按工作负载选择模型路由，避免业务代码写死某个模型名称。
- `ToolPlanner`：根据目标、变量和工具注册表生成工具计划，先采用可解释的规则式骨架，后续可替换为 LLM 规划器。
- `AgentOrchestrator`：以状态节点方式串联目标接收、模型选择、上下文构建、工具规划、审批判断和响应生成。
- Agent Skill 治理：已具备本地/远程 Skill descriptor、语义选择、准入策略、准入 runtime event 和智能网关摘要；Java `permission-admin` 已新增 Skill admission evaluate 契约，Python Runtime 已支持可选远程调用。Skill 命中后会继续校验 `grantedPermissions`、`actorRole` 与风险等级；显式缺权限或普通用户命中高风险 Skill 时会进入 `rejectedSkills`，缺少控制面事实时只做条件性推荐。`SKILL_ADMISSION_EVALUATED` 事件与 `intelligentGatewayGovernance.skillAdmission` 会解释 Skill 启用、条件性启用或拒绝原因。
- 智能网关工具治理：已具备模型工具调用候选规划、可见工具校验、参数 schema 校验和工具调用预算守卫，可限制单轮工具数量、自动推进数量、高风险工具数量和 arguments 体积；预算策略已抽象为 provider，当前支持环境变量、`AgentRequest.variables["toolCallBudget"]` 覆盖，以及可选远程调用 Java permission-admin `/permissions/agent/tool-budget-policies/evaluate`。远程策略默认关闭，适合生产或联调环境按租户套餐、项目等级、角色、workspace 风险和实时 backlog 动态生成预算；预算阻断会写入独立 `MODEL_TOOL_CALL_BUDGET_GUARDED` runtime event，API 响应已提供 `intelligentGatewayGovernance` 统一摘要，汇总模型路由、工具预算、workspace 和记忆检索治理事实。
- 长期记忆治理：已具备记忆召回计划、候选生成、审批/拒绝、候选 SQL store、低敏摘要正式落成、materialization receipt、正式记忆 SQL store、正式记忆 runtime builder 和 store-backed 检索接入；候选和正式记忆都会携带 `workspaceKey/memoryNamespace`，检索时按当前 Agent 工作空间过滤，避免同项目不同 workspace 或 session 沙箱误共享记忆。当前默认装配仍以本地内存 store 便于学习和单测；生产可通过 `DATASMART_AI_FORMAL_MEMORY_*` 切到 MySQL，让 `/agent/plans` 从正式记忆表召回低敏经验；后续再围绕同一 `memoryId` 接入 Chroma、Neo4j、MinIO 和对象索引。
- `api.create_app()`：提供可选 FastAPI 入口。当前测试不依赖 FastAPI，安装 API 依赖后即可启动服务。
- Agent API 路由已从 bootstrap 入口拆到 `api_agent_routes.py`：`api.py` 只负责装配模型网关、事件组件、长期记忆候选治理和 Java 控制面客户端；`/agent/plans`、事件 replay/control 与 WebSocket handler 由独立注册函数承载。这样后续继续增加服务间认证、智能网关会话、审计导出和长期记忆上下文注入时，不会把启动文件拖成难以维护的巨型模块。
- 目录层级治理已开始落地：长期记忆相关服务已迁入 `services/memory/`，实时事件流相关服务已迁入 `services/runtime_events/`，模型路由/provider/预算/tool-call 相关服务已迁入 `services/model_gateway/`，并新增 [Python AI Runtime 目录层级治理规范](../docs/python-ai-runtime-package-layout.md)。后续新增功能应优先进入 `agent/`、`memory/`、`model_gateway/`、`runtime_events/`、`tools/`、`skills/` 等能力包，而不是继续把十几个文件散放在同一个目录。

## 为什么先做这个骨架

商业化的数据治理平台不能把智能能力写成“一个 prompt 调一个模型”的 demo。真实产品里至少要提前留出这些扩展点：

- 模型会变化：主推理模型、代码模型、多模态模型、Embedding、Reranker 应按能力拆开。
- 场景会变多：数据源分析、质量规则生成、任务创建、权限审批、合规解释都可能走不同模型与工具链。
- 风险要分级：只读分析可以自动执行，高风险变更必须进入人工审批。
- 工具要可注册：Java 控制面的能力应作为工具被 Agent 调用，而不是由 Agent 随意拼接内部逻辑。
- 状态要可观测：每个 Agent 节点都应能被记录、追踪和排错。

## 本地验证

```powershell
python -m compileall python-ai-runtime/src python-ai-runtime/tests
python -m unittest discover -s python-ai-runtime/tests
```

## 可选远程工具预算策略

默认情况下，Python Runtime 使用本地环境变量和请求变量生成工具预算，便于学习、单测和离线开发。如果希望让预算策略来自 Java permission-admin 控制面，可启用以下环境变量：

```powershell
$env:DATASMART_PERMISSION_ADMIN_TOOL_BUDGET_ENABLED="true"
$env:DATASMART_PERMISSION_ADMIN_BASE_URL="http://localhost:8085"
$env:DATASMART_PERMISSION_ADMIN_TOOL_BUDGET_TIMEOUT_SECONDS="3"
```

启用后，`build_default_orchestrator()` 会优先请求 permission-admin，并把 Java 返回的 `toolCallBudget` 转为 Python `ModelToolCallBudgetPolicy`。远程调用失败时默认回退到本地策略，保证本地开发和灰度联调不断流；生产环境如果希望策略中心故障显式暴露，可在代码注入时关闭 `allow_remote_tool_budget_policy_fallback`，形成 fail-closed 治理。

## 可选远程 Skill 准入策略

默认情况下，Python Runtime 使用本地 `AgentSkillAdmissionPolicy` 完成 Skill 准入，便于离线学习和单元测试。如果希望让 Skill 准入来自 Java permission-admin 控制面，可启用以下环境变量：

```powershell
$env:DATASMART_PERMISSION_ADMIN_SKILL_ADMISSION_ENABLED="true"
$env:DATASMART_PERMISSION_ADMIN_BASE_URL="http://localhost:8085"
$env:DATASMART_PERMISSION_ADMIN_SKILL_ADMISSION_TIMEOUT_SECONDS="3"
```

启用后，`build_default_orchestrator()` 会在 Skill 语义命中后，优先请求 permission-admin `/permissions/agent/skill-admissions/evaluate`，并把 Java 返回的 `allowed/admissionStatus/rejectionReason/policyVersion` 转成 Python `AgentSkillAdmissionDecision`。远程失败默认回退本地策略；生产环境如果希望 fail-closed，可在代码注入时关闭 `allow_remote_skill_admission_fallback`。

## 下一步建议

- 接入 Java `agent-runtime` 的工具注册表接口，让 Python 层从真实注册表获取工具定义。
- 为远程 Skill 准入补 gateway 可信注入、服务间认证、策略版本写入 runtime event、租户级 Skill 启停和前端 Skill 治理卡片。
- 将规则式 `ToolPlanner` 抽象为策略接口，增加 LLM 规划器实现。
- 增加 RAG/GraphRAG 上下文构建器，区分元数据检索、权限事实检索、质量规则案例检索。
- 增加模型 Provider 适配器，优先兼容 OpenAI-compatible、vLLM、SGLang 等部署形态。
- 将 permission-admin 工具预算策略继续升级为数据库策略表、租户套餐版本、worker backlog 指标源和策略发布版本，而不是长期停留在内存规则。
- 为正式长期记忆增加 SQL receipt store、后台 outbox worker、失败重试、补偿查询和向量库 namespace 过滤适配器。
- 远程 Skill 准入请求中的角色、权限集合、租户开关和 workspace 风险现在只从
  `variables["trustedControlPlane"]["skillAdmission"]` 保留命名空间读取。普通终端变量即使伪造
  `actorRole` 或 `grantedPermissions` 也不会被当成可信控制面事实。该命名空间仍需要由 gateway 或
  agent-runtime 在受控内部链路中注入；生产环境不能直接暴露 Python Runtime 入口，也不能允许外部客户端
  自行提交 `trustedControlPlane`。迁移期如需兼容旧联调夹具，可以显式开启
  `allow_legacy_request_variables`，但不应在生产启用。
# 4.96 远程工具预算可信上下文

远程 permission-admin 工具预算评估现在只从
`variables["trustedControlPlane"]["toolBudget"]` 读取角色、套餐、workspace 风险、worker backlog 和工具风险。
普通终端 variables 即使伪造管理员角色、企业套餐或空闲队列，也不会污染远程预算策略请求。

本地 `EnvAndRequestModelToolCallBudgetPolicyProvider` 仍保留 request override，便于离线开发、测试和灰度联调；
远程 `JavaPermissionAdminToolBudgetPolicyClient` 则默认采用更严格边界。迁移期可以显式开启
`allow_legacy_request_variables`，生产环境不应启用。
# 4.97 Gateway Agent Plan 可信 Header 桥接

gateway 新增 `/api/agent/plans` 专用路由，将 Agent 规划请求转发给 Python Runtime `/agent/plans`。
Python API 边界新增 `enrich_agent_plan_payload_from_gateway_headers()`：先删除请求体中任何
`trustedControlPlane`，再仅在 `X-DataSmart-Source-Service=datasmart-govern-gateway` 时，根据 gateway
已清理并重建的 `X-DataSmart-*` Header 生成最小可信快照。

该桥接是迁移期边界，不是完整服务间认证。生产环境仍必须禁止终端直连 Python Runtime，并继续补充
服务账号 Token、签名或 mTLS；权限集合、租户 Skill 开关、策略版本和 worker backlog 也应由 Java
控制面继续注入，而不是由终端提交。

# 4.98 Gateway 到 Python Runtime 签名信任链

`/agent/plans` 现在支持校验 Java gateway 生成的 HMAC-SHA256 内部签名。gateway 会在转发
`/api/agent/plans` 前，对已清理并重建的租户、操作者、角色、workspace、数据范围和 trace Header 快照
签名；Python Runtime 在启用校验后，只有签名、keyId、timestamp、nonce 与 HMAC 全部通过时，才允许把
这些 Header 重建为 `trustedControlPlane`。

本地学习环境可以继续保持不强制验签；生产或集成环境建议同时配置：

```powershell
# Java gateway
$env:DATASMART_GATEWAY_PYTHON_RUNTIME_SIGNATURE_ENABLED="true"
$env:DATASMART_GATEWAY_PYTHON_RUNTIME_SIGNATURE_SECRET="<从 Secret Manager 注入的强随机密钥>"
$env:DATASMART_GATEWAY_PYTHON_RUNTIME_SIGNATURE_KEY_ID="gateway-prod-v1"

# Python Runtime
$env:DATASMART_GATEWAY_SIGNATURE_REQUIRED="true"
$env:DATASMART_GATEWAY_SIGNATURE_SECRET="<同一把密钥>"
$env:DATASMART_GATEWAY_SIGNATURE_KEY_ID="gateway-prod-v1"
$env:DATASMART_GATEWAY_SIGNATURE_MAX_SKEW_SECONDS="300"
```

当前签名保护的是可信 Header 快照，不读取 request body。这样可以避免 reactive gateway 引入 body 缓存和
背压风险；请求体里的 `trustedControlPlane` 仍会被 Python API 边界无条件删除。后续生产增强方向是
TLS/mTLS、Secret Manager 密钥轮换、Redis nonce 去重、服务网格访问控制和统一异常映射，而不是让 HMAC
单独承担全部服务间安全。

# 4.99 Gateway 签名失败 API 错误映射

`/agent/plans` 现在会把 gateway 内部签名校验失败映射为 HTTP 401，而不是让
`GatewaySignatureVerificationError` 冒泡成 500。响应 detail 会包含稳定错误码
`GATEWAY_SIGNATURE_INVALID`、失败 `reason`、`traceId`、`sourceService` 和请求路径，便于 gateway、运维
脚本或前端 SDK 定位“未通过统一网关、签名缺失、签名过期或密钥不一致”等问题。

同时，路由层会写入一条安全审计日志，但不会记录共享密钥、签名值、签名原文或完整 Header。这个边界很重要：
生产安全日志应该帮助定位“谁在什么时候访问了哪个入口并因什么原因失败”，而不是保存可以被复制或重放的认证材料。

当前错误映射仍是 Python API 层日志级审计。后续更成熟的做法是接入统一审计事件、Prometheus 指标、
告警规则和 Java replay/index，让服务间认证失败可以进入运维大盘与安全告警。

# 5.00 Gateway 签名 nonce 去重与安全诊断

gateway 签名现在支持 nonce 短 TTL 去重。HMAC 能证明请求由 gateway 生成，但如果同一请求在允许时间窗口内
被截获并重放，单纯 HMAC 仍可能通过；nonce store 会记录 `keyId + nonce`，TTL 内再次出现相同组合时返回
`nonce-replayed`。

本地默认使用进程内 nonce store，适合学习、单元测试和单实例开发。生产多实例应显式启用 Redis：

```powershell
$env:DATASMART_GATEWAY_SIGNATURE_NONCE_STORE="redis"
$env:DATASMART_GATEWAY_SIGNATURE_NONCE_REDIS_URL="redis://localhost:6379/0"
$env:DATASMART_GATEWAY_SIGNATURE_NONCE_TTL_SECONDS="300"
$env:DATASMART_GATEWAY_SIGNATURE_NONCE_KEY_PREFIX="datasmart:gateway-signature:nonce"
```

启用 Redis 需要安装可选依赖：

```powershell
pip install -e python-ai-runtime[redis]
```

同时新增 `/agent/security/gateway-signature/diagnostics` 诊断入口，返回 nonce store 类型、TTL、集群安全提示和
签名失败 reason 统计。该接口不返回 secret、nonce 原文、签名值或签名原文；生产环境仍必须由 gateway 和
permission-admin 管理员权限保护。

# 5.01 Agent 正式长期记忆 SQL Store

长期记忆现在不再只有内存正式 store。新增 `SqlAgentMemoryStore` 与
`docker/mysql/migrations/20260602_agent_memory_store_entry.sql`，用于保存已经通过审批并由
materializer 落成的低敏正式记忆摘要。

这张表的产品定位是“长期记忆控制面事实源”，不是最终的向量检索引擎：

- 保存 `tenantId/projectId/sessionId/scope/memoryType`，让检索可以在 SQL 层先完成治理范围过滤；
- 保存 `workspaceKey/memoryNamespace/namespaceJson`，避免同项目不同 workspace、session 沙箱或专题空间互相召回记忆；
- 保存 `idempotencyKey/sourceCandidateId`，支持 worker 重试、补偿任务和审计台反查；
- 保存 `expiresAt/materializedAt`，为遗忘任务、归档任务和召回排序留下稳定字段；
- 只保存 `contentSummary` 这类低敏摘要，不保存完整工具输出、样本数据、原始 SQL、文件正文或敏感日志。

当前 SQL Store 采用 Python DB-API 连接对象，不直接创建连接池；sqlite 单测用于验证语义，生产部署应使用
MySQL migration 建表并传入 MySQL 驱动连接。后续如果引入 Chroma/Neo4j/MinIO，应围绕同一 `memoryId`
构建二级索引，而不是让向量库成为唯一事实源。

该设计也贴合当前 Agent 长期记忆趋势：长期记忆需要跨会话保留，并通过 namespace/key 或类似机制隔离；
同时，生产环境必须考虑敏感数据保留、过期、审计和可恢复写入，而不能只依赖进程内缓存。

# 5.02 Agent 正式长期记忆 Runtime Builder

正式长期记忆现在已经进入 API 启动装配。`create_app()` 会通过 `build_api_memory_runtime()` 同时创建：

- 记忆写入候选 store：由 `DATASMART_AI_MEMORY_WRITE_*` 控制；
- 正式长期记忆 store：由 `DATASMART_AI_FORMAL_MEMORY_*` 控制；
- 落成 receipt store：由 `DATASMART_AI_MEMORY_RECEIPT_*` 控制；
- `AgentApprovedMemoryWriteMaterializer`：为后续 worker 或补偿入口复用；
- `StoreBackedAgentMemoryRetriever`：注入默认 orchestrator，使 `/agent/plans` 能从正式 store 召回低敏记忆。

生产或准生产环境可以启用 MySQL 正式记忆 store：

```powershell
$env:DATASMART_AI_FORMAL_MEMORY_STORE="mysql"
$env:DATASMART_AI_FORMAL_MEMORY_MYSQL_DSN="mysql://datasmart:******@localhost:3306/datasmart?charset=utf8mb4"
$env:DATASMART_AI_FORMAL_MEMORY_SQL_CONNECT_TIMEOUT_SECONDS="3"
$env:DATASMART_AI_FORMAL_MEMORY_STORE_FAIL_OPEN="false"
```

本地联调也可以使用 SQLite 验证持久化语义：

```powershell
$env:DATASMART_AI_FORMAL_MEMORY_STORE="sqlite"
$env:DATASMART_AI_FORMAL_MEMORY_SQLITE_PATH=".local/formal-memory.sqlite3"
```

新增 `/agent/memory/diagnostics` 会同时返回候选 store、正式 store、retriever 和 materializer 的低敏诊断。
诊断会脱敏 MySQL DSN，不返回候选内容、正式记忆正文、标签或 namespace 明细；生产环境仍必须通过 gateway 和
permission-admin 保护该接口。

当前仍未自动启动后台 materialization worker。也就是说：API 已经具备正式 store 与 materializer 装配，
但 APPROVED 候选的异步消费、租约、失败退避、DLQ、补偿查询和指标仍是下一步。

# 5.03 Agent 长期记忆落成 Receipt SQL Store

长期记忆落成 receipt 现在也支持 SQL 持久化。receipt 与候选、正式记忆是三类不同事实：

- 候选 store 回答“某条工具结果摘要是否允许写入长期记忆”；
- 正式记忆 store 回答“模型后续可以召回哪条低敏经验摘要”；
- receipt store 回答“后台 worker 或同步 materializer 是否已经处理过该候选、尝试几次、成功还是失败”。

生产或准生产环境可以启用 MySQL receipt store：

```powershell
$env:DATASMART_AI_MEMORY_RECEIPT_STORE="mysql"
$env:DATASMART_AI_MEMORY_RECEIPT_MYSQL_DSN="mysql://datasmart:******@localhost:3306/datasmart?charset=utf8mb4"
$env:DATASMART_AI_MEMORY_RECEIPT_SQL_CONNECT_TIMEOUT_SECONDS="3"
$env:DATASMART_AI_MEMORY_RECEIPT_STORE_FAIL_OPEN="false"
```

本地联调可以使用 SQLite：

```powershell
$env:DATASMART_AI_MEMORY_RECEIPT_STORE="sqlite"
$env:DATASMART_AI_MEMORY_RECEIPT_SQLITE_PATH=".local/memory-materialization-receipts.sqlite3"
```

`/agent/memory/diagnostics` 现在会额外展示 `receiptStore`，用于判断落成执行证据是否真的持久化。
receipt 只保存状态、attemptCount、workerId、memoryId、namespace、低敏成功/失败摘要和时间，不保存 prompt、
原始工具输出、样本数据或完整异常堆栈。

当前仍未启动后台 outbox worker。下一步应在 receipt store 之上增加 APPROVED 候选扫描、租约、批量窗口、
失败退避、DLQ、管理员补偿和指标，而不是让 API 请求同步承担所有正式落成工作。
