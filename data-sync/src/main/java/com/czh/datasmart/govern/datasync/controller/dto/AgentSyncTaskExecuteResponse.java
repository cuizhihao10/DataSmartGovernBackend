/**
 * @Author : Cui
 * @Date: 2026/05/31 23:20
 * @Description DataSmart Govern Backend - AgentSyncTaskExecuteResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

/**
 * Agent 触发数据同步任务后的内部响应。
 *
 * @param commandId Agent 异步命令 ID，便于 task-management 把下游结果写回当前任务。
 * @param syncTaskId data-sync 创建或复用的同步任务 ID。
 * @param syncExecutionId data-sync 入队后创建的执行记录 ID。
 * @param state 同步任务当前状态，通常为 QUEUED。
 * @param created 本次请求是否实际新建了同步任务。
 * @param queued 本次请求是否实际把任务推进到执行队列。
 * @param duplicate 本次请求是否命中幂等重复请求。
 * @param message 面向内部调用方和运维人员的结果说明。
 */
public record AgentSyncTaskExecuteResponse(
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
