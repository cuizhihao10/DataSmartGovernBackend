ALTER TABLE datasource_config
    ADD COLUMN owner_id BIGINT NOT NULL DEFAULT 0 COMMENT '数据源所有者 actorId；实例授权可编辑/使用但不能删除，删除仅允许 owner 或项目管理者'
    AFTER workspace_id;

ALTER TABLE datasource_config
    ADD COLUMN created_by BIGINT NOT NULL DEFAULT 0 COMMENT '数据源创建人 actorId；通常等于 owner_id，后续用于代创建或所有权转移审计'
    AFTER owner_id;

CREATE INDEX idx_datasource_owner_project
    ON datasource_config (tenant_id, project_id, owner_id, status, id);

CREATE INDEX idx_datasource_created_by
    ON datasource_config (tenant_id, created_by, id);
