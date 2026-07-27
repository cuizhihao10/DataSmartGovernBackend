-- Allow the trusted local service account to verify low-sensitive Agent diagnostics through Gateway.
--
-- This is intentionally an exact read-only allow-list. It does not grant session creation, model
-- execution, tool execution, outbox recovery, memory approval, or any business data mutation.
-- Existing SERVICE_ACCOUNT deny policies for human-operated compensation endpoints remain in force.

DELETE FROM permission_route_policy
WHERE role_code = 'SERVICE_ACCOUNT'
  AND http_method = 'GET'
  AND resource_type = 'AI_RUNTIME'
  AND path_pattern IN (
      '/api/agent/capabilities/**',
      '/api/agent/skills/publication/diagnostics',
      '/api/agent/models/*/diagnostics',
      '/api/agent/metrics',
      '/api/agent/sessions',
      '/api/agent/tools/**',
      '/api/agent/skills/publication/manifest',
      '/api/agent/models/routes',
      '/api/agent/runtime-events/diagnostics',
      '/api/agent/runtime-events/skill-visibility-snapshots/diagnostics',
      '/api/agent/tool-execution-events/outbox/diagnostics',
      '/api/agent/async-task-commands/outbox/diagnostics'
  );

INSERT INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action,
 effect, priority, enabled, description, create_time, update_time)
VALUES
(0, '服务账号诊断 Agent 能力闭口', 'SERVICE_ACCOUNT', 'GET',
 '/api/agent/capabilities/**', 'AI_RUNTIME', 'DIAGNOSE', 'ALLOW', 840, TRUE,
 '仅允许受信服务账号读取低敏能力闭口状态，不授予模型调用或工具执行权限。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '服务账号诊断 Agent Skill 发布', 'SERVICE_ACCOUNT', 'GET',
 '/api/agent/skills/publication/diagnostics', 'AI_RUNTIME', 'DIAGNOSE', 'ALLOW', 840, TRUE,
 '仅允许读取 Skill Manifest 缓存、指纹和状态计数。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '服务账号诊断 Agent 模型运行时', 'SERVICE_ACCOUNT', 'GET',
 '/api/agent/models/*/diagnostics', 'AI_RUNTIME', 'DIAGNOSE', 'ALLOW', 840, TRUE,
 '仅允许读取模型 Provider 与推理优化低敏诊断，不返回凭据、提示词或完整模型输出。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '服务账号抓取 Agent 低基数指标', 'SERVICE_ACCOUNT', 'GET',
 '/api/agent/metrics', 'AI_RUNTIME', 'DIAGNOSE', 'ALLOW', 840, TRUE,
 '仅允许读取不含租户、项目、Run 等高基数标签的 Prometheus 指标。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '服务账号查看 Agent 会话目录', 'SERVICE_ACCOUNT', 'GET',
 '/api/agent/sessions', 'AI_RUNTIME', 'VIEW', 'ALLOW', 830, TRUE,
 '只读查看租户范围内的 Agent 会话低敏目录，用于统一入口连通性诊断。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '服务账号查看 Agent 工具描述符', 'SERVICE_ACCOUNT', 'GET',
 '/api/agent/tools/**', 'AI_RUNTIME', 'VIEW', 'ALLOW', 830, TRUE,
 '只读查看工具 schema、风险和目标服务描述，不授予工具执行权限。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '服务账号查看 Agent Skill Manifest', 'SERVICE_ACCOUNT', 'GET',
 '/api/agent/skills/publication/manifest', 'AI_RUNTIME', 'VIEW', 'ALLOW', 830, TRUE,
 '只读查看 Java Agent Runtime 发布的 Skill Manifest。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '服务账号查看 Agent 模型路由', 'SERVICE_ACCOUNT', 'GET',
 '/api/agent/models/routes', 'AI_RUNTIME', 'VIEW', 'ALLOW', 830, TRUE,
 '只读查看模型路由配置的低敏字段，不返回 Provider 密钥。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '服务账号诊断 Agent 运行事件', 'SERVICE_ACCOUNT', 'GET',
 '/api/agent/runtime-events/diagnostics', 'AI_RUNTIME', 'DIAGNOSE', 'ALLOW', 840, TRUE,
 '仅允许读取事件消费和投影状态计数。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '服务账号诊断 Agent Skill 可见性投影', 'SERVICE_ACCOUNT', 'GET',
 '/api/agent/runtime-events/skill-visibility-snapshots/diagnostics', 'AI_RUNTIME', 'DIAGNOSE', 'ALLOW', 840, TRUE,
 '仅允许读取 Skill 可见性投影的低敏诊断摘要。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '服务账号诊断 Agent 工具事件 outbox', 'SERVICE_ACCOUNT', 'GET',
 '/api/agent/tool-execution-events/outbox/diagnostics', 'AI_RUNTIME', 'DIAGNOSE', 'ALLOW', 840, TRUE,
 '只读查看工具事件 outbox 状态计数；重新入队、忽略和备注仍由既有策略禁止。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(0, '服务账号诊断 Agent 异步命令 outbox', 'SERVICE_ACCOUNT', 'GET',
 '/api/agent/async-task-commands/outbox/diagnostics', 'AI_RUNTIME', 'DIAGNOSE', 'ALLOW', 840, TRUE,
 '只读查看异步命令 outbox 状态计数，不允许派发、重试或修改命令。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
