-- 将最终态 callback worker 的租约、退避和历史时间统一为 PostgreSQL TIMESTAMPTZ。
-- 旧版 V8 使用无时区列时，JVM 默认时区会影响 lease 到期判断；本迁移明确按 UTC 墙钟解释旧值，
-- 使跨时区 Java 实例和数据库连接共享同一个绝对时刻。

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = current_schema()
           AND table_name = 'agent_command_task_final_callback_job'
           AND column_name = 'next_attempt_at'
           AND data_type = 'timestamp without time zone'
    ) THEN
        ALTER TABLE agent_command_task_final_callback_job
            ALTER COLUMN next_attempt_at TYPE TIMESTAMP WITH TIME ZONE
                USING next_attempt_at AT TIME ZONE 'UTC',
            ALTER COLUMN lease_expires_at TYPE TIMESTAMP WITH TIME ZONE
                USING lease_expires_at AT TIME ZONE 'UTC',
            ALTER COLUMN callback_delivered_at TYPE TIMESTAMP WITH TIME ZONE
                USING callback_delivered_at AT TIME ZONE 'UTC',
            ALTER COLUMN create_time TYPE TIMESTAMP WITH TIME ZONE
                USING create_time AT TIME ZONE 'UTC',
            ALTER COLUMN update_time TYPE TIMESTAMP WITH TIME ZONE
                USING update_time AT TIME ZONE 'UTC';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = current_schema()
           AND table_name = 'agent_command_task_final_callback_history'
           AND column_name = 'occurred_at'
           AND data_type = 'timestamp without time zone'
    ) THEN
        ALTER TABLE agent_command_task_final_callback_history
            ALTER COLUMN occurred_at TYPE TIMESTAMP WITH TIME ZONE
                USING occurred_at AT TIME ZONE 'UTC',
            ALTER COLUMN create_time TYPE TIMESTAMP WITH TIME ZONE
                USING create_time AT TIME ZONE 'UTC';
    END IF;
END
$$;
