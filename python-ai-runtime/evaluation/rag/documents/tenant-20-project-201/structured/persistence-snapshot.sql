合成声明：DataSmart Govern RAG 评测原创资料，不含真实客户、个人、凭据或生产数据。
-- 精确码：SQL-DB-688
-- 独立锚点：tenant-20-project-201:persistence-snapshot
-- 范围：租户 20 项目 201 合成演示空间
BEGIN;
INSERT INTO synthetic_task_execution (execution_id, task_id, config_version, state, started_at, completed_at) VALUES
  ('EX-tenant-20-project-201-304', 'TASK-1001', 'cfg-v21', 'RECOVERED', '2026-08-15T02:14:00+08:00', '2026-08-15T02:15:02+08:00');
INSERT INTO synthetic_object_ledger (execution_id, object_id, attempt_count, object_state, checkpoint, dirty_records) VALUES
  ('EX-tenant-20-project-201-304', 'shard-07', 2, 'SUCCEEDED', 'offset-318', 0);
INSERT INTO synthetic_recovery_case (case_id, execution_id, cycle, max_cycles, case_state, reason_code) VALUES
  ('RC-tenant-20-project-201-901', 'EX-tenant-20-project-201-304', 1, 5, 'RECOVERED', 'APPROVED_DEFAULT_APPLIED');
INSERT INTO synthetic_evidence_record (case_id, source_uri, observed_at, confidence, confidence_basis) VALUES
  ('RC-tenant-20-project-201-901', 'synthetic://worker-execution.log', '2026-08-15T02:14:07+08:00', 0.99, 'STRUCTURED_LOG_EXACT_ERROR');
COMMIT;
