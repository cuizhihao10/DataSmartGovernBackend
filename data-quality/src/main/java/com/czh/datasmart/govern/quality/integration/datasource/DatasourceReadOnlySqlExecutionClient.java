/**
 * @Author : Cui
 * @Date: 2026/04/28 21:07
 * @Description DataSmart Govern Backend - DatasourceReadOnlySqlExecutionClient.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.integration.datasource;

import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.quality.config.DatasourceReadOnlySqlExecutionProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.UUID;

/**
 * datasource-management 受控只读 SQL 执行客户端。
 *
 * <p>这个类是 data-quality 到 datasource-management 的“真实扫描执行边界”。
 * coordinator 不直接拼 HTTP 请求，而是通过这个客户端完成远程调用，原因是：
 * 1. 集中处理开关、fail-open、响应校验和日志；
 * 2. 避免 coordinator 同时承担编排、SQL 解析和 HTTP 细节；
 * 3. 后续如果要加重试、熔断、指标、服务账号签名，可以只改这一层。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DatasourceReadOnlySqlExecutionClient {

    private final DatasourceReadOnlySqlExecutionProperties properties;

    private final RestClient.Builder restClientBuilder;

    /**
     * 执行质量指标 SQL。
     *
     * <p>metric SQL 预期返回一行，包含 sample_size、exception_count、measured_value。
     * 如果返回空行，coordinator 会将任务视为执行失败，而不是生成空报告。
     */
    public DatasourceReadOnlySqlExecutionResult executeMetricSql(Long datasourceId, String sql, Integer timeoutSeconds) {
        return executeReadOnlySql(
                datasourceId,
                sql,
                properties.getMetricMaxRows(),
                timeoutSeconds == null ? properties.getMetricQueryTimeoutSeconds() : timeoutSeconds,
                "QUALITY_METRIC_SCAN"
        );
    }

    /**
     * 执行异常样本 SQL。
     *
     * <p>异常样本只用于提供少量证据，不应该把所有异常明细一次性拉回 data-quality。
     * 因此这里会使用扫描计划里的 sampleLimit 和配置上限取较小值。
     */
    public DatasourceReadOnlySqlExecutionResult executeAnomalySampleSql(Long datasourceId, String sql,
                                                                        Integer sampleLimit,
                                                                        Integer timeoutSeconds) {
        int maxRows = applySampleLimit(sampleLimit);
        return executeReadOnlySql(
                datasourceId,
                sql,
                maxRows,
                timeoutSeconds == null ? properties.getAnomalySampleQueryTimeoutSeconds() : timeoutSeconds,
                "QUALITY_ANOMALY_SAMPLE"
        );
    }

    /**
     * 统一执行受控只读 SQL。
     *
     * <p>如果客户端配置关闭，这里直接抛出异常，让 coordinator 走失败回调。
     * 这是更安全的生产默认：没有受控执行能力时，不应该把质量任务伪装成成功。
     */
    private DatasourceReadOnlySqlExecutionResult executeReadOnlySql(Long datasourceId, String sql,
                                                                    Integer maxRows,
                                                                    Integer timeoutSeconds,
                                                                    String purpose) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("datasource-management 只读 SQL 执行客户端未启用");
        }

        DatasourceReadOnlySqlExecutionRequest request = new DatasourceReadOnlySqlExecutionRequest();
        request.setSql(sql);
        request.setMaxRows(maxRows);
        request.setQueryTimeoutSeconds(timeoutSeconds);
        request.setPurpose(purpose);
        request.setActorRole(properties.getActorRole());

        try {
            RemoteApiResponse<DatasourceReadOnlySqlExecutionResult> response = restClientBuilder
                    .baseUrl(properties.getBaseUrl())
                    .build()
                    .post()
                    .uri("/datasources/{id}/sql/read-only/execute", datasourceId)
                    .headers(this::applyPlatformHeaders)
                    .body(request)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (response == null) {
                throw new IllegalStateException("datasource-management 返回空只读 SQL 执行响应");
            }
            if (!response.successful()) {
                throw new IllegalStateException("datasource-management 执行只读 SQL 失败: " + response.getMessage());
            }
            return response.getData();
        } catch (Exception ex) {
            log.warn("调用 datasource-management 执行受控只读 SQL 失败，datasourceId={}, purpose={}, failOpen={}",
                    datasourceId, purpose, properties.isFailOpen(), ex);
            if (properties.isFailOpen()) {
                return null;
            }
            throw ex;
        }
    }

    /**
     * 计算异常样本最大返回行数。
     *
     * <p>如果扫描计划传入更小的 sampleLimit，以扫描计划为准；
     * 如果调用方传入空值或非法值，则回退到配置默认上限。
     */
    private int applySampleLimit(Integer sampleLimit) {
        int configuredMax = properties.getAnomalySampleMaxRows() == null ? 100 : properties.getAnomalySampleMaxRows();
        if (sampleLimit == null || sampleLimit <= 0) {
            return configuredMax;
        }
        return Math.min(sampleLimit, configuredMax);
    }

    /**
     * 写入平台上下文 Header。
     *
     * datasource-management 会优先使用这些 Header 填充只读 SQL 审计，
     * 从而把一次质量任务中的“服务账号身份、来源模块、traceId”保留下来。
     */
    private void applyPlatformHeaders(HttpHeaders headers) {
        headers.set(PlatformContextHeaders.TENANT_ID, String.valueOf(properties.getActorTenantId()));
        headers.set(PlatformContextHeaders.ACTOR_ID, String.valueOf(properties.getActorId()));
        headers.set(PlatformContextHeaders.ACTOR_ROLE, properties.getActorRole());
        headers.set(PlatformContextHeaders.ACTOR_TYPE, properties.getActorType());
        headers.set(PlatformContextHeaders.SOURCE_SERVICE, properties.getSourceService());
        headers.set(PlatformContextHeaders.TRACE_ID, properties.getSourceService() + "-" + UUID.randomUUID());
    }
}
