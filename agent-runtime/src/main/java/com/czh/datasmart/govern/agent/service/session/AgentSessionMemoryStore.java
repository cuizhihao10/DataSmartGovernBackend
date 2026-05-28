/**
 * @Author : Cui
 * @Date: 2026/05/13 22:48
 * @Description DataSmart Govern Backend - AgentSessionMemoryStore.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.session;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Agent 会话内存仓储。
 *
 * <p>这是第一阶段的“运行时仓储”实现，用于支撑 API 契约、状态流转和前端联调。
 * 它不是最终商业化持久化方案，当前边界必须清楚：
 * 1. 服务重启后内存状态会丢失；
 * 2. 多实例部署时各实例看不到彼此会话；
 * 3. 不适合承载审计合规事实；
 * 4. 不适合长时间保存模型上下文或工具调用历史。
 *
 * <p>为什么仍然先做内存仓储：
 * Agent 状态模型、工具注册、审批和 Python Runtime 契约还在快速成型，过早设计表结构会导致频繁迁移。
 * 当前先用仓储接口形态隔离起来，后续替换成 MySQL/Redis/EventStore 时，Controller 不需要跟着大改。
 */
@Component
public class AgentSessionMemoryStore {

    private final ConcurrentMap<String, AgentSessionRecord> sessions = new ConcurrentHashMap<>();

    public void save(AgentSessionRecord session) {
        sessions.put(session.getSessionId(), session);
    }

    public Optional<AgentSessionRecord> findById(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    public List<AgentSessionRecord> list(Long tenantId, Long projectId, String actorId) {
        return sessions.values().stream()
                .filter(item -> tenantId == null || tenantId.equals(item.getTenantId()))
                .filter(item -> projectId == null || projectId.equals(item.getProjectId()))
                .filter(item -> actorId == null || actorId.isBlank() || actorId.equals(item.getActorId()))
                .sorted(Comparator.comparing(AgentSessionRecord::getCreateTime).reversed())
                .toList();
    }
}
