-- ---------------------------------------------------------------------------
-- Agent tool-action command：放宽 Inbox/Outbox 端点与工作空间字段
-- ---------------------------------------------------------------------------
-- 背景：
-- 1. 早期 AGENT_TOOL_ASYNC_TASK_REQUESTED 会携带工具目录 targetEndpoint 和 workspaceId，
--    worker 用它们与 agent-runtime 参数快照做一致性校验；
-- 2. 新增 AGENT_TOOL_ACTION_CONTROLLED_COMMAND 属于控制面命令，不允许外部调用方携带内部 targetEndpoint，
--    否则会把模型 tool_call/MCP/A2A 输入变成内部任意调用路径；
-- 3. 新工具动作当前可能只绑定 run/project，workspace 需要在后续 payload store 或专用执行器阶段物化；
-- 4. 因此 Inbox/Outbox 表结构需要允许 target_endpoint 为空，Inbox.workspace_id 也需要允许为空。
--
-- 安全说明：
-- - 这不是放宽业务校验。Service 层仍要求历史 async-task 必须带 targetEndpoint/workspaceId；
-- - 新 tool-action controlled command 反而会拒绝 targetEndpoint，只允许 agent-runtime 作为目标控制面；
-- - 本迁移只是让数据库结构能表达两类命令的不同语义。
-- ---------------------------------------------------------------------------

USE datasmart_govern;

SET @schema_name = DATABASE();

SET @inbox_target_endpoint_nullable_sql = IF(
    EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = @schema_name
          AND table_name = 'agent_async_task_command_inbox'
          AND column_name = 'target_endpoint'
          AND is_nullable = 'NO'
    ),
    'ALTER TABLE agent_async_task_command_inbox MODIFY COLUMN target_endpoint VARCHAR(300) DEFAULT NULL COMMENT ''工具目录声明的目标端点模板；历史 async-task 必填，新工具动作受控命令禁止携带，由后续执行器重新解析''',
    'SELECT 1'
);
PREPARE stmt FROM @inbox_target_endpoint_nullable_sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @inbox_workspace_nullable_sql = IF(
    EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = @schema_name
          AND table_name = 'agent_async_task_command_inbox'
          AND column_name = 'workspace_id'
          AND is_nullable = 'NO'
    ),
    'ALTER TABLE agent_async_task_command_inbox MODIFY COLUMN workspace_id BIGINT DEFAULT NULL COMMENT ''Agent 工作空间隔离边界；历史 async-task 必填，新工具动作受控命令可为空并在 payload store/执行器阶段补强''',
    'SELECT 1'
);
PREPARE stmt FROM @inbox_workspace_nullable_sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @outbox_target_endpoint_nullable_sql = IF(
    EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = @schema_name
          AND table_name = 'agent_async_task_command_outbox'
          AND column_name = 'target_endpoint'
          AND is_nullable = 'NO'
    ),
    'ALTER TABLE agent_async_task_command_outbox MODIFY COLUMN target_endpoint VARCHAR(300) DEFAULT NULL COMMENT ''目标端点模板；新工具动作受控命令不携带 endpoint，避免外部输入变成内部任意调用路径''',
    'SELECT 1'
);
PREPARE stmt FROM @outbox_target_endpoint_nullable_sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
