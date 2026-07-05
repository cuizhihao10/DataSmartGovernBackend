-- DataSmart Govern Backend - data-sync V2 migration
--
-- 本迁移把 data_sync_template 从“单对象同步模板”推进到“可表达多对象、全 schema、全库迁移和受控自定义 SQL 查询”的产品级配置模型。
-- 这里故意只新增控制面字段，不直接承诺底层 runner 已经全部支持这些范围：
-- 1. sync_scope_type 表示“同步哪些对象”，例如 SINGLE_OBJECT、OBJECT_LIST、SCHEMA_FULL、DATABASE_FULL、CUSTOM_SQL_QUERY；
-- 2. sync_mode 仍然表示“如何同步”，例如 FULL、INCREMENTAL_TIME、CDC_STREAMING、ONE_TIME_MIGRATION；
-- 3. object_mapping_config 保存多对象/全库场景的对象选择和映射合同；
-- 4. custom_sql_config 保存自定义 SQL 查询场景的受控配置合同。
--
-- 商业化安全边界：
-- - 本表不保存数据源账号、密码、连接串、token、样本行数据或 checkpoint 原始值；
-- - custom_sql_config 在生产上应优先保存 statementRef、参数 schema、digest 和审批信息；
-- - 如果需要临时保存 SQL 正文，也必须只允许 SELECT/WITH 只读查询，并禁止在日志、审计摘要、普通 API 响应和 worker plan 中回显。

ALTER TABLE data_sync_template
    ADD COLUMN IF NOT EXISTS sync_scope_type VARCHAR(64) NOT NULL DEFAULT 'SINGLE_OBJECT';

ALTER TABLE data_sync_template
    ADD COLUMN IF NOT EXISTS object_mapping_config TEXT;

ALTER TABLE data_sync_template
    ADD COLUMN IF NOT EXISTS custom_sql_config TEXT;

COMMENT ON COLUMN data_sync_template.sync_scope_type IS '同步范围类型。SINGLE_OBJECT 表示单表/单对象；OBJECT_LIST 表示勾选多对象；SCHEMA_FULL/DATABASE_FULL 表示更大范围迁移；CUSTOM_SQL_QUERY 表示受控只读 SQL 查询结果同步。该字段只描述同步哪些对象，不替代 sync_mode 的执行模式语义';
COMMENT ON COLUMN data_sync_template.object_mapping_config IS '对象映射配置 JSON 文本，用于 OBJECT_LIST、SCHEMA_FULL、DATABASE_FULL 等范围声明 include/exclude、源对象到目标对象映射和目标命名策略；普通列表和审计响应只应展示低敏摘要，不返回 JSON 正文';
COMMENT ON COLUMN data_sync_template.custom_sql_config IS '自定义 SQL 查询同步配置 JSON 文本，只用于 CUSTOM_SQL_QUERY 场景；生产建议保存 statementRef、参数 schema、digest 和审批信息，禁止在普通响应、日志和审计摘要中泄露 SQL 正文';

CREATE INDEX IF NOT EXISTS idx_data_sync_template_scope_mode
    ON data_sync_template (tenant_id, sync_scope_type, sync_mode, enabled);
