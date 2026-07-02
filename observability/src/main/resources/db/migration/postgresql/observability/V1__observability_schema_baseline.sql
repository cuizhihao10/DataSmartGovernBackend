-- observability PostgreSQL schema 基线。
--
-- 当前 observability 的告警规则目录主要由代码和 Prometheus rule 文件派生，尚没有需要从 MySQL 搬运的业务表。
-- 因此首个迁移不虚构复杂告警表，而是建立一个极小的 schema 元数据表，用来验证：
-- 1. pgJDBC 连接确实进入 observability schema；
-- 2. Flyway 能在 PostgreSQL 上登记版本并幂等升级；
-- 3. timestamptz、CHECK、UPSERT 等目标数据库语义可以通过真实集成测试；
-- 4. 后续告警规则、订阅、静默窗口和 SLO 配置拥有正式的版本化迁移入口。

SET search_path TO observability, public;

CREATE TABLE IF NOT EXISTS observability_schema_metadata (
    metadata_key VARCHAR(100) PRIMARY KEY,
    metadata_value VARCHAR(500) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_observability_schema_metadata_key_not_blank
        CHECK (length(btrim(metadata_key)) > 0)
);

COMMENT ON TABLE observability_schema_metadata IS
    'observability schema 的低敏版本与迁移元数据，不保存日志正文、指标正文、token 或业务样本';
COMMENT ON COLUMN observability_schema_metadata.metadata_key IS '稳定的元数据键';
COMMENT ON COLUMN observability_schema_metadata.metadata_value IS '低敏配置值或迁移说明';
COMMENT ON COLUMN observability_schema_metadata.updated_at IS '最近更新时间，使用带时区时间戳';

INSERT INTO observability_schema_metadata(metadata_key, metadata_value)
VALUES
    ('database.engine', 'postgresql'),
    ('database.schema', 'observability'),
    ('migration.phase', 'mysql_to_postgresql_pilot')
ON CONFLICT (metadata_key) DO UPDATE
SET metadata_value = EXCLUDED.metadata_value,
    updated_at = CURRENT_TIMESTAMP;
