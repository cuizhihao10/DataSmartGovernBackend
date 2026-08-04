/**
 * @Author : Cui
 * @Date: 2026/08/04 00:00
 * @Description DataSmart Govern Backend - AgentDelegationRecord.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.session;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 用户向指定 Agent 发出的最小权限委托事实。
 *
 * <p>它不是用户权限的副本，更不是提权凭证。有效权限始终是用户 RBAC、资源授权、工具范围、
 * 本次委托范围和系统安全策略的交集。委托过期或撤销后，已有 sessionId 也不能继续执行工具。</p>
 */
public class AgentDelegationRecord {

    public static final String ACTIVE = "ACTIVE";
    public static final String REVOKED = "REVOKED";

    private final String delegationId;
    private final String agentId;
    private final String userActorId;
    private final Long tenantId;
    private final Long projectId;
    private final Set<String> toolCodes;
    private final Set<String> actions;
    private final Set<String> resourceScopes;
    private String status;
    private final LocalDateTime issuedAt;
    private LocalDateTime expiresAt;
    private LocalDateTime revokedAt;
    private LocalDateTime updateTime;

    public AgentDelegationRecord(String delegationId,
                                 String agentId,
                                 String userActorId,
                                 Long tenantId,
                                 Long projectId,
                                 List<String> toolCodes,
                                 List<String> actions,
                                 List<String> resourceScopes,
                                 String status,
                                 LocalDateTime issuedAt,
                                 LocalDateTime expiresAt,
                                 LocalDateTime revokedAt,
                                 LocalDateTime updateTime) {
        this.delegationId = requireText(delegationId, "delegationId");
        this.agentId = requireText(agentId, "agentId");
        this.userActorId = requireText(userActorId, "userActorId");
        this.tenantId = tenantId;
        this.projectId = projectId;
        this.toolCodes = normalizedSet(toolCodes);
        this.actions = normalizedSet(actions);
        this.resourceScopes = normalizedSet(resourceScopes);
        this.status = status == null || status.isBlank() ? ACTIVE : status.trim().toUpperCase();
        this.issuedAt = issuedAt == null ? LocalDateTime.now() : issuedAt;
        this.expiresAt = expiresAt;
        this.revokedAt = revokedAt;
        this.updateTime = updateTime == null ? this.issuedAt : updateTime;
    }

    public void grant(AgentToolBindingRecord binding) {
        if (binding == null) {
            return;
        }
        toolCodes.add(binding.toolCode());
        if (binding.allowedActions() != null) {
            binding.allowedActions().stream().filter(Objects::nonNull).map(String::trim)
                    .filter(value -> !value.isBlank()).forEach(actions::add);
        }
        String service = binding.targetService() == null || binding.targetService().isBlank()
                ? "platform"
                : binding.targetService().trim();
        resourceScopes.add(service + ":" + (binding.targetResourceId() == null ? "*" : binding.targetResourceId()));
        updateTime = LocalDateTime.now();
    }

    public boolean allows(String toolCode, String targetService, Long targetResourceId) {
        if (!active(LocalDateTime.now()) || toolCode == null || !toolCodes.contains(toolCode)) {
            return false;
        }
        String service = targetService == null || targetService.isBlank() ? "platform" : targetService.trim();
        return resourceScopes.contains(service + ":*")
                || resourceScopes.contains(service + ":" + targetResourceId);
    }

    public boolean active(LocalDateTime now) {
        LocalDateTime reference = now == null ? LocalDateTime.now() : now;
        return ACTIVE.equals(status) && (expiresAt == null || expiresAt.isAfter(reference));
    }

    public void revoke() {
        status = REVOKED;
        revokedAt = LocalDateTime.now();
        updateTime = revokedAt;
    }

    public String getDelegationId() { return delegationId; }
    public String getAgentId() { return agentId; }
    public String getUserActorId() { return userActorId; }
    public Long getTenantId() { return tenantId; }
    public Long getProjectId() { return projectId; }
    public List<String> getToolCodes() { return List.copyOf(toolCodes); }
    public List<String> getActions() { return List.copyOf(actions); }
    public List<String> getResourceScopes() { return List.copyOf(resourceScopes); }
    public String getStatus() { return status; }
    public LocalDateTime getIssuedAt() { return issuedAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public LocalDateTime getRevokedAt() { return revokedAt; }
    public LocalDateTime getUpdateTime() { return updateTime; }

    private static Set<String> normalizedSet(List<String> values) {
        Set<String> result = new LinkedHashSet<>();
        if (values != null) {
            values.stream().filter(Objects::nonNull).map(String::trim)
                    .filter(value -> !value.isBlank()).forEach(result::add);
        }
        return result;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value.trim();
    }
}
