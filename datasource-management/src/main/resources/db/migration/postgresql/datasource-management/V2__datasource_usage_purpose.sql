-- datasource-management V2：为数据源补充“源端/目标端用途”。
--
-- 设计背景：
-- 1. 新建同步任务时，源端数据源和目标端数据源承担完全不同的业务角色；
-- 2. 真实生产环境通常会把只读账号和写入账号拆开，避免误把生产源库当成目标库写入；
-- 3. 前端不应该自行猜测某个数据源能否作为源端或目标端，而应该消费后端明确返回的 usage_purpose。
--
-- 字段语义：
-- SOURCE：仅用于源端读取；
-- TARGET：仅用于目标端写入；
-- BOTH：同时允许作为源端和目标端，也是历史数据的兼容默认值。

SET search_path TO datasource_management, public;

ALTER TABLE datasource_config
    ADD COLUMN IF NOT EXISTS usage_purpose VARCHAR(32) NOT NULL DEFAULT 'BOTH';

UPDATE datasource_config
SET usage_purpose = 'BOTH'
WHERE usage_purpose IS NULL OR btrim(usage_purpose) = '';

ALTER TABLE datasource_config
    ADD CONSTRAINT ck_datasource_usage_purpose
        CHECK (usage_purpose IN ('SOURCE', 'TARGET', 'BOTH')) NOT VALID;

ALTER TABLE datasource_config
    VALIDATE CONSTRAINT ck_datasource_usage_purpose;

COMMENT ON COLUMN datasource_config.usage_purpose IS
    '数据源用途：SOURCE=仅源端读取，TARGET=仅目标端写入，BOTH=源端和目标端都可用；用于新建同步任务时按源端/目标端角色过滤候选数据源';

CREATE INDEX IF NOT EXISTS idx_datasource_usage_purpose
    ON datasource_config (tenant_id, project_id, usage_purpose, status);
