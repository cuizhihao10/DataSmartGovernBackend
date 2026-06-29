/**
 * @Author : Cui
 * @Date: 2026/06/29 13:18
 * @Description DataSmart Govern Backend - HttpTaskManagementExecutionReceiptClient.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.integration.task.receipt;

import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.datasync.config.DataSyncTaskManagementReceiptProperties;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;

/**
 * 基于 HTTP 的 task-management execution receipt 客户端。
 *
 * <p>本类只负责把 data-sync 的低敏执行结果投递到 task-management internal receipt 路由。
 * 它不会读取 task-management outbox，不会修改 data-sync execution，也不会把对方返回的 data 结构耦合进本模块。</p>
 *
 * <p>日志安全边界：</p>
 * <p>1. 不打印 baseUrl、完整 URI、请求体、响应体或远端 message；</p>
 * <p>2. 只记录 syncTaskId、syncExecutionId、receiptId、traceId 和异常类型；</p>
 * <p>3. 请求体不允许包含 SQL、endpoint、凭据、checkpoint 原始值或样本数据。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HttpTaskManagementExecutionReceiptClient implements TaskManagementExecutionReceiptClient {

    private final RestClient.Builder restClientBuilder;
    private final DataSyncTaskManagementReceiptProperties properties;

    /**
     * 投递 DataSync worker execution receipt。
     */
    @Override
    public void record(TaskManagementExecutionReceiptRequest request, SyncActorContext actorContext) {
        if (!properties.isEnabled()) {
            return;
        }
        requireRequest(request);
        try {
            RestClient client = restClientBuilder
                    .baseUrl(properties.getBaseUrl())
                    .requestFactory(requestFactory())
                    .build();
            TaskManagementExecutionReceiptEnvelope response = client.post()
                    .uri(properties.getRecordPath())
                    .headers(headers -> applyInternalHeaders(headers, actorContext))
                    .body(request)
                    .retrieve()
                    .body(TaskManagementExecutionReceiptEnvelope.class);
            unwrap(request, response);
        } catch (RestClientException exception) {
            log.warn("投递 task-management execution receipt 失败: syncTaskId={}, syncExecutionId={}, receiptId={}, traceId={}, exceptionType={}",
                    request.getSyncTaskId(), request.getSyncExecutionId(), request.getReceiptId(),
                    traceId(actorContext), exception.getClass().getSimpleName());
            throw new PlatformBusinessException(PlatformErrorCode.EXTERNAL_DEPENDENCY_FAILED,
                    "task-management execution receipt 暂不可用，data-sync 已保留本地执行事实，syncExecutionId="
                            + request.getSyncExecutionId());
        }
    }

    /**
     * 基础请求校验。
     *
     * <p>这里不做完整业务校验，task-management 仍是最终契约校验方。
     * 但 receiptId、syncTaskId、syncExecutionId 和 eventType 是最小必需字段，缺失时说明 data-sync 调用方构造逻辑有误。</p>
     */
    private void requireRequest(TaskManagementExecutionReceiptRequest request) {
        if (request == null
                || request.getReceiptId() == null
                || request.getReceiptId().isBlank()
                || request.getSyncTaskId() == null
                || request.getSyncExecutionId() == null
                || request.getEventType() == null
                || request.getEventType().isBlank()) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "task-management execution receipt 请求缺少最小必需字段");
        }
    }

    private SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(Math.max(1L, properties.getConnectTimeoutMs())));
        requestFactory.setReadTimeout(Duration.ofMillis(Math.max(1L, properties.getReadTimeoutMs())));
        return requestFactory;
    }

    /**
     * 写入 internal 调用 Header。
     */
    private void applyInternalHeaders(HttpHeaders headers, SyncActorContext actorContext) {
        headers.set(PlatformContextHeaders.SOURCE_SERVICE, properties.getSourceService());
        headers.set(PlatformContextHeaders.ACTOR_ROLE, properties.getActorRole());
        headers.set(PlatformContextHeaders.ACTOR_TYPE, "SERVICE_ACCOUNT");
        headers.set(PlatformContextHeaders.TRACE_ID, traceId(actorContext));
        if (actorContext != null && actorContext.tenantId() != null) {
            headers.set(PlatformContextHeaders.TENANT_ID, String.valueOf(actorContext.tenantId()));
        }
        if (actorContext != null && actorContext.actorId() != null) {
            headers.set(PlatformContextHeaders.ACTOR_ID, String.valueOf(actorContext.actorId()));
        }
    }

    /**
     * 解包 task-management 响应。
     */
    private void unwrap(TaskManagementExecutionReceiptRequest request,
                        TaskManagementExecutionReceiptEnvelope response) {
        if (response == null || response.getCode() == null || response.getCode() != 0) {
            throw new PlatformBusinessException(PlatformErrorCode.EXTERNAL_DEPENDENCY_FAILED,
                    "task-management execution receipt 未确认接收，syncExecutionId=" + request.getSyncExecutionId());
        }
    }

    private String traceId(SyncActorContext actorContext) {
        if (actorContext == null || actorContext.traceId() == null || actorContext.traceId().isBlank()) {
            return "data-sync-task-management-receipt";
        }
        return actorContext.traceId();
    }
}
