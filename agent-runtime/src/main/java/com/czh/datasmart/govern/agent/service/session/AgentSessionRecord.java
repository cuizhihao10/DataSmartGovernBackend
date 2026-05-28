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

/**
 * 内部 Agent 会话记录。
 *
 * <p>会话记录承载 Agent 的上下文边界：租户、项目、工作空间、操作者、隔离级别、工具绑定、运行历史。
 * 当前存储在内存中，主要用于把状态模型和 API 契约跑通；后续可以迁移到 MySQL 保存长生命周期事实，
 * 再用 Redis 缓存在线上下文，用 Kafka 记录运行事件。
 */
public class AgentSessionRecord {

    private final String sessionId;
    private final Long tenantId;
    private final Long projectId;
    private final Long workspaceId;
    private final String actorId;
    private final String channel;
    private final String objective;
    private final WorkspaceIsolationLevel isolationLevel;
    private final String workspaceKey;
    private AgentSessionState state;
    private final List<AgentToolBindingRecord> toolBindings = new ArrayList<>();
    private final List<AgentRunRecord> runs = new ArrayList<>();
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
        this.sessionId = sessionId;
        this.tenantId = tenantId;
        this.projectId = projectId;
        this.workspaceId = workspaceId;
        this.actorId = actorId;
        this.channel = channel;
        this.objective = objective;
        this.isolationLevel = isolationLevel;
        this.workspaceKey = workspaceKey;
        this.state = AgentSessionState.ACTIVE;
        this.createTime = createTime;
        this.updateTime = createTime;
    }

    public String getSessionId() {
        return sessionId;
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
        this.updateTime = LocalDateTime.now();
    }

    /**
     * 追加运行记录。
     */
    public void addRun(AgentRunRecord run) {
        this.runs.add(run);
        this.updateTime = LocalDateTime.now();
    }
}
