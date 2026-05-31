/**
 * @Author : Cui
 * @Date: 2026/05/31 23:35
 * @Description DataSmart Govern Backend - DataSyncAgentExecuteResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

/**
 * data-sync 内部 Agent 执行入口响应 DTO。
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
