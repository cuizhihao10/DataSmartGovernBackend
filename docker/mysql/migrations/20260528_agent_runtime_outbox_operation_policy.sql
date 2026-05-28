-- DataSmart Govern - Agent Runtime outbox 人工补偿权限策略
--
-- 背景：
-- 1. Agent 工具执行事件 outbox 是 Java 控制面到实时事件流之间的可靠投递缓冲区；
-- 2. 查询 outbox 记录通常用于排查“前端为什么没有收到工具状态事件”“dispatcher 是否堆积”
--    “某条事件为什么被 BLOCKED 或 FAILED”等生产问题；
-- 3. requeue/ignore/notes 属于人工补偿动作，不能复用普通 POST=CREATE，否则权限中心无法区分
--    “创建业务对象”和“重放/归档/备注历史事件”这两类完全不同的风险；
-- 4. 当前默认只开放给运维和审计只读视角，平台管理员仍通过 `/api/**` 兜底策略拥有 break-glass 能力。

INSERT IGNORE INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled, description, create_time, update_time)
VALUES
(0, '审计员查看 Agent outbox 投递记录', 'AUDITOR', 'GET', '/api/agent/tool-execution-events/outbox/**', 'AI_RUNTIME', 'VIEW_OUTBOX_EVENTS', 'ALLOW', 114, 1, '审计员可只读查看 Agent 工具状态事件 outbox，用于复核事件是否进入可靠投递链路；后续应结合租户、项目与脱敏策略继续收紧。', NOW(), NOW()),
(0, '运营人员查看 Agent outbox 投递记录', 'OPERATOR', 'GET', '/api/agent/tool-execution-events/outbox/**', 'AI_RUNTIME', 'VIEW_OUTBOX_EVENTS', 'ALLOW', 138, 1, '运营人员可查看 Agent 工具状态事件 outbox，用于定位实时事件缺失、投递失败、payload 阻断和 dispatcher 堆积。', NOW(), NOW()),
(0, '运营人员诊断 Agent outbox 状态分布', 'OPERATOR', 'GET', '/api/agent/tool-execution-events/outbox/diagnostics', 'AI_RUNTIME', 'DIAGNOSE', 'ALLOW', 139, 1, '运营人员可查看 pending、publishing、failed、blocked、ignored 等 outbox 状态计数，用于告警判断和容量评估。', NOW(), NOW()),
(0, '运营人员重新入队 Agent outbox 事件', 'OPERATOR', 'POST', '/api/agent/tool-execution-events/outbox/*/requeue', 'AI_RUNTIME', 'REQUEUE_OUTBOX', 'ALLOW', 140, 1, '运营人员可在下游 topic、消费链路或 payload 契约修复后，把 FAILED/BLOCKED 事件重新置为 PENDING 等待 dispatcher 补偿投递。', NOW(), NOW()),
(0, '运营人员备注 Agent outbox 事件', 'OPERATOR', 'POST', '/api/agent/tool-execution-events/outbox/*/notes', 'AI_RUNTIME', 'ANNOTATE_OUTBOX', 'ALLOW', 140, 1, '运营人员可追加 outbox 排障备注，记录客户确认、下游修复计划或暂缓处理原因；备注不改变投递状态。', NOW(), NOW()),
(0, '运营人员禁止忽略 Agent outbox 事件', 'OPERATOR', 'POST', '/api/agent/tool-execution-events/outbox/*/ignore', 'AI_RUNTIME', 'IGNORE_OUTBOX', 'DENY', 900, 1, 'ignore 会把异常事件人工归档并停止自动补偿，默认不授予普通运营人员，需由平台管理员或后续审批流程执行。', NOW(), NOW()),
(0, '审计员禁止变更 Agent outbox 事件', 'AUDITOR', 'POST', '/api/agent/tool-execution-events/outbox/**', 'AI_RUNTIME', NULL, 'DENY', 900, 1, '审计员是只读角色，只能复核 outbox 投递证据，不能执行重新入队、忽略或备注等改变排障事实的动作。', NOW(), NOW()),
(0, '服务账号禁止人工处理 Agent outbox', 'SERVICE_ACCOUNT', 'ANY', '/api/agent/tool-execution-events/outbox/**', 'AI_RUNTIME', NULL, 'DENY', 900, 1, '服务账号用于内部协议调用，不应默认执行 outbox 人工补偿动作，避免机器身份替代人类责任链。', NOW(), NOW());
