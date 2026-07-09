/**
 * @Author : Cui
 * @Date: 2026/06/29 23:45
 * @Description DataSmart Govern Backend - GatewayAuthenticationCenterService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.authentication;

import com.czh.datasmart.govern.common.context.PlatformActorType;
import com.czh.datasmart.govern.common.context.PlatformAuthorizedProjectHeaderSupport;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.gateway.config.GatewayAuthenticationCenterProperties;
import com.czh.datasmart.govern.gateway.config.GatewayContextProperties;
import com.czh.datasmart.govern.gateway.controller.dto.GatewayAuthenticationCapabilityView;
import com.czh.datasmart.govern.gateway.controller.dto.GatewayAuthenticationPrincipalView;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 网关认证中心服务。
 *
 * <p>该服务是生产级认证中心接入的“业务解释层”。JWT 的签名、过期时间、issuer、audience 等安全校验由
 * Spring Security OAuth2 Resource Server 完成；本服务只在 token 已验证后，把 IdP claim 映射成 DataSmart
 * 平台统一身份上下文。</p>
 *
 * <p>为什么不让业务服务自己解析 JWT？</p>
 * <p>1. 每个微服务重复解析 token 会导致配置漂移，某个服务漏配 issuer/audience 就可能形成安全洞；</p>
 * <p>2. 业务服务真正需要的是 tenantId、actorId、actorRole、workspaceId，而不是 IdP 私有 claim 结构；</p>
 * <p>3. gateway 集中映射后，permission-admin、data-sync、agent-runtime 等模块可以稳定消费 X-DataSmart-*，
 * 后续切换 Keycloak、企业 IdP 或云 IAM 时不需要重写业务层。</p>
 */
@Service
@RequiredArgsConstructor
public class GatewayAuthenticationCenterService {

    /**
     * 认证中心可见性接口固定载荷策略。
     *
     * <p>响应中只允许出现低敏身份事实、认证模式和 issueCode；不返回 access token、refresh token、完整 JWT claim、
     * 密钥、密码、用户手机号/邮箱、权限策略正文、内部服务地址、SQL、prompt 或模型输出。</p>
     */
    public static final String PAYLOAD_POLICY =
            "LOW_SENSITIVE_OIDC_AUTH_VIEW_NO_TOKEN_NO_REFRESH_TOKEN_NO_FULL_CLAIMS_NO_POLICY_BODY";

    private static final String AUTH_TYPE_OIDC_JWT = "OIDC_JWT";
    private static final String AUTH_TYPE_TRUSTED_CONTEXT = "TRUSTED_PLATFORM_CONTEXT";
    private static final String AUTH_TYPE_ANONYMOUS = "ANONYMOUS";

    private final GatewayAuthenticationCenterProperties authenticationProperties;
    private final GatewayContextProperties contextProperties;

    /**
     * 查询当前认证中心能力。
     *
     * <p>该能力说明可以公开给前端或运维，不包含任何 token 或 secret。
     * 它的价值是让调用方明确：当前平台采用 OIDC/JWT Resource Server 作为认证方案，permission-admin 作为授权方案。</p>
     */
    public GatewayAuthenticationCapabilityView capabilities() {
        GatewayAuthenticationCenterProperties.OidcJwtProperties oidc = authenticationProperties.getOidc();
        return new GatewayAuthenticationCapabilityView(
                authenticationProperties.isEnabled(),
                authenticationProperties.getProviderMode(),
                authenticationProperties.getIssuer(),
                oidc.isEnabled(),
                oidc.isAudienceValidationEnabled(),
                List.copyOf(oidc.getRequiredAudiences()),
                List.of(AUTH_TYPE_OIDC_JWT, AUTH_TYPE_TRUSTED_CONTEXT, AUTH_TYPE_ANONYMOUS),
                List.of(PlatformActorType.USER.name(),
                        PlatformActorType.SERVICE_ACCOUNT.name(),
                        PlatformActorType.AGENT.name(),
                        PlatformActorType.SYSTEM_SCHEDULER.name()),
                "生产推荐使用 Keycloak/OIDC/企业 IdP 签发 JWT，gateway 验证 JWT 后写入 X-DataSmart-*，permission-admin 负责 RBAC/数据范围授权",
                PAYLOAD_POLICY
        );
    }

    /**
     * 从 Spring Security Authentication 解析当前请求身份。
     *
     * <p>该方法同时服务 `/auth/session` 查询和 gateway 全局过滤器。调用前应确保 Authentication 来自已经通过
     * Resource Server 验证的请求；本方法不会自行验证 JWT 签名，也不会信任未经安全链路确认的 token。</p>
     */
    public GatewayAuthenticationPrincipalView currentPrincipal(Authentication authentication, HttpHeaders headers) {
        if (!authenticationProperties.isEnabled()) {
            return anonymous(headers, "AUTH_CENTER_DISABLED");
        }
        GatewayAuthenticationPrincipalView jwtPrincipal = principalFromJwt(authentication, headers);
        if (jwtPrincipal.authenticated() || jwtPrincipal.issueCodes().contains("OIDC_JWT_CONTEXT_INCOMPLETE")) {
            return jwtPrincipal;
        }
        if (contextProperties.isTrustIncomingPlatformContext()) {
            GatewayAuthenticationPrincipalView trusted = trustedPlatformContext(headers);
            if (trusted.authenticated()) {
                return trusted;
            }
        }
        return anonymous(headers, "NO_AUTHENTICATED_PRINCIPAL");
    }

    /**
     * 把已认证身份写入下游服务可消费的统一平台 Header。
     *
     * <p>写入时使用 set 覆盖而不是 add 追加，避免同名 Header 出现多个值。
     * 多值身份 Header 在安全领域非常危险，因为不同框架可能读取第一个值或最后一个值，造成审计和授权不一致。</p>
     */
    public void writePlatformIdentityHeaders(HttpHeaders headers, GatewayAuthenticationPrincipalView principal) {
        headers.set(PlatformContextHeaders.TENANT_ID, String.valueOf(principal.tenantId()));
        headers.set(PlatformContextHeaders.ACTOR_ID, String.valueOf(principal.actorId()));
        headers.set(PlatformContextHeaders.ACTOR_ROLE, principal.actorRole());
        headers.set(PlatformContextHeaders.ACTOR_TYPE, principal.actorType());
        headers.set(PlatformContextHeaders.WORKSPACE_ID, principal.workspaceId());
    }

    /**
     * 判断身份是否缺少进入业务系统所需的关键 claim。
     *
     * <p>JWT 可以合法，但如果没有 tenantId、actorId、actorRole，就不能安全进入多租户业务路由。
     * 这类问题通常是 IdP client mapper 配置错误，而不是用户没有某个业务权限。</p>
     */
    public boolean missingRequiredBusinessClaims(GatewayAuthenticationPrincipalView principal) {
        return principal.issueCodes().contains("OIDC_JWT_CONTEXT_INCOMPLETE");
    }

    private GatewayAuthenticationPrincipalView principalFromJwt(Authentication authentication, HttpHeaders headers) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return anonymous(headers, "SPRING_SECURITY_PRINCIPAL_MISSING");
        }
        Jwt jwt = resolveJwt(authentication);
        if (jwt == null) {
            return anonymous(headers, "SPRING_SECURITY_PRINCIPAL_NOT_JWT");
        }

        GatewayAuthenticationCenterProperties.OidcJwtProperties oidc = authenticationProperties.getOidc();
        Long tenantId = parseLongClaim(jwt, oidc.getTenantIdClaim());
        Long actorId = parseLongClaim(jwt, oidc.getActorIdClaim());
        String actorRole = resolveActorRole(jwt, authentication.getAuthorities(), oidc);
        String actorType = normalizeActorType(textClaim(jwt, oidc.getActorTypeClaim()), oidc.getDefaultActorType());
        String workspaceId = firstText(textClaim(jwt, oidc.getWorkspaceIdClaim()), oidc.getDefaultWorkspaceId());
        List<Long> tokenProjectIds = parseLongListClaim(jwt, oidc.getProjectIdsClaim());

        List<String> issueCodes = new ArrayList<>();
        issueCodes.add("OIDC_JWT_RESOLVED");
        if (!tokenProjectIds.isEmpty()) {
            /*
             * 这里记录的是“认证中心声明过的项目候选集合”，不是最终数据范围授权。
             * 真正进入业务路由前，GatewayAuthorizationFilter 仍会调用 permission-admin，
             * 使用数据库里的项目成员关系和资源级数据范围策略重新计算 authorizedProjectIds。
             * 这样可以让 /auth/session 给前端提供项目切换提示，同时避免旧 token 中的项目集合越权生效。
             */
            issueCodes.add("OIDC_JWT_PROJECT_IDS_RESOLVED");
        }
        if (tenantId == null || actorId == null || actorRole == null) {
            issueCodes.add("OIDC_JWT_CONTEXT_INCOMPLETE");
        }
        if (actorType == null) {
            issueCodes.add("OIDC_JWT_ACTOR_TYPE_INVALID");
            actorType = PlatformActorType.USER.name();
        }

        return principal(
                !issueCodes.contains("OIDC_JWT_CONTEXT_INCOMPLETE"),
                AUTH_TYPE_OIDC_JWT,
                tenantId,
                actorId,
                actorRole,
                actorType,
                workspaceId,
                headers.getFirst(PlatformContextHeaders.DATA_SCOPE_LEVEL),
                tokenProjectIds,
                traceId(headers),
                issueCodes
        );
    }

    private Jwt resolveJwt(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwtAuthenticationToken) {
            return jwtAuthenticationToken.getToken();
        }
        Object principal = authentication.getPrincipal();
        return principal instanceof Jwt jwt ? jwt : null;
    }

    private String resolveActorRole(Jwt jwt,
                                    Collection<? extends GrantedAuthority> authorities,
                                    GatewayAuthenticationCenterProperties.OidcJwtProperties oidc) {
        String directRole = normalizeRole(textClaim(jwt, oidc.getActorRoleClaim()), oidc);
        if (directRole != null && oidc.getAllowedRoles().contains(directRole)) {
            return directRole;
        }

        for (String role : listClaim(jwt, oidc.getRolesClaim())) {
            String normalized = normalizeRole(role, oidc);
            if (normalized != null && oidc.getAllowedRoles().contains(normalized)) {
                return normalized;
            }
        }

        for (String role : keycloakRealmRoles(jwt, oidc)) {
            String normalized = normalizeRole(role, oidc);
            if (normalized != null && oidc.getAllowedRoles().contains(normalized)) {
                return normalized;
            }
        }

        if (authorities != null) {
            for (GrantedAuthority authority : authorities) {
                String normalized = normalizeRole(authority.getAuthority(), oidc);
                if (normalized != null && oidc.getAllowedRoles().contains(normalized)) {
                    return normalized;
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<String> keycloakRealmRoles(Jwt jwt, GatewayAuthenticationCenterProperties.OidcJwtProperties oidc) {
        Object realmAccess = jwt.getClaim(oidc.getKeycloakRealmAccessClaim());
        if (!(realmAccess instanceof Map<?, ?> map)) {
            return List.of();
        }
        Object roles = map.get(oidc.getKeycloakRolesField());
        if (!(roles instanceof Collection<?> collection)) {
            return List.of();
        }
        return collection.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();
    }

    private List<String> listClaim(Jwt jwt, String claimName) {
        Object value = jwt.getClaim(claimName);
        if (value instanceof Collection<?> collection) {
            return collection.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .toList();
        }
        if (value instanceof String text && !text.isBlank()) {
            return List.of(text);
        }
        return List.of();
    }

    /**
     * 解析 JWT 中的项目集合 claim。
     *
     * <p>不同 IdP 对“多值用户属性”的输出格式并不完全一致：
     * 1. Keycloak 在开启 multivalued mapper 后通常会输出 JSON 数组；
     * 2. 某些企业 IdP 或历史 mapper 可能输出 `101,102` 这种逗号分隔字符串；
     * 3. 本地调试或脚本烟测也可能只输出单个数字或字符串。
     *
     * <p>gateway 在这里做宽松解析，但只保留正整数并去重；坏片段会被忽略而不是抛出异常。
     * 这样既能兼容不同认证中心，又不会因为一个异常项目片段导致整个登录态解析失败。
     * 需要特别注意的是，本方法解析出的项目集合只用于认证身份视图和低敏诊断，
     * 最终业务请求仍以后续 permission-admin 判定结果为准。</p>
     */
    private List<Long> parseLongListClaim(Jwt jwt, String claimName) {
        Object value = jwt.getClaim(claimName);
        List<Long> projectIds = new ArrayList<>();
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                appendProjectId(projectIds, item);
            }
            return List.copyOf(projectIds);
        }
        appendProjectId(projectIds, value);
        return List.copyOf(projectIds);
    }

    private void appendProjectId(List<Long> projectIds, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof Number number) {
            appendDistinctPositive(projectIds, number.longValue());
            return;
        }
        String text = value.toString();
        if (text == null || text.isBlank()) {
            return;
        }
        for (String segment : text.split(",")) {
            Long parsed = parsePositiveLong(segment);
            if (parsed != null) {
                appendDistinctPositive(projectIds, parsed);
            }
        }
    }

    private void appendDistinctPositive(List<Long> projectIds, long value) {
        if (value > 0 && !projectIds.contains(value)) {
            projectIds.add(value);
        }
    }

    private GatewayAuthenticationPrincipalView trustedPlatformContext(HttpHeaders headers) {
        Long tenantId = parsePositiveLong(headers.getFirst(PlatformContextHeaders.TENANT_ID));
        Long actorId = parsePositiveLong(headers.getFirst(PlatformContextHeaders.ACTOR_ID));
        String actorRole = normalizeCode(headers.getFirst(PlatformContextHeaders.ACTOR_ROLE));
        String actorType = normalizeActorType(headers.getFirst(PlatformContextHeaders.ACTOR_TYPE), PlatformActorType.USER.name());
        String workspaceId = firstText(headers.getFirst(PlatformContextHeaders.WORKSPACE_ID), "default");
        if (tenantId == null || actorId == null || actorRole == null) {
            return anonymous(headers, "TRUSTED_CONTEXT_INCOMPLETE");
        }
        return principal(true,
                AUTH_TYPE_TRUSTED_CONTEXT,
                tenantId,
                actorId,
                actorRole,
                actorType,
                workspaceId,
                headers.getFirst(PlatformContextHeaders.DATA_SCOPE_LEVEL),
                PlatformAuthorizedProjectHeaderSupport.parse(headers.getFirst(PlatformContextHeaders.AUTHORIZED_PROJECT_IDS)),
                traceId(headers),
                List.of("TRUSTED_CONTEXT_RESOLVED"));
    }

    private GatewayAuthenticationPrincipalView anonymous(HttpHeaders headers, String issueCode) {
        return principal(false, AUTH_TYPE_ANONYMOUS, null, null, null, null, null,
                null, List.of(), traceId(headers), List.of(issueCode));
    }

    private GatewayAuthenticationPrincipalView principal(boolean authenticated,
                                                         String authenticationType,
                                                         Long tenantId,
                                                         Long actorId,
                                                         String actorRole,
                                                         String actorType,
                                                         String workspaceId,
                                                         String dataScopeLevel,
                                                         List<Long> authorizedProjectIds,
                                                         String traceId,
                                                         List<String> issueCodes) {
        return new GatewayAuthenticationPrincipalView(
                authenticated,
                authenticationType,
                tenantId,
                actorId,
                actorRole,
                actorType,
                workspaceId,
                dataScopeLevel,
                authorizedProjectIds == null ? List.of() : List.copyOf(authorizedProjectIds),
                traceId,
                issueCodes == null ? List.of() : List.copyOf(issueCodes),
                PAYLOAD_POLICY
        );
    }

    private Long parseLongClaim(Jwt jwt, String claimName) {
        Object value = jwt.getClaim(claimName);
        if (value instanceof Number number) {
            long parsed = number.longValue();
            return parsed > 0 ? parsed : null;
        }
        if (value instanceof String text) {
            return parsePositiveLong(text);
        }
        return null;
    }

    private String textClaim(Jwt jwt, String claimName) {
        Object value = jwt.getClaim(claimName);
        return value == null ? null : value.toString();
    }

    private Long parsePositiveLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String normalizeRole(String role, GatewayAuthenticationCenterProperties.OidcJwtProperties oidc) {
        String normalized = normalizeCode(role);
        if (normalized == null) {
            return null;
        }
        for (String prefix : oidc.getRolePrefixesToStrip()) {
            String normalizedPrefix = normalizeCode(prefix);
            if (normalizedPrefix != null && normalized.startsWith(normalizedPrefix)) {
                normalized = normalized.substring(normalizedPrefix.length());
            }
        }
        return normalized;
    }

    private String normalizeActorType(String actorType, String fallback) {
        String normalized = normalizeCode(firstText(actorType, fallback));
        if (normalized == null) {
            return null;
        }
        try {
            PlatformActorType.valueOf(normalized);
            return normalized;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String normalizeCode(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim()
                .replace('-', '_')
                .replace(' ', '_')
                .toUpperCase(Locale.ROOT);
    }

    private String firstText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String traceId(HttpHeaders headers) {
        return headers == null ? null : headers.getFirst(PlatformContextHeaders.TRACE_ID);
    }
}
