-- ---------------------------------------------------------------------------
-- agent-runtime: Human-in-the-loop 澄清事实低敏持久化表
-- ---------------------------------------------------------------------------
-- 背景：
-- 1. Java Agent Runtime 5.79 已经新增内存版 clarification fact store，让“用户已补充信息”从请求体自报字段
--    升级为 Java 控制面可验证事实；
-- 2. 内存 store 适合本地学习和单实例联调，但无法支撑服务重启、多实例控制面、长期审计或真实 resume gate；
-- 3. 本表把澄清事实升级为 MySQL durable host fact，使 LangGraph/OpenClaw/OpenAI Agents 风格的
--    pause -> clarify -> resume 能在企业环境中跨实例、跨重启、可审计地验证。
--
-- 安全边界：
-- 1. 本表只保存低敏控制面元数据：factId、run/session/command/tool、策略版本、租户/项目/actor、状态、
--    低敏 evidence/issue code 和过期时间；
-- 2. 本表绝不保存用户澄清原文、prompt、SQL、arguments、payload body、样本数据、模型输出、凭证、token、
--    内部 endpoint 或工具执行结果正文；
-- 3. 如果未来需要保存用户澄清正文，应进入独立加密对象存储或审计中心，并通过更严格的权限页面读取，
--    不能把正文塞进本控制面事实表。
--
-- 性能与演进：
-- 1. clarification_fact_id 是幂等主键，防止前端重试、网关超时重放或 Agent Host 重新提交产生多条事实；
-- 2. tenant/project/actor/run/session/command/tool 组合索引用于恢复预检中的范围验真；
-- 3. status/expires_at 索引用于 TTL 清理、归档、过期事实诊断和后续低基数指标；
-- 4. 如果事实量进入千万级，应继续演进为按 expires_at 分区、归档表、审计事件湖或冷热分层查询。
-- ---------------------------------------------------------------------------

USE datasmart_govern;

CREATE TABLE IF NOT EXISTS agent_tool_action_clarification_fact (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '数据库自增主键，仅用于表内排序、运维定位和后续分页诊断',
    clarification_fact_id VARCHAR(180) NOT NULL COMMENT '澄清事实 ID；恢复预检按该字段回查服务端事实',
    session_id VARCHAR(180) DEFAULT NULL COMMENT 'Agent Session ID；用于会话级恢复和跨会话隔离',
    run_id VARCHAR(180) DEFAULT NULL COMMENT 'Agent Run ID；用于防止旧 run 的澄清被新 run 误用',
    command_id VARCHAR(180) DEFAULT NULL COMMENT '受控工具动作 commandId；为空表示澄清发生在 command 生成前',
    tool_code VARCHAR(180) DEFAULT NULL COMMENT '低敏工具编码；只表示工具目录身份，不保存工具参数或目标 endpoint',
    requested_policy_version VARCHAR(180) DEFAULT NULL COMMENT '工具治理策略版本；用于恢复预检识别策略漂移',
    tenant_id VARCHAR(80) NOT NULL COMMENT '租户边界；查询和恢复预检必须与可信 Header 求交集',
    project_id VARCHAR(80) NOT NULL COMMENT '项目边界；PROJECT 数据范围下必须命中授权项目集合',
    actor_id VARCHAR(120) NOT NULL COMMENT '登记该澄清事实的 actor；SELF/PROJECT_OWNER 范围会按该字段验真',
    status VARCHAR(40) NOT NULL DEFAULT 'AVAILABLE' COMMENT '事实状态：AVAILABLE、REVOKED、REJECTED',
    evidence_codes_json JSON NOT NULL COMMENT '低敏证据码 JSON 数组；不包含用户澄清正文',
    issue_codes_json JSON NOT NULL COMMENT '低敏问题码 JSON 数组；不包含 prompt、SQL、payload 或工具参数',
    expires_at DATETIME(3) NOT NULL COMMENT '事实过期时间；过期后恢复预检必须按 REJECTED 处理',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '事实首次登记时间',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '事实最近业务更新时间',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '数据库行首次创建时间',
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '数据库行最后更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_clarification_fact_id (clarification_fact_id),
    KEY idx_agent_clarification_fact_scope (
        tenant_id, project_id, actor_id, run_id, session_id, command_id, tool_code
    ),
    KEY idx_agent_clarification_fact_status_expire (status, expires_at),
    KEY idx_agent_clarification_fact_run (run_id, session_id, updated_at),
    KEY idx_agent_clarification_fact_update_time (update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='agent-runtime Human-in-the-loop 澄清事实低敏持久化表';
