/**
 * @Author : Cui
 * @Date: 2026/06/20 16:40
 * @Description DataSmart Govern Backend - DataSyncWorkerReceiptRecordRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.datasync;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DataSync worker receipt 记录请求。
 *
 * <p>receipt 是下游对 outbox 命令的低敏回执。
 * 它只记录机器可读结果和下游引用 ID，不保存 data-sync 执行详情、失败行、SQL、连接信息或业务样本。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DataSyncWorkerReceiptRecordRequest {

    /**
     * outbox 对应的 commandId。
     */
    private String commandId;

    /**
     * 稳定 receipt ID。
     * 同一次下游响应重试回写时应复用该值，避免重复 receipt 污染排障视图。
     */
    private String receiptId;

    /**
     * data-sync 返回的同步任务 ID。
     */
    private Long syncTaskId;

    /**
     * data-sync 返回的同步 execution ID。
     */
    private Long syncExecutionId;

    /**
     * 低敏状态摘要。
     */
    private String downstreamState;

    /**
     * 是否已创建下游同步任务。
     */
    private Boolean created;

    /**
     * 是否已进入下游队列。
     */
    private Boolean queued;

    /**
     * 是否是下游幂等重复响应。
     */
    private Boolean duplicate;

    /**
     * 低敏消息摘要。
     */
    private String message;
}
