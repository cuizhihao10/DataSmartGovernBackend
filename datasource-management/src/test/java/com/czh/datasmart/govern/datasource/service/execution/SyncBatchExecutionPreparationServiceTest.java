/**
 * @Author : Cui
 * @Date: 2026/06/20 03:22
 * @Description DataSmart Govern Backend - SyncBatchExecutionPreparationServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.execution;

import com.czh.datasmart.govern.datasource.controller.dto.SyncBatchExecutionPlan;
import com.czh.datasmart.govern.datasource.service.execution.jdbc.MySqlSyncJdbcDialect;
import com.czh.datasmart.govern.datasource.service.execution.jdbc.PostgreSqlSyncJdbcDialect;
import com.czh.datasmart.govern.datasource.service.execution.jdbc.SqlServerSyncJdbcDialect;
import com.czh.datasmart.govern.datasource.service.execution.jdbc.SyncJdbcDialectRegistry;
import com.czh.datasmart.govern.datasource.support.SyncWriteStrategy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批处理执行准备服务测试。
 *
 * <p>这组测试验证的是“claim 执行计划 -> worker 内部准备包”的衔接能力。
 * 它不会连接真实数据库，也不会执行 SQL；它只确认准备服务能正确选择方言、生成 read/write context、
 * 汇总 checkpoint 和回调计划，并在危险场景下 fail-closed。</p>
 */
class SyncBatchExecutionPreparationServiceTest {

    private final SyncBatchExecutionPreparationService service = new SyncBatchExecutionPreparationService(
            new SyncJdbcDialectRegistry(List.of(
                    new MySqlSyncJdbcDialect(),
                    new PostgreSqlSyncJdbcDialect(),
                    new SqlServerSyncJdbcDialect()
            ))
    );
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void prepareJdbcExecutionShouldBuildReadWriteContextsAndCallbackPlan() {
        SyncBatchWorkerExecutionBundle bundle = service.prepareJdbcExecution(new SyncBatchExecutionPreparationRequest(
                incrementalPlan("MYSQL", "POSTGRESQL", "INCREMENTAL_TIME_WINDOW", "UPSERT"),
                List.of("id", "updated_at", "amount"),
                List.of("id", "updated_at", "amount"),
                List.of("id")
        ));

        assertEquals("datasmart.datasource.sync-worker-bundle.v1", bundle.getBundleVersion());
        assertEquals(100L, bundle.getTaskId());
        assertEquals(900L, bundle.getExecutionId());
        assertEquals("TIME_WATERMARK", bundle.getCheckpointPlan().getCheckpointType());
        assertTrue(bundle.getCheckpointPlan().getResumeRequired());
        assertTrue(bundle.getCallbackPlan().getRequiredCallbacks().contains("progress"));
        assertEquals("worker-a", bundle.getCallbackPlan().getExecutorId());

        assertEquals(10L, bundle.getReadContext().getDatasourceId());
        assertEquals("INCREMENTAL_READ", bundle.getReadContext().getReadStatement().getExecutionIntent());
        assertTrue(bundle.getReadContext().getReadStatement().getSql().contains("`ods`.`orders`"));
        assertEquals(List.of("checkpointValue", "limit"), bundle.getReadContext().getReadStatement().getParameterNames());

        assertEquals(20L, bundle.getWriteContext().getDatasourceId());
        assertEquals("UPSERT_WRITE", bundle.getWriteContext().getWriteStatement().getExecutionIntent());
        assertTrue(bundle.getWriteContext().getWriteStatement().getSql().contains("ON CONFLICT (\"id\") DO UPDATE SET"));
        assertEquals(List.of("id", "updated_at", "amount"), bundle.getWriteContext().getWriteStatement().getParameterNames());
    }

    @Test
    void prepareJdbcExecutionShouldFallbackToPlanPrimaryKeyField() {
        SyncBatchWorkerExecutionBundle bundle = service.prepareJdbcExecution(new SyncBatchExecutionPreparationRequest(
                incrementalPlan("MYSQL", "MYSQL", "INCREMENTAL_ID_RANGE", "INSERT_IGNORE"),
                List.of("id", "updated_at", "amount"),
                List.of("id", "updated_at", "amount"),
                List.of()
        ));

        assertEquals("INSERT_IGNORE_WRITE", bundle.getWriteContext().getWriteStatement().getExecutionIntent());
        assertTrue(bundle.getWriteContext().getWriteStatement().getSql().contains("INSERT IGNORE INTO"));
    }

    @Test
    void prepareJdbcExecutionShouldBindStructuredFilterParameters() {
        SyncBatchExecutionPlan plan = incrementalPlan("MYSQL", "POSTGRESQL", "FULL_OBJECT_SCAN", "APPEND");
        plan.getReadPlan().setFilterConditions(List.of(
                new SyncBatchExecutionPlan.ReadFilterCondition("status", "EQ", "ACTIVE", true),
                new SyncBatchExecutionPlan.ReadFilterCondition("biz_date", "GTE", "2026-01-01", true)
        ));

        SyncBatchWorkerExecutionBundle bundle = service.prepareJdbcExecution(new SyncBatchExecutionPreparationRequest(
                plan,
                List.of("id", "status", "biz_date"),
                List.of("id", "status", "biz_date"),
                List.of()
        ));

        assertEquals(List.of("filter_0", "filter_1", "limit", "offset"),
                bundle.getReadContext().getReadStatement().getParameterNames());
        assertTrue(bundle.getReadContext().getReadStatement().getSql()
                .contains("WHERE `status` = ? AND `biz_date` >= ?"));
        assertTrue(bundle.getReadContext().getReadStatement().getSql()
                .contains("ORDER BY `id` ASC, `status` ASC, `biz_date` ASC"));
        assertFalse(bundle.getReadContext().getReadStatement().getSql().contains("ACTIVE"));
        assertEquals("ACTIVE", bundle.getReadContext().getParameterValues().get("filter_0"));
        assertEquals("2026-01-01", bundle.getReadContext().getParameterValues().get("filter_1"));
        assertEquals(0L, bundle.getReadContext().getParameterValues().get("offset"));
    }

    @Test
    void prepareJdbcExecutionShouldRejectMissingWriteColumns() {
        SyncBatchExecutionPreparationRequest request = new SyncBatchExecutionPreparationRequest(
                incrementalPlan("MYSQL", "MYSQL", "FULL_OBJECT_SCAN", "APPEND"),
                List.of("id"),
                List.of(),
                List.of()
        );

        assertThrows(IllegalArgumentException.class, () -> service.prepareJdbcExecution(request));
    }

    @Test
    void prepareJdbcExecutionShouldRejectStreamingStrategyForJdbcBatchPreparation() {
        SyncBatchExecutionPreparationRequest request = new SyncBatchExecutionPreparationRequest(
                incrementalPlan("MYSQL", "MYSQL", "STREAMING_CONNECTOR_REQUIRED", "APPEND"),
                List.of("id"),
                List.of("id"),
                List.of()
        );

        assertThrows(UnsupportedOperationException.class, () -> service.prepareJdbcExecution(request));
    }

    @Test
    void prepareJdbcExecutionShouldRejectOverwriteUntilApprovalAndRollbackAreClosed() {
        SyncBatchExecutionPreparationRequest request = new SyncBatchExecutionPreparationRequest(
                incrementalPlan("MYSQL", "MYSQL", "FULL_OBJECT_SCAN", SyncWriteStrategy.OVERWRITE.name()),
                List.of("id", "amount"),
                List.of("id", "amount"),
                List.of("id")
        );

        assertThrows(UnsupportedOperationException.class, () -> service.prepareJdbcExecution(request));
    }

    @Test
    void internalBundleShouldNotContainCredentialsOrSampleValues() throws JsonProcessingException {
        SyncBatchWorkerExecutionBundle bundle = service.prepareJdbcExecution(new SyncBatchExecutionPreparationRequest(
                incrementalPlan("MYSQL", "SQL_SERVER", "INCREMENTAL_TIME_WINDOW", "UPSERT"),
                List.of("id", "updated_at", "amount"),
                List.of("id", "updated_at", "amount"),
                List.of("id")
        ));

        String json = objectMapper.writeValueAsString(bundle).toLowerCase();

        assertTrue(json.contains("select "));
        assertTrue(json.contains("merge into"));
        assertFalse(json.contains("jdbc:mysql"));
        assertFalse(json.contains("secret-password"));
        assertFalse(json.contains("customer-alice"));
        assertFalse(json.contains("api_key"));
    }

    private SyncBatchExecutionPlan incrementalPlan(String sourceConnector,
                                                   String targetConnector,
                                                   String readStrategy,
                                                   String writeStrategy) {
        return new SyncBatchExecutionPlan(
                "datasmart.datasource.sync-batch-plan.v1",
                "BATCH_EXECUTION_CONTRACT_NO_RAW_SQL_NO_CREDENTIALS",
                100L,
                900L,
                new SyncBatchExecutionPlan.ReadPlan(
                        sourceConnector,
                        10L,
                        "ods.orders",
                        readStrategy,
                        "INCREMENTAL_TIME",
                        "updated_at",
                        true,
                        512,
                        List.of("JDBC_BATCH_READ", "CHECKPOINT_AWARE_READ")
                ),
                new SyncBatchExecutionPlan.WritePlan(
                        targetConnector,
                        20L,
                        "dwd.orders_clean",
                        writeStrategy,
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
                        LocalDateTime.of(2026, 6, 20, 3, 30),
                        true,
                        600,
                        3,
                        "taskId:100/executionId:900/shard",
                        List.of("heartbeat", "progress", "complete", "fail")
                ),
                List.of("测试告警：字段映射由模板治理层提供"),
                LocalDateTime.of(2026, 6, 20, 3, 20)
        );
    }
}
