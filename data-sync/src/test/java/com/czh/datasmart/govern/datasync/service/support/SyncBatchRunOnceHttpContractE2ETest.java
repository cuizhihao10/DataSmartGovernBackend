/**
 * @Author : Cui
 * @Date: 2026/07/07 09:42
 * @Description DataSmart Govern Backend - SyncBatchRunOnceHttpContractE2ETest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.datasync.config.DataSyncDatasourceRunOnceProperties;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionCompleteRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionFailRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncWorkerExecutionPlanView;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.entity.SyncTemplate;
import com.czh.datasmart.govern.datasync.integration.datasource.runonce.DatasourceRunOnceResponse;
import com.czh.datasmart.govern.datasync.integration.datasource.runonce.HttpDatasourceRunOnceClient;
import com.czh.datasmart.govern.datasync.support.SyncExecutionState;
import com.czh.datasmart.govern.datasync.support.SyncTriggerType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * data-sync 到 datasource-management run-once internal API 的 HTTP 契约级 E2E。
 *
 * <p>这个测试刻意放在 data-sync 模块内部，而不是启动完整 Spring Cloud/Nacos/Docker 链路，
 * 是为了补齐“真实 HTTP 序列化合同”这一层守门，同时保持执行速度足够快。前几轮测试已经分别证明：
 * data-sync 控制面可以生成 run-once 计划、datasource-management 执行面可以真实 JDBC 读写数据库。
 * 本测试关注二者之间最容易在微服务演进中断裂的合同细节：URL、HTTP 方法、Header、JSON 字段、响应 envelope、
 * 多批次累计计数推进，以及远端不可用时的 fail-closed 状态回写。</p>
 *
 * <p>为什么不直接把 datasource-management 模块依赖进来调用 Controller：
 * 微服务之间应通过稳定的网络合同交互，而不是共享对方内部类或直接 new Controller。使用轻量 HTTP Server
 * 可以让 {@link HttpDatasourceRunOnceClient} 走真实 {@link RestClient}、真实 JSON 序列化和真实 HTTP 状态码，
 * 同时不引入真实端口、真实数据库或容器启动成本。</p>
 *
 * <p>安全边界：测试服务端只返回低敏摘要，不返回 SQL、JDBC URL、账号密码、样本行、字段值明细或 checkpoint 原始值。
 * 失败用例会故意让远端 body 带上“敏感样式”的文本，再断言 data-sync 生命周期错误不会把它透传出去，
 * 以守住商用环境中内部诊断信息不外泄的底线。</p>
 */
class SyncBatchRunOnceHttpContractE2ETest {

    private static final String RUN_ONCE_PATH = "/internal/sync-batch-runs/run-once";

    /**
     * 测试专用 ObjectMapper。
     *
     * <p>findAndRegisterModules 会自动加载 JavaTimeModule 等模块，避免请求体中的 LocalDateTime
     * 在测试服务端读取 JSON 树时出现时间类型不兼容。这里读取为 JsonNode 而不是完整反序列化 DTO，
     * 是因为本测试的目标是跨服务 JSON 合同，不应该和 datasource-management 的 Java DTO 形成编译期耦合。</p>
     */
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    /**
     * 验证 data-sync 通过真实 HTTP 客户端调用 datasource-management run-once，并在两批次后完成 execution。
     *
     * <p>业务场景：一个离线 FULL 同步任务，把 MySQL 源表 {@code ods.customer} 中 region=EAST 的数据，
     * 按字段映射写入 PostgreSQL 目标表 {@code dwd.customer_clean}。datasource-management 第一次返回
     * “本批成功但还有后续批次”，第二次返回“源端已读完，可 complete”。data-sync 必须把第一批累计读写数
     * 作为第二批 request 的 previousRecordsRead/previousRecordsWritten，最后只 complete 一次。</p>
     */
    @Test
    void fullRunOnceShouldCrossHttpContractAndCompleteAfterTwoBatches() throws Exception {
        try (RunOnceContractServer server = RunOnceContractServer.success(objectMapper)) {
            SyncExecutionLifecycleSupport lifecycleSupport = mock(SyncExecutionLifecycleSupport.class);
            DataSyncTaskManagementReceiptPublisher receiptPublisher = mock(DataSyncTaskManagementReceiptPublisher.class);
            SyncBatchRunOnceDispatchService service = dispatchService(server.baseUrl(), lifecycleSupport, receiptPublisher);
            SyncExecution execution = execution();
            SyncTask task = task();

            SyncBatchRunOnceDispatchResult result = service.dispatchRunOnce(
                    execution,
                    task,
                    fullCustomerTemplate(),
                    workerPlan(),
                    actor());

            assertThat(result.dispatched()).isTrue();
            assertThat(result.completed()).isTrue();
            assertThat(result.failed()).isFalse();
            assertThat(result.dispatchStatus()).isEqualTo("DISPATCHED_AND_COMPLETED");
            assertThat(result.remoteRunStatus()).isEqualTo("SOURCE_EXHAUSTED_COMPLETE_REQUIRED");

            assertThat(server.calls()).hasSize(2);
            RecordedRunOnceCall firstCall = server.calls().get(0);
            RecordedRunOnceCall secondCall = server.calls().get(1);

            assertThat(firstCall.method()).isEqualTo("POST");
            assertThat(firstCall.path()).isEqualTo(RUN_ONCE_PATH);
            assertInternalServiceHeaders(firstCall);

            assertThat(firstCall.body().at("/executionPlan/taskId").asLong()).isEqualTo(11L);
            assertThat(firstCall.body().at("/executionPlan/executionId").asLong()).isEqualTo(88L);
            assertThat(firstCall.body().at("/executionPlan/executionBoundary").asText())
                    .isEqualTo("DATA_SYNC_TO_DATASOURCE_RUN_ONCE_NO_RAW_SQL_NO_CREDENTIALS");
            assertThat(firstCall.body().at("/executionPlan/readPlan/objectLocator").asText())
                    .isEqualTo("ods.customer");
            assertThat(firstCall.body().at("/executionPlan/writePlan/objectLocator").asText())
                    .isEqualTo("dwd.customer_clean");
            assertThat(firstCall.body().at("/executionPlan/readPlan/readStrategy").asText())
                    .isEqualTo("FULL_OBJECT_SCAN");
            assertThat(firstCall.body().at("/executionPlan/writePlan/writeStrategy").asText())
                    .isEqualTo("UPSERT");
            assertThat(firstCall.body().at("/executionPlan/writePlan/conflictPolicy").asText())
                    .isEqualTo("UPDATE_ON_CONFLICT");
            assertThat(firstCall.body().at("/executionPlan/checkpointPlan/checkpointType").asText())
                    .isEqualTo("NONE_OR_FINAL_WATERMARK");
            assertThat(firstCall.body().at("/executionPlan/checkpointPlan/checkpointValueVisibility").asText())
                    .isEqualTo("WORKER_INTERNAL_AND_SYNC_CHECKPOINT_TABLE_ONLY");
            assertThat(textArray(firstCall.body().at("/selectedColumns")))
                    .containsExactly("id", "customer_name", "amount", "region");
            assertThat(textArray(firstCall.body().at("/writeColumns")))
                    .containsExactly("id", "name", "amount", "region");
            assertThat(textArray(firstCall.body().at("/primaryKeyColumns")))
                    .containsExactly("id");
            assertNullOrMissing(firstCall.body().path("checkpointValue"));
            assertThat(firstCall.body().path("previousRecordsRead").asLong()).isZero();
            assertThat(firstCall.body().path("previousRecordsWritten").asLong()).isZero();
            assertThat(firstCall.body().path("previousFailedRecordCount").asLong()).isZero();

            JsonNode filter = firstCall.body().at("/executionPlan/readPlan/filterConditions").get(0);
            assertThat(filter.path("column").asText()).isEqualTo("region");
            assertThat(filter.path("operator").asText()).isEqualTo("EQ");
            assertThat(filter.path("value").asText()).isEqualTo("EAST");
            assertThat(filter.path("valueRequired").asBoolean()).isTrue();

            assertThat(textArray(firstCall.body().at("/executionPlan/readPlan/requiredWorkerCapabilities")))
                    .containsExactly("JDBC_BATCH_READ");
            assertThat(textArray(firstCall.body().at("/executionPlan/writePlan/requiredWorkerCapabilities")))
                    .containsExactly("JDBC_BATCH_WRITE", "IDEMPOTENT_CONFLICT_WRITE");
            assertThat(firstCall.body().at("/executionPlan/runtimeControlPlan/idempotencyScope").asText())
                    .isEqualTo("task:11:execution:88");
            assertThat(textArray(firstCall.body().at("/executionPlan/runtimeControlPlan/requiredCallbacks")))
                    .containsExactly("COMPLETE_OR_FAIL");

            /*
             * 第二次请求复用同一个 request 对象继续推进批次，因此 previous* 字段必须被上一批远端累计计数覆盖。
             * 这就是当前 DataX-style run-once 循环中最小的“可恢复进度交接”：控制面不保存行样本和 SQL，
             * 只保存低敏累计计数，后续真正的 checkpoint 原始值交接会单独进入 checkpoint 表治理。
             */
            assertThat(secondCall.body().path("previousRecordsRead").asLong()).isEqualTo(2L);
            assertThat(secondCall.body().path("previousRecordsWritten").asLong()).isEqualTo(2L);
            assertThat(secondCall.body().path("previousFailedRecordCount").asLong()).isZero();

            String requestBodyText = firstCall.body().toString().toLowerCase();
            assertThat(requestBodyText)
                    .doesNotContain("select ")
                    .doesNotContain("jdbc:")
                    .doesNotContain("password");

            ArgumentCaptor<SyncExecutionCompleteRequest> completeCaptor =
                    ArgumentCaptor.forClass(SyncExecutionCompleteRequest.class);
            verify(lifecycleSupport).completeExecution(eq(task), eq(execution),
                    completeCaptor.capture(), any(SyncActorContext.class));
            assertThat(completeCaptor.getValue().getExecutorId()).isEqualTo("worker-1");
            assertThat(completeCaptor.getValue().getRecordsRead()).isEqualTo(3L);
            assertThat(completeCaptor.getValue().getRecordsWritten()).isEqualTo(3L);
            assertThat(completeCaptor.getValue().getCheckpointRef()).isNull();
            assertThat(completeCaptor.getValue().getIdempotencyKey())
                    .isEqualTo("datasource-run-once-complete-88");

            ArgumentCaptor<DatasourceRunOnceResponse> receiptCaptor =
                    ArgumentCaptor.forClass(DatasourceRunOnceResponse.class);
            verify(receiptPublisher).publishComplete(eq(task), eq(execution),
                    any(SyncActorContext.class), receiptCaptor.capture());
            assertThat(receiptCaptor.getValue().getTotalRecordsRead()).isEqualTo(3L);
            assertThat(receiptCaptor.getValue().getTotalRecordsWritten()).isEqualTo(3L);
            assertThat(receiptCaptor.getValue().getPayloadPolicy())
                    .isEqualTo("LOW_SENSITIVE_RUN_ONCE_REMOTE_RESULT_NO_ROWS_NO_SQL_NO_CREDENTIALS_NO_CHECKPOINT_VALUE");
            verify(lifecycleSupport, never()).failExecution(any(), any(), any(), any());
            verify(receiptPublisher, never()).publishFailed(any(), any(), any(), any(), any());
        }
    }

    /**
     * 验证 datasource-management HTTP 不可用或拒绝访问时，data-sync 会 fail-closed 且不透传远端响应正文。
     *
     * <p>商用系统里，远端错误体经常会带内部 endpoint、SQL 片段、连接器信息或排障上下文。
     * data-sync 作为控制面不能把这些内容写入 execution 错误、任务回执或普通日志正文；它只应该把失败归一化为
     * DATASOURCE_RUN_ONCE_UNAVAILABLE，交给运维侧通过受控日志和 traceId 进一步排查。</p>
     */
    @Test
    void httpFailureShouldFailClosedWithoutLeakingRemoteBody() throws Exception {
        try (RunOnceContractServer server = RunOnceContractServer.forbidden(objectMapper)) {
            SyncExecutionLifecycleSupport lifecycleSupport = mock(SyncExecutionLifecycleSupport.class);
            DataSyncTaskManagementReceiptPublisher receiptPublisher = mock(DataSyncTaskManagementReceiptPublisher.class);
            SyncBatchRunOnceDispatchService service = dispatchService(server.baseUrl(), lifecycleSupport, receiptPublisher);
            SyncExecution execution = execution();
            SyncTask task = task();

            SyncBatchRunOnceDispatchResult result = service.dispatchRunOnce(
                    execution,
                    task,
                    fullCustomerTemplate(),
                    workerPlan(),
                    actor());

            assertThat(result.dispatched()).isTrue();
            assertThat(result.completed()).isFalse();
            assertThat(result.failed()).isTrue();
            assertThat(result.dispatchStatus()).isEqualTo("DISPATCHED_AND_FAILED_BY_CLIENT_EXCEPTION");
            assertThat(result.issueCodes()).containsExactly("DATASOURCE_RUN_ONCE_UNAVAILABLE");
            assertThat(server.calls()).hasSize(1);

            ArgumentCaptor<SyncExecutionFailRequest> failCaptor =
                    ArgumentCaptor.forClass(SyncExecutionFailRequest.class);
            verify(lifecycleSupport).failExecution(eq(task), eq(execution),
                    failCaptor.capture(), any(SyncActorContext.class));
            assertThat(failCaptor.getValue().getErrorType())
                    .isEqualTo("CONNECTOR_RUNTIME_RUN_ONCE_CALL_FAILED");
            assertThat(failCaptor.getValue().getErrorCode())
                    .isEqualTo("DATASOURCE_RUN_ONCE_UNAVAILABLE");
            assertThat(failCaptor.getValue().getErrorMessage())
                    .contains("fail-closed")
                    .doesNotContain("select")
                    .doesNotContain("jdbc")
                    .doesNotContain("credential")
                    .doesNotContain("password");
            assertThat(failCaptor.getValue().getSamplePayload()).isNull();
            assertThat(failCaptor.getValue().getSourceRecordKey()).isNull();
            assertThat(failCaptor.getValue().getTargetRecordKey()).isNull();
            assertThat(failCaptor.getValue().getIdempotencyKey())
                    .isEqualTo("datasource-run-once-fail-88-DATASOURCE_RUN_ONCE_UNAVAILABLE");

            verify(receiptPublisher).publishFailed(eq(task), eq(execution), any(SyncActorContext.class),
                    eq("DATASOURCE_RUN_ONCE_UNAVAILABLE"), eq(List.of("DATASOURCE_RUN_ONCE_UNAVAILABLE")));
            verify(lifecycleSupport, never()).completeExecution(any(), any(), any(), any());
            verify(receiptPublisher, never()).publishComplete(any(), any(), any(), any());
        }
    }

    private SyncBatchRunOnceDispatchService dispatchService(String baseUrl,
                                                            SyncExecutionLifecycleSupport lifecycleSupport,
                                                            DataSyncTaskManagementReceiptPublisher receiptPublisher) {
        DataSyncDatasourceRunOnceProperties properties = runOnceProperties(baseUrl);
        HttpDatasourceRunOnceClient httpClient = new HttpDatasourceRunOnceClient(RestClient.builder(), properties);
        return new SyncBatchRunOnceDispatchService(
                bridgePlanSupport(),
                httpClient,
                properties,
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

    private DataSyncDatasourceRunOnceProperties runOnceProperties(String baseUrl) {
        DataSyncDatasourceRunOnceProperties properties = new DataSyncDatasourceRunOnceProperties();
        properties.setEnabled(true);
        properties.setBaseUrl(baseUrl);
        properties.setRunOncePath(RUN_ONCE_PATH);
        properties.setDefaultFetchSize(2);
        properties.setDefaultWriteBatchSize(2);
        properties.setDefaultCommitIntervalRecords(2);
        properties.setMaxRunOnceBatches(5);
        properties.setConnectTimeoutMs(1000L);
        properties.setReadTimeoutMs(3000L);
        return properties;
    }

    private SyncExecution execution() {
        SyncExecution execution = new SyncExecution();
        execution.setId(88L);
        execution.setTenantId(7L);
        execution.setProjectId(101L);
        execution.setWorkspaceId(301L);
        execution.setSyncTaskId(11L);
        execution.setExecutionNo(3L);
        execution.setExecutionState(SyncExecutionState.RUNNING.name());
        execution.setTriggerType(SyncTriggerType.MANUAL.name());
        execution.setExecutorId("worker-1");
        execution.setLeaseExpireTime(LocalDateTime.now().plusMinutes(2));
        execution.setRecordsRead(0L);
        execution.setRecordsWritten(0L);
        execution.setFailedRecordCount(0L);
        execution.setTriggeredBy(1001L);
        return execution;
    }

    private SyncTask task() {
        SyncTask task = new SyncTask();
        task.setId(11L);
        task.setTenantId(7L);
        task.setProjectId(101L);
        task.setWorkspaceId(301L);
        task.setTemplateId(22L);
        task.setCurrentState("RUNNING");
        return task;
    }

    private SyncTemplate fullCustomerTemplate() {
        SyncTemplate template = new SyncTemplate();
        template.setId(22L);
        template.setTenantId(7L);
        template.setProjectId(101L);
        template.setWorkspaceId(301L);
        template.setSourceDatasourceId(10001L);
        template.setTargetDatasourceId(10002L);
        template.setSourceConnectorType("MYSQL");
        template.setTargetConnectorType("POSTGRESQL");
        template.setSourceSchemaName("ods");
        template.setSourceObjectName("customer");
        template.setTargetSchemaName("dwd");
        template.setTargetObjectName("customer_clean");
        template.setSyncMode("FULL");
        template.setSyncScopeType("SINGLE_OBJECT");
        /*
         * 使用 UPSERT 是为了让跨批次、重试和故障恢复更接近生产推荐路径。
         * APPEND 在远端部分提交后重试可能造成重复数据，通常需要额外 checkpoint、去重键或补偿策略兜底。
         */
        template.setWriteStrategy("UPSERT");
        template.setPrimaryKeyField("id");
        template.setIncrementalField("updated_at");
        template.setFieldMappingConfig("""
                [
                  {"sourceField":"id","targetField":"id"},
                  {"sourceField":"customer_name","targetField":"name"},
                  {"sourceField":"amount","targetField":"amount"},
                  {"sourceField":"region","targetField":"region"}
                ]
                """);
        /*
         * 用户界面的 where 条件在控制面保存为结构化 filterConfig。
         * data-sync 只负责把条件翻译成跨服务 JSON 合同，真实 SQL 拼接和 PreparedStatement 参数绑定
         * 仍由 datasource-management 的方言执行层完成，避免控制面持有 raw SQL。
         */
        template.setFilterConfig("""
                {
                  "conditions": [
                    {"field":"region","operator":"=","value":"EAST"}
                  ]
                }
                """);
        template.setEnabled(true);
        return template;
    }

    private SyncWorkerExecutionPlanView workerPlan() {
        return new SyncWorkerExecutionPlanView(
                true,
                "READY_TO_RUN",
                7L,
                101L,
                301L,
                11L,
                88L,
                3L,
                SyncExecutionState.RUNNING.name(),
                SyncTriggerType.MANUAL.name(),
                "worker-1",
                LocalDateTime.now().plusMinutes(2),
                22L,
                10001L,
                10002L,
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
                false,
                false,
                false,
                false,
                List.of(),
                List.of("CLAIM_ALREADY_MARKED_RUNNING_DO_NOT_CALL_START"),
                List.of(),
                List.of(),
                "LOW_SENSITIVE_WORKER_PLAN_METADATA_ONLY");
    }

    private SyncActorContext actor() {
        return new SyncActorContext(7L, 1001L, "SERVICE_ACCOUNT", "trace-http-contract-e2e",
                "PROJECT", "project_id IN ${actorProjectIds}", List.of(101L), false);
    }

    private void assertInternalServiceHeaders(RecordedRunOnceCall call) {
        assertThat(call.headerValue(PlatformContextHeaders.SOURCE_SERVICE)).isEqualTo("data-sync");
        assertThat(call.headerValue(PlatformContextHeaders.ACTOR_ROLE)).isEqualTo("SERVICE_ACCOUNT");
        assertThat(call.headerValue(PlatformContextHeaders.ACTOR_TYPE)).isEqualTo("SERVICE_ACCOUNT");
        assertThat(call.headerValue(PlatformContextHeaders.TRACE_ID)).isEqualTo("trace-http-contract-e2e");
        assertThat(call.headerValue(PlatformContextHeaders.TENANT_ID)).isEqualTo("7");
        assertThat(call.headerValue(PlatformContextHeaders.ACTOR_ID)).isEqualTo("1001");
    }

    private List<String> textArray(JsonNode node) {
        List<String> values = new ArrayList<>();
        node.forEach(item -> values.add(item.asText()));
        return values;
    }

    private void assertNullOrMissing(JsonNode node) {
        assertThat(node == null || node.isMissingNode() || node.isNull()).isTrue();
    }

    /**
     * 本地轻量 run-once 合同服务端。
     *
     * <p>它不是 datasource-management 的替代实现，只用于在测试中承接真实 HTTP 请求。
     * 服务端会记录请求方法、路径、Header 和 JSON body，再按预设响应序列返回低敏 envelope。
     * 这样测试可以同时验证“客户端真的发出了什么”和“data-sync 如何消费远端响应”。</p>
     */
    private static final class RunOnceContractServer implements AutoCloseable {

        private final ObjectMapper objectMapper;
        private final HttpServer server;
        private final List<ContractResponse> responses;
        private final List<RecordedRunOnceCall> calls = new ArrayList<>();

        private RunOnceContractServer(ObjectMapper objectMapper, List<ContractResponse> responses) throws IOException {
            this.objectMapper = objectMapper;
            this.responses = responses;
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            this.server.createContext(RUN_ONCE_PATH, this::handleRunOnce);
            this.server.start();
        }

        private static RunOnceContractServer success(ObjectMapper objectMapper) throws IOException {
            return new RunOnceContractServer(objectMapper, List.of(
                    ContractResponse.ok(successEnvelope(objectMapper,
                            "BATCH_WRITTEN_MORE_REMAIN", 2L, 2L, 2L, 2L, false, true, false)),
                    ContractResponse.ok(successEnvelope(objectMapper,
                            "SOURCE_EXHAUSTED_COMPLETE_REQUIRED", 1L, 1L, 3L, 3L, true, false, true))
            ));
        }

        private static RunOnceContractServer forbidden(ObjectMapper objectMapper) throws IOException {
            /*
             * 故意在远端错误体中放入敏感样式文本，验证 HttpDatasourceRunOnceClient 和调度服务
             * 不会把远端正文拼进 data-sync 生命周期错误、回执或普通断言输出。
             */
            String remoteBody = """
                    {"code":403,"message":"forbidden select * from secret_table jdbc:mysql://internal password=credential","data":null}
                    """;
            return new RunOnceContractServer(objectMapper, List.of(ContractResponse.status(403, remoteBody)));
        }

        private String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private List<RecordedRunOnceCall> calls() {
            return calls;
        }

        private void handleRunOnce(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeJson(exchange, 405, "{\"code\":405,\"message\":\"method not allowed\",\"data\":null}");
                return;
            }

            JsonNode body = objectMapper.readTree(exchange.getRequestBody());
            calls.add(new RecordedRunOnceCall(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    snapshotHeaders(exchange.getRequestHeaders()),
                    body));

            int responseIndex = Math.min(calls.size() - 1, responses.size() - 1);
            ContractResponse response = responses.get(responseIndex);
            writeJson(exchange, response.status(), response.body());
        }

        private static Map<String, List<String>> snapshotHeaders(Headers headers) {
            Map<String, List<String>> snapshot = new LinkedHashMap<>();
            headers.forEach((name, values) -> snapshot.put(name, List.copyOf(values)));
            return snapshot;
        }

        private static String successEnvelope(ObjectMapper objectMapper,
                                              String runStatus,
                                              Long batchRecordsRead,
                                              Long batchRecordsWritten,
                                              Long totalRecordsRead,
                                              Long totalRecordsWritten,
                                              boolean endOfSource,
                                              boolean progressCallbackRecommended,
                                              boolean completeCallbackRecommended) {
            try {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("taskId", 11L);
                data.put("executionId", 88L);
                data.put("runStatus", runStatus);
                data.put("batchRecordsRead", batchRecordsRead);
                data.put("batchRecordsWritten", batchRecordsWritten);
                data.put("batchFailedRecordCount", 0L);
                data.put("totalRecordsRead", totalRecordsRead);
                data.put("totalRecordsWritten", totalRecordsWritten);
                data.put("totalFailedRecordCount", 0L);
                data.put("endOfSource", endOfSource);
                data.put("failed", false);
                data.put("progressCallbackRecommended", progressCallbackRecommended);
                data.put("checkpointCallbackRecommended", false);
                data.put("checkpointCandidateProduced", false);
                data.put("completeCallbackRecommended", completeCallbackRecommended);
                data.put("failCallbackRecommended", false);
                data.put("checkpointType", "NONE_OR_FINAL_WATERMARK");
                data.put("checkpointValueVisibility", "WORKER_INTERNAL_AND_SYNC_CHECKPOINT_TABLE_ONLY");
                data.put("payloadPolicy", "LOW_SENSITIVE_RUN_ONCE_RESULT_NO_ROWS_NO_SQL_NO_CREDENTIALS_NO_CHECKPOINT_VALUE");

                Map<String, Object> envelope = new LinkedHashMap<>();
                envelope.put("code", 0);
                envelope.put("message", "ok");
                envelope.put("data", data);
                return objectMapper.writeValueAsString(envelope);
            } catch (IOException exception) {
                throw new IllegalStateException("无法构造 run-once 测试响应 envelope", exception);
            }
        }

        private static void writeJson(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    /**
     * HTTP 响应脚本项。
     *
     * <p>测试服务端按请求序号返回这些响应，用来模拟 datasource-management 在不同批次下的状态建议。</p>
     */
    private record ContractResponse(int status, String body) {

        private static ContractResponse ok(String body) {
            return status(200, body);
        }

        private static ContractResponse status(int status, String body) {
            return new ContractResponse(status, body);
        }
    }

    /**
     * 服务端捕获到的一次 run-once 请求。
     *
     * <p>Header 使用大小写不敏感查询，因为 HTTP Header 名称不区分大小写，不同客户端/服务端实现可能保留不同大小写。</p>
     */
    private record RecordedRunOnceCall(String method,
                                       String path,
                                       Map<String, List<String>> headers,
                                       JsonNode body) {

        private String headerValue(String name) {
            return headers.entrySet()
                    .stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .flatMap(entry -> entry.getValue().stream())
                    .findFirst()
                    .orElse(null);
        }
    }
}
