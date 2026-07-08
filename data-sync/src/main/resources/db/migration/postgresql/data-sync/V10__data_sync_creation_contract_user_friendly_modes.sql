/*
 * data-sync 创建任务合同收口迁移。
 *
 * 本迁移不删除历史执行器策略，也不重写已有模板数据，只把数据库默认值和字段注释调整到当前产品口径：
 * 1. 新建任务/模板面对用户只展示 INSERT、UPDATE 两种写入策略；
 * 2. APPEND、UPSERT、INSERT_IGNORE、REPLACE、OVERWRITE 继续作为历史兼容或执行器内部策略保留；
 * 3. 主键、外键、字段数量、字段类型、对象存在性和 SQL 安全检查应由预检查阶段自动完成，不再要求用户在创建表单手工填写；
 * 4. run_mode、approval_confirmed、approval_fact_id 等概念不属于普通新建任务向导，应由任务发布、执行恢复或高风险运营流程承载。
 */
SET search_path TO data_sync, public;

ALTER TABLE data_sync_template
    ALTER COLUMN write_strategy SET DEFAULT 'INSERT';

COMMENT ON COLUMN data_sync_template.write_strategy IS
    '目标端写入策略。新建任务向导只展示 INSERT 和 UPDATE：INSERT 表示插入写入，UPDATE 表示更新/合并写入；APPEND、UPSERT、INSERT_IGNORE、REPLACE、OVERWRITE 仅作为历史模板或执行器内部兼容策略保留，不应继续暴露在普通创建表单中';

COMMENT ON COLUMN data_sync_template.primary_key_field IS
    '历史兼容字段：主键或冲突判断字段，只保存字段名。新的创建向导不要求用户手填该字段，目标表主键、唯一键、外键和约束应在预检查阶段通过目标端元数据自动判断';

COMMENT ON COLUMN data_sync_template.incremental_field IS
    '历史兼容字段：增量字段，只保存字段名。按主键/按时间增量不再作为新建任务一级传输模式展示，相关窗口或 checkpoint 策略应沉到定期批量、高级策略或运行期恢复流程';

COMMENT ON COLUMN data_sync_task.run_mode IS
    '任务运行方式。新建任务时由 sync_mode 自动推导：SCHEDULED_FULL/SCHEDULED_BATCH 为 SCHEDULED，其他模式默认为 MANUAL；普通创建页面不应让用户手工选择 run_mode';

COMMENT ON COLUMN data_sync_task.approval_state IS
    '审批状态。普通新建任务向导不承载审批确认；高风险 SQL、全库迁移、恢复回放或导出类动作应在发布、执行、恢复或运营专用流程中进入审批策略';
