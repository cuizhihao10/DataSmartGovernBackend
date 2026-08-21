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

## 1.1 离线管线与线上模型的职责边界

离线管线和 Embedding/Reranker 不是二选一的两套 RAG。它们处在同一条链路的不同位置：

| 阶段 | 主要执行方式 | 解决的问题 | 是否作为线上质量主指标 |
| --- | --- | --- | --- |
| 文档摄取 | 离线或增量后台任务解析 Markdown、DOCX、XLSX、日志、JSON 等，切块并生成文档向量 | 把原始资料变成可检索索引，保存来源、范围、时间和版本 | 否，主要看摄取成功率、索引完整性和向量一致性 |
| 查询召回 | 线上请求生成 query embedding，并在 pgvector/全文索引中做范围过滤后的混合召回 | 找到“可能相关”的候选，优先保证相关文档不漏掉 | 是，重点看 Recall@K、候选覆盖率、范围泄漏率 |
| 精排 | 线上把有界候选窗口交给 `BAAI/bge-reranker-v2-m3` | 在相似候选中判断哪份证据最适合回答当前问题 | 是，重点看 MRR、nDCG、引用精确率和单用例通过率 |
| 证据治理 | 线上确定性门禁、时效/来源校验、去重、拒答和引用绑定 | 防止模型拿弱证据或越权/过期证据生成答案 | 是，范围泄漏必须为 0，拒答和禁止文档门禁必须通过 |
| 回归评测 | 离线固定黄金集，分别运行 lexical、Reranker 消融和 Embedding+Reranker 档位 | 比较算法版本、模型版本、chunk/阈值和索引版本是否退化 | 是发布前门禁，但不把离线词法分数冒充线上模型分数 |

实际生产中最常见的形态是“离线建立索引，线上查询和重排”：文档向量可以在摄取时批量生成并持久化，
但用户问题的向量必须在请求时生成；Reranker 通常也在请求时对当前候选重排。只有固定 FAQ、批处理报表等
场景才会把答案或重排结果预计算。DataSmart 的数据同步、事故恢复和运维问答都依赖实时范围、最新配置、
错误日志和权限事实，因此不能只依赖离线预计算结果。

因此当前真正需要优化的线上指标优先级是：

1. 先提高 Embedding/混合召回的 `Recall@K` 和候选覆盖率，避免目标资料根本没有进入 Reranker；
2. 再提高 Reranker 的 `MRR`、`nDCG@K` 和多证据排序，减少相似事故、成功案例和泛化手册互相串线；
3. 同时提高最终引用精确率、引用召回率、拒答 F1 和单用例通过率，不能只看“目标文档曾经被召回”；
4. 最后验证 p50/p95、吞吐、429/5xx/超时恢复和费用，确保模型质量提升没有破坏无人值守恢复的时延预算；
5. 全部指标都必须按租户/项目、问题类型、来源格式和是否多证据分层，不能让容易的精确码用例掩盖自然问法退化。

离线词法结果仍然不可省略：它是没有外部 Provider 时的可重复安全基线，用来守住范围隔离、拒答、过期
证据抑制、引用来源和算法回归；但它只能回答“基础管线有没有退化”，不能回答“BGE-M3 和 Reranker 在生产
语义检索上是否达标”。发布报告必须明确写出 `lexical`、`siliconflow-rerank` 和 `siliconflow` 档位，
并在同一 Manifest 指纹、同一黄金集和同一门禁下比较。

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

### 4.1 真实外发窗口与多证据路由

评测中的 ``candidateLimit`` 是召回器的上限，不能直接等同于 Reranker 真正阅读的文档数。远端
Provider 还受 ``DATASMART_RAG_RERANK_MAX_DOCUMENTS`` 约束；管线会在调用 Reranker 前调用同一个
``prepare_candidates`` 协议，并把返回值同时用于：

1. 发送到 ``/v1/rerank`` 的 ``documents`` 数组；
2. 低敏报告的 ``rerankerInputDocumentIds``；
3. 后续证据门禁、引用和单用例评测。

对于“接口追踪、Recovery 事件、分片 replay、最终验证”这类明确要求多个互补证据面的查询，Provider
会在已经通过租户/项目/工作区过滤、且已经被召回的候选中保留 facet 代表，再按融合排序补齐到上限。
这是一种有界的运行时 fan-out：不会重新扫描知识库、扩大权限范围、绕过 ``sourceStatus``/时效过滤，
也不会把候选总数扩到供应商上限之外。单一事实查询仍保持原始候选顺序，精确资料码仍优先。

本轮还固定了两个容易被误判成“Embedding 失效”的结构性问题：

* ``DATASMART_RAG_RERANK_VECTOR_RECALL_RESERVE_RATIO`` 默认 ``0.25``。在精确资料和职责 facet 保留
  后，Provider 至少为 vector-only 候选预留一个名额；因此词法候选填满前缀时，Embedding 已召回的
  互补资料仍会进入真实 ``/v1/rerank`` 请求。该配置只作用于已授权、已召回集合，不扩大扫描范围。
* ``DATASMART_RAG_RERANK_RETRIEVAL_PRIOR_WEIGHT`` 默认 ``0.2``。Reranker 的远端排序仍是主要信号，
  但会与本批次归一化的 fused/vector 先验做小幅融合；设置为 ``0`` 可复现纯远端模型基线。最终
  相对裁剪仅对已经通过证据门禁的高置信 vector-only 资料有限补回一条，不能绕过权限或拒答门禁。

每次普通 RAG 结果的内部评测快照还会写入 ``vectorStageMetrics``，包括
``vectorRetrievedCount``、``vectorInRerankerCount``、``vectorAcceptedCount`` 和
``vectorSelectedCount``，以及 vector-only 对应计数和窗口覆盖率。评测报告只保留数量和比例，不保存
问题、正文、Endpoint 或密钥；对 GraphRAG 则额外记录 ``graphEntityResolution``，区分精确别名与
受控语义消歧。若实体候选分数不够高或两个候选过近，GraphRAG 返回 ``ALIAS_NOT_FOUND``/
``AMBIGUOUS_ALIAS``，不会把普通 Embedding 近邻直接当作关系事实。

因此重现多文档窗口问题时应显式固定上限，例如：

```powershell
$env:SILICONFLOW_API_KEY = Read-Host "SiliconFlow API Key" -MaskInput
$env:DATASMART_RAG_RERANK_MAX_DOCUMENTS = "16"
python -B scripts/rag-evaluation.py `
  --profile siliconflow `
  --case-type cross_format_multi_document `
  --embedding-cache "$env:TEMP/datasmart-rag-bge-cache.sqlite" `
  --report "$env:TEMP/datasmart-rag-cross-format-window.json"
```

报告的 ``runProfile`` 会保存 ``candidateLimitPolicy``、``rerankerMaxDocuments`` 和不含敏感信息的
``retrievalParameters``。它不会保存 Endpoint、API Key、问题正文、文档正文或供应商原始响应。比较两次
指标前必须确认数据集指纹、模型名、候选策略和外发上限一致；否则只能把结果当作不同实验，不能宣称
算法改动带来的收益。

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

## 2026-08-18 复测记录与当前解释

本轮先完成离线算法回归，再用受控 Secret 调用远程 BGE。当前管线测试为 `45 passed`；五个运行时
RAG/Embedding/Persistence 测试文件与评测资产合同合计 `103 passed`，`compileall` 和 `git diff --check` 通过；
Python Runtime 全测试目录本轮为 `1263 passed, 1 skipped`。

运行时新增的意图先验不是黄金答案表，也不包含任何文档 ID。它把“成功任务参数、字段映射、Worker 日志、API 合同、
Recovery 决策、限流事故”等自然表达映射到 Manifest 的职责 `category`，只作为很小的排序修正；权限范围、来源状态、
词法/向量证据和 Reranker 门槛仍然优先。多证据选择先按 facet 做集合覆盖；某 facet 已有
`intentScore >= 0.85` 的明确职责候选时，通用资料不能只凭整句词法重合宣告该 facet 已覆盖。facet 文本负责激活职责，
整句上下文只在 Recovery 职责内部区分 replay 案例和事件流水。所有候选都必须先通过上游门禁，且不会为了填满
`topK` 被强行引用。

拒答用例曾被“规则的”这类中文 n-gram 边界碎片误放行。本轮在生成拒答锚点前移除固定治理泛词，只保留未知实体和
稳定字段/错误码；因此“火星冷链、量子账本、海岛传感器”等知识库外实体能够触发更高的无锚点门槛，而“当前阈值、
普通规则”不会单独构成答案依据。对 facet 来说，两个以上独立两字业务词可以联合证明主题，避免“授权事实”被错误拒绝。

本轮可复现的离线子集结果如下。报告文件位于本机临时目录，不进入仓库：

| 命令筛选 | 用例 | 关键结果 | 门禁 |
| --- | ---: | --- | --- |
| `multi_document` | 12 | Recall/MRR=`1.0/1.0`，nDCG=`0.889327`，引用精确率/召回率=`1.0/1.0`，通过 `12/12` | 通过 |
| `cross-format-multi-global-*` | 12 | Recall/MRR=`1.0/1.0`，nDCG=`0.876666`，引用精确率/召回率=`1.0/1.0`，通过 `12/12` | 通过 |
| 代表性跨格式用例 | 5 | Recall/MRR=`1.0/1.0`，nDCG=`0.866204`，引用精确率/召回率=`1.0/1.0`，通过 `5/5` | 通过 |
| `no_answer` | 12 | 拒答 Precision/Recall/F1=`1.0/1.0/1.0`，通过 `12/12` | 通过 |
| `stale_conflict` | 12 | Recall/MRR/nDCG/引用指标=`1.0`，过期抑制=`1.0`，通过 `12/12` | 通过 |
| `history_lookup` | 16 | Recall/MRR/nDCG/引用指标=`1.0`，通过 `16/16` | 通过 |
| `cross_scope_refusal` | 28 | 范围泄漏=`0`，拒答 F1=`1.0`，通过 `28/28` | 通过 |
| `exact_error_code` | 80 | Recall/MRR/nDCG/引用指标=`1.0`，通过 `80/80` | 通过 |

`multi_document` 在此前一次过宽补充阶段出现过额外引用；收窄 Checkpoint 意图、增加职责门禁并传递生命周期上下文后，
当前跨格式全局集和代表集的引用精确率/召回率均为 `1.0`。这说明评测必须同时看 Recall、Citation Precision、Citation
Recall 和单用例通过率，不能只看“目标文档是否曾出现在候选窗口”。

2026-08-18 已完成一次真实 SiliconFlow smoke。密钥通过当前 PowerShell 进程的隐藏输入转为环境变量，
没有写入仓库、命令行参数、报告或 Git，评测结束后立即移除。当前 356 份文档、752 条黄金集的同一数据集
指纹上，`siliconflow` 档位实际标记并调用 `BAAI/bge-m3` Embedding 和 `BAAI/bge-reranker-v2-m3` Reranker；
20 条 `cross_format_semantic` 用例执行错误数为 0，结果如下：

| 指标 | 当前 20 条 smoke |
| --- | ---: |
| Recall@K / MRR / nDCG@K | `1.000000 / 1.000000 / 1.000000` |
| 引用精确率 / 引用召回率 | `1.000000 / 1.000000` |
| 范围泄漏率 | `0.000000` |
| 禁止文档通过率 / 过期抑制率 | `1.000000 / 1.000000` |
| 单用例通过率 | `1.000000`（20/20） |
| p50 / p95 | `3760 ms / 17363 ms` |

另一个包含告警历史、连接器清单、可观测性手册和 Schema 恢复手册的 4 条职责聚焦集也为 `4/4`，
所有核心检索和引用指标为 `1.0`。这已经证明当前实现不是“只做离线”：查询向量和候选重排都实际经过远程
模型；但 20 条 smoke 不能替代 752 条全量、真实 PostgreSQL/pgvector、并发容量和生产稳定性验收。报告位于
本机临时目录，不纳入仓库；Embedding SQLite 缓存同样位于仓库外。

下一轮应在同一 Manifest 指纹上执行完整 `siliconflow`，并单独保存 embedding cache、模型名、数据集指纹、
限流/重试统计和 p50/p95。若 Provider 不可达，应记录为环境失败，不能改写成代码失败或伪造通过。

剩余发布项保持明确：当前离线子集通过不代表 356/752 全量门禁通过；仍需复跑全量词法与 BGE、在 PostgreSQL/pgvector
上验证真实向量与 HNSW、执行并发/容量/429/超时/5xx 故障测试，并完成 Java Kafka、Recovery 和前端 API/WebSocket 的
真实端到端合同验证。LibreOffice 页面渲染若本机未安装，仍记录为视觉 QA 环境阻塞，不影响 OOXML/提取文本合同结果。

## 2026-08-20 当前复核：自主路由已接入，质量门禁仍未通过

当前运行时默认 `retrievalMode=auto`，由 Agent 路由模型在 `hybrid`、`graph` 和 `hybrid_graph` 之间选择；没有
模型决策、模型输出非法或 GraphRAG 能力未装配时，系统记录受控兜底原因并保持 fail-closed。前端 `/agent/rag/query`
调用已显式提交 `retrievalMode=auto`，并能读取 `decisionMode`、`decisionSource`、`graphPath` 和 `graphCitations`。

本轮代码修改后的后端聚焦回归为 `127 passed`，前端 `build`、`lint`、API adapter contract 和 Agent control-plane
contract 均通过。新增的职责候选保护只会从已经通过 scope/source 过滤的候选中选一个文档代表，不能凭资料 category
扩大检索范围；因此它解决的是“目标已召回但未进入 BGE Reranker 窗口”的结构性损失，而不是把黄金集答案写进代码。

最近一份可用的 356 文档、752 用例真实 BGE 报告（Embedding=`BAAI/bge-m3`，Reranker=`BAAI/bge-reranker-v2-m3`）
指标仍为：Recall@K `0.735019`、MRR `0.740403`、nDCG `0.729877`、引用精确率 `0.725070`、引用召回率
`0.735019`、拒答 F1 `0.824742`、单用例通过率 `0.710106`、执行错误 `0`。因此当前结论仍是“实现已继续优化，
但 RAG 生产质量门禁未完成”，不能把单元测试或 20 条 smoke 当作全量达标。

本机当前 PowerShell 进程未发现 `SILICONFLOW_API_KEY`、`DATASMART_RAG_EMBEDDING_API_KEY` 或
`DATASMART_RAG_RERANK_API_KEY` 环境变量。真实全量复测必须由 Secret Manager 或当前进程临时注入这些变量；脚本
不会从命令行读取密钥，也不会把密钥写入报告、缓存、仓库或日志。缺少变量时属于环境阻塞，不计作模型或代码执行错误。

下一轮真实评测应固定同一 Manifest 指纹，重新保存独立的 Embedding 缓存和报告，并重点对比：目标文档是否进入
Reranker 输入窗口、职责候选是否通过 evidence gate、多文档 facet 是否完整、引用是否多余，以及 p50/p95 是否受
候选窗口保护影响。只有全量报告达到质量门禁，才能更新本文档为“RAG 优化完成”。
