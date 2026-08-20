/**
 * @Author : Cui
 * @Date: 2026/08/19 00:00
 * @Description DataSmart Govern Backend - AgentCommandTaskFinalStateCallbackJobStore.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 最终态 callback durable job 的仓储端口。
 *
 * <p>端口把“从 Java receipt 事实发现候选、按 source receipt 幂等入队、用 visibility lease 领取、记录历史”
 * 收口到一处。生产实现必须使用 PostgreSQL 条件更新/行锁；内存实现仅用于单元测试，不允许由默认 Spring 路径注册。</p>
 */
public interface AgentCommandTaskFinalStateCallbackJobStore {

    /**
     * 发现还没有 callback job 的真实终态 receipt 候选。
     *
     * <p>实现只能从 {@code agent_tool_action_worker_receipt_index} 这类 Java 已物化事实读取候选，不能从
     * AUTO_APPROVED、command outbox PUBLISHED 或发布状态推断执行成功。</p>
     *
     * @param limit 本轮最多返回的候选数量。
     * @return 尚未创建 durable job 的低敏 receipt 候选。
     */
    List<AgentToolActionWorkerReceiptIndexRecord> listUnregisteredTerminalReceiptCandidates(int limit);

    /**
     * 按 source receipt identity 幂等创建 job，并同步写入第一条历史记录。
     *
     * @param job 要保存的低敏 job。
     * @param eventType 创建事件码。
     * @param reasonCode 可选低敏原因码。
     * @param now 当前 Java 控制面时间。
     * @return true 表示本次首次创建；false 表示已有同源 receipt job。
     */
    boolean append(AgentCommandTaskFinalStateCallbackJob job, String eventType, String reasonCode, Instant now);

    /**
     * 原子领取当前可见或租约已超时的 job。
     *
     * @param workerId 当前 worker 低敏身份。
     * @param leaseToken 本轮内部 fence token，不得写入响应或日志。
     * @param now 当前 Java 控制面时间。
     * @param leaseExpiresAt 新的可见性到期时间。
     * @param limit 最多领取数量。
     * @return 只有当前实例成功领取的 job。
     */
    List<AgentCommandTaskFinalStateCallbackJob> claimDue(String workerId,
                                                         String leaseToken,
                                                         Instant now,
                                                         Instant leaseExpiresAt,
                                                         int limit);

    /**
     * 续期当前 job 的可见性 lease。
     *
     * @return true 表示 owner/token 仍是当前持有者并已成功续期；false 表示已经被其他实例接管。
     */
    boolean heartbeat(String jobId, String workerId, String leaseToken, Instant leaseExpiresAt, Instant now);

    /**
     * 标记下游已接受 callback，或将已送达但需人工处理的 job 标记为补偿待办。
     */
    boolean markDelivered(String jobId,
                          String workerId,
                          String leaseToken,
                          boolean requiresManualCompensation,
                          Instant now);

    /**
     * 标记旧 source receipt 已被更高 Java replay sequence 覆盖，避免旧事实在新事实到达后仍写入下游。
     */
    boolean markSuperseded(String jobId,
                           String workerId,
                           String leaseToken,
                           String reasonCode,
                           Instant now);

    /**
     * 把下游暂时不可用的 job 放入带退避的重试队列。
     */
    boolean markRetry(String jobId,
                      String workerId,
                      String leaseToken,
                      String failureCode,
                      Instant nextAttemptAt,
                      Instant now);

    /**
     * 停止自动尝试并记录人工补偿原因，例如事实已变化或下游拒绝当前 run/executor。
     */
    boolean markCompensationRequired(String jobId,
                                     String workerId,
                                     String leaseToken,
                                     String reasonCode,
                                     boolean callbackDelivered,
                                     Instant now);

    /**
     * 将超过最大尝试次数的 job 移入死信，保留历史供补偿台排障。
     */
    boolean markDeadLetter(String jobId,
                           String workerId,
                           String leaseToken,
                           String reasonCode,
                           Instant now);

    /** 查询同一 source receipt 对应的当前 job，用于幂等测试和运维诊断。 */
    Optional<AgentCommandTaskFinalStateCallbackJob> findBySourceReceiptIdentityKey(String sourceReceiptIdentityKey);

    /** 查询同一 source receipt 的低敏状态历史，按发生时间升序返回。 */
    List<AgentCommandTaskFinalStateCallbackHistoryRecord> historyFor(String sourceReceiptIdentityKey);
}
