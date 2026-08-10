-- 专业 Agent turn 事实的专用路由权限策略。
--
-- 这张事实表保存的是模型/专业 Agent 已完成工作的低敏审计摘要，不保存 prompt、思维链、SQL、工具参数、
-- 凭据、样本数据或模型原文。权限策略只解决“谁能进入哪个 HTTP 入口”，agent-runtime 仍会在 Controller、
-- Service 和 JDBC Store 三层校验 tenant_id、project_id、user_id、agent_id 以及 delegation 责任链。
--
-- 本迁移只使用租户、应用和项目已有的权限模型，不新增或依赖 workspace 层级。专业事实的项目边界由现有
-- AI_RUNTIME 数据范围策略和 permission_project_membership 物化的授权项目集合共同表达。
--
-- 角色边界明确如下：
-- 1. ORDINARY_USER/PROJECT_OWNER 的专业事实入口是 SELF + VIEW，只能查看本人当前项目的事实；
-- 2. TENANT_ADMINISTRATOR/AUDITOR/OPERATOR 不在本迁移中自动获得该入口的 VIEW。三类角色已有的
--    Agent runtime-events 查询入口保持原有 VIEW_EVENTS/DIAGNOSE 语义；如果未来需要跨用户事实审计，
--    应另建审计路由并显式配置 TENANT/PROJECT 数据范围，不能把普通用户入口直接扩大；
-- 3. PLATFORM_ADMINISTRATOR 的既有 /api/** 路由兜底仍可命中 GET，但没有显式 AI_RUNTIME 数据范围时，
--    Java agent-runtime 只能按当前可信 tenant_id/project_id/actor_id 做对象级收口，不能把角色名当作全量范围；
-- 4. 人类主体 POST 使用 /specialist-turn-facts/** 的高优先级 DENY。PathPatternParser 的 /** 能匹配无尾
--    斜杠集合根路径，因此它可以覆盖与 SERVICE_ACCOUNT exact allow 相同的 POST 基路径。
SET search_path TO permission_admin, public;

-- 普通用户和项目负责人只能读取本人当前项目的 session/run 专业事实。
-- session 与 run 使用独立路径，避免未来某一类查询被宽泛的会话列表策略意外吞掉。
INSERT INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled,
 description, create_time, update_time)
VALUES
(0, '普通用户查看本人专业 Agent session 事实', 'ORDINARY_USER', 'GET',
 '/api/agent/specialist-turn-facts/sessions/*', 'AI_RUNTIME', 'VIEW', 'ALLOW', 978, TRUE,
 '只允许读取当前用户在当前授权项目中的专业 Agent session turn 摘要；agent-runtime 继续执行租户、项目和 actor 二次校验。',
 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '普通用户查看本人专业 Agent run 事实', 'ORDINARY_USER', 'GET',
 '/api/agent/specialist-turn-facts/runs/*', 'AI_RUNTIME', 'VIEW', 'ALLOW', 978, TRUE,
 '只允许读取当前用户在当前授权项目中的专业 Agent run turn 摘要；runId 不是授权凭据。',
 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '项目负责人查看本人专业 Agent session 事实', 'PROJECT_OWNER', 'GET',
 '/api/agent/specialist-turn-facts/sessions/*', 'AI_RUNTIME', 'VIEW', 'ALLOW', 988, TRUE,
 '项目负责人使用本人 Agent 会话时仍按 SELF 范围读取，项目角色不会自动接管其他成员的 session。',
 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '项目负责人查看本人专业 Agent run 事实', 'PROJECT_OWNER', 'GET',
 '/api/agent/specialist-turn-facts/runs/*', 'AI_RUNTIME', 'VIEW', 'ALLOW', 988, TRUE,
 '项目负责人只能读取本人当前项目的专业 Agent run 摘要，项目审计读取不通过该用户入口开放。',
 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),

-- 事实登记是机器控制面动作，不是普通用户可调用的“创建事实”接口。
-- SERVICE_ACCOUNT 的允许策略仍会叠加 Gateway 主体类型校验和 agent-runtime 的共享凭证校验。
(0, '受信 Agent Runtime 登记专业 Agent turn 事实', 'SERVICE_ACCOUNT', 'POST',
 '/api/agent/specialist-turn-facts', 'AI_RUNTIME', 'EXECUTE', 'ALLOW', 989, TRUE,
 '仅允许受信服务登记低敏专业 Agent turn；必须同时满足 SERVICE_ACCOUNT 主体、来源服务白名单、内部共享凭证和事实归属一致性。',
 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT DO NOTHING;

-- 显式拒绝人类角色的事实登记入口。
--
-- “没有 ALLOW 就默认拒绝”本身已经安全，但这里保留高优先级 DENY 是为了防止未来新增 /api/agent/**
-- 或平台级通配 ALLOW 后把 POST 误放行。DENY 只覆盖 POST，不影响这些角色读取自己的 session/run。
INSERT INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled,
 description, create_time, update_time)
SELECT 0,
       role_code || ' 禁止伪造专业 Agent turn 事实',
       role_code,
       'POST',
       '/api/agent/specialist-turn-facts/**',
       'AI_RUNTIME',
       'EXECUTE',
       'DENY',
       1100,
       TRUE,
       '普通客户端不能通过修改状态、模型名或摘要正文伪造专业 Agent 事实；登记必须走受信服务控制面。',
       CURRENT_TIMESTAMP,
       CURRENT_TIMESTAMP
FROM (VALUES
    ('ORDINARY_USER'),
    ('PROJECT_OWNER'),
    ('OPERATOR'),
    ('AUDITOR'),
    ('TENANT_ADMINISTRATOR'),
    ('PLATFORM_ADMINISTRATOR')
) AS human_roles(role_code)
ON CONFLICT DO NOTHING;

-- V35 已建立 AI_RUNTIME 的 SELF 数据范围。这里用幂等更新把“专业事实”明确写入策略说明，
-- 防止后续维护者误以为新增的事实路由可以使用 TENANT/PLATFORM 范围读取普通用户会话。
INSERT INTO permission_data_scope_policy
(tenant_id, role_code, resource_type, scope_level, scope_expression, approval_required, enabled, description,
 create_time, update_time)
VALUES
(0, 'ORDINARY_USER', 'AI_RUNTIME', 'SELF', 'actor_id = ${actorId} AND project_id IN ${actorProjectIds}', FALSE, TRUE,
 '普通用户只能读取本人当前授权项目内的 Agent 会话、Run、工具事件和专业 Agent turn 事实。',
 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, 'PROJECT_OWNER', 'AI_RUNTIME', 'SELF', 'actor_id = ${actorId} AND project_id IN ${actorProjectIds}', FALSE, TRUE,
 '项目负责人默认只能读取本人 Agent 会话、Run、工具事件和专业 Agent turn 事实；项目审计需使用独立审计范围。',
 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, role_code, resource_type) DO UPDATE
SET scope_level = EXCLUDED.scope_level,
    scope_expression = EXCLUDED.scope_expression,
    approval_required = EXCLUDED.approval_required,
    enabled = EXCLUDED.enabled,
    description = EXCLUDED.description,
    update_time = CURRENT_TIMESTAMP;
