/**
 * @Author : Cui
 * @Date: 2026/07/03 00:00
 * @Description DataSmartGovernBackend - TaskManagementPostgreSqlMigrationIntegrationTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.czh.datasmart.govern.task.entity.AgentAsyncTaskCommandInbox;
import com.czh.datasmart.govern.task.entity.DataSyncWorkerCommandOutbox;
import com.czh.datasmart.govern.task.entity.DataSyncWorkerExecutionReceipt;
import com.czh.datasmart.govern.task.entity.Task;
import com.czh.datasmart.govern.task.entity.TaskCallbackIdempotency;
import com.czh.datasmart.govern.task.entity.TaskDraft;
import com.czh.datasmart.govern.task.entity.TaskExecutionLog;
import com.czh.datasmart.govern.task.entity.TaskExecutionRun;
import com.czh.datasmart.govern.task.mapper.AgentAsyncTaskCommandInboxMapper;
import com.czh.datasmart.govern.task.mapper.DataSyncWorkerCommandOutboxMapper;
import com.czh.datasmart.govern.task.mapper.DataSyncWorkerExecutionReceiptMapper;
import com.czh.datasmart.govern.task.mapper.TaskCallbackIdempotencyMapper;
import com.czh.datasmart.govern.task.mapper.TaskDraftMapper;
import com.czh.datasmart.govern.task.mapper.TaskExecutionLogMapper;
import com.czh.datasmart.govern.task.mapper.TaskExecutionRunMapper;
import com.czh.datasmart.govern.task.mapper.TaskMapper;
import com.czh.datasmart.govern.task.support.AgentAsyncTaskCommandState;
import com.czh.datasmart.govern.task.support.DataSyncWorkerCommandOutboxStatus;
import com.czh.datasmart.govern.task.support.DataSyncWorkerExecutionReceiptEventType;
import com.czh.datasmart.govern.task.support.TaskDraftStatus;
import com.czh.datasmart.govern.task.support.TaskExecutionRunState;
import com.czh.datasmart.govern.task.support.TaskStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * task-management PostgreSQL 真实集成测试。
 *
 * <p>这个测试不是为了覆盖所有任务中心业务分支，而是建立迁移烟测基线：</p>
 * <p>1. Flyway 能在 {@code task_management} schema 中创建 8 张任务域表；</p>
 * <p>2. MyBatis-Plus 能在 PostgreSQL identity 主键下回填 ID，并用 PostgreSQL 方言做分页；</p>
 * <p>3. 任务认领、心跳续租、超时 run 扫描、草稿转换这些自定义 SQL 已经脱离 MySQL 函数；</p>
 * <p>4. Agent command inbox 与 task_data_sync_* 桥接表可以写入 TEXT JSON 和 BOOLEAN 字段。</p>
 *
 * <p>运行安全边界：</p>
 * <p>只有显式设置 {@code DATASMART_POSTGRES_INTEGRATION_ENABLED=true} 时才会执行；
 * 测试不创建或删除数据库，不执行 Flyway clean，不读取真实客户数据；
 * 写入样本全部使用 931xxx 测试租户、项目和随机后缀，并在 finally 中按依赖反向清理。</p>
 */
@SpringBootTest(properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "datasmart.task-management.agent-async-commands.kafka.enabled=false",
        "datasmart.task-management.agent-async-worker.enabled=false",
        "datasmart.task-management.agent-async-worker.scheduler-enabled=false",
        "datasmart.task-management.agent-async-worker.data-sync-outbox-scheduler-enabled=false"
})
@EnabledIfEnvironmentVariable(named = "DATASMART_POSTGRES_INTEGRATION_ENABLED", matches = "(?i)true")
class TaskManagementPostgreSqlMigrationIntegrationTest {

    private final JdbcTemplate jdbcTemplate;
    private final TaskMapper taskMapper;
    private final TaskDraftMapper taskDraftMapper;
    private final TaskExecutionLogMapper taskExecutionLogMapper;
    private final TaskExecutionRunMapper taskExecutionRunMapper;
    private final TaskCallbackIdempotencyMapper callbackIdempotencyMapper;
    private final AgentAsyncTaskCommandInboxMapper agentInboxMapper;
    private final DataSyncWorkerCommandOutboxMapper dataSyncOutboxMapper;
    private final DataSyncWorkerExecutionReceiptMapper dataSyncReceiptMapper;

    @Autowired
    TaskManagementPostgreSqlMigrationIntegrationTest(
            JdbcTemplate jdbcTemplate,
            TaskMapper taskMapper,
            TaskDraftMapper taskDraftMapper,
            TaskExecutionLogMapper taskExecutionLogMapper,
            TaskExecutionRunMapper taskExecutionRunMapper,
            TaskCallbackIdempotencyMapper callbackIdempotencyMapper,
            AgentAsyncTaskCommandInboxMapper agentInboxMapper,
            DataSyncWorkerCommandOutboxMapper dataSyncOutboxMapper,
            DataSyncWorkerExecutionReceiptMapper dataSyncReceiptMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.taskMapper = taskMapper;
        this.taskDraftMapper = taskDraftMapper;
        this.taskExecutionLogMapper = taskExecutionLogMapper;
        this.taskExecutionRunMapper = taskExecutionRunMapper;
        this.callbackIdempotencyMapper = callbackIdempotencyMapper;
        this.agentInboxMapper = agentInboxMapper;
        this.dataSyncOutboxMapper = dataSyncOutboxMapper;
        this.dataSyncReceiptMapper = dataSyncReceiptMapper;
    }

    /**
     * 验证 task-management PostgreSQL 基线和关键读写链路。
     */
    @Test
    void shouldApplyTaskManagementSchemaAndPersistCoreFactsThroughMyBatis() {
        assertPostgreSqlSchemaBaseline();

        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Task task = null;
        TaskDraft draft = null;
        TaskExecutionLog log = null;
        TaskExecutionRun run = null;
        TaskCallbackIdempotency idempotency = null;
        AgentAsyncTaskCommandInbox inbox = null;
        DataSyncWorkerCommandOutbox outbox = null;
        DataSyncWorkerExecutionReceipt receipt = null;
        try {
            task = insertTask(suffix);
            draft = insertDraft(suffix);
            log = insertLog(task);
            run = insertRun(task);
            idempotency = insertIdempotency(task, run, suffix);
            inbox = insertAgentInbox(task, suffix);
            outbox = insertDataSyncOutbox(task, suffix);
            receipt = insertDataSyncReceipt(task, outbox, suffix);

            assertThat(task.getId()).isPositive();
            assertThat(draft.getId()).isPositive();
            assertThat(log.getId()).isPositive();
            assertThat(run.getId()).isPositive();
            assertThat(idempotency.getId()).isPositive();
            assertThat(inbox.getId()).isPositive();
            assertThat(outbox.getId()).isPositive();
            assertThat(receipt.getId()).isPositive();
            assertThat(outbox.getPayloadTruncated()).isFalse();
            assertThat(receipt.getCheckpointPersisted()).isTrue();

            assertPaginationUsesPostgreSqlDialect(task);
            assertTaskLeaseSqlIsPostgreSqlCompatible(task);
            assertExecutionRunSqlIsPostgreSqlCompatible(run);
            assertDraftStateTransitionSqlIsPostgreSqlCompatible(draft, task);
        } finally {
            deleteIntegrationFacts(receipt, outbox, inbox, idempotency, run, log, draft, task);
        }
    }

    /**
     * 校验当前连接的 schema 和 Flyway V1 状态。
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
                WHERE table_schema = 'task_management'
                  AND table_name IN (
                      'task',
                      'task_draft',
                      'task_execution_log',
                      'task_execution_run',
                      'task_callback_idempotency',
                      'agent_async_task_command_inbox',
                      'task_data_sync_worker_command_outbox',
                      'task_data_sync_worker_execution_receipt'
                  )
                """, Integer.class);

        assertThat(currentSchema).isEqualTo("task_management");
        assertThat(flywaySuccessCount).isEqualTo(1);
        assertThat(tableCount).isEqualTo(8);
    }

    /**
     * 创建任务主表样本，用于验证 identity、BOOLEAN、分页和认领 SQL。
     */
    private Task insertTask(String suffix) {
        Task task = new Task();
        task.setName("pg-task-" + suffix);
        task.setDescription("PostgreSQL migration integration task");
        task.setType("DATA_SYNC");
        task.setCreationIdempotencyKey("pg-task-create-" + suffix);
        task.setTenantId(931001L);
        task.setOwnerId(931401L);
        task.setProjectId(931101L);
        task.setStatus(TaskStatus.PENDING);
        task.setParams("{\"source\":\"integration\"}");
        task.setProgress(0);
        task.setPriority("HIGH");
        task.setRetryCount(0);
        task.setMaxRetryCount(3);
        task.setDeferCount(0);
        task.setMaxDeferCount(20);
        task.setQueuedTime(LocalDateTime.now().minusSeconds(5));
        task.setAttentionRequired(false);
        task.setTimeoutSeconds(3600);
        taskMapper.insert(task);
        return task;
    }

    private TaskDraft insertDraft(String suffix) {
        LocalDateTime now = LocalDateTime.now();
        TaskDraft draft = new TaskDraft();
        draft.setName("pg-draft-" + suffix);
        draft.setDescription("PostgreSQL migration integration draft");
        draft.setType("DATA_SYNC");
        draft.setTenantId(931001L);
        draft.setOwnerId(931401L);
        draft.setProjectId(931101L);
        draft.setStatus(TaskDraftStatus.APPROVED);
        draft.setParams("{\"draft\":true}");
        draft.setPriority("MEDIUM");
        draft.setMaxRetryCount(3);
        draft.setMaxDeferCount(20);
        draft.setSourceType("AGENT");
        draft.setSourceRef("pg-draft-source-" + suffix);
        draft.setCreatedBy(931401L);
        draft.setSubmittedBy(931401L);
        draft.setApprovedBy(931402L);
        draft.setApprovalComment("integration approved");
        draft.setCreateTime(now);
        draft.setUpdateTime(now);
        draft.setSubmitTime(now.minusMinutes(2));
        draft.setApprovalTime(now.minusMinutes(1));
        taskDraftMapper.insert(draft);
        return draft;
    }

    private TaskExecutionLog insertLog(Task task) {
        TaskExecutionLog log = new TaskExecutionLog();
        log.setTaskId(task.getId());
        log.setAction("CREATE");
        log.setToStatus(TaskStatus.PENDING);
        log.setMessage("integration task created");
        log.setOperator("integration-test");
        log.setDetails("{\"safe\":true}");
        taskExecutionLogMapper.insert(log);
        return log;
    }

    private TaskExecutionRun insertRun(Task task) {
        TaskExecutionRun run = new TaskExecutionRun();
        run.setTaskId(task.getId());
        run.setRunNo(1L);
        run.setExecutorId("pg-task-worker");
        run.setState(TaskExecutionRunState.RUNNING);
        run.setTriggerType("INTEGRATION_TEST");
        run.setTriggeredBy(task.getOwnerId());
        run.setStartedAt(LocalDateTime.now().minusMinutes(5));
        run.setHeartbeatAt(LocalDateTime.now().minusMinutes(4));
        run.setLeaseExpireTime(LocalDateTime.now().minusSeconds(5));
        run.setProgress(10);
        run.setCheckpoint("{\"step\":\"started\"}");
        taskExecutionRunMapper.insert(run);
        return run;
    }

    private TaskCallbackIdempotency insertIdempotency(Task task, TaskExecutionRun run, String suffix) {
        LocalDateTime now = LocalDateTime.now();
        TaskCallbackIdempotency idempotency = new TaskCallbackIdempotency();
        idempotency.setTaskId(task.getId());
        idempotency.setAction("PROGRESS");
        idempotency.setIdempotencyKey("pg-callback-" + suffix);
        idempotency.setRunId(run.getId());
        idempotency.setExecutorId("pg-task-worker");
        idempotency.setRequestDigest("low-sensitive digest");
        idempotency.setCallbackState("SUCCEEDED");
        idempotency.setResponseSummary("ok");
        idempotency.setFirstSeenTime(now);
        idempotency.setLastSeenTime(now);
        callbackIdempotencyMapper.insert(idempotency);
        return idempotency;
    }

    private AgentAsyncTaskCommandInbox insertAgentInbox(Task task, String suffix) {
        LocalDateTime now = LocalDateTime.now();
        AgentAsyncTaskCommandInbox inbox = new AgentAsyncTaskCommandInbox();
        inbox.setCommandId("pg-agent-command-" + suffix);
        inbox.setIdempotencyKey("pg-agent-idempotency-" + suffix);
        inbox.setSchemaVersion("agent-command.v1");
        inbox.setCommandType("AGENT_TOOL_ASYNC_TASK_REQUESTED");
        inbox.setAuditId("pg-agent-audit-" + suffix);
        inbox.setSessionId("pg-agent-session-" + suffix);
        inbox.setRunId("pg-agent-run-" + suffix);
        inbox.setToolCode("data-sync.execute");
        inbox.setTargetService("task-management");
        inbox.setTargetEndpoint(null);
        inbox.setTenantId(task.getTenantId());
        inbox.setProjectId(task.getProjectId());
        inbox.setWorkspaceId(931201L);
        inbox.setActorId(String.valueOf(task.getOwnerId()));
        inbox.setTraceId("pg-agent-trace-" + suffix);
        inbox.setPayloadReference("agent-tool-audit://pg/session/run/audit/plan-arguments");
        inbox.setArgumentNames("[\"syncTemplateId\"]");
        inbox.setSensitiveArgumentNames("[]");
        inbox.setConsumeState(AgentAsyncTaskCommandState.TASK_CREATED);
        inbox.setTaskId(task.getId());
        inbox.setFirstSeenTime(now);
        inbox.setLastSeenTime(now);
        agentInboxMapper.insert(inbox);
        return inbox;
    }

    private DataSyncWorkerCommandOutbox insertDataSyncOutbox(Task task, String suffix) {
        DataSyncWorkerCommandOutbox outbox = new DataSyncWorkerCommandOutbox();
        outbox.setOutboxId("pg-task-datasync-outbox-" + suffix);
        outbox.setCommandId("pg-datasync-command-" + suffix);
        outbox.setIdempotencyKey("pg-datasync-idempotency-" + suffix);
        outbox.setTaskId(task.getId());
        outbox.setAgentRunId("pg-agent-run-" + suffix);
        outbox.setAgentSessionId("pg-agent-session-" + suffix);
        outbox.setAuditId("pg-agent-audit-" + suffix);
        outbox.setToolCode("data-sync.execute");
        outbox.setTargetService("data-sync");
        outbox.setOperation("DATA_SYNC_EXECUTE");
        outbox.setTenantId(task.getTenantId());
        outbox.setProjectId(task.getProjectId());
        outbox.setWorkspaceId(931201L);
        outbox.setActorId(String.valueOf(task.getOwnerId()));
        outbox.setTraceId("pg-datasync-trace-" + suffix);
        outbox.setSyncTemplateId(931501L);
        outbox.setStatus(DataSyncWorkerCommandOutboxStatus.PENDING.name());
        outbox.setAttemptCount(0);
        outbox.setPayloadJson("{\"syncTemplateId\":931501}");
        outbox.setPayloadSizeBytes(24);
        outbox.setPayloadTruncated(false);
        outbox.setSideEffectStarted(false);
        outbox.setSideEffectExecuted(false);
        dataSyncOutboxMapper.insert(outbox);
        return outbox;
    }

    private DataSyncWorkerExecutionReceipt insertDataSyncReceipt(Task task, DataSyncWorkerCommandOutbox outbox, String suffix) {
        DataSyncWorkerExecutionReceipt receipt = new DataSyncWorkerExecutionReceipt();
        receipt.setReceiptId("pg-task-datasync-receipt-" + suffix);
        receipt.setCommandId(outbox.getCommandId());
        receipt.setOutboxId(outbox.getOutboxId());
        receipt.setTaskId(task.getId());
        receipt.setAgentRunId(outbox.getAgentRunId());
        receipt.setAgentSessionId(outbox.getAgentSessionId());
        receipt.setAuditId(outbox.getAuditId());
        receipt.setTenantId(task.getTenantId());
        receipt.setProjectId(task.getProjectId());
        receipt.setWorkspaceId(outbox.getWorkspaceId());
        receipt.setSyncTaskId(931601L);
        receipt.setSyncExecutionId(931701L);
        receipt.setEventType(DataSyncWorkerExecutionReceiptEventType.CHECKPOINT.name());
        receipt.setEventTime(LocalDateTime.now());
        receipt.setExecutorId("pg-datasync-runner");
        receipt.setSourceService("data-sync");
        receipt.setBatchRecordsRead(100L);
        receipt.setBatchRecordsWritten(98L);
        receipt.setBatchFailedRecordCount(2L);
        receipt.setTotalRecordsRead(100L);
        receipt.setTotalRecordsWritten(98L);
        receipt.setTotalFailedRecordCount(2L);
        receipt.setProgressPercent(50);
        receipt.setEndOfSource(false);
        receipt.setCompleted(false);
        receipt.setFailed(false);
        receipt.setProgressReported(true);
        receipt.setCheckpointPersisted(true);
        receipt.setCheckpointType("PRIMARY_KEY");
        receipt.setCheckpointValueVisibility("HASHED");
        receipt.setWarningCount(1);
        receipt.setWarningSummary("low-sensitive warning summary");
        dataSyncReceiptMapper.insert(receipt);
        return receipt;
    }

    private void assertPaginationUsesPostgreSqlDialect(Task task) {
        Page<Task> page = taskMapper.selectPage(
                new Page<>(1, 10),
                new LambdaQueryWrapper<Task>()
                        .eq(Task::getTenantId, task.getTenantId())
                        .eq(Task::getName, task.getName())
                        .orderByAsc(Task::getId)
        );
        assertThat(page.getTotal()).isEqualTo(1);
        assertThat(page.getRecords()).extracting(Task::getId).containsExactly(task.getId());
    }

    private void assertTaskLeaseSqlIsPostgreSqlCompatible(Task task) {
        Task candidate = taskMapper.selectNextClaimCandidate(task.getType(), task.getTenantId(), task.getOwnerId(), task.getProjectId());
        assertThat(candidate).isNotNull();
        assertThat(candidate.getId()).isEqualTo(task.getId());

        int claimed = taskMapper.claimTask(task.getId(), "pg-task-worker", 60);
        assertThat(claimed).isEqualTo(1);

        int heartbeat = taskMapper.heartbeatLease(task.getId(), "pg-task-worker", 20, "{\"step\":\"heartbeat\"}", 60);
        assertThat(heartbeat).isEqualTo(1);

        Task refreshed = taskMapper.selectById(task.getId());
        assertThat(refreshed.getStatus()).isEqualTo(TaskStatus.RUNNING);
        assertThat(refreshed.getCurrentExecutorId()).isEqualTo("pg-task-worker");
        assertThat(refreshed.getLeaseExpireTime()).isAfter(LocalDateTime.now().minusSeconds(1));
    }

    private void assertExecutionRunSqlIsPostgreSqlCompatible(TaskExecutionRun run) {
        assertThat(taskExecutionRunMapper.selectMaxRunNo(run.getTaskId())).isEqualTo(1L);
        assertThat(taskExecutionRunMapper.selectTimedOutRuns(10))
                .extracting(TaskExecutionRun::getId)
                .contains(run.getId());
        assertThat(taskExecutionRunMapper.finishRunningRun(run.getId(), TaskExecutionRunState.TIMEOUT, "integration timeout")).isEqualTo(1);
        assertThat(taskExecutionRunMapper.finishRunningRun(run.getId(), TaskExecutionRunState.FAILED, "duplicate finish")).isZero();
    }

    private void assertDraftStateTransitionSqlIsPostgreSqlCompatible(TaskDraft draft, Task task) {
        assertThat(taskDraftMapper.markConverting(draft.getId(), TaskDraftStatus.APPROVED, TaskDraftStatus.CONVERTING)).isEqualTo(1);
        assertThat(taskDraftMapper.markConverting(draft.getId(), TaskDraftStatus.APPROVED, TaskDraftStatus.CONVERTING)).isZero();
        assertThat(taskDraftMapper.markConverted(draft.getId(), task.getId(), TaskDraftStatus.CONVERTING, TaskDraftStatus.CONVERTED)).isEqualTo(1);
    }

    /**
     * 按依赖反向删除本测试写入的低敏样本。
     *
     * <p>生产 DDL 故意不设置级联外键，因此测试也显式按顺序删除。
     * 这能提醒后续开发者：任务事实、执行证据、outbox 和 receipt 的保留/归档应由业务策略控制，
     * 不能依赖数据库级联自动吞掉审计线索。</p>
     */
    private void deleteIntegrationFacts(
            DataSyncWorkerExecutionReceipt receipt,
            DataSyncWorkerCommandOutbox outbox,
            AgentAsyncTaskCommandInbox inbox,
            TaskCallbackIdempotency idempotency,
            TaskExecutionRun run,
            TaskExecutionLog log,
            TaskDraft draft,
            Task task) {
        if (receipt != null && receipt.getId() != null) {
            jdbcTemplate.update("DELETE FROM task_data_sync_worker_execution_receipt WHERE id = ?", receipt.getId());
        }
        if (outbox != null && outbox.getId() != null) {
            jdbcTemplate.update("DELETE FROM task_data_sync_worker_command_outbox WHERE id = ?", outbox.getId());
        }
        if (inbox != null && inbox.getId() != null) {
            jdbcTemplate.update("DELETE FROM agent_async_task_command_inbox WHERE id = ?", inbox.getId());
        }
        if (idempotency != null && idempotency.getId() != null) {
            jdbcTemplate.update("DELETE FROM task_callback_idempotency WHERE id = ?", idempotency.getId());
        }
        if (run != null && run.getId() != null) {
            jdbcTemplate.update("DELETE FROM task_execution_run WHERE id = ?", run.getId());
        }
        if (log != null && log.getId() != null) {
            jdbcTemplate.update("DELETE FROM task_execution_log WHERE id = ?", log.getId());
        }
        if (draft != null && draft.getId() != null) {
            jdbcTemplate.update("DELETE FROM task_draft WHERE id = ?", draft.getId());
        }
        if (task != null && task.getId() != null) {
            jdbcTemplate.update("DELETE FROM task WHERE id = ?", task.getId());
        }
    }
}
