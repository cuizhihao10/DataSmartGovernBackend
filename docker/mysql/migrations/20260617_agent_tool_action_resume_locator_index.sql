-- ---------------------------------------------------------------------------
-- agent-runtime: checkpoint/thread 恢复 locator index
-- ---------------------------------------------------------------------------
-- 背景：
-- 1. Python AI Runtime 已经可以在 checkpoint query/resume-preview 阶段生成低敏恢复预检信息；
-- 2. Java agent-runtime 需要把 checkpointId/threadId 映射回 commandId、outboxId、approvalFactId、
--    clarificationFactId、toolCode 和 policyVersion 等控制面事实定位符；
-- 3. 内存索引只适合本地学习和单实例联调，无法支撑服务重启、多实例部署、长期审计和真实恢复前置校验。
--
-- 安全边界：
-- 1. 本表只保存低敏定位符，不保存 prompt、SQL、arguments、payload body、模型输出、样本数据、密钥或内部 endpoint；
-- 2. approval_fact_id / clarification_fact_id 只用于 Java 服务端回查，不应在外部 API 响应中原文回显；
-- 3. 如果未来需要读取真实工具参数或用户补充内容，应通过受控 payloadReference、审批事实接口或审计详情接口完成。
--
-- 性能与演进：
-- 1. checkpoint_id 是最精确恢复入口，建立唯一索引；
-- 2. thread_id 用作 checkpoint 缺失时的兜底恢复入口，也建立唯一索引；
-- 3. tenant/project/run/tool 组合索引用于后续管理员诊断、TTL 清理、策略漂移排查和运行详情聚合；
-- 4. 如果表进入千万级，应继续演进为按时间分区、归档表或审计中心事件湖。
-- ---------------------------------------------------------------------------

USE datasmart_govern;

CREATE TABLE IF NOT EXISTS agent_tool_action_resume_locator_index (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '数据库自增主键，仅用于表内排序、运维定位和后续分页诊断',
    checkpoint_id VARCHAR(160) DEFAULT NULL COMMENT 'Python/LangGraph/OpenClaw checkpoint ID；恢复预检优先按该字段精确定位',
    thread_id VARCHAR(160) DEFAULT NULL COMMENT '线程或图运行上下文 ID；checkpointId 缺失时可作为兜底恢复入口',
    session_id VARCHAR(128) DEFAULT NULL COMMENT 'Agent 会话 ID；用于把恢复事实限定在同一会话边界内',
    run_id VARCHAR(128) DEFAULT NULL COMMENT 'Agent run ID；用于回查 outbox、worker receipt 和 runtime event projection',
    command_id VARCHAR(128) DEFAULT NULL COMMENT 'Java command outbox 的 commandId；用于串联 task-management inbox 和 worker receipt',
    outbox_id VARCHAR(128) DEFAULT NULL COMMENT 'Java command outbox 的 outboxId；用于服务端精确回查 outbox 状态，不对外原文回显',
    approval_fact_id VARCHAR(160) DEFAULT NULL COMMENT 'permission-admin 审批事实 ID；只供服务端回查审批状态，不对外原文回显',
    clarification_fact_id VARCHAR(160) DEFAULT NULL COMMENT '用户澄清事实 ID；当前为未来 clarification fact store 预留定位符',
    tool_code VARCHAR(160) DEFAULT NULL COMMENT '工具编码；用于校验 outbox、approval 和 receipt 是否属于同一个工具动作',
    requested_policy_version VARCHAR(160) DEFAULT NULL COMMENT '恢复预检请求时观察到的策略版本；用于识别恢复时的策略漂移',
    tenant_id VARCHAR(128) DEFAULT NULL COMMENT '租户边界快照；locator 命中后仍必须与访问上下文求交集',
    project_id VARCHAR(128) DEFAULT NULL COMMENT '项目边界快照；防止 checkpoint/thread locator 被跨项目误用',
    actor_id VARCHAR(128) DEFAULT NULL COMMENT '发起用户、服务账号或 Agent 身份快照；SELF 范围校验和审计排查会使用',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '业务层最近一次观察或合并 locator 的时间，用于 TTL、清理和排序',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '索引记录首次创建时间',
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '数据库行最后更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_resume_locator_checkpoint (checkpoint_id),
    UNIQUE KEY uk_agent_resume_locator_thread (thread_id),
    KEY idx_agent_resume_locator_scope_run (tenant_id, project_id, run_id, update_time),
    KEY idx_agent_resume_locator_scope_tool (tenant_id, project_id, tool_code, update_time),
    KEY idx_agent_resume_locator_command (command_id),
    KEY idx_agent_resume_locator_outbox (outbox_id),
    KEY idx_agent_resume_locator_approval (approval_fact_id),
    KEY idx_agent_resume_locator_updated_at (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='agent-runtime checkpoint/thread 到恢复事实定位符的低敏持久化索引';
