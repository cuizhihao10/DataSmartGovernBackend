# DataSmart Govern AI Agent 项目技术文档与学习路径

## 1. 项目定位与当前成熟度

DataSmart Govern 是一个企业级多 Agent 数据治理平台后端项目，目标是围绕数据源接入、数据同步、数据质量、任务调度、权限治理、Agent 工具治理、RAG 知识问答和可观测运维，构建一个可商业化演进的数据治理控制面。

学习和面试时要先把项目边界讲清楚：

- 当前仓库已经达到“工程发布候选”状态，不等于已经完成真实客户生产上线。
- Java 微服务、Gateway、permission-admin、task-management、data-sync、data-quality、agent-runtime、Python AI Runtime、LangGraph、RAG、Skill、MCP/A2A、可观测与生产加固文档都有真实源码或测试证据。
- 真实生产上线仍需要客户环境完成 Secret 注入、TLS/mTLS、企业 IdP 联调、Kubernetes/Helm 部署、SBOM、镜像签名、容量压测、备份恢复、故障演练、告警值班流程。
- ETL、数据资产、合规脱敏、反思优化等 Agent 角色保留产品目录和轻量能力，不应在简历或面试中说成已经完整生产闭环。

截至仓库当前状态可引用的证据：

- `docs/final-platform-closure-audit.md`：2026-07-02 标记为 Engineering Release Candidate。
- Python Runtime 全量测试在当前状态记录中最高阶段证据为 `700 passed`，早期 closure 证据为 `682 passed`。
- Maven JDK 21 reactor 全量测试在发布审计中为 `868 tests, 0 failures, 0 errors, 0 skipped`。
- 生产就绪静态门禁曾达到 `PASS=33, WARN=0, FAIL=0`。
- 最终闭环审计曾达到 `PASS=93, WARN=0, FAIL=0`。

面试表述建议：

> 这个项目不是简单的 LangChain Demo，而是把 AI Agent 放进企业数据治理控制面里。我的重点不是让模型直接执行高风险动作，而是把意图、工具、审批、租约、幂等、outbox、worker receipt、审计、可观测和生产加固边界都设计出来。当前仓库是工程发布候选，已经有大量测试和静态门禁证据，但我会明确区分本地闭环和客户生产上线。

## 2. 固定技术栈

后端固定栈：

- JDK 21
- Spring Boot 3.5.11
- Spring Cloud 2023.0.3
- Spring Cloud Alibaba 23.0.1.2
- MyBatis-Plus 3.5.14
- PostgreSQL/pgvector 作为目标系统事实库、语义检索和 Agent 记忆库
- MySQL 保留为迁移兼容，不作为新增能力的目标绑定
- Redis 用于短期状态、会话、缓存、限流和协调
- Kafka 用于 Java 业务服务与 Python AI Runtime 的异步解耦
- MinIO 用于报告、导出、artifact 和文件对象
- Neo4j 作为知识图谱方向
- Prometheus/Grafana/Actuator 用于可观测

AI Runtime 栈：

- Python FastAPI
- LangGraph
- provider-neutral 模型路由
- OpenAI-compatible provider 适配方向
- RAG、LangGraph durable checkpoint、MCP outbound client、A2A、多 Agent turn runner
- 模型选择不绑定旧 Qwen2，推荐以 Qwen3.5、DeepSeek-V3.2 或同等级当前主流开源/开放权重模型为候选，通过 provider adapter 屏蔽模型族差异。

架构原则：

- Java 业务服务保存业务事实，Python Runtime 不成为唯一事实源。
- Python 负责意图分析、上下文构建、规划、RAG、低敏协作状态和模型能力编排。
- 高风险副作用必须回到 Java agent-runtime 的 proposal/outbox、审批事实、worker receipt 和审计链路。
- Java 与 Python 通过 Kafka/outbox 或受控 HTTP/gRPC 合同解耦，避免同步调用链无限拉长。
- 所有跨边界数据都尽量低敏化：事件、checkpoint、metric、cache、日志不能保存 prompt、SQL、样本数据、凭据、模型原文和 artifact 正文。

## 3. 仓库模块地图

| 模块 | 定位 | 学习重点 |
|---|---|---|
| `platform-common` | 统一响应、错误码、租户/操作者上下文、低敏头部合同 | 所有模块共享但不能沉淀业务逻辑 |
| `gateway` | Spring Cloud Gateway、OIDC/JWT 入口、权限判定、HMAC 可信上下文、Skill 可见性缓存 | 企业 Agent 入口安全与上下文可信传递 |
| `permission-admin` | RBAC、项目成员、权限判定、Agent 工具预算、Skill 准入、审批事实、审计/outbox | 把权限从“菜单控制”扩展成“Agent 动作治理” |
| `task-management` | 平台通用任务生命周期、租约、心跳、重试、defer、dead letter、回调幂等 | 可靠异步任务控制面 |
| `datasource-management` | 数据源注册、连接诊断、元数据和同步模板 | 数据治理的连接器入口 |
| `data-sync` | 同步任务、执行记录、状态机、worker loop、租约恢复、receipt outbox | 数据移动场景的可靠执行控制 |
| `data-quality` | 质量规则、执行、异常、报告、整改任务、worker receipt | 数据质量治理链路 |
| `agent-runtime` | Java Agent 控制面，session/run/tool audit/DAG/approval/outbox/artifact/Skill/projection/model route | AI Agent 在企业后端里的“受控执行骨架” |
| `observability` | 平台健康、闭环就绪、告警覆盖、Prometheus/Grafana 配置 | 上线门禁和运维可解释性 |
| `python-ai-runtime` | FastAPI、Agent Orchestrator、Intent、ToolPlanner、RAG、LangGraph、MCP/A2A、memory/checkpoint | AI 能力层和多 Agent 编排层 |

## 4. 推荐学习路径

### 阶段 0：先跑通环境和证据

目标：知道项目能否构建、测什么、如何判断不是“纸面架构”。

阅读顺序：

1. `README.md`
2. `pom.xml`
3. `docs/development-jdk21.md`
4. `docs/local-e2e-closure-runbook.md`
5. `docs/final-platform-closure-audit.md`
6. `docs/production-hardening-runbook.md`

应该掌握：

- 为什么必须使用 JDK 21 和 Maven Toolchains。
- Docker Compose 只是本地闭环，不是生产编排。
- 本地 smoke、生产就绪静态门禁、最终闭环审计分别验证什么。
- 日志中的模拟异常、fail-closed、重试、死信日志不一定代表测试失败，要看进程退出码和 Surefire XML。

练习任务：

- 列出所有 Maven module。
- 找到每个服务端口和 health endpoint。
- 解释“Engineering Release Candidate”和“客户生产上线”的区别。

### 阶段 1：理解平台公共契约

阅读路径：

- `platform-common/src/main/java/...`
- 各模块 `GlobalExceptionHandler`
- 各模块 controller DTO

学习重点：

- 统一响应和错误码用于让前端、网关、运维脚本获得稳定语义。
- 租户、操作者、工作区、请求来源通过统一 header/context 传递。
- 异常不能把内部堆栈、SQL、连接信息、密钥打给用户。

面试可讲：

> 我会先建立统一错误语义，否则 Agent 工具调用失败后只能看到 500，无法区分权限失败、状态冲突、下游不可用、参数错误和审批阻断。这个项目里 ResponseStatusException 的状态码需要保留，不能被全局异常处理统一包成 500。

### 阶段 2：学习 Gateway 安全入口

核心类：

- `GatewayAuthorizationFilter`
- `GatewaySkillVisibilityCacheContextFilter`
- `GatewayPythonRuntimeSignatureFilter`
- `GatewayInternalServiceEndpointGuard`
- `GlobalExceptionHandler`

关键实现：

- Gateway 在转发前调用 permission-admin 做 route-level 授权。
- 支持 public path、internal endpoint guard、decision cache、shadow mode、fail-open/fail-closed。
- 授权结果会注入数据范围 header，包括 data scope level、data scope expression、authorized project ids、approval required。
- 路由动作优先来自 route metadata，而不是简单用 HTTP method 推断，因为 POST 可能代表 create、retry、approve、cancel、export 等完全不同语义。
- `/api/agent/plans` 前置生成 Skill 可见性缓存上下文，只基于租户、角色、工作区、数据范围、预算策略，不基于 prompt/body。
- Gateway 对发往 Python Runtime 的可信上下文 header 做 HMAC-SHA256 签名，并先清理外部伪造的签名 header。

实现原理：

- HMAC 不是为了加密，而是为了证明“这些 header 是 gateway 生成的，不是客户端伪造的”。
- 签名只覆盖可信 header 快照，不覆盖 body，是为了避免 reactive gateway 中缓存 request body 导致背压和内存风险。
- body 完整性、机密性和防篡改依赖 HTTPS/mTLS、服务网格、内网隔离或 API Gateway 入口策略。

典型问题：

- 权限中心不可用时，后台管理和高风险入口应 fail-closed，低风险灰度场景可通过配置 fail-open。
- 初次上线权限矩阵容易误杀，可以先 shadow mode，只记录“如果拦截会拒绝什么”。
- 缺少 Spring Cloud LoadBalancer 时，网关可能把下游不可用包装成 500，项目中通过补齐 loadbalancer/Caffeine 并保留 503 语义修复。

### 阶段 3：学习 permission-admin 的 Agent 治理

核心能力：

- RBAC 和项目成员权限。
- Agent 工具预算策略。
- Skill admission policy。
- Tool action approval fact。
- 权限 outbox、审计、缓存快照和手动 retry/ignore。

技术要点：

- `AgentToolBudgetPolicyServiceImpl` 根据角色、租户套餐、workspace 风险、worker backlog、工具风险输出预算策略。
- 预算策略包括最大 proposed tool calls、最大自动执行工具数、最大高风险工具数、参数字节限制、readiness policy 和影响原因码。
- `AgentSkillAdmissionPolicyServiceImpl` 只做准入判断，不执行 Skill。高风险 Skill 在关键 workspace、权限缺失、租户禁用、角色不支持时拒绝或条件允许。
- `AgentToolActionApprovalFactServiceImpl` 把审批事实保存在服务端，而不是把“已批准”写进 prompt 或 task params。
- 审批事实校验 tenant/project/actor/session/run/command/tool/expiry/policyVersion/status，避免跨租户复用或过期复用。

实现原理：

- Agent 的工具权限不能只靠模型自觉，也不能只靠 prompt 约束。必须在服务端生成可审计、可过期、可版本化的事实。
- 工具预算是防止 Agent 一次规划过多高风险动作，造成成本、权限和运维风险。
- Skill 准入和 Skill 发布生命周期是两层含义：READY 代表可以进入 manifest 或 marketplace，不代表当前用户、租户、workspace 一定可执行。

### 阶段 4：学习 task-management 的异步可靠性

核心类：

- `TaskStatus`
- `TaskExecutionRunState`
- `TaskLifecycleSupport`
- `TaskMapper`
- `TaskCallbackIdempotencySupport`

主表状态：

- `PENDING`
- `RUNNING`
- `PAUSED`
- `RETRYING`
- `DEFERRED`
- `DEAD_LETTER`
- `SUCCESS`
- `FAILED`
- `CANCELLED`

执行记录状态：

- `RUNNING`
- `SUCCESS`
- `FAILED`
- `TIMEOUT`
- `DEFERRED`
- `CANCELLED`

关键设计：

- 任务主状态表达“这个任务作为业务对象现在处于什么阶段”。
- Run 状态表达“某一次执行尝试的结果是什么”。
- 一个 Task 可以有多条 Run，因此失败、重试、超时、defer、事故复盘都可以保留证据。
- `DEFERRED` 是背压退避，不是业务失败。资源不足、租户配额不足、下游限流时执行器可以主动延迟回队列。
- 多次 defer 超过上限进入 `DEAD_LETTER`，停止自动推进，由运维判断是扩容、降级、调整配额还是人工重试。

队列与租约实现：

- `TaskMapper.selectNextClaimCandidate` 把 `PENDING` 和已到期 `DEFERRED` 作为轻量队列。
- 按 priority 排序，同一优先级按 queued_time FIFO。
- 认领通过条件更新：只有 `status in ('PENDING','DEFERRED')` 且 queued_time 到期的记录能被改成 `RUNNING`。
- 多个 worker 同时看到候选任务时，只有一个能更新成功。
- heartbeat 必须匹配当前 `executorId`，避免旧 worker 或竞争 worker 续租。

幂等实现：

- 回调先插入 `PROCESSING` 幂等记录。
- 唯一键冲突说明 `taskId + action + idempotencyKey` 已经处理或正在处理。
- 业务状态推进成功后标记 `SUCCEEDED`。
- 当前事务模型下，业务推进失败会和 `PROCESSING` 一起回滚，避免“幂等键占用但任务没推进”的悬挂状态。

面试重点：

> 我不会把 Kafka 或 HTTP 回调当成天然 exactly-once。项目里通过数据库唯一索引做并发裁判，用状态机做业务合法性，用租约和 executorId 做执行权 fencing，用 dead letter 把持续背压从业务失败里分离出来。

### 阶段 5：学习 data-sync 的领域状态机

核心类：

- `SyncTaskState`
- `SyncExecutionState`
- `SyncTaskStateMachineSupport`
- `DataSyncExecutorLeaseServiceImpl`
- `DataSyncWorkerLoopServiceImpl`
- `SyncCallbackIdempotencySupport`
- `DataSyncTaskManagementReceiptOutboxService`

同步任务状态：

- `DRAFT`
- `CONFIGURED`
- `PENDING_APPROVAL`
- `SCHEDULED`
- `QUEUED`
- `RUNNING`
- `PAUSED`
- `RETRYING`
- `PARTIALLY_SUCCEEDED`
- `SUCCEEDED`
- `FAILED`
- `AWAITING_OPERATOR_ACTION`
- `CANCELLED`
- `ARCHIVED`

同步执行状态：

- `QUEUED`
- `RUNNING`
- `PAUSED`
- `RETRYING`
- `PARTIALLY_SUCCEEDED`
- `SUCCEEDED`
- `FAILED`
- `CANCELLED`

实现方式：

- data-sync 不直接复用 task-management 的 `TaskStatus`，因为同步任务包含配置完整性、审批、checkpoint、部分成功、补数、连接器版本等领域语义。
- `claim` 通过条件更新完成并发裁决。
- `heartbeat` 只有当前 executor 可以续租。
- `defer` 允许执行器暂时放弃本次 execution 并延迟回队。
- 心跳阶段先检查 pause/cancel 控制信号，再做幂等判断和续租，避免重复 idempotencyKey 掩盖最新停止指令。
- 租约恢复扫描过期 execution，达到上限后把 task 推入 `AWAITING_OPERATOR_ACTION`。
- worker loop 只做胶水：claim、加载模板、dispatch run-once、complete/fail。模板缺失或 dispatch 异常要 fail-closed，不能让 execution 长期卡在 RUNNING。
- 通过 receipt outbox 向 task-management 发低敏执行回执，状态包括 PENDING、DELIVERING、RETRY_WAIT、DELIVERED、DEAD_LETTER，采用稳定 `receiptId` 做幂等。

为什么要有 `AWAITING_OPERATOR_ACTION`：

- 普通 `FAILED` 表示业务执行失败，例如 SQL 错误、字段映射错误、目标端唯一键冲突。
- 多次租约过期、反复 defer、连接器持续不稳定更像容量、配额、环境或外部系统问题，需要运营台显式介入。
- 把二者分开后，告警、报表和事故复盘不会把“业务失败”和“系统背压”混在一起。

### 阶段 6：学习 agent-runtime Java 控制面

核心对象：

- Agent session
- Agent run
- Tool registry
- Tool execution audit
- DAG plan/preview/dry-run
- Approval/reject/execute
- Async command plan/outbox
- Command safety precheck
- Worker receipt
- Artifact access/body grant/final check
- Skill registry/publication lifecycle
- Runtime event projection/replay
- Skill visibility diagnostics
- Model route
- A2A
- MCP worker receipt ingestion
- Turn runner projection

关键状态：

- `AgentRunState`：`CREATED`、`PLANNING`、`WAITING_MODEL`、`TOOL_CALLING`、`WAITING_HUMAN`、`SUCCEEDED`、`REJECTED`、`FAILED`、`CANCELLED`。
- `AgentToolExecutionState`：`PLANNED`、`WAITING_APPROVAL`、`EXECUTING`、`SUCCEEDED`、`FAILED`、`SKIPPED`、`CANCELLED`。
- `AgentSkillPublicationLifecycleStatus`：`DRAFT`、`IN_REVIEW`、`READY`、`REJECTED`、`DEPRECATED`。

设计要点：

- `AgentRunState.isTerminal()` 防止成功、失败、取消、拒绝后的并发回调把状态回滚。
- `REJECTED` 不等于 `FAILED`。前者是人或策略拒绝高风险动作，是安全拦截成功；后者是系统、模型、工具或下游错误。
- 工具执行审计状态和下游业务状态分离。Agent Runtime 记录“某个 run 准备或正在调用某工具”，下游任务模块记录真正业务对象生命周期。
- Skill READY 只说明发布物可进入 manifest，不代表当前请求有执行授权。
- Turn runner projection 从 Python runtime event 中解析低敏状态，回答“多 Agent turn 卡在哪里、哪个 checkpoint 可以恢复、缺什么 host fact”。

`agentTurnRunnerCheckpoint` 当前能力：

- Python Runtime 写入低敏 LangGraph durable checkpoint。
- Java agent-runtime 通过 runtime event projection 查询 checkpoint locator。
- locator 包含 threadId、checkpointId、parentCheckpointId、graphName、graphVersion、nodeName、checkpointStatus、nextNodes、resumeRequirementKeys 等。
- locator 不包含 checkpoint state 正文、用户目标、prompt、ToolPlan.arguments、SQL、样本数据、模型输出、RAG answer、artifact body、endpoint、token 或 secret。
- 当前 Java 只查询 locator，尚未直接读取 PostgreSQL `ai_memory.langgraph_thread_checkpoint` 表，也未真正执行 pause/resume/fork/recover。

### 阶段 7：学习 Python AI Runtime

重点目录：

- `python-ai-runtime/src/datasmart_ai_runtime/api`
- `python-ai-runtime/src/datasmart_ai_runtime/services`
- `python-ai-runtime/src/datasmart_ai_runtime/services/rag`
- `python-ai-runtime/src/datasmart_ai_runtime/services/multi_agent`
- `python-ai-runtime/src/datasmart_ai_runtime/services/memory`
- `python-ai-runtime/src/datasmart_ai_runtime/services/agent_execution`
- `python-ai-runtime/tests`

核心链路：

1. `/agent/plans` 接收网关透传的可信上下文。
2. 删除 body 中伪造的 trustedControlPlane。
3. 构建上下文块。
4. 结构化意图分析。
5. 规则式和上下文驱动工具规划。
6. 结合权限、Skill 可见性、预算、readiness policy 生成低敏计划。
7. 生成 runtime event。
8. 可选写入 LangGraph durable checkpoint。
9. 返回 AgentPlan、agentTurnRunner、checkpoint locator。

重要实现点：

- `RuleBasedIntentAnalyzer` 识别治理域，如 datasource、data_quality、data_sync、task_management、permission_admin、knowledge_qa。
- `ToolPlanner` 不直接保存原始问题，RAG 工具只生成低敏 `queryRef/scopePolicy/evidencePolicy`。
- `knowledge.rag.query` 是工具目录能力，`knowledge.rag.answer` 是 Skill 能力。
- RAG 通过 `KNOWLEDGE_AGENT` 接入多 Agent runner，但当前 runner 仍主要输出能力合同和 checkpoint，不代表已经自动执行真实工具副作用。
- RAG API 支持 `/agent/rag/query` 和 `/api/agent/rag/query`，并记录 LangGraph checkpoint 节点：`rag_retrieve_knowledge`、`rag_evidence_gate`、`rag_grounded_answer_completed`、`rag_no_evidence_completed`。
- checkpoint 中只保存计数、策略 key、节点、状态和低敏恢复摘要，不保存问题、答案、证据正文或 sourceUri。
- MCP outbound client 支持受控 Streamable HTTP/stdio、真实 `initialize`、`tools/list`、`tools/call`、工具命名空间和 admission gate。

为什么这样设计：

- AI Runtime 可以快速迭代模型和规划逻辑，但不能越权成为业务事实源。
- prompt、模型输出、工具参数和证据正文都是敏感内容，不能进入 cache、metric、runtime event 或 checkpoint。
- Agent 规划和真实副作用执行必须分离。规划可以同步返回，副作用必须经过 Java 控制面审批、outbox、worker receipt 和审计。

### 阶段 8：学习 RAG、模型路由与评测

RAG 当前定位：

- 面向治理知识、业务口径、数据标准、runbook 解释类问题。
- 已作为 `KNOWLEDGE_AGENT` 能力接入多 Agent turn runner。
- 已有 LangGraph checkpoint 节点化和 fail-closed 证据门控。
- 尚未完整落地 PostgreSQL/pgvector Knowledge Store、MinIO 文档解析、增量索引、专用 reranker 和 Neo4j GraphRAG 生产链路。

评测维度：

- 检索召回率：给定标准问题能否召回相关文档。
- 证据有效率：召回内容是否满足 scope、租户、项目、时间、版本约束。
- grounded answer：回答是否只基于证据，不编造。
- fail-closed：无证据或弱证据时是否拒答或给出安全说明。
- 低敏合规：日志、事件、checkpoint 是否不保存问题原文、文档正文、sourceUri、模型回答。
- 延迟与成本：检索、rerank、生成、总耗时和 token 使用。

模型路由原则：

- 不把业务模块绑定到某个模型名。
- 通过 generation、code、multimodal、embedding、reranker、model routing adapter 分层。
- provider 失败时需要超时、重试、降级、熔断、健康检查、usage 统计。
- 面试中不要说“项目必须用 Qwen2”，应说“旧文档有 Qwen2 痕迹，当前策略是 provider-neutral，并优先考虑当前主流模型族”。

### 阶段 9：学习可观测与生产加固

核心文档：

- `docs/production-hardening-runbook.md`
- `docs/backup-restore-runbook.md`
- `docs/capacity-baseline-runbook.md`
- `docs/failure-drill-runbook.md`
- `docs/kubernetes-helm-deployment.md`

生产加固重点：

- Secret Manager，不把密码、HMAC secret、OIDC client secret、模型 API Key 写进 `.env` 或镜像。
- TLS/mTLS，南北向和东西向都要有清晰边界。
- Kubernetes/Helm，不把 Compose 当生产编排。
- SBOM、镜像签名、漏洞扫描、固定 tag。
- PostgreSQL/MySQL、Redis、Kafka、MinIO、Neo4j、Keycloak、Nacos 的备份恢复策略。
- 容量基线：Gateway、Java 服务、Python Runtime、Kafka、数据库、对象存储、RAG、模型 provider。
- 故障演练：Keycloak 不可用、下游服务不可用、Kafka 暂停、Python Runtime 不可用、数据库不可用、Chroma/Neo4j 不可用。

上线前必须回答的问题：

- 哪些 worker/dispatcher/真实副作用默认关闭？
- 谁能打开？
- 打开前是否完成审批、审计、幂等、死信、回滚、告警、值班？
- 告警能否定位到 tenant/project/run/execution/worker/traceId？
- 事故恢复时是否能从 run、outbox、receipt、checkpoint 还原链路？

## 5. 核心技术专题

### 5.1 Java/Python 分层

实现方式：

- Java 管业务事实、权限、审批、审计、outbox、worker receipt。
- Python 管规划、RAG、模型路由、LangGraph 低敏状态、多 Agent 协作摘要。
- Gateway 用 HMAC 把可信 header 传给 Python。
- Python 产出的计划要回到 Java 控制面才能真正执行高风险动作。

优势：

- Java 服务稳态、事务、审计、权限更适合企业后端。
- Python 生态适合快速接 LangGraph、RAG、模型 provider、MCP。
- 两边独立扩展，模型迭代不会破坏业务事实库。

代价：

- 合同更多，调试链路更长。
- 需要严格低敏字段规范。
- 需要 outbox、幂等、traceId 和 projection 保证跨服务可解释。

### 5.2 状态机

实现方式：

- 用显式 enum/常量表达生命周期。
- 对每个转移动作做方法级校验。
- 终态不可回滚。
- 主对象状态和执行记录状态分离。

原理：

- 长任务、Agent run、同步 execution 都不是一次 HTTP 请求能完成的。
- 状态机让系统知道“可以做什么、不能做什么、需要谁介入”。

常见坑：

- 把 `FAILED` 当成万能错误桶。
- 不区分 `REJECTED` 和 `FAILED`。
- 不区分 task 主状态和 run 状态。
- 高并发回调导致终态被覆盖。

### 5.3 租约与 fencing

实现方式：

- worker 通过条件更新 claim。
- claim 成功后写入 executorId、leaseExpireTime、heartbeatTime。
- heartbeat 必须匹配 executorId。
- 租约过期后恢复扫描负责 requeue 或转人工关注。

原理：

- 租约不是锁的替代品，而是一种可恢复执行权。
- executorId 是 fencing token 的简化形式，用于防止旧 worker 在失去执行权后继续写入。

### 5.4 幂等

实现方式：

- 使用业务唯一键或 idempotencyKey。
- 先插入 `PROCESSING`，唯一键冲突即判重。
- 成功后更新 `SUCCEEDED`。
- 重复请求刷新 lastSeenTime，但不重复推进状态。

原理：

- 网络重试、worker 重启、Kafka 重投、HTTP 超时都会导致重复回调。
- exactly-once 不能依赖中间件承诺，必须在业务落库处做幂等裁决。

### 5.5 Outbox 与 worker receipt

实现方式：

- 业务事务内写 outbox。
- dispatcher 异步发送。
- 状态包括 PENDING、DELIVERING、RETRY_WAIT、DELIVERED、DEAD_LETTER。
- receiptId 稳定，消费者按 receiptId 幂等。

原理：

- 避免“数据库提交成功但消息发送失败”。
- 避免“消息发出但业务事务回滚”。
- 把跨服务状态同步变成可重试、可观测、可人工处理的过程。

### 5.6 低敏控制面

实现方式：

- runtime event 只保存状态、计数、策略 code、role、checkpoint locator。
- checkpoint 只保存恢复所需摘要。
- RAG tool plan 只保存 `queryRef`，不保存 question。
- artifact 先保存 metadata，正文读取需要 grant/final check。

原理：

- Agent 系统最容易泄露 prompt、SQL、业务样本、客户数据、模型输出。
- 控制面要能调度和排障，但不应把敏感正文复制到每个系统里。

### 5.7 Skill 与工具治理

实现方式：

- Skill registry 管元数据和发布生命周期。
- Skill admission policy 决定当前请求是否可见、可用、条件可用。
- Tool registry 管工具 schema、风险、权限。
- Tool budget 控制一次规划能提出和自动执行多少工具。
- 高风险动作进入 WAITING_APPROVAL。

原理：

- Skill 是能力声明，Tool 是可执行接口，Approval 是本次动作授权。
- 这三者不能混成一个 prompt 字符串。

### 5.8 RAG 证据门控

实现方式：

- 检索阶段记录候选数、证据接受数、弱证据拒绝数。
- evidence gate 决定是否允许生成。
- 有证据走 grounded answer。
- 无证据或弱证据走 fail-closed。

原理：

- 企业知识问答宁可拒答，也不能无依据编造治理规则、字段口径或生产操作步骤。

### 5.9 可观测

实现方式：

- Gateway 授权结果、cache 命中、fail-open/fail-closed 指标。
- Agent runtime event projection 和 replay。
- Turn runner projection 查询等待、阻塞、handoff、checkpoint linked count。
- Worker loop、outbox、dead letter、lease recovery 指标。
- Prometheus/Grafana/Alertmanager 配置和 runbook。

原则：

- metric 低基数。
- log 低敏。
- traceId 贯穿 gateway、Java、Python、worker。
- 告警要能对应 runbook 动作。

## 6. 功能特性技术要点速查

| 功能 | 实现方式 | 实现原理 | 优势 | 风险与补救 |
|---|---|---|---|---|
| 网关授权 | Gateway 调 permission-admin，注入数据范围 header | 入口先判定资源和动作，再转发 | 集中治理，便于审计 | 权限中心故障需 fail-open/closed 策略 |
| Python Runtime 签名 | HMAC-SHA256 签可信 header | 防止客户端伪造租户、角色、预算和数据范围 | 低成本、跨语言简单 | body 未签名，生产需要 TLS/mTLS |
| Skill 可见性缓存 | Gateway 生成低敏 cache context | prompt 不进入缓存 key | 降低 Python 重复准入计算 | 权限变更要有 TTL/失效策略 |
| 工具预算 | permission-admin 输出预算 envelope | 控制 Agent 单轮工具膨胀和高风险动作 | 成本和风险可控 | 初期策略可能保守，需要指标校准 |
| 审批事实 | 服务端保存 approval fact | prompt 不能代表授权 | 防跨租户、过期、越权复用 | 需要审批 UI/外部审批系统集成 |
| 任务队列 | DB 条件更新 claim | 数据库行更新做并发裁决 | 简单、可解释 | 高吞吐需 Kafka/专用队列扩展 |
| 租约心跳 | executorId + leaseExpireTime | 执行权可恢复且可 fencing | worker 崩溃可恢复 | 需要时钟、扫描频率和阈值调优 |
| 回调幂等 | unique key + PROCESSING/SUCCEEDED | 重复回调不重复推进状态 | 抗重试和重投 | 幂等 key 设计要稳定 |
| defer/dead letter | 背压延迟回队，多次退避进人工 | 区分容量问题和业务失败 | 告警更准确 | 需要运营台处理死信 |
| data-sync receipt outbox | 本地 outbox 发低敏回执 | 事务与消息解耦 | 避免丢消息 | dispatcher 默认生产需谨慎开启 |
| Agent run 状态 | run/session/tool audit 分离 | 审计粒度独立 | 可重试、可复盘 | 状态多，需要文档化 |
| RAG as KNOWLEDGE_AGENT | RAG 能力合同进入 turn runner | 知识问答成为可调度 Agent 能力 | 可观测、可恢复 | 真实执行闭环仍需 outbox/receipt |
| LangGraph checkpoint | 保存低敏恢复摘要 | 状态机可恢复、可审计 | 支持 pause/resume 方向 | 不保存正文，恢复时需重新取证 |
| Model provider abstraction | provider-neutral route | 模型可替换 | 避免供应商锁定 | 需要真实 provider 压测和评测 |

## 7. 生产上线检查清单

上线前必须完成：

1. Secret 管理：OIDC client secret、HMAC secret、模型 API Key、数据库密码全部进入 Secret Manager 或 Kubernetes Secret。
2. 传输安全：Ingress TLS，内部高敏调用评估 mTLS。
3. 身份联调：Keycloak 或企业 IdP issuer、JWKS、client、service account、token 传播规则验证。
4. Helm/K8s：`helm lint`、`helm template`、资源限制、探针、滚动升级、回滚说明。
5. 供应链：SBOM、镜像签名、漏洞扫描、固定 tag。
6. 数据可靠性：数据库、Redis、Kafka、MinIO、Neo4j、Keycloak/Nacos 备份恢复演练。
7. 容量基线：Gateway、Java、Python、Kafka、DB、RAG、模型 provider 的 P95/P99 和 backlog 指标。
8. 故障演练：IdP、gateway、下游服务、Kafka、Python Runtime、DB、RAG store 不可用。
9. 高风险开关：worker、dispatcher、真实工具提交、artifact 正文读取默认关闭，生产启用要有审批和回滚。
10. 值班闭环：告警接收人、runbook、RTO/RPO、事故复盘模板。

## 8. 排障路线

### 8.1 `/api/agent/plans` 返回 401/403

排查：

1. Gateway 是否识别 public/internal path。
2. JWT/OIDC header 是否正确。
3. route metadata 是否把 action 识别错。
4. permission-admin decision 是否 deny。
5. 是否处于 shadow mode。
6. 数据范围和审批 header 是否被正确注入。

### 8.2 Python Runtime 认为 trustedControlPlane 不可信

排查：

1. 请求是否经过 Gateway，而不是直连 Python。
2. HMAC secret 和 keyId 是否一致。
3. timestamp/nonce 是否在允许窗口。
4. Java/Python 签名 header 顺序是否一致。
5. 外部伪造签名 header 是否被 Gateway 清理。
6. 是否把 body 中的 trustedControlPlane 当成可信来源。

### 8.3 任务长期 RUNNING

排查：

1. 当前 executorId 和 leaseExpireTime。
2. heartbeat 是否持续刷新。
3. worker 是否崩溃但租约恢复未扫描。
4. 是否 pause/cancel 但 worker 未收到停止信号。
5. run 状态是否已终态但主表未同步。
6. outbox/receipt 是否卡在 retry 或 dead letter。

### 8.4 重复回调导致状态异常

排查：

1. idempotencyKey 是否稳定。
2. 数据库唯一键是否生效。
3. 是否先执行业务状态变更再写幂等。
4. 失败事务是否留下 PROCESSING 悬挂。
5. duplicate 是否只刷新 lastSeenTime 而没有推进状态。

### 8.5 RAG 回答质量差

排查：

1. 是否命中 `KNOWLEDGE_QA` 意图。
2. tool plan 是否只生成 `queryRef`。
3. scopePolicy 是否过滤掉正确文档。
4. evidence gate 是否因为证据弱而 fail-closed。
5. 是否没有接真实 pgvector/文档索引。
6. reranker、chunk、metadata、版本过滤是否需要优化。

### 8.6 Gateway 下游不可用被包装成 500

排查：

1. Spring Cloud LoadBalancer 依赖是否存在。
2. 服务发现或静态 route 是否正确。
3. GlobalExceptionHandler 是否保留 `ResponseStatusException` 的原状态码。
4. Caffeine/cache 依赖是否满足 loadbalancer 缓存需要。
5. Actuator health 和 route 日志是否显示 503。

## 9. 学习验收题

能答出以下问题，基本说明你已经掌握项目核心：

1. 为什么 Java 和 Python 要分层，为什么不能让 Python Runtime 直接写业务表？
2. Gateway HMAC 解决什么问题，为什么不签 body？
3. Skill READY 和“当前请求可执行”有什么区别？
4. Tool budget、Skill admission、Approval fact 分别解决什么问题？
5. 为什么 TaskStatus 和 TaskExecutionRunState 要分开？
6. `DEFERRED`、`FAILED`、`DEAD_LETTER` 的区别是什么？
7. 租约、heartbeat、executorId 如何防止多个 worker 写同一个 execution？
8. 数据库唯一键在幂等里为什么比内存锁可靠？
9. outbox 解决哪两类消息一致性问题？
10. 为什么 RAG checkpoint 不能保存 question、answer、sourceUri？
11. 多 Agent turn runner 当前完成到什么程度，哪些还没有真实执行？
12. 工程发布候选和客户生产上线之间还差哪些门禁？

## 10. 最新能力同步：RAG Command Worker 与受控 Artifact 闭环

当前仓库在 RAG 链路上已经从“RAG 作为 KNOWLEDGE_AGENT 能力合同”继续推进到更接近生产的受控执行闭环。新的核心链路是：

1. Java `agent-runtime` 生成 `knowledge.rag.query` command proposal/outbox。
2. Java outbox dispatcher 通过 `HttpAgentRagCommandWorkerClient` 调用 Python 内部路由 `/internal/agent/rag/command-worker/run`。
3. Python `RagCommandWorkerRunner` 执行 RAG pipeline。
4. Python 将 answer、citations、compressedContext 写入受控 artifact writer。
5. Python 返回低敏 `javaReceiptPayload`、`artifactWrite` 和 `langGraphCheckpoint`。
6. Java `AgentRagCommandWorkerReceiptIngestionService` 把 receipt 汇入统一 command worker receipt 控制面。
7. Java artifact access/grant/final-check 链路负责后续正文读取授权。
8. MinIO/S3-compatible writer 已通过真实 MinIO smoke 验证 Python objectName 与 Java locator/probe 合同对齐。

### 10.1 Java outbox 到 Python RAG worker

关键类：

- `AgentRagCommandWorkerClientProperties`
- `HttpAgentRagCommandWorkerClient`
- `AgentRagCommandWorkerRunRequest`
- `AgentRagCommandWorkerRunResponse`
- `AgentRagCommandWorkerCallResult`
- `AgentRagCommandWorkerReceiptIngestionService`

实现要点：

- HTTP 只是第一条可测试投递链路，可靠性仍由 Java outbox dispatcher 负责。
- client 只负责投递和低敏结果封装，不做 RAG 检索、不拼 prompt、不写 receipt、不修改 outbox。
- 网络错误、非 2xx、空响应都转成 dispatcher 可理解的失败结果，再由 outbox 退避重试或进入死信。
- request 中 `arguments.question` 允许作为短生命周期输入进入 Python，但不进入 Java receipt/projection。
- 返回对象不包含 endpoint、header、question、answer、Python 原始响应、异常堆栈或服务账号 token。

面试讲法：

> 这里我没有把 Java 业务线程和 Python RAG 强同步绑死，而是把 HTTP 调用包在 outbox dispatcher 后面。Java 先形成可审计 command，再由 dispatcher 调 Python worker。调用失败不会造成业务状态半提交，而是进入 outbox 重试/死信治理。

### 10.2 Python RAG command worker 内部路由

关键文件：

- `python-ai-runtime/src/datasmart_ai_runtime/api/agent/rag_command_worker.py`
- `python-ai-runtime/src/datasmart_ai_runtime/services/rag/command_worker.py`

内部路由：

- `POST /internal/agent/rag/command-worker/run`
- `POST /api/internal/agent/rag/command-worker/run`

与 `/agent/rag/query` 的区别：

- `/agent/rag/query` 是产品/调试查询入口，可以返回 answer、citations、compressedContext 等展示字段。
- `/internal/agent/rag/command-worker/run` 是 outbox worker 入口，只返回低敏 receipt、Java 可写回 payload、LangGraph checkpoint summary 和执行计数。
- worker route 可以接收短生命周期 `arguments.question`，但响应不返回 question 或 answer 正文。

实现原理：

- `rag_command_worker_request_from_payload` 强制把 `arguments` 和 `controlFacts` 分开。
- `commandId/runId/sessionId` 必须存在，避免无归属 worker 执行。
- `postToJava=true` 时才回写 Java receipt，便于本地测试不触发外部 HTTP。
- 生产部署应由 gateway/service-account/OIDC 或 mTLS 保护内部路由。

### 10.3 RAG answer artifact writer

关键文件：

- `artifact_writer.py`
- `s3_artifact_writer.py`
- `scripts/rag-artifact-minio-smoke.py`

为什么需要 artifact writer：

- RAG 的 answer、citations、compressedContext 属于正文级结果，用户最终可能需要查看。
- 但这些正文不能进入 Java receipt、runtime event、projection、checkpoint 或日志。
- 因此 Python 把正文写入受控 artifact，控制面只保存 artifactReference、artifactReferenceType、storageBackend、byteSize、contentSha256、citationCount、selectedChunkCount、objectKeyDigest。

Local writer：

- 用于本地闭环和 CI。
- 按对象存储语义写 JSON。
- resolved path 校验阻断路径逃逸。
- 临时文件 + replace 原子写入。
- `to_summary()` 不返回本地路径、answer、compressedContext、citation snippet 或 sourceUri。

MinIO/S3-compatible writer：

- 懒加载 boto3，默认 Python Runtime 不强绑对象存储 SDK。
- 支持 fake client 离线单测。
- objectName 映射与 Java `AgentToolActionArtifactMinioObjectLocator` 对齐。
- metadata 只写 schema-version、payload-policy、content-sha256、query-ref-digest、citation-count、selected-chunk-count、generated。
- 不写 question、answer、citation snippet、sourceUri、prompt、SQL、URL、token 或 secret。
- `diagnostics()` 只返回 bucketDigest、endpointDigest、objectRootPrefixDigest 和 mapping count。

真实 smoke 证据：

- `scripts/rag-artifact-minio-smoke.py` 使用生产同款 `S3CompatibleRagAnswerArtifactWriter` 写入 MinIO。
- 脚本执行 `head_object/get_object` 校验对象可用性和 sha256。
- 输出低敏 JSON summary，不暴露 endpoint、bucket、objectName、accessKey、secretKey、answer marker、context marker 或 citation marker。
- 当前状态记录显示真实 MinIO smoke 已成功，输出 `RAG_ARTIFACT_MINIO_SMOKE_SUCCEEDED`。

### 10.4 Java artifact grant/read final-check

关键类：

- `AgentToolActionArtifactAccessAuthorizationService`
- `AgentToolActionArtifactBodyReadGrantService`
- `AgentToolActionArtifactBodyReadFinalCheckService`
- `AgentToolActionArtifactBodyReadGrantVerificationService`
- `AgentToolActionArtifactObjectStoreProbeService`

三段式正文读取控制：

1. Metadata authorization：确认 artifactReference 归属于当前 tenant/project/actor/run/session/command。
2. Body read grant：确认读取目的、读取形态、最大字节数、请求组件，签发低敏 grantDecisionReference。
3. Final check：对象存储服务提交已脱敏预览，Java 再校验 grant 引用、预览形态、敏感片段和 UTF-8 字节上限。

RAG 新增读取目的：

- `RAG_ANSWER_VIEW`

为什么单独定义：

- RAG answer 是模型产物，和普通任务结果不同。
- 后续可以绑定更严格的 prompt/context 泄露检查、预览字节上限、审计等级和人工复核策略。

边界：

- grant 响应不返回正文。
- 不签发 signed URL。
- 不发 bearer token。
- 不暴露 bucket/key。
- final-check 只处理短预览，不连接 MinIO，不读取完整对象。
- 真正完整下载仍应由对象存储服务执行 ACL、DLP、恶意内容扫描、水印、下载审计、保留期和限速。

### 10.5 RAG 只读 receipt 与写入型工具的差异

RAG receipt 的特殊点：

- `outcome=RAG_QUERY_COMPLETED`
- `workerReceiptMode=READ_ONLY_QUERY_SUMMARY`
- `toolCode=knowledge.rag.query`
- `sideEffectStarted=false`
- `sideEffectExecuted=false`
- `workerLeaseRequired=false`
- artifactAvailable 为 true 时只说明答案 artifact 可通过授权链路读取。

为什么重要：

- RAG 查询默认是只读知识检索，不应被审计成“真实命令执行成功”。
- 即使生成了 answer artifact，也不代表发生了业务副作用。
- Java receipt 只保存 artifactReference 等低敏事实，不保存 answer、question、compressedContext、chunk text 或 sourceUri。

### 10.6 最新 RAG 链路排障

Java outbox 没有调用 Python：

1. 检查 `AgentRagCommandWorkerClientProperties.enabled`。
2. 检查 baseUrl/runPath。
3. 检查 service account token/header。
4. 检查 outbox dispatcher 是否启用。
5. 检查 `AgentRagCommandWorkerCallResult` errorCode。

Python worker 执行成功但 Java 没 receipt：

1. 检查 `postToJava`。
2. 检查 `javaReceiptPayload` 是否存在 commandId/runId/sessionId。
3. 检查 `AgentRagCommandWorkerReceiptIngestionService` 字段映射。
4. 检查 receipt idempotencyKey。
5. 检查 Java receipt 服务是否拒绝状态或租户边界。

artifact 写入失败：

1. Local writer 看 root path 和路径逃逸校验。
2. MinIO writer 看 endpoint、bucket、凭据、objectRootPrefix。
3. 看是否安装 `python-ai-runtime[object-store]`。
4. 看 artifactReference 是否被 Java locator 接受。
5. 写入失败应让 outbox 重试/死信，不应生成“receipt 成功但正文丢失”。

grant/final-check 拒绝：

1. artifact metadata authorization 是否通过。
2. readPurpose 是否为允许值，RAG 应使用 `RAG_ANSWER_VIEW`。
3. requestedContentMode 是否是 `TRUNCATED_TEXT_PREVIEW` 或 `SAFE_RENDERED_PREVIEW`。
4. previousGrantDecisionReference 是否为合法形态。
5. safePreviewText 是否包含敏感标记或超过字节上限。

## 11. 最新能力同步：Keycloak/企业 IdP 身份供应与 PostgreSQL-backed 身份存储

当前仓库在身份体系上也完成了重要收敛：从“文档上采用 OIDC/Keycloak”推进到“permission-admin 可执行账号供应、本地保存影子身份、Keycloak 身份事实存 PostgreSQL”。

### 11.1 登录认证与账号供应的边界

登录认证：

- 人类用户通过 Keycloak 或企业 IdP 登录页完成账号密码、MFA、账号锁定、密码策略和会话管理。
- Gateway 作为 OAuth2 Resource Server 验证 access token。
- Gateway 把 token claim 转换成 `X-DataSmart-*` 平台上下文。

账号供应：

- permission-admin 代表租户管理员或平台管理员调用 IdP 创建、禁用、重置账号。
- DataSmart 保存低敏影子身份映射。
- 操作写入权限审计。

DataSmart 明确不保存：

- 用户密码
- refresh token
- Keycloak admin token
- OIDC client secret
- 可直接登录凭据

### 11.2 permission-admin 身份供应

关键类：

- `IdentityProvisioningProperties`
- `IdentityProviderAdminClient`
- `KeycloakIdentityProviderAdminClient`
- `IdentityProvisioningService`
- `IdentityProvisioningServiceImpl`
- `IdentityProvisioningController`
- `PermissionIdentityUser`
- `PermissionIdentityAuditSupport`

API 能力：

- `/identity/capabilities`
- `/identity/users/register`
- `/identity/users/{providerUserId}/disable`
- `/identity/users/{providerUserId}/password/reset`

实现要点：

- 只有 `TENANT_ADMINISTRATOR` 和 `PLATFORM_ADMINISTRATOR` 可以执行账号供应写操作。
- 租户管理员只能管理本租户，平台管理员可以跨租户。
- 注册用户时先调用 IdP 创建外部用户，再写本地 `PermissionIdentityUser` 影子表。
- 禁用和重置密码都调用 IdP，DataSmart 只更新状态或 updateTime，不保存密码。
- 审计 detail 使用 `NO_PASSWORD_NO_TOKEN_NO_SECRET` 策略。

当前取舍：

- 当前采用同步调用 IdP 后写影子表。
- 高可用生产版本可演进为 outbox/saga：先写 `PENDING_PROVISION`，异步调用 IdP，再更新 `ACTIVE/FAILED`，并提供补偿或人工修复。

### 11.3 Gateway 身份管理路由

新增能力：

- Gateway 将 `/api/identity/**` 转发到 permission-admin `/identity/**`。
- route metadata 新增 `IDENTITY_USER + CREATE`、`IDENTITY_USER + DISABLE`、`IDENTITY_USER + RESET_PASSWORD`。
- 资源类型推断把 `/api/identity/**` 归类为 `IDENTITY_USER`。
- Gateway 契约说明也纳入 identity-provisioning。

面试讲法：

> 我不会在 gateway 写 username/password 登录接口。gateway 负责 token 验证、上下文注入、限流、路由和鉴权；账号密码、MFA、密码策略和账号锁定交给成熟 IdP。permission-admin 只做管理员账号供应和低敏影子身份管理。

### 11.4 Keycloak PostgreSQL-backed 身份存储

关键文件：

- `docker/postgresql/init/01-keycloak-database.sh`
- `docker-compose.yml`
- `.env.application.example`
- `docker/keycloak/README.md`
- `scripts/backup-restore-check.ps1`

实现方式：

- PostgreSQL 初始化脚本创建 Keycloak 专用 database/user。
- `keycloak-db-bootstrap` 一次性服务补偿已有 `postgresql_data` 卷不会重跑 initdb 脚本的问题。
- Keycloak 使用 `KC_DB=postgres` 和 `jdbc:postgresql://postgresql:5432/keycloak`。
- 移除旧 `/opt/keycloak/data` 文件数据卷。
- `.env.application.example` 新增 Keycloak database 名称、账号和密码样例。

为什么 Keycloak 需要独立数据库：

- realm、用户、client、角色、密码哈希、会话和密钥轮换历史都属于认证中心事实数据。
- 放在容器文件目录会把容器生命周期和身份数据生命周期混在一起。
- 独立 PostgreSQL database 更适合备份、恢复、审计、容量评估和未来迁移到托管 PostgreSQL。
- Keycloak 不应复用 DataSmart 业务库超级/业务账号，避免权限混杂。

当前验证：

- `docker compose config` 通过。
- `backup-restore-check.ps1` 结果为 `PASS=35, WARN=1, FAIL=0`，warning 是默认未检查本机恢复工具链。
- 本机真实 Keycloak smoke 通过，Keycloak public schema 下可见 90 张表。
- 使用本地样例用户获取 access token 成功，验证过程未打印 token。

当前边界：

- 旧本地 Keycloak 文件卷中的 realm/用户不会自动迁移。
- 本地仍使用 `start-dev` 作为学习/E2E 模式。
- 生产仍需要 TLS、固定 hostname、HA/集群、Secret 轮换、最小权限管理员、正式 confidential client、数据库备份和故障演练。

## 12. 面向深水区能力的实现原理学习路线

这一节把当前系统能力按 AI Agent 面试最容易深挖的方向重新组织。学习时不要只记“用了 LangGraph、RAG、Memory”，而要能讲清楚一次请求从入口到控制面、再到 Python Runtime、工具执行、回执、评测和上线排障的完整机制。

推荐总路线：

1. 先学可信入口：Gateway 如何生成身份、权限、数据范围、Skill 可见性和 HMAC 可信上下文。
2. 再学 Agent 规划：Python Runtime 如何构建上下文、压缩上下文、做 Intent 和 ToolPlan。
3. 再学工具治理：Tool registry、tool budget、Skill admission、readiness、approval、outbox、worker receipt。
4. 再学可恢复工作流：LangGraph checkpoint、execution gate、Java host facts、租约、幂等、dead letter。
5. 再学 Memory：候选、审批、物化、检索、冲突、污染、防越权召回。
6. 再学 RAG：文档治理、chunk、hybrid retrieval、rerank、证据门控、artifact、grant/final-check。
7. 最后学评测和上线：golden eval、CI、shadow/canary、观测指标、故障演练。

### 12.1 上下文压缩：从“塞全文”到“保状态、保决策”

当前实现入口：

- `python-ai-runtime/src/datasmart_ai_runtime/services/hybrid_context_builder.py`
- `python-ai-runtime/src/datasmart_ai_runtime/services/context_micro_compactor.py`
- runtime event：`CONTEXT_MICRO_COMPACTED`

实现过程：

1. `HybridContextBuilder` 接收多来源上下文，包括用户目标、工具候选、记忆摘要、RAG 摘要、治理上下文。
2. 先过滤过期、越权或敏感级别不允许的上下文。
3. 再去重，避免同一事实从 session、memory、runtime event 重复进入模型。
4. 再排序，优先保留和当前目标、当前工作区、当前工具计划相关的块。
5. 在总 token 预算前调用 `ContextMicroCompactor`，对单块长文本做确定性微压缩。
6. 压缩报告只记录低敏计数、来源类型、token 节省和原因码。
7. 最后再按 token 预算裁剪，生成进入模型的上下文块。

核心原理：

- 上下文压缩不是文学摘要，而是决策状态压缩。
- 要保留能影响下一步决策的事实，例如审批状态、工具风险、runId、commandId、errorCode、checkpoint locator、evidence count。
- 对正文级内容只保留 locator、hash、count、policyVersion，不把正文扩散到 checkpoint、event、metric 和日志。
- 微压缩优先用确定性抽取和字段白名单，因为它比 LLM 摘要更适合作为执行前控制事实。

学习重点：

- 读 `HybridContextBuilder.build(...)`，画出过滤、去重、压缩、预算裁剪顺序。
- 读 `ContextMicroCompactor` 的 token 估算、敏感片段规避、可信度标记和报告字段。
- 对比“压缩进入模型的内容”和“runtime event 可见内容”，理解为什么 event 只能存低敏摘要。

练习：

1. 构造一个包含长工具返回、RAG 摘要、记忆摘要和审批事实的上下文，手动判断哪些字段能保留。
2. 设计一个压缩评测样本：压缩前后 ToolPlan、approvalRequired 和 fail-closed 结果必须一致。
3. 解释为什么“已审批”不能由摘要模型生成，必须来自 Java approval fact。

生产增强方向：

- 接真实 tokenizer，而不是只用启发式估算。
- 增加长会话历史摘要、工具结果 tool-compact、RAG citation compact。
- 对压缩结果做 replay eval，评估关键事实保留率、决策一致性和幻觉事实率。

### 12.2 工作流编排：模型规划、LangGraph 和 Java 控制面的分工

当前实现入口：

- `python-ai-runtime/src/datasmart_ai_runtime/services/agent_orchestrator.py`
- `python-ai-runtime/src/datasmart_ai_runtime/services/langgraph_planning_workflow.py`
- `python-ai-runtime/src/datasmart_ai_runtime/services/tools/langgraph_execution_gate.py`
- `python-ai-runtime/src/datasmart_ai_runtime/services/agent_execution/langgraph_durable_checkpointer.py`
- `agent-runtime/src/main/java/.../AgentToolExecutionAuditService.java`
- `agent-runtime/src/main/java/.../AgentAsyncTaskCommandOutboxController.java`

实现过程：

1. Gateway 把可信控制上下文透传给 Python Runtime。
2. Python 编排器先运行 LangGraph planning workflow 外壳，生成低敏 workflow diagnostics。
3. Intent、context、ToolPlanner 和 Skill admission 生成计划。
4. execution gate 根据 readiness 决定是可以继续、等待审批、等待预算、需要澄清还是需要 resume preflight。
5. 如果需要真实副作用，Python 只生成计划和低敏合同，不直接写业务表。
6. Java agent-runtime 接收计划后创建 tool execution audit。
7. 高风险工具进入 WAITING_APPROVAL，审批通过后才可能进入 PLANNED。
8. 可执行命令写入 command outbox，由 dispatcher/worker 异步执行。
9. worker 回写 receipt，Java projection/replay 展示状态。

核心原理：

- LangGraph 适合表达节点、边、条件路由和 checkpoint，但不能替代业务事实源。
- Java 控制面负责“已经发生什么”和“是否允许继续”，Python 负责“下一步建议怎么走”。
- checkpoint 只保存低敏恢复摘要；恢复时必须回查 Java host facts。
- 工作流每个节点都要能回答：输入是什么、输出是什么、是否有副作用、失败如何重试、是否能恢复。

关键状态：

- 规划态：PLANNING、READY、WAITING_CLARIFICATION。
- 审批态：WAITING_APPROVAL、APPROVED、REJECTED。
- 执行态：PLANNED、EXECUTING、SUCCEEDED、FAILED、SKIPPED、CANCELLED。
- 异步投递态：PENDING、DELIVERING、RETRY_WAIT、DELIVERED、DEAD_LETTER。

学习重点：

- 读 `LangGraphExecutionGateWorkflow`，理解 readiness 如何转成条件路由。
- 读 `LangGraphDurableCheckpoint`，理解 checkpoint 保存哪些低敏字段。
- 读 `AgentToolExecutionAuditService`，理解高风险工具如何让 run/tool audit 进入等待人工状态。
- 读 outbox controller 和 writer，理解为什么写 outbox 前要再次复核 readiness、容量和幂等。

练习：

1. 画出一个高风险工具从 ToolPlan 到 WAITING_APPROVAL，再到 outbox，再到 receipt 的状态图。
2. 设计一个节点失败分类表：参数错、权限错、审批缺失、下游 503、超时、幂等冲突分别如何处理。
3. 解释为什么工作流升级要保留 graphVersion、schemaVersion 和 policyVersion。

生产增强方向：

- 持久化更多 Java turn fact，支持真实 pause/resume/fork/recover。
- execution gate 增加低基数指标：route、fallback、failure reason、duration。
- 对旧 checkpoint 做版本兼容和迁移演练。

### 12.3 Agent Memory：从聊天记录到受治理长期记忆

当前实现入口：

- `python-ai-runtime/src/datasmart_ai_runtime/api/memory/write.py`
- `python-ai-runtime/src/datasmart_ai_runtime/api/memory/materialization_admin.py`
- `python-ai-runtime/src/datasmart_ai_runtime/api/memory/runtime.py`
- `python-ai-runtime/src/datasmart_ai_runtime/services/memory/memory_write_candidate_factory.py`
- `python-ai-runtime/src/datasmart_ai_runtime/services/memory/memory_write_materializer.py`
- `python-ai-runtime/src/datasmart_ai_runtime/services/memory/memory_materialization_runner.py`
- `python-ai-runtime/src/datasmart_ai_runtime/services/memory/memory_store_retriever.py`
- `python-ai-runtime/src/datasmart_ai_runtime/services/memory/memory_write_workspace.py`
- `python-ai-runtime/src/datasmart_ai_runtime/services/memory/user_profile_memory.py`

实现过程：

1. 模型、工具或运行时观察只生成 memory write candidate。
2. candidate 携带 tenantId、projectId、workspaceKey、memoryNamespace、memoryType、sensitivity、retentionDays、sourceRunId。
3. 管理员或策略通过 `/agent/memory/write-candidates/{candidateId}/approve|reject` 决策。
4. `AgentMemoryMaterializationRunner` 有界扫描 APPROVED 候选，领取 lease，逐条物化。
5. `AgentApprovedMemoryWriteMaterializer` 校验 scope、sensitivity、retentionDays、contentSummary 和 workspace 绑定。
6. 正式记忆写入 store，并记录 materialization receipt。
7. `StoreBackedAgentMemoryRetriever` 在 `/agent/plans` 构建上下文时按 tenant/project/workspace/memoryNamespace 过滤召回。
8. SQLite FTS 二级索引只作为检索加速，命中后仍要回查正式 store 和范围。

核心原理：

- Memory 不是历史消息。历史消息是发生过的对话，Memory 是经过治理后可复用的长期事实。
- 写入必须从 candidate 开始，不能让模型直接写正式记忆。
- 检索必须先做范围过滤，再做相关性排序。
- 记忆正文、候选正文、lease token、工具原始输出不能进入 runtime event 或指标。

冲突治理：

- 新记忆不直接覆盖旧记忆，而是通过 superseded、expired、rejected、active 等状态表达。
- 用户明确纠正优先级高于模型推断。
- 管理员 reject 的记忆不能被下一次自动观察直接复活。
- 跨 workspace 的 memoryNamespace 不匹配时禁止生成或召回。

防污染机制：

- 低置信度候选停留在 candidate。
- 高敏内容没有脱敏流水线时不允许物化。
- 写入前校验 `workspaceKey` 和 `memoryNamespace`。
- worker 失败进入退避、DLQ 和管理员补偿，不让坏候选热循环。
- 审计 outbox 记录物化和补偿事件。

学习重点：

- 读 `AgentMemoryWorkspaceSupport`，理解为什么 namespace 是防越权召回核心。
- 读 `AgentApprovedMemoryWriteMaterializer._validate_candidate(...)`，理解正式记忆写入前的安全门。
- 读 `AgentMemoryMaterializationRunner`，理解 lease、批次报告、失败隔离和低敏输出。
- 读 `user_profile_memory`，理解 candidate、active、rejected、superseded、expired 的状态意义。

练习：

1. 设计一个“用户纠正旧偏好”的记忆冲突处理流程。
2. 设计一个“删除某用户偏好”的流程，覆盖正式 store、FTS、缓存和审计。
3. 构造一个跨 workspace 召回测试，验证记忆不会串到另一个工作区。

生产增强方向：

- 将正式 store 和 pgvector 结合，增加语义检索但保留 SQL 范围过滤。
- 增加记忆删除、修订、冲突合并管理 API。
- 建立多轮记忆 eval：正确召回、错误拒召回、删除后不召回、冲突不污染。

### 12.4 大模型工具调用：从 ToolPlan 到受控执行

当前实现入口：

- Python：`ToolPlanner`、tool readiness、MCP worker、RAG command worker、model tool feedback。
- Java：tool registry、tool execution audit、approval、command proposal、command outbox、worker receipt。
- Gateway/permission-admin：tool budget、Skill admission、route permission、数据范围。

实现过程：

1. Tool registry 定义工具 schema、风险、执行模式、目标服务、是否审批。
2. Gateway/permission-admin 生成工具预算和可见 Skill。
3. Python Runtime 基于 Intent 和上下文生成 ToolPlan。
4. ToolPlan 做参数 schema 校验和预算校验。
5. readiness 判断缺哪些事实：approval、payloadReference、resume fact、budget、worker capacity。
6. Java 创建 tool audit，低风险进入 PLANNED，高风险进入 WAITING_APPROVAL。
7. 审批通过后 writer 复核条件，写 command outbox。
8. dispatcher 投递给 task-management、MCP worker、RAG worker 或受控命令 worker。
9. worker 回写 receipt，Java 用 receiptId/idempotencyKey 幂等接收。
10. 大结果通过 artifactReference 和 grant/final-check 控制读取。

核心原理：

- 模型可以建议工具，但不能成为执行权限来源。
- 工具 schema 解决参数结构，tool budget 解决调用膨胀，approval fact 解决高风险授权，outbox 解决异步可靠投递，receipt 解决执行证据。
- 写操作必须和幂等键绑定；下游重试和 worker 重启不能创建重复业务对象。
- 工具失败要分类，不同错误对应澄清、拒绝、重试、defer、dead letter 或人工介入。

学习重点：

- 读 `AgentToolExecutionAuditService.requiresApprovalBeforeExecution(...)`，理解风险判断。
- 读 `AgentToolActionCommandOutboxWriterService`，理解写 outbox 前的容量保护、payload 大小限制和幂等复用。
- 读 `AgentRagCommandWorkerReceiptIngestionService` 和 MCP receipt ingestion，理解 Python worker response 如何被收敛成 Java receipt。
- 读工具结果 feedback 相关测试，理解模型二轮不能直接消费完整工具正文。

练习：

1. 把“下单扣库存”映射成 DataSmart 的“创建同步任务/提交质量修复任务”，设计幂等键、outbox、receipt。
2. 设计一个参数缺失场景：缺 datasourceId 时应该澄清，而不是让模型猜。
3. 设计一个模型选错高风险工具的拦截链路。

生产增强方向：

- 为更多真实工具补 durable worker 和 receipt。
- 增加统一错误码和工具失败样本回流。
- 对工具选择、参数生成、审批判断做离线 eval。

### 12.5 Agent 评测系统：从答案评测到过程评测

当前项目已有基础：

- Python Runtime 大量单元测试覆盖 RAG、memory、tool schema、runtime event、MCP、context、closure readiness。
- Java Maven reactor、agent-runtime、gateway、permission-admin 有模块测试和静态门禁。
- RAG MinIO smoke、Keycloak smoke、本地只读 E2E smoke 提供工程闭环证据。

应该补齐的评测体系：

1. 离线 golden eval：覆盖意图、工具、参数、审批、RAG、Memory、上下文压缩、工作流恢复。
2. 过程指标：ToolPlan 准确率、参数完整率、approval hit rate、fail-closed rate、no evidence rate。
3. 安全指标：敏感泄露、越权召回、高风险自动执行、伪造 approval。
4. 工程指标：P95/P99、token 成本、重试次数、outbox backlog、worker heartbeat、dead letter。
5. 线上反馈：用户负反馈、人工复盘、告警样本回流。

实现做法：

- 每条 eval 样本保存低敏输入，不保存客户真实 prompt 或工具正文。
- 期望输出不只写 final answer，还写 expectedTool、expectedArguments、expectedDecision、forbiddenActions。
- 安全项用规则判定，比如不允许出现 token、sourceUri、bucket/key、SQL 样本。
- 文本质量可用 LLM-as-judge，但安全、权限、幂等必须用确定性检查。
- CI 中跑小型关键样本集；夜间或发布前跑完整回归。
- 灰度时先 shadow mode，只记录新策略会怎么选工具、怎么审批，不直接执行。

学习重点：

- 把现有单元测试按能力分类：context、memory、tool、RAG、runtime event、gateway auth。
- 把 `scripts/local-e2e-smoke-check.ps1` 当成工程验收范式，理解 smoke 和生产上线验收的区别。
- 设计一张 eval 样本表，字段包括 userGoal、trustedContext、expectedTool、expectedDecision、expectedNoLeakFields、expectedMetrics。

练习：

1. 构造 20 条工具调用 eval：10 条正常、5 条参数缺失、3 条权限不足、2 条恶意绕审批。
2. 构造 20 条 RAG eval：有证据、无证据、证据冲突、过期文档、跨 workspace。
3. 构造 10 条 Memory eval：正确召回、冲突、删除、污染、跨租户隔离。

生产增强方向：

- 建立 eval dashboard，按模型版本、prompt 版本、retrieval 版本、tool schema 版本对比。
- 失败样本自动脱敏入库，人工标注根因后转成回归集。
- 把 eval 门禁接入 release pipeline，关键安全项必须 100% 通过。

### 12.6 企业级 RAG：从知识库治理到低敏 Artifact

当前实现入口：

- `python-ai-runtime/src/datasmart_ai_runtime/services/rag/text.py`
- `python-ai-runtime/src/datasmart_ai_runtime/services/rag/knowledge_base.py`
- `python-ai-runtime/src/datasmart_ai_runtime/services/rag/pipeline.py`
- `python-ai-runtime/src/datasmart_ai_runtime/services/rag/command_worker.py`
- `python-ai-runtime/src/datasmart_ai_runtime/services/rag/artifact_writer.py`
- `python-ai-runtime/src/datasmart_ai_runtime/services/rag/s3_artifact_writer.py`
- `python-ai-runtime/src/datasmart_ai_runtime/services/rag/langgraph_checkpoint.py`
- `agent-runtime/src/main/java/.../runtime/rag/AgentRagCommandWorkerReceiptIngestionService.java`

实现过程：

1. 文档进入轻量 knowledge base，按 tenant/project/workspace 控制可见范围。
2. `chunk_document` 按段落优先、长段落滑窗、overlap 切 chunk。
3. 检索时先做 metadata 可见性过滤。
4. 召回结合 lexical 和可选 vector score。
5. 使用 Reciprocal Rank Fusion 融合多路排序。
6. 使用 MMR 思路降低重复 chunk。
7. `RagHeuristicReranker` 叠加词项、标题和治理文档 boost。
8. evidence gate 根据 lexical score、match terms、vector score 判断是否足以回答。
9. `RagContextCompressor` 生成 compressedContext 和 citation。
10. 无证据时 fail-closed 或 evidence-only fallback。
11. 有答案时写入 artifact，控制面只保留 artifactReference、hash、计数和策略版本。
12. LangGraph checkpoint 记录 retrieve -> evidence_gate -> grounded_answer/no_evidence 低敏节点。
13. Java receipt ingestion 把 Python worker 结果纳入统一 worker receipt index。

核心原理：

- RAG 的核心不是“召回 topK 后让模型回答”，而是证据治理。
- 向量库会尽力返回最近邻，但最近不等于有证据，所以必须有 evidence gate。
- citation 是回答可信度的接口，不能只让模型口头说“根据资料”。
- answer、citations、compressedContext 属于正文级结果，应该进对象存储 artifact，而不是进 Java 控制面。
- 读 artifact 正文要走 `RAG_ANSWER_VIEW`、grant、probe、final-check。

学习重点：

- 读 `text.py`，理解 chunk、overlap、query 相关压缩。
- 读 `knowledge_base.py`，理解 metadata filter、lexical/vector、RRF、MMR。
- 读 `pipeline.py`，理解 rerank、evidence gate、fallback 和生成。
- 读 `command_worker.py`，理解 `/internal/agent/rag/command-worker/run` 为什么只返回低敏 receipt。
- 读 `artifact_writer.py` 和 `s3_artifact_writer.py`，理解 Local writer 和 MinIO writer 的共同协议。
- 读 Java grant/final-check 相关服务，理解为什么控制面不暴露 bucket/key/signed URL。

多类型文档增强路线：

- PDF/Word：需要 layout parser，保留标题层级、页码和段落。
- 表格：按表头、行组、主键字段切片，避免表头和数据分离。
- Runbook：按现象、原因、处理步骤、验证命令切片。
- API 文档：按 endpoint、参数、错误码、示例切片。
- 数据标准：按主题域、指标、字段、口径版本切片。

线上效果评估：

- Recall@K、MRR、nDCG。
- citation precision、grounded answer rate。
- no evidence rate 和错误拒答率。
- 越权召回率、敏感泄露率。
- P95/P99 检索、rerank、生成和 artifact 写入耗时。
- 按租户、知识库、文档类型拆分失败样本。

生产增强方向：

- PostgreSQL/pgvector Knowledge Store。
- MinIO 文档解析和增量索引。
- 专用 reranker。
- 文档 ACL、版本、生效时间、过期时间治理。
- 对象存储 DLP、下载审计、水印和限速。
- Neo4j GraphRAG 用于实体关系和血缘推理。

### 12.7 一条完整学习主线：从用户一句话到可审计执行

可以用下面这条主线把所有能力串起来：

1. 用户说：“帮我检查这个项目的数据同步为什么卡住，并给出处理建议。”
2. Gateway 校验 OIDC/JWT，生成租户、角色、workspace、数据范围和 HMAC 可信上下文。
3. permission-admin 返回 route permission、tool budget、Skill admission 和可能的审批要求。
4. Python Runtime 构建上下文，召回正式记忆，必要时做 ContextMicroCompactor。
5. Intent 识别为 data-sync 运维诊断，ToolPlanner 选择只读诊断工具和可选 RAG runbook。
6. Readiness 判断当前是只读诊断，不需要审批；如果要执行重试/取消/补偿，则需要审批。
7. LangGraph execution gate 生成低敏 route 和 resume requirement。
8. RAG 查询 runbook，证据不足则 fail-closed，证据充分则生成带 citation 的 answer artifact。
9. Java agent-runtime 接收计划，记录 tool execution audit。
10. 如果进入真实工具，写 command outbox，dispatcher 投递，worker 带 lease 执行。
11. worker 写 receipt，Java projection 展示执行结果。
12. 如果用户要查看 RAG answer 正文，走 `RAG_ANSWER_VIEW` grant/final-check。
13. 运行中所有 event/checkpoint/metric/log 只保存低敏状态、计数、locator、hash 和 reason code。
14. 失败样本进入 eval 回流，补充工具、RAG、Memory 或上下文压缩回归集。

掌握这条主线后，面试官无论问上下文压缩、工作流、工具调用、Memory、RAG、评测还是上线排障，都能回到同一个项目事实链路上回答。

### 12.8 最终交付闭环与本地依赖恢复

当前新增交付能力：

- `docs/final-delivery-closure-runbook.md`
- `scripts/final-delivery-closure-check.ps1`
- `scripts/local-dependency-recovery-drill.ps1`

`final-delivery-closure-check.ps1` 的定位：

- 它是收敛阶段的总闸门，不是新业务功能。
- 它把生产就绪、Helm、SBOM、镜像签名、备份恢复、容量基线、故障演练、最终闭环审计和可选只读 E2E smoke 串起来。
- 它通过解析子脚本统一的 `[SUMMARY] PASS=..., WARN=..., FAIL=...` 输出形成总结果。
- 默认不运行真实 worker、不执行 Agent 工具、不创建任务、不读写业务数据。
- `-RunLiveSmoke` 才调用本地只读 E2E smoke。
- `-RunFullTests` 才让最终闭环审计复跑 Python/Maven 全量测试。
- `-Strict` 把 warning 当作发布阻断项。
- `-WriteEvidence` 把低敏 JSON 证据写入 `target/final-delivery-closure`，该目录不进入 Git。

实现原理：

- 总闸门不重新实现每个检查项，而是复用已有脚本，避免验收逻辑分叉。
- 子门禁只通过 summary、exitCode、warning/fail 计数汇总，不把大量子脚本正文扩散到证据文件。
- 它把“当前仓库是否是交付候选”变成可重复命令，而不是靠口头记忆一堆脚本。
- 它仍明确区分本地交付候选和客户生产上线，生产上线需要客户环境提供 TLS/mTLS、Secret Manager、企业 IdP、镜像签名、K8s、压测、备份恢复和故障注入证据。

`local-dependency-recovery-drill.ps1` 的定位：

- 它解决本地长期运行后的依赖漂移，例如 Zookeeper 退出、Kafka 连不上 Zookeeper、Python Runtime Kafka bootstrap 失败。
- 默认只诊断 Docker、Compose、Zookeeper、Kafka、Python Runtime 状态。
- 只有显式 `-RecoverKafkaChain` 才拉起 Zookeeper/Kafka。
- 只有显式 `-RestartPythonRuntime` 才尝试重启 Python Runtime。
- 它不会删除 volume、不会重置数据库、不会重建 Keycloak realm、不会清空 topic、不会执行 worker loop。

实现原理：

- 本地恢复首先保护状态事实，尤其 Keycloak 已迁到 PostgreSQL-backed database 后，不能用“删卷重来”伪装修复。
- 恢复动作必须有界：拉起依赖、等待健康、重连 runtime、再跑只读 smoke。
- Python Runtime 重启优先走 Compose service，如果当前 compose overlay 未加载，再回退到固定容器名，避免把“服务名不在当前 compose 文件中”误判为恢复失败。

学习重点：

- 读总闸门脚本，理解交付门禁如何组合静态检查、只读 smoke、全量测试和低敏证据。
- 读依赖恢复脚本，理解本地恢复为什么不能删除 volume。
- 把“服务 running”与“只读 smoke 通过”区分开：前者只是容器状态，后者才说明鉴权、路由、Java 控制面和 Python Runtime 诊断链路恢复。

面试讲法：

> 我把项目交付收敛成一个总闸门，而不是让验收依赖一堆散落命令。默认只跑静态和只读门禁，真实副作用全部关闭；如果要加强证据，可以显式打开 live smoke、full tests 和 evidence 输出。依赖恢复也遵循同样原则：不删 volume、不重置状态，只做 Zookeeper/Kafka/Python Runtime 的有界恢复，然后继续跑只读 smoke 验证。

## 13. 深水区学习任务

为了把项目学到能应对 AI Agent 开发面试的程度，建议继续按以下任务练习：

1. 画出 `knowledge.rag.query` 从 Java proposal/outbox 到 Python worker，再到 Java receipt 和 artifact grant/final-check 的完整时序图。
2. 手动构造一个 RAG command payload，说明哪些字段属于 `arguments`，哪些字段属于 `controlFacts`。
3. 解释为什么 `arguments.question` 可以短生命周期进入 Python，但不能进入 Java receipt/projection/checkpoint。
4. 对比 `/agent/rag/query` 和 `/internal/agent/rag/command-worker/run` 的返回字段和使用场景。
5. 解释 Local writer 和 MinIO writer 的共同协议，以及为什么 writer 失败必须让 outbox 重试/死信。
6. 解释 artifactReference、objectName、bucket/key、signed URL 的区别，为什么控制面只暴露 artifactReference。
7. 说明 `RAG_ANSWER_VIEW` 和 `TASK_RESULT_VIEW` 的策略差异。
8. 设计一个 RAG answer artifact 的 DLP/final-check 策略，包括敏感词、PII、SQL、token、sourceUri 和字节上限。
9. 解释 Keycloak 保存哪些真实身份事实，DataSmart 影子表保存哪些低敏映射。
10. 设计身份供应从同步调用演进到 outbox/saga 的状态机。
11. 说明租户管理员和平台管理员在身份供应上的边界。
12. 写一份客户生产上线前的 IdP 验收清单：TLS、hostname、confidential client、Secret、备份恢复、HA、审计、MFA。
