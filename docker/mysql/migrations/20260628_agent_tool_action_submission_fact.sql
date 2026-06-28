-- DataSmart Govern Backend - Agent tool action submission fact
--
-- 业务背景：
-- 1. command outbox 表示“Agent 已把受控工具动作投递给 worker”，但不能证明下游真实副作用是否已经开始；
-- 2. command worker receipt 表示“worker 已回写结果”，但如果 agent-runtime 在真实提交后、receipt 前崩溃，仍会出现重放风险；
-- 3. 本表用于保存 commandId 到真实下游提交状态的低敏事实，让重复调用优先命中事实而不是再次调用 data-quality。
--
-- 状态语义：
-- SUBMITTING：已登记真实提交意图，当前不能重复调用下游；
-- SUBMITTED：下游明确创建了真实治理任务，可用 downstream_task_id 对账；
-- REJECTED：下游明确拒绝创建任务，可复用该拒绝结果；
-- UNKNOWN：网络超时、响应异常或写回不确定，必须先对账下游后再决定是否补偿。
--
-- 安全边界：
-- 本表只保存低敏机器事实，不保存 payload body、工具参数正文、用户 prompt、SQL、样本数据、模型输出、凭据、
-- 内部 URL、stdout/stderr 或 data-quality payloadPreview。payload_reference 和 confirmation_id 只是服务端引用，
-- 真正读取正文仍必须回到 agent-runtime Host 内部受控 store，并重新校验租户、项目、run、tool、policy 和 TTL。
CREATE TABLE IF NOT EXISTS agent_tool_action_submission_fact
(
    -- 稳定主键，当前默认为 tool-action-submission:{commandId}，为后续支持更多工具提交类型预留命名空间。
    submission_identity_key VARCHAR(220) NOT NULL COMMENT '受控工具提交事实唯一键',
    -- command outbox ID，是防重复提交的核心业务幂等键。
    command_id              VARCHAR(180) NOT NULL COMMENT 'Command Outbox 指令 ID',
    -- worker 或 command 侧幂等键；为空时服务端可回退 command_id，但生产环境建议显式传入。
    idempotency_key         VARCHAR(180) NULL COMMENT '提交幂等键',
    -- Agent 会话与运行定位字段，只用于控制面恢复、审计和排障。
    session_id              VARCHAR(180) NULL COMMENT 'Agent 会话 ID',
    run_id                  VARCHAR(180) NULL COMMENT 'Agent Run ID',
    audit_id                VARCHAR(200) NULL COMMENT '工具审计 ID',
    tool_code               VARCHAR(180) NULL COMMENT '工具编码',
    -- 租户、项目、触发者边界，用于查询过滤和恢复校验。
    tenant_id               VARCHAR(80)  NULL COMMENT '租户边界',
    project_id              VARCHAR(80)  NULL COMMENT '项目边界',
    actor_id                VARCHAR(120) NULL COMMENT '触发者边界',
    -- 服务端引用与审批事实引用。这里不保存正文，只保存低敏定位符。
    payload_reference       VARCHAR(260) NULL COMMENT '服务端 payload 引用',
    confirmation_id         VARCHAR(220) NULL COMMENT '审批确认事实 ID',
    policy_version          VARCHAR(120) NULL COMMENT '执行策略版本',
    -- 下游服务逻辑名和端点模板。禁止保存完整内部 URL。
    target_service          VARCHAR(120) NULL COMMENT '目标服务逻辑名',
    target_endpoint         VARCHAR(180) NULL COMMENT '目标端点模板',
    -- 提交事实状态。SUBMITTING/UNKNOWN 都不能盲目重放下游副作用。
    status                  VARCHAR(40)  NOT NULL COMMENT '提交状态',
    side_effect_started     TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否已经开始真实副作用调用',
    side_effect_executed    TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否已经确认副作用成功落地',
    outcome                 VARCHAR(120) NULL COMMENT '低敏结果编码',
    downstream_task_id      BIGINT       NULL COMMENT '下游真实任务 ID',
    downstream_task_status  VARCHAR(80)  NULL COMMENT '下游任务状态摘要',
    error_code              VARCHAR(160) NULL COMMENT '低敏机器错误码',
    issue_codes             VARCHAR(900) NULL COMMENT '低敏问题码集合，换行分隔',
    recommended_actions     VARCHAR(1000) NULL COMMENT '低敏推荐动作集合，换行分隔',
    low_sensitive_message   VARCHAR(300) NULL COMMENT '低敏消息摘要',
    first_submitted_at      DATETIME(3)  NOT NULL COMMENT '首次登记提交意图时间',
    last_updated_at         DATETIME(3)  NOT NULL COMMENT '最近状态更新时间',
    create_time             TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '数据库创建时间',
    update_time             TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '数据库更新时间',
    PRIMARY KEY (submission_identity_key),
    UNIQUE KEY uk_agent_tool_action_submission_command (command_id),
    KEY idx_agent_tool_action_submission_idempotency (idempotency_key),
    KEY idx_agent_tool_action_submission_scope (tenant_id, project_id, actor_id, run_id, session_id),
    KEY idx_agent_tool_action_submission_status (status, last_updated_at),
    KEY idx_agent_tool_action_submission_downstream (downstream_task_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Agent 受控工具真实提交低敏事实表';
