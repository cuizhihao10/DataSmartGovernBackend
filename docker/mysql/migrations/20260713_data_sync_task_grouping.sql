-- MySQL 兼容迁移：data-sync 同步任务分组字段。
--
-- PostgreSQL 目标迁移见：
-- data-sync/src/main/resources/db/migration/postgresql/data-sync/V8__data_sync_task_grouping.sql
--
-- 本脚本用于仍在 MySQL 兼容库上运行的本地/过渡环境。项目目标数据库仍是 PostgreSQL，
-- 这里不引入 MySQL 专属业务语义，只补齐列、注释和查询索引，避免本地 E2E 或历史环境缺字段。

SET @add_task_group_code = (
    SELECT IF(
        EXISTS (
            SELECT 1
            FROM information_schema.columns
            WHERE table_schema = DATABASE()
              AND table_name = 'data_sync_task'
              AND column_name = 'group_code'
        ),
        'SELECT 1',
        'ALTER TABLE data_sync_task ADD COLUMN group_code VARCHAR(64) COMMENT ''任务分组稳定编码，用于业务域、迁移批次、Agent 编排、导入导出和后续组级批量操作'' AFTER template_id'
    )
);
PREPARE stmt FROM @add_task_group_code;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_task_group_name = (
    SELECT IF(
        EXISTS (
            SELECT 1
            FROM information_schema.columns
            WHERE table_schema = DATABASE()
              AND table_name = 'data_sync_task'
              AND column_name = 'group_name'
        ),
        'SELECT 1',
        'ALTER TABLE data_sync_task ADD COLUMN group_name VARCHAR(128) COMMENT ''任务分组展示名称，用于前端分组卡片、运营台和 Agent 低敏回复，不作为稳定唯一标识'' AFTER group_code'
    )
);
PREPARE stmt FROM @add_task_group_name;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_task_group_state_index = (
    SELECT IF(
        EXISTS (
            SELECT 1
            FROM information_schema.statistics
            WHERE table_schema = DATABASE()
              AND table_name = 'data_sync_task'
              AND index_name = 'idx_data_sync_task_group_state'
        ),
        'SELECT 1',
        'ALTER TABLE data_sync_task ADD INDEX idx_data_sync_task_group_state (tenant_id, project_id, group_code, current_state, update_time)'
    )
);
PREPARE stmt FROM @add_task_group_state_index;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_task_workspace_group_index = (
    SELECT IF(
        EXISTS (
            SELECT 1
            FROM information_schema.statistics
            WHERE table_schema = DATABASE()
              AND table_name = 'data_sync_task'
              AND index_name = 'idx_data_sync_task_workspace_group'
        ),
        'SELECT 1',
        'ALTER TABLE data_sync_task ADD INDEX idx_data_sync_task_workspace_group (tenant_id, workspace_id, group_code, update_time)'
    )
);
PREPARE stmt FROM @add_task_workspace_group_index;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
