/**
 * @Author : Cui
 * @Date: 2026/07/01 11:08
 * @Description DataSmartGovernBackend - QualityReportLowSensitiveExportContent.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.controller.dto;

/**
 * 质量报告低敏导出内容。
 *
 * <p>该对象不是普通 JSON 响应，而是 Controller 组装 CSV 下载响应时使用的服务层结果。
 * 它只承载已经完成脱敏/裁剪后的 CSV 文本，不承载异常样本原文、观测值、recordIdentifier、
 * samplePayload、SQL、连接串或任何凭据信息。</p>
 *
 * @param fileName 建议下载文件名。
 * @param contentType HTTP Content-Type。
 * @param body CSV 文本正文。
 * @param anomalyRows 导出的异常行数。
 * @param truncated 是否因为 maxAnomalyRows 上限发生截断。
 */
public record QualityReportLowSensitiveExportContent(
        String fileName,
        String contentType,
        String body,
        int anomalyRows,
        boolean truncated) {
}
