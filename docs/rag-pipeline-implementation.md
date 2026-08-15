# DataSmart RAG 管线实现说明

本文记录 Python Runtime 当前 RAG（Retrieval-Augmented Generation，检索增强生成）能力的实现原理、代码边界和后续演进方向。它不是简单地“调用某个框架的 retriever API”，而是把 RAG 的核心阶段拆成可解释、可测试、可替换的工作单元，方便后续接入 LangGraph、多 Agent、pgvector、Neo4j GraphRAG 或企业搜索服务。

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

1. `query validation`：规范化租户、项目、workspace、topK、候选窗口和上下文预算，防止外部请求无限扩大召回或 prompt 长度。
2. `scope filter`：在任何排序前先做 `tenantId/projectId/workspaceKey` 过滤，避免跨租户文档先参与向量排序再过滤造成泄漏风险。
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
- 96 份合成中文文档、168 条黄金用例、资产哈希校验、低敏评测报告和质量门禁。

尚未完成但已预留接口：

- Neo4j GraphRAG，用于血缘、表关系、业务口径和资产图谱推理。
- MinIO 文档解析、增量索引、删除重建和索引版本管理。
- 已完成一次 168 条用例的真实 BGE 全量评测和本机 pgvector 摄取/查询 smoke；质量门禁仍未通过，完整
  pgvector 基准、并发、限流和故障注入仍待执行。
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

本轮新增 96 份纯合成中文知识文档和 168 条黄金用例，覆盖精确错误码、语义改写、多文档、无答案、
跨范围拒答和过期证据冲突。Manifest 保存每份 Markdown 的 SHA-256、来源 URI、证据状态和范围三元组；
黄金集保存期望文档、三级相关性、期望引用、禁止文档、拒答原因和租户范围。

运行时新增可重复评测器，统一计算 Recall@K、MRR、nDCG@K、引用精确率/召回率、拒答 F1、禁止文档
通过率、过期证据抑制率、范围泄漏率、单用例通过率和 p50/p95。单用例通过率也进入质量门禁，防止宏平均
达标掩盖一部分完整引用/拒答合同失败。禁止文档和范围泄漏会检查召回候选、Reranker 输入、
最终证据三个阶段；内部候选 ID 不进入普通 API 摘要。报告不保存问题、正文、模型输出、Endpoint 或密钥。
真实 BGE 全量运行没有执行错误且范围泄漏为零，但引用精确率、拒答 F1、禁止文档通过率和单用例通过率仍未达门禁，
不能被表述为生产 RAG 验收。

Embedding Provider 现支持数组输入、受限批次、严格整数 index 和维度一致性校验；PostgreSQL/pgvector
摄取有单次文档/chunk 硬上限，远程向量生成不占用数据库锁，准备完成后再事务写入。完整治理范围参与
chunk 身份和替换/删除条件，跨租户同名 documentId 不会碰撞。Reranker 已作为独立协议接入管线，硅基流动适配器固定
`return_documents=false` 并严格验证完整 index 集。详细运行步骤见
[RAG 黄金集与硅基流动 BGE 评测 Runbook](rag-evaluation-siliconflow-runbook.md)。
确定性哈希 Embedding 只保留给学习、单元测试和 smoke；生产 pgvector 装配检测到该 Provider 时会
fail-closed，不能把可重复伪向量描述为语义召回。

PostgreSQL 连接使用 `search_path=ai_memory`，而 pgvector 扩展安装在 `public`。因此建表类型固定为
`public.vector`，距离表达式固定为 `OPERATOR(public.<=>)`；该写法已经过真实向量写入与查询验证，避免
依赖部署环境的隐式 search path。数据库向量分数会被外层混合检索直接复用；1024 维 `BAAI/bge-m3`
使用部分表达式 HNSW 索引，其他模型/维度仍走精确排序，必须另行建立匹配索引并做容量验收。合成语料摄取只允许本地、开发、测试或学习模式，生产和预发布环境
即使提供确认参数也会拒绝写入。
