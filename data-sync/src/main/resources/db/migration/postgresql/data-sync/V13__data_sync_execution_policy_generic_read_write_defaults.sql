-- DataSmart Govern - data-sync 通用读写默认执行策略纠偏。
--
-- 背景说明：
-- 1. V12 首次引入执行策略治理时，用 MYSQL_SOURCE_DEFAULT 与 POSTGRESQL_TARGET_DEFAULT 作为默认种子。
-- 2. 这两个名字来自“典型测试链路”的举例：MySQL 作为源端、PostgreSQL 作为目标端。
-- 3. 真实产品不能把示例误建模为边界，否则 Oracle、SQL Server、Kafka、文件、API 等连接器上线时，
--    会缺少“默认读取 / 默认写入”的基础治理层，只能依赖系统全局策略，无法区分读端和写端。
-- 4. 本迁移不修改已经发布的 V12 文件，避免 Flyway checksum 变化导致本地或客户环境启动失败；
--    它通过 V13 把旧种子升级为通用源端读取策略与通用目标端写入策略。
--
-- 建模语义：
-- - scope_type='CONNECTOR' + connector_role='SOURCE' + connector_type IS NULL
--   表示“任意源端连接器的默认读取策略”；
-- - scope_type='CONNECTOR' + connector_role='TARGET' + connector_type IS NULL
--   表示“任意目标端连接器的默认写入策略”；
-- - 后续如果需要 MySQL、PostgreSQL、Oracle、Kafka 等特定连接器策略，再填写 connector_type 并提高 priority。

UPDATE data_sync_execution_policy old_policy
SET scope_key = 'CONNECTOR:SOURCE:ANY',
    scope_name = '通用源端默认读取策略',
    policy_code = 'DEFAULT_SOURCE_READ',
    policy_name = '通用源端默认读取策略',
    connector_type = NULL,
    connector_role = 'SOURCE',
    description = '通用源端默认读取策略：适用于任意源端连接器，约束默认读取并发和读取批大小；具体连接器可通过更高优先级策略覆盖。',
    update_time = CURRENT_TIMESTAMP
WHERE old_policy.policy_code = 'MYSQL_SOURCE_DEFAULT'
  AND NOT EXISTS (
      SELECT 1
      FROM data_sync_execution_policy existing
      WHERE existing.tenant_id = old_policy.tenant_id
        AND existing.scope_type = 'CONNECTOR'
        AND existing.scope_key = 'CONNECTOR:SOURCE:ANY'
        AND existing.policy_code = 'DEFAULT_SOURCE_READ'
  );

UPDATE data_sync_execution_policy old_policy
SET scope_key = 'CONNECTOR:TARGET:ANY',
    scope_name = '通用目标端默认写入策略',
    policy_code = 'DEFAULT_TARGET_WRITE',
    policy_name = '通用目标端默认写入策略',
    connector_type = NULL,
    connector_role = 'TARGET',
    description = '通用目标端默认写入策略：适用于任意目标端连接器，约束默认写入批次和提交间隔；具体连接器可通过更高优先级策略覆盖。',
    update_time = CURRENT_TIMESTAMP
WHERE old_policy.policy_code = 'POSTGRESQL_TARGET_DEFAULT'
  AND NOT EXISTS (
      SELECT 1
      FROM data_sync_execution_policy existing
      WHERE existing.tenant_id = old_policy.tenant_id
        AND existing.scope_type = 'CONNECTOR'
        AND existing.scope_key = 'CONNECTOR:TARGET:ANY'
        AND existing.policy_code = 'DEFAULT_TARGET_WRITE'
  );

INSERT INTO data_sync_execution_policy
(tenant_id, project_id, scope_type, scope_key, scope_name, policy_code, policy_name, enabled,
 connector_type, connector_role, max_channel, read_batch_size, priority, description, create_time, update_time)
VALUES
(0, NULL, 'CONNECTOR', 'CONNECTOR:SOURCE:ANY', '通用源端默认读取策略', 'DEFAULT_SOURCE_READ',
 '通用源端默认读取策略', TRUE, NULL, 'SOURCE', 4, 1000, 30,
 '通用源端默认读取策略：适用于任意源端连接器，约束默认读取并发和读取批大小；具体连接器可通过更高优先级策略覆盖。',
 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, scope_type, scope_key, policy_code) DO NOTHING;

INSERT INTO data_sync_execution_policy
(tenant_id, project_id, scope_type, scope_key, scope_name, policy_code, policy_name, enabled,
 connector_type, connector_role, write_batch_size, commit_interval_records, priority, description, create_time, update_time)
VALUES
(0, NULL, 'CONNECTOR', 'CONNECTOR:TARGET:ANY', '通用目标端默认写入策略', 'DEFAULT_TARGET_WRITE',
 '通用目标端默认写入策略', TRUE, NULL, 'TARGET', 1000, 1000, 30,
 '通用目标端默认写入策略：适用于任意目标端连接器，约束默认写入批次和提交间隔；具体连接器可通过更高优先级策略覆盖。',
 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, scope_type, scope_key, policy_code) DO NOTHING;

UPDATE data_sync_execution_policy
SET enabled = FALSE,
    description = CONCAT('已由通用源端默认读取策略 DEFAULT_SOURCE_READ 取代；保留本记录仅用于审计旧版本种子迁移。原说明：', COALESCE(description, '')),
    update_time = CURRENT_TIMESTAMP
WHERE policy_code = 'MYSQL_SOURCE_DEFAULT';

UPDATE data_sync_execution_policy
SET enabled = FALSE,
    description = CONCAT('已由通用目标端默认写入策略 DEFAULT_TARGET_WRITE 取代；保留本记录仅用于审计旧版本种子迁移。原说明：', COALESCE(description, '')),
    update_time = CURRENT_TIMESTAMP
WHERE policy_code = 'POSTGRESQL_TARGET_DEFAULT';
