-- data-sync：同步任务生命周期控制路由策略补充
--
-- 背景：
-- 1. data-sync 已新增普通任务生命周期控制入口：pause、resume、retry、cancel；
-- 2. 旧权限种子中虽然已有 `/api/sync/sync-tasks/**` 通配允许策略，但语义补全过程会把未知 POST 归类为 CREATE；
-- 3. 商业化权限矩阵需要能区分“创建/运行任务”和“暂停/恢复/重试/取消任务”，否则前端按钮权限、审计矩阵和后续审批流都会缺少稳定 action。
--
-- 设计边界：
-- 1. 本迁移只补普通任务生命周期动作，不开放人工介入 attention，也不开放执行器回调；
-- 2. 普通用户与项目负责人仍依赖 data-sync 服务层的 SELF/PROJECT 数据范围二次校验；
-- 3. 运营人员和租户管理员可处理租户内生命周期动作，但“批量强制取消、连接器维护模式、管理员强制重试”仍应另建管理员入口；
-- 4. 服务账号默认不授予这些人工按钮动作，避免 worker 或内部系统绕过人类责任链直接暂停/取消业务任务。

INSERT IGNORE INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled, description, create_time, update_time)
VALUES
(0, '普通用户暂停自己的同步任务', 'ORDINARY_USER', 'POST', '/api/sync/sync-tasks/*/pause', 'SYNC_TASK', 'PAUSE', 'ALLOW', 125, 1, '普通用户可暂停自己数据范围内的同步任务；data-sync 服务层仍按 SELF 数据范围校验任务归属。', NOW(), NOW()),
(0, '普通用户恢复自己的同步任务', 'ORDINARY_USER', 'POST', '/api/sync/sync-tasks/*/resume', 'SYNC_TASK', 'RESUME', 'ALLOW', 125, 1, '普通用户可恢复自己已暂停的同步任务；恢复会创建新的待执行 execution 并写入审计。', NOW(), NOW()),
(0, '普通用户重试自己的同步任务', 'ORDINARY_USER', 'POST', '/api/sync/sync-tasks/*/retry', 'SYNC_TASK', 'RETRY', 'ALLOW', 125, 1, '普通用户可重试自己失败或部分成功的同步任务；人工介入任务不能绕过 attention 流程。', NOW(), NOW()),
(0, '普通用户取消自己的同步任务', 'ORDINARY_USER', 'POST', '/api/sync/sync-tasks/*/cancel', 'SYNC_TASK', 'CANCEL', 'ALLOW', 125, 1, '普通用户可取消自己数据范围内尚未归档的同步任务；执行器回调仍由服务账号协议控制。', NOW(), NOW()),
(0, '项目负责人暂停项目同步任务', 'PROJECT_OWNER', 'POST', '/api/sync/sync-tasks/*/pause', 'SYNC_TASK', 'PAUSE', 'ALLOW', 145, 1, '项目负责人可暂停授权项目内同步任务，用于维护窗口、下游限流或业务冻结。', NOW(), NOW()),
(0, '项目负责人恢复项目同步任务', 'PROJECT_OWNER', 'POST', '/api/sync/sync-tasks/*/resume', 'SYNC_TASK', 'RESUME', 'ALLOW', 145, 1, '项目负责人可恢复授权项目内已暂停任务，服务层继续按 authorizedProjectIds 收口。', NOW(), NOW()),
(0, '项目负责人重试项目同步任务', 'PROJECT_OWNER', 'POST', '/api/sync/sync-tasks/*/retry', 'SYNC_TASK', 'RETRY', 'ALLOW', 145, 1, '项目负责人可重试项目范围内失败或部分成功任务，但不能绕过人工介入处置。', NOW(), NOW()),
(0, '项目负责人取消项目同步任务', 'PROJECT_OWNER', 'POST', '/api/sync/sync-tasks/*/cancel', 'SYNC_TASK', 'CANCEL', 'ALLOW', 145, 1, '项目负责人可取消项目范围内尚未归档的同步任务，取消动作会进入 data-sync 审计。', NOW(), NOW()),
(0, '运营人员暂停同步任务', 'OPERATOR', 'POST', '/api/sync/sync-tasks/*/pause', 'SYNC_TASK', 'PAUSE', 'ALLOW', 780, 1, '运营人员可在容量、故障或维护窗口场景暂停租户内同步任务，避免继续扩大运行风险。', NOW(), NOW()),
(0, '运营人员恢复同步任务', 'OPERATOR', 'POST', '/api/sync/sync-tasks/*/resume', 'SYNC_TASK', 'RESUME', 'ALLOW', 780, 1, '运营人员可在确认故障恢复或维护结束后恢复同步任务。', NOW(), NOW()),
(0, '运营人员重试同步任务', 'OPERATOR', 'POST', '/api/sync/sync-tasks/*/retry', 'SYNC_TASK', 'RETRY', 'ALLOW', 780, 1, '运营人员可对失败或部分成功同步任务发起普通重试；人工介入任务仍走 attention/rerun。', NOW(), NOW()),
(0, '运营人员取消同步任务', 'OPERATOR', 'POST', '/api/sync/sync-tasks/*/cancel', 'SYNC_TASK', 'CANCEL', 'ALLOW', 780, 1, '运营人员可取消无法继续执行或风险过高的普通同步任务；强制批量取消后续应单独建管理员入口。', NOW(), NOW()),
(0, '租户管理员暂停同步任务', 'TENANT_ADMINISTRATOR', 'POST', '/api/sync/sync-tasks/*/pause', 'SYNC_TASK', 'PAUSE', 'ALLOW', 760, 1, '租户管理员可暂停本租户同步任务，适合租户级维护窗口和风险止血。', NOW(), NOW()),
(0, '租户管理员恢复同步任务', 'TENANT_ADMINISTRATOR', 'POST', '/api/sync/sync-tasks/*/resume', 'SYNC_TASK', 'RESUME', 'ALLOW', 760, 1, '租户管理员可恢复本租户已暂停同步任务。', NOW(), NOW()),
(0, '租户管理员重试同步任务', 'TENANT_ADMINISTRATOR', 'POST', '/api/sync/sync-tasks/*/retry', 'SYNC_TASK', 'RETRY', 'ALLOW', 760, 1, '租户管理员可重试本租户失败或部分成功同步任务。', NOW(), NOW()),
(0, '租户管理员取消同步任务', 'TENANT_ADMINISTRATOR', 'POST', '/api/sync/sync-tasks/*/cancel', 'SYNC_TASK', 'CANCEL', 'ALLOW', 760, 1, '租户管理员可取消本租户尚未归档同步任务；执行器回调和人工介入仍由更细策略控制。', NOW(), NOW());
