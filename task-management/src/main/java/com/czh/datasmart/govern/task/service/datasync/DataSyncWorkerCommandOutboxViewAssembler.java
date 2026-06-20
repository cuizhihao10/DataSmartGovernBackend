/**
 * @Author : Cui
 * @Date: 2026/06/20 23:35
 * @Description DataSmart Govern Backend - DataSyncWorkerCommandOutboxViewAssembler.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.datasync;

import com.czh.datasmart.govern.task.entity.DataSyncWorkerCommandOutbox;

/**
 * DataSync worker outbox 低敏视图组装器。
 *
 * <p>这个类专门解决一个边界问题：数据库实体 {@link DataSyncWorkerCommandOutbox} 是内部账本对象，
 * 里面包含 payloadJson、lastError 等只应在服务端受控保存的字段；而 claim、diagnostics、recovery
 * 等内部控制面接口返回给调用方时，只应该展示可定位、可排障、低敏的摘要字段。</p>
 *
 * <p>为什么把组装逻辑单独拆出来：</p>
 * <p>1. claim、diagnostics、stale recovery 都需要同一套低敏响应，不应该各自复制一份字段映射；</p>
 * <p>2. 未来如果新增管理台、runtime event 或审计投影，也可以复用同一条“哪些字段允许出站”的白名单；</p>
 * <p>3. 出站白名单集中在这里，能降低某个新接口误把 payload_json、SQL、连接串、工具实参、prompt 或错误正文暴露出去的风险。</p>
 *
 * <p>注意：该类不是通用工具包，而是 task-management/data-sync 子域内的边界适配器。
 * 只有 outbox 控制面需要使用它，避免把模块局部规则扩散成全局公共工具。</p>
 */
final class DataSyncWorkerCommandOutboxViewAssembler {

    /**
     * 数据库中存在 lastError 时，对外只返回这个策略说明，不返回错误正文。
     *
     * <p>lastError 虽然在写入时已经要求低敏化，但真实系统里错误摘要仍可能被上游异常消息污染，
     * 例如包含内部 endpoint、JDBC URL、SQL 片段或连接参数。控制面列表只需要知道“有错误”和“错误正文被隐藏”，
     * 真正的错误排查应进入受控审计后台或日志系统。</p>
     */
    private static final String ERROR_BODY_HIDDEN_POLICY = "ERROR_SUMMARY_BODY_STORED_BUT_NOT_EXPOSED";

    /**
     * 数据库中没有 lastError 时，对外返回的可见性策略。
     */
    private static final String ERROR_BODY_EMPTY_POLICY = "NO_ERROR_SUMMARY";

    private DataSyncWorkerCommandOutboxViewAssembler() {
        throw new UnsupportedOperationException("DataSyncWorkerCommandOutboxViewAssembler 只提供静态组装方法");
    }

    /**
     * 将内部 outbox 实体转换成可安全返回给内部控制面调用方的低敏视图。
     *
     * <p>字段选择原则：</p>
     * <p>1. 保留 outboxId、commandId、idempotencyKey、taskId、tenantId、projectId 等定位字段；</p>
     * <p>2. 保留 status、attemptCount、nextRetryAt、dispatchedAt 等状态流转字段；</p>
     * <p>3. 保留 receiptId、syncTaskId、syncExecutionId 等下游低敏引用，方便串联 task-management 与 datasource-management；</p>
     * <p>4. 不返回 payloadJson、lastError 正文、actorId、traceId 或任何可能携带敏感业务内容的字段。</p>
     *
     * @param outbox 数据库中的 outbox 实体，通常来自 mapper 查询。
     * @return 低敏 outbox 视图，可用于内部 API、诊断面板、恢复结果和后续 runtime event 摘要。
     */
    static DataSyncWorkerCommandOutboxView toView(DataSyncWorkerCommandOutbox outbox) {
        boolean hasLastError = outbox.getLastError() != null && !outbox.getLastError().isBlank();
        return new DataSyncWorkerCommandOutboxView(
                outbox.getId(),
                outbox.getOutboxId(),
                outbox.getCommandId(),
                outbox.getIdempotencyKey(),
                outbox.getTaskId(),
                outbox.getAgentRunId(),
                outbox.getAgentSessionId(),
                outbox.getAuditId(),
                outbox.getToolCode(),
                outbox.getTargetService(),
                outbox.getOperation(),
                outbox.getTenantId(),
                outbox.getProjectId(),
                outbox.getWorkspaceId(),
                outbox.getTemplateId(),
                outbox.getSyncTemplateId(),
                outbox.getStatus(),
                outbox.getAttemptCount(),
                outbox.getPayloadSizeBytes(),
                outbox.getPayloadTruncated(),
                outbox.getNextRetryAt(),
                outbox.getDispatchedAt(),
                outbox.getReceiptId(),
                outbox.getSyncTaskId(),
                outbox.getSyncExecutionId(),
                outbox.getSideEffectStarted(),
                outbox.getSideEffectExecuted(),
                hasLastError,
                hasLastError ? ERROR_BODY_HIDDEN_POLICY : ERROR_BODY_EMPTY_POLICY,
                outbox.getCreateTime(),
                outbox.getUpdateTime()
        );
    }
}
