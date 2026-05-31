/**
 * @Author : Cui
 * @Date: 2026/05/31 23:59
 * @Description DataSmart Govern Backend - HttpPermissionAdminServiceAuthorizationClient.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.authorization;

import com.czh.datasmart.govern.agent.config.AgentToolServiceAuthorizationProperties;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.List;

/**
 * 基于 HTTP 的 permission-admin 授权客户端。
 *
 * <p>该实现只负责“如何调用权限中心”，不负责解释 Agent 工具业务语义。
 * 业务语义，例如一个 data-sync.execute 工具应该映射成 SYNC_TASK + EXECUTE，
 * 由上层 {@code AgentToolServiceAuthorizationPreviewService} 负责整理。</p>
 *
 * <p>当前仍使用 RestClient 轻量直连，是因为项目还处在微服务控制面逐步落地阶段。
 * 生产化后可以替换为 Nacos LoadBalancer、OpenFeign、服务网格或带 mTLS 的内部 gateway，
 * 但建议继续保留这个接口边界，让 DAG preview 和真实 worker 不直接依赖具体通信技术。</p>
 */
@Component
@RequiredArgsConstructor
public class HttpPermissionAdminServiceAuthorizationClient implements PermissionAdminServiceAuthorizationClient {

    private final AgentToolServiceAuthorizationProperties properties;
    private final RestClient.Builder restClientBuilder;

    /**
     * 调用 permission-admin 的 evaluate 接口。
     *
     * <p>失败时抛出 {@link RestClientException}，由上层转换为 PERMISSION_ADMIN_UNAVAILABLE。
     * 这样做比在 HTTP 客户端里直接吞掉异常更清晰：客户端负责通信，业务服务负责 fail-open/fail-closed 策略。</p>
     */
    @Override
    public AgentToolServiceAuthorizationRemoteResult evaluate(AgentToolServiceAuthorizationRemoteRequest request) {
        RestClient client = restClientBuilder
                .requestFactory(requestFactory())
                .build();
        PermissionDecisionEnvelope response = client.post()
                .uri(properties.getPermissionAdminEvaluateUrl())
                .header(PlatformContextHeaders.TRACE_ID, request.traceId() == null ? "" : request.traceId())
                .header(PlatformContextHeaders.SOURCE_SERVICE, "agent-runtime")
                .body(new PermissionDecisionRequestPayload(
                        request.tenantId(),
                        request.serviceAccountActorId(),
                        request.serviceAccountRole(),
                        request.httpMethod(),
                        request.requestPath(),
                        request.resourceType(),
                        request.action(),
                        request.serviceAccountActorId(),
                        request.serviceAccountCode(),
                        request.representedActorId(),
                        request.delegationType(),
                        request.delegationReason(),
                        request.requestedPolicyVersion()
                ))
                .retrieve()
                .body(PermissionDecisionEnvelope.class);
        return unwrap(response);
    }

    /**
     * 为授权请求设置短超时。
     *
     * <p>权限预检位于执行前关键路径，不能因为权限中心慢响应而长时间占用 Agent worker。
     * 当前使用 Spring 提供的 SimpleClientHttpRequestFactory，后续若引入连接池、熔断、重试，
     * 可以在这一层替换为更完整的 HTTP Client 配置。</p>
     */
    private SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        Long configuredTimeout = properties.getTimeoutMs() == null ? 1500L : properties.getTimeoutMs();
        Duration timeout = Duration.ofMillis(Math.max(1L, configuredTimeout));
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        return requestFactory;
    }

    private AgentToolServiceAuthorizationRemoteResult unwrap(PermissionDecisionEnvelope response) {
        if (response == null) {
            throw new RestClientException("permission-admin 返回空响应");
        }
        if (response.getCode() == null || response.getCode() != 0) {
            throw new RestClientException("permission-admin 返回失败响应，reason=" + response.getReason()
                    + ", message=" + response.getMessage());
        }
        PermissionDecisionData data = response.getData();
        if (data == null) {
            throw new RestClientException("permission-admin 响应缺少 data");
        }
        return new AgentToolServiceAuthorizationRemoteResult(
                data.getAllowed(),
                data.getReason(),
                data.getRouteEffect(),
                data.getDataScopeLevel(),
                data.getAuthorizedProjectIds() == null ? List.of() : data.getAuthorizedProjectIds(),
                data.getApprovalRequired(),
                data.getPolicyVersion(),
                data.getDelegated(),
                data.getDelegationEvidence()
        );
    }

    /**
     * 与 permission-admin 的 PermissionDecisionRequest JSON 字段保持一致的本地载荷。
     *
     * <p>使用本地 payload 类而不是跨模块 import，是为了保持微服务之间只通过 API 契约耦合。</p>
     */
    private record PermissionDecisionRequestPayload(Long tenantId,
                                                    Long actorId,
                                                    String actorRole,
                                                    String httpMethod,
                                                    String requestPath,
                                                    String resourceType,
                                                    String action,
                                                    Long serviceAccountActorId,
                                                    String serviceAccountCode,
                                                    String representedActorId,
                                                    String delegationType,
                                                    String delegationReason,
                                                    String requestedPolicyVersion) {
    }

    /**
     * platform-common 统一响应体的本地解析结构。
     */
    @Data
    private static class PermissionDecisionEnvelope {
        private Integer code;
        private String reason;
        private String message;
        private PermissionDecisionData data;
        private String traceId;
    }

    /**
     * permission-admin PermissionDecisionResult 的本地解析结构。
     */
    @Data
    private static class PermissionDecisionData {
        private Boolean allowed;
        private String reason;
        private Long matchedRoutePolicyId;
        private String routeEffect;
        private String dataScopeLevel;
        private String dataScopeExpression;
        private List<Long> authorizedProjectIds;
        private Boolean approvalRequired;
        private String policyVersion;
        private Boolean delegated;
        private String delegationEvidence;
    }
}
