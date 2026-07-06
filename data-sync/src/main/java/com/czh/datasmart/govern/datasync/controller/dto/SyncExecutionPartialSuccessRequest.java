/**
 * @Author : Cui
 * @Date: 2026/07/06 21:49
 * @Description DataSmart Govern Backend - SyncExecutionPartialSuccessRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 同步执行“部分成功”请求。
 *
 * <p>部分成功不是简单失败：它表示父 execution 中已经有一部分对象/分片成功落地，另一部分对象/分片失败且需要
 * 后续选择性重试。因此它必须单独建模，不能继续复用 failExecution，否则会丢失“哪些数据已经成功、
 * 是否可以只重跑失败对象”的业务语义。</p>
 */
@Data
public class SyncExecutionPartialSuccessRequest {

    /**
     * 当前持有父 execution 租约的执行器 ID。
     */
    @NotBlank(message = "执行器 ID 不能为空")
    private String executorId;

    /**
     * 父 execution 下所有成功对象累计读取记录数。
     */
    private Long recordsRead;

    /**
     * 父 execution 下所有成功对象累计写入记录数。
     */
    private Long recordsWritten;

    /**
     * 父 execution 下失败对象数或失败记录数的聚合值。
     */
    private Long failedRecordCount;

    /**
     * 低敏错误摘要，说明本次为什么不是完全成功。
     */
    private String errorSummary;

    /**
     * 幂等键。同一个父 execution 的同一次部分成功回写重试时必须复用同一键。
     */
    private String idempotencyKey;
}
