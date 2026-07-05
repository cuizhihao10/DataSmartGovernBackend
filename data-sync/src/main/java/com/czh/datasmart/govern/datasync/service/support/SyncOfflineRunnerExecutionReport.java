/**
 * @Author : Cui
 * @Date: 2026/07/05 14:26
 * @Description DataSmart Govern Backend - SyncOfflineRunnerExecutionReport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import java.util.List;

/**
 * DataX-style 离线 Runner 的执行报告合同。
 *
 * <p>离线同步真正进入生产后，用户、Agent 和运维人员最关心的不只是“有没有成功”，还包括读取多少、
 * 写入多少、失败多少、卡在哪个阶段、是否可重试、checkpoint 是否推进、是否产生失败样本和是否需要人工介入。
 * 这些信息如果没有提前形成合同，执行器很容易只返回一段不可检索的日志，后续就无法做告警、审计、续跑和运营报表。</p>
 *
 * <p>本对象只定义“报告应包含哪些低敏类别”，不承载真实执行结果。真实执行结果应该在 Runner 完成后写入
 * data_sync_execution、checkpoint、error_sample、audit_record 或后续观测事件表中。</p>
 *
 * @param reportPolicy 报告总策略，说明 Runner 必须输出低敏结构化报告。
 * @param progressEventPolicy 进度事件策略。用于约束心跳、批次进度、分片进度不能携带行数据。
 * @param checkpointReportPolicy checkpoint 报告策略。需要水位时只允许返回 checkpointRef/digest，不返回原始值。
 * @param errorSamplePolicy 失败样本策略。商用环境默认只允许 digest/分类/计数，不允许把业务行原文打入普通报告。
 * @param metricsPolicy 指标策略。强调低基数标签，避免 Prometheus 被 tableName、SQL hash、字段名等高基数标签打爆。
 * @param publishProgressRequired 是否要求 Runner 运行中发布进度。
 * @param publishFinalReportRequired 是否要求 Runner 结束后发布最终报告。
 * @param failedSampleDigestOnly 失败样本是否只能以 digest/引用方式出现。
 * @param lowCardinalityMetricsRequired 是否要求低基数指标。
 * @param requiredReportFields 最终报告必须包含的字段类别。
 * @param payloadPolicy 低敏载荷策略说明。
 */
public record SyncOfflineRunnerExecutionReport(
        String reportPolicy,
        String progressEventPolicy,
        String checkpointReportPolicy,
        String errorSamplePolicy,
        String metricsPolicy,
        boolean publishProgressRequired,
        boolean publishFinalReportRequired,
        boolean failedSampleDigestOnly,
        boolean lowCardinalityMetricsRequired,
        List<String> requiredReportFields,
        String payloadPolicy
) {
}
