/**
 * @Author : Cui
 * @Date: 2026/08/11 18:30
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryTriggerOutboxState.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.support;

/**
 * Autopilot 恢复触发事件的可靠投递状态。
 *
 * <p>PENDING/RETRY_WAIT 可被认领，DISPATCHING 表示某实例正在发送，DELIVERED 表示 Kafka
 * 已确认接收，DEAD_LETTER 表示达到最大尝试次数并停止自动重试。</p>
 */
public enum SyncAutopilotRecoveryTriggerOutboxState {
    /** Durable row is ready for its first conditional dispatch claim. */
    PENDING,
    /** One instance owns a time-bounded claim and is awaiting the broker result. */
    DISPATCHING,
    /** A failed delivery is waiting until its bounded backoff expires. */
    RETRY_WAIT,
    /** Kafka acknowledged the event; later scheduler passes must not send it again. */
    DELIVERED,
    /** Retry budget was exhausted; automatic delivery stops until an explicit operational action occurs. */
    DEAD_LETTER
}
