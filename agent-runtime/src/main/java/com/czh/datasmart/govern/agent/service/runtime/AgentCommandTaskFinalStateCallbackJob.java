/**
 * @Author : Cui
 * @Date: 2026/08/19 00:00
 * @Description DataSmart Govern Backend - AgentCommandTaskFinalStateCallbackJob.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import java.time.Instant;

/**
 * 一条可恢复的最终态 callback job。
 *
 * <p>该记录只持有 Java receipt 的低敏关联字段、下游幂等键、可见性租约和失败机器码；绝不保存命令正文、
 * stdout/stderr、payload、SQL、prompt、token 或 task-management 内部地址。{@code sourceReceiptIdentityKey}
 * 是本地幂等根：同一条 receipt 无论被扫描多少次，最多只创建一个 callback job。</p>
 */
public record AgentCommandTaskFinalStateCallbackJob(
        String jobId,
        String sourceReceiptIdentityKey,
        Long sourceReplaySequence,
        String commandId,
        Long taskId,
        Long taskRunId,
        String executorId,
        String auditId,
        String tenantId,
        String projectId,
        String actorId,
        String runId,
        String sessionId,
        String toolCode,
        String callbackStatus,
        String callbackIdempotencyKey,
        boolean requiresManualCompensation,
        AgentCommandTaskFinalStateCallbackJobStatus status,
        int attemptCount,
        Instant nextAttemptAt,
        String leaseOwner,
        String leaseToken,
        Instant leaseExpiresAt,
        String failureCode,
        Instant callbackDeliveredAt,
        Instant createdAt,
        Instant updatedAt
) {

    /**
     * 统一把空状态和负尝试次数收口为安全值，避免异常数据库/测试输入破坏状态机。
     */
    public AgentCommandTaskFinalStateCallbackJob {
        status = status == null ? AgentCommandTaskFinalStateCallbackJobStatus.PENDING : status;
        attemptCount = Math.max(0, attemptCount);
        createdAt = createdAt == null ? Instant.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }

    /**
     * 返回当前 worker 成功领取后的新记录，并递增尝试次数作为有限重试的持久依据。
     *
     * @param workerId 当前 worker 的低敏身份。
     * @param token 本次领取的内部 fencing token，不得写入日志或外部响应。
     * @param expiresAt 可见性租约到期时间。
     * @param now 当前 Java 控制面时间。
     * @return 带 DISPATCHING 状态的新不可变记录。
     */
    public AgentCommandTaskFinalStateCallbackJob withClaim(String workerId,
                                                           String token,
                                                           Instant expiresAt,
                                                           Instant now) {
        return new AgentCommandTaskFinalStateCallbackJob(
                jobId, sourceReceiptIdentityKey, sourceReplaySequence, commandId, taskId, taskRunId, executorId, auditId,
                tenantId, projectId, actorId, runId, sessionId, toolCode, callbackStatus, callbackIdempotencyKey,
                requiresManualCompensation, AgentCommandTaskFinalStateCallbackJobStatus.DISPATCHING, attemptCount + 1,
                null, workerId, token, expiresAt, failureCode, callbackDeliveredAt, createdAt, now
        );
    }

    /**
     * 返回续期后的记录，保持同一 lease owner/token，避免旧 worker 覆盖新持有者的状态。
     *
     * @param expiresAt 新的可见性租约到期时间。
     * @param now 当前 Java 控制面时间。
     * @return 仅刷新租约和更新时间的新记录。
     */
    public AgentCommandTaskFinalStateCallbackJob withHeartbeat(Instant expiresAt, Instant now) {
        return new AgentCommandTaskFinalStateCallbackJob(
                jobId, sourceReceiptIdentityKey, sourceReplaySequence, commandId, taskId, taskRunId, executorId, auditId,
                tenantId, projectId, actorId, runId, sessionId, toolCode, callbackStatus, callbackIdempotencyKey,
                requiresManualCompensation, status, attemptCount, nextAttemptAt, leaseOwner, leaseToken, expiresAt,
                failureCode, callbackDeliveredAt, createdAt, now
        );
    }

    /**
     * 返回退避等待的新记录，并主动清空领取信息，允许到期后由任一健康实例重新领取。
     *
     * @param nextAttemptAt 下一次允许尝试的时间。
     * @param failureCode 低敏失败机器码。
     * @param now 当前 Java 控制面时间。
     * @return RETRY_WAIT 状态的新记录。
     */
    public AgentCommandTaskFinalStateCallbackJob withRetry(Instant nextAttemptAt,
                                                           String failureCode,
                                                           Instant now) {
        return terminalLike(AgentCommandTaskFinalStateCallbackJobStatus.RETRY_WAIT, nextAttemptAt,
                failureCode, null, now);
    }

    /**
     * 返回最终交付、人工补偿或死信状态的新记录，并清空旧租约防止过期 worker 再次写回。
     *
     * @param targetStatus 目标状态，只允许使用不再自动领取的终止状态。
     * @param failureCode 低敏机器码；正常交付可以为 null。
     * @param deliveredAt 下游已接受 callback 的时间；未送达时为 null。
     * @param now 当前 Java 控制面时间。
     * @return 清空 lease 的新不可变记录。
     */
    public AgentCommandTaskFinalStateCallbackJob withTerminal(
            AgentCommandTaskFinalStateCallbackJobStatus targetStatus,
            String failureCode,
            Instant deliveredAt,
            Instant now) {
        return terminalLike(targetStatus, null, failureCode, deliveredAt, now);
    }

    /**
     * 校验当前调用方仍拥有本次领取的 fence，防止过期 worker 覆盖新 worker 的进度。
     *
     * @param workerId 当前 worker 身份。
     * @param token 当前领取 token。
     * @return true 表示 job 仍处于 DISPATCHING 且 owner/token 完全匹配。
     */
    public boolean matchesLease(String workerId, String token) {
        return status == AgentCommandTaskFinalStateCallbackJobStatus.DISPATCHING
                && leaseOwner != null && leaseOwner.equals(workerId)
                && leaseToken != null && leaseToken.equals(token);
    }

    /**
     * 校验调用方仍持有未过期的 lease fence。
     *
     * <p>仅比较 owner/token 还不够：旧 worker 可能在网络调用阻塞期间跨过租约到期时间。
     * 数据库已经允许另一实例接管后，旧 worker 不得再续租或写入终态。因此所有带外部副作用的
     * 状态更新都应使用这个带时间参数的重载；时间缺失时 fail-closed。</p>
     *
     * @param workerId 当前 worker 身份
     * @param token 当前领取 token
     * @param now 当前控制面时间
     * @return true 表示 owner/token 匹配且租约严格晚于当前时间
     */
    public boolean matchesLease(String workerId, String token, Instant now) {
        return matchesLease(workerId, token)
                && now != null
                && leaseExpiresAt != null
                && leaseExpiresAt.isAfter(now);
    }

    /**
     * 统一构造“清空租约后的状态变更”，避免每个完成分支遗漏 fence 字段。
     */
    private AgentCommandTaskFinalStateCallbackJob terminalLike(
            AgentCommandTaskFinalStateCallbackJobStatus targetStatus,
            Instant nextAttemptAt,
            String failureCode,
            Instant deliveredAt,
            Instant now) {
        return new AgentCommandTaskFinalStateCallbackJob(
                jobId, sourceReceiptIdentityKey, sourceReplaySequence, commandId, taskId, taskRunId, executorId, auditId,
                tenantId, projectId, actorId, runId, sessionId, toolCode, callbackStatus, callbackIdempotencyKey,
                requiresManualCompensation, targetStatus, attemptCount, nextAttemptAt, null, null, null,
                failureCode, deliveredAt, createdAt, now
        );
    }
}
