/**
 * @Author : Cui
 * @Date: 2026/07/07 23:59
 * @Description DataSmart Govern Backend - HttpDatasourceMetadataDiscoveryClient.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.integration.datasource.metadata;

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
 * datasource-management 元数据发现 HTTP 客户端。
 *
 * <p>该客户端只用于执行前发现表清单，不执行数据读取和写入。即便如此，表名和字段名也属于企业数据结构信息，
 * 因此调用仍走服务账号 Header、超时控制和低敏异常处理，不在日志中打印响应体。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HttpDatasourceMetadataDiscoveryClient implements DatasourceMetadataDiscoveryClient {

    private final RestClient.Builder restClientBuilder;
    private final DataSyncDatasourceRunOnceProperties properties;

    @Override
    public DatasourceMetadataDiscoveryResponse discover(Long datasourceId,
                                                        DatasourceMetadataDiscoveryRequest request,
                                                        SyncActorContext actorContext) {
        if (datasourceId == null || request == null) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "元数据发现缺少 datasourceId 或 request，SCHEMA_FULL/DATABASE_FULL 已终止");
        }
        try {
            RestClient client = restClientBuilder
                    .baseUrl(properties.getBaseUrl())
                    .requestFactory(requestFactory())
                    .build();
            DatasourceMetadataDiscoveryEnvelope response = client.post()
                    .uri(properties.getMetadataDiscoveryPathTemplate(), datasourceId)
                    .headers(headers -> applyInternalHeaders(headers, actorContext))
                    .body(request)
                    .retrieve()
                    .body(DatasourceMetadataDiscoveryEnvelope.class);
            return unwrap(datasourceId, response);
        } catch (RestClientException exception) {
            log.warn("调用 datasource-management metadata discovery 失败: datasourceId={}, traceId={}, exceptionType={}",
                    datasourceId, traceId(actorContext), exception.getClass().getSimpleName());
            throw new PlatformBusinessException(PlatformErrorCode.EXTERNAL_DEPENDENCY_FAILED,
                    "datasource-management 元数据发现暂不可用，SCHEMA_FULL/DATABASE_FULL 已按 fail-closed 终止");
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

    private DatasourceMetadataDiscoveryResponse unwrap(Long datasourceId, DatasourceMetadataDiscoveryEnvelope response) {
        if (response == null || response.getCode() == null || response.getCode() != 0 || response.getData() == null) {
            throw new PlatformBusinessException(PlatformErrorCode.EXTERNAL_DEPENDENCY_FAILED,
                    "datasource-management 元数据发现响应不可用，datasourceId=" + datasourceId);
        }
        return response.getData();
    }

    private String traceId(SyncActorContext actorContext) {
        if (actorContext == null || actorContext.traceId() == null || actorContext.traceId().isBlank()) {
            return "data-sync-metadata-discovery";
        }
        return actorContext.traceId();
    }
}
