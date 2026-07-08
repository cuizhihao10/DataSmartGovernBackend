/**
 * @Author : Cui
 * @Date: 2026/07/08 16:44
 * @Description DataSmart Govern Backend - HttpDatasourceReadOnlySqlClient.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.integration.datasource.sql;

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
 * datasource-management 受控只读 SQL HTTP 客户端。
 *
 * <p>这个客户端是 data-sync 与 datasource-management 的边界适配器：
 * data-sync 传入源端数据源 ID、SQL 和低敏上下文，datasource-management 负责实际 JDBC 连接、只读门禁、
 * 查询超时、行数限制和审计。客户端层不打印 SQL、不打印响应体，只记录 datasourceId、traceId 和异常类型，
 * 避免日志泄露用户 SQL 中可能包含的业务字段、常量或敏感筛选条件。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HttpDatasourceReadOnlySqlClient implements DatasourceReadOnlySqlClient {

    /**
     * Spring Boot 提供的 RestClient 构建器。
     */
    private final RestClient.Builder restClientBuilder;

    /**
     * datasource-management 远程调用配置。
     */
    private final DataSyncDatasourceRunOnceProperties properties;

    @Override
    public DatasourceReadOnlySqlResponse execute(Long datasourceId,
                                                 DatasourceReadOnlySqlRequest request,
                                                 SyncActorContext actorContext) {
        if (datasourceId == null || request == null) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "SQL 检查缺少 datasourceId 或 request，已按 fail-closed 终止");
        }
        try {
            RestClient client = restClientBuilder
                    .baseUrl(properties.getBaseUrl())
                    .requestFactory(requestFactory())
                    .build();
            DatasourceReadOnlySqlEnvelope response = client.post()
                    .uri(properties.getReadOnlySqlExecutePathTemplate(), datasourceId)
                    .headers(headers -> applyInternalHeaders(headers, actorContext))
                    .body(request)
                    .retrieve()
                    .body(DatasourceReadOnlySqlEnvelope.class);
            return unwrap(datasourceId, response);
        } catch (RestClientException exception) {
            log.warn("调用 datasource-management read-only SQL 检查失败: datasourceId={}, traceId={}, exceptionType={}",
                    datasourceId, traceId(actorContext), exception.getClass().getSimpleName());
            throw new PlatformBusinessException(PlatformErrorCode.EXTERNAL_DEPENDENCY_FAILED,
                    "datasource-management 受控只读 SQL 检查暂不可用，SQL 语句模式已按 fail-closed 终止");
        }
    }

    /**
     * 构造带超时的 HTTP 请求工厂。
     *
     * <p>创建向导 SQL 检查是同步交互，不能因为源库慢查询让页面无限等待。
     * 这里复用 run-once 配置中的连接/读取超时，后续如果 SQL 检查需要独立阈值，可以在同一个配置类中拆出更细粒度字段。</p>
     */
    private SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(Math.max(1L, properties.getConnectTimeoutMs())));
        requestFactory.setReadTimeout(Duration.ofMillis(Math.max(1L, properties.getReadTimeoutMs())));
        return requestFactory;
    }

    /**
     * 注入服务间调用 Header。
     *
     * <p>Header 中的角色和来源服务会被 datasource-management Controller 优先采纳。
     * 请求体中的 actorRole 只是兼容字段；真正的服务间可信身份应来自网关、服务网格或内部调用链 Header。</p>
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
     * 解包平台统一响应。
     */
    private DatasourceReadOnlySqlResponse unwrap(Long datasourceId, DatasourceReadOnlySqlEnvelope response) {
        if (response == null || response.getCode() == null || response.getCode() != 0 || response.getData() == null) {
            throw new PlatformBusinessException(PlatformErrorCode.EXTERNAL_DEPENDENCY_FAILED,
                    "datasource-management 受控只读 SQL 检查响应不可用，datasourceId=" + datasourceId);
        }
        return response.getData();
    }

    private String traceId(SyncActorContext actorContext) {
        if (actorContext == null || actorContext.traceId() == null || actorContext.traceId().isBlank()) {
            return "data-sync-custom-sql-check";
        }
        return actorContext.traceId();
    }
}
