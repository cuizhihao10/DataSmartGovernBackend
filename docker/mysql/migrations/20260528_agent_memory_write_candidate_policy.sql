-- DataSmart Govern Backend - Agent 长期记忆写入候选权限策略迁移
--
-- 背景：
-- Python AI Runtime 已经可以生成、查询、批准和拒绝长期记忆写入候选。
-- 这些候选未来会驱动 Chroma/Neo4j/MySQL/MinIO 等长期记忆持久层写入，因此不能只依赖普通 GET/POST 授权。
--
-- 设计原则：
-- 1. 查看与决策分离：查看候选不等于可以批准候选进入长期记忆。
-- 2. 人工责任链优先：批准/拒绝默认交给项目负责人，后续可扩展租户管理员、双人复核或审批流。
-- 3. 服务账号默认禁止人工决策：异步 worker 可以执行系统协议，但不能替代人类审批长期记忆。
-- 4. 审计优先：拒绝动作同样独立建模，因为拒绝原因会反哺 memoryWritePolicy 和合规评估。

INSERT IGNORE INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled, description, create_time, update_time)
VALUES
(0, '审计员查看 Agent 记忆写入候选', 'AUDITOR', 'GET', '/api/agent/memory/write-candidates/**', 'AI_RUNTIME', 'VIEW_MEMORY_WRITE_CANDIDATES', 'ALLOW', 115, 1, '审计员可只读查看长期记忆写入候选，用于复核哪些工具结果、治理经验或运行结论准备进入长期记忆；后续应结合脱敏和项目范围继续收紧。', NOW(), NOW()),
(0, '运营人员查看 Agent 记忆写入候选', 'OPERATOR', 'GET', '/api/agent/memory/write-candidates/**', 'AI_RUNTIME', 'VIEW_MEMORY_WRITE_CANDIDATES', 'ALLOW', 141, 1, '运营人员可查看记忆写入候选，用于排查候选生成、审批等待、拒绝原因和后续写入 worker 阻塞问题。', NOW(), NOW()),
(0, '项目负责人查看 Agent 记忆写入候选', 'PROJECT_OWNER', 'GET', '/api/agent/memory/write-candidates/**', 'AI_RUNTIME', 'VIEW_MEMORY_WRITE_CANDIDATES', 'ALLOW', 146, 1, '项目负责人可查看自己负责项目范围内的长期记忆候选，服务层和数据范围策略应继续限制 tenant/project/scope。', NOW(), NOW()),
(0, '项目负责人批准 Agent 记忆写入候选', 'PROJECT_OWNER', 'POST', '/api/agent/memory/write-candidates/*/approve', 'AI_RUNTIME', 'APPROVE_MEMORY_WRITE', 'ALLOW', 146, 1, '项目负责人可批准本项目候选进入长期记忆写入流程；批准后内容未来可能被 Agent 跨会话检索和复用，因此必须保留审批理由和操作者。', NOW(), NOW()),
(0, '项目负责人拒绝 Agent 记忆写入候选', 'PROJECT_OWNER', 'POST', '/api/agent/memory/write-candidates/*/reject', 'AI_RUNTIME', 'REJECT_MEMORY_WRITE', 'ALLOW', 146, 1, '项目负责人可拒绝敏感、越权、过期、质量不足或不适合长期沉淀的记忆候选，拒绝原因会成为后续策略优化和审计依据。', NOW(), NOW()),
(0, '审计员禁止变更 Agent 记忆写入候选', 'AUDITOR', 'POST', '/api/agent/memory/write-candidates/**', 'AI_RUNTIME', NULL, 'DENY', 900, 1, '审计员是只读复核角色，不能批准或拒绝长期记忆候选，避免审计职责与业务决策职责混在一起。', NOW(), NOW()),
(0, '运营人员禁止批准 Agent 记忆写入候选', 'OPERATOR', 'POST', '/api/agent/memory/write-candidates/*/approve', 'AI_RUNTIME', 'APPROVE_MEMORY_WRITE', 'DENY', 900, 1, '运营人员可以排障查看候选，但默认不批准内容进入长期记忆，避免运维权限扩大为业务知识沉淀权限。', NOW(), NOW()),
(0, '服务账号禁止人工决策 Agent 记忆写入候选', 'SERVICE_ACCOUNT', 'ANY', '/api/agent/memory/write-candidates/**', 'AI_RUNTIME', NULL, 'DENY', 900, 1, '服务账号用于内部协议和异步 worker，不应默认执行人工批准或拒绝，避免模型链路绕过人类责任链。', NOW(), NOW());
