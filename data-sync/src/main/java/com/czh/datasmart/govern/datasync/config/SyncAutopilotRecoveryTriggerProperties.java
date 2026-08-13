/**
 * @Author : Cui
 * @Date: 2026/08/11 18:30
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryTriggerProperties.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Autopilot 恢复触发 outbox 与 Kafka 投递参数。
 *
 * <p>这些参数只控制可靠投递，不控制模型如何诊断，也不扩大用户授权。恢复循环次数、
 * 风险上限和允许动作始终来自任务上已持久化的用户授权快照。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "datasmart.data-sync.autopilot-recovery-trigger")
public class SyncAutopilotRecoveryTriggerProperties {

    /** 是否允许 data-sync 在执行失败后创建 Autopilot 恢复触发事件。 */
    private boolean enabled = true;

    /** Java Agent Runtime 消费的 Kafka topic。 */
    private String topic = "datasmart.agent.autopilot-recovery-trigger.v1";

    /** outbox 入库后是否立即尝试发送；失败后仍由 scheduler 补偿。 */
    private boolean immediateDispatchEnabled = true;

    /** 是否启用后台 outbox 补偿调度。 */
    private boolean schedulerEnabled = true;

    /** 单轮最多派发的 due outbox 数量。 */
    private int batchSize = 20;

    /** 单条事件最大投递次数，达到后进入 DEAD_LETTER，避免无限重试。 */
    private int maxAttempts = 8;

    /** 首次失败后的退避秒数。 */
    private long baseBackoffSeconds = 15L;

    /** 指数退避的最大秒数。 */
    private long maxBackoffSeconds = 900L;

    /** DISPATCHING 超过该时间视为进程崩溃残留，可被其他实例重新认领。 */
    private long staleDispatchingSeconds = 300L;

    /** 等待 Kafka send future 的最长毫秒数。 */
    private long sendTimeoutMs = 5000L;

    /** 应用启动后首次扫描 outbox 的延迟。 */
    private long initialDelayMs = 30000L;

    /** 两轮 outbox 扫描之间的 fixed delay。 */
    private long fixedDelayMs = 15000L;

    /**
     * Settings for the V23 local compensation journal that replays a sidecar transaction after it failed before
     * the normal trigger outbox could prove completion. These are separate from the Kafka trigger outbox values:
     * the compensation row retries a local Java call, while the trigger outbox retries a broker delivery.
     */
    private SidecarCompensation sidecarCompensation = new SidecarCompensation();

    /**
     * Bounded scheduler and retry controls for the V23 sidecar compensation journal.
     *
     * <p>Every value has a conservative default so a local control-plane outage is retried without sharing the
     * Kafka outbox budget. The top-level {@link #enabled} switch still remains a global kill switch for all
     * Autopilot recovery traffic.</p>
     */
    @Data
    public static class SidecarCompensation {

        /** Whether V23 retry facts may be recorded and replayed when Autopilot is globally enabled. */
        private boolean enabled = true;

        /** Whether the background scheduler may scan and replay due compensation rows. */
        private boolean schedulerEnabled = true;

        /** Maximum number of rows considered during one bounded scheduler pass. */
        private int batchSize = 20;

        /** Maximum persisted replay claims before the row becomes a dead letter. */
        private int maxAttempts = 6;

        /** First retry delay in seconds after a replay-side exception. */
        private long baseBackoffSeconds = 30L;

        /** Upper bound for exponential replay backoff in seconds. */
        private long maxBackoffSeconds = 1800L;

        /** Age in seconds after which a stranded claim can be safely reclaimed by another instance. */
        private long staleDispatchingSeconds = 300L;

        /** Delay after application startup before the first V23 compensation scan. */
        private long initialDelayMs = 45000L;

        /** Delay after a completed V23 compensation pass before the next scan. */
        private long fixedDelayMs = 30000L;
    }
}
