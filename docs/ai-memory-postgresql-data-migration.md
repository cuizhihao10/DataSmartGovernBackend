# ai_memory MySQL 到 PostgreSQL/pgvector 存量迁移说明

本文档用于指导旧 MySQL 中混放的 `agent_memory_*` 历史表迁入 PostgreSQL `ai_memory` schema。当前脚本入口：

```powershell
python scripts\ai-memory-mysql-to-postgresql.py --mode plan
```

## 迁移边界

本批只迁移旧 MySQL 已存在并且语义稳定的 6 张 Agent Memory 历史表：

- `agent_memory_write_candidate`
- `agent_memory_write_candidate_audit`
- `agent_memory_store_entry`
- `agent_memory_materialization_receipt`
- `agent_memory_materialization_lease`
- `agent_memory_materialization_audit_outbox`

这些表保存长期记忆候选、候选审批审计、正式记忆低敏摘要、物化 receipt、worker lease 和物化审计 outbox。它们共同构成 AI Memory 的“历史事实层”，后续 pgvector、用户画像和 LangGraph durable state 都应围绕这些事实扩展。

以下表不从 MySQL 迁移：

- `agent_memory_embedding_index`：PostgreSQL/pgvector 新语义索引表，后续由 embedding adapter 根据正式记忆重建。
- `user_profile_fact`：用户画像事实表，后续由用户画像管道从会话、长期记忆和用户反馈中沉淀。
- `langgraph_thread_checkpoint`：LangGraph durable checkpoint 表，后续由 PostgreSQL checkpointer 写入。
- `langgraph_checkpoint_event`：LangGraph 节点、边、恢复和失败事件表，后续由图执行事件管道写入。

## 脚本能力

`scripts/ai-memory-mysql-to-postgresql.py` 支持五种模式：

- `plan`：只读检查 MySQL 源表、PostgreSQL 目标表、PostgreSQL-only 新能力表和延期/人工复核表。
- `export`：把 6 张历史表导出为 JSONL，并生成低敏 `manifest.json`。
- `import`：把 JSONL 通过 PostgreSQL `COPY FROM STDIN` 导入 `ai_memory` schema，必须显式传入 `--apply`。
- `verify`：按行数和稳定 SHA-256 checksum 对账。
- `all`：执行 `export -> import -> verify`，仍然必须显式传入 `--apply` 才能写 PostgreSQL。

脚本默认拒绝导入到非空目标表。这是有意设计的保护：长期记忆表包含候选 ID、正式记忆 ID、幂等键、receipt、lease token 和 outbox ID。如果目标表已有 seed/test data、失败残留或人工写入，继续 COPY 会产生混合事实，后续模型召回、补偿重试和审计解释都会不可信。

## 时间解释

旧 MySQL 使用 `DATETIME(3)`，没有时区信息；目标 PostgreSQL 使用 `TIMESTAMPTZ`。脚本默认按 UTC 解释旧 MySQL 墙钟时间：

```powershell
python scripts\ai-memory-mysql-to-postgresql.py --mode plan --mysql-datetime-timezone +00:00
```

如果某个客户环境历史上按北京时间写入 MySQL，则应显式使用：

```powershell
python scripts\ai-memory-mysql-to-postgresql.py --mode export --mysql-datetime-timezone +08:00
```

这个选择必须记录到 `manifest.json`、变更单和迁移验收报告里。脚本不应该偷偷使用本机时区，因为本机时区在开发机、CI、Linux 服务器和 Windows 服务器之间可能不同。

## 推荐操作流程

1. 停止 AI Memory 写入入口。

   停写范围包括候选生成、审批决策、物化 worker、正式记忆写入、lease 领取、审计 outbox append 和相关补偿接口。迁移窗口内允许只读查询，但不要继续产生新的 `agent_memory_*` 事实。

2. 备份 MySQL 与 PostgreSQL。

   至少保留 MySQL 逻辑备份、PostgreSQL 目标库快照、当前应用镜像版本、Compose 配置和迁移脚本版本。迁移失败时必须能回滚到明确状态。

3. 确认 PostgreSQL `ai_memory` schema 已创建。

   新环境由 `docker/postgresql/init/10-ai-memory-schema.sql` 创建。已有 PostgreSQL 数据卷不会自动重跑 init 脚本，需要手工执行该 SQL 或等待后续正式迁移管理工具。

4. 执行只读计划检查。

   ```powershell
   python scripts\ai-memory-mysql-to-postgresql.py --mode plan
   ```

   重点确认 6 张迁移表的源端和目标端行数，确认 `agent_memory_embedding_index`、`user_profile_fact`、`langgraph_thread_checkpoint`、`langgraph_checkpoint_event` 被识别为 PostgreSQL-only 新能力表。

5. 导出 JSONL。

   ```powershell
   python scripts\ai-memory-mysql-to-postgresql.py --mode export --export-dir artifacts\postgresql-migration\ai-memory\manual-20260703
   ```

   导出目录包含真实长期记忆低敏正文、审批原因、错误摘要、审计 payload 和内部 lease token。该目录不能提交 Git，不能上传普通工单或聊天工具，生产环境应放在加密磁盘、受控临时目录或企业指定安全工作区。

6. 导入 PostgreSQL。

   ```powershell
   python scripts\ai-memory-mysql-to-postgresql.py --mode import --apply --export-dir artifacts\postgresql-migration\ai-memory\manual-20260703
   ```

   导入使用 PostgreSQL `COPY`，会保留 MySQL 原始 `id`。导入完成后脚本会按最大 `id` 校正 identity sequence，避免应用恢复写入时生成重复主键。

7. 执行对账。

   ```powershell
   python scripts\ai-memory-mysql-to-postgresql.py --mode verify --export-dir artifacts\postgresql-migration\ai-memory\manual-20260703
   ```

   对账比较 manifest 中记录的源端行数与 SHA-256 checksum，以及当前 PostgreSQL 重新计算出的结果。任何一张表失败都应停止切换，保留现场并排查字段映射、时间解释、JSONB 规范化、目标表非空或脚本版本问题。

## 后续任务

本脚本完成的是 AI Memory 存量搬迁脚手架。后续仍需要：

- 增加 Python Runtime PostgreSQL store 的真实容器级 smoke，覆盖 candidate/formal/receipt/lease/audit outbox。
- 接入 pgvector adapter，把正式记忆低敏摘要写入 `agent_memory_embedding_index` 并支持 metadata filter。
- 接入 LangGraph PostgreSQL checkpointer，把 durable agent loop 从 Redis/内存迁到 `langgraph_thread_checkpoint` 与 `langgraph_checkpoint_event`。
- 全平台 smoke 通过后，再清理 MySQL 初始化脚本中混放的 `agent_memory_*`，并规划 MySQL 最终下线。
