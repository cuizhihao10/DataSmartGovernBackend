# data-sync MySQL 到 PostgreSQL 存量数据迁移说明

本文档用于指导 `data-sync` 微服务在已经完成 PostgreSQL 代码路径切换后，把历史 MySQL 业务数据搬迁到 PostgreSQL `data_sync` schema。

当前脚本入口为：

```powershell
python scripts\data-sync-mysql-to-postgresql.py --mode plan
```

## 迁移边界

本批只迁移 `data-sync` 微服务自有的 10 张控制面事实表：

- `data_sync_template`
- `data_sync_task`
- `data_sync_execution`
- `data_sync_callback_idempotency`
- `data_sync_task_management_receipt_outbox`
- `data_sync_checkpoint`
- `data_sync_execution_recovery_plan`
- `data_sync_error_sample`
- `data_sync_incident_record`
- `data_sync_audit_record`

`task_data_sync_*` 不属于本批迁移对象。虽然表名包含 `data_sync`，但它们的实体、Mapper、Service、Controller 和接口路由都在 `task-management`，表达的是 task-management 向 data-sync worker 下发命令、接收执行回执投影的任务平台侧事实。因此它们后续应随 `task-management` 迁入 PostgreSQL `task_management` schema。

`agent_memory_*` 也不属于本批迁移对象。它们属于 Agent Runtime / AI Memory，后续应迁入 PostgreSQL `ai_memory` schema，并与 pgvector、用户画像、长期记忆和 LangGraph durable state 一起验收。

## 迁移脚本能力

`scripts/data-sync-mysql-to-postgresql.py` 支持五种模式：

- `plan`：只读检查 MySQL 源表、PostgreSQL 目标表、延期迁移表和额外待复核表，不写文件、不写数据库。
- `export`：把 10 张 `data_sync_*` 表导出为 JSONL，并生成低敏 `manifest.json`。
- `import`：把 JSONL 通过 PostgreSQL `COPY FROM STDIN` 导入 `data_sync` schema，必须显式传入 `--apply`。
- `verify`：按行数和稳定 SHA-256 checksum 对账。
- `all`：执行 `export -> import -> verify`，仍然必须显式传入 `--apply` 才能写 PostgreSQL。

脚本默认拒绝导入到非空目标表。这个保护是有意的：如果 PostgreSQL 已经存在 seed/test data 或上次失败残留，继续导入会造成主键冲突、执行号重复、审计链路断裂，甚至让 checksum 失败变得不可解释。只有在明确知道目标表已有数据来源且已完成人工审批时，才允许使用 `--allow-target-not-empty`。

## 推荐操作流程

1. 停止 data-sync 写入入口。

   包括人工运行、定时调度、worker 领取、恢复计划消费、receipt outbox 投递和任何会写入 `data_sync_*` 的后台任务。迁移窗口内允许只读查询，但不允许产生新的执行、checkpoint、错误样本或审计记录。

2. 备份 MySQL 与 PostgreSQL。

   迁移前至少保留 MySQL 逻辑备份、PostgreSQL 目标库备份或快照，以及当前应用版本号。这样在验证失败时可以回滚到明确状态。

3. 确认 PostgreSQL schema 已由 Flyway 创建。

   `data-sync` 的 PostgreSQL V1 位于 `data-sync/src/main/resources/db/migration/postgresql/data-sync/V1__data_sync_schema_baseline.sql`。脚本不会替你创建业务表，它只负责搬迁和对账。

4. 执行只读计划检查。

   ```powershell
   python scripts\data-sync-mysql-to-postgresql.py --mode plan
   ```

   重点检查 10 张源表和目标表行数，确认 `task_data_sync_*` 被标记为 `DEFERRED targetSchema=task_management`，确认 `agent_memory_*` 被标记为 `DEFERRED targetSchema=ai_memory`。

5. 导出 JSONL。

   ```powershell
   python scripts\data-sync-mysql-to-postgresql.py --mode export --export-dir artifacts\postgresql-migration\data-sync\manual-20260703
   ```

   导出目录包含真实业务迁移数据，可能含同步配置、checkpoint、错误样本、事故描述、审计摘要和低敏 outbox payload。该目录不能提交 Git，不能上传普通工单或聊天工具，生产环境应放在加密磁盘、受控临时目录或企业指定安全工作区。

6. 导入 PostgreSQL。

   ```powershell
   python scripts\data-sync-mysql-to-postgresql.py --mode import --apply --export-dir artifacts\postgresql-migration\data-sync\manual-20260703
   ```

   导入使用 PostgreSQL `COPY`，会保留 MySQL 原始 `id`，导入完成后按最大 `id` 校正 identity sequence，避免应用恢复写入后生成重复主键。

7. 执行对账。

   ```powershell
   python scripts\data-sync-mysql-to-postgresql.py --mode verify --export-dir artifacts\postgresql-migration\data-sync\manual-20260703
   ```

   对账比较 manifest 中记录的源端行数和 SHA-256 checksum 与当前 PostgreSQL 重新计算的结果。任何一张表失败都应停止切换，保留现场并排查字段映射、时间精度、JSON 文本或目标表非空问题。

8. 只读观察和恢复写入。

   对账通过后，先让 data-sync 以 PostgreSQL 配置启动并执行只读健康检查、列表查询、执行历史查询、checkpoint 查询、错误样本查询和 outbox 查询。确认无误后再逐步恢复 worker、调度和写入入口。

## 重要字段转换

- `TINYINT(1)` 转为 PostgreSQL `BOOLEAN`，脚本导出时统一使用 `true/false` 文本。
- `DATETIME` 转为 PostgreSQL `TIMESTAMP WITHOUT TIME ZONE`，脚本按微秒级文本对账，不做隐式时区转换。
- MySQL `JSON` 类型的 `payload_json` 导出时会 `CAST AS CHAR`，因为 PostgreSQL V1 暂按 `TEXT` 保存，保持 Java `String` 映射稳定。
- JSON 配置、checkpoint、错误样本、事故说明和审计 payload 都会迁移和参与 checksum，但不会在终端日志或 manifest 中输出样本值。

## 回滚原则

如果导入或对账失败：

- 不要直接手工修 PostgreSQL 目标表后继续导入，除非已经记录问题原因和修复 SQL。
- 优先清空或恢复 PostgreSQL `data_sync` schema 到迁移前快照，然后重新执行 `import/verify`。
- MySQL 在迁移验收完成前保持只读保留，不要立即删除或覆盖。
- 如果应用已经短暂连接 PostgreSQL 并产生新写入，需要先判断这些写入是否应丢弃、回放到 MySQL，还是作为新事实重新迁移。

## 后续任务

本脚本完成的是 data-sync 自有表的存量搬迁闭环。后续仍需要：

- 迁移 `task-management`，并在该批次处理 `task_data_sync_worker_command_outbox` 与 `task_data_sync_worker_execution_receipt`。
- 迁移 `agent-runtime/ai_memory`，集中处理 `agent_memory_*`、pgvector 记忆索引和 LangGraph durable state。
- 重建 data-sync 容器，执行真实容器级 PostgreSQL smoke，确认 Compose 合成后的 data-sync 不再连接 MySQL 平台库。
