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
- 长期记忆治理：已具备记忆召回计划、候选生成、审批/拒绝、候选 SQL store、低敏摘要正式落成、materialization receipt 和 store-backed 检索骨架；候选和正式记忆都会携带 `workspaceKey/memoryNamespace`，检索时按当前 Agent 工作空间过滤，避免同项目不同 workspace 或 session 沙箱误共享记忆。当前正式记忆 store 默认仍为内存实现，后续再按类型接入 Chroma、Neo4j、MySQL 和 MinIO。
- `api.create_app()`：提供可选 FastAPI 入口。当前测试不依赖 FastAPI，安装 API 依赖后即可启动服务。

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
