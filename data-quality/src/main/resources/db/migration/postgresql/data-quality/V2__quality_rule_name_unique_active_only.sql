SET search_path TO data_quality, public;

ALTER TABLE quality_rule
    DROP CONSTRAINT IF EXISTS uk_quality_rule_tenant_project_name;

CREATE UNIQUE INDEX IF NOT EXISTS uk_quality_rule_tenant_project_name_active
    ON quality_rule (tenant_id, project_id, name)
    WHERE status NOT IN ('ARCHIVED', 'DELETED');
