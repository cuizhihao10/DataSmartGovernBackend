/**
 * @Author : Cui
 * @Date: 2026/07/09 22:34
 * @Description DataSmart Govern Backend - SyncExecutionPolicyQueryCriteria.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

/**
 * 执行策略列表查询条件。
 *
 * <p>管理员执行策略页会按作用域、连接器、数据源、任务和启用状态筛选策略。这里保持查询条件
 * 低敏且结构化，不允许通过任意 SQL 或表达式查询策略表。</p>
 */
public record SyncExecutionPolicyQueryCriteria(
        Long tenantId,
        Long projectId,
        String scopeType,
        String scopeKey,
        Long datasourceId,
        String connectorType,
        String connectorRole,
        Long syncTaskId,
        Boolean enabled,
        Long current,
        Long size
) {
}
