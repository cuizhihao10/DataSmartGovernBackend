-- @Author : Cui
-- @Date: 2026/06/26 21:31
-- @Description DataSmart Govern Backend - 20260626_agent_artifact_body_read_grant_fact.sql
-- @Version:1.0.0

-- artifact 正文读取 grant fact 表。
--
-- 设计意图：
-- 1. body-read-grants 接口返回的 grantDecisionReference 不是 bearer token，只是一段低敏审计引用；
-- 2. final-check、object-store probe 和后续下载链路必须回查本表，确认 grant 确实由 Java Host 签发、
--    未过期、未撤销，并且仍绑定同一组 command/artifact/run/session/tool 上下文；
-- 3. 本表支持跨 JVM 重启、跨 agent-runtime 实例、审计排障、管理员撤销和未来 TTL 归档；
-- 4. 本表不保存 artifact 正文、sample bytes、stdout/stderr、bucket/key、签名 URL、bearer token、
--    prompt、SQL、工具参数、模型输出、凭据或内部 endpoint，避免把控制面事实库变成敏感内容缓存。
CREATE TABLE IF NOT EXISTS agent_artifact_body_read_grant_fact (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '自增主键，仅用于数据库内部定位，不进入 API 响应或领域 record',
    grant_decision_reference VARCHAR(240) NOT NULL COMMENT '低敏 grant 决策引用，唯一键；不是 bearer token',
    command_id VARCHAR(180) NULL COMMENT '产出 artifact 的 commandId，用于防止跨命令复用 grant',
    artifact_reference VARCHAR(500) NULL COMMENT '低敏 artifact 引用，不是 bucket/key、真实路径或下载地址',
    artifact_reference_type VARCHAR(80) NULL COMMENT 'artifact 引用类型，例如 MINIO_OBJECT、COMMAND_OUTPUT',
    read_purpose VARCHAR(120) NULL COMMENT '正文读取目的，例如 TASK_RESULT_VIEW、AUDIT_REVIEW',
    requested_content_mode VARCHAR(120) NULL COMMENT '请求的读取形态，例如 SAFE_RENDERED_PREVIEW 或受控对象存储读取',
    max_readable_bytes INT NULL COMMENT '本次 grant 允许读取的最大字节数；下游仍需二次裁剪',
    tenant_id VARCHAR(80) NULL COMMENT '租户边界，用于多租户隔离和审计过滤',
    project_id VARCHAR(80) NULL COMMENT '项目或 workspace 边界，用于 PROJECT 数据范围过滤',
    actor_id VARCHAR(120) NULL COMMENT '发起 grant 的 actor 或服务账号低敏标识',
    run_id VARCHAR(180) NULL COMMENT 'Agent run 编号，用于运行轨迹和事件回放串联',
    session_id VARCHAR(180) NULL COMMENT 'Agent session 编号，用于会话级排障',
    tool_code VARCHAR(180) NULL COMMENT '触发 artifact 读取链路的工具编码，不包含工具参数',
    matched_receipt_fingerprint VARCHAR(160) NULL COMMENT 'worker receipt 低敏摘要指纹，不包含 receipt 原文',
    replay_sequence BIGINT NULL COMMENT 'runtime event 或 receipt replay 序号，用于补物化和排障',
    receipt_outcome VARCHAR(120) NULL COMMENT 'receipt 低敏结果状态，不包含 stdout/stderr 或错误堆栈正文',
    issued_at DATETIME(3) NOT NULL COMMENT 'Java Host 签发 grant 的时间',
    expires_at DATETIME(3) NULL COMMENT 'grant 过期时间；为空时业务层应按 fail-closed 处理',
    status VARCHAR(40) NOT NULL COMMENT 'grant fact 状态：ACTIVE、EXPIRED、REVOKED',
    revoked_at DATETIME(3) NULL COMMENT '撤销时间；非空时即使未过期也应拒绝后续正文读取',
    revoked_by VARCHAR(120) NULL COMMENT '执行撤销的低敏操作者标识，来自可信 Header 或服务账号',
    revoke_reason_code VARCHAR(120) NULL COMMENT '机器可读撤销原因码，不保存人工备注正文',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '数据库行创建时间',
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '数据库行更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_aabr_grant_reference (grant_decision_reference),
    KEY idx_aabr_command_scope (command_id, tenant_id, project_id, run_id, session_id, issued_at),
    KEY idx_aabr_scope_status (tenant_id, project_id, status, issued_at),
    KEY idx_aabr_artifact_ref (artifact_reference(191), tenant_id, project_id),
    KEY idx_aabr_expiry_status (status, expires_at),
    KEY idx_aabr_update_time (update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Agent artifact 正文读取授权低敏事实表';
