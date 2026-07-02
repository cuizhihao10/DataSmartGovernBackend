# data-quality MySQL 到 PostgreSQL 存量数据迁移说明

本文档用于 `data-quality` 已完成 PostgreSQL 代码路径切换后的存量数据搬迁与对账。它解决的是“旧 MySQL 环境已经有质量规则、执行记录、报告和异常明细时，如何安全切到 PostgreSQL”，不是替代 Flyway 的建表脚本。

## 适用范围

- 迁移表：`quality_rule`、`quality_check_execution`、`quality_check_report`、`quality_anomaly_detail`。
- 源端：迁移期 MySQL `datasmart_govern`。
- 目标端：PostgreSQL `datasmart_govern` 数据库中的 `data_quality` schema。
- 工具：`scripts/data-quality-mysql-to-postgresql.py`。

## 迁移原则

- 默认只读。脚本 `--mode plan/export/verify` 都不写 PostgreSQL；`import/all` 必须显式传 `--apply`。
- 目标表默认必须为空。若目标表已有数据，脚本会拒绝导入，避免误把真实数据覆盖成混合状态。
- 导入会保留旧 MySQL 主键 ID，并在导入后自动校正 PostgreSQL identity sequence。
- 对账使用每表行数和稳定 SHA-256 摘要；摘要只用于判断数据一致性，不输出业务样本正文。
- 脚本不会打印数据库密码、完整连接串、SQL 结果正文、异常样本或报告内容。

## 推荐步骤

1. 停写：关闭 `data-quality` 写入口、后台执行器和调度器，确认没有新的规则、报告或异常明细产生。
2. 备份：分别备份 MySQL 源库和 PostgreSQL 目标库，保留可回滚点。
3. 建表：确认 `data-quality` 服务已启动或 Flyway 已执行，`data_quality` schema 中四张目标表存在。
4. 计划检查：

```powershell
python scripts/data-quality-mysql-to-postgresql.py --mode plan
```

5. 导出：

```powershell
python scripts/data-quality-mysql-to-postgresql.py --mode export
```

6. 导入：

```powershell
python scripts/data-quality-mysql-to-postgresql.py --mode import --apply --export-dir artifacts/postgresql-migration/data-quality/<timestamp>
```

7. 对账：

```powershell
python scripts/data-quality-mysql-to-postgresql.py --mode verify --export-dir artifacts/postgresql-migration/data-quality/<timestamp>
```

8. 只读观察：把 `data-quality` 指向 PostgreSQL，先只读查看规则列表、执行历史、报告详情、异常聚合和低敏导出。
9. 恢复写入：确认只读观察通过后，再恢复规则创建、执行器认领和报告写入。

## 一条链路执行

开发环境或预生产空目标库可使用：

```powershell
python scripts/data-quality-mysql-to-postgresql.py --mode all --apply
```

`all` 会顺序执行导出、导入和对账。生产环境仍建议拆分步骤执行，便于在每一步保留日志、审批和人工确认。

## 失败处理

- `目标表非空`：说明 PostgreSQL 已经存在数据。默认不要强行导入，应先确认这些数据来自哪里。
- `迁移对账失败`：不要切流。应保留导出目录和日志，检查 MySQL 停写是否真实生效、字段类型是否存在未覆盖新列、文本内容是否超长。
- `COPY 失败`：通常是目标 DDL 与源数据约束不一致，例如通过率超出范围、租户/项目为空或文本超长。
- `identity 冲突`：检查脚本是否完成 sequence 校正，以及是否有人在导入后又手工写入了目标表。

## 进入下一个服务迁移的门禁

`permission-admin` 迁移前至少应满足：

- `data-quality` 空库 Flyway 与存量迁移脚本都能重复执行并通过对账；
- 迁移脚本默认安全边界已经被验证，不会误写非空 PostgreSQL 表；
- 对账指标、导出 manifest 和执行日志能作为预生产验收附件；
- 当前 PostgreSQL 目标表无测试残留，且应用层不再依赖 MySQL 驱动。
