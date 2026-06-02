-- DataSmart Govern Backend - Agent 长期记忆落成租约退避与 DLQ 增量字段
--
-- 设计背景：
-- 第一版 lease 表已经能解决“多 worker 同时抢同一候选”的问题，但失败候选会立即重新领取。
-- 在真实生产环境中，坏候选、下游数据库抖动、向量库不可用或历史配置缺字段都可能导致连续失败。
-- 如果失败后不退避，worker 会在短时间内反复打同一条候选，造成日志风暴、数据库写放大和不可解释 backlog。
--
-- 本 migration 增加 next_retry_at：
-- - status=failed 时表示下一次允许自动领取的时间；
-- - status=dead_letter 时保持为空，表示必须由管理员补偿或人工重放；
-- - status=leased/succeeded 时通常为空。
--
-- 注意：
-- 本文件不直接修改历史 attempt_count，也不自动把已有 failed 记录迁入 DLQ。已有失败记录在下一次 Runner 写回时
-- 会按照新的 maxAttempts/retryBase/retryMax 策略推进，避免 migration 阶段引入业务副作用。
ALTER TABLE agent_memory_materialization_lease
    ADD COLUMN next_retry_at DATETIME(3) DEFAULT NULL COMMENT '失败退避结束时间；未到该时间前 Runner 不自动重新领取，进入 dead_letter 时为空。' AFTER leased_until,
    ADD KEY idx_agent_memory_materialization_lease_retry (status, next_retry_at, leased_until, update_time);
