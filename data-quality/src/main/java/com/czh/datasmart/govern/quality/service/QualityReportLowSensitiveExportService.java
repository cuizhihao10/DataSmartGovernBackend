/**
 * @Author : Cui
 * @Date: 2026/07/01 11:09
 * @Description DataSmartGovernBackend - QualityReportLowSensitiveExportService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.czh.datasmart.govern.quality.controller.dto.QualityReportLowSensitiveExportContent;
import com.czh.datasmart.govern.quality.entity.QualityAnomalyDetail;
import com.czh.datasmart.govern.quality.entity.QualityCheckReport;
import com.czh.datasmart.govern.quality.mapper.QualityAnomalyDetailMapper;
import com.czh.datasmart.govern.quality.mapper.QualityCheckReportMapper;
import com.czh.datasmart.govern.quality.service.support.QualityProjectScopeSupport;
import com.czh.datasmart.govern.quality.service.support.QualityProjectVisibility;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * 质量报告低敏 CSV 导出服务。
 *
 * <p>README 中明确要求质量报告支持导出，但商业级导出不能简单把异常明细表整行 dump 出去。
 * `quality_anomaly_detail` 中的 observedValue、recordIdentifier、samplePayload 都可能包含客户业务数据、
 * 主键、手机号、邮箱、地址、交易号或其他敏感内容。如果直接同步导出，会把“排障功能”变成“数据泄露入口”。</p>
 *
 * <p>因此本服务先实现一条保守但可用的低敏导出链路：
 * 1. 导出报告摘要、规则快照、状态、样本量、异常数量、通过率等低敏运营字段；
 * 2. 导出异常维度字段：异常类型、字段名、严重级别、目标对象；
 * 3. 明确不导出 observedValue、recordIdentifier、samplePayload、recommendation、notes 等可能含敏字段；
 * 4. 用 maxAnomalyRows 限制同步导出规模，未来大批量高敏导出必须走异步任务、审批、脱敏和审计。</p>
 */
@Service
@RequiredArgsConstructor
public class QualityReportLowSensitiveExportService {

    private static final int DEFAULT_MAX_ANOMALY_ROWS = 500;
    private static final int HARD_MAX_ANOMALY_ROWS = 2000;
    private static final String CONTENT_TYPE = "text/csv;charset=UTF-8";
    private static final DateTimeFormatter FILE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final QualityCheckReportMapper qualityCheckReportMapper;
    private final QualityAnomalyDetailMapper qualityAnomalyDetailMapper;
    private final QualityProjectScopeSupport qualityProjectScopeSupport;

    /**
     * 导出指定质量报告的低敏 CSV。
     *
     * @param reportId 质量报告 ID。
     * @param visibility 当前请求的数据范围。
     * @param maxAnomalyRows 调用方期望导出的异常明细最大行数，服务层会裁剪到安全范围。
     * @return 低敏 CSV 导出内容。
     */
    public QualityReportLowSensitiveExportContent exportReport(
            Long reportId,
            QualityProjectVisibility visibility,
            Integer maxAnomalyRows) {
        QualityCheckReport report = qualityCheckReportMapper.selectById(reportId);
        if (report == null) {
            throw new NoSuchElementException("质量检测报告不存在: " + reportId);
        }
        qualityProjectScopeSupport.validateProjectReadable(report.getProjectId(), visibility, "质量报告");

        int safeLimit = safeMaxRows(maxAnomalyRows);
        List<QualityAnomalyDetail> anomalies = qualityAnomalyDetailMapper.selectList(
                new LambdaQueryWrapper<QualityAnomalyDetail>()
                        .eq(QualityAnomalyDetail::getReportId, reportId)
                        .orderByDesc(QualityAnomalyDetail::getId)
                        .last("LIMIT " + (safeLimit + 1)));
        boolean truncated = anomalies.size() > safeLimit;
        List<QualityAnomalyDetail> exportedAnomalies = truncated ? anomalies.subList(0, safeLimit) : anomalies;

        String csv = buildCsv(report, exportedAnomalies, truncated);
        return new QualityReportLowSensitiveExportContent(
                buildFileName(report),
                CONTENT_TYPE,
                csv,
                exportedAnomalies.size(),
                truncated);
    }

    /**
     * 构建 CSV 正文。
     *
     * <p>为了让用户打开文件后马上理解安全边界，CSV 第一段先写导出说明，再写报告摘要，
     * 最后写低敏异常明细。这样即使文件被单独转发，接收方也能看到哪些字段被刻意隐藏。</p>
     */
    private String buildCsv(QualityCheckReport report, List<QualityAnomalyDetail> anomalies, boolean truncated) {
        StringBuilder builder = new StringBuilder();
        appendRow(builder, "section", "field", "value");
        appendRow(builder, "export-policy", "sensitivity", "LOW_SENSITIVE_ONLY");
        appendRow(builder, "export-policy", "suppressedFields",
                "recordIdentifier,observedValue,expectedValue,samplePayload,recommendation,notes");
        appendRow(builder, "export-policy", "truncated", String.valueOf(truncated));

        appendRow(builder, "report", "reportId", report.getId());
        appendRow(builder, "report", "tenantId", report.getTenantId());
        appendRow(builder, "report", "projectId", report.getProjectId());
        appendRow(builder, "report", "workspaceId", report.getWorkspaceId());
        appendRow(builder, "report", "ruleId", report.getRuleId());
        appendRow(builder, "report", "ruleVersion", report.getRuleVersion());
        appendRow(builder, "report", "ruleName", report.getRuleName());
        appendRow(builder, "report", "ruleType", report.getRuleType());
        appendRow(builder, "report", "targetObject", report.getTargetObject());
        appendRow(builder, "report", "severity", report.getSeverity());
        appendRow(builder, "report", "checkStatus", report.getCheckStatus());
        appendRow(builder, "report", "sampleSize", report.getSampleSize());
        appendRow(builder, "report", "exceptionCount", report.getExceptionCount());
        appendRow(builder, "report", "passRate", report.getPassRate());
        appendRow(builder, "report", "triggerType", report.getTriggerType());
        appendRow(builder, "report", "createTime", report.getCreateTime());

        appendRow(builder, "");
        appendRow(builder, "section", "anomalyId", "anomalyType", "fieldName", "severity", "targetObject");
        for (QualityAnomalyDetail anomaly : anomalies) {
            appendRow(builder,
                    "anomaly",
                    anomaly.getId(),
                    anomaly.getAnomalyType(),
                    anomaly.getFieldName(),
                    anomaly.getSeverity(),
                    anomaly.getTargetObject());
        }
        return builder.toString();
    }

    private String buildFileName(QualityCheckReport report) {
        String time = report.getCreateTime() == null ? "unknown-time" : FILE_TIME_FORMATTER.format(report.getCreateTime());
        return "quality-report-" + report.getId() + "-" + time + "-low-sensitive.csv";
    }

    private int safeMaxRows(Integer maxAnomalyRows) {
        if (maxAnomalyRows == null || maxAnomalyRows < 1) {
            return DEFAULT_MAX_ANOMALY_ROWS;
        }
        return Math.min(maxAnomalyRows, HARD_MAX_ANOMALY_ROWS);
    }

    private void appendRow(StringBuilder builder, Object... values) {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(escapeCsv(values[i]));
        }
        builder.append('\n');
    }

    /**
     * CSV 字段转义。
     *
     * <p>只要字段包含逗号、引号、换行，就必须整体加双引号，并把内部双引号转成两个双引号。
     * 这是 CSV 的基础规范。如果不处理，规则名称或目标对象中包含逗号时会导致列错位。</p>
     */
    private String escapeCsv(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value);
        boolean needsQuote = text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r");
        String escaped = text.replace("\"", "\"\"");
        return needsQuote ? "\"" + escaped + "\"" : escaped;
    }
}
