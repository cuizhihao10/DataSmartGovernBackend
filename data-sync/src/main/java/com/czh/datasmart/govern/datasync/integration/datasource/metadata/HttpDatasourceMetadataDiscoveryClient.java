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
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /**
     * 只提取 datasource-management 本地 ApiResponse 中的 message 字段。
     *
     * <p>这里没有把远端响应体整体透出，是因为未来远端错误体可能包含内部字段、路径或排障上下文。
     * 但 message 字段本身是 datasource-management 已经面向调用方准备的低敏提示，保留下来可以让前端弹窗
     * 展示“actorTenantId 不能为空”“数据源不存在”“maxTables 不能大于 200”这类具体原因。</p>
     */
    private static final Pattern REMOTE_MESSAGE_PATTERN = Pattern.compile("\"message\"\\s*:\\s*\"([^\"]*)\"");

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
        } catch (RestClientResponseException exception) {
            /*
             * 远端返回 4xx/5xx 时，RestClient 会直接抛 RestClientResponseException。
             * 旧逻辑把所有这类异常折叠成“元数据发现暂不可用”，前端只能看到 500 或 502。
             * 现在按远端 HTTP 语义做低敏映射：参数/权限/不存在类错误返回给用户修正，真正 5xx 才视为外部依赖失败。
             */
            log.warn("调用 datasource-management metadata discovery 返回错误: datasourceId={}, traceId={}, httpStatus={}",
                    datasourceId, traceId(actorContext), exception.getStatusCode().value());
            throw new PlatformBusinessException(errorCodeForRemoteStatus(exception.getStatusCode().value()),
                    "datasource-management 元数据发现未通过，datasourceId=" + datasourceId
                            + remoteMessageSuffix(exception.getResponseBodyAsString()));
        } catch (RestClientException exception) {
            log.warn("调用 datasource-management metadata discovery 失败: datasourceId={}, traceId={}, exceptionType={}",
                    datasourceId, traceId(actorContext), exception.getClass().getSimpleName());
            throw new PlatformBusinessException(PlatformErrorCode.EXTERNAL_DEPENDENCY_FAILED,
                    "datasource-management 元数据发现服务暂不可用，已按 fail-closed 终止，datasourceId=" + datasourceId);
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

    private PlatformErrorCode errorCodeForRemoteStatus(int httpStatus) {
        return switch (httpStatus) {
            case 400 -> PlatformErrorCode.VALIDATION_ERROR;
            case 401 -> PlatformErrorCode.UNAUTHORIZED;
            case 403 -> PlatformErrorCode.FORBIDDEN;
            case 404 -> PlatformErrorCode.NOT_FOUND;
            case 409 -> PlatformErrorCode.BUSINESS_STATE_CONFLICT;
            case 504 -> PlatformErrorCode.DEPENDENCY_TIMEOUT;
            default -> PlatformErrorCode.EXTERNAL_DEPENDENCY_FAILED;
        };
    }

    private String remoteMessageSuffix(String responseBody) {
        String remoteMessage = extractRemoteMessage(responseBody);
        return remoteMessage == null ? "" : "，下游提示：" + remoteMessage;
    }

    private String extractRemoteMessage(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }
        Matcher matcher = REMOTE_MESSAGE_PATTERN.matcher(responseBody);
        if (!matcher.find()) {
            return null;
        }
        String message = matcher.group(1)
                .replace("\\\"", "\"")
                .replace("\\n", " ")
                .replace("\\r", " ")
                .replace("\\t", " ")
                .trim();
        return message.length() <= 300 ? message : message.substring(0, 300) + "...";
    }

    private String traceId(SyncActorContext actorContext) {
        if (actorContext == null || actorContext.traceId() == null || actorContext.traceId().isBlank()) {
            return "data-sync-metadata-discovery";
        }
        return actorContext.traceId();
    }
}
