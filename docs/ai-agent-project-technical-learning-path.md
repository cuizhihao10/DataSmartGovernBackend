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

## 12. 深水区学习任务

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
