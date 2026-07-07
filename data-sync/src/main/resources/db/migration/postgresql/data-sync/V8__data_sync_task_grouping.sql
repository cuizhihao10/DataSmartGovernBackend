-- data-sync：同步任务分组字段。
--
-- 设计说明：
-- 1. 当前阶段先把 group_code / group_name 放在 data_sync_task 上，形成轻量分组能力；
-- 2. group_code 是稳定编码，用于导入导出、Agent 工具调用、组级查询和后续批量操作；
-- 3. group_name 是展示名，可以被运营人员重命名，不作为唯一键；
-- 4. 后续如果需要组级负责人、并发配额、冻结状态、SLA 或成员审计，可平滑迁移到独立 sync_task_group 表。

SET search_path TO data_sync, public;

ALTER TABLE data_sync_task
    ADD COLUMN IF NOT EXISTS group_code VARCHAR(64),
    ADD COLUMN IF NOT EXISTS group_name VARCHAR(128);

COMMENT ON COLUMN data_sync_task.group_code IS
    '任务分组稳定编码，用于业务域、迁移批次、Agent 编排、导入导出和后续组级批量操作';

COMMENT ON COLUMN data_sync_task.group_name IS
    '任务分组展示名称，用于前端分组卡片、运营台和 Agent 低敏回复，不作为稳定唯一标识';

CREATE INDEX IF NOT EXISTS idx_data_sync_task_group_state
    ON data_sync_task (tenant_id, project_id, group_code, current_state, update_time);

CREATE INDEX IF NOT EXISTS idx_data_sync_task_workspace_group
    ON data_sync_task (tenant_id, workspace_id, group_code, update_time);
