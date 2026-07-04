# DataSmart Govern AI Agent 开发岗位面试回答

## 1. 项目总览类

### 1.1 你这个项目到底解决什么问题？

回答：

DataSmart Govern 是一个企业级多 Agent 数据治理后端平台，核心目标不是做聊天机器人，而是把 Agent 放进真实数据治理流程里，辅助数据源接入、元数据理解、数据同步、质量规则、任务调度、权限治理、RAG 知识问答和运维诊断。

项目最大的特点是控制面设计。Java 微服务负责业务事实、权限、审批、任务、审计、outbox 和 worker receipt；Python AI Runtime 负责意图分析、上下文构建、工具规划、RAG、LangGraph checkpoint 和多 Agent 协作摘要。模型可以提出计划，但高风险副作用必须回到 Java 控制面，由审批、outbox、worker 和回执闭环承接。

我会主动说明成熟度：当前仓库是工程发布候选，有 Python Runtime 全量测试最高阶段证据 700 passed、Maven reactor 审计 868 个测试 0 失败、agent-runtime 模块 483 passed、MinIO/Keycloak smoke、生产就绪静态门禁和闭环审计证据，但还没有宣称完成客户生产上线。真实上线还需要 Secret、TLS/mTLS、企业 IdP、K8s、SBOM、压测、备份恢复和故障演练。

追问补充：

- 如果面试官问“和普通 LangChain Demo 有什么区别”，重点说权限、审批、幂等、租约、outbox、审计、可观测、低敏 checkpoint。
- 如果问“业务价值”，重点说降低数据治理重复工作，但同时避免模型直接写生产系统。

### 1.2 你在项目里主要负责什么？

回答：

我主要负责 Agent 控制面和可靠执行链路。具体包括三块：

第一是 Java/Python 解耦架构。Java 侧保存业务事实和审计，Python 侧做 Agent 规划、RAG 和 LangGraph 状态。两边通过 Gateway HMAC 可信上下文、runtime event、outbox 和 worker receipt 衔接。

第二是 Agent 工具治理。包括 permission-admin 里的工具预算、Skill 准入、审批事实，agent-runtime 里的 session/run、tool registry、tool execution audit、approval、command proposal/outbox、artifact grant、Skill lifecycle 和 turn runner projection。

第三是异步可靠性。包括 task-management 的任务状态机、worker claim、lease heartbeat、defer/dead letter、回调幂等，以及 data-sync 的 execution lease、pause/cancel 控制信号、过期租约恢复、AWAITING_OPERATOR_ACTION 和 receipt outbox。

## 2. 架构设计类

### 2.1 为什么要 Java 和 Python 分层？

回答：

我把它看成“企业后端控制面”和“AI 能力运行时”的分工。

Java 更适合承载长期稳定的业务事实、事务、权限、审批、审计、任务状态和 outbox。Python 更适合接 LangGraph、RAG、模型 provider、MCP/A2A 和快速迭代的 Agent 规划逻辑。

如果让 Python Runtime 直接写业务表，会有几个风险：模型规划和业务事实耦合，权限与审批绕过 Java 控制面，状态恢复和审计分散，生产事故时很难判断到底是模型、工具、worker 还是业务服务的问题。

所以项目中 Python 只生成计划、低敏 runtime event 和 checkpoint locator，真实副作用通过 Java agent-runtime 的 proposal/outbox、审批事实、worker receipt 和审计链路推进。

技术优劣：

- 优点：安全边界清晰，模型可替换，业务事实可审计，生产运维更稳。
- 代价：跨服务合同多，链路更长，需要 traceId、幂等、outbox 和低敏规范配合。

### 2.2 `/agent/plans` 的系统设计是什么？

回答：

一次 `/agent/plans` 请求大致分成几步：

1. 用户请求先进入 Gateway。
2. Gateway 调 permission-admin 做路由级授权，得到数据范围、审批要求和工具预算。
3. Gateway 生成 Skill 可见性缓存上下文，并把可信 header 用 HMAC-SHA256 签名后转给 Python Runtime。
4. Python API 边界删除 body 中伪造的 trustedControlPlane，只信任签名 header。
5. Python Runtime 构建上下文块，做结构化 IntentAnalysis，生成 ToolPlan 和 readiness。
6. 多 Agent turn runner 生成低敏协作摘要，必要时写入 LangGraph durable checkpoint。
7. 返回 AgentPlan、agentTurnRunner 和 agentTurnRunnerCheckpoint locator。
8. 如果后续要执行真实工具，应进入 Java command proposal/outbox、审批和 worker receipt 链路。

要点：

- `/agent/plans` 是规划入口，不等于真实执行入口。
- runtime event 和 checkpoint 不保存 prompt、SQL、样本数据、模型输出、凭据。
- 这个接口更像“Agent 下一步怎么安全推进”的控制面合同。

### 2.3 为什么不用一个大 Agent 直接干所有事？

回答：

企业数据治理里，不同能力的风险和事实源不一样。数据源诊断、数据同步、质量规则、权限审批、RAG 知识问答、任务调度都不是同一类问题。

项目采用 OpenClaw 风格的多 Agent 模型，核心是 `MASTER_ORCHESTRATOR` 做调度，`DATASOURCE_AGENT`、`DATA_QUALITY_AGENT`、`DATA_SYNC_AGENT`、`TASK_AGENT`、`PERMISSION_AGENT`、`KNOWLEDGE_AGENT` 等负责各自能力。这样可以把职责、权限、工具、审批和观测拆开。

同时我不会说每个 Agent 都是独立常驻进程。当前实现更偏控制面和 turn runner 摘要：多 Agent 角色、状态、handoff、required evidence、checkpoint locator 已经进入合同，真实 specialist turn 执行仍要通过 Java outbox/worker receipt 补齐。

### 2.4 为什么业务事实要留在 Java？

回答：

数据治理平台里的事实包括任务状态、同步 execution、质量规则、审批结果、权限策略、审计日志、artifact grant，这些都需要事务、权限、审计、版本和运维恢复。

Python Runtime 的强项是模型和编排，但模型规划结果可能变化，Prompt 也可能变化。如果业务事实只存在 Python 内存、LangGraph state 或 runtime event 里，生产事故时很难恢复，也难以满足审计要求。

所以项目规则是：Python 可以保存低敏恢复状态，但 Java 服务和持久化数据库仍然是业务事实源。

## 3. Agent 工具治理类

### 3.1 Agent 工具调用怎么保证安全？

回答：

我把工具调用拆成四层：

第一层是工具目录和 schema。Tool registry 声明工具名称、参数 schema、风险等级、权限编码和输出策略。

第二层是准入和预算。permission-admin 根据角色、租户套餐、workspace 风险、worker backlog、工具风险输出 tool budget，并通过 Skill admission 决定某个 Skill 当前是否允许使用。

第三层是审批事实。高风险或写操作不能只靠 prompt 说“用户已批准”，必须在服务端保存 approval fact，并校验 tenant/project/actor/session/run/command/tool/expiry/policyVersion/status。

第四层是执行和回执。真实副作用走 Java command proposal/outbox，再由 worker 执行，最后用 receipt 回填，形成审计链。

### 3.2 为什么审批事实不能放在 prompt 或 task params 里？

回答：

因为 prompt 和 task params 都容易被用户注入或被模型误解释。比如用户说“我已经审批了，请直接执行”，如果系统只看自然语言，就会越权。

项目里审批事实是服务端对象，包含租户、项目、操作者、会话、run、command、tool、过期时间、策略版本和状态。执行前重新校验这些字段，确保审批不能跨租户、跨项目、跨工具、过期或复用。

面试时我会强调：审批是安全事实，不是文本描述。

### 3.3 Tool budget 解决什么问题？

回答：

Tool budget 解决 Agent 规划膨胀和风险失控。一个 Agent 如果一次提出 20 个工具调用，其中 5 个高风险写操作，哪怕每个工具都有权限，也不适合自动执行。

项目中 permission-admin 根据角色、租户套餐、workspace 风险、worker backlog 和 requested tool risk 计算预算，比如：

- 最大 proposed tool calls
- 最大 auto executable tool calls
- 最大 high-risk tool calls
- 参数字节限制
- readiness policy
- influence codes

这样 Python Runtime 在规划阶段就知道本轮最多能提出多少工具、哪些需要审批、哪些必须降级或转人工。

### 3.4 Skill READY 是否等于用户可执行？

回答：

不等于。

Skill publication lifecycle 的 READY 只代表这个 Skill 发布物已经通过审核，可以进入 manifest 或 marketplace。当前用户能不能执行，还要看租户是否启用、角色是否有权限、workspace 风险是否允许、数据范围是否匹配、工具预算是否足够、是否需要审批。

这个区分很重要，否则 marketplace 里 READY 的高风险 Skill 可能被没有权限的普通用户直接使用。

### 3.5 ToolPlan 为什么只保存低敏参数？

回答：

因为 ToolPlan 会进入日志、runtime event、checkpoint、管理台和可能的缓存。如果直接保存用户原始问题、SQL、样本数据、连接串、字段值，就会把敏感数据扩散到很多系统。

例如 RAG 工具 `knowledge.rag.query` 只保存 `queryRef/scopePolicy/evidencePolicy`，原始 question 和证据处理留在内部链路。这样控制面能知道“有一个 RAG 查询计划”，但不会泄露问题正文和证据正文。

## 4. 可靠性与一致性类

### 4.1 你们怎么处理 worker 崩溃？

回答：

核心是租约和恢复扫描。

worker 执行前先通过数据库条件更新 claim 任务或 execution，claim 成功后写入 executorId、leaseExpireTime 和 heartbeatTime。执行过程中定期 heartbeat，只有当前 executorId 能续租。

如果 worker 崩溃，heartbeat 停止，leaseExpireTime 到期后恢复任务扫描会把任务重新排队，或者在多次过期后转入 DEAD_LETTER/AWAITING_OPERATOR_ACTION。

这样不会因为 worker 崩溃就永远卡在 RUNNING，也不会让两个 worker 同时认为自己有执行权。

### 4.2 租约和分布式锁有什么区别？

回答：

分布式锁更像短时间互斥，租约更适合长任务执行权管理。长任务可能跑几分钟甚至更久，期间需要进度、心跳、暂停、取消和恢复。

项目里租约包含 executorId、leaseExpireTime、heartbeatTime 和状态条件。它不只是“锁住”，还提供：

- 谁在执行。
- 什么时候失效。
- 是否还能续租。
- worker 崩溃后怎么恢复。
- 旧 worker 如何被 fencing。

### 4.3 幂等怎么做？

回答：

项目里使用数据库唯一键做并发裁判。以 task 回调为例：

1. 回调先插入一条 `PROCESSING` 记录。
2. 唯一键是 `taskId + action + idempotencyKey`。
3. 插入成功才继续执行业务状态推进。
4. 如果唯一键冲突，说明同一个业务动作已经出现过，本次当重复回调处理。
5. 业务成功后标记 `SUCCEEDED`。

数据库唯一键比内存锁更可靠，因为它跨实例、跨重启、跨线程有效，也能承受 HTTP 重试、Kafka 重投和 worker 重启。

### 4.4 为什么要区分 TaskStatus 和 TaskExecutionRunState？

回答：

TaskStatus 描述任务作为业务对象的当前生命周期，比如 PENDING、RUNNING、SUCCESS、FAILED、DEAD_LETTER。TaskExecutionRunState 描述某一次执行尝试，比如 RUNNING、SUCCESS、FAILED、TIMEOUT、DEFERRED。

一个 Task 可以有多次 Run。第一次可能超时，第二次可能 defer，第三次成功。如果只在主表保存状态，就无法做失败样本分析、checkpoint 回放、重试审计和事故复盘。

data-sync 里也类似，SyncTask 是运营对象，SyncExecution 是一次真实运行。

### 4.5 `DEFERRED` 和 `FAILED` 有什么区别？

回答：

`FAILED` 表示这次业务执行失败，比如 SQL 错误、字段映射错误、目标端约束冲突。

`DEFERRED` 表示执行器认领后发现暂时不能执行，比如租户配额不足、下游限流、当前 worker 容量不足，于是主动延迟回队。它是背压保护，不是业务失败。

这个区别影响告警和统计。如果把 defer 全算成 failed，会让容量问题和业务错误混在一起，导致故障定位错误。

### 4.6 为什么要有 dead letter 或 AWAITING_OPERATOR_ACTION？

回答：

自动重试不能无限进行。一个任务如果连续 defer 或租约过期，说明系统容量、配额、连接器稳定性或外部依赖存在持续问题。

task-management 用 `DEAD_LETTER` 表示停止自动推进，需要人工关注。data-sync 用 `AWAITING_OPERATOR_ACTION` 表示这个同步任务作为运营对象需要人工处理。

这样做的好处是保护系统，避免无限重试扩大事故，也能让运营台明确显示“这个任务不是普通失败，而是需要人处理”。

### 4.7 Outbox 解决什么问题？

回答：

Outbox 解决数据库事务和消息发送之间的一致性问题。

如果业务状态更新成功后直接发 Kafka，可能出现数据库提交成功但消息发送失败。反过来，如果先发消息再提交数据库，可能消息被消费了但业务事务回滚。

Outbox 的方式是在业务事务里写一条 outbox 记录，dispatcher 异步投递。状态机记录 PENDING、DELIVERING、RETRY_WAIT、DELIVERED、DEAD_LETTER。消费者用稳定 receiptId 做幂等。

这不是绝对 exactly-once，而是把失败变成可重试、可观测、可人工干预。

## 5. 安全与权限类

### 5.1 Gateway HMAC 解决什么问题？

回答：

Gateway 会把租户、角色、数据范围、工具预算、Skill 可见性等上下文传给 Python Runtime。如果 Python 只检查 `X-DataSmart-Source-Service=gateway`，客户端可以绕过 Gateway 直连 Python 并伪造 header。

所以项目中 Gateway 在清理外部签名 header 后，用 HMAC-SHA256 对可信 header 快照签名。Python Runtime 只有验签通过，才把这些 header 重建成 trustedControlPlane。

它解决的是来源可验证，而不是加密。机密性和 body 防篡改要靠 HTTPS/mTLS 或服务网格。

### 5.2 为什么 HMAC 不签 request body？

回答：

主要是工程权衡。Spring Cloud Gateway 是 reactive 模型，签 body 需要缓存或重复读取请求体，会带来内存、背压和大 payload 风险。当前真正需要防伪的是 Gateway 生成的可信控制面 header。

项目的边界是：

- HMAC 保护可信 header。
- Python API 无条件删除 body 里的 trustedControlPlane。
- body 传输完整性和机密性由 HTTPS/mTLS、内网策略或 API Gateway 保障。
- 后续如果有强防篡改要求，可以对特定小 payload 加 body digest，而不是全量请求体默认签名。

### 5.3 数据范围是怎么传递的？

回答：

Gateway 调用 permission-admin 后，拿到数据范围判定结果，再注入低敏 header，例如 data scope level、data scope expression、authorized project ids、approval required。

这解决两件事：

1. 下游服务知道用户能访问哪些项目或数据范围。
2. Python Runtime 的 Agent 规划知道本轮不能越过数据边界。

但要注意，header 只是一层控制面传递。最终业务查询仍然要在 Java 服务里按租户、项目和数据范围落到 SQL 或服务端校验。

### 5.4 permission-admin 不可用怎么办？

回答：

要分入口和风险级别。

管理、写操作、高风险 Agent 工具入口应该 fail-closed，权限中心不可用就拒绝，避免越权。低风险只读、灰度或 shadow 阶段可以配置 fail-open，但必须打指标和日志。

项目里的 GatewayAuthorizationFilter 支持 shadow mode、fail-open/fail-closed 和 metrics。上线初期可以 shadow mode 校准权限矩阵，正式启用后高风险入口使用 fail-closed。

### 5.5 如何防止日志和 checkpoint 泄露敏感数据？

回答：

项目有低敏控制面原则：

- runtime event 只保存状态、计数、策略 code、role、checkpoint locator。
- LangGraph checkpoint 只保存恢复需要的摘要，不保存 prompt、SQL、样本数据、模型输出、RAG answer、sourceUri、artifact body、token 或 secret。
- RAG 工具只保存 queryRef，不保存原始 question。
- artifact 正文读取需要 access grant 和 final check。
- 日志只记录 traceId、tenantId、runId、状态码、原因码和低敏摘要。

我会把这个作为企业 Agent 的基本要求，因为 Agent 系统的泄露面通常不在主业务表，而在日志、事件、缓存和调试面板。

## 6. RAG 与模型类

### 6.1 RAG 在项目中怎么设计？

回答：

项目中的 RAG 不是孤立问答 API，而是 `KNOWLEDGE_AGENT` 的可调度能力。

`knowledge.rag.query` 是工具能力，输入只暴露低敏 `queryRef/scopePolicy/evidencePolicy`。`knowledge.rag.answer` 是 Skill 能力，用于治理知识、业务口径、数据标准、runbook 解释等场景。

RAG API 通过 LangGraph checkpoint 节点记录：

- `rag_retrieve_knowledge`
- `rag_evidence_gate`
- `rag_grounded_answer_completed`
- `rag_no_evidence_completed`

证据不足时 fail-closed，不让模型无依据编造治理规则。

当前边界也要说清：RAG 已接入 KNOWLEDGE_AGENT、Java outbox -> Python command worker、answer artifact、Java grant/final-check 和真实 MinIO smoke，但 PostgreSQL/pgvector Knowledge Store、MinIO 文档解析/增量索引、专用 reranker、Neo4j GraphRAG、对象存储层 DLP/完整下载服务和全平台 E2E 仍是生产增强项。

### 6.2 RAG 怎么评测？

回答：

我会分五类指标：

第一是检索指标：Recall@K、MRR、命中正确文档比例。

第二是证据指标：证据是否符合租户、项目、版本、时间和权限 scope。

第三是生成指标：答案是否 grounded，是否引用了有效证据，是否出现 hallucination。

第四是 fail-closed 指标：无证据或弱证据时是否拒答，而不是编造。

第五是工程指标：P95/P99 延迟、token 成本、rerank 耗时、cache 命中率、错误率、checkpoint 低敏合规。

如果是上线前，我还会构造黄金集：治理制度问答、数据标准问答、runbook 问答、权限边界问答、无证据问答和跨租户越权问答。

### 6.3 模型 provider 如何设计？

回答：

项目不把业务模块绑定到某个模型族。模型能力通过 provider-neutral adapter 处理，按 generation、code、multimodal、embedding、reranker、model routing 分层。

这样做有几个原因：

- 模型更新很快，不能让业务代码写死 Qwen2 或某个 API。
- 不同任务需要不同模型能力，通用推理、代码生成、embedding、reranking 不应该混用同一个配置。
- 生产要支持超时、重试、熔断、降级、健康检查和 usage 统计。

面试时我会说：旧文档可能有 Qwen2 痕迹，但当前推荐是 Qwen3.5、DeepSeek-V3.2 或同等级当前主流模型候选，通过模型路由屏蔽差异。

### 6.4 如果模型 provider 失败怎么办？

回答：

要看失败发生在哪个阶段。

如果是意图分析或规划辅助失败，可以降级到规则式 IntentAnalyzer 和 ToolPlanner，返回可解释的低风险计划。

如果是高风险工具执行前的模型失败，不能自动补脑执行，应转人工或 fail-closed。

如果是 RAG 生成失败，但检索证据存在，可以返回证据摘要或提示稍后重试；如果证据不足，直接 fail-closed。

工程上需要记录 provider、model route、错误码、超时、重试次数、token usage、traceId，并通过指标触发告警。

## 7. LangGraph 与多 Agent 类

### 7.1 LangGraph 在项目里做了什么？

回答：

LangGraph 主要承担状态机和可恢复编排，不是直接替代 Java 控制面。

当前项目里 LangGraph 用在几类地方：

- Agent planning workflow。
- Multi-agent turn runner。
- RAG 检索、证据门控和 grounded answer/fail-closed。
- Execution gate workflow。
- Memory retrieval workflow。
- Durable checkpoint。

重要边界是：LangGraph 不直接写业务数据库，不直接执行高风险工具，不创建审批，不派发 worker。它保存低敏状态和下一步恢复要求，真实副作用仍回到 Java proposal/outbox/worker receipt。

### 7.2 Durable checkpoint 里保存什么？

回答：

只保存低敏恢复摘要，例如：

- threadId、checkpointId、parentCheckpointId
- graphName、graphVersion、nodeName
- checkpointStatus、checkpointVersion
- nextNodes、resumeRequirementKeys
- role/status、handoffRequired
- 状态计数、策略 code、requiredEvidenceCodes

明确不保存：

- checkpoint state 正文
- 用户目标和 prompt
- ToolPlan.arguments
- SQL、样本数据、模型输出
- RAG answer、文档正文、sourceUri
- artifact body
- endpoint、token、secret

这样既能让 Java 管理台知道“卡在哪里、从哪里恢复”，又不会把敏感正文扩散到 checkpoint 和 projection。

### 7.3 Java turn runner projection 有什么作用？

回答：

Python Runtime 会产生 `agent_turn_runner_recorded` 事件，并附带低敏 `turnRunnerCheckpoint` locator。Java agent-runtime 的 projection service 解析这些白名单字段，形成管理台可查询视图。

它可以回答：

- 本轮多 Agent turn 是 WAITING 还是 BLOCKED。
- 哪些 Agent role 卡住。
- 缺哪些 required evidence。
- 是否需要 control plane handoff。
- 对应哪个 LangGraph checkpoint。

当前它复用 runtime event projection store，没有直接读 PostgreSQL checkpoint 表。后续如果查询量或审计保留期上升，可以下沉成专用索引表。

### 7.4 多 Agent 当前有哪些边界？

回答：

当前已经实现多 Agent 角色、能力合同、turn runner summary、checkpoint locator 和 Java projection，但不应夸大成所有 Agent 都已经独立常驻、自动完成真实业务执行。

真实 specialist turn 执行、结果仲裁、RAG/工具 command proposal/outbox、worker receipt、Java turn fact 专用索引、PostgreSQL checkpoint smoke 仍是后续路线。

这个边界在面试里反而是加分点，因为说明我知道 Agent 工程化不是“能跑图”就结束，还要接权限、审批、执行、回执和生产恢复。

## 8. 上线与生产类

### 8.1 项目如何上线？

回答：

我会先区分本地闭环和生产上线。

本地闭环可以用 Docker Compose、Keycloak realm import、各服务 health、Python Runtime 测试、Maven 测试和 smoke check 验证。

生产上线不能直接用 Compose，需要：

- Kubernetes/Helm。
- Secret Manager 或 Kubernetes Secret。
- TLS/mTLS。
- 企业 IdP 或高可用 Keycloak。
- 镜像固定 tag、SBOM、签名和漏洞扫描。
- 数据库、Kafka、Redis、MinIO、Neo4j、Keycloak/Nacos 的备份恢复。
- 容量基线和故障演练。
- worker、dispatcher、真实工具提交默认关闭，经过审批和演练后逐步打开。

### 8.2 上线前你会做哪些压测？

回答：

我会分链路做容量基线，而不是只压一个接口。

Gateway：授权、路由、HMAC 签名、decision cache 命中率、P95/P99。

Java 服务：任务查询、状态变更、claim/heartbeat、outbox dispatcher、权限判定、tool audit。

Python Runtime：`/agent/plans`、Intent、ToolPlanner、RAG query、LangGraph checkpoint、MCP tools/list/call。

Kafka/outbox：backlog、重试、dead letter、消费延迟。

存储：PostgreSQL/MySQL 慢查询、Redis 命中率、MinIO 上传下载、pgvector 检索、Neo4j 查询。

模型 provider：超时率、错误率、token usage、并发、fallback。

压测目标不是一开始追极限，而是形成初始资源 requests/limits、告警阈值和扩容策略。

### 8.3 你会做哪些故障演练？

回答：

优先做 P0 链路：

- Keycloak/企业 IdP 不可用：Gateway 应返回清晰 401/503，不泄露内部错误。
- 下游服务不可用：Gateway 应保留 503，不误包装成 500。
- permission-admin 不可用：高风险入口 fail-closed，shadow/fail-open 只用于低风险灰度。
- Kafka 暂停：outbox backlog 可观测，恢复后可重试。
- Python Runtime 不可用：Agent 规划失败受控，不影响普通 Java 业务服务健康。
- 数据库不可用：服务 health 异常，不能无限重试打爆连接池。
- RAG store 不可用：知识问答降级或 fail-closed，不影响其他控制面。
- worker 崩溃：lease 过期后恢复扫描能 requeue 或转人工。

每次演练要记录触发方式、预期结果、实际结果、恢复步骤、RTO/RPO、告警时间线和修复项。

### 8.4 哪些能力生产默认关闭？

回答：

真实副作用相关能力应该默认关闭，例如：

- Java/Python worker loop 和 outbox dispatcher。
- DataSync/DataQuality 真实写入执行器。
- Agent 真实工具提交和命令执行。
- artifact 正文读取。
- 自动终态回调、自动补偿、高风险恢复动作。

原因是这些能力一旦打开就可能写业务数据、触发外部系统、读取敏感内容。启用前必须完成权限、审批、审计、幂等、死信、回滚、容量、告警和值班流程。

## 9. 实际问题与排障类

### 9.1 Gateway 把下游不可用报成 500，你怎么排查？

回答：

我会先看 Gateway route 和 LoadBalancer。项目里遇到过类似问题：下游不可用本应是 503，但缺少 Spring Cloud LoadBalancer 或异常处理不保留 `ResponseStatusException` 状态时，可能被包装成 500。

排查步骤：

1. 看 route 是否正确命中。
2. 看服务发现或静态 URI 是否可达。
3. 看是否缺少 loadbalancer 相关依赖和缓存依赖。
4. 看 GlobalExceptionHandler 是否把 ResponseStatusException 统一转成 500。
5. 用 Actuator health 和 gateway 日志验证。

修复方向：

- 补齐 Spring Cloud LoadBalancer 和 Caffeine。
- GlobalExceptionHandler 保留原始 status code。
- 对下游不可用返回 503，并记录低敏 traceId。

### 9.2 Agent 计划里出现了用户原始 prompt，怎么办？

回答：

这是低敏控制面违规。我会先定位是哪个阶段写入的：

- ToolPlanner 是否把原始 query 放进 arguments。
- runtime event builder 是否直接序列化了 AgentPlan。
- checkpoint recorder 是否保存了 state 正文。
- log/metric 是否把 request body 打出来。

修复策略：

- 用 queryRef 替代原文。
- 建白名单 DTO，只允许低敏字段进入 event/checkpoint。
- 在测试中加入敏感字符串断言，验证 secret objective、SQL、sourceUri、hidden_customer 等不会出现在 checkpoint 和 event。
- 对历史事件按保留策略清理或脱敏。

### 9.3 worker 一直 heartbeat 失败怎么办？

回答：

先判断是状态冲突、执行权丢失还是控制信号。

排查：

1. execution 是否仍是 RUNNING。
2. executorId 是否匹配。
3. lease 是否已经过期并被恢复流程接管。
4. 是否被 PAUSED 或 CANCELLED。
5. heartbeat 的 idempotencyKey 是否重复。
6. 数据库更新是否返回 0 行。

项目里 heartbeat 更新失败后会重新读取最新状态。如果最新是 PAUSED/CANCELLED，就返回明确停止指令，而不是让 worker 误判为普通失败重试。

### 9.4 outbox 进入 dead letter 怎么办？

回答：

先不要手动改状态。要看 dead letter 原因：

- 下游一直不可用。
- payload schema 不兼容。
- 消费端幂等冲突。
- 权限或租户上下文缺失。
- 重试次数耗尽。

处理步骤：

1. 查 traceId、receiptId、tenantId、target service、last error。
2. 验证下游服务和 topic。
3. 如果是临时故障，修复后手动 retry。
4. 如果是 payload 错误，修复代码或数据后补偿。
5. 如果是不可恢复错误，标记 ignore 但保留审计。

### 9.5 RAG 没有证据但用户要求必须回答怎么办？

回答：

不能为了满足用户而编造。项目设计是 fail-closed。

可以返回：

- 当前知识库没有足够证据。
- 已使用的 scope 和检索策略。
- 建议用户补充文档、选择范围或转人工。
- 如果允许，只返回通用流程性说明，不给具体业务结论。

企业数据治理场景里，错误口径比拒答风险更大。

### 9.6 权限明明配置了但 Agent 还是看不到 Skill，怎么排查？

回答：

排查顺序：

1. Skill publication 是否 READY。
2. 租户是否启用该 Skill。
3. actor role 是否满足 admission policy。
4. workspace 风险是否阻断高风险 Skill。
5. Gateway Skill visibility cache key 是否基于正确租户、角色、workspace、数据范围和预算策略。
6. cache TTL 是否导致旧结果。
7. Python Runtime 是否只缓存 admission decision，而不是缓存完整 prompt 或 AgentPlan。
8. HMAC 签名后的 header 是否被 Python 验证通过。

### 9.7 Agent run 已经终态但又收到回调怎么办？

回答：

终态不可回滚。`AgentRunState.isTerminal()` 用来保护 SUCCEEDED、REJECTED、FAILED、CANCELLED 后不再被并发回调推进。

收到终态后的回调应记录审计或幂等重复结果，但不能把 run 改回 TOOL_CALLING、WAITING_MODEL 或 RUNNING。否则会出现事故复盘时状态倒退。

### 9.8 模型生成了危险 SQL，系统怎么处理？

回答：

首先模型不能直接执行 SQL。危险 SQL 只会作为 proposal 或草案进入控制面。

处理链路：

- ToolPlanner 标记高风险或写操作。
- command safety precheck 检查危险命令、路径、网络和参数。
- 高风险动作进入 WAITING_APPROVAL。
- approval fact 校验通过后才可能进入 outbox。
- worker 执行前还要校验租户、项目、工具、命令、sandbox admission。
- 输出要脱敏，artifact 正文读取需要 grant。

当前仓库里真实容器级 sandbox 仍是生产增强项，不能说已经具备完整 namespace/cgroup 隔离。

## 10. 系统设计延伸类

### 10.1 如果要把 RAG 接到真实 pgvector，你怎么设计？

回答：

我会按 ingestion、index、query、eval 四层设计。

Ingestion：从 MinIO、文档上传、runbook、数据标准、治理制度导入，解析后生成 document、chunk、metadata、tenant/project/workspace scope。

Index：PostgreSQL/pgvector 保存 embedding，metadata 字段支持租户、项目、文档版本、有效期、敏感级别、sourceRef。必要时增加 reranker。

Query：先按权限 scope 过滤，再向量召回和关键词召回混合，rerank 后进入 evidence gate。

Eval：黄金集评测 Recall@K、证据有效率、grounded answer、fail-closed、延迟和低敏泄露扫描。

控制面：RAG runtime event/checkpoint 仍只保存计数、策略 code 和 locator，不保存正文。

### 10.2 如果 QPS 增加，DB 轻量队列不够怎么办？

回答：

当前 task-management 的 DB 条件更新队列适合第一版可解释闭环，不是最终高吞吐方案。

扩展方案：

- 保留 DB 作为业务事实和幂等裁判。
- 用 Kafka topic 做任务分发和 backlog 缓冲。
- worker 消费消息后仍到 DB claim，防止重复消费导致并发执行。
- 分区按 tenant/project/taskType。
- 引入批量 claim、优先级 topic 或延迟队列。
- 指标关注 backlog、claim 冲突率、lease timeout、defer rate。

这样可以提高吞吐，同时不丢掉业务状态机和审计。

### 10.3 如何做多租户隔离？

回答：

多租户隔离要贯穿入口、业务、存储、Agent 和可观测。

入口：Gateway 从 JWT/OIDC 解析 tenantId，清理外部伪造 header。

权限：permission-admin 按 tenant/project/role/workspace 计算数据范围。

业务：所有表和查询带 tenantId/projectId，不能只信前端参数。

Agent：trustedControlPlane、ToolPlan、Skill admission、RAG scope 都带租户和 workspace。

存储：pgvector、MinIO、Neo4j、Redis key 都要有租户/项目 namespace。

可观测：metric 避免高基数和敏感标签，日志只记录低敏 tenant/run/trace。

### 10.4 如何设计 Agent 评测体系？

回答：

我会分离离线评测、在线观测和人工复盘。

离线评测：

- Intent 分类准确率。
- ToolPlan 正确率。
- 参数补齐准确率。
- 审批/风险识别准确率。
- RAG groundedness。
- 无证据 fail-closed。
- 低敏泄露扫描。

在线观测：

- plan latency。
- model provider 错误率和超时率。
- 工具 proposal 数。
- approval rate/reject rate。
- worker success/failure/defer/dead letter。
- RAG no evidence rate。

人工复盘：

- 抽样 Agent plan。
- 检查是否越权、是否误触发工具、是否缺少澄清、是否过度保守。
- 把复盘结果反馈到规则、prompt、模型路由和工具 schema。

### 10.5 如果要接企业微信或钉钉，你怎么做？

回答：

我不会让多渠道直接绕过 Gateway。企业微信/钉钉适配器应该只负责消息入口、身份映射和格式转换，然后进入统一 Gateway 或统一 Agent session API。

关键点：

- 外部用户身份映射到企业 IdP 或平台 actor。
- tenant/project/workspace 必须明确。
- 高风险动作不能在聊天里一句“同意”就执行，要生成服务端 approval fact。
- 消息内容进入 Agent 前要做敏感过滤和长度限制。
- 结果推送要遵守数据范围，不能把 artifact 正文直接发到群里。

## 11. 技术取舍类

### 11.1 为什么不用纯 Kafka 做所有状态？

回答：

Kafka 适合事件流和异步解耦，但不适合承载所有可查询业务事实。任务当前状态、审批事实、权限策略、执行记录、artifact grant 都需要强查询、事务和审计。

项目使用 Kafka/outbox 做异步协调，用 PostgreSQL/MySQL 兼容层保存业务事实。这样既有事件驱动能力，也保留管理台查询和恢复能力。

### 11.2 为什么不用 Redis 锁做幂等？

回答：

Redis 可以做短期去重，但业务幂等最好落在数据库唯一键上。原因是数据库唯一键和业务事务在一起，能保证“幂等裁决”和“状态推进”一致。

Redis 锁可能过期、丢失、主从切换，还需要处理锁释放和续期。对于任务完成、审批执行、receipt 这类长期可审计动作，我更倾向数据库唯一键。

### 11.3 为什么不让 LangGraph 直接执行工具？

回答：

从 demo 角度可以，但企业场景风险太高。LangGraph 直接执行工具会把权限、审批、幂等、审计、worker 恢复和业务状态写入都放到 Python Runtime，Java 控制面就失去事实源。

项目里 LangGraph 负责规划、门控、低敏 checkpoint 和恢复摘要。真实工具执行必须通过 Java proposal/outbox 和 worker receipt。这样出事故时能查到谁批准、什么时候执行、哪个 worker 执行、结果如何、是否可补偿。

### 11.4 为什么不把 prompt 全量记录下来方便调试？

回答：

因为 prompt 可能包含客户数据、表名、字段、SQL、业务口径、凭据片段或用户输入隐私。全量记录确实方便调试，但会把敏感数据扩散到日志、事件、对象存储和监控系统。

更好的方式是：

- 保存 prompt hash、长度、策略版本、模板版本、模型 route。
- 保存低敏上下文来源 code。
- 对问题复现使用受控样本或脱敏 replay。
- 必要时通过短期受控 debug 开关采集，并有审批和保留期。

## 12. 最新能力深挖类

### 12.1 RAG command worker 的完整链路是什么？

回答：

当前项目里的 RAG 已经不只是 `/agent/rag/query` 这个同步查询接口，而是进入了受控 command worker 链路。

完整链路是：

1. Java agent-runtime 生成 `knowledge.rag.query` command proposal/outbox。
2. outbox dispatcher 通过 `HttpAgentRagCommandWorkerClient` 调 Python 内部路由 `/internal/agent/rag/command-worker/run`。
3. 请求里 `arguments.question` 作为短生命周期输入给 Python，`controlFacts` 携带 commandId、runId、sessionId、tenantId、projectId、traceId 等低敏事实。
4. Python `RagCommandWorkerRunner` 执行 RAG pipeline。
5. 如果配置 artifact writer，Python 把 answer、citations、compressedContext 写入受控 artifact。
6. Python 返回 `javaReceiptPayload`、`artifactWrite`、`langGraphCheckpoint` 等低敏摘要。
7. Java `AgentRagCommandWorkerReceiptIngestionService` 把 receipt 写入统一 command worker receipt 控制面。
8. 后续用户要看答案正文，必须通过 artifact grant/read final-check，而不是从 receipt 或 checkpoint 读取。

关键边界：

- Java 控制面不保存 question、answer、compressedContext、chunk text、sourceUri。
- RAG receipt 是只读查询语义，`sideEffectStarted=false`、`sideEffectExecuted=false`。
- HTTP 调 Python 失败时不让业务线程直接失败半提交，而是让 outbox dispatcher 进入重试或死信。

### 12.2 为什么要把 `/agent/rag/query` 和 `/internal/agent/rag/command-worker/run` 分开？

回答：

两个接口面向不同对象。

`/agent/rag/query` 是产品或调试查询入口，可以返回 answer、citations、compressedContext，适合学习、调试、交互式问答。

`/internal/agent/rag/command-worker/run` 是 Java outbox 或未来 Kafka consumer 调用的 worker 入口。它必须低敏，只返回 receipt、javaReceiptPayload、artifactWrite、postResult 和 checkpoint summary。

如果把两者混用，就会出现一个风险：Java 控制面为了拿执行结果，把展示型 API 的 answer、citation snippet、compressedContext 一起持久化到 receipt/projection 里。这个项目专门拆开接口，就是为了把“可展示正文”和“可持久控制事实”分开。

### 12.3 RAG answer artifact writer 解决什么问题？

回答：

RAG 生成的 answer、citations 和 compressedContext 是用户可能需要查看的正文级结果，但这些内容不能进入 Java receipt、runtime event、projection 或 checkpoint。

artifact writer 的作用就是把正文级结果写到受控存储里，对控制面只暴露低敏元数据：

- artifactReference
- artifactReferenceType
- storageBackend
- byteSize
- contentSha256
- citationCount
- selectedChunkCount
- objectKeyDigest

Python 当前有 local writer 和 MinIO/S3-compatible writer。local writer 用于本地闭环和 CI，MinIO writer 用于真实对象存储。两者实现同一个 `RagAnswerArtifactWriter` 协议，所以 RAG pipeline、command worker 和 Java receipt 合同不需要关心具体存储后端。

### 12.4 MinIO/S3 writer 为什么懒加载 boto3？

回答：

这是为了保持 Python Runtime 默认依赖轻量。不是每个环境都会启用对象存储写入：本地学习、纯规划测试、LangGraph checkpoint 单测都不应该强制安装 boto3 或启动 MinIO。

所以 writer 支持两种方式：

- 单测注入 fake `s3_client`，不用安装 boto3。
- 真实运行时启用 MinIO/S3 writer，才懒加载 boto3。

如果生产启用了 MinIO writer 但镜像没装 `python-ai-runtime[object-store]`，写入会失败，并交给 Java outbox 的重试/死信治理，而不是返回一个“receipt 成功但正文没写”的假成功。

### 12.5 artifactReference、objectName、bucket/key、signed URL 有什么区别？

回答：

artifactReference 是控制面可以保存和传递的低敏引用，例如 `agent-artifact:{runId}/{commandId}/rag-answer-{hash}.json`。它不等于真实 bucket/key。

objectName 是对象存储内部定位路径，由 Python writer 和 Java locator 按同一映射规则从 artifactReference 推导出来。它不应该进入普通响应。

bucket/key 是对象存储真实存储位置，属于后端存储细节，不能出现在 Java receipt、runtime event、checkpoint 或前端普通响应。

signed URL 是下载授权凭据，风险更高。项目当前 grant/final-check 链路不直接签发 signed URL，只签发低敏 grantDecisionReference。真正要下载时，应由对象存储服务在 ACL、DLP、恶意内容扫描、下载审计、保留期和限速都通过后单独生成短时 URL。

### 12.6 Java artifact grant/final-check 为什么要做三段式？

回答：

因为“能看到 artifactReference”不代表“能读正文”。

三段式是：

第一段 metadata authorization：确认 artifactReference 能匹配当前 tenant/project/actor/run/session/command 范围内的 worker receipt，防止调用方伪造引用。

第二段 body-read grant：调用方必须说明 readPurpose、contentMode、maxReadableBytes、requesterComponent。服务端只签发低敏 grantDecisionReference，不返回正文、不签 URL、不发 token。

第三段 final-check：对象存储服务把已经脱敏的候选预览提交给 Java，Java 复查 grant 引用、读取目的、预览形态、敏感标记和 UTF-8 字节上限。

这样可以把“归属校验”“读取意图校验”“最终内容安全校验”分开。后续要接 DLP、水印、下载审计、限速，也有清晰位置。

### 12.7 `RAG_ANSWER_VIEW` 为什么不复用 `TASK_RESULT_VIEW`？

回答：

RAG answer 是模型生成产物，风险和普通任务结果不同。它可能包含 prompt 影响、检索证据、压缩上下文、模型幻觉和引用片段。

单独定义 `RAG_ANSWER_VIEW` 的好处是：

- 可以绑定更严格的 prompt/context 泄露检查。
- 可以设置更低的预览字节上限。
- 可以单独统计 RAG answer 查看审计。
- 可以要求更严格的人工复核或 DLP 策略。
- 不会把模型产物误归类为普通任务执行结果。

### 12.8 RAG answer artifact 写入成功，但 final-check 拒绝，如何排查？

回答：

我会按链路分层排查：

1. Python writer 是否成功返回 artifactReference、contentSha256、objectKeyDigest。
2. Java receipt 是否 ingest 成功，`artifactAvailable=true`、`artifactReferenceType=AGENT_RAG_ANSWER_ARTIFACT`、`toolCode=knowledge.rag.query`。
3. metadata authorization 是否能通过 tenant/project/actor/run/session/command 范围校验。
4. body-read grant 的 readPurpose 是否是 `RAG_ANSWER_VIEW`。
5. requestedContentMode 是否是 `TRUNCATED_TEXT_PREVIEW` 或 `SAFE_RENDERED_PREVIEW`。
6. previousGrantDecisionReference 是否来自 grant 服务，并且形态合法。
7. safePreviewText 是否被对象存储服务脱敏，是否包含 token、SQL、sourceUri、secret 等敏感标记。
8. preview 字节数是否超过 HARD_PREVIEW_BYTES。

如果是对象存储读不到，则看 Java locator 映射和 Python objectName 是否一致，MinIO smoke 脚本就是专门验证这个问题的。

### 12.9 RAG command worker 如何保持幂等？

回答：

RAG worker 链路有几层幂等思路：

- Java outbox commandId 是稳定业务动作标识。
- Python request 携带 idempotencyKey，receipt payload 也返回 idempotencyKey。
- Java receipt ingest 进入统一 command worker receipt 服务，由服务端按 command/run/session/receipt 语义去重。
- artifactReference 可以由上游预分配，也可以由 writer 根据 commandId/runId/hash 生成，避免同一次执行产生不可追踪的随机正文对象。
- contentSha256 用于对象正文对账。

真正生产里还可以继续增强：对象存储写入用 If-None-Match 或版本化 bucket，Java receipt store 加唯一索引，outbox dispatcher 对同一 commandId 做终态保护。

### 12.10 为什么 RAG receipt 要保持 `sideEffectExecuted=false`？

回答：

因为 RAG 查询本身是只读知识检索，不应该被审计成写操作或命令副作用。

即使 Python 写了 answer artifact，这个 artifact 是结果保存，不代表对业务系统执行了写入型工具，比如创建同步任务、修改质量规则、执行 SQL 或调用外部 API。

把它标成 `sideEffectExecuted=false` 有两个价值：

- 审计报表不会把 RAG 查询误判为生产副作用。
- 工具治理策略可以把 RAG answer 查看和高风险写操作分开处理。

### 12.11 身份供应为什么放在 permission-admin，而不是 gateway？

回答：

Gateway 是入口网关，适合做 token 校验、上下文注入、限流、路由、HMAC 签名和授权前置。它不适合持有密码、不适合调用 Keycloak Admin API，也不适合管理用户生命周期。

账号供应属于权限管理和身份治理的一部分，所以放在 permission-admin：

- 可以复用角色权限和租户边界。
- 可以写权限审计。
- 可以保存低敏影子身份。
- 可以把 Keycloak/企业 IdP 封装在 `IdentityProviderAdminClient` 抽象后面。

这样 gateway 保持薄入口，permission-admin 持有身份管理业务规则，IdP 持有真实认证事实。

### 12.12 DataSmart 为什么不保存用户密码？

回答：

因为企业身份认证不只是密码字段，还包括 MFA、密码策略、账号锁定、登录审计、会话管理、client secret、token 轮换、企业目录同步等。

这些能力应该由 Keycloak、Okta、Azure AD、LDAP/OIDC 网关等成熟 IdP 承担。DataSmart 保存密码会扩大攻击面，也会让合规责任变重。

项目中 DataSmart 只保存影子身份：

- providerUserId
- tenantId
- actorId
- actorRole
- actorType
- workspaceId
- status

它不保存 password、refresh token、Keycloak admin token、client secret 或任何可直接登录凭据。

### 12.13 影子身份表有什么用？

回答：

影子身份表解决的是“外部 IdP 用户如何映射成 DataSmart 平台 actor”。

IdP 里保存的是用户认证事实，例如用户名、密码哈希、realm、client、角色和会话。DataSmart 业务服务需要知道这个用户在平台里属于哪个租户、哪个 actorId、什么角色、哪个 workspace、当前是否启用。

所以 `PermissionIdentityUser` 保存低敏映射。这样做的好处是：

- 不复制密码和 token。
- 可以按 DataSmart 租户模型管理角色。
- 可以审计账号供应、禁用和重置密码动作。
- 可以支持企业 IdP、Keycloak、SCIM、LDAP bridge 等不同 provider。

### 12.14 身份供应为什么当前是同步调用，未来怎么演进？

回答：

当前同步调用是为了先闭环最小能力：管理员请求 register/disable/reset，permission-admin 调 Keycloak Admin API，成功后写影子表和审计。

这个设计简单、可测试，适合本地 Keycloak 和早期集成。

生产高可用版本可以演进成 outbox/saga：

1. 管理员提交账号供应请求。
2. 本地写 `PENDING_PROVISION` 影子记录和 outbox。
3. 异步 worker 调 IdP。
4. 成功后更新 `ACTIVE`。
5. 失败后更新 `FAILED`，记录 errorCode。
6. 提供 retry、ignore、补偿删除或人工修复入口。

这样可以避免 IdP 短暂不可用导致管理接口长时间阻塞，也能支持批量组织同步。

### 12.15 Keycloak 为什么要从文件卷迁到 PostgreSQL？

回答：

Keycloak 的 realm、用户、client、角色、密码哈希、会话和密钥轮换历史都是认证中心事实数据，不能依赖容器文件目录。

使用 PostgreSQL-backed 存储的好处是：

- 可备份、可恢复。
- 可以进入数据库审计和容量评估。
- 可以迁移到托管 PostgreSQL。
- 可以做 HA/集群基础。
- 数据生命周期和容器生命周期分离。

项目还做了一个 `keycloak-db-bootstrap` 一次性服务，解决已有 `postgresql_data` 卷不会重跑 initdb 脚本的问题。脚本幂等，只补齐角色、数据库和权限，不删除已有 realm 或用户。

### 12.16 Keycloak PostgreSQL-backed 生产还差什么？

回答：

当前本地 smoke 只能证明 Compose 单机模式可用，不等于生产 HA 完成。

生产还需要：

- TLS 和固定 hostname。
- 正式 confidential client。
- Secret Manager 注入数据库密码、client secret、admin secret。
- Keycloak HA/集群或托管 IdP。
- 数据库备份恢复演练。
- realm export/import 或迁移策略。
- 最小权限管理员。
- 密码策略、MFA、账号锁定。
- IdP 故障演练。
- 登录审计和告警。

### 12.17 新增这些能力后，项目成熟度怎么重新表述？

回答：

我会这样表述：

项目已经从“Agent 规划和低敏 checkpoint 闭环”推进到“部分 RAG 工具具备 Java outbox -> Python worker -> Java receipt -> artifact grant/final-check 的受控执行链路”，并且身份体系从“OIDC/Keycloak 设计说明”推进到“账号供应、影子身份和 Keycloak PostgreSQL-backed 存储闭环”。

但我仍不会说已经完成客户生产上线，因为还缺：

- PostgreSQL LangGraph checkpoint 跨进程 smoke。
- Java outbox -> Python worker -> Java receipt index -> artifact grant/probe/final-check 的完整端到端联调。
- 全平台 Keycloak/gateway/permission-admin/agent-runtime/Python/task/data-sync/data-quality/observability 最小闭环 smoke。
- 对象存储服务层 DLP、ACL、水印、下载审计、限速。
- Keycloak HA、TLS、正式 client、Secret 轮换、备份恢复演练。

这种说法既能体现新增能力，又不夸大上线状态。

## 13. 面经深挖场景专项回答

这一节专门对应真实 AI Agent 面试里的“继续往下抠”问题。回答时不要只说概念，要把目标、状态、边界、工程机制、评测和排障一起说清楚。

### 13.1 上下文压缩为什么不是简单摘要？

回答：

我会先强调一句：Agent 的上下文压缩目标不是把文本变短，而是在 token 预算内保持后续决策一致、状态可恢复、证据不丢失，并且不把敏感正文扩散到日志、checkpoint 或事件里。

所以压缩时必须保留几类信息：

- 当前用户目标和已确认的约束，比如租户、项目、workspace、时间范围、数据范围、输出格式。
- 服务端控制事实，比如 actor、role、tool budget、Skill admission、approval fact、policyVersion、runId、commandId、traceId。
- 工作流状态，比如当前节点、上一个节点结果、未解决的澄清问题、等待审批的工具、可恢复 checkpoint locator。
- 工具调用事实，比如工具是否真正执行、执行结果是成功、失败、拒绝、超时、defer 还是 dead letter。
- RAG 证据事实，比如 queryRef、retrievalPolicyVersion、candidateCount、selectedChunkCount、citationCount、artifactReference。
- 记忆事实，比如 memoryNamespace、记忆来源、置信度、是否被用户拒绝、是否过期或被 superseded。
- 安全边界，比如哪些内容不能自动执行，哪些工具必须走审批，哪些上下文只能短生命周期进入模型。

不能保留的是 prompt 原文、完整工具返回、SQL、样本数据、文档正文、sourceUri、answer artifact body、token、secret、内部 endpoint 和完整异常堆栈。因为这些内容一旦进入 checkpoint、runtime event、metric、日志或诊断接口，就会变成二次泄露面。

项目里的落地方式是 `HybridContextBuilder` 先做过滤、去重、排序，再由 `ContextMicroCompactor` 在总 token 预算前做确定性微压缩。它不是让模型“自由总结”，而是抽取式、规则式、带可信度标记的微压缩，并通过 `CONTEXT_MICRO_COMPACTED` runtime event 只记录压缩块数量、来源类型分布、估算 token 节省和原因码，不记录正文。

如果面试官追问“为什么不用 LLM 摘要”，我会说：LLM 摘要可以作为后续增强，但不能作为唯一事实来源。因为 LLM 摘要可能漏掉审批状态、租约版本、失败原因、幂等键或证据编号。企业 Agent 里这些字段决定能不能执行工具，漏一个就可能越权或重复执行。所以项目当前优先用确定性压缩保存结构化控制事实，未来如果引入 LLM 摘要，也要用 schema 校验、关键字段回填、原始 locator 对账和离线 replay 评测。

### 13.2 超长工具返回怎么处理？

回答：

我不会把超长工具返回直接塞回下一轮模型。真实系统里工具返回可能包含 SQL 结果、日志、文件内容、RAG answer、MCP 工具正文、错误堆栈或对象存储路径，直接进入上下文会带来三个问题：超 token、泄露敏感数据、让模型基于不完整截断内容做错误决策。

我的处理方式分层：

1. 小结果可以做字段白名单摘要，只保留状态码、数量、耗时、错误码、schema 字段名和低敏 digest。
2. 中等结果做 tool-compact，只抽取和当前目标相关的片段，同时保留“被截断”“原始结果可通过 artifactReference 读取”的标记。
3. 大结果写入受控 artifact，例如项目里的 RAG answer artifact，控制面只保存 artifactReference、contentSha256、byteSize、citationCount、selectedChunkCount。
4. 后续正文读取必须走 metadata authorization、body-read grant、object-store probe 和 final-check，不能让模型或前端直接拿 bucket/key、signed URL。

在 DataSmart 的 RAG 链路里，answer、citations、compressedContext 可以进入 MinIO/S3-compatible artifact body，但 Java receipt、runtime event、checkpoint、projection 只保存低敏引用。这样排障时能知道“有结果、大小多少、hash 是否一致、引用数多少”，但不会把正文扩散到控制面。

### 13.3 怎么控制上下文压缩频率？

回答：

压缩频率不能简单设置成“每轮都压缩”。每轮压缩成本高，而且频繁摘要会累计信息损失。也不能等到完全超过窗口再压缩，因为那时已经可能丢掉关键状态。

我会用触发条件加滞后区间：

- 当前估算 token 超过模型窗口或业务预算的 70%-80% 时触发。
- 工具返回超过单工具结果预算时触发 tool-compact。
- 工作流跨阶段时触发，例如 planning -> waiting_approval、worker_receipt -> final_check。
- 长会话达到固定轮数或累计工具调用数时触发。
- RAG 检索候选过多、引用过多或 compressedContext 超过预算时触发。
- 低风险普通闲聊不频繁压缩，只做窗口裁剪。

为了避免震荡，压缩后要记录 summaryVersion、sourceEventRange、sourceHash、compressedAt、tokenBefore/tokenAfter。下一轮如果上下文没有明显增长，就不要重复压缩同一批内容。

### 13.4 怎么评估上下文压缩效果？

回答：

我会从“压缩率”和“决策一致性”两个方向评估，不能只看 token 省了多少。

核心评测指标包括：

- 压缩率：压缩前后 token 比例。
- 关键事实保留率：tenant/project/actor/tool/approval/checkpoint/errorCode/evidenceId 是否保留。
- 决策一致性：同一个样本在未压缩和压缩后，工具选择、参数、审批判断、RAG fail-closed 判断是否一致。
- 状态恢复率：只给压缩上下文和 Java host facts，能否恢复到正确节点。
- 幻觉事实率：压缩后是否凭空出现“已审批”“已执行”“证据充足”等事实。
- 安全泄露率：摘要、event、checkpoint 是否包含 prompt、SQL、token、sourceUri、artifact body。
- 线上观测：压缩后澄清率、工具失败率、RAG no evidence rate、用户追问“你忘了什么”的比例。

在 CI 里可以做 replay eval：固定一批多轮 Agent 样本，分别跑原始上下文和压缩上下文，对比 ToolPlan、readiness、approvalRequired、nextAction 和 fail-closed 结果。如果安全字段不一致，直接失败；如果只是表达不同，可以进入人工复盘。

### 13.5 工作流编排为什么不能只靠模型规划？

回答：

模型规划适合提出下一步建议，但不适合作为工作流事实源。因为模型输出不稳定，可能漏步骤、重复步骤、跳过审批、把失败当成功，或者在高峰期因为 provider 超时导致状态丢失。

所以我会把 Agent 工作流拆成两层：

- Python/LangGraph 层负责意图、计划、条件路由、低敏 checkpoint、RAG 和多 Agent 协作摘要。
- Java 控制面负责业务事实、审批、outbox、worker receipt、幂等、租约、审计和状态终态。

项目里 `/agent/plans` 主路径已经接入 LangGraph planning workflow、execution gate workflow、memory retrieval workflow 和 turn runner checkpoint，但它们当前主要是 preview/control-plane-ready，不直接执行真实工具。真实副作用必须通过 Java proposal/outbox，再由 dispatcher/worker 执行并回写 receipt。

这个设计的好处是：模型可以错，但系统状态不能跟着乱。模型错选工具时会被 tool registry、readiness、approval、budget、outbox writer 和 worker precheck 拦住。

### 13.6 工作流状态管理怎么设计？

回答：

状态要分清楚“AI 运行状态”和“业务事实状态”。

AI 运行状态包括：当前图节点、nextNodes、checkpointId、parentCheckpointId、graphVersion、resumeRequirementKeys、低敏协作摘要。这些可以放在 LangGraph durable checkpoint 或 Java projection locator 里。

业务事实状态包括：任务是否创建、审批是否通过、outbox 是否投递、worker 是否执行、receipt 是否写回、artifact 是否可读。这些必须由 Java 服务和数据库保存。

我不会把完整 prompt、工具参数、SQL、模型输出、RAG answer 放入 checkpoint。checkpoint 只保存恢复所需的低敏摘要；恢复时必须回查 Java host facts，比如 approval fact、outbox 状态、worker receipt、artifact grant，而不是相信请求体自报。

### 13.7 节点失败、中断恢复、重试和补偿怎么做？

回答：

我会先把失败分类：

- 参数或 schema 错误：不可重试，返回澄清或修正建议。
- 权限、审批、数据范围错误：fail-closed，等待授权或人工处理。
- provider 超时、网络抖动、Kafka/HTTP 临时失败：可重试，指数退避。
- 下游业务冲突：根据业务状态机判断是重试、defer、补偿还是人工介入。
- 安全预检失败：不可自动重试，除非策略或审批事实发生变化。

恢复机制上，长任务用租约和 heartbeat。worker claim 成功后写 executorId、leaseExpireTime、leaseVersion 或 fencing token；worker 崩溃后 lease 到期，由恢复扫描重新入队。重复消息靠 idempotencyKey、commandId、receiptId 和数据库唯一约束去重。

补偿不是简单“反向执行”。例如创建数据同步任务失败，可能只是 outbox 投递失败，补偿是 requeue；如果任务已创建但 worker 未回执，补偿是对账 receipt；如果高风险工具被拒绝，状态应是 REJECTED/SKIPPED，而不是 FAILED。

### 13.8 并行节点合并怎么处理？

回答：

并行节点合并最怕两个问题：一个是重复写，另一个是部分成功后主流程不知道该继续还是停。

我会给每个并行节点一个稳定 nodeId、attemptId、idempotencyKey 和输出摘要。合并节点不直接读大正文，而是读每个节点的低敏结果：SUCCEEDED、FAILED、SKIPPED、WAITING_APPROVAL、DEFERRED，以及 resultRef/hash/count。

合并策略要提前定义：

- all_success：所有节点成功才继续，适合强依赖任务。
- partial_ok：部分成功可继续，但要记录缺口，适合检索、多源诊断。
- quorum：达到一定数量或置信度即可继续。
- first_success：任一成功即可，适合 fallback provider。
- fail_fast：任一安全失败立即停止。

如果两个节点产生冲突结果，不能让模型拍脑袋合并，要按权威等级、时间版本、数据范围和来源可信度排序；仍冲突则进入 human_review。

### 13.9 如何防止工作流无限循环？

回答：

防无限循环要同时限制模型层和系统层。

模型层限制包括 maxIterations、maxToolCalls、maxSubAgentTurns、maxClarificationCount、maxCost、maxElapsedTime。系统层限制包括 tool budget、outbox per-run/per-tenant backlog、重复 ToolPlan digest 检测、相同错误码连续出现阈值、同一节点重试上限。

如果连续几轮没有产生新事实，比如没有新 approval、没有新 receipt、没有新 evidence、没有新用户输入，就要停止自动推进，返回 WAITING_HUMAN 或 DEAD_LETTER，而不是继续让模型“再想想”。

项目里的 tool budget、readiness gate、outbox capacity protection、defer/dead letter 和 AWAITING_OPERATOR_ACTION 都是这个思想的落地。

### 13.10 工作流版本升级怎么做？

回答：

工作流升级必须把 graphVersion、schemaVersion 和 policyVersion 当成一等字段。新版本上线时不能假设旧 checkpoint 都能直接恢复。

我会采用：

- checkpoint 里保存 graphName、graphVersion、nodeName、state schema version。
- 新版本对旧 checkpoint 做兼容读取，不认识的字段忽略，关键字段缺失则要求 resume preflight。
- 对高风险节点做双轨灰度，先 shadow mode 只记录新路由决策，不执行。
- 旧版本未完成 run 继续按旧 graph 恢复，或走显式 migration。
- 每个版本都有 golden workflow eval，覆盖中断恢复、审批后恢复、worker 失败、并行合并和无证据 RAG。

如果升级后高峰期卡死，我会先看每个节点的入队、出队、耗时、失败、重试和 backlog，而不是只看接口 500。

### 13.11 高峰期工作流卡死怎么排查？

回答：

我会按链路分层排查：

1. Gateway：401/403/429/5xx 是否异常，permission-admin 是否慢，HMAC 是否失败。
2. Python Runtime：模型 provider 队列、LangGraph workflow fallback、RAG 检索耗时、memory retrieval 耗时。
3. Java agent-runtime：outbox PENDING/DELIVERING/RETRY_WAIT/DEAD_LETTER 数量，dispatcher 线程是否饱和。
4. worker：lease claim 成功率、heartbeat 失败率、租约过期恢复次数。
5. DB/Kafka/Redis/MinIO：连接池、慢查询、consumer lag、对象存储超时。
6. 业务状态：是否大量 WAITING_APPROVAL、WAITING_BUDGET、AWAITING_OPERATOR_ACTION。

定位时一定要用 traceId、runId、commandId、checkpointId 串起来看。如果只有日志没有状态机，很容易把“等待审批”误判成“系统卡死”。

### 13.12 Agent Memory 和历史消息有什么区别？

回答：

历史消息是本次会话发生过什么，Memory 是经过筛选、治理、可跨轮次复用的长期事实或偏好。不能把聊天记录直接塞进向量库就叫记忆。

我会把 Memory 分成几类：

- episodic memory：某次任务经历，例如一次同步失败的排障结论。
- semantic memory：稳定知识，例如某项目的数据标准口径。
- procedural memory：操作偏好或流程，例如某租户要求质量规则先走审批。
- profile memory：用户偏好，例如用户习惯看简版报告。

DataSmart 的长期记忆采用候选、审批、物化、检索四段式。工具或模型先生成 memory write candidate，管理员或策略 approve/reject，`AgentMemoryMaterializationRunner` 领取 APPROVED 候选，`AgentApprovedMemoryWriteMaterializer` 写入正式 store，`StoreBackedAgentMemoryRetriever` 在 `/agent/plans` 构建上下文时按 tenant/project/workspace/memoryNamespace 召回低敏摘要。

这和“把所有聊天消息 embedding”完全不同。后者没有审批、没有 namespace、没有过期、没有删除、没有冲突治理，也很容易把错误输出变成长期污染。

### 13.13 记忆冲突怎么治理？

回答：

记忆冲突不能简单用相似度最高覆盖旧记忆。我会先区分冲突类型：

- 事实冲突：同一个字段两个不同值。
- 偏好冲突：用户习惯发生变化。
- 时效冲突：旧规则已过期，新规则生效。
- 权限冲突：某个 workspace 的记忆被错误召回到另一个 workspace。

处理方式：

- 记忆带 source、createdAt、expiresAt、confidence、memoryType、namespace、sourceRunId、sourceAuditId。
- 新记忆不直接覆盖旧记忆，而是把旧记忆标成 superseded 或保留多版本。
- 用户明确纠正优先级高于模型推断。
- 管理员 reject 的记忆不能被下一次自动观察直接复活。
- 冲突无法自动裁决时进入人工复核，不进入模型上下文。

项目里的 `user_profile_memory` 已经有 candidate、active、rejected、superseded、expired 语义，这正是为了避免记忆污染和错误复活。

### 13.14 怎么防止记忆污染？

回答：

记忆污染的本质是把临时、错误、敏感或越权的信息写成了长期事实。

我会用几层防护：

- 写入前只生成 candidate，不自动进入正式记忆。
- 低置信度、敏感级别高、缺少 workspaceKey/memoryNamespace 的候选直接拒绝或等待审批。
- 正式物化前检查 scope、sensitivity、retentionDays、contentSummary。
- 检索时先按 tenant/project/workspace/memoryNamespace 过滤，再做相似度排序。
- runtime event、诊断接口和指标不返回记忆正文。
- 对记忆写入、审批、物化、补偿重排写审计 outbox。
- 对失败候选使用 lease、退避、DLQ 和管理员 requeue，避免坏候选反复污染系统。

如果面试官问“为什么需要人工审批”，我会说不是所有记忆都需要人工，但会影响长期决策、跨会话复用、包含用户画像或业务规则的记忆必须经过策略或人工治理。

### 13.15 记忆删除怎么做？

回答：

记忆删除不能只删向量库，因为正式 store、二级索引、缓存、运行时上下文和审计记录都可能引用它。

我会设计成：

1. 正式 store 标记 memory status 为 deleted 或 revoked，并记录 deletionReason、operator、time。
2. 向量索引/FTS 索引删除或标记不可检索。
3. 缓存失效，包括 session context cache、retrieval cache。
4. 后续检索必须默认过滤 deleted/revoked/expired。
5. 已经生成的历史回答不强行改写，但后续不再引用该记忆。
6. 对合规删除保留低敏审计，不保留正文。

如果是用户要求“忘记我某个偏好”，要能定位 profile facet；如果是租户管理员删除项目规则记忆，要按 project/workspace namespace 批量处理，不能跨租户误删。

### 13.16 大模型工具调用完整步骤是什么？

回答：

我会把工具调用拆成 10 步：

1. Tool registry 声明工具名称、schema、风险等级、目标服务、执行模式、是否需要审批。
2. Gateway 和 permission-admin 生成可信控制上下文、数据范围、tool budget、Skill admission。
3. Python Runtime 根据意图、上下文、可见工具和预算生成 ToolPlan。
4. ToolPlan 做 schema 校验、参数字节预算、风险标记和 readiness 检查。
5. 参数缺失时进入 clarification，不让模型编造关键参数。
6. 高风险或写操作进入 approval fact 校验。
7. 达到执行条件后由 Java 写 command proposal/outbox。
8. dispatcher/worker 执行，带 lease/fencing/idempotency。
9. worker 写低敏 receipt，Java 做幂等接收和 projection。
10. 如果需要模型二轮解释，只把低敏结果或受控 tool feedback 给模型，不直接回填完整工具正文。

项目里的 `AgentToolExecutionAuditService`、tool readiness、command outbox、RAG/MCP worker receipt、artifact grant/final-check 都是在补这个闭环。

### 13.17 模型选错工具怎么办？

回答：

模型选错工具不能等执行后才发现。我的处理策略是执行前多层拦截：

- 工具目录只暴露当前角色可见工具。
- Skill admission 判断当前 workspace 和权限是否允许。
- Tool budget 限制工具数量和高风险工具数量。
- readiness 判断参数、审批、payloadReference、resume facts 是否齐全。
- 写操作必须走审批和 outbox writer 服务端复核。

如果模型选择了错误工具但还没执行，返回 clarification 或 rejected tool reason，让模型二轮改计划。如果已经执行了只读工具，可以记录为低风险失败样本；如果是写工具，正常设计下不会被模型直接执行，因为写操作必须经过 Java 控制面审批和 outbox。

面试时我会强调：工具选择准确率要评测，但生产安全不能依赖工具选择准确率。

### 13.18 参数缺失怎么处理？

回答：

参数缺失分三类：

- 可从可信上下文确定，比如 tenantId、actorId、workspaceKey，这些来自 Gateway/permission-admin，不让模型自己填。
- 可从历史状态确定，比如 runId、commandId、artifactReference，这些从 Java host facts 或 checkpoint locator 回查。
- 业务关键参数缺失，比如要同步哪个数据源、要禁用哪个用户、要创建什么任务，这些必须向用户澄清。

我不会让模型猜测关键参数。猜错数据源、用户或任务，比返回一个澄清问题风险大得多。项目里的 ToolPlan 只保存低敏参数和 governance hints，真实执行前还要经过 Java writer/precheck 复核。

### 13.19 下单类工具和库存并发怎么设计幂等？

回答：

虽然 DataSmart 不是电商系统，但“下单类工具”的本质和创建同步任务、提交质量修复任务、禁用账号一样，都是有副作用的写操作。

我会这样设计：

- 客户端或服务端生成 idempotencyKey，语义是“同一用户同一业务动作只能成功一次”。
- commandId 由 tenant/project/run/tool/normalizedArguments/idempotencyKey 生成稳定 digest。
- outbox 表对 commandId 或业务幂等键建唯一约束。
- 业务表也要有唯一约束，例如订单号、任务外部请求号、账号 providerUserId。
- worker 重试时携带同一个 idempotencyKey，下游服务按幂等键返回已有结果。
- receipt 也用稳定 receiptId 去重，避免重复回写。

库存并发用条件更新或预占模型：

- 简单库存：`update stock set available = available - n where sku = ? and available >= n`，影响行数为 1 才成功。
- 高并发库存：先 reserve，再支付或确认，超时释放 reservation。
- 分布式业务：用 saga，库存预占、订单创建、支付确认分别有补偿动作。

对应到 DataSmart，可以把库存理解为 worker 容量、租户配额、outbox backlog 或任务并发额度。项目里的 `DEFERRED`、tool budget、outbox per-run/per-tenant backlog 就是在做容量保护。

### 13.20 工具异常怎么分类？

回答：

我会把工具异常分类成稳定机器码，不能都变成 500：

- VALIDATION_ERROR：参数或 schema 错误。
- PERMISSION_DENIED：权限、租户、数据范围不允许。
- APPROVAL_REQUIRED：缺审批事实。
- APPROVAL_REJECTED：用户或策略拒绝。
- CONFLICT：状态冲突或幂等冲突。
- QUOTA_EXCEEDED：预算、并发、容量不足。
- RETRYABLE_DOWNSTREAM_ERROR：下游临时不可用。
- TIMEOUT：调用超时。
- SAFETY_PRECHECK_FAILED：安全预检失败。
- DEAD_LETTERED：超过自动重试上限。

不同异常对应不同动作：validation 走澄清，permission/approval fail-closed，retryable 退避重试，quota 进入 defer，dead letter 等待人工，safety 不能自动重试。

### 13.21 Agent 评测和普通大模型评测有什么区别？

回答：

普通大模型评测更关注答案文本质量，Agent 评测必须评估过程。因为 Agent 的错误不一定体现在最终回答里，可能是选错工具、漏审批、参数错、越权、重复执行、没有 fail-closed。

我会把 Agent 评测拆成：

- 意图识别：用户目标、风险等级、需要澄清的字段是否正确。
- 工具选择：应该选哪些工具、不应该选哪些工具。
- 参数生成：必填字段、默认值、数据范围、idempotencyKey 是否正确。
- 权限和审批：高风险动作是否进入 WAITING_APPROVAL。
- 工作流：节点顺序、中断恢复、重试、补偿是否符合预期。
- RAG：召回、证据门控、引用、拒答。
- Memory：是否召回正确记忆、是否忽略被删除或冲突记忆。
- 安全：是否泄露 prompt、SQL、token、artifact body。
- 成本和性能：token、延迟、工具调用次数、失败重试次数。

### 13.22 评测集怎么构造？

回答：

评测集不能只收“正常问题”。我会按业务场景和失败模式构造：

- 正常链路：创建任务、查询状态、RAG 问答、数据源诊断。
- 权限边界：普通用户尝试高风险写操作、跨租户访问、跨 workspace 记忆召回。
- 参数缺失：缺数据源、缺目标表、缺审批原因。
- 错误工具诱导：用户让系统跳过审批、伪造“我已经批准”。
- RAG 无证据：知识库没有答案时必须拒答。
- 记忆冲突：旧偏好和新偏好冲突，被 reject 的记忆不能复活。
- 长上下文：压缩后仍要保留审批、失败原因和 next action。
- 工具异常：timeout、429、下游 503、幂等冲突、dead letter。

每条样本要有 expected intent、expected tool、expected arguments schema、expected safety decision、expected final answer type 和禁止项。禁止项很重要，例如“不允许调用写工具”“不允许回答没有证据的问题”。

### 13.23 工具调用准确率怎么评估？

回答：

我不会只算“工具名是否相同”。工具调用准确率至少分四层：

- tool selection accuracy：选对工具。
- argument completeness：必填参数齐全。
- argument correctness：参数值来自可信上下文或用户输入，不能编造。
- execution decision correctness：该自动执行、澄清、审批、拒绝还是 fail-closed。

对于写工具，execution decision 比 tool selection 更重要。选对了工具但绕过审批，在生产上是严重错误。对于 RAG 工具，选对了 `knowledge.rag.query` 但无证据仍然回答，也是严重错误。

### 13.24 LLM 做裁判怎么保证稳定？

回答：

LLM-as-judge 可以用，但不能直接作为唯一上线门禁。

我会这样提高稳定性：

- 使用固定 rubric，拆成事实性、完整性、引用、拒答、安全等维度。
- 低 temperature，多次采样或多 judge 投票。
- 对关键安全项使用规则校验，比如是否出现 token、SQL、sourceUri、是否调用高风险工具。
- 用人工标注集校准 judge，一旦 judge 和人工一致率下降要回滚。
- 对同一批样本做版本间 pairwise 比较，而不是只看绝对分。
- judge 输入也要低敏化，不能把客户真实数据随便发给外部模型。

上线门禁里，安全、权限、幂等、泄露扫描应该用确定性规则；文本质量和 groundedness 可以让 LLM judge 辅助。

### 13.25 失败样本怎么回流？

回答：

失败样本回流要先脱敏和归因，不能直接把线上 prompt 和工具结果塞进训练集。

流程是：

1. 线上记录低敏 trace：intent、tool、reasonCode、errorCode、decision、latency、token、artifactReference。
2. 用户反馈或告警触发 case 进入复盘队列。
3. 人工标注根因：检索失败、工具选择错、参数缺失、权限策略错、模型幻觉、记忆污染、下游故障。
4. 转成离线 eval 样本或单元测试。
5. 修 prompt、工具 schema、检索策略、预算策略或代码。
6. CI 跑回归，灰度观察线上指标。

关键是失败样本要变成可重复验证的 regression case，而不是只靠“改了 prompt 感觉好了”。

### 13.26 Agent 评测怎么接 CI 和灰度发布？

回答：

CI 里我会分层跑：

- 单元测试：schema、状态机、幂等、压缩器、记忆过滤、RAG 证据门控。
- 合同测试：Gateway HMAC、permission-admin budget/admission、Java receipt、artifact grant/final-check。
- 小型 golden eval：几十到几百条高价值样本，要求关键安全项 100% 通过。
- 泄露扫描：事件、checkpoint、日志、响应里不能出现敏感字段。

灰度发布时：

- shadow mode 先只记录新策略会怎么决策，不真实执行。
- canary 小流量启用新模型、新工具规划或新 RAG 策略。
- 监控 tool selection、approvalRequired、fail-closed、no evidence、dead letter、latency、cost。
- 设自动回滚阈值，例如高风险自动执行比例异常、RAG 幻觉率升高、工具失败率升高。

### 13.27 企业级 RAG 的知识库治理怎么做？

回答：

企业 RAG 不是把文档丢进向量库。知识库首先是治理问题。

我会治理这些字段：

- 文档 owner、来源系统、sourceType、版本、生效时间、过期时间。
- tenant/project/workspace ACL。
- 文档解析状态、chunk 状态、embedding 版本、reranker 版本。
- 敏感等级、PII/DLP 标签、是否允许进入模型上下文。
- 删除、更新、重建索引和回滚记录。

DataSmart 当前 RAG 已有轻量 knowledge base、chunk、lexical/vector/RRF/MMR、heuristic rerank、evidence gate、citation 和 artifact，但真实生产还要补 PostgreSQL/pgvector Knowledge Store、MinIO 文档解析、增量索引、专用 reranker、ACL/DLP 和 GraphRAG。

### 13.28 多类型文档怎么切片？

回答：

切片策略要按文档类型定制：

- 普通制度文档：按标题层级、段落和语义边界切，保留 heading path。
- 表格：按表头、行组、关键字段切，避免把表头和数据分开。
- FAQ：问题和答案尽量保持在同一 chunk。
- API 文档：endpoint、参数、错误码、示例要结构化保存。
- 日志/runbook：按故障现象、原因、处理步骤切。
- 代码或 SQL：按函数、类、DDL 对象切，保留依赖关系。

项目里的 `chunk_document` 采用段落优先、长段落滑窗、overlap 的方式，适合本地 smoke 和学习。生产增强时，应增加 layout parser、table parser、文档结构元数据和 chunk 版本。

### 13.29 口语化 Query 怎么重写？

回答：

Query rewrite 的目标是把用户口语映射到企业术语，但不能改写用户意图。

我会分三步：

1. 识别业务对象和约束，例如“同步任务卡住了”映射到 data-sync execution、lease、worker backlog。
2. 补充同义词和术语，例如“账号”“用户”“身份供应”“Keycloak 影子身份”。
3. 生成多个检索 query，但保留 originalQueryRef，后续回答仍以用户原问题为准。

rewrite 不能跨 tenant/workspace 扩大检索范围，也不能把“我想看看某表数据”改写成“导出某表数据”。如果 rewrite 置信度低，应同时使用原始 query 和 rewrite query 做 hybrid retrieval。

### 13.30 召回冲突怎么处理？

回答：

召回冲突很常见，比如旧 runbook 和新 runbook 说法不同，或者不同项目的数据标准不同。

我会按权威性排序：

- 当前租户和项目优先于全局默认。
- 新版本优先于旧版本。
- 已发布/已审批文档优先于草稿。
- owner 权威来源优先于普通会话记忆。
- 明确生效时间内的文档优先于过期文档。

如果冲突仍然存在，答案要显式说“当前检索到冲突口径”，列出引用，而不是强行合成一个看似确定的结论。在高风险数据治理场景里，拒答或提示人工确认比编一个统一答案安全。

### 13.31 RAG 幻觉怎么优化？

回答：

我会用检索、证据、生成、后检四层控制：

- 检索层：hybrid retrieval、metadata filter、ACL filter、rerank、MMR 去冗余。
- 证据层：minimum lexical score、minimum vector score、minimum match terms、citation required。
- 生成层：prompt 要求只基于证据回答，引用编号必须存在。
- 后检层：检查回答是否引用有效 citation，是否出现证据外实体，是否在无证据时拒答。

项目的 RAG pipeline 已经有 evidence gate：没有足够证据时进入 `rag_no_evidence_completed` 或 evidence-only fallback，不让模型裸答。RAG command worker receipt 也只记录 candidateCount、selectedChunkCount、citationCount 和 artifactReference，不记录 question、answer、compressedContext 或 sourceUri。

### 13.32 企业 RAG 线上效果怎么评估？

回答：

线上不能只看点赞率。我会看：

- 检索 no evidence rate。
- evidence accepted count 分布。
- citation click / citation useful feedback。
- grounded answer pass rate。
- 用户追问“来源是什么”“不对”的比例。
- RAG latency、rerank latency、embedding latency、cache hit。
- 按租户、知识库、文档类型拆分的失败率。
- 敏感泄露扫描和越权召回率。

离线则用黄金问答集评 Recall@K、MRR、nDCG、citation precision、answer groundedness、fail-closed rate。上线时用灰度策略比较新旧 retrieval/rerank/prompt，不能一次全量替换。

### 13.33 最终交付闭环门禁怎么设计？

回答：

我不会只说“单测通过就能交付”。企业 Agent 项目交付要把生产就绪、容器化、供应链、备份恢复、容量基线、故障演练、最终平台闭环和只读 E2E smoke 串成一条可重复门禁。

项目新增了 `scripts/final-delivery-closure-check.ps1` 作为总闸门，默认串联这些子检查：

- production readiness
- Helm delivery
- SBOM readiness
- image signature readiness
- backup/restore readiness
- capacity baseline readiness
- failure drill readiness
- final platform closure audit
- 可选 local readonly E2E smoke

设计原则是默认静态和只读，不触发 worker、不创建任务、不读取业务数据、不写目标端、不打印 token、secret、prompt 或模型输出。`-RunLiveSmoke` 才跑真实本地只读 smoke，`-RunFullTests` 才复跑 Python/Maven 全量测试，`-Strict` 会把 warning 当作阻断项，`-WriteEvidence` 只把低敏 JSON 证据写到 `target/`。

如果面试官问“这和上线有什么区别”，我会说：这是交付候选门禁，不等于客户生产上线。真正上线还要客户环境里的 TLS/mTLS、Secret Manager、企业 IdP、镜像仓库签名、K8s/Helm 部署、压测、备份恢复演练、故障注入和值班告警流程。

### 13.34 Zookeeper/Kafka/Python Runtime 依赖漂移怎么恢复？

回答：

本地长时间运行后，常见问题是 Zookeeper 退出、Kafka bootstrap 失败，导致 Python Runtime 因连不上 Kafka unhealthy。这个时候不能上来就删 volume 或重建数据库，因为 Keycloak、PostgreSQL、Kafka、MinIO 里都有本地状态事实。

项目新增了 `scripts/local-dependency-recovery-drill.ps1`，默认只做诊断：

- Docker CLI 是否可用。
- Docker daemon 是否可达。
- Compose config 是否有效。
- `datasmart-zookeeper`、`datasmart-kafka`、`datasmart-python-ai-runtime` 容器状态。

只有显式传 `-RecoverKafkaChain` 才执行 `docker compose up -d zookeeper kafka`，只有显式传 `-RestartPythonRuntime` 才尝试重启 Python Runtime。脚本明确不删除 volume、不重置数据库、不重建 Keycloak realm、不清 Kafka topic、不触发 worker loop。

恢复后应该继续跑只读 smoke，例如 `final-delivery-closure-check.ps1 -RunLiveSmoke` 或 `local-e2e-smoke-check.ps1`，验证 Keycloak、gateway、Java 控制面、Python Runtime 诊断和低基数指标恢复，而不是只看容器 running。

## 14. 面试收尾总结

如果面试官让你总结项目亮点，可以这样说：

这个项目最核心的亮点是把 AI Agent 工程化放在企业后端语境里处理，而不是只做一个能调用工具的 Demo。具体来说，我做了四件事：

第一，把 Java 业务事实控制面和 Python AI Runtime 解耦，模型负责规划，业务事实、审批和执行权回到 Java。

第二，把 Agent 工具调用治理成服务端能力，包括工具预算、Skill 准入、审批事实、低敏 ToolPlan、outbox 和 worker receipt。

第三，把异步可靠性做完整，包括状态机、租约、heartbeat、幂等、defer、dead letter、过期恢复和人工介入。

第四，把生产问题提前纳入设计，包括 Gateway HMAC、低敏 runtime event、LangGraph checkpoint、RAG fail-closed、RAG answer artifact grant/final-check、Keycloak/企业 IdP、Prometheus/Grafana、Helm、SBOM、备份恢复、容量基线和故障演练。

最后我会明确边界：当前是工程发布候选，有测试、MinIO smoke、Keycloak smoke 和门禁证据，但不是已经完成客户生产 SLA。这个表述更真实，也更符合 AI Agent 开发岗位会关注的工程成熟度。
