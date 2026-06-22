/**
 * @Author : Cui
 * @Date: 2026/06/22 10:44
 * @Description DataSmart Govern Backend - HttpTaskManagementExecutionReceiptPublisher.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.execution.receipt;

import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.datasource.config.TaskManagementExecutionReceiptProperties;
import com.czh.datasmart.govern.datasource.service.execution.SyncBatchExecutionRunRequest;
import com.czh.datasmart.govern.datasource.service.execution.SyncBatchExecutionRunResult;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 基于 HTTP 的 task-management 执行回执发布器。
 *
 * <p>该实现把 datasource-management Runner 的低敏执行结果发送到 task-management 内部控制面接口。
 * 它只负责通信适配，不负责 task-management 的表结构、幂等裁决或查询视图；这些规则都由接收方服务层保证。</p>
 *
 * <p>失败策略：</p>
 * <p>默认 failOpen=true。原因是执行回执是可观测性和治理闭环，不应在默认配置下反向中断真实同步执行。
 * 如果客户部署要求“控制面审计必须强一致”，可以把 failOpen 设为 false，让发布失败直接抛出异常。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HttpTaskManagementExecutionReceiptPublisher implements TaskManagementExecutionReceiptPublisher {

    private final TaskManagementExecutionReceiptProperties properties;
    private final RestClient.Builder restClientBuilder;

    /**
     * 发布 Runner 执行回执。
     *
     * <p>日志只打印 receiptId、syncTaskId、syncExecutionId、eventType 和异常类型，不打印目标 URL、响应正文、
     * SQL、工具参数、样本数据、checkpoint 原始值或错误正文。</p>
     */
    @Override
    public void publish(SyncBatchExecutionRunRequest request, SyncBatchExecutionRunResult result) {
        if (!Boolean.TRUE.equals(properties.getEnabled())) {
            return;
        }
        TaskManagementExecutionReceiptRequest payload = buildPayload(request, result);
        try {
            RestClient client = restClientBuilder
                    .baseUrl(properties.getTaskManagementBaseUrl())
                    .requestFactory(requestFactory())
                    .build();
            ExecutionReceiptEnvelope response = client.post()
                    .uri(properties.getRecordEndpointPath())
                    .header(PlatformContextHeaders.SOURCE_SERVICE, properties.getSourceService())
                    .header(PlatformContextHeaders.ACTOR_ROLE, "SERVICE_ACCOUNT")
                    .header(PlatformContextHeaders.ACTOR_TYPE, "SERVICE_ACCOUNT")
                    .header(PlatformContextHeaders.TENANT_ID, request.getActorTenantId() == null
                            ? "" : String.valueOf(request.getActorTenantId()))
                    .body(payload)
                    .retrieve()
                    .body(ExecutionReceiptEnvelope.class);
            unwrap(response);
        } catch (RuntimeException exception) {
            handlePublishFailure(payload, exception);
        }
    }

    private TaskManagementExecutionReceiptRequest buildPayload(SyncBatchExecutionRunRequest request,
                                                               SyncBatchExecutionRunResult result) {
        String eventType = eventType(result);
        return new TaskManagementExecutionReceiptRequest(
                receiptId(result, eventType),
                null,
                result.getTaskId(),
                result.getExecutionId(),
                eventType,
                LocalDateTime.now(),
                properties.getExecutorId(),
                properties.getSourceService(),
                result.getBatchRecordsRead(),
                result.getBatchRecordsWritten(),
                result.getBatchFailedRecordCount(),
                result.getTotalRecordsRead(),
                result.getTotalRecordsWritten(),
                result.getTotalFailedRecordCount(),
                null,
                result.getEndOfSource(),
                result.getCompleted(),
                result.getFailed(),
                result.getProgressReported(),
                result.getCheckpointPersisted(),
                result.getCheckpointType(),
                result.getCheckpointValueVisibility(),
                result.getErrorSummary(),
                result.getWarnings() == null ? List.of() : result.getWarnings()
        );
    }

    /**
     * 将 Runner 结果归一化为 task-management 支持的事件类型。
     */
    private String eventType(SyncBatchExecutionRunResult result) {
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

    /**
     * 生成稳定 receiptId。
     *
     * <p>该 ID 只由低敏字段组成。同一批执行结果被 HTTP 重试或消息重放时会生成相同 ID，
     * task-management 可以据此幂等复用；下一批通常会因为累计计数变化而生成新 ID。</p>
     */
    private String receiptId(SyncBatchExecutionRunResult result, String eventType) {
        return "datasource-runner:"
                + result.getTaskId() + ":"
                + result.getExecutionId() + ":"
                + eventType + ":"
                + safeLong(result.getTotalRecordsRead()) + ":"
                + safeLong(result.getTotalRecordsWritten()) + ":"
                + safeLong(result.getTotalFailedRecordCount()) + ":"
                + Boolean.TRUE.equals(result.getCompleted()) + ":"
                + Boolean.TRUE.equals(result.getFailed());
    }

    private SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(Math.max(1L, nullToDefault(properties.getConnectTimeoutMs(), 1000L))));
        requestFactory.setReadTimeout(Duration.ofMillis(Math.max(1L, nullToDefault(properties.getReadTimeoutMs(), 1500L))));
        return requestFactory;
    }

    private void unwrap(ExecutionReceiptEnvelope response) {
        if (response == null) {
            throw new RestClientException("task-management execution receipt 返回空响应");
        }
        if (response.getCode() == null || response.getCode() != 0) {
            throw new RestClientException("task-management execution receipt 返回失败响应，reason="
                    + response.getReason() + ", message=" + response.getMessage());
        }
    }

    private void handlePublishFailure(TaskManagementExecutionReceiptRequest payload, RuntimeException exception) {
        String exceptionType = exception.getClass().getSimpleName();
        if (Boolean.TRUE.equals(properties.getFailOpen())) {
            log.warn("DataSync execution receipt 发布失败但已按 fail-open 跳过: receiptId={}, syncTaskId={}, syncExecutionId={}, eventType={}, exceptionType={}",
                    payload.receiptId(), payload.syncTaskId(), payload.syncExecutionId(), payload.eventType(), exceptionType);
            return;
        }
        throw exception;
    }

    private Long nullToDefault(Long value, Long defaultValue) {
        return value == null ? defaultValue : value;
    }

    private long safeLong(Long value) {
        return value == null ? 0L : value;
    }

    /**
     * task-management 统一响应 envelope 的本地解析结构。
     */
    @Data
    private static class ExecutionReceiptEnvelope {
        private Integer code;
        private String reason;
        private String message;
    }
}
