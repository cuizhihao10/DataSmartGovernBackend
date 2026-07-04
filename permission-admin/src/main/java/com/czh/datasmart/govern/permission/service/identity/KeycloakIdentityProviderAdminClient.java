/**
 * @Author : Cui
 * @Date: 2026/07/05 03:26
 * @Description DataSmartGovernBackend - KeycloakIdentityProviderAdminClient.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service.identity;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.permission.config.IdentityProvisioningProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Keycloak Admin API 适配器。
 *
 * <p>该类只负责“如何调用 Keycloak”，不负责 DataSmart 的租户、角色、actorId、审计和影子表规则。
 * 分层的好处是：后续企业 IdP 接入时，可以新增另一个 IdentityProviderAdminClient 实现，而不需要重写
 * IdentityProvisioningServiceImpl。
 *
 * <p>安全约束：
 * 1. access_token 只保存在方法局部变量中；
 * 2. password/clientSecret/adminPassword 不写日志、不进审计、不进响应；
 * 3. Keycloak 错误响应体不透传给上层，避免把上游细节、realm、client 或策略配置泄露给前端。
 */
@Slf4j
@Component
public class KeycloakIdentityProviderAdminClient implements IdentityProviderAdminClient {

    private static final String EVIDENCE_TOKEN_OBTAINED = "KEYCLOAK_ADMIN_TOKEN_OBTAINED";
    private static final String EVIDENCE_USER_CREATED = "KEYCLOAK_USER_CREATED";
    private static final String EVIDENCE_USER_DISABLED = "KEYCLOAK_USER_DISABLED";
    private static final String EVIDENCE_PASSWORD_RESET = "KEYCLOAK_PASSWORD_RESET";
    private static final String EVIDENCE_REALM_ROLE_ASSIGNED = "KEYCLOAK_REALM_ROLE_ASSIGNED";
    private static final String EVIDENCE_ROLE_ASSIGNMENT_SKIPPED = "KEYCLOAK_REALM_ROLE_ASSIGNMENT_SKIPPED";

    private final IdentityProvisioningProperties properties;
    private final RestClient restClient;

    /**
     * 构造 Keycloak 管理客户端。
     *
     * <p>这里直接创建 RestClient，是为了把超时与 identity-provisioning 配置绑定在一起；后续如果需要统一
     * HTTP 连接池、重试、熔断或 mTLS，可以把 RestClient.Builder 提升到 platform-common 或配置类中。
     */
    public KeycloakIdentityProviderAdminClient(IdentityProvisioningProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) properties.getKeycloak().getConnectTimeout().toMillis());
        requestFactory.setReadTimeout((int) properties.getKeycloak().getReadTimeout().toMillis());
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    /**
     * 创建 Keycloak 用户。
     *
     * <p>流程按 Keycloak Admin API 推荐方式执行：
     * 1. 获取 admin token；
     * 2. POST /admin/realms/{realm}/users 创建用户；
     * 3. 从 Location 或 username 精确查询中拿到 providerUserId；
     * 4. 可选绑定 realm role，让 gateway 后续能从 JWT 中识别 DataSmart 角色。
     */
    @Override
    public IdentityProviderOperationResult createUser(IdentityProviderUserCreateCommand command) {
        try {
            String token = adminAccessToken();
            List<String> evidenceCodes = new ArrayList<>(List.of(EVIDENCE_TOKEN_OBTAINED));
            URI location = restClient.post()
                    .uri(adminUsersUri(), properties.getKeycloak().getTargetRealm())
                    .header(HttpHeaders.AUTHORIZATION, bearer(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(createUserPayload(command))
                    .retrieve()
                    .toBodilessEntity()
                    .getHeaders()
                    .getLocation();

            String providerUserId = providerUserIdFromLocation(location);
            if (providerUserId == null || providerUserId.isBlank()) {
                providerUserId = findUserIdByUsername(token, command.username());
            }
            evidenceCodes.add(EVIDENCE_USER_CREATED);

            if (properties.getKeycloak().isAssignRealmRole()) {
                assignRealmRole(token, providerUserId, command.realmRoleName());
                evidenceCodes.add(EVIDENCE_REALM_ROLE_ASSIGNED);
            } else {
                evidenceCodes.add(EVIDENCE_ROLE_ASSIGNMENT_SKIPPED);
            }
            return new IdentityProviderOperationResult(providerUserId, "CREATE_USER", "Keycloak 用户已创建", evidenceCodes);
        } catch (RestClientResponseException exception) {
            throw translateKeycloakException(exception, "Keycloak 创建用户失败");
        }
    }

    /**
     * 禁用 Keycloak 用户。
     *
     * <p>Keycloak 使用 enabled=false 表达禁用。DataSmart 本地影子表的 DISABLED 状态会由业务服务在外部调用成功后更新。
     */
    @Override
    public IdentityProviderOperationResult disableUser(String providerUserId, String reason) {
        try {
            String token = adminAccessToken();
            restClient.put()
                    .uri(adminUserUri(providerUserId))
                    .header(HttpHeaders.AUTHORIZATION, bearer(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("enabled", false))
                    .retrieve()
                    .toBodilessEntity();
            return new IdentityProviderOperationResult(providerUserId, "DISABLE_USER", "Keycloak 用户已禁用",
                    List.of(EVIDENCE_TOKEN_OBTAINED, EVIDENCE_USER_DISABLED));
        } catch (RestClientResponseException exception) {
            throw translateKeycloakException(exception, "Keycloak 禁用用户失败");
        }
    }

    /**
     * 重置 Keycloak 用户密码。
     *
     * <p>密码只作为 reset-password payload 发送给 Keycloak，不会被保存在任何返回对象或日志中。
     */
    @Override
    public IdentityProviderOperationResult resetPassword(String providerUserId, String password, boolean temporaryPassword) {
        try {
            String token = adminAccessToken();
            Map<String, Object> credential = new LinkedHashMap<>();
            credential.put("type", "password");
            credential.put("value", password);
            credential.put("temporary", temporaryPassword);

            restClient.put()
                    .uri(adminUserResetPasswordUri(providerUserId))
                    .header(HttpHeaders.AUTHORIZATION, bearer(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(credential)
                    .retrieve()
                    .toBodilessEntity();
            return new IdentityProviderOperationResult(providerUserId, "RESET_PASSWORD", "Keycloak 密码已重置",
                    List.of(EVIDENCE_TOKEN_OBTAINED, EVIDENCE_PASSWORD_RESET));
        } catch (RestClientResponseException exception) {
            throw translateKeycloakException(exception, "Keycloak 重置密码失败");
        }
    }

    /**
     * 获取 Keycloak admin access token。
     *
     * <p>adminClientSecret 不为空时使用 client_credentials；为空时使用本地开发更方便的 password grant。
     * 生产环境推荐前者，并给 client 配置最小化 realm-management 权限。
     */
    @SuppressWarnings("unchecked")
    private String adminAccessToken() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", properties.getKeycloak().getAdminClientId());
        if (hasText(properties.getKeycloak().getAdminClientSecret())) {
            form.add("grant_type", "client_credentials");
            form.add("client_secret", properties.getKeycloak().getAdminClientSecret());
        } else {
            form.add("grant_type", "password");
            form.add("username", properties.getKeycloak().getAdminUsername());
            form.add("password", properties.getKeycloak().getAdminPassword());
        }

        try {
            Map<String, Object> response = restClient.post()
                    .uri(baseUrl() + "/realms/{realm}/protocol/openid-connect/token", properties.getKeycloak().getAdminRealm())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Map.class);
            Object token = response == null ? null : response.get("access_token");
            if (token == null || token.toString().isBlank()) {
                throw new PlatformBusinessException(PlatformErrorCode.EXTERNAL_DEPENDENCY_FAILED, "Keycloak 未返回 admin token");
            }
            return token.toString();
        } catch (RestClientResponseException exception) {
            throw translateKeycloakException(exception, "Keycloak admin token 获取失败");
        }
    }

    /**
     * 构造创建用户 payload。
     *
     * <p>attributes 中写入 DataSmart 上下文，是为了让后续 Keycloak Protocol Mapper 可以把 tenantId、actorId、
     * actorRole、actorType、workspaceId 映射到 JWT claim，再由 gateway 写成 X-DataSmart-* Header。
     */
    private Map<String, Object> createUserPayload(IdentityProviderUserCreateCommand command) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("username", command.username());
        payload.put("enabled", command.enabled());
        payload.put("emailVerified", command.emailVerified());
        putIfHasText(payload, "email", command.email());
        putIfHasText(payload, "firstName", command.firstName());
        putIfHasText(payload, "lastName", command.lastName());
        payload.put("attributes", Map.of(
                "datasmart_tenant_id", List.of(String.valueOf(command.tenantId())),
                "datasmart_actor_id", List.of(String.valueOf(command.actorId())),
                "datasmart_actor_role", List.of(command.actorRole()),
                "datasmart_actor_type", List.of(command.actorType()),
                "datasmart_workspace_id", List.of(command.workspaceId() == null ? "" : command.workspaceId())
        ));

        if (hasText(command.password())) {
            Map<String, Object> credential = new LinkedHashMap<>();
            credential.put("type", "password");
            credential.put("value", command.password());
            credential.put("temporary", command.temporaryPassword());
            payload.put("credentials", List.of(credential));
        }
        return payload;
    }

    /**
     * 给用户绑定 realm role。
     *
     * <p>如果目标 role 不存在，Keycloak 会返回 404。这里选择 fail-closed，因为账号创建成功但角色未绑定会导致登录后
     * gateway 无法正确识别权限，继续静默成功反而更危险。
     */
    @SuppressWarnings("unchecked")
    private void assignRealmRole(String token, String providerUserId, String realmRoleName) {
        if (!hasText(realmRoleName)) {
            return;
        }
        Map<String, Object> roleRepresentation = restClient.get()
                .uri(baseUrl() + "/admin/realms/{realm}/roles/{role}",
                        properties.getKeycloak().getTargetRealm(), realmRoleName)
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .retrieve()
                .body(Map.class);
        restClient.post()
                .uri(baseUrl() + "/admin/realms/{realm}/users/{userId}/role-mappings/realm",
                        properties.getKeycloak().getTargetRealm(), providerUserId)
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(List.of(roleRepresentation))
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * 当创建用户响应没有 Location 时，按 username 精确查询补齐 providerUserId。
     */
    @SuppressWarnings("unchecked")
    private String findUserIdByUsername(String token, String username) {
        List<Map<String, Object>> users = restClient.get()
                .uri(baseUrl() + "/admin/realms/{realm}/users?username={username}&exact=true",
                        properties.getKeycloak().getTargetRealm(), username)
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .retrieve()
                .body(List.class);
        if (users == null || users.isEmpty()) {
            throw new PlatformBusinessException(PlatformErrorCode.EXTERNAL_DEPENDENCY_FAILED, "Keycloak 用户已创建但无法回查 providerUserId");
        }
        Object id = users.getFirst().get("id");
        if (id == null || id.toString().isBlank()) {
            throw new PlatformBusinessException(PlatformErrorCode.EXTERNAL_DEPENDENCY_FAILED, "Keycloak 用户回查结果缺少 id");
        }
        return id.toString();
    }

    /**
     * 将 Keycloak HTTP 错误转换为平台错误。
     */
    private PlatformBusinessException translateKeycloakException(RestClientResponseException exception, String safeMessage) {
        int status = exception.getStatusCode().value();
        if (status == 409) {
            return new PlatformBusinessException(PlatformErrorCode.DUPLICATE_OPERATION, safeMessage + "：外部身份已存在或发生幂等冲突");
        }
        if (status == 401 || status == 403) {
            return new PlatformBusinessException(PlatformErrorCode.EXTERNAL_DEPENDENCY_FAILED, safeMessage + "：IdP 管理凭据无效或权限不足");
        }
        if (status == 404) {
            return new PlatformBusinessException(PlatformErrorCode.NOT_FOUND, safeMessage + "：外部身份资源不存在");
        }
        log.warn("Keycloak Admin API 调用失败，status={}，safeMessage={}，响应体已按安全策略隐藏", status, safeMessage);
        return new PlatformBusinessException(PlatformErrorCode.EXTERNAL_DEPENDENCY_FAILED, safeMessage + "：外部身份系统调用失败");
    }

    private String adminUsersUri() {
        return baseUrl() + "/admin/realms/{realm}/users";
    }

    private String adminUserUri(String providerUserId) {
        return baseUrl() + "/admin/realms/" + properties.getKeycloak().getTargetRealm() + "/users/" + providerUserId;
    }

    private String adminUserResetPasswordUri(String providerUserId) {
        return adminUserUri(providerUserId) + "/reset-password";
    }

    private String providerUserIdFromLocation(URI location) {
        if (location == null) {
            return null;
        }
        String path = location.getPath();
        int lastSlash = path == null ? -1 : path.lastIndexOf('/');
        return lastSlash < 0 ? null : path.substring(lastSlash + 1);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private String baseUrl() {
        String value = properties.getKeycloak().getBaseUrl();
        if (value == null || value.isBlank()) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, "Keycloak base-url 不能为空");
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private void putIfHasText(Map<String, Object> payload, String key, String value) {
        if (hasText(value)) {
            payload.put(key, value.trim());
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
