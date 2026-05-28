/**
 * @Author : Cui
 * @Date: 2026/05/07 21:28
 * @Description DataSmart Govern Backend - SyncTaskQueryCriteria.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

/**
 * 同步任务查询条件。
 */
public record SyncTaskQueryCriteria(
        Long tenantId,
        Long projectId,
        Long workspaceId,
        Long templateId,
        Long ownerId,
        String currentState,
        String approvalState,
        String triggerType,
        Long current,
        Long size
) {
}
