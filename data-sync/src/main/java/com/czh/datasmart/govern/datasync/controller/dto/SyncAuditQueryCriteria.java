/**
 * @Author : Cui
 * @Date: 2026/05/07 21:39
 * @Description DataSmart Govern Backend - SyncAuditQueryCriteria.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

/**
 * 数据同步审计查询条件。
 */
public record SyncAuditQueryCriteria(
        Long syncTaskId,
        Long executionId,
        String actionType,
        Long actorId,
        Long current,
        Long size
) {
}
