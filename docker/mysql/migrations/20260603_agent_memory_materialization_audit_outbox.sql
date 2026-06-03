-- DataSmart Govern Backend - Agent 长期记忆物化审计 outbox 表
--
-- 设计背景：
-- agent_memory_materialization_receipt 记录“候选是否已经尝试落成正式记忆”，
-- agent_memory_materialization_lease 记录“哪个 worker 暂时拥有处理权”，
-- 本表记录“worker 批次或管理员补偿动作是否已经留下可转交审计中心的审计事实”。
--
-- 为什么需要单独 outbox：
-- - Runtime Event 更偏前端 replay 和实时可见性，不一定满足客户审计归档要求；
-- - Prometheus 只保留低基数聚合指标，不能作为单次补偿动作的审计凭证；
-- - 审计 outbox 后续可以由独立 dispatcher 投递到 Java permission-admin 审计、Kafka、对象归档或客户 SIEM；
-- - 与 lease/receipt 解耦后，投递失败、重试、归档不会污染 worker 领取状态或物化执行证据。
--
-- 敏感信息约束：
-- payload_json 只能保存低敏控制面事实，例如计数、状态、candidateId、workerId、namespace、attemptCount。
-- 禁止保存 prompt、候选正文、正式记忆正文、SQL、样本数据、工具原始输出、lease token 或完整异常堆栈。
--
-- 当前 migration 只建表，不创建 dispatcher。后续如果实现投递 worker，可围绕 delivery_status、
-- attempt_count、next_delivery_attempt_at 增加 claim/ack/retry 语义。

CREATE TABLE IF NOT EXISTS agent_memory_materialization_audit_outbox (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '自增主键，仅用于数据库内部索引。',
    outbox_id VARCHAR(128) NOT NULL COMMENT '审计 outbox 业务 ID；每条 worker 批次或补偿动作一条。',
    event_type VARCHAR(96) NOT NULL COMMENT '审计事实类型，例如 memory_materialization_run_completed 或 memory_materialization_requeue_recorded。',
    event_purpose VARCHAR(128) NOT NULL COMMENT '事件用途，例如 batch_observability 或 compensation_audit，便于后续审计路由。',
    aggregate_id VARCHAR(160) NOT NULL COMMENT '聚合对象 ID；补偿优先 candidateId，批次优先 workerId/runId。',
    tenant_id VARCHAR(64) DEFAULT NULL COMMENT '租户 ID；后台批次可能没有单一租户，管理员补偿通常有租户。',
    project_id VARCHAR(64) DEFAULT NULL COMMENT '项目 ID；后台批次可能为空，补偿通常继承 lease 项目。',
    actor_id VARCHAR(64) DEFAULT NULL COMMENT '操作者或服务账号 ID；worker 批次可为空或使用 worker 身份。',
    request_id VARCHAR(128) DEFAULT NULL COMMENT '管理请求 ID，用于串联 gateway/Java 管理台调用。',
    run_id VARCHAR(128) DEFAULT NULL COMMENT 'Runtime Event runId，用于 replay 和审计详情页跳转。',
    session_id VARCHAR(128) DEFAULT NULL COMMENT '会话 ID；后台补偿通常为空。',
    severity VARCHAR(32) NOT NULL COMMENT '事件严重级别：info、warning、error、audit。',
    action VARCHAR(64) DEFAULT NULL COMMENT '动作，例如 batch_completed、dry_run_requeue、scheduled_retry。',
    dry_run TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否 dry-run；真实重排和预览必须明确区分。',
    payload_json JSON NOT NULL COMMENT '低敏审计 payload，禁止保存正文、SQL、样本数据、工具原始输出或 lease token。',
    delivery_status VARCHAR(32) NOT NULL DEFAULT 'pending' COMMENT '投递状态：pending、dispatched、failed；当前阶段先写 pending。',
    attempt_count INT NOT NULL DEFAULT 0 COMMENT '后续 dispatcher 投递尝试次数；当前 append 阶段保持 0。',
    next_delivery_attempt_at DATETIME(3) DEFAULT NULL COMMENT '后续 dispatcher 下一次允许投递时间。',
    created_at DATETIME(3) NOT NULL COMMENT '审计事实产生时间。',
    updated_at DATETIME(3) NOT NULL COMMENT '审计 outbox 最近更新时间。',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '数据库记录创建时间。',
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '数据库记录更新时间。',
    UNIQUE KEY uk_agent_memory_materialization_audit_outbox_id (outbox_id),
    KEY idx_agent_memory_materialization_audit_dispatch (delivery_status, next_delivery_attempt_at, update_time),
    KEY idx_agent_memory_materialization_audit_scope (tenant_id, project_id, event_type, created_at),
    KEY idx_agent_memory_materialization_audit_actor (tenant_id, actor_id, created_at),
    KEY idx_agent_memory_materialization_audit_aggregate (aggregate_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 长期记忆物化审计 outbox 表';
