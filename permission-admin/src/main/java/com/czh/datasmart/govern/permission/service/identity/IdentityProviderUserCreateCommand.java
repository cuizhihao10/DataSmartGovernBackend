/**
 * @Author : Cui
 * @Date: 2026/07/05 03:26
 * @Description DataSmartGovernBackend - IdentityProviderUserCreateCommand.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service.identity;

/**
 * 发送给外部 IdP 的创建用户命令。
 *
 * <p>这里把 DataSmart 业务上下文和 IdP 用户资料放在同一个命令中，是因为 Keycloak/企业 IdP 创建用户时
 * 通常需要同时写入属性、角色、初始凭据。注意 password 只允许从 controller 到 IdP 适配器短暂流转，不允许进入
 * 本地持久化实体、审计 detail 或返回结果。
 */
public record IdentityProviderUserCreateCommand(
        String username,
        String email,
        String firstName,
        String lastName,
        String password,
        boolean temporaryPassword,
        boolean enabled,
        boolean emailVerified,
        Long tenantId,
        Long actorId,
        String actorRole,
        String actorType,
        String workspaceId,
        String realmRoleName) {
}
