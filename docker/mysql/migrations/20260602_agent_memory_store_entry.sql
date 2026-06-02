-- DataSmart Govern Backend - Agent 正式长期记忆持久化表
--
-- 设计背景：
-- agent_memory_write_candidate 表表达“哪些工具结果摘要被允许进入长期记忆写入流程”；
-- agent_memory_materialization_receipt 表表达“后台 worker 是否已经处理过候选”；
-- 本表表达“模型后续可以检索到的正式长期记忆低敏摘要已经存在”。
--
-- 注意：
-- 1. 本表只保存低敏摘要、治理元数据、workspace namespace 和来源引用；
-- 2. 不保存完整工具输出、大样本数据、原始 SQL、文件正文或敏感日志；
-- 3. 向量库/图谱/对象存储可以围绕 memory_id 建二级索引，但本表仍是正式记忆控制面的事实源；
-- 4. 检索时必须使用 tenant/project/session 与 memory_namespace 过滤，不能先全局召回再交给模型“自己忽略”。

CREATE TABLE IF NOT EXISTS agent_memory_store_entry (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '自增主键，仅用于数据库内部索引；业务引用统一使用 memory_id。',
    memory_id VARCHAR(128) NOT NULL COMMENT '正式长期记忆业务 ID，materializer 根据幂等键稳定生成。',
    tenant_id VARCHAR(64) DEFAULT NULL COMMENT '租户 ID；GLOBAL 范围可为空，其他范围必须参与隔离。',
    project_id VARCHAR(64) DEFAULT NULL COMMENT '项目 ID；PROJECT/SESSION 范围必须参与隔离。',
    session_id VARCHAR(128) DEFAULT NULL COMMENT '会话 ID；SESSION 范围使用。',
    memory_type VARCHAR(32) NOT NULL COMMENT '记忆类型：semantic、episodic、procedural、resource、short_term。',
    scope VARCHAR(32) NOT NULL COMMENT '可见范围：session、project、tenant、global。',
    title VARCHAR(255) NOT NULL COMMENT '面向检索解释和审计展示的低敏标题。',
    content TEXT NOT NULL COMMENT '正式记忆低敏摘要，禁止保存完整工具输出、样本数据、原始 SQL 或敏感日志。',
    source VARCHAR(128) DEFAULT NULL COMMENT '记忆来源，例如 agent-runtime-tool-feedback。',
    importance_score DECIMAL(10,4) NOT NULL DEFAULT 0.5000 COMMENT '重要性分，后续可由人工、策略或模型评估更新。',
    sensitivity_level VARCHAR(32) NOT NULL DEFAULT 'internal' COMMENT '敏感级别，例如 public、internal、sensitive、restricted。',
    tags_json JSON DEFAULT NULL COMMENT '轻量标签数组，用于检索排序、UI 筛选和运营分析。',
    created_at DATETIME(3) DEFAULT NULL COMMENT '记忆内容创建时间，通常来自 materializer。',
    attributes_json JSON DEFAULT NULL COMMENT '机器可读治理扩展，例如 payloadPolicy、sourceAuditId、retentionDays。',
    workspace_key VARCHAR(255) NOT NULL COMMENT 'Agent 工作空间隔离键；同项目不同 workspace 不应互相召回记忆。',
    memory_namespace VARCHAR(255) NOT NULL COMMENT '长期记忆命名空间；向量库 metadata filter 和 SQL 查询必须共同使用。',
    namespace_json JSON DEFAULT NULL COMMENT '层级命名空间 tuple 的 JSON 表示，便于迁移到 LangGraph store 或向量库 collection。',
    idempotency_key VARCHAR(512) NOT NULL COMMENT '正式写入幂等键，防止 worker 重试和消息重复投递制造重复记忆。',
    source_candidate_id VARCHAR(128) NOT NULL COMMENT '来源候选 ID，用于从正式记忆反查审批事实。',
    expires_at DATETIME(3) NOT NULL COMMENT '记忆过期时间；检索必须排除已过期记录，后续遗忘任务可按该字段清理。',
    materialized_at DATETIME(3) NOT NULL COMMENT '正式落成时间。',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '数据库记录创建时间。',
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '数据库记录更新时间。',
    UNIQUE KEY uk_agent_memory_store_memory_id (memory_id),
    UNIQUE KEY uk_agent_memory_store_idempotency (idempotency_key),
    UNIQUE KEY uk_agent_memory_store_candidate (source_candidate_id),
    KEY idx_agent_memory_store_project_lookup (tenant_id, project_id, memory_namespace, memory_type, scope, expires_at, materialized_at),
    KEY idx_agent_memory_store_tenant_lookup (tenant_id, memory_namespace, memory_type, scope, expires_at, materialized_at),
    KEY idx_agent_memory_store_session_lookup (tenant_id, project_id, session_id, memory_namespace, memory_type, expires_at),
    KEY idx_agent_memory_store_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 正式长期记忆低敏摘要表';
