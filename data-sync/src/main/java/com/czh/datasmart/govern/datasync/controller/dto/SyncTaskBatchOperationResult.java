/**
 * @Author : Cui
 * @Date: 2026/07/07 20:00
 * @Description DataSmart Govern Backend - SyncTaskBatchOperationResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 同步任务批量操作汇总结果。
 *
 * <p>批量接口在商业系统里通常不是“要么全成、要么全失败”的黑盒动作。
 * 例如批量下线 50 个任务时，其中 40 个可能已经成功下线，8 个因为仍在运行而失败，2 个因为权限或 ID 错误失败。
 * 这个结果对象同时提供汇总计数和逐条明细，方便前端、审计台和 Agent 都能解释本次批量操作。</p>
 */
@Data
public class SyncTaskBatchOperationResult {

    /**
     * 批量动作类型。
     *
     * <p>例如 MANUAL_DISPATCH、OFFLINE、RECYCLE、HARD_DELETE。
     * 该字段用于前端提示、审计检索和 Agent 复盘。</p>
     */
    private String operationType;

    /**
     * 批量动作总体状态。
     *
     * <p>COMPLETED 表示全部成功；PARTIALLY_COMPLETED 表示部分成功部分失败；
     * FAILED 表示没有成功项；STOPPED_ON_ERROR 表示调用方关闭 continueOnError 后遇到首个失败并停止。</p>
     */
    private String status;

    private int totalCount;

    private int successCount;

    private int failedCount;

    private int skippedCount;

    private Boolean continueOnError;

    private List<SyncTaskBatchItemResult> items = new ArrayList<>();

    public static SyncTaskBatchOperationResult start(String operationType, int totalCount, boolean continueOnError) {
        SyncTaskBatchOperationResult result = new SyncTaskBatchOperationResult();
        result.setOperationType(operationType);
        result.setTotalCount(totalCount);
        result.setContinueOnError(continueOnError);
        result.setStatus("RUNNING");
        return result;
    }

    public void addSuccess(SyncTaskBatchItemResult item) {
        items.add(item);
        successCount++;
    }

    public void addFailure(SyncTaskBatchItemResult item) {
        items.add(item);
        failedCount++;
    }

    public void addSkipped(SyncTaskBatchItemResult item) {
        items.add(item);
        skippedCount++;
    }

    /**
     * 根据明细计数收口总体状态。
     *
     * <p>该方法放在 DTO 内部，是为了让批量 support 不需要散落多处状态拼装规则。
     * 后续如果产品上要新增 WARNING、PARTIAL_WITH_SKIPPED 等状态，也可以集中修改。</p>
     */
    public void finalizeStatus() {
        if (failedCount == 0 && skippedCount == 0) {
            status = "COMPLETED";
        } else if (successCount > 0 && failedCount > 0) {
            status = Boolean.FALSE.equals(continueOnError) ? "STOPPED_ON_ERROR" : "PARTIALLY_COMPLETED";
        } else if (successCount > 0) {
            status = "PARTIALLY_COMPLETED";
        } else {
            status = Boolean.FALSE.equals(continueOnError) && skippedCount > 0 ? "STOPPED_ON_ERROR" : "FAILED";
        }
    }
}
