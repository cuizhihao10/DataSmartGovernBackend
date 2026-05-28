/**
 * @Author : Cui
 * @Date: 2026/05/08 22:42
 * @Description DataSmart Govern Backend - SyncIncidentOperationResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

/**
 * 同步事故处理结果。
 *
 * @param incidentId 事故记录 ID
 * @param syncTaskId 关联同步任务 ID
 * @param incidentStatus 操作后的事故状态
 * @param operation 本次动作
 * @param assignedOperatorId 当前负责人 ID
 * @param message 面向调用方的说明
 */
public record SyncIncidentOperationResult(
        Long incidentId,
        Long syncTaskId,
        String incidentStatus,
        String operation,
        Long assignedOperatorId,
        String message
) {
}
