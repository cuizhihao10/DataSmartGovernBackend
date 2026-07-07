-- permission-admin：补充 Application 与 Project 的领域边界说明。
--
-- 背景说明：
-- V12 引入了 permission_application 与 permission_project 两类主数据。
-- 在企业级多租户产品里，这两个概念容易被误解为“都是租户下的应用”，
-- 但它们实际承担的产品职责完全不同：
-- 1. Application 表示租户开通的产品/应用能力，例如 FlashSync、数据质量、资产目录、合规脱敏。
--    它更接近“购买了什么产品入口、启用了哪些能力、有哪些应用级菜单/配额/订阅/路由”。
-- 2. Project 表示某个应用下的业务项目、数据域或实施项目，例如“ERP 到数仓同步项目”、
--    “CRM 历史数据迁移项目”或“财务主数据治理项目”。
--    它更接近“数据源、同步任务、质量规则、Agent 会话和成员授权应该归属到哪个业务边界”。
-- 3. Workspace 则进一步表示项目内的开发/测试/生产/系统执行空间，用于隔离环境、风险和机器任务。
--
-- 本迁移只更新数据库注释，不改变表结构和业务数据。这样可以避免修改已发布 V12 迁移导致
-- Flyway checksum 漂移，同时让数据库自描述信息与后续产品口径保持一致。

SET search_path TO permission_admin, public;

COMMENT ON TABLE permission_application IS
    '租户应用主数据表。Application 表示租户购买、开通或启用的产品/应用能力，例如 FlashSync 数据同步、数据质量、资产目录、合规脱敏；它不是业务项目。';
COMMENT ON COLUMN permission_application.application_id IS
    '应用数字 ID。用于产品入口、应用级菜单、订阅套餐、能力开关、配额、审计、路由和多应用切换；不要把它当成具体业务项目 ID。';
COMMENT ON COLUMN permission_application.application_code IS
    '应用稳定编码，适合进入 Keycloak claim、日志、配置和 Agent 工具上下文；示例：FLASHSYNC，表示租户开通的 FlashSync 产品能力。';
COMMENT ON COLUMN permission_application.application_type IS
    '应用类型。当前 FlashSync 定位为数据同步 + Agent 协同应用，未来可扩展为 DATA_QUALITY_APP、DATA_ASSET_APP、COMPLIANCE_APP 等产品能力。';
COMMENT ON COLUMN permission_application.homepage_path IS
    '应用默认首页路径，便于前端根据当前应用跳转到正确产品入口；例如 FlashSync 进入 /data-sync。';
COMMENT ON COLUMN permission_application.default_project_id IS
    '应用默认业务项目 ID。用户进入某个应用但尚未选择项目时，可用它落到默认业务项目/数据域，而不是把 application 与 project 合并。';
COMMENT ON COLUMN permission_application.default_workspace_id IS
    '应用默认工作空间 ID。通常指向默认项目下的默认 workspace，用于本地样例、单工作空间租户和 Agent 默认执行上下文。';

COMMENT ON TABLE permission_project IS
    '业务项目/数据域主数据表。Project 位于 Application 之下，表示一个应用内的业务资源归属边界，例如某条数据同步工程、某个数据域或某个客户实施项目。';
COMMENT ON COLUMN permission_project.project_id IS
    '业务项目数字 ID。permission_project_membership 会把 actor 可访问的 project_id 物化给 gateway 和业务服务，用于数据源、同步任务、质量规则和 Agent 会话的数据范围隔离。';
COMMENT ON COLUMN permission_project.application_id IS
    '项目所属应用 ID。该字段表达“这个业务项目属于哪个产品能力”，例如 FlashSync 默认项目属于 FlashSync 应用；它不表示 project 本身也是应用。';
COMMENT ON COLUMN permission_project.project_code IS
    '业务项目稳定编码。用于跨环境迁移、导入导出、审计和人工排障；示例：FLASHSYNC_DEFAULT。';
COMMENT ON COLUMN permission_project.project_type IS
    '业务项目类型。可表达默认数据治理项目、生产项目、沙箱项目、客户交付项目、数据域项目等，不应用来表示应用类型。';
COMMENT ON COLUMN permission_project.default_workspace_id IS
    '项目默认工作空间 ID。创建数据源、同步任务、质量规则或 Agent 会话时，如果用户只选择了项目但未选择 workspace，可使用该默认工作空间。';

COMMENT ON TABLE permission_workspace IS
    '工作空间主数据表。Workspace 位于业务项目之下，用于项目内研发/测试/生产/系统执行空间隔离，也用于 Agent 工作区、工具预算策略和机器任务隔离。';
COMMENT ON COLUMN permission_workspace.application_id IS
    '工作空间所属应用 ID，用于在多应用租户中快速判断该 workspace 服务哪个产品能力。';
COMMENT ON COLUMN permission_workspace.project_id IS
    '工作空间所属业务项目 ID。普通人工配置空间通常归属具体项目；系统执行空间可归属默认项目，后续也可扩展为应用级系统空间。';
COMMENT ON COLUMN permission_workspace.default_workspace IS
    '是否为当前项目或应用的默认工作空间。创建任务或 Agent 会话时没有显式选择 workspace 时可使用默认项。';
