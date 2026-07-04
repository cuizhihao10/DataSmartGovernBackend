# DataSmart Govern 项目简历经历

## 1. 简历项目标题

DataSmart Govern Pro 企业级多 Agent 数据治理平台

## 2. 一句话项目介绍

面向企业数据治理场景，设计并实现基于 Spring Cloud 微服务和 Python LangGraph Runtime 的多 Agent 控制面，覆盖数据源接入、数据同步、数据质量、任务调度、权限治理、RAG 知识问答、Agent 工具审批、异步 worker 回执、审计与可观测闭环。

## 3. 推荐简历版本

项目：DataSmart Govern Pro 企业级多 Agent 数据治理平台

角色：AI Agent 平台后端开发 / Agent Runtime 控制面开发

技术栈：Java 21、Spring Boot 3.5、Spring Cloud Gateway、Spring Cloud Alibaba、MyBatis-Plus、PostgreSQL/pgvector、MySQL 兼容迁移、Redis、Kafka、MinIO、Keycloak/OIDC、Prometheus/Grafana、Python FastAPI、LangGraph、RAG、MCP、A2A、OpenAI-compatible Model Provider

项目描述：

参与企业级数据治理多 Agent 平台建设，负责 Java 控制面与 Python AI Runtime 的架构解耦、Agent 工具治理、权限准入、任务可靠执行、RAG 低敏化、LangGraph durable checkpoint、worker/outbox 回执和生产化加固方案。平台以 Java 微服务保存业务事实，以 Python Runtime 承接意图分析、工具规划、RAG 和多 Agent 协作摘要，通过 Gateway HMAC、权限预算、审批事实、outbox、租约、幂等和可观测链路保证 Agent 在企业场景下可控、可审计、可恢复。

核心贡献：

- 设计 Java/Python 双控制面架构：Java 侧沉淀业务事实、权限、审批、审计、outbox 和 worker receipt，Python 侧承接 LangGraph、RAG、Intent、ToolPlanner、MCP/A2A 和低敏多 Agent 状态，避免模型运行时成为唯一事实源。
- 实现 Gateway 到 Python Runtime 的可信上下文链路：在路由授权后生成租户、角色、数据范围、工具预算、Skill 可见性等低敏 header，并使用 HMAC-SHA256 签名，防止客户端绕过 Gateway 伪造 Agent 权限上下文。
- 建设 Agent 工具治理能力：在 permission-admin 中实现工具预算策略、Skill 准入策略和服务端审批事实，支持按角色、租户套餐、workspace 风险、worker backlog、工具风险限制自动工具调用和高风险动作。
- 设计 task-management 可靠任务生命周期：支持 PENDING/RUNNING/PAUSED/DEFERRED/DEAD_LETTER/SUCCESS/FAILED/CANCELLED 状态，基于数据库条件更新实现 worker claim，基于 executorId 和 heartbeat 实现租约续期，基于唯一键实现回调幂等。
- 完成 data-sync 执行控制面：区分 SyncTask 与 SyncExecution 状态，支持 claim、heartbeat、pause/cancel 控制信号、defer、过期租约恢复、AWAITING_OPERATOR_ACTION、低敏 receipt outbox 和 fail-closed worker loop。
- 建设 Java agent-runtime 控制面：支持 Agent session/run、tool registry、tool execution audit、DAG plan、approval/reject/execute、command proposal/outbox、artifact access grant、Skill registry/publication lifecycle、runtime event projection/replay、turn runner checkpoint locator projection。
- 推进 Python AI Runtime：实现结构化 IntentAnalysis、Hybrid Context、ToolPlanner、RAG API、KNOWLEDGE_AGENT 能力合同、LangGraph checkpoint 节点链路、多 Agent turn runner 和 MCP outbound client，保证 runtime event/checkpoint 不保存 prompt、SQL、样本数据、模型回答和凭据。
- 打通 RAG command worker 受控执行链路：Java outbox dispatcher 调用 Python `/internal/agent/rag/command-worker/run`，Python 执行 RAG 后生成低敏 `javaReceiptPayload`，Java 统一 ingest 为 command worker receipt，保持 `RAG_QUERY_COMPLETED` 只读语义和 `sideEffectExecuted=false` 边界。
- 设计 RAG answer artifact 安全读取链路：Python 将 answer/citations/compressedContext 写入 local 或 MinIO/S3-compatible 受控 artifact，Java 只保存 artifactReference/hash/计数等低敏事实，并通过 metadata authorization、body-read grant、final-check 三段式控制正文预览和下载前授权。
- 完成身份供应和 IdP 持久化闭环：permission-admin 支持 Keycloak/企业 IdP 账号注册、禁用、密码重置和低敏影子身份映射；Gateway 纳入 `/api/identity/**` 路由权限；Keycloak 从容器文件卷迁移为 PostgreSQL-backed 身份存储，便于备份、恢复和生产审计。
- 完善可观测和生产化材料：补齐 Prometheus/Grafana、生产就绪静态门禁、Helm/K8s、SBOM、镜像签名、备份恢复、容量基线、故障演练和高风险 worker 默认关闭策略。

结果与证据：

- 当前仓库记录 Python Runtime 全量单元测试最高阶段证据 `700 passed`，早期 closure 证据 `682 passed`。
- Maven JDK 21 reactor 发布审计记录 `868 tests, 0 failures, 0 errors, 0 skipped`。
- RAG artifact 阶段记录 Python Runtime 全量单元测试 `700 passed`，agent-runtime 模块测试 `483 passed`。
- RAG answer artifact 真实 MinIO smoke 记录 `RAG_ARTIFACT_MINIO_SMOKE_SUCCEEDED`。
- Keycloak PostgreSQL-backed 身份存储阶段记录备份恢复检查 `PASS=35, WARN=1, FAIL=0`，本地 Keycloak smoke 通过。
- 生产就绪静态门禁曾达到 `PASS=33, WARN=0, FAIL=0`。
- 最终闭环审计曾达到 `PASS=93, WARN=0, FAIL=0`。
- 项目定位为工程发布候选，后续客户生产上线仍需完成真实 Secret、TLS/mTLS、IdP、K8s、SBOM、容量压测、备份恢复和故障演练。

## 4. 更精简的简历版本

DataSmart Govern Pro 企业级多 Agent 数据治理平台

- 基于 Java 21、Spring Boot 3.5、Spring Cloud Gateway、Kafka、PostgreSQL/pgvector、Redis、Python FastAPI、LangGraph 建设企业级数据治理 Agent 后端，覆盖数据源、同步、质量、任务、权限、RAG、Skill、MCP 和可观测。
- 负责 Agent Runtime 控制面设计，将 Java 业务事实、权限、审批、outbox、worker receipt 与 Python 意图分析、工具规划、RAG、多 Agent checkpoint 解耦，避免模型侧直接执行高风险副作用。
- 实现 Gateway HMAC 可信上下文、permission-admin 工具预算/Skill 准入/审批事实、task-management 租约/幂等/defer/dead-letter、data-sync 执行租约恢复和低敏 receipt outbox。
- 推进 RAG command worker、MinIO/S3 artifact writer、Java artifact grant/final-check、LangGraph durable checkpoint 与 Java turn runner projection，保证 runtime event/checkpoint/receipt 只保存低敏 locator、状态、计数和策略 code。
- 同步身份治理能力：接入 Keycloak/企业 IdP 账号供应、本地影子身份映射、Gateway 身份管理路由和 Keycloak PostgreSQL-backed 身份存储。
- 仓库工程证据：Python Runtime 全量测试最高记录 700 passed，Maven reactor 审计 868 tests 0 failures，agent-runtime 模块 483 passed，生产就绪静态门禁 PASS=33，闭环审计 PASS=93。

## 5. 面试自我介绍版本

我最近重点做的是一个企业数据治理方向的多 Agent 后端平台，名字叫 DataSmart Govern。这个项目和普通 Agent Demo 不太一样，它不是让模型直接连工具执行，而是把 Agent 放进企业后端控制面里。

整体架构上，Java 21 和 Spring Cloud 微服务负责业务事实、权限、审批、审计、任务状态、outbox 和 worker receipt，Python FastAPI 和 LangGraph 负责意图分析、上下文构建、工具规划、RAG、多 Agent turn runner 和 checkpoint。Gateway 会先调用 permission-admin 做授权，再把租户、角色、数据范围、工具预算、Skill 可见性这些可信上下文用 HMAC 签名传给 Python Runtime，防止客户端直连 Python 伪造权限。

我主要做了三块：第一是 Agent 控制面，包括 session/run、tool registry、tool audit、DAG plan、approval、command proposal/outbox、artifact grant、Skill lifecycle 和 runtime event projection；第二是异步可靠性，包括 task-management 的状态机、租约、heartbeat、defer、dead letter、回调幂等，以及 data-sync 的 execution lease、pause/cancel 控制信号、过期租约恢复和 receipt outbox；第三是 AI Runtime 的低敏化和可恢复，包括 RAG 证据门控、KNOWLEDGE_AGENT 能力合同、LangGraph durable checkpoint 和 Java turn runner checkpoint locator projection。

后续我又把 RAG 从“可规划”继续推进到“可受控执行”：Java outbox dispatcher 可以投递到 Python RAG command worker，Python 将 answer/citations/compressedContext 写入受控 artifact，Java 只接收低敏 receipt 和 artifactReference，正文读取要经过 metadata authorization、body-read grant 和 final-check。身份体系上，我把 Keycloak/企业 IdP 账号供应接到 permission-admin，并让 Keycloak 使用 PostgreSQL-backed 存储，DataSmart 只保存低敏影子身份，不碰用户密码和 token。

我在这个项目里最重视的是企业上线会问的问题：Agent 为什么有权限做这件事、谁批准的、能不能重试、重复回调会不会改错状态、worker 崩了怎么恢复、RAG 没证据会不会胡说、日志和 checkpoint 会不会泄露客户数据、生产怎么做容量基线和故障演练。当前仓库是工程发布候选，有 Python Runtime 全量测试最高阶段证据 700 passed、Maven reactor 审计 868 个测试 0 失败、agent-runtime 模块 483 passed、MinIO/Keycloak smoke、生产就绪和闭环审计门禁通过的证据，但我会明确说明真实客户上线还要做 Secret、TLS/mTLS、企业 IdP、K8s、SBOM、压测、备份恢复和故障演练。

## 6. STAR 展开素材

### 场景一：Agent 工具不能直接执行高风险动作

Situation：数据治理平台里 Agent 可能生成同步任务、质量规则、数据导出或命令执行，如果只靠 prompt 限制模型，很容易出现越权、误执行和审计缺失。

Task：设计一套企业可接受的 Agent 工具治理链路，让 Agent 可以规划工具，但真实执行必须可控。

Action：

- 在 permission-admin 设计工具预算、Skill 准入和审批事实。
- 在 agent-runtime 设计 tool registry、tool execution audit、approval/reject/execute、command proposal/outbox。
- 在 Python Runtime 只生成低敏 tool plan 和 readiness，不直接写业务事实。
- 高风险动作进入 `WAITING_APPROVAL`，审批事实由服务端校验 tenant/project/actor/session/run/command/tool/expiry/policyVersion。

Result：形成“模型建议、控制面决策、人审兜底、worker 执行、receipt 回填、审计可查”的链路，避免 Agent 直接越权写业务库。

### 场景二：worker 崩溃或重复回调导致状态混乱

Situation：任务和同步 execution 都是长时间异步执行，worker 会崩溃、网络会超时、回调会重试，不能假设请求只发生一次。

Task：保证任务可以恢复，重复回调不重复推进状态。

Action：

- 用数据库条件更新完成 claim，只有一个 worker 能把任务改成 RUNNING。
- 用 executorId、leaseExpireTime、heartbeatTime 做租约续期和 fencing。
- 用 `PROCESSING`/`SUCCEEDED` 幂等记录和数据库唯一键识别重复回调。
- 对资源不足引入 `DEFERRED`，超过上限进入 `DEAD_LETTER` 或 `AWAITING_OPERATOR_ACTION`。

Result：系统能区分业务失败、容量背压和执行环境异常，便于自动恢复和人工介入。

### 场景三：RAG 不能泄露客户数据

Situation：RAG 和 Agent checkpoint 容易把用户问题、文档正文、sourceUri、模型回答写进日志、事件或 checkpoint，形成隐性数据泄露。

Task：让 RAG 可观测、可恢复，同时保持低敏。

Action：

- RAG tool plan 只保存 `queryRef/scopePolicy/evidencePolicy`。
- LangGraph checkpoint 只保存检索候选数、证据接受数、弱证据拒绝数、节点状态、策略 code 和低敏恢复摘要。
- `KNOWLEDGE_AGENT` 只暴露能力合同，不保存 question、answer、sourceUri、compressedContext。
- 无足够证据时走 fail-closed，不让模型无依据生成治理结论。

Result：RAG 成为可调度、可审计、可恢复的 Agent 能力，同时减少 prompt、证据和模型输出泄露面。

### 场景四：RAG 答案正文不能进入 Java 控制面

Situation：RAG command worker 能生成答案后，如果直接把 answer、citations、compressedContext 写入 Java receipt 或 runtime event，会让模型输出和证据正文扩散到审计、日志、projection、checkpoint。

Task：既要支持用户在授权后查看完整答案，又要保证 Java 控制面保持低敏。

Action：

- 在 Python Runtime 定义 `RagAnswerArtifactWriter` 协议，支持 local 和 MinIO/S3-compatible backend。
- Python worker 将 answer、citations、compressedContext 写入受控 artifact，只返回 artifactReference、contentSha256、byteSize、citationCount、objectKeyDigest。
- Java 侧把 RAG receipt 识别为 `RAG_QUERY_COMPLETED` 和 `READ_ONLY_QUERY_SUMMARY`，保持 `sideEffectExecuted=false`。
- Java artifact 链路新增 `RAG_ANSWER_VIEW`，通过 metadata authorization、body-read grant、final-check 三段式控制正文读取。
- 使用真实 MinIO smoke 验证 Python objectName 与 Java locator/probe 合同一致。

Result：RAG answer 正文可被受控保存和授权读取，但不进入 Java timeline、receipt、projection、checkpoint 或日志。

### 场景五：企业账号体系不能自研密码登录

Situation：企业客户通常已有 IdP，或者需要系统自带 Keycloak。自研 username/password 登录会带来密码存储、MFA、账号锁定、审计和合规风险。

Task：让平台支持账号管理闭环，但不在 DataSmart 保存密码或 token。

Action：

- 采用 OIDC/Keycloak/企业 IdP 登录路线，Gateway 作为 OAuth2 Resource Server 校验 access token。
- permission-admin 新增身份供应能力，支持注册、禁用、重置密码和能力查询。
- 本地保存 `PermissionIdentityUser` 影子身份，只记录 providerUserId、tenantId、actorId、actorRole、workspaceId、status。
- Gateway 新增 `/api/identity/**` 路由和 `IDENTITY_USER` 权限语义。
- Keycloak 从 dev 文件卷迁移到 PostgreSQL-backed 存储，并新增 bootstrap 脚本补齐已有 volume 不重跑 initdb 的问题。

Result：平台具备账号供应和登录集成闭环，同时真实密码、MFA、会话、client secret 仍归 IdP 管理，DataSmart 只保留低敏身份映射和审计。

## 7. 技术关键词

可放在简历技能或项目关键词中：

- AI Agent Control Plane
- LangGraph Durable Checkpoint
- Multi-Agent Turn Runner
- Tool Governance
- Skill Admission
- Human-in-the-loop Approval
- Agent Runtime Event Projection
- Outbox Pattern
- Worker Receipt
- Lease and Fencing
- Idempotency Key
- Dead Letter
- Backpressure Defer
- Gateway HMAC Trusted Context
- RBAC and Data Scope
- RAG Evidence Gate
- Fail-closed RAG
- RAG Command Worker
- RAG Answer Artifact
- Artifact Body Read Grant
- Artifact Final Check
- MinIO/S3-compatible Artifact Store
- Low-sensitive Runtime Event
- Model Provider Abstraction
- MCP Client
- A2A Handoff
- Keycloak/OIDC
- Identity Provisioning
- Shadow Identity Mapping
- PostgreSQL-backed Keycloak
- Prometheus/Grafana Observability
- Production Hardening

## 8. 避免过度表述

不要写：

- “已在客户生产环境稳定运行”。
- “完整实现 ETL/资产/合规脱敏所有 Agent”。
- “Python Runtime 直接完成真实工具执行闭环”。
- “RAG 已完成 pgvector、MinIO 文档解析、Neo4j GraphRAG 全生产链路”。
- “RAG answer artifact 已经等同于完整下载服务或 DLP 服务”。
- “DataSmart 自己保存用户密码和 token”。
- “模型使用 Qwen2 固定方案”。

建议写：

- “达到工程发布候选，具备本地闭环和测试证据”。
- “Java/Python 控制面解耦，真实副作用通过 Java proposal/outbox 和 worker receipt 承接”。
- “ETL/资产/合规脱敏保留产品目录和轻量能力，后续作为下一版本增强项”。
- “RAG 已接入 KNOWLEDGE_AGENT、Java outbox -> Python command worker、answer artifact、grant/final-check 和 MinIO smoke；生产知识库索引、DLP/下载服务和全平台 E2E 仍需继续验收”。
- “身份体系采用 Keycloak/企业 IdP，DataSmart 只保存低敏影子身份，不保存密码、refresh token 或 client secret”。
- “模型路由采用 provider-neutral 架构，模型族可替换”。
