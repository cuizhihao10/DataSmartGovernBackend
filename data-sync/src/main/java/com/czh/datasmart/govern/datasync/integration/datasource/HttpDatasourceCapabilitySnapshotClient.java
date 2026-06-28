/**
 * @Author : Cui
 * @Date: 2026/06/29 00:09
 * @Description DataSmart Govern Backend - HttpDatasourceCapabilitySnapshotClient.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.integration.datasource;

import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.datasync.config.DataSyncDatasourceCapabilityProperties;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;

/**
 * 基于 HTTP 的 datasource-management 能力快照客户端。
 *
 * <p>该类只负责“如何调用远端服务”，不负责模板业务规则。模板业务规则包括：
 * 是否必须补全 connectorType、快照是否允许模板规划、租户/项目是否一致、源端/目标端是否兼容等，
 * 这些全部放在 SyncTemplateConnectorFactResolver 中。这样拆分后，未来如果快照来源改成本地缓存、
 * Nacos 配置、Kafka 投影或服务网格代理，只需要替换客户端实现。</p>
 *
 * <p>安全边界：</p>
 * <p>1. 日志只记录 datasourceId、traceId 和异常类型，不记录 baseUrl、完整 URI、响应正文、连接信息或远端 message；</p>
 * <p>2. 响应只解析低敏 DTO，不解析或返回 JDBC URL、账号、密码、SQL、样本数据、topic、bucket 或 endpoint；</p>
 * <p>3. 调用失败时抛出平台业务异常，让模板创建 fail-closed，而不是猜测 connector type 后继续放行。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HttpDatasourceCapabilitySnapshotClient implements DatasourceCapabilitySnapshotClient {

    private final RestClient.Builder restClientBuilder;
    private final DataSyncDatasourceCapabilityProperties properties;

    /**
     * 读取数据源能力快照。
     *
     * @param datasourceId 数据源 ID。
     * @param actorContext 当前调用上下文，主要用于透传 traceId 便于跨服务排障。
     * @return 低敏能力快照。
     */
    @Override
    public DatasourceCapabilitySnapshotView getSnapshot(Long datasourceId, SyncActorContext actorContext) {
        if (datasourceId == null) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, "数据源能力快照查询缺少 datasourceId");
        }
        try {
            RestClient client = restClientBuilder
                    .baseUrl(properties.getBaseUrl())
                    .requestFactory(requestFactory())
                    .build();
            DatasourceCapabilitySnapshotEnvelope response = client.get()
                    .uri(properties.getSnapshotPathTemplate(), datasourceId)
                    .header(PlatformContextHeaders.SOURCE_SERVICE, properties.getSourceService())
                    .header(PlatformContextHeaders.ACTOR_ROLE, properties.getActorRole())
                    .header(PlatformContextHeaders.ACTOR_TYPE, "SERVICE_ACCOUNT")
                    .header(PlatformContextHeaders.TRACE_ID, traceId(actorContext))
                    .retrieve()
                    .body(DatasourceCapabilitySnapshotEnvelope.class);
            return unwrap(datasourceId, response);
        } catch (RestClientException exception) {
            log.warn("读取 datasource-management 能力快照失败: datasourceId={}, traceId={}, exceptionType={}",
                    datasourceId, traceId(actorContext), exception.getClass().getSimpleName());
            throw new PlatformBusinessException(PlatformErrorCode.EXTERNAL_DEPENDENCY_FAILED,
                    "无法读取数据源能力快照，模板连接器事实补全已按 fail-closed 终止，datasourceId=" + datasourceId);
        }
    }

    /**
     * 构建每次调用使用的请求工厂。
     *
     * <p>RestClient 本身是轻量构建器模式；这里把超时放进请求工厂，是为了避免 datasource-management
     * 不可达时拖慢用户创建模板的交互链路。能力快照是轻量查询，不应该等待长时间外部 IO。</p>
     */
    private SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(Math.max(1L, properties.getConnectTimeoutMs())));
        requestFactory.setReadTimeout(Duration.ofMillis(Math.max(1L, properties.getReadTimeoutMs())));
        return requestFactory;
    }

    /**
     * 解包远端响应 envelope。
     *
     * <p>这里不把远端 message 原样返回给调用方，是为了避免未来远端排障信息中出现过多内部细节。
     * data-sync 只暴露“快照不可用/不完整”这类业务结论，具体跨服务排障通过 traceId 和服务端日志完成。</p>
     */
    private DatasourceCapabilitySnapshotView unwrap(Long datasourceId, DatasourceCapabilitySnapshotEnvelope response) {
        if (response == null || response.getCode() == null || response.getCode() != 0 || response.getData() == null) {
            throw new PlatformBusinessException(PlatformErrorCode.EXTERNAL_DEPENDENCY_FAILED,
                    "数据源能力快照响应不可用，模板连接器事实补全已终止，datasourceId=" + datasourceId);
        }
        return response.getData();
    }

    private String traceId(SyncActorContext actorContext) {
        if (actorContext == null || actorContext.traceId() == null || actorContext.traceId().isBlank()) {
            return "data-sync-datasource-capability";
        }
        return actorContext.traceId();
    }
}
