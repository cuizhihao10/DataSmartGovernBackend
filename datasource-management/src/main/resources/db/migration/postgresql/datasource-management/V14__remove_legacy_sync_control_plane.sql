-- Data-source management owns connectors and metadata only.
-- The data-sync service is the single owner of task definitions and execution state.
DROP TABLE IF EXISTS sync_checkpoint;
DROP TABLE IF EXISTS sync_execution;
DROP TABLE IF EXISTS sync_agent_command_receipt;
DROP TABLE IF EXISTS sync_task;
DROP TABLE IF EXISTS sync_template;

DELETE FROM sync_permission_policy_binding
WHERE binding_value = 'sync:template-center';
