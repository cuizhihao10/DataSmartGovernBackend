# DataSmart Govern Python AI Runtime

> 2026-06-03 补充：Python Runtime 的模型网关已新增 Provider Health 与熔断治理。非流式模型调用和受控二轮推理会把 providerName、错误码、延迟和 usage 回写到统一治理服务；`/agent/models/provider-health/diagnostics` 可查看低敏健康摘要、熔断状态、关联 workload/model 与推荐动作。当前仍是内存版，后续应接 Prometheus、Redis/数据库、真实健康探针和工具调用沙箱。

> 2026-06-03 补充：Java `agent-runtime` 的 Skill 注册表已新增 Marketplace 治理摘要接口，可聚合领域、风险、审批、记忆依赖、启用/禁用和隔离策略。Python Runtime 当前仍消费 descriptor 与 permission-admin admission；后续可把该摘要接入启动诊断，用于判断远程 Skill 市场是否健康、是否高风险能力过多、是否缺少审计或隔离。

> 2026-06-04 补充：Python Runtime 已新增 Skill Publication Manifest 启动诊断。`/agent/skills/publication/diagnostics` 会报告 Java `agent-runtime` Manifest 是否可用、目录级 `contentFingerprint`、READY Skill 数量、非 READY 状态分布和本地默认 Skill fallback 状态。该接口只返回低敏摘要，不返回完整 descriptor、权限明细、prompt 或工具参数。

> 2026-06-04 补充：`/agent/plans` 响应现在会把最近一次 Skill Manifest 诊断快照压缩为 `intelligentGatewayGovernance.skillManifest`，并同步写入 `skillVisibility.manifestBinding` 与 `SKILL_VISIBILITY_SNAPSHOT_RECORDED` runtime event attributes。这样 Java 控制面和前端治理卡片可以回答“本轮会话绑定了哪版 Skill 发布目录”，而不是只能看到可见/隐藏数量。

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
- 智能网关会话级 Skill 快照：`intelligentGatewayGovernance.skillVisibility` 已能输出本轮会话真正可见的 Skill 能力集、被准入策略隐藏的 Skill、可见风险分布、领域分布、隐藏状态分布和可信事实来源。该快照基于本轮 `AgentSkillPlan`，不会重新拉 Manifest 或重新请求 permission-admin；响应组装器会把它压缩为低敏 `SKILL_VISIBILITY_SNAPSHOT_RECORDED` runtime event，随 `eventEnvelope`、event store、live push 和 publisher 一起发布，便于断线 replay、Java 控制面补索引和后续审计报表。当前快照还会携带 `manifestBinding`，记录 Manifest 绑定状态、来源、`manifestFingerprint`、READY/非 READY 数量和 fallback 状态，用于灰度、缓存、事故复盘和 Skill Marketplace 版本统计。
- Skill Publication Manifest 诊断：已具备 `services/skills/` 独立能力包，FastAPI startup 可主动读取 Java `agent-runtime` `/agent-runtime/skills/publication/manifest`，并通过 `/agent/skills/publication/diagnostics` 暴露低敏诊断。诊断会区分 `REMOTE_READY`、`REMOTE_UNAVAILABLE_FALLBACK`、`REMOTE_NOT_REFRESHED` 和 `LOCAL_DEFAULT_ONLY`，展示 Manifest 指纹、READY/非 READY 数量、发布状态分布、风险等级分布和推荐动作；远端失败时默认回退本地 Skill，生产可通过 `DATASMART_AGENT_SKILL_PUBLICATION_MANIFEST_REQUIRED=true` 改为 fail-closed。
- 智能网关工具治理：已具备模型工具调用候选规划、可见工具校验、参数 schema 校验和工具调用预算守卫，可限制单轮工具数量、自动推进数量、高风险工具数量和 arguments 体积；预算策略已抽象为 provider，当前支持环境变量、`AgentRequest.variables["toolCallBudget"]` 覆盖，以及可选远程调用 Java permission-admin `/permissions/agent/tool-budget-policies/evaluate`。远程策略默认关闭，适合生产或联调环境按租户套餐、项目等级、角色、workspace 风险和实时 backlog 动态生成预算；预算阻断会写入独立 `MODEL_TOOL_CALL_BUDGET_GUARDED` runtime event，API 响应已提供 `intelligentGatewayGovernance` 统一摘要，汇总模型路由、工具预算、workspace 和记忆检索治理事实。
- 模型 Provider Health 治理：已具备调用结果驱动的内存健康注册表、连续失败熔断、错误率/延迟降级、低敏诊断摘要和路由 fallback 联动。`ModelGatewayGovernanceService.record_invocation_result(...)` 会同时更新 token usage 与 Provider 健康；`/agent/models/provider-health/diagnostics` 可用于智能网关运维卡片和 Java 控制面排障。当前未持久化健康状态，也未接真实 `/health` 探针和 Prometheus 回灌。
- 长期记忆治理：已具备记忆召回计划、候选生成、审批/拒绝、候选 SQL store、低敏摘要正式落成、materialization receipt、正式记忆 SQL store、正式记忆 runtime builder、lease token fencing runner、失败退避、DLQ 基础语义、管理员补偿入口、物化 runtime event、低基数 Prometheus 指标、受控后台 worker、Prometheus 告警规则、审计 outbox 和 store-backed 检索接入；候选和正式记忆都会携带 `workspaceKey/memoryNamespace`，检索时按当前 Agent 工作空间过滤，避免同项目不同 workspace 或 session 沙箱误共享记忆。当前默认装配仍以本地内存 store 便于学习和单测，后台 worker 与审计 outbox 默认关闭；生产可通过 `DATASMART_AI_FORMAL_MEMORY_*`、`DATASMART_AI_MEMORY_LEASE_*` 与 `DATASMART_AI_MEMORY_MATERIALIZATION_AUDIT_OUTBOX_*` 切到 MySQL，并显式开启 worker，让 `/agent/plans` 从正式记忆表召回低敏经验，同时让多实例 worker 安全领取候选并留下审计事实。
- `api.create_app()`：提供可选 FastAPI 入口。当前测试不依赖 FastAPI，安装 API 依赖后即可启动服务。
- Agent API 路由已从 bootstrap 入口拆到 `api_agent_routes.py`：`api.py` 只负责装配模型网关、事件组件、长期记忆候选治理和 Java 控制面客户端；`/agent/plans`、事件 replay/control 与 WebSocket handler 由独立注册函数承载。这样后续继续增加服务间认证、智能网关会话、审计导出和长期记忆上下文注入时，不会把启动文件拖成难以维护的巨型模块。
- A2A Task 规划预览：`POST /agent/protocol-adapters/a2a/task-planning-preview` 可接收 Java A2A task 查询预览或未来真实 task 低敏合同，并返回 Python Runtime 可消费的 planning decision。该接口只做状态映射与生产化缺口说明，不创建 task、不取消 task、不执行工具、不写 outbox、不回显原始 payload。
- A2A Task 会话调度接入：`/agent/plans` 现在可以消费 `trustedControlPlane.a2aTaskPlanningDecision` 中的低敏 planning decision，并把 A2A task 状态纳入 `intelligentGatewayGovernance.agentSessionScheduling`。授权等待态会激活权限 Agent 并进入 `APPROVAL_REQUIRED`，用户输入/预检态会降级阻断直接执行，未知状态会 fail-closed 并激活运维诊断 Agent。对应 runtime event 只记录 mode、状态、阶段、guardrail code 和计数，不记录 task id、prompt、工具参数、SQL、artifact 正文或内部 endpoint。
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

## Skill Publication Manifest 启动诊断

当配置 `DATASMART_AGENT_RUNTIME_BASE_URL` 后，Python Runtime 可以在启动期读取 Java `agent-runtime` 的 Skill 发布 Manifest，并把低敏健康摘要暴露给智能网关、Java 控制面或运维台：

```text
GET /agent/skills/publication/diagnostics
```

常用环境变量：

```powershell
$env:DATASMART_AGENT_RUNTIME_BASE_URL="http://localhost:8091"
$env:DATASMART_AGENT_SKILL_PUBLICATION_MANIFEST_DIAGNOSTICS_ENABLED="true"
$env:DATASMART_AGENT_SKILL_PUBLICATION_MANIFEST_INCLUDE_DISABLED="true"
$env:DATASMART_AGENT_SKILL_PUBLICATION_MANIFEST_REFRESH_ON_STARTUP="true"
$env:DATASMART_AGENT_SKILL_PUBLICATION_MANIFEST_REQUIRED="false"
$env:DATASMART_AGENT_SKILL_PUBLICATION_MANIFEST_MAX_NON_READY_ITEMS="10"
```

设计边界：

- 诊断接口不改变当前 Agent 规划主路径，规划仍使用已加载的 Skill descriptor。
- 诊断默认包含禁用 Skill，是为了看见非 READY 原因；模型规划不应直接消费禁用 Skill。
- 本地学习环境允许远端 Manifest 失败后 fallback 到本地默认 Skill。
- 生产强治理环境可以设置 `DATASMART_AGENT_SKILL_PUBLICATION_MANIFEST_REQUIRED=true`，让远端发布事实源不可用时显式失败。
- 诊断响应不返回完整 descriptor、权限明细、prompt、工具参数、样本数据或密钥。
- `/agent/plans` 不会在每次请求实时刷新 Manifest，只读取诊断服务的最近快照并做低敏归一化；这样既能给会话事件补充版本证据，又避免用户同步规划路径被远端 Manifest 网络 IO 拖慢。

## A2A Task 规划预览

Python Runtime 已提供 A2A task 规划预览入口，用于把 Java Agent Runtime 的 A2A task 低敏合同转换成
Master Agent 可理解的 planning decision：

```text
POST /agent/protocol-adapters/a2a/task-planning-preview
```

请求体可以直接提交 Java `task-query-preview` 风格 JSON，也可以使用 `{"contract": {...}}` 包一层。

响应会包含：

- `planningDecision.mode`：`PRECHECK_REQUIRED`、`WORKER_PLANNING_ALLOWED`、`WAIT_FOR_USER_INPUT`、`WAIT_FOR_AUTHORIZATION`、`TERMINAL_NO_EXECUTION` 或 `REJECTED_OR_DIAGNOSTIC`；
- `productionReadiness.missingProductionRequirements`：真实执行前仍缺失的 task fact、task-management 对接、permission-admin 预检、幂等限流、confirmation outbox、worker pre-check、artifact 二次鉴权和 runtime event timeline；
- `inputPayloadPolicy`：说明接口不会回显原始 payload，会统计并丢弃不应进入 planning 层的敏感字段。

设计边界：

- 该接口不是 A2A `message/send`、`tasks/get`、`tasks/cancel` 或 `tasks/subscribe`；
- 不保存、不回显 prompt、工具参数、SQL、样本数据、artifact 正文、模型输出、凭证或内部 endpoint；
- 真实执行仍必须回到 Java 控制面的权限、审批、outbox、worker receipt 和 artifact 服务二次鉴权链路。

## 下一步建议

- 接入 Java `agent-runtime` 的工具注册表接口，让 Python 层从真实注册表获取工具定义。
- 为远程 Skill 准入补 gateway 可信注入、服务间认证、策略版本写入 runtime event、租户级 Skill 启停和前端 Skill 治理卡片。
- 将规则式 `ToolPlanner` 抽象为策略接口，增加 LLM 规划器实现。
- 增加 RAG/GraphRAG 上下文构建器，区分元数据检索、权限事实检索、质量规则案例检索。
- 增加模型 Provider 适配器，优先兼容 OpenAI-compatible、vLLM、SGLang 等部署形态。
- 模型 Provider Health 下一步应接真实探针、Prometheus 指标、手动熔断权限、fallback rate 告警和 streaming trailer usage；同时继续推进 KV/prefix cache 命中率与 cache admission。
- 将 permission-admin 工具预算策略继续升级为数据库策略表、租户套餐版本、worker backlog 指标源和策略发布版本，而不是长期停留在内存规则。
- 正式长期记忆 runner 已具备 Prometheus 指标、基础告警规则和 Python 审计 outbox；下一步建议补 Java 审计派发、事务级 outbox、向量库 namespace 过滤适配器、二级索引同步 worker 和批量补偿审批流。
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
- 落成 lease store：由 `DATASMART_AI_MEMORY_LEASE_*` 控制；
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

后台 materialization worker 现在已经具备受控启动能力，但默认关闭。也就是说：API 已经具备正式 store、
materializer、租约、退避、DLQ、补偿、事件和指标装配；只有显式配置
`DATASMART_AI_MEMORY_MATERIALIZATION_WORKER_ENABLED=true` 后，才会在 FastAPI 生命周期中自动消费 APPROVED 候选。

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

当前已具备受控常驻 worker，但仍没有事务 outbox、批量补偿审批流或二级索引同步 worker。下一步应在 receipt store
与 lease store 之上继续增加审计 outbox、告警规则和向量库/图谱索引同步，而不是让 API 请求同步承担所有正式落成工作。

# 5.04 Agent 长期记忆最小 Materialization Runner

长期记忆落成现在新增 `AgentMemoryMaterializationRunner`，用于执行一个有界窗口内的 APPROVED 候选：

- 每轮只扫描 `APPROVED` 候选，避免 DRAFT、REJECTED 或 IGNORED 历史候选绕过审批进入正式记忆；
- `limit` 会被裁剪到安全范围，防止管理接口误传极大值导致 Python Runtime 一次性扫描过多；
- 每条候选逐条调用 `AgentApprovedMemoryWriteMaterializer.materialize(...)`；
- 单条候选失败会被记录为失败 item，不会阻塞同批其他候选；
- 批次报告只返回 candidateId、memoryId、outcome、workerId、短错误和低敏 attributes，不返回候选正文、正式记忆正文或原始工具输出；
- Runner 不修改候选审批状态。APPROVED 表示“允许进入落成流程”，是否已经落成由 formal memory store 与 receipt store 共同证明。

`build_api_memory_runtime()` 现在会同时装配：

- 候选 store；
- 正式记忆 store；
- receipt store；
- `AgentApprovedMemoryWriteMaterializer`；
- `AgentMemoryMaterializationRunner`；
- `StoreBackedAgentMemoryRetriever`。

`/agent/memory/diagnostics` 会展示 `materializationRunner` 与 `materializationWorker`。worker 默认关闭；
只有显式配置开启后才会在 API 进程启动时自动消费候选。这样设计是为了避免本地学习环境或未配置 SQL lease store
的部署悄悄产生后台副作用。

下一步推荐：

- 继续评估 SQL/outbox 风格的领取租约与审计 outbox，支持强审计客户场景；
- 增加 Prometheus 告警规则，覆盖 DLQ 增长、失败率升高、finalize error 和补偿重排激增；
- 增加批量补偿入口，允许管理员先 dry-run、再二次确认重排或归档失败候选；
- 把 worker 与 Java task-management 或平台任务中心打通，支持更统一的调度、暂停、恢复和审计；
- worker 稳定后再接 Chroma/Neo4j 二级索引，继续强制 `memoryNamespace` 作为 metadata filter 或 collection 边界。

# 5.05 Agent 长期记忆 Materialization Lease Store

长期记忆落成现在新增独立 `AgentMemoryMaterializationLeaseStore`，并把 Runner 从“只会批量处理”
升级为“处理前先领取、处理后按 token 完成”的多 worker 安全执行入口。

为什么需要单独的 lease store：

- candidate store 是审批事实，应该回答“是否允许写入长期记忆”，不应该混入 worker 抢占状态；
- receipt store 是执行证据，应该回答“尝试过几次、成功还是失败”，不应该承担短 TTL 锁；
- lease store 是并发控制事实，专门回答“当前哪个 worker 暂时拥有处理权”；
- 三类事实拆开后，审批台、补偿台、运维指标和未来分布式 worker 可以各自演进，避免一个 `status` 字段承载过多含义。

当前已提供两类实现：

- `InMemoryAgentMemoryMaterializationLeaseStore`：用于本地学习、单测和单实例开发，零依赖，但不能跨进程协调；
- `SqlAgentMemoryMaterializationLeaseStore`：用于 SQLite 联调与 MySQL 生产，使用条件 UPDATE、唯一约束和
  fencing token 阻止过期 worker 覆盖新 worker。

生产或准生产环境可以启用 MySQL lease store：

```powershell
$env:DATASMART_AI_MEMORY_LEASE_STORE="mysql"
$env:DATASMART_AI_MEMORY_LEASE_MYSQL_DSN="mysql://datasmart:******@localhost:3306/datasmart?charset=utf8mb4"
$env:DATASMART_AI_MEMORY_LEASE_SQL_CONNECT_TIMEOUT_SECONDS="3"
$env:DATASMART_AI_MEMORY_LEASE_STORE_FAIL_OPEN="false"
$env:DATASMART_AI_MEMORY_LEASE_SECONDS="60"
```

本地联调可以使用 SQLite：

```powershell
$env:DATASMART_AI_MEMORY_LEASE_STORE="sqlite"
$env:DATASMART_AI_MEMORY_LEASE_SQLITE_PATH=".local/memory-materialization-leases.sqlite3"
```

`/agent/memory/diagnostics` 现在会额外展示 `leaseStore`，用于判断当前租约实现是否持久化、是否发生
fail-open 回退，以及默认租约窗口是多少秒。诊断不会返回 `leaseToken`、候选正文、正式记忆正文或真实租约记录。

Runner 的执行策略现在是 `BOUNDED_AT_LEAST_ONCE_WITH_LEASE_TOKEN_FENCING_AND_BACKOFF_DLQ`：

- 每轮仍然只处理有限数量的 APPROVED 候选，防止补偿任务扫爆数据库；
- 如果窗口前部候选被其他 worker 持有，Runner 会继续向后扫描，减少可执行候选饥饿；
- 成功终态会阻止后续轮次重复领取同一候选，避免每次扫描都重复写 receipt；
- 失败候选会进入 `nextRetryAt` 冷却窗口，冷却结束前不会被 Runner 自动重新领取；
- 达到最大尝试次数后进入 `dead_letter`，等待管理员补偿或人工重放；
- `lease_token` 是内部 fencing 凭证，旧 worker 在租约过期后即使晚到，也不能覆盖新 worker 的成功或失败结果。

当前边界仍然很明确：这一步还没有自动后台循环、Prometheus 指标、管理员重放入口和 Chroma/Neo4j 二级索引同步。
下一阶段更建议先补“管理员补偿 + 指标 + 管理路由/CLI”，再决定是否启动常驻 worker，而不是现在就把自动线程悄悄放进 API 进程。

# 5.06 Agent 长期记忆失败退避与 DLQ 基础语义

长期记忆落成现在不再让失败候选立即重新领取。Runner 在单条候选失败时会通过 lease store 写入：

- `status=failed`：说明尚未达到最大尝试次数，候选会进入 `nextRetryAt` 冷却窗口；
- `status=dead_letter`：说明失败次数达到上限，候选进入 DLQ，后续 Runner 不再自动领取；
- `errorMessage`：只保存低敏错误摘要，不保存 prompt、原始工具输出、SQL、样本数据或完整异常堆栈。

新增执行策略配置：

```powershell
$env:DATASMART_AI_MEMORY_MATERIALIZATION_MAX_ATTEMPTS="5"
$env:DATASMART_AI_MEMORY_MATERIALIZATION_RETRY_BASE_SECONDS="30"
$env:DATASMART_AI_MEMORY_MATERIALIZATION_RETRY_MAX_SECONDS="3600"
```

退避计算采用简单指数退避：第 1 次失败等待 base，第 2 次失败等待 base*2，并受 retryMax 限制。
这不是最终智能调度算法，而是先给生产可靠性一个可解释、可测试、可诊断的底座。未来可以按错误类型、
租户套餐、下游容量或维护窗口调整退避策略。

`/agent/memory/diagnostics` 的 `leaseStore.failurePolicy` 会展示当前最大尝试次数和退避配置。
Runner 批次报告也会展示：

- `skippedReasons`：例如 `active_lease`、`retry_cooldown`、`dead_letter`、`already_succeeded`；
- `deadLetterCount`：本轮新进入 DLQ 的数量；
- 单条失败 item 的 `leaseStatus/nextRetryAt/attemptCount/maxAttempts/deadLettered`。

管理员补偿入口已经在 5.07 开始提供；`dead_letter` 不再只是阻止自动热循环的终态，也可以被管理员通过
dry-run 和受控重排重新放回 Runner 可领取窗口。

# 5.07 Agent 长期记忆管理员补偿入口

长期记忆物化现在新增 `AgentMemoryMaterializationAdminService` 与 FastAPI 管理路由，用于处理 failed/DLQ
候选的低敏查询、dry-run 预览和受控重排。

新增路由：

- `GET /agent/memory/materialization/leases`：查询 `failed/dead_letter` lease，支持 `tenantId/projectId/workspaceKey/status/limit`；
- `POST /agent/memory/materialization/leases/{candidateId}/requeue`：预览或执行重排。

重排请求示例：

```json
{
  "operatorId": "admin-a",
  "reason": "下游 MySQL 连接恢复后重新物化",
  "dryRun": true,
  "delaySeconds": 30
}
```

设计边界：

- `dryRun` 默认应保持为 `true`，管理台确认后才传 `false` 执行真实重排；
- 补偿入口只允许处理 `failed/dead_letter`，不允许改动 `succeeded` 或正在 `leased` 的候选；
- 重排只把 lease 改回 `failed` 并设置 `nextRetryAt`，不会绕过候选审批，也不会直接写正式记忆；
- `attemptCount` 会保留为故障证据，不在普通补偿动作中清零；
- 结果只返回 lease 低敏摘要，不返回候选正文、正式记忆正文、lease token 原文或工具输出。

当前边界：

- Python Runtime 仍不直接做用户鉴权，生产环境必须由 gateway/permission-admin 保护该入口；
- 补偿操作当前写入 lease message，尚未接统一审计事件表；
- 还没有批量补偿、审批流、错误类型分组处理或统一审计事件表；
- 下一步应把补偿事件接入统一审计 outbox，并设计批量 dry-run/二次确认能力。

# 5.08 Agent 长期记忆物化 Runtime Event

长期记忆物化现在新增 `memory_materialization_events.py`，把 Runner 批次报告和管理员补偿重排结果转换为统一
`AgentRuntimeEvent`。这一步的目标不是简单多写一行日志，而是让长期记忆后台链路进入与 `/agent/plans` 相同的
运行时间线、replay store 和未来 Kafka/审计/指标通道。

新增事件类型：

- `memory_materialization_run_completed`：记录一轮物化 Runner 的低敏汇总事实；
- `memory_materialization_requeue_recorded`：记录管理员 dry-run 或真实重排动作。

Runner 汇总事件会记录：

- `requestedLimit/scannedCount/succeededCount/failedCount/skippedCount`：用于判断本轮吞吐与成功率；
- `deadLetterCount/retryCooldownSkippedCount/activeLeaseSkippedCount/deadLetterSkippedCount/alreadySucceededSkippedCount`：用于区分“没有处理”到底是容量、冷却、DLQ 还是已完成；
- `leaseFinalizeErrorCount`：用于识别 token fencing 或迟到 worker 回写失败；
- `executionPolicy/maxAttempts/retryBaseSeconds/retryMaxSeconds/durationMillis`：用于后续告警和性能分析。

管理员重排事件会记录：

- `candidateId/leaseId/action/dryRun/operatorId`：审计谁对哪条候选执行了什么动作；
- `beforeStatus/afterStatus/attemptCount/beforeNextRetryAt/afterNextRetryAt`：解释状态变化；
- `workspaceKey/memoryNamespace`：帮助运维按工作区定位，但仍不暴露候选正文、正式记忆正文或 lease token 原文。

FastAPI `create_app()` 已把 `/agent/memory/materialization/leases/{candidateId}/requeue` 接入当前 runtime
event store 与 publisher。事件投递当前采用 fail-open 语义：如果 Redis/Kafka 旁路不可用，补偿主流程不会被回滚，
响应中的 `runtimeEventDelivery.errors` 会返回低敏诊断摘要。生产环境如果要求审计事件强一致，下一步应引入事务
outbox 或 fail-closed 配置，而不是在 API handler 中隐式阻塞所有本地学习场景。

# 5.09 Agent 长期记忆物化低基数 Prometheus 指标

长期记忆物化 Runtime Event 现在可以进一步映射为 Prometheus 文本指标。新增
`AgentMemoryMaterializationMetrics`，用于把 `memory_materialization_run_completed` 与
`memory_materialization_requeue_recorded` 转换成低基数 Counter/Summary 风格序列。

新增端点：

- `GET /agent/metrics`：返回 Prometheus exposition text，默认由 `create_app()` 暴露。

新增指标：

- `datasmart_ai_memory_materialization_runs_total{result,severity}`：Runner 批次数，`result` 只允许 `succeeded/partial_failed/dead_lettered/finalize_error`；
- `datasmart_ai_memory_materialization_candidates_total{result}`：候选扫描、领取、成功、失败、跳过、DLQ 聚合数；
- `datasmart_ai_memory_materialization_skips_total{reason}`：跳过原因，限定为 `retry_cooldown/active_lease/dead_letter/already_succeeded/other`；
- `datasmart_ai_memory_materialization_finalize_errors_total`：lease token fencing 或 finalize 回写失败数；
- `datasmart_ai_memory_materialization_duration_milliseconds_count/sum{result}`：Runner 批次耗时；
- `datasmart_ai_memory_materialization_requeues_total{action,dry_run,after_status}`：管理员补偿重排次数。

指标边界：

- 不使用 `tenantId/projectId/candidateId/leaseId/requestId/runId/sessionId/workspaceKey` 作为标签，避免高基数时序拖垮 Prometheus；
- 单条候选定位继续走 Runtime Event replay、lease/receipt 查询和审计日志；
- 当前实现不依赖 `prometheus_client`，保持 Python Runtime 默认零依赖；后续如果需要 Histogram、multiprocess 或进程指标，可替换为官方 client 适配器；
- `docker/prometheus/prometheus.yml` 已新增 `python-ai-runtime` job，默认抓取 `host.docker.internal:8090/agent/metrics`。

# 5.10 Agent 长期记忆物化受控后台 Worker

长期记忆物化现在新增 `AgentMemoryMaterializationWorker`，用于在 FastAPI 生命周期中按配置周期性调用
`AgentMemoryMaterializationRunner.run_once(...)`。它会把每轮 Runner 报告转换为 Runtime Event，并同步写入
低基数 Prometheus 指标。

Worker 默认关闭，生产或联调环境可以通过以下环境变量启用：

```powershell
$env:DATASMART_AI_MEMORY_MATERIALIZATION_WORKER_ENABLED="true"
$env:DATASMART_AI_MEMORY_MATERIALIZATION_WORKER_INTERVAL_SECONDS="30"
$env:DATASMART_AI_MEMORY_MATERIALIZATION_WORKER_BATCH_LIMIT="50"
$env:DATASMART_AI_MEMORY_MATERIALIZATION_WORKER_RUN_ON_STARTUP="true"
$env:DATASMART_AI_MEMORY_MATERIALIZATION_WORKER_MAX_CONSECUTIVE_ERRORS="5"
$env:DATASMART_AI_MEMORY_MATERIALIZATION_WORKER_STOP_TIMEOUT_SECONDS="5"
```

诊断入口：

- `GET /agent/memory/diagnostics`：返回 `materializationWorker`，包含 enabled/running/fuseOpen/runCount/lastResult 等低敏状态。

设计边界：

- 默认关闭，避免本地学习环境启动 API 后产生隐式长期记忆写入副作用；
- 当前是单线程循环。多实例部署应使用 SQL lease store，由 lease token fencing 防止多个 worker 覆盖同一候选；
- 连续 Runner 异常达到 `maxConsecutiveErrors` 后会打开熔断标记并停止后台循环，避免坏配置造成错误风暴；
- 每轮成功后会写 Runtime Event、event store/publisher、指标和可选审计 outbox；Runtime Event/指标失败仍按旁路处理，审计 outbox 可通过 required 配置改成 fail-closed；
- 还没有 Java 审计派发、事务级 outbox、批量补偿审批流或 Chroma/Neo4j 二级索引 worker。

# 5.11 Agent 长期记忆物化 Prometheus 告警规则

长期记忆物化现在不仅能暴露指标，也新增了 Prometheus 告警规则草案：
`docker/prometheus/rules/python-ai-runtime-alerts.yml`。这一步的目标是让后台 worker 从“有指标可看”
推进到“异常会主动提醒”，避免生产环境只有等用户发现长期记忆不再召回、DLQ 堆积或补偿动作异常时才排查。

告警覆盖：

- `PythonAiRuntimeMetricsDown`：Prometheus 无法抓取 `/agent/metrics`。这是所有 AI Runtime 指标和告警的入口级健康检查；
- `PythonAiRuntimeMetricsTargetMissing`：Prometheus 配置中不存在 `python-ai-runtime` job，用于发现配置未加载或容器未重载；
- `PythonAiMemoryMaterializationDlqIncreasing`：出现新的 `dead_lettered` 候选，说明自动 worker 已经放弃继续重试，需要管理员介入；
- `PythonAiMemoryMaterializationFinalizeError`：lease finalize 或 token fencing 回写失败，重点指向多 worker、租约过短或旧 worker 迟到回写；
- `PythonAiMemoryMaterializationFailureRatioHigh`：候选失败比例持续偏高，用于发现下游正式记忆 store、receipt store、lease store 或数据质量问题；
- `PythonAiMemoryMaterializationRetryCooldownBacklog`：失败候选大量处于退避冷却，提示系统正在自我保护，但也可能代表下游持续不可用；
- `PythonAiMemoryMaterializationWorkerNoSuccessfulRun`：Python Runtime 可抓取，但 worker 长时间没有批次指标，用于发现 worker 未启用、熔断或调度配置错误；
- `PythonAiMemoryMaterializationRequeueSurge`：真实补偿重排次数短时间升高，提示可能存在批量误操作、下游恢复后的集中重放或坏候选反复恢复；
- `PythonAiMemoryMaterializationDryRunOnly`：管理员做了较多 dry-run 但没有真实 requeue，提示运维流程可能停在二次确认或风险评估阶段。

设计边界：

- 规则只使用 `result/severity/reason/action/dry_run/after_status` 等低基数标签，不把
  `tenantId/projectId/candidateId/leaseId/requestId/runId/sessionId/traceId/workspaceKey`
  放进 Prometheus 时序；
- 单条候选排障继续走 Runtime Event replay、lease/receipt 查询和审计日志，Prometheus 只负责聚合趋势与告警；
- 当前阈值适合本地或早期集成环境。生产环境需要结合租户规模、worker 实例数、候选产生速率、下游 Chroma/Neo4j/MySQL 容量和 SLA 进行调优；
- 告警规则配套新增 `test_prometheus_alert_rules.py`，用于固定 scrape job、核心告警名称、低基数选择器和 dry-run 聚合表达式，降低后续修改时的回归风险。

当前边界仍然明确：这一步还没有把告警接入 Alertmanager 路由模板、Grafana 面板、Java 统一审计 outbox 或客户级 SLA 报表。
下一步如果继续生产化长期记忆，更适合补审计 outbox/fail-closed 选项、批量补偿审批和 Chroma/Neo4j 二级索引 worker，
而不是继续无限增加单条 Prometheus 规则。

# 5.12 Agent 长期记忆物化审计 Outbox

长期记忆物化现在新增 Python 侧审计 outbox，用于把两类关键事实写成后续可派发、可归档、可导出的审计记录：

- worker 批次事件：`memory_materialization_run_completed`；
- 管理员补偿事件：`memory_materialization_requeue_recorded`。

审计 outbox 与 Runtime Event、Prometheus 的职责不同：

- Runtime Event 负责前端时间线、replay 和问题定位；
- Prometheus 负责低基数聚合指标与告警；
- 审计 outbox 负责“这次后台批次或管理员补偿是否留下可追责事实”，后续可以派发到 Java 审计中心、Kafka、对象归档或客户 SIEM。

可选配置：

```powershell
$env:DATASMART_AI_MEMORY_MATERIALIZATION_AUDIT_OUTBOX_ENABLED="true"
$env:DATASMART_AI_MEMORY_MATERIALIZATION_AUDIT_OUTBOX_REQUIRED="false"
$env:DATASMART_AI_MEMORY_MATERIALIZATION_AUDIT_OUTBOX_STORE="sqlite"
$env:DATASMART_AI_MEMORY_MATERIALIZATION_AUDIT_OUTBOX_SQLITE_PATH=".local/memory-materialization-audit-outbox.sqlite3"
$env:DATASMART_AI_MEMORY_MATERIALIZATION_AUDIT_OUTBOX_STORE_FAIL_OPEN="true"
```

生产 MySQL 模式建议：

```powershell
$env:DATASMART_AI_MEMORY_MATERIALIZATION_AUDIT_OUTBOX_ENABLED="true"
$env:DATASMART_AI_MEMORY_MATERIALIZATION_AUDIT_OUTBOX_REQUIRED="true"
$env:DATASMART_AI_MEMORY_MATERIALIZATION_AUDIT_OUTBOX_STORE="mysql"
$env:DATASMART_AI_MEMORY_MATERIALIZATION_AUDIT_OUTBOX_MYSQL_DSN="mysql://user:password@localhost:3306/datasmart_govern?charset=utf8mb4"
$env:DATASMART_AI_MEMORY_MATERIALIZATION_AUDIT_OUTBOX_STORE_FAIL_OPEN="false"
```

新增数据表：

- `agent_memory_materialization_audit_outbox`：保存低敏审计 payload、聚合对象、操作者、dry-run 标记、投递状态和未来 dispatcher 重试字段。

设计边界：

- outbox payload 只能保存计数、状态、candidateId、workerId、namespace、attemptCount 等低敏控制面事实；
- 禁止保存 prompt、候选正文、正式记忆正文、SQL、样本数据、工具原始输出、lease token 或完整异常堆栈；
- 当前 `required=true` 会让 worker 本轮按失败计数，并让补偿 API 在审计写入失败时返回 503；
- 但当前还不是同库事务级强一致：如果业务状态已经写入而 outbox append 失败，系统会显式暴露错误但不能自动回滚。真正强合规终态应继续演进为 lease/requeue 与 outbox append 同事务提交。

下一步建议：

- 增加 Java audit bridge/dispatcher，把 pending outbox 派发到 permission-admin 审计中心；
- 增加 claim/ack/retry 字段流转和 Prometheus 指标，覆盖 outbox 积压、投递失败率和最老 pending 年龄；
- 把管理员批量补偿做成“批量 dry-run -> 风险分组 -> 二次确认 -> 真实 requeue -> 审计 outbox”的完整流程。

# 5.13 智能网关多 Agent 会话调度策略视图

Python Runtime 现在在 `intelligentGatewayGovernance.agentSessionScheduling` 中返回会话级多 Agent 调度摘要。
这一步不是完整多 Agent 执行器，而是先把控制面契约稳定下来：主控 Agent、领域专家 Agent、防护型
Agent、工具预算、Skill 可见性、长期记忆、模型路由和人工 handoff 都能在同一个低敏字段里被解释。

调度视图包含：

- `primaryAgentRole`：当前固定为 `MASTER_ORCHESTRATOR`，表示本轮由主控编排 Agent 汇总目标、计划和治理结论；
- `participatingAgents`：本轮参与的 Agent，例如 `DATASOURCE_AGENT`、`DATA_QUALITY_AGENT`、`TASK_AGENT`、`PERMISSION_AGENT`、`MEMORY_AGENT`、`OPS_AGENT`；
- `policyAxes`：调度依据，包括治理域、已选 Skill、可见 Skill、计划工具、记忆依赖、模型网关状态、Skill 准入、工具预算和审批需求；
- `status`：`READY`、`DEGRADED`、`APPROVAL_REQUIRED` 或 `BLOCKED`；
- `handoffRequired`：高风险工具、审批任务或关键阻断是否需要交给 Java 控制面审批/人工接管；
- `recommendedActions`：下一步建议，例如恢复模型网关、补权限包、拆分工具批次或接入真实多 Agent runtime。

设计边界：

- 调度器只读取已有计划事实，不重新做权限、预算、模型或记忆决策；
- 响应只暴露 Skill code、工具名、记忆类型和状态摘要，不暴露 prompt、工具参数、SQL、样本数据或记忆正文；
- 当前是同步策略视图，还没有真正启动并发专家 Agent、A2A handoff、MCP 工具资源协商或长期会话状态机；
- 后续真实执行应由 Java agent-runtime 负责审批、审计、幂等和任务状态，Python Runtime 负责模型规划与策略解释。

下一步建议：

- 将该策略视图写入 Runtime Event，支持 WebSocket/replay/Java 投影；
- 把 Master Agent handoff 图升级成可执行状态机，明确专家 Agent 的输入输出和失败回退；
- 为 data-sync、permission-admin、task-management 等微服务补齐专属 Agent Skill，避免调度只依赖通用关键词；
- 跟进 A2A、MCP、Agents SDK tracing/session 等生态能力，但继续保持租户、项目、workspace 和审计边界优先。

# 5.14 多 Agent 会话调度 Runtime Event

`agentSessionScheduling` 现在不仅存在于同步 HTTP 响应中，也会追加一条低敏 Runtime Event：
`agent_session_scheduling_recorded`。这让事件存储、WebSocket replay、Kafka publisher 和未来 Java projection
都能还原本轮多 Agent 调度事实。

事件属性覆盖：

- `status`、`available`、`primaryAgentRole`、`participatingAgentCount`；
- `participatingAgentRoles`、`participationModeCounts`、`agentStatusCounts`；
- `handoffRequired`、`handoffAgentRoles`；
- `intentDomains`、`selectedSkillCodes`、`visibleSkillCodes`、`plannedToolNames`、`memoryDependencies`；
- `modelGatewayAvailable`、`skillAdmissionAllowed`、`toolBudgetAllowed`、`approvalRequired`；
- `tenantScoped`、`projectScoped`、`displaySummary`、`recommendedActionCount`。

安全边界：

- 事件不写入 objective、prompt、SQL、工具参数、样本数据、模型输出、长期记忆正文或完整推荐动作；
- Agent 角色、工具名、Skill code 等低敏控制面字段最多保留 20 个，避免未来能力目录变大后事件膨胀；
- `BLOCKED` 记为 `ERROR`，`DEGRADED/APPROVAL_REQUIRED` 或 handoff 场景记为 `AUDIT`，正常 `READY` 记为 `INFO`。

设计意义：

- Skill 可见性事件回答“模型本轮看见哪些能力”；
- Agent 会话调度事件回答“本轮由哪些 Agent 参与以及是否需要 handoff”；
- 两者都进入 Runtime Event 后，前端实时会话、断线恢复、Java replay index 和审计报表可以基于同一条事件链演进。
