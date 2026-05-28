-- DataSmart Govern - permission_project_membership 管理 API 权限策略补充
--
-- 背景：
-- 1. permission_project_membership 表已经是 PROJECT 数据范围的事实来源；
-- 2. datasource-management、data-sync、data-quality 会消费授权项目集合；
-- 3. 因此项目成员授权管理本身也必须进入 route policy 矩阵，不能只靠服务层代码兜底；
-- 4. 本迁移只补充策略事实，不改变表结构，可安全用于已有数据库升级。

INSERT IGNORE INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled, description, create_time, update_time)
VALUES
(0, '运营人员查看项目成员授权', 'OPERATOR', 'GET', '/api/permission/project-memberships/**', 'PROJECT_MEMBERSHIP', 'VIEW', 'ALLOW', 137, 1, '运营人员可只读查看项目成员授权，用于排查 PROJECT 数据范围为空、项目授权缺失或授权来源异常。', NOW(), NOW()),
(0, '项目负责人查看项目成员', 'PROJECT_OWNER', 'GET', '/api/permission/project-memberships/**', 'PROJECT_MEMBERSHIP', 'VIEW', 'ALLOW', 145, 1, '项目负责人可查看自己拥有 OWNER 授权的项目成员，服务层继续限制项目范围。', NOW(), NOW()),
(0, '项目负责人维护项目成员', 'PROJECT_OWNER', 'POST', '/api/permission/project-memberships/**', 'PROJECT_MEMBERSHIP', 'CREATE', 'ALLOW', 145, 1, '项目负责人可维护自己负责项目的成员，但不能授予 OWNER 或跨项目操作。', NOW(), NOW()),
(0, '项目负责人更新项目成员', 'PROJECT_OWNER', 'PUT', '/api/permission/project-memberships/**', 'PROJECT_MEMBERSHIP', 'UPDATE', 'ALLOW', 145, 1, '项目负责人可更新自己负责项目的非 OWNER 成员关系，服务层继续执行细粒度校验。', NOW(), NOW()),
(0, '审计员查看项目成员授权', 'AUDITOR', 'GET', '/api/permission/project-memberships/**', 'PROJECT_MEMBERSHIP', 'VIEW', 'ALLOW', 110, 1, '审计员可只读查看项目成员授权，用于授权来源和越权排查。', NOW(), NOW());

UPDATE permission_route_policy
SET resource_type = 'PROJECT_MEMBERSHIP',
    action = CASE
        WHEN http_method = 'GET' THEN 'VIEW'
        WHEN http_method = 'POST' AND path_pattern LIKE '%/batch-upsert' THEN 'IMPORT'
        WHEN http_method = 'POST' AND path_pattern LIKE '%/enable' THEN 'ENABLE'
        WHEN http_method = 'POST' AND path_pattern LIKE '%/disable' THEN 'DISABLE'
        WHEN http_method = 'POST' THEN 'CREATE'
        WHEN http_method IN ('PUT', 'PATCH') THEN 'UPDATE'
        ELSE action
    END
WHERE path_pattern LIKE '/api/permission/project-memberships/%'
   OR path_pattern = '/api/permission/project-memberships/**';
