/**
 * @Author : Cui
 * @Date: 2026/06/20 21:43
 * @Description DataSmart Govern Backend - DataSyncWorkerCommandDeliveryResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.datasync;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DataSync worker outbox 单条命令投递结果。
 *
 * <p>该结果同时服务两个调用场景：</p>
 * <p>1. Agent 工具适配器需要把投递结果转换成 {@code AgentAsyncToolExecutionResult}；</p>
 * <p>2. 内部 dispatch-batch 路由需要返回一组低敏投递摘要，方便运维确认 PENDING/DEFERRED 是否被成功送达下游。</p>
 *
 * <p>字段安全原则：</p>
 * <p>结果只包含 outboxId、commandId、receiptId、syncTaskId、syncExecutionId、状态和低敏消息摘要；
 * 不包含 payload_json、工具实参、SQL、连接串、凭据、样本数据、prompt、模型输出或内部服务 URL。</p>
 *
 * @param commandId Agent command ID，用于和上游工具动作关联。
 * @param outboxId task-management 本地 outbox ID，用于排障和幂等回放。
 * @param status outbox 当前状态，例如 SUCCEEDED、DEFERRED、FAILED。
 * @param outcome 本次投递动作的低敏结果分类，例如 SUCCEEDED、DEFERRED、FAILED、REUSED_SUCCEEDED。
 * @param success true 表示下游已确认接收并返回成功 receipt。
 * @param retryable true 表示本次失败适合延迟重试，而不是立即判定为永久失败。
 * @param receiptId 下游或本地生成的 receipt ID，成功时用于证明幂等接收。
 * @param syncTaskId datasource-management 返回的同步任务 ID。
 * @param syncExecutionId datasource-management 返回的同步执行 ID，排队阶段可能为空。
 * @param downstreamState 下游返回的低敏状态摘要。
 * @param created 下游是否创建了新的同步任务。
 * @param queued 下游是否已经把同步任务放入队列。
 * @param duplicate 下游是否复用了已有幂等执行结果。
 * @param message 面向内部控制面的低敏说明。
 */
public record DataSyncWorkerCommandDeliveryResult(
        String commandId,
        String outboxId,
        String status,
        String outcome,
        boolean success,
        boolean retryable,
        String receiptId,
        Long syncTaskId,
        Long syncExecutionId,
        String downstreamState,
        Boolean created,
        Boolean queued,
        Boolean duplicate,
        String message
) {

    /**
     * 转换成 Agent 工具执行输出。
     *
     * <p>这里显式白名单输出字段，避免直接把 record 全量序列化后把未来新增字段意外暴露给 Agent Runtime。
     * 输出面向审计 timeline 和任务结果摘要，因此只保留定位与状态字段。</p>
     */
    public Map<String, Object> toAgentOutput() {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("downstreamService", "data-sync");
        output.put("commandId", commandId);
        output.put("dataSyncWorkerOutboxId", outboxId);
        output.put("dataSyncWorkerOutboxStatus", status);
        output.put("dataSyncWorkerDeliveryOutcome", outcome);
        output.put("dataSyncWorkerReceiptId", receiptId);
        output.put("syncTaskId", syncTaskId);
        output.put("syncExecutionId", syncExecutionId);
        output.put("state", downstreamState);
        output.put("created", created);
        output.put("queued", queued);
        output.put("duplicate", duplicate);
        output.put("message", message);
        return output;
    }
}
