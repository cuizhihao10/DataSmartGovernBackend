/**
 * @Author : Cui
 * @Date: 2026/06/20 16:25
 * @Description DataSmart Govern Backend - SyncBatchExecutionRunnerTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.execution;

import com.czh.datasmart.govern.datasource.controller.dto.SyncBatchExecutionPlan;
import com.czh.datasmart.govern.datasource.controller.dto.SyncCompleteRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncFailRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncProgressRequest;
import com.czh.datasmart.govern.datasource.entity.SyncTask;
import com.czh.datasmart.govern.datasource.service.SyncTaskService;
import com.czh.datasmart.govern.datasource.service.execution.jdbc.MySqlSyncJdbcDialect;
import com.czh.datasmart.govern.datasource.service.execution.jdbc.PostgreSqlSyncJdbcDialect;
import com.czh.datasmart.govern.datasource.service.execution.jdbc.SqlServerSyncJdbcDialect;
import com.czh.datasmart.govern.datasource.service.execution.jdbc.SyncJdbcDialectRegistry;
import com.czh.datasmart.govern.datasource.service.execution.receipt.TaskManagementExecutionReceiptPublisher;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 同步单批执行 Runner 测试。
 *
 * <p>这组测试验证的是 datasource-management 内部执行链路是否已经形成最小闭环：
 * executionPlan 准备、reader 读取、writer 写入、progress/checkpoint 回写、complete/fail 回写。
 * 测试不会连接真实数据库，因为 JDBC reader/writer 已在独立测试中覆盖；
 * 这里重点看 Runner 是否正确编排“业务状态”和“低敏控制面回调”。</p>
 *
 * <p>测试替身使用 JDK 原生动态代理和手写 fake，而不是 Mockito。
 * 这样做的原因是当前项目固定 JDK 21，Mockito inline mock 在较新 JDK 上会打印动态 agent 警告；
 * 当前 Runner 只依赖接口，手写 fake 已经足够表达测试意图，也能让测试输出更稳定。</p>
 */
class SyncBatchExecutionRunnerTest {

    private final SyncBatchExecutionPreparationService preparationService = new SyncBatchExecutionPreparationService(
            new SyncJdbcDialectRegistry(List.of(
                    new MySqlSyncJdbcDialect(),
                    new PostgreSqlSyncJdbcDialect(),
                    new SqlServerSyncJdbcDialect()
            ))
    );

    @Test
    void runOnceShouldReportProgressAndKeepExecutionOpenWhenMoreBatchesRemain() {
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
        RecordingTaskService taskService = new RecordingTaskService();
        RecordingReceiptPublisher receiptPublisher = new RecordingReceiptPublisher();
        SyncBatchExecutionRunner runner = runner(reader, writer, taskService, receiptPublisher);

        SyncBatchExecutionRunResult result = runner.runOnce(runRequest(10L, 9L, 0L, "2026-06-20T16:19:00"));

        assertFalse(result.getCompleted());
        assertFalse(result.getFailed());
        assertTrue(result.getProgressReported());
        assertTrue(result.getCheckpointPersisted());
        assertEquals(12L, result.getTotalRecordsRead());
        assertEquals(11L, result.getTotalRecordsWritten());

        assertEquals(1, taskService.progressRequests.size());
        SyncProgressRequest progressRequest = taskService.progressRequests.get(0);
        assertEquals(900L, progressRequest.getExecutionId());
        assertEquals(12L, progressRequest.getRecordsRead());
        assertEquals(11L, progressRequest.getRecordsWritten());
        assertEquals("TIME_WATERMARK", progressRequest.getCheckpointType());
        assertEquals("2026-06-20T16:21:00", progressRequest.getCheckpointValue());
        assertEquals("shard-0", progressRequest.getShardOrPartition());
        assertEquals(0, taskService.completeRequests.size());
        assertEquals(0, taskService.failRequests.size());
        assertEquals(1, receiptPublisher.results.size());
        assertEquals("CHECKPOINT", receiptPublisher.lastEventType());
    }

    @Test
    void runOnceShouldCompleteExecutionWhenSourceEnds() {
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
        RecordingTaskService taskService = new RecordingTaskService();
        RecordingReceiptPublisher receiptPublisher = new RecordingReceiptPublisher();
        SyncBatchExecutionRunner runner = runner(reader, writer, taskService, receiptPublisher);

        SyncBatchExecutionRunResult result = runner.runOnce(runRequest(0L, 0L, 0L, "2026-06-20T16:21:00"));

        assertTrue(result.getCompleted());
        assertFalse(result.getFailed());
        assertTrue(result.getEndOfSource());

        assertEquals(1, taskService.completeRequests.size());
        SyncCompleteRequest completeRequest = taskService.completeRequests.get(0);
        assertEquals(900L, completeRequest.getExecutionId());
        assertEquals(1L, completeRequest.getRecordsRead());
        assertEquals(1L, completeRequest.getRecordsWritten());
        assertEquals(0L, completeRequest.getFailedRecordCount());
        assertEquals(0, taskService.failRequests.size());
        assertEquals(1, receiptPublisher.results.size());
        assertEquals("COMPLETE", receiptPublisher.lastEventType());
    }

    @Test
    void runOnceShouldFailWhenReaderReportsError() {
        StaticReader reader = new StaticReader(new SyncBatchReadResult(
                0L,
                false,
                false,
                "源端连接超时",
                null
        ));
        StaticWriter writer = new StaticWriter(new SyncBatchWriteResult(0L, 0L, true, null));
        RecordingTaskService taskService = new RecordingTaskService();
        RecordingReceiptPublisher receiptPublisher = new RecordingReceiptPublisher();
        SyncBatchExecutionRunner runner = runner(reader, writer, taskService, receiptPublisher);

        SyncBatchExecutionRunResult result = runner.runOnce(runRequest(0L, 0L, 0L, "2026-06-20T16:21:00"));

        assertTrue(result.getFailed());
        assertFalse(result.getProgressReported());
        assertTrue(result.getErrorSummary().contains("读取阶段失败"));
        assertFalse(writer.invoked);
        assertEquals(1, taskService.failRequests.size());
        assertEquals(1, receiptPublisher.results.size());
        assertEquals("FAILED", receiptPublisher.lastEventType());
    }

    @Test
    void runOnceShouldReportProgressThenFailWhenWriterReportsError() {
        StaticReader reader = new StaticReader(new SyncBatchReadResult(
                2L,
                false,
                false,
                null,
                new SyncBatchRecordBatch(
                        List.of("id", "updated_at", "amount"),
                        List.of(
                                Map.of("id", 4L, "updated_at", "2026-06-20T16:23:00", "amount", 400),
                                Map.of("id", 5L, "updated_at", "2026-06-20T16:24:00", "amount", 500)
                        )
                )
        ));
        StaticWriter writer = new StaticWriter(new SyncBatchWriteResult(0L, 2L, false, "目标端唯一键冲突过多"));
        RecordingTaskService taskService = new RecordingTaskService();
        RecordingReceiptPublisher receiptPublisher = new RecordingReceiptPublisher();
        SyncBatchExecutionRunner runner = runner(reader, writer, taskService, receiptPublisher);

        SyncBatchExecutionRunResult result = runner.runOnce(runRequest(3L, 3L, 1L, "2026-06-20T16:22:00"));

        assertTrue(result.getFailed());
        assertTrue(result.getProgressReported());
        assertEquals(5L, result.getTotalRecordsRead());
        assertEquals(3L, result.getTotalRecordsWritten());
        assertEquals(3L, result.getTotalFailedRecordCount());
        assertEquals(1, taskService.progressRequests.size());
        assertEquals(1, taskService.failRequests.size());
        assertEquals(1, receiptPublisher.results.size());
        assertEquals("FAILED", receiptPublisher.lastEventType());
    }

    @Test
    void runOnceShouldFailClosedWhenIncrementalCheckpointIsMissing() {
        StaticReader reader = new StaticReader(new SyncBatchReadResult(0L, false, false, null, null));
        StaticWriter writer = new StaticWriter(new SyncBatchWriteResult(0L, 0L, true, null));
        RecordingTaskService taskService = new RecordingTaskService();
        RecordingReceiptPublisher receiptPublisher = new RecordingReceiptPublisher();
        SyncBatchExecutionRunner runner = runner(reader, writer, taskService, receiptPublisher);

        SyncBatchExecutionRunResult result = runner.runOnce(runRequest(0L, 0L, 0L, null));

        assertTrue(result.getFailed());
        assertTrue(result.getErrorSummary().contains("checkpointValue"));
        assertFalse(reader.invoked);
        assertFalse(writer.invoked);
        assertEquals(1, taskService.failRequests.size());
        assertEquals(1, receiptPublisher.results.size());
        assertEquals("FAILED", receiptPublisher.lastEventType());
    }

    /**
     * 创建 Runner 测试对象。
     *
     * <p>把构造逻辑集中在这里，是为了后续 Runner 新增端口依赖时不需要在每个测试里重复修改构造参数。
     * 也能让每个测试更聚焦在“输入、输出、状态回写、执行回执发布”本身。</p>
     */
    private SyncBatchExecutionRunner runner(SyncBatchReader reader,
                                            SyncBatchWriter writer,
                                            RecordingTaskService taskService,
                                            RecordingReceiptPublisher receiptPublisher) {
        return new SyncBatchExecutionRunner(
                preparationService,
                reader,
                writer,
                taskService.proxy(),
                receiptPublisher
        );
    }

    /**
     * 构造 Runner 请求。
     *
     * <p>测试固定使用增量时间水位场景，因为这是最能体现 checkpoint 参数绑定、下一水位解析和累计 progress 回写的模式。
     * 全量扫描没有 checkpointValue 参数，覆盖价值反而较低。</p>
     */
    private SyncBatchExecutionRunRequest runRequest(Long previousRecordsRead,
                                                    Long previousRecordsWritten,
                                                    Long previousFailedRecordCount,
                                                    Object checkpointValue) {
        return new SyncBatchExecutionRunRequest(
                new SyncBatchExecutionPreparationRequest(
                        incrementalPlan(),
                        List.of("id", "updated_at", "amount"),
                        List.of("id", "updated_at", "amount"),
                        List.of("id")
                ),
                700L,
                "WORKER",
                1L,
                "shard-0",
                checkpointValue,
                previousRecordsRead,
                previousRecordsWritten,
                previousFailedRecordCount
        );
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

    /**
     * 静态 reader 测试替身。
     * 它只返回预设结果，并记录是否被调用，用于验证缺少 checkpoint 时 Runner 是否在读取前就 fail-closed。
     */
    private static class StaticReader implements SyncBatchReader {

        private final SyncBatchReadResult result;
        private boolean invoked;

        private StaticReader(SyncBatchReadResult result) {
            this.result = result;
        }

        @Override
        public SyncBatchReadResult readNextBatch(SyncBatchReadContext context) {
            invoked = true;
            return result;
        }
    }

    /**
     * 静态 writer 测试替身。
     * 它不关心真实记录内容，只验证 Runner 是否在应当写入时调用 writer。
     */
    private static class StaticWriter implements SyncBatchWriter {

        private final SyncBatchWriteResult result;
        private boolean invoked;

        private StaticWriter(SyncBatchWriteResult result) {
            this.result = result;
        }

        @Override
        public SyncBatchWriteResult writeBatch(SyncBatchWriteContext context, SyncBatchRecordBatch recordBatch) {
            invoked = true;
            return result;
        }
    }

    /**
     * 执行回执发布器测试替身。
     *
     * <p>它不发 HTTP，只记录 Runner 是否在每个出口都发布了低敏执行结果。
     * 这里复刻发布器的事件类型归一逻辑，是为了让测试能直接表达“这个结果应该形成哪类 task-management execution receipt”。</p>
     */
    private static class RecordingReceiptPublisher implements TaskManagementExecutionReceiptPublisher {

        private final List<SyncBatchExecutionRunRequest> requests = new ArrayList<>();
        private final List<SyncBatchExecutionRunResult> results = new ArrayList<>();

        @Override
        public void publish(SyncBatchExecutionRunRequest request, SyncBatchExecutionRunResult result) {
            requests.add(request);
            results.add(result);
        }

        private String lastEventType() {
            SyncBatchExecutionRunResult result = results.get(results.size() - 1);
            if (Boolean.TRUE.equals(result.getFailed())) {
                return "FAILED";
            }
            if (Boolean.TRUE.equals(result.getCompleted())) {
                return "COMPLETE";
            }
            if (Boolean.TRUE.equals(result.getCheckpointPersisted())) {
                return "CHECKPOINT";
            }
            return "PROGRESS";
        }
    }

    /**
     * `SyncTaskService` 调用记录器。
     *
     * <p>由于 `SyncTaskService` 继承 MyBatis-Plus 的 `IService`，手写实现会被迫实现大量无关方法。
     * 这里用 JDK 动态代理只拦截 Runner 会调用的三个方法：
     * reportProgress、completeExecution、failExecution。其他方法保持默认空返回，避免测试被无关接口噪音淹没。</p>
     */
    private static class RecordingTaskService implements InvocationHandler {

        private final List<SyncProgressRequest> progressRequests = new ArrayList<>();
        private final List<SyncCompleteRequest> completeRequests = new ArrayList<>();
        private final List<SyncFailRequest> failRequests = new ArrayList<>();

        private SyncTaskService proxy() {
            return (SyncTaskService) Proxy.newProxyInstance(
                    SyncTaskService.class.getClassLoader(),
                    new Class[]{SyncTaskService.class},
                    this
            );
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "reportProgress" -> {
                    progressRequests.add((SyncProgressRequest) args[1]);
                    yield new SyncTask();
                }
                case "completeExecution" -> {
                    completeRequests.add((SyncCompleteRequest) args[1]);
                    yield new SyncTask();
                }
                case "failExecution" -> {
                    failRequests.add((SyncFailRequest) args[1]);
                    yield new SyncTask();
                }
                default -> defaultReturnValue(method);
            };
        }

        private Object defaultReturnValue(Method method) {
            Class<?> returnType = method.getReturnType();
            if (returnType == Boolean.TYPE) {
                return false;
            }
            if (returnType == Integer.TYPE || returnType == Long.TYPE || returnType == Short.TYPE || returnType == Byte.TYPE) {
                return 0;
            }
            return null;
        }
    }
}
