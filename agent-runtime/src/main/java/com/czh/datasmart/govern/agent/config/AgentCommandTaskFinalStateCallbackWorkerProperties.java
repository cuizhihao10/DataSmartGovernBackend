/**
 * @Author : Cui
 * @Date: 2026/08/19 00:00
 * @Description DataSmart Govern Backend - AgentCommandTaskFinalStateCallbackWorkerProperties.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent command 最终态自动 callback worker 配置。
 *
 * <p>该 worker 会把 Java 已物化的真实 worker receipt 收敛为 task-management 回调。因为该动作会推进任务状态，
 * 所以总开关默认关闭，且运行时还要求 {@code persistence.database-enabled=true}，防止本地 memory 环境误启动
 * 一个没有持久幂等/补偿记录的后台副作用线程。</p>
 */
@Data
@ConfigurationProperties(prefix = "datasmart.agent-runtime.async-task-final-state-callback-worker")
public class AgentCommandTaskFinalStateCallbackWorkerProperties {

    /** 是否启用无人值守最终态 callback；默认 false，必须由部署环境显式打开。 */
    private boolean enabled = false;

    /** 应用启动后的首次扫描延迟，给 JDBC/Flyway 和服务注册留出预热时间，单位毫秒。 */
    private long initialDelayMs = 15000;

    /** 每轮完成后等待多久再扫描，使用 fixedDelay 防止本 JVM 内轮次重叠，单位毫秒。 */
    private long fixedDelayMs = 5000;

    /** 单轮最多发现和领取多少条 callback job，防止故障恢复时压满 task-management。 */
    private int batchSize = 20;

    /** 单条 job 最多自动调用下游多少次；达到上限后转死信和人工补偿。 */
    private int maxAttempts = 5;

    /** 第一次可恢复失败的退避秒数。 */
    private long initialBackoffSeconds = 30;

    /** 指数退避的最大秒数，避免长期故障把重试间隔无限放大。 */
    private long maxBackoffSeconds = 900;

    /** 领取后对其他实例不可见的时间窗口，单位秒。 */
    private long visibilityTimeoutSeconds = 60;

    /** 连接 task-management 的最长等待时间，单位毫秒；连接失败应尽快交给 durable retry。 */
    private long connectTimeoutMs = 1500;

    /** 等待 task-management callback 响应的最长时间，单位毫秒；必须显著短于 visibility lease。 */
    private long readTimeoutMs = 15000;

    /** 写入 lease/history 的低敏 worker 身份；不要配置主机地址、令牌或容器运行时详情。 */
    private String workerId = "agent-runtime-final-state-callback-worker";

    /**
     * 返回受控连接超时，防止异常配置形成零等待或长期占用调度线程。
     */
    public long normalizedConnectTimeoutMs() {
        return Math.max(100L, Math.min(connectTimeoutMs, 10000L));
    }

    /**
     * 返回受控读取超时。callback 是短控制面请求，超过一分钟应由幂等重试接管。
     */
    public long normalizedReadTimeoutMs() {
        return Math.max(500L, Math.min(readTimeoutMs, 60000L));
    }

    /**
     * 计算覆盖一次连接、读取和五秒状态落库余量所需的最小 lease 秒数。
     *
     * <p>该下限同时被 worker 使用，保证 HTTP 客户端不会在租约已经失效后才返回结果。
     * 向上取整避免毫秒截断让超时与租约恰好落在同一时刻。</p>
     */
    public long minimumVisibilityTimeoutSeconds() {
        long requestBudgetMs = normalizedConnectTimeoutMs() + normalizedReadTimeoutMs() + 5000L;
        return Math.max(5L, (requestBudgetMs + 999L) / 1000L);
    }
}
