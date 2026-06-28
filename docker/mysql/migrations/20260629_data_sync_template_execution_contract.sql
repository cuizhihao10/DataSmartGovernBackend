-- data-sync：同步模板执行必需低敏契约
--
-- 背景：
-- 1. data-sync 已经具备模板、任务、execution、checkpoint、worker lease 与 workerPlan；
-- 2. 但模板只有 datasourceId、connectorType、syncMode 和若干 JSON 配置块时，真实 worker 无法稳定判断“读哪个对象、写哪个对象、如何处理冲突、用哪个字段推进 checkpoint”；
-- 3. 本迁移补齐源/目标对象定位、写入策略、冲突键和增量字段，让后续 batch runner 能围绕明确契约执行，而不是从 JSON 或人工约定里猜测。
--
-- 安全边界：
-- 1. 本迁移只新增低敏控制面字段，不保存 JDBC URL、host、port、账号、密钥、SQL、where 条件、样本数据、checkpoint 原始值、内部 endpoint 或完整文件路径；
-- 2. 对象名和字段名虽然不是凭据，但也可能透露业务域，因此普通预览、workerPlan、审计摘要应优先返回“是否声明”而不是正文；
-- 3. 历史模板允许列为空，服务层会在创建任务、执行前预览和 workerPlan 中给出 BLOCKED/NEEDS_REVIEW，不在数据库迁移阶段强行填充猜测值。

SET @schema_name = DATABASE();

SET @ddl = IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'data_sync_template' AND column_name = 'source_schema_name'),
    'SELECT 1',
    'ALTER TABLE data_sync_template ADD COLUMN source_schema_name VARCHAR(128) COMMENT ''源端 schema 名称，属于低敏对象定位元数据；不保存连接地址、账号、密钥或 SQL 条件'' AFTER target_datasource_id'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'data_sync_template' AND column_name = 'source_object_name'),
    'SELECT 1',
    'ALTER TABLE data_sync_template ADD COLUMN source_object_name VARCHAR(128) COMMENT ''源端对象名称，例如表、视图、topic 或逻辑资源名；真实执行器必须据此确定读取对象'' AFTER source_schema_name'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'data_sync_template' AND column_name = 'target_schema_name'),
    'SELECT 1',
    'ALTER TABLE data_sync_template ADD COLUMN target_schema_name VARCHAR(128) COMMENT ''目标端 schema 名称，属于低敏对象定位元数据；不保存连接地址、账号、密钥或 SQL 条件'' AFTER source_object_name'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'data_sync_template' AND column_name = 'target_object_name'),
    'SELECT 1',
    'ALTER TABLE data_sync_template ADD COLUMN target_object_name VARCHAR(128) COMMENT ''目标端对象名称，例如表、topic、文件逻辑对象或 API 资源名；真实执行器必须据此确定写入对象'' AFTER target_schema_name'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'data_sync_template' AND column_name = 'write_strategy'),
    'SELECT 1',
    'ALTER TABLE data_sync_template ADD COLUMN write_strategy VARCHAR(64) NOT NULL DEFAULT ''APPEND'' COMMENT ''目标端写入策略：APPEND、UPSERT、INSERT_IGNORE、REPLACE、OVERWRITE；影响幂等、冲突处理、回放和补数'' AFTER sync_mode'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'data_sync_template' AND column_name = 'primary_key_field'),
    'SELECT 1',
    'ALTER TABLE data_sync_template ADD COLUMN primary_key_field VARCHAR(128) COMMENT ''主键或冲突判断字段，用于 UPSERT、INSERT_IGNORE、REPLACE 等幂等写入策略；只保存字段名不保存字段值'' AFTER write_strategy'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'data_sync_template' AND column_name = 'incremental_field'),
    'SELECT 1',
    'ALTER TABLE data_sync_template ADD COLUMN incremental_field VARCHAR(128) COMMENT ''增量字段，用于 INCREMENTAL_TIME 或 INCREMENTAL_ID 模式推进 checkpoint；不保存 checkpoint 原始值'' AFTER primary_key_field'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema = @schema_name AND table_name = 'data_sync_template' AND index_name = 'idx_data_sync_template_object_pair'),
    'SELECT 1',
    'ALTER TABLE data_sync_template ADD INDEX idx_data_sync_template_object_pair (tenant_id, source_object_name, target_object_name)'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema = @schema_name AND table_name = 'data_sync_template' AND index_name = 'idx_data_sync_template_write_strategy'),
    'SELECT 1',
    'ALTER TABLE data_sync_template ADD INDEX idx_data_sync_template_write_strategy (tenant_id, write_strategy, sync_mode)'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
