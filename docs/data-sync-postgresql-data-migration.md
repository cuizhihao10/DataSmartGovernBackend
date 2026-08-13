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

## 2026-08-12 勘误：Autopilot V20-V25 是 PostgreSQL 控制面 schema，不是历史数据导入批次

当前源码包含六个连续的 Autopilot PostgreSQL 增量：V20 保存 recovery case、receipt 与任务授权快照；V21 保存 Kafka trigger outbox、退避、认领和死信状态；V22 保存消费者结果摘要及 case 关联；V23 保存触发/成功收敛 sidecar 事务失败后的有界本地补偿日志；V24 保存 digest-bound quarantine 的幂等 durable receipt；V25 将模型自主的 `SEARCH`/`SKIP`、检索策略、证据计数和 evidence-ID SHA-256 digest 投影到 trigger outbox。六个版本只保存 tenant/project/task/execution 定位、状态、次数、截止时间、低敏原因码、SHA-256 摘要和必要的 selector/计数事实，不保存 prompt、SQL、凭据、日志正文、模型输出、RAG 正文/citation 正文或业务记录正文。

V20-V25 不加入本批 MySQL 导出、JSONL 或 `COPY` 对账对象：当前没有对应的历史 MySQL 事实需要搬迁，现有 10 张 `data_sync_*` 表的存量迁移范围不变。部署时必须按 V20 -> V21 -> V22 -> V23 -> V24 -> V25 由目标 PostgreSQL Flyway 执行并保留版本、校验和与结果证据，不应手工建表或把这些表混入历史导入脚本。

这些 schema 是受治理自动恢复的持久化基础；实际源码还包含受控 Kafka 触发、Python `SEARCH`/`SKIP` 规划、Java 证据/策略复核、失败对象重试、preview 绑定 quarantine、worker 和最终 receipt。普通同步规划只把 RAG 暴露为模型可选工具，不能由规则在模型跳过后补写检索 ToolPlan；Autopilot V25 仅记录已经通过 Java 合同校验的低敏检索投影。部署验收仍必须证明六个迁移已应用，并分别覆盖重复投递、sidecar 补偿、死信、自动低风险 retry/quarantine、高风险审批停点，以及恢复写动作后的 PRECHECK/MONITOR durable 复核；最后一项当前仍待主线接线和真实 E2E 验证。不能用表存在或单条 `AUTO_APPROVED` 代替运行证据。

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
