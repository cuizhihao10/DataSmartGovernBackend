/**
 * @Author : Cui
 * @Date: 2026/05/07 21:28
 * @Description DataSmart Govern Backend - SyncTemplateQueryCriteria.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

/**
 * 同步模板查询条件。
 */
public record SyncTemplateQueryCriteria(
        Long tenantId,
        Long projectId,
        Long workspaceId,
        Long sourceDatasourceId,
        Long targetDatasourceId,
        String syncMode,
        Boolean enabled,
        Long current,
        Long size
) {
}
