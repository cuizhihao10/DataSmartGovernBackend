/**
 * @Author : Cui
 * @Date: 2026/05/08 22:40
 * @Description DataSmart Govern Backend - SyncIncidentQueryCriteria.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

/**
 * 同步事故记录查询条件。
 *
 * @param tenantId 租户 ID，普通租户角色只能查询自身租户，平台管理员和服务账号可跨租户
 * @param syncTaskId 同步任务 ID，用于查看某个任务关联的事故历史
 * @param executionId 执行记录 ID，用于定位某次运行触发的事故
 * @param incidentType 事故类型，例如 CONNECTOR_FAILURE、TARGET_THROTTLED
 * @param severity 严重级别，例如 P1、P2、P3、P4
 * @param incidentStatus 事故状态，例如 OPEN、ACKNOWLEDGED、RESOLVED、CLOSED
 * @param operatorId 创建事故的操作者 ID
 * @param assignedOperatorId 当前事故负责人 ID
 * @param current 页码，从 1 开始
 * @param size 每页大小
 */
public record SyncIncidentQueryCriteria(
        Long tenantId,
        Long projectId,
        Long workspaceId,
        Long syncTaskId,
        Long executionId,
        String incidentType,
        String severity,
        String incidentStatus,
        Long operatorId,
        Long assignedOperatorId,
        Long current,
        Long size
) {
}
