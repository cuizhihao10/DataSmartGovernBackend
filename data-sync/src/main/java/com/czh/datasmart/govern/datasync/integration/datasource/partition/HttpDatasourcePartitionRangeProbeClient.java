/**
 * @Author : Cui
 * @Date: 2026/07/07 23:35
 * @Description DataSmart Govern Backend - HttpDatasourcePartitionRangeProbeClient.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.integration.datasource.partition;

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
 * datasource-management range-probe HTTP 客户端。
 *
 * <p>该客户端和 run-once 客户端使用同一个 baseUrl 与服务账号 Header，但访问的是不同 internal 路由。
 * range-probe 只读源端 min/max/count，不写目标端；因此它可以在真正创建分片账本之前执行，帮助 data-sync
 * 将用户的 {@code AUTO_SPLIT_PK} 配置转换成确定的 ID_RANGE 分片清单。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HttpDatasourcePartitionRangeProbeClient implements DatasourcePartitionRangeProbeClient {

    private final RestClient.Builder restClientBuilder;
    private final DataSyncDatasourceRunOnceProperties properties;

    @Override
    public DatasourcePartitionRangeProbeResponse probeRange(DatasourcePartitionRangeProbeRequest request,
                                                            SyncActorContext actorContext) {
        if (request == null || request.getDatasourceId() == null) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "分片范围探测缺少 datasourceId，AUTO_SPLIT_PK 已终止");
        }
        try {
            RestClient client = restClientBuilder
                    .baseUrl(properties.getBaseUrl())
                    .requestFactory(requestFactory())
                    .build();
            DatasourcePartitionRangeProbeEnvelope response = client.post()
                    .uri(properties.getPartitionRangeProbePath())
                    .headers(headers -> applyInternalHeaders(headers, actorContext))
                    .body(request)
                    .retrieve()
                    .body(DatasourcePartitionRangeProbeEnvelope.class);
            return unwrap(response);
        } catch (RestClientException exception) {
            log.warn("调用 datasource-management range-probe 失败: datasourceId={}, traceId={}, exceptionType={}",
                    request.getDatasourceId(), traceId(actorContext), exception.getClass().getSimpleName());
            throw new PlatformBusinessException(PlatformErrorCode.EXTERNAL_DEPENDENCY_FAILED,
                    "datasource-management range-probe 暂不可用，AUTO_SPLIT_PK 已按 fail-closed 终止");
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

    private DatasourcePartitionRangeProbeResponse unwrap(DatasourcePartitionRangeProbeEnvelope response) {
        if (response == null || response.getCode() == null || response.getCode() != 0 || response.getData() == null) {
            throw new PlatformBusinessException(PlatformErrorCode.EXTERNAL_DEPENDENCY_FAILED,
                    "datasource-management range-probe 响应不可用，AUTO_SPLIT_PK 已终止");
        }
        return response.getData();
    }

    private String traceId(SyncActorContext actorContext) {
        if (actorContext == null || actorContext.traceId() == null || actorContext.traceId().isBlank()) {
            return "data-sync-partition-range-probe";
        }
        return actorContext.traceId();
    }
}
