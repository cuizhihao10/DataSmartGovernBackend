/**
 * @Author : Cui
 * @Date: 2026/08/04 00:00
 * @Description DataSmart Govern Backend - AgentSessionAccessContext.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.session;

import java.util.Set;

/** Gateway 清理并注入的当前用户访问上下文。 */
public record AgentSessionAccessContext(Long tenantId,
                                        Long projectId,
                                        String actorId,
                                        String actorRole) {

    private static final Set<String> PRIVILEGED_READ_ROLES = Set.of(
            "PLATFORM_ADMIN", "PLATFORM_ADMINISTRATOR", "TENANT_ADMIN", "TENANT_ADMINISTRATOR",
            "OPERATOR", "AUDITOR");

    public boolean platformAdministrator() {
        if (actorRole == null) {
            return false;
        }
        String role = actorRole.trim().toUpperCase();
        return "PLATFORM_ADMIN".equals(role) || "PLATFORM_ADMINISTRATOR".equals(role);
    }

    public boolean privilegedRead() {
        return actorRole != null && PRIVILEGED_READ_ROLES.contains(actorRole.trim().toUpperCase());
    }
}
