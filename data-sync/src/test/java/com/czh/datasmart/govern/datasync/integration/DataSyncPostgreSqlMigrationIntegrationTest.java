/**
 * @Author : Cui
 * @Date: 2026/07/02 00:00
 * @Description DataSmartGovernBackend - DataSyncPostgreSqlMigrationIntegrationTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.czh.datasmart.govern.datasync.entity.SyncAuditRecord;
import com.czh.datasmart.govern.datasync.entity.SyncCallbackIdempotency;
import com.czh.datasmart.govern.datasync.entity.SyncCheckpoint;
import com.czh.datasmart.govern.datasync.entity.SyncErrorSample;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncExecutionRecoveryPlan;
import com.czh.datasmart.govern.datasync.entity.SyncIncidentRecord;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.entity.SyncTaskManagementReceiptOutbox;
import com.czh.datasmart.govern.datasync.entity.SyncTemplate;
import com.czh.datasmart.govern.datasync.mapper.SyncAuditRecordMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncCallbackIdempotencyMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncCheckpointMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncErrorSampleMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionRecoveryPlanMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncIncidentRecordMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskManagementReceiptOutboxMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncTemplateMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * data-sync PostgreSQL 真实集成测试。
 *
 * <p>这个测试专门覆盖 H2、Mock 或纯单元测试很难发现的迁移风险：
 * PostgreSQL schema search_path、Flyway V1 记录、identity 主键回填、BOOLEAN 字段映射、
 * MyBatis-Plus PostgreSQL 分页、执行器租约 interval 表达式、receipt outbox 的过期投递扫描，
 * 以及 PostgreSQL CTE 小批量删除。</p>
 *
 * <p>运行安全边界：
 * 只有显式设置 {@code DATASMART_POSTGRES_INTEGRATION_ENABLED=true} 时才会执行；
 * 测试不创建或删除数据库，不执行 Flyway clean，不读取真实客户数据；
 * 写入样本全部使用 930xxx 测试租户、项目和随机后缀，并在 finally 中按依赖反向清理，
 * 避免污染共享开发库或误删其它测试事实。</p>
 */
@SpringBootTest(properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "datasmart.data-sync.worker-loop.scheduler-enabled=false",
        "datasmart.data-sync.task-management-receipt.outbox.scheduler-enabled=false",
        "datasmart.data-sync.executor.recovery.enabled=false",
        "datasmart.data-sync.executor.idempotency-cleanup.enabled=false",
        "datasmart.data-sync.maintenance.operational-data-cleanup.enabled=false",
        "datasmart.data-sync.datasource-capability.enabled=false",
        "datasmart.data-sync.datasource-run-once.enabled=false",
        "datasmart.data-sync.task-management-receipt.enabled=false"
})
@EnabledIfEnvironmentVariable(named = "DATASMART_POSTGRES_INTEGRATION_ENABLED", matches = "(?i)true")
class DataSyncPostgreSqlMigrationIntegrationTest {

    private final JdbcTemplate jdbcTemplate;
    private final SyncTemplateMapper templateMapper;
    private final SyncTaskMapper taskMapper;
    private final SyncExecutionMapper executionMapper;
    private final SyncCheckpointMapper checkpointMapper;
    private final SyncExecutionRecoveryPlanMapper recoveryPlanMapper;
    private final SyncErrorSampleMapper errorSampleMapper;
    private final SyncIncidentRecordMapper incidentMapper;
    private final SyncAuditRecordMapper auditMapper;
    private final SyncCallbackIdempotencyMapper idempotencyMapper;
    private final SyncTaskManagementReceiptOutboxMapper receiptOutboxMapper;

    @Autowired
    DataSyncPostgreSqlMigrationIntegrationTest(
            JdbcTemplate jdbcTemplate,
            SyncTemplateMapper templateMapper,
            SyncTaskMapper taskMapper,
            SyncExecutionMapper executionMapper,
            SyncCheckpointMapper checkpointMapper,
            SyncExecutionRecoveryPlanMapper recoveryPlanMapper,
            SyncErrorSampleMapper errorSampleMapper,
            SyncIncidentRecordMapper incidentMapper,
            SyncAuditRecordMapper auditMapper,
            SyncCallbackIdempotencyMapper idempotencyMapper,
            SyncTaskManagementReceiptOutboxMapper receiptOutboxMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.templateMapper = templateMapper;
        this.taskMapper = taskMapper;
        this.executionMapper = executionMapper;
        this.checkpointMapper = checkpointMapper;
        this.recoveryPlanMapper = recoveryPlanMapper;
        this.errorSampleMapper = errorSampleMapper;
        this.incidentMapper = incidentMapper;
        this.auditMapper = auditMapper;
        this.idempotencyMapper = idempotencyMapper;
        this.receiptOutboxMapper = receiptOutboxMapper;
    }

    /**
     * 验证 data-sync PostgreSQL 基线和关键读写链路。
     *
     * <p>这里不是覆盖所有业务分支，而是建立迁移烟测基线：
     * 只要表结构、分页、布尔映射、执行器租约 SQL、outbox 状态推进和保留期清理能在真实 PostgreSQL 上通过，
     * 后续服务层单测覆盖的业务流程就不会因为底层 DDL 或 SQL 方言错误整体不可用。</p>
     */
    @Test
    void shouldApplyDataSyncSchemaAndPersistCoreFactsThroughMyBatis() {
        assertPostgreSqlSchemaBaseline();

        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        SyncTemplate template = null;
        SyncTask task = null;
        SyncExecution execution = null;
        SyncCheckpoint checkpoint = null;
        SyncExecutionRecoveryPlan recoveryPlan = null;
        SyncErrorSample errorSample = null;
        SyncIncidentRecord incident = null;
        SyncAuditRecord audit = null;
        SyncCallbackIdempotency idempotency = null;
        SyncTaskManagementReceiptOutbox receipt = null;
        try {
            template = insertTemplate(suffix);
            task = insertTask(template, suffix);
            execution = insertExecution(task);
            checkpoint = insertCheckpoint(task, execution);
            recoveryPlan = insertRecoveryPlan(task, execution);
            errorSample = insertErrorSample(task, execution);
            incident = insertIncident(task, execution, suffix);
            audit = insertAudit(template, task, execution, suffix);
            idempotency = insertIdempotency(task, execution, suffix);
            receipt = insertReceiptOutbox(task, execution, suffix);

            assertThat(template.getId()).isPositive();
            assertThat(template.getEnabled()).isTrue();
            assertTemplateScopeColumnsAreMapped(template);
            assertThat(task.getId()).isPositive();
            assertThat(task.getAttentionRequired()).isFalse();
            assertThat(execution.getId()).isPositive();
            assertThat(checkpoint.getId()).isPositive();
            assertThat(recoveryPlan.getId()).isPositive();
            assertThat(errorSample.getRetryable()).isTrue();

            assertPaginationUsesPostgreSqlDialect(template);
            assertExecutionLeaseSqlIsPostgreSqlCompatible(execution);
            assertRecoveryPlanStateTransitionIsAtomic(recoveryPlan);
            assertReceiptOutboxSqlIsPostgreSqlCompatible(receipt);
            assertIdempotencyCleanupUsesPostgreSqlCte(idempotency);
        } finally {
            deleteIntegrationFacts(receipt, idempotency, audit, incident, errorSample, recoveryPlan, checkpoint, execution, task, template);
        }
    }

    /**
     * 校验连接实际进入 data_sync schema，并且 Flyway 已成功登记 V1。
     */
    private void assertPostgreSqlSchemaBaseline() {
        String currentSchema = jdbcTemplate.queryForObject("SELECT current_schema()", String.class);
        Integer flywaySuccessCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success = true",
                Integer.class
        );
        Integer flywayV2SuccessCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '2' AND success = true",
                Integer.class
        );
        Integer tableCount = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM information_schema.tables
                WHERE table_schema = 'data_sync'
                  AND table_name IN (
                      'data_sync_template',
                      'data_sync_task',
                      'data_sync_execution',
                      'data_sync_callback_idempotency',
                      'data_sync_task_management_receipt_outbox',
                      'data_sync_checkpoint',
                      'data_sync_execution_recovery_plan',
                      'data_sync_error_sample',
                      'data_sync_incident_record',
                      'data_sync_audit_record'
                  )
                """, Integer.class);

        assertThat(currentSchema).isEqualTo("data_sync");
        assertThat(flywaySuccessCount).isEqualTo(1);
        assertThat(flywayV2SuccessCount).isEqualTo(1);
        assertThat(tableCount).isEqualTo(10);
        assertTemplateScopeColumnsExist();
    }

    private void assertTemplateScopeColumnsExist() {
        Integer columnCount = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM information_schema.columns
                WHERE table_schema = 'data_sync'
                  AND table_name = 'data_sync_template'
                  AND column_name IN ('sync_scope_type', 'object_mapping_config', 'custom_sql_config')
                """, Integer.class);
        assertThat(columnCount).isEqualTo(3);
    }

    /**
     * 创建同步模板，验证 TEXT JSON、BOOLEAN enabled 和低敏 connector fact 可写入 PostgreSQL。
     */
    private SyncTemplate insertTemplate(String suffix) {
        SyncTemplate template = new SyncTemplate();
        template.setTenantId(930001L);
        template.setProjectId(930101L);
        template.setWorkspaceId(930201L);
        template.setName("pg-data-sync-template-" + suffix);
        template.setDescription("PostgreSQL migration integration template");
        template.setSourceDatasourceId(930301L);
        template.setTargetDatasourceId(930302L);
        template.setSourceSchemaName("public");
        template.setSourceObjectName("customer_source");
        template.setTargetSchemaName("warehouse");
        template.setTargetObjectName("customer_target");
        template.setSourceConnectorType("MYSQL");
        template.setTargetConnectorType("POSTGRESQL");
        template.setSyncMode("CUSTOM_SQL_QUERY");
        template.setSyncScopeType("CUSTOM_SQL_QUERY");
        template.setWriteStrategy("UPSERT");
        template.setPrimaryKeyField("id");
        template.setIncrementalField("updated_at");
        template.setFieldMappingConfig("{\"id\":\"id\",\"name\":\"name\"}");
        template.setObjectMappingConfig("{\"mappings\":[]}");
        template.setCustomSqlConfig("{\"statementRef\":\"integration.customer_safe_select\",\"digest\":\"low-sensitive\"}");
        template.setFilterConfig("{\"safePreview\":true}");
        template.setPartitionConfig("{\"partition\":\"single\"}");
        template.setRetryPolicy("{\"maxAttempts\":3}");
        template.setTimeoutPolicy("{\"timeoutSeconds\":60}");
        template.setEnabled(true);
        template.setCreatedBy(930401L);
        template.setUpdatedBy(930401L);
        templateMapper.insert(template);
        return template;
    }

    private void assertTemplateScopeColumnsAreMapped(SyncTemplate template) {
        SyncTemplate persisted = templateMapper.selectById(template.getId());
        assertThat(persisted.getSyncScopeType()).isEqualTo("CUSTOM_SQL_QUERY");
        assertThat(persisted.getObjectMappingConfig()).contains("\"mappings\"");
        assertThat(persisted.getCustomSqlConfig()).contains("integration.customer_safe_select");
    }

    /**
     * 创建同步任务，验证任务状态、审批状态、布尔人工介入标记和时间自动填充。
     */
    private SyncTask insertTask(SyncTemplate template, String suffix) {
        SyncTask task = new SyncTask();
        task.setTenantId(template.getTenantId());
        task.setProjectId(template.getProjectId());
        task.setWorkspaceId(template.getWorkspaceId());
        task.setTemplateId(template.getId());
        task.setName("pg-data-sync-task-" + suffix);
        task.setCurrentState("QUEUED");
        task.setApprovalState("APPROVED");
        task.setPriority("MEDIUM");
        task.setScheduleConfig("{\"type\":\"manual\"}");
        task.setRunMode("MANUAL");
        task.setTriggerType("INTEGRATION_TEST");
        task.setOwnerId(930401L);
        task.setAttentionRequired(false);
        task.setDescription("PostgreSQL migration integration task");
        taskMapper.insert(task);
        return task;
    }

    /**
     * 创建排队中的执行记录，为后续 claim/heartbeat/outbox/恢复计划测试提供执行锚点。
     */
    private SyncExecution insertExecution(SyncTask task) {
        SyncExecution execution = new SyncExecution();
        execution.setTenantId(task.getTenantId());
        execution.setProjectId(task.getProjectId());
        execution.setWorkspaceId(task.getWorkspaceId());
        execution.setSyncTaskId(task.getId());
        execution.setExecutionNo(1L);
        execution.setExecutionState("QUEUED");
        execution.setTriggerType("INTEGRATION_TEST");
        execution.setQueuedAt(LocalDateTime.now().minusSeconds(2));
        execution.setRecordsRead(0L);
        execution.setRecordsWritten(0L);
        execution.setFailedRecordCount(0L);
        execution.setTriggeredBy(task.getOwnerId());
        execution.setDeferCount(0);
        executionMapper.insert(execution);
        return execution;
    }

    private SyncCheckpoint insertCheckpoint(SyncTask task, SyncExecution execution) {
        SyncCheckpoint checkpoint = new SyncCheckpoint();
        checkpoint.setTenantId(task.getTenantId());
        checkpoint.setProjectId(task.getProjectId());
        checkpoint.setWorkspaceId(task.getWorkspaceId());
        checkpoint.setSyncTaskId(task.getId());
        checkpoint.setExecutionId(execution.getId());
        checkpoint.setCheckpointType("ID_RANGE");
        checkpoint.setCheckpointValue("{\"lastId\":100}");
        checkpoint.setShardOrPartition("shard-0");
        checkpoint.setRecordsRead(100L);
        checkpoint.setRecordsWritten(100L);
        checkpoint.setCheckpointTime(LocalDateTime.now().minusDays(40));
        checkpointMapper.insert(checkpoint);
        return checkpoint;
    }

    private SyncExecutionRecoveryPlan insertRecoveryPlan(SyncTask task, SyncExecution execution) {
        SyncExecutionRecoveryPlan plan = new SyncExecutionRecoveryPlan();
        plan.setTenantId(task.getTenantId());
        plan.setProjectId(task.getProjectId());
        plan.setWorkspaceId(task.getWorkspaceId());
        plan.setSyncTaskId(task.getId());
        plan.setExecutionId(execution.getId());
        plan.setRecoveryType("REPLAY");
        plan.setSourceExecutionId(execution.getId());
        plan.setSourceCheckpointId(null);
        plan.setReason("PostgreSQL migration integration replay plan");
        plan.setPlanState("CREATED");
        recoveryPlanMapper.insert(plan);
        return plan;
    }

    private SyncErrorSample insertErrorSample(SyncTask task, SyncExecution execution) {
        SyncErrorSample sample = new SyncErrorSample();
        sample.setTenantId(task.getTenantId());
        sample.setProjectId(task.getProjectId());
        sample.setWorkspaceId(task.getWorkspaceId());
        sample.setSyncTaskId(task.getId());
        sample.setExecutionId(execution.getId());
        sample.setErrorType("SOURCE_READ_ERROR");
        sample.setErrorCode("PG_INTEGRATION_SAMPLE");
        sample.setErrorMessage("low-sensitive error summary");
        sample.setSourceRecordKey("source-key-1");
        sample.setTargetRecordKey("target-key-1");
        sample.setSamplePayload("{\"masked\":true}");
        sample.setRetryable(true);
        errorSampleMapper.insert(sample);
        return sample;
    }

    private SyncIncidentRecord insertIncident(SyncTask task, SyncExecution execution, String suffix) {
        SyncIncidentRecord incident = new SyncIncidentRecord();
        incident.setTenantId(task.getTenantId());
        incident.setProjectId(task.getProjectId());
        incident.setWorkspaceId(task.getWorkspaceId());
        incident.setSyncTaskId(task.getId());
        incident.setExecutionId(execution.getId());
        incident.setIncidentType("EXECUTOR_UNSTABLE");
        incident.setSeverity("P3");
        incident.setIncidentStatus("OPEN");
        incident.setTitle("pg-data-sync-incident-" + suffix);
        incident.setDescription("low-sensitive incident summary");
        incident.setOperatorId(930401L);
        incident.setOperatorRole("SERVICE_ACCOUNT");
        incidentMapper.insert(incident);
        return incident;
    }

    private SyncAuditRecord insertAudit(SyncTemplate template, SyncTask task, SyncExecution execution, String suffix) {
        SyncAuditRecord audit = new SyncAuditRecord();
        audit.setTenantId(template.getTenantId());
        audit.setProjectId(template.getProjectId());
        audit.setWorkspaceId(template.getWorkspaceId());
        audit.setTemplateId(template.getId());
        audit.setSyncTaskId(task.getId());
        audit.setExecutionId(execution.getId());
        audit.setActionType("RUN_TASK");
        audit.setActorId(930401L);
        audit.setActorRole("SERVICE_ACCOUNT");
        audit.setActionPayload("{\"summary\":\"integration\"}");
        audit.setResult("SUCCESS");
        audit.setTraceId("pg-data-sync-audit-" + suffix);
        auditMapper.insert(audit);
        return audit;
    }

    private SyncCallbackIdempotency insertIdempotency(SyncTask task, SyncExecution execution, String suffix) {
        SyncCallbackIdempotency idempotency = new SyncCallbackIdempotency();
        idempotency.setTenantId(task.getTenantId());
        idempotency.setSyncTaskId(task.getId());
        idempotency.setExecutionId(execution.getId());
        idempotency.setScopeKey(task.getId() + ":" + execution.getId());
        idempotency.setAction("HEARTBEAT");
        idempotency.setIdempotencyKey("pg-idempotency-" + suffix);
        idempotency.setExecutorId("pg-data-sync-worker");
        idempotency.setRequestDigest("low-sensitive digest");
        idempotency.setCallbackState("SUCCEEDED");
        idempotency.setResponseSummary("ok");
        idempotency.setFirstSeenTime(LocalDateTime.now().minusDays(40));
        idempotency.setLastSeenTime(LocalDateTime.now().minusDays(40));
        idempotencyMapper.insert(idempotency);
        return idempotency;
    }

    private SyncTaskManagementReceiptOutbox insertReceiptOutbox(SyncTask task, SyncExecution execution, String suffix) {
        SyncTaskManagementReceiptOutbox receipt = new SyncTaskManagementReceiptOutbox();
        receipt.setReceiptId("pg-data-sync-receipt-" + suffix);
        receipt.setTenantId(task.getTenantId());
        receipt.setProjectId(task.getProjectId());
        receipt.setWorkspaceId(task.getWorkspaceId());
        receipt.setSyncTaskId(task.getId());
        receipt.setSyncExecutionId(execution.getId());
        receipt.setEventType("COMPLETE");
        receipt.setSourceService("data-sync");
        receipt.setOutboxState("PENDING");
        receipt.setAttemptCount(0);
        receipt.setMaxAttemptCount(6);
        receipt.setNextRetryAt(LocalDateTime.now().minusSeconds(1));
        receipt.setActorId(930401L);
        receipt.setActorRole("SERVICE_ACCOUNT");
        receipt.setTraceId("pg-data-sync-receipt-" + suffix);
        receipt.setPayloadJson("{\"receipt\":\"low-sensitive\"}");
        receiptOutboxMapper.insert(receipt);
        return receipt;
    }

    private void assertPaginationUsesPostgreSqlDialect(SyncTemplate template) {
        Page<SyncTemplate> page = templateMapper.selectPage(
                new Page<>(1, 10),
                new LambdaQueryWrapper<SyncTemplate>()
                        .eq(SyncTemplate::getTenantId, template.getTenantId())
                        .eq(SyncTemplate::getName, template.getName())
                        .orderByAsc(SyncTemplate::getId)
        );
        assertThat(page.getTotal()).isEqualTo(1);
        assertThat(page.getRecords()).extracting(SyncTemplate::getId).containsExactly(template.getId());
    }

    private void assertExecutionLeaseSqlIsPostgreSqlCompatible(SyncExecution execution) {
        SyncExecution candidate = executionMapper.selectNextClaimCandidate(execution.getTenantId());
        assertThat(candidate).isNotNull();
        assertThat(candidate.getId()).isEqualTo(execution.getId());

        int claimed = executionMapper.claimQueuedExecution(execution.getId(), "pg-data-sync-worker", 60);
        assertThat(claimed).isEqualTo(1);

        int heartbeat = executionMapper.heartbeatLease(execution.getId(), "pg-data-sync-worker", 10L, 9L, 60);
        assertThat(heartbeat).isEqualTo(1);

        SyncExecution refreshed = executionMapper.selectById(execution.getId());
        assertThat(refreshed.getExecutionState()).isEqualTo("RUNNING");
        assertThat(refreshed.getExecutorId()).isEqualTo("pg-data-sync-worker");
        assertThat(refreshed.getHeartbeatTime()).isNotNull();
        assertThat(refreshed.getLeaseExpireTime()).isAfter(LocalDateTime.now().minusSeconds(1));
    }

    private void assertRecoveryPlanStateTransitionIsAtomic(SyncExecutionRecoveryPlan recoveryPlan) {
        assertThat(recoveryPlanMapper.selectByExecutionId(recoveryPlan.getExecutionId())).isNotNull();
        assertThat(recoveryPlanMapper.markPlanState(recoveryPlan.getExecutionId(), "CREATED", "CLAIMED")).isEqualTo(1);
        assertThat(recoveryPlanMapper.markPlanState(recoveryPlan.getExecutionId(), "CREATED", "CONSUMED")).isZero();
    }

    private void assertReceiptOutboxSqlIsPostgreSqlCompatible(SyncTaskManagementReceiptOutbox receipt) {
        List<SyncTaskManagementReceiptOutbox> dueReceipts = receiptOutboxMapper.selectDueReceipts(10, 1);
        assertThat(dueReceipts).extracting(SyncTaskManagementReceiptOutbox::getId).contains(receipt.getId());
        assertThat(receiptOutboxMapper.markDelivering(receipt.getId(), 1)).isEqualTo(1);
        assertThat(receiptOutboxMapper.markDelivered(receipt.getId())).isEqualTo(1);
    }

    private void assertIdempotencyCleanupUsesPostgreSqlCte(SyncCallbackIdempotency idempotency) {
        int deleted = idempotencyMapper.deleteExpiredRecords(LocalDateTime.now().minusDays(30), 10);
        assertThat(deleted).isGreaterThanOrEqualTo(1);
        assertThat(idempotencyMapper.selectById(idempotency.getId())).isNull();
    }

    /**
     * 按依赖反向清理本次测试事实。
     *
     * <p>生产 DDL 故意不创建级联外键，因此测试也显式按依赖顺序删除。
     * 这能更真实地模拟未来数据保留、归档和运维清理任务需要遵守的事实顺序。</p>
     */
    private void deleteIntegrationFacts(
            SyncTaskManagementReceiptOutbox receipt,
            SyncCallbackIdempotency idempotency,
            SyncAuditRecord audit,
            SyncIncidentRecord incident,
            SyncErrorSample errorSample,
            SyncExecutionRecoveryPlan recoveryPlan,
            SyncCheckpoint checkpoint,
            SyncExecution execution,
            SyncTask task,
            SyncTemplate template) {
        if (receipt != null && receipt.getId() != null) {
            jdbcTemplate.update("DELETE FROM data_sync_task_management_receipt_outbox WHERE id = ?", receipt.getId());
        }
        if (idempotency != null && idempotency.getId() != null) {
            jdbcTemplate.update("DELETE FROM data_sync_callback_idempotency WHERE id = ?", idempotency.getId());
        }
        if (audit != null && audit.getId() != null) {
            jdbcTemplate.update("DELETE FROM data_sync_audit_record WHERE id = ?", audit.getId());
        }
        if (incident != null && incident.getId() != null) {
            jdbcTemplate.update("DELETE FROM data_sync_incident_record WHERE id = ?", incident.getId());
        }
        if (errorSample != null && errorSample.getId() != null) {
            jdbcTemplate.update("DELETE FROM data_sync_error_sample WHERE id = ?", errorSample.getId());
        }
        if (recoveryPlan != null && recoveryPlan.getId() != null) {
            jdbcTemplate.update("DELETE FROM data_sync_execution_recovery_plan WHERE id = ?", recoveryPlan.getId());
        }
        if (checkpoint != null && checkpoint.getId() != null) {
            jdbcTemplate.update("DELETE FROM data_sync_checkpoint WHERE id = ?", checkpoint.getId());
        }
        if (execution != null && execution.getId() != null) {
            jdbcTemplate.update("DELETE FROM data_sync_execution WHERE id = ?", execution.getId());
        }
        if (task != null && task.getId() != null) {
            jdbcTemplate.update("DELETE FROM data_sync_task WHERE id = ?", task.getId());
        }
        if (template != null && template.getId() != null) {
            jdbcTemplate.update("DELETE FROM data_sync_template WHERE id = ?", template.getId());
        }
    }
}
