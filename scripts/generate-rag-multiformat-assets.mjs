#!/usr/bin/env node
/**
 * 生成 DataSmart Govern RAG 异构合成语料。
 *
 * 本脚本只写入评测目录，不读取网络、环境变量、数据库或客户文件。DOCX 使用 `docx` 生成，XLSX
 * 使用 `@oai/artifact-tool` 生成，其余格式使用 Node.js 标准库。生成后的 catalog 由 Python 主生成器
 * 读取，再统一计算原文件哈希、提取文本哈希、Manifest 和黄金评测集。
 */

import fs from "node:fs/promises";
import path from "node:path";
import process from "node:process";
import {
  AlignmentType,
  BorderStyle,
  Document,
  Footer,
  Header,
  HeadingLevel,
  LevelFormat,
  Packer,
  PageNumber,
  Paragraph,
  ShadingType,
  Table,
  TableCell,
  TableLayoutType,
  TableRow,
  TextRun,
  WidthType,
} from "docx";
import { SpreadsheetFile, Workbook } from "@oai/artifact-tool";


const repositoryRoot = process.env.DATASMART_REPOSITORY_ROOT
  ? path.resolve(process.env.DATASMART_REPOSITORY_ROOT)
  : path.resolve(import.meta.dirname, "..");
const assetRoot = path.join(repositoryRoot, "python-ai-runtime", "evaluation", "rag");
const qaRoot = process.env.DATASMART_RAG_ARTIFACT_QA_ROOT
  ? path.resolve(process.env.DATASMART_RAG_ARTIFACT_QA_ROOT)
  : null;
const requestedFormat = readArgument("--format") ?? "all";

const scopes = [
  {
    key: "global",
    tenantId: "*",
    projectId: "*",
    workspaceKey: "*",
    label: "全局产品基线",
    lagBudgetMinutes: 10,
    retryLimit: 3,
    retentionDays: 180,
    batchSize: 500,
    channelCount: 2,
    timeoutSeconds: 120,
  },
  {
    key: "tenant-10-project-101",
    tenantId: "10",
    projectId: "101",
    workspaceKey: "tenant-10-project-101",
    label: "租户 10 项目 101 合成演示空间",
    lagBudgetMinutes: 6,
    retryLimit: 4,
    retentionDays: 120,
    batchSize: 800,
    channelCount: 4,
    timeoutSeconds: 180,
  },
  {
    key: "tenant-10-project-102",
    tenantId: "10",
    projectId: "102",
    workspaceKey: "tenant-10-project-102",
    label: "租户 10 项目 102 合成演示空间",
    lagBudgetMinutes: 8,
    retryLimit: 2,
    retentionDays: 90,
    batchSize: 300,
    channelCount: 2,
    timeoutSeconds: 150,
  },
  {
    key: "tenant-20-project-201",
    tenantId: "20",
    projectId: "201",
    workspaceKey: "tenant-20-project-201",
    label: "租户 20 项目 201 合成演示空间",
    lagBudgetMinutes: 12,
    retryLimit: 5,
    retentionDays: 150,
    batchSize: 1000,
    channelCount: 6,
    timeoutSeconds: 240,
  },
];

const docxTopics = [
  {
    slug: "manual-user-guide",
    title: "DataSmart Govern 用户操作手册",
    code: "DOC-USR-701",
    category: "user_manual",
    sourceType: "document",
    tags: ["用户手册", "同步任务", "首次授权"],
    summary: "用户从自然语言需求创建同步任务，首次执行前确认授权盒，随后查看执行证据、引用和最终验证。",
    question: "普通用户怎样从需求描述创建同步任务并完成首次授权？",
    sections: [
      ["创建任务", "选择项目与应用，描述来源、目标、调度周期、质量要求和允许的自治恢复边界。"],
      ["确认计划", "核对 Agent 生成的任务参数、影响范围、审批主体和数据预检结果。"],
      ["查看执行", "通过任务详情和 WebSocket 事件查看当前节点、对象台账、重试次数和引用证据。"],
      ["处理退出", "遇到凭据、权限、DDL、覆盖数据或扩大同步范围时，按根因和操作指引交由有权限用户处理。"],
    ],
  },
  {
    slug: "manual-administrator-guide",
    title: "DataSmart Govern 管理员手册",
    code: "DOC-ADM-714",
    category: "administrator_manual",
    sourceType: "document",
    tags: ["管理员手册", "RBAC", "租户隔离"],
    summary: "管理员维护项目、应用、角色、审批策略和密钥引用，不能把模型判断当作授权事实。",
    question: "管理员如何配置项目角色、审批策略和知识范围隔离？",
    sections: [
      ["组织边界", "只保留项目与应用层级；workspaceKey 在 Agent 工具中表示受限本地执行目录，不是业务层级。"],
      ["角色授权", "授权同时校验角色、租户、项目、动作和资源，跨范围导出需要可信控制面审批事实。"],
      ["密钥管理", "模型、Embedding、Reranker 和连接器密钥通过 Secret 注入，禁止写入文档、日志或 Git。"],
      ["审计复核", "定期检查首次授权盒、无人值守恢复动作、人工退出原因和不可变审计摘要。"],
    ],
  },
  {
    slug: "manual-deployment-guide",
    title: "DataSmart Govern 部署手册",
    code: "DOC-DEP-731",
    category: "deployment_manual",
    sourceType: "runbook",
    tags: ["部署手册", "Kafka", "pgvector"],
    summary: "部署基线为 JDK 21、Spring Boot 3.5.11、Kafka、PostgreSQL/pgvector 和 Python AI Runtime。",
    question: "部署 DataSmart Govern 时需要按什么顺序验证 Java、Kafka、pgvector 和 AI Runtime？",
    sections: [
      ["基础设施", "先启动 PostgreSQL/pgvector、Kafka 与对象存储，再启动 Java 服务和 Python AI Runtime。"],
      ["配置注入", "数据库 DSN、Provider Key、Gateway HMAC 和对象存储凭据仅通过部署 Secret 提供。"],
      ["健康检查", "验证数据库迁移、Kafka topic、消费者组、模型连通性和 `/actuator/health`。"],
      ["发布验证", "执行只读预检、合成任务 smoke、RAG 范围查询和 Recovery 后置验证后再切换流量。"],
    ],
  },
  {
    slug: "manual-operations-guide",
    title: "DataSmart Govern 运维手册",
    code: "DOC-OPS-746",
    category: "operations_manual",
    sourceType: "runbook",
    tags: ["运维手册", "日志", "指标"],
    summary: "运维排查先查询结构化日志与指标，再比较上次成功配置、连接器能力、Runbook 和历史事故。",
    question: "同步任务异常时运维人员应按什么证据顺序排查？",
    sections: [
      ["发现异常", "按 traceId、taskId、executionId、objectId 和 errorCode 查询结构化日志。"],
      ["建立基线", "比较当前配置与上一次成功配置，重点检查字段映射、默认值、非空、外键和 checkpoint。"],
      ["核对容量", "查询连接器版本、限流、批量、并发、超时和目标端容量，不盲目扩大资源。"],
      ["恢复闭环", "低风险修复在首次授权盒内自动执行；越权动作退出 Loop 并给出根因、权限、步骤、影响、回滚和验证。"],
    ],
  },
  {
    slug: "record-operations-incident",
    title: "同步平台运维记录",
    code: "DOC-REC-752",
    category: "operations_record",
    sourceType: "incident",
    tags: ["运维记录", "事故时间线", "恢复验证"],
    summary: "合成运维记录描述一次字段非空约束失败，从日志证据、映射修复到分片 replay 和最终验证的时间线。",
    question: "字段非空约束事故的时间线、自动修复和验证结果是什么？",
    sections: [
      ["告警", "MONITOR_AGENT 发现目标字段 region_code 的空值写入错误并关联失败分片。"],
      ["诊断", "RECOVERY_AGENT 对比上次成功映射，确认新字段缺少允许范围内的静态默认值 CN-UNKNOWN。"],
      ["修复", "在不修改目标 DDL 的前提下更新任务字段映射，对失败分片执行 checkpoint replay。"],
      ["验证", "PRECHECK_AGENT 验证映射类型、非空覆盖和外键引用，MONITOR_AGENT 确认脏数据为零。"],
    ],
  },
  {
    slug: "report-platform-test",
    title: "DataSmart Govern 平台测试报告",
    code: "DOC-TST-768",
    category: "test_report",
    sourceType: "document",
    tags: ["测试报告", "六Agent", "RAG评测"],
    summary: "测试报告覆盖六 Specialist、审批双主体、Kafka 异步桥、RAG 引用、自动恢复和前后端契约。",
    question: "平台测试报告覆盖了哪些 Agent、治理和异步执行检查？",
    sections: [
      ["Agent 测试", "验证 KNOWLEDGE、DATASOURCE、DATA_SYNC、PRECHECK、RECOVERY、MONITOR 六类 Specialist 的成功和恢复路径。"],
      ["治理测试", "验证首次授权、审批双主体、幂等、范围隔离、审计和风险升级。"],
      ["RAG 测试", "验证混合召回、BGE-M3、Reranker、引用来源、时间、可信度和无证据拒答。"],
      ["执行测试", "验证 Kafka outbox、worker 台账、失败分片 replay、最终状态和 WebSocket 事件形状。"],
    ],
  },
  {
    slug: "product-feature-specification",
    title: "DataSmart Govern 产品特性说明",
    code: "DOC-PRD-783",
    category: "product_features",
    sourceType: "document",
    tags: ["产品特性", "多Agent", "自治恢复"],
    summary: "产品以受治理的多 Agent 执行闭环为核心，提供需求规划、证据检索、同步执行、预检、恢复和监控。",
    question: "产品的六类 Agent 如何协作完成数据同步闭环？",
    sections: [
      ["需求到计划", "KNOWLEDGE_AGENT 按需检索证据，DATASOURCE_AGENT 核对连接器和元数据，DATA_SYNC_AGENT 形成执行计划。"],
      ["执行前治理", "PRECHECK_AGENT 验证范围、配置、权限、字段契约和审批事实。"],
      ["无人值守恢复", "RECOVERY_AGENT 根据错误日志和历史资料选择低风险修复，受最大循环次数约束。"],
      ["最终收敛", "MONITOR_AGENT 检查任务结果、脏数据、延迟、台账和引用，输出统一链路状态。"],
    ],
  },
  {
    slug: "reference-api-websocket",
    title: "Agent、任务与数据同步接口说明",
    code: "DOC-API-795",
    category: "api_reference",
    sourceType: "document",
    tags: ["接口说明", "WebSocket", "任务合同"],
    summary: "接口说明定义 Agent 请求、任务创建、执行查询、审批与 WebSocket 事件的稳定字段和状态。",
    question: "Agent、任务和数据同步接口之间使用哪些稳定标识关联？",
    sections: [
      ["Agent 请求", "请求携带 tenantId、projectId、actorId、traceId 和目标描述，响应返回 planId、状态轨迹和证据。"],
      ["任务执行", "taskId 关联配置，executionId 关联一次运行，objectId 关联分片或对象台账。"],
      ["审批合同", "审批请求和审批确认记录不同主体，执行端只消费可信控制面给出的批准事实。"],
      ["实时事件", "WebSocket 事件包含 schemaVersion、eventId、traceId、taskId、executionId、node、state、occurredAt 和低敏摘要。"],
    ],
  },
  {
    slug: "manual-schema-recovery",
    title: "字段映射与约束故障恢复手册",
    code: "DOC-RCV-812",
    category: "recovery_manual",
    sourceType: "runbook",
    tags: ["字段映射", "默认值", "外键"],
    summary: "恢复动作可修复可证明安全的映射、类型转换、静态默认值和失败分片；DDL、数据覆盖和权限变更必须退出 Loop。",
    question: "字段映射、非空默认值和外键错误分别允许哪些自动修复？",
    sections: [
      ["字段映射", "仅在源目标元数据和上次成功配置共同证明唯一映射时自动修正；歧义映射退出 Loop。"],
      ["默认与非空", "可使用任务契约中已批准的静态默认值或安全转换；禁止自动放宽目标非空约束。"],
      ["类型转换", "无损或已批准的有界转换可以自动应用；截断、精度损失和时区语义变化需要人工确认。"],
      ["外键与 DDL", "可调整写入顺序或 replay 依赖对象；创建/删除外键、修改表结构或覆盖数据必须升级。"],
    ],
  },
  {
    slug: "manual-security-approval",
    title: "安全、审批与自治边界手册",
    code: "DOC-SEC-829",
    category: "security_manual",
    sourceType: "rule",
    tags: ["安全手册", "审批", "授权盒"],
    summary: "首次批准定义可自治的任务、对象、动作、参数和循环上限，模型不能自行扩大授权盒。",
    question: "首次授权盒如何限制后续无人值守自动修复？",
    sections: [
      ["授权盒内容", "记录任务、项目、应用、数据对象、允许动作、参数上下界、有效期和最大恢复循环。"],
      ["允许动作", "配置回滚、降低并发或批量、有界增加超时、刷新元数据、恢复 checkpoint、失败分片 replay。"],
      ["退出条件", "凭据、权限、DDL、删除或覆盖数据、扩大同步范围、不可逆转换以及证据冲突。"],
      ["人工指引", "退出时返回根因、证据来源与时间、所需权限、操作步骤、影响范围、回滚步骤和验证方法。"],
    ],
  },
];

const xlsxTopics = [
  {
    slug: "workbook-success-task-parameters",
    title: "成功同步任务参数案例",
    code: "XLSX-TASK-518",
    category: "successful_task_case",
    sourceType: "task_case",
    tags: ["Excel", "成功任务", "任务参数"],
    summary: "保存已经成功执行的来源、目标、batch、channel、timeout、checkpoint 和验证结果。",
    question: "上一次成功同步任务使用了哪些 batch、channel 和 timeout 参数？",
  },
  {
    slug: "workbook-field-mapping-cases",
    title: "字段映射与默认值案例",
    code: "XLSX-MAP-536",
    category: "field_mapping_case",
    sourceType: "dataset",
    tags: ["Excel", "字段映射", "默认值"],
    summary: "保存来源字段、目标字段、类型、是否可空、默认值、转换和自动修复策略。",
    question: "region_code 非空字段应该使用什么映射和默认值策略？",
  },
  {
    slug: "workbook-schedule-retry-cases",
    title: "调度与重试参数案例",
    code: "XLSX-SCH-554",
    category: "schedule_case",
    sourceType: "task_case",
    tags: ["Excel", "调度", "重试"],
    summary: "记录 cron、时区、最大循环、退避、超时和非工作时间自治策略。",
    question: "凌晨定时任务失败后最多自动恢复几轮并采用什么退避？",
  },
  {
    slug: "workbook-test-result-matrix",
    title: "RAG 与 Agent 测试结果矩阵",
    code: "XLSX-TST-572",
    category: "test_matrix",
    sourceType: "document",
    tags: ["Excel", "测试报告", "质量门禁"],
    summary: "按测试域记录用例数、通过数、失败数、通过率、P95 和质量门禁。",
    question: "测试矩阵怎样计算通过率并判断质量门禁？",
  },
  {
    slug: "workbook-incident-repair-ledger",
    title: "事故证据与修复动作台账",
    code: "XLSX-INC-589",
    category: "repair_ledger",
    sourceType: "incident",
    tags: ["Excel", "事故台账", "自动修复"],
    summary: "把错误码、日志证据、来源时间、可信度、修复动作、风险和验证结果放在同一台账。",
    question: "字段非空失败对应的证据、修复动作和最终验证是什么？",
  },
];

const textTopics = [
  ["quick-reference", "DataSmart Govern 快速参考", "TXT-QRF-611", "txt", "document", ["TXT", "快速参考"], "快速参考汇总任务状态、恢复状态、常用错误码和排查顺序。", "任务失败后快速参考建议先检查什么？"],
  ["operator-faq", "运维常见问题", "TXT-FAQ-624", "txt", "runbook", ["TXT", "FAQ", "运维"], "FAQ 回答为什么不能盲目重试、何时使用 RAG 和何时退出自治 Loop。", "为什么明确的权限或 DDL 错误不能继续自动循环？"],
  ["connector-capabilities", "连接器能力与容量快照", "JSON-CON-637", "json", "metadata", ["JSON", "连接器", "容量"], "结构化记录连接器版本、限流、最大批量、并发、超时和能力标志。", "当前连接器版本和允许的最大批量是多少？"],
  ["agent-state-snapshot", "Agent 全链路状态快照", "JSON-AGT-645", "json", "memory_export", ["JSON", "Agent", "状态机"], "结构化记录目标、Agent 节点、Kafka 事件、Java 审计、worker、Recovery 和最终验证状态。", "全链路状态快照中 Recovery 之后进入哪个最终验证节点？"],
  ["recovery-events", "恢复事件流水", "JSONL-RCV-653", "jsonl", "incident", ["JSONL", "恢复事件", "时间线"], "逐行保存恢复诊断、检索决策、低风险修复、分片 replay 和验证事件。", "恢复事件流水中自动修复后执行了什么 replay？"],
  ["successful-runs", "历史成功运行记录", "CSV-RUN-667", "csv", "task_case", ["CSV", "成功任务", "历史记录"], "保存历史成功 execution 的配置版本、读写量、脏数据、耗时和 checkpoint。", "最近一次成功运行的配置版本和脏数据数量是多少？"],
  ["worker-execution", "数据同步 Worker 执行日志", "LOG-WRK-679", "log", "incident", ["LOG", "Worker", "错误日志"], "结构化日志包含字段非空失败、错误码、对象、重试资格和修复后的成功记录。", "Worker 日志中的非空约束失败发生在哪个字段？"],
  ["persistence-snapshot", "任务与恢复持久化快照", "SQL-DB-688", "sql", "dataset", ["SQL", "数据库", "持久化"], "SQL 快照描述任务、执行、对象台账、恢复案例和证据记录的合成行。", "持久化快照怎样关联任务执行、恢复案例和证据？"],
].map(([slug, title, code, format, sourceType, tags, summary, question]) => ({
  slug,
  title,
  code,
  format,
  category: slug.replaceAll("-", "_"),
  sourceType,
  tags,
  summary,
  question,
}));


/** 返回命令行参数值，未知参数由主流程统一拒绝。 */
function readArgument(name) {
  const index = process.argv.indexOf(name);
  return index >= 0 ? process.argv[index + 1] : null;
}

/** 创建目录后写文件，确保所有生成路径都位于固定评测根目录下。 */
async function writeAsset(relativePath, payload) {
  const target = path.resolve(assetRoot, relativePath);
  const relative = path.relative(assetRoot, target);
  if (relative.startsWith("..") || path.isAbsolute(relative)) {
    throw new Error(`资产路径越界：${relativePath}`);
  }
  await fs.mkdir(path.dirname(target), { recursive: true });
  await fs.writeFile(target, payload);
}

/** 构造所有格式共用的 catalog 条目，供 Python Manifest 与黄金集生成器消费。 */
function catalogEntry(scope, topic, format, directory) {
  const relativePath = `documents/${scope.key}/${directory}/${topic.slug}.${format}`;
  return {
    documentId: `rag-eval-${scope.key}-${topic.slug}`,
    slug: topic.slug,
    title: topic.title,
    path: relativePath,
    sourceUri: `synthetic://datasmart-govern/rag-evaluation/${scope.key}/${directory}/${topic.slug}.${format}`,
    tenantId: scope.tenantId,
    projectId: scope.projectId,
    workspaceKey: scope.workspaceKey,
    scopeKey: scope.key,
    scopeLabel: scope.label,
    sourceType: topic.sourceType,
    tags: topic.tags,
    category: topic.category,
    artifactCode: topic.code,
    summary: topic.summary,
    exactQuestion: topic.question,
    contentFormat: format,
  };
}

/** 生成一份具备真实标题层级、表格、页眉页脚和编号步骤的中文 Word 手册。 */
async function buildDocx(scope, topic, entry) {
  const statusRows = [
    ["文档状态", "当前有效"],
    ["适用范围", scope.label],
    ["检索精确码", topic.code],
    ["独立锚点", `${scope.key}:${topic.slug}`],
    ["审计保留", `${scope.retentionDays} 天`],
  ];
  const controlRows = [
    ["同步延迟预算", `${scope.lagBudgetMinutes} 分钟`, "超出预算后触发 MONITOR_AGENT 诊断"],
    ["最大恢复循环", `${scope.retryLimit} 轮`, "每轮必须产生新证据或新修复动作"],
    ["基线批量", `${scope.batchSize} 行`, "自动调参只允许降低批量"],
    ["基线并发", `${scope.channelCount}`, "自动调参只允许降低并发"],
    ["基线超时", `${scope.timeoutSeconds} 秒`, "只能在授权上界内有界增加"],
  ];

  const children = [
    new Paragraph({
      style: "DocumentTitle",
      children: [new TextRun({ text: topic.title, bold: true, color: "17365D" })],
    }),
    new Paragraph({
      style: "DocumentSubtitle",
      children: [new TextRun(`DataSmart Govern 合成 RAG 评测资料 | ${scope.label}`)],
    }),
    new Paragraph({
      style: "Callout",
      children: [
        new TextRun({
          text: "合成声明：本文档为原创评测样本，不含真实客户、个人、凭据或生产数据。",
          bold: true,
        }),
      ],
    }),
    buildDocxTable(statusRows, [2400, 6960], true),
    heading("1. 文档目的"),
    bodyParagraph(`${topic.summary} 本文档用于验证 DOCX 解析、中文语义召回、来源引用、时间和可信度字段，不代表任何真实环境。`),
    heading("2. 适用边界"),
    ...[
      `tenantId=${scope.tenantId}，projectId=${scope.projectId}，workspaceKey=${scope.workspaceKey}。`,
      "同主题的其他租户或项目文档属于硬隔离干扰项，必须在向量召回和 Reranker 之前过滤。",
      "模型输出不能替代权限、审批、幂等或审计事实；没有足够证据时必须拒答。",
    ].map((text) => bullet(text)),
    heading("3. 核心流程与说明"),
    ...topic.sections.flatMap(([name, detail], index) => [
      new Paragraph({
        numbering: { reference: "datasmart-steps", level: 0 },
        children: [new TextRun({ text: name, bold: true }), new TextRun(`：${detail}`)],
      }),
      new Paragraph({
        style: "Note",
        children: [new TextRun(`验证点 ${index + 1}：记录来源、发生时间、可信度依据和执行结果。`)],
      }),
    ]),
    heading("4. 参数与治理控制"),
    buildDocxTable([["控制项", "本范围值", "治理要求"], ...controlRows], [2400, 2160, 4800], false),
    heading("5. 异常与升级"),
    ...[
      "低风险动作必须落在首次授权盒允许的任务、对象、动作、参数上下界和循环次数内。",
      "涉及凭据、权限、DDL、删除或覆盖数据、扩大同步范围、不可逆转换时立即退出自治 Loop。",
      "退出结果必须包含根因、证据来源与时间、所需权限、手工步骤、影响、回滚和验证方法。",
    ].map((text) => bullet(text)),
    heading("6. 检索与引用信息"),
    bodyParagraph(`精确码 ${topic.code}；独立锚点 ${scope.key}:${topic.slug}；文档标识 ${entry.documentId}。RAG 回答必须引用原始 DOCX sourceUri，而不是无来源的中间纯文本。`),
  ];

  const document = new Document({
    creator: "DataSmart Govern",
    title: topic.title,
    description: "纯合成中文 RAG 异构评测资料",
    styles: {
      default: {
        document: { run: { font: "Microsoft YaHei", size: 21, color: "202124" } },
        heading1: { run: { font: "Microsoft YaHei", size: 30, bold: true, color: "17365D" }, paragraph: { spacing: { before: 260, after: 120 } } },
      },
      paragraphStyles: [
        { id: "DocumentTitle", name: "Document Title", basedOn: "Normal", next: "Normal", run: { font: "Microsoft YaHei", size: 52, bold: true, color: "17365D" }, paragraph: { spacing: { before: 0, after: 80 } } },
        { id: "DocumentSubtitle", name: "Document Subtitle", basedOn: "Normal", next: "Normal", run: { font: "Microsoft YaHei", size: 22, color: "5F6368" }, paragraph: { spacing: { after: 240 } } },
        { id: "Callout", name: "Callout", basedOn: "Normal", next: "Normal", run: { font: "Microsoft YaHei", size: 20, color: "7A3E00" }, paragraph: { shading: { type: ShadingType.CLEAR, fill: "FFF4CE" }, spacing: { before: 120, after: 180 }, indent: { left: 160, right: 160 } } },
        { id: "Note", name: "Note", basedOn: "Normal", next: "Normal", run: { font: "Microsoft YaHei", size: 18, color: "5F6368" }, paragraph: { spacing: { after: 100 }, indent: { left: 560 } } },
      ],
    },
    numbering: {
      config: [
        {
          reference: "datasmart-steps",
          levels: [{ level: 0, format: LevelFormat.DECIMAL, text: "%1.", alignment: AlignmentType.LEFT, style: { paragraph: { indent: { left: 480, hanging: 240 } } } }],
        },
      ],
    },
    sections: [
      {
        properties: { page: { margin: { top: 1440, right: 1440, bottom: 1440, left: 1440 } } },
        headers: { default: new Header({ children: [new Paragraph({ children: [new TextRun({ text: "DataSmart Govern | 合成知识资料", color: "5F6368", size: 18 })] })] }) },
        footers: { default: new Footer({ children: [new Paragraph({ alignment: AlignmentType.CENTER, children: [new TextRun({ text: `${topic.code} | 第 `, color: "5F6368", size: 18 }), new TextRun({ children: [PageNumber.CURRENT], color: "5F6368", size: 18 }), new TextRun({ text: " 页", color: "5F6368", size: 18 })] })] }) },
        children,
      },
    ],
  });
  await writeAsset(entry.path, await Packer.toBuffer(document));
}

/** 创建符合固定 DXA 几何的 Word 表格，避免不同渲染器自动缩放。 */
function buildDocxTable(rows, widths, keyValueTable) {
  return new Table({
    width: { size: 9360, type: WidthType.DXA },
    layout: TableLayoutType.FIXED,
    rows: rows.map((values, rowIndex) => new TableRow({
      children: values.map((value, columnIndex) => new TableCell({
        width: { size: widths[columnIndex], type: WidthType.DXA },
        margins: { top: 100, bottom: 100, left: 120, right: 120 },
        shading: rowIndex === 0 && !keyValueTable
          ? { type: ShadingType.CLEAR, fill: "D9EAF7" }
          : columnIndex === 0 && keyValueTable
            ? { type: ShadingType.CLEAR, fill: "EEF3F8" }
            : undefined,
        borders: {
          top: { style: BorderStyle.SINGLE, size: 2, color: "C7D0D9" },
          bottom: { style: BorderStyle.SINGLE, size: 2, color: "C7D0D9" },
          left: { style: BorderStyle.SINGLE, size: 2, color: "C7D0D9" },
          right: { style: BorderStyle.SINGLE, size: 2, color: "C7D0D9" },
        },
        children: [new Paragraph({ children: [new TextRun({ text: String(value), bold: rowIndex === 0 || (keyValueTable && columnIndex === 0), size: 19 })] })],
      })),
    })),
  });
}

/** 创建一级标题。 */
function heading(text) {
  return new Paragraph({ text, heading: HeadingLevel.HEADING_1, keepNext: true });
}

/** 创建正文段落。 */
function bodyParagraph(text) {
  return new Paragraph({ children: [new TextRun(text)], spacing: { after: 140, line: 320 } });
}

/** 创建真实 Word 项目符号段落。 */
function bullet(text) {
  return new Paragraph({ bullet: { level: 0 }, children: [new TextRun(text)], spacing: { after: 80 } });
}

/** 为一种 XLSX 主题生成可编辑数据行。 */
function workbookRows(scope, topic) {
  const common = {
    scope: scope.label,
    anchor: `${scope.key}:${topic.slug}`,
    code: topic.code,
  };
  if (topic.slug === "workbook-success-task-parameters") {
    return {
      headers: ["任务编号", "配置版本", "来源", "目标", "batch_size", "channel", "timeout_s", "checkpoint", "读取行", "写入行", "脏数据", "状态"],
      rows: [
        ["TASK-SYN-1001", "cfg-v18", "postgres_orders", "warehouse_order_fact", scope.batchSize, scope.channelCount, scope.timeoutSeconds, "lsn-000318", 12000, 12000, 0, "SUCCEEDED"],
        ["TASK-SYN-1002", "cfg-v19", "mysql_customer", "lake_customer", Math.max(100, scope.batchSize - 100), Math.max(1, scope.channelCount - 1), scope.timeoutSeconds, "pk-008800", 8800, 8800, 0, "SUCCEEDED"],
        ["TASK-SYN-1003", "cfg-v20", "kafka_payment", "warehouse_payment", Math.max(100, scope.batchSize - 200), Math.max(1, scope.channelCount - 1), scope.timeoutSeconds + 30, "offset-01240", 6400, 6400, 0, "SUCCEEDED"],
      ],
      notes: { ...common, rule: "自动恢复优先回滚到最近成功配置 cfg-v20；不得自动提高 batch_size 或 channel。" },
    };
  }
  if (topic.slug === "workbook-field-mapping-cases") {
    return {
      headers: ["来源字段", "目标字段", "来源类型", "目标类型", "允许为空", "静态默认值", "转换", "自动修复策略"],
      rows: [
        ["order_id", "order_id", "varchar(64)", "varchar(64)", false, "", "trim", "唯一映射，可自动修复"],
        ["region", "region_code", "varchar(16)", "varchar(16)", false, "CN-UNKNOWN", "upper", "使用已批准静态默认值"],
        ["amount", "order_amount", "decimal(18,2)", "decimal(18,2)", false, "0.00", "decimal", "精度一致时自动修复"],
        ["customer_id", "customer_id", "bigint", "bigint", false, "", "identity", "外键缺失仅允许调整依赖写入顺序"],
        ["occurred_at", "occurred_at", "timestamp", "timestamp", false, "", "timezone:Asia/Shanghai", "时区语义变化需人工确认"],
      ],
      notes: { ...common, rule: "禁止自动放宽非空、删除外键或执行 DDL；歧义字段映射退出 Loop。" },
    };
  }
  if (topic.slug === "workbook-schedule-retry-cases") {
    return {
      headers: ["计划编号", "cron", "时区", "最大恢复循环", "退避秒", "超时秒", "非工作时间策略", "状态"],
      rows: [
        ["SCH-NIGHTLY-01", "0 0 2 * * ?", "Asia/Shanghai", scope.retryLimit, 60, scope.timeoutSeconds, "授权盒内自动恢复", "ENABLED"],
        ["SCH-HOURLY-02", "0 5 * * * ?", "Asia/Shanghai", Math.min(scope.retryLimit, 3), 30, scope.timeoutSeconds, "低风险修复后 replay", "ENABLED"],
        ["SCH-WEEKLY-03", "0 30 3 ? * SUN", "Asia/Shanghai", 2, 120, scope.timeoutSeconds + 60, "越权时退出并通知", "ENABLED"],
      ],
      notes: { ...common, rule: "每轮必须有新诊断证据；达到最大循环或需要越权时停止。" },
    };
  }
  if (topic.slug === "workbook-test-result-matrix") {
    return {
      headers: ["测试域", "用例数", "通过数", "失败数", "P95毫秒", "门槛", "结论"],
      rows: [
        ["六 Specialist", 42, 42, 0, 820, "通过率=100%", "PASS"],
        ["审批与范围隔离", 36, 36, 0, 210, "泄漏率=0", "PASS"],
        ["RAG 召回", 48, 44, 4, 1034, "Recall>=0.90", "REVIEW"],
        ["无人值守恢复", 24, 23, 1, 1880, "成功率>=0.95", "REVIEW"],
        ["WebSocket 合同", 18, 18, 0, 95, "字段完整", "PASS"],
      ],
      notes: { ...common, rule: "总体门禁必须同时满足质量、治理、拒答、范围和性能指标。" },
    };
  }
  return {
    headers: ["事故编号", "错误码", "证据来源", "发生时间", "可信度", "修复动作", "风险", "验证结果"],
    rows: [
      ["INC-SYN-901", "NOT_NULL_VIOLATION", "worker-execution.log", "2026-08-15T02:14:07+08:00", 0.99, "补充已批准静态默认值并 replay 失败分片", "LOW", "SUCCEEDED"],
      ["INC-SYN-902", "FIELD_MAPPING_MISSING", "workbook-field-mapping-cases.xlsx", "2026-08-15T03:01:12+08:00", 0.96, "采用唯一元数据映射", "LOW", "SUCCEEDED"],
      ["INC-SYN-903", "FOREIGN_KEY_MISSING", "persistence-snapshot.sql", "2026-08-15T03:18:40+08:00", 0.94, "先 replay 父对象再 replay 子对象", "MEDIUM", "SUCCEEDED"],
      ["INC-SYN-904", "DDL_REQUIRED", "worker-execution.log", "2026-08-15T04:00:00+08:00", 0.99, "退出 Loop，返回权限和手工操作指引", "HIGH", "ATTENTION_REQUIRED"],
    ],
    notes: { ...common, rule: "证据必须同时附带来源、时间和可信度；高风险动作不自动执行。" },
  };
}

/** 生成一份带说明、数据和校验三个工作表的 XLSX，并可选渲染全部工作表做 QA。 */
async function buildXlsx(scope, topic, entry) {
  const workbook = Workbook.create();
  const overview = workbook.worksheets.add("说明");
  const data = workbook.worksheets.add("数据");
  const validation = workbook.worksheets.add("校验");
  const dataset = workbookRows(scope, topic);

  overview.showGridLines = false;
  overview.getRange("A1:H1").merge();
  overview.getRange("A1").values = [[topic.title]];
  overview.getRange("A1:H1").format = { fill: "#17365D", font: { bold: true, color: "#FFFFFF", size: 18 }, rowHeight: 32, verticalAlignment: "center" };
  overview.getRange("A3:B10").values = [
    ["合成声明", "原创评测样本，不含真实客户、个人、凭据或生产数据"],
    ["范围", scope.label],
    ["tenantId", scope.tenantId],
    ["projectId", scope.projectId],
    ["workspaceKey", scope.workspaceKey],
    ["精确码", topic.code],
    ["独立锚点", `${scope.key}:${topic.slug}`],
    ["结论", topic.summary],
  ];
  overview.getRange("A3:A10").format = { fill: "#EAF2F8", font: { bold: true, color: "#17365D" } };
  overview.getRange("A3:B10").format.borders = { preset: "insideHorizontal", style: "thin", color: "#D9E1E8" };
  overview.getRange("A3:B10").format.wrapText = true;
  overview.getRange("A3:A10").format.columnWidth = 18;
  overview.getRange("B3:B10").format.columnWidth = 72;

  data.showGridLines = false;
  const lastDataColumn = columnName(dataset.headers.length);
  data.getRange(`A1:${lastDataColumn}1`).values = [dataset.headers];
  data.getRange(`A2:${lastDataColumn}${dataset.rows.length + 1}`).values = dataset.rows;
  data.getRange(`A1:${lastDataColumn}1`).format = { fill: "#1F4E78", font: { bold: true, color: "#FFFFFF" }, rowHeight: 28, horizontalAlignment: "center" };
  data.getRange(`A1:${lastDataColumn}${dataset.rows.length + 1}`).format.borders = { preset: "insideHorizontal", style: "thin", color: "#D9E1E8" };
  data.getRange(`A1:${lastDataColumn}${dataset.rows.length + 1}`).format.wrapText = true;
  data.getRange(`A1:${lastDataColumn}${dataset.rows.length + 1}`).format.autofitColumns();
  data.getRange(`A1:${lastDataColumn}${dataset.rows.length + 1}`).format.autofitRows();
  data.freezePanes.freezeRows(1);

  validation.showGridLines = false;
  validation.getRange("A1:D1").merge();
  validation.getRange("A1").values = [["可审计校验"]];
  validation.getRange("A1:D1").format = { fill: "#2E7D32", font: { bold: true, color: "#FFFFFF", size: 16 }, rowHeight: 30 };
  validation.getRange("A3:B8").values = [
    ["检查项", "结果或规则"],
    ["数据行数", null],
    ["范围锚点", dataset.notes.anchor],
    ["精确码", dataset.notes.code],
    ["治理规则", dataset.notes.rule],
    ["来源要求", "引用必须指向原始 XLSX，并保留工作表与单元格坐标"],
  ];
  validation.getRange("B4").formulas = [[`=COUNTA('数据'!A2:A${dataset.rows.length + 1})`]];
  validation.getRange("A3:B3").format = { fill: "#E2F0D9", font: { bold: true, color: "#1B5E20" } };
  validation.getRange("A3:B8").format.borders = { preset: "insideHorizontal", style: "thin", color: "#D9E1E8" };
  validation.getRange("A3:B8").format.wrapText = true;
  validation.getRange("A3:A8").format.columnWidth = 20;
  validation.getRange("B3:B8").format.columnWidth = 68;

  const output = await SpreadsheetFile.exportXlsx(workbook);
  const target = path.resolve(assetRoot, entry.path);
  await fs.mkdir(path.dirname(target), { recursive: true });
  await output.save(target);

  const errors = await workbook.inspect({
    kind: "match",
    searchTerm: "#REF!|#DIV/0!|#VALUE!|#NAME\\?|#N/A",
    options: { useRegex: true, maxResults: 50 },
    summary: `${entry.documentId} 公式错误扫描`,
  });
  if (/"count"\s*:\s*[1-9]/.test(errors.ndjson ?? "")) {
    throw new Error(`工作簿存在公式错误：${entry.documentId}`);
  }
  // artifact-tool 在本地调试模式下可能把 inspect 明细写到工作簿旁边。它是 QA 中间文件，
  // 不是 RAG 原始语料；生成器读取结果后立即移除，避免被误当成额外的 JSONL 文档。
  await fs.rm(`${target}.inspect.ndjson`, { force: true });

  if (qaRoot) {
    for (const sheetName of ["说明", "数据", "校验"]) {
      const image = await workbook.render({ sheetName, autoCrop: "all", scale: 1.25, format: "png" });
      const bytes = new Uint8Array(await image.arrayBuffer());
      const qaPath = path.join(qaRoot, "xlsx", scope.key, `${topic.slug}-${sheetName}.png`);
      await fs.mkdir(path.dirname(qaPath), { recursive: true });
      await fs.writeFile(qaPath, bytes);
    }
  }
}

/** 把 1-based 列数转换成 Excel A1 列名。 */
function columnName(columnCount) {
  let value = columnCount;
  let name = "";
  while (value > 0) {
    value -= 1;
    name = String.fromCharCode(65 + (value % 26)) + name;
    value = Math.floor(value / 26);
  }
  return name;
}

/** 根据主题生成 TXT/JSON/JSONL/CSV/LOG/SQL 的结构化合成正文。 */
function textPayload(scope, topic, entry) {
  const anchor = `${scope.key}:${topic.slug}`;
  const header = `合成声明：DataSmart Govern RAG 评测原创资料，不含真实客户、个人、凭据或生产数据。`;
  if (topic.format === "txt") {
    return `${topic.title}\n${header}\n精确码：${topic.code}\n独立锚点：${anchor}\n范围：${scope.label}\n\n结论\n${topic.summary}\n\n排查顺序\n1. 按 traceId、taskId、executionId、objectId 和 errorCode 查询结构化日志。\n2. 对比当前配置与上一次成功配置。\n3. 查询连接器版本、限流、容量和目标端约束。\n4. 按需读取 Runbook、历史事故和成功任务案例。\n5. 每条证据记录来源、时间、可信度和可信依据。\n\n自治边界\n授权盒内允许低风险配置修复和失败分片 replay；凭据、权限、DDL、覆盖数据或扩大范围时退出 Loop。\n`;
  }
  if (topic.slug === "connector-capabilities") {
    return JSON.stringify({
      synthetic: true,
      documentId: entry.documentId,
      artifactCode: topic.code,
      retrievalAnchor: anchor,
      scope: { tenantId: scope.tenantId, projectId: scope.projectId, workspaceKey: scope.workspaceKey },
      connector: {
        name: "synthetic-postgresql-connector",
        version: "2.7.4-eval",
        rateLimitRowsPerSecond: scope.batchSize * 8,
        maximumBatchSize: scope.batchSize,
        maximumChannels: scope.channelCount,
        maximumTimeoutSeconds: scope.timeoutSeconds + 120,
        capabilities: ["snapshot", "cdc", "checkpoint-replay", "metadata-refresh"],
      },
      evidence: { sourceStatus: "COMPLETE", effectiveAt: "2026-08-15T00:00:00+08:00", confidence: 0.97, basis: "SYNTHETIC_CONNECTOR_CAPABILITY_SNAPSHOT" },
    }, null, 2) + "\n";
  }
  if (topic.slug === "agent-state-snapshot") {
    return JSON.stringify({
      synthetic: true,
      documentId: entry.documentId,
      artifactCode: topic.code,
      retrievalAnchor: anchor,
      scope: { tenantId: scope.tenantId, projectId: scope.projectId, workspaceKey: scope.workspaceKey },
      goal: "把订单增量同步到分析仓库并在授权盒内自动恢复",
      states: [
        { stage: "AGENT", node: "KNOWLEDGE_AGENT", state: "SUCCEEDED", evidenceCount: 3 },
        { stage: "AGENT", node: "DATASOURCE_AGENT", state: "SUCCEEDED", evidenceCount: 2 },
        { stage: "KAFKA", node: "agent-tool-plan-command", state: "DELIVERED" },
        { stage: "JAVA_AUDIT", node: "PLAN_INGESTION", state: "ACCEPTED" },
        { stage: "WORKER", node: "DATA_SYNC", state: "FAILED", errorCode: "NOT_NULL_VIOLATION" },
        { stage: "RECOVERY", node: "RECOVERY_AGENT", state: "REPAIRED", action: "PATCH_APPROVED_DEFAULT_AND_REPLAY" },
        { stage: "FINALIZATION", node: "PRECHECK_AGENT", state: "SUCCEEDED" },
        { stage: "FINALIZATION", node: "MONITOR_AGENT", state: "SUCCEEDED" },
      ],
      finalState: "RECOVERED",
    }, null, 2) + "\n";
  }
  if (topic.format === "jsonl") {
    const rows = [
      { eventId: `${scope.key}-evt-01`, occurredAt: "2026-08-15T02:14:07+08:00", type: "DIAGNOSIS_STARTED", errorCode: "NOT_NULL_VIOLATION", source: "worker-execution.log", confidence: 0.99 },
      { eventId: `${scope.key}-evt-02`, occurredAt: "2026-08-15T02:14:11+08:00", type: "RAG_SEARCH_SELECTED", source: "manual-schema-recovery.docx", confidence: 0.96 },
      { eventId: `${scope.key}-evt-03`, occurredAt: "2026-08-15T02:14:19+08:00", type: "LOW_RISK_REPAIR_APPLIED", action: "SET_APPROVED_STATIC_DEFAULT", field: "region_code", value: "CN-UNKNOWN" },
      { eventId: `${scope.key}-evt-04`, occurredAt: "2026-08-15T02:14:24+08:00", type: "FAILED_SHARD_REPLAY", checkpoint: "shard-07:offset-318" },
      { eventId: `${scope.key}-evt-05`, occurredAt: "2026-08-15T02:15:02+08:00", type: "POST_RECOVERY_VERIFIED", state: "RECOVERED", dirtyRecords: 0 },
    ];
    return rows.map((row) => JSON.stringify({ synthetic: true, artifactCode: topic.code, retrievalAnchor: anchor, ...row })).join("\n") + "\n";
  }
  if (topic.format === "csv") {
    return [
      "execution_id,task_id,config_version,started_at,rows_read,rows_written,dirty_records,duration_ms,checkpoint,status,artifact_code,retrieval_anchor",
      `EX-${scope.key}-301,TASK-1001,cfg-v18,2026-08-13T02:00:00+08:00,12000,12000,0,48120,lsn-000301,SUCCEEDED,${topic.code},${anchor}`,
      `EX-${scope.key}-302,TASK-1001,cfg-v19,2026-08-14T02:00:00+08:00,12400,12400,0,49280,lsn-000309,SUCCEEDED,${topic.code},${anchor}`,
      `EX-${scope.key}-303,TASK-1001,cfg-v20,2026-08-15T02:00:00+08:00,12800,12800,0,50340,lsn-000318,SUCCEEDED,${topic.code},${anchor}`,
    ].join("\n") + "\n";
  }
  if (topic.format === "log") {
    return [
      header,
      `2026-08-15T02:14:07.124+08:00 level=ERROR traceId=trace-${scope.key}-901 taskId=TASK-1001 executionId=EX-${scope.key}-304 objectId=shard-07 errorCode=NOT_NULL_VIOLATION field=region_code retryable=false message="目标字段不允许为空" artifactCode=${topic.code} retrievalAnchor=${anchor}`,
      `2026-08-15T02:14:11.450+08:00 level=INFO traceId=trace-${scope.key}-901 node=RECOVERY_AGENT action=COMPARE_LAST_SUCCESS_CONFIG baseline=cfg-v20 difference="region_code default missing" source=workbook-field-mapping-cases.xlsx confidence=0.96`,
      `2026-08-15T02:14:19.310+08:00 level=INFO traceId=trace-${scope.key}-901 action=SET_APPROVED_STATIC_DEFAULT field=region_code value=CN-UNKNOWN risk=LOW governance=WITHIN_AUTHORIZATION_BOX`,
      `2026-08-15T02:14:24.872+08:00 level=INFO traceId=trace-${scope.key}-901 action=FAILED_SHARD_REPLAY objectId=shard-07 checkpoint=offset-318 cycle=1 maxCycles=${scope.retryLimit}`,
      `2026-08-15T02:15:02.009+08:00 level=INFO traceId=trace-${scope.key}-901 state=RECOVERED rowsRead=800 rowsWritten=800 dirtyRecords=0 verification=PRECHECK_AND_MONITOR_SUCCEEDED`,
    ].join("\n") + "\n";
  }
  return `${header}\n-- 精确码：${topic.code}\n-- 独立锚点：${anchor}\n-- 范围：${scope.label}\nBEGIN;\nINSERT INTO synthetic_task_execution (execution_id, task_id, config_version, state, started_at, completed_at) VALUES\n  ('EX-${scope.key}-304', 'TASK-1001', 'cfg-v21', 'RECOVERED', '2026-08-15T02:14:00+08:00', '2026-08-15T02:15:02+08:00');\nINSERT INTO synthetic_object_ledger (execution_id, object_id, attempt_count, object_state, checkpoint, dirty_records) VALUES\n  ('EX-${scope.key}-304', 'shard-07', 2, 'SUCCEEDED', 'offset-318', 0);\nINSERT INTO synthetic_recovery_case (case_id, execution_id, cycle, max_cycles, case_state, reason_code) VALUES\n  ('RC-${scope.key}-901', 'EX-${scope.key}-304', 1, ${scope.retryLimit}, 'RECOVERED', 'APPROVED_DEFAULT_APPLIED');\nINSERT INTO synthetic_evidence_record (case_id, source_uri, observed_at, confidence, confidence_basis) VALUES\n  ('RC-${scope.key}-901', 'synthetic://worker-execution.log', '2026-08-15T02:14:07+08:00', 0.99, 'STRUCTURED_LOG_EXACT_ERROR');\nCOMMIT;\n`;
}

/** 生成纯文本类资产。 */
async function buildTextAsset(scope, topic, entry) {
  await writeAsset(entry.path, Buffer.from(textPayload(scope, topic, entry), "utf8"));
}

/** 主流程：按格式生成文件并写入一个完整 catalog。 */
async function main() {
  if (!new Set(["all", "docx", "xlsx", "text"]).has(requestedFormat)) {
    throw new Error("--format 只允许 all、docx、xlsx 或 text");
  }
  const catalog = [];
  for (const scope of scopes) {
    for (const topic of docxTopics) {
      const entry = catalogEntry(scope, topic, "docx", "manuals");
      catalog.push(entry);
      if (requestedFormat === "all" || requestedFormat === "docx") {
        await buildDocx(scope, topic, entry);
      }
    }
    for (const topic of xlsxTopics) {
      const entry = catalogEntry(scope, topic, "xlsx", "spreadsheets");
      catalog.push(entry);
      if (requestedFormat === "all" || requestedFormat === "xlsx") {
        await buildXlsx(scope, topic, entry);
      }
    }
    for (const topic of textTopics) {
      const entry = catalogEntry(scope, topic, topic.format, "structured");
      catalog.push(entry);
      if (requestedFormat === "all" || requestedFormat === "text") {
        await buildTextAsset(scope, topic, entry);
      }
    }
  }
  await writeAsset(
    "multiformat_catalog.json",
    Buffer.from(JSON.stringify({
      schemaVersion: "datasmart.rag-multiformat-catalog.v1",
      assetBoundary: "synthetic-only",
      generatedBy: "scripts/generate-rag-multiformat-assets.mjs",
      documents: catalog,
    }, null, 2) + "\n", "utf8"),
  );
  process.stdout.write(`已生成 ${catalog.length} 条异构语料目录；本次文件模式：${requestedFormat}。\n`);
}

await main();
