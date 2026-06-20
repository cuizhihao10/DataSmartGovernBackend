/**
 * @Author : Cui
 * @Date: 2026/06/20 16:51
 * @Description DataSmart Govern Backend - DataSyncWorkerCommandOutboxView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.datasync;

import java.time.LocalDateTime;

/**
 * DataSync worker command outbox 的低敏展示视图。
 *
 * <p>这个对象专门用于调度器领取结果、内部诊断接口和后续管理台展示。它刻意不直接复用
 * {@code DataSyncWorkerCommandOutbox} 实体，是因为实体里包含 {@code payloadJson} 和
 * {@code lastError} 等“内部持久化字段”。这些字段虽然在写入时已经按低敏策略处理过，但真实商业系统里
 * 仍然要避免把内部 payload、错误正文、SQL 片段、工具参数正文或模型输出沿着 API 原样扩散出去。</p>
 *
 * <p>字段设计原则：</p>
 * <p>1. 返回可定位问题的 ID、状态、次数、时间和下游 receipt 引用；</p>
 * <p>2. 不返回 payload_json、工具实参、SQL、连接串、样本数据、prompt、模型输出和内部服务地址；</p>
 * <p>3. 错误信息只返回“是否存在错误摘要”和“错误可见性策略”，具体错误正文留在受控后台或审计链路中；</p>
 * <p>4. 该视图可以安全进入运维诊断、任务时间线、低敏 runtime event 或管理台列表。</p>
 *
 * @param databaseId 数据库自增主键，仅用于内部排障排序和分页定位，不作为跨服务业务 ID。
 * @param outboxId outbox 业务记录 ID，便于从 commandId 反查本地命令账本。
 * @param commandId Agent command ID，表示一次上游工具动作意图。
 * @param idempotencyKey 跨服务幂等键，用于定位重复命令是否被复用。
 * @param taskId task-management 主任务 ID。
 * @param agentRunId Agent Runtime run ID，用于把 outbox 与 Agent 执行链路关联。
 * @param agentSessionId Agent 会话 ID，可用于按会话排障。
 * @param auditId Agent 工具审计 ID，可用于对接审计视图。
 * @param toolCode 工具编码，例如 data-sync.execute。
 * @param targetService 目标服务编码，当前主要是 data-sync/datasource-management 同步入口。
 * @param operation 命令操作类型，例如 DATA_SYNC_EXECUTE。
 * @param tenantId 租户隔离 ID。
 * @param projectId 项目隔离 ID。
 * @param workspaceId 工作空间隔离 ID。
 * @param templateId 兼容旧任务模板 ID。
 * @param syncTemplateId data-sync 同步模板 ID。
 * @param status outbox 状态，描述跨服务命令投递生命周期。
 * @param attemptCount 投递尝试次数。
 * @param payloadSizeBytes payload 字节数，仅用于容量诊断，不返回 payload 正文。
 * @param payloadTruncated payload 是否被截断或阻断。
 * @param nextRetryAt DEFERRED 状态下一次允许重试的时间。
 * @param dispatchedAt 最近一次开始投递的时间。
 * @param receiptId 下游 worker receipt ID。
 * @param syncTaskId 下游 data-sync 任务 ID。
 * @param syncExecutionId 下游 data-sync execution ID。
 * @param sideEffectStarted 是否已经越过“调用下游”的副作用边界。
 * @param sideEffectExecuted 下游是否确认接收或执行了副作用。
 * @param hasLastError 是否存在错误摘要。
 * @param errorVisibilityPolicy 错误可见性策略说明，替代错误正文直接外露。
 * @param createTime outbox 创建时间。
 * @param updateTime outbox 最近更新时间。
 */
public record DataSyncWorkerCommandOutboxView(
        Long databaseId,
        String outboxId,
        String commandId,
        String idempotencyKey,
        Long taskId,
        String agentRunId,
        String agentSessionId,
        String auditId,
        String toolCode,
        String targetService,
        String operation,
        Long tenantId,
        Long projectId,
        Long workspaceId,
        Long templateId,
        Long syncTemplateId,
        String status,
        Integer attemptCount,
        Integer payloadSizeBytes,
        Boolean payloadTruncated,
        LocalDateTime nextRetryAt,
        LocalDateTime dispatchedAt,
        String receiptId,
        Long syncTaskId,
        Long syncExecutionId,
        Boolean sideEffectStarted,
        Boolean sideEffectExecuted,
        boolean hasLastError,
        String errorVisibilityPolicy,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
}
