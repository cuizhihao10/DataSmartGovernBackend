-- 图事实事件 ID 由 graph-facts-approved:{approvalFactId}:{sha256} 组成。
-- approvalFactId 最长 160 字符，加上固定前缀和 64 位指纹后可能超过旧的 VARCHAR(128)。
-- 扩容只改变长度上限，不改变唯一约束、索引或已有数据，适合存量 PostgreSQL 环境前向升级。
ALTER TABLE permission_event_outbox
    ALTER COLUMN event_id TYPE VARCHAR(256);
