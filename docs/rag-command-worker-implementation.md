# RAG Command Worker 闭环说明

本文记录 `knowledge.rag.query` 从普通 RAG API 推进到 Java command outbox 可消费 worker 的实现方式。

## 1. 为什么需要单独的 command worker

`POST /agent/rag/query` 面向产品查询和学习调试，可以返回 answer、citations、compressedContext 等字段，便于观察 RAG 的检索、压缩和引用绑定过程。

`POST /internal/agent/rag/command-worker/run` 面向 Java outbox dispatcher 或未来 Kafka consumer。它不能返回 question、answer、compressedContext、chunk text、sourceUri、prompt、SQL、endpoint、token 或 secret，只能返回低敏控制面事实。

这种拆分可以避免把“可展示查询接口”误当成“可持久化执行事实”。商业化系统里，Java 控制面、审计、Grafana、runtime event 和 worker receipt index 都可能长期保存或导出数据，因此必须只进入低敏 payload。

## 2. 当前代码入口

- `python-ai-runtime/src/datasmart_ai_runtime/services/rag/command_worker.py`
- `python-ai-runtime/src/datasmart_ai_runtime/services/rag/command_worker_receipt.py`
- `python-ai-runtime/src/datasmart_ai_runtime/api/agent/rag_command_worker.py`
- `python-ai-runtime/src/datasmart_ai_runtime/api/app.py`
- `python-ai-runtime/tests/test_rag_command_worker_api.py`

内部路由：

- `POST /internal/agent/rag/command-worker/run`
- `POST /api/internal/agent/rag/command-worker/run`

## 3. 执行流程

1. Java proposal/outbox 形成 durable command。
2. Java dispatcher 或未来 Kafka consumer 调用 Python RAG command worker。
3. Python worker 从 `controlFacts` 读取 `commandId/runId/sessionId/tenantId/projectId/actorId`。
4. Python worker 从短生命周期 `arguments.question` 读取 RAG 查询正文。
5. `RagCommandWorkerRunner` 调用 `RagPipeline.answer(...)` 执行检索、重排、证据门控、压缩、生成和引用绑定。
6. 如已装配 `LangGraphDurableCheckpointerService`，写入 `rag_retrieve_knowledge -> rag_evidence_gate -> rag_grounded_answer_completed/rag_no_evidence_completed` 低敏节点链路。
7. `RagCommandWorkerReceiptBuilder` 生成 Java `AgentToolActionCommandWorkerReceiptRequest` 可消费的低敏 payload。
8. 如果 `postToJava=true` 且环境显式启用 Java receipt client，则 Python worker 调用 Java receipt endpoint。
9. 响应只返回 `ragExecutionSummary`、`receipt`、`javaReceiptPayload`、`postResult` 和 `langGraphCheckpoint`。

## 4. 低敏边界

允许进入 worker summary / receipt / checkpoint 的内容：

- `commandId`
- `runId`
- `sessionId`
- `queryRef`
- `artifactReference`
- `candidateCount`
- `selectedChunkCount`
- `citationCount`
- `retrievalPolicyVersion`
- `tenantId/projectId/actorId`
- LangGraph checkpoint locator 与 Agent role/status 摘要

禁止进入 worker summary / receipt / checkpoint 的内容：

- 原始 question
- RAG answer 正文
- compressedContext
- document body
- chunk text
- sourceUri
- prompt/messages
- SQL
- endpoint 或 URL
- token、secret、credential
- 模型 Provider 原始响应

## 5. 配置

默认不会把 receipt 主动写回 Java，避免本地学习和单元测试误触发真实控制面写入。

```text
DATASMART_COMMAND_WORKER_RECEIPT_CLIENT_ENABLED=true
DATASMART_AGENT_RUNTIME_BASE_URL=http://agent-runtime:8091
DATASMART_COMMAND_WORKER_RECEIPT_BASE_URL=http://agent-runtime:8091
DATASMART_COMMAND_WORKER_RECEIPT_FAIL_CLOSED=true
```

配置含义：

- `DATASMART_COMMAND_WORKER_RECEIPT_CLIENT_ENABLED`：只有显式为 true 时，`postToJava=true` 才会触发真实 HTTP 写回。
- `DATASMART_AGENT_RUNTIME_BASE_URL`：Java agent-runtime 基础地址，未设置专用 receipt base URL 时复用它。
- `DATASMART_COMMAND_WORKER_RECEIPT_BASE_URL`：专门覆盖 worker receipt 写回地址。
- `DATASMART_COMMAND_WORKER_RECEIPT_FAIL_CLOSED`：为 true 时，写回失败交给外层队列重试或死信，不在 Python 侧吞掉失败。

## 6. 当前边界与下一步

当前已经完成：

- RAG helper 已升级为内部 worker route；
- RAG worker 可以执行真实 `RagPipeline.answer(...)`；
- RAG worker 可以生成 Java receipt payload；
- RAG worker 可以写入 LangGraph checkpoint summary；
- RAG worker route 已接入 FastAPI `create_app()`；
- 单元测试覆盖低敏响应、Java receipt payload、可选 Java post 和 checkpoint recovery。

尚未完成：

- 真实 Java outbox dispatcher 自动调用该 route；
- RAG answer body 写入 MinIO/受控 artifact store；
- Java artifact grant 二次授权读取 answer body；
- PostgreSQL LangGraph store 真实 smoke；
- gateway + Keycloak + agent-runtime + Python Runtime + task/data-sync/data-quality/observability 全平台 E2E。
