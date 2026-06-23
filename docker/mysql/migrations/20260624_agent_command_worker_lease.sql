-- DataSmart Govern Backend - Agent Runtime command worker lease fact
--
-- 业务背景：
-- 1. command worker lease 用于约束“哪个 worker 当前有资格处理某个 command outbox 指令”；
-- 2. worker 进入真实副作用区前必须先领取 lease，并获得 fencing_token；
-- 3. worker 写回 command worker receipt 时必须携带同一 token、lease_version 和 lease_expires_at；
-- 4. Java agent-runtime 会读取本表当前事实，拒绝过期 worker、重复 worker 或伪造 token 的回执。
--
-- 安全边界：
-- fencing_token 是内部写回资格凭证，本表可以保存明文以便服务端校验；
-- 但该 token 不允许进入 runtime event、timeline、前端查询、Prometheus label、日志或审计导出正文。
-- 表中不保存命令行、stdout/stderr、payload、SQL、prompt、模型输出、真实路径、URL、凭据或内部 endpoint。

CREATE TABLE IF NOT EXISTS agent_command_worker_lease
(
    -- sessionId/runId/commandId 拼出的稳定唯一键，是领取与回执校验的主定位符。
    lease_identity_key VARCHAR(260) NOT NULL COMMENT '租约唯一键：sessionId/runId/commandId',
    -- Agent 会话 ID，用于控制面定位，不保存用户输入正文。
    session_id         VARCHAR(180) NOT NULL COMMENT 'Agent 会话 ID',
    -- Agent run ID，用于一次多步执行链路的定位。
    run_id             VARCHAR(180) NOT NULL COMMENT 'Agent Run ID',
    -- command outbox 指令 ID。一个 command 同一时刻最多只能有一个有效 worker lease。
    command_id         VARCHAR(180) NOT NULL COMMENT 'Command Outbox 指令 ID',
    -- 当前持有 lease 的 worker 低敏身份，例如 agent-command-worker-01。
    executor_id        VARCHAR(160) NOT NULL COMMENT '当前持有租约的 worker 低敏身份',
    -- 租户、项目、触发者边界。当前为低敏字符串，便于兼容不同上游 ID 类型。
    tenant_id          VARCHAR(80)  NULL COMMENT '租户边界',
    project_id         VARCHAR(80)  NULL COMMENT '项目边界',
    actor_id           VARCHAR(120) NULL COMMENT '触发者边界',
    -- 内部 fencing token。只能用于 claim 响应和 receipt 校验，禁止投影到可回放事件。
    fencing_token      VARCHAR(128) NOT NULL COMMENT '内部 fencing token，禁止对外展示',
    -- 单调递增版本。过期后重新领取必须递增，旧 worker 的 token/version 会被拒绝。
    lease_version      BIGINT       NOT NULL COMMENT '租约单调版本号',
    -- 租约过期时间。worker 超过该时间写回 receipt 会被拒绝。
    lease_expires_at   DATETIME(3)  NOT NULL COMMENT '租约过期时间',
    -- 本次领取时间。过期抢占后会刷新，用于审计“谁在什么时候成为当前持有者”。
    acquired_at        DATETIME(3)  NOT NULL COMMENT '本次领取时间',
    -- 最近更新时间。当前主要用于诊断与后续续租/释放能力扩展。
    updated_at         DATETIME(3)  NOT NULL COMMENT '最近更新时间',
    -- MySQL 维护字段。业务代码不依赖它做状态判断。
    create_time        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '数据库创建时间',
    update_time        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '数据库更新时间',
    PRIMARY KEY (lease_identity_key),
    UNIQUE KEY uk_agent_command_worker_lease_command (session_id, run_id, command_id),
    KEY idx_agent_command_worker_lease_scope (tenant_id, project_id, actor_id, run_id, session_id),
    KEY idx_agent_command_worker_lease_expiry (lease_expires_at),
    KEY idx_agent_command_worker_lease_executor (executor_id, lease_expires_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Agent command worker lease 当前事实表';
