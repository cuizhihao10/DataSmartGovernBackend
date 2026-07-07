-- permission-admin：data-sync 任务管理生命周期路由策略。
--
-- 背景：
-- 1. gateway 已把 manual-dispatch、terminate、offline、recycle、hard-delete、clone 显式映射为
--    MANUAL_DISPATCH、MANUAL_TERMINATE、OFFLINE、RECYCLE、HARD_DELETE、CLONE 动作。
-- 2. 如果 permission-admin 不补精确策略，这些 POST 路由可能被旧的宽泛 /api/sync/** 策略覆盖，
--    审计里只能看到粗粒度 CREATE/RUN，无法支撑商用产品的权限复核和事故复盘。
-- 3. 删除任务必须按“下线 -> 回收站 -> 彻底删除”推进。彻底删除当前是逻辑 DELETED，
--    但仍属于高影响管理动作，因此默认不给 ORDINARY_USER。

SET search_path TO permission_admin, public;

INSERT INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled, description, create_time, update_time)
VALUES
(0, '普通用户手工调度自己的同步任务', 'ORDINARY_USER', 'POST', '/api/sync/sync-tasks/*/manual-dispatch', 'SYNC_TASK', 'MANUAL_DISPATCH', 'ALLOW', 127, TRUE, '普通用户可在 SELF 数据范围内立即执行一次自己的同步任务；服务层仍会做任务归属和执行前预检。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '普通用户手工结束自己的同步任务', 'ORDINARY_USER', 'POST', '/api/sync/sync-tasks/*/terminate', 'SYNC_TASK', 'MANUAL_TERMINATE', 'ALLOW', 127, TRUE, '普通用户可结束自己正在排队、运行、重试或暂停中的同步任务；worker 后续会收到停止信号。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '普通用户下线自己的同步任务', 'ORDINARY_USER', 'POST', '/api/sync/sync-tasks/*/offline', 'SYNC_TASK', 'OFFLINE', 'ALLOW', 127, TRUE, '普通用户可下线自己的非活跃同步任务，关闭后续自动调度。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '普通用户删除自己的同步任务到回收站', 'ORDINARY_USER', 'POST', '/api/sync/sync-tasks/*/recycle', 'SYNC_TASK', 'RECYCLE', 'ALLOW', 127, TRUE, '普通用户可把已下线的自有同步任务移入回收站，但不能直接彻底删除。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '普通用户克隆自己的同步任务', 'ORDINARY_USER', 'POST', '/api/sync/sync-tasks/*/clone', 'SYNC_TASK', 'CLONE', 'ALLOW', 127, TRUE, '普通用户可克隆自有同步任务为新的 DRAFT 草稿，克隆不会复制执行历史和审批事实。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),

(0, '项目负责人手工调度项目同步任务', 'PROJECT_OWNER', 'POST', '/api/sync/sync-tasks/*/manual-dispatch', 'SYNC_TASK', 'MANUAL_DISPATCH', 'ALLOW', 148, TRUE, '项目负责人可在授权项目内立即执行一次同步任务，用于验证修复、临时补跑或业务窗口触发。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '项目负责人手工结束项目同步任务', 'PROJECT_OWNER', 'POST', '/api/sync/sync-tasks/*/terminate', 'SYNC_TASK', 'MANUAL_TERMINATE', 'ALLOW', 148, TRUE, '项目负责人可手工结束授权项目内活跃同步任务，避免异常同步继续扩大影响。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '项目负责人下线项目同步任务', 'PROJECT_OWNER', 'POST', '/api/sync/sync-tasks/*/offline', 'SYNC_TASK', 'OFFLINE', 'ALLOW', 148, TRUE, '项目负责人可下线授权项目内同步任务，下线会关闭自动调度并作为删除前置步骤。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '项目负责人删除项目同步任务到回收站', 'PROJECT_OWNER', 'POST', '/api/sync/sync-tasks/*/recycle', 'SYNC_TASK', 'RECYCLE', 'ALLOW', 148, TRUE, '项目负责人可把已下线的项目同步任务移入回收站。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '项目负责人彻底删除回收站同步任务', 'PROJECT_OWNER', 'POST', '/api/sync/sync-tasks/*/hard-delete', 'SYNC_TASK', 'HARD_DELETE', 'ALLOW', 148, TRUE, '项目负责人可对授权项目内回收站任务执行逻辑彻底删除，历史审计证据仍按保留策略保存。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '项目负责人克隆项目同步任务', 'PROJECT_OWNER', 'POST', '/api/sync/sync-tasks/*/clone', 'SYNC_TASK', 'CLONE', 'ALLOW', 148, TRUE, '项目负责人可克隆项目同步任务，复用成熟配置创建新的编辑中草稿或立即执行一次。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),

(0, '运营人员手工调度同步任务', 'OPERATOR', 'POST', '/api/sync/sync-tasks/*/manual-dispatch', 'SYNC_TASK', 'MANUAL_DISPATCH', 'ALLOW', 784, TRUE, '运营人员可在租户范围内按事故修复或运维窗口立即调度一次同步任务。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '运营人员手工结束同步任务', 'OPERATOR', 'POST', '/api/sync/sync-tasks/*/terminate', 'SYNC_TASK', 'MANUAL_TERMINATE', 'ALLOW', 784, TRUE, '运营人员可在风险止血场景手工结束活跃同步任务。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '运营人员下线同步任务', 'OPERATOR', 'POST', '/api/sync/sync-tasks/*/offline', 'SYNC_TASK', 'OFFLINE', 'ALLOW', 784, TRUE, '运营人员可下线租户内同步任务，用于故障隔离、容量保护或客户退订。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '运营人员删除同步任务到回收站', 'OPERATOR', 'POST', '/api/sync/sync-tasks/*/recycle', 'SYNC_TASK', 'RECYCLE', 'ALLOW', 784, TRUE, '运营人员可把已下线任务移入回收站，并保留后续查看与克隆能力。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '运营人员彻底删除回收站同步任务', 'OPERATOR', 'POST', '/api/sync/sync-tasks/*/hard-delete', 'SYNC_TASK', 'HARD_DELETE', 'ALLOW', 784, TRUE, '运营人员可按租户治理流程对回收站任务执行逻辑彻底删除。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '运营人员克隆同步任务', 'OPERATOR', 'POST', '/api/sync/sync-tasks/*/clone', 'SYNC_TASK', 'CLONE', 'ALLOW', 784, TRUE, '运营人员可克隆同步任务用于问题复现、修复验证或新任务草拟。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),

(0, '租户管理员手工调度同步任务', 'TENANT_ADMINISTRATOR', 'POST', '/api/sync/sync-tasks/*/manual-dispatch', 'SYNC_TASK', 'MANUAL_DISPATCH', 'ALLOW', 764, TRUE, '租户管理员可在本租户范围内立即执行一次同步任务。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '租户管理员手工结束同步任务', 'TENANT_ADMINISTRATOR', 'POST', '/api/sync/sync-tasks/*/terminate', 'SYNC_TASK', 'MANUAL_TERMINATE', 'ALLOW', 764, TRUE, '租户管理员可手工结束本租户活跃同步任务。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '租户管理员下线同步任务', 'TENANT_ADMINISTRATOR', 'POST', '/api/sync/sync-tasks/*/offline', 'SYNC_TASK', 'OFFLINE', 'ALLOW', 764, TRUE, '租户管理员可下线本租户同步任务并关闭自动调度。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '租户管理员删除同步任务到回收站', 'TENANT_ADMINISTRATOR', 'POST', '/api/sync/sync-tasks/*/recycle', 'SYNC_TASK', 'RECYCLE', 'ALLOW', 764, TRUE, '租户管理员可把本租户已下线任务移入回收站。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '租户管理员彻底删除回收站同步任务', 'TENANT_ADMINISTRATOR', 'POST', '/api/sync/sync-tasks/*/hard-delete', 'SYNC_TASK', 'HARD_DELETE', 'ALLOW', 764, TRUE, '租户管理员可对本租户回收站任务执行逻辑彻底删除。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '租户管理员克隆同步任务', 'TENANT_ADMINISTRATOR', 'POST', '/api/sync/sync-tasks/*/clone', 'SYNC_TASK', 'CLONE', 'ALLOW', 764, TRUE, '租户管理员可克隆本租户同步任务，用于任务复制、模板化运营和问题复现。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),

(0, '普通用户禁止彻底删除同步任务', 'ORDINARY_USER', 'POST', '/api/sync/sync-tasks/*/hard-delete', 'SYNC_TASK', 'HARD_DELETE', 'DENY', 840, TRUE, '彻底删除属于高影响治理动作，普通用户即使拥有任务也不能绕过回收站治理直接逻辑删除。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '审计员禁止写同步任务生命周期', 'AUDITOR', 'POST', '/api/sync/sync-tasks/*/manual-dispatch', 'SYNC_TASK', 'MANUAL_DISPATCH', 'DENY', 900, TRUE, '审计员只能只读查看同步证据，不能触发手工调度。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '审计员禁止手工结束同步任务', 'AUDITOR', 'POST', '/api/sync/sync-tasks/*/terminate', 'SYNC_TASK', 'MANUAL_TERMINATE', 'DENY', 900, TRUE, '审计员只能复核，不能结束运行中的同步任务。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '审计员禁止下线同步任务', 'AUDITOR', 'POST', '/api/sync/sync-tasks/*/offline', 'SYNC_TASK', 'OFFLINE', 'DENY', 900, TRUE, '审计员不能下线同步任务。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '审计员禁止删除同步任务到回收站', 'AUDITOR', 'POST', '/api/sync/sync-tasks/*/recycle', 'SYNC_TASK', 'RECYCLE', 'DENY', 900, TRUE, '审计员不能删除同步任务到回收站。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '审计员禁止彻底删除同步任务', 'AUDITOR', 'POST', '/api/sync/sync-tasks/*/hard-delete', 'SYNC_TASK', 'HARD_DELETE', 'DENY', 900, TRUE, '审计员不能彻底删除同步任务。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '审计员禁止克隆同步任务', 'AUDITOR', 'POST', '/api/sync/sync-tasks/*/clone', 'SYNC_TASK', 'CLONE', 'DENY', 900, TRUE, '审计员不能克隆同步任务。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),

(0, '服务账号禁止人工调度同步任务', 'SERVICE_ACCOUNT', 'POST', '/api/sync/sync-tasks/*/manual-dispatch', 'SYNC_TASK', 'MANUAL_DISPATCH', 'DENY', 910, TRUE, '服务账号不应调用人工调度入口；机器调度应使用 internal scheduler 或 worker-loop 协议。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '服务账号禁止人工管理同步任务生命周期', 'SERVICE_ACCOUNT', 'POST', '/api/sync/sync-tasks/*/terminate', 'SYNC_TASK', 'MANUAL_TERMINATE', 'DENY', 910, TRUE, '服务账号不应手工结束用户任务，避免机器身份绕过人工确认。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '服务账号禁止下线同步任务', 'SERVICE_ACCOUNT', 'POST', '/api/sync/sync-tasks/*/offline', 'SYNC_TASK', 'OFFLINE', 'DENY', 910, TRUE, '服务账号不应调用人工下线入口。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '服务账号禁止删除同步任务到回收站', 'SERVICE_ACCOUNT', 'POST', '/api/sync/sync-tasks/*/recycle', 'SYNC_TASK', 'RECYCLE', 'DENY', 910, TRUE, '服务账号不应调用人工删除入口。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '服务账号禁止彻底删除同步任务', 'SERVICE_ACCOUNT', 'POST', '/api/sync/sync-tasks/*/hard-delete', 'SYNC_TASK', 'HARD_DELETE', 'DENY', 910, TRUE, '服务账号不应调用人工彻底删除入口。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '服务账号禁止克隆同步任务', 'SERVICE_ACCOUNT', 'POST', '/api/sync/sync-tasks/*/clone', 'SYNC_TASK', 'CLONE', 'DENY', 910, TRUE, '服务账号不应调用人工克隆入口。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT DO NOTHING;
