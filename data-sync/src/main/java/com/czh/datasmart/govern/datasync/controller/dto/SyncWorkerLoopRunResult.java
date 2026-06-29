/**
 * @Author : Cui
 * @Date: 2026/06/29 13:04
 * @Description DataSmart Govern Backend - SyncWorkerLoopRunResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import java.util.List;

/**
 * worker loop 单轮执行摘要。
 *
 * <p>该结果是 data-sync 从“控制面状态机”走向“可执行 worker 闭环”的运维可见契约。
 * 它适合被运维接口、定时调度器、Prometheus 指标、后续 task-management receipt 或 Agent 诊断面板消费。
 * 为了遵守低敏边界，本对象不会返回 run-once 请求体、远端响应体、字段映射正文、SQL、checkpoint 值、连接凭据或样本数据。</p>
 *
 * @param workerId 本轮使用的执行器 ID
 * @param tenantId 本轮租户过滤条件；为空表示平台级扫描
 * @param maxExecutions 本轮最多尝试处理的 execution 数量
 * @param claimAttempts 实际发起 claim 的次数
 * @param claimedCount 成功认领的 execution 数量
 * @param dispatchedCount 已经调用 datasource-management run-once 的数量
 * @param completedCount 已经回写 complete 的数量
 * @param failedCount 已经回写 fail 或被 fail-closed 的数量
 * @param queueDrained 是否在本轮发现队列已空；true 表示 claim 返回无可执行记录
 * @param executions 单条 execution 的低敏处理摘要
 * @param issueCodes 本轮聚合问题码，用于快速判断是否存在结构性阻断
 * @param message 面向运维人员的低敏说明
 * @param payloadPolicy 载荷策略，提醒调用方不要把该对象扩展为敏感执行详情
 */
public record SyncWorkerLoopRunResult(String workerId,
                                      Long tenantId,
                                      int maxExecutions,
                                      int claimAttempts,
                                      int claimedCount,
                                      int dispatchedCount,
                                      int completedCount,
                                      int failedCount,
                                      boolean queueDrained,
                                      List<SyncWorkerLoopExecutionResult> executions,
                                      List<String> issueCodes,
                                      String message,
                                      String payloadPolicy) {

    /**
     * 固定载荷策略。
     */
    public static final String PAYLOAD_POLICY =
            "LOW_SENSITIVE_WORKER_LOOP_RESULT_NO_ROWS_NO_SQL_NO_CREDENTIALS_NO_CHECKPOINT_VALUE";

    /**
     * 防御性复制集合，避免调用方拿到结果后继续修改内部列表。
     */
    public SyncWorkerLoopRunResult {
        executions = executions == null ? List.of() : List.copyOf(executions);
        issueCodes = issueCodes == null ? List.of() : List.copyOf(issueCodes);
    }
}
