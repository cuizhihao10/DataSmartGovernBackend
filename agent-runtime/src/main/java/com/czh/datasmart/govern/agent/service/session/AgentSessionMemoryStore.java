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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

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
     * Applies a compound change to the current map entry without replacing it with a caller-owned stale snapshot.
     *
     * <p>{@code computeIfPresent} serializes changes to the map slot and the session monitor protects its mutable
     * child lists. The callback therefore observes all changes previously committed in this process. Memory mode is
     * still intentionally development-only and cannot provide cross-process rollback; production uses PostgreSQL.</p>
     */
    @Override
    public <T> Optional<T> mutateAtomically(String sessionId, Function<AgentSessionRecord, T> mutation) {
        if (sessionId == null || sessionId.isBlank() || mutation == null) {
            return Optional.empty();
        }
        AtomicReference<T> result = new AtomicReference<>();
        sessions.computeIfPresent(sessionId.trim(), (ignored, currentSession) -> {
            synchronized (currentSession) {
                T changed = mutation.apply(currentSession);
                if (changed == null) {
                    throw new IllegalStateException("Agent session atomic mutation must return a non-null result");
                }
                result.set(changed);
                return currentSession;
            }
        });
        return Optional.ofNullable(result.get());
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

    /**
     * Applies the process-local application-scope binding without replacing Runs, messages, tools, or delegation.
     *
     * <p>The session monitor makes the absent-or-equal check atomic with the optional assignment. Returning
     * {@code false} for a different existing value mirrors the JDBC conditional update and prevents a caller from
     * reusing one session across product applications. This memory profile remains suitable only for local
     * development; production uses the database implementation for cross-instance arbitration.</p>
     */
    @Override
    public boolean bindApplicationIdIfAbsent(String sessionId, Long applicationId) {
        if (sessionId == null || sessionId.isBlank() || applicationId == null || applicationId <= 0) {
            return false;
        }
        boolean[] bound = {false};
        sessions.computeIfPresent(sessionId.trim(), (ignored, currentSession) -> {
            synchronized (currentSession) {
                Long currentApplicationId = currentSession.getApplicationId();
                if (currentApplicationId == null) {
                    currentSession.bindApplicationId(applicationId);
                    bound[0] = true;
                } else {
                    bound[0] = currentApplicationId.equals(applicationId);
                }
                return currentSession;
            }
        });
        return bound[0];
    }

    /**
     * Updates only the delegated identity fields on the currently stored session object.
     *
     * <p>The map-slot computation and session monitor make the parent update atomic for the local development
     * profile. No caller-owned session snapshot is inserted into the map, so Runs and messages concurrently added
     * to the current object remain present.</p>
     */
    @Override
    public boolean refreshDelegatedIdentity(String sessionId,
                                            String actorRole,
                                            String actorType,
                                            String authorizedProjectRoles) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        boolean[] refreshed = {false};
        sessions.computeIfPresent(sessionId.trim(), (ignored, currentSession) -> {
            synchronized (currentSession) {
                currentSession.refreshDelegatedIdentity(actorRole, actorType, authorizedProjectRoles);
                refreshed[0] = true;
                return currentSession;
            }
        });
        return refreshed[0];
    }

    /**
     * Persists one terminal Run lifecycle in memory without replacing any session child collection.
     *
     * <p>The current Run object usually is the same instance as {@code run}; delegating to the domain method also
     * handles reloaded snapshots and enforces the same different-terminal-state rejection used by PostgreSQL.</p>
     */
    @Override
    public boolean updateRunLifecycle(String sessionId, AgentRunRecord run) {
        if (sessionId == null || sessionId.isBlank() || run == null || run.getRunId() == null
                || run.getRunId().isBlank() || run.getState() == null || !run.getState().isTerminal()) {
            return false;
        }
        boolean[] updated = {false};
        sessions.computeIfPresent(sessionId.trim(), (ignored, currentSession) -> {
            synchronized (currentSession) {
                currentSession.getRuns().stream()
                        .filter(currentRun -> run.getRunId().equals(currentRun.getRunId()))
                        .findFirst()
                        .ifPresent(currentRun -> updated[0] = currentRun.applyTerminalLifecycleSnapshot(run));
                return currentSession;
            }
        });
        return updated[0];
    }

    /**
     * Applies one approval-reconciliation result to the current in-memory Run without replacing the session slot.
     *
     * <p>The domain method enforces the WAITING_HUMAN-or-same-target predicate, while the session monitor keeps the
     * check and mutation atomic for the local profile.</p>
     */
    @Override
    public boolean updateRunAfterToolDecision(String sessionId, AgentRunRecord run) {
        if (sessionId == null || sessionId.isBlank() || run == null || run.getRunId() == null
                || run.getRunId().isBlank()) {
            return false;
        }
        boolean[] updated = {false};
        sessions.computeIfPresent(sessionId.trim(), (ignored, currentSession) -> {
            synchronized (currentSession) {
                currentSession.getRuns().stream()
                        .filter(currentRun -> run.getRunId().equals(currentRun.getRunId()))
                        .findFirst()
                        .ifPresent(currentRun -> updated[0] = currentRun.applyToolDecisionLifecycleSnapshot(run));
                return currentSession;
            }
        });
        return updated[0];
    }

    /**
     * 在 memory profile 中对一个 Run 执行与 PostgreSQL JSONB 条件更新等价的原子合并。
     *
     * <p>外层 {@link ConcurrentMap#computeIfPresent} 固定 session 槽位，内层同步会话对象以保护普通 List；
     * 最终由 {@link AgentRunRecord#putVariablesIfGuardAbsent(String, Map)} 一次检查并写入所有变量。
     * 因而两个并发确认最多只有一个返回 true，失败方随后只能读取 receipt 或报告确认仍在处理中。</p>
     *
     * @param sessionId 目标会话 ID
     * @param runId 目标 Run ID
     * @param guardVariable 服务器控制的一次性守卫变量
     * @param values 要整体写入的变量
     * @return 找到目标且首次写入成功时返回 true
     */
    @Override
    public boolean putRunVariablesIfAbsent(String sessionId,
                                           String runId,
                                           String guardVariable,
                                           Map<String, Object> values) {
        if (sessionId == null || sessionId.isBlank() || runId == null || runId.isBlank()) {
            return false;
        }
        boolean[] inserted = {false};
        sessions.computeIfPresent(sessionId.trim(), (ignored, currentSession) -> {
            synchronized (currentSession) {
                currentSession.getRuns().stream()
                        .filter(run -> runId.trim().equals(run.getRunId()))
                        .findFirst()
                        .ifPresent(run -> inserted[0] = run.putVariablesIfGuardAbsent(guardVariable, values));
                return currentSession;
            }
        });
        return inserted[0];
    }

    /** 按业务会话编号读取当前进程中的聚合；服务重启或请求落到其他实例时可能不存在。 */
    /**
     * Performs the in-memory equivalent of the durable first-AUTOPILOT authorization claim.
     *
     * <p>{@link ConcurrentMap#computeIfPresent(Object, java.util.function.BiFunction)} selects one stable
     * session slot and the nested monitor protects the session's mutable Run list. The operation first checks
     * every Run for the session-wide authorization fact, then delegates the target Run write to
     * {@link AgentRunRecord#putVariablesIfGuardAbsent(String, Map)}. Consequently, two local threads that
     * confirm different Runs cannot both return {@code true}. This profile is still process-local and is only
     * suitable for development and tests; the JDBC implementation provides the cross-instance database lock
     * used in production.</p>
     *
     * @param sessionId session that owns both competing Runs
     * @param runId target Run receiving the claim and authorization facts
     * @param guardVariable target Run's one-time confirmation claim key
     * @param sessionUniqueVariable session-wide authorization key, normally {@code autopilotAuthorization}
     * @param values complete immutable values to write to the target Run
     * @return {@code true} for the single winning first claim, otherwise {@code false}
     */
    @Override
    public boolean putRunVariablesIfAbsentAndSessionVariableAbsent(String sessionId,
                                                                    String runId,
                                                                    String guardVariable,
                                                                    String sessionUniqueVariable,
                                                                    Map<String, Object> values) {
        if (sessionId == null || sessionId.isBlank() || runId == null || runId.isBlank()
                || guardVariable == null || guardVariable.isBlank()
                || sessionUniqueVariable == null || sessionUniqueVariable.isBlank()
                || values == null || values.isEmpty()
                || !values.containsKey(guardVariable)
                || !values.containsKey(sessionUniqueVariable)) {
            return false;
        }
        boolean[] inserted = {false};
        sessions.computeIfPresent(sessionId.trim(), (ignored, currentSession) -> {
            synchronized (currentSession) {
                boolean authorizationAlreadyEstablished = currentSession.getRuns().stream()
                        .map(AgentRunRecord::getVariables)
                        .anyMatch(variables -> variables.containsKey(sessionUniqueVariable));
                if (authorizationAlreadyEstablished) {
                    return currentSession;
                }
                currentSession.getRuns().stream()
                        .filter(run -> runId.trim().equals(run.getRunId()))
                        .findFirst()
                        .ifPresent(run -> inserted[0] = run.putVariablesIfGuardAbsent(guardVariable, values));
                return currentSession;
            }
        });
        return inserted[0];
    }

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
