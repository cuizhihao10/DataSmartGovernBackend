/**
 * @Author : Cui
 * @Date: 2026/07/07 23:04
 * @Description DataSmart Govern Backend - SyncTaskScheduleDispatchResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import java.util.List;

/**
 * 定时同步任务调度触发结果。
 *
 * <p>结果只包含任务 ID、execution ID、计数和问题码，不返回 scheduleConfig 原文、字段映射、过滤条件、
 * SQL、连接信息或任何源端/目标端样本数据。这样即使 internal 入口被运营台展示，也不会扩大敏感信息暴露面。</p>
 *
 * @param scannedTaskCount 本轮扫描到的到期任务数量。
 * @param dispatchedTaskCount 成功创建至少一条 SCHEDULED execution 的任务数量。
 * @param createdExecutionCount 本轮创建的 execution 总数。
 * @param skippedByConcurrencyCount 因上一轮仍在运行且不允许并发而跳过的任务数。
 * @param skippedByMisfirePolicyCount 因 misfirePolicy=SKIP 只推进游标、不创建 execution 的任务数。
 * @param skippedByRaceCount 因多实例抢占失败或状态已变化而跳过的任务数。
 * @param skippedByInvalidConfigCount 因历史任务调度配置非法而跳过的任务数。
 * @param dryRun 是否为只预览、不写库。
 * @param executionIds 本轮创建的 execution ID 列表。
 * @param issueCodes 低敏问题码集合。
 * @param message 面向调用方的摘要说明。
 */
public record SyncTaskScheduleDispatchResult(
        int scannedTaskCount,
        int dispatchedTaskCount,
        int createdExecutionCount,
        int skippedByConcurrencyCount,
        int skippedByMisfirePolicyCount,
        int skippedByRaceCount,
        int skippedByInvalidConfigCount,
        boolean dryRun,
        List<Long> executionIds,
        List<String> issueCodes,
        String message
) {
}
