/**
 * @Author : Cui
 * @Date: 2026/05/08 22:25
 * @Description DataSmart Govern Backend - SyncAttentionOperationResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

/**
 * 人工介入处理结果。
 *
 * @param taskId 同步任务 ID
 * @param taskState 操作后的任务主状态
 * @param attentionRequired 操作后是否仍需要人工介入
 * @param operation 本次执行的运营动作
 * @param executionId 本次动作关联或新建的执行记录 ID
 * @param incidentId 本次动作创建的事故记录 ID，非事故动作为空
 * @param incidentStatus 事故状态，非事故动作为空
 * @param message 面向调用方的结果说明
 */
public record SyncAttentionOperationResult(
        Long taskId,
        String taskState,
        Boolean attentionRequired,
        String operation,
        Long executionId,
        Long incidentId,
        String incidentStatus,
        String message
) {
}
