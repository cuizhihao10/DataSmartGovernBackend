-- 直接 Agent 工具调用不会经过初始 async command outbox，因此 command_id 必须允许为空。
-- entry_mode 只描述进入 data-sync 的方式，不是新的执行状态机；worker 与 Recovery 状态仍由原表维护。
ALTER TABLE data_sync_agent_execution_correlation
    ALTER COLUMN command_id DROP NOT NULL;

ALTER TABLE data_sync_agent_execution_correlation
    ADD COLUMN IF NOT EXISTS entry_mode VARCHAR(32) NOT NULL DEFAULT 'ASYNC_AGENT_COMMAND';

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'ck_data_sync_agent_execution_correlation_entry_mode'
    ) THEN
        ALTER TABLE data_sync_agent_execution_correlation
            ADD CONSTRAINT ck_data_sync_agent_execution_correlation_entry_mode
            CHECK (entry_mode IN ('ASYNC_AGENT_COMMAND', 'DIRECT_AGENT_TOOL'));
    END IF;
END $$;

COMMENT ON COLUMN data_sync_agent_execution_correlation.entry_mode IS
    'Agent 进入 data-sync 的方式：异步命令或直接受治理工具调用';
