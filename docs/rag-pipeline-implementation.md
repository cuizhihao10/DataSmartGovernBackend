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
- `python-ai-runtime/src/datasmart_ai_runtime/services/rag/pipeline.py`
- `python-ai-runtime/src/datasmart_ai_runtime/services/rag/components.py`
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

## 2. 当前管线阶段

一次 RAG 问答会经过以下阶段：

1. `query validation`：规范化租户、项目、workspace、topK、候选窗口和上下文预算，防止外部请求无限扩大召回或 prompt 长度。
2. `scope filter`：在任何排序前先做 `tenantId/projectId/workspaceKey` 过滤，避免跨租户文档先参与向量排序再过滤造成泄漏风险。
3. `chunking`：把文档切成 chunk，保留少量 overlap，让答案所需信息不容易被切在边界外。
4. `lexical score`：使用轻量 BM25 风格词项分，标题和 tag 命中权重大于正文命中。
5. `optional vector score`：如果配置了 embedding provider，则计算 query/chunk 的余弦相似度。
6. `RRF fusion`：用 Reciprocal Rank Fusion 融合词项召回和向量召回，避免两类分数尺度不同导致简单加权失真。
7. `MMR diversity`：用 Maximal Marginal Relevance 在相关性和多样性之间平衡，避免 topK 都来自同一文档重复段落。
8. `heuristic rerank`：使用可解释规则模拟 reranker，生产后可替换为专用 reranker 模型。
9. `evidence gate`：生成前执行证据强度门控，过滤只命中单个泛词或低质量近邻的弱证据。
10. `context compression`：按问题相关词压缩 chunk，优先保留命中句子，控制进入模型的上下文长度。
11. `model generation`：通过统一 `ModelQueryEngine` 调用治理问答模型，继承模型路由、限流、预算、fallback 和低敏错误处理。
12. `citation binding`：答案必须绑定 `[C1]`、`[C2]` 这类引用编号，方便审计和回溯。
13. `langgraph checkpoint`：把 RAG 的检索、证据门控和最终收口写入 durable checkpoint，支持后续暂停、恢复、分支和多 Agent 状态恢复。

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
- 单元测试覆盖召回、租户隔离、无证据拒绝和 API 合同。

尚未完成但已预留接口：

- PostgreSQL/pgvector 持久化知识库适配器。
- Neo4j GraphRAG，用于血缘、表关系、业务口径和资产图谱推理。
- 专用 embedding/reranker 模型，例如当前一代 Qwen embedding/reranker、BGE 或 Jina reranker。
- MinIO 文档解析、增量索引、删除重建和索引版本管理。
- RAG 与真实多 Agent runner 的自动 handoff，例如 DATA_QUALITY_AGENT 基于 RAG 证据生成规则草案。

## 6. 面试讲解要点

可以按下面思路讲：

- RAG 不是“向量数据库 + 大模型”这么简单，而是 `ingestion -> chunk -> retrieve -> rerank -> gate -> compress -> generate -> cite` 的完整链路。
- 多租户系统必须先过滤 scope 再排序，不能先全局向量搜索再过滤。
- lexical 适合精确术语、字段名、规则名；vector 适合语义相似；RRF 用于融合两种排序。
- MMR 解决 topK 冗余问题，避免召回结果全是同一文档相似段落。
- 证据门控解决“弱命中也生成”的问题，是降低幻觉和误导的关键。
- 引用绑定让答案可追溯，适合治理、权限、质量规则这类需要审计的场景。
- 当前实现把模型生成、embedding、reranker、知识库都隔离成可替换组件，后续可平滑接入 pgvector、GraphRAG 和专用模型。
