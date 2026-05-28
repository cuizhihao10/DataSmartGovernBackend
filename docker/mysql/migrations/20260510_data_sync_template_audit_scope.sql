-- @Author : Cui
-- @Date: 2026/05/10 13:20
-- @Description DataSmart Govern Backend - data-sync 模板级审计范围迁移
-- @Version:1.0.0

-- ---------------------------------------------------------------------------
-- data-sync 模板级审计范围迁移
-- ---------------------------------------------------------------------------
-- 设计说明：
-- 1. 3.02 已为 data_sync_audit_record 增加 project_id/workspace_id；
-- 2. 3.03 已让任务级审计可以通过 sync_task_id 自动回填项目/空间；
-- 3. 但模板级审计通常没有 sync_task_id，如果没有 template_id，只能把模板 ID 放进 action_payload；
-- 4. 本迁移补 template_id，并尽量从历史 action_payload 中的 `templateId=xxx` 回填历史模板级审计；
-- 5. 后续模板创建、校验、禁用等动作会直接写 template_id/project_id/workspace_id。

SET @schema_name = DATABASE();

SET @sql = IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'data_sync_audit_record' AND column_name = 'template_id'),
    'SELECT 1',
    'ALTER TABLE data_sync_audit_record ADD COLUMN template_id BIGINT COMMENT ''关联同步模板 ID，可为空；模板创建、校验、禁用等动作通常没有 sync_task_id'' AFTER workspace_id'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 尝试回填历史模板审计。格式来自既有代码：templateId=123,syncMode=FULL 或 templateId=123。
UPDATE data_sync_audit_record audit
SET audit.template_id = CAST(
        SUBSTRING_INDEX(
                SUBSTRING_INDEX(audit.action_payload, 'templateId=', -1),
                ',',
                1
        ) AS UNSIGNED
    )
WHERE audit.template_id IS NULL
  AND audit.sync_task_id IS NULL
  AND audit.action_payload LIKE '%templateId=%';

-- 根据模板表回填模板级审计的项目/空间范围。
UPDATE data_sync_audit_record audit
JOIN data_sync_template template ON template.id = audit.template_id
SET audit.project_id = COALESCE(audit.project_id, template.project_id),
    audit.workspace_id = COALESCE(audit.workspace_id, template.workspace_id)
WHERE audit.template_id IS NOT NULL;

SET @sql = IF(
    EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema = @schema_name AND table_name = 'data_sync_audit_record' AND index_name = 'idx_data_sync_audit_template_time'),
    'SELECT 1',
    'ALTER TABLE data_sync_audit_record ADD INDEX idx_data_sync_audit_template_time (template_id, create_time)'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
