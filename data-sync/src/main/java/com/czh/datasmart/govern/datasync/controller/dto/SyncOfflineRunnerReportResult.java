/**
 * @Author : Cui
 * @Date: 2026/07/05 15:00
 * @Description DataSmart Govern Backend - SyncOfflineRunnerReportResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import java.util.List;

/**
 * 专用离线 Runner 报告处理结果。
 *
 * <p>该结果只返回低敏处理结论，帮助 Runner 判断本次回调是否被 data-sync 接收。
 * 它不返回 checkpoint 原始值、错误样本正文、SQL、对象定位、字段映射或内部执行计划。</p>
 *
 * @param accepted 报告是否被接收并处理。
 * @param actionApplied data-sync 对该报告采取的动作，例如 PROGRESS_ACCEPTED、CHECKPOINT_WRITTEN、EXECUTION_COMPLETED。
 * @param executionId 当前 execution ID。
 * @param executionState 处理后的 execution 状态。
 * @param checkpointRef 当前可见 checkpoint 引用；只允许是 checkpoint 表 ID、外部引用或 digest/ref 摘要。
 * @param issueCodes 低敏问题码集合。
 * @param payloadPolicy 固定载荷策略说明。
 */
public record SyncOfflineRunnerReportResult(boolean accepted,
                                            String actionApplied,
                                            Long executionId,
                                            String executionState,
                                            String checkpointRef,
                                            List<String> issueCodes,
                                            String payloadPolicy) {

    /**
     * 固定低敏载荷策略。
     */
    public static final String PAYLOAD_POLICY =
            "LOW_SENSITIVE_OFFLINE_RUNNER_REPORT_RESULT_NO_ROWS_NO_SQL_NO_CREDENTIALS_NO_CHECKPOINT_RAW_VALUE";
}
