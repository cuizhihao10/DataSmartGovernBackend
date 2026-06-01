/**
 * @Author : Cui
 * @Date: 2026/06/01 23:59
 * @Description DataSmart Govern Backend - PermissionAdminAgentAsyncToolAuthorizationClient.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.task.config.AgentAsyncToolWorkerProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;

/**
 * task-management Agent worker 调用 permission-admin evaluate 的 HTTP 客户端。
 *
 * <p>这个客户端是执行前权限复核的服务间边界。它只负责三件事：
 * 1. 把 worker 内部授权请求转换成 permission-admin evaluate JSON；
 * 2. 设置服务间调用 Header、超时和统一响应解包；
 * 3. 把通信失败转换成运行时异常，交给 pre-check 决定 fail-closed、fail-open 或 defer。</p>
 *
 * <p>为什么不直接在 pre-check 里写 RestClient：
 * pre-check 的职责是业务判断，HTTP 调用的职责是集成边界。如果把两者混在一起，后续接入 OpenFeign、
 * 服务网格、mTLS、连接池、熔断或本地缓存时，执行安全门会被基础设施细节污染。</p>
 */
@Component
@RequiredArgsConstructor
public class PermissionAdminAgentAsyncToolAuthorizationClient {

    private final AgentAsyncToolWorkerProperties properties;
    private final RestClient.Builder restClientBuilder;

    /**
     * 调用 permission-admin evaluate。
     *
     * @param request worker 整理出的授权问题
     * @return 权限中心的当前判定结果
     */
    public AgentAsyncToolPermissionAuthorizationResult evaluate(AgentAsyncToolPermissionAuthorizationRequest request) {
        try {
            PlatformApiResponse<AgentAsyncToolPermissionAuthorizationResult> response = restClientBuilder
                    .requestFactory(requestFactory())
                    .build()
                    .post()
                    .uri(properties.getPermissionAdminEvaluateUrl())
                    .header(PlatformContextHeaders.TRACE_ID, safeText(request.traceId()))
                    .header(PlatformContextHeaders.SOURCE_SERVICE, "task-management")
                    .body(new PermissionDecisionRequestPayload(
                            request.tenantId(),
                            request.serviceAccountActorId(),
                            request.serviceAccountRole(),
                            "POST",
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
                    .body(new ParameterizedTypeReference<>() {
                    });
            return unwrap(response);
        } catch (RestClientException exception) {
            throw new IllegalStateException("调用 permission-admin 授权判定接口失败: " + exception.getMessage(), exception);
        }
    }

    /**
     * 构造短超时 HTTP request factory。
     *
     * <p>授权复核位于 worker 执行前关键路径。超时后由任务退避重试，比长时间占住执行线程更安全；
     * 后续如果接入连接池或 Resilience4j 熔断器，也应在这一层替换，而不是修改 pre-check 业务代码。</p>
     */
    private SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofMillis(Math.max(1L, properties.getPermissionAdminTimeoutMs()));
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        return requestFactory;
    }

    private AgentAsyncToolPermissionAuthorizationResult unwrap(
            PlatformApiResponse<AgentAsyncToolPermissionAuthorizationResult> response) {
        if (response == null) {
            throw new IllegalStateException("permission-admin 返回空响应");
        }
        if (response.getCode() == null || response.getCode() != 0) {
            throw new IllegalStateException("permission-admin 返回失败响应，reason=" + response.getReason()
                    + ", message=" + response.getMessage());
        }
        if (response.getData() == null) {
            throw new IllegalStateException("permission-admin 授权判定成功但 data 为空");
        }
        return response.getData();
    }

    private String safeText(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * 与 permission-admin `PermissionDecisionRequest` JSON 字段对齐的本地请求体。
     *
     * <p>这里保持本地 record，是为了避免 task-management 直接 import permission-admin 的 DTO。
     * 微服务之间用 HTTP/JSON 契约协作，不用 Java 包依赖互相穿透边界。</p>
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
}
