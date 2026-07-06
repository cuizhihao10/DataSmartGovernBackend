/**
 * @Author : Cui
 * @Date: 2026/06/29 12:06
 * @Description DataSmart Govern Backend - SyncBatchConnectorRuntimeRunOnceServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.execution;

import com.czh.datasmart.govern.datasource.controller.dto.SyncBatchExecutionPlan;
import com.czh.datasmart.govern.datasource.controller.dto.SyncBatchRunOnceInternalRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncBatchRunOnceInternalResponse;
import com.czh.datasmart.govern.datasource.service.execution.jdbc.MySqlSyncJdbcDialect;
import com.czh.datasmart.govern.datasource.service.execution.jdbc.PostgreSqlSyncJdbcDialect;
import com.czh.datasmart.govern.datasource.service.execution.jdbc.SqlServerSyncJdbcDialect;
import com.czh.datasmart.govern.datasource.service.execution.jdbc.SyncJdbcDialectRegistry;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 连接器运行时 run-once 服务测试。
 *
 * <p>这组测试验证新的 internal run-once 能力是否满足 data-sync 闭环所需的边界：
 * 只执行一批 read/write，返回低敏摘要，不调用 datasource-management legacy SyncTaskService，
 * 不返回真实行数据、SQL、连接串或 checkpoint 原始值。</p>
 */
class SyncBatchConnectorRuntimeRunOnceServiceTest {

    private final SyncBatchExecutionPreparationService preparationService = new SyncBatchExecutionPreparationService(
            new SyncJdbcDialectRegistry(List.of(
                    new MySqlSyncJdbcDialect(),
                    new PostgreSqlSyncJdbcDialect(),
                    new SqlServerSyncJdbcDialect()
            ))
    );

    @Test
    void runOnceShouldReturnProgressRecommendationWithoutCompletingWhenMoreBatchesRemain() {
        StaticReader reader = new StaticReader(new SyncBatchReadResult(
                2L,
                false,
                true,
                null,
                new SyncBatchRecordBatch(
                        List.of("id", "updated_at", "amount"),
                        List.of(
                                Map.of("id", 1L, "updated_at", "2026-06-20T16:20:00", "amount", 100),
                                Map.of("id", 2L, "updated_at", "2026-06-20T16:21:00", "amount", 200)
                        )
                )
        ));
        StaticWriter writer = new StaticWriter(new SyncBatchWriteResult(2L, 0L, true, null));

        SyncBatchRunOnceInternalResponse response = service(reader, writer)
                .runOnce(runRequest(10L, 9L, 0L, "2026-06-20T16:19:00"));

        assertEquals("BATCH_WRITTEN_MORE_REMAIN", response.getRunStatus());
        assertFalse(response.getFailed());
        assertTrue(response.getProgressCallbackRecommended());
        assertTrue(response.getCheckpointCandidateProduced());
        assertTrue(response.getCheckpointCallbackRecommended());
        assertFalse(response.getCompleteCallbackRecommended());
        assertEquals(12L, response.getTotalRecordsRead());
        assertEquals(11L, response.getTotalRecordsWritten());
        assertEquals("TIME_WATERMARK", response.getCheckpointType());
        assertEquals(SyncBatchRunOnceInternalResponse.PAYLOAD_POLICY, response.getPayloadPolicy());
        assertFalse(response.toString().contains("2026-06-20T16:21:00"));
        assertTrue(reader.invoked);
        assertTrue(writer.invoked);
    }

    @Test
    void runOnceShouldRecommendCompleteWhenSourceEnds() {
        StaticReader reader = new StaticReader(new SyncBatchReadResult(
                1L,
                true,
                true,
                null,
                new SyncBatchRecordBatch(
                        List.of("id", "updated_at", "amount"),
                        List.of(Map.of("id", 3L, "updated_at", "2026-06-20T16:22:00", "amount", 300))
                )
        ));
        StaticWriter writer = new StaticWriter(new SyncBatchWriteResult(1L, 0L, true, null));

        SyncBatchRunOnceInternalResponse response = service(reader, writer)
                .runOnce(runRequest(0L, 0L, 0L, "2026-06-20T16:21:00"));

        assertEquals("SOURCE_EXHAUSTED_COMPLETE_REQUIRED", response.getRunStatus());
        assertTrue(response.getEndOfSource());
        assertTrue(response.getCompleteCallbackRecommended());
        assertFalse(response.getFailCallbackRecommended());
        assertEquals(1L, response.getTotalRecordsWritten());
    }

    @Test
    void fullRunOnceShouldBindPreviousReadCountAsOffset() {
        StaticReader reader = new StaticReader(new SyncBatchReadResult(
                1L,
                true,
                false,
                null,
                new SyncBatchRecordBatch(
                        List.of("id", "amount"),
                        List.of(Map.of("id", 11L, "amount", 300))
                )
        ));
        StaticWriter writer = new StaticWriter(new SyncBatchWriteResult(1L, 0L, true, null));

        SyncBatchRunOnceInternalRequest request = runRequest(10L, 10L, 0L, null);
        request.setExecutionPlan(fullPlan());
        request.setSelectedColumns(List.of("id", "amount"));
        request.setWriteColumns(List.of("id", "amount"));
        request.setPrimaryKeyColumns(List.of("id"));

        SyncBatchRunOnceInternalResponse response = service(reader, writer).runOnce(request);

        assertEquals("SOURCE_EXHAUSTED_COMPLETE_REQUIRED", response.getRunStatus());
        assertEquals(10L, reader.capturedContext.getParameterValues().get("offset"));
        assertTrue(reader.capturedContext.getReadStatement().getParameterNames().contains("offset"));
    }

    @Test
    void runOnceShouldRenameSourceColumnsToTargetColumnsBeforeWriting() {
        StaticReader reader = new StaticReader(new SyncBatchReadResult(
                1L,
                true,
                false,
                null,
                new SyncBatchRecordBatch(
                        List.of("customer_id", "customer_name"),
                        List.of(Map.of("customer_id", 1001L, "customer_name", "Alice"))
                )
        ));
        StaticWriter writer = new StaticWriter(new SyncBatchWriteResult(1L, 0L, true, null));

        SyncBatchRunOnceInternalRequest request = runRequest(0L, 0L, 0L, "2026-06-20T16:21:00");
        request.setSelectedColumns(List.of("customer_id", "customer_name"));
        request.setWriteColumns(List.of("id", "name"));
        request.setPrimaryKeyColumns(List.of("id"));

        SyncBatchRunOnceInternalResponse response = service(reader, writer).runOnce(request);

        assertEquals("SOURCE_EXHAUSTED_COMPLETE_REQUIRED", response.getRunStatus());
        assertTrue(writer.invoked);
        assertEquals(List.of("id", "name"), writer.capturedBatch.getColumns());
        assertEquals(1001L, writer.capturedBatch.getRows().get(0).get("id"));
        assertEquals("Alice", writer.capturedBatch.getRows().get(0).get("name"));
        assertFalse(writer.capturedBatch.getRows().get(0).containsKey("customer_id"));
    }

    @Test
    void dirtyRatioShouldUseReadRowsAsDenominatorAndAllowSmallDirtyBatch() {
        StaticReader reader = new StaticReader(new SyncBatchReadResult(
                1000L,
                true,
                false,
                null,
                new SyncBatchRecordBatch(
                        List.of("id", "amount"),
                        testRows(1000)
                )
        ));
        StaticWriter writer = new StaticWriter(new SyncBatchWriteResult(
                990L,
                10L,
                true,
                "batch write isolated dirtyRecords=10",
                List.of(dirtySample(1L)),
                false
        ));
        SyncBatchRunOnceInternalRequest request = runRequest(0L, 0L, 0L, null);
        request.setExecutionPlan(fullPlan());
        request.getExecutionPlan().getRuntimeControlPlan().setMaxDirtyRecordCount(100L);
        request.getExecutionPlan().getRuntimeControlPlan().setMaxDirtyRecordRatio(0.02D);
        request.setSelectedColumns(List.of("id", "amount"));
        request.setWriteColumns(List.of("id", "amount"));

        SyncBatchRunOnceInternalResponse response = service(reader, writer).runOnce(request);

        assertEquals("SOURCE_EXHAUSTED_COMPLETE_REQUIRED", response.getRunStatus());
        assertFalse(response.getFailed());
        assertTrue(response.getCompleteCallbackRecommended());
        assertFalse(response.getFailCallbackRecommended());
        assertEquals(1000L, response.getBatchRecordsRead());
        assertEquals(10L, response.getBatchFailedRecordCount());
        assertEquals(10L, response.getTotalFailedRecordCount());
        assertFalse(response.getDirtyThresholdExceeded());
        assertEquals(1, response.getDirtySamples().size());
    }

    @Test
    void dirtyRatioShouldFailClosedWhenMostRowsAreDirty() {
        StaticReader reader = new StaticReader(new SyncBatchReadResult(
                1000L,
                false,
                false,
                null,
                new SyncBatchRecordBatch(
                        List.of("id", "amount"),
                        testRows(1000)
                )
        ));
        StaticWriter writer = new StaticWriter(new SyncBatchWriteResult(
                0L,
                1000L,
                true,
                "batch write isolated dirtyRecords=1000",
                List.of(dirtySample(1L)),
                false
        ));
        SyncBatchRunOnceInternalRequest request = runRequest(0L, 0L, 0L, null);
        request.setExecutionPlan(fullPlan());
        request.getExecutionPlan().getRuntimeControlPlan().setMaxDirtyRecordCount(100L);
        request.getExecutionPlan().getRuntimeControlPlan().setMaxDirtyRecordRatio(0.01D);
        request.setSelectedColumns(List.of("id", "amount"));
        request.setWriteColumns(List.of("id", "amount"));

        SyncBatchRunOnceInternalResponse response = service(reader, writer).runOnce(request);

        assertEquals("WRITE_FAILED", response.getRunStatus());
        assertTrue(response.getFailed());
        assertTrue(response.getFailCallbackRecommended());
        assertTrue(response.getProgressCallbackRecommended());
        assertEquals(1000L, response.getBatchRecordsRead());
        assertEquals(1000L, response.getBatchFailedRecordCount());
        assertEquals(1000L, response.getTotalFailedRecordCount());
        assertTrue(response.getDirtyThresholdExceeded());
        assertEquals(1, response.getDirtySamples().size());
    }

    @Test
    void readerErrorShouldNotCallWriterAndShouldScrubSensitiveSummary() {
        StaticReader reader = new StaticReader(new SyncBatchReadResult(
                0L,
                false,
                false,
                "jdbc:mysql://127.0.0.1:3306/prod select * from secret_table password=abc",
                null
        ));
        StaticWriter writer = new StaticWriter(new SyncBatchWriteResult(0L, 0L, true, null));

        SyncBatchRunOnceInternalResponse response = service(reader, writer)
                .runOnce(runRequest(0L, 0L, 0L, "2026-06-20T16:21:00"));

        assertEquals("READ_FAILED", response.getRunStatus());
        assertTrue(response.getFailed());
        assertTrue(response.getFailCallbackRecommended());
        assertFalse(response.getProgressCallbackRecommended());
        assertFalse(writer.invoked);
        assertTrue(response.getErrorSummary().contains("读取阶段失败"));
        assertFalse(response.getErrorSummary().contains("jdbc:mysql"));
        assertFalse(response.getErrorSummary().contains("secret_table"));
        assertFalse(response.getErrorSummary().contains("password"));
    }

    @Test
    void missingCheckpointShouldFailBeforeReaderIsInvoked() {
        StaticReader reader = new StaticReader(new SyncBatchReadResult(0L, false, false, null, null));
        StaticWriter writer = new StaticWriter(new SyncBatchWriteResult(0L, 0L, true, null));

        SyncBatchRunOnceInternalResponse response = service(reader, writer)
                .runOnce(runRequest(0L, 0L, 0L, null));

        assertEquals("RUNNER_FAILED", response.getRunStatus());
        assertTrue(response.getFailed());
        assertTrue(response.getFailCallbackRecommended());
        assertFalse(reader.invoked);
        assertFalse(writer.invoked);
        assertTrue(response.getErrorSummary().contains("执行阶段异常"));
        assertFalse(response.getErrorSummary().contains("updated_at"));
    }

    private SyncBatchConnectorRuntimeRunOnceService service(SyncBatchReader reader, SyncBatchWriter writer) {
        return new SyncBatchConnectorRuntimeRunOnceService(preparationService, reader, writer);
    }

    private SyncDirtyRecordSample dirtySample(Long id) {
        return new SyncDirtyRecordSample(
                "DUPLICATE_KEY",
                "23505",
                "duplicate key",
                "{\"strategy\":\"PRIMARY_KEY_EQ\",\"column\":\"id\",\"value\":" + id + ",\"valueType\":\"Long\"}",
                null,
                "{\"columns\":\"id,amount\"}",
                true
        );
    }

    /**
     * 构造与 `recordsRead` 一致的测试批次。
     *
     * <p>dirty ratio 的核心业务含义是“失败行 / 读取行”，因此测试数据也要让读取行数和批次行数保持一致。
     * 如果测试只返回 1 行却声明 recordsRead=1000，虽然当前执行器不会强制校验二者完全相等，
     * 但会让后续阅读者误以为脏数据比例只依赖计数字段、不关心真实批次规模。</p>
     */
    private List<Map<String, Object>> testRows(int count) {
        List<Map<String, Object>> rows = new ArrayList<>(count);
        for (int index = 1; index <= count; index++) {
            rows.add(Map.of("id", (long) index, "amount", index * 10));
        }
        return rows;
    }

    private SyncBatchRunOnceInternalRequest runRequest(Long previousRecordsRead,
                                                       Long previousRecordsWritten,
                                                       Long previousFailedRecordCount,
                                                       Object checkpointValue) {
        SyncBatchRunOnceInternalRequest request = new SyncBatchRunOnceInternalRequest();
        request.setExecutionPlan(incrementalPlan());
        request.setSelectedColumns(List.of("id", "updated_at", "amount"));
        request.setWriteColumns(List.of("id", "updated_at", "amount"));
        request.setPrimaryKeyColumns(List.of("id"));
        request.setActorId(700L);
        request.setActorRole("SERVICE_ACCOUNT");
        request.setActorTenantId(1L);
        request.setShardOrPartition("shard-0");
        request.setCheckpointValue(checkpointValue);
        request.setPreviousRecordsRead(previousRecordsRead);
        request.setPreviousRecordsWritten(previousRecordsWritten);
        request.setPreviousFailedRecordCount(previousFailedRecordCount);
        return request;
    }

    private SyncBatchExecutionPlan incrementalPlan() {
        return new SyncBatchExecutionPlan(
                "datasmart.datasource.sync-batch-plan.v1",
                "BATCH_EXECUTION_CONTRACT_NO_RAW_SQL_NO_CREDENTIALS",
                100L,
                900L,
                new SyncBatchExecutionPlan.ReadPlan(
                        "MYSQL",
                        10L,
                        "ods.orders",
                        "INCREMENTAL_TIME_WINDOW",
                        "INCREMENTAL_TIME",
                        "updated_at",
                        true,
                        512,
                        List.of("JDBC_BATCH_READ", "CHECKPOINT_AWARE_READ")
                ),
                new SyncBatchExecutionPlan.WritePlan(
                        "POSTGRESQL",
                        20L,
                        "dwd.orders_clean",
                        "UPSERT",
                        "UPSERT_BY_PRIMARY_OR_UNIQUE_KEY",
                        true,
                        "id",
                        256,
                        256,
                        List.of("JDBC_BATCH_WRITE", "IDEMPOTENT_CONFLICT_WRITE")
                ),
                new SyncBatchExecutionPlan.CheckpointPlan(
                        "TIME_WATERMARK",
                        "START_FROM_TEMPLATE_FILTER_OR_LOWEST_AVAILABLE_WATERMARK",
                        true,
                        true,
                        2048,
                        "WORKER_INTERNAL_AND_SYNC_CHECKPOINT_TABLE_ONLY"
                ),
                new SyncBatchExecutionPlan.RuntimeControlPlan(
                        "worker-a",
                        LocalDateTime.of(2026, 6, 20, 16, 30),
                        true,
                        600,
                        3,
                        "taskId:100/executionId:900/shard",
                        List.of("heartbeat", "progress", "complete", "fail")
                ),
                List.of("测试告警：字段映射由模板治理层提供"),
                LocalDateTime.of(2026, 6, 20, 16, 25)
        );
    }

    private SyncBatchExecutionPlan fullPlan() {
        SyncBatchExecutionPlan plan = incrementalPlan();
        plan.getReadPlan().setReadStrategy("FULL_OBJECT_SCAN");
        plan.getReadPlan().setSyncMode("FULL");
        plan.getReadPlan().setIncrementalField(null);
        plan.getCheckpointPlan().setCheckpointType("NONE_OR_FINAL_WATERMARK");
        plan.getCheckpointPlan().setResumeRequired(false);
        return plan;
    }

    /**
     * 静态 reader 测试替身。
     */
    private static class StaticReader implements SyncBatchReader {

        private final SyncBatchReadResult result;
        private boolean invoked;
        private SyncBatchReadContext capturedContext;

        private StaticReader(SyncBatchReadResult result) {
            this.result = result;
        }

        @Override
        public SyncBatchReadResult readNextBatch(SyncBatchReadContext context) {
            invoked = true;
            capturedContext = context;
            return result;
        }
    }

    /**
     * 静态 writer 测试替身。
     */
    private static class StaticWriter implements SyncBatchWriter {

        private final SyncBatchWriteResult result;
        private boolean invoked;
        private SyncBatchRecordBatch capturedBatch;

        private StaticWriter(SyncBatchWriteResult result) {
            this.result = result;
        }

        @Override
        public SyncBatchWriteResult writeBatch(SyncBatchWriteContext context, SyncBatchRecordBatch recordBatch) {
            invoked = true;
            capturedBatch = recordBatch;
            return result;
        }
    }
}
