/**
 * @Author : Cui
 * @Date: 2026/07/09 18:50
 * @Description DataSmart Govern Backend - SyncExecutionLogQueryCriteria.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

/**
 * 执行日志查询条件。
 *
 * <p>该 DTO 服务于 {@code GET /sync-tasks/{taskId}/executions/{executionId}/logs}。
 * 路径中的 taskId/executionId 负责确定父子归属和权限边界，query 参数只做轻量筛选与分页：</p>
 * <p>1. {@code logStage}：按阶段筛选，例如只看 REMOTE_BATCH 或 CHECKPOINT；</p>
 * <p>2. {@code logLevel}：按级别筛选，例如只看 WARN/ERROR；</p>
 * <p>3. {@code current/size}：分页参数，避免一次加载超长执行的全部日志。</p>
 *
 * <p>为什么不允许按 message 模糊搜索：执行日志是运维事实，不是全文日志系统。
 * 如果需要全文检索，后续应接入 observability/日志平台，而不是让业务库承接高成本 LIKE 查询。</p>
 */
public record SyncExecutionLogQueryCriteria(
        Long syncTaskId,
        Long executionId,
        String logStage,
        String logLevel,
        Long current,
        Long size
) {
}
