/**
 * @Author : Cui
 * @Date: 2026/06/29 23:20
 * @Description DataSmart Govern Backend - GatewayAuthenticationCenterProperties.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 网关认证中心配置。
 *
 * <p>本配置类描述的是生产级 OIDC/JWT 认证中心接入策略。
 * 认证中心不在 gateway 内部“自造账号密码体系”，而是对接 Keycloak、企业统一 IdP、云厂商 IAM 或兼容
 * OpenID Connect 的身份提供方。gateway 只负责两件事：让 Spring Security 验证 JWT，再把可信 claim
 * 映射为 DataSmart 平台统一身份上下文。</p>
 *
 * <p>商业化演进原则：</p>
 * <p>1. token 签发、密码策略、MFA、SSO、用户生命周期、refresh token 轮换由 IdP 负责；</p>
 * <p>2. gateway 只校验 access token，并提取租户、操作者、角色、类型、workspace 等低敏控制面事实；</p>
 * <p>3. 下游业务服务不直接解析 JWT，只消费 gateway 写入的 X-DataSmart-*；</p>
 * <p>4. authorization 仍由 permission-admin 负责，认证中心不直接决定业务资源访问权限。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "datasmart.gateway.authentication-center")
public class GatewayAuthenticationCenterProperties {

    /**
     * 是否启用 gateway 认证中心 API。
     *
     * <p>关闭后 `/auth/**` 和 `/api/auth/**` 仍可由 Spring Security 放行，但 controller 会返回当前能力不可用。
     * 这样做的目的是保留统一路由契约，同时允许某些部署把认证完全交给外部企业网关。</p>
     */
    private boolean enabled = true;

    /** 当前认证提供方模式，默认使用 OIDC/JWT Resource Server。 */
    private String providerMode = "OIDC_RESOURCE_SERVER";

    /**
     * 认证中心展示给调用方的 issuer 标识。
     *
     * <p>该值应与 `spring.security.oauth2.resourceserver.jwt.issuer-uri` 对齐。
     * 对 Keycloak 来说通常类似：`http://localhost:18080/realms/datasmart`。</p>
     */
    private String issuer = "http://localhost:18080/realms/datasmart";

    /**
     * OIDC/JWT claim 映射配置。
     */
    private OidcJwtProperties oidc = new OidcJwtProperties();

    /**
     * OIDC/JWT Resource Server 映射策略。
     *
     * <p>Keycloak、Azure AD、Authing、Okta 或企业自研 IdP 的 claim 命名往往不同。
     * 因此这里把 claim 名称配置化，避免 gateway 代码写死某一家 IdP 的私有字段。</p>
     */
    @Data
    public static class OidcJwtProperties {

        /** 是否启用 OIDC/JWT 认证与 claim 到平台 Header 的映射。 */
        private boolean enabled = true;

        /**
         * JWT 公钥集合地址，也就是 OIDC/JWKS 协议里的 `jwks_uri`。
         *
         * <p>这个字段和上层 `issuer` 是故意拆开的：</p>
         * <p>1. `issuer` 是安全语义，代表 token 的 `iss` 必须等于哪个可信签发者；</p>
         * <p>2. `jwkSetUri` 是网络寻址语义，代表 gateway 到哪里拉取验签公钥；</p>
         * <p>3. 在本地 Docker Compose 中，宿主机通过 `http://localhost:18080` 向 Keycloak 取 token，
         * token 里的 issuer 也会是 `http://localhost:18080/realms/datasmart`；</p>
         * <p>4. 但 gateway 容器内部访问 `localhost` 只会访问自身，因此必须用
         * `http://keycloak:18080/.../certs` 这样的容器 DNS 拉取公钥；</p>
         * <p>5. 生产环境如果 IdP issuer 对 gateway 可直连，可以留空本字段，让 Spring Security 通过
         * issuer discovery 自动发现 JWKS；如果存在内外网地址差异，则显式配置该字段。</p>
         *
         * <p>安全边界：配置该字段并不会跳过 issuer 校验。`GatewaySecurityConfig` 仍会使用
         * `JwtValidators.createDefaultWithIssuer(...)` 校验 `iss/exp/nbf` 等协议级事实。</p>
         */
        private String jwkSetUri;

        /**
         * 是否校验 JWT audience。
         *
         * <p>OIDC access token 不仅要证明“由可信 issuer 签发”，还要证明“这个 token 是发给当前资源服务器的”。
         * 如果只校验 issuer，不校验 audience，其他系统拿到的 access token 可能被误用于调用 DataSmart gateway，
         * 这就是典型的 token confusion 风险。生产环境建议保持 true。</p>
         */
        private boolean audienceValidationEnabled = true;

        /**
         * gateway 作为资源服务器接受的 audience 列表。
         *
         * <p>Keycloak 中通常可以通过 Client Scope 或 Audience Mapper 把网关 client id 写入 aud。
         * 企业 IdP 或云 IAM 中则通常对应“API Identifier”“Resource Server Identifier”或“Application ID URI”。
         * 只要 JWT 的 aud 与该列表存在交集，就认为 token 是发给 DataSmart gateway 的。</p>
         */
        private List<String> requiredAudiences = new ArrayList<>(List.of("datasmart-gateway"));

        /**
         * JWT 已验证但缺少 tenantId、actorId 或 actorRole 等关键 claim 时是否失败关闭。
         *
         * <p>生产默认 true。原因是“token 合法”不等于“能进入多租户业务系统”。
         * 如果没有租户、操作者和角色，继续放行会让 permission-admin 只能按匿名身份或默认租户判定，商业上不可接受。</p>
         */
        private boolean failClosedOnMissingRequiredClaims = true;

        /** 租户 ID claim，建议由 IdP 作为自定义 claim 下发。 */
        private String tenantIdClaim = "datasmart_tenant_id";

        /**
         * 操作者数字 ID claim。
         *
         * <p>不直接使用 OIDC 标准 sub，是因为 sub 常常是 UUID/字符串，而当前 Java 业务服务和 permission-admin
         * 使用 Long actorId。后续如果平台统一改成字符串 subject，可再迁移该字段类型。</p>
         */
        private String actorIdClaim = "datasmart_actor_id";

        /** 操作者角色 claim，优先级高于 roles 数组和 Keycloak realm_access.roles。 */
        private String actorRoleClaim = "datasmart_actor_role";

        /** 操作者类型 claim，例如 USER、SERVICE_ACCOUNT、AGENT、SYSTEM_SCHEDULER。 */
        private String actorTypeClaim = "datasmart_actor_type";

        /** workspace claim，用于 Agent 工作区、任务隔离和资产目录边界。 */
        private String workspaceIdClaim = "datasmart_workspace_id";

        /**
         * 项目集合 claim，用于让 gateway 的会话视图知道当前登录主体在 IdP 侧声明的项目候选范围。
         *
         * <p>注意：该字段不是最终授权裁决。真实业务请求仍必须经过 permission-admin，
         * 由 permission-admin 基于 `permission_project_membership`、角色、路由策略和数据范围策略
         * 重新物化 `authorizedProjectIds`，再由 gateway 写入下游可信 Header。这样设计的原因是：
         * 1. Keycloak/企业 IdP 的 access token 通常有有效期，项目成员关系变更后不会立即刷新旧 token；
         * 2. permission-admin 才是 DataSmart 的业务授权事实源，能审计、撤销和按资源类型差异化裁决；
         * 3. token 中保留项目集合主要服务前端项目切换、登录态诊断和本地闭环 smoke，不应绕过授权中心。</p>
         */
        private String projectIdsClaim = "datasmart_project_ids";

        /** 角色数组 claim。若 actorRoleClaim 为空，则从该数组选择第一个可识别平台角色。 */
        private String rolesClaim = "datasmart_roles";

        /** Keycloak realm_access claim 名称。 */
        private String keycloakRealmAccessClaim = "realm_access";

        /** Keycloak realm_access 下角色数组字段名。 */
        private String keycloakRolesField = "roles";

        /** 没有 actorType claim 时的默认操作者类型。 */
        private String defaultActorType = "USER";

        /** 没有 workspace claim 时的默认工作区。 */
        private String defaultWorkspaceId = "default";

        /** 允许从 JWT 映射出来的平台角色。 */
        private List<String> allowedRoles = new ArrayList<>(List.of(
                "ORDINARY_USER",
                "PROJECT_OWNER",
                "OPERATOR",
                "AUDITOR",
                "TENANT_ADMINISTRATOR",
                "PLATFORM_ADMINISTRATOR",
                "SERVICE_ACCOUNT"
        ));

        /**
         * 角色前缀裁剪。
         *
         * <p>很多 IdP 会把角色写成 ROLE_PLATFORM_ADMINISTRATOR 或 DATASMART_PLATFORM_ADMINISTRATOR。
         * gateway 会按顺序裁剪这些前缀，再规范化成大写下划线格式。</p>
         */
        private List<String> rolePrefixesToStrip = new ArrayList<>(List.of("ROLE_", "DATASMART_"));
    }
}
