# datasource-management MySQL 到 PostgreSQL 存量数据迁移说明

本文档用于 datasource-management 已完成 PostgreSQL 代码路径切换之后的存量客户数据迁移。当前服务默认业务库已经切换到 PostgreSQL `datasource_management` schema，但如果历史环境已经在 MySQL `datasmart_govern` 中产生数据，仍必须按本流程执行停写、导出、导入、对账和只读观察，不能把“新安装可运行”误判为“存量迁移完成”。

## 迁移边界

本批只迁移 datasource-management 当前 Java 服务真实使用的 14 张控制面表：

- `datasource_config`
- `datasource_readonly_sql_execution_audit`
- `sync_template`
- `sync_task`
- `sync_agent_command_receipt`
- `sync_execution`
- `sync_checkpoint`
- `sync_audit_record`
- `sync_permission_policy_binding`
- `sync_permission_policy_change_request`
- `sync_permission_approval_delegate_rule`
- `sync_governance_alert`
- `sync_alert_delivery_record`
- `sync_permission_governance_notification`

本批不迁移 `data_sync_*` 表。它们属于独立 data-sync 微服务，后续应在 data-sync PostgreSQL 迁移阶段处理同步执行、恢复计划、回放补数、错误样本和 worker receipt。

本批不迁移 `agent_memory_*` 表。它们属于 AI Memory / Agent Runtime，后续应进入 PostgreSQL `ai_memory` schema，并结合 pgvector、全文检索和 LangGraph durable state 统一处理。

本批不处理 `task_data_sync_*` 桥接表。它们需要在 task-management 与 data-sync 迁移边界中确认归属，避免把任务调度事实误迁到 datasource-management。

## 敏感数据要求

`datasource_config` 中的 `jdbc_url`、`username`、`password` 是迁移必须携带的真实凭据字段。脚本不会把这些字段值打印到日志，也不会把样本写入 manifest，但 JSONL 导出文件会包含真实迁移数据，因此必须按敏感介质处理：

- 生产迁移前确认导出目录位于受控磁盘或加密卷。
- 不要把 `artifacts/postgresql-migration/datasource-management/**` 提交到 Git。
- 不要把 JSONL 文件上传到普通工单、聊天工具或不受控网盘。
- 迁移完成并完成回滚窗口后，按企业数据销毁要求清理导出目录。
- 若客户环境要求零明文落盘，应在本脚本外层增加加密归档或受控临时目录策略，而不是删除凭据列。

只读 SQL 审计、同步审计、告警投递和 Agent receipt 中也可能存在 SQL 预览、错误摘要、通道地址摘要、业务对象名或低敏 Agent 元数据。脚本只输出行数与 checksum 前缀，避免在终端和 CI 日志扩散这些内容。

## 推荐流程

1. 冻结 datasource-management 写入。
2. 备份 MySQL `datasmart_govern` 与 PostgreSQL `datasmart_govern`。
3. 确认 PostgreSQL 已通过 Flyway 创建 `datasource_management` schema 与 V1 表结构。
4. 执行只读计划检查。

```powershell
python scripts\datasource-management-mysql-to-postgresql.py --mode plan
```

5. 导出 MySQL 存量数据。

```powershell
python scripts\datasource-management-mysql-to-postgresql.py --mode export
```

6. 使用空 PostgreSQL 目标 schema 执行导入。

```powershell
python scripts\datasource-management-mysql-to-postgresql.py --mode import --apply --export-dir artifacts\postgresql-migration\datasource-management\<timestamp>
```

7. 执行对账。

```powershell
python scripts\datasource-management-mysql-to-postgresql.py --mode verify --export-dir artifacts\postgresql-migration\datasource-management\<timestamp>
```

8. 只读观察 datasource-management 列表、分页、连接测试记录、模板、任务、执行、检查点、告警和审批治理接口。
9. 恢复写入，并保留 MySQL 回滚点到约定窗口结束。

开发或预生产空库环境可以使用一条命令执行完整链路：

```powershell
python scripts\datasource-management-mysql-to-postgresql.py --mode all --apply
```

生产环境仍建议拆分 `plan -> export -> import -> verify`，因为每一步都应保留审批、备份点、日志和人工确认。

## 目标非空保护

脚本默认拒绝导入到非空 PostgreSQL 目标表。原因是 datasource-management 存在数据源名称唯一约束、任务名称唯一约束、Agent receipt 幂等键、告警去重键等。如果目标表已经有 seed、测试数据或人工数据，直接 COPY 可能导致主键冲突、唯一键冲突，或者更危险地形成“部分来自 MySQL、部分来自 PostgreSQL 原有数据”的混合事实。

只有在明确完成目标数据清理、冲突评估和人工审批后，才可以使用：

```powershell
python scripts\datasource-management-mysql-to-postgresql.py --mode import --apply --allow-target-not-empty --export-dir <dir>
```

## 对账方式

脚本对每张表执行两类校验：

- 行数校验：MySQL 导出的行数必须等于 PostgreSQL 目标表行数。
- 稳定 SHA-256 摘要：按固定列顺序、固定时间格式、固定布尔格式和文本化数值计算摘要。

checksum 会覆盖凭据、配置 JSON、SQL 预览和错误摘要等字段，但只输出摘要前缀，不输出明文字段值。这样可以证明迁移完整性，同时避免在日志中泄露客户数据。

## 当前边界

当前脚本服务于 Docker Compose 本地和预生产迁移，默认使用容器内 `mysql` 与 `psql` CLI。如果客户生产环境不能使用 Docker CLI，需要增加远程 CLI、JDBC 或数据库网关模式。

当前 PostgreSQL V1 仍把 JSON 配置和 payload 字段保存为 `TEXT`，与 Java 实体的 String 映射一致。未来如果升级为 JSONB，需要同步修改 DDL、TypeHandler、查询 SQL 和本迁移脚本的 checksum 表达式。

当前脚本不会自动清理 MySQL 表，也不会执行双写或 CDC。商业迁移建议采用停写窗口，或在后续引入可审计 CDC/双读校验后再做在线切换。
