/**
 * @Author : Cui
 * @Date: 2026/08/04 00:00
 * @Description DataSmart Govern Backend - AgentSessionStore.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.session;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Agent 会话聚合仓储端口。
 *
 * <p>会话、Run、消息、工具绑定和委托事实共同构成一个安全边界，不能分别落在互不一致的内存 Map 中。
 * 生产环境使用 PostgreSQL 实现，本地轻量测试仍可使用内存实现。</p>
 */
public interface AgentSessionStore {

    /**
     * 原子保存一个完整会话聚合。
     *
     * <p>实现必须保证 session 主记录、delegation、tool bindings、runs 和 messages 要么整体成功、要么整体回滚，
     * 否则恢复后可能出现“页面能看到 Run，但执行时找不到委托”这类安全与一致性问题。</p>
     *
     * @param session 已完成领域校验的会话聚合
     */
    void save(AgentSessionRecord session);

    /**
     * Loads and changes one existing session inside the store's strongest available concurrency boundary.
     *
     * <p>This method is the safe entry point for changes that legitimately span several children, such as adding a
     * Run together with new tool bindings and conversation messages. A normal read followed by
     * {@link #save(AgentSessionRecord)} is unsafe in a multi-instance deployment: another Runtime may append a Run,
     * confirmation fact, or message between those two calls, after which the older aggregate snapshot would delete
     * the new fact during replacement.</p>
     *
     * <p>The PostgreSQL implementation locks the session parent and every existing aggregate child row, reloads the
     * current aggregate on that same connection, invokes {@code mutation}, and persists the result in one transaction.
     * The memory implementation serializes the callback on the current map entry. The callback must be synchronous,
     * deterministic, and return a non-null result; it must not perform remote network calls while the database lock is
     * held. Same-database stores may participate through the shared JDBC connection manager.</p>
     *
     * @param sessionId stable ID of an existing session
     * @param mutation validated domain change to apply to the freshly loaded aggregate
     * @param <T> non-null result returned to the service layer after persistence succeeds
     * @return mutation result, or empty when the session no longer exists
     */
    <T> Optional<T> mutateAtomically(String sessionId, Function<AgentSessionRecord, T> mutation);

    /**
     * 原子追加一条用户可见会话消息，而不覆盖同一会话中已经由其他调用链写入的 Run 或工具事实。
     *
     * <p>Agent 的确认后续跑存在一个重要并发边界：Java 先把当前 Run 终态交给 Python，Python 随后会回调
     * Java 创建下一 Run，最后原 Java 请求再记录二轮助手回复。如果最后一步继续调用 {@link #save(AgentSessionRecord)}
     * 保存调用 Python 之前读取的旧聚合，就会把 Python 刚创建的新 Run 当成“快照中不存在的子记录”删除。
     * 因此，追加对话消息必须是独立的增量写操作，不能通过整聚合替换来模拟。</p>
     *
     * <p>实现必须同时更新会话的 {@code lastMessageAt/updateTime}，并保证消息插入与时间更新处于同一原子边界。
     * 返回 {@code false} 表示会话已经不存在，调用方应停止返回任何后续 Run 引用，避免浏览器拿到悬空操作入口。</p>
     *
     * @param sessionId 消息所属的稳定会话 ID
     * @param message 已完成低敏治理、可以向用户展示的对话消息
     * @return 成功写入或幂等确认消息已存在时返回 true；会话不存在或参数无效时返回 false
     */
    boolean appendConversationMessage(String sessionId, AgentConversationMessageRecord message);

    /**
     * Binds the trusted product-application scope without replacing the session aggregate.
     *
     * <p>Confirmation can race with Python continuation or another Agent Runtime instance that appends Runs and
     * messages to the same session. Persisting a newly observed {@code applicationId} through {@link #save} would
     * replace all child collections from an older in-memory snapshot. Implementations must therefore update only
     * the session column and accept the operation only when the durable value is absent or already equal.</p>
     *
     * <p>The method grants no permission. It records the Gateway-authenticated application boundary before any
     * approval or tool side effect, and fails closed when another application already owns the session.</p>
     *
     * @param sessionId existing Agent session identifier
     * @param applicationId positive trusted application identifier
     * @return {@code true} when the session exists and is now bound to the requested application
     */
    boolean bindApplicationIdIfAbsent(String sessionId, Long applicationId);

    /**
     * Refreshes the trusted delegated-user snapshot without replacing the session aggregate.
     *
     * <p>The confirmation endpoint receives a fresh role snapshot from Gateway immediately before it crosses the
     * approval and tool side-effect boundary. Persisting that snapshot with {@link #save(AgentSessionRecord)} is
     * unsafe in a multi-instance Runtime because the caller may have loaded the session before another instance
     * appended a continuation Run or conversation message. Implementations must therefore update only the three
     * delegated-identity columns and the parent activity timestamp.</p>
     *
     * <p>This method does not evaluate roles and cannot grant a tool permission by itself. The service layer must
     * validate the supplied identity first, while every downstream business service still performs its own object
     * authorization. A {@code false} result means the parent session no longer exists and callers must stop before
     * approving or executing a tool.</p>
     *
     * @param sessionId existing Agent session identifier
     * @param actorRole current Gateway-authenticated platform role
     * @param actorType current authenticated principal type
     * @param authorizedProjectRoles current project-role fact snapshot
     * @return {@code true} when the narrow parent-row update was applied
     */
    boolean refreshDelegatedIdentity(String sessionId,
                                     String actorRole,
                                     String actorType,
                                     String authorizedProjectRoles);

    /**
     * Persists one Run's terminal lifecycle fields without replacing sibling session children.
     *
     * <p>A confirmed tool batch mutates only the source Run's state, message, next actions and finish timestamps.
     * Python continuation may create the next Run as soon as Java reports that terminal batch, so an aggregate
     * {@link #save(AgentSessionRecord)} would be able to delete that new child from an older snapshot. This method
     * deliberately excludes Run variables, delegation, bindings and messages; immutable confirmation receipts and
     * AUTOPILOT authorization facts remain owned by the conditional variable-write methods above.</p>
     *
     * <p>Implementations must reject a different existing terminal state. For example, a Run cancelled while a
     * downstream call was completing must not later be rewritten to SUCCEEDED. Repeating the same terminal state is
     * allowed so an idempotent delivery can converge after a transport retry.</p>
     *
     * @param sessionId parent session that owns the Run
     * @param run terminal in-memory Run snapshot produced by the governed execution service
     * @return {@code true} when the lifecycle was persisted or idempotently reconfirmed
     */
    boolean updateRunLifecycle(String sessionId, AgentRunRecord run);

    /**
     * Persists the Run state produced by one governed approval or rejection decision.
     *
     * <p>Approval reconciliation starts from {@code WAITING_HUMAN}. Depending on the remaining tool audits, the Run
     * either stays there, resumes {@code PLANNING}, or closes as {@code REJECTED}. A whole-session save is unsafe
     * because a different Runtime instance may already have appended another Run or message. Implementations must
     * therefore update only this Run's lifecycle columns and the parent activity timestamp.</p>
     *
     * <p>The durable predicate must accept only {@code WAITING_HUMAN} or an idempotent repeat of the target state.
     * That rule prevents a delayed approval callback from moving a Run backwards after model/tool execution has
     * already advanced it. This method does not change a tool audit; the approval service owns that fact.</p>
     *
     * @param sessionId parent session that owns the Run
     * @param run Run after approval reconciliation
     * @return {@code true} when the guarded lifecycle update succeeded
     */
    boolean updateRunAfterToolDecision(String sessionId, AgentRunRecord run);

    /**
     * 只在目标 Run 尚未包含守卫变量时，原子合并一组服务器拥有的运行变量。
     *
     * <p>该增量接口用于确认 claim、Autopilot 授权和最终确认 receipt。它与整聚合 {@link #save} 的区别是
     * 写集合只包含一个 Run 的 {@code variables/updateTime}：Python continuation 可能已经创建了下一个 Run，
     * 所以后确认阶段绝不能用旧会话快照替换全部子记录。生产实现必须把“检查守卫”和“写入全部变量”放在
     * 同一条 SQL 或同一事务锁内，不能使用容易发生并发穿透的先查后写。</p>
     *
     * <p>{@code false} 同时表示 Run 不存在、守卫已存在或待写键与旧事实冲突。调用方应重新读取持久状态，
     * 只有匹配相同请求摘要的完整 receipt 才能回放；其他情况必须失败关闭。方法不授予权限，也不执行工具。</p>
     *
     * @param sessionId 目标会话 ID
     * @param runId 目标 Run ID
     * @param guardVariable 一次性写入的守卫变量名
     * @param values 要整体合并的低敏 JSONB 兼容值，必须包含守卫变量
     * @return 首次完整写入返回 true；未写入返回 false
     */
    boolean putRunVariablesIfAbsent(String sessionId,
                                    String runId,
                                    String guardVariable,
                                    Map<String, Object> values);

    /**
     * Atomically writes immutable facts to one Run only when a session-wide fact has not yet been established.
     *
     * <p>This is the durable boundary for the first AUTOPILOT authorization. A Run-local conditional write is
     * sufficient for ordinary confirmation idempotency, but it cannot protect two different Runs in the same
     * session: both Runs would otherwise observe that their own variables are empty and both could authorize
     * unattended work. Implementations must therefore check the target Run facts, check every Run in the
     * session for {@code sessionUniqueVariable}, and write {@code values} in one atomic concurrency boundary.
     * The operation must finish before callers approve or execute any tool.</p>
     *
     * <p>The method is deliberately generic because the store owns concurrency and persistence semantics, not
     * AUTOPILOT policy interpretation. The confirmation service supplies {@code confirmedExecutionClaim} as
     * the Run guard and {@code autopilotAuthorization} as the session-wide unique fact. A false result means
     * that the session or Run no longer exists, the target Run already has a conflicting immutable fact, or a
     * different Run has already established the session-wide fact. Callers must reload durable state and either
     * replay the same Run receipt or fail closed; they must never execute tools after a false result.</p>
     *
     * @param sessionId stable session identifier containing all competing Runs
     * @param runId target Run that will own the newly established facts
     * @param guardVariable target Run's one-time write guard; it must be included in {@code values}
     * @param sessionUniqueVariable variable that may appear in at most one Run in the session
     * @param values immutable low-sensitive facts to write as one unit; they must include both guard variables
     * @return {@code true} only when this call wrote the complete fact set; otherwise {@code false}
     */
    boolean putRunVariablesIfAbsentAndSessionVariableAbsent(String sessionId,
                                                             String runId,
                                                             String guardVariable,
                                                             String sessionUniqueVariable,
                                                             Map<String, Object> values);

    /**
     * 按稳定 ID 重建完整会话聚合。
     *
     * @param sessionId 会话 ID
     * @return 找到时返回包含子事实的完整聚合，否则返回空 Optional
     */
    Optional<AgentSessionRecord> findById(String sessionId);

    /**
     * 按所有者边界查询历史会话。
     *
     * <p>仓储负责把 tenant/project/actor 条件下沉到数据库并限制返回数量；服务层仍需再次执行对象可见性过滤，
     * 形成纵深防御。结果按置顶优先、最近更新时间倒序返回。</p>
     *
     * @param tenantId 租户过滤条件
     * @param projectId 项目过滤条件
     * @param actorId 用户过滤条件
     * @param archived true 查询已归档，false 查询未归档
     * @param limit 最大返回数，实现还应应用平台硬上限
     * @return 满足边界的会话聚合列表
     */
    List<AgentSessionRecord> list(Long tenantId,
                                  Long projectId,
                                  String actorId,
                                  boolean archived,
                                  int limit);

    /**
     * 为旧调用方保留的未归档会话查询快捷方法。
     *
     * <p>默认最多返回 100 条，防止旧接口在迁移 JDBC 后意外执行无上限全表加载。</p>
     */
    default List<AgentSessionRecord> list(Long tenantId, Long projectId, String actorId) {
        return list(tenantId, projectId, actorId, false, 100);
    }
}
