-- DataSmart Govern Backend - Agent 长期记忆正式落成 receipt 表
--
-- 设计背景：
-- agent_memory_write_candidate 表表达“是否允许写入长期记忆”，不表达后台 worker 是否真正写入成功。
-- 如果把执行状态继续塞进候选 status，审批语义和执行语义会混在一起，后续补偿、重试、DLQ、
-- 审计导出和指标统计都会变得含糊。
--
-- 本表记录“候选落成正式记忆”的执行证据：
-- - started：worker 已开始处理；
-- - succeeded：正式记忆 store 已写入或幂等复用；
-- - failed：本次尝试失败，保留低敏错误摘要，等待补偿或重试。
--
-- 注意：error_message 只能保存异常类型和短错误说明，不能保存 prompt、SQL、样本数据或原始工具输出。

CREATE TABLE IF NOT EXISTS agent_memory_materialization_receipt (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '自增主键，仅用于数据库内部索引。',
    receipt_id VARCHAR(128) NOT NULL COMMENT '长期记忆落成 receipt 业务 ID；当前按 candidate_id 稳定生成。',
    candidate_id VARCHAR(128) NOT NULL COMMENT '关联的长期记忆候选 ID。',
    tenant_id VARCHAR(64) NOT NULL COMMENT '租户 ID，用于补偿查询、指标分组和多租户隔离。',
    project_id VARCHAR(64) NOT NULL COMMENT '项目 ID，用于项目级补偿、审计和问题复盘。',
    workspace_key VARCHAR(255) NOT NULL COMMENT 'Agent 工作空间隔离键；正式记忆落成必须继承候选 workspace。',
    memory_namespace VARCHAR(255) NOT NULL COMMENT '长期记忆命名空间；后续检索和向量库 metadata filter 必须使用同一值。',
    status VARCHAR(32) NOT NULL COMMENT '落成执行状态：started、succeeded、failed。',
    attempt_count INT NOT NULL DEFAULT 1 COMMENT '同一 receipt 的处理尝试次数，用于识别 worker 抖动、超时重试或管理员补偿。',
    worker_id VARCHAR(128) DEFAULT NULL COMMENT '处理该候选的 worker/实例标识；同步调用可使用 python-ai-runtime-inline。',
    memory_id VARCHAR(128) DEFAULT NULL COMMENT '成功落成后的正式记忆 ID。',
    namespace_json JSON DEFAULT NULL COMMENT '正式记忆层级命名空间 tuple 的 JSON 表示，便于调试和后续迁移。',
    outcome VARCHAR(64) DEFAULT NULL COMMENT 'materializer 返回的结果，例如 materialized 或 already_materialized。',
    message VARCHAR(1024) DEFAULT NULL COMMENT '低敏成功说明，禁止保存工具原始输出。',
    error_message VARCHAR(1024) DEFAULT NULL COMMENT '低敏失败摘要，只保存异常类型和短消息。',
    started_at DATETIME(3) DEFAULT NULL COMMENT '最近一次处理开始时间。',
    finished_at DATETIME(3) DEFAULT NULL COMMENT '最近一次成功或失败完成时间。',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'receipt 首次创建时间。',
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'receipt 最近更新时间。',
    UNIQUE KEY uk_agent_memory_materialization_receipt_id (receipt_id),
    UNIQUE KEY uk_agent_memory_materialization_candidate (candidate_id),
    KEY idx_agent_memory_materialization_scope_status (tenant_id, project_id, status, update_time),
    KEY idx_agent_memory_materialization_workspace (tenant_id, project_id, workspace_key, status, update_time),
    KEY idx_agent_memory_materialization_memory (memory_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 长期记忆正式落成 receipt 表';
