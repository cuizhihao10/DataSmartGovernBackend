-- ---------------------------------------------------------------------------
-- agent-runtime：Agent ASYNC_TASK command outbox 死信状态说明
-- ---------------------------------------------------------------------------
-- 本迁移不改变字段类型，只更新 status 字段注释，明确 DEAD_LETTER 是合法状态。
-- status 仍保持 VARCHAR(32)，是为了避免不同 MySQL 环境中 ENUM 变更带来的兼容性风险。
--
-- DEAD_LETTER 的业务语义：
-- 1. dispatcher 不再自动领取；
-- 2. 管理员可以在补偿台 requeue、ignore 或追加备注；
-- 3. commandId/idempotencyKey/payloadReference 等低敏事实继续保留，便于审计和恢复；
-- 4. 不能把 DEAD_LETTER 误当成 PUBLISHED，它只代表自动恢复已经停止。
-- ---------------------------------------------------------------------------

USE datasmart_govern;

ALTER TABLE agent_async_task_command_outbox
    MODIFY COLUMN status VARCHAR(32) NOT NULL DEFAULT 'PENDING'
        COMMENT '投递状态：PENDING/PUBLISHING/PUBLISHED/FAILED/BLOCKED/DEAD_LETTER/IGNORED';
