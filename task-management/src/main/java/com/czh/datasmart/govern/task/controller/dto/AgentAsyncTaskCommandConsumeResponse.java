/**
 * @Author : Cui
 * @Date: 2026/05/31 16:42
 * @Description DataSmart Govern Backend - AgentAsyncTaskCommandConsumeResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.controller.dto;

/**
 * Agent 异步工具命令消费回执。
 *
 * @param commandId 命令 ID。
 * @param idempotencyKey 跨服务幂等键。
 * @param consumeState Inbox 消费状态，不等同于任务执行状态。
 * @param duplicate 是否属于重复投递。
 * @param taskCreated 是否已经成功创建任务。
 * @param taskId 已创建任务 ID；尚未创建时为空。
 * @param message 面向联调、审计和运维人员的说明。
 */
public record AgentAsyncTaskCommandConsumeResponse(
        String commandId,
        String idempotencyKey,
        String consumeState,
        Boolean duplicate,
        Boolean taskCreated,
        Long taskId,
        String message
) {
}
