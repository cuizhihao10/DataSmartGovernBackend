/**
 * @Author : Cui
 * @Date: 2026/07/05 03:26
 * @Description DataSmartGovernBackend - IdentityProviderAdminClient.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service.identity;

/**
 * 外部身份系统管理客户端抽象。
 *
 * <p>Keycloak 只是当前本地闭环和默认商用参考实现。真实企业客户可能使用 Azure AD、Okta、自建 IAM、
 * LDAP 网关或通过 SCIM 同步用户。把 IdP 管理能力放在接口后面，可以让 permission-admin 的业务层保持稳定。
 */
public interface IdentityProviderAdminClient {

    /**
     * 创建外部身份用户。
     */
    IdentityProviderOperationResult createUser(IdentityProviderUserCreateCommand command);

    /**
     * 禁用外部身份用户。
     */
    IdentityProviderOperationResult disableUser(String providerUserId, String reason);

    /**
     * 重置外部身份用户密码。
     */
    IdentityProviderOperationResult resetPassword(String providerUserId, String password, boolean temporaryPassword);
}
