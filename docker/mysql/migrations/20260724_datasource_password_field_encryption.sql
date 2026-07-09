-- datasource-management 数据源连接凭据字段级加密兼容迁移。
--
-- MySQL 仍作为本地历史数据卷和迁移期兼容路径存在；PostgreSQL 才是当前目标事实库。
-- 这里保持与 PostgreSQL V9 等价的字段长度和注释，避免旧本地卷继续把 password 理解为“明文密码字段”。

ALTER TABLE datasource_config
    MODIFY COLUMN password TEXT NOT NULL COMMENT '外部数据源连接凭据存储值。新写入必须为 ENC[v1] AES-GCM 密文；历史明文仅用于启动迁移兼容。接口、日志和审计禁止返回明文或密文正文。';
