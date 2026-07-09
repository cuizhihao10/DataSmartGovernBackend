-- datasource-management 数据源连接凭据字段级加密迁移。
--
-- 背景：
-- 1. 历史版本 datasource_config.password 使用 VARCHAR(256)，并允许保存外部数据源连接密码明文；
-- 2. 本版本开始由应用层使用 AES-GCM 写入 ENC[v1] 密文，密文会包含版本、keyId、IV 和认证标签；
-- 3. AES-GCM 密文长度通常明显长于原始密码，因此字段需要放宽到 TEXT；
-- 4. 历史明文由 DataSourceCredentialMigrationRunner 在服务启动时原地迁移，本 SQL 只负责结构和注释。

SET search_path TO datasource_management, public;

ALTER TABLE datasource_config
    ALTER COLUMN password TYPE TEXT;

COMMENT ON COLUMN datasource_config.password IS
    '外部数据源连接凭据存储值。新写入必须为 ENC[v1] AES-GCM 密文；历史明文仅用于启动迁移兼容。接口、日志和审计禁止返回明文或密文正文。';
