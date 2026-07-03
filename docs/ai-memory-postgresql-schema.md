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

### pgvector 运行时适配器

Python Runtime 已实现 `PgvectorAgentMemorySecondaryIndex`，同一个适配器同时承担两条链路：

- materializer 创建的 `VECTOR/UPSERT` 同步任务会回查正式记忆、生成 embedding，并按
  `memory_id + embedding_model + content_fingerprint` 幂等写入；
- Agent 检索语义记忆时，会先在 SQL 中过滤 tenant/project/session/workspace/namespace/type/scope/model/dimension/
  status/expiry，再执行余弦距离排序；命中后仍回查正式 store 并再次校验边界；
- 同模型正文变化会把旧 fingerprint 标记为 `stale`；删除或过期动作会清空向量并标记 `deleted`；
- 诊断只展示模型、维度、状态计数和相似度摘要，不返回记忆正文、查询文本或向量。

运行时默认关闭，避免本地环境因缺少 Embedding 服务而无法启动。主要配置如下：

```text
DATASMART_AI_MEMORY_PGVECTOR_ENABLED=true
DATASMART_AI_MEMORY_PGVECTOR_FAIL_OPEN=false
DATASMART_AI_MEMORY_PGVECTOR_POSTGRESQL_DSN=postgresql://...
DATASMART_AI_MEMORY_PGVECTOR_SCHEMA=ai_memory
DATASMART_AI_MEMORY_PGVECTOR_DOCUMENT_MAX_CHARS=4000
DATASMART_AI_MEMORY_PGVECTOR_MINIMUM_SIMILARITY=0.2

DATASMART_AI_MEMORY_EMBEDDING_PROVIDER=openai-compatible
DATASMART_AI_MEMORY_EMBEDDING_ENDPOINT=http://embedding-provider:8000/v1
DATASMART_AI_MEMORY_EMBEDDING_API_KEY=由 Secret Manager 注入
DATASMART_AI_MEMORY_EMBEDDING_MODEL=企业批准的 embedding 模型
DATASMART_AI_MEMORY_EMBEDDING_DIMENSIONS=1024
DATASMART_AI_MEMORY_EMBEDDING_TIMEOUT_SECONDS=30
```

`deterministic` Provider 只用于单元测试与 smoke；它能证明 pgvector 数据路径，却不能证明真实语义召回质量。
生产必须使用独立 Embedding 服务，并以业务语料验收 recall@k、MRR、P95/P99 延迟、吞吐、成本和租户公平性。

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

## 真实 store smoke

项目提供 `scripts/ai-memory-postgresql-store-smoke.py`，用于验证 Python Runtime 的真实 SQL store，而不是只验证
手写 SQL 或表结构。它覆盖以下运行时语义：

- 候选记忆首次写入、审批状态推进、乐观版本号和决策审计；
- 正式记忆首次写入、重复写入幂等、按候选反查和 tenant/project/namespace 范围检索；
- materialization receipt 的 `started -> succeeded` 状态流转；
- materialization lease 的领取、fencing token 条件完成和终态回读；
- materialization audit outbox 的低敏写入与最近记录回读。
- pgvector 向量首次写入、重复同步幂等、距离检索和 workspace 硬隔离。

脚本默认只做 schema 检查，必须显式增加 `--apply` 才会写入。所有测试 ID 都带 runId 前缀，默认在执行前后清理；
只有显式增加 `--keep-records` 才保留测试记录。测试数据只包含低敏摘要，不包含真实 prompt、SQL、样本数据、
工具原始输出、密钥或完整异常堆栈。

推荐从已安装 `postgresql` extra 的 Python Runtime 容器执行：

```powershell
docker compose -f docker-compose.yml -f docker-compose.application.yml run --rm --no-deps `
  -v "${PWD}:/workspace:ro" `
  python-ai-runtime python /workspace/scripts/ai-memory-postgresql-store-smoke.py --apply
```

如果 PostgreSQL 使用已有数据卷且缺少 `ai_memory` 表，应先显式执行
`docker/postgresql/init/10-ai-memory-schema.sql`，不能让 Runtime 在启动时静默建表或掩盖 schema 漂移。

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

1. 把 LangGraph checkpoint 从 Redis 低敏 checkpoint 扩展为 PostgreSQL durable checkpoint 可选实现。
2. 增加真实 Embedding Provider 集成环境与语义召回评测集，并按固定模型维度建立 HNSW/IVFFLAT 索引。
3. 在预生产配置中把 memory stores 和 pgvector 切换为 `postgresql + fail_open=false`，验证多实例、重启恢复与容量边界。
4. 通过全平台 smoke 后，清理 MySQL 初始化脚本中的 Agent Memory 表并停止新增 MySQL 专属耦合。
