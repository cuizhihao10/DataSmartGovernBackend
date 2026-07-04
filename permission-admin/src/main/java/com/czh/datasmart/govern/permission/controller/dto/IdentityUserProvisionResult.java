/**
 * @Author : Cui
 * @Date: 2026/07/05 03:26
 * @Description DataSmartGovernBackend - IdentityUserProvisionResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

import java.util.List;

/**
 * 身份供应结果视图。
 *
 * <p>响应只包含低敏控制面事实：账号创建到了哪个 provider、对应哪个 providerUserId、DataSmart actorId
 * 是多少、当前状态是什么。它不包含密码、Token、client secret、Keycloak admin endpoint 或任何可直接登录的凭据。
 */
public record IdentityUserProvisionResult(
        String operation,
        String providerMode,
        String providerUserId,
        Long localIdentityId,
        String username,
        String emailMasked,
        Long tenantId,
        Long actorId,
        String actorRole,
        String actorType,
        String workspaceId,
        String status,
        String message,
        String payloadPolicy,
        List<String> evidenceCodes) {
}
