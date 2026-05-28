/**
 * @Author : Cui
 * @Date: 2026/05/28 18:00
 * @Description DataSmart Govern Backend - AgentToolExecutionEventOutboxDiagnostics.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.event.outbox;

import java.time.Instant;

/**
 * Agent 工具执行事件 outbox 诊断摘要。
 *
 * <p>诊断对象不返回 payload 明细，只返回状态计数和容量上限，用于运维判断：
 * outbox 是否堆积、是否有大量 BLOCKED 事件、是否即将触发内存裁剪、后续 dispatcher 是否需要扩容。</p>
 */
public record AgentToolExecutionEventOutboxDiagnostics(
        boolean inMemoryStore,
        int totalRecords,
        int pendingRecords,
        int publishingRecords,
        int publishedRecords,
        int failedRecords,
        int blockedRecords,
        int maxEventsPerRun,
        int maxTotalRecords,
        Instant generatedAt
) {
}
