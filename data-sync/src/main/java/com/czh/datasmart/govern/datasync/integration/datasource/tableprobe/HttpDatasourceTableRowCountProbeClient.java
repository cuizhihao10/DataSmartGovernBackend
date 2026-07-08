/**
 * @Author : Cui
 * @Date: 2026/07/09 22:45
 * @Description DataSmart Govern Backend - HttpDatasourceTableRowCountProbeClient.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.integration.datasource.tableprobe;

import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.datasync.config.DataSyncDatasourceRunOnceProperties;
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
 * datasource-management 表行数探测 HTTP 客户端。
 *
 * <p>该客户端和 run-once、range-probe 使用同一个 datasource-management baseUrl 与服务账号 Header。
 * row-count probe 只读目标端 {@code COUNT(*)}，用于创建任务预检查判断目标表是否为空，不触发任何写入副作用。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HttpDatasourceTableRowCountProbeClient implements DatasourceTableRowCountProbeClient {

    private final RestClient.Builder restClientBuilder;
    private final DataSyncDatasourceRunOnceProperties properties;

    @Override
    public DatasourceTableRowCountProbeResponse probeRowCount(DatasourceTableRowCountProbeRequest request,
                                                              SyncActorContext actorContext) {
        if (request == null || request.getDatasourceId() == null) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "目标表行数探测缺少 datasourceId，全量 INSERT 预检查已终止");
        }
        try {
            RestClient client = restClientBuilder
                    .baseUrl(properties.getBaseUrl())
                    .requestFactory(requestFactory())
                    .build();
            DatasourceTableRowCountProbeEnvelope response = client.post()
                    .uri(properties.getTableRowCountProbePath())
                    .headers(headers -> applyInternalHeaders(headers, actorContext))
                    .body(request)
                    .retrieve()
                    .body(DatasourceTableRowCountProbeEnvelope.class);
            return unwrap(response);
        } catch (RestClientException exception) {
            log.warn("调用 datasource-management row-count probe 失败: datasourceId={}, traceId={}, exceptionType={}",
                    request.getDatasourceId(), traceId(actorContext), exception.getClass().getSimpleName());
            throw new PlatformBusinessException(PlatformErrorCode.EXTERNAL_DEPENDENCY_FAILED,
                    "datasource-management 目标表行数探测暂不可用，全量 INSERT 已按 fail-closed 终止");
        }
    }

    private SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(Math.max(1L, properties.getConnectTimeoutMs())));
        requestFactory.setReadTimeout(Duration.ofMillis(Math.max(1L, properties.getReadTimeoutMs())));
        return requestFactory;
    }

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

    private DatasourceTableRowCountProbeResponse unwrap(DatasourceTableRowCountProbeEnvelope response) {
        if (response == null || response.getCode() == null || response.getCode() != 0 || response.getData() == null) {
            throw new PlatformBusinessException(PlatformErrorCode.EXTERNAL_DEPENDENCY_FAILED,
                    "datasource-management row-count probe 响应不可用，全量 INSERT 预检查已终止");
        }
        return response.getData();
    }

    private String traceId(SyncActorContext actorContext) {
        if (actorContext == null || actorContext.traceId() == null || actorContext.traceId().isBlank()) {
            return "data-sync-table-row-count-probe";
        }
        return actorContext.traceId();
    }
}
