-- data-sync：脏数据修复重放 selector
--
-- 背景：
-- 1. DataX-style dirty record threshold 允许少量坏数据结构化落盘并继续任务；
-- 2. 商业化产品不能停留在“能看到错误样本”，还要能把修复后的样本纳入受控 replay；
-- 3. recovery plan 已经是 replay/backfill 的执行面契约，因此错误样本重放 selector 应挂在该计划上，
--    而不是塞进 execution.error_summary 或审计 payload。
--
-- 低敏边界：
-- error_sample_selector 只允许保存 errorSampleIds、sampleCount、sourceExecutionId、selectorMode、
-- repairStrategy 等控制信息；禁止保存原始坏行、SQL、连接串、密码、token、where 条件或完整样本 payload。

ALTER TABLE data_sync_execution_recovery_plan
    ADD COLUMN IF NOT EXISTS error_sample_selector TEXT;

COMMENT ON COLUMN data_sync_execution_recovery_plan.error_sample_selector IS
    '脏数据修复重放 selector，只保存错误样本 ID、数量、来源 execution 和修复策略摘要，不保存原始坏行、SQL 或凭据';
