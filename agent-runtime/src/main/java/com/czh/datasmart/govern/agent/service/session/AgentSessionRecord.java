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

    /** 会话业务编号，是聚合主表及消息、运行、工具记录的关联键。 */
    private final String sessionId;
    /** 实际执行 Agent 身份，与发起会话的 user actor 共同构成双主体审计。 */
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
    /** 可跨服务重启恢复的多轮对话，元素通过 runId 关联具体运行。 */
    private final List<AgentConversationMessageRecord> messages = new ArrayList<>();
    /** 用户授予 Agent 的最小权限快照；它只能收窄权限，不能替代业务服务授权。 */
    private AgentDelegationRecord delegation;
    /** 仅影响当前用户历史列表排序，不影响会话执行语义。 */
    private boolean pinned;
    /** 非空表示已归档；归档保留全部审计与消息，不等同于删除。 */
    private LocalDateTime archivedAt;
    /** 最近一条有效消息时间，用于历史会话排序和活跃度展示。 */
    private LocalDateTime lastMessageAt;
    private final LocalDateTime createTime;
    private LocalDateTime updateTime;

    /**
     * 兼容早期调用方的最小构造器。
     *
     * <p>未提供角色和委托明细时会创建空权限委托；空委托不会允许任何工具执行，后续需通过工具绑定
     * 显式授予本会话需要的工具范围。</p>
     */
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

    /**
     * 创建新会话使用的构造器，补充 Gateway 物化的用户身份快照。
     *
     * <p>状态、时间和子集合使用安全初始值，并生成默认 Agent 身份；该身份不会覆盖 actor 的权限。</p>
     */
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

    /**
     * 从持久化层完整恢复会话聚合。
     *
     * <p>所有可变状态和子集合均由数据库快照传入。delegation 仅在旧数据缺失时补成“无工具权限”的
     * 默认委托，绝不根据角色推导更高权限；集合复制进内部列表，避免仓储返回后被外部引用修改。</p>
     */
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

    /** 返回本会话执行主体的 Agent 标识，用于与 user actor 组成双主体审计键。 */
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

    /** 返回不可通过调用方修改的消息快照。 */
    public List<AgentConversationMessageRecord> getMessages() {
        return List.copyOf(messages);
    }

    /** 返回当前委托；执行前仍需检查有效期、撤销状态和目标资源。 */
    public AgentDelegationRecord getDelegation() {
        return delegation;
    }

    /** 返回会话是否在用户历史中置顶。 */
    public boolean isPinned() {
        return pinned;
    }

    /** 通过 archivedAt 是否存在判断归档状态，避免布尔值与归档时间不一致。 */
    public boolean isArchived() {
        return archivedAt != null;
    }

    /** 返回归档时间；活跃会话返回 null。 */
    public LocalDateTime getArchivedAt() {
        return archivedAt;
    }

    /** 返回最近有效消息时间，用于历史会话排序。 */
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

    /**
     * 追加一条非空对话消息并同步更新会话活跃时间。
     *
     * <p>空对象或空白内容直接忽略，避免制造无法展示和无法用于模型上下文的噪声消息。</p>
     */
    public void addMessage(AgentConversationMessageRecord message) {
        if (message == null || message.content() == null || message.content().isBlank()) {
            return;
        }
        this.messages.add(message);
        this.lastMessageAt = message.createTime() == null ? LocalDateTime.now() : message.createTime();
        this.updateTime = this.lastMessageAt;
    }

    /** 设置置顶标志并刷新更新时间，确保历史列表立即重新排序。 */
    public void setPinned(boolean pinned) {
        this.pinned = pinned;
        this.updateTime = LocalDateTime.now();
    }

    /**
     * 设置归档状态并记录操作时刻。
     *
     * <p>取消归档会清空 archivedAt，但不会丢失此前消息、运行或工具审计。</p>
     */
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
