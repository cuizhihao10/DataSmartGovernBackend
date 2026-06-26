/**
 * @Author : Cui
 * @Date: 2026/06/27 02:10
 * @Description DataSmart Govern Backend - SyncExecutionHeartbeatResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import com.czh.datasmart.govern.datasync.entity.SyncExecution;

import java.time.LocalDateTime;

/**
 * 同步执行心跳处理结果。
 *
 * <p>这个 DTO 是执行器心跳协议的“控制面返回值”，它和 {@link SyncExecution} 的职责不同：
 * 1. {@code SyncExecution} 是数据库实体，承载完整执行记录与持久化字段；
 * 2. {@code SyncExecutionHeartbeatResult} 是返回给 worker 的低敏控制协议，只告诉 worker 当前应继续执行还是停止；
 * 3. 这里刻意不返回 checkpointRef、errorSummary、连接配置、SQL、样本数据、凭据、内部端点等字段，避免心跳接口成为敏感信息扩散通道。
 *
 * <p>为什么需要单独协议：
 * 早期 heartbeat 返回完整 execution，worker 只能从 execution_state 间接猜测服务端意图。
 * 在引入 pause/cancel 后，控制台会把 RUNNING execution 改成 PAUSED 或 CANCELLED。
 * 如果心跳仍只抛“状态不是 RUNNING”，worker 很难区分是普通并发冲突、任务暂停，还是任务取消。
 * 因此本结果明确提供 {@code controlAction} 与 {@code shouldContinue}，让执行器可以稳定实现：
 * 1. CONTINUE：租约已续期，继续读取、写入、checkpoint；
 * 2. STOP_FOR_PAUSE：控制台要求暂停，worker 应尽快安全停止，不再写业务结果；
 * 3. STOP_FOR_CANCEL：控制台要求取消，worker 应停止执行并释放本地资源；
 * 4. CONFLICT：当前请求不是合法执行器或状态已经不适合心跳，服务端会通过异常表达，不会返回成功结果。
 *
 * @param executionId 执行记录 ID，worker 用它确认本次响应对应哪条 execution
 * @param syncTaskId 所属同步任务 ID，用于 worker 日志与本地状态索引，不包含任务配置明细
 * @param tenantId 租户 ID，用于多租户 worker 区分执行上下文
 * @param projectId 项目 ID，用于项目级资源隔离、日志归档和后续指标聚合
 * @param workspaceId 工作空间 ID，用于未来 workspace 级别的执行沙箱或资源配额
 * @param executionState 服务端当前 execution 状态，例如 RUNNING、PAUSED、CANCELLED
 * @param executorId 当前持有或曾持有租约的执行器 ID，用于 worker 自校验
 * @param recordsRead 服务端当前记录的已读取行数，只返回计数，不返回任何样本数据
 * @param recordsWritten 服务端当前记录的已写入行数，只返回计数，不返回目标端写入内容
 * @param heartbeatTime 最近一次成功心跳时间；暂停/取消响应通常不会更新该时间
 * @param leaseExpireTime 当前租约过期时间；暂停/取消响应不会延长租约
 * @param leaseExtended 本次请求是否成功延长了租约
 * @param shouldContinue worker 是否应该继续执行当前 execution
 * @param controlAction 面向 worker 的控制动作：CONTINUE、STOP_FOR_PAUSE、STOP_FOR_CANCEL
 * @param message 给 worker 日志和运维台展示的低敏解释，不包含 SQL、凭据、样本、内部 URL 或工具参数
 */
public record SyncExecutionHeartbeatResult(
        Long executionId,
        Long syncTaskId,
        Long tenantId,
        Long projectId,
        Long workspaceId,
        String executionState,
        String executorId,
        Long recordsRead,
        Long recordsWritten,
        LocalDateTime heartbeatTime,
        LocalDateTime leaseExpireTime,
        boolean leaseExtended,
        boolean shouldContinue,
        String controlAction,
        String message
) {

    /**
     * 构造“续租成功、继续执行”的心跳结果。
     *
     * <p>该结果只在数据库已经完成 heartbeatLease 原子更新后返回。
     * 这意味着 worker 不需要再猜测租约是否有效，可以继续处理下一批数据或写 checkpoint。
     */
    public static SyncExecutionHeartbeatResult leaseExtended(SyncExecution execution) {
        return from(execution, true, true, "CONTINUE", "心跳续租成功，执行器可以继续处理当前同步执行");
    }

    /**
     * 构造“控制台已暂停，worker 应停止”的心跳结果。
     *
     * <p>暂停是协作式控制，不是强制 kill。
     * worker 收到该结果后应停止拉取新数据，保留本地可恢复上下文，避免继续写入导致用户以为任务已暂停但数据仍在变化。
     */
    public static SyncExecutionHeartbeatResult stopForPause(SyncExecution execution) {
        return from(execution, false, false, "STOP_FOR_PAUSE", "同步任务已被暂停，执行器应安全停止当前执行并等待后续恢复");
    }

    /**
     * 构造“控制台已取消，worker 应停止”的心跳结果。
     *
     * <p>取消表达的是用户或运营人员已经终止本次业务意图。
     * worker 收到该结果后不应再写 checkpoint、complete 或 fail，而是释放连接、本地缓存和临时文件等资源。
     */
    public static SyncExecutionHeartbeatResult stopForCancel(SyncExecution execution) {
        return from(execution, false, false, "STOP_FOR_CANCEL", "同步任务已被取消，执行器应停止当前执行并释放本地资源");
    }

    /**
     * 从持久化实体映射到低敏心跳协议。
     *
     * <p>这里集中做字段白名单映射，是为了防止后续 {@link SyncExecution} 新增敏感字段时被心跳接口自动暴露。
     * 如果未来确实需要新增返回字段，应在这里逐项评估是否属于 worker 决策所必需、是否会泄漏业务数据。
     */
    private static SyncExecutionHeartbeatResult from(SyncExecution execution,
                                                     boolean leaseExtended,
                                                     boolean shouldContinue,
                                                     String controlAction,
                                                     String message) {
        return new SyncExecutionHeartbeatResult(
                execution.getId(),
                execution.getSyncTaskId(),
                execution.getTenantId(),
                execution.getProjectId(),
                execution.getWorkspaceId(),
                execution.getExecutionState(),
                execution.getExecutorId(),
                safeLong(execution.getRecordsRead()),
                safeLong(execution.getRecordsWritten()),
                execution.getHeartbeatTime(),
                execution.getLeaseExpireTime(),
                leaseExtended,
                shouldContinue,
                controlAction,
                message
        );
    }

    private static Long safeLong(Long value) {
        return value == null ? 0L : value;
    }
}
