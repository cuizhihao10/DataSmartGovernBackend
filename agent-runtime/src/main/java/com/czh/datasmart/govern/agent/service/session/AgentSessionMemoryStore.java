/**
 * @Author : Cui
 * @Date: 2026/05/13 22:48
 * @Description DataSmart Govern Backend - AgentSessionMemoryStore.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.session;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
@ConditionalOnProperty(prefix = "datasmart.agent-runtime.persistence", name = "session-store",
        havingValue = "memory", matchIfMissing = true)
public class AgentSessionMemoryStore implements AgentSessionStore {

    private final ConcurrentMap<String, AgentSessionRecord> sessions = new ConcurrentHashMap<>();

    /**
     * 保存会话对象引用。
     *
     * <p>ConcurrentMap 保证单次替换线程安全，但不提供跨进程持久性；生产配置应切换到 JDBC 实现。</p>
     */
    @Override
    public void save(AgentSessionRecord session) {
        sessions.put(session.getSessionId(), session);
    }

    /**
     * 在当前 Map 槽位上原子追加消息，保持与 JDBC 增量写契约一致。
     *
     * <p>{@link ConcurrentMap#computeIfPresent(Object, java.util.function.BiFunction)} 会锁定当前 sessionId 对应的
     * 更新槽位；再同步会话对象，是为了防止测试或 memory profile 下另一个线程同时修改其普通 List 子集合。
     * 这里绝不把调用方持有的旧会话对象重新放回 Map，因此不会把已经追加到当前对象上的 Run 替换掉。</p>
     *
     * @param sessionId 目标会话 ID
     * @param message 需要追加的用户可见消息
     * @return 会话存在且消息参数有效时返回 true，否则返回 false
     */
    @Override
    public boolean appendConversationMessage(String sessionId, AgentConversationMessageRecord message) {
        if (sessionId == null || sessionId.isBlank() || message == null
                || message.content() == null || message.content().isBlank()) {
            return false;
        }
        boolean[] appended = {false};
        sessions.computeIfPresent(sessionId.trim(), (ignored, currentSession) -> {
            synchronized (currentSession) {
                boolean alreadyExists = currentSession.getMessages().stream()
                        .anyMatch(existing -> existing.messageId().equals(message.messageId()));
                if (!alreadyExists) {
                    currentSession.addMessage(message);
                }
                appended[0] = true;
                return currentSession;
            }
        });
        return appended[0];
    }

    /** 按业务会话编号读取当前进程中的聚合；服务重启或请求落到其他实例时可能不存在。 */
    @Override
    public Optional<AgentSessionRecord> findById(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    /**
     * 在内存快照上筛选会话历史。
     *
     * <p>排序和数量限制与 JDBC 实现保持一致，保证开发模式与生产模式的前端行为相同；这里的筛选参数
     * 仍必须由上层授权逻辑生成，仓储本身不负责判断调用者身份。</p>
     */
    @Override
    public List<AgentSessionRecord> list(Long tenantId,
                                         Long projectId,
                                         String actorId,
                                         boolean archived,
                                         int limit) {
        return sessions.values().stream()
                .filter(item -> tenantId == null || tenantId.equals(item.getTenantId()))
                .filter(item -> projectId == null || projectId.equals(item.getProjectId()))
                .filter(item -> actorId == null || actorId.isBlank() || actorId.equals(item.getActorId()))
                .filter(item -> item.isArchived() == archived)
                .sorted(Comparator.comparing(AgentSessionRecord::isPinned).reversed()
                        .thenComparing(AgentSessionRecord::getUpdateTime, Comparator.reverseOrder()))
                .limit(Math.max(1, Math.min(limit, 100)))
                .toList();
    }
}
