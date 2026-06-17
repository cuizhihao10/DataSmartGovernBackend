-- ---------------------------------------------------------------------------
-- agent-runtime: worker/dry-run receipt 低敏持久化索引
-- ---------------------------------------------------------------------------
-- 背景：
-- 1. Java Agent Runtime 5.80 已经新增内存版 worker receipt 专用索引，让恢复事实包可以优先按 commandId
--    查询 dry-run/worker 回执，而不是每次扫描通用 runtime event projection 热窗口；
-- 2. 内存索引适合本地学习和单实例联调，但无法支撑服务重启、多实例控制面、长期审计、管理员排障或真实恢复前置校验；
-- 3. 本表把 worker receipt 升级为 MySQL durable host fact，使 Codex/Claude Code/LangGraph/OpenAI Agents
--    类暂停-审批-恢复链路中的“worker 已处理过该 command”成为 Java 控制面可回查事实。
--
-- 安全边界：
-- 1. 本表只保存低敏机器事实：eventIdentityKey、commandId、租户/项目/actor、run/session、toolCode、
--    taskStatus、outcome、preCheckPassed、sideEffectExecuted、errorCode、replaySequence 和时间戳；
-- 2. 本表绝不保存 receipt message、payloadKey、payload body、prompt、SQL、工具参数、样本数据、模型输出、
--    凭证、token、内部 endpoint、工具结果正文或用户澄清原文；
-- 3. tool_code 只是目录标识，用于避免同一 commandId 被误关联到不同工具动作，不代表工具参数或目标地址。
--
-- 性能与演进：
-- 1. event_identity_key 是幂等主键，防止 HTTP 重试、Kafka 重放或 fallback 补物化放大 receiptCount；
-- 2. commandId + tenant/project/run/session/replaySequence 组合索引用于恢复事实包的关键路径；
-- 3. tenant/project/consumedAt 索引用于管理员按时间排障、后续 TTL 清理和审计导出；
-- 4. 如果表进入千万级，应继续演进为按 consumed_at 分区、归档表、审计中心事件湖或冷热分层查询。
-- ---------------------------------------------------------------------------

USE datasmart_govern;

CREATE TABLE IF NOT EXISTS agent_tool_action_worker_receipt_index (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '数据库自增主键，仅用于表内排序、运维定位和后续分页诊断',
    event_identity_key VARCHAR(240) NOT NULL COMMENT 'runtime event projection 的低敏 identityKey；用于 worker receipt 幂等物化',
    command_id VARCHAR(180) NOT NULL COMMENT 'Java command outbox 的 commandId；恢复事实包按该字段查询 worker/dry-run receipt',
    tenant_id VARCHAR(80) DEFAULT NULL COMMENT '租户边界快照；查询时必须与访问上下文求交集，不能仅凭 commandId 返回',
    project_id VARCHAR(80) DEFAULT NULL COMMENT '项目边界快照；用于 PROJECT 数据范围过滤和跨项目隔离',
    actor_id VARCHAR(120) DEFAULT NULL COMMENT '发起用户、服务账号或 Agent 身份快照；用于 SELF/PROJECT_OWNER 等细粒度恢复策略',
    run_id VARCHAR(180) DEFAULT NULL COMMENT 'Agent Run ID；用于把 receipt 限定在同一次运行内，避免跨 run 误采信',
    session_id VARCHAR(180) DEFAULT NULL COMMENT 'Agent Session ID；用于会话级恢复、timeline 聚合和审计排障',
    tool_code VARCHAR(180) DEFAULT NULL COMMENT '低敏工具编码；只用于工具目录校验，不保存工具参数或目标 endpoint',
    task_status VARCHAR(80) DEFAULT NULL COMMENT 'task-management 任务状态摘要，例如 RUNNING、FAILED；仅用于状态解释和排障',
    outcome VARCHAR(120) DEFAULT NULL COMMENT 'dry-run/worker 结果摘要，例如 DRY_RUN_PASSED、FAILED_PRECHECK；不保存人类 message',
    pre_check_passed TINYINT(1) NOT NULL DEFAULT 0 COMMENT '执行前复核是否通过；false 可能代表阻断、等待条件或失败预检',
    side_effect_executed TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否已经越过真实副作用边界；dry-run receipt 必须保持 false',
    error_code VARCHAR(160) DEFAULT NULL COMMENT '低敏机器错误码；用于聚合和建议动作，不保存错误 message 或 payload',
    replay_sequence BIGINT DEFAULT NULL COMMENT 'Java runtime event projection 分配的回放游标，用于判断最新 receipt',
    consumed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'receipt 事件被 Java 控制面接收或消费的时间',
    indexed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '索引记录写入或刷新时间，用于 TTL、归档和运维诊断',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '索引记录首次创建时间',
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '数据库行最后更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_worker_receipt_identity (event_identity_key),
    KEY idx_agent_worker_receipt_command_scope (
        command_id, tenant_id, project_id, run_id, session_id, replay_sequence
    ),
    KEY idx_agent_worker_receipt_scope_time (tenant_id, project_id, consumed_at),
    KEY idx_agent_worker_receipt_run (run_id, session_id, replay_sequence),
    KEY idx_agent_worker_receipt_tool_time (tenant_id, project_id, tool_code, consumed_at),
    KEY idx_agent_worker_receipt_update_time (update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='agent-runtime worker/dry-run receipt 的低敏持久化索引';
