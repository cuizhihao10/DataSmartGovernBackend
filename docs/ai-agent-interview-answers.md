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

## 13. 面试收尾总结

如果面试官让你总结项目亮点，可以这样说：

这个项目最核心的亮点是把 AI Agent 工程化放在企业后端语境里处理，而不是只做一个能调用工具的 Demo。具体来说，我做了四件事：

第一，把 Java 业务事实控制面和 Python AI Runtime 解耦，模型负责规划，业务事实、审批和执行权回到 Java。

第二，把 Agent 工具调用治理成服务端能力，包括工具预算、Skill 准入、审批事实、低敏 ToolPlan、outbox 和 worker receipt。

第三，把异步可靠性做完整，包括状态机、租约、heartbeat、幂等、defer、dead letter、过期恢复和人工介入。

第四，把生产问题提前纳入设计，包括 Gateway HMAC、低敏 runtime event、LangGraph checkpoint、RAG fail-closed、RAG answer artifact grant/final-check、Keycloak/企业 IdP、Prometheus/Grafana、Helm、SBOM、备份恢复、容量基线和故障演练。

最后我会明确边界：当前是工程发布候选，有测试、MinIO smoke、Keycloak smoke 和门禁证据，但不是已经完成客户生产 SLA。这个表述更真实，也更符合 AI Agent 开发岗位会关注的工程成熟度。
