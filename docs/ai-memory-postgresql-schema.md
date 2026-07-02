# AI Memory PostgreSQL / pgvector Schema 说明

本文说明 `ai_memory` schema 的 V1 目标结构、业务边界和运行方式。它对应
`docker/postgresql/init/10-ai-memory-schema.sql`，用于把旧 MySQL 中混放的 `agent_memory_*`
迁移到 PostgreSQL/pgvector 目标架构。

## 设计边界

`ai_memory` 不属于 `agent_runtime`。两者的职责必须拆开：

- `agent_runtime` 保存工具执行审计、outbox、receipt、恢复定位符、artifact grant 等控制面事实。
- `ai_memory` 保存长期记忆候选、正式记忆低敏摘要、用户画像、pgvector 语义索引和 LangGraph durable checkpoint。
- `task_management` 继续保存任务、执行、Agent command inbox 和 data-sync worker 任务桥接事实。

这个拆分的原因是生命周期不同：控制面事实通常服务于审计、恢复和幂等；长期记忆和画像会进入模型上下文，
必须有更严格的低敏、过期、召回过滤、向量重建和遗忘治理。

## V1 表清单

- `agent_memory_write_candidate`：长期记忆写入候选，表示某次 Agent 工具结果是否允许沉淀为记忆。
- `agent_memory_write_candidate_audit`：候选审批/拒绝/重开等决策审计。
- `agent_memory_store_entry`：正式长期记忆低敏摘要，是模型召回的结构化事实源。
- `agent_memory_embedding_index`：pgvector 语义索引表，围绕 `memory_id` 保存向量、模型版本和 metadata filter。
- `agent_memory_materialization_receipt`：候选物化执行证据，回答“approved 候选是否真正落成正式记忆”。
- `agent_memory_materialization_lease`：多 worker 物化租约，防止重复处理、重复写库和重复向量化。
- `agent_memory_materialization_audit_outbox`：物化批次和管理员补偿动作的低敏审计 outbox。
- `user_profile_fact`：用户画像事实，保存偏好、工作方式、常用上下文等低敏、可过期事实。
- `langgraph_thread_checkpoint`：LangGraph durable checkpoint，让 Agent 图可恢复、可暂停、可分支、可循环。
- `langgraph_checkpoint_event`：LangGraph 节点/边/恢复事件，用于审计回放和可观测图执行。

## pgvector 策略

`agent_memory_embedding_index.embedding` 使用未固定维度的 `vector` 类型，而不是直接写死 `vector(1024)` 或
`vector(1536)`。这是为了保留模型替换能力：Qwen、BGE、Jina、DeepSeek 或企业内置 embedding provider 的维度
可能不同。

生产优化建议：

- 先用 `tenant_id/project_id/memory_namespace/memory_type/scope/index_status` 做 metadata filter。
- 再按当前主力 embedding 模型建立维度专用 HNSW/IVFFLAT 索引或分区。
- 模型升级时允许同一 `memory_id` 并存多个 `embedding_model + content_fingerprint` 版本。
- 不要把模型名称写死进业务合同，应该通过 provider/router 配置决定。

## Python Runtime 接入

本阶段已经让 Python 记忆组件识别 `postgresql` store 类型，并新增全局 DSN：

```text
DATASMART_AI_MEMORY_POSTGRESQL_DSN=postgresql://datasmart:***@postgresql:5432/datasmart_govern?options=-csearch_path%3Dai_memory
```

各组件可以用专属 DSN 覆盖：

- `DATASMART_AI_MEMORY_WRITE_POSTGRESQL_DSN`
- `DATASMART_AI_FORMAL_MEMORY_POSTGRESQL_DSN`
- `DATASMART_AI_MEMORY_RECEIPT_POSTGRESQL_DSN`
- `DATASMART_AI_MEMORY_LEASE_POSTGRESQL_DSN`
- `DATASMART_AI_MEMORY_MATERIALIZATION_AUDIT_OUTBOX_POSTGRESQL_DSN`

当前 Compose 只注入全局 DSN，不默认强制把 store 切到 PostgreSQL。这样做是为了避免已有本地环境在 schema
尚未手工应用到旧数据卷时启动失败。后续完成存量迁移脚手架和 smoke 后，可以逐步把生产/预生产默认改为
`postgresql + fail_open=false`。

## 安全约束

这些表都只能保存低敏摘要和治理元数据。禁止写入：

- 完整 prompt；
- 原始 SQL；
- 客户样本数据；
- 完整工具输出；
- API key、数据库密码、HMAC secret、lease token；
- 完整异常堆栈；
- 未经审批的敏感字段正文。

模型召回必须同时使用 `tenant_id/project_id/session_id/workspace_key/memory_namespace/scope/expires_at`
过滤，不允许先全局召回后交给模型自行判断权限。

## 下一步

1. 为 `agent_memory_*` 补 MySQL 到 PostgreSQL `ai_memory` 的存量迁移脚手架。
2. 为 Python Runtime 增加真实 PostgreSQL store smoke，覆盖候选、正式记忆、receipt、lease、audit outbox。
3. 增加 pgvector embedding 写入/检索 adapter，使 Chroma 成为兼容路径而不是目标路径。
4. 把 LangGraph checkpoint 从 Redis 低敏 checkpoint 扩展为 PostgreSQL durable checkpoint 可选实现。
5. 通过全平台 smoke 后，再清理 MySQL 初始化脚本中的 Agent Memory 表。
