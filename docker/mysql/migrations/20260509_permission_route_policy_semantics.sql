-- ---------------------------------------------------------------------------
-- DataSmart Govern permission-admin 迁移脚本
-- 版本：20260509_permission_route_policy_semantics
-- ---------------------------------------------------------------------------
-- 背景说明：
-- docker/mysql/init/permission-admin.sql 只会在 MySQL 容器第一次初始化数据目录时执行。
-- 如果开发机或客户环境已经存在 mysql_data 数据卷，直接升级 Java 代码后，
-- permission-admin 会尝试读写 permission_route_policy.resource_type 和 action，
-- 但旧表里还没有这两列，最终会在运行时出现 Unknown column。
--
-- 因此本脚本用于“已有数据库升级”场景：
-- 1. 为 permission_route_policy 增加 resource_type 和 action；
-- 2. 补充语义索引，降低后续按资源/动作筛选和矩阵查询成本；
-- 3. 将已有种子策略补上资源类型和关键动作；
-- 4. 插入 data-sync 的动作级显式策略。
--
-- 使用方式：
-- mysql -uroot -ppassword datasmart_govern < docker/mysql/migrations/20260509_permission_route_policy_semantics.sql
--
-- 注意：
-- 该脚本设计为幂等迁移，多次执行不会重复添加列或重复插入策略。
-- ---------------------------------------------------------------------------

USE datasmart_govern;

DELIMITER $$

CREATE PROCEDURE migrate_permission_route_policy_semantics()
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'permission_route_policy'
          AND COLUMN_NAME = 'resource_type'
    ) THEN
        ALTER TABLE permission_route_policy
            ADD COLUMN resource_type VARCHAR(64)
                COMMENT '业务资源类型，例如 SYNC_TASK、SYNC_EXECUTION、SYNC_INCIDENT；为空表示路径级通配策略'
                AFTER path_pattern;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'permission_route_policy'
          AND COLUMN_NAME = 'action'
    ) THEN
        ALTER TABLE permission_route_policy
            ADD COLUMN action VARCHAR(64)
                COMMENT '业务动作，例如 VIEW、CREATE、CALLBACK、RECOVER；为空表示动作级通配策略'
                AFTER resource_type;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM INFORMATION_SCHEMA.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'permission_route_policy'
          AND INDEX_NAME = 'idx_permission_route_semantic'
    ) THEN
        CREATE INDEX idx_permission_route_semantic
            ON permission_route_policy (tenant_id, role_code, resource_type, action, enabled, priority);
    END IF;
END$$

DELIMITER ;

CALL migrate_permission_route_policy_semantics();
DROP PROCEDURE migrate_permission_route_policy_semantics;

UPDATE permission_route_policy
SET resource_type = 'DATASOURCE'
WHERE resource_type IS NULL AND path_pattern LIKE '/api/datasource/%';

UPDATE permission_route_policy
SET resource_type = 'TASK_OPERATION'
WHERE resource_type IS NULL AND path_pattern LIKE '/api/task/operations/%';

UPDATE permission_route_policy
SET resource_type = 'TASK'
WHERE resource_type IS NULL AND path_pattern LIKE '/api/task/%';

UPDATE permission_route_policy
SET resource_type = 'QUALITY_RULE'
WHERE resource_type IS NULL AND path_pattern LIKE '/api/quality/%';

UPDATE permission_route_policy
SET resource_type = 'SYSTEM_SETTING'
WHERE resource_type IS NULL AND path_pattern LIKE '/api/permission/%';

UPDATE permission_route_policy
SET resource_type = 'AUDIT_LOG'
WHERE resource_type IS NULL AND path_pattern LIKE '/api/observability/%';

UPDATE permission_route_policy
SET resource_type = 'SYNC_TEMPLATE'
WHERE resource_type IS NULL AND path_pattern LIKE '/api/sync/sync-templates%';

UPDATE permission_route_policy
SET resource_type = 'SYNC_EXECUTION'
WHERE resource_type IS NULL
  AND (path_pattern LIKE '/api/sync/sync-executions%' OR path_pattern LIKE '/api/sync/sync-tasks/%/executions/%');

UPDATE permission_route_policy
SET resource_type = 'SYNC_INCIDENT'
WHERE resource_type IS NULL AND path_pattern LIKE '/api/sync/sync-incidents%';

UPDATE permission_route_policy
SET resource_type = 'SYNC_OPERATION'
WHERE resource_type IS NULL AND path_pattern LIKE '/api/sync/sync-tasks/%/attention%';

UPDATE permission_route_policy
SET resource_type = 'SYNC_TASK'
WHERE resource_type IS NULL AND path_pattern LIKE '/api/sync/%';

UPDATE permission_route_policy
SET action = CASE
    WHEN http_method = 'GET' THEN 'VIEW'
    WHEN http_method = 'POST' AND path_pattern LIKE '%/validate' THEN 'VALIDATE'
    WHEN http_method = 'POST' AND path_pattern LIKE '%/run' THEN 'RUN'
    WHEN http_method = 'POST' AND path_pattern LIKE '/api/sync/sync-tasks/%/executions/%' THEN 'CALLBACK'
    WHEN http_method = 'POST' AND path_pattern = '/api/sync/sync-executions/recover-expired-leases' THEN 'RECOVER'
    WHEN http_method = 'POST' THEN 'CREATE'
    WHEN http_method IN ('PUT', 'PATCH') THEN 'UPDATE'
    WHEN http_method = 'DELETE' THEN 'DELETE'
    ELSE action
END
WHERE action IS NULL
  AND http_method IN ('GET', 'POST', 'PUT', 'PATCH', 'DELETE');

INSERT IGNORE INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled, description, create_time, update_time)
VALUES
(0, '运营人员确认同步事故', 'OPERATOR', 'POST', '/api/sync/sync-incidents/*/acknowledge', 'SYNC_INCIDENT', 'ACKNOWLEDGE', 'ALLOW', 780, 1, '运营人员可确认同步事故已经接手，形成事故责任链起点。', NOW(), NOW()),
(0, '运营人员分派同步事故', 'OPERATOR', 'POST', '/api/sync/sync-incidents/*/assign', 'SYNC_INCIDENT', 'ASSIGN', 'ALLOW', 780, 1, '运营人员可把同步事故分派给具体负责人，避免事故无人跟进。', NOW(), NOW()),
(0, '运营人员解决同步事故', 'OPERATOR', 'POST', '/api/sync/sync-incidents/*/resolve', 'SYNC_INCIDENT', 'RESOLVE', 'ALLOW', 780, 1, '运营人员可标记同步事故已解决，但关闭仍会留下独立审计动作。', NOW(), NOW()),
(0, '运营人员关闭同步事故', 'OPERATOR', 'POST', '/api/sync/sync-incidents/*/close', 'SYNC_INCIDENT', 'CLOSE', 'ALLOW', 780, 1, '运营人员可关闭已解决事故，后续 P1/P2 可升级为审批动作。', NOW(), NOW()),
(0, '运营人员确认同步人工介入', 'OPERATOR', 'POST', '/api/sync/sync-tasks/*/attention/acknowledge', 'SYNC_OPERATION', 'ACKNOWLEDGE', 'ALLOW', 780, 1, '运营人员可确认人工介入任务已经接手。', NOW(), NOW()),
(0, '运营人员重跑同步人工介入任务', 'OPERATOR', 'POST', '/api/sync/sync-tasks/*/attention/rerun', 'SYNC_OPERATION', 'RETRY', 'ALLOW', 780, 1, '运营人员可在处理人工介入后重新入队执行同步任务。', NOW(), NOW()),
(0, '运营人员取消同步人工介入任务', 'OPERATOR', 'POST', '/api/sync/sync-tasks/*/attention/cancel', 'SYNC_OPERATION', 'CANCEL', 'ALLOW', 780, 1, '运营人员可取消无法继续执行的人工介入任务。', NOW(), NOW()),
(0, '服务账号认领同步执行', 'SERVICE_ACCOUNT', 'POST', '/api/sync/sync-executions/claim', 'SYNC_EXECUTION', 'CLAIM', 'ALLOW', 800, 1, '服务账号可代表受控 worker 认领下一条同步 execution。', NOW(), NOW()),
(0, '服务账号同步执行心跳', 'SERVICE_ACCOUNT', 'POST', '/api/sync/sync-executions/*/heartbeat', 'SYNC_EXECUTION', 'HEARTBEAT', 'ALLOW', 800, 1, '服务账号可代表受控 worker 续租并上报执行进度。', NOW(), NOW()),
(0, '服务账号同步执行延期', 'SERVICE_ACCOUNT', 'POST', '/api/sync/sync-executions/*/defer', 'SYNC_EXECUTION', 'DEFER', 'ALLOW', 800, 1, '服务账号可因容量、配额或维护窗口将 execution 延迟回队列。', NOW(), NOW()),
(0, '服务账号同步执行回调', 'SERVICE_ACCOUNT', 'POST', '/api/sync/sync-tasks/*/executions/*/**', 'SYNC_EXECUTION', 'CALLBACK', 'ALLOW', 810, 1, '服务账号可提交 start、checkpoint、complete、fail 等执行器回调。', NOW(), NOW()),
(0, '普通用户禁止伪造同步执行回调', 'ORDINARY_USER', 'POST', '/api/sync/sync-tasks/*/executions/*/**', 'SYNC_EXECUTION', 'CALLBACK', 'DENY', 820, 1, '普通用户不能伪造 worker 回调推进执行状态。', NOW(), NOW()),
(0, '项目负责人禁止伪造同步执行回调', 'PROJECT_OWNER', 'POST', '/api/sync/sync-tasks/*/executions/*/**', 'SYNC_EXECUTION', 'CALLBACK', 'DENY', 820, 1, '项目负责人不能伪造 worker 回调推进执行状态。', NOW(), NOW()),
(0, '租户管理员禁止伪造同步执行回调', 'TENANT_ADMINISTRATOR', 'POST', '/api/sync/sync-tasks/*/executions/*/**', 'SYNC_EXECUTION', 'CALLBACK', 'DENY', 820, 1, '租户管理员不能伪造 worker 回调推进执行状态。', NOW(), NOW());
