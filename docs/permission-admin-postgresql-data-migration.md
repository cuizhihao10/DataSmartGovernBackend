# permission-admin MySQL 到 PostgreSQL 存量数据迁移说明

本文档用于 `permission-admin` 已经完成 PostgreSQL 代码路径切换后的存量数据搬迁与对账。它解决的是“旧 MySQL 环境已经存在角色、菜单、路由策略、数据范围、项目成员、审计记录和 outbox 时，如何安全切到 PostgreSQL”，不是替代 Flyway 的建表脚本。

## 适用范围

- 迁移表：`permission_role`、`permission_menu`、`permission_role_menu_binding`、`permission_route_policy`、`permission_data_scope_policy`、`permission_project_membership`、`permission_audit_record`、`permission_event_outbox`。
- 源端：迁移期 MySQL `datasmart_govern`。
- 目标端：PostgreSQL `datasmart_govern` 数据库中的 `permission_admin` schema。
- 工具：`scripts/permission-admin-mysql-to-postgresql.py`。
- 延后表：MySQL 中发现的 `agent_memory_*` 表只记录到 manifest 的 `deferredTables`，不导出、不导入、不对账。

## Agent Memory 混放表处理原则

旧的 `docker/mysql/init/permission-admin.sql` 为了早期闭环方便，把部分 Agent Memory 表临时混放在权限初始化脚本后半段。现在 PostgreSQL 目标架构已经明确服务级 schema 边界，因此这些表必须从权限迁移中剥离：

- `agent_memory_write_candidate`、`agent_memory_write_candidate_audit` 属于长期记忆候选写入和人工审批事实。
- `agent_memory_materialization_receipt`、`agent_memory_materialization_lease` 属于长期记忆物化 worker 的执行证据和租约。
- `agent_memory_materialization_audit_outbox` 属于 Agent Memory 物化审计事件。
- `agent_memory_store_entry` 等后续 MySQL migration 中出现的 Agent Memory 表，也应归入 `ai_memory` 或 Agent Runtime 迁移批次。

脚本在 `plan/export/verify` 中会扫描 MySQL 当前 schema 的所有 `agent_memory_*` 表，并输出或写入 manifest：

```json
{
  "table": "agent_memory_write_candidate",
  "rows": 0,
  "status": "DEFERRED",
  "targetSchema": "ai_memory",
  "reason": "Agent Memory 表属于 AI Memory / Agent Runtime，后续单独迁入 PostgreSQL ai_memory schema，本脚本不会导出或导入。"
}
```

这不是遗漏，而是有意保持微服务边界：`permission_admin` 只保存权限事实，长期记忆事实后续迁入 `ai_memory`，并结合 pgvector、PostgreSQL 全文索引、LangGraph checkpoint 或 Agent Runtime durable state 统一设计。

## 迁移原则

- 默认只读。脚本 `--mode plan/export/verify` 都不写 PostgreSQL；`import/all` 必须显式传 `--apply`。
- 目标表默认必须为空。若目标表已有数据，脚本会拒绝导入，避免权限事实变成“旧 MySQL + 新 PostgreSQL seed/test data”的混合状态。
- 导入会保留旧 MySQL 主键 ID，并在导入后自动校正 PostgreSQL identity sequence。
- 对账使用每表行数和稳定 SHA-256 摘要；摘要只用于判断数据一致性，不输出审计 detail、outbox payload 或业务样本正文。
- 脚本不会打印数据库密码、完整连接串、SQL 结果正文、token、prompt、payload 或异常样本。

## 推荐步骤

1. 停写：关闭 `permission-admin` 写入口、权限策略管理入口、项目成员授权入口和 outbox dispatcher。
2. 冻结授权变更：通知 gateway、agent-runtime、task-management 等调用方不要在窗口内刷新权限策略或写入新权限事件。
3. 备份：分别备份 MySQL 源库和 PostgreSQL 目标库，保留可回滚点。
4. 建表：确认 `permission-admin` 服务已启动或 Flyway 已执行，`permission_admin` schema 中 8 张目标表存在。
5. 计划检查：

```powershell
python scripts/permission-admin-mysql-to-postgresql.py --mode plan
```

6. 导出：

```powershell
python scripts/permission-admin-mysql-to-postgresql.py --mode export
```

7. 导入：

```powershell
python scripts/permission-admin-mysql-to-postgresql.py --mode import --apply --export-dir artifacts/postgresql-migration/permission-admin/<timestamp>
```

8. 对账：

```powershell
python scripts/permission-admin-mysql-to-postgresql.py --mode verify --export-dir artifacts/postgresql-migration/permission-admin/<timestamp>
```

9. 只读观察：把 `permission-admin` 指向 PostgreSQL，先只读验证角色列表、菜单树、路由策略、数据范围、项目成员授权、审计查询和 outbox 运维列表。
10. 恢复写入：确认只读观察通过后，再恢复策略创建、授权变更、outbox dispatcher 和 gateway 缓存失效链路。

## 一条链路执行

开发环境或预生产空目标库可使用：

```powershell
python scripts/permission-admin-mysql-to-postgresql.py --mode all --apply
```

`all` 会顺序执行导出、导入和对账。生产环境仍建议拆分步骤执行，便于在每一步保留审批、日志、备份点和人工确认。

## 失败处理

- `目标表非空`：说明 PostgreSQL 已经存在 seed、测试数据或人工写入。默认不要强行导入，应先确认这些数据是否可以清理或是否需要重新建库。
- `迁移对账失败`：不要切流。应保留导出目录和日志，检查 MySQL 停写是否真实生效、字段是否存在旧 migration 未补齐、时间精度是否被改写、JSON 文本是否被手工格式化。
- `COPY 导入失败`：通常是目标 DDL 与源数据约束不一致，例如状态枚举不在 PostgreSQL CHECK 范围内、优先级为负数、attempt_count 超过 max_attempts、文本长度超出目标列。
- `identity 冲突`：检查脚本是否完成 sequence 校正，以及是否有人在导入后又手工写入了目标表。
- `Agent Memory 表仍在 MySQL`：这是当前批次的预期结果。不要手工把这些表导入 `permission_admin`，后续应进入 `ai_memory` 迁移设计。

## 进入下一个服务迁移的门禁

`datasource-management` 迁移前至少应满足：

- `permission-admin` 空库 Flyway 与存量迁移脚本都能执行并通过对账；
- manifest 中 `deferredTables` 已清楚记录 Agent Memory 混放表，且团队确认它们不属于权限 schema；
- gateway 授权判定、权限策略缓存失效、项目成员数据范围和 outbox 运维面在 PostgreSQL 下可只读观察；
- 当前 PostgreSQL 目标表无测试残留，应用层不再依赖 MySQL 驱动；
- 已经登记后续 `ai_memory` 迁移任务，避免 Agent Memory 表长期留在 MySQL 迁移盲区。
