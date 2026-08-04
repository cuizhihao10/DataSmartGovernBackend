/**
 * @Author : Cui
 * @Date: 2026/05/13 22:48
 * @Description DataSmart Govern Backend - AgentSessionRecord.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.session;

import com.czh.datasmart.govern.agent.model.AgentSessionState;
import com.czh.datasmart.govern.agent.model.WorkspaceIsolationLevel;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 内部 Agent 会话记录。
 *
 * <p>会话记录承载 Agent 的上下文边界：租户、项目、工作空间、操作者、隔离级别、工具绑定、运行历史。
 * 当前存储在内存中，主要用于把状态模型和 API 契约跑通；后续可以迁移到 MySQL 保存长生命周期事实，
 * 再用 Redis 缓存在线上下文，用 Kafka 记录运行事件。
 */
public class AgentSessionRecord {

    private final String sessionId;
    private final String agentId;
    private final Long tenantId;
    private final Long projectId;
    private final Long workspaceId;
    private final String actorId;
    /** 当前委托用户的平台角色；工具下游调用不能把普通用户提升为 SERVICE_ACCOUNT。 */
    private String actorRole;
    /** 当前委托主体类型，浏览器登录用户通常为 USER。 */
    private String actorType;
    /** gateway 物化的项目内角色快照，例如 101:MANAGER；写操作继续由业务服务二次校验。 */
    private String authorizedProjectRoles;
    private final String channel;
    private final String objective;
    private final WorkspaceIsolationLevel isolationLevel;
    private final String workspaceKey;
    private AgentSessionState state;
    private final List<AgentToolBindingRecord> toolBindings = new ArrayList<>();
    private final List<AgentRunRecord> runs = new ArrayList<>();
    private final List<AgentConversationMessageRecord> messages = new ArrayList<>();
    private AgentDelegationRecord delegation;
    private boolean pinned;
    private LocalDateTime archivedAt;
    private LocalDateTime lastMessageAt;
    private final LocalDateTime createTime;
    private LocalDateTime updateTime;

    public AgentSessionRecord(String sessionId,
                              Long tenantId,
                              Long projectId,
                              Long workspaceId,
                              String actorId,
                              String channel,
                              String objective,
                              WorkspaceIsolationLevel isolationLevel,
                              String workspaceKey,
                              LocalDateTime createTime) {
        this(sessionId, tenantId, projectId, workspaceId, actorId, null, null, null,
                channel, objective, isolationLevel, workspaceKey, createTime);
    }

    public AgentSessionRecord(String sessionId,
                              Long tenantId,
                              Long projectId,
                              Long workspaceId,
                              String actorId,
                              String actorRole,
                              String actorType,
                              String authorizedProjectRoles,
                              String channel,
                              String objective,
                              WorkspaceIsolationLevel isolationLevel,
                              String workspaceKey,
                              LocalDateTime createTime) {
        this(sessionId, "datasmart-govern-agent", tenantId, projectId, workspaceId, actorId,
                actorRole, actorType, authorizedProjectRoles, channel, objective, isolationLevel, workspaceKey,
                AgentSessionState.ACTIVE, null, false, null, createTime, createTime, createTime,
                List.of(), List.of(), List.of());
    }

    public AgentSessionRecord(String sessionId,
                              String agentId,
                              Long tenantId,
                              Long projectId,
                              Long workspaceId,
                              String actorId,
                              String actorRole,
                              String actorType,
                              String authorizedProjectRoles,
                              String channel,
                              String objective,
                              WorkspaceIsolationLevel isolationLevel,
                              String workspaceKey,
                              AgentSessionState state,
                              AgentDelegationRecord delegation,
                              boolean pinned,
                              LocalDateTime archivedAt,
                              LocalDateTime lastMessageAt,
                              LocalDateTime createTime,
                              LocalDateTime updateTime,
                              List<AgentToolBindingRecord> toolBindings,
                              List<AgentRunRecord> runs,
                              List<AgentConversationMessageRecord> messages) {
        this.sessionId = sessionId;
        this.agentId = normalize(agentId) == null ? "datasmart-govern-agent" : agentId.trim();
        this.tenantId = tenantId;
        this.projectId = projectId;
        this.workspaceId = workspaceId;
        this.actorId = actorId;
        this.actorRole = normalize(actorRole);
        this.actorType = normalize(actorType);
        this.authorizedProjectRoles = normalize(authorizedProjectRoles);
        this.channel = channel;
        this.objective = objective;
        this.isolationLevel = isolationLevel;
        this.workspaceKey = workspaceKey;
        this.state = state == null ? AgentSessionState.ACTIVE : state;
        this.createTime = createTime;
        this.updateTime = updateTime == null ? createTime : updateTime;
        this.pinned = pinned;
        this.archivedAt = archivedAt;
        this.lastMessageAt = lastMessageAt == null ? createTime : lastMessageAt;
        if (toolBindings != null) {
            this.toolBindings.addAll(toolBindings);
        }
        if (runs != null) {
            this.runs.addAll(runs);
        }
        if (messages != null) {
            this.messages.addAll(messages);
        }
        this.delegation = delegation == null ? new AgentDelegationRecord(
                "agd_" + UUID.randomUUID().toString().replace("-", ""),
                this.agentId,
                actorId,
                tenantId,
                projectId,
                List.of(),
                List.of(),
                List.of(),
                AgentDelegationRecord.ACTIVE,
                this.createTime,
                null,
                null,
                this.createTime
        ) : delegation;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getAgentId() {
        return agentId;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public Long getProjectId() {
        return projectId;
    }

    public Long getWorkspaceId() {
        return workspaceId;
    }

    public String getActorId() {
        return actorId;
    }

    public String getActorRole() {
        return actorRole;
    }

    public String getActorType() {
        return actorType;
    }

    public String getAuthorizedProjectRoles() {
        return authorizedProjectRoles;
    }

    public String getChannel() {
        return channel;
    }

    public String getObjective() {
        return objective;
    }

    public WorkspaceIsolationLevel getIsolationLevel() {
        return isolationLevel;
    }

    public String getWorkspaceKey() {
        return workspaceKey;
    }

    public AgentSessionState getState() {
        return state;
    }

    public List<AgentToolBindingRecord> getToolBindings() {
        return List.copyOf(toolBindings);
    }

    public List<AgentRunRecord> getRuns() {
        return List.copyOf(runs);
    }

    public List<AgentConversationMessageRecord> getMessages() {
        return List.copyOf(messages);
    }

    public AgentDelegationRecord getDelegation() {
        return delegation;
    }

    public boolean isPinned() {
        return pinned;
    }

    public boolean isArchived() {
        return archivedAt != null;
    }

    public LocalDateTime getArchivedAt() {
        return archivedAt;
    }

    public LocalDateTime getLastMessageAt() {
        return lastMessageAt;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    /**
     * 追加工具绑定。
     */
    public void addToolBinding(AgentToolBindingRecord binding) {
        this.toolBindings.add(binding);
        this.delegation.grant(binding);
        this.updateTime = LocalDateTime.now();
    }

    /**
     * 追加运行记录。
     */
    public void addRun(AgentRunRecord run) {
        this.runs.add(run);
        this.updateTime = LocalDateTime.now();
    }

    public void addMessage(AgentConversationMessageRecord message) {
        if (message == null || message.content() == null || message.content().isBlank()) {
            return;
        }
        this.messages.add(message);
        this.lastMessageAt = message.createTime() == null ? LocalDateTime.now() : message.createTime();
        this.updateTime = this.lastMessageAt;
    }

    public void setPinned(boolean pinned) {
        this.pinned = pinned;
        this.updateTime = LocalDateTime.now();
    }

    public void setArchived(boolean archived) {
        this.archivedAt = archived ? LocalDateTime.now() : null;
        this.updateTime = LocalDateTime.now();
    }

    /**
     * 用确认执行请求携带的最新 gateway 权限快照刷新委托身份。
     *
     * <p>计划生成到用户确认之间，项目角色可能被撤销或降级。执行前刷新可以避免继续使用过期的 MANAGER/OWNER
     * 快照；下游服务仍会基于这些 Header 和自身数据库事实再次执行 fail-closed 校验。</p>
     */
    public void refreshDelegatedIdentity(String actorRole, String actorType, String authorizedProjectRoles) {
        this.actorRole = normalize(actorRole);
        this.actorType = normalize(actorType);
        this.authorizedProjectRoles = normalize(authorizedProjectRoles);
        this.updateTime = LocalDateTime.now();
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
