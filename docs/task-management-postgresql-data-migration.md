# task-management MySQL 到 PostgreSQL 存量数据迁移说明

本文档用于指导 `task-management` 微服务在已经完成 PostgreSQL 代码路径切换后，把历史 MySQL 任务数据搬迁到 PostgreSQL `task_management` schema。

当前脚本入口为：

```powershell
python scripts\task-management-mysql-to-postgresql.py --mode plan
```

## 迁移边界

本批只迁移 `task-management` 当前 Java 服务和 PostgreSQL V1 真实使用的 8 张任务域表：

- `task`
- `task_draft`
- `task_execution_log`
- `task_execution_run`
- `task_callback_idempotency`
- `agent_async_task_command_inbox`
- `task_data_sync_worker_command_outbox`
- `task_data_sync_worker_execution_receipt`

`task_data_sync_worker_command_outbox` 与 `task_data_sync_worker_execution_receipt` 必须随 `task-management` 迁移。虽然它们的表名包含 `data_sync`，但它们不是 data-sync 微服务自有事实，而是任务中心保存的“向 data-sync worker 下发命令”和“接收 worker 执行回执投影”的任务平台侧证据。把它们放进 `data_sync` schema 会导致跨 schema JOIN、重复迁移、回滚责任不清和任务时间线断裂。

`agent_async_task_command_outbox`、`agent_run_tool_dag_confirmation` 不属于本批迁移对象。它们属于 Agent Runtime 控制面，应在后续 `agent_runtime` schema 批次迁移。

`agent_memory_*` 也不属于本批迁移对象。它们属于 AI Memory / 长期记忆 / 用户画像 / pgvector 批次，后续应迁入 `ai_memory` schema，并与 LangGraph durable state 一起验收。

`data_sync_*`、`sync_*`、`datasource_*`、`quality_*` 分别属于其他微服务。脚本会在 `plan/export/verify` 中登记为 `DEFERRED`，但不会导出、导入或参与 task-management checksum。

## 脚本能力

`scripts/task-management-mysql-to-postgresql.py` 支持五种模式：

- `plan`：只读检查 MySQL 源表、PostgreSQL 目标表、延期迁移表和额外待复核 task 表，不写文件、不写数据库。
- `export`：把 8 张任务域表导出为 JSONL，并生成低敏 `manifest.json`。
- `import`：把 JSONL 通过 PostgreSQL `COPY FROM STDIN` 导入 `task_management` schema，必须显式传入 `--apply`。
- `verify`：按行数和稳定 SHA-256 checksum 对账。
- `all`：执行 `export -> import -> verify`，仍然必须显式传入 `--apply` 才能写 PostgreSQL。

脚本默认拒绝导入到非空目标表。这个保护是有意的：任务中心存在任务主键、创建幂等键、执行 run 编号、Agent command 幂等键、worker outbox 幂等键等强约束。如果 PostgreSQL 已存在 seed/test data、失败残留或人工写入，继续导入会造成冲突或混合事实，后续审计很难解释。

## 推荐操作流程

1. 停止 task-management 写入入口。

   包括 API 创建任务、草稿审批转换、调度器认领、执行器回调、Agent async task inbox、data-sync worker outbox 投递和 execution receipt 回写。迁移窗口内允许只读查询，但不允许产生新的任务、run、日志、幂等记录或 outbox/receipt。

2. 备份 MySQL 与 PostgreSQL。

   至少保留 MySQL 逻辑备份、PostgreSQL 目标库快照、当前应用镜像版本和 Compose 配置。迁移失败时必须能回滚到明确状态。

3. 确认 PostgreSQL schema 已由 Flyway 创建。

   `task-management` 的 PostgreSQL V1 位于 `task-management/src/main/resources/db/migration/postgresql/task-management/V1__task_management_schema_baseline.sql`。迁移脚本不会替你创建表，它只负责搬迁、对账和 sequence 校正。

4. 执行只读计划检查。

   ```powershell
   python scripts\task-management-mysql-to-postgresql.py --mode plan
   ```

   重点确认 8 张源表和目标表行数，确认 `agent_async_task_command_outbox`、`agent_run_tool_dag_confirmation` 被标记为 `DEFERRED targetSchema=agent_runtime`，确认 `agent_memory_*` 被标记为 `DEFERRED targetSchema=ai_memory`。如果出现额外 `task_*` 表，脚本会标记为 `REVIEW_REQUIRED`，需要人工确认是否属于新的 task-management V2 表。

5. 导出 JSONL。

   ```powershell
   python scripts\task-management-mysql-to-postgresql.py --mode export --export-dir artifacts\postgresql-migration\task-management\manual-20260703
   ```

   导出目录包含真实任务迁移数据，可能含任务参数、checkpoint、执行摘要、Agent 命令引用、worker payload 和低敏错误摘要。该目录不能提交 Git，不能上传普通工单或聊天工具，生产环境应放在加密磁盘、受控临时目录或企业指定安全工作区。

6. 导入 PostgreSQL。

   ```powershell
   python scripts\task-management-mysql-to-postgresql.py --mode import --apply --export-dir artifacts\postgresql-migration\task-management\manual-20260703
   ```

   导入使用 PostgreSQL `COPY`，会保留 MySQL 原始 `id`，导入完成后按最大 `id` 校正 identity sequence，避免应用恢复写入后生成重复主键。

7. 执行对账。

   ```powershell
   python scripts\task-management-mysql-to-postgresql.py --mode verify --export-dir artifacts\postgresql-migration\task-management\manual-20260703
   ```

   对账比较 manifest 中记录的源端行数和 SHA-256 checksum 与当前 PostgreSQL 重新计算的结果。任何一张表失败都应停止切换，保留现场并排查字段映射、时间精度、JSON 文本、目标表非空或脚本版本问题。

8. 只读观察和恢复写入。

   对账通过后，先让 task-management 以 PostgreSQL 配置启动并执行只读健康检查、任务列表、任务详情、草稿列表、执行 run、执行日志、Agent inbox、data-sync worker outbox 和 receipt 查询。确认无误后再逐步恢复调度器、执行器、Agent command 消费和 worker receipt 回写。

## 重要字段转换

- `TINYINT(1)` 转为 PostgreSQL `BOOLEAN`，脚本导出时统一使用 `true/false` 文本。
- `DATETIME` / `DATETIME(3)` 转为 PostgreSQL `TIMESTAMP WITHOUT TIME ZONE`，脚本按微秒级文本对账，不做隐式时区转换。
- MySQL `JSON` 类型的 `argument_names`、`sensitive_argument_names`、`payload_json` 导出时会 `CAST AS CHAR`，因为 PostgreSQL V1 暂按 `TEXT` 保存，保持 Java `String` 映射稳定。
- `params`、`checkpoint`、`details`、`payload_json`、`error_summary` 等字段会迁移和参与 checksum，但不会在终端日志或 manifest 中输出样本值。

## 回滚原则

如果导入或对账失败：

- 不要直接手工修 PostgreSQL 目标表后继续导入，除非已经记录问题原因、修复 SQL 和审批结论。
- 优先清空或恢复 PostgreSQL `task_management` schema 到迁移前快照，然后重新执行 `import/verify`。
- MySQL 在迁移验收完成前保持只读保留，不要立即删除或覆盖。
- 如果应用已经短暂连接 PostgreSQL 并产生新写入，需要先判断这些写入是否应丢弃、回放到 MySQL，还是作为新事实重新迁移。

## 后续任务

本脚本完成的是 task-management 自有表的存量搬迁闭环。后续仍需要：

- 重建 `task-management` 容器，执行真实容器级 PostgreSQL smoke，确认运行中的服务使用 `task_management` schema。
- 进入 `agent-runtime/ai_memory` 批次，集中处理 `agent_async_task_command_outbox`、`agent_run_tool_dag_confirmation`、`agent_memory_*`、pgvector 记忆索引和 LangGraph durable state。
- 在所有 Java 业务服务、Agent Runtime 和 Nacos/Keycloak 持久化都完成 PostgreSQL 收敛后，再规划 MySQL 下线。
