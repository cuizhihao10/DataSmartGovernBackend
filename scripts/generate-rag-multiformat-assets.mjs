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
import {
  extraDocxTopics,
  extraTextTopics,
  extraXlsxTopics,
} from "./rag-enterprise-corpus-library.mjs";
import { collectActualApiContracts } from "./rag-api-contract-inventory.mjs";
import {
  buildSemanticDocxContent,
  buildSemanticStructuredPayload,
  buildSemanticWorkbookDataset,
  semanticWorkbookColumnWidth,
} from "./rag-semantic-corpus-library.mjs";


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

// 扩展主题按同一生成合同进入目录。用户需要的是单份资料中的高密度数据，新增主题用于进一步覆盖
// 认证、Agent、同步、Recovery、运维和事故等独立知识域，不能替代后续每份文件内部的详细记录。
docxTopics.push(...extraDocxTopics);
xlsxTopics.push(...extraXlsxTopics);
textTopics.push(...extraTextTopics);


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

/**
 * 按文档职责模型生成 Word 资料。
 *
 * 内容库负责决定“这类文档应该写什么”，本方法只负责版式。这样用户手册不会因为共用渲染器而
 * 混入事故记录，接口文档也不会出现与接口合同无关的失败案例。接口条目由源码扫描器提供，文档
 * 中的路径、参数、请求体和响应体都可以回溯到对应 Controller 或 FastAPI 路由。
 */
async function buildSemanticDocx(scope, topic, entry, actualApiContracts) {
  const model = buildSemanticDocxContent(scope, topic, actualApiContracts);
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
      children: [new TextRun({
        text: "合成声明：本文档为原创评测样本，不含真实客户、个人、凭据或生产数据。",
        bold: true,
      })],
    }),
    buildDocxTable([
      ["文档状态", "当前有效"],
      ["文档类型", model.kind],
      ["适用范围", scope.label],
      ["检索精确码", topic.code],
      ["独立锚点", `${scope.key}:${topic.slug}`],
      ["文档标识", entry.documentId],
      ["主题记录数", model.itemCount],
      ["内容摘要", topic.summary],
    ], [2400, 6960], true),
  ];

  model.chapters.forEach((chapterModel, chapterIndex) => {
    children.push(heading(`${chapterIndex + 1}. ${chapterModel.title}`));
    chapterModel.blocks.forEach((block) => {
      children.push(...renderSemanticDocxBlock(block, chapterIndex + 1));
    });
  });

  children.push(
    heading(`${model.chapters.length + 1}. 检索与引用信息`),
    bodyParagraph(
      `精确码：${topic.code}；独立锚点：${scope.key}:${topic.slug}；文档标识：${entry.documentId}。`
      + "RAG 回答必须引用原始 DOCX sourceUri，并保留来源、观测时间、可信度、可信依据和 sourceStatus。",
    ),
  );

  const document = new Document({
    creator: "DataSmart Govern",
    title: topic.title,
    description: `纯合成中文 RAG ${model.kind}`,
    styles: {
      default: {
        document: { run: { font: "Microsoft YaHei", size: 21, color: "202124" } },
        heading1: { run: { font: "Microsoft YaHei", size: 32, bold: true, color: "2E74B5" }, paragraph: { spacing: { before: 360, after: 200 } } },
        heading2: { run: { font: "Microsoft YaHei", size: 26, bold: true, color: "2E74B5" }, paragraph: { spacing: { before: 280, after: 140 } } },
      },
      paragraphStyles: [
        { id: "DocumentTitle", name: "Document Title", basedOn: "Normal", next: "Normal", run: { font: "Microsoft YaHei", size: 52, bold: true, color: "17365D" }, paragraph: { spacing: { before: 0, after: 80 } } },
        { id: "DocumentSubtitle", name: "Document Subtitle", basedOn: "Normal", next: "Normal", run: { font: "Microsoft YaHei", size: 22, color: "5F6368" }, paragraph: { spacing: { after: 240 } } },
        { id: "Callout", name: "Callout", basedOn: "Normal", next: "Normal", run: { font: "Microsoft YaHei", size: 20, color: "7A3E00" }, paragraph: { shading: { type: ShadingType.CLEAR, fill: "FFF4CE" }, spacing: { before: 120, after: 180 }, indent: { left: 160, right: 160 } } },
        { id: "CodeBlock", name: "Code Block", basedOn: "Normal", next: "Normal", run: { font: "Consolas", size: 17, color: "202124" }, paragraph: { shading: { type: ShadingType.CLEAR, fill: "F4F6F8" }, spacing: { before: 80, after: 120 }, indent: { left: 180, right: 180 } } },
      ],
    },
    sections: [{
      properties: {
        page: {
          size: { width: 12240, height: 15840 },
          margin: { top: 1440, right: 1440, bottom: 1440, left: 1440, header: 708, footer: 708 },
        },
      },
      headers: { default: new Header({ children: [new Paragraph({ children: [new TextRun({ text: `DataSmart Govern | ${model.kind}`, color: "5F6368", size: 18 })] })] }) },
      footers: { default: new Footer({ children: [new Paragraph({ alignment: AlignmentType.CENTER, children: [new TextRun({ text: `${topic.code} | 第 `, color: "5F6368", size: 18 }), new TextRun({ children: [PageNumber.CURRENT], color: "5F6368", size: 18 }), new TextRun({ text: " 页", color: "5F6368", size: 18 })] })] }) },
      children,
    }],
  });
  await writeAsset(entry.path, await Packer.toBuffer(document));
}

/** 把语义内容块转换成 Word 节点；未知块立即失败，避免静默丢失正文。 */
function renderSemanticDocxBlock(block, chapterNumber) {
  if (block.type === "paragraph") {
    return [bodyParagraph(String(block.text))];
  }
  if (block.type === "bullets") {
    return block.items.map((item) => bullet(String(item)));
  }
  if (block.type === "table") {
    const rows = [block.headers, ...block.rows];
    return [buildDocxTable(rows, equalDocxColumnWidths(block.headers.length), false)];
  }
  if (block.type === "entries") {
    return block.items.flatMap((item, index) => {
      const result = [subheading(`${chapterNumber}.${index + 1} ${item.title}`)];
      for (const field of item.fields) {
        const [label, value, presentation] = field;
        const formatted = `${label}：${String(value)}`;
        result.push(presentation === "code" ? codeParagraph(formatted) : bodyParagraph(formatted));
      }
      return result;
    });
  }
  throw new Error(`不支持的 DOCX 内容块：${block.type}`);
}

/** 按列数平均分配 Word 表格宽度，并把除法余数放入最后一列。 */
function equalDocxColumnWidths(columnCount) {
  if (!Number.isInteger(columnCount) || columnCount < 1) {
    throw new Error(`Word 表格列数必须大于 0，实际为 ${columnCount}`);
  }
  const base = Math.floor(9360 / columnCount);
  const widths = Array.from({ length: columnCount }, () => base);
  widths[widths.length - 1] += 9360 - base * columnCount;
  return widths;
}

/** 创建符合固定 DXA 几何的 Word 表格，避免不同渲染器自动缩放。 */
function buildDocxTable(rows, widths, keyValueTable) {
  return new Table({
    width: { size: 9360, type: WidthType.DXA },
    indent: { size: 120, type: WidthType.DXA },
    columnWidths: widths,
    layout: TableLayoutType.FIXED,
    rows: rows.map((values, rowIndex) => new TableRow({
      children: values.map((value, columnIndex) => new TableCell({
        width: { size: widths[columnIndex], type: WidthType.DXA },
        margins: { top: 80, bottom: 80, left: 120, right: 120 },
        shading: rowIndex === 0 && !keyValueTable
          ? { type: ShadingType.CLEAR, fill: "E8EEF5" }
          : columnIndex === 0 && keyValueTable
            ? { type: ShadingType.CLEAR, fill: "F2F4F7" }
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

/** 创建二级标题，用于逐接口和逐事故案例定位。 */
function subheading(text) {
  return new Paragraph({ text, heading: HeadingLevel.HEADING_2, keepNext: true });
}

/** 创建正文段落。 */
function bodyParagraph(text) {
  return new Paragraph({ children: [new TextRun(text)], spacing: { after: 120, line: 300 } });
}

/** 创建等宽字体示例块，保留 JSON、事件和请求字段的可读结构。 */
function codeParagraph(text) {
  return new Paragraph({ style: "CodeBlock", children: [new TextRun(text)] });
}

/** 创建真实 Word 项目符号段落。 */
function bullet(text) {
  return new Paragraph({ bullet: { level: 0 }, children: [new TextRun(text)], spacing: { after: 80, line: 300 } });
}

/** 把二维数据写入工作表，并使用稳定列宽避免 200+ 行数据造成布局漂移。 */
function populateWorksheetTable(worksheet, headers, rows, headerColor = "#1F4E78") {
  worksheet.showGridLines = false;
  const lastColumn = columnName(headers.length);
  worksheet.getRange(`A1:${lastColumn}1`).values = [headers];
  if (rows.length > 0) {
    worksheet.getRange(`A2:${lastColumn}${rows.length + 1}`).values = rows;
  }
  worksheet.getRange(`A1:${lastColumn}1`).format = {
    fill: headerColor,
    font: { bold: true, color: "#FFFFFF" },
    rowHeight: 30,
    horizontalAlignment: "center",
    verticalAlignment: "center",
  };
  worksheet.getRange(`A1:${lastColumn}${rows.length + 1}`).format.borders = {
    preset: "insideHorizontal",
    style: "thin",
    color: "#D9E1E8",
  };
  worksheet.getRange(`A1:${lastColumn}${rows.length + 1}`).format.wrapText = true;
  headers.forEach((header, index) => {
    const column = columnName(index + 1);
    worksheet.getRange(`${column}1:${column}${rows.length + 1}`).format.columnWidth = semanticWorkbookColumnWidth(header);
    if (rows.length > 0 && /时间|日期|At$|_at$/.test(header)) {
      worksheet.getRange(`${column}2:${column}${rows.length + 1}`).format.numberFormat = "yyyy-mm-dd hh:mm:ss";
    } else if (rows.length > 0 && /可信度/.test(header)) {
      worksheet.getRange(`${column}2:${column}${rows.length + 1}`).format.numberFormat = "0.00";
    } else if (rows.length > 0 && /通过率|比例|占比/.test(header)) {
      worksheet.getRange(`${column}2:${column}${rows.length + 1}`).format.numberFormat = "0.00%";
    }
  });
  worksheet.freezePanes.freezeRows(1);
}

/**
 * 生成主题专属工作簿。
 *
 * 每种主题在内容库中声明自己的工作表、字段和记录。成功任务工作簿只包含成功基线与验证；字段映射
 * 工作簿只包含映射、约束和转换；只有任务案例与事故台账才允许出现失败原因和处置字段。
 */
async function buildSemanticXlsx(scope, topic, entry) {
  const workbook = Workbook.create();
  const dataset = buildSemanticWorkbookDataset(scope, topic);
  const overview = workbook.worksheets.add("说明");
  const totalRows = dataset.sheets.reduce((sum, sheetModel) => sum + sheetModel.rows.length, 0);

  overview.showGridLines = false;
  overview.getRange("A1:H1").merge();
  overview.getRange("A1").values = [[dataset.title]];
  overview.getRange("A1:H1").format = {
    fill: "#17365D",
    font: { bold: true, color: "#FFFFFF", size: 18 },
    rowHeight: 34,
    verticalAlignment: "center",
  };
  overview.getRange("A3:B12").values = [
    ["合成声明", "原创评测样本，不含真实客户、个人、凭据或生产数据"],
    ["工作簿主题", topic.category],
    ["范围", scope.label],
    ["tenantId", scope.tenantId],
    ["projectId", scope.projectId],
    ["workspaceKey", scope.workspaceKey],
    ["精确码", topic.code],
    ["独立锚点", `${scope.key}:${topic.slug}`],
    ["业务工作表", dataset.sheets.map((sheetModel) => sheetModel.name).join("、")],
    ["内容摘要", `${dataset.description}；共 ${totalRows} 条主题记录。`],
  ];
  overview.getRange("A3:A12").format = { fill: "#EAF2F8", font: { bold: true, color: "#17365D" } };
  overview.getRange("A3:B12").format.borders = { preset: "insideHorizontal", style: "thin", color: "#D9E1E8" };
  overview.getRange("A3:B12").format.wrapText = true;
  overview.getRange("A3:A12").format.columnWidth = 20;
  overview.getRange("B3:B12").format.columnWidth = 88;

  for (const sheetModel of dataset.sheets) {
    const worksheet = workbook.worksheets.add(sheetModel.name);
    populateSemanticWorksheet(worksheet, sheetModel);
  }

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
  await fs.rm(`${target}.inspect.ndjson`, { force: true });

  if (qaRoot) {
    const renderWindows = [["说明", "A1:H12", "全表"]];
    for (const sheetModel of dataset.sheets) {
      const lastColumn = columnName(sheetModel.headers.length);
      const lastRow = sheetModel.rows.length + 1;
      if (lastRow <= 41) {
        renderWindows.push([sheetModel.name, `A1:${lastColumn}${lastRow}`, "全表"]);
        continue;
      }
      const middle = Math.floor(lastRow / 2);
      renderWindows.push(
        [sheetModel.name, `A1:${lastColumn}${Math.min(31, lastRow)}`, "开头"],
        [sheetModel.name, `A${Math.max(2, middle - 14)}:${lastColumn}${Math.min(lastRow, middle + 15)}`, "中段"],
        [sheetModel.name, `A${Math.max(2, lastRow - 29)}:${lastColumn}${lastRow}`, "结尾"],
      );
    }
    for (const [sheetName, range, label] of renderWindows) {
      const image = await workbook.render({ sheetName, range, scale: 1.0, format: "png" });
      const bytes = new Uint8Array(await image.arrayBuffer());
      const qaPath = path.join(qaRoot, "xlsx", scope.key, `${topic.slug}-${sheetName}-${label}.png`);
      await fs.mkdir(path.dirname(qaPath), { recursive: true });
      await fs.writeFile(qaPath, bytes);
    }
  }
}

/** 将语义工作表写入 artifact-tool，并为每列设置稳定宽度与格式。 */
function populateSemanticWorksheet(worksheet, sheetModel) {
  const rows = sheetModel.rows.map((row) => row.map(normalizeWorkbookCellValue));
  populateWorksheetTable(worksheet, sheetModel.headers, rows, sheetModel.color);
  sheetModel.headers.forEach((header, index) => {
    const column = columnName(index + 1);
    worksheet.getRange(`${column}1:${column}${rows.length + 1}`).format.columnWidth = semanticWorkbookColumnWidth(header);
  });
}

/**
 * 阻止 artifact-tool 把 ISO 时间字符串隐式转换成 Excel 浮点日期序列。
 *
 * 日期序列的小数部分可能偶然形成手机号或身份证号样式，既不利于 RAG 阅读，也会触发敏感信息误报。
 * 加上明确的 UTC 前缀后，工作簿保留稳定、可读、可检索的时间文本，且不改变原始时刻。
 */
function normalizeWorkbookCellValue(value) {
  if (typeof value === "string" && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{3})?Z$/.test(value)) {
    return `UTC ${value.replace("T", " ").replace(/Z$/, "")}`;
  }
  return value;
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

/** 按文件用途生成结构化资料，并把真实 API 合同提供给接口快照主题。 */
function textPayload(scope, topic, entry, actualApiContracts) {
  return buildSemanticStructuredPayload(scope, topic, entry, actualApiContracts);
}

/** 生成纯文本类资产。 */
async function buildTextAsset(scope, topic, entry, actualApiContracts) {
  await writeAsset(entry.path, Buffer.from(textPayload(scope, topic, entry, actualApiContracts), "utf8"));
}

/**
 * 在写入任何资产前验证每个主题都有专属语义处理器。
 *
 * 主题清单会持续扩充；如果新增主题后忘记补内容模型，这个预检会立即报出 slug，避免生成到一半才
 * 发现漏配，更不会退回通用事故模板掩盖问题。只需使用一个范围验证路由，范围差异由正式生成覆盖。
 */
function validateSemanticCoverage(actualApiContracts) {
  const scope = scopes[0];
  for (const topic of docxTopics) {
    buildSemanticDocxContent(scope, topic, actualApiContracts);
  }
  for (const topic of xlsxTopics) {
    buildSemanticWorkbookDataset(scope, topic);
  }
  for (const topic of textTopics) {
    const entry = catalogEntry(scope, topic, topic.format, "structured");
    buildSemanticStructuredPayload(scope, topic, entry, actualApiContracts);
  }
}

/** 主流程：按格式生成文件并写入一个完整 catalog。 */
async function main() {
  if (!new Set(["all", "docx", "xlsx", "text"]).has(requestedFormat)) {
    throw new Error("--format 只允许 all、docx、xlsx 或 text");
  }
  const actualApiContracts = await collectActualApiContracts(repositoryRoot);
  if (actualApiContracts.length === 0) {
    throw new Error("没有从源码扫描到任何 API 合同，拒绝生成空接口资料");
  }
  validateSemanticCoverage(actualApiContracts);
  const catalog = [];
  for (const scope of scopes) {
    for (const topic of docxTopics) {
      const entry = catalogEntry(scope, topic, "docx", "manuals");
      catalog.push(entry);
      if (requestedFormat === "all" || requestedFormat === "docx") {
        await buildSemanticDocx(scope, topic, entry, actualApiContracts);
      }
    }
    for (const topic of xlsxTopics) {
      const entry = catalogEntry(scope, topic, "xlsx", "spreadsheets");
      catalog.push(entry);
      if (requestedFormat === "all" || requestedFormat === "xlsx") {
        await buildSemanticXlsx(scope, topic, entry);
      }
    }
    for (const topic of textTopics) {
      const entry = catalogEntry(scope, topic, topic.format, "structured");
      catalog.push(entry);
      if (requestedFormat === "all" || requestedFormat === "text") {
        await buildTextAsset(scope, topic, entry, actualApiContracts);
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
  process.stdout.write(
    `已生成 ${catalog.length} 条异构语料目录；本次文件模式：${requestedFormat}；源码接口合同：${actualApiContracts.length} 条。\n`,
  );
}

await main();
