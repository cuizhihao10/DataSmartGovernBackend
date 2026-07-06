/**
 * @Author : Cui
 * @Date: 2026/07/06 23:35
 * @Description DataSmart Govern Backend - SyncObjectExecutionView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import java.time.LocalDateTime;

/**
 * 对象级执行账本的低敏运维视图。
 *
 * <p>该视图面向“已经通过 gateway + permission-admin + data-sync 数据范围校验”的用户或运维人员。
 * 它会展示对象名，因为排查多表迁移失败时必须知道是哪张表或哪个对象失败；但它仍然不展示以下高敏内容：</p>
 * <p>1. 不展示字段映射 JSON 原文，避免泄露字段级业务语义或敏感列名集合；</p>
 * <p>2. 不展示 where/filter 原文，避免泄露业务筛选条件、客户标识或时间窗口；</p>
 * <p>3. 不展示 SQL、连接串、账号、密码、token、样本行和 checkpoint 原始值；</p>
 * <p>4. 错误信息仅保留对象账本中已经截断过的低敏摘要，真实堆栈或下游响应体不进入该视图。</p>
 *
 * <p>为什么不直接返回实体：实体属于持久化模型，未来可能增加内部字段。使用 View 可以稳定 API 契约，
 * 也能把“哪些字段允许对外展示”的策略显式写在控制层附近。</p>
 */
public record SyncObjectExecutionView(
        Long id,
        Long tenantId,
        Long projectId,
        Long workspaceId,
        Long syncTaskId,
        Long executionId,
        Long templateId,
        Integer objectOrdinal,
        String sourceSchemaName,
        String sourceObjectName,
        String targetSchemaName,
        String targetObjectName,
        String objectState,
        Integer attemptCount,
        Integer maxAttemptCount,
        Long recordsRead,
        Long recordsWritten,
        Long failedRecordCount,
        String lastErrorType,
        String lastErrorCode,
        String lastErrorMessage,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        String payloadPolicy,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
}
