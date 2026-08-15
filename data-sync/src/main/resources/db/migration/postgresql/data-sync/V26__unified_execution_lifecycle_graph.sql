-- 统一生命周期图不创建第二套状态机，只保存 Agent 工具调用与 data-sync execution 的跨域关联键。
-- 所有状态仍由 Agent Runtime、Kafka outbox、data-sync execution 和 Recovery case 各自维护。
CREATE TABLE IF NOT EXISTS data_sync_agent_execution_correlation (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    project_id BIGINT,
    sync_task_id BIGINT NOT NULL,
    sync_execution_id BIGINT NOT NULL,
    command_id VARCHAR(128) NOT NULL,
    session_id VARCHAR(128) NOT NULL,
    run_id VARCHAR(128) NOT NULL,
    audit_id VARCHAR(128) NOT NULL,
    trace_id VARCHAR(128),
    create_time TIMESTAMP NOT NULL DEFAULT LOCALTIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT LOCALTIMESTAMP,
    CONSTRAINT uk_data_sync_agent_execution_correlation
        UNIQUE (tenant_id, sync_execution_id, audit_id)
);

CREATE INDEX IF NOT EXISTS idx_data_sync_agent_execution_lookup
    ON data_sync_agent_execution_correlation (tenant_id, sync_task_id, sync_execution_id);

COMMENT ON TABLE data_sync_agent_execution_correlation IS
    'Agent 工具调用与 data-sync execution 的低敏关联事实，不保存 prompt、参数、SQL、凭据或模型输出';
