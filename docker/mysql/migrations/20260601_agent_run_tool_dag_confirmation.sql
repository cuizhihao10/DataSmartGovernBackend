-- ---------------------------------------------------------------------------
-- agent-runtime：DAG selected-node 确认记录
-- ---------------------------------------------------------------------------
-- 背景：
-- 1. Agent 在执行有副作用的异步工具前，会先返回 DAG dry-run 预案；
-- 2. 调用方必须带回 selectionFingerprint 并显式 confirmed=true，服务端重新 dry-run 后才能写入 command outbox；
-- 3. command outbox 负责可靠投递，但它本身不能完整解释“谁确认了哪版预案”；
-- 4. 本表保存确认事实，为后续审计时间线、管理员补偿台、策略版本复核和争议排查提供证据。
--
-- 安全边界：
-- 1. selected_node_ids、selected_audit_ids、outbox_ids、command_ids 只保存 ID 列表；
-- 2. 不保存原始工具参数、SQL、prompt、连接密钥、文件内容或样本数据；
-- 3. confirmation_id 由 session/run/fingerprint/auditIds 稳定派生，用唯一索引保证重复确认幂等。
-- ---------------------------------------------------------------------------

USE datasmart_govern;

CREATE TABLE IF NOT EXISTS agent_run_tool_dag_confirmation (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '数据库自增主键，仅用于表内排序和运维定位',
    confirmation_id VARCHAR(160) NOT NULL COMMENT 'DAG selected-node 确认 ID，由 session_id、run_id、selection_fingerprint 和 auditId 集合稳定派生',
    session_id VARCHAR(128) NOT NULL COMMENT 'Agent 会话 ID，用于防止跨会话确认和审计聚合',
    run_id VARCHAR(128) NOT NULL COMMENT 'Agent Run ID，用于把确认记录挂到一次编排执行上下文',
    selection_fingerprint VARCHAR(160) NOT NULL COMMENT '确认瞬间服务端重新 dry-run 得到的选择指纹，用于证明调用方确认的是哪一版预案',
    selected_node_ids JSON NOT NULL COMMENT '调用方显式选择的 DAG nodeId 列表，仅保存节点标识，不保存工具参数',
    selected_audit_ids JSON NOT NULL COMMENT '服务端根据 dry-run 重新提取出的工具审计 ID 白名单，是实际允许入箱的范围',
    policy_versions JSON NOT NULL COMMENT '确认瞬间命中的 permission-admin 策略版本列表，用于执行前复核策略是否漂移',
    delegation_evidence JSON NOT NULL COMMENT '服务账号委托授权证据摘要列表，不保存工具参数或敏感载荷',
    bridge_source_evidence JSON DEFAULT NULL COMMENT 'handoff DAG bridge preview 来源证据摘要；只保存 sourceType、bridgeAction、fingerprint、节点 ID 和 traceId，不保存 prompt、SQL、工具参数或完整模板',
    outbox_ids JSON NOT NULL COMMENT '本次确认关联的 command outbox 记录 ID 列表，便于从确认事实跳转到投递状态',
    command_ids JSON NOT NULL COMMENT '本次确认关联的跨服务 commandId 列表，便于排查 Kafka、task-management Inbox 和任务创建链路',
    tenant_id BIGINT DEFAULT NULL COMMENT '租户边界快照，用于审计查询、配额治理和多租户隔离',
    project_id BIGINT DEFAULT NULL COMMENT '项目边界快照，用于项目级审计、审批和成本归集',
    workspace_id BIGINT DEFAULT NULL COMMENT '工作空间边界快照，用于 Agent workspace 隔离与载荷解析',
    actor_id VARCHAR(128) DEFAULT NULL COMMENT '原始发起人或被代表主体 ID；SERVICE_ACCOUNT 场景下后续可配合 representedActorId 扩展',
    trace_id VARCHAR(128) DEFAULT NULL COMMENT 'HTTP 或网关链路追踪 ID，用于把确认请求、dry-run 事件和 outbox 写入串联起来',
    confirmed TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否已显式确认；当前入箱成功只写 true，字段预留给后续撤销/作废语义',
    status VARCHAR(32) NOT NULL DEFAULT 'CONFIRMED' COMMENT '确认记录状态；当前为 CONFIRMED，后续可扩展 EXPIRED/REVOKED/POLICY_CHANGED',
    expires_at DATETIME(3) DEFAULT NULL COMMENT '确认有效期截止时间；过期不删除历史事实，但不应再作为近期执行确认使用',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '确认记录创建时间',
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '确认记录更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_dag_confirmation_id (confirmation_id),
    KEY idx_agent_dag_confirmation_run_time (session_id, run_id, create_time),
    KEY idx_agent_dag_confirmation_scope_time (tenant_id, project_id, workspace_id, create_time),
    KEY idx_agent_dag_confirmation_status_expire (status, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='agent-runtime DAG selected-node 确认事实表';
