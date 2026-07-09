/**
 * @Author : Cui
 * @Date: 2026/07/08 03:20
 * @Description DataSmart Govern Backend - SyncAutoSplitPkRealWorkerE2ETest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.czh.datasmart.govern.datasource.controller.dto.SyncBatchExecutionPlan;
import com.czh.datasmart.govern.datasource.controller.dto.SyncBatchRunOnceInternalRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncBatchRunOnceInternalResponse;
import com.czh.datasmart.govern.datasource.service.execution.SyncBatchConnectorRuntimeRunOnceService;
import com.czh.datasmart.govern.datasource.service.execution.SyncBatchExecutionPreparationService;
import com.czh.datasmart.govern.datasource.service.execution.jdbc.JdbcSyncBatchReader;
import com.czh.datasmart.govern.datasource.service.execution.jdbc.JdbcSyncBatchWriter;
import com.czh.datasmart.govern.datasource.service.execution.jdbc.MySqlSyncJdbcDialect;
import com.czh.datasmart.govern.datasource.service.execution.jdbc.PostgreSqlSyncJdbcDialect;
import com.czh.datasmart.govern.datasource.service.execution.jdbc.SyncJdbcConnectionProvider;
import com.czh.datasmart.govern.datasource.service.execution.jdbc.SyncJdbcDialectRegistry;
import com.czh.datasmart.govern.datasync.config.DataSyncDatasourceRunOnceProperties;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncObjectRetryRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncObjectRetryResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncRecoveryPlanWorkerResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncWorkerExecutionPlanView;
import com.czh.datasmart.govern.datasync.entity.SyncErrorSample;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncObjectExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.entity.SyncTemplate;
import com.czh.datasmart.govern.datasync.integration.datasource.partition.DatasourcePartitionRangeProbeClient;
import com.czh.datasmart.govern.datasync.integration.datasource.partition.DatasourcePartitionRangeProbeRequest;
import com.czh.datasmart.govern.datasync.integration.datasource.partition.DatasourcePartitionRangeProbeResponse;
import com.czh.datasmart.govern.datasync.integration.datasource.runonce.DatasourceDirtyRecordSample;
import com.czh.datasmart.govern.datasync.integration.datasource.runonce.DatasourceRunOnceClient;
import com.czh.datasmart.govern.datasync.integration.datasource.runonce.DatasourceRunOnceRequest;
import com.czh.datasmart.govern.datasync.integration.datasource.runonce.DatasourceRunOnceResponse;
import com.czh.datasmart.govern.datasync.mapper.SyncCheckpointMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncErrorSampleMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncObjectExecutionMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskMapper;
import com.czh.datasmart.govern.datasync.support.SyncExecutionState;
import com.czh.datasmart.govern.datasync.support.SyncObjectExecutionState;
import com.czh.datasmart.govern.datasync.support.SyncTaskState;
import com.czh.datasmart.govern.datasync.support.SyncTriggerType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 真实 MySQL -> PostgreSQL 的 AUTO_SPLIT_PK 分片同步、脏数据落库与 replay E2E。
 *
 * <p>这个测试是 data-sync 当前离线同步闭环里最接近真实产品执行路径的一条验收线。它不是只验证某个
 * JSON 配置能被解析，也不是 fake client 直接返回成功，而是把以下能力放进同一个任务生命周期：</p>
 *
 * <p>1. 在 MySQL 准备源表，在 PostgreSQL 准备目标表；</p>
 * <p>2. data-sync 读取模板中的 {@code AUTO_SPLIT_PK(id)}，调用 range-probe 获取 min/max/count；</p>
 * <p>3. 控制面生成 4 个 {@code ID_RANGE} 分片，并按 TaskGroup + channel 做有界并发派发；</p>
 * <p>4. 每个分片通过 datasource-management 的真实 JDBC Reader/Writer 写入 PostgreSQL；</p>
 * <p>5. 其中一个分片故意失败，验证成功分片不会回滚，后续只重试失败分片；</p>
 * <p>6. 源端制造一条 {@code customer_name = NULL}，目标端 {@code name NOT NULL}，验证坏行被落为结构化脏样本；</p>
 * <p>7. 修复源端坏行后，通过 {@code PRIMARY_KEY_EQ} dirty replay 精确重放该行，最终 PostgreSQL 数据完整。</p>
 *
 * <p>默认跳过说明：这个测试会真实访问本机 MySQL/PostgreSQL，并会 DROP/CREATE 专用 E2E 表。
 * 因此只有显式设置 {@code DATASMART_E2E_REAL_JDBC=true} 时才运行，避免日常单测或 CI 在没有外部数据库时误报。</p>
 */
class SyncAutoSplitPkRealWorkerE2ETest {

    private static final Long SOURCE_DATASOURCE_ID = 10L;
    private static final Long TARGET_DATASOURCE_ID = 20L;
    private static final Long TASK_ID = 11L;
    private static final Long TEMPLATE_ID = 22L;
    private static final Long FIRST_EXECUTION_ID = 88L;
    private static final Long REPLAY_EXECUTION_ID = 89L;
    private static final String SOURCE_TABLE = "datasmart_e2e_splitpk_orders";
    private static final String TARGET_SCHEMA = "datasmart_e2e";
    private static final String TARGET_TABLE = "orders_splitpk_clean";
    private static final String FAILED_ONCE_SHARD = "splitpk-range-0002";

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 端到端验证大表离线同步的产品化恢复语义。
     *
     * <p>业务上可以把这个场景理解为：用户创建了一个 MySQL 订单表到 PostgreSQL 清洗表的全量离线任务。
     * 由于表数据较多，用户配置 splitPk=id、shardCount=4、channel=2、taskGroupSize=2，希望系统像 DataX
     * 一样把大任务拆成可恢复的小分片。同步过程中某个分片遇到临时故障，另一个分片遇到 1 条坏数据。
     * 正确行为不是整单回滚，而是：成功分片保留、失败分片可重试、坏行落样本、源端修复后只 replay 坏行。</p>
     */
    @Test
    void mysqlToPostgresqlShouldAutoSplitRetryDirtySampleAndReplayWithRealWorker() throws Exception {
        assumeTrue(realJdbcE2EEnabled(),
                "真实 JDBC E2E 默认跳过；如需验证 MySQL -> PostgreSQL 数据面，请设置 DATASMART_E2E_REAL_JDBC=true");

        ExternalJdbcSettings settings = ExternalJdbcSettings.fromEnvironment();
        verifyExternalConnections(settings);
        initializeSourceTable(settings);
        initializeTargetTable(settings);

        ObjectExecutionMapperFixture objectFixture = objectExecutionMapperFixture();
        ErrorSampleMapperFixture errorFixture = errorSampleMapperFixture();
        LifecycleFixture lifecycleFixture = lifecycleFixture(errorFixture.mapper());
        DataSyncTaskManagementReceiptPublisher receiptPublisher = mock(DataSyncTaskManagementReceiptPublisher.class);
        SyncBatchRunnerBridgePlanSupport bridgePlanSupport = bridgePlanSupport();
        RealDatasourceRunOnceClient runOnceClient = new RealDatasourceRunOnceClient(
                runOnceService(settings), FAILED_ONCE_SHARD);
        SyncBatchRunOnceDispatchService runOnceDispatchService = new SyncBatchRunOnceDispatchService(
                bridgePlanSupport,
                runOnceClient,
                runOnceProperties(),
                lifecycleFixture.lifecycleSupport(),
                receiptPublisher,
                mock(SyncExecutionLogSupport.class));
        SyncOfflineRunnerDispatchService dispatchService = dispatchService(
                bridgePlanSupport,
                runOnceDispatchService,
                objectFixture.mapper(),
                new JdbcPartitionRangeProbeClient(settings),
                lifecycleFixture.lifecycleSupport(),
                receiptPublisher);
        SyncObjectExecutionOperationSupport retrySupport = retrySupport(objectFixture.mapper());
        SyncDirtyRecordReplayExecutionSupport replaySupport = new SyncDirtyRecordReplayExecutionSupport(
                errorFixture.mapper(),
                bridgePlanSupport,
                runOnceDispatchService,
                lifecycleFixture.lifecycleSupport(),
                receiptPublisher,
                objectMapper);

        SyncTask task = task(SyncTaskState.RUNNING.name());
        SyncExecution firstExecution = execution(FIRST_EXECUTION_ID, SyncExecutionState.RUNNING, SyncTriggerType.MANUAL);
        SyncTemplate template = partitionedTemplate();
        SyncActorContext actor = actor();

        SyncOfflineRunnerDispatchResult firstDispatch = dispatchService.dispatchOffline(
                firstExecution, task, template, workerPlan(FIRST_EXECUTION_ID, SyncTriggerType.MANUAL), actor);

        assertThat(firstDispatch.dispatchStatus()).isEqualTo("PARTITION_SHARD_FAN_OUT_PARTIALLY_SUCCEEDED");
        assertThat(firstDispatch.completed()).isFalse();
        assertThat(firstDispatch.failed()).isFalse();
        assertThat(runOnceClient.maxObservedConcurrency())
                .as("channel=2 时，同一个 TaskGroup 内应至少出现 2 个并发 run-once 调用")
                .isGreaterThanOrEqualTo(2);
        assertShardState(objectFixture.rows(), 0, SyncObjectExecutionState.SUCCEEDED, "splitpk-range-0000", 1);
        assertShardState(objectFixture.rows(), 1, SyncObjectExecutionState.SUCCEEDED, "splitpk-range-0001", 1);
        assertShardState(objectFixture.rows(), 2, SyncObjectExecutionState.FAILED, FAILED_ONCE_SHARD, 1);
        assertShardState(objectFixture.rows(), 3, SyncObjectExecutionState.SUCCEEDED, "splitpk-range-0003", 1);
        assertThat(errorFixture.rows()).hasSize(1);
        SyncErrorSample dirtySample = errorFixture.rows().get(0);
        assertThat(dirtySample.getErrorType()).isEqualTo("NOT_NULL_VIOLATION");
        assertThat(dirtySample.getSourceRecordKey()).contains("PRIMARY_KEY_EQ").contains("\"value\":7");
        assertThat(targetIds(settings)).doesNotContain(7L, 11L, 12L, 13L, 14L, 15L);

        DatasourceRunOnceRequest dirtyShardRequest = runOnceClient.firstRequestFor("splitpk-range-0001");
        assertThat(dirtyShardRequest.getExecutionPlan().getRuntimeControlPlan().getMaxDirtyRecordCount())
                .isEqualTo(10L);
        assertThat(dirtyShardRequest.getExecutionPlan().getRuntimeControlPlan().getMaxDirtyRecordRatio())
                .isEqualTo(0.20D);
        assertThat(dirtyShardRequest.getExecutionPlan().getReadPlan().getFilterConditions())
                .extracting(DatasourceRunOnceRequest.ReadFilterCondition::getColumn)
                .contains("region", "id", "id");

        firstExecution.setExecutionState(SyncExecutionState.PARTIALLY_SUCCEEDED.name());
        task.setCurrentState(SyncTaskState.PARTIALLY_SUCCEEDED.name());
        SyncObjectRetryRequest retryRequest = new SyncObjectRetryRequest();
        retryRequest.setObjectOrdinals(List.of(2));
        retryRequest.setRetryAttemptBudget(3);
        retryRequest.setResetAttemptCount(true);
        retryRequest.setReason("retry failed splitPk shard only");

        SyncObjectRetryResult retryResult = retrySupport.retryFailedObjects(task, firstExecution, retryRequest, actor);

        assertThat(retryResult.retryObjectCount()).isEqualTo(1);
        assertShardState(objectFixture.rows(), 2, SyncObjectExecutionState.PENDING, FAILED_ONCE_SHARD, 0);

        firstExecution.setExecutionState(SyncExecutionState.RUNNING.name());
        task.setCurrentState(SyncTaskState.RETRYING.name());
        SyncOfflineRunnerDispatchResult retryDispatch = dispatchService.dispatchOffline(
                firstExecution, task, template, workerPlan(FIRST_EXECUTION_ID, SyncTriggerType.MANUAL), actor);

        assertThat(retryDispatch.dispatchStatus()).isEqualTo("PARTITION_SHARD_FAN_OUT_COMPLETED");
        assertThat(retryDispatch.completed()).isTrue();
        assertThat(runOnceClient.callCount("splitpk-range-0000")).isEqualTo(1);
        assertThat(runOnceClient.callCount("splitpk-range-0001")).isEqualTo(1);
        assertThat(runOnceClient.callCount(FAILED_ONCE_SHARD)).isEqualTo(2);
        assertThat(runOnceClient.callCount("splitpk-range-0003")).isEqualTo(1);
        assertThat(targetIds(settings)).hasSize(19).doesNotContain(7L).contains(11L, 12L, 13L, 14L, 15L);

        repairBadSourceRow(settings);
        SyncExecution replayExecution = execution(REPLAY_EXECUTION_ID, SyncExecutionState.RUNNING, SyncTriggerType.REPLAY);
        task.setCurrentState(SyncTaskState.RUNNING.name());
        SyncOfflineRunnerDispatchResult replayResult = replaySupport.dispatchDirtyRecordReplay(
                replayExecution,
                task,
                template,
                workerPlan(REPLAY_EXECUTION_ID, SyncTriggerType.REPLAY),
                recoveryPlan(dirtySample.getId()),
                actor);

        assertThat(replayResult.dispatchStatus()).isEqualTo("DIRTY_RECORD_REPLAY_COMPLETED");
        assertThat(replayResult.completed()).isTrue();
        assertThat(runOnceClient.callCount("dirty-sample-" + dirtySample.getId())).isEqualTo(1);
        assertThat(targetIds(settings)).containsExactlyElementsOf(expectedIds());
        assertThat(targetNames(settings).get(7L)).isEqualTo("Repaired-Customer-7");
    }

    private SyncOfflineRunnerDispatchService dispatchService(SyncBatchRunnerBridgePlanSupport bridgePlanSupport,
                                                            SyncBatchRunOnceDispatchService runOnceDispatchService,
                                                            SyncObjectExecutionMapper objectExecutionMapper,
                                                            DatasourcePartitionRangeProbeClient rangeProbeClient,
                                                            SyncExecutionLifecycleSupport lifecycleSupport,
                                                            DataSyncTaskManagementReceiptPublisher receiptPublisher) {
        SyncObjectListFanOutDispatchService objectListFanOutDispatchService =
                mock(SyncObjectListFanOutDispatchService.class);
        SyncPartitionShardFanOutDispatchService partitionShardFanOutDispatchService =
                new SyncPartitionShardFanOutDispatchService(
                        new SyncPartitionShardExecutionContractSupport(objectMapper),
                        new SyncObjectExecutionLifecycleSupport(objectExecutionMapper),
                        bridgePlanSupport,
                        runOnceDispatchService,
                        rangeProbeClient,
                        lifecycleSupport,
                        receiptPublisher,
                        mock(SyncExecutionLogSupport.class));
        return new SyncOfflineRunnerDispatchService(
                bridgePlanSupport,
                runOnceDispatchService,
                new SyncOfflineRunnerAdapterRegistry(List.of()),
                objectListFanOutDispatchService,
                partitionShardFanOutDispatchService,
                lifecycleSupport,
                receiptPublisher);
    }

    private SyncBatchRunnerBridgePlanSupport bridgePlanSupport() {
        return new SyncBatchRunnerBridgePlanSupport(
                new SyncFieldMappingExecutionContractSupport(objectMapper),
                new SyncFilterExecutionContractSupport(objectMapper),
                new SyncTemplateScopeContractSupport(objectMapper),
                new SyncOfflineRunnerContractSupport());
    }

    private SyncObjectExecutionOperationSupport retrySupport(SyncObjectExecutionMapper objectExecutionMapper) {
        SyncExecutionMapper executionMapper = mock(SyncExecutionMapper.class);
        SyncTaskMapper taskMapper = mock(SyncTaskMapper.class);
        when(executionMapper.requeueTerminalObjectLevelRetry(eq(FIRST_EXECUTION_ID), any())).thenReturn(1);
        when(taskMapper.markLifecycleState(eq(TASK_ID), eq(SyncTaskState.RETRYING.name()),
                eq(SyncTriggerType.MANUAL.name()), eq(FIRST_EXECUTION_ID))).thenReturn(1);
        return new SyncObjectExecutionOperationSupport(
                objectExecutionMapper,
                executionMapper,
                taskMapper,
                new SyncQuerySupport(),
                mock(SyncAuditSupport.class));
    }

    private LifecycleFixture lifecycleFixture(SyncErrorSampleMapper errorSampleMapper) {
        SyncExecutionMapper executionMapper = mock(SyncExecutionMapper.class);
        SyncTaskMapper taskMapper = mock(SyncTaskMapper.class);
        when(executionMapper.updateById(any(SyncExecution.class))).thenReturn(1);
        when(taskMapper.updateById(any(SyncTask.class))).thenReturn(1);
        return new LifecycleFixture(new SyncExecutionLifecycleSupport(
                executionMapper,
                taskMapper,
                mock(SyncCheckpointMapper.class),
                errorSampleMapper,
                mock(SyncAuditSupport.class),
                mock(SyncCallbackIdempotencySupport.class),
                mock(SyncExecutionCallbackControlSignalSupport.class),
                mock(SyncExecutionLogSupport.class)));
    }

    private SyncBatchConnectorRuntimeRunOnceService runOnceService(ExternalJdbcSettings settings) {
        SyncJdbcConnectionProvider connectionProvider = new ExternalSyncJdbcConnectionProvider(settings);
        return new SyncBatchConnectorRuntimeRunOnceService(
                new SyncBatchExecutionPreparationService(
                        new SyncJdbcDialectRegistry(List.of(new MySqlSyncJdbcDialect(), new PostgreSqlSyncJdbcDialect()))),
                new JdbcSyncBatchReader(connectionProvider),
                new JdbcSyncBatchWriter(connectionProvider));
    }

    private DataSyncDatasourceRunOnceProperties runOnceProperties() {
        DataSyncDatasourceRunOnceProperties properties = new DataSyncDatasourceRunOnceProperties();
        properties.setEnabled(true);
        properties.setDefaultFetchSize(10);
        properties.setDefaultWriteBatchSize(10);
        properties.setDefaultCommitIntervalRecords(10);
        properties.setMaxRunOnceBatches(3);
        properties.setDefaultMaxDirtyRecordCount(100L);
        properties.setDefaultMaxDirtyRecordRatio(0.01D);
        return properties;
    }

    private ObjectExecutionMapperFixture objectExecutionMapperFixture() {
        SyncObjectExecutionMapper mapper = mock(SyncObjectExecutionMapper.class);
        List<SyncObjectExecution> rows = Collections.synchronizedList(new ArrayList<>());
        when(mapper.selectByExecutionId(eq(FIRST_EXECUTION_ID))).thenAnswer(invocation -> new ArrayList<>(rows));
        when(mapper.insert(any(SyncObjectExecution.class))).thenAnswer(invocation -> {
            SyncObjectExecution row = invocation.getArgument(0);
            row.setId((long) rows.size() + 1L);
            rows.add(row);
            return 1;
        });
        when(mapper.updateById(any(SyncObjectExecution.class))).thenReturn(1);
        return new ObjectExecutionMapperFixture(mapper, rows);
    }

    private ErrorSampleMapperFixture errorSampleMapperFixture() {
        SyncErrorSampleMapper mapper = mock(SyncErrorSampleMapper.class);
        List<SyncErrorSample> rows = Collections.synchronizedList(new ArrayList<>());
        when(mapper.insert(any(SyncErrorSample.class))).thenAnswer(invocation -> {
            SyncErrorSample row = invocation.getArgument(0);
            row.setId((long) rows.size() + 501L);
            rows.add(row);
            return 1;
        });
        when(mapper.selectList(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> new ArrayList<>(rows));
        return new ErrorSampleMapperFixture(mapper, rows);
    }

    private void assertShardState(List<SyncObjectExecution> rows,
                                  int ordinal,
                                  SyncObjectExecutionState state,
                                  String shard,
                                  int attemptCount) {
        assertThat(rows)
                .filteredOn(row -> row.getObjectOrdinal() == ordinal)
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.getObjectState()).isEqualTo(state.name());
                    assertThat(row.getWorkUnitType())
                            .isEqualTo(SyncObjectExecutionLifecycleSupport.WORK_UNIT_TYPE_PARTITION_SHARD);
                    assertThat(row.getShardOrPartition()).isEqualTo(shard);
                    assertThat(row.getPartitionStrategy()).isEqualTo("ID_RANGE");
                    assertThat(row.getPartitionField()).isEqualTo("id");
                    assertThat(row.getAttemptCount()).isEqualTo(attemptCount);
                });
    }

    private SyncTemplate partitionedTemplate() {
        SyncTemplate template = new SyncTemplate();
        template.setId(TEMPLATE_ID);
        template.setTenantId(7L);
        template.setProjectId(101L);
        template.setWorkspaceId(301L);
        template.setSourceDatasourceId(SOURCE_DATASOURCE_ID);
        template.setTargetDatasourceId(TARGET_DATASOURCE_ID);
        template.setSourceConnectorType("MYSQL");
        template.setTargetConnectorType("POSTGRESQL");
        template.setSourceObjectName(SOURCE_TABLE);
        template.setTargetSchemaName(TARGET_SCHEMA);
        template.setTargetObjectName(TARGET_TABLE);
        template.setSyncMode("FULL");
        template.setSyncScopeType("SINGLE_OBJECT");
        template.setWriteStrategy("UPSERT");
        template.setPrimaryKeyField("id");
        template.setFieldMappingConfig("""
                [
                  {"sourceField":"id","targetField":"id"},
                  {"sourceField":"customer_name","targetField":"name"},
                  {"sourceField":"amount","targetField":"amount"},
                  {"sourceField":"region","targetField":"region"}
                ]
                """);
        template.setFilterConfig("""
                [
                  {"field":"region","operator":"=","value":"EAST"}
                ]
                """);
        template.setPartitionConfig("""
                {
                  "strategy": "AUTO_SPLIT_PK",
                  "splitPk": "id",
                  "shardCount": 4,
                  "channel": 2,
                  "taskGroupSize": 2,
                  "maxShardAttempts": 1,
                  "maxDirtyRecordCount": 10,
                  "maxDirtyRecordRatio": 0.20
                }
                """);
        template.setEnabled(true);
        return template;
    }

    private SyncWorkerExecutionPlanView workerPlan(Long executionId, SyncTriggerType triggerType) {
        return new SyncWorkerExecutionPlanView(
                true,
                "READY_TO_RUN",
                7L,
                101L,
                301L,
                TASK_ID,
                executionId,
                3L,
                SyncExecutionState.RUNNING.name(),
                triggerType.name(),
                "worker-real-e2e",
                LocalDateTime.now().plusMinutes(5),
                TEMPLATE_ID,
                SOURCE_DATASOURCE_ID,
                TARGET_DATASOURCE_ID,
                "MYSQL",
                "POSTGRESQL",
                "FULL",
                "OFFLINE",
                "DATAX_STYLE_OFFLINE_READER_WRITER_RUNNER",
                "SINGLE_OBJECT",
                true,
                false,
                false,
                1,
                false,
                true,
                true,
                true,
                "UPSERT",
                false,
                true,
                false,
                true,
                "SNAPSHOT_BOUNDED",
                false,
                "SEGMENT_RETRY",
                true,
                false,
                false,
                true,
                true,
                false,
                false,
                List.of(),
                List.of("CLAIM_ALREADY_MARKED_RUNNING_DO_NOT_CALL_START"),
                List.of("AUTO_SPLIT_PK_DECLARED", "CHANNEL_AND_TASK_GROUP_DECLARED"),
                List.of(),
                "LOW_SENSITIVE_WORKER_PLAN_METADATA_ONLY");
    }

    private SyncRecoveryPlanWorkerResult recoveryPlan(Long sampleId) {
        return new SyncRecoveryPlanWorkerResult(
                true,
                7L,
                101L,
                301L,
                TASK_ID,
                REPLAY_EXECUTION_ID,
                7001L,
                SyncTriggerType.REPLAY.name(),
                FIRST_EXECUTION_ID,
                null,
                null,
                null,
                "DIRTY_RECORD_REPLAY",
                "{\"selectorVersion\":\"1.0\",\"sourceExecutionId\":" + FIRST_EXECUTION_ID
                        + ",\"errorSampleIds\":[" + sampleId + "]}",
                "repair confirmed by E2E",
                "CONSUMED",
                "ok");
    }

    private SyncExecution execution(Long executionId, SyncExecutionState state, SyncTriggerType triggerType) {
        SyncExecution execution = new SyncExecution();
        execution.setId(executionId);
        execution.setTenantId(7L);
        execution.setProjectId(101L);
        execution.setWorkspaceId(301L);
        execution.setSyncTaskId(TASK_ID);
        execution.setExecutionNo(3L);
        execution.setExecutionState(state.name());
        execution.setTriggerType(triggerType.name());
        execution.setExecutorId("worker-real-e2e");
        execution.setLeaseExpireTime(LocalDateTime.now().plusMinutes(5));
        execution.setRecordsRead(0L);
        execution.setRecordsWritten(0L);
        execution.setFailedRecordCount(0L);
        execution.setTriggeredBy(1001L);
        return execution;
    }

    private SyncTask task(String currentState) {
        SyncTask task = new SyncTask();
        task.setId(TASK_ID);
        task.setTenantId(7L);
        task.setProjectId(101L);
        task.setWorkspaceId(301L);
        task.setTemplateId(TEMPLATE_ID);
        task.setCurrentState(currentState);
        return task;
    }

    private SyncActorContext actor() {
        return new SyncActorContext(7L, 1001L, "SERVICE_ACCOUNT", "trace-auto-splitpk-real-worker-e2e",
                "PROJECT", "project_id IN ${actorProjectIds}", List.of(101L), false);
    }

    private boolean realJdbcE2EEnabled() {
        String value = System.getenv("DATASMART_E2E_REAL_JDBC");
        return value != null && List.of("true", "1", "yes", "y", "on")
                .contains(value.trim().toLowerCase(Locale.ROOT));
    }

    private void verifyExternalConnections(ExternalJdbcSettings settings) throws Exception {
        Class.forName("com.mysql.cj.jdbc.Driver");
        Class.forName("org.postgresql.Driver");
        try (Connection ignored = DriverManager.getConnection(settings.mysqlUrl(), settings.mysqlUser(), settings.mysqlPassword())) {
            // 只验证连接可打开，不读取业务表，避免把连通性检查变成隐式数据访问。
        } catch (SQLException exception) {
            fail("真实 AUTO_SPLIT_PK E2E 已开启，但 MySQL 源端连接不可用，issue="
                    + exception.getClass().getSimpleName());
        }
        try (Connection ignored = DriverManager.getConnection(settings.postgresUrl(), settings.postgresUser(), settings.postgresPassword())) {
            // PostgreSQL 连接同样只做打开验证；后续建表限定在 datasmart_e2e 专用 schema 内。
        } catch (SQLException exception) {
            fail("真实 AUTO_SPLIT_PK E2E 已开启，但 PostgreSQL 目标端连接不可用，issue="
                    + exception.getClass().getSimpleName());
        }
    }

    private void initializeSourceTable(ExternalJdbcSettings settings) throws Exception {
        try (Connection connection = DriverManager.getConnection(settings.mysqlUrl(), settings.mysqlUser(), settings.mysqlPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS `" + SOURCE_TABLE + "`");
            statement.execute("""
                    CREATE TABLE `datasmart_e2e_splitpk_orders` (
                        `id` BIGINT PRIMARY KEY,
                        `customer_name` VARCHAR(80) NULL,
                        `amount` DECIMAL(10, 2) NOT NULL,
                        `region` VARCHAR(20) NOT NULL
                    )
                    """);
        }
        try (Connection connection = DriverManager.getConnection(settings.mysqlUrl(), settings.mysqlUser(), settings.mysqlPassword());
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO `datasmart_e2e_splitpk_orders` (`id`, `customer_name`, `amount`, `region`)
                     VALUES (?, ?, ?, ?)
                     """)) {
            for (long id = 1L; id <= 20L; id++) {
                statement.setLong(1, id);
                statement.setString(2, id == 7L ? null : "Customer-" + id);
                statement.setBigDecimal(3, BigDecimal.valueOf(id * 10L).setScale(2));
                statement.setString(4, "EAST");
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void initializeTargetTable(ExternalJdbcSettings settings) throws Exception {
        try (Connection connection = DriverManager.getConnection(settings.postgresUrl(), settings.postgresUser(), settings.postgresPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS \"" + TARGET_SCHEMA + "\"");
            statement.execute("DROP TABLE IF EXISTS \"" + TARGET_SCHEMA + "\".\"" + TARGET_TABLE + "\"");
            statement.execute("""
                    CREATE TABLE "datasmart_e2e"."orders_splitpk_clean" (
                        "id" BIGINT PRIMARY KEY,
                        "name" VARCHAR(80) NOT NULL,
                        "amount" NUMERIC(10, 2) NOT NULL,
                        "region" VARCHAR(20) NOT NULL
                    )
                    """);
        }
    }

    private void repairBadSourceRow(ExternalJdbcSettings settings) throws Exception {
        try (Connection connection = DriverManager.getConnection(settings.mysqlUrl(), settings.mysqlUser(), settings.mysqlPassword());
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE `datasmart_e2e_splitpk_orders`
                     SET `customer_name` = ?
                     WHERE `id` = ?
                     """)) {
            statement.setString(1, "Repaired-Customer-7");
            statement.setLong(2, 7L);
            statement.executeUpdate();
        }
    }

    private List<Long> targetIds(ExternalJdbcSettings settings) throws Exception {
        return new ArrayList<>(targetNames(settings).keySet());
    }

    private Map<Long, String> targetNames(ExternalJdbcSettings settings) throws Exception {
        Map<Long, String> rows = new LinkedHashMap<>();
        try (Connection connection = DriverManager.getConnection(settings.postgresUrl(), settings.postgresUser(), settings.postgresPassword());
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT "id", "name"
                     FROM "datasmart_e2e"."orders_splitpk_clean"
                     ORDER BY "id"
                     """)) {
            while (resultSet.next()) {
                rows.put(resultSet.getLong("id"), resultSet.getString("name"));
            }
        }
        return rows;
    }

    private List<Long> expectedIds() {
        List<Long> ids = new ArrayList<>();
        for (long id = 1L; id <= 20L; id++) {
            ids.add(id);
        }
        return ids;
    }

    private record ObjectExecutionMapperFixture(SyncObjectExecutionMapper mapper, List<SyncObjectExecution> rows) {
    }

    private record ErrorSampleMapperFixture(SyncErrorSampleMapper mapper, List<SyncErrorSample> rows) {
    }

    private record LifecycleFixture(SyncExecutionLifecycleSupport lifecycleSupport) {
    }

    /**
     * 测试期连接参数。
     *
     * <p>真实产品中 datasource-management 会从数据源注册表和密钥系统读取连接配置；
     * E2E 测试为了避免依赖业务配置种子，直接通过环境变量构造两个受控测试连接。</p>
     */
    private record ExternalJdbcSettings(String mysqlUrl,
                                        String mysqlUser,
                                        String mysqlPassword,
                                        String postgresUrl,
                                        String postgresUser,
                                        String postgresPassword) {

        private static ExternalJdbcSettings fromEnvironment() {
            String mysqlPort = envOrDefault("DATASMART_LOCAL_MYSQL_PORT", "13306");
            String postgresPort = envOrDefault("DATASMART_LOCAL_POSTGRES_PORT", "5432");
            String defaultMysqlUrl = "jdbc:mysql://127.0.0.1:" + mysqlPort
                    + "/datasmart_govern?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
                    + "&useSSL=false&allowPublicKeyRetrieval=true&rewriteBatchedStatements=true";
            String defaultPostgresUrl = "jdbc:postgresql://127.0.0.1:" + postgresPort + "/datasmart_govern";
            return new ExternalJdbcSettings(
                    envOrDefault("DATASMART_E2E_MYSQL_URL", defaultMysqlUrl),
                    envOrDefault("DATASMART_E2E_MYSQL_USER", envOrDefault("DATASMART_MYSQL_USER", "datasmart")),
                    envOrDefault("DATASMART_E2E_MYSQL_PASSWORD", envOrDefault("DATASMART_MYSQL_PASSWORD", "password")),
                    envOrDefault("DATASMART_E2E_POSTGRES_URL", defaultPostgresUrl),
                    envOrDefault("DATASMART_E2E_POSTGRES_USER", envOrDefault("DATASMART_POSTGRES_USER", "datasmart")),
                    envOrDefault("DATASMART_E2E_POSTGRES_PASSWORD", envOrDefault("DATASMART_POSTGRES_PASSWORD", "password")));
        }

        private static String envOrDefault(String name, String defaultValue) {
            String value = System.getenv(name);
            return value == null || value.isBlank() ? defaultValue : value.trim();
        }
    }

    private static class ExternalSyncJdbcConnectionProvider implements SyncJdbcConnectionProvider {

        private final ExternalJdbcSettings settings;

        private ExternalSyncJdbcConnectionProvider(ExternalJdbcSettings settings) {
            this.settings = settings;
        }

        @Override
        public Connection openConnection(Long datasourceId, boolean readOnly) throws SQLException, ClassNotFoundException {
            if (SOURCE_DATASOURCE_ID.equals(datasourceId)) {
                Class.forName("com.mysql.cj.jdbc.Driver");
                return DriverManager.getConnection(settings.mysqlUrl(), settings.mysqlUser(), settings.mysqlPassword());
            }
            if (TARGET_DATASOURCE_ID.equals(datasourceId)) {
                Class.forName("org.postgresql.Driver");
                return DriverManager.getConnection(settings.postgresUrl(), settings.postgresUser(), settings.postgresPassword());
            }
            throw new IllegalArgumentException("真实 AUTO_SPLIT_PK E2E 只允许访问测试期 source/target datasourceId");
        }
    }

    /**
     * 测试期 range-probe client。
     *
     * <p>生产路径中 data-sync 会通过 HTTP internal client 调用 datasource-management。
     * 本测试为了让同一个 Maven 测试进程覆盖控制面和数据面，使用本地 client 直接执行只读
     * {@code MIN/MAX/COUNT} 探测，但仍只返回低敏 min/max/count，不返回 SQL、连接串或样本行。</p>
     */
    private static class JdbcPartitionRangeProbeClient implements DatasourcePartitionRangeProbeClient {

        private final ExternalJdbcSettings settings;

        private JdbcPartitionRangeProbeClient(ExternalJdbcSettings settings) {
            this.settings = settings;
        }

        @Override
        public DatasourcePartitionRangeProbeResponse probeRange(DatasourcePartitionRangeProbeRequest request,
                                                                SyncActorContext actorContext) {
            try (Connection connection = DriverManager.getConnection(settings.mysqlUrl(), settings.mysqlUser(), settings.mysqlPassword());
                 Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("SELECT MIN(" + quote(request.getSplitPk())
                         + ") AS min_value, MAX(" + quote(request.getSplitPk())
                         + ") AS max_value, COUNT(*) AS row_count FROM " + qualifiedObject(request.getObjectLocator()))) {
                if (!resultSet.next()) {
                    throw new IllegalStateException("range probe 未返回结果");
                }
                DatasourcePartitionRangeProbeResponse response = new DatasourcePartitionRangeProbeResponse();
                response.setProbeStatus("RANGE_PROBED");
                response.setMinValue(resultSet.getLong("min_value"));
                response.setMaxValue(resultSet.getLong("max_value"));
                response.setRowCount(resultSet.getLong("row_count"));
                response.setNumericRange(true);
                response.setWarnings(List.of("E2E_RANGE_PROBE_USED_REAL_MYSQL_MIN_MAX_COUNT"));
                response.setPayloadPolicy("LOW_SENSITIVE_RANGE_PROBE_RESULT_NO_SQL_NO_ROWS_NO_CREDENTIALS");
                return response;
            } catch (SQLException exception) {
                throw new IllegalStateException("真实 range probe 执行失败，已隐藏 SQL 与连接细节", exception);
            }
        }

        private String qualifiedObject(String objectLocator) {
            String[] parts = objectLocator.split("\\.");
            List<String> quoted = new ArrayList<>();
            for (String part : parts) {
                quoted.add(quote(part));
            }
            return String.join(".", quoted);
        }

        private String quote(String identifier) {
            if (identifier == null || !identifier.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new IllegalArgumentException("range probe 标识符不安全");
            }
            return "`" + identifier + "`";
        }
    }

    /**
     * data-sync -> datasource-management 的测试期 run-once adapter。
     *
     * <p>生产代码中 {@link DatasourceRunOnceClient} 通过 HTTP 发送 JSON 到 datasource-management。
     * 本测试把同样的镜像 DTO 转换成 datasource-management 内部 DTO，并调用真实
     * {@link SyncBatchConnectorRuntimeRunOnceService}。这样可以避免同时启动两个 Spring Boot 进程，
     * 但执行的数据读取、字段映射、写入、事务、脏数据样本全部是真实 Java Worker 路径。</p>
     */
    private static class RealDatasourceRunOnceClient implements DatasourceRunOnceClient {

        private final SyncBatchConnectorRuntimeRunOnceService runOnceService;
        private final String shardToFailOnce;
        private final AtomicBoolean failedOnce = new AtomicBoolean(false);
        private final ConcurrentHashMap<String, AtomicInteger> callCounts = new ConcurrentHashMap<>();
        private final CopyOnWriteArrayList<DatasourceRunOnceRequest> requests = new CopyOnWriteArrayList<>();
        private final AtomicInteger activeCalls = new AtomicInteger();
        private final AtomicInteger maxObservedConcurrency = new AtomicInteger();

        private RealDatasourceRunOnceClient(SyncBatchConnectorRuntimeRunOnceService runOnceService,
                                            String shardToFailOnce) {
            this.runOnceService = runOnceService;
            this.shardToFailOnce = shardToFailOnce;
        }

        @Override
        public DatasourceRunOnceResponse runOnce(DatasourceRunOnceRequest request, SyncActorContext actorContext) {
            requests.add(request);
            String shard = request.getShardOrPartition();
            callCounts.computeIfAbsent(shard, ignored -> new AtomicInteger()).incrementAndGet();
            int active = activeCalls.incrementAndGet();
            maxObservedConcurrency.accumulateAndGet(active, Math::max);
            try {
                sleepBrieflyToExposeChannelConcurrency();
                if (shardToFailOnce.equals(shard) && failedOnce.compareAndSet(false, true)) {
                    return failedShardResponse(request);
                }
                return toDatasourceResponse(runOnceService.runOnce(toInternalRequest(request)));
            } finally {
                activeCalls.decrementAndGet();
            }
        }

        private int callCount(String shardOrPartition) {
            AtomicInteger count = callCounts.get(shardOrPartition);
            return count == null ? 0 : count.get();
        }

        private int maxObservedConcurrency() {
            return maxObservedConcurrency.get();
        }

        private DatasourceRunOnceRequest firstRequestFor(String shardOrPartition) {
            return requests.stream()
                    .filter(request -> shardOrPartition.equals(request.getShardOrPartition()))
                    .findFirst()
                    .orElseThrow();
        }

        private void sleepBrieflyToExposeChannelConcurrency() {
            try {
                Thread.sleep(80L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }

        private DatasourceRunOnceResponse failedShardResponse(DatasourceRunOnceRequest request) {
            DatasourceRunOnceResponse response = new DatasourceRunOnceResponse();
            response.setTaskId(request.getExecutionPlan().getTaskId());
            response.setExecutionId(request.getExecutionPlan().getExecutionId());
            response.setRunStatus("E2E_INTENTIONAL_SHARD_FAILURE");
            response.setBatchRecordsRead(0L);
            response.setBatchRecordsWritten(0L);
            response.setBatchFailedRecordCount(1L);
            response.setTotalRecordsRead(0L);
            response.setTotalRecordsWritten(0L);
            response.setTotalFailedRecordCount(1L);
            response.setEndOfSource(true);
            response.setFailed(true);
            response.setProgressCallbackRecommended(false);
            response.setCompleteCallbackRecommended(false);
            response.setFailCallbackRecommended(true);
            response.setCheckpointCandidateProduced(false);
            response.setErrorSummary("E2E intentional failed shard; verify selective retry");
            response.setDirtySamples(List.of());
            response.setWarnings(List.of("E2E_FAILED_SHARD_ONCE"));
            response.setPayloadPolicy("LOW_SENSITIVE_E2E_INTENTIONAL_FAILURE_NO_SQL_NO_ROWS");
            return response;
        }

        private SyncBatchRunOnceInternalRequest toInternalRequest(DatasourceRunOnceRequest source) {
            SyncBatchRunOnceInternalRequest target = new SyncBatchRunOnceInternalRequest();
            target.setExecutionPlan(toInternalPlan(source.getExecutionPlan()));
            target.setSelectedColumns(source.getSelectedColumns());
            target.setWriteColumns(source.getWriteColumns());
            target.setPrimaryKeyColumns(source.getPrimaryKeyColumns());
            target.setActorId(source.getActorId());
            target.setActorRole(source.getActorRole());
            target.setActorTenantId(source.getActorTenantId());
            target.setShardOrPartition(source.getShardOrPartition());
            target.setCheckpointValue(source.getCheckpointValue());
            target.setPreviousRecordsRead(source.getPreviousRecordsRead());
            target.setPreviousRecordsWritten(source.getPreviousRecordsWritten());
            target.setPreviousFailedRecordCount(source.getPreviousFailedRecordCount());
            return target;
        }

        private SyncBatchExecutionPlan toInternalPlan(DatasourceRunOnceRequest.ExecutionPlan source) {
            SyncBatchExecutionPlan target = new SyncBatchExecutionPlan();
            target.setPlanVersion(source.getPlanVersion());
            target.setExecutionBoundary(source.getExecutionBoundary());
            target.setTaskId(source.getTaskId());
            target.setExecutionId(source.getExecutionId());
            target.setReadPlan(toInternalReadPlan(source.getReadPlan()));
            target.setWritePlan(toInternalWritePlan(source.getWritePlan()));
            target.setCheckpointPlan(toInternalCheckpointPlan(source.getCheckpointPlan()));
            target.setRuntimeControlPlan(toInternalRuntimeControlPlan(source.getRuntimeControlPlan()));
            target.setWarnings(source.getWarnings());
            target.setGeneratedAt(source.getGeneratedAt());
            return target;
        }

        private SyncBatchExecutionPlan.ReadPlan toInternalReadPlan(DatasourceRunOnceRequest.ReadPlan source) {
            SyncBatchExecutionPlan.ReadPlan target = new SyncBatchExecutionPlan.ReadPlan();
            target.setConnectorType(source.getConnectorType());
            target.setDatasourceId(source.getDatasourceId());
            target.setObjectLocator(source.getObjectLocator());
            target.setReadStrategy(source.getReadStrategy());
            target.setSyncMode(source.getSyncMode());
            target.setIncrementalField(source.getIncrementalField());
            target.setFilterConditions(source.getFilterConditions().stream()
                    .map(condition -> new SyncBatchExecutionPlan.ReadFilterCondition(
                            condition.getColumn(),
                            condition.getOperator(),
                            condition.getValue(),
                            condition.getValueRequired()))
                    .toList());
            target.setWherePredicate(source.getWherePredicate());
            target.setCustomSql(source.getCustomSql());
            target.setCustomSqlFingerprint(source.getCustomSqlFingerprint());
            target.setPartitionConfigured(source.getPartitionConfigured());
            target.setRecommendedFetchSize(source.getRecommendedFetchSize());
            target.setRequiredWorkerCapabilities(source.getRequiredWorkerCapabilities());
            return target;
        }

        private SyncBatchExecutionPlan.WritePlan toInternalWritePlan(DatasourceRunOnceRequest.WritePlan source) {
            return new SyncBatchExecutionPlan.WritePlan(
                    source.getConnectorType(),
                    source.getDatasourceId(),
                    source.getObjectLocator(),
                    source.getWriteStrategy(),
                    source.getConflictPolicy(),
                    source.getPrimaryKeyRequired(),
                    source.getPrimaryKeyField(),
                    source.getRecommendedWriteBatchSize(),
                    source.getRecommendedCommitIntervalRecords(),
                    source.getRequiredWorkerCapabilities());
        }

        private SyncBatchExecutionPlan.CheckpointPlan toInternalCheckpointPlan(
                DatasourceRunOnceRequest.CheckpointPlan source) {
            return new SyncBatchExecutionPlan.CheckpointPlan(
                    source.getCheckpointType(),
                    source.getInitialCheckpointPolicy(),
                    source.getResumeRequired(),
                    source.getShardAware(),
                    source.getPersistEveryRecords(),
                    source.getCheckpointValueVisibility());
        }

        private SyncBatchExecutionPlan.RuntimeControlPlan toInternalRuntimeControlPlan(
                DatasourceRunOnceRequest.RuntimeControlPlan source) {
            SyncBatchExecutionPlan.RuntimeControlPlan target = new SyncBatchExecutionPlan.RuntimeControlPlan();
            target.setExecutorId(source.getExecutorId());
            target.setLeaseExpireAt(source.getLeaseExpireAt());
            target.setHeartbeatRequired(source.getHeartbeatRequired());
            target.setTimeoutSeconds(source.getTimeoutSeconds());
            target.setMaxRetryCount(source.getMaxRetryCount());
            target.setMaxDirtyRecordCount(source.getMaxDirtyRecordCount());
            target.setMaxDirtyRecordRatio(source.getMaxDirtyRecordRatio());
            target.setIdempotencyScope(source.getIdempotencyScope());
            target.setRequiredCallbacks(source.getRequiredCallbacks());
            return target;
        }

        private DatasourceRunOnceResponse toDatasourceResponse(SyncBatchRunOnceInternalResponse source) {
            DatasourceRunOnceResponse target = new DatasourceRunOnceResponse();
            target.setTaskId(source.getTaskId());
            target.setExecutionId(source.getExecutionId());
            target.setRunStatus(source.getRunStatus());
            target.setBatchRecordsRead(source.getBatchRecordsRead());
            target.setBatchRecordsWritten(source.getBatchRecordsWritten());
            target.setBatchFailedRecordCount(source.getBatchFailedRecordCount());
            target.setTotalRecordsRead(source.getTotalRecordsRead());
            target.setTotalRecordsWritten(source.getTotalRecordsWritten());
            target.setTotalFailedRecordCount(source.getTotalFailedRecordCount());
            target.setEndOfSource(source.getEndOfSource());
            target.setFailed(source.getFailed());
            target.setProgressCallbackRecommended(source.getProgressCallbackRecommended());
            target.setCheckpointCallbackRecommended(source.getCheckpointCallbackRecommended());
            target.setCheckpointCandidateProduced(source.getCheckpointCandidateProduced());
            target.setCheckpointHandoffMode(source.getCheckpointHandoffMode());
            target.setCompleteCallbackRecommended(source.getCompleteCallbackRecommended());
            target.setFailCallbackRecommended(source.getFailCallbackRecommended());
            target.setCheckpointType(source.getCheckpointType());
            target.setCheckpointValueVisibility(source.getCheckpointValueVisibility());
            target.setErrorSummary(source.getErrorSummary());
            target.setDirtySamples(source.getDirtySamples().stream().map(this::toDirtySample).toList());
            target.setDirtyThresholdExceeded(source.getDirtyThresholdExceeded());
            target.setWarnings(source.getWarnings());
            target.setPayloadPolicy(source.getPayloadPolicy());
            return target;
        }

        private DatasourceDirtyRecordSample toDirtySample(
                com.czh.datasmart.govern.datasource.service.execution.SyncDirtyRecordSample source) {
            DatasourceDirtyRecordSample target = new DatasourceDirtyRecordSample();
            target.setErrorType(source.getErrorType());
            target.setErrorCode(source.getErrorCode());
            target.setErrorMessage(source.getErrorMessage());
            target.setSourceRecordKey(source.getSourceRecordKey());
            target.setTargetRecordKey(source.getTargetRecordKey());
            target.setSamplePayload(source.getSamplePayload());
            target.setRetryable(source.getRetryable());
            return target;
        }
    }
}
