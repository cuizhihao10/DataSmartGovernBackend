-- ---------------------------------------------------------------------------
-- DataSmart Govern 迁移脚本：permission-admin 项目成员授权关系
-- ---------------------------------------------------------------------------
-- 设计说明：
-- 1. PROJECT 数据范围不能只停留在 `project_id IN ${actorProjectIds}` 字符串表达式；
-- 2. permission-admin 需要有一张稳定的“用户/服务账号 -> 项目”授权事实表，用于把占位符物化成项目 ID 集合；
-- 3. gateway 只透传 permission-admin 的判定结果，不直接查询该表，避免网关耦合权限中心内部结构；
-- 4. data-sync 只消费物化后的项目 ID 集合，并转换为安全的 MyBatis 查询条件；
-- 5. 本脚本幂等创建表和索引，便于已有开发库、测试库和平滑升级。
-- ---------------------------------------------------------------------------

USE datasmart_govern;

CREATE TABLE IF NOT EXISTS permission_project_membership (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '项目成员授权关系主键',
    tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户 ID；项目授权必须带租户边界，避免不同租户 project_id 相同导致串权',
    actor_id BIGINT NOT NULL COMMENT '操作者 ID；可对应用户、服务账号或未来外部身份映射',
    project_id BIGINT NOT NULL COMMENT '项目 ID；permission-admin 会把同一 actor 的项目集合物化为 X-DataSmart-Authorized-Project-Ids',
    workspace_id BIGINT COMMENT '工作空间 ID；为后续空间级数据范围和空间级看板预留',
    project_role VARCHAR(64) NOT NULL DEFAULT 'MEMBER' COMMENT '项目内角色，例如 OWNER、MAINTAINER、VIEWER；后续可影响导出、审批和事故处置',
    grant_source VARCHAR(64) NOT NULL DEFAULT 'MANUAL' COMMENT '授权来源，例如 MANUAL、IMPORT、IDP_GROUP、APPROVAL',
    enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用；成员离开项目时优先禁用以保留历史审计线索',
    create_time DATETIME NOT NULL COMMENT '创建时间',
    update_time DATETIME NOT NULL COMMENT '更新时间',
    UNIQUE KEY uk_permission_project_member (tenant_id, actor_id, project_id),
    INDEX idx_permission_project_actor_enabled (tenant_id, actor_id, enabled, project_id),
    INDEX idx_permission_project_project_role (tenant_id, project_id, project_role, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='项目成员授权关系表';

INSERT IGNORE INTO permission_project_membership
(tenant_id, actor_id, project_id, workspace_id, project_role, grant_source, enabled, create_time, update_time)
VALUES
(0, 1001, 101, 10001, 'OWNER', 'BOOTSTRAP', 1, NOW(), NOW()),
(0, 1001, 102, 10001, 'OWNER', 'BOOTSTRAP', 1, NOW(), NOW()),
(0, 1002, 201, 20001, 'MAINTAINER', 'BOOTSTRAP', 1, NOW(), NOW());
