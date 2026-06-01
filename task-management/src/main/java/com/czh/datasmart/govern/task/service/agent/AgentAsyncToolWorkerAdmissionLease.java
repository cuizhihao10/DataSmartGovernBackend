/**
 * @Author : Cui
 * @Date: 2026/06/01 22:12
 * @Description DataSmart Govern Backend - AgentAsyncToolWorkerAdmissionLease.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent 异步工具 worker 入场许可。
 *
 * <p>这个对象表达的是“本次 dispatch-once 是否被允许进入认领任务阶段”。它不是业务权限，也不是工具执行结果，
 * 而是 worker 自身的容量保护结果：例如本地并发数已经达到上限、最小调度间隔尚未到达、未来租户级配额不足等。</p>
 *
 * <p>为什么把许可做成 AutoCloseable：
 * worker 一旦通过入场保护，就会占用一个本地并发槽位。无论后续是没有任务、预检失败、工具成功、工具失败，
 * 还是运行时异常，都必须释放这个槽位。使用 try-with-resources 可以把“申请和释放必须成对出现”写进代码结构，
 * 避免未来维护时漏掉 finally 导致 worker 永久认为自己满载。</p>
 */
public final class AgentAsyncToolWorkerAdmissionLease implements AutoCloseable {

    /**
     * 是否成功获得入场许可。
     *
     * <p>false 表示 dispatch-once 不应该继续 claim 任务，因为继续认领只会把任务提前置为 RUNNING，
     * 但当前 worker 又没有能力立即执行它，容易造成租约抖动和不必要的任务状态变化。</p>
     */
    private final boolean acquired;

    /**
     * 机器可读的阻断原因编码。
     *
     * <p>当前主要用于区分 LOCAL_CONCURRENCY_LIMIT、LOCAL_RATE_LIMIT 等本地保护原因。
     * 后续接入租户配额、工具级限流、下游熔断时，可以继续扩展该编码并接入指标告警。</p>
     */
    private final String reasonCode;

    /**
     * 面向日志、接口调用方和运维人员的中文说明。
     */
    private final String message;

    /**
     * 低敏诊断信息。
     *
     * <p>这里只保存容量、等待时间、executorId 等运维可见信息，不能放入 prompt、SQL、token、密码或真实样本数据。</p>
     */
    private final Map<String, Object> diagnostics;

    /**
     * 释放入场许可的回调。
     *
     * <p>成功获得许可时，该回调通常会减少 in-flight 计数；未获得许可时它是空操作。</p>
     */
    private final Runnable releaseCallback;

    /**
     * 防止 close 被重复调用导致并发计数被多减。
     */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private AgentAsyncToolWorkerAdmissionLease(boolean acquired,
                                               String reasonCode,
                                               String message,
                                               Map<String, Object> diagnostics,
                                               Runnable releaseCallback) {
        this.acquired = acquired;
        this.reasonCode = reasonCode;
        this.message = message;
        this.diagnostics = diagnostics == null ? Map.of() : new LinkedHashMap<>(diagnostics);
        this.releaseCallback = releaseCallback == null ? () -> {
        } : releaseCallback;
    }

    public static AgentAsyncToolWorkerAdmissionLease acquired(String message,
                                                              Map<String, Object> diagnostics,
                                                              Runnable releaseCallback) {
        return new AgentAsyncToolWorkerAdmissionLease(true, null, message, diagnostics, releaseCallback);
    }

    public static AgentAsyncToolWorkerAdmissionLease rejected(String reasonCode,
                                                              String message,
                                                              Map<String, Object> diagnostics) {
        return new AgentAsyncToolWorkerAdmissionLease(false, reasonCode, message, diagnostics, null);
    }

    public boolean acquired() {
        return acquired;
    }

    public String reasonCode() {
        return reasonCode;
    }

    public String message() {
        return message;
    }

    public Map<String, Object> diagnostics() {
        return new LinkedHashMap<>(diagnostics);
    }

    @Override
    public void close() {
        if (acquired && closed.compareAndSet(false, true)) {
            releaseCallback.run();
        }
    }
}
