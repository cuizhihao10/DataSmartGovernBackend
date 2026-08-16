/**
 * DataSmart Govern RAG 语料的语义化内容生成库。
 *
 * 本模块最重要的约束不是“每份文件都很长”，而是“每类文件只记录它应该记录的内容”：
 * 用户手册讲用户操作，管理员手册讲管理操作，接口文档讲真实合同，运维手册讲标准作业，事故文档
 * 讲事故，测试报告讲测试。只有任务案例、事故、恢复和日志类资料才允许出现失败根因与修复记录。
 */

import { apiContractsForTopic } from "./rag-api-contract-inventory.mjs";

const SYNTHETIC_NOTICE = "原创合成评测资料，不含真实客户、个人、凭据或生产数据";

const API_TOPIC_SLUGS = new Set([
  "reference-api-websocket",
  "reference-authentication-api",
  "reference-agent-api",
  "reference-task-api",
  "reference-data-sync-api",
  "reference-recovery-api",
  "reference-websocket-events",
]);

const INCIDENT_TOPIC_SLUGS = new Set([
  "record-operations-incident",
  "postmortem-schema-drift",
  "postmortem-foreign-key",
  "postmortem-rate-limit",
  "postmortem-checkpoint",
  "postmortem-kafka-backlog",
]);

const TEST_TOPIC_SLUGS = new Set([
  "report-platform-test",
  "report-e2e-test",
  "report-performance-test",
  "report-rag-agent-evaluation",
]);

const SPECIALIZED_RUNBOOK_SLUGS = new Set([
  "manual-observability",
  "manual-kafka-operations",
  "manual-postgresql-pgvector",
  "manual-model-provider",
  "manual-backup-disaster-recovery",
  "manual-upgrade-rollback",
]);

const ERROR_CATALOG = [
  ["CONNECTION_TIMEOUT", "网络或端点超时", "连接耗时、DNS、目标健康", "有界增加超时或等待端点恢复"],
  ["AUTHENTICATION_FAILED", "凭据失效或引用错误", "认证响应与凭据引用版本", "退出自治并要求有权限主体轮换凭据"],
  ["PERMISSION_DENIED", "资源动作未授权", "actor、resource、action、审批事实", "退出自治并说明所需权限"],
  ["RATE_LIMIT_EXCEEDED", "来源或目标端限流", "429、限流头、连接器容量", "降低并发和批量后有界退避"],
  ["SCHEMA_DRIFT_DETECTED", "来源结构发生变化", "schema 指纹与元数据差异", "刷新元数据；唯一兼容映射才修复"],
  ["FIELD_MAPPING_MISSING", "目标字段缺少映射", "源目标字段与历史成功映射", "唯一映射可修复，歧义时退出"],
  ["NOT_NULL_VIOLATION", "目标非空字段收到空值", "错误字段、字段画像、已批准默认值", "使用已批准默认值，不放宽约束"],
  ["DATA_TYPE_MISMATCH", "源目标类型不兼容", "类型、精度、长度与样本统计", "无损转换可修复，有损转换退出"],
  ["NUMERIC_OVERFLOW", "数值超过目标精度", "最值、precision 与 scale", "禁止截断，转人工扩容或修改映射"],
  ["STRING_TRUNCATION_RISK", "字符串超过目标长度", "最大长度与超限行数", "禁止静默截断，转人工确认影响"],
  ["FOREIGN_KEY_MISSING", "父记录尚未存在", "约束名、父子对象与执行 DAG", "调整依赖顺序并重放父子对象"],
  ["UNIQUE_CONSTRAINT_VIOLATION", "业务键或幂等键重复", "约束名、键摘要与历史回执", "仅确认幂等重放，业务冲突退出"],
  ["CHECKPOINT_NOT_FOUND", "没有可恢复位点", "对象台账与 checkpoint 表", "仅在授权允许时从安全起点重跑"],
  ["CHECKPOINT_STALE", "位点与已提交结果不一致", "worker 回执、目标提交与位点时间", "选择最近已确认位点并验证去重"],
  ["KAFKA_BACKLOG_HIGH", "消费者处理速度低于生产速度", "group、partition、lag 与处理时延", "隔离坏消息并按容量策略处理"],
  ["OUTBOX_DELIVERY_TIMEOUT", "事务事实未及时投递", "outbox 状态、attempt 与 broker 回执", "重投未交付 outbox，不重复业务事务"],
  ["CONNECTOR_VERSION_INCOMPATIBLE", "连接器版本不支持配置", "版本、能力快照与配置字段", "回滚兼容配置或人工升级连接器"],
  ["TARGET_CAPACITY_EXCEEDED", "目标连接或磁盘达到阈值", "容量指标、等待事件与配额", "降低负载并等待恢复"],
  ["DIRTY_RECORD_THRESHOLD_EXCEEDED", "脏数据超过任务阈值", "规则结果、坏行引用与阈值", "隔离坏行，超过停止阈值时退出"],
  ["DDL_REQUIRED", "需要修改目标结构", "元数据差异与目标约束", "高风险动作，转人工执行与验证"],
];

const USER_OPERATION_BLUEPRINTS = [
  ["登录并建立会话", "登录页", "ORDINARY_USER", "SESSION/CREATE", ["输入账号并完成身份验证", "阅读当前租户和角色", "确认会话有效期", "进入工作台"], "进入个人工作台并取得受限会话"],
  ["切换当前项目", "顶部项目选择器", "ORDINARY_USER", "PROJECT/VIEW", ["打开项目选择器", "搜索本人已加入项目", "选择目标项目", "等待页面重新加载范围数据"], "所有列表和 Agent 请求切换到新项目范围"],
  ["查看平台概览", "工作台", "ORDINARY_USER", "DASHBOARD/VIEW", ["进入工作台", "选择时间窗口", "查看任务和质量摘要", "进入异常或任务明细"], "获得当前项目的低敏运行概览"],
  ["创建同步任务草稿", "数据同步/新建任务", "PROJECT_OWNER", "SYNC_TASK/CREATE", ["填写名称和负责人", "选择来源与目标数据源", "选择同步模式", "保存草稿"], "生成 DRAFT 状态任务且不产生执行副作用"],
  ["校验创建向导步骤", "数据同步/新建任务", "PROJECT_OWNER", "SYNC_TASK/CREATE", ["完成当前步骤字段", "点击下一步", "阅读阻断项和提示", "修正后重新校验"], "当前步骤达到进入下一步的条件"],
  ["发现可同步对象", "数据同步/对象选择", "PROJECT_OWNER", "SYNC_TASK_METADATA/DISCOVER", ["选择来源数据源", "设置对象筛选条件", "发起只读发现", "勾选需要同步的对象"], "获得当前权限范围内的表、文件或 Topic 清单"],
  ["获取字段映射建议", "数据同步/字段映射", "PROJECT_OWNER", "SYNC_TASK_METADATA/SUGGEST", ["选择源对象与目标对象", "刷新两端元数据", "查看建议映射", "逐项确认或修改"], "形成待预检的字段映射草稿"],
  ["检查自定义 SQL", "数据同步/SQL 模式", "PROJECT_OWNER", "SYNC_TASK/CREATE", ["填写只读 SELECT", "点击 SQL 检查", "查看输出列和安全提示", "确认后进入字段映射"], "只返回列定义和低敏诊断，不返回原始数据"],
  ["运行任务预检", "数据同步/预检", "PROJECT_OWNER", "SYNC_TASK/PRECHECK", ["打开任务草稿", "确认数据源和映射", "运行预检", "查看连接、约束与容量结果"], "生成可审计预检结果，不自动发布任务"],
  ["发布同步任务", "数据同步/任务详情", "PROJECT_OWNER", "SYNC_TASK/PUBLISH", ["确认预检通过", "核对调度和恢复策略", "填写发布说明", "提交发布"], "任务配置形成不可变发布版本"],
  ["手动运行任务", "数据同步/任务详情", "PROJECT_OWNER", "SYNC_TASK/RUN", ["打开已发布任务", "核对执行范围", "点击立即运行", "查看新 execution"], "创建一次受治理的 MANUAL execution"],
  ["终止运行中任务", "数据同步/执行详情", "PROJECT_OWNER", "SYNC_TASK/TERMINATE", ["打开运行中的 execution", "阅读终止影响", "填写终止原因", "确认终止"], "停止后续工作单元并形成终止审计"],
  ["查看执行详情", "数据同步/执行详情", "ORDINARY_USER", "SYNC_EXECUTION/VIEW", ["打开任务历史", "选择 execution", "查看读写量和状态", "下钻对象台账"], "展示服务端权威执行状态和统计"],
  ["查看统一生命周期图", "数据同步/执行详情", "ORDINARY_USER", "SYNC_EXECUTION/VIEW", ["打开 execution", "切换全链路视图", "查看节点来源和时间", "按证据引用下钻"], "展示用户目标到最终验证的只读投影"],
  ["查看对象执行台账", "数据同步/执行详情", "ORDINARY_USER", "SYNC_EXECUTION/VIEW", ["进入对象页签", "按状态筛选", "查看 attempt 与 checkpoint", "导出低敏列表"], "获得表、分片、文件或分区级状态"],
  ["查看执行日志", "数据同步/执行详情", "ORDINARY_USER", "SYNC_EXECUTION/VIEW_LOG", ["选择 execution", "设置时间和级别", "按 trace 或错误码过滤", "复制低敏引用"], "获得脱敏结构化日志，不展示凭据或原始数据"],
  ["查看恢复状态", "数据同步/执行详情", "ORDINARY_USER", "SYNC_EXECUTION/VIEW", ["进入自治恢复页签", "查看 cycle 和动作", "核对证据来源", "查看最终验证"], "展示服务端已记录的恢复事实"],
  ["查看同步事故", "数据同步/事故中心", "ORDINARY_USER", "SYNC_INCIDENT/VIEW", ["打开事故中心", "按任务和级别筛选", "查看时间线", "关注后续更新"], "读取当前项目可见的事故记录"],
  ["导出任务定义", "数据同步/任务列表", "PROJECT_OWNER", "SYNC_TASK/EXPORT", ["设置筛选条件", "选择 CSV 或 XLSX", "提交导出", "下载低敏任务定义"], "获得不含连接串、凭据和完整 SQL 的定义文件"],
  ["导入任务定义", "数据同步/任务列表", "PROJECT_OWNER", "SYNC_TASK/IMPORT", ["上传 CSV 或 XLSX", "先执行 dry-run", "处理字段校验提示", "确认创建草稿"], "批量创建受服务端状态机控制的任务草稿"],
  ["克隆同步任务", "数据同步/任务详情", "PROJECT_OWNER", "SYNC_TASK/CREATE", ["打开来源任务", "选择克隆", "修改名称和目标范围", "保存新草稿"], "生成独立 taskId，不复制运行状态"],
  ["维护任务分组", "数据同步/任务分组", "PROJECT_OWNER", "SYNC_TASK_GROUP/MANAGE", ["打开分组树", "创建或选择分组", "调整任务归属", "保存排序"], "更新项目内的任务组织结构"],
  ["查看质量概览", "数据质量/概览", "ORDINARY_USER", "QUALITY_OVERVIEW/VIEW", ["选择项目和时间", "查看规则与问题趋势", "按对象下钻", "打开报告"], "展示当前范围的质量统计"],
  ["查看质量报告", "数据质量/报告", "ORDINARY_USER", "QUALITY_REPORT/VIEW", ["筛选任务或对象", "选择报告版本", "查看规则结果", "导出低敏报告"], "获得可审计质量报告"],
  ["申请创建项目", "项目管理/我的申请", "ORDINARY_USER", "PROJECT_CREATION_REQUEST/CREATE", ["填写项目用途", "选择预期租户", "提交申请", "关注审批状态"], "创建待审批项目申请"],
  ["申请加入项目", "项目管理/加入项目", "ORDINARY_USER", "PROJECT_JOIN_REQUEST/CREATE", ["搜索可申请项目", "填写申请理由", "提交申请", "查看审批进度"], "创建项目成员资格申请"],
  ["查看我的审批请求", "审批中心/我的申请", "ORDINARY_USER", "APPROVAL/VIEW_SELF", ["进入审批中心", "按类型筛选", "查看范围与有效期", "取消尚未处理的申请"], "展示本人发起的审批记录"],
  ["创建 Agent 会话", "Agent 助手", "ORDINARY_USER", "AI_RUNTIME/CREATE_SESSION", ["选择项目", "新建会话", "填写会话标题", "开始描述目标"], "创建仅属于当前用户和项目的 Agent 会话"],
  ["提交 Agent 目标", "Agent 助手", "ORDINARY_USER", "AI_RUNTIME/PLAN", ["描述同步目标", "补充来源、目标与时效", "提交规划", "观察 Specialist 进度"], "生成待确认计划或需要澄清的问题"],
  ["确认 Agent 计划", "Agent 助手", "PROJECT_OWNER", "AI_RUNTIME/EXECUTE", ["查看 ToolPlan", "核对范围和风险", "完成首次确认", "观察受治理执行"], "在授权盒内启动执行"],
  ["查询 RAG 证据", "Agent 助手/知识检索", "ORDINARY_USER", "AI_KNOWLEDGE/QUERY", ["输入问题", "确认当前项目", "提交检索", "检查引用来源"], "返回范围内证据或明确拒答"],
  ["查看 Specialist 事实", "Agent 助手/执行详情", "ORDINARY_USER", "AI_RUNTIME/VIEW", ["打开历史会话", "选择一次 Run", "展开 Specialist", "查看低敏 durable fact"], "展示六 Specialist 的可审计执行事实"],
  ["停止 Agent 运行", "Agent 助手", "ORDINARY_USER", "AI_RUNTIME/CANCEL", ["打开运行中会话", "点击停止", "确认取消", "等待终态回执"], "停止后续规划或执行，不删除历史事实"],
  ["置顶或归档会话", "Agent 助手/历史", "ORDINARY_USER", "AI_RUNTIME/MANAGE_SELF", ["选择本人会话", "设置置顶或归档", "刷新列表", "确认消息仍可恢复"], "只改变个人会话展示状态"],
  ["查看通知与实时进度", "通知中心", "ORDINARY_USER", "AI_RUNTIME/SUBSCRIBE", ["建立实时连接", "选择项目范围", "查看顺序事件", "断线后按游标恢复"], "持续接收范围内低敏状态事件"],
];

const ADMIN_OPERATION_BLUEPRINTS = [
  ["开通租户", "PLATFORM_ADMINISTRATOR", "TENANT/CREATE", "创建租户基本信息、默认角色和数据范围基线"],
  ["更新租户资料", "TENANT_ADMINISTRATOR", "TENANT/UPDATE", "维护租户名称、联系人引用、配额和状态说明"],
  ["暂停或恢复租户", "PLATFORM_ADMINISTRATOR", "TENANT/CHANGE_STATE", "按审计原因暂停或恢复租户访问"],
  ["关闭租户", "PLATFORM_ADMINISTRATOR", "TENANT/CLOSE", "执行关闭前检查并保留审计与数据保留策略"],
  ["创建项目", "TENANT_ADMINISTRATOR", "PROJECT/CREATE", "创建项目并指定负责人和初始范围"],
  ["审批项目创建申请", "TENANT_ADMINISTRATOR", "PROJECT_CREATION_REQUEST/APPROVE", "核对用途、配额和负责人后决定申请"],
  ["审批加入项目申请", "PROJECT_OWNER", "PROJECT_JOIN_REQUEST/APPROVE", "核对申请主体与目标角色后决定成员资格"],
  ["维护项目成员", "PROJECT_OWNER", "PROJECT_MEMBERSHIP/UPDATE", "调整成员角色、状态和数据范围"],
  ["禁用项目成员", "PROJECT_OWNER", "PROJECT_MEMBERSHIP/DISABLE", "撤销后续访问并保留历史审计"],
  ["查询权限矩阵", "TENANT_ADMINISTRATOR", "SYSTEM_SETTING/VIEW", "查看角色、菜单、路由策略和数据范围"],
  ["创建路由策略", "TENANT_ADMINISTRATOR", "ROUTE_POLICY/CREATE", "为角色配置 API 路径与方法的允许或拒绝规则"],
  ["更新路由策略", "TENANT_ADMINISTRATOR", "ROUTE_POLICY/UPDATE", "修改资源动作映射并触发授权缓存失效"],
  ["启停路由策略", "TENANT_ADMINISTRATOR", "ROUTE_POLICY/CHANGE_STATE", "保留策略记录并控制是否参与判定"],
  ["维护数据范围策略", "TENANT_ADMINISTRATOR", "DATA_SCOPE/UPDATE", "配置 SELF、PROJECT、TENANT 或显式项目集合"],
  ["查看角色菜单", "TENANT_ADMINISTRATOR", "MENU/VIEW", "验证不同角色可见的菜单与产品入口"],
  ["注册用户身份", "TENANT_ADMINISTRATOR", "IDENTITY_USER/CREATE", "在外部 IdP 创建用户并建立权限主体"],
  ["禁用用户身份", "TENANT_ADMINISTRATOR", "IDENTITY_USER/DISABLE", "阻止后续登录并保留历史操作主体"],
  ["发起密码重置", "TENANT_ADMINISTRATOR", "IDENTITY_USER/RESET_PASSWORD", "通过 IdP 安全流程重置，不读取旧密码"],
  ["查看授权主体", "AUDITOR", "IDENTITY_USER/VIEW", "核对用户、角色、租户和项目成员关系"],
  ["审批高风险动作", "TENANT_ADMINISTRATOR", "APPROVAL/DECIDE", "确保请求主体和批准主体分离"],
  ["撤销未消费批准", "TENANT_ADMINISTRATOR", "APPROVAL/REVOKE", "在批准仍有效且未消费时撤销"],
  ["维护 Agent 工具预算", "PLATFORM_ADMINISTRATOR", "AGENT_TOOL_BUDGET/UPDATE", "限制调用次数、并发、时长和费用"],
  ["维护 Agent 动作审批策略", "PLATFORM_ADMINISTRATOR", "AGENT_APPROVAL_POLICY/UPDATE", "配置工具、风险和审批要求"],
  ["维护 Skill 准入策略", "PLATFORM_ADMINISTRATOR", "AGENT_SKILL_POLICY/UPDATE", "控制 Skill 发布、启用和可见范围"],
  ["查看 Agent 工具目录", "PLATFORM_ADMINISTRATOR", "AI_TOOL/VIEW", "检查工具 schema、风险、所有者与启用状态"],
  ["查看模型路由", "PLATFORM_ADMINISTRATOR", "MODEL_ROUTE/VIEW", "检查模型、Provider、超时和降级策略"],
  ["查看 RAG 诊断", "OPERATOR", "AI_KNOWLEDGE/DIAGNOSE", "检查存储、Embedding、Reranker 和索引状态"],
  ["维护数据源授权", "PROJECT_OWNER", "DATASOURCE_AUTHORIZATION/UPDATE", "为项目成员授予数据源用途权限"],
  ["维护连接器能力快照", "OPERATOR", "CONNECTOR_CAPABILITY/REFRESH", "刷新版本、模式、限流和容量事实"],
  ["维护同步告警规则", "OPERATOR", "SYNC_ALERT/UPDATE", "配置延迟、错误、积压和容量阈值"],
  ["管理同步执行策略", "OPERATOR", "SYNC_OPERATION/UPDATE", "设置批量、并发、超时与资源预算边界"],
  ["处置同步事故", "OPERATOR", "SYNC_INCIDENT/MANAGE", "确认、指派、解决和关闭事故"],
  ["查看平台健康", "OPERATOR", "AUDIT_LOG/VIEW", "检查服务、依赖、指标和告警摘要"],
  ["导出审计记录", "AUDITOR", "AUDIT_LOG/EXPORT", "按范围和保留政策导出低敏审计"],
  ["执行备份检查", "PLATFORM_ADMINISTRATOR", "PLATFORM_BACKUP/VERIFY", "核对数据库、对象存储和配置备份"],
  ["执行升级审批", "PLATFORM_ADMINISTRATOR", "PLATFORM_RELEASE/APPROVE", "核对版本、迁移、回滚和验收计划"],
];

const DEPLOYMENT_COMPONENTS = [
  ["JDK 与 Maven", "java -version；mvn -version", "JDK 21，Maven 使用项目锁定依赖"],
  ["PostgreSQL/pgvector", "docker compose ps postgres；SELECT extversion FROM pg_extension WHERE extname='vector'", "数据库健康且 pgvector 扩展可查询"],
  ["Kafka", "docker compose ps kafka；检查 topic 与 consumer group", "Broker 健康，主 topic、retry 和 DLT 已创建"],
  ["Redis", "docker compose ps redis；redis-cli PING", "返回 PONG 且持久策略符合环境"],
  ["MinIO", "docker compose ps minio；检查 bucket", "对象存储健康且 bucket 权限最小化"],
  ["Nacos", "docker compose ps nacos；检查服务实例", "配置中心和服务发现可用"],
  ["Java 微服务", "docker compose ps gateway task-management data-sync", "声明健康检查的服务均为 healthy"],
  ["Python AI Runtime", "docker compose ps python-ai-runtime；访问低敏诊断", "Runtime 健康且 Provider 状态可解释"],
  ["Gateway", "访问 /actuator/health 与只读诊断路由", "认证、授权和路径重写正常"],
  ["前端", "检查生产构建与静态资源容器", "页面可加载且 API 基址指向 Gateway"],
];

const OPERATIONS_JOBS = [
  ["服务健康巡检", "每 5 分钟", "Gateway 与全部后端服务", "actuator health、实例数、重启次数", "所有关键服务 healthy，实例地址有效"],
  ["Kafka 积压巡检", "每 5 分钟", "主 topic、retry、DLT", "group lag、消费速率、最老消息时间", "lag 未超过任务延迟预算"],
  ["数据库容量巡检", "每 15 分钟", "PostgreSQL", "连接数、锁等待、磁盘、WAL、慢查询", "连接和磁盘低于告警阈值"],
  ["pgvector 索引巡检", "每日", "ai_memory schema", "索引大小、维度、失效 chunk、查询延迟", "模型维度与索引合同一致"],
  ["Redis 状态巡检", "每 15 分钟", "Redis", "内存、淘汰、持久化、阻塞客户端", "无异常淘汰和持续阻塞"],
  ["对象存储巡检", "每小时", "MinIO", "容量、错误率、未完成上传、生命周期", "容量充足且无长期未完成上传"],
  ["任务执行巡检", "每 10 分钟", "data-sync", "运行时长、读写量、脏数据、对象状态", "执行状态与对象台账一致"],
  ["调度触发巡检", "每小时", "data-sync scheduler", "应触发数、实际触发数、错过策略", "没有无解释的漏触发"],
  ["Recovery 巡检", "每 10 分钟", "Recovery case", "cycle、动作、证据、最终状态", "循环未超限且每轮有新事实"],
  ["Agent Runtime 巡检", "每 10 分钟", "Java/Python Agent Runtime", "session、run、checkpoint、Provider", "状态可恢复且 Provider 诊断明确"],
  ["RAG 检索巡检", "每小时", "RAG pipeline", "范围泄漏、引用、拒答、延迟", "范围泄漏为零且延迟在预算内"],
  ["授权缓存巡检", "每小时", "Gateway/permission-admin", "缓存年龄、失效事件、判定错误", "策略变更后缓存及时失效"],
  ["审计完整性巡检", "每日", "审计存储", "请求、批准、执行、修复和导出链路", "关键动作都有主体、时间和结果"],
  ["备份结果巡检", "每日", "PostgreSQL/MinIO/配置", "备份状态、大小、校验和、保留期", "备份成功且校验和可验证"],
  ["证书与 Secret 到期巡检", "每日", "Gateway/Provider/连接器", "到期时间和轮换计划", "到期前完成轮换且不输出 Secret 值"],
  ["容器资源巡检", "每 15 分钟", "Compose/Kubernetes", "CPU、内存、重启、文件句柄", "资源使用低于容量阈值"],
  ["日志采集巡检", "每 15 分钟", "日志管线", "采集延迟、丢弃量、敏感字段扫描", "日志完整且无凭据正文"],
  ["告警投递巡检", "每小时", "告警通道", "触发、聚合、静默、送达、确认", "关键告警可送达值班角色"],
  ["时间同步巡检", "每日", "全部节点", "NTP 偏差、事件时间与数据库时间", "偏差不影响跨服务排序"],
  ["发布后巡检", "每次发布", "变更服务", "迁移、健康、错误率、关键 smoke", "门禁通过后才继续放量"],
];

const RUNBOOK_PROFILES = {
  "manual-observability": ["指标采集", "结构化日志", "分布式追踪", "告警路由", "仪表盘", "审计查询"],
  "manual-kafka-operations": ["Broker 健康", "Topic", "Consumer Group", "Retry Topic", "DLT", "Outbox"],
  "manual-postgresql-pgvector": ["数据库连接", "Schema 迁移", "锁与慢查询", "WAL", "pgvector 维度", "HNSW 索引"],
  "manual-model-provider": ["Provider 路由", "模型健康", "超时", "限流", "降级", "费用预算"],
  "manual-backup-disaster-recovery": ["备份计划", "恢复点", "数据库恢复", "对象恢复", "Kafka 位点", "业务验证"],
  "manual-upgrade-rollback": ["版本评审", "数据库迁移", "灰度", "兼容性", "回滚", "发布后验证"],
  "manual-schema-recovery": ["元数据刷新", "字段映射", "非空默认值", "类型转换", "外键顺序", "失败对象重放"],
};

const PRODUCT_FEATURES = [
  ["多 Agent 规划", "项目负责人", "把自然语言同步目标转换为可确认计划", "AgentPlan、ToolPlan 和状态轨迹", "模型不能直接绕过 Java 控制面"],
  ["自主 RAG 决策", "普通用户/Agent", "按需要检索文档、Runbook 和历史案例", "SEARCH/SKIP 决策和引用", "范围过滤始终强制"],
  ["数据源管理", "数据工程师", "登记连接器、测试连接并采集元数据", "数据源与能力快照", "凭据只保存引用"],
  ["同步任务向导", "项目负责人", "逐步配置来源、目标、模式和映射", "任务草稿", "草稿不产生执行副作用"],
  ["任务版本发布", "项目负责人", "把已预检配置发布为不可变版本", "configVersion", "发布前必须预检"],
  ["批量与调度", "数据工程师", "管理手动、周期和补数执行", "调度与 execution", "避免同任务重叠副作用"],
  ["对象级台账", "运维人员", "查看表、分片、文件和分区状态", "object ledger", "成功对象不重复执行"],
  ["无人值守恢复", "项目负责人", "首次授权后修复低风险错误并有限重跑", "Recovery case", "越权动作退出 Loop"],
  ["字段映射修复", "数据工程师", "在唯一元数据证据下修复映射", "修复回执", "歧义或有损转换不自动执行"],
  ["统一生命周期图", "普通用户/运维", "查看用户目标到最终验证的全链路", "节点、边和证据", "只读投影不创建第二状态机"],
  ["审批中心", "批准人", "处理项目、成员和高风险动作审批", "审批事实", "请求与批准主体分离"],
  ["权限与范围", "管理员", "配置角色、路由和数据范围", "授权决策", "业务服务仍需二次范围校验"],
  ["实时事件", "普通用户", "接收 Agent 和任务状态更新", "WebSocket/SSE frame", "支持游标恢复和范围隔离"],
  ["质量治理", "质量管理员", "运行规则、报告和整改任务", "质量结果与整改状态", "规则动作受权限控制"],
  ["可观测性", "运维人员", "查询健康、指标、日志和告警", "低敏诊断", "诊断不返回凭据和正文"],
];

const TEST_DOMAINS = [
  ["Agent 规划", "提交明确和含糊目标", "计划、澄清、Specialist 选择与状态轨迹正确"],
  ["动态编排", "多角色就绪波次", "LangGraph Send 数与子图收口数一致"],
  ["RAG 检索", "精确码、自然问法和跨格式问题", "范围、召回、引用和拒答符合黄金集"],
  ["任务管理", "创建、发布、运行、暂停、终止、克隆", "状态机与审计一致"],
  ["数据同步", "全量、增量、CDC、文件、API、Kafka", "读写量、对象台账与 checkpoint 正确"],
  ["字段约束", "非空、类型、外键、唯一键", "预检与受治理修复边界正确"],
  ["Recovery", "可重试、可修复和越权错误", "有限循环、动作、退出与后置验证正确"],
  ["Kafka", "主 topic、retry、DLT 和 outbox", "投递、消费、幂等和死信事实一致"],
  ["权限", "普通用户、负责人、运维、审计、管理员", "角色、动作和数据范围不泄漏"],
  ["审批", "首次授权、高风险和自批准", "双主体、有效期与用途绑定正确"],
  ["WebSocket/SSE", "连接、断线、游标和乱序", "鉴权、重连和事件顺序正确"],
  ["可观测性", "日志、指标、追踪和告警", "所有证据带来源、时间与可信度"],
];

/** 按文档类型返回完全不同的章节模型。 */
export function buildSemanticDocxContent(scope, topic, allApiContracts) {
  if (API_TOPIC_SLUGS.has(topic.slug)) return buildApiDocument(scope, topic, allApiContracts);
  if (topic.slug === "manual-user-guide") return buildUserManual(scope, topic);
  if (topic.slug === "manual-administrator-guide") return buildAdministratorManual(scope, topic);
  if (topic.slug === "manual-deployment-guide") return buildDeploymentManual(scope, topic);
  if (topic.slug === "manual-operations-guide") return buildOperationsManual(scope, topic);
  if (INCIDENT_TOPIC_SLUGS.has(topic.slug)) return buildIncidentDocument(scope, topic);
  if (TEST_TOPIC_SLUGS.has(topic.slug)) return buildTestReport(scope, topic);
  if (topic.slug === "product-feature-specification") return buildProductSpecification(scope, topic);
  if (topic.slug === "manual-security-approval") return buildSecurityManual(scope, topic);
  if (topic.slug === "manual-schema-recovery" || SPECIALIZED_RUNBOOK_SLUGS.has(topic.slug)) {
    return buildSpecializedRunbook(scope, topic);
  }
  throw new Error(`没有为 DOCX 主题配置语义化内容：${topic.slug}`);
}

/** 用户手册只描述终端用户能完成的操作、权限、步骤和结果。 */
function buildUserManual(scope, topic) {
  const operations = Array.from({ length: 140 }, (_, index) => {
    const blueprint = USER_OPERATION_BLUEPRINTS[index % USER_OPERATION_BLUEPRINTS.length];
    const scenario = ["日常操作", "首次配置", "历史记录复查", "定时任务维护"][index % 4];
    return entry(`${index + 1}. ${blueprint[0]}（${scenario}）`, [
      ["用户操作编号", `USR-${String(index + 1).padStart(4, "0")}`],
      ["功能入口", blueprint[1]],
      ["适用角色", blueprint[2]],
      ["所需权限", blueprint[3]],
      ["前置条件", `已登录，当前范围为 ${scope.label}，且拥有目标资源的可见权限。`],
      ["操作步骤", blueprint[4].map((step, stepIndex) => `${stepIndex + 1}. ${step}`).join("；")],
      ["操作结果", blueprint[5]],
      ["注意事项", "页面展示以服务端状态为准；不得在文本框、附件或备注中填写密码、Token 或连接串。"],
    ]);
  });
  return documentModel("用户操作手册", operations.length, [
    chapter("手册对象与阅读方式", [
      paragraph(`${topic.summary} 本手册面向普通用户和项目负责人，只描述产品操作，不承担运维事故或恢复案例记录。`),
      table(["角色", "可执行操作", "不能执行的操作"], [
        ["普通用户", "查看本人可见项目、任务、报告、Agent 会话和审批申请", "租户管理、平台策略、越权数据访问"],
        ["项目负责人", "创建发布任务、确认 Agent 计划、管理项目成员与任务", "平台级策略、其他租户资源"],
        ["审计人员", "读取授权范围内审计与报告", "修改任务、批准自己的请求"],
      ]),
    ]),
    chapter("常用导航与状态说明", [
      table(["功能区", "主要对象", "常见状态", "用户关注点"], [
        ["数据同步", "任务、execution、对象台账", "DRAFT/PUBLISHED/RUNNING/SUCCEEDED/FAILED", "范围、读写量、checkpoint"],
        ["Agent 助手", "session、run、ToolPlan", "PLANNING/WAITING_CONFIRMATION/RUNNING/COMPLETED", "计划、引用、风险和确认"],
        ["审批中心", "项目、成员、动作审批", "PENDING/APPROVED/REJECTED/CANCELLED", "批准主体、范围与有效期"],
        ["数据质量", "规则、报告、整改", "DRAFT/ENABLED/COMPLETED", "规则结果与整改责任"],
      ]),
    ]),
    chapter(`逐项操作说明（${operations.length} 项）`, [entries(operations)]),
    chapter("个人数据与安全注意事项", [
      bullets([
        "只在当前租户和项目范围内操作，切换项目后重新核对页面标题与筛选条件。",
        "浏览器不应手工伪造租户、项目、角色、审批或服务身份 Header。",
        "导入文件只放任务配置，不放数据库密码、API Key、访问令牌或真实敏感样本。",
        "Agent 给出的计划必须在确认页核对范围、工具、影响和授权盒后再执行。",
        "服务端返回拒答或需要更高权限时，应按页面指引申请权限，不通过重复提交绕过门禁。",
      ]),
    ]),
  ]);
}

/** 管理员手册只描述租户、项目、身份、权限、策略和平台配置操作。 */
function buildAdministratorManual(scope, topic) {
  const operations = Array.from({ length: 120 }, (_, index) => {
    const blueprint = ADMIN_OPERATION_BLUEPRINTS[index % ADMIN_OPERATION_BLUEPRINTS.length];
    const changeType = ["日常维护", "新增配置", "状态变更", "定期复核"][index % 4];
    return entry(`${index + 1}. ${blueprint[0]}（${changeType}）`, [
      ["管理操作编号", `ADM-${String(index + 1).padStart(4, "0")}`],
      ["适用管理员", blueprint[1]],
      ["资源动作", blueprint[2]],
      ["管理目标", blueprint[3]],
      ["操作前检查", "核对当前租户、项目、目标主体、已有策略、变更影响和审批要求。"],
      ["操作步骤", `进入对应管理页；检索目标对象；预览差异；提交变更；重新查询并确认生效。`],
      ["审计结果", `记录操作者、目标对象、前后值摘要、时间、traceId 和 ${topic.code}。`],
      ["权限边界", "管理员角色不会自动放大所有数据范围；仍以 permission-admin 返回的显式范围为准。"],
    ]);
  });
  return documentModel("管理员手册", operations.length, [
    chapter("管理员角色与职责", [
      paragraph(`${topic.summary} 本手册不记录任务事故，所有章节围绕账号、角色、租户、项目、审批和平台配置。`),
      table(["角色", "管理范围", "典型职责", "关键限制"], [
        ["项目负责人", "单个项目", "成员、任务、项目内审批", "不能修改租户或平台策略"],
        ["运营人员", "授权项目或租户", "执行、告警、事故和容量运营", "默认不能管理身份和凭据"],
        ["审计人员", "授权审计范围", "只读审计与报告", "不能修改被审计对象"],
        ["租户管理员", "单个租户", "项目、成员、角色和策略", "不能访问其他租户"],
        ["平台管理员", "平台控制面", "租户、Provider、基础设施和全局策略", "高风险动作必须审计和审批"],
      ]),
    ]),
    chapter("管理对象关系", [
      paragraph("业务层级只使用租户、项目和应用/资源；Agent 工具中的 workspaceKey 是受限执行目录或检索范围键，不是新的业务组织层级。"),
      table(["对象", "主标识", "所有者", "变更方式"], [
        ["租户", "tenantId", "平台管理员", "开通、更新、暂停、恢复、关闭"],
        ["项目", "projectId", "租户管理员/项目负责人", "申请、创建、成员管理"],
        ["用户身份", "actorId/IdP subject", "租户管理员", "注册、禁用、重置"],
        ["角色与策略", "roleCode/policyId", "租户或平台管理员", "创建、更新、启停、审计"],
      ]),
    ]),
    chapter(`逐项管理操作（${operations.length} 项）`, [entries(operations)]),
    chapter("管理变更复核清单", [
      bullets([
        "确认操作者管理范围覆盖目标租户或项目。",
        "确认请求主体和批准主体在需要审批时互不相同。",
        "确认权限扩大、身份变更、Provider 和基础设施变更已经记录影响与回滚。",
        "确认 Gateway 授权缓存已在策略变更后失效。",
        "确认审计记录不包含密码、Token、API Key、完整连接串或业务正文。",
      ]),
    ]),
  ]);
}

/** 部署手册记录环境、组件、配置、顺序、命令和验收，不记录任务事故案例。 */
function buildDeploymentManual(scope, topic) {
  const steps = Array.from({ length: 100 }, (_, index) => {
    const component = DEPLOYMENT_COMPONENTS[index % DEPLOYMENT_COMPONENTS.length];
    const phase = ["准备", "配置", "启动", "验证", "切流", "回滚准备"][index % 6];
    return entry(`${index + 1}. ${component[0]} - ${phase}`, [
      ["部署步骤编号", `DEP-${String(index + 1).padStart(4, "0")}`],
      ["阶段", phase],
      ["组件", component[0]],
      ["前置条件", `已完成前序组件验收；目标环境为 ${scope.label}；配置文件不包含明文 Secret。`],
      ["配置项", `镜像/版本、端口、健康检查、资源限制、依赖地址和持久卷按环境清单注入。`],
      ["执行步骤", `核对配置；启动或更新 ${component[0]}；等待健康检查；执行只读验证。`],
      ["验收命令", component[1]],
      ["预期结果", component[2]],
      ["回滚方法", "停止本次变更，恢复上一版本镜像和配置；数据库迁移按已审核回滚方案处理。"],
    ]);
  });
  return documentModel("部署手册", steps.length, [
    chapter("部署基线与拓扑", [
      paragraph(`${topic.summary} 本手册覆盖开发/测试环境的可重复部署步骤；生产部署应使用组织批准的 Secret、镜像仓库、证书和变更流程。`),
      table(["层级", "组件", "职责", "启动依赖"], [
        ["入口", "Frontend/Gateway", "用户界面、认证、路由与授权", "IdP、Nacos、业务服务"],
        ["业务", "permission/task/datasource/quality/data-sync", "控制面与执行面", "PostgreSQL、Kafka、Redis"],
        ["Agent", "agent-runtime/python-ai-runtime", "计划、工具、RAG 与事件", "Kafka、PostgreSQL/pgvector、Provider"],
        ["基础设施", "PostgreSQL/Kafka/Redis/MinIO/Nacos", "持久化、消息、缓存、对象与配置", "宿主机存储与网络"],
      ]),
    ]),
    chapter("配置与 Secret 规则", [
      table(["配置类别", "提供方式", "禁止事项", "验证方式"], [
        ["数据库 DSN", "Secret/环境注入", "写入 Git、日志或文档", "只显示连接状态与 schema"],
        ["模型与 RAG Key", "Secret/运行时注入", "写入镜像或命令历史", "只显示 Provider configured 状态"],
        ["服务签名/共享令牌", "Secret", "浏览器可见或回显", "内部请求鉴权 smoke"],
        ["普通非敏感配置", "YAML/环境变量", "生产值混入示例文件", "配置快照和启动日志"],
      ]),
    ]),
    chapter(`逐项部署步骤（${steps.length} 项）`, [entries(steps)]),
    chapter("发布验收顺序", [
      bullets([
        "验证数据库迁移与 pgvector 扩展。",
        "验证 Kafka 主 topic、retry、DLT 和消费者组。",
        "验证权限中心、Gateway 和业务服务健康。",
        "验证 Agent Runtime、RAG 诊断和 Provider 低敏状态。",
        "运行只读 smoke 和隔离合成任务，确认审计、幂等和范围隔离。",
        "保留上一版本镜像、配置与数据库回滚说明后再切换流量。",
      ]),
    ]),
  ]);
}

/** 运维手册记录标准作业、命令、阈值、升级和回滚，不伪装成事故台账。 */
function buildOperationsManual(scope, topic) {
  const jobs = Array.from({ length: 140 }, (_, index) => {
    const job = OPERATIONS_JOBS[index % OPERATIONS_JOBS.length];
    const window = ["工作日白天", "夜间批次", "周末窗口", "发布后 30 分钟"][index % 4];
    return entry(`${index + 1}. ${job[0]}（${window}）`, [
      ["运维作业编号", `OPS-${String(index + 1).padStart(4, "0")}`],
      ["执行频率", job[1]],
      ["责任角色", index % 7 === 0 ? "平台管理员" : "运维人员"],
      ["检查对象", job[2]],
      ["检查命令", `${job[3]}；使用只读诊断接口或平台批准的运维命令。`],
      ["正常判据", job[4]],
      ["异常升级", "先保留来源、时间、traceId 和指标快照；达到升级阈值时创建事故记录。"],
      ["回滚步骤", "若异常由刚完成的配置或发布引起，恢复上一有效版本并重新执行健康检查。"],
      ["执行记录", `写入作业编号、操作者、开始/结束时间、结论和 ${topic.code}，不复制业务正文。`],
    ]);
  });
  return documentModel("运维手册", jobs.length, [
    chapter("值班范围与证据顺序", [
      paragraph(`${topic.summary} 运维人员先看结构化日志与指标，再比较成功配置、连接器能力和正式 Runbook；事故发生后另行写入事故记录。`),
      table(["顺序", "证据", "查询键", "用途"], [
        ["1", "结构化日志", "traceId/taskId/executionId/objectId/errorCode", "确定时间、组件和错误"],
        ["2", "指标与容量", "服务、实例、任务、消费者组", "判断延迟、积压和资源瓶颈"],
        ["3", "配置版本", "current/lastSuccessful", "定位变更差异"],
        ["4", "连接器能力", "版本、限流、批量、并发", "判断配置是否越界"],
        ["5", "Runbook 与历史事故", "错误码、组件和版本", "选择已有操作步骤"],
      ]),
    ]),
    chapter(`标准运维作业（${jobs.length} 项）`, [entries(jobs)]),
    chapter("交接班与升级原则", [
      bullets([
        "交接必须说明未关闭告警、运行中长任务、进行中的恢复循环和已批准变更。",
        "发现高风险、不可逆、凭据、权限、DDL 或扩大范围操作时停止自动处置并升级。",
        "事故记录由专门事故文档保存，本手册只维护稳定、可复用的标准作业。",
        "每次 Runbook 更新都要记录版本、适用组件、审批人、回滚和验证方式。",
      ]),
    ]),
  ]);
}

/** 专项 Runbook 按主题组件生成步骤、命令、回滚和验证，不批量塞入事故案例。 */
function buildSpecializedRunbook(scope, topic) {
  const areas = RUNBOOK_PROFILES[topic.slug] || topic.sections.map((section) => section[0]);
  const procedures = Array.from({ length: 120 }, (_, index) => {
    const area = areas[index % areas.length];
    const action = ["检查", "配置", "演练", "维护", "验证", "回滚准备"][index % 6];
    return entry(`${index + 1}. ${area} - ${action}`, [
      ["操作步骤编号", `RB-${topic.code}-${String(index + 1).padStart(4, "0")}`],
      ["适用组件", area],
      ["操作类型", action],
      ["前置条件", `已取得 ${scope.label} 的只读诊断或相应运维权限，并确认变更窗口。`],
      ["输入参数", "组件实例、版本、时间窗口、目标资源和 traceId；不得输入明文凭据。"],
      ["执行步骤", `读取当前状态；保存配置摘要；执行 ${area} 的${action}；等待稳定状态；核对审计。`],
      ["检查命令", `使用 ${area} 对应的健康、配置、指标或只读查询命令，命令参数使用占位符。`],
      ["成功判据", `${area} 的状态、指标、配置和依赖均满足本文档基线。`],
      ["回滚步骤", "恢复操作前配置或上一成功版本，重新加载组件并执行相同验证。"],
      ["升级条件", "需要凭据、权限、DDL、删除、覆盖、扩大范围或无法确认回滚时转人工审批。"],
    ]);
  });
  return documentModel("专项 Runbook", procedures.length, [
    chapter("用途与适用范围", [
      paragraph(`${topic.summary} 本文档是可重复执行的操作手册，不是事故台账；真实事故必须引用本 Runbook 的步骤编号并另行记录时间线。`),
      table(["主题", "操作目标", "必须保留的证据", "禁止事项"], areas.map((area) => [
        area,
        `稳定完成 ${area} 的检查、维护和验证`,
        "配置摘要、命令、时间、操作者和验证结果",
        "明文凭据、未经审批的不可逆变更",
      ])),
    ]),
    chapter(`标准操作步骤（${procedures.length} 项）`, [entries(procedures)]),
    chapter("执行后验证", [
      bullets([
        "重新查询组件健康、版本和关键指标。",
        "确认相关服务没有新增持续错误或积压。",
        "确认配置、审计和实际运行状态一致。",
        "需要任务级验证时只运行隔离合成任务，不读写客户数据。",
      ]),
    ]),
  ]);
}

/** 安全手册记录角色、策略、审批和授权盒规则，不记录任务失败案例。 */
function buildSecurityManual(scope, topic) {
  const policyAreas = [
    "身份认证", "会话", "服务身份", "租户隔离", "项目范围", "资源动作", "数据范围", "请求主体",
    "批准主体", "首次授权盒", "高风险动作", "凭据引用", "日志脱敏", "导出控制", "RAG 范围",
    "模型 Provider", "内部 API", "WebSocket", "审计保留", "权限复核",
  ];
  const policies = Array.from({ length: 140 }, (_, index) => {
    const area = policyAreas[index % policyAreas.length];
    const level = ["平台", "租户", "项目", "资源", "操作"][index % 5];
    return entry(`${index + 1}. ${area}策略（${level}级）`, [
      ["策略编号", `SEC-${String(index + 1).padStart(4, "0")}`],
      ["策略域", area],
      ["适用主体", ["ORDINARY_USER", "PROJECT_OWNER", "OPERATOR", "AUDITOR", "TENANT_ADMINISTRATOR", "PLATFORM_ADMINISTRATOR", "SERVICE_ACCOUNT"][index % 7]],
      ["受保护对象", `${level}级 ${area} 资源`],
      ["允许条件", "身份可信、资源动作匹配、数据范围覆盖、用途有效且未超过策略有效期。"],
      ["审批要求", index % 4 === 0 ? "请求主体与批准主体分离，批准绑定用途、范围和有效期。" : "低风险只读操作按角色和范围直接判定。"],
      ["拒绝条件", "身份不可信、范围不足、自批准、批准过期、参数超出授权盒或操作不可逆。"],
      ["审计字段", "actor、approver、tenant、project、resource、action、decision、occurredAt、traceId。"],
    ]);
  });
  return documentModel("安全与审批手册", policies.length, [
    chapter("安全模型", [
      paragraph(`${topic.summary} 模型只能建议动作，授权事实始终由 Gateway、permission-admin 和业务服务控制面共同确认。`),
      table(["控制层", "负责内容", "不负责内容"], [
        ["Gateway", "认证、可信上下文、路由动作授权", "业务对象最终范围"],
        ["permission-admin", "角色、策略、数据范围和审批事实", "执行任务副作用"],
        ["业务服务", "对象可见性、状态机与业务约束", "替代身份中心"],
        ["Agent Runtime", "计划、工具门禁和审计关联", "自行批准或扩大授权"],
      ]),
    ]),
    chapter(`策略目录（${policies.length} 项）`, [entries(policies)]),
    chapter("首次授权盒", [
      table(["字段", "含义", "约束"], [
        ["任务与范围", "允许自治的 task/project/application/object", "后续循环不可扩大"],
        ["动作目录", "允许的低风险修复和重放动作", "未列出的动作拒绝"],
        ["参数边界", "batch/channel/timeout/cycle 等上下界", "每轮都重新校验"],
        ["有效期", "批准生效和过期时间", "过期后重新确认"],
        ["主体", "请求者和批准者", "高风险时必须双主体"],
      ]),
    ]),
  ]);
}

/** 产品说明以特性、用户、场景、输入输出和边界组织，不混入事故记录。 */
function buildProductSpecification(scope, topic) {
  const features = Array.from({ length: 180 }, (_, index) => {
    const feature = PRODUCT_FEATURES[index % PRODUCT_FEATURES.length];
    const maturity = ["核心能力", "治理能力", "运维能力", "扩展能力"][index % 4];
    return entry(`${index + 1}. ${feature[0]}（${maturity}）`, [
      ["特性编号", `FEAT-${String(index + 1).padStart(4, "0")}`],
      ["特性名称", feature[0]],
      ["目标用户", feature[1]],
      ["使用场景", feature[2]],
      ["输入", "用户目标、范围、资源状态或配置；具体字段由对应接口合同定义。"],
      ["输出", feature[3]],
      ["功能边界", feature[4]],
      ["可观测性", "状态、时间、来源、traceId 和低敏结果可查询。"],
    ]);
  });
  return documentModel("产品特性说明", features.length, [
    chapter("产品定位", [
      paragraph(`${topic.summary} 产品目标是把数据治理、任务执行、知识检索和受治理自治恢复组合成可审计闭环。`),
      table(["价值", "面向角色", "产品结果"], [
        ["降低任务配置成本", "项目负责人/数据工程师", "向导和 Agent 协同生成计划"],
        ["降低非工作时间故障影响", "运维人员", "授权盒内自动修复和有限重跑"],
        ["保持治理边界", "管理员/审计人员", "权限、审批、幂等和审计事实"],
        ["提高可解释性", "所有用户", "统一生命周期与证据引用"],
      ]),
    ]),
    chapter(`详细特性目录（${features.length} 项）`, [entries(features)]),
    chapter("非功能要求", [
      bullets([
        "租户和项目范围在检索、列表、执行、事件和审计中保持一致。",
        "写操作具备幂等、状态机前置条件和可解释错误。",
        "Kafka 负责 Java/Python 异步解耦，持久状态以 PostgreSQL 为准。",
        "日志、指标、追踪和证据不回显凭据或受限正文。",
        "长任务支持超时、取消、恢复、重放和最终验证。",
      ]),
    ]),
  ]);
}

/** 接口文档只渲染从源码提取的真实接口合同。 */
function buildApiDocument(scope, topic, allApiContracts) {
  const selected = apiContractsForTopic(allApiContracts, topic.slug);
  const publicCount = selected.filter((item) => item.visibility === "PUBLIC").length;
  const internalCount = selected.length - publicCount;
  const contractEntries = selected.map((contract, index) => entry(
    `${index + 1}. ${contract.httpMethod} ${contract.externalPaths[0] || contract.declaredPaths[0]}`,
    [
      ["接口编号", contract.contractId],
      ["来源控制器", `${contract.module}/${contract.controller}.${contract.methodName}`],
      ["源码位置", `${contract.sourceFile}:${contract.sourceLine}`],
      ["接口类型", contract.transport],
      ["公开性", contract.visibility === "PUBLIC" ? "公开接口" : "内部控制面接口"],
      ["请求方法", contract.httpMethod],
      ["访问路径", contract.externalPaths.length ? contract.externalPaths.join("；") : "不通过公共网关，仅服务内调用"],
      ["服务内路径", contract.declaredPaths.join("；")],
      ["用途", contract.purpose],
      ["认证与权限", contract.permission],
      ["请求参数", formatParameters(contract.parameters)],
      ["请求体 Schema", formatSchema(contract.requestType, contract.requestSchema)],
      ["响应类型", contract.responseType],
      ["响应 Schema", formatSchema(contract.responseType, contract.responseSchema)],
      ["Content-Type", `consumes=${contract.consumes.join(",") || "默认"}；produces=${contract.produces.join(",") || "application/json"}`],
      ["幂等与重连", contract.idempotency],
      ["请求示例", contract.requestExample, "code"],
      ["成功响应示例", contract.responseExample, "code"],
      ["错误响应", contract.errorResponses.map((item) => `${item.status} ${item.code}：${item.message}`).join("；")],
    ],
  ));
  return documentModel("接口参考", selected.length, [
    chapter("接口目录说明", [
      paragraph(`${topic.summary} 本文档由仓库实际 Controller 和 FastAPI 路由生成，接口条目以源码位置为准；不包含任务事故或失败诊断记录。`),
      table(["接口范围", "数量", "访问方式", "授权原则"], [
        ["公开接口", publicCount, "经 Gateway 的 /api/** 路径", "认证、资源动作授权和业务范围复核"],
        ["内部控制面接口", internalCount, "服务内路径", "服务身份、来源白名单、签名或内部令牌"],
        ["WebSocket/SSE", selected.filter((item) => item.transport !== "REST").length, "升级连接或事件流", "连接鉴权、游标和范围隔离"],
      ]),
      paragraph(`当前文档范围：${scope.label}。所有请求示例使用占位 Token 和合成 ID，不包含可用凭据。`),
    ]),
    chapter("统一请求与响应约定", [
      table(["约定", "说明"], [
        ["认证", "公开接口使用 Gateway 验证的 Bearer 会话；内部接口使用受信服务身份。"],
        ["范围", "tenantId、projectId、actor 和 role 由可信控制面注入，客户端同名 Header 不可信。"],
        ["追踪", "X-Trace-Id 贯穿 Gateway、Java、Kafka、Python 和 Worker。"],
        ["分页", "current/size 或 limit/cursor 按接口参数表执行。"],
        ["错误", "错误返回稳定 code、message 和 traceId，不回显堆栈、凭据或业务正文。"],
        ["版本", "事件和跨服务合同携带 schemaVersion；未知主版本 fail-closed。"],
      ]),
    ]),
    chapter(`真实接口详细合同（${contractEntries.length} 个）`, [entries(contractEntries)]),
  ]);
}

/** 事故文档只记录事故身份、影响、证据、时间线、根因、处置和验证。 */
function buildIncidentDocument(scope, topic) {
  const relevantErrors = incidentErrorSubset(topic.slug);
  const incidents = Array.from({ length: 250 }, (_, index) => {
    const ids = correlationIds(scope, index);
    const error = relevantErrors[index % relevantErrors.length];
    const manual = ["AUTHENTICATION_FAILED", "PERMISSION_DENIED", "DDL_REQUIRED"].includes(error[0]);
    return entry(`${index + 1}. ${ids.incidentId} / ${error[0]}`, [
      ["事故编号", ids.incidentId],
      ["任务与执行", `taskId=${ids.taskId}；executionId=${ids.executionId}；objectId=${ids.objectId}`],
      ["追踪与恢复", `traceId=${ids.traceId}；recoveryCaseId=${ids.recoveryCaseId}`],
      ["发生时间", syntheticTime(index, 9)],
      ["影响范围", `仅影响 ${scope.label} 的 ${ids.objectId}；已成功对象和其他项目不受影响。`],
      ["现象", `${error[0]} 导致当前工作单元停止，服务端保留状态与低敏错误摘要。`],
      ["证据来源", `synthetic://datasmart-govern/incidents/${scope.key}/${ids.incidentId}；confidence=${(0.90 + (index % 10) / 100).toFixed(2)}；sourceStatus=COMPLETE`],
      ["根因", `${error[1]}；通过 ${error[2]} 与最近成功配置交叉确认。`],
      ["处置时间线", `T+0 告警；T+3 分钟定位；T+8 分钟完成${manual ? "人工接管" : "受治理处置"}；T+15 分钟验证。`],
      ["处置动作", manual ? "退出自治，返回权限、操作步骤、影响、回滚和验证方法。" : error[3]],
      ["回滚", `恢复 ${ids.lastSuccessfulConfigVersion}，保留已成功对象，不扩大数据范围。`],
      ["恢复验证", manual ? "有权限主体处理后重新预检和监控。" : "PRECHECK_AGENT 与 MONITOR_AGENT 均通过，对象台账收敛。"],
      ["最终结论", manual ? "ATTENTION_REQUIRED" : "RECOVERED"],
    ]);
  });
  return documentModel("事故复盘", incidents.length, [
    chapter("记录口径", [
      paragraph(`${topic.summary} 每条记录是独立合成事故，字段遵循运维事故管理语义；接口参数和用户操作另见对应手册。`),
      table(["阶段", "必须记录", "责任角色"], [
        ["发现", "告警、时间、影响与关联标识", "监控/值班人员"],
        ["诊断", "结构化证据、配置差异和根因", "运维/Recovery"],
        ["处置", "动作、授权、回滚和回执", "执行人/控制面"],
        ["验证", "业务状态、对象台账、指标和最终结论", "预检/监控"],
        ["复盘", "改进项、责任人和回归测试", "产品/研发/运维"],
      ]),
    ]),
    chapter(`详细事故记录（${incidents.length} 条）`, [entries(incidents)]),
  ]);
}

/** 测试报告记录测试目标、环境、步骤、预期、实际和缺陷，不复用事故修复模板。 */
function buildTestReport(scope, topic) {
  const cases = Array.from({ length: 200 }, (_, index) => {
    const domain = TEST_DOMAINS[index % TEST_DOMAINS.length];
    const passed = index % 17 !== 0;
    return entry(`${index + 1}. ${domain[0]} / 场景 ${String(index + 1).padStart(3, "0")}`, [
      ["测试用例编号", `TEST-${topic.code}-${String(index + 1).padStart(4, "0")}`],
      ["测试域", domain[0]],
      ["测试目标", domain[1]],
      ["前置条件", `${scope.label} 的合成夹具已准备，服务版本和测试数据指纹已记录。`],
      ["测试步骤", `准备输入；执行接口或工作流；读取服务端状态；核对审计、范围和副作用。`],
      ["预期结果", domain[2]],
      ["实际结果", passed ? domain[2] : "主断言通过，但一项非阻断性能或展示指标低于目标。"],
      ["测试结果", passed ? "PASS" : "REVIEW"],
      ["缺陷编号", passed ? "无" : `DEF-${String(index + 1).padStart(4, "0")}`],
      ["证据", `synthetic://datasmart-govern/tests/${topic.slug}/${index + 1}；执行时间=${syntheticTime(index, 4)}`],
    ]);
  });
  return documentModel("测试报告", cases.length, [
    chapter("测试范围与环境", [
      paragraph(`${topic.summary} 报告只记录测试活动和质量结论，任务运行事故由事故记录负责。`),
      table(["环境项", "基线"], [
        ["Java", "JDK 21 / Spring Boot 3.5.11"],
        ["Python", "Python AI Runtime / LangGraph"],
        ["消息", "Kafka 主 topic、retry、DLT 与 outbox"],
        ["存储", "PostgreSQL/pgvector、Redis、MinIO"],
        ["数据", "synthetic-only，按租户/项目隔离"],
      ]),
    ]),
    chapter("质量门禁", [
      table(["门禁", "目标", "说明"], [
        ["功能通过率", ">= 95%", "阻断缺陷必须为 0"],
        ["范围泄漏率", "0", "任何越权候选都失败"],
        ["幂等冲突保护", "100%", "同键异请求拒绝"],
        ["RAG 引用", "达到评测阈值", "来源、时间、可信度完整"],
        ["恢复循环", "不超过授权上限", "每轮有新增证据"],
      ]),
    ]),
    chapter(`详细测试用例（${cases.length} 条）`, [entries(cases)]),
  ]);
}

/** 返回主题对应的错误子集，使专项事故复盘围绕自己的事故类型。 */
function incidentErrorSubset(slug) {
  const patterns = {
    "postmortem-schema-drift": /SCHEMA|FIELD_MAPPING|NOT_NULL|DATA_TYPE|NUMERIC|STRING/,
    "postmortem-foreign-key": /FOREIGN_KEY|UNIQUE_CONSTRAINT/,
    "postmortem-rate-limit": /RATE_LIMIT|TARGET_CAPACITY|CONNECTION_TIMEOUT/,
    "postmortem-checkpoint": /CHECKPOINT|OUTBOX/,
    "postmortem-kafka-backlog": /KAFKA|OUTBOX/,
  };
  const pattern = patterns[slug];
  return pattern ? ERROR_CATALOG.filter((item) => pattern.test(item[0])) : ERROR_CATALOG;
}

/** 构造统一 DOCX 模型；“统一”只指渲染协议，不代表内容模板相同。 */
function documentModel(kind, itemCount, chapters) {
  return { kind, itemCount, chapters };
}

function chapter(title, blocks) {
  return { title, blocks };
}

function paragraph(text) {
  return { type: "paragraph", text };
}

function bullets(items) {
  return { type: "bullets", items };
}

function table(headers, rows) {
  return { type: "table", headers, rows };
}

function entries(items) {
  return { type: "entries", items };
}

function entry(title, fields) {
  return { title, fields };
}

/** 把接口参数按位置、必填、类型、默认值和说明格式化。 */
function formatParameters(parameters) {
  if (!parameters.length) return "无显式请求参数";
  return parameters.map((item) => (
    `${item.location}:${item.name} | type=${item.javaType} | required=${item.required}`
    + `${item.defaultValue !== null ? ` | default=${item.defaultValue}` : ""} | ${item.description}`
  )).join("\n");
}

/** 把 DTO 第一层字段格式化为接口文档中的 Schema 描述。 */
function formatSchema(typeName, schema) {
  if (!typeName) return "无请求体";
  if (!schema?.fields?.length) return `${typeName}（源码未声明可展开字段或由框架生成）`;
  return `${typeName}\n${schema.fields.map((field) => (
    isSensitiveContractField(field.name)
      ? `${field.name}（敏感字段；类型=${field.javaType}；required=${field.required}；示例值不展示）`
      : `${field.name}: ${field.javaType} | required=${field.required} | example=${JSON.stringify(field.example)}`
  )).join("\n")}`;
}

/**
 * 判断接口字段是否承载凭据或密钥。
 *
 * 接口文档仍保留字段名、类型和必填性，帮助调用方正确构造请求；但不会把敏感字段写成
 * `字段名: 示例值` 的形式，也不会生成任何看起来可用的凭据，从而兼顾合同完整性和语料安全。
 */
function isSensitiveContractField(fieldName) {
  return /password|passwd|secret|token|credential|access[_-]?key|api[_-]?key/i.test(String(fieldName));
}

/** 为所有案例类资料生成跨格式稳定关联标识。 */
function correlationIds(scope, index) {
  const sequence = String(index + 1).padStart(4, "0");
  return {
    taskId: `TASK-${scope.key}-${sequence}`,
    executionId: `EXEC-${scope.key}-${sequence}`,
    objectId: `object-${String((index % 32) + 1).padStart(2, "0")}`,
    traceId: `trace-${scope.key}-${sequence}`,
    incidentId: `INC-${scope.key}-${sequence}`,
    recoveryCaseId: `RC-${scope.key}-${sequence}`,
    eventId: `EVT-${scope.key}-${sequence}`,
    configVersion: `cfg-v${100 + index}`,
    lastSuccessfulConfigVersion: `cfg-v${99 + index}`,
  };
}

/** 返回固定 UTC 时间，避免生成结果受本机时区影响。 */
function syntheticTime(index, stepMinutes = 7) {
  return new Date(Date.UTC(2026, 7, 10, 0, index * stepMinutes)).toISOString();
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

/**
 * 为每种工作簿返回主题专属工作表；不会再无条件附加“失败诊断”。
 *
 * 任务案例和事故台账保留失败明细是因为这正是文件用途；成功参数、字段映射、调度和测试矩阵则使用
 * 成功记录、约束、策略、测试和缺陷等各自的业务表。
 */
export function buildSemanticWorkbookDataset(scope, topic) {
  if (TASK_MODES[topic.slug]) return buildTaskCaseWorkbook(scope, topic, TASK_MODES[topic.slug]);
  if (topic.slug === "workbook-success-task-parameters") return buildSuccessfulTaskWorkbook(scope, topic);
  if (topic.slug === "workbook-field-mapping-cases") return buildFieldMappingWorkbook(scope, topic);
  if (topic.slug === "workbook-schedule-retry-cases") return buildScheduleWorkbook(scope, topic);
  if (topic.slug === "workbook-test-result-matrix") return buildTestWorkbook(scope, topic);
  if (topic.slug === "workbook-incident-repair-ledger") return buildIncidentWorkbook(scope, topic);
  throw new Error(`没有为工作簿主题配置语义化数据：${topic.slug}`);
}

/** 成功任务参数工作簿只保存成功配置、性能和验证结果。 */
function buildSuccessfulTaskWorkbook(scope, topic) {
  const rows = Array.from({ length: 240 }, (_, index) => {
    const ids = correlationIds(scope, index);
    const read = 10_000 + index * 1_137;
    return [ids.taskId, ids.configVersion, ["FULL", "INCREMENTAL", "CDC", "FILE", "API", "KAFKA"][index % 6], SOURCES[index % SOURCES.length], TARGETS[index % TARGETS.length], 200 + (index % 8) * 100, 1 + (index % Math.max(1, scope.channelCount)), scope.timeoutSeconds + (index % 4) * 30, `checkpoint-${String(index + 318).padStart(6, "0")}`, read, read, 0, 35_000 + index * 137, "SUCCEEDED"];
  });
  const parameterRows = rows.map((row, index) => [row[0], "batch/channel/timeout", `${row[5]}/${row[6]}/${row[7]}`, "最近成功 execution", syntheticTime(index, 13), "可作为同任务恢复基线"]);
  const verificationRows = rows.map((row, index) => [row[0], `EXEC-${scope.key}-${String(index + 1).padStart(4, "0")}`, row[9], row[10], row[11], "对象台账全部 SUCCEEDED", "PRECHECK_AND_MONITOR_VERIFIED"]);
  return workbookModel(topic, [
    sheet("成功任务", ["任务编号", "配置版本", "同步模式", "来源", "目标", "batch_size", "channel", "timeout_s", "checkpoint", "读取行", "写入行", "脏数据", "耗时毫秒", "状态"], rows, "#1F4E78"),
    sheet("参数基线", ["任务编号", "参数组", "参数值", "依据", "完成时间", "适用说明"], parameterRows, "#355E3B"),
    sheet("成功验证", ["任务编号", "执行编号", "读取行", "写入行", "脏数据", "对象结论", "最终验证"], verificationRows, "#2E7D32"),
    dictionarySheet(["成功任务", "参数基线", "成功验证"], [rows, parameterRows, verificationRows]),
    validationSheet(topic, scope, rows.length, "所有记录必须为 SUCCEEDED，读写量一致且脏数据为 0"),
  ]);
}

/** 字段映射工作簿记录映射、约束和转换规则，不把它变成事故表。 */
function buildFieldMappingWorkbook(scope, topic) {
  const fields = ["order_id", "region_code", "order_amount", "customer_id", "occurred_at", "currency", "status", "source_system", "created_at", "updated_at", "product_id", "quantity", "discount_amount", "tax_amount", "shipping_address", "email_hash", "phone_masked", "country_code", "city_code", "postal_code"];
  const types = ["varchar(64)", "varchar(16)", "decimal(18,2)", "bigint", "timestamp", "char(3)", "varchar(32)"];
  const mappings = Array.from({ length: 240 }, (_, index) => {
    const field = fields[index % fields.length];
    const type = types[index % types.length];
    const nullable = index % 5 !== 0;
    const approvedDefault = !nullable && ["region_code", "country_code", "status"].includes(field) ? "APPROVED-UNKNOWN" : "";
    return [`MAP-${String(index + 1).padStart(4, "0")}`, `source_${index % 12}`, `target_${index % 9}`, field, index % 9 === 1 ? `${field}_normalized` : field, type, type, nullable, approvedDefault, index % 4 === 0 ? "trim+normalize" : "identity", `cfg-v${20 + (index % 8)}`, approvedDefault ? "APPROVED_DEFAULT" : "UNIQUE_LOSSLESS_MAPPING"];
  });
  const constraints = mappings.map((row, index) => [row[0], row[4], row[7], row[8] || "无", index % 7 === 0 ? "FK_PARENT_FIRST" : "NONE", index % 11 === 0 ? "UNIQUE_BUSINESS_KEY" : "NONE", "禁止自动放宽约束或截断数据"]);
  const rules = mappings.map((row, index) => [`RULE-${String(index + 1).padStart(4, "0")}`, row[0], row[9], row[5], row[6], row[11], index % 13 === 0 ? "需要人工确认" : "可自动校验", "元数据+历史成功版本"]);
  return workbookModel(topic, [
    sheet("字段映射案例", ["映射编号", "源对象", "目标对象", "来源字段", "目标字段", "来源类型", "目标类型", "允许为空", "已批准默认值", "转换", "历史成功版本", "判定"], mappings, "#1F4E78"),
    sheet("字段约束", ["映射编号", "目标字段", "允许为空", "默认值", "外键规则", "唯一规则", "边界"], constraints, "#9C6500"),
    sheet("转换规则", ["规则编号", "映射编号", "转换", "输入类型", "输出类型", "策略", "确认要求", "证据"], rules, "#355E3B"),
    dictionarySheet(["字段映射案例", "字段约束", "转换规则"], [mappings, constraints, rules]),
    validationSheet(topic, scope, mappings.length, "映射必须唯一、类型兼容，默认值必须已批准"),
  ]);
}

/** 调度工作簿记录触发、重叠、重试和非工作时间策略。 */
function buildScheduleWorkbook(scope, topic) {
  const schedules = Array.from({ length: 260 }, (_, index) => [`SCH-${String(index + 1).padStart(4, "0")}`, `TASK-${scope.key}-${String(index + 1).padStart(4, "0")}`, ["FULL", "INCREMENTAL", "CDC", "FILE", "API"][index % 5], index % 3 === 0 ? `0 ${index % 60} 2 * * ?` : `0 ${index % 60} * * * ?`, "Asia/Shanghai", index % 4 === 0 ? "FIRE_ONCE_NOW" : "SKIP", index % 2 === 0 ? "SERIAL" : "SKIP_OVERLAP", "ENABLED"]);
  const retryPolicies = schedules.map((row, index) => [row[0], 1 + (index % Math.max(1, scope.retryLimit)), 30 + (index % 4) * 30, 300 + (index % 5) * 60, scope.timeoutSeconds + (index % 4) * 30, index % 5 === 0 ? "仅人工可重试" : "授权盒内自动重试", "达到循环上限或越权立即停止"]);
  const windows = schedules.map((row, index) => [row[0], index % 2 === 0 ? "00:00" : "08:00", index % 2 === 0 ? "06:00" : "22:00", index % 3 === 0 ? "夜间允许自治" : "工作时间优先", "Asia/Shanghai", syntheticTime(index, 60)]);
  return workbookModel(topic, [
    sheet("调度方案", ["计划编号", "任务编号", "任务模式", "cron", "时区", "错过触发策略", "并发策略", "状态"], schedules, "#1F4E78"),
    sheet("重试策略", ["计划编号", "最大次数", "初始退避秒", "最大退避秒", "单次超时秒", "非工作时间策略", "停止条件"], retryPolicies, "#9C6500"),
    sheet("执行窗口", ["计划编号", "开始时间", "结束时间", "窗口策略", "时区", "下次评估时间"], windows, "#355E3B"),
    dictionarySheet(["调度方案", "重试策略", "执行窗口"], [schedules, retryPolicies, windows]),
    validationSheet(topic, scope, schedules.length, "cron、时区、重叠和重试上限必须完整"),
  ]);
}

/** 测试矩阵工作簿包含测试用例、执行证据、缺陷和指标。 */
function buildTestWorkbook(scope, topic) {
  const testCases = Array.from({ length: 240 }, (_, index) => {
    const domain = TEST_DOMAINS[index % TEST_DOMAINS.length];
    const passed = index % 17 !== 0;
    return [`TEST-${String(index + 1).padStart(4, "0")}`, domain[0], domain[1], `准备 ${scope.label} 合成夹具`, "执行并读取服务端事实", domain[2], passed ? domain[2] : "一个非阻断指标低于目标", passed ? "PASS" : "REVIEW", 100 + index * 7, 300 + index * 17];
  });
  const evidence = testCases.map((row, index) => [row[0], `trace-test-${String(index + 1).padStart(4, "0")}`, syntheticTime(index, 5), `synthetic://datasmart-govern/tests/${topic.slug}/${index + 1}`, row[7], "来源、时间和断言摘要完整"]);
  const defects = testCases.filter((row) => row[7] !== "PASS").map((row, index) => [`DEF-${String(index + 1).padStart(4, "0")}`, row[0], "MEDIUM", "非阻断质量指标低于门禁", "OPEN", "测试负责人", "补充优化后回归"]);
  return workbookModel(topic, [
    sheet("测试用例", ["用例编号", "测试域", "测试目标", "前置条件", "步骤", "预期结果", "实际结果", "结果", "P50毫秒", "P95毫秒"], testCases, "#1F4E78"),
    sheet("测试证据", ["用例编号", "traceId", "执行时间", "证据URI", "结果", "证据完整性"], evidence, "#355E3B"),
    sheet("缺陷记录", ["缺陷编号", "用例编号", "级别", "缺陷描述", "状态", "负责人角色", "验证计划"], defects, "#9C3D10"),
    dictionarySheet(["测试用例", "测试证据", "缺陷记录"], [testCases, evidence, defects]),
    validationSheet(topic, scope, testCases.length, "阻断缺陷为 0，范围泄漏为 0，所有证据可追溯"),
  ]);
}

/** 事故台账工作簿只承载事故、根因、修复、证据和验证。 */
function buildIncidentWorkbook(scope, topic) {
  const incidents = Array.from({ length: 320 }, (_, index) => {
    const ids = correlationIds(scope, index);
    const error = ERROR_CATALOG[index % ERROR_CATALOG.length];
    return [ids.incidentId, ids.taskId, ids.executionId, ids.objectId, ids.traceId, error[0], syntheticTime(index, 7), ["P1", "P2", "P3"][index % 3], `${error[1]}；${error[2]}`, index % 6 === 0 ? "ATTENTION_REQUIRED" : "RECOVERED"];
  });
  const repairs = incidents.map((row, index) => {
    const error = ERROR_CATALOG[index % ERROR_CATALOG.length];
    const manual = row[9] === "ATTENTION_REQUIRED";
    return [row[0], `RC-${scope.key}-${String(index + 1).padStart(4, "0")}`, error[1], manual ? "人工接管" : error[3], manual ? "HIGH" : "LOW", `恢复 cfg-v${99 + index}`, manual ? "有权限主体处理后重新预检" : "PRECHECK/MONITOR 通过", row[9]];
  });
  const evidence = incidents.map((row, index) => [row[0], row[4], `synthetic://datasmart-govern/incidents/${scope.key}/${row[0]}`, syntheticTime(index, 7), Number((0.90 + (index % 10) / 100).toFixed(2)), "LOG_CONFIG_RUNBOOK_CORROBORATION", "COMPLETE"]);
  return workbookModel(topic, [
    sheet("事故记录", ["事故编号", "任务编号", "执行编号", "对象编号", "traceId", "错误码", "发生时间", "级别", "现象", "状态"], incidents, "#9C3D10"),
    sheet("根因与修复", ["事故编号", "恢复案例", "根因", "处置动作", "风险", "回滚", "恢复验证", "最终状态"], repairs, "#7A3E00"),
    sheet("证据索引", ["事故编号", "traceId", "来源URI", "观察时间", "可信度", "可信依据", "来源状态"], evidence, "#355E3B"),
    dictionarySheet(["事故记录", "根因与修复", "证据索引"], [incidents, repairs, evidence]),
    validationSheet(topic, scope, incidents.length, "事故、根因、处置、回滚、验证和证据必须一一关联"),
  ]);
}

/** 十类任务案例工作簿同时保存成功和失败任务；失败明细只在这类文件中出现。 */
function buildTaskCaseWorkbook(scope, topic, mode) {
  const cases = Array.from({ length: 240 }, (_, index) => {
    const ids = correlationIds(scope, index);
    const failed = index % 3 !== 0;
    const error = ERROR_CATALOG[index % ERROR_CATALOG.length];
    return [`CASE-${topic.code}-${String(index + 1).padStart(4, "0")}`, ids.taskId, ids.executionId, mode, SOURCES[index % SOURCES.length], TARGETS[index % TARGETS.length], ids.configVersion, 200 + (index % 8) * 100, 1 + (index % Math.max(1, scope.channelCount)), scope.timeoutSeconds + (index % 4) * 30, failed ? error[0] : "", failed ? "FAILED" : "SUCCEEDED"];
  });
  const failures = cases.filter((row) => row[11] === "FAILED").map((row, index) => {
    const error = ERROR_CATALOG[index % ERROR_CATALOG.length];
    const ids = correlationIds(scope, Number(row[0].slice(-4)) - 1);
    return [row[0], row[1], row[2], ids.objectId, ids.traceId, ids.incidentId, error[0], `${error[1]}；核对 ${error[2]}`, `synthetic://datasmart-govern/correlated/${scope.key}/execution/${row[2]}`, syntheticTime(index, 8), Number((0.90 + (index % 10) / 100).toFixed(2)), error[3], ["AUTHENTICATION_FAILED", "PERMISSION_DENIED", "DDL_REQUIRED"].includes(error[0]) ? "ATTENTION_REQUIRED" : "RECOVERED"];
  });
  const parameters = cases.map((row) => [row[0], row[6], row[7], row[8], row[9], `checkpoint-${row[1].slice(-4)}`, "首次授权盒内参数"]);
  return workbookModel(topic, [
    sheet("任务案例", ["案例编号", "任务编号", "执行编号", "模式", "来源", "目标", "配置版本", "batch", "channel", "timeout", "错误码", "结果"], cases, "#1F4E78"),
    sheet("失败任务明细", ["案例编号", "任务编号", "执行编号", "对象编号", "traceId", "事故编号", "错误码", "失败原因", "证据来源", "发生时间", "可信度", "处置", "最终状态"], failures, "#9C3D10"),
    sheet("任务参数", ["案例编号", "配置版本", "batch", "channel", "timeout", "checkpoint", "参数边界"], parameters, "#355E3B"),
    dictionarySheet(["任务案例", "失败任务明细", "任务参数"], [cases, failures, parameters]),
    validationSheet(topic, scope, cases.length, "每个失败任务都必须有原因、证据、处置和最终状态"),
  ]);
}

function workbookModel(topic, sheets) {
  return {
    title: topic.title,
    description: topic.summary,
    sheets,
  };
}

function sheet(name, headers, rows, color) {
  return { name, headers, rows, color };
}

/** 字段说明表只解释相邻业务表字段，不混入别的主题记录。 */
function dictionarySheet(sheetNames, rowSets) {
  const rows = [];
  sheetNames.forEach((sheetName, sheetIndex) => {
    const width = Math.max(1, ...(rowSets[sheetIndex] || []).map((row) => row.length));
    for (let columnIndex = 0; columnIndex < width; columnIndex += 1) {
      rows.push([sheetName, `第 ${columnIndex + 1} 列`, "string/number", `解释 ${sheetName} 的第 ${columnIndex + 1} 个字段`, "按该表业务合同校验", "internal/synthetic"]);
    }
  });
  return sheet("字段说明", ["工作表", "字段", "类型", "说明", "校验", "敏感级别"], rows, "#5B4B8A");
}

/** 校验表汇总规模、范围、精确码和主题规则。 */
function validationSheet(topic, scope, primaryCount, rule) {
  return sheet("校验", ["检查项", "结果或规则"], [
    ["主记录数", primaryCount],
    ["范围", scope.label],
    ["tenantId", scope.tenantId],
    ["projectId", scope.projectId],
    ["workspaceKey", scope.workspaceKey],
    ["精确码", topic.code],
    ["独立锚点", `${scope.key}:${topic.slug}`],
    ["主题规则", rule],
    ["合成声明", SYNTHETIC_NOTICE],
  ], "#2E7D32");
}

/** 为字段名提供适合大量数据表的稳定列宽。 */
export function semanticWorkbookColumnWidth(header) {
  if (/编号|版本|状态|模式|级别|风险|结果/.test(header)) return 20;
  if (/时间|checkpoint|来源|目标|URI|trace/.test(header)) return 26;
  if (/原因|根因|处置|验证|规则|说明|目标|步骤|判据/.test(header)) return 36;
  if (/batch|channel|timeout|数量|行|毫秒|秒|率|可信度/.test(header)) return 15;
  return 22;
}

/** 按主题生成 TXT/JSON/JSONL/CSV/LOG/SQL，各文件使用自己的业务字段。 */
export function buildSemanticStructuredPayload(scope, topic, entry, actualApiContracts) {
  const anchor = `${scope.key}:${topic.slug}`;
  switch (topic.slug) {
    case "quick-reference": return buildQuickReference(scope, topic, anchor);
    case "operator-faq": return buildOperatorFaq(scope, topic, anchor);
    case "runbook-command-reference": return buildCommandReference(scope, topic, anchor);
    case "error-code-catalog": return buildErrorCodeReference(scope, topic, anchor);
    case "connector-capabilities": return buildConnectorCapabilities(scope, topic, entry, anchor);
    case "agent-state-snapshot": return buildAgentStateSnapshots(scope, topic, entry, anchor);
    case "api-contract-snapshot": return buildApiContractSnapshot(scope, topic, entry, anchor, actualApiContracts);
    case "task-config-versions": return buildTaskConfigVersions(scope, topic, entry, anchor);
    case "recovery-events": return buildRecoveryEvents(scope, topic, anchor);
    case "task-case-library": return buildTaskCaseLibrary(scope, topic, anchor);
    case "audit-events": return buildAuditEvents(scope, topic, anchor);
    case "recovery-decision-trace": return buildRecoveryDecisionTrace(scope, topic, anchor);
    case "successful-runs": return buildSuccessfulRunsCsv(scope, topic, anchor);
    case "connector-inventory": return buildConnectorInventoryCsv(scope, topic, anchor);
    case "field-profile-statistics": return buildFieldProfileCsv(scope, topic, anchor);
    case "alert-history": return buildAlertHistoryCsv(scope, topic, anchor);
    case "worker-execution": return buildWorkerLog(scope, topic, anchor);
    case "kafka-consumer-lag": return buildKafkaLagLog(scope, topic, anchor);
    case "persistence-snapshot": return buildPersistenceSql(scope, topic, anchor);
    case "database-recovery-ledger": return buildRecoveryLedgerSql(scope, topic, anchor);
    default: throw new Error(`没有为结构化主题配置语义化内容：${topic.slug}`);
  }
}

/** 快速参考只列入口、状态、快捷判断和文档指针。 */
function buildQuickReference(scope, topic, anchor) {
  const lines = structuredHeader(scope, topic, anchor);
  lines.push("一、产品入口速查");
  USER_OPERATION_BLUEPRINTS.forEach((item, index) => lines.push(`${index + 1}. ${item[0]} | 入口=${item[1]} | 角色=${item[2]} | 权限=${item[3]} | 结果=${item[5]}`));
  lines.push("", "二、状态速查");
  const states = ["DRAFT", "PUBLISHED", "QUEUED", "RUNNING", "PAUSED", "SUCCEEDED", "FAILED", "TERMINATED", "ATTENTION_REQUIRED", "RECOVERED", "WAITING_CONFIRMATION", "COMPLETED"];
  for (let index = 0; index < 80; index += 1) {
    const state = states[index % states.length];
    lines.push(`${index + 1}. ${state} | 对象=${["任务", "execution", "对象台账", "Agent Run", "Recovery case"][index % 5]} | 用户操作=${state === "FAILED" ? "查看证据和恢复状态" : "按页面允许动作继续"}`);
  }
  lines.push("", "三、文档指针");
  for (let index = 0; index < 100; index += 1) {
    lines.push(`${index + 1}. 主题=${["用户操作", "管理员操作", "接口合同", "部署", "运维", "事故", "测试", "恢复", "安全审批"][index % 9]} | 精确码=${topic.code}-REF-${String(index + 1).padStart(3, "0")} | 范围=${scope.label}`);
  }
  return renderTextLines(lines);
}

/** 运维 FAQ 以问答组织，不追加事故案例。 */
function buildOperatorFaq(scope, topic, anchor) {
  const lines = structuredHeader(scope, topic, anchor);
  const questions = OPERATIONS_JOBS.map((job) => [`如何执行${job[0]}？`, `按${job[1]}检查 ${job[2]}，读取 ${job[3]}，正常判据为 ${job[4]}。`]);
  lines.push("一、运维常见问题");
  for (let index = 0; index < 140; index += 1) {
    const [question, answer] = questions[index % questions.length];
    lines.push(`Q${String(index + 1).padStart(3, "0")}：${question}`, `A：${answer}`, "边界：需要凭据、权限、DDL、删除、覆盖或扩大范围时停止并升级。", "");
  }
  return renderTextLines(lines);
}

/** 命令参考逐项记录用途、权限、占位命令、预期和回滚。 */
function buildCommandReference(scope, topic, anchor) {
  const lines = structuredHeader(scope, topic, anchor);
  for (let index = 0; index < 200; index += 1) {
    const job = OPERATIONS_JOBS[index % OPERATIONS_JOBS.length];
    lines.push(
      `CMD-${String(index + 1).padStart(4, "0")} | 名称=${job[0]} | 目标=${job[2]}`,
      `用途：${job[3]}`,
      "权限：只读诊断优先；变更命令需要对应运维权限和变更窗口。",
      `命令：datasmart-ops inspect --target <${job[2].replaceAll(" ", "-")}> --scope <tenant/project> --trace-id <trace-id>`,
      `预期：${job[4]}`,
      "回滚：该示例为只读命令；任何变更命令必须在正式 Runbook 中声明回滚。",
      "",
    );
  }
  return renderTextLines(lines);
}

/** 错误码目录逐码解释含义、证据、自治边界和人工指引。 */
function buildErrorCodeReference(scope, topic, anchor) {
  const lines = structuredHeader(scope, topic, anchor);
  ERROR_CATALOG.forEach((error, index) => {
    lines.push(
      `${index + 1}. ${error[0]}`,
      `含义：${error[1]}`,
      `定位证据：${error[2]}`,
      `允许处置：${error[3]}`,
      "重试资格：只有瞬态错误或完成根因修复后才允许重试。",
      "退出条件：凭据、权限、DDL、覆盖、扩大范围、有损转换或证据冲突。",
      "用户提示：返回根因、证据、所需权限、步骤、影响、回滚和验证方法。",
      "日志字段：errorCode、traceId、taskId、executionId、objectId、occurredAt。",
      "证据字段：sourceUri、observedAt、confidence、confidenceBasis、sourceStatus。",
      "",
    );
  });
  return renderTextLines(lines);
}

/** 连接器能力 JSON 只记录连接器版本、模式、限流和容量。 */
function buildConnectorCapabilities(scope, topic, entry, anchor) {
  const records = Array.from({ length: 240 }, (_, index) => ({
    recordId: `${topic.code}-${String(index + 1).padStart(4, "0")}`,
    connectorId: `connector-${String(index + 1).padStart(4, "0")}`,
    connectorType: ["POSTGRESQL", "MYSQL", "KAFKA", "CSV", "REST_API", "S3", "MONGODB"][index % 7],
    version: `2.${index % 10}.${index % 20}`,
    supportedModes: ["FULL", "INCREMENTAL", index % 2 === 0 ? "CDC" : "FILE"],
    maximumBatchSize: 500 + (index % 8) * 500,
    maximumChannels: 1 + (index % 8),
    maximumTimeoutSeconds: 120 + (index % 6) * 60,
    rateLimitRowsPerSecond: 5_000 + index * 100,
    metadataRefreshSupported: true,
    checkpointReplaySupported: index % 5 !== 0,
    observedAt: syntheticTime(index, 60),
    sourceUri: `synthetic://datasmart-govern/connectors/${scope.key}/${index + 1}`,
    confidence: Number((0.94 + (index % 6) / 100).toFixed(2)),
    sourceStatus: "COMPLETE",
  }));
  return jsonDocument(scope, topic, entry, anchor, records);
}

/** Agent 状态快照 JSON 记录图节点和关联状态，不伪装成事故目录。 */
function buildAgentStateSnapshots(scope, topic, entry, anchor) {
  const nodes = ["KNOWLEDGE_AGENT", "DATASOURCE_AGENT", "DATA_SYNC_AGENT", "PRECHECK_AGENT", "RECOVERY_AGENT", "MONITOR_AGENT", "JAVA_AUDIT", "WORKER", "FINAL_VERIFICATION"];
  const records = Array.from({ length: 240 }, (_, index) => {
    const ids = correlationIds(scope, index);
    return {
      recordId: `${topic.code}-${String(index + 1).padStart(4, "0")}`,
      sessionId: `session-${scope.key}-${String(index + 1).padStart(4, "0")}`,
      runId: `run-${scope.key}-${String(index + 1).padStart(4, "0")}`,
      taskId: ids.taskId,
      executionId: ids.executionId,
      currentNode: nodes[index % nodes.length],
      state: ["READY", "RUNNING", "SUCCEEDED", "WAITING_CONFIRMATION"][index % 4],
      checkpointSequence: index + 1,
      specialistCount: 1 + (index % 6),
      evidenceCount: 1 + (index % 8),
      updatedAt: syntheticTime(index, 5),
      sourceUri: `synthetic://datasmart-govern/agent-state/${scope.key}/${index + 1}`,
      sourceStatus: "COMPLETE",
    };
  });
  return jsonDocument(scope, topic, entry, anchor, records);
}

/** API 快照 JSON 直接序列化真实源码合同的低敏字段。 */
function buildApiContractSnapshot(scope, topic, entry, anchor, contracts) {
  const records = contracts.map((contract, index) => ({
    recordId: `${topic.code}-${String(index + 1).padStart(4, "0")}`,
    contractId: contract.contractId,
    module: contract.module,
    controller: contract.controller,
    methodName: contract.methodName,
    sourceFile: contract.sourceFile,
    sourceLine: contract.sourceLine,
    transport: contract.transport,
    httpMethod: contract.httpMethod,
    visibility: contract.visibility,
    declaredPaths: contract.declaredPaths,
    externalPaths: contract.externalPaths,
    purpose: contract.purpose,
    parameters: contract.parameters.map((parameter) => ({ name: parameter.name, location: parameter.location, type: parameter.javaType, required: parameter.required })),
    requestType: contract.requestType,
    responseType: contract.responseType,
  }));
  return jsonDocument(scope, topic, entry, anchor, records);
}

/** 任务配置版本 JSON 只记录不可变版本、参数与发布信息。 */
function buildTaskConfigVersions(scope, topic, entry, anchor) {
  const records = Array.from({ length: 240 }, (_, index) => {
    const ids = correlationIds(scope, index);
    return {
      recordId: `${topic.code}-${String(index + 1).padStart(4, "0")}`,
      taskId: ids.taskId,
      configVersion: ids.configVersion,
      previousVersion: ids.lastSuccessfulConfigVersion,
      syncMode: ["FULL", "INCREMENTAL", "CDC", "FILE", "API", "KAFKA"][index % 6],
      sourceObject: SOURCES[index % SOURCES.length],
      targetObject: TARGETS[index % TARGETS.length],
      batchSize: 200 + (index % 8) * 100,
      channelCount: 1 + (index % 6),
      timeoutSeconds: scope.timeoutSeconds + (index % 4) * 30,
      publishedByRole: "PROJECT_OWNER",
      publishedAt: syntheticTime(index, 30),
      immutable: true,
      sourceUri: `synthetic://datasmart-govern/task-config/${scope.key}/${ids.taskId}/${ids.configVersion}`,
    };
  });
  return jsonDocument(scope, topic, entry, anchor, records);
}

/** 恢复事件 JSONL 保存恢复流程事件。 */
function buildRecoveryEvents(scope, topic, anchor) {
  const eventTypes = ["RECOVERY_TRIGGERED", "DIAGNOSIS_COMPLETED", "RAG_DECIDED", "ACTION_PREVIEWED", "ACTION_APPLIED", "FAILED_OBJECT_REPLAYED", "POSTCHECK_COMPLETED", "RECOVERY_CLOSED"];
  return jsonl(Array.from({ length: 600 }, (_, index) => {
    const ids = correlationIds(scope, Math.floor(index / 2));
    const error = ERROR_CATALOG[Math.floor(index / 2) % ERROR_CATALOG.length];
    return structuredEvent(scope, topic, anchor, index, {
      ...ids,
      eventType: eventTypes[index % eventTypes.length],
      errorCode: error[0],
      action: error[3],
      cycle: 1 + (index % Math.max(1, scope.retryLimit)),
      state: index % 8 === 7 ? "RECOVERED" : "PROCESSING",
    });
  }));
}

/** 任务案例 JSONL 逐条保存失败原因、根因、证据与处置，满足案例资料职责。 */
function buildTaskCaseLibrary(scope, topic, anchor) {
  return jsonl(Array.from({ length: 600 }, (_, index) => {
    const ids = correlationIds(scope, index);
    const error = ERROR_CATALOG[index % ERROR_CATALOG.length];
    const manual = ["AUTHENTICATION_FAILED", "PERMISSION_DENIED", "DDL_REQUIRED"].includes(error[0]);
    return structuredEvent(scope, topic, anchor, index, {
      ...ids,
      caseType: ["FULL", "INCREMENTAL", "CDC", "FILE", "API", "KAFKA"][index % 6],
      errorCode: error[0],
      failureReason: `${error[1]}；需要核对 ${error[2]}`,
      rootCause: `${error[1]}，与 ${ids.lastSuccessfulConfigVersion} 的差异已确认。`,
      evidenceSource: `synthetic://datasmart-govern/correlated/${scope.key}/execution/${ids.executionId}`,
      repairAction: manual ? "MANUAL_HANDOFF" : error[3],
      finalState: manual ? "ATTENTION_REQUIRED" : "RECOVERED",
    });
  }));
}

/** 审计事件 JSONL 只记录主体、资源、动作、决定和摘要。 */
function buildAuditEvents(scope, topic, anchor) {
  return jsonl(Array.from({ length: 600 }, (_, index) => structuredEvent(scope, topic, anchor, index, {
    auditId: `AUD-${scope.key}-${String(index + 1).padStart(4, "0")}`,
    actorId: `actor-${(index % 30) + 1}`,
    actorRole: ["ORDINARY_USER", "PROJECT_OWNER", "OPERATOR", "AUDITOR", "TENANT_ADMINISTRATOR", "PLATFORM_ADMINISTRATOR", "SERVICE_ACCOUNT"][index % 7],
    resourceType: ["SYNC_TASK", "SYNC_EXECUTION", "AI_RUNTIME", "PROJECT", "ROUTE_POLICY", "AUDIT_LOG"][index % 6],
    resourceId: `resource-${String(index + 1).padStart(4, "0")}`,
    action: ["VIEW", "CREATE", "UPDATE", "EXECUTE", "APPROVE", "EXPORT"][index % 6],
    decision: index % 11 === 0 ? "DENY" : "ALLOW",
    reasonCode: index % 11 === 0 ? "SCOPE_NOT_ALLOWED" : "POLICY_MATCHED",
  })));
}

/** Recovery 决策轨迹 JSONL 保存证据、候选、风险和退出判断。 */
function buildRecoveryDecisionTrace(scope, topic, anchor) {
  return jsonl(Array.from({ length: 600 }, (_, index) => {
    const ids = correlationIds(scope, index);
    const error = ERROR_CATALOG[index % ERROR_CATALOG.length];
    return structuredEvent(scope, topic, anchor, index, {
      ...ids,
      errorCode: error[0],
      diagnosis: error[1],
      evidenceRequired: error[2],
      retrievalDecision: index % 3 === 0 ? "SEARCH" : "SKIP",
      candidateAction: error[3],
      risk: ["AUTHENTICATION_FAILED", "PERMISSION_DENIED", "DDL_REQUIRED"].includes(error[0]) ? "HIGH" : "LOW",
      withinAuthorizationBox: !["AUTHENTICATION_FAILED", "PERMISSION_DENIED", "DDL_REQUIRED"].includes(error[0]),
    });
  }));
}

/** 成功运行 CSV 不包含错误码或失败原因。 */
function buildSuccessfulRunsCsv(scope, topic, anchor) {
  const headers = ["record_id", "artifact_code", "retrieval_anchor", "task_id", "execution_id", "config_version", "sync_mode", "source_object", "target_object", "rows_read", "rows_written", "dirty_records", "duration_ms", "checkpoint", "completed_at", "status", "source_uri"];
  const rows = Array.from({ length: 600 }, (_, index) => {
    const ids = correlationIds(scope, index);
    const count = 10_000 + index * 991;
    return [`RUN-${String(index + 1).padStart(4, "0")}`, topic.code, anchor, ids.taskId, ids.executionId, ids.configVersion, ["FULL", "INCREMENTAL", "CDC", "FILE", "API", "KAFKA"][index % 6], SOURCES[index % SOURCES.length], TARGETS[index % TARGETS.length], count, count, 0, 30_000 + index * 131, `checkpoint-${String(index + 1).padStart(6, "0")}`, syntheticTime(index, 15), "SUCCEEDED", `synthetic://datasmart-govern/successful-runs/${scope.key}/${index + 1}`];
  });
  return csv(headers, rows);
}

/** 连接器清单 CSV 记录资产和能力，不包含任务错误字段。 */
function buildConnectorInventoryCsv(scope, topic, anchor) {
  const headers = ["record_id", "artifact_code", "retrieval_anchor", "connector_id", "connector_type", "version", "environment", "supported_modes", "max_batch_size", "max_channels", "rate_limit_rps", "health_state", "last_probed_at", "source_uri"];
  const rows = Array.from({ length: 600 }, (_, index) => [`CON-${String(index + 1).padStart(4, "0")}`, topic.code, anchor, `connector-${String(index + 1).padStart(4, "0")}`, ["POSTGRESQL", "MYSQL", "KAFKA", "CSV", "REST_API", "S3", "MONGODB"][index % 7], `2.${index % 10}.${index % 20}`, ["DEV", "TEST", "STAGING"][index % 3], "FULL|INCREMENTAL|CDC", 500 + (index % 8) * 500, 1 + (index % 8), 50 + (index % 20) * 10, index % 19 === 0 ? "DEGRADED" : "HEALTHY", syntheticTime(index, 60), `synthetic://datasmart-govern/connectors/${scope.key}/${index + 1}`]);
  return csv(headers, rows);
}

/** 字段画像 CSV 保存数据类型、空值率、唯一率、长度和分布统计。 */
function buildFieldProfileCsv(scope, topic, anchor) {
  const headers = ["record_id", "artifact_code", "retrieval_anchor", "dataset_id", "field_name", "data_type", "sample_count", "null_count", "null_ratio", "distinct_count", "distinct_ratio", "min_value", "max_value", "max_length", "profiled_at", "source_uri"];
  const fields = ["order_id", "customer_id", "region_code", "amount", "occurred_at", "status", "currency", "product_id", "quantity", "email_hash"];
  const rows = Array.from({ length: 600 }, (_, index) => {
    const sample = 100_000 + index * 100;
    const nulls = index % 7 === 0 ? index % 200 : 0;
    const distinct = Math.max(1, sample - (index % 1000));
    return [`PROF-${String(index + 1).padStart(4, "0")}`, topic.code, anchor, `dataset-${String((index % 40) + 1).padStart(3, "0")}`, fields[index % fields.length], ["varchar", "bigint", "decimal", "timestamp"][index % 4], sample, nulls, Number((nulls / sample).toFixed(6)), distinct, Number((distinct / sample).toFixed(6)), index % 4 === 2 ? "0.00" : "", index % 4 === 2 ? String(100_000 + index) : "", 16 + (index % 128), syntheticTime(index, 120), `synthetic://datasmart-govern/field-profile/${scope.key}/${index + 1}`];
  });
  return csv(headers, rows);
}

/** 告警历史 CSV 保存告警触发、响应和恢复信息。 */
function buildAlertHistoryCsv(scope, topic, anchor) {
  const headers = ["record_id", "artifact_code", "retrieval_anchor", "alert_id", "component", "severity", "metric", "threshold", "observed_value", "triggered_at", "acknowledged_at", "resolved_at", "owner_role", "response_action", "status", "source_uri"];
  const rows = Array.from({ length: 600 }, (_, index) => [`ALR-${String(index + 1).padStart(4, "0")}`, topic.code, anchor, `alert-${String(index + 1).padStart(4, "0")}`, OPERATIONS_JOBS[index % OPERATIONS_JOBS.length][2], ["P1", "P2", "P3"][index % 3], ["error_rate", "latency_p95", "consumer_lag", "disk_usage", "running_duration"][index % 5], 80, 81 + (index % 20), syntheticTime(index, 10), syntheticTime(index, 10), syntheticTime(index, 10), index % 4 === 0 ? "PLATFORM_ADMINISTRATOR" : "OPERATOR", "按告警 Runbook 检查并记录结论", "RESOLVED", `synthetic://datasmart-govern/alerts/${scope.key}/${index + 1}`]);
  return csv(headers, rows);
}

/** Worker 日志保存执行错误、诊断、处置和验证四阶段。 */
function buildWorkerLog(scope, topic, anchor) {
  const phases = ["OBJECT_FAILED", "RECOVERY_DIAGNOSED", "REPAIR_APPLIED_OR_HANDOFF", "POST_RECOVERY_VERIFIED"];
  const rows = Array.from({ length: 1200 }, (_, index) => {
    const caseIndex = Math.floor(index / phases.length);
    const phaseIndex = index % phases.length;
    const ids = correlationIds(scope, caseIndex);
    const error = ERROR_CATALOG[caseIndex % ERROR_CATALOG.length];
    const manual = ["AUTHENTICATION_FAILED", "PERMISSION_DENIED", "DDL_REQUIRED"].includes(error[0]);
    const state = phaseIndex === 0 ? "FAILED" : phaseIndex === 3 ? (manual ? "ATTENTION_REQUIRED" : "RECOVERED") : "RECOVERING";
    return `${syntheticTime(index, 2)} level=${phaseIndex === 0 ? "ERROR" : phaseIndex === 1 ? "WARN" : "INFO"} phase=${phases[phaseIndex]} traceId=${ids.traceId} taskId=${ids.taskId} executionId=${ids.executionId} objectId=${ids.objectId} incidentId=${ids.incidentId} recoveryCaseId=${ids.recoveryCaseId} errorCode=${error[0]} state=${state} retryable=${!manual} configVersion=${ids.configVersion} lastSuccessfulConfigVersion=${ids.lastSuccessfulConfigVersion} sourceUri=synthetic://datasmart-govern/correlated/${scope.key}/execution/${ids.executionId} confidence=${(0.90 + (caseIndex % 10) / 100).toFixed(2)} artifactCode=${topic.code} retrievalAnchor=${anchor} message="${error[1]}；证据=${error[2]}；处置=${manual ? "人工接管" : error[3]}"`;
  });
  return `${SYNTHETIC_NOTICE}\n${rows.join("\n")}\n`;
}

/** Kafka 日志专门记录 group、partition、offset、lag、retry 和 DLT。 */
function buildKafkaLagLog(scope, topic, anchor) {
  const rows = Array.from({ length: 1200 }, (_, index) => {
    const ids = correlationIds(scope, Math.floor(index / 4));
    const partition = index % 24;
    const currentOffset = 100_000 + index * 113;
    const lag = index % 17 === 0 ? 5_000 + index : index % 200;
    return `${syntheticTime(index, 2)} level=${lag > 1000 ? "WARN" : "INFO"} component=kafka-consumer groupId=${["agent-runtime", "python-recovery", "data-sync-worker", "task-command"][index % 4]} topic=${["agent-tool-plan", "autopilot-recovery", "data-sync-command", "runtime-event"][index % 4]} partition=${partition} currentOffset=${currentOffset} endOffset=${currentOffset + lag} lag=${lag} retryTopicLag=${index % 50} dltCount=${index % 97 === 0 ? 1 : 0} processingLatencyMs=${20 + (index % 300)} traceId=${ids.traceId} taskId=${ids.taskId} eventId=${ids.eventId} observedAt=${syntheticTime(index, 2)} artifactCode=${topic.code} retrievalAnchor=${anchor}`;
  });
  return `${SYNTHETIC_NOTICE}\n${rows.join("\n")}\n`;
}

/** 任务持久化 SQL 快照保存任务、execution、对象台账和恢复案例。 */
function buildPersistenceSql(scope, topic, anchor) {
  const statements = sqlHeader(topic, anchor);
  for (let index = 0; index < 320; index += 1) {
    const ids = correlationIds(scope, index);
    statements.push(`INSERT INTO synthetic_task_definition (task_id, tenant_id, project_id, config_version, state) VALUES ('${ids.taskId}', '${scope.tenantId}', '${scope.projectId}', '${ids.configVersion}', 'PUBLISHED');`);
    statements.push(`INSERT INTO synthetic_task_execution (execution_id, task_id, state, started_at, completed_at) VALUES ('${ids.executionId}', '${ids.taskId}', 'SUCCEEDED', '${syntheticTime(index, 10)}', '${syntheticTime(index, 10)}');`);
    statements.push(`INSERT INTO synthetic_object_ledger (execution_id, object_id, attempt_count, object_state, checkpoint) VALUES ('${ids.executionId}', '${ids.objectId}', 1, 'SUCCEEDED', 'checkpoint-${String(index + 1).padStart(6, "0")}');`);
    statements.push(`INSERT INTO synthetic_recovery_case (case_id, task_id, execution_id, case_state, reason_code) VALUES ('${ids.recoveryCaseId}', '${ids.taskId}', '${ids.executionId}', 'NOT_REQUIRED', 'INITIAL_RUN_SUCCEEDED');`);
  }
  statements.push("COMMIT;");
  return `${statements.join("\n")}\n`;
}

/** 恢复台账 SQL 保存恢复案例、证据和动作。 */
function buildRecoveryLedgerSql(scope, topic, anchor) {
  const statements = sqlHeader(topic, anchor);
  for (let index = 0; index < 320; index += 1) {
    const ids = correlationIds(scope, index);
    const error = ERROR_CATALOG[index % ERROR_CATALOG.length];
    const manual = ["AUTHENTICATION_FAILED", "PERMISSION_DENIED", "DDL_REQUIRED"].includes(error[0]);
    statements.push(`INSERT INTO synthetic_recovery_case (case_id, tenant_id, project_id, task_id, execution_id, object_id, trace_id, incident_id, cycle, max_cycles, case_state, reason_code, created_at) VALUES ('${ids.recoveryCaseId}', '${scope.tenantId}', '${scope.projectId}', '${ids.taskId}', '${ids.executionId}', '${ids.objectId}', '${ids.traceId}', '${ids.incidentId}', ${1 + (index % Math.max(1, scope.retryLimit))}, ${scope.retryLimit}, '${manual ? "ATTENTION_REQUIRED" : "RECOVERED"}', '${error[0]}', '${syntheticTime(index, 5)}');`);
    statements.push(`INSERT INTO synthetic_evidence_record (case_id, source_uri, observed_at, confidence, confidence_basis, source_status) VALUES ('${ids.recoveryCaseId}', 'synthetic://datasmart-govern/correlated/${scope.key}/execution/${ids.executionId}', '${syntheticTime(index, 5)}', ${(0.90 + (index % 10) / 100).toFixed(2)}, 'LOG_CONFIG_RUNBOOK_CORROBORATION', 'COMPLETE');`);
    statements.push(`INSERT INTO synthetic_recovery_action (case_id, action_code, risk_level, action_state, rollback_instruction, verification_instruction) VALUES ('${ids.recoveryCaseId}', '${manual ? "MANUAL_HANDOFF" : "GOVERNED_REPAIR"}', '${manual ? "HIGH" : "LOW"}', '${manual ? "BLOCKED" : "SUCCEEDED"}', '恢复 ${ids.lastSuccessfulConfigVersion}', '执行 PRECHECK_AGENT 与 MONITOR_AGENT 验证');`);
  }
  statements.push("COMMIT;");
  return `${statements.join("\n")}\n`;
}

/** 结构化 JSON 文档统一外层只承载来源元数据，records 内容由各主题独立定义。 */
function jsonDocument(scope, topic, entry, anchor, records) {
  return `${JSON.stringify({
    synthetic: true,
    notice: SYNTHETIC_NOTICE,
    schemaVersion: "datasmart.rag-structured-corpus.v3",
    documentId: entry.documentId,
    artifactCode: topic.code,
    retrievalAnchor: anchor,
    scope: { tenantId: scope.tenantId, projectId: scope.projectId, workspaceKey: scope.workspaceKey },
    title: topic.title,
    summary: topic.summary,
    records,
  }, null, 2)}\n`;
}

/** JSONL 事件公共信封只提供范围、来源和时间，不强迫不同主题共享业务字段。 */
function structuredEvent(scope, topic, anchor, index, payload) {
  return {
    synthetic: true,
    artifactCode: topic.code,
    retrievalAnchor: anchor,
    recordId: `${topic.code}-${String(index + 1).padStart(4, "0")}`,
    tenantId: scope.tenantId,
    projectId: scope.projectId,
    ...payload,
    occurredAt: syntheticTime(index, 3),
    sourceUri: `synthetic://datasmart-govern/${topic.slug}/${scope.key}/${index + 1}`,
    confidence: Number((0.90 + (index % 10) / 100).toFixed(2)),
    confidenceBasis: "SYNTHETIC_STRUCTURED_RECORD",
    sourceStatus: "COMPLETE",
  };
}

function jsonl(records) {
  return `${records.map((record) => JSON.stringify(record)).join("\n")}\n`;
}

/** CSV 写入使用标准双引号转义，避免说明文本中的逗号破坏列结构。 */
function csv(headers, rows) {
  return `${[headers, ...rows].map((row) => row.map(csvValue).join(",")).join("\n")}\n`;
}

function csvValue(value) {
  const text = value === null || value === undefined ? "" : String(value);
  return /[",\n\r]/.test(text) ? `"${text.replaceAll('"', '""')}"` : text;
}

function structuredHeader(scope, topic, anchor) {
  return [topic.title, SYNTHETIC_NOTICE, `精确码：${topic.code}`, `独立锚点：${anchor}`, `范围：${scope.label}`, ""];
}

function sqlHeader(topic, anchor) {
  return [`-- ${topic.title}`, `-- ${SYNTHETIC_NOTICE}`, `-- 精确码：${topic.code}`, `-- 独立锚点：${anchor}`, "BEGIN;"];
}

/** 去掉构建过程中的尾部空行，并为文本文件保留一个标准换行符。 */
function renderTextLines(lines) {
  return `${lines.join("\n").trimEnd()}\n`;
}
