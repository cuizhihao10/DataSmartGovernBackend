-- datasource-management V3：将数据源用途收口为 SOURCE / TARGET 二选一。
--
-- 背景说明：
-- 1. V2 为了兼容早期页面和本地 E2E，曾经允许 BOTH，表示同一条数据源既可以作为源端，也可以作为目标端。
-- 2. 当前产品设计已经明确：新建数据源时必须选择“仅源端”或“仅目标端”，不再提供“两端都可用”。
-- 3. 这样做的核心价值是把只读账号和写入账号在数据模型层直接隔离，避免同步任务创建页面把生产源库误放进目标端候选列表。
-- 4. 对于历史 BOTH 数据，本迁移采用安全优先策略折叠为 SOURCE。若某条历史数据源确实需要作为目标端写入，
--    管理员应在页面或运维脚本中显式改为 TARGET，或者重新登记一条目标端专用数据源。

SET search_path TO datasource_management, public;

UPDATE datasource_config
SET usage_purpose = 'SOURCE'
WHERE usage_purpose IS NULL
   OR btrim(usage_purpose) = ''
   OR upper(usage_purpose) = 'BOTH';

ALTER TABLE datasource_config
    ALTER COLUMN usage_purpose SET DEFAULT 'SOURCE';

ALTER TABLE datasource_config
    DROP CONSTRAINT IF EXISTS ck_datasource_usage_purpose;

ALTER TABLE datasource_config
    ADD CONSTRAINT ck_datasource_usage_purpose
        CHECK (usage_purpose IN ('SOURCE', 'TARGET')) NOT VALID;

ALTER TABLE datasource_config
    VALIDATE CONSTRAINT ck_datasource_usage_purpose;

COMMENT ON COLUMN datasource_config.usage_purpose IS
    '数据源用途：SOURCE=仅源端读取，TARGET=仅目标端写入；不再支持 BOTH，创建同步任务时源端和目标端候选数据源必须严格分离';
