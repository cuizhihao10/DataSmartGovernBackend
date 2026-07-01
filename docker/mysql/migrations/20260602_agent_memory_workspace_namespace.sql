-- DataSmart Govern Backend - Agent 长期记忆 workspace 命名空间升级迁移
--
-- 背景说明：
-- 早期 agent_memory_write_candidate 只按 tenant/project/scope 保存长期记忆候选。
-- 这对普通项目级经验足够，但对 Codex/Claude Code 类 Agent 不够安全：
-- 同一个项目下可能同时存在默认项目空间、客户实施专项 workspace、一次性诊断 session 沙箱。
-- 如果候选进入正式记忆前没有 workspace_key 和 memory_namespace，后续检索只能按项目召回，
-- 就可能把 A 工作空间的治理经验误注入 B 工作空间的模型上下文。
--
-- 本迁移只为候选表补充空间证据字段和查询索引，不自动回填历史数据。
-- 历史候选进入正式记忆前应由补偿工具基于原始 run/audit 重新计算并人工确认 namespace，
-- 避免把旧候选猜测性迁移到错误空间。

-- 真实 E2E 闭环发现：init.sql 已经吸收了较新的 agent_memory_write_candidate 表结构，
-- 新库首次初始化后再执行本 migration 时可能遇到 Duplicate column。
-- 因此这里改为 information_schema + 动态 SQL 的幂等写法：
-- 1. 字段已存在时只执行 SELECT 1；
-- 2. 字段不存在时才 ALTER TABLE；
-- 3. 索引同样先检查再创建。
SET @schema_name = DATABASE();

SET @sql = IF(
    EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = @schema_name
          AND table_name = 'agent_memory_write_candidate'
          AND column_name = 'workspace_key'
    ),
    'SELECT 1',
    'ALTER TABLE agent_memory_write_candidate ADD COLUMN workspace_key VARCHAR(255) DEFAULT NULL COMMENT ''Agent 工作空间隔离键；同一项目内不同 workspace/session 不应共享长期记忆。'' AFTER source'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = @schema_name
          AND table_name = 'agent_memory_write_candidate'
          AND column_name = 'memory_namespace'
    ),
    'SELECT 1',
    'ALTER TABLE agent_memory_write_candidate ADD COLUMN memory_namespace VARCHAR(255) DEFAULT NULL COMMENT ''长期记忆命名空间，通常为 memory:{workspaceKey}；正式记忆检索必须按该字段过滤。'' AFTER workspace_key'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    EXISTS (
        SELECT 1
        FROM information_schema.statistics
        WHERE table_schema = @schema_name
          AND table_name = 'agent_memory_write_candidate'
          AND index_name = 'idx_agent_memory_write_candidate_workspace'
    ),
    'SELECT 1',
    'ALTER TABLE agent_memory_write_candidate ADD KEY idx_agent_memory_write_candidate_workspace (tenant_id, project_id, workspace_key, status, create_time)'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
