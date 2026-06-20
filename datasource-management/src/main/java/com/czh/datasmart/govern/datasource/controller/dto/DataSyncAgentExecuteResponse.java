/**
 * @Author : Cui
 * @Date: 2026/06/20 23:18
 * @Description DataSmart Govern Backend - DataSyncAgentExecuteResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.controller.dto;

/**
 * datasource-management 内部 Agent 执行入口的低敏响应。
 *
 * <p>这个响应会被 task-management 的 data-sync worker outbox 记录为 receipt。
 * 因此它只返回可公开排障的控制面事实：命令 ID、同步任务 ID、执行记录 ID、状态和是否重复。
 * 它不返回 SQL、连接串、样本数据、工具实参、模型输出、prompt 或内部执行器 endpoint。</p>
 *
 * @param commandId 上游稳定命令 ID。
 * @param syncTaskId datasource-management 创建或复用的同步任务 ID。
 * @param syncExecutionId 当前阶段仅入队，不一定已经创建 execution，因此允许为空。
 * @param state 同步任务当前主状态，例如 QUEUED。
 * @param created 本次请求是否新建了同步任务。
 * @param queued 本次请求是否确认任务已进入待执行队列。
 * @param duplicate 本次请求是否命中了 datasource 侧幂等 receipt。
 * @param message 面向调用方的低敏结果说明。
 */
public record DataSyncAgentExecuteResponse(
        String commandId,
        Long syncTaskId,
        Long syncExecutionId,
        String state,
        boolean created,
        boolean queued,
        boolean duplicate,
        String message
) {
}
