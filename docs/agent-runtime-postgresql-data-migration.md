# agent-runtime MySQL 到 PostgreSQL 存量数据迁移说明

本文档用于指导 `agent-runtime` 在完成 PostgreSQL 控制面代码路径与 Flyway V1 基线后，把历史 MySQL 控制面事实搬迁到 PostgreSQL `agent_runtime` schema。

当前脚本入口：

```powershell
python scripts\agent-runtime-mysql-to-postgresql.py --mode plan
```

## 迁移边界

本批只迁移 `agent-runtime` PostgreSQL V1 已定义的 11 张 Java 控制面表：

- `agent_tool_execution_audit`
- `agent_tool_execution_event_outbox`
- `agent_async_task_command_outbox`
- `agent_run_tool_dag_confirmation`
- `agent_skill_visibility_snapshot_index`
- `agent_tool_action_resume_locator_index`
- `agent_tool_action_clarification_fact`
- `agent_tool_action_worker_receipt_index`
- `agent_command_worker_lease`
- `agent_artifact_body_read_grant_fact`
- `agent_tool_action_submission_fact`

这些表保存的是 Agent 工具调用审计、状态事件 outbox、异步命令 outbox、DAG selected-node 确认、Skill 可见性投影、恢复定位符、澄清事实、worker receipt、worker lease、artifact 正文读取授权和受控工具提交事实。它们共同构成 Agent Runtime 的“控制面事实层”，用于审计、恢复、重放、幂等和运维诊断。

`agent_memory_*` 不属于本批迁移对象。长期记忆、用户画像、pgvector 语义索引和 LangGraph durable state 后续必须进入独立 `ai_memory` schema。如果把 `agent_memory_*` 塞进 `agent_runtime`，会把“运行控制事实”和“AI 记忆正文/向量”混在同一个生命周期里，后续清理、容量规划、权限隔离和恢复演练都会变得不清晰。

`agent_async_task_command_inbox` 也不属于本批迁移对象。它虽然以 `agent_` 开头，但已经归入 `task_management` schema，因为它是 task-management 接收 Agent command 的 inbox 投影。

`task_*` / `task_data_sync_*` 属于 task-management；`data_sync_*` 属于 data-sync；`sync_*` / `datasource_*` 属于 datasource-management；`quality_*` 属于 data-quality。脚本会在 `plan/export/verify` 中把这些表登记为 `DEFERRED` 或 `REVIEW_REQUIRED`，但不会导出、导入或参与 agent-runtime checksum。

## 脚本能力

`scripts/agent-runtime-mysql-to-postgresql.py` 支持五种模式：

- `plan`：只读检查 MySQL 源表、PostgreSQL 目标表、延期表和人工复核表，不写文件、不写数据库。
- `export`：把 11 张控制面表导出为 JSONL，并生成低敏 `manifest.json`。
- `import`：把 JSONL 通过 PostgreSQL `COPY FROM STDIN` 导入 `agent_runtime` schema，必须显式传入 `--apply`。
- `verify`：按行数和稳定 SHA-256 checksum 对账。
- `all`：执行 `export -> import -> verify`，仍然必须显式传入 `--apply` 才能写 PostgreSQL。

脚本默认拒绝导入到非空目标表。这是有意设计的保护：Agent Runtime 表包含 outbox 幂等键、commandId、auditId、receipt identity、lease identity 和 artifact grant reference。如果目标表已有 seed/test data、失败残留或人工写入，继续 COPY 会造成主键冲突、唯一键冲突或混合事实，后续审计和恢复会失去可信解释。

## 推荐操作流程

1. 停止 agent-runtime 写入入口。

   停写范围包括工具审计写入、状态事件 outbox、异步 command outbox、DAG confirmation、Skill 可见性投影、恢复定位索引、clarification fact、worker receipt、worker lease、artifact read grant 和 submission fact。迁移窗口内允许只读查询，但不要继续产生新的 Agent 控制面事实。

2. 备份 MySQL 与 PostgreSQL。

   至少保留 MySQL 逻辑备份、PostgreSQL 目标库快照、当前应用镜像版本、Compose 配置和迁移脚本版本。迁移失败时必须能回滚到明确状态。

3. 确认 PostgreSQL schema 已由 Flyway 创建。

   `agent-runtime` 的 V1 基线位于 `agent-runtime/src/main/resources/db/migration/postgresql/agent-runtime/V1__agent_runtime_schema_baseline.sql`。迁移脚本不负责建表，它只负责搬迁、对账和 identity sequence 校正。

4. 执行只读计划检查。

   ```powershell
   python scripts\agent-runtime-mysql-to-postgresql.py --mode plan
   ```

   重点确认 11 张控制面表的源端和目标端行数，确认 `agent_memory_*` 被登记为 `DEFERRED targetSchema=ai_memory`，确认 `agent_async_task_command_inbox` 被登记为 `task_management`，并确认额外 `agent_*` 表是否需要人工复核。

5. 导出 JSONL。

   ```powershell
   python scripts\agent-runtime-mysql-to-postgresql.py --mode export --export-dir artifacts\postgresql-migration\agent-runtime\manual-20260703
   ```

   导出目录包含真实 Agent 控制面迁移数据，可能含工具参数引用、payload 引用、低敏治理提示、错误摘要、artifact 引用和 worker lease 信息。该目录不能提交 Git，不能上传普通工单或聊天工具，生产环境应放在加密磁盘、受控临时目录或企业指定安全工作区。

6. 导入 PostgreSQL。

   ```powershell
   python scripts\agent-runtime-mysql-to-postgresql.py --mode import --apply --export-dir artifacts\postgresql-migration\agent-runtime\manual-20260703
   ```

   导入使用 PostgreSQL `COPY`，会保留 MySQL 原始 `id`。对 `agent_command_worker_lease` 和 `agent_tool_action_submission_fact` 这类业务键主键表，脚本不会执行 identity sequence 校正；对拥有 `id` 的表，导入完成后会按最大 `id` 校正 identity sequence。

7. 执行对账。

   ```powershell
   python scripts\agent-runtime-mysql-to-postgresql.py --mode verify --export-dir artifacts\postgresql-migration\agent-runtime\manual-20260703
   ```

   对账比较 manifest 中记录的源端行数与 SHA-256 checksum，以及当前 PostgreSQL 重新计算出的结果。任何一张表失败都应停止切换，保留现场并排查字段映射、时间精度、JSON 文本、目标表非空或脚本版本问题。

8. 只读观察与恢复写入。

   对账通过后，先让 `agent-runtime` 以 PostgreSQL 配置启动并执行只读健康检查、控制面列表查询、run 详情、outbox 查询、receipt 查询和恢复定位查询。确认无误后再逐步恢复 dispatcher、worker receipt 消费、事件投递和受控工具提交。

## 重要字段转换

- `TINYINT(1)` 转为 PostgreSQL `BOOLEAN`，脚本导出时统一使用 `true/false` 文本。
- `DATETIME` / `DATETIME(3)` 转为 PostgreSQL `TIMESTAMP WITHOUT TIME ZONE`，脚本按微秒级文本对账，不做隐式时区转换。
- MySQL `JSON` 类型的字段导出时会 `CAST AS CHAR`，因为 PostgreSQL V1 暂按 `TEXT` 保存，保持 Java `String` 映射稳定。
- `payload_json`、`plan_arguments`、`governance_hints`、`attributes_json`、`issue_codes` 等字段会迁移并参与 checksum，但不会在终端日志或 manifest 中输出样本值。

## 回滚原则

如果导入或对账失败：

- 不要直接手工修改 PostgreSQL 目标表后继续导入，除非已经记录问题原因、修复 SQL 和审批结论。
- 优先清空或恢复 PostgreSQL `agent_runtime` schema 到迁移前快照，然后重新执行 `import/verify`。
- MySQL 在迁移验收完成前保持只读保留，不要立即删除或覆盖。
- 如果应用短暂连接 PostgreSQL 并产生新写入，需要先判断这些写入是否应丢弃、回放到 MySQL，还是作为新事实重新迁移。

## 后续任务

本脚本完成的是 agent-runtime 控制面存量搬迁闭环。后续仍需要：

- 重建 `agent-runtime` 容器并执行真实容器级 PostgreSQL smoke，确认运行中的服务使用 `agent_runtime` schema。
- 进入 `ai_memory` 批次，处理旧 MySQL 初始化脚本中混放的 `agent_memory_*`、pgvector 语义索引、用户画像和 LangGraph durable state。
- 在 Agent Runtime / AI Memory 批次闭环后，再规划旧 MySQL 初始化脚本清理与 MySQL 最终下线。
