# DataSmart RAG 管线实现说明

本文记录 Python Runtime 当前 RAG（Retrieval-Augmented Generation，检索增强生成）能力的实现原理、代码边界和后续演进方向。它不是简单地“调用某个框架的 retriever API”，而是把 RAG 的核心阶段拆成可解释、可测试、可替换的工作单元，方便后续接入 LangGraph、多 Agent、pgvector、Neo4j GraphRAG 或企业搜索服务。

## 2026-08-20 Neo4j Provider 运行状态

GraphRAG 的代码适配器已经接入 `api/app.py` 的默认 RAG 管线，Agent 在 `retrievalMode=auto` 下可以把
普通 Hybrid RAG、GraphRAG 或 `hybrid_graph` 联合检索作为模型决策结果。此前本地运行容器仍处于旧状态：
Compose 的 Provider 值为 `disabled`，旧镜像没有安装 Neo4j Python Driver，因此模型选择图路径时会记录
`MODEL_CAPABILITY_FALLBACK` 并回退普通 RAG。

本轮已完成运行时修正并在本机 Docker 验证：

- `docker-compose.application.yml` 和 `.env.application.example` 的完整 Compose 默认启用
  `DATASMART_GRAPH_RAG_PROVIDER=neo4j`，生产环境仍可显式设为 `disabled`；
- Python Runtime 构建参数包含 `graph` extra，镜像实际安装 `neo4j` Driver；
- `DATASMART_GRAPH_RAG_INITIALIZE_SCHEMA=true` 启动时幂等创建标准实体约束和别名索引；
- `GET /agent/rag/diagnostics` 已返回 `graphRag.provider=neo4j`、`available=true`、`enabled=true`；
- Neo4j 容器健康检查通过；结构初始化完成后，受控摄取样本已写入 `3` 个实体和 `2` 条关系。

最后一项是有意保留的事实边界：Provider 已连接不等于所有业务图数据都已入库。当前只摄取了一份合成
组织关系事实；没有真实实体、来源、时间和关系证据时，GraphRAG 会按 `ALIAS_NOT_FOUND` 或
`NO_CURRENT_PATH` 安全拒答，普通文档相似度不会掩盖关系证据缺失。

## 2026-08-20 受控图数据摄取：结构初始化与事实物化的区别

这里要区分两个容易混淆的动作：

1. **数据库结构初始化**：`DATASMART_GRAPH_RAG_INITIALIZE_SCHEMA=true` 只创建 `GraphEntity.standard_id`
   唯一约束和别名索引。它不会从 Markdown、DOCX、XLSX 或日志中自动猜关系，也不会产生任何业务实体。
2. **图事实物化/摄取**：`scripts/rag-graph-ingest.py` 读取已授权、已审批的 JSON 图事实包，把文档中的
   显式 `graphEntities` 和 `graphRelations` 校验后幂等写入 Neo4j。每条关系必须保留来源文档 ID、原始
   source URI、source chunk、断言时间、生效/失效时间、可信度、状态和 tenant/application/project 范围。

因此，图数据可以依据文档内容初始化，但不是“把所有文档交给模型后直接写库”。推荐生产链路是：

```text
已授权文档 -> 规则或模型生成候选事实 -> 人工/治理流程审批 -> 全量来源与范围校验
-> Neo4j 幂等摄取 -> GraphRAG 有限跳查询 -> 返回完整引用链
```

模型可以参与“从文档提出候选实体和关系”，但不能绕过 `APPROVED`、`sourceStatus=COMPLETE`、稳定
实体 ID、来源一致性、范围继承和当前关系冲突检查。当前摄取器只接受 `REPORTS_TO`，因为这是已经
接入查询核心并有冲突拒答语义的关系；血缘、字段映射、任务依赖等关系应在补齐对应查询和黄金集后
分别扩展，不应先把未经定义的关系写入生产图。

本地合成验证命令：

```powershell
# 默认只校验，不连接 Neo4j。
python scripts/rag-graph-ingest.py

# 明确确认后才写入当前 Compose Neo4j；重复执行不会增加重复节点或关系。
python scripts/rag-graph-ingest.py --ingest --confirm-controlled-graph-facts
```

真实数据同步业务图谱使用另一条明确的快照合同：

```powershell
python scripts/rag-business-graph-build.py `
  --snapshot evaluation/rag/graph/business-sync-snapshot.json `
  --output evaluation/rag/graph/business-sync-facts.json
```

快照由控制面导出低敏的应用、项目、数据源、Schema、表、字段、约束、任务版本、执行、日志、错误码、
字段映射、Runbook、事故和恢复动作；构建器生成 `PROPOSED` 事实和稳定 fingerprint，不能直接写 Neo4j。
随后由 permission-admin 完成双主体审批并通过 Kafka/outbox 发布，Python consumer 回查审批、范围、数量和
fingerprint 后才调用受控摄取器。业务图中的 `EXECUTION_HAS_LOG`、`LOG_MATCHES_ERROR`、
`FIELD_HAS_CONSTRAINT` 和 `TASK_HAS_VERSION` 关系使“日志错误 -> 约束/映射/成功版本 -> Runbook/修复动作”
可以在同一应用范围内回溯；没有审批或来源不完整时仍 fail-closed。

该流程目前验证了 3 个实体、2 条关系、两跳“小张 -> 李四 -> 王五”以及重复摄取后的 `3/2`
计数稳定。后续剩余工作是让组织目录、数据血缘和任务元数据等真实业务来源生成同一格式的已审批事实，
并为每种关系补充对应的冲突、时间和跨范围黄金用例；这不是数据库启动失败，而是业务事实尚未覆盖的范围。

## 1. RAG 在项目中的定位

DataSmart 的 AI 层现在同时存在两类“知识”：

- `Agent Memory`：偏用户画像、会话历史、任务经验、长期记忆、偏好和操作事实，目标是让 Agent 知道“这个用户/这个项目过去发生了什么”。
- `RAG Knowledge`：偏企业文档、产品说明、治理规则、字段口径、数据质量规则、权限手册、运维 runbook 和可引用证据，目标是让模型在回答时有“可追溯出处”。

这两者不能混在一起。长期记忆可以辅助个性化和上下文恢复，但 RAG 回答必须以可引用文档作为证据，否则容易把用户偏好、历史猜测或模型幻觉当成产品规则。

当前实现位于：

- `python-ai-runtime/src/datasmart_ai_runtime/services/rag/models.py`
- `python-ai-runtime/src/datasmart_ai_runtime/services/rag/text.py`
- `python-ai-runtime/src/datasmart_ai_runtime/services/rag/knowledge_base.py`
- `python-ai-runtime/src/datasmart_ai_runtime/services/rag/persistence.py`
- `python-ai-runtime/src/datasmart_ai_runtime/services/rag/pipeline.py`
- `python-ai-runtime/src/datasmart_ai_runtime/services/rag/reranker_provider.py`
- `python-ai-runtime/src/datasmart_ai_runtime/services/rag/evaluation.py`
- `python-ai-runtime/src/datasmart_ai_runtime/services/rag/components.py`
- `python-ai-runtime/src/datasmart_ai_runtime/services/multi_agent/knowledge_agent_capability.py`
- `python-ai-runtime/src/datasmart_ai_runtime/api/rag.py`

API 已接入：

- `POST /agent/rag/query`
- `POST /api/agent/rag/query`
- `GET /agent/rag/diagnostics`
- `GET /api/agent/rag/diagnostics`

LangGraph durable checkpoint 已接入：

- `rag_retrieve_knowledge`：记录召回候选数、证据数量、lexical/vector 信号和 scope；
- `rag_evidence_gate`：记录证据门控接受/拒绝数量、fail-closed 决策和引用要求；
- `rag_grounded_answer_completed`：记录有证据约束的回答或证据摘要已完成；
- `rag_no_evidence_completed`：记录无合格证据时已按 fail-closed 策略收口。

这些 checkpoint 只保存低敏计数、策略、状态码和多 Agent 角色状态，不保存用户问题、答案、compressedContext、
文档正文、sourceUri、prompt 或模型原始响应。

多 Agent runner 已接入：

- 默认工具目录新增 `knowledge.rag.query`，该工具只携带 `queryRef`、`scopePolicy` 和 `evidencePolicy` 等低敏参数。
- 默认 Skill 注册表新增 `knowledge.rag.answer`，用于把治理知识库、业务口径、规则说明和 runbook 问答交给 `KNOWLEDGE_AGENT`。
- `AgentSessionScheduler` 可根据 `knowledge.`、`rag.`、`web.search.` 前缀或 `KNOWLEDGE_QA` 意图激活 `KNOWLEDGE_AGENT`。
- `agentTurnRunner` 新增 `bind_knowledge_agent_rag_capabilities` 节点，当 `KNOWLEDGE_AGENT` 参与时输出 `knowledgeAgentCapabilities`。
- `knowledgeAgentCapabilities` 只声明 RAG graph、节点、证据门控、checkpoint 和 Java 控制面边界，不执行 RAG、不调用模型、不保存证据正文。
- `agentTurnRunner` 低敏摘要已可写入 LangGraph durable checkpoint，恢复时可以看到 `KNOWLEDGE_AGENT` 是否具备 RAG 能力、当前是否需要 Java 控制面或 worker receipt。

## 2. 当前管线阶段

一次 RAG 问答会经过以下阶段：

1. `query validation`：规范化租户、应用、项目、topK、候选窗口和上下文预算，防止外部请求无限扩大召回或 prompt 长度。
2. `scope filter`：在任何排序前先做 `tenantId/applicationId/projectId` 过滤，避免跨租户或跨应用文档先参与向量排序再过滤造成泄漏风险。
3. `chunking`：把文档切成 chunk，保留少量 overlap，让答案所需信息不容易被切在边界外。
4. `lexical score`：使用轻量 BM25 风格词项分，标题和 tag 命中权重大于正文命中。
5. `optional vector score`：如果配置了 embedding provider，则计算 query/chunk 的余弦相似度。
6. `RRF fusion`：用 Reciprocal Rank Fusion 融合词项召回和向量召回，避免两类分数尺度不同导致简单加权失真。
7. `rerank`：把完整 `candidateLimit` 候选窗口交给独立 Reranker，再决定最终 topK；未配置远程模型时使用可解释规则。当前已适配硅基流动 `BAAI/bge-reranker-v2-m3`，异常按 fail-closed 处理。
8. `evidence gate`：在重排后执行证据强度门控，过滤只命中单个泛词或低质量近邻的弱证据。
9. `MMR diversity`：只在合格证据中选择最终 topK，在相关性和多样性之间平衡；MMR 不覆盖 Reranker 的相关性分数。
10. `context compression`：按问题相关词压缩 chunk，优先保留命中句子，控制进入模型的上下文长度。
11. `model generation`：通过统一 `ModelQueryEngine` 调用治理问答模型，继承模型路由、限流、预算、fallback 和低敏错误处理。
12. `citation binding`：答案必须绑定 `[C1]`、`[C2]` 这类引用编号，方便审计和回溯。
13. `langgraph checkpoint`：把 RAG 的检索、证据门控和最终收口写入 durable checkpoint，支持后续暂停、恢复、分支和多 Agent 状态恢复。
14. `multi-agent capability binding`：当用户目标属于治理知识问答时，`KNOWLEDGE_AGENT` 会把 `knowledge.rag.query` 作为 manager-as-tools 能力暴露给主控；真实执行仍需 Java 控制面、checkpoint 和 worker receipt 补齐。
15. `turn runner checkpoint`：把本轮多 Agent turn 的 role/status、required evidence、RAG capability code 和下一步动作写入 durable checkpoint；该状态不包含用户问题、证据正文或模型回答。

## 3. 为什么要做证据门控

RAG 很容易出现一种隐蔽问题：检索系统总会返回“最像”的候选，但“最像”不等于“足够可引用”。

例如用户问“完全不存在的火星仓库调度策略”，文档中如果出现“审批策略”，词项检索可能因为“策略”这个泛词命中质量文档。没有门控时，模型会拿着这段弱证据生成一个看似合理但没有依据的答案。

当前 `RagPipelineSettings` 提供三类门槛：

- `minimum_lexical_score`：词项召回最低证据分。
- `minimum_match_terms`：至少命中的 token 数。
- `minimum_vector_score`：向量召回最低相似度。

候选 chunk 只要满足“强词项证据”或“强向量证据”之一，才允许进入压缩上下文。否则会计入 `weakEvidenceRejectedCount`，并在无合格证据时返回 fail-closed 文案：

```text
当前知识库没有召回到足够证据，已拒绝无依据生成。
```

这个设计比“只要检索到东西就让模型答”更适合数据治理产品，因为治理问答、权限说明、质量规则和运维 runbook 都有审计和误导风险。

## 4. 当前没有直接重度依赖 LangChain 的原因

项目后续可以使用 LangChain、LangGraph、LlamaIndex 等框架，但 RAG V1 先保留自研的轻量核心流程，原因是：

- 面试或架构评审时能讲清 RAG 的每一步，而不是只说“调了框架 API”。
- 关键安全边界，例如租户过滤、证据门控、低敏诊断和引用绑定，需要符合本项目治理要求。
- 后续替换 pgvector、Neo4j GraphRAG、企业搜索或专用 reranker 时，上层 API 不需要重写。

LangGraph 更适合作为 Agent 执行状态机，负责暂停、恢复、循环、分支、多 Agent 协作和 checkpoint；RAG 管线则适合作为其中一个可观测节点，例如：

```text
MASTER_ORCHESTRATOR
  -> retrieve_governance_knowledge
  -> rerank_and_gate_evidence
  -> generate_grounded_answer
  -> DATA_QUALITY_AGENT / PERMISSION_AGENT / TASK_AGENT
```

## 5. 当前 V1 边界

已完成：

- 内存知识库 V1，适合单测、本地学习和 API smoke。
- 中英文混合 token、文档切块、overlap、压缩。
- lexical/vector 两路召回接口。
- RRF 融合与 MMR 去冗余。
- 证据门控、弱证据拒绝、无证据 fail-closed。
- 统一模型查询引擎生成。
- API 查询和诊断路由。
- LangGraph checkpoint 节点化，已形成 `retrieve -> evidence_gate -> completed/no_evidence` 低敏状态链路。
- `knowledge.rag.query` 工具定义、`knowledge.rag.answer` Skill、意图识别、ToolPlan 低敏 `queryRef` 规划。
- `KNOWLEDGE_AGENT` RAG 能力合同已进入 `agentTurnRunner.knowledgeAgentCapabilities`，可被主控以 manager-as-tools 方式调度。
- `agentTurnRunner` 已可把 RAG 能力、Agent role/status、requiredEvidenceCodes 和 handoff 状态写入 LangGraph durable checkpoint。
- 多 Agent 执行前计划已把 `KNOWLEDGE_AGENT -> DATA_QUALITY_AGENT/PERMISSION_AGENT/TASK_AGENT/...` 建模为 `supports_context` 协作边。
- 单元测试覆盖召回、租户隔离、无证据拒绝和 API 合同。
- PostgreSQL 持久化词法知识库与可选 pgvector 查询路径，范围谓词在排序前进入同一 SQL。
- 查询显式点名其他租户或项目时，在进入 retriever 前直接拒绝，避免用当前范围的相似资料回答越权目标。
- 现行查询在进入 Reranker 前排除 `superseded` 证据；只有来源唯一限定为 `git_history` 的审计追溯才允许读取历史版本。
- 候选窗口先重排、再证据门控和 MMR 选取 topK，内部候选快照仅供评测，不进入 API 摘要。
- OpenAI-compatible 批量 Embedding Provider，摄取和内存评测均按有界批次生成 chunk 向量。
- 硅基流动 `BAAI/bge-m3` / `BAAI/bge-reranker-v2-m3` 配置与独立重排适配器。
- 356 份合成中文异构文档、752 条黄金用例、原文件/提取文本双哈希、低敏评测报告和质量门禁；
  物理格式覆盖 Markdown、DOCX、XLSX、TXT、JSON、JSONL、CSV、LOG 和 SQL。Word、Excel 和结构化资料
  按文档职责独立建模，接口资料从源码扫描 475 条真实合同，不再复用通用事故模板。
- 运维与事故检索证据已使用统一三视角合同：用户操作和页面提示、运维日志位置与逐步定位、开发根因与
  配置/代码修复分别保存；所有记录同时保留 scope、来源 URI、时间、可信度和状态。四个范围共 560 条
  运维作业、1,000 条事故记录，20 类错误码均有可复制低敏日志和可执行 Compose 日志命令。

尚未完成但已预留接口：

- Neo4j GraphRAG 已提供可选分支，用于血缘、表关系、业务口径和资产图谱推理；完整 Compose 默认启用，
  单模块开发仍可默认关闭，启用后始终受标准实体 ID/别名、范围、时间有效性、最多三跳、冲突拒答和完整引用链约束。
- MinIO 文档解析、增量索引、删除重建和索引版本管理。
- 历史 188/308 基线已完成一次真实 BGE 全量评测，以及 188 文档/313 chunk 的本机 pgvector 摄取和
  DOCX/XLSX 原始 URI 查询 smoke；这些结果不代表当前 356/752 语料。新指纹上的 BGE、pgvector 基准、
  并发、限流和故障注入仍待执行。
- RAG 的真实执行 handoff：当前 runner 已输出低敏能力合同并写入 durable checkpoint，但尚未自动创建 Java outbox、派发 worker 或把 RAG 结果作为低敏 specialist summary 回填给 DATA_QUALITY_AGENT/PERMISSION_AGENT/TASK_AGENT。

## 6. 面试讲解要点

可以按下面思路讲：

- RAG 不是“向量数据库 + 大模型”这么简单，而是 `ingestion -> chunk -> retrieve -> rerank -> gate -> compress -> generate -> cite` 的完整链路。
- 多租户系统必须先过滤 scope 再排序，不能先全局向量搜索再过滤。
- lexical 适合精确术语、字段名、规则名；vector 适合语义相似；RRF 用于融合两种排序。
- MMR 解决 topK 冗余问题，避免召回结果全是同一文档相似段落。
- 证据门控解决“弱命中也生成”的问题，是降低幻觉和误导的关键。
- 引用绑定让答案可追溯，适合治理、权限、质量规则这类需要审计的场景。
- 当前实现把模型生成、embedding、reranker、知识库都隔离成可替换组件，后续可平滑接入 pgvector、GraphRAG 和专用模型。
- 在 Agent 层，RAG 已不是孤立 API：`KNOWLEDGE_AGENT` 能通过 turn runner 暴露 `knowledge.rag.query` 能力，且 turn runner 状态可进入 durable checkpoint；但执行副作用继续由 Java 控制面、RAG pipeline 和 worker receipt 承接。

## 2026-07-05 补充：RAG 进入 Java outbox / worker receipt 低敏闭环

本阶段把 RAG 从“多 Agent 能力合同 + LangGraph checkpoint”继续推进到 Java 控制面闭环：

- `ToolActionExecutionGraphRunner` 已支持显式注入 `JavaToolActionCommandOutboxClient`，当 Java proposal 返回 `outboxWriteAllowedByPreflight=true` 时，可以继续调用 `/agent-runtime/tool-action-commands/outbox/write`。
- outbox writer 默认仍是 disabled/fail-closed；未配置时停在 `WAITING_OUTBOX_CONFIRMATION`，配置但未启用时停在 `OUTBOX_CLIENT_DISABLED`，真实写入成功后进入 `OUTBOX_ENQUEUED`。
- `services/rag/command_worker_receipt.py` 新增 RAG 专用 worker receipt helper，把 `knowledge.rag.query` 的执行结果裁剪成 Java `AgentToolActionCommandWorkerReceiptRequest` 可消费的低敏 payload。
- RAG receipt 只保存 `queryRef`、`commandId`、`artifactReference`、候选数、选中 chunk 数和引用数，不保存 question、answer、compressedContext、document body、chunk text、sourceUri、prompt、SQL、endpoint、token 或 secret。
- 这一步仍不代表 dispatcher/worker 已经完整自动消费 outbox；它完成的是 `proposal -> outbox/write -> worker receipt payload` 的 Python/Java 控制面契约闭环，为后续真实 E2E worker 消费铺路。

## 2026-08-11 补充：Recovery 按需 RAG 与双 durable turn 边界

Recovery 不是“发生失败就先检索”的固定流程。`RECOVERY_AGENT` 让模型在低敏失败事实、诊断覆盖、已有引用和自身置信度基础上输出 `ragDecision=SEARCH|SKIP`、原因和置信度；`AgentSessionScheduler` 也只在结构化恢复证据需求成立时让 `KNOWLEDGE_AGENT` 参与，不把 RAG 设为每轮 Recovery 的第一步。

- 普通同步规划也采用同一原则：结构化意图只决定 `knowledge.rag.query` 是否进入 model-visible tools；模型可以在获得候选工具后调用或跳过。规则式 ToolPlanner 不得把 `useRag`、`knowledgeQuery` 等请求变量重新解释成强制 RAG ToolPlan，否则会把模型的 `SKIP` 覆盖掉。这里的自主性只针对“是否使用已授权的检索工具”，不改变 Java 的权限、审计、审批和副作用边界。
- `SEARCH` 且当前没有 grounded 知识时，系统会丢弃同轮任何修复建议，只保留 `SEARCH_RECOVERY_KNOWLEDGE -> sync.execution.rag.lookup` 的只读动作。检索结果先作为低敏 durable evidence/reference 固化，下一 turn 才能重新提出恢复候选，避免模型在“决定检索”的同一步假定证据已经支持修复。
- `SKIP` 表示模型认为现有 grounded citation 已足够，不是一个执行许可；工具注册、可见性、tenant/project 范围、`allowed_actions`、参数 schema、风险、预算、审批和 Java control-plane handoff 仍逐项生效。
- 为兼容未输出新字段的旧 Provider，`AUTO` 在缺少 grounded citation 时归一为 `SEARCH`，在已有有效 grounded citation 时归一为 `SKIP`。
- RAG 证据只能补充恢复判断，不能替代 Autopilot 的幂等、作用域、循环/时间预算或风险决策。高风险动作仍由 Java 审批链路处理，Python 不因检索成功而直接执行恢复。

本轮检索能力只包括受治理 RAG、结构化控制面查询与 allowlist 的 repository 文本搜索；不把 Elasticsearch 或 Web Search 作为自动恢复的隐含依赖。这里的 repository workspace 只是 worker 注入的文件系统搜索根，受相对路径、隐藏/凭据路径、符号链接和预算限制，不是产品数据模型中的 Workspace 层级。

## 2026-08-15 补充：中文黄金集、批量 Embedding 与独立 Reranker

首轮新增 96 份纯合成中文 Markdown 和 168 条黄金用例；历史版本扩展到 188/308。2026-08-17 的职责
重整后基线为 356 份异构原文件和 752 条黄金用例，包含 120 份 DOCX、60 份 XLSX 以及 TXT、JSON、
JSONL、CSV、LOG、SQL。用户、管理员、部署、运维、安全、产品、测试、事故与接口资料分别使用专属
内容模型；综合接口参考从实际 Java Controller 和 FastAPI 路由生成 475 条合同。Manifest 保存每份原文件的 SHA-256、
提取文本 SHA-256、格式、MIME、来源 URI、证据状态和范围三元组；
黄金集保存期望文档、三级相关性、期望引用、禁止文档、拒答原因和租户范围。

运维和事故内容在职责分离基础上继续细化为用户、运维、开发三种视角。事故记录先说明用户在哪个功能、
什么操作下看到什么提示，再给出责任微服务、日志入口、执行日志 API、可复制错误行和七步定位过程，最后
分别记录通俗根因、技术根因、配置/代码修改、审批边界、回滚和 PRECHECK/MONITOR 验证。任务案例 JSONL、
worker LOG 与 Excel 失败明细使用同一关联键和诊断字段，便于 RAG 联合引用；用户手册、管理员手册和接口
参考仍保持各自职责，不混入事故流水。

运行时新增可重复评测器，统一计算 Recall@K、MRR、nDCG@K、引用精确率/召回率、拒答 F1、禁止文档
通过率、过期证据抑制率、范围泄漏率、单用例通过率和 p50/p95。单用例通过率也进入质量门禁，防止宏平均
达标掩盖一部分完整引用/拒答合同失败。禁止文档和范围泄漏会检查召回候选、Reranker 输入、
最终证据三个阶段；内部候选 ID 不进入普通 API 摘要。报告不保存问题、正文、模型输出、Endpoint 或密钥。
历史 188/308 基线的真实 BGE 全量运行没有执行错误且范围泄漏为零；Recall@K 为 `0.964976`，异构逐文件与自然问法召回率
均为 `1.0`，但跨格式多证据召回率只有 `0.6458`，引用精确率、拒答 F1、禁止文档通过率和单用例通过率
仍未达门禁，
不能被表述为当前语料的生产 RAG 验收。最终 356/752 词法基线指纹为 `50a11dec...`，149,609 个 chunk，
Recall@K `0.773174`、MRR `0.658708`、引用精确率 `0.427832`、范围泄漏率 `0`。详细事故语料提高了
Recall，但同类证据密度导致排序、引用和延迟退化；当前只有范围隔离与过期证据抑制通过严格门禁。

异构提取器使用标准库受限读取 `.md/.txt/.log/.sql/.csv/.tsv/.json/.jsonl/.docx/.xlsx`。DOCX/XLSX
只读取包内文本 XML，保留 Word 正文顺序、表格行、Excel 工作表名、单元格坐标和公式文本，不执行宏、
公式、外部关系或嵌入对象。文件大小、ZIP 条目、解压大小、行列数、单元格长度和总字符数都有硬上限。
`RagDocument.content` 使用提取文本，citation/sourceUri 始终保留原始办公文件，从而同时满足检索和来源审计。

Embedding Provider 现支持数组输入、受限批次、严格整数 index 和维度一致性校验；PostgreSQL/pgvector
摄取有单次文档/chunk 硬上限，远程向量生成不占用数据库锁，准备完成后再事务写入。完整治理范围参与
chunk 身份和替换/删除条件，跨租户同名 documentId 不会碰撞。Reranker 已作为独立协议接入管线，硅基流动适配器固定
`return_documents=false` 并严格验证完整 index 集。详细运行步骤见
[RAG 黄金集与硅基流动 BGE 评测 Runbook](rag-evaluation-siliconflow-runbook.md)。
确定性哈希 Embedding 只保留给学习、单元测试和 smoke；生产 pgvector 装配检测到该 Provider 时会
fail-closed，不能把可重复伪向量描述为语义召回。

## 2026-08-20 补充：Agent 自主检索路径与候选窗口保护

RAG 查询现在支持 `retrievalMode=auto`。这是面向真实 Agent 调用方的默认值，前端和 HTTP 调用方不需要在
部署时选择某一种 RAG。Runtime 会把用户问题交给受治理的检索路由模型，只要求模型返回严格 JSON，并在响应的
`retrievalSummary` 中记录低敏的 `decisionMode`、`decisionSource`、`decisionConfidence` 和公开原因：

- `hybrid`：普通文档、手册、日志、任务案例和运维资料，内部继续组合词法召回与向量召回；
- `graph`：组织关系、血缘、父子依赖等需要有限跳数关系遍历的问题；
- `hybrid_graph`：既需要关系链推理，又需要普通文档原文依据的问题。

模型只做路径选择，不直接读取文档、不执行图数据库写操作，也不能改变租户、应用、项目、敏感级别或
工具权限。模型调用失败、限流、预算阻断或返回非法 JSON 时使用规则式保守兜底；模型选择 GraphRAG 但当前实例
没有装配 GraphRAG Provider 时，执行前会收敛到 `hybrid` 并记录 `MODEL_CAPABILITY_FALLBACK`，不会把不可用图
能力伪装成成功。GraphRAG 发生关系冲突、别名歧义、来源不完整或超过最多三跳时保持拒答，不用普通文档相似度
猜一个关系答案。

本轮还修复了 BGE Reranker 前的候选丢失问题。长 DOCX、XLSX 和日志经常产生很多重复 chunk；目标资料虽然已经
被词法/向量召回，却可能在 16 条远端窗口前被同一份长文档挤掉。`knowledge_base.py` 与
`reranker_provider.py` 现在会在已通过范围和来源过滤的候选中，为职责分数达到阈值且有真实召回信号的资料各保留
一个文档代表，再进行多 facet 保留和文档轮询。该保护不重新搜索、不扩大权限、不替代 Reranker 或 evidence gate。
错误码目录、管理员手册和自治恢复 API 的自然语言职责提示也已补齐。

新增回归覆盖：模型选择联合模式、非法 JSON 兜底、GraphRAG 能力不可用时的约束、联合 `C*`/`G*` 引用、单职责
目标进入真实远端窗口。当前本机后端聚焦 RAG 回归为 `127 passed`；这证明代码合同已通过，但不等于真实模型质量
门禁已经通过。最近可用的 752 条全量 BGE 报告仍低于 Recall、nDCG、引用和单用例门禁，后续必须在 Secret 通过
环境变量注入后重新运行全量 `siliconflow` 评测。

PostgreSQL 连接使用 `search_path=ai_memory`，而 pgvector 扩展安装在 `public`。因此建表类型固定为
`public.vector`，距离表达式固定为 `OPERATOR(public.<=>)`；该写法已经过真实向量写入与查询验证，避免
依赖部署环境的隐式 search path。数据库向量分数会被外层混合检索直接复用；1024 维 `BAAI/bge-m3`
使用部分表达式 HNSW 索引，其他模型/维度仍走精确排序，必须另行建立匹配索引并做容量验收。合成语料摄取只允许本地、开发、测试或学习模式，生产和预发布环境
即使提供确认参数也会拒绝写入。

## 2026-08-18 补充：查询意图、拒答锚点与互补证据复核

本轮没有把黄金集中的 `documentId` 写进检索逻辑，而是在 `text.py` 维护可审计的“查询表达 -> 资料职责”
先验。它只使用 Manifest 中已经存在的 `category`、`sourceType`、`contentFormat`、标题和标签做次级排序，
最终仍必须经过范围过滤、词法/向量召回、Reranker 和 evidence gate。当前先验覆盖成功任务参数、运维流程、
字段映射、Worker/Kafka 日志、API 与 WebSocket、限流、Schema 漂移、Recovery、Checkpoint、失败分片 replay
和 RAG 评测等职责，目的是在“同一词出现在很多文档类型”时减少职责串线。

多证据查询现在分两段处理：

1. 第一段以有限 facet 做有界集合覆盖。每个 facet 查看所有已通过上游门禁的 chunk，同一 DOCX/XLSX 的多个
   chunk 可以共同证明该文档覆盖了多个主题，但最终只保留一个代表 chunk。如果某个 facet 存在
   `intentScore >= 0.85` 的明确 category 候选，则只允许达到同一职责门槛的资料覆盖该 facet；没有职责候选时
   才退回词法覆盖，避免通用案例凭整句复述吞掉接口、任务案例和恢复台账等独立职责。
2. 所有 facet 已有证据后，第二段才在 `topK` 上限内补充高意图、尚未出现的 `category`。候选不能重新绕过
   scope、Reranker 或 evidence gate；没有职责意图或 facet 信号的候选不会为了凑满 `topK` 被加入。没有
   `category` 的旧资料退化为文档自身去重键，不会把所有 `document` 或 `incident` 粗暴合并。

facet 职责评分还保留一个只用于消歧的整句上下文。facet 自身决定激活哪一种职责；整句仅在已激活的 Recovery
职责内部区分“Checkpoint/安全位点的 replay 案例”和“从接口标识追踪到最终验证的 Recovery 事件流水”。因此
上下文不会把整句其他主题重新灌入当前 facet，也不会绕过词法、范围、来源状态或 Reranker 门禁。

拒答保护也做了一个重要修正。中文 n-gram 先删除固定治理泛词，再提取未知实体锚点，避免“火星冷链调度规则的
当前阈值”被切出“则的”之类跨词碎片，从而误把通用文档当作答案。多个独立的两字业务词（例如“授权、决策、最小”）
现在可以共同构成 facet 强证据；单个泛化两字词仍不能单独放行。Checkpoint 职责先验仅由“Checkpoint 事故、
检查点事故、位点事故”这类明确事故表达触发，普通“CDC 检查点”不会自动补入无关 Recovery 资料。

本轮新增中文回归覆盖恢复手册/字段案例/Worker 日志、API 合同/Recovery 事件/状态快照、Kafka 任务案例/成功参数、
限流事故/API 案例/连接器清单、Checkpoint 事故/失败分片 replay/Recovery 决策、全量执行接口/任务案例/恢复台账，
以及“接口追踪到最终验证”“Checkpoint replay”“告警/连接器/可观测性职责”“部署/灾备总流程”语境。当前算法相关
管线测试为 `45 passed`；五个运行时 RAG/Embedding/Persistence 测试与评测资产合同合计 `103 passed`，
Python Runtime 全测试目录本轮为 `1263 passed, 1 skipped`。

已复跑的离线子集（数据集仍为当前 356 份文档；报告只保存低敏 ID、URI、分数和指标）：

| 子集 | 结果 | 说明 |
| --- | --- | --- |
| `multi_document`（12 条） | `12/12`，Recall/MRR=`1.0/1.0`，nDCG=`0.889327`，引用精确率/召回率=`1.0/1.0` | 互补证据与 URI 完整 |
| `cross-format-multi-global-*`（12 条） | `12/12`，Recall/MRR=`1.0/1.0`，nDCG=`0.876666`，引用精确率/召回率=`1.0/1.0` | 职责门禁与生命周期上下文通过 |
| 代表性跨格式用例（5 条） | `5/5`，Recall/MRR=`1.0/1.0`，nDCG=`0.866204`，引用精确率/召回率=`1.0/1.0` | DOCX/XLSX/JSONL/LOG/SQL 联合引用通过 |
| `no_answer`（12 条） | `12/12`，拒答 Precision/Recall/F1=`1.0/1.0/1.0` | 未知实体不再被通用 n-gram 放行 |
| `stale_conflict`（12 条） | `12/12`，过期抑制=`1.0` | 现行资料优先，引用无多余历史资料 |
| `history_lookup`（16 条） | `16/16`，Recall/MRR/nDCG/引用指标均为 `1.0` | 只在明确历史检索范围读取 |
| `cross_scope_refusal`（28 条） | `28/28`，范围泄漏=`0`，拒答 F1=`1.0` | 私有范围硬隔离仍成立 |
| `exact_error_code`（80 条） | `80/80`，Recall/MRR/nDCG/引用指标均为 `1.0` | 修复前 `78/80` 仅保留为历史对照 |

这些子集通过不等于当前 RAG 已完成生产验收。2026-08-18 已在同一当前数据集指纹上完成真实在线
Embedding + Reranker smoke：`siliconflow-bge-m3` 报告标记模型为 `BAAI/bge-m3` 和
`BAAI/bge-reranker-v2-m3`，20 条跨格式语义用例 `20/20`，Recall/MRR/nDCG、引用精确率/召回率和单用例
通过率均为 `1.0`，范围泄漏为 `0`，p50/p95 为 `3760/17363 ms`。因此“只做离线、尚未调用模型”已经不再是
当前事实；但该报告仍是 20 条 smoke，不代表 752 条全量、pgvector 大规模吞吐或生产稳定性。Embedding 缓存
保存在仓库外临时目录，密钥只在当前评测进程环境中存在。PostgreSQL/pgvector 全量、并发/吞吐、冷暖缓存、
429/5xx/超时故障注入和前后端真实 E2E 仍是剩余事项。
