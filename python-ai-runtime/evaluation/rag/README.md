# DataSmart Govern 中文 RAG 评测资产

本目录是 DataSmart Govern 的离线中文 RAG 评测基准，不是运行时默认知识库，也不是客户资料导入目录。

## 合成边界

- 全部文本、标题、错误码、表名、运行参数和事件均为原创合成样本。
- 不包含真实客户数据、原始生产数据、个人信息、凭据、网络连接信息或可用访问材料。
- `tenant=10/project=101`、`tenant=10/project=102`、`tenant=20/project=201` 只是评测隔离标签；不指向真实组织、项目或环境。
- 任何使用方不得把这些文件标注、宣传或包装成客户生产语料、客户事故记录或真实运维手册。

## 规模与分布

当前固定生成：

| 项目 | 数量 | 说明 |
| --- | ---: | --- |
| Markdown 文档 | 96 | 4 个范围各 24 份，所有文档都有独立检索锚点 |
| DOCX 文档 | 40 | 每个范围 10 份，覆盖用户、管理员、部署、运维、测试、产品、接口、恢复和安全手册 |
| XLSX 工作簿 | 20 | 每个范围 5 份，覆盖成功任务参数、字段映射、调度重试、测试矩阵和修复台账 |
| TXT/JSON/JSONL/CSV/LOG/SQL | 32 | 每个范围 8 份，覆盖结构化状态、数据库快照和运行日志 |
| 全部原始文档 | 188 | 9 种物理格式，全部进入 Manifest 和逐文件黄金用例 |
| 黄金 JSONL 用例 | 308 | 一行一条，可直接流式读取 |
| 全局范围 | 47 | `tenantId=*`、`projectId=*`、`workspaceKey=*` |
| 私有范围 | 141 | 三个私有范围各 47 份，用于相似内容的硬隔离验证 |
| 现行证据 | 172 | 架构、产品、手册、事故、任务、元数据、日志和持久化快照 |
| 已过期历史证据 | 16 | 仅供追溯；冲突用例要求优先引用现行资料 |

四个范围含有同主题、同精确码但范围不同的近重复文档。目标是验证检索实现是否在词法、向量、融合和重排之前先执行 `tenantId/projectId/workspaceKey` 过滤。全局资料可以被所有范围读取；私有资料只能被完全匹配的私有范围读取。

## 文件说明

- `documents/<scope>/*.md`：原有架构、产品、Runbook、事故、任务和治理 Markdown。
- `documents/<scope>/manuals/*.docx`：中文用户手册、管理员手册、部署手册、运维资料、测试报告、产品与接口说明。
- `documents/<scope>/spreadsheets/*.xlsx`：可以直接用 Excel 检查和维护的成功任务参数、字段映射、调度与台账。
- `documents/<scope>/structured/*`：TXT、JSON、JSONL、CSV、LOG 和 SQL 合成资料。
- `multiformat_catalog.json`：异构文件生成目录，是 Node 办公文档生成器与 Python Manifest 生成器之间的低敏合同。
- `manifest.json`：文档清单。除范围和证据字段外，每条还保存 `contentFormat`、`mediaType`、`contentSha256` 和 `extractedTextSha256`。
- 现行事故、任务案例和数据字典分别使用 `incident`、`task_case`、`dataset` 来源类型；被替代的历史版本继续使用 `git_history`，便于分层评测与过期证据抑制。
- `golden_cases.jsonl`：黄金问题集。每条包含 `caseId`、`question`、`scope`、`retrievalMode`、`topK`、`relevantDocuments`（含 `relevance`）、`expectedCitationUris`、`forbiddenDocumentIds`、`shouldRefuse`、`refusalReason`、`sourceTypes` 和 `tags`。
- `test_rag_evaluation_assets.py`：只使用标准库的资产合同校验。

Manifest 字段使用 camelCase，载入时可直接映射到运行时的 `RagDocument`：`documentId -> document_id`、
`sourceUri -> source_uri`、`tenantId -> tenant_id`、`projectId -> project_id`、`workspaceKey -> workspace_key`、
`sourceType -> source_type`。`contentSha256` 覆盖原文件字节，`extractedTextSha256` 覆盖真正用于切块和
Embedding 的规范化文本。DOCX/XLSX 的引用仍指向原始办公文件，不会改成无来源的临时 TXT。

受限提取器支持 `.md/.txt/.log/.sql/.csv/.tsv/.json/.jsonl/.docx/.xlsx`。它不执行宏、公式、外部链接或
嵌入对象，并限制原文件大小、ZIP 条目、解压大小、行列数、单元格长度和最终字符数。生产文件上传仍需
在 Gateway 层完成 MIME 检查、病毒扫描、权限校验和对象存储治理。

## 用例覆盖

| 用例类型 | 数量 | 评测目标 |
| --- | ---: | --- |
| `exact_error_code` | 80 | 精确错误码、稳定锚点与各类现行文档的独立召回 |
| `history_lookup` | 16 | 允许受控追溯已过期历史记录 |
| `semantic_paraphrase` | 24 | 不依赖精确码的中文语义改写 |
| `multi_document` | 12 | 多份互补证据和多个引用 URI |
| `no_answer` | 12 | 没有知识依据时拒绝自由生成 |
| `cross_scope_refusal` | 20 | Markdown、DOCX、XLSX 跨租户或跨项目细节的硬拒答 |
| `stale_conflict` | 12 | 当前证据优先于 `superseded` 历史记录 |
| `multiformat_exact` | 92 | 每一份新增异构文件都能被精确码和原始格式独立召回 |
| `cross_format_semantic` | 24 | 不提供代码或文件名的 DOCX/XLSX/JSON/LOG 自然问法 |
| `cross_format_multi_document` | 16 | 手册、表格、日志、事件和数据库快照共同回答排障问题 |

`relevance` 使用离散三级：`3` 为主要证据，`2` 为支持证据，`1` 为背景证据。`forbiddenDocumentIds` 是评测时绝不能进入检索候选、Reranker 输入、最终证据或引用的文档；它既用于隔离测试，也用于过期证据排除。

## 指标建议

建议同时报出检索质量和治理质量，不能只看生成文本是否流畅：

- `Recall@K`、`MRR`、`nDCG@K`：按 `relevantDocuments` 和 `relevance` 评估召回排序。
- 引用精确率与引用召回率：按 `expectedCitationUris` 评估可追溯证据是否完整且无多余引用。
- 禁止召回率：所有 `forbiddenDocumentIds` 均未出现在候选、最终引用或答案证据中。
- 范围隔离通过率：私有范围查询不得输出其他私有范围文档；该指标应为 100%。
- 拒答精确率、拒答召回率和拒答 F1：对 `no_answer` 与 `cross_scope_refusal` 分别统计，避免把证据不足与越权混为一类。
- 过期证据抑制率：`stale_conflict` 用例必须引用现行资料，不能引用 `superseded` 记录。
- 单用例通过率：每条用例必须同时满足相关文档、期望引用、拒答、禁止文档和作用域合同；宏平均指标达标
  但该指标未达标时，质量门禁仍然失败。
- 延迟与稳定性：在相同 `retrievalMode`、`topK`、索引版本和查询范围下，记录 p50/p95 与重复运行方差。

评测实现会在进程内检查检索候选、Reranker 输入和最终引用三个阶段。低敏报告只记录这些阶段的文档 ID、
最终 source URI、分数和拒答原因，不记录问题正文、文档正文、模型原始输出、Endpoint、密钥或供应商响应。

当前集合中 276 条可回答用例有 188 条直接包含目标文档的唯一锚点或精确码，主要用于格式解析、引用、
隔离和确定性回归；`semantic_paraphrase`、`cross_format_semantic` 和多文档用例才是不依赖锚点的语义子集。
因此当前宏平均 Recall/MRR 不能直接代表自然用户问法，
后续必须继续增加无锚点、多跳、歧义、冲突和真实脱敏问法的独立验收集。

## 再生成与校验

办公文档生成器使用 `docx` 和 `@oai/artifact-tool` 生成 DOCX/XLSX，其余格式使用 Node.js 标准库；
它不访问网络、环境变量、数据库或密钥。主生成器使用 Python 标准库和运行时受限提取器，先在
`evaluation/rag/.staging` 校验全量原文件、双哈希、Manifest 和黄金集，再以单文件原子替换发布。
日常校验不需要重新生成办公文件；只有修改办公文档模板时才需要具备对应 Node 依赖。

```powershell
# 修改办公文档模板后先生成异构原文件；普通校验可跳过这一行。
node scripts/generate-rag-multiformat-assets.mjs --format all
python -B scripts/generate-rag-evaluation-assets.py
python -B scripts/generate-rag-evaluation-assets.py --check
python -B -m unittest discover -s python-ai-runtime/evaluation/rag -p "test_*.py" -v
```

修改语料前应同时审阅两个生成器，并更新数量、格式分布、Manifest schema 和测试合同。不要用网络抓取、
生产导出或脱敏不足的材料替换合成模板。办公文件视觉 QA 首选 `render_docx.py` 和 artifact-tool render；
若本机未安装 LibreOffice，应记录环境阻塞并至少执行 OOXML 结构、提取文本、工作表公式和可打开性检查。

合成语料摄取脚本默认只校验。真正写库除了需要两个显式参数，还要求运行模式属于
`local/development/dev/test/testing/learning`；空模式、生产、预发布和 staging 均会 fail-closed，避免评测资产
污染客户知识库。
