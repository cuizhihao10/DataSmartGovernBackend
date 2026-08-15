# DataSmart RAG 黄金集与硅基流动 BGE 评测 Runbook

本文说明 DataSmart Govern 如何校验、摄取和评测中文 RAG 资产，以及如何以硅基流动
`BAAI/bge-m3` 和 `BAAI/bge-reranker-v2-m3` 形成可重复对照实验。

## 1. 资产边界

评测资产位于 `python-ai-runtime/evaluation/rag/`：

- `documents/`：96 份原创合成中文 Markdown；
- `manifest.json`：文档 ID、路径、来源 URI、租户/项目范围、来源类型、标签、证据状态和 SHA-256；
- `golden_cases.jsonl`：168 条黄金问题；
- `test_rag_evaluation_assets.py`：不依赖应用和第三方包的资产合同测试；
- `README.md`：规模、分布、用例类型和再生成说明。

全部资产都是 `synthetic-only`，不能宣传成客户事故、客户文档或生产数据。四种范围中包含同主题、
同错误码、不同结论的近重复文档，用于验证检索是否先执行 `tenantId/projectId/workspaceKey` 硬过滤。

来源类型分布：

| 来源类型 | 数量 | 用途 |
| --- | ---: | --- |
| `document` | 12 | 架构和产品说明 |
| `wiki` | 8 | 产品与架构知识 |
| `runbook` | 12 | 运维处置步骤 |
| `incident` | 12 | 已复盘的现行事故案例 |
| `task_case` | 12 | 数据同步任务案例 |
| `dataset` | 12 | 数据字典和数据集说明 |
| `rule` | 12 | 权限、分级和审批规则 |
| `git_history` | 16 | 已被替代的历史依据 |

黄金用例保存问题、期望文档及相关性等级、期望引用 URI、禁止文档、拒答条件、拒答原因、来源类型、
标签和范围三元组。评测报告不保存问题正文、文档正文、模型原始输出、Endpoint 或密钥。

## 2. 完整性校验

```powershell
python -B scripts/generate-rag-evaluation-assets.py --check
python -B -m unittest discover -s python-ai-runtime/evaluation/rag -p "test_*.py" -v
python -B scripts/rag-evaluation.py --validate-only
python -B scripts/rag-corpus-ingest.py
```

前三个命令分别验证生成器输出、静态资产合同和运行时加载合同。最后一个命令默认只校验，不连接数据库。
任何 Markdown 字节变化、路径越界、哈希不匹配、引用不存在、相关文档越权或拒答条件不完整都会失败。

## 3. 离线词法基线

```powershell
python -B scripts/rag-evaluation.py --profile lexical --report "$env:TEMP/datasmart-rag-lexical.json"
```

2026-08-16 在 96 份文档、168 条用例上的当前基线：

| 指标 | 结果 |
| --- | ---: |
| Recall@K | 1.000000 |
| MRR | 0.928241 |
| nDCG@K | 0.939545 |
| 引用精确率 | 0.334259 |
| 引用召回率 | 1.000000 |
| 拒答 F1 | 0.666667 |
| 范围泄漏率 | 0.000000 |
| 禁止文档通过率 | 0.365385 |
| 过期证据抑制率 | 1.000000 |
| 单用例通过率 | 0.071429 |
| p50 / p95 | 8 ms / 38 ms |

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
可通过 `--case-type semantic_paraphrase` 或 `--limit 20` 做小范围连通性验证，再运行全部 168 条用例。
`SILICONFLOW_API_KEY` 可以明确作为共享凭据；两个 RAG 专用 Key 则严格按能力隔离，缺少其中任意一个时
都会停止评测，不会把 Embedding Key 发送给 Reranker，或反向复用。

2026-08-16 已使用运行时 Secret 完成一轮全新的全量真实调用。运行结束后清除了进程环境中的密钥；仓库、报告和
容器配置均未保存真实密钥。资产指纹为
`77a3a709c6be04c85ad938cd023861fe6519a36c8f88dc8a00f1b06297d38ac0`，168 条用例执行错误数为 0：

| 指标 | SiliconFlow BGE 结果 |
| --- | ---: |
| Recall@K | 1.000000 |
| MRR | 0.978009 |
| nDCG@K | 0.978937 |
| 引用精确率 | 0.334259 |
| 引用召回率 | 1.000000 |
| 拒答精确率 / 召回率 / F1 | 1.000000 / 0.500000 / 0.666667 |
| 范围泄漏率 | 0.000000 |
| 禁止文档通过率 | 0.365385 |
| 过期证据抑制率 | 1.000000 |
| 单用例通过率 | 0.071429 |
| p50 / p95 | 313 ms / 1034 ms |

真实 BGE 同样没有通过发布门禁，失败项还包括单用例通过率。它证明 Provider、Embedding、Reranker 和范围隔离链路可以完成全量运行，
但当前仍会保留过多证据，且对无答案问题的拒答召回不足。一次连通性成功不等于费用、限流、并发、故障
恢复或生产稳定性已经验收。

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

摄取流程先校验 96 份文档和 168 条用例，再在文档/chunk 硬上限内切块。Embedding 批量调用位于数据库
锁外，在线查询不会因远程模型延迟被长时间阻塞；向量准备完成后，数据库事务才按“租户 + 项目 +
workspace + documentId”替换每份文档的旧 chunk。chunk 主键也包含完整范围的稳定摘要，因此不同租户的
同名 documentId 不会覆盖。输出只包含文档数、chunk 数、存储类型、是否启用向量、模型名和数据集指纹，
不包含 DSN、正文、Endpoint 或密钥。该入口不会删除 Manifest 之外的知识文档。

摄取入口还会校验运行模式。它只允许 `local/development/dev/test/testing/learning`，对空值、`production`、
`prod`、`staging` 和 `preprod` 一律拒绝，即使操作者给出了确认参数也不能把合成语料写入生产知识库。

本机 PostgreSQL 已完成一次真实摄取验证：96 份文档生成 96 个 chunk，全部使用 1024 维
`BAAI/bge-m3` 向量，四个范围各 24 份，来源类型分布与 Manifest 一致。由于连接固定
`search_path=ai_memory`，pgvector 类型和距离运算符必须分别写为 `public.vector` 与
`OPERATOR(public.<=>)`；初始化 SQL 和运行时查询均已采用该显式限定，并以真实写入、读取和向量查询验证。
数据库返回的向量相似度会直接交给混合检索器复用，不会再次发送查询和候选正文做 Embedding。初始化 SQL
还为当前固定的 1024 维 `BAAI/bge-m3` 建立部分表达式 HNSW 索引；本地 PostgreSQL 0.8.3 已验证索引可创建
且执行计划可选择它。当前只有 96 个 chunk，小表默认计划仍可能选择精确索引加排序，不能据此宣称大规模
ANN 吞吐已经验收。

## 7. 发布门禁与后续测评

默认质量门禁：Recall@K `>=0.80`、MRR `>=0.70`、nDCG@K `>=0.75`、引用精确率 `>=0.90`、
引用召回率 `>=0.80`、拒答 F1 `>=0.90`、禁止文档通过率 `=1.0`、单用例通过率 `>=0.85`、
范围泄漏率 `=0`。

发布前至少还需要：

1. 增加独立的 answerability/groundedness 判定，不能仅靠单一相似度阈值区分无答案与低词项语义问题；
2. 在生成前做证据去重和引用裁剪，重点提高引用精确率和禁止文档通过率；
3. 扩充不含唯一锚点的自然问法。目前 144 条可回答用例中有 96 条直接包含目标文档的唯一锚点，宏平均
   Recall/MRR 会高估真实自然语言检索质量，现有集合只能作为确定性合同集和首轮回归基线；
4. 在 PostgreSQL/pgvector 上完成全量黄金集与并发测评，记录冷/热 p50、p95、吞吐、批量摄取速度和索引大小；
5. 验证 429、超时、5xx、返回缺项、维度漂移时的 fail-closed、重试边界与告警；
6. 按 tenant/project 分层检查范围泄漏必须持续为零，并对模型、语料、chunk 参数和索引版本建立可比记录。

使用 `--enforce-quality-gate` 后，任何门禁失败都会返回退出码 `2`，可直接接入 CI。
