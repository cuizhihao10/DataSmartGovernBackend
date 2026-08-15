/**
 * DataSmart Govern 企业级 RAG 合成语料内容库。
 *
 * 该模块只保存可公开提交的原创合成知识，不读取网络、环境变量、数据库或真实客户文件。内容按
 * “主题定义 -> DOCX 深度章节 -> XLSX 案例数据 -> 结构化记录”分层组织，生成器可以稳定复用，
 * 评测也能依靠固定错误码、任务编号、接口路径和证据字段做确定性回归。
 */

const SYNTHETIC_NOTICE = "原创合成评测资料，不含真实客户、个人、凭据或生产数据";

/** 用统一字段构造 DOCX 主题，减少新增手册时遗漏来源类型或检索问题。 */
function docxTopic(slug, title, code, category, sourceType, tags, summary, question, sections) {
  return { slug, title, code, category, sourceType, tags, summary, question, sections };
}

/** 用统一字段构造 XLSX 主题，所有工作簿都能进入同一 Manifest 与黄金集。 */
function xlsxTopic(slug, title, code, category, sourceType, tags, summary, question) {
  return { slug, title, code, category, sourceType, tags, summary, question };
}

/** 用统一字段构造 TXT/JSON/JSONL/CSV/LOG/SQL 主题。 */
function structuredTopic(slug, title, code, format, category, sourceType, tags, summary, question) {
  return { slug, title, code, format, category, sourceType, tags, summary, question };
}

export const extraDocxTopics = [
  docxTopic(
    "reference-authentication-api",
    "认证、会话与服务身份接口说明",
    "DOC-API-AUT-841",
    "api_authentication_reference",
    "document",
    ["接口说明", "认证", "服务身份", "RBAC"],
    "定义登录会话、服务身份、令牌刷新、权限查询和可信控制面身份传播合同。",
    "认证与服务身份接口如何传递用户、项目和可信审批主体？",
    [
      ["用户会话", "登录、刷新、退出和会话撤销必须返回稳定会话标识，响应不回显凭据。"],
      ["服务身份", "Java 与 Python Runtime 之间使用受控服务身份，不能冒充最终用户。"],
      ["权限快照", "执行前按 actor、tenant、project、application、resource 和 action 查询权限事实。"],
      ["审批主体", "请求主体和批准主体必须分别记录，审批事实包含用途、范围、有效期和摘要指纹。"],
      ["错误合同", "认证失败、令牌过期、范围不足和审批缺失使用稳定 reasonCode。"],
      ["审计要求", "所有身份切换、权限查询和拒绝结果写入低敏审计事件。"],
    ],
  ),
  docxTopic(
    "reference-agent-api",
    "Agent 规划、工具与证据接口说明",
    "DOC-API-AGT-852",
    "api_agent_reference",
    "document",
    ["接口说明", "Agent", "ToolPlan", "证据"],
    "定义自然语言目标、六 Specialist、ToolPlan、RAG 决策、审批预览和持久化事实接口。",
    "Agent 规划接口如何关联 ToolPlan、RAG 证据和六 Specialist 执行事实？",
    [
      ["目标提交", "请求携带目标、范围、操作者、幂等键和可用工具，不携带数据库凭据。"],
      ["规划输出", "返回 planId、objective、stateTrace、toolPlans、governance 和 evidenceRecords。"],
      ["工具执行", "每个 ToolPlan 声明工具代码、参数、风险、审批需求、超时和预期回执。"],
      ["RAG 决策", "模型可以选择 SEARCH 或 SKIP，并记录决策理由、检索策略和证据数量。"],
      ["状态追踪", "六 Specialist turn、LangGraph checkpoint 和 Java durable fact 使用稳定 ID 关联。"],
      ["失败处理", "规划冲突、工具越权、证据不足和 Provider 故障必须显式失败或降级。"],
    ],
  ),
  docxTopic(
    "reference-task-api",
    "任务管理与调度接口说明",
    "DOC-API-TSK-863",
    "api_task_reference",
    "document",
    ["接口说明", "任务", "调度", "幂等"],
    "定义任务创建、版本、启停、调度、执行历史、导入导出、审批和状态查询接口。",
    "任务接口如何创建版本、触发执行并查询历史和调度状态？",
    [
      ["任务草稿", "创建草稿只保存配置，不产生执行副作用。"],
      ["版本发布", "发布生成不可变配置版本和摘要指纹，后续恢复可以对比上次成功版本。"],
      ["调度管理", "cron、时区、错过触发策略、并发策略和暂停原因必须可审计。"],
      ["运行控制", "启动、暂停、恢复、取消、重跑和失败对象 replay 使用独立命令接口。"],
      ["历史查询", "按任务、执行、对象、状态、错误码和时间窗口查询。"],
      ["导入导出", "Excel 导入先校验列、范围和权限，导出遵循脱敏与审计政策。"],
    ],
  ),
  docxTopic(
    "reference-data-sync-api",
    "数据同步执行与连接器接口说明",
    "DOC-API-SYN-874",
    "api_data_sync_reference",
    "document",
    ["接口说明", "数据同步", "连接器", "对象台账"],
    "定义预检、执行、分片、checkpoint、对象台账、连接器能力和执行回执合同。",
    "数据同步接口如何从预检进入分片执行并返回对象台账和 checkpoint？",
    [
      ["预检", "验证连接、元数据、字段映射、目标约束、容量和权限。"],
      ["执行命令", "命令引用已发布 taskVersion，携带 executionId 和幂等键。"],
      ["分片台账", "每个 objectId 记录 workUnitType、attempt、checkpoint、读写量和错误。"],
      ["连接器能力", "版本、批量、并发、超时、限流和支持模式通过只读接口查询。"],
      ["执行回执", "回执必须匹配原 taskId/executionId，状态限定为可识别枚举。"],
      ["取消与收敛", "取消需要终止新分片并等待在途分片进入可解释终态。"],
    ],
  ),
  docxTopic(
    "reference-recovery-api",
    "自治恢复、修复动作与人工接管接口说明",
    "DOC-API-RCV-885",
    "api_recovery_reference",
    "document",
    ["接口说明", "Recovery", "修复动作", "人工接管"],
    "定义诊断、证据检索、修复预览、受治理动作、循环、退出和后置验证接口。",
    "Recovery 接口如何提交修复预览、执行低风险动作并在越权时退出？",
    [
      ["恢复触发", "失败事件经 Kafka 进入 Recovery，携带执行、对象、错误和授权盒摘要。"],
      ["诊断证据", "结构化日志、配置差异、连接器能力、Runbook 和事故资料统一记录来源与可信度。"],
      ["修复预览", "执行前返回 actionCode、参数差异、风险、回滚、验证和审批要求。"],
      ["自治执行", "只有授权盒内、可回滚、证据唯一的低风险动作可以自动执行。"],
      ["循环控制", "每轮使用新的 cycle 身份，达到上限或没有新证据时停止。"],
      ["人工接管", "退出时返回根因、证据、权限、步骤、影响、回滚和验证方法。"],
    ],
  ),
  docxTopic(
    "reference-websocket-events",
    "实时状态与 WebSocket 事件字典",
    "DOC-API-WS-896",
    "websocket_event_reference",
    "document",
    ["WebSocket", "事件字典", "状态图", "前后端合同"],
    "定义 Agent、Kafka、Java 审计、Worker、Recovery 和最终验证的实时事件字段与顺序。",
    "WebSocket 如何展示从用户目标到 Recovery 和最终验证的全链路状态？",
    [
      ["订阅", "客户端按 projectId、taskId 或 executionId 建立授权订阅。"],
      ["事件信封", "schemaVersion、eventId、traceId、occurredAt、sequence 和 sourceService 为公共字段。"],
      ["Agent 事件", "展示 Specialist、node、turnId、state、retrievalDecision 和证据摘要。"],
      ["执行事件", "展示 Kafka 投递、Java 审计、对象台账、进度、checkpoint 和错误码。"],
      ["恢复事件", "展示 cycle、actionCode、risk、governanceDecision、rerun 和 verification。"],
      ["重连补偿", "客户端使用 lastEventId 恢复，服务端按保留窗口返回缺失事件或快照。"],
    ],
  ),
  docxTopic(
    "manual-observability",
    "日志、指标、追踪与告警运维手册",
    "DOC-OPS-OBS-907",
    "observability_operations_manual",
    "runbook",
    ["运维手册", "日志", "指标", "追踪", "告警"],
    "给出统一 trace、结构化日志、核心指标、告警分级、看板和证据导出的操作规范。",
    "如何使用日志、指标和 trace 定位一次跨 Agent 与 Worker 的同步失败？",
    [
      ["统一标识", "traceId、taskId、executionId、objectId、eventId 和 recoveryCaseId 贯穿所有服务。"],
      ["日志检索", "先按错误码和执行标识缩小范围，再读取相邻状态事件。"],
      ["指标关联", "将吞吐、延迟、积压、错误率、脏数据和 Provider 延迟与日志时间对齐。"],
      ["分布式追踪", "检查 Gateway、Java、Kafka、Python、Worker 和数据库 span 的父子关系。"],
      ["告警响应", "P1/P2/P3 使用不同响应时限、通知对象和自动化边界。"],
      ["证据导出", "导出只包含低敏摘要和来源引用，原始正文继续受权限控制。"],
    ],
  ),
  docxTopic(
    "manual-kafka-operations",
    "Kafka Topic、消费者组与 DLT 运维手册",
    "DOC-OPS-KFK-918",
    "kafka_operations_manual",
    "runbook",
    ["运维手册", "Kafka", "消费者组", "DLT"],
    "覆盖 Topic、分区、消费者组、lag、outbox、retry、DLT、顺序、幂等和容量处置。",
    "Kafka 积压、重复消费和 DLT 增长时应如何排查与恢复？",
    [
      ["Topic 清单", "核对命令、事件、回执、重试和 DLT Topic 的分区与保留策略。"],
      ["消费者组", "按 group、partition、currentOffset、logEndOffset 和 lag 建立基线。"],
      ["重复消费", "使用 eventId、idempotencyKey 和业务指纹区分安全重放与冲突。"],
      ["重试与 DLT", "只对可重试异常进入有限退避，永久错误直接生成可审计 DLT 记录。"],
      ["扩缩容", "先确认分区与下游容量，再调整消费者实例，不以扩容掩盖坏消息。"],
      ["恢复验证", "恢复后确认 lag 回落、无重复副作用、outbox delivered 和最终状态一致。"],
    ],
  ),
  docxTopic(
    "manual-postgresql-pgvector",
    "PostgreSQL、pgvector 与 AI Memory 运维手册",
    "DOC-OPS-PGV-929",
    "postgresql_pgvector_manual",
    "runbook",
    ["运维手册", "PostgreSQL", "pgvector", "AI Memory"],
    "覆盖 schema、角色、连接池、索引、向量维度、备份、慢查询、膨胀和范围过滤检查。",
    "pgvector 检索变慢或向量维度不一致时怎样诊断？",
    [
      ["Schema 边界", "业务控制面、任务、权限和 AI Memory 使用独立 schema 与受限角色。"],
      ["连接池", "检查活动连接、等待事件、事务时长和连接泄漏。"],
      ["向量索引", "核对 embedding_model、dimension、HNSW 条件和查询运算符。"],
      ["全文检索", "向量不可用时只允许显式词法路径，不伪装为语义检索。"],
      ["维护", "监控 autovacuum、索引膨胀、WAL、磁盘和备份恢复点。"],
      ["隔离验证", "EXPLAIN 与样本查询必须先出现 tenant/project/workspace 范围谓词。"],
    ],
  ),
  docxTopic(
    "manual-model-provider",
    "模型、Embedding 与 Reranker Provider 运维手册",
    "DOC-OPS-LLM-940",
    "model_provider_manual",
    "runbook",
    ["运维手册", "模型Provider", "Embedding", "Reranker"],
    "覆盖 Provider 健康、模型路由、超时、429、5xx、降级、费用、密钥轮换和响应校验。",
    "模型 Provider degraded、429 或响应缺项时怎样安全降级？",
    [
      ["健康探测", "使用不含业务正文的低敏 smoke 验证模型、Embedding 和 Reranker。"],
      ["路由", "规划模型、Embedding 和 Reranker 独立配置，不能混用 Endpoint 或凭据。"],
      ["限流", "429 使用有界退避和预算，不无限重试，不扩大并发。"],
      ["响应校验", "向量维度、重排缺项、非有限分数和模型名漂移均 fail-closed。"],
      ["降级", "语义不可用时明确切换词法或无证据模式，不生成伪向量。"],
      ["费用与审计", "记录模型、token/条目数量、延迟和状态，不记录密钥或完整正文。"],
    ],
  ),
  docxTopic(
    "manual-backup-disaster-recovery",
    "备份、恢复与灾难演练手册",
    "DOC-OPS-DR-951",
    "backup_disaster_recovery_manual",
    "runbook",
    ["运维手册", "备份", "恢复", "灾难演练"],
    "定义 PostgreSQL、对象存储、配置、Kafka 位点和审计数据的 RPO/RTO、恢复顺序与演练证据。",
    "平台灾难恢复时应按什么顺序恢复数据库、对象、Kafka 位点和服务？",
    [
      ["资产分级", "区分控制面事实、任务配置、checkpoint、知识文档、向量和可再生缓存。"],
      ["备份策略", "全量、增量、WAL、对象版本和配置快照使用不同保留周期。"],
      ["恢复顺序", "先身份与数据库，再 Kafka/对象存储，最后 Java、Python 和 Worker。"],
      ["一致性", "恢复点必须使任务、对象台账、outbox 和 checkpoint 相互一致。"],
      ["演练", "每季度执行隔离环境恢复，记录实际 RPO/RTO 和失败步骤。"],
      ["验收", "使用合成任务、RAG 范围查询和审计链完整性验证。"],
    ],
  ),
  docxTopic(
    "manual-upgrade-rollback",
    "版本升级、数据库迁移与回滚手册",
    "DOC-OPS-UPG-962",
    "upgrade_rollback_manual",
    "runbook",
    ["运维手册", "升级", "迁移", "回滚"],
    "覆盖兼容矩阵、灰度、数据库迁移、事件版本、模型变更、回滚点和发布后验证。",
    "升级 Java、Python Runtime 或数据库 schema 时如何建立可回滚发布？",
    [
      ["兼容矩阵", "确认 JDK、Spring Boot、Kafka、PostgreSQL、Python 和前端合同兼容。"],
      ["迁移预检", "检查向前/向后兼容、锁表风险、磁盘空间和回滚 SQL。"],
      ["灰度发布", "先只读流量和合成任务，再逐步扩大租户范围。"],
      ["事件版本", "生产者先兼容旧消费者，消费者再升级，最后清理旧字段。"],
      ["模型变更", "Embedding 模型升级使用新版本索引并可双读比较。"],
      ["回滚", "应用、配置、schema 和索引分别定义触发条件与验证。"],
    ],
  ),
  docxTopic(
    "postmortem-schema-drift",
    "事故复盘：来源 Schema 漂移导致字段映射失败",
    "DOC-INC-SCH-973",
    "incident_schema_drift",
    "incident",
    ["事故复盘", "Schema漂移", "字段映射"],
    "复盘新增字段、字段重命名和类型变化未及时刷新元数据导致的同步中断。",
    "Schema 漂移事故的根因、自动修复边界和长期改进是什么？",
    [
      ["影响", "多个增量分片在映射阶段失败，已写入分片保持一致。"],
      ["发现", "预检缓存未过期，但来源 schemaVersion 已变化。"],
      ["根因", "连接器元数据事件延迟与任务发布竞态导致旧映射被采用。"],
      ["处置", "刷新元数据并在唯一映射可证明时更新任务版本。"],
      ["恢复", "从失败 checkpoint replay，不覆盖成功对象。"],
      ["改进", "发布前比较 schema 指纹并增加漂移演练。"],
    ],
  ),
  docxTopic(
    "postmortem-foreign-key",
    "事故复盘：父子表顺序错误导致外键失败",
    "DOC-INC-FK-984",
    "incident_foreign_key",
    "incident",
    ["事故复盘", "外键", "依赖顺序"],
    "复盘父表分片未完成时子表提前写入造成的外键约束失败。",
    "外键事故为什么允许调整依赖顺序但不允许自动删除外键？",
    [
      ["影响", "子对象写入失败，父对象无数据损坏。"],
      ["发现", "目标数据库返回 FOREIGN_KEY_MISSING 并附约束名。"],
      ["根因", "对象 DAG 在一次配置合并后丢失父子边。"],
      ["处置", "恢复依赖边，先 replay 父对象，再 replay 子对象。"],
      ["边界", "不得自动删除或禁用外键，不得伪造父记录。"],
      ["改进", "预检增加目标约束与对象 DAG 一致性校验。"],
    ],
  ),
  docxTopic(
    "postmortem-rate-limit",
    "事故复盘：目标端限流与连接池耗尽",
    "DOC-INC-RAT-995",
    "incident_rate_limit",
    "incident",
    ["事故复盘", "限流", "连接池", "退避"],
    "复盘并发变更触发目标端 429/连接池等待，导致批量写入超时。",
    "目标端限流事故中为何只能降低并发并有界增加超时？",
    [
      ["影响", "写入吞吐下降，部分分片超时但 checkpoint 可恢复。"],
      ["发现", "429、连接池等待和目标 CPU 同时升高。"],
      ["根因", "新配置把 channel 提高到连接器建议上限之外。"],
      ["处置", "回滚上次成功配置或降低并发与批量。"],
      ["边界", "自动恢复不得扩大并发或绕过目标限流。"],
      ["改进", "发布前读取实时容量与连接器版本能力。"],
    ],
  ),
  docxTopic(
    "postmortem-checkpoint",
    "事故复盘：Checkpoint 漂移与重复读取",
    "DOC-INC-CHK-1006",
    "incident_checkpoint",
    "incident",
    ["事故复盘", "Checkpoint", "幂等", "重复读取"],
    "复盘 checkpoint 写入确认丢失导致的安全重放与重复读取风险。",
    "Checkpoint 漂移事故如何判断可以 replay 而不会产生重复副作用？",
    [
      ["影响", "来源重复读取一个窗口，目标幂等键阻止重复写入。"],
      ["发现", "worker 成功日志与 checkpoint 表时间不一致。"],
      ["根因", "网络中断发生在目标提交之后、checkpoint 确认之前。"],
      ["处置", "比较对象指纹、目标幂等回执和最近安全位点。"],
      ["恢复", "从确认位点 replay，并验证写入去重计数。"],
      ["改进", "把数据提交与 checkpoint 回执纳入统一审计。"],
    ],
  ),
  docxTopic(
    "postmortem-kafka-backlog",
    "事故复盘：Kafka 积压与 Recovery DLT 增长",
    "DOC-INC-KFK-1017",
    "incident_kafka_backlog",
    "incident",
    ["事故复盘", "Kafka积压", "DLT", "Recovery"],
    "复盘坏消息、Provider 延迟和消费者扩容不匹配共同造成的 Recovery 积压。",
    "Recovery Kafka 积压和 DLT 增长的根因及恢复步骤是什么？",
    [
      ["影响", "恢复事件延迟，正常数据同步仍运行但告警增加。"],
      ["发现", "consumer lag、处理时延和 DLT 速率同步升高。"],
      ["根因", "相同幂等键的冲突消息持续重试，Provider 延迟放大积压。"],
      ["处置", "隔离永久冲突消息，修复重放稳定性后再 replay DLT。"],
      ["验证", "lag 回落、DLT 无新增、恢复案例进入确定终态。"],
      ["改进", "按错误类型区分 retryable，并建立容量压测。"],
    ],
  ),
  docxTopic(
    "report-e2e-test",
    "六 Specialist 与自治恢复 E2E 测试报告",
    "DOC-TST-E2E-1028",
    "e2e_test_report",
    "document",
    ["测试报告", "六Specialist", "E2E", "Recovery"],
    "记录从目标提交、RAG 决策、审批、Kafka、Worker、Recovery 到最终验证的黑盒用例。",
    "六 Specialist 自治恢复 E2E 需要证明哪些成功和失败条件？",
    [
      ["正常路径", "六 Specialist 依次产生 durable turn，任务执行成功。"],
      ["恢复路径", "先注入可恢复故障，再证明修复、重跑、PRECHECK 和 MONITOR。"],
      ["治理路径", "首次授权后低风险动作无人值守，高风险动作退出。"],
      ["异步路径", "outbox、Kafka、消费者回执、DLT 和幂等均有证据。"],
      ["范围路径", "跨租户文档、任务和审批事实不可见。"],
      ["前端合同", "WebSocket 与 REST 状态能够还原统一链路。"],
    ],
  ),
  docxTopic(
    "report-performance-test",
    "Agent、RAG 与数据同步性能测试报告",
    "DOC-TST-PERF-1039",
    "performance_test_report",
    "document",
    ["测试报告", "性能", "吞吐", "延迟"],
    "定义规划、检索、重排、Kafka、数据库、同步吞吐和恢复时延的冷/热测试方法。",
    "性能报告应怎样区分 Agent、RAG、Kafka 和 Worker 的延迟与容量？",
    [
      ["工作负载", "按文档规模、并发查询、任务数量、分片数和行数分层。"],
      ["Agent 指标", "记录规划端到端、模型调用、工具等待和图节点耗时。"],
      ["RAG 指标", "记录摄取、Embedding、召回、Reranker、引用和拒答延迟。"],
      ["Kafka 指标", "记录生产延迟、lag、重试、DLT 和吞吐。"],
      ["同步指标", "记录 rows/s、batch、channel、checkpoint 和目标提交耗时。"],
      ["门禁", "同时满足延迟、错误率、范围泄漏和资源使用目标。"],
    ],
  ),
  docxTopic(
    "report-rag-agent-evaluation",
    "RAG 与 Agent 决策质量评测报告",
    "DOC-TST-RAG-1050",
    "rag_agent_evaluation_report",
    "document",
    ["测试报告", "RAG评测", "Agent决策", "引用"],
    "覆盖召回、排序、引用、拒答、范围、证据可信度、工具选择和恢复决策质量。",
    "RAG 与 Agent 评测为什么不能只看 Recall，还要看引用、拒答和治理？",
    [
      ["检索质量", "Recall、MRR、nDCG 与多文档覆盖分别评估召回和排序。"],
      ["引用质量", "引用精确率、召回率、来源、时间和可信度必须同时检查。"],
      ["拒答质量", "无证据、跨范围和证据冲突场景必须拒答。"],
      ["决策质量", "评估 SEARCH/SKIP、工具选择、修复动作和退出 Loop 的合理性。"],
      ["鲁棒性", "验证 Provider 429、超时、缺项和模型漂移。"],
      ["发布门禁", "任何范围泄漏、未授权动作或高风险误执行直接阻断发布。"],
    ],
  ),
];

export const extraXlsxTopics = [
  xlsxTopic("workbook-full-load-task-cases", "全量同步任务案例库", "XLSX-FULL-601", "full_load_task_cases", "task_case", ["Excel", "全量同步", "任务案例"], "收录关系库、文件和对象存储的全量初始化、切片、校验与切换案例。", "全量同步在大表初始化和目标切换时应采用哪些参数与验证？"),
  xlsxTopic("workbook-incremental-task-cases", "增量同步任务案例库", "XLSX-INC-612", "incremental_task_cases", "task_case", ["Excel", "增量同步", "游标"], "收录时间戳、递增主键、复合游标和迟到数据窗口案例。", "增量同步怎样选择游标、回看窗口和 checkpoint？"),
  xlsxTopic("workbook-cdc-task-cases", "CDC 实时同步任务案例库", "XLSX-CDC-623", "cdc_task_cases", "task_case", ["Excel", "CDC", "实时同步"], "收录 WAL/binlog、Schema 变更、心跳、位点和 Exactly-once 近似案例。", "CDC 任务怎样配置位点、心跳和 Schema 变更策略？"),
  xlsxTopic("workbook-file-task-cases", "文件导入导出任务案例库", "XLSX-FILE-634", "file_task_cases", "task_case", ["Excel", "CSV", "Excel导入", "文件同步"], "收录 CSV、TSV、JSONL、Excel、压缩包、编码和坏行隔离案例。", "Excel 或 CSV 导入任务如何处理编码、表头、坏行和重复文件？"),
  xlsxTopic("workbook-api-task-cases", "API 到数据库同步任务案例库", "XLSX-API-645", "api_task_cases", "task_case", ["Excel", "API同步", "分页", "限流"], "收录分页、游标、OAuth 引用、429、重试、去重和响应漂移案例。", "API 同步遇到分页、429 和响应字段漂移时如何恢复？"),
  xlsxTopic("workbook-kafka-task-cases", "Kafka 流式同步任务案例库", "XLSX-KFK-656", "kafka_task_cases", "task_case", ["Excel", "Kafka", "流式同步"], "收录 Topic、分区、消费者组、offset、乱序、重复和 DLT 案例。", "Kafka 同步任务如何配置分区、offset、乱序与 DLT 策略？"),
  xlsxTopic("workbook-object-storage-task-cases", "对象存储同步任务案例库", "XLSX-OBJ-667", "object_storage_task_cases", "task_case", ["Excel", "对象存储", "Parquet"], "收录 MinIO/S3、前缀扫描、清单、Parquet 分区、覆盖保护和断点续传案例。", "对象存储任务怎样避免重复对象、覆盖数据和分区遗漏？"),
  xlsxTopic("workbook-schema-evolution-task-cases", "Schema 演进与字段兼容案例库", "XLSX-SCH-678", "schema_evolution_cases", "dataset", ["Excel", "Schema演进", "字段兼容"], "收录新增、重命名、类型扩大、精度变化、非空和外键变化的治理决策。", "Schema 演进中哪些字段变化可自动兼容，哪些必须退出 Loop？"),
  xlsxTopic("workbook-quality-task-cases", "数据质量与脏数据处置案例库", "XLSX-QLT-689", "data_quality_cases", "task_case", ["Excel", "数据质量", "脏数据"], "收录完整性、唯一性、范围、枚举、外键、时效和对账规则案例。", "同步任务如何按质量规则隔离脏数据并决定继续或停止？"),
  xlsxTopic("workbook-recovery-replay-task-cases", "自动修复与失败对象 Replay 案例库", "XLSX-RPL-700", "recovery_replay_cases", "incident", ["Excel", "自动修复", "Replay", "授权盒"], "收录配置回滚、默认值、映射、元数据、checkpoint、顺序和失败分片 replay 案例。", "授权盒内可自动执行哪些修复与失败对象 replay？"),
];

export const extraTextTopics = [
  structuredTopic("runbook-command-reference", "运维命令与查询参考", "TXT-CMD-711", "txt", "operations_command_reference", "runbook", ["TXT", "运维命令", "查询"], "汇总健康检查、日志、Kafka、PostgreSQL、容器和 RAG 的只读排查命令。", "运维人员应优先执行哪些只读命令定位同步异常？"),
  structuredTopic("error-code-catalog", "同步与 Agent 错误码目录", "TXT-ERR-722", "txt", "error_code_catalog", "runbook", ["TXT", "错误码", "根因"], "按错误码记录组件、重试资格、证据、自动动作和升级条件。", "哪些错误码可以自动修复，哪些必须人工接管？"),
  structuredTopic("api-contract-snapshot", "REST 与 WebSocket 合同快照", "JSON-API-733", "json", "api_contract_snapshot", "metadata", ["JSON", "API合同", "WebSocket"], "保存主要接口、事件类型、必填字段、版本和治理要求。", "合同快照中任务执行与 Recovery 使用哪些稳定字段？"),
  structuredTopic("task-config-versions", "任务配置版本与差异快照", "JSON-CFG-744", "json", "task_config_versions", "task_case", ["JSON", "配置版本", "差异"], "保存当前配置、上次成功配置和受治理差异。", "当前任务配置与上一次成功配置有哪些差异？"),
  structuredTopic("task-case-library", "多模式任务案例流水", "JSONL-TASK-755", "jsonl", "task_case_library", "task_case", ["JSONL", "任务案例", "多模式"], "逐行保存全量、增量、CDC、文件、API、Kafka 和对象存储任务案例。", "任务案例流水中有哪些同步模式和恢复策略？"),
  structuredTopic("audit-events", "权限、审批与修复审计事件", "JSONL-AUD-766", "jsonl", "audit_event_stream", "memory_export", ["JSONL", "审计", "审批"], "逐行保存请求主体、批准主体、动作、范围、指纹和结果。", "审计事件如何区分请求主体、批准主体和执行主体？"),
  structuredTopic("recovery-decision-trace", "Recovery 决策与循环轨迹", "JSONL-DEC-777", "jsonl", "recovery_decision_trace", "incident", ["JSONL", "Recovery", "决策轨迹"], "逐行保存每轮诊断、RAG 决策、候选动作、门禁、执行和验证。", "Recovery 每轮怎样证明有新证据并避免无限循环？"),
  structuredTopic("connector-inventory", "连接器版本与容量清单", "CSV-CON-788", "csv", "connector_inventory", "metadata", ["CSV", "连接器", "容量"], "列出连接器版本、模式、最大批量、并发、限流、超时和健康状态。", "连接器清单中哪些版本支持 CDC 和 checkpoint replay？"),
  structuredTopic("field-profile-statistics", "字段画像与质量统计", "CSV-PROF-799", "csv", "field_profile_statistics", "dataset", ["CSV", "字段画像", "质量"], "保存空值率、唯一率、最值、长度、枚举和异常数量。", "字段画像如何判断 region_code 的默认值和非空修复是否安全？"),
  structuredTopic("alert-history", "告警历史与响应记录", "CSV-ALR-810", "csv", "alert_history", "incident", ["CSV", "告警", "响应"], "保存告警级别、阈值、触发值、责任人角色、自动动作和恢复时间。", "哪些告警会触发 Recovery，哪些只通知运维人员？"),
  structuredTopic("kafka-consumer-lag", "Kafka 消费积压诊断日志", "LOG-KFK-821", "log", "kafka_lag_log", "incident", ["LOG", "Kafka", "Lag"], "记录消费者组、分区、offset、lag、处理时延、重试和 DLT 变化。", "Kafka 日志中哪个消费者组和分区出现最大积压？"),
  structuredTopic("database-recovery-ledger", "数据库恢复与证据台账快照", "SQL-RCV-832", "sql", "database_recovery_ledger", "dataset", ["SQL", "恢复台账", "证据"], "保存恢复案例、循环、证据、修复动作、对象 replay 和最终验证的合成持久化行。", "数据库台账如何关联恢复循环、证据、修复动作和最终验证？"),
];

const ERROR_CATALOG = [
  ["CONNECTION_TIMEOUT", "网络或端点超时", "连接耗时、DNS、目标健康", "有界增加超时或等待端点恢复；持续失败时退出"],
  ["AUTHENTICATION_FAILED", "凭据失效或引用错误", "认证响应与凭据引用版本", "禁止自动修改凭据，返回轮换指引"],
  ["PERMISSION_DENIED", "资源动作未授权", "actor、resource、action、审批事实", "立即退出 Loop，说明所需权限"],
  ["RATE_LIMIT_EXCEEDED", "来源或目标端限流", "429、限流头、连接器容量", "降低并发/批量并有界退避"],
  ["SCHEMA_DRIFT_DETECTED", "来源结构发生变化", "schema 指纹与元数据差异", "刷新元数据；唯一兼容映射才自动修复"],
  ["FIELD_MAPPING_MISSING", "目标字段缺少映射", "源目标字段、历史成功映射", "唯一映射可修复，歧义映射退出"],
  ["NOT_NULL_VIOLATION", "目标非空字段收到空值", "错误字段、字段画像、已批准默认值", "使用已批准静态默认值；不得放宽约束"],
  ["DATA_TYPE_MISMATCH", "源目标类型不兼容", "类型、精度、长度、样本统计", "无损转换可修复，有损转换退出"],
  ["NUMERIC_OVERFLOW", "数值超过目标精度", "最值、precision、scale", "禁止截断，返回扩容或映射建议"],
  ["STRING_TRUNCATION_RISK", "字符串超过目标长度", "最大长度与超限行数", "禁止静默截断，退出并给出影响"],
  ["FOREIGN_KEY_MISSING", "父记录尚未存在", "约束名、父子对象、执行 DAG", "调整依赖顺序并 replay 父子对象"],
  ["UNIQUE_CONSTRAINT_VIOLATION", "业务键或幂等键重复", "约束名、键摘要、历史回执", "确认幂等重放；非幂等冲突退出"],
  ["CHECKPOINT_NOT_FOUND", "没有可恢复位点", "对象台账与 checkpoint 表", "只在授权允许时从安全起点重跑"],
  ["CHECKPOINT_STALE", "位点落后或与提交不一致", "worker 回执、目标提交、位点时间", "选择最近已确认位点并验证去重"],
  ["KAFKA_BACKLOG_HIGH", "消费者处理速度低于生产速度", "group、partition、lag、处理时延", "隔离坏消息并在容量允许时扩容"],
  ["OUTBOX_DELIVERY_TIMEOUT", "事务事实未及时投递", "outbox 状态、attempt、broker 响应", "重投未交付 outbox，不重复业务事务"],
  ["CONNECTOR_VERSION_INCOMPATIBLE", "连接器版本不支持配置", "版本、能力快照、配置字段", "回滚兼容配置或人工升级连接器"],
  ["TARGET_CAPACITY_EXCEEDED", "目标 CPU/连接/磁盘达到阈值", "容量指标、等待事件、配额", "降低负载并等待恢复，不扩大压力"],
  ["DIRTY_RECORD_THRESHOLD_EXCEEDED", "脏数据超过任务阈值", "规则结果、坏行样本引用、阈值", "隔离坏行；超过停止阈值时退出"],
  ["DDL_REQUIRED", "需要修改目标结构", "元数据差异与目标约束", "高风险动作，返回 DDL、影响、回滚与验证指引"],
];

const API_CONTRACTS = [
  ["POST", "/api/auth/sessions", "创建用户会话并返回 sessionId", "限流、审计、不回显凭据", "auth"],
  ["POST", "/api/auth/sessions/refresh", "刷新即将到期的会话", "绑定原会话与设备摘要", "auth"],
  ["DELETE", "/api/auth/sessions/{sessionId}", "撤销会话", "本人或管理员权限", "auth"],
  ["GET", "/api/permissions/effective", "查询有效权限快照", "按 actor/project/resource/action", "auth"],
  ["POST", "/api/approvals/requests", "创建首次授权或高风险审批", "请求与批准主体分离", "auth"],
  ["POST", "/api/approvals/{approvalId}/decisions", "登记批准或拒绝", "签名、有效期、用途绑定", "auth"],
  ["POST", "/api/agent/requests", "提交自然语言目标", "范围、幂等、工具白名单", "agent"],
  ["GET", "/api/agent/requests/{requestId}", "查询规划与状态轨迹", "低敏响应、范围过滤", "agent"],
  ["GET", "/api/agent/plans/{planId}", "读取 AgentPlan", "ToolPlan 与治理事实可审计", "agent"],
  ["POST", "/api/agent/plans/{planId}/preview", "生成执行预览", "禁止副作用", "agent"],
  ["POST", "/api/agent/plans/{planId}/execute", "执行已批准计划", "审批、幂等、风险门禁", "agent"],
  ["GET", "/api/agent/executions/{executionId}/trace", "查询六 Specialist 与图节点", "统一链路 ID", "agent"],
  ["POST", "/api/agent/rag/query", "执行受范围约束的知识检索", "来源、时间、可信度、拒答", "agent"],
  ["GET", "/api/tasks", "分页查询任务", "项目范围与字段脱敏", "task"],
  ["POST", "/api/tasks", "创建任务草稿", "幂等键与配置校验", "task"],
  ["GET", "/api/tasks/{taskId}", "读取任务详情", "范围过滤", "task"],
  ["PUT", "/api/tasks/{taskId}", "更新任务草稿", "乐观锁与版本", "task"],
  ["POST", "/api/tasks/{taskId}/versions", "发布不可变配置版本", "摘要指纹与审计", "task"],
  ["POST", "/api/tasks/{taskId}/executions", "触发一次执行", "审批、幂等、调度互斥", "task"],
  ["POST", "/api/tasks/{taskId}/pause", "暂停调度和新分片", "记录原因", "task"],
  ["POST", "/api/tasks/{taskId}/resume", "恢复任务", "重新预检", "task"],
  ["GET", "/api/tasks/{taskId}/executions", "查询执行历史", "按状态与时间过滤", "task"],
  ["POST", "/api/data-sync/prechecks", "执行数据源与映射预检", "只读、超时、证据", "sync"],
  ["POST", "/api/data-sync/executions", "创建 Worker 执行", "taskVersion 与 executionId 绑定", "sync"],
  ["GET", "/api/data-sync/executions/{executionId}", "读取执行状态", "状态机一致性", "sync"],
  ["GET", "/api/data-sync/executions/{executionId}/objects", "读取对象/分片台账", "分页与范围过滤", "sync"],
  ["POST", "/api/data-sync/executions/{executionId}/cancel", "取消执行", "幂等取消", "sync"],
  ["POST", "/api/data-sync/executions/{executionId}/objects/{objectId}/replay", "重放失败对象", "授权盒、checkpoint、次数上限", "sync"],
  ["GET", "/api/connectors/{connectorId}/capabilities", "读取版本与容量", "只读、缓存有效期", "sync"],
  ["POST", "/api/recovery/cases", "创建恢复案例", "失败事件幂等", "recovery"],
  ["GET", "/api/recovery/cases/{caseId}", "读取诊断与循环状态", "低敏证据", "recovery"],
  ["POST", "/api/recovery/cases/{caseId}/diagnose", "执行一次诊断", "cycle 身份与证据变化", "recovery"],
  ["POST", "/api/recovery/cases/{caseId}/actions/preview", "预览修复动作", "风险、回滚、验证", "recovery"],
  ["POST", "/api/recovery/cases/{caseId}/actions/execute", "执行授权内低风险动作", "双重策略与幂等", "recovery"],
  ["POST", "/api/recovery/cases/{caseId}/handoff", "退出自治并生成人工指引", "根因、权限、影响", "recovery"],
  ["WS", "/ws/projects/{projectId}/events", "订阅项目实时事件", "连接鉴权与范围", "websocket"],
  ["EVENT", "agent.node.changed", "Agent 节点状态变化", "sequence 与 turnId", "websocket"],
  ["EVENT", "task.execution.changed", "任务执行状态变化", "taskId/executionId", "websocket"],
  ["EVENT", "sync.object.changed", "对象台账变化", "objectId/checkpoint", "websocket"],
  ["EVENT", "recovery.cycle.changed", "恢复循环变化", "caseId/cycle/actionCode", "websocket"],
  ["EVENT", "verification.completed", "后置验证完成", "PRECHECK/MONITOR durable fact", "websocket"],
];

// 综合接口文档不能只列几个示例端点。下面的扩展清单覆盖身份、Agent、任务、同步、恢复、质量、
// 可观测性和实时事件；专门接口手册按 domain 过滤，综合手册则完整收录全部合同。
const ADDITIONAL_API_CONTRACTS = [
  ["GET", "/api/auth/sessions", "查询当前主体的活动会话", "分页、设备摘要、不可返回令牌", "auth"],
  ["GET", "/api/auth/sessions/{sessionId}", "读取单个会话安全摘要", "本人或安全管理员", "auth"],
  ["POST", "/api/auth/sessions/{sessionId}/revoke", "撤销可疑会话", "幂等、记录撤销原因", "auth"],
  ["GET", "/api/auth/service-identities", "查询服务身份与用途", "平台管理员只读", "auth"],
  ["POST", "/api/auth/service-identities", "创建受限服务身份", "双主体审批与有效期", "auth"],
  ["POST", "/api/auth/service-identities/{identityId}/rotate", "轮换服务身份凭据引用", "不返回真实凭据", "auth"],
  ["GET", "/api/permissions/roles", "查询角色目录", "租户范围过滤", "auth"],
  ["POST", "/api/permissions/roles", "创建项目角色", "权限集合最小化", "auth"],
  ["PUT", "/api/permissions/roles/{roleId}", "更新角色权限", "乐观锁与变更审计", "auth"],
  ["GET", "/api/approvals/requests", "查询审批请求", "请求人、批准人和状态过滤", "auth"],
  ["GET", "/api/approvals/requests/{approvalId}", "读取审批事实", "返回范围、用途、有效期和指纹", "auth"],
  ["POST", "/api/approvals/{approvalId}/revoke", "撤销尚未消费或仍有效的批准", "撤销主体权限与审计", "auth"],
  ["GET", "/api/agent/requests", "按范围查询 Agent 请求", "不返回完整模型上下文", "agent"],
  ["POST", "/api/agent/requests/{requestId}/cancel", "取消尚未产生副作用的 Agent 请求", "幂等取消", "agent"],
  ["GET", "/api/agent/plans", "查询 AgentPlan 列表", "按状态、风险和时间过滤", "agent"],
  ["POST", "/api/agent/plans/ingestions", "摄取 Python 生成的 AgentPlan", "请求指纹与幂等冲突保护", "agent"],
  ["GET", "/api/agent/plans/{planId}/tool-plans", "查询计划中的 ToolPlan", "隐藏敏感参数", "agent"],
  ["GET", "/api/agent/executions", "查询 Agent 执行", "tenant/project 硬过滤", "agent"],
  ["POST", "/api/agent/executions/{executionId}/resume", "从 durable checkpoint 恢复", "状态与 checkpoint 校验", "agent"],
  ["POST", "/api/agent/executions/{executionId}/terminate", "终止图执行", "记录终止主体与原因", "agent"],
  ["GET", "/api/agent/executions/{executionId}/turns", "查询 Specialist turn", "按 sequence 返回低敏事实", "agent"],
  ["GET", "/api/agent/executions/{executionId}/facts", "查询 Java durable fact", "来源节点与摘要指纹", "agent"],
  ["GET", "/api/agent/executions/{executionId}/evidence", "查询统一证据记录", "来源、时间、可信度和状态", "agent"],
  ["POST", "/api/agent/rag/feedback", "提交引用相关性反馈", "操作者与 caseId 可审计", "agent"],
  ["GET", "/api/agent/rag/diagnostics", "读取 RAG 低敏诊断", "不返回 Endpoint、密钥或正文", "agent"],
  ["POST", "/api/agent/rag/evaluations", "启动黄金集评测", "非生产合成语料门禁", "agent"],
  ["GET", "/api/agent/rag/evaluations/{evaluationId}", "读取低敏评测报告", "只含 ID、指标和来源 URI", "agent"],
  ["DELETE", "/api/tasks/{taskId}", "归档或软删除任务", "运行中任务禁止删除", "task"],
  ["POST", "/api/tasks/imports", "从 Excel 导入任务", "文件扫描、列校验、预览后确认", "task"],
  ["GET", "/api/tasks/exports", "导出任务配置", "脱敏、审批和审计", "task"],
  ["GET", "/api/tasks/{taskId}/versions", "查询配置版本历史", "不可变版本与发布主体", "task"],
  ["GET", "/api/tasks/{taskId}/versions/{version}", "读取指定任务版本", "范围过滤", "task"],
  ["POST", "/api/tasks/{taskId}/versions/{version}/rollback-preview", "预览回滚差异", "只读、返回影响与验证", "task"],
  ["POST", "/api/tasks/{taskId}/versions/{version}/rollback", "回滚到历史成功版本", "授权盒或人工批准", "task"],
  ["GET", "/api/tasks/{taskId}/schedules", "查询调度计划", "时区和下次触发时间", "task"],
  ["POST", "/api/tasks/{taskId}/schedules", "创建调度计划", "cron、时区、重叠策略校验", "task"],
  ["PUT", "/api/tasks/{taskId}/schedules/{scheduleId}", "更新调度", "乐观锁与审计", "task"],
  ["POST", "/api/tasks/{taskId}/schedules/{scheduleId}/pause", "暂停调度", "保留暂停原因", "task"],
  ["POST", "/api/tasks/{taskId}/schedules/{scheduleId}/resume", "恢复调度", "恢复前重新预检", "task"],
  ["GET", "/api/tasks/{taskId}/executions/{executionId}", "读取一次执行", "任务与执行归属校验", "task"],
  ["POST", "/api/tasks/{taskId}/executions/{executionId}/retry", "重试整次执行", "仅允许可重试终态", "task"],
  ["POST", "/api/tasks/{taskId}/executions/{executionId}/reconcile", "触发读写量与状态对账", "只读验证", "task"],
  ["GET", "/api/tasks/{taskId}/audit-events", "查询任务审计轨迹", "低敏摘要和分页", "task"],
  ["POST", "/api/data-sync/executions/{executionId}/pause", "暂停领取新工作单元", "已在途单元安全完成", "sync"],
  ["POST", "/api/data-sync/executions/{executionId}/resume", "恢复暂停执行", "checkpoint 与任务版本校验", "sync"],
  ["GET", "/api/data-sync/executions/{executionId}/metrics", "查询吞吐与质量指标", "时间窗口和低敏维度", "sync"],
  ["GET", "/api/data-sync/executions/{executionId}/checkpoints", "查询对象 checkpoint", "只返回位点摘要", "sync"],
  ["POST", "/api/data-sync/executions/{executionId}/checkpoints/restore-preview", "预览位点恢复", "差异、重复风险与回滚", "sync"],
  ["POST", "/api/data-sync/executions/{executionId}/checkpoints/restore", "恢复已确认位点", "授权盒与幂等门禁", "sync"],
  ["GET", "/api/data-sync/executions/{executionId}/dirty-records", "查询脏数据摘要", "不返回未脱敏原始行", "sync"],
  ["POST", "/api/data-sync/executions/{executionId}/dirty-records/export", "导出受控脏数据", "显式权限与水印审计", "sync"],
  ["GET", "/api/data-sync/executions/{executionId}/lineage", "查询来源到目标血缘", "对象级范围过滤", "sync"],
  ["GET", "/api/connectors", "查询连接器目录", "版本、状态和能力摘要", "sync"],
  ["POST", "/api/connectors", "注册连接器配置", "凭据只保存引用", "sync"],
  ["PUT", "/api/connectors/{connectorId}", "更新连接器非敏感配置", "版本与回滚", "sync"],
  ["POST", "/api/connectors/{connectorId}/test", "测试连接器连通性", "只读、超时、低敏错误", "sync"],
  ["POST", "/api/connectors/{connectorId}/metadata/refresh", "刷新元数据", "授权盒内低风险动作", "sync"],
  ["GET", "/api/connectors/{connectorId}/metadata", "读取表、字段与约束", "按数据源权限过滤", "sync"],
  ["GET", "/api/connectors/{connectorId}/capacity", "读取限流和容量", "带探测时间和可信度", "sync"],
  ["GET", "/api/connectors/{connectorId}/health", "读取连接器健康", "不暴露端点凭据", "sync"],
  ["POST", "/api/data-sync/mappings/validate", "校验字段映射", "类型、非空、默认值和外键", "sync"],
  ["POST", "/api/data-sync/mappings/suggestions", "生成唯一映射候选", "候选证据与歧义标志", "sync"],
  ["GET", "/api/recovery/cases", "查询恢复案例列表", "范围、状态、错误码和时间过滤", "recovery"],
  ["POST", "/api/recovery/cases/{caseId}/cancel", "终止尚未执行动作的恢复案例", "幂等与原因", "recovery"],
  ["GET", "/api/recovery/cases/{caseId}/cycles", "查询恢复循环", "按 cycle 返回新增证据和动作", "recovery"],
  ["GET", "/api/recovery/cases/{caseId}/evidence", "查询恢复证据", "来源、时间、可信度和状态", "recovery"],
  ["POST", "/api/recovery/cases/{caseId}/evidence/search", "由模型选择后执行 RAG 检索", "硬范围过滤", "recovery"],
  ["GET", "/api/recovery/cases/{caseId}/actions", "查询候选与已执行动作", "参数前后差异", "recovery"],
  ["POST", "/api/recovery/cases/{caseId}/actions/{actionId}/rollback-preview", "预览修复回滚", "只读影响分析", "recovery"],
  ["POST", "/api/recovery/cases/{caseId}/actions/{actionId}/rollback", "回滚已执行低风险动作", "版本与状态校验", "recovery"],
  ["POST", "/api/recovery/cases/{caseId}/verify", "触发后置 PRECHECK/MONITOR", "强类型回执", "recovery"],
  ["POST", "/api/recovery/cases/{caseId}/replan", "基于新证据进入下一循环", "cycle 新幂等身份", "recovery"],
  ["POST", "/api/recovery/cases/{caseId}/resolve", "人工确认已解决", "主体、证据和说明", "recovery"],
  ["GET", "/api/recovery/action-catalog", "查询受治理修复动作目录", "风险、参数边界与所需权限", "recovery"],
  ["GET", "/api/recovery/policies/{projectId}", "查询项目恢复策略", "最大循环和动作白名单", "recovery"],
  ["PUT", "/api/recovery/policies/{projectId}", "更新恢复策略", "管理员审批与版本", "recovery"],
  ["EVENT", "agent.request.accepted", "Agent 请求已接受", "requestId/traceId/sequence", "websocket"],
  ["EVENT", "agent.plan.generated", "AgentPlan 已生成", "planId/toolPlanCount/risk", "websocket"],
  ["EVENT", "agent.retrieval.decided", "模型完成 SEARCH/SKIP 决策", "decision/strategy/evidenceCount", "websocket"],
  ["EVENT", "agent.approval.required", "计划等待首次授权", "approvalId/risk/scope", "websocket"],
  ["EVENT", "agent.approval.decided", "审批结果已记录", "requester/approver/result", "websocket"],
  ["EVENT", "kafka.event.published", "Outbox 事件已投递", "eventId/topic/partition", "websocket"],
  ["EVENT", "kafka.event.retried", "异步事件进入有限重试", "attempt/reason/nextAt", "websocket"],
  ["EVENT", "kafka.event.dead-lettered", "永久失败事件进入 DLT", "reason/originalTopic", "websocket"],
  ["EVENT", "sync.execution.started", "Worker 执行开始", "executionId/configVersion", "websocket"],
  ["EVENT", "sync.execution.progress", "执行进度变化", "rowsRead/rowsWritten/dirty", "websocket"],
  ["EVENT", "sync.checkpoint.committed", "对象位点已确认", "objectId/checkpoint", "websocket"],
  ["EVENT", "sync.execution.failed", "执行进入失败终态", "errorCode/retryable", "websocket"],
  ["EVENT", "recovery.action.previewed", "修复动作预览完成", "actionCode/risk/impact", "websocket"],
  ["EVENT", "recovery.action.executed", "修复动作已执行", "receipt/changedFields", "websocket"],
  ["EVENT", "recovery.handoff.required", "自治恢复需要人工接管", "rootCause/requiredPermission", "websocket"],
  ["EVENT", "task.lifecycle.closed", "任务工作流已收敛", "finalState/verification", "websocket"],
  ["GET", "/api/quality/rules", "查询数据质量规则", "项目范围与版本", "quality"],
  ["POST", "/api/quality/rules", "创建质量规则", "字段权限与阈值校验", "quality"],
  ["PUT", "/api/quality/rules/{ruleId}", "更新质量规则", "乐观锁和审计", "quality"],
  ["POST", "/api/quality/evaluations", "执行质量评估", "只读样本和限制", "quality"],
  ["GET", "/api/quality/evaluations/{evaluationId}", "读取质量结果", "规则、计数和低敏样本引用", "quality"],
  ["GET", "/api/quality/profiles/{datasetId}", "读取字段画像", "范围、空值、唯一和分布", "quality"],
  ["POST", "/api/quality/reconciliations", "执行来源目标对账", "聚合结果与差异引用", "quality"],
  ["GET", "/api/quality/dirty-records", "查询脏数据摘要", "脱敏与分页", "quality"],
  ["POST", "/api/quality/dirty-records/{batchId}/quarantine", "隔离坏行批次", "任务策略与审计", "quality"],
  ["POST", "/api/quality/dirty-records/{batchId}/replay", "修复后重放坏行", "验证规则和次数上限", "quality"],
  ["GET", "/api/observability/health", "读取平台聚合健康", "只返回低敏状态", "observability"],
  ["GET", "/api/observability/metrics/summary", "读取核心指标摘要", "时间窗口和范围", "observability"],
  ["GET", "/api/observability/traces/{traceId}", "读取统一链路追踪", "span 摘要与权限", "observability"],
  ["GET", "/api/observability/log-events", "查询结构化日志事件", "过滤、分页和脱敏", "observability"],
  ["GET", "/api/observability/alerts", "查询活动与历史告警", "项目范围", "observability"],
  ["POST", "/api/observability/alerts/{alertId}/acknowledge", "确认告警", "责任角色与说明", "observability"],
  ["POST", "/api/observability/alerts/{alertId}/silences", "创建有限静默", "有效期和审批", "observability"],
  ["GET", "/api/observability/kafka/consumer-groups", "查询消费者组 lag", "只读运维权限", "observability"],
  ["GET", "/api/observability/providers", "读取模型 Provider 状态", "不返回 Endpoint 或密钥", "observability"],
  ["GET", "/api/observability/capacity", "读取数据库、Kafka、Worker 容量", "时间戳与可信度", "observability"],
];

const ALL_API_CONTRACTS = [...API_CONTRACTS, ...ADDITIONAL_API_CONTRACTS];

const OPERATIONS_CHECKS = [
  ["Gateway", "请求率、4xx/5xx、鉴权延迟", "traceId 与 routeId", "先隔离入口异常再检查下游"],
  ["Task Management", "调度延迟、运行状态、版本冲突", "taskId 与 configVersion", "核对调度锁和任务版本"],
  ["Agent Runtime", "规划延迟、ToolPlan、审计失败", "planId 与 durableFactId", "区分模型、门禁和持久化故障"],
  ["Python AI Runtime", "模型延迟、图节点、checkpoint", "requestId 与 turnId", "检查 Provider 与 LangGraph 状态"],
  ["Kafka Broker", "under-replicated、磁盘、吞吐", "topic 与 brokerId", "先确认集群健康"],
  ["Kafka Consumer", "lag、rebalance、处理时延", "group 与 partition", "隔离坏消息后再扩容"],
  ["Outbox", "NEW/DELIVERED/FAILED 数量", "eventId 与 aggregateId", "重投未交付事件"],
  ["DLT", "新增速率与错误分布", "originalTopic 与 reasonCode", "永久错误人工处置"],
  ["Datasource", "连接、元数据、版本能力", "datasourceId 与 connectorId", "刷新元数据或恢复端点"],
  ["Data Sync Worker", "rows/s、dirty、checkpoint", "executionId 与 objectId", "按对象定位"],
  ["PostgreSQL", "连接、锁、慢查询、WAL", "database 与 queryId", "避免长事务和锁等待"],
  ["pgvector", "维度、模型、索引命中", "embeddingModel 与 dimension", "检查条件索引和范围谓词"],
  ["Redis", "命中率、内存、过期、连接", "key namespace", "只清理可再生缓存"],
  ["MinIO", "对象错误、容量、版本", "bucket 与 objectRef", "保留审计和引用对象"],
  ["Model Provider", "429、5xx、p95、缺项", "provider 与 model", "有界退避或显式降级"],
  ["Embedding", "批次、维度、延迟", "model 与 vectorVersion", "禁止维度漂移"],
  ["Reranker", "候选数、缺项、分数", "model 与 candidateCount", "缺项 fail-closed"],
  ["WebSocket", "连接数、重连、事件积压", "sessionId 与 lastEventId", "快照补偿"],
  ["Prometheus", "抓取失败与规则错误", "job 与 instance", "恢复监控可信度"],
  ["Alertmanager", "通知失败与静默", "alertname 与 receiver", "检查路由和抑制"],
  ["磁盘", "使用率、inode、增长率", "volume 与 mount", "清理可再生文件或扩容"],
  ["证书", "有效期与握手错误", "issuer 与 endpoint", "按变更流程轮换"],
  ["备份", "最近成功、大小、恢复测试", "backupId 与 restorePoint", "失败立即升级"],
  ["审计", "缺失事件、哈希链、保留", "auditId 与 traceId", "禁止无审计副作用"],
];

const TEST_MATRIX = [
  ["六 Specialist 正常路径", "自然语言同步目标", "六个 durable turn 顺序完整", "PASS"],
  ["六 Specialist 恢复路径", "注入可恢复错误", "Recovery 后 PRECHECK/MONITOR 完成", "PASS"],
  ["RAG SEARCH 决策", "历史事故问题", "模型选择检索且引用来源", "PASS"],
  ["RAG SKIP 决策", "结构化事实充分", "不产生虚假引用", "PASS"],
  ["跨租户隔离", "其他租户精确码", "候选与引用均为零", "PASS"],
  ["双主体审批", "请求人与批准人不同", "批准事实可验证", "PASS"],
  ["自批准拒绝", "同主体批准", "门禁拒绝", "PASS"],
  ["幂等重放", "相同键相同请求", "返回原回执", "PASS"],
  ["幂等冲突", "相同键不同请求", "稳定冲突错误", "PASS"],
  ["Kafka 重试", "可重试网络错误", "有限退避后成功", "PASS"],
  ["Kafka DLT", "永久合同错误", "进入 DLT 且无重复副作用", "PASS"],
  ["Outbox", "事务后投递失败", "重投并标记 DELIVERED", "PASS"],
  ["字段唯一映射", "历史与元数据一致", "低风险自动修复", "PASS"],
  ["字段歧义映射", "两个候选同分", "退出 Loop", "PASS"],
  ["非空默认值", "已有批准静态默认", "修复并 replay", "PASS"],
  ["DDL 必需", "目标缺列", "人工接管", "PASS"],
  ["外键顺序", "父对象延迟", "先父后子 replay", "PASS"],
  ["超时调参", "端点短暂变慢", "授权内增加超时", "PASS"],
  ["并发调参", "目标限流", "降低 channel", "PASS"],
  ["Checkpoint 恢复", "确认丢失", "安全位点 replay", "PASS"],
  ["WebSocket 重连", "客户端断线", "lastEventId 补偿", "PASS"],
  ["Provider 429", "远程限流", "有界退避", "PASS"],
  ["Embedding 维度漂移", "返回错误维度", "fail-closed", "PASS"],
  ["Reranker 缺项", "返回候选不足", "fail-closed", "PASS"],
];

/** 根据主题推断内容家族，以便选择真正相关的接口、运维、事故或测试资料。 */
function topicFamily(topic) {
  if (topic.category.includes("api") || topic.slug.includes("websocket")) return "api";
  if (topic.sourceType === "incident" || topic.slug.includes("postmortem") || topic.slug.includes("record")) return "incident";
  if (topic.category.includes("test") || topic.slug.includes("report-")) return "test";
  if (topic.slug.includes("operations") || topic.slug.includes("kafka") || topic.slug.includes("postgresql") || topic.slug.includes("provider") || topic.slug.includes("backup") || topic.slug.includes("upgrade") || topic.slug.includes("deployment")) return "operations";
  if (topic.slug.includes("recovery") || topic.slug.includes("schema")) return "recovery";
  if (topic.slug.includes("security") || topic.slug.includes("administrator")) return "governance";
  return "guide";
}

/** 为 API 主题选择端点子集；综合接口说明保留全域清单。 */
function endpointRows(topic) {
  const domain = topic.slug === "reference-api-websocket" ? null
    : topic.slug.includes("authentication") ? "auth"
    : topic.slug.includes("agent-api") ? "agent"
      : topic.slug.includes("task-api") ? "task"
        : topic.slug.includes("data-sync") ? "sync"
          : topic.slug.includes("recovery-api") ? "recovery"
            : topic.slug.includes("websocket") ? "websocket"
              : null;
  const rows = domain ? ALL_API_CONTRACTS.filter((row) => row[4] === domain) : ALL_API_CONTRACTS;
  return rows.map(([method, path, contract, governance]) => [method, path, contract, governance]);
}

/**
 * 把接口清单扩展为字段级合同。
 *
 * 合成接口资料不需要假装拥有真实 OpenAPI 文件，但仍要让初学者看清一次调用的完整边界：
 * 谁能调用、输入放在哪里、幂等如何工作、返回哪些关联标识、什么错误可以重试，以及审计保存
 * 什么。所有内容由稳定的端点清单派生，因此重复生成不会改变同一接口的语义。
 */
function apiContractDetails(topic) {
  const domain = topic.slug === "reference-api-websocket" ? null
    : topic.slug.includes("authentication") ? "auth"
    : topic.slug.includes("agent-api") ? "agent"
      : topic.slug.includes("task-api") ? "task"
        : topic.slug.includes("data-sync") ? "sync"
          : topic.slug.includes("recovery-api") ? "recovery"
            : topic.slug.includes("websocket") ? "websocket"
              : null;
  const contracts = domain
    ? ALL_API_CONTRACTS.filter((row) => row[4] === domain)
    : ALL_API_CONTRACTS;
  return contracts.map(([method, endpoint, purpose, governance], index) => {
    const identifierFields = [...endpoint.matchAll(/\{([^}]+)\}/g)].map((match) => match[1]);
    const isRead = method === "GET" || method === "WS-EVENT" || method === "WS-SUBSCRIBE";
    const requestFields = [
      ...identifierFields.map((field) => `${field}: path/string/必填`),
      "tenantId: trusted-context/string/必填",
      "projectId: trusted-context/string/必填",
      "traceId: header/string/必填",
      ...(isRead
        ? ["pageToken: query/string/可选", "limit: query/integer/1..200"]
        : ["idempotencyKey: header/string/必填", "schemaVersion: body/string/必填"]),
    ];
    const responseFields = method.startsWith("WS")
      ? ["eventId", "sequence", "occurredAt", "sourceService", "traceId", "payload", "sourceStatus"]
      : ["requestId", "traceId", "status", "reasonCode", "occurredAt", "data", "evidenceRecords"];
    const error = ERROR_CATALOG[index % ERROR_CATALOG.length];
    return {
      sequence: index + 1,
      method,
      endpoint,
      purpose,
      governance,
      permission: `${domain ?? "platform"}:${isRead ? "read" : "execute"}`,
      requestFields,
      responseFields,
      idempotency: isRead ? "只读查询；pageToken 必须绑定范围和过滤条件" : "相同幂等键与相同语义指纹返回原回执；同键异请求拒绝",
      errors: [error[0], "PERMISSION_DENIED", "VALIDATION_FAILED", "INTERNAL_CONTRACT_ERROR"],
      audit: "记录请求/执行主体、租户、项目、资源、动作、幂等键摘要、结果、reasonCode 和 traceId；不记录凭据与业务正文",
      exampleId: `API-${String(index + 1).padStart(3, "0")}`,
    };
  });
}

/**
 * 生成可跨格式复用的一组关联标识。
 *
 * 第 N 个任务在 XLSX、JSONL、CSV、LOG、SQL 和事故手册中始终得到同一组 ID，RAG 评测因此
 * 可以真正验证“从失败日志追到事故、从事故追到修复任务”，而不是只做相似词命中。
 */
function correlationFacts(scope, index) {
  const serial = String(index + 1).padStart(4, "0");
  return {
    taskId: `TASK-${scope.key}-${serial}`,
    executionId: `EXEC-${scope.key}-${serial}`,
    objectId: `object-${String(index % 32).padStart(2, "0")}`,
    traceId: `trace-${scope.key}-${serial}`,
    incidentId: `INC-${scope.key}-${serial}`,
    recoveryCaseId: `RC-${scope.key}-${serial}`,
    eventId: `EVT-${scope.key}-${serial}`,
    configVersion: `cfg-v${100 + index}`,
    lastSuccessfulConfigVersion: `cfg-v${99 + index}`,
  };
}

/**
 * 生成独立事故案例。每个案例都包含故障原因、证据、自动修复边界、回滚和最终验证，且标识能
 * 与其他格式中的同序号记录关联。高风险案例明确退出 Loop，不把“无法自动处理”伪装成失败重试。
 */
function incidentCases(scope, topic, count) {
  return Array.from({ length: count }, (_, index) => {
    const ids = correlationFacts(scope, index);
    const error = ERROR_CATALOG[index % ERROR_CATALOG.length];
    const requiresPrivilege = ["AUTHENTICATION_FAILED", "PERMISSION_DENIED", "DDL_REQUIRED"].includes(error[0]);
    const ambiguous = index % 17 === 0 && error[0] === "FIELD_MAPPING_MISSING";
    const manual = requiresPrivilege || ambiguous || index % 23 === 0;
    const actionCode = manual
      ? "MANUAL_HANDOFF"
      : ["ROLLBACK_LAST_SUCCESS_CONFIG", "PATCH_UNIQUE_FIELD_MAPPING", "SET_APPROVED_STATIC_DEFAULT", "REFRESH_METADATA", "RESTORE_CONFIRMED_CHECKPOINT", "REORDER_DEPENDENCY_AND_REPLAY", "REDUCE_BATCH_AND_CHANNEL", "INCREASE_TIMEOUT_WITHIN_BOUND", "REPLAY_FAILED_OBJECTS"][index % 9];
    const observedAt = syntheticTime(index, 9);
    const sourceUri = `synthetic://datasmart-govern/correlated/${scope.key}/execution/${ids.executionId}`;
    return {
      caseId: `CASE-${topic.code}-${String(index + 1).padStart(4, "0")}`,
      ...ids,
      mode: ["FULL", "INCREMENTAL", "CDC", "FILE", "API", "KAFKA", "OBJECT_STORAGE", "SCHEMA_EVOLUTION", "DATA_QUALITY", "RECOVERY_REPLAY"][index % 10],
      errorCode: error[0],
      failureReason: `${error[1]}；当前配置 ${ids.configVersion} 在对象 ${ids.objectId} 上触发 ${error[0]}，需要依据 ${error[2]} 判定根因。`,
      evidenceSource: sourceUri,
      observedAt,
      confidence: Number((0.90 + (index % 10) / 100).toFixed(2)),
      confidenceBasis: index % 3 === 0 ? "STRUCTURED_LOG_AND_DATABASE_CONSTRAINT" : index % 3 === 1 ? "CONFIG_DIFF_AND_LAST_SUCCESS" : "RUNBOOK_AND_HISTORICAL_INCIDENT_CORROBORATION",
      sourceStatus: "COMPLETE",
      currentConfig: `${ids.configVersion};batch=${scope.batchSize};channel=${scope.channelCount};timeout=${scope.timeoutSeconds}`,
      lastSuccessConfig: `${ids.lastSuccessfulConfigVersion};batch=${Math.max(50, scope.batchSize - 50)};channel=${Math.max(1, scope.channelCount - 1)};timeout=${scope.timeoutSeconds}`,
      rootCause: error[1],
      repairAction: `${actionCode}：${error[3]}`,
      actionCode,
      risk: manual ? "HIGH" : index % 7 === 0 ? "MEDIUM" : "LOW",
      requiresPrivilege: manual ? "是" : "否",
      requiredPermission: manual ? "任务管理员或数据库/安全管理员按问题类型执行" : "首次授权盒内 data-sync:recover",
      impact: manual ? "继续自动执行可能越权、改变结构或产生不可逆副作用" : `仅影响 ${ids.objectId}，不扩大任务和数据范围`,
      rollback: `恢复 ${ids.lastSuccessfulConfigVersion}，撤销本轮动作并保持已成功对象不变`,
      verification: manual ? "人工处理后重新执行 PRECHECK_AGENT，再由 MONITOR_AGENT 验证" : "PRECHECK_AGENT 校验配置/约束，MONITOR_AGENT 核对读写量、脏数据、延迟和告警",
      cycle: 1 + (index % Math.max(1, scope.retryLimit)),
      finalState: manual ? "ATTENTION_REQUIRED" : "RECOVERED",
    };
  });
}

/** 为事故复盘生成分钟级时间线，确保每一步都有来源、决策和验证。 */
function incidentTimeline(scope, topic) {
  const stages = [
    ["02:00", "调度触发", "task scheduler", "创建 execution 与对象台账"],
    ["02:01", "PRECHECK 完成", "precheck durable fact", "连接、元数据和权限通过"],
    ["02:03", "首个分片运行", "worker metric", "吞吐进入基线范围"],
    ["02:07", "异常首次出现", "worker structured log", "记录错误码与 objectId"],
    ["02:08", "告警触发", "Prometheus/Alertmanager", "关联 traceId 与 executionId"],
    ["02:09", "Recovery 建案", "Kafka recovery event", "caseState=INVESTIGATING"],
    ["02:10", "结构化诊断", "日志与配置差异", "确认当前配置偏离成功基线"],
    ["02:11", "知识检索", "Runbook 与历史事故", "记录来源、时间和可信度"],
    ["02:12", "动作预览", "recovery action preview", "计算风险、影响和回滚"],
    ["02:13", "治理门禁", "authorization box", "确认动作位于首次授权范围"],
    ["02:14", "执行低风险修复", "action receipt", "保存参数前后差异"],
    ["02:15", "失败对象 replay", "object ledger", "attempt 增加且 checkpoint 不倒退"],
    ["02:18", "数据写入完成", "worker receipt", "读写量一致"],
    ["02:19", "PRECHECK 后置复核", "durable fact", "映射、约束和位点通过"],
    ["02:20", "MONITOR 后置复核", "metric evidence", "脏数据为零且延迟恢复"],
    ["02:21", "案例收敛", "recovery case", "caseState=RECOVERED"],
    ["次日", "复盘评审", "incident review", "登记根因和改进责任"],
    ["七日后", "改进验收", "test report", "故障注入用例通过"],
  ];
  return stages.map(([time, event, source, decision], index) => [
    time,
    event,
    `${source}; confidence=${(0.9 + (index % 9) / 100).toFixed(2)}`,
    `${decision}；${topic.code}-${scope.key}-${String(index + 1).padStart(2, "0")}`,
  ]);
}

/** 生成每份手册都需要的深度内容，主生成器只负责排版为 Word 元素。 */
export function buildEnterpriseDocxDetail(scope, topic) {
  const family = topicFamily(topic);
  const roleRows = [
    ["普通用户", "创建、查看和管理本人有权限的同步任务", "不能批准自己的高风险请求"],
    ["项目负责人", "确认任务目标、范围、质量和首次授权盒", "不能绕过平台安全策略"],
    ["数据工程师", "维护连接器、映射、调度和性能参数", "只在项目范围内操作"],
    ["运维人员", "处理告警、容量、恢复和发布", "不直接读取无权限业务正文"],
    ["审计人员", "查询审批、执行、修复和导出证据", "默认只读"],
    ["租户管理员", "配置租户角色与项目授权", "不能修改其他租户"],
    ["平台管理员", "维护平台级策略、Provider 和基础设施", "所有高风险操作需审计"],
    ["服务身份", "执行 Java/Python/Kafka 内部调用", "不能替代最终用户审批"],
  ];
  const termRows = [
    ["taskId", "一个可版本化的数据同步任务", "任务配置、调度和历史查询"],
    ["configVersion", "不可变任务配置版本", "对比当前与上次成功配置"],
    ["executionId", "一次任务运行", "状态、指标和 Recovery 关联"],
    ["objectId", "表、分片、文件或消息分区工作单元", "失败对象 replay"],
    ["traceId", "跨 Gateway、Agent、Kafka 和 Worker 的追踪标识", "日志与指标关联"],
    ["eventId", "Kafka/WebSocket 事件身份", "投递幂等和重放"],
    ["planId", "AgentPlan 身份", "ToolPlan 与治理事实"],
    ["turnId", "Specialist 一次 durable turn", "LangGraph checkpoint"],
    ["recoveryCaseId", "一次恢复案例", "循环、动作和最终状态"],
    ["checkpoint", "已确认的数据消费位点", "安全恢复与去重"],
    ["authorizationBox", "首次授权允许的范围、动作和参数边界", "无人值守自治门禁"],
    ["evidenceRecord", "带来源、时间、可信度和状态的证据", "RAG 与诊断引用"],
  ];
  const preconditions = [
    `当前范围必须为 tenantId=${scope.tenantId}、projectId=${scope.projectId}、workspaceKey=${scope.workspaceKey}。`,
    "操作者身份、项目角色、资源动作和审批事实必须来自可信控制面。",
    "任务配置必须已通过 schema、字段映射、连接器能力和目标约束校验。",
    "数据库、Kafka、对象存储、Java 服务、Python Runtime 和 Worker 健康状态可查询。",
    "日志不得包含凭据、完整连接串、原始敏感数据或不受控模型正文。",
    "执行命令必须携带幂等键、超时、重试上限和预期回执合同。",
    "RAG 查询必须先执行 tenant/project/workspace 硬范围过滤。",
    "任何自动修复都必须能说明触发证据、风险、影响、回滚和验证。",
    "不可逆动作、DDL、权限、凭据、覆盖数据和扩大范围不在自动修复目录中。",
    "演练与评测仅使用合成数据，并与生产运行模式 fail-closed 隔离。",
  ];
  const sectionDetails = topic.sections.map(([name, detail], index) => ({
    name,
    detail,
    inputs: `输入包括 ${topic.title} 的范围事实、配置版本、关联标识和第 ${index + 1} 组证据。`,
    actions: [
      `确认 ${name} 的前置状态和责任角色，不从模型输出推断授权。`,
      `按 traceId、taskId、executionId 和 objectId 查询与 ${name} 相关的结构化事实。`,
      `比较当前值、上一次成功值和连接器/平台允许上界，记录差异来源。`,
      `执行可回滚动作或输出人工接管指引，并保存幂等回执。`,
    ],
    evidence: `证据至少包含 sourceUri、observedAt、confidence、confidenceBasis、sourceStatus 和 ${topic.code}。`,
    acceptance: `验收要求 ${name} 产生确定状态、无范围泄漏、无重复副作用并可由后续节点复核。`,
  }));
  const configurationRows = [
    ["tenantId", scope.tenantId, "不可自动修改", "来自可信会话与任务事实"],
    ["projectId", scope.projectId, "不可自动扩大", "跨项目需要新审批"],
    ["workspaceKey", scope.workspaceKey, "硬范围过滤", "用于现有 RAG/Agent 隔离合同"],
    ["lagBudgetMinutes", scope.lagBudgetMinutes, "只读基线", "超出后触发监控诊断"],
    ["maxRecoveryCycles", scope.retryLimit, "不可突破", "达到上限转人工"],
    ["batchSize", scope.batchSize, `1..${scope.batchSize}`, "自动动作只允许降低"],
    ["channelCount", scope.channelCount, `1..${scope.channelCount}`, "自动动作只允许降低"],
    ["timeoutSeconds", scope.timeoutSeconds, `${scope.timeoutSeconds}..${scope.timeoutSeconds + 120}`, "只允许有界增加"],
    ["retentionDays", scope.retentionDays, "按合规策略", "删除需要保留策略授权"],
    ["dirtyRecordRatio", "0.1%", "0..任务阈值", "超过停止阈值退出"],
    ["checkpointPolicy", "confirmed-only", "不可倒退到未确认位点", "重放需验证幂等"],
    ["idempotencyPolicy", "request+semantic fingerprint", "同键同请求重放", "同键异请求冲突"],
    ["retrievalMode", "model-selected SEARCH/SKIP", "记录理由", "范围过滤始终强制"],
    ["citationPolicy", "source+time+confidence", "缺项不可作为完整证据", "保留原始 sourceUri"],
    ["approvalPolicy", "requester/approver separated", "高风险双主体", "自批准拒绝"],
    ["eventSchemaVersion", "datasmart.event.v1", "向后兼容", "未知主版本拒绝"],
    ["embeddingModel", "BAAI/bge-m3", "1024 维", "模型变化需重建索引"],
    ["rerankerModel", "BAAI/bge-reranker-v2-m3", "候选有界", "缺项或非法分数拒绝"],
  ];
  const procedureRows = Array.from({ length: 18 }, (_, index) => {
    const phase = ["准备", "发现", "定位", "对比", "检索", "决策", "预览", "门禁", "执行", "回执", "重跑", "预检", "监控", "收敛", "审计", "通知", "复盘", "改进"][index];
    const owner = ["用户", "MONITOR_AGENT", "RECOVERY_AGENT", "DATA_SYNC_AGENT", "KNOWLEDGE_AGENT", "RECOVERY_AGENT", "Java 控制面", "权限服务", "Worker", "data-sync", "Worker", "PRECHECK_AGENT", "MONITOR_AGENT", "Task Runtime", "审计服务", "Gateway", "运维", "产品/研发"][index];
    return [
      `${String(index + 1).padStart(2, "0")}-${phase}`,
      owner,
      `围绕“${topic.title}”执行 ${phase}，保留 ${topic.code}-${String(index + 1).padStart(2, "0")} 证据。`,
      index < 14 ? "状态与回执可验证后进入下一步" : "形成长期改进与回归用例",
    ];
  });
  let familyTitle = "能力与控制清单";
  let familyHeaders = ["能力", "输入", "输出", "治理控制"];
  let familyRows = procedureRows;
  if (family === "api") {
    familyTitle = "接口与事件合同清单";
    familyHeaders = ["方法/类型", "路径或事件", "用途", "治理要求"];
    familyRows = endpointRows(topic);
  } else if (family === "operations") {
    familyTitle = "组件运维检查清单";
    familyHeaders = ["组件", "指标/查询", "关联标识", "处置原则"];
    familyRows = OPERATIONS_CHECKS;
  } else if (family === "incident") {
    familyTitle = "事故时间线";
    familyHeaders = ["时间", "事件", "证据来源与可信度", "决策/结果"];
    familyRows = incidentTimeline(scope, topic);
  } else if (family === "test") {
    familyTitle = "测试场景矩阵";
    familyHeaders = ["测试域", "输入/注入", "核心断言", "结果"];
    familyRows = TEST_MATRIX;
  }
  const caseCount = family === "incident" ? 250
    : family === "test" ? 200
      : family === "operations" ? 160
        : family === "api" ? 120
          : 100;
  const caseDetails = incidentCases(scope, topic, caseCount);
  const taskCaseRows = caseDetails.map((item) => [
    item.caseId,
    item.taskId,
    item.executionId,
    item.mode,
    item.errorCode,
    item.failureReason,
    item.finalState,
  ]);
  const evidenceRows = [
    ["结构化日志", "traceId/taskId/executionId/objectId/errorCode", "事件时间", "0.95-0.99"],
    ["Prometheus 指标", "吞吐、延迟、错误、lag、容量", "采样时间", "0.90-0.98"],
    ["任务配置版本", "当前与最近成功配置差异", "版本发布时间", "0.99"],
    ["连接器能力", "版本、模式、限流、批量、并发", "探测时间", "0.95"],
    ["数据库约束", "字段、类型、非空、外键、索引", "元数据刷新时间", "0.98"],
    ["对象台账", "attempt、checkpoint、读写量、状态", "状态更新时间", "0.99"],
    ["Kafka 事件", "eventId、topic、partition、offset", "broker 时间", "0.98"],
    ["审批事实", "请求主体、批准主体、范围、有效期", "决定时间", "1.00"],
    ["Runbook", "现行操作步骤与边界", "effectiveAt", "0.90-0.97"],
    ["历史事故", "根因、修复、回滚、验证", "复盘时间", "0.85-0.96"],
    ["成功任务案例", "参数、映射、结果和 checkpoint", "完成时间", "0.92-0.99"],
    ["模型判断", "候选动作与解释", "生成时间", "不作为授权事实"],
  ];
  const riskRows = [
    ["回滚到最近成功配置", "LOW", "配置版本存在且范围相同", "自动执行"],
    ["降低 batch/channel", "LOW", "不低于最小值", "自动执行"],
    ["有界增加 timeout", "LOW", "不超过授权上界", "自动执行"],
    ["刷新元数据", "LOW", "只读连接器能力", "自动执行"],
    ["恢复已确认 checkpoint", "LOW", "幂等与位点可证明", "自动执行"],
    ["失败对象 replay", "LOW/MEDIUM", "仅失败对象且次数有界", "自动或策略复核"],
    ["唯一字段映射修复", "LOW", "元数据与历史配置一致", "自动执行"],
    ["已批准静态默认值", "LOW", "默认值在任务契约中", "自动执行"],
    ["调整父子写入顺序", "MEDIUM", "不改变约束与范围", "策略复核后执行"],
    ["修改凭据或权限", "HIGH", "需要特权主体", "退出 Loop"],
    ["执行 DDL/删除/覆盖", "HIGH", "不可逆或高影响", "退出 Loop"],
    ["扩大同步对象或租户范围", "HIGH", "超出首次授权", "退出 Loop"],
  ];
  const checklist = [
    "确认文档版本、适用范围、精确码和 sourceStatus 当前有效。",
    "确认用户、项目、应用和服务身份均来自可信控制面。",
    "确认 taskId、configVersion、executionId、objectId 和 traceId 可相互关联。",
    "确认当前配置与最近成功配置已经生成结构化差异。",
    "确认连接器版本、能力、限流、容量和目标约束已刷新。",
    "确认日志、指标、追踪、对象台账和 Kafka 事件时间已对齐。",
    "确认每条证据带 sourceUri、observedAt、confidence 和 basis。",
    "确认 RAG 候选在 Reranker 前完成范围过滤。",
    "确认模型 SEARCH/SKIP 决策和理由可审计。",
    "确认修复动作位于受治理动作目录和首次授权盒。",
    "确认参数只在允许上下界内变化。",
    "确认幂等键和业务指纹能区分重放与冲突。",
    "确认可重试与永久错误分类准确。",
    "确认失败对象 replay 不包含已成功对象。",
    "确认 checkpoint 不倒退到未确认位点。",
    "确认写入前后数据量、脏数据和约束结果可核对。",
    "确认 PRECHECK_AGENT 完成修复后复核。",
    "确认 MONITOR_AGENT 完成结果、延迟和告警复核。",
    "确认最终 task/execution/object/recovery 状态一致。",
    "确认 WebSocket 事件能够重建完整顺序。",
    "确认审计记录不包含凭据、完整正文或敏感样本。",
    "确认越权场景返回权限、步骤、影响、回滚和验证。",
    "确认最大恢复循环生效且每轮有新证据。",
    "确认新增事故规则已经进入自动化回归。",
  ];
  const faqRows = [
    ["为什么不能只重试？", "明确的映射、默认值、位点或依赖错误必须先修复根因，再 replay 失败对象。"],
    ["为什么不能自动修改 DDL？", "DDL 可能锁表、丢数据或改变共享结构，超出低风险授权盒。"],
    ["什么时候调用 RAG？", "模型认为历史文档能补足当前结构化事实时选择 SEARCH，否则选择 SKIP。"],
    ["证据可信度如何使用？", "可信度用于排序和冲突提示，权限与审批仍由控制面事实决定。"],
    ["如何处理证据冲突？", "优先当前、完整、同范围和高可信来源；无法消解时拒答或人工接管。"],
    ["为什么要比较成功配置？", "它是同任务、同范围、真实成功的最强低风险恢复基线。"],
    ["什么是失败对象 replay？", "只重放失败表、分片、文件或消息分区，不重跑已成功对象。"],
    ["如何避免无限循环？", "限制最大 cycle，并要求每轮新增诊断证据或不同的受治理动作。"],
    ["Provider 不可用怎么办？", "有界重试后显式降级或暂停，不伪造模型、向量或重排结果。"],
    ["跨租户精确码能否命中？", "不能；范围过滤先于召回和 Reranker，越权文档不得成为候选。"],
    ["为什么保存原始 URI？", "引用必须能回到受权限控制的原文件，而不是无法审计的临时文本。"],
    ["如何判断修复成功？", "执行成功还不够，必须通过 PRECHECK 与 MONITOR 后置验证。"],
    ["谁可以批准高风险动作？", "由权限策略指定且必须与请求主体区分，模型没有批准权。"],
    ["日志可否保存原始数据？", "默认只保存低敏字段、摘要和引用；正文留在受控存储。"],
    ["这份资料能否直接用于生产操作？", "它是合成评测资料；生产操作仍需读取当前环境配置与正式 Runbook。"],
  ];
  const exampleBlocks = [
    `请求示例：{"tenantId":"${scope.tenantId}","projectId":"${scope.projectId}","traceId":"trace-synthetic-001","objective":"${topic.title}","idempotencyKey":"idem-${topic.code}-001"}`,
    `证据示例：{"sourceUri":"synthetic://datasmart-govern/${topic.slug}","observedAt":"2026-08-16T02:10:00+08:00","confidence":0.97,"confidenceBasis":"STRUCTURED_FACT","sourceStatus":"COMPLETE"}`,
    `动作预览：{"actionCode":"REPLAY_FAILED_OBJECTS","risk":"LOW","affectedObjects":["shard-07"],"rollback":"恢复原任务配置版本","verification":["PRECHECK_AGENT","MONITOR_AGENT"]}`,
    `事件示例：{"schemaVersion":"datasmart.event.v1","eventId":"evt-${topic.code}-001","traceId":"trace-synthetic-001","node":"RECOVERY_AGENT","state":"SUCCEEDED","occurredAt":"2026-08-16T02:15:00+08:00"}`,
  ];
  return {
    family,
    apiDetails: family === "api" ? apiContractDetails(topic) : [],
    caseDetails,
    roleRows,
    termRows,
    preconditions,
    sectionDetails,
    procedureRows,
    configurationRows,
    familyTitle,
    familyHeaders,
    familyRows,
    errorRows: ERROR_CATALOG,
    taskCaseRows,
    evidenceRows,
    riskRows,
    checklist,
    faqRows,
    exampleBlocks,
  };
}

const TASK_MODES = {
  "workbook-full-load-task-cases": "FULL",
  "workbook-incremental-task-cases": "INCREMENTAL",
  "workbook-cdc-task-cases": "CDC",
  "workbook-file-task-cases": "FILE",
  "workbook-api-task-cases": "API",
  "workbook-kafka-task-cases": "KAFKA",
  "workbook-object-storage-task-cases": "OBJECT_STORAGE",
  "workbook-schema-evolution-task-cases": "SCHEMA_EVOLUTION",
  "workbook-quality-task-cases": "DATA_QUALITY",
  "workbook-recovery-replay-task-cases": "RECOVERY_REPLAY",
};

const SOURCES = ["PostgreSQL.orders", "MySQL.customer", "Kafka.payment", "CSV.daily_sales", "API.inventory", "MinIO.parquet/events", "MongoDB.profile", "PostgreSQL.refund"];
const TARGETS = ["warehouse.order_fact", "lake.customer", "warehouse.payment", "staging.daily_sales", "warehouse.inventory", "lake.events", "warehouse.profile", "warehouse.refund_fact"];
const STRATEGIES = ["唯一字段映射", "已批准静态默认值", "无损类型转换", "迟到数据回看窗口", "父子对象依赖顺序", "脏数据隔离", "Schema 指纹比较", "业务键去重"];

/** 为任务案例工作簿生成 240 条跨来源、目标和故障类型的可编辑案例。 */
function taskScenarioDataset(scope, topic, mode) {
  const rows = Array.from({ length: 240 }, (_, index) => {
    const ids = correlationFacts(scope, index);
    const error = ERROR_CATALOG[index % ERROR_CATALOG.length];
    const source = SOURCES[index % SOURCES.length];
    const target = TARGETS[(index + 2) % TARGETS.length];
    const batch = Math.max(50, scope.batchSize - (index % 5) * 50);
    const channel = Math.max(1, scope.channelCount - (index % 3));
    const timeout = scope.timeoutSeconds + (index % 4) * 30;
    const cursor = mode === "CDC" ? `lsn-${String(300 + index).padStart(6, "0")}`
      : mode === "KAFKA" ? `partition-${index % 8}:offset-${1200 + index * 17}`
        : mode === "FILE" || mode === "OBJECT_STORAGE" ? `manifest-${String(index + 1).padStart(3, "0")}`
          : `cursor-${String(8000 + index * 113).padStart(6, "0")}`;
    return [
      ids.taskId,
      ids.executionId,
      ids.traceId,
      `${mode} 场景 ${index + 1}`,
      source,
      target,
      mode,
      cursor,
      batch,
      channel,
      timeout,
      STRATEGIES[index % STRATEGIES.length],
      `${error[0]} -> ${error[3]}`,
      index % 7 === 0 ? "ATTENTION_REQUIRED" : "SUCCEEDED",
    ];
  });
  const diagnosisRows = incidentCases(scope, topic, 240).map((item) => [
    item.caseId,
    item.taskId,
    item.executionId,
    item.objectId,
    item.traceId,
    item.incidentId,
    item.errorCode,
    item.failureReason,
    item.evidenceSource,
    item.observedAt,
    item.confidence,
    item.confidenceBasis,
    item.repairAction,
    item.risk,
    item.requiresPrivilege,
    item.requiredPermission,
    item.impact,
    item.rollback,
    item.verification,
    item.finalState,
  ]);
  return {
    headers: ["任务编号", "执行编号", "追踪编号", "场景", "来源", "目标", "同步模式", "游标/分区", "batch_size", "channel", "timeout_s", "映射/质量策略", "恢复策略", "预期结果"],
    rows,
    diagnosisHeaders: ["案例编号", "任务编号", "执行编号", "对象编号", "追踪编号", "事故编号", "错误码", "失败原因", "证据来源", "发生时间", "可信度", "可信依据", "修复动作", "风险", "需要特权", "所需权限", "影响", "回滚", "验证", "最终状态"],
    diagnosisRows,
    notes: {
      scope: scope.label,
      anchor: `${scope.key}:${topic.slug}`,
      code: topic.code,
      rule: "案例参数只能在首次授权盒内调整；凭据、权限、DDL、覆盖数据和扩大范围必须退出 Loop。",
    },
  };
}

/**
 * 给任意工作簿补充统一的“失败诊断”工作表数据。
 *
 * 主数据可以是成功参数、字段映射、调度计划或测试结果；诊断数据始终保留相同的 20 个治理字段，
 * 便于维护者把任务、日志、事故、自动修复和最终状态按 ID 连接起来。
 */
function withWorkbookDiagnosis(scope, topic, dataset, count = 240) {
  const diagnosisRows = incidentCases(scope, topic, count).map((item) => [
    item.caseId,
    item.taskId,
    item.executionId,
    item.objectId,
    item.traceId,
    item.incidentId,
    item.errorCode,
    item.failureReason,
    item.evidenceSource,
    item.observedAt,
    item.confidence,
    item.confidenceBasis,
    item.repairAction,
    item.risk,
    item.requiresPrivilege,
    item.requiredPermission,
    item.impact,
    item.rollback,
    item.verification,
    item.finalState,
  ]);
  return {
    ...dataset,
    diagnosisHeaders: ["案例编号", "任务编号", "执行编号", "对象编号", "追踪编号", "事故编号", "错误码", "失败原因", "证据来源", "发生时间", "可信度", "可信依据", "修复动作", "风险", "需要特权", "所需权限", "影响", "回滚", "验证", "最终状态"],
    diagnosisRows,
  };
}

/** 生成成功任务、字段映射、调度、测试和事故台账的高密度数据集。 */
export function buildEnterpriseWorkbookDataset(scope, topic) {
  if (TASK_MODES[topic.slug]) return taskScenarioDataset(scope, topic, TASK_MODES[topic.slug]);
  const common = {
    scope: scope.label,
    anchor: `${scope.key}:${topic.slug}`,
    code: topic.code,
  };
  if (topic.slug === "workbook-success-task-parameters") {
    const modes = ["FULL", "INCREMENTAL", "CDC", "FILE", "API", "KAFKA", "OBJECT_STORAGE"];
    return withWorkbookDiagnosis(scope, topic, {
      headers: ["任务编号", "配置版本", "同步模式", "来源", "目标", "batch_size", "channel", "timeout_s", "checkpoint", "读取行", "写入行", "脏数据", "耗时毫秒", "状态"],
      rows: Array.from({ length: 240 }, (_, index) => {
        const ids = correlationFacts(scope, index);
        const read = 8000 + index * 1273;
        return [ids.taskId, ids.configVersion, modes[index % modes.length], SOURCES[index % SOURCES.length], TARGETS[(index + 1) % TARGETS.length], Math.max(100, scope.batchSize - (index % 4) * 50), Math.max(1, scope.channelCount - (index % 3)), scope.timeoutSeconds + (index % 3) * 30, `checkpoint-${String(318 + index * 13).padStart(6, "0")}`, read, read, 0, 40000 + index * 1370, "SUCCEEDED"];
      }),
      notes: { ...common, rule: "自动恢复优先比较最近成功配置；不得自动提高 batch_size 或 channel。" },
    });
  }
  if (topic.slug === "workbook-field-mapping-cases") {
    const fields = ["order_id", "region_code", "order_amount", "customer_id", "occurred_at", "currency", "status", "source_system", "created_at", "updated_at", "product_id", "quantity", "discount_amount", "tax_amount", "shipping_address", "email_hash", "phone_masked", "country_code", "city_code", "postal_code"];
    const types = ["varchar(64)", "varchar(16)", "decimal(18,2)", "bigint", "timestamp", "char(3)", "varchar(32)"];
    return withWorkbookDiagnosis(scope, topic, {
      headers: ["来源字段", "目标字段", "来源类型", "目标类型", "允许为空", "静态默认值", "转换", "历史成功版本", "自动修复策略", "升级条件"],
      rows: Array.from({ length: 240 }, (_, index) => {
        const field = fields[index % fields.length];
        const target = index % 9 === 1 ? `${field}_normalized` : field;
        const sourceType = types[index % types.length];
        const nullable = index % 5 !== 0;
        const defaultValue = !nullable && ["region_code", "country_code", "status"].includes(field) ? "APPROVED-UNKNOWN" : "";
        return [field, target, sourceType, sourceType, nullable, defaultValue, index % 4 === 0 ? "trim+normalize" : "identity", `cfg-v${20 + (index % 8)}`, defaultValue ? "使用已批准静态默认值" : "唯一映射且无损时自动修复", index % 11 === 0 ? "类型有损或映射歧义" : "DDL/权限/覆盖数据"];
      }),
      notes: { ...common, rule: "禁止自动放宽非空、删除外键、截断数据或执行 DDL；歧义字段映射退出 Loop。" },
    });
  }
  if (topic.slug === "workbook-schedule-retry-cases") {
    return withWorkbookDiagnosis(scope, topic, {
      headers: ["计划编号", "任务类型", "cron", "时区", "错过触发策略", "最大恢复循环", "初始退避秒", "最大退避秒", "超时秒", "并发策略", "非工作时间策略", "状态"],
      rows: Array.from({ length: 240 }, (_, index) => [`SCH-${String(index + 1).padStart(4, "0")}`, ["全量", "增量", "CDC", "文件", "API"][index % 5], index % 3 === 0 ? `0 ${index % 60} 2 * * ?` : `0 ${index % 60} * * * ?`, "Asia/Shanghai", index % 4 === 0 ? "FIRE_ONCE_NOW" : "SKIP", Math.max(1, Math.min(scope.retryLimit, 1 + (index % 5))), 30 + (index % 4) * 30, 300 + (index % 5) * 60, scope.timeoutSeconds + (index % 4) * 30, index % 2 === 0 ? "同任务串行" : "跳过重叠触发", index % 6 === 0 ? "越权退出并通知" : "授权盒内自动恢复", "ENABLED"]),
      notes: { ...common, rule: "每轮必须有新诊断证据；达到最大循环、预算或越权条件立即停止。" },
    });
  }
  if (topic.slug === "workbook-test-result-matrix") {
    return withWorkbookDiagnosis(scope, topic, {
      headers: ["测试编号", "测试域", "场景", "用例数", "通过数", "失败数", "通过率", "P50毫秒", "P95毫秒", "门槛", "结论"],
      rows: Array.from({ length: 240 }, (_, index) => {
        const total = 20 + (index % 8) * 4;
        const failed = index % 9 === 0 ? 1 : 0;
        return [`TEST-${String(index + 1).padStart(3, "0")}`, TEST_MATRIX[index % TEST_MATRIX.length][0], TEST_MATRIX[index % TEST_MATRIX.length][1], total, total - failed, failed, Number(((total - failed) / total).toFixed(4)), 80 + index * 13, 300 + index * 47, index % 9 === 0 ? "需达到质量门禁" : "通过率=100%", failed ? "REVIEW" : "PASS"];
      }),
      notes: { ...common, rule: "总体门禁同时覆盖质量、治理、拒答、范围、性能、恢复和不可逆副作用。" },
    });
  }
  if (topic.slug === "workbook-incident-repair-ledger") {
    const cases = incidentCases(scope, topic, 320);
    return withWorkbookDiagnosis(scope, topic, {
      headers: ["事故编号", "任务编号", "执行编号", "对象编号", "追踪编号", "恢复案例", "错误码", "证据来源", "发生时间", "可信度", "根因", "修复动作", "风险", "循环", "回滚", "验证结果", "最终状态"],
      rows: cases.map((item) => [item.incidentId, item.taskId, item.executionId, item.objectId, item.traceId, item.recoveryCaseId, item.errorCode, item.evidenceSource, item.observedAt, item.confidence, item.rootCause, item.repairAction, item.risk, item.cycle, item.rollback, item.verification, item.finalState]),
      notes: { ...common, rule: "证据必须附来源、时间和可信度；高风险动作不自动执行。" },
    }, 320);
  }
  throw new Error(`没有为工作簿主题配置数据集：${topic.slug}`);
}

/** 生成统一字段说明，工作簿中的每一列都可以被维护者和 RAG 解释。 */
export function workbookFieldDictionary(headers) {
  return headers.map((header, index) => [
    header,
    /数量|行|毫秒|秒|batch|channel|循环|可信度|率/.test(header) ? "number" : "string",
    `第 ${index + 1} 列，描述 ${header}；用于任务案例筛选、对比和证据引用。`,
    index === 0 ? "主标识不能为空且在工作簿内唯一" : "按主题合同校验类型与允许值",
    /凭据|密码|密钥|原文/.test(header) ? "禁止保存" : "internal/synthetic",
  ]);
}

/** 根据标题长度和语义给出稳定列宽，避免大量案例导致工作簿渲染过宽或截断。 */
export function workbookColumnWidth(header) {
  if (/编号|版本|状态|模式|风险|循环|结论/.test(header)) return 18;
  if (/时间|checkpoint|游标|分区|来源|目标/.test(header)) return 24;
  if (/策略|根因|动作|验证|场景|门槛|升级/.test(header)) return 34;
  if (/batch|channel|timeout|行|毫秒|秒|率|数量|通过|失败|可信度/.test(header)) return 14;
  return 22;
}

/** 为结构化语料生成可比较的 ISO 时间，不依赖本机时区。 */
function syntheticTime(index, stepMinutes = 7) {
  return new Date(Date.UTC(2026, 7, 10, 0, index * stepMinutes)).toISOString();
}

/** 生成 TXT 长篇参考资料，包含流程、错误码、案例和治理边界。 */
function buildTxtPayload(scope, topic, anchor) {
  const lines = [topic.title, SYNTHETIC_NOTICE, `精确码：${topic.code}`, `独立锚点：${anchor}`, `范围：${scope.label}`, "", "一、目的与适用角色", topic.summary, "适用角色：普通用户、项目负责人、数据工程师、运维人员、审计人员和平台管理员。", "", "二、标准排查顺序"];
  OPERATIONS_CHECKS.forEach((row, index) => lines.push(`${index + 1}. ${row[0]}：检查 ${row[1]}；关联 ${row[2]}；处置原则为 ${row[3]}。`));
  lines.push("", "三、错误码与恢复决策");
  ERROR_CATALOG.forEach((row) => lines.push(`${row[0]} | 根因=${row[1]} | 证据=${row[2]} | 动作=${row[3]}`));
  lines.push("", "四、治理边界", "低风险动作可在首次授权盒内自动执行；凭据、权限、DDL、删除、覆盖、扩大范围和不可逆转换必须退出 Loop。", "退出时必须返回根因、证据来源、时间、可信度、所需权限、操作步骤、影响、回滚和验证方法。", "", "五、证据要求");
  ["结构化日志", "指标", "配置差异", "连接器能力", "Runbook", "历史事故", "任务案例", "对象台账", "审批事实", "最终验证"].forEach((item, index) => lines.push(`${index + 1}. ${item}：记录 sourceUri、observedAt、confidence、confidenceBasis 和 sourceStatus。`));
  lines.push("", "六、关联事故与任务案例（200 条）");
  incidentCases(scope, topic, 200).forEach((item) => lines.push(
    `${item.caseId} | taskId=${item.taskId} | executionId=${item.executionId} | traceId=${item.traceId} | errorCode=${item.errorCode} | 失败原因=${item.failureReason} | 证据=${item.evidenceSource}@${item.observedAt}/${item.confidence} | 修复=${item.repairAction} | 最终状态=${item.finalState}`,
  ));
  return `${lines.join("\n")}\n`;
}

/** 生成 JSON 快照；不同主题共享可审计信封，但记录内容保持主题差异。 */
function buildJsonPayload(scope, topic, entry, anchor) {
  const records = Array.from({ length: 240 }, (_, index) => {
    const ids = correlationFacts(scope, index);
    const error = ERROR_CATALOG[index % ERROR_CATALOG.length];
    return {
      recordId: `${topic.code}-${String(index + 1).padStart(3, "0")}`,
      ...ids,
      component: OPERATIONS_CHECKS[index % OPERATIONS_CHECKS.length][0],
      errorCode: error[0],
      currentValue: index % 2 === 0 ? scope.batchSize : scope.timeoutSeconds,
      lastSuccessfulValue: index % 2 === 0 ? Math.max(50, scope.batchSize - 50) : scope.timeoutSeconds,
      decision: error[3],
      observedAt: syntheticTime(index),
      sourceUri: `synthetic://datasmart-govern/${topic.slug}/${index + 1}`,
      confidence: Number((0.9 + (index % 10) / 100).toFixed(2)),
      confidenceBasis: "SYNTHETIC_STRUCTURED_FACT",
      sourceStatus: "COMPLETE",
    };
  });
  return `${JSON.stringify({
    synthetic: true,
    notice: SYNTHETIC_NOTICE,
    schemaVersion: "datasmart.rag-structured-corpus.v2",
    documentId: entry.documentId,
    artifactCode: topic.code,
    retrievalAnchor: anchor,
    scope: { tenantId: scope.tenantId, projectId: scope.projectId, workspaceKey: scope.workspaceKey },
    title: topic.title,
      summary: topic.summary,
    apiContracts: topic.slug === "api-contract-snapshot" ? apiContractDetails(topic) : undefined,
    records,
  }, null, 2)}\n`;
}

/** 生成 JSONL 事件/任务流水，每行都可独立摄取并保留来源、时间和可信度。 */
function buildJsonlPayload(scope, topic, anchor) {
  const eventTypes = ["TASK_CREATED", "PRECHECK_SUCCEEDED", "RAG_SEARCH_SELECTED", "PLAN_APPROVED", "WORKER_STARTED", "OBJECT_FAILED", "RECOVERY_DIAGNOSED", "LOW_RISK_REPAIR_APPLIED", "FAILED_OBJECT_REPLAYED", "POST_RECOVERY_VERIFIED"];
  return `${Array.from({ length: 600 }, (_, index) => {
    const caseIndex = Math.floor(index / 2);
    const ids = correlationFacts(scope, caseIndex);
    const error = ERROR_CATALOG[caseIndex % ERROR_CATALOG.length];
    return JSON.stringify({
      synthetic: true,
      artifactCode: topic.code,
      retrievalAnchor: anchor,
      recordId: `${topic.code}-${String(index + 1).padStart(4, "0")}`,
      tenantId: scope.tenantId,
      projectId: scope.projectId,
      ...ids,
      cycle: 1 + (index % Math.max(1, scope.retryLimit)),
      eventType: eventTypes[index % eventTypes.length],
      errorCode: error[0],
      decision: error[3],
      sourceUri: `synthetic://datasmart-govern/${topic.slug}/record-${index + 1}`,
      occurredAt: syntheticTime(index, 3),
      confidence: Number((0.9 + (index % 10) / 100).toFixed(2)),
      confidenceBasis: "SYNTHETIC_EVENT_CORRELATION",
      sourceStatus: "COMPLETE",
    });
  }).join("\n")}\n`;
}

/** 生成 CSV 记录，覆盖任务、连接器、字段画像或告警的共同可审计字段。 */
function buildCsvPayload(scope, topic, anchor) {
  const header = "record_id,artifact_code,retrieval_anchor,task_id,execution_id,object_id,trace_id,incident_id,recovery_case_id,component,error_code,failure_reason,current_value,last_success_value,action,status,observed_at,source_uri,confidence,confidence_basis";
  const rows = Array.from({ length: 600 }, (_, index) => {
    const ids = correlationFacts(scope, index);
    const error = ERROR_CATALOG[index % ERROR_CATALOG.length];
    return [
      `${topic.code}-${String(index + 1).padStart(3, "0")}`,
      topic.code,
      anchor,
      ids.taskId,
      ids.executionId,
      ids.objectId,
      ids.traceId,
      ids.incidentId,
      ids.recoveryCaseId,
      OPERATIONS_CHECKS[index % OPERATIONS_CHECKS.length][0].replaceAll(",", " "),
      error[0],
      `"${error[1]}：${error[2]}"`,
      scope.batchSize + index,
      scope.batchSize,
      `"${error[3].replaceAll('"', "")}"`,
      index % 7 === 0 ? "ATTENTION_REQUIRED" : "RECOVERED",
      syntheticTime(index, 11),
      `synthetic://datasmart-govern/${topic.slug}/${index + 1}`,
      (0.9 + (index % 10) / 100).toFixed(2),
      "SYNTHETIC_CORRELATED_RECORD",
    ].join(",");
  });
  return `${[header, ...rows].join("\n")}\n`;
}

/** 生成结构化 Worker/Kafka 日志，便于按 trace、任务、对象和错误码检索。 */
function buildLogPayload(scope, topic, anchor) {
  const phases = ["OBJECT_FAILED", "RECOVERY_DIAGNOSED", "REPAIR_APPLIED_OR_HANDOFF", "POST_RECOVERY_VERIFIED"];
  const rows = Array.from({ length: 1200 }, (_, index) => {
    const caseIndex = Math.floor(index / phases.length);
    const phaseIndex = index % phases.length;
    const ids = correlationFacts(scope, caseIndex);
    const error = ERROR_CATALOG[caseIndex % ERROR_CATALOG.length];
    const manual = ["AUTHENTICATION_FAILED", "PERMISSION_DENIED", "DDL_REQUIRED"].includes(error[0]) || caseIndex % 23 === 0;
    const level = phaseIndex === 0 ? "ERROR" : phaseIndex === 1 ? "WARN" : "INFO";
    const state = phaseIndex === 0 ? "FAILED" : phaseIndex === 3 ? (manual ? "ATTENTION_REQUIRED" : "RECOVERED") : "RECOVERING";
    return `${syntheticTime(index, 2)} level=${level} phase=${phases[phaseIndex]} traceId=${ids.traceId} taskId=${ids.taskId} executionId=${ids.executionId} objectId=${ids.objectId} incidentId=${ids.incidentId} recoveryCaseId=${ids.recoveryCaseId} component=${OPERATIONS_CHECKS[caseIndex % OPERATIONS_CHECKS.length][0].replaceAll(" ", "_")} errorCode=${error[0]} state=${state} retryable=${!manual} configVersion=${ids.configVersion} lastSuccessfulConfigVersion=${ids.lastSuccessfulConfigVersion} sourceUri=synthetic://datasmart-govern/correlated/${scope.key}/execution/${ids.executionId} confidence=${(0.9 + (caseIndex % 10) / 100).toFixed(2)} confidenceBasis=SYNTHETIC_LOG_CONFIG_AND_RUNBOOK_CORROBORATION artifactCode=${topic.code} retrievalAnchor=${anchor} message="${error[1]}；证据=${error[2]}；处置=${manual ? "退出Loop并返回人工操作指引" : error[3]}"`;
  });
  return `${SYNTHETIC_NOTICE}\n${rows.join("\n")}\n`;
}

/** 生成数据库持久化快照，表和数据均为 synthetic 命名，不会被误用于生产迁移。 */
function buildSqlPayload(scope, topic, anchor) {
  const statements = [
    `-- ${topic.title}`,
    `-- ${SYNTHETIC_NOTICE}`,
    `-- 精确码：${topic.code}`,
    `-- 独立锚点：${anchor}`,
    "BEGIN;",
  ];
  for (let index = 0; index < 320; index += 1) {
    const ids = correlationFacts(scope, index);
    const error = ERROR_CATALOG[index % ERROR_CATALOG.length];
    statements.push(`INSERT INTO synthetic_recovery_case (case_id, tenant_id, project_id, task_id, execution_id, object_id, trace_id, incident_id, cycle, max_cycles, case_state, reason_code, created_at) VALUES ('${ids.recoveryCaseId}', '${scope.tenantId}', '${scope.projectId}', '${ids.taskId}', '${ids.executionId}', '${ids.objectId}', '${ids.traceId}', '${ids.incidentId}', ${1 + (index % Math.max(1, scope.retryLimit))}, ${scope.retryLimit}, '${index % 6 === 0 ? "ATTENTION_REQUIRED" : "RECOVERED"}', '${error[0]}', '${syntheticTime(index, 5)}');`);
    statements.push(`INSERT INTO synthetic_evidence_record (case_id, source_uri, observed_at, confidence, confidence_basis, source_status) VALUES ('${ids.recoveryCaseId}', 'synthetic://datasmart-govern/correlated/${scope.key}/execution/${ids.executionId}', '${syntheticTime(index, 5)}', ${(0.9 + (index % 10) / 100).toFixed(2)}, 'SYNTHETIC_SQL_SNAPSHOT', 'COMPLETE');`);
    statements.push(`INSERT INTO synthetic_recovery_action (case_id, action_code, risk_level, action_state, rollback_instruction, verification_instruction) VALUES ('${ids.recoveryCaseId}', '${index % 6 === 0 ? "MANUAL_HANDOFF" : "REPLAY_FAILED_OBJECTS"}', '${index % 6 === 0 ? "HIGH" : "LOW"}', '${index % 6 === 0 ? "BLOCKED" : "SUCCEEDED"}', '恢复 ${ids.lastSuccessfulConfigVersion}', '检查 PRECHECK_AGENT 与 MONITOR_AGENT durable fact');`);
  }
  statements.push("COMMIT;");
  return `${statements.join("\n")}\n`;
}

/** 按文件格式生成结构化正文，并确保所有主题都包含精确码和范围锚点。 */
export function buildEnterpriseStructuredPayload(scope, topic, entry) {
  const anchor = `${scope.key}:${topic.slug}`;
  if (topic.format === "txt") return buildTxtPayload(scope, topic, anchor);
  if (topic.format === "json") return buildJsonPayload(scope, topic, entry, anchor);
  if (topic.format === "jsonl") return buildJsonlPayload(scope, topic, anchor);
  if (topic.format === "csv") return buildCsvPayload(scope, topic, anchor);
  if (topic.format === "log") return buildLogPayload(scope, topic, anchor);
  if (topic.format === "sql") return buildSqlPayload(scope, topic, anchor);
  throw new Error(`没有为结构化主题配置格式生成器：${topic.slug}.${topic.format}`);
}
