-- permission-admin MySQL 兼容迁移：补充 Application 与 Project 的领域边界说明。
--
-- PostgreSQL 是 permission-admin 目标数据库，主注释迁移见
-- `V13__clarify_application_project_semantics.sql`。
-- 本脚本服务仍在 MySQL 兼容路径上的本地环境或历史数据卷，仅更新表级注释，
-- 不修改字段类型、不改业务数据，避免对外键和既有数据产生结构性影响。

USE datasmart_govern;

ALTER TABLE permission_application COMMENT =
'租户应用主数据表。Application 表示租户购买、开通或启用的产品/应用能力，例如 FlashSync 数据同步、数据质量、资产目录、合规脱敏；它不是业务项目。';

ALTER TABLE permission_project COMMENT =
'业务项目/数据域主数据表。Project 位于 Application 之下，表示一个应用内的业务资源归属边界，例如某条数据同步工程、某个数据域或某个客户实施项目。';

ALTER TABLE permission_workspace COMMENT =
'工作空间主数据表。Workspace 位于业务项目之下，用于项目内研发/测试/生产/系统执行空间隔离，也用于 Agent 工作区、工具预算策略和机器任务隔离。';
