# DataSmart RAG 黄金集与硅基流动 BGE 评测 Runbook

本文说明 DataSmart Govern 如何校验、摄取和评测中文 RAG 资产，以及如何以硅基流动
`BAAI/bge-m3` 和 `BAAI/bge-reranker-v2-m3` 形成可重复对照实验。

## 1. 资产边界

评测资产位于 `python-ai-runtime/evaluation/rag/`：

- `documents/`：356 份原创合成中文资料，包括 96 Markdown、120 DOCX、60 XLSX，以及
  80 份 TXT/JSON/JSONL/CSV/LOG/SQL；
- `manifest.json`：文档 ID、原始路径、来源 URI、租户/项目范围、格式、MIME、证据状态、原文件 SHA-256
  和提取文本 SHA-256；
- `multiformat_catalog.json`：异构办公文档与结构化资料的生成目录；
- `golden_cases.jsonl`：752 条黄金问题；
- `test_rag_evaluation_assets.py`：不依赖应用和第三方包的资产合同测试；
- `README.md`：规模、分布、用例类型和再生成说明。

全部资产都是 `synthetic-only`，不能宣传成客户事故、客户文档或生产数据。四种范围中包含同主题、
同错误码、不同结论的近重复文档，用于验证检索是否先执行 `tenantId/projectId/workspaceKey` 硬过滤。

来源类型分布：

| 来源类型 | 数量 | 用途 |
| --- | ---: | --- |
| `document` | 76 | 架构、产品、用户/管理员/部署手册、测试和接口说明 |
| `wiki` | 8 | 产品与架构知识 |
| `runbook` | 60 | 运维、部署、恢复手册、命令参考和 FAQ |
| `incident` | 64 | 已复盘事故、运维记录、修复台账、告警和事件日志 |
| `task_case` | 64 | 十类数据同步任务、成功参数、调度与运行案例 |
| `dataset` | 32 | 数据字典、字段映射、画像、CSV 和 SQL 持久化样本 |
| `metadata` | 12 | 连接器版本、限流、容量和接口合同快照 |
| `memory_export` | 8 | Agent 全链路状态与审计事件快照 |
| `rule` | 16 | 权限、分级、审批和自治边界规则 |
| `git_history` | 16 | 已被替代的历史依据 |

黄金用例保存问题、期望文档及相关性等级、期望引用 URI、禁止文档、拒答条件、拒答原因、来源类型、
标签和范围三元组。评测报告不保存问题正文、文档正文、模型原始输出、Endpoint 或密钥。

本轮高密度语料不是靠重复增加空壳文件扩容，也不再把同一批失败案例复制进所有文档。用户、管理员、
部署、运维、安全、产品、测试、事故和接口 DOCX 分别使用专属内容模型。综合接口手册从 Java Controller
和 FastAPI 路由扫描出 475 条真实 REST/SSE/WebSocket 合同，记录源码、可见性、方法、路径、权限、参数、
Schema、请求/响应示例、错误响应和幂等规则；接口文档不记录任务事故。

工作簿统一只有版式，不统一业务表：成功任务、字段映射、调度、测试、事故和十类任务案例各有 5 张专属
业务表，外加说明页。只有事故台账和任务案例包含失败明细；成功运行、连接器能力、产品特性和接口快照
不带事故字段。结构化资料也按格式职责区分，任务、日志、事故和 Recovery 资料才共享任务、执行、对象、
追踪、事故和恢复案例 ID。

运维与事故资料不是只增加“影响、根因、处置”等标题。四个范围的运维手册共含 560 条标准作业，逐条保存
用户可见现象、运维定位路径、准确日志位置、可复制低敏日志、通俗判断、开发排查建议、处理和回滚；事故
文档共含 1,000 条记录，逐条区分用户、运维、开发视角，并按“页面 `traceId` -> 执行日志 -> 生命周期图
-> 责任服务日志 -> 配置/元数据/SQL/指标 -> 最近成功配置 -> 定位结论”的七步顺序还原诊断过程。
20 类错误画像覆盖连接、认证、权限、限流、字段映射、非空、类型、精度、长度、外键、唯一约束、checkpoint、
Kafka、outbox、连接器版本、容量、脏数据和 DDL。生成器将展示名称与命令参数分离，确保 `docker compose logs`
使用仓库真实 service key；合成日志只保留时间、服务、关联 ID、稳定错误码和脱敏标志，不包含端点、SQL、
凭据或原始业务行。

752 条黄金用例中，260 条逐文件精确用例保证每份异构资产可达，260 条自然语言用例防止只依赖精确码，
48 条跨格式多证据用例验证日志、任务、事故和 Runbook 的联合引用，28 条跨范围用例验证硬隔离。
旧版 188/308 指标只能作为历史对照，不能代表本轮 356/752 资产的当前分数；重新发布质量结论前必须使用
新 Manifest 指纹完成词法与 BGE 全量评测。

## 2. 完整性校验

```powershell
python -B scripts/generate-rag-evaluation-assets.py --check
python -B -m unittest discover -s python-ai-runtime/evaluation/rag -p "test_*.py" -v
python -B scripts/rag-evaluation.py --validate-only
python -B scripts/rag-corpus-ingest.py
```

前三个命令分别验证生成器输出、静态资产合同和运行时加载合同。最后一个命令默认只校验，不连接数据库。
任何原文件字节变化、提取文本漂移、格式/MIME 不匹配、OOXML 路径越界或外部关系、引用不存在、相关文档
越权或拒答条件不完整都会失败。DOCX/XLSX 只读取 OOXML 文本，不执行宏、公式、外部链接或嵌入对象。

## 3. 离线词法基线

```powershell
python -B scripts/rag-evaluation.py --profile lexical --report "$env:TEMP/datasmart-rag-lexical.json"
```

2026-08-17 在补齐三视角事故定位后的 356 份高密度资料、752 条用例上完成词法基线，数据集指纹为
`50a11dec76941de6fc7da4b34adcde9113649751aff3c253b5dfcf5fcd78448a`。本次运行没有检索执行错误，
128 条用例完整通过；当前内存知识库按 700 字切分为 149,609 个 chunk，全量运行 2,235,349 ms：

| 指标 | 当前结果 |
| --- | ---: |
| Recall@K | 0.773174 |
| MRR | 0.658708 |
| nDCG@K | 0.676538 |
| 引用精确率 | 0.427832 |
| 引用召回率 | 0.773174 |
| 拒答精确率 / 召回率 / F1 | 1.000000 / 0.700000 / 0.823529 |
| 范围泄漏率 | 0.000000 |
| 禁止文档通过率 | 0.710811 |
| 过期证据抑制率 | 1.000000 |
| 单用例通过率 | 0.170213 |
| p50 / p95 | 2234 ms / 9801 ms |

当前基线只通过范围隔离和过期证据抑制门禁，未通过 Recall、MRR、nDCG、引用精确率/召回率、拒答 F1、
禁止文档通过率和单用例通过率。与上一版相比，详细诊断语料使 Recall 从 `0.758427` 上升到 `0.773174`，
但同类证据密度和 chunk 数增长使 MRR、引用精确率、单用例通过率与延迟退化。这证明资料可以被完整加载、
过滤、检索和引用，也如实暴露了纯词法排序在高密度近重复诊断条目上的区分能力不足。该内存实现用于离线合同回归，不能替代
BGE-M3/Reranker 或 PostgreSQL FTS/pgvector 的质量、吞吐和延迟测评。

2026-08-16 在旧版 188 份异构文档、308 条用例上的历史词法基线：

| 指标 | 结果 |
| --- | ---: |
| Recall@K | 0.876208 |
| MRR | 0.800423 |
| nDCG@K | 0.805850 |
| 引用精确率 | 0.331944 |
| 引用召回率 | 0.876208 |
| 拒答 F1 | 0.769231 |
| 范围泄漏率 | 0.000000 |
| 禁止文档通过率 | 0.331081 |
| 过期证据抑制率 | 1.000000 |
| 单用例通过率 | 0.077922 |
| p50 / p95 | 20 ms / 85 ms |

该基线没有通过质量门禁，失败项为引用精确率、拒答 F1、禁止文档通过率和单用例通过率。禁止文档检查覆盖检索候选、
Reranker 输入和最终引用三个阶段，因此比只检查最终引用更严格。结果证明范围过滤和词法召回已有基础，
也证明“召回高”不能等同于“RAG 合格”。BGE 结果必须使用同一资产指纹和门禁对比，不能用回答流畅度
替代检索、引用和拒答指标。

## 4. SiliconFlow 配置

Embedding 使用：

- Endpoint：`https://api.siliconflow.cn/v1/embeddings`
- Model：`BAAI/bge-m3`
- 声明维度：`1024`

Reranker 使用：

- Endpoint：`https://api.siliconflow.cn/v1/rerank`
- Model：`BAAI/bge-reranker-v2-m3`

代码分别适配数组 Embedding 响应和 Rerank `index/relevance_score` 响应，并严格验证数量、整数且唯一的
index、向量维度和有限浮点值。远程 Endpoint 无论是否携带密钥都必须使用 HTTPS，只有 localhost 调试
地址允许 HTTP。Reranker 固定 `return_documents=false`，避免上游在响应中重复回显正文。

远程 Embedding 和 Reranker 都需要 API Key。密钥只能由 Secret Manager、CI/CD Secret、Kubernetes
Secret 或未跟踪的本地环境变量注入：

```powershell
$env:SILICONFLOW_API_KEY = Read-Host "SiliconFlow API Key" -MaskInput
python -B scripts/rag-evaluation.py --profile siliconflow --report "$env:TEMP/datasmart-rag-bge.json"
```

脚本不提供命令行密钥参数。报告只记录逻辑档位和模型名，不记录 Endpoint、密钥、问题、正文或供应商响应。
可通过 `--case-type cross_format_semantic` 或 `--limit 20` 做小范围连通性验证，再运行全部 752 条用例。
`SILICONFLOW_API_KEY` 可以明确作为共享凭据；两个 RAG 专用 Key 则严格按能力隔离，缺少其中任意一个时
都会停止评测，不会把 Embedding Key 发送给 Reranker，或反向复用。

2026-08-16 已使用容器运行时 Secret 对旧版 188/308 异构集合完成一轮全量真实调用。密钥只存在于当前容器
进程环境，未写入镜像、Compose、仓库、日志或报告；重建容器时必须重新通过 Secret 注入。资产指纹为
`a872761c6824ce77ff4ca3c42f2450dd9c75a090dbe309d5723c8b386d669734`，308 条用例执行错误数为 0：

| 指标 | SiliconFlow BGE 结果 |
| --- | ---: |
| Recall@K | 0.964976 |
| MRR | 0.904287 |
| nDCG@K | 0.916241 |
| 引用精确率 | 0.407488 |
| 引用召回率 | 0.964976 |
| 拒答精确率 / 召回率 / F1 | 1.000000 / 0.625000 / 0.769231 |
| 范围泄漏率 | 0.000000 |
| 禁止文档通过率 | 0.331081 |
| 过期证据抑制率 | 1.000000 |
| 单用例通过率 | 0.084416 |
| p50 / p95 | 567 ms / 1748 ms |

真实 BGE 同样没有通过发布门禁，失败项为引用精确率、拒答 F1、禁止文档通过率和单用例通过率。它证明
Provider、Embedding、Reranker、异构提取和范围隔离链路可以完成全量运行，
但当前仍会保留过多证据，且对无答案问题的拒答召回不足。一次连通性成功不等于费用、限流、并发、故障
恢复或生产稳定性已经验收。

旧版异构分桶进一步证明文件不是“只生成未检索”：92 条逐文件精确用例和 24 条自然异构问法的文档召回率
均为 `1.0`；16 条跨格式多证据用例的文档召回率为 `0.6458`，说明下一步重点是多跳召回、证据去重和引用
裁剪，而不是继续增加格式后缀。20 条跨范围拒答全部通过。

同日使用最新镜像重建本地 `python-ai-runtime`，仅通过容器运行时环境注入 Secret，并显式启用
`pgvector + BAAI/bge-m3 + BAAI/bge-reranker-v2-m3`。低敏诊断确认两个 Provider 已配置且知识库无错误；
容器内真实 smoke 返回 1024 维向量和 1 条有限分数的重排结果。该 smoke 不打印问题正文、候选正文、
Endpoint 或密钥，也不能替代上表的全量质量门禁。

## 5. Compose 启用顺序

`.env.application.example` 和 `docker-compose.application.yml` 已提供模型名、Endpoint、超时、批次、单次摄取
文档/chunk 上限和候选上限。
远程 Provider 默认保持 `disabled`。准备好 Secret 后按顺序启用：

1. 将 `DATASMART_RAG_KNOWLEDGE_BASE` 改为 `pgvector`；
2. 将 `DATASMART_RAG_EMBEDDING_PROVIDER` 改为 `openai-compatible`；
3. 将 `DATASMART_RAG_RERANK_PROVIDER` 改为 `siliconflow`；
4. 通过 Secret 注入 `SILICONFLOW_API_KEY`，或分别注入两个 RAG 专用 Key；共享 Key 只允许发送到
   `api.siliconflow.cn`，Endpoint 中包含凭据、查询参数或非 HTTPS 地址时会 fail-closed；
5. 重新摄取语料生成真实向量；
6. 检查 `/agent/rag/diagnostics` 中知识库、embedding 和 reranker 的低敏状态；
7. 运行黄金集、并发和故障注入评测后再放量。

没有密钥时继续使用 PostgreSQL 词法检索，不会静默生成哈希伪向量，也不会把规则重排标记为生产模型；
生产 pgvector 装配若检测到确定性测试 Embedding Provider 会直接 fail-closed。

## 6. 受控摄取

评测语料写入本地 PostgreSQL/pgvector 前，需要先安装 PostgreSQL extra，并通过未跟踪的本地配置或
Secret 准备 DSN。下面示例中的占位值不能直接用于真实环境：

```powershell
python -m pip install -e ".\python-ai-runtime[postgresql]"
$env:DATASMART_AI_RUNTIME_MODE = "local"
$env:DATASMART_RAG_KNOWLEDGE_BASE = "pgvector"
$env:DATASMART_RAG_POSTGRESQL_DSN = "<由本地 Secret 注入的 PostgreSQL DSN>"
$env:DATASMART_RAG_EMBEDDING_PROVIDER = "openai-compatible"
$env:DATASMART_RAG_EMBEDDING_ENDPOINT = "https://api.siliconflow.cn/v1/embeddings"
$env:DATASMART_RAG_EMBEDDING_MODEL = "BAAI/bge-m3"
$env:DATASMART_RAG_EMBEDDING_DIMENSIONS = "1024"
$env:SILICONFLOW_API_KEY = Read-Host "SiliconFlow API Key" -MaskInput
python -B scripts/rag-corpus-ingest.py --ingest --confirm-synthetic-evaluation-corpus
Remove-Item Env:\SILICONFLOW_API_KEY
```

摄取流程先校验 356 份文档和 752 条用例，再在文档/chunk 硬上限内切块。Embedding 批量调用位于数据库
锁外，在线查询不会因远程模型延迟被长时间阻塞；向量准备完成后，数据库事务才按“租户 + 项目 +
workspace + documentId”替换每份文档的旧 chunk。chunk 主键也包含完整范围的稳定摘要，因此不同租户的
同名 documentId 不会覆盖。输出只包含文档数、chunk 数、存储类型、是否启用向量、模型名和数据集指纹，
不包含 DSN、正文、Endpoint 或密钥。该入口不会删除 Manifest 之外的知识文档。

摄取入口还会校验运行模式。它只允许 `local/development/dev/test/testing/learning`，对空值、`production`、
`prod`、`staging` 和 `preprod` 一律拒绝，即使操作者给出了确认参数也不能把合成语料写入生产知识库。

旧版资产已在本机 PostgreSQL 完成一次真实摄取验证：188 份异构原文件生成 313 个 chunk，全部使用 1024 维
`BAAI/bge-m3` 向量，四个范围各 47 份，来源类型与格式分布均由 Manifest 固定。真实 pgvector 查询已
从 XLSX 字段映射和 DOCX 恢复手册返回原文件 `sourceUri`，证明二进制资料不是仅通过离线加载测试。
由于连接固定
`search_path=ai_memory`，pgvector 类型和距离运算符必须分别写为 `public.vector` 与
`OPERATOR(public.<=>)`；初始化 SQL 和运行时查询均已采用该显式限定，并以真实写入、读取和向量查询验证。
数据库返回的向量相似度会直接交给混合检索器复用，不会再次发送查询和候选正文做 Embedding。初始化 SQL
还为当前固定的 1024 维 `BAAI/bge-m3` 建立部分表达式 HNSW 索引；本地 PostgreSQL 0.8.3 已验证索引可创建
且执行计划可选择它。当前只有 313 个 chunk，小表默认计划仍可能选择精确索引加排序，不能据此宣称大规模
ANN 吞吐已经验收。

本轮 XLSX 的 60 份工作簿、360 个工作表均完成 artifact-tool 公式检查，并按说明页、短表全表以及长表
开头/中段/结尾生成 712 张 PNG；像素检查没有空白图或异常小图，人工抽查成功任务、字段映射、调度、
测试、事故和失败任务六类页面，没有发现明显截断或公式错误。事故台账、十类任务失败页和结构化
任务/Recovery/worker 资料都已纳入三视角字段门禁；20 类错误和 20 类运维作业生成的日志命令均能匹配
当前 Compose 服务。120 份 DOCX 均通过 ZIP/OOXML、样式、
Letter 页面、1 英寸页边距、文本提取和表格几何检查；300 张表的 `tblW/tblGrid/tcW/tblInd` 均一致。
本机没有安装 LibreOffice，因此 `render_docx.py` 的 DOCX 页面视觉渲染属于环境阻塞，不能宣称通过该项
视觉门禁。

## 7. 发布门禁与后续测评

默认质量门禁：Recall@K `>=0.80`、MRR `>=0.70`、nDCG@K `>=0.75`、引用精确率 `>=0.90`、
引用召回率 `>=0.80`、拒答 F1 `>=0.90`、禁止文档通过率 `=1.0`、单用例通过率 `>=0.85`、
范围泄漏率 `=0`。

发布前至少还需要：

1. 增加独立的 answerability/groundedness 判定，不能仅靠单一相似度阈值区分无答案与低词项语义问题；
2. 在生成前做证据去重和引用裁剪，重点提高引用精确率和禁止文档通过率；
3. 扩充不含唯一锚点的自然问法。目前 712 条可回答用例中有 356 条直接包含目标文档的唯一锚点或精确码，宏平均
   Recall/MRR 会高估真实自然语言检索质量，现有集合只能作为确定性合同集和首轮回归基线；
4. 在 PostgreSQL/pgvector 上完成全量黄金集与并发测评，记录冷/热 p50、p95、吞吐、批量摄取速度和索引大小；
5. 验证 429、超时、5xx、返回缺项、维度漂移时的 fail-closed、重试边界与告警；
6. 按 tenant/project 分层检查范围泄漏必须持续为零，并对模型、语料、chunk 参数和索引版本建立可比记录。

使用 `--enforce-quality-gate` 后，任何门禁失败都会返回退出码 `2`，可直接接入 CI。
