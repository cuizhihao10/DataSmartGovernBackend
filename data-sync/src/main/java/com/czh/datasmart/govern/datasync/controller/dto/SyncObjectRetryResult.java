/**
 * @Author : Cui
 * @Date: 2026/07/06 23:35
 * @Description DataSmart Govern Backend - SyncObjectRetryResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import java.util.List;

/**
 * 失败对象选择性重试结果。
 *
 * <p>该结果只描述“控制面是否已经把失败对象和父 execution 放回可执行队列”，不承诺数据已经搬运完成。
 * 真实同步仍需要 worker 再次认领 execution，并由 fan-out 调度器跳过已成功对象、执行被重置的失败对象。</p>
 */
public record SyncObjectRetryResult(
        Long taskId,
        Long executionId,
        int retryObjectCount,
        String executionState,
        String taskState,
        List<String> issueCodes,
        String message
) {
}
