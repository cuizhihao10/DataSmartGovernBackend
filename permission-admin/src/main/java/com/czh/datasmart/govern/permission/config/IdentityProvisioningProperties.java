/**
 * @Author : Cui
 * @Date: 2026/07/05 03:26
 * @Description DataSmartGovernBackend - IdentityProvisioningProperties.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 身份供应配置。
 *
 * <p>这里的“身份供应”不是在 DataSmart 内部重新造一套用户名密码登录系统，而是让 permission-admin
 * 通过 Keycloak Admin API 或未来的企业 IdP 管理 API 去创建、禁用、重置外部身份。这样的设计更符合商用产品：
 * 1. 密码、登录会话、MFA、账号锁定、Token 签发继续交给专业 IdP；
 * 2. DataSmart 只保存低敏影子身份，用于租户、角色、workspace、审计和业务授权；
 * 3. 如果客户已有 Azure AD、Okta、Keycloak、CAS、LDAP 网关等企业身份系统，后续只需要替换适配器。
 */
@Data
@Component
@ConfigurationProperties(prefix = "datasmart.permission.identity-provisioning")
public class IdentityProvisioningProperties {

    /**
     * 是否启用身份供应能力。
     *
     * <p>本地 Compose 默认启用，方便快速通过 Keycloak 创建测试账号；生产环境如果客户只允许外部 IAM
     * 或企业工单系统创建账号，可以关闭该开关，DataSmart 只消费 OIDC Token 和组织同步结果。
     */
    private boolean enabled = true;

    /**
     * 身份供应适配模式。
     *
     * <p>当前落地 KEYCLOAK_ADMIN_API。保留字符串而不是直接绑定枚举，是为了后续灰度接入 ENTERPRISE_IDP、
     * SCIM、LDAP_BRIDGE、MANAGED_IAM 等模式时，可以先通过配置识别，再逐步补适配器。
     */
    private String providerMode = "KEYCLOAK_ADMIN_API";

    /**
     * Keycloak Admin API 连接配置。
     */
    private Keycloak keycloak = new Keycloak();

    @Data
    public static class Keycloak {

        /**
         * Keycloak 基础地址。
         *
         * <p>本机开发通常是 http://localhost:18080；容器内应用访问 Compose Keycloak 时通常是
         * http://keycloak:18080。这里不包含 realm 路径，代码会按 Admin API 规范拼接。
         */
        private String baseUrl = "http://localhost:18080";

        /**
         * 管理员认证所在 realm。
         *
         * <p>本地快速闭环使用 master + admin-cli password grant；生产建议改为专用 confidential client
         * 的 client_credentials，并只授予最小 admin 权限。
         */
        private String adminRealm = "master";

        /**
         * 业务用户所在 realm。
         *
         * <p>DataSmart gateway 默认验证 datasmart realm 签发的 Token，所以这里默认也是 datasmart。
         */
        private String targetRealm = "datasmart";

        /**
         * 管理客户端 ID。
         *
         * <p>本地默认 admin-cli；生产建议使用专用 client，例如 datasmart-identity-provisioner，并通过
         * client secret 或私钥 JWT 认证。
         */
        private String adminClientId = "admin-cli";

        /**
         * 管理客户端密钥。
         *
         * <p>为空时使用本地开发友好的 password grant；非空时优先使用 client_credentials。
         * 注意：该值永远不能写入日志、审计表或 API 响应。
         */
        private String adminClientSecret = "";

        /**
         * 本地开发管理员用户名。
         *
         * <p>仅在 adminClientSecret 为空时使用。生产环境不建议让应用长期保存超级管理员密码。
         */
        private String adminUsername = "admin";

        /**
         * 本地开发管理员密码。
         *
         * <p>仅在 adminClientSecret 为空时使用。该字段属于高敏配置，只能来自环境变量、Secret Manager
         * 或受控配置中心，不能进入响应、审计、日志。
         */
        private String adminPassword = "admin";

        /**
         * 是否把 DataSmart 角色同步为 Keycloak realm role。
         *
         * <p>开启后，新建用户会被授予 DATASMART_ORDINARY_USER、DATASMART_TENANT_ADMINISTRATOR 等
         * realm role，gateway 可以从 JWT roles 或自定义 mapper 中读取角色。
         */
        private boolean assignRealmRole = true;

        /**
         * Keycloak realm role 前缀。
         *
         * <p>仓库内置 realm 使用 DATASMART_ 前缀，避免与 Keycloak 自身角色或客户已有角色冲突。
         */
        private String realmRolePrefix = "DATASMART_";

        /**
         * Admin API 连接建立超时。
         *
         * <p>账号供应是管理动作，不能无限等待 IdP；短超时可以让调用方快速感知外部身份系统不可用。
         */
        private Duration connectTimeout = Duration.ofSeconds(2);

        /**
         * Admin API 读取超时。
         *
         * <p>如果 Keycloak 响应慢于该值，permission-admin 会按外部依赖失败处理，并避免泄露响应体。
         */
        private Duration readTimeout = Duration.ofSeconds(5);
    }
}
