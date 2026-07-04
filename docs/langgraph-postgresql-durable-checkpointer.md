# LangGraph PostgreSQL Durable Checkpointer 说明

本文记录 DataSmart Python Runtime 中 LangGraph durable state 的当前闭环方向。该能力不继续发散 pgvector，而是把 Agent 执行现场收敛到 PostgreSQL `ai_memory` schema 下的可恢复状态机。

## 当前能力

- `LangGraphDurableCheckpoint`：保存 thread、checkpoint、父 checkpoint、当前节点、下一候选节点、恢复条件和低敏状态摘要。
- `LangGraphCheckpointEvent`：记录 checkpoint 保存、暂停、恢复、分支、循环等低敏事件。
- `LangGraphDurableCheckpointerService`：提供 `record_checkpoint`、`pause`、`resume`、`fork_branch`、`record_loop_iteration`、`recover_multi_agent_state`。
- `InMemoryLangGraphCheckpointStore`：用于本地学习和单元测试。
- `PostgresLangGraphCheckpointStore`：对齐 `langgraph_thread_checkpoint` 与 `langgraph_checkpoint_event` 两张表。
- `/agent/langgraph/checkpoints/diagnostics`：只读诊断入口，展示 store 类型、持久化状态和 pause/resume/branch/loop/multi-agent recovery 能力，不读取 checkpoint 正文。
- `mcp_worker_langgraph.py`：把 MCP durable worker 的安全 `modelFeedback -> modelSecondTurn` 迁入 LangGraph 节点链路，形成 `mcp_model_feedback -> mcp_model_second_turn -> mcp_model_second_turn_completed` 的可观测状态机。
- `services/rag/langgraph_checkpoint.py`：把 RAG 管线迁入 LangGraph 低敏节点链路，形成 `rag_retrieve_knowledge -> rag_evidence_gate -> rag_grounded_answer_completed/rag_no_evidence_completed`。
- `/agent/langgraph/checkpoints/latest`：按 `threadId` 查询最新 checkpoint 摘要。
- `/agent/langgraph/checkpoints/events`：按 `threadId` 查询低敏事件流。
- `/agent/langgraph/checkpoints/pause`：暂停 thread，用于人工审批、容量保护或运维冻结。
- `/agent/langgraph/checkpoints/resume`：携带低敏 `resumeFacts` 恢复 thread。
- `/agent/langgraph/checkpoints/fork`：从指定 checkpoint 派生分支 thread，用于重试、修复或人工确认路径。
- `/agent/langgraph/checkpoints/recover/multi-agent`：恢复最新 checkpoint 中的多 Agent 角色状态摘要。

## 设计原则

- 节点是可替换工作单元：例如 `retrieve_memory`、`mcp_model_feedback_second_turn`、`permission_gate`、`multi_agent_handoff`。
- 边是状态机控制关系：用于分支、循环、暂停、人审、工具等待和失败恢复。
- 状态是可恢复现场：只保存恢复所需的低敏指针、计数、状态码和摘要，禁止保存 prompt、工具参数、SQL、模型输出、凭据或大对象正文。
- PostgreSQL 是 durable state 目标源：生产环境应配置 `DATASMART_LANGGRAPH_CHECKPOINT_STORE=postgresql`，并使用 `DATASMART_LANGGRAPH_CHECKPOINT_POSTGRESQL_DSN` 或全局 `DATASMART_AI_MEMORY_POSTGRESQL_DSN`。
- MCP 二轮模型节点的 checkpoint 只保存工具名、toolCallId、状态码、Provider/模型名、错误码、多 Agent 状态和策略 key，不保存 MCP arguments、tool result 正文、二轮 messages 或 Java lease token。
- RAG 节点的 checkpoint 只保存召回数量、弱证据拒绝数量、引用数量、模型错误码、多 Agent 状态和安全策略 key，不保存用户问题、答案、compressedContext、文档正文、sourceUri 或模型原始响应。

## 配置

```text
DATASMART_LANGGRAPH_CHECKPOINT_STORE=postgresql
DATASMART_LANGGRAPH_CHECKPOINT_POSTGRESQL_DSN=postgresql://datasmart:***@postgresql:5432/datasmart_govern?options=-csearch_path%3Dai_memory
DATASMART_LANGGRAPH_CHECKPOINT_CONNECT_TIMEOUT_SECONDS=3
DATASMART_LANGGRAPH_CHECKPOINT_FAIL_OPEN=false
```

本地默认是 `in-memory`，保证离线单测和学习环境零依赖。准生产和生产建议 `postgresql + fail_open=false`，避免 Agent 暂停/恢复现场在进程重启后丢失。

## 下一步迁入顺序

1. 已完成：MCP 安全 `modelFeedback -> second_turn_model` 调用已包装为 LangGraph 节点，并在节点前后写 checkpoint/event。
2. 已完成：RAG 检索、证据门控和生成/fail-closed 收口已包装为 LangGraph 节点，并写入同一个 checkpointer service。
3. 把长期记忆 `retrieve_memory` 从内部编排步骤迁入可观测图节点。
4. 把多 Agent turn runner 的 MASTER_ORCHESTRATOR、DATA_QUALITY_AGENT、DATASOURCE_AGENT、PERMISSION_AGENT 等角色状态写入 checkpoint，并让 runner 直接使用 pause/resume/fork/recover 控制面。
5. 将现有 `DurableAgentLoopService` 的 Redis/内存 checkpoint 逐步迁到 LangGraph checkpointer，保留兼容查询期。
6. 完成 PostgreSQL store smoke、MCP 大结果 MinIO artifact writer 与全平台 E2E，进入项目最终闭环验收。
