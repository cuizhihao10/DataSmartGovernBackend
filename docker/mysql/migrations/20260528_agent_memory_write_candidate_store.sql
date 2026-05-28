-- DataSmart Govern Backend - Agent 长期记忆写入候选持久化表
--
-- 设计背景：
-- 长期记忆候选是 Agent 工具结果进入 Chroma、Neo4j、MySQL、MinIO 等长期存储前的治理闸口。
-- 它不是正式记忆，也不是普通日志，而是一个需要审批、拒绝、审计、重试和后续异步写入 worker 消费的业务资源。
--
-- 生产化关注点：
-- 1. candidate_id：审批台、审计表、写入 worker 和前端详情页共同引用的稳定 ID；
-- 2. idempotency_key：同一请求、同一工具、同一 audit/run 重复提交时用于去重；
-- 3. candidate_version：乐观锁版本号，防止并发审批互相覆盖；
-- 4. tenant/project/scope：后续必须与 gateway/permission-admin 下发的数据范围共同约束；
-- 5. audit 表：批准/拒绝都要记录前后状态、操作者和原因，不能只看主表最后状态。

CREATE TABLE IF NOT EXISTS agent_memory_write_candidate (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '自增主键，仅用于数据库内部索引；业务引用统一使用 candidate_id。',
    candidate_id VARCHAR(128) NOT NULL COMMENT '长期记忆写入候选业务 ID，审批、拒绝、审计和异步写入 worker 均围绕该 ID 关联。',
    tenant_id VARCHAR(64) NOT NULL COMMENT '租户 ID，用于多租户隔离；候选查询和写入 worker 必须按租户过滤。',
    project_id VARCHAR(64) NOT NULL COMMENT '项目 ID，用于项目级数据范围隔离；项目负责人审批时也应基于该字段校验。',
    actor_id VARCHAR(64) NOT NULL COMMENT '候选来源操作者 ID，表示最初触发本次 Agent Run 或工具结果沉淀的人。',
    memory_type VARCHAR(32) NOT NULL COMMENT '候选希望写入的记忆类型：semantic、episodic、procedural、resource、short_term。',
    scope VARCHAR(32) NOT NULL COMMENT '候选未来可见范围：session、project、tenant、global；范围越大审批和脱敏要求越高。',
    status VARCHAR(32) NOT NULL COMMENT '候选生命周期状态：draft、pending_approval、approved、rejected、ignored。',
    title VARCHAR(255) NOT NULL COMMENT '面向审批台展示的人类可读标题，禁止放入完整敏感数据。',
    content_summary TEXT NOT NULL COMMENT '候选内容摘要，只保存脱敏摘要和引用说明，不保存完整工具输出。',
    source VARCHAR(64) NOT NULL COMMENT '候选来源，例如 agent-runtime-tool-feedback 或 agent-plan-tool-plan。',
    source_tool_name VARCHAR(128) DEFAULT NULL COMMENT '来源工具名称，用于按工具分析哪些结果经常进入长期记忆。',
    source_status VARCHAR(64) DEFAULT NULL COMMENT '来源工具反馈状态，例如 succeeded、failed、skipped，用于排查候选生成原因。',
    source_audit_id VARCHAR(128) DEFAULT NULL COMMENT 'Java agent-runtime 工具执行审计 ID，可追溯到真实工具执行事实。',
    source_run_id VARCHAR(128) DEFAULT NULL COMMENT 'Java agent-runtime run ID，用于和运行事件、WebSocket replay、审计回放关联。',
    output_ref VARCHAR(512) DEFAULT NULL COMMENT '完整工具结果的外部引用，例如 MinIO 路径或审计输出引用；候选表不直接保存完整输出。',
    approval_required TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否要求人工审批；高风险、敏感字段、租户/全局范围通常为 true。',
    retention_days INT NOT NULL DEFAULT 30 COMMENT '建议保留天数，后续遗忘/过期清理任务应基于该字段计算。',
    sensitivity_level VARCHAR(32) NOT NULL DEFAULT 'internal' COMMENT '敏感级别，例如 public、internal、sensitive、restricted。',
    privacy_notes_json JSON DEFAULT NULL COMMENT '隐私、脱敏、租户隔离和写入前注意事项，JSON 数组格式。',
    candidate_version INT NOT NULL DEFAULT 1 COMMENT '候选版本号，用于乐观锁和审计时间线；每次状态决策后递增。',
    idempotency_key VARCHAR(512) DEFAULT NULL COMMENT '幂等键，用于识别重复候选生成或消息重试。',
    decided_at DATETIME(3) DEFAULT NULL COMMENT '最近一次批准或拒绝时间；完整时间线以审计表为准。',
    decided_by VARCHAR(64) DEFAULT NULL COMMENT '最近一次决策操作者 ID；完整操作者记录以审计表为准。',
    decision_reason VARCHAR(1024) DEFAULT NULL COMMENT '最近一次决策原因摘要；完整决策记录以审计表为准。',
    attributes_json JSON DEFAULT NULL COMMENT '机器可读扩展属性，例如 resultKeys、riskLevel、governanceHintKeys。',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '候选创建时间。',
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '候选最近更新时间。',
    UNIQUE KEY uk_agent_memory_write_candidate_id (candidate_id),
    UNIQUE KEY uk_agent_memory_write_candidate_idempotency (tenant_id, idempotency_key),
    KEY idx_agent_memory_write_candidate_scope_status (tenant_id, project_id, status, create_time),
    KEY idx_agent_memory_write_candidate_type_scope (tenant_id, memory_type, scope, status),
    KEY idx_agent_memory_write_candidate_source (source_audit_id, source_run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 长期记忆写入候选表';

CREATE TABLE IF NOT EXISTS agent_memory_write_candidate_audit (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '自增主键。',
    candidate_id VARCHAR(128) NOT NULL COMMENT '被操作的长期记忆候选 ID。',
    tenant_id VARCHAR(64) NOT NULL COMMENT '租户 ID，便于审计查询按租户隔离。',
    project_id VARCHAR(64) NOT NULL COMMENT '项目 ID，便于项目级审批复核。',
    operator_id VARCHAR(64) NOT NULL COMMENT '执行批准或拒绝的操作者 ID。',
    action VARCHAR(32) NOT NULL COMMENT '决策动作：approve 或 reject；后续可扩展 archive、forget、reopen。',
    previous_status VARCHAR(32) NOT NULL COMMENT '操作前状态，用于还原状态流转。',
    next_status VARCHAR(32) NOT NULL COMMENT '操作后状态，用于审计报表和异常复盘。',
    reason VARCHAR(1024) NOT NULL COMMENT '操作者填写的决策原因，禁止为空。',
    candidate_version INT NOT NULL COMMENT '操作后候选版本号，用于和主表快照对齐。',
    decided_at DATETIME(3) DEFAULT NULL COMMENT '业务决策时间，通常来自 Runtime 或审批中心。',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '审计记录写入时间。',
    KEY idx_agent_memory_write_candidate_audit_candidate (candidate_id, id),
    KEY idx_agent_memory_write_candidate_audit_operator (tenant_id, operator_id, create_time),
    KEY idx_agent_memory_write_candidate_audit_project (tenant_id, project_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 长期记忆写入候选操作审计表';
