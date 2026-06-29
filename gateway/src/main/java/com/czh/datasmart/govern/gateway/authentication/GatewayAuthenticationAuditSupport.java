/**
 * @Author : Cui
 * @Date: 2026/06/29 23:59
 * @Description DataSmart Govern Backend - GatewayAuthenticationAuditSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.authentication;

import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.gateway.controller.dto.GatewayAuthenticationPrincipalView;
import com.czh.datasmart.govern.gateway.monitoring.GatewayAuthenticationMetrics;
import lombok.RequiredArgsConstructor;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 网关认证审计辅助组件。
 *
 * <p>它位于 OIDC/JWT 身份解析之后、业务授权之前，负责把“认证结果”转换为统一、低敏、可扩展的审计事件。
 * 这样做的好处是过滤器只关心请求是否继续向下游流转，审计组件只关心如何记录身份解析证据，二者职责不互相挤压。</p>
 */
@Component
@RequiredArgsConstructor
public class GatewayAuthenticationAuditSupport {

    /**
     * 认证审计载荷策略。
     *
     * <p>该策略名会进入每条事件，方便日志、SIEM、Kafka 消费方知道：这里只能保存低敏认证摘要，
     * 不能因为后续排障需要就把 token、完整 claim 或用户联系方式补进去。</p>
     */
    public static final String PAYLOAD_POLICY =
            "LOW_SENSITIVE_AUTH_AUDIT_NO_TOKEN_NO_REFRESH_TOKEN_NO_FULL_CLAIMS_NO_SECRET_NO_PII";

    private final List<GatewayAuthenticationAuditSink> auditSinks;
    private final GatewayAuthenticationMetrics authenticationMetrics;

    /**
     * 记录一次认证成功解析事件。
     *
     * <p>成功解析并不表示已经授权访问业务资源，只表示 gateway 已经从 OIDC/JWT 或受信上下文中获得了可用于
     * permission-admin 判定的租户、actor、角色和主体类型。</p>
     */
    public void recordResolved(ServerHttpRequest request, GatewayAuthenticationPrincipalView principal) {
        record(request, principal, "RESOLVED");
    }

    /**
     * 记录一次认证拒绝或不可用事件。
     *
     * <p>典型场景包括：JWT 已通过签名校验但缺少 DataSmart 必需 claim、actorType 非法、
     * Spring Security principal 不是 JwtAuthenticationToken 等。这里记录的是认证层原因，
     * 不替代 permission-admin 后续的授权拒绝审计。</p>
     */
    public void recordRejected(ServerHttpRequest request, GatewayAuthenticationPrincipalView principal, String reasonCode) {
        GatewayAuthenticationPrincipalView enrichedPrincipal = enrichIssue(principal, reasonCode);
        record(request, enrichedPrincipal, "REJECTED");
    }

    private void record(ServerHttpRequest request,
                        GatewayAuthenticationPrincipalView principal,
                        String outcome) {
        GatewayAuthenticationAuditEvent event = new GatewayAuthenticationAuditEvent(
                UUID.randomUUID().toString(),
                traceId(request, principal),
                outcome,
                principal.authenticationType(),
                principal.tenantId(),
                principal.actorId(),
                principal.actorRole(),
                principal.actorType(),
                principal.workspaceId(),
                safeRequestPath(request),
                request == null ? null : request.getHeaders().getFirst(PlatformContextHeaders.REQUEST_SOURCE),
                safeIssueCodes(principal.issueCodes()),
                PAYLOAD_POLICY,
                OffsetDateTime.now()
        );

        authenticationMetrics.recordAuthenticationOutcome(
                event.outcome(),
                event.authenticationType(),
                event.actorType(),
                primaryIssueCode(event.issueCodes())
        );
        auditSinks.forEach(sink -> sink.emit(event));
    }

    /**
     * 给拒绝事件追加网关本地原因。
     *
     * <p>principal 自身通常已经包含 OIDC_JWT_CONTEXT_INCOMPLETE 等解析 issue。
     * 这里再追加一层 gateway filter 的拒绝原因，方便排障时区分“解析器发现问题”和“过滤器按 fail-closed 拦截”。</p>
     */
    private GatewayAuthenticationPrincipalView enrichIssue(GatewayAuthenticationPrincipalView principal, String reasonCode) {
        List<String> existingIssues = safeIssueCodes(principal.issueCodes());
        if (reasonCode == null || reasonCode.isBlank() || existingIssues.contains(reasonCode)) {
            return principal;
        }
        List<String> issues = new java.util.ArrayList<>(existingIssues);
        issues.add(reasonCode);
        return new GatewayAuthenticationPrincipalView(
                principal.authenticated(),
                principal.authenticationType(),
                principal.tenantId(),
                principal.actorId(),
                principal.actorRole(),
                principal.actorType(),
                principal.workspaceId(),
                principal.dataScopeLevel(),
                principal.authorizedProjectIds(),
                principal.traceId(),
                List.copyOf(issues),
                principal.payloadPolicy()
        );
    }

    /**
     * 归一化认证 issueCode 列表。
     *
     * <p>当前 OIDC 解析器通常会返回非空 issueCodes，但 gateway 认证中心未来还可能接入企业反向代理、
     * 服务网格、mTLS 或其他 IdP 适配器。为了避免某个新适配器忘记初始化列表导致审计链路 NPE，
     * 这里把空列表语义统一收口为 `List.of()`。这类防御式处理放在审计辅助类中，比散落在过滤器里更容易维护。</p>
     */
    private List<String> safeIssueCodes(List<String> issueCodes) {
        if (issueCodes == null || issueCodes.isEmpty()) {
            return List.of();
        }
        return issueCodes;
    }

    private String traceId(ServerHttpRequest request, GatewayAuthenticationPrincipalView principal) {
        if (principal.traceId() != null && !principal.traceId().isBlank()) {
            return principal.traceId();
        }
        return request == null ? null : request.getHeaders().getFirst(PlatformContextHeaders.TRACE_ID);
    }

    private String safeRequestPath(ServerHttpRequest request) {
        if (request == null) {
            return null;
        }
        return request.getPath().value();
    }

    private String primaryIssueCode(List<String> issueCodes) {
        if (issueCodes == null || issueCodes.isEmpty()) {
            return "NONE";
        }
        return issueCodes.stream()
                .filter(issue -> issue != null && !issue.isBlank())
                .filter(issue -> !"OIDC_JWT_RESOLVED".equalsIgnoreCase(issue))
                .findFirst()
                .orElse("NONE");
    }
}
