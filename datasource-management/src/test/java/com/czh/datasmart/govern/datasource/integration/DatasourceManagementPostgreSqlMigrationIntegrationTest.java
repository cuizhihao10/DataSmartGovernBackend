/**
 * @Author : Cui
 * @Date: 2026/07/02 23:58
 * @Description DataSmartGovernBackend - DatasourceManagementPostgreSqlMigrationIntegrationTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.czh.datasmart.govern.datasource.entity.DataSourceConfig;
import com.czh.datasmart.govern.datasource.entity.DataSourceReadOnlySqlExecutionAudit;
import com.czh.datasmart.govern.datasource.entity.DataSyncAgentCommandReceipt;
import com.czh.datasmart.govern.datasource.entity.SyncCheckpoint;
import com.czh.datasmart.govern.datasource.entity.SyncExecution;
import com.czh.datasmart.govern.datasource.entity.SyncTask;
import com.czh.datasmart.govern.datasource.entity.SyncTemplate;
import com.czh.datasmart.govern.datasource.mapper.DataSourceConfigMapper;
import com.czh.datasmart.govern.datasource.mapper.DataSourceReadOnlySqlExecutionAuditMapper;
import com.czh.datasmart.govern.datasource.mapper.DataSyncAgentCommandReceiptMapper;
import com.czh.datasmart.govern.datasource.mapper.SyncCheckpointMapper;
import com.czh.datasmart.govern.datasource.mapper.SyncExecutionMapper;
import com.czh.datasmart.govern.datasource.mapper.SyncTaskMapper;
import com.czh.datasmart.govern.datasource.mapper.SyncTemplateMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * datasource-management PostgreSQL 真实集成测试。
 *
 * <p>这个测试专门验证 H2、Mock 或纯单元测试无法覆盖的迁移风险：
 * PostgreSQL schema search_path、Flyway V1 登记、identity 主键回填、BOOLEAN 字段映射、
 * MyBatis-Plus PostgreSQL 分页方言，以及 datasource-management 当前 14 张控制面表是否全部存在。</p>
 *
 * <p>运行安全边界：
 * 只有显式设置 {@code DATASMART_POSTGRES_INTEGRATION_ENABLED=true} 时才会执行；
 * 测试不创建或删除数据库，不执行 Flyway clean，不读取真实客户数据；
 * 写入的数据全部使用 910xxx 段测试租户/项目和随机后缀，并在 finally 中按依赖顺序定向清理，
 * 避免污染共享开发库或误删其他数据。</p>
 *
 * <p>产品边界说明：
 * 本测试验证的是 datasource-management 的“平台自身业务库”迁移到 PostgreSQL。
 * 它不代表外部客户 MySQL 数据源不再受支持；外部连接器路径由 JDBC dialect 和连接器单测继续覆盖。</p>
 */
@SpringBootTest(properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "datasmart.datasource.sync-alert.scheduler.enabled=false",
        "datasmart.datasource.sync-permission-notification.reminder-scheduler-enabled=false",
        "datasmart.datasource.task-management-execution-receipt.enabled=false"
})
@EnabledIfEnvironmentVariable(named = "DATASMART_POSTGRES_INTEGRATION_ENABLED", matches = "(?i)true")
class DatasourceManagementPostgreSqlMigrationIntegrationTest {

    private final JdbcTemplate jdbcTemplate;
    private final DataSourceConfigMapper datasourceMapper;
    private final SyncTemplateMapper templateMapper;
    private final SyncTaskMapper taskMapper;
    private final SyncExecutionMapper executionMapper;
    private final SyncCheckpointMapper checkpointMapper;
    private final DataSourceReadOnlySqlExecutionAuditMapper auditMapper;
    private final DataSyncAgentCommandReceiptMapper receiptMapper;

    @Autowired
    DatasourceManagementPostgreSqlMigrationIntegrationTest(
            JdbcTemplate jdbcTemplate,
            DataSourceConfigMapper datasourceMapper,
            SyncTemplateMapper templateMapper,
            SyncTaskMapper taskMapper,
            SyncExecutionMapper executionMapper,
            SyncCheckpointMapper checkpointMapper,
            DataSourceReadOnlySqlExecutionAuditMapper auditMapper,
            DataSyncAgentCommandReceiptMapper receiptMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.datasourceMapper = datasourceMapper;
        this.templateMapper = templateMapper;
        this.taskMapper = taskMapper;
        this.executionMapper = executionMapper;
        this.checkpointMapper = checkpointMapper;
        this.auditMapper = auditMapper;
        this.receiptMapper = receiptMapper;
    }

    /**
     * 验证 PostgreSQL schema 基线以及核心控制面事实读写路径。
     *
     * <p>这里不是为了覆盖所有业务分支，而是建立迁移“冒烟基线”：
     * 只要这些插入、分页、BOOLEAN、TEXT JSON 和定向清理可以在真实 PostgreSQL 上通过，
     * 后续服务层单测覆盖的业务流程就不会因为底层 DDL 缺失、方言错误或主键回填失败而整体不可用。</p>
     */
    @Test
    void shouldApplyDatasourceManagementSchemaAndPersistCoreFactsThroughMyBatis() {
        assertPostgreSqlSchemaBaseline();

        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        DataSourceConfig source = null;
        DataSourceConfig target = null;
        SyncTemplate template = null;
        SyncTask task = null;
        SyncExecution execution = null;
        DataSourceReadOnlySqlExecutionAudit audit = null;
        DataSyncAgentCommandReceipt receipt = null;
        try {
            source = insertDatasource("source", suffix);
            target = insertDatasource("target", suffix);
            template = insertTemplate(source, target, suffix);
            task = insertTask(template, suffix);
            execution = insertExecution(task);
            insertCheckpoint(execution);
            audit = insertReadOnlyAudit(source, suffix);
            receipt = insertAgentCommandReceipt(template, task, suffix);

            assertThat(source.getId()).isPositive();
            assertThat(target.getId()).isPositive();
            assertThat(template.getId()).isPositive();
            assertThat(template.getEnabled()).isTrue();
            assertThat(task.getId()).isPositive();
            assertThat(task.getEnabled()).isTrue();
            assertThat(task.getOperatorAttentionRequired()).isFalse();
            assertThat(execution.getId()).isPositive();
            assertThat(audit.getId()).isPositive();
            assertThat(receipt.getId()).isPositive();
            assertThat(receipt.getSideEffectStarted()).isTrue();

            Page<DataSourceConfig> datasourcePage = datasourceMapper.selectPage(
                    new Page<>(1, 10),
                    new LambdaQueryWrapper<DataSourceConfig>()
                            .eq(DataSourceConfig::getTenantId, 910001L)
                            .eq(DataSourceConfig::getProjectId, 910101L)
                            .likeRight(DataSourceConfig::getName, "pg-datasource-")
                            .orderByAsc(DataSourceConfig::getId)
            );

            assertThat(datasourcePage.getTotal()).isGreaterThanOrEqualTo(2);
            assertThat(datasourcePage.getRecords()).extracting(DataSourceConfig::getId)
                    .contains(source.getId(), target.getId());
        } finally {
            deleteIntegrationFacts(receipt, audit, execution, task, template, source, target);
        }
    }

    /**
     * 校验连接实际进入 datasource_management schema，并且 Flyway 已成功登记 V1。
     */
    private void assertPostgreSqlSchemaBaseline() {
        String currentSchema = jdbcTemplate.queryForObject("SELECT current_schema()", String.class);
        Integer flywaySuccessCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success = true",
                Integer.class
        );
        Integer tableCount = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM information_schema.tables
                WHERE table_schema = 'datasource_management'
                  AND table_name IN (
                      'datasource_config',
                      'datasource_readonly_sql_execution_audit',
                      'sync_template',
                      'sync_task',
                      'sync_agent_command_receipt',
                      'sync_execution',
                      'sync_checkpoint',
                      'sync_audit_record',
                      'sync_permission_policy_binding',
                      'sync_permission_policy_change_request',
                      'sync_permission_approval_delegate_rule',
                      'sync_governance_alert',
                      'sync_alert_delivery_record',
                      'sync_permission_governance_notification'
                  )
                """, Integer.class);

        assertThat(currentSchema).isEqualTo("datasource_management");
        assertThat(flywaySuccessCount).isEqualTo(1);
        assertThat(tableCount).isEqualTo(14);
    }

    /**
     * 创建外部数据源登记记录。
     *
     * <p>测试使用 PostgreSQL 类型只是为了避免写入真实客户 MySQL 连接串；业务上 datasource-management
     * 仍然可以登记 MYSQL、POSTGRESQL、SQLSERVER 等多种外部数据源。</p>
     */
    private DataSourceConfig insertDatasource(String role, String suffix) {
        DataSourceConfig config = new DataSourceConfig();
        config.setTenantId(910001L);
        config.setProjectId(910101L);
        config.setWorkspaceId(910201L);
        config.setName("pg-datasource-" + role + "-" + suffix);
        config.setType("POSTGRESQL");
        config.setJdbcUrl("jdbc:postgresql://example.invalid:5432/" + role);
        config.setUsername("integration_user");
        config.setPassword("integration-password-placeholder");
        config.setDriverClassName("org.postgresql.Driver");
        config.setDescription("PostgreSQL migration integration datasource " + role);
        config.setStatus("ACTIVE");
        config.setLastTestStatus("SUCCESS");
        config.setLastTestMessage("低敏连接测试摘要");
        config.setLastTestTime(LocalDateTime.now());
        datasourceMapper.insert(config);
        return config;
    }

    /**
     * 创建同步模板，验证 TEXT JSON 配置、BOOLEAN enabled 和双数据源引用可以在 PostgreSQL 上稳定写入。
     */
    private SyncTemplate insertTemplate(DataSourceConfig source, DataSourceConfig target, String suffix) {
        SyncTemplate template = new SyncTemplate();
        template.setTenantId(source.getTenantId());
        template.setProjectId(source.getProjectId());
        template.setWorkspaceId(source.getWorkspaceId());
        template.setName("pg-template-" + suffix);
        template.setDescription("PostgreSQL migration integration template");
        template.setSourceDatasourceId(source.getId());
        template.setSourceSchemaName("public");
        template.setSourceObjectName("customer_source");
        template.setTargetDatasourceId(target.getId());
        template.setTargetSchemaName("public");
        template.setTargetObjectName("customer_target");
        template.setSyncMode("FULL");
        template.setWriteStrategy("UPSERT");
        template.setPrimaryKeyField("id");
        template.setFieldMappingConfig("{\"id\":\"id\",\"name\":\"name\"}");
        template.setRetryPolicy("{\"maxAttempts\":3}");
        template.setTimeoutPolicy("{\"timeoutSeconds\":60}");
        template.setEnabled(true);
        template.setCreatedBy(910301L);
        templateMapper.insert(template);
        return template;
    }

    /**
     * 创建同步任务，覆盖 PostgreSQL BOOLEAN 映射、调度租约字段和任务级重试字段。
     */
    private SyncTask insertTask(SyncTemplate template, String suffix) {
        SyncTask task = new SyncTask();
        task.setTenantId(template.getTenantId());
        task.setProjectId(template.getProjectId());
        task.setWorkspaceId(template.getWorkspaceId());
        task.setTemplateId(template.getId());
        task.setName("pg-task-" + suffix);
        task.setDescription("PostgreSQL migration integration task");
        task.setCurrentState("QUEUED");
        task.setApprovalState("APPROVED");
        task.setPriority("NORMAL");
        task.setRunMode("MANUAL");
        task.setTriggerType("INTEGRATION_TEST");
        task.setScheduleConfig("{\"type\":\"manual\"}");
        task.setOwnerId(910301L);
        task.setQueuedAt(LocalDateTime.now());
        task.setEnabled(true);
        task.setOperatorAttentionRequired(false);
        task.setTimeoutSeconds(60);
        task.setMaxRetryCount(3);
        task.setRetryCount(0);
        task.setQueueAttemptCount(1);
        task.setCreatedBy(910301L);
        taskMapper.insert(task);
        return task;
    }

    /**
     * 创建一次执行记录，验证执行序号、统计计数和租约字段可以稳定写入。
     */
    private SyncExecution insertExecution(SyncTask task) {
        SyncExecution execution = new SyncExecution();
        execution.setSyncTaskId(task.getId());
        execution.setExecutionNo(1L);
        execution.setState("RUNNING");
        execution.setStartedAt(LocalDateTime.now());
        execution.setCheckpointRef("integration-checkpoint");
        execution.setRecordsRead(10L);
        execution.setRecordsWritten(10L);
        execution.setFailedRecordCount(0L);
        execution.setTriggeredBy(task.getOwnerId());
        execution.setExecutorId("postgresql-integration-runner");
        execution.setHeartbeatAt(LocalDateTime.now());
        execution.setLeaseExpireAt(LocalDateTime.now().plusMinutes(2));
        execution.setTriggerReason("PostgreSQL migration integration test");
        executionMapper.insert(execution);
        return execution;
    }

    /**
     * 创建检查点明细，验证 TEXT 水位值和分片字段。
     */
    private void insertCheckpoint(SyncExecution execution) {
        SyncCheckpoint checkpoint = new SyncCheckpoint();
        checkpoint.setExecutionId(execution.getId());
        checkpoint.setCheckpointType("PRIMARY_KEY_RANGE");
        checkpoint.setCheckpointValue("{\"lastId\":100}");
        checkpoint.setShardOrPartition("shard-0");
        checkpointMapper.insert(checkpoint);
        assertThat(checkpoint.getId()).isPositive();
    }

    /**
     * 创建只读 SQL 审计记录，验证敏感 SQL 只保存指纹和脱敏预览。
     */
    private DataSourceReadOnlySqlExecutionAudit insertReadOnlyAudit(DataSourceConfig datasource, String suffix) {
        DataSourceReadOnlySqlExecutionAudit audit = new DataSourceReadOnlySqlExecutionAudit();
        audit.setDatasourceTenantId(datasource.getTenantId());
        audit.setDatasourceProjectId(datasource.getProjectId());
        audit.setDatasourceWorkspaceId(datasource.getWorkspaceId());
        audit.setDatasourceId(datasource.getId());
        audit.setDatasourceName(datasource.getName());
        audit.setDatasourceType(datasource.getType());
        audit.setPurpose("INTEGRATION_TEST");
        audit.setActorTenantId(datasource.getTenantId());
        audit.setActorId(910301L);
        audit.setActorRole("SERVICE_ACCOUNT");
        audit.setActorType("SERVICE_ACCOUNT");
        audit.setSourceService("datasource-management-integration-test");
        audit.setTraceId("pg-datasource-audit-" + suffix);
        audit.setSqlFingerprint("abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789");
        audit.setSqlPreview("SELECT id, name FROM customer LIMIT 10");
        audit.setRequestedMaxRows(10);
        audit.setAppliedMaxRows(10);
        audit.setRequestedQueryTimeoutSeconds(5);
        audit.setAppliedQueryTimeoutSeconds(5);
        audit.setReturnedRowCount(1);
        audit.setColumnCount(2);
        audit.setDurationMs(15L);
        audit.setExecutionStatus("SUCCESS");
        audit.setExecutedAt(LocalDateTime.now());
        auditMapper.insert(audit);
        return audit;
    }

    /**
     * 创建 Agent 命令 receipt，验证幂等键、低敏状态快照和 BOOLEAN 副作用标记。
     */
    private DataSyncAgentCommandReceipt insertAgentCommandReceipt(SyncTemplate template, SyncTask task, String suffix) {
        DataSyncAgentCommandReceipt receipt = new DataSyncAgentCommandReceipt();
        receipt.setReceiptId("pg-receipt-" + suffix);
        receipt.setCommandId("pg-command-" + suffix);
        receipt.setIdempotencyKey("pg-idempotency-" + suffix);
        receipt.setAgentSessionId("session-" + suffix);
        receipt.setAgentRunId("run-" + suffix);
        receipt.setAuditId("audit-" + suffix);
        receipt.setToolCode("data-sync.execute");
        receipt.setTenantId(template.getTenantId());
        receipt.setProjectId(template.getProjectId());
        receipt.setWorkspaceId(template.getWorkspaceId());
        receipt.setActorId("910301");
        receipt.setTraceId("pg-receipt-trace-" + suffix);
        receipt.setTemplateId(template.getId());
        receipt.setSyncTemplateId(template.getId());
        receipt.setResolvedTemplateId(template.getId());
        receipt.setSyncTaskId(task.getId());
        receipt.setStatus("QUEUED");
        receipt.setDownstreamState(task.getCurrentState());
        receipt.setSideEffectStarted(true);
        receipt.setSideEffectExecuted(true);
        receipt.setDuplicate(false);
        receipt.setMessage("PostgreSQL migration integration receipt");
        receiptMapper.insert(receipt);
        return receipt;
    }

    /**
     * 按依赖反向清理本次测试事实。
     *
     * <p>生产 DDL 故意没有外键级联，因此测试也不依赖级联删除。
     * 这样可以真实模拟未来归档、审计保留和手工清理任务需要遵守的事实顺序。</p>
     */
    private void deleteIntegrationFacts(
            DataSyncAgentCommandReceipt receipt,
            DataSourceReadOnlySqlExecutionAudit audit,
            SyncExecution execution,
            SyncTask task,
            SyncTemplate template,
            DataSourceConfig source,
            DataSourceConfig target) {
        if (receipt != null && receipt.getId() != null) {
            jdbcTemplate.update("DELETE FROM sync_agent_command_receipt WHERE id = ?", receipt.getId());
        }
        if (audit != null && audit.getId() != null) {
            jdbcTemplate.update("DELETE FROM datasource_readonly_sql_execution_audit WHERE id = ?", audit.getId());
        }
        if (execution != null && execution.getId() != null) {
            jdbcTemplate.update("DELETE FROM sync_checkpoint WHERE execution_id = ?", execution.getId());
            jdbcTemplate.update("DELETE FROM sync_execution WHERE id = ?", execution.getId());
        }
        if (task != null && task.getId() != null) {
            jdbcTemplate.update("DELETE FROM sync_task WHERE id = ?", task.getId());
        }
        if (template != null && template.getId() != null) {
            jdbcTemplate.update("DELETE FROM sync_template WHERE id = ?", template.getId());
        }
        if (source != null && source.getId() != null) {
            jdbcTemplate.update("DELETE FROM datasource_config WHERE id = ?", source.getId());
        }
        if (target != null && target.getId() != null) {
            jdbcTemplate.update("DELETE FROM datasource_config WHERE id = ?", target.getId());
        }
    }
}
