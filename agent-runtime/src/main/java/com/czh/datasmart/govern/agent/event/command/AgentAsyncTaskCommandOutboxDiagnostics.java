/**
 * @Author : Cui
 * @Date: 2026/05/31 17:08
 * @Description DataSmart Govern Backend - AgentAsyncTaskCommandOutboxDiagnostics.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.event.command;

import java.time.Instant;

/**
 * Agent 异步命令 outbox 诊断摘要。
 *
 * @param enabled outbox 是否启用。
 * @param totalRecords 当前记录总数。
 * @param pendingRecords 待投递记录数。
 * @param publishingRecords 投递中记录数。
 * @param publishedRecords 已投递记录数。
 * @param failedRecords 失败待重试记录数。
 * @param blockedRecords 阻断记录数。
 * @param deadLetterRecords 死信记录数，表示自动 dispatcher 不再领取、需要管理员处理。
 * @param ignoredRecords 人工忽略记录数。
 * @param maxCommandsPerRun 单 run 保留上限。
 * @param maxTotalRecords 总保留上限。
 * @param generatedAt 诊断生成时间。
 */
public record AgentAsyncTaskCommandOutboxDiagnostics(
        boolean enabled,
        int totalRecords,
        int pendingRecords,
        int publishingRecords,
        int publishedRecords,
        int failedRecords,
        int blockedRecords,
        int deadLetterRecords,
        int ignoredRecords,
        int maxCommandsPerRun,
        int maxTotalRecords,
        Instant generatedAt
) {
}
