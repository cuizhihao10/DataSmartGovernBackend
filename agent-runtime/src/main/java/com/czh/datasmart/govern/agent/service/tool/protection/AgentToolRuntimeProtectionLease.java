/**
 * @Author : Cui
 * @Date: 2026/06/04 00:01
 * @Description DataSmart Govern Backend - AgentToolRuntimeProtectionLease.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.tool.protection;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent 工具运行时保护租约。
 *
 * <p>工具执行是一个有生命周期的动作：进入执行前需要占用容量，执行结束后必须释放容量。
 * 如果只在执行前做一次 `inspect`，并发计数就无法反映真实 in-flight 状态；
 * 如果只在 finally 里手写 decrement，则不同执行入口很容易遗漏释放逻辑。</p>
 *
 * <p>因此这里使用 lease 模型：
 * 1. `AgentToolRuntimeProtectionService.beginExecution(...)` 校验通过后创建租约；
 * 2. 调用方执行具体工具；
 * 3. 成功时调用 `recordSuccess()`，失败时调用 `recordFailure(...)`；
 * 4. 最终无论成功失败都调用 `close()` 释放 in-flight 计数。</p>
 *
 * <p>lease 本身不直接暴露计数器实现，避免执行服务绕过保护服务修改状态。
 * 这也是后续从 JVM 内存计数迁移到 Redis/DB 原子计数时保持调用方稳定的关键。</p>
 */
public final class AgentToolRuntimeProtectionLease implements AutoCloseable {

    private final Runnable releaseAction;
    private final Runnable successAction;
    private final FailureRecorder failureRecorder;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean outcomeRecorded = new AtomicBoolean(false);

    AgentToolRuntimeProtectionLease(Runnable releaseAction,
                                    Runnable successAction,
                                    FailureRecorder failureRecorder) {
        this.releaseAction = releaseAction == null ? () -> { } : releaseAction;
        this.successAction = successAction == null ? () -> { } : successAction;
        this.failureRecorder = failureRecorder == null ? (errorCode, message) -> { } : failureRecorder;
    }

    /**
     * 记录工具执行成功。
     *
     * <p>成功会清零对应 targetService 的连续失败计数。
     * 多次调用只会生效一次，避免执行框架和适配器重复上报导致状态抖动。</p>
     */
    public void recordSuccess() {
        if (outcomeRecorded.compareAndSet(false, true)) {
            successAction.run();
        }
    }

    /**
     * 记录工具执行失败。
     *
     * <p>失败可能会推动连续失败计数增长，并在达到阈值后打开熔断。
     * errorCode 应尽量使用低基数错误码，例如 `TOOL_ADAPTER_EXCEPTION`、`DEPENDENCY_TIMEOUT`，
     * 不要把下游原始异常全文当作错误码传入，否则后续指标会出现高基数问题。</p>
     *
     * @param errorCode 低基数错误码。
     * @param message 失败说明，仅用于本地策略判断，不会由 lease 主动写日志或暴露。
     */
    public void recordFailure(String errorCode, String message) {
        if (outcomeRecorded.compareAndSet(false, true)) {
            failureRecorder.record(errorCode, message);
        }
    }

    /**
     * 释放执行容量。
     *
     * <p>close 必须可幂等，因为真实执行路径可能在 try/catch/finally 中多处保护性调用。
     * 幂等释放能避免计数被重复扣减成负数。</p>
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            releaseAction.run();
        }
    }

    @FunctionalInterface
    interface FailureRecorder {

        void record(String errorCode, String message);
    }
}
