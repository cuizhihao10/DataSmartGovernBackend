-- DataSmart Govern Backend - Agent 长期记忆落成 worker 租约表
--
-- 设计背景：
-- agent_memory_write_candidate 表表达审批事实，agent_memory_materialization_receipt 表表达执行证据。
-- 当 Python Runtime 部署多个 worker 实例时，还需要独立回答：“当前哪个 worker 暂时拥有某条候选的处理权？”
-- 如果没有租约表，多个实例会同时扫描到同一条 APPROVED 候选并重复写入。正式记忆 store 虽然已经具备幂等键，
-- 但重复执行仍会浪费数据库、向量库、图谱和对象索引资源，并让运维难以解释 attempt_count 为什么持续增长。
--
-- 状态说明：
-- - leased：某个 worker 已在 leased_until 之前取得处理权；
-- - succeeded：候选已经成功落成，后续 runner 不再重复领取；
-- - failed：最近一次落成失败，当前阶段允许后续 runner 再次领取。
--
-- 安全说明：
-- lease_token 是每次领取生成的 fencing token。旧 worker 即使晚到，也不能覆盖新 worker 的完成结果。
-- 该 token 只用于服务内部条件更新，禁止输出到诊断接口、日志或前端。
CREATE TABLE IF NOT EXISTS agent_memory_materialization_lease (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '自增主键，仅用于数据库内部索引。',
    lease_id VARCHAR(160) NOT NULL COMMENT '稳定租约业务 ID，当前按 candidate_id 生成。',
    candidate_id VARCHAR(128) NOT NULL COMMENT '被领取的长期记忆候选 ID。',
    tenant_id VARCHAR(64) NOT NULL COMMENT '租户 ID，用于多租户隔离、补偿查询和指标分组。',
    project_id VARCHAR(64) NOT NULL COMMENT '项目 ID，用于项目级补偿、审计和问题复盘。',
    workspace_key VARCHAR(255) NOT NULL COMMENT 'Agent 工作空间隔离键，必须继承候选 workspace。',
    memory_namespace VARCHAR(255) NOT NULL COMMENT '长期记忆命名空间，必须继承候选 namespace。',
    status VARCHAR(32) NOT NULL COMMENT '租约状态：leased、succeeded、failed。',
    attempt_count INT NOT NULL DEFAULT 1 COMMENT '候选被领取的累计次数，用于识别 worker 抖动或毒性候选。',
    worker_id VARCHAR(128) NOT NULL COMMENT '当前或最近处理该候选的 worker 实例标识。',
    lease_token VARCHAR(128) NOT NULL COMMENT '内部 fencing token，禁止写入日志、诊断接口或前端。',
    leased_until DATETIME(3) NOT NULL COMMENT '租约过期时间；worker 崩溃后其他实例可在该时间之后接管。',
    memory_id VARCHAR(128) DEFAULT NULL COMMENT '成功落成后的正式记忆 ID。',
    outcome VARCHAR(64) DEFAULT NULL COMMENT 'materializer 结果，例如 materialized 或 already_materialized。',
    message VARCHAR(1024) DEFAULT NULL COMMENT '低敏成功说明，禁止保存工具原始输出。',
    error_message VARCHAR(1024) DEFAULT NULL COMMENT '低敏失败摘要，禁止保存 prompt、SQL、样本或完整异常堆栈。',
    started_at DATETIME(3) DEFAULT NULL COMMENT '最近一次领取开始时间。',
    finished_at DATETIME(3) DEFAULT NULL COMMENT '最近一次成功或失败结束时间。',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '租约记录首次创建时间。',
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '租约记录最近更新时间。',
    UNIQUE KEY uk_agent_memory_materialization_lease_id (lease_id),
    UNIQUE KEY uk_agent_memory_materialization_lease_candidate (candidate_id),
    KEY idx_agent_memory_materialization_lease_claim (status, leased_until, update_time),
    KEY idx_agent_memory_materialization_lease_scope (tenant_id, project_id, status, update_time),
    KEY idx_agent_memory_materialization_lease_workspace (tenant_id, project_id, workspace_key, status, update_time),
    KEY idx_agent_memory_materialization_lease_memory (memory_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 长期记忆落成 worker 租约表';
