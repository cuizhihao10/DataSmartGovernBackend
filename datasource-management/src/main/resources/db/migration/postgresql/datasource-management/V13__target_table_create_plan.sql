SET search_path TO datasource_management, public;

ALTER TABLE datasource_schema_repair_plan
    ALTER COLUMN column_name DROP NOT NULL;

ALTER TABLE datasource_schema_repair_plan
    ADD COLUMN IF NOT EXISTS columns_json TEXT;

ALTER TABLE datasource_schema_repair_plan
    DROP CONSTRAINT IF EXISTS ck_datasource_schema_repair_operation;

ALTER TABLE datasource_schema_repair_plan
    ADD CONSTRAINT ck_datasource_schema_repair_operation CHECK (
        operation IN ('ADD_NULLABLE_COLUMN', 'WIDEN_VARCHAR', 'DROP_NOT_NULL', 'CREATE_TABLE')
    );

COMMENT ON COLUMN datasource_schema_repair_plan.columns_json IS
    'Normalized allow-listed CREATE_TABLE columns. Never stores raw DDL, defaults, expressions, or credentials.';
