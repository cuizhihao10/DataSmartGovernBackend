/**
 * @Author : Cui
 * @Date: 2026/07/01 11:12
 * @Description DataSmartGovernBackend - QualityReportLowSensitiveExportServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.service;

import com.czh.datasmart.govern.quality.controller.dto.QualityReportLowSensitiveExportContent;
import com.czh.datasmart.govern.quality.entity.QualityAnomalyDetail;
import com.czh.datasmart.govern.quality.entity.QualityCheckReport;
import com.czh.datasmart.govern.quality.mapper.QualityAnomalyDetailMapper;
import com.czh.datasmart.govern.quality.mapper.QualityCheckReportMapper;
import com.czh.datasmart.govern.quality.service.support.QualityProjectScopeSupport;
import com.czh.datasmart.govern.quality.service.support.QualityProjectVisibility;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 低敏质量报告导出测试。
 *
 * <p>这类测试的重点不是 CSV 好不好看，而是验证导出边界：
 * 样本载荷、实际观测值、主键定位、处理建议等可能包含客户数据的字段绝不能进入低敏导出文件。</p>
 */
class QualityReportLowSensitiveExportServiceTest {

    @Test
    void shouldExportLowSensitiveCsvWithoutSampleValues() {
        QualityCheckReportMapper reportMapper = mock(QualityCheckReportMapper.class);
        QualityAnomalyDetailMapper anomalyMapper = mock(QualityAnomalyDetailMapper.class);
        QualityReportLowSensitiveExportService service = new QualityReportLowSensitiveExportService(
                reportMapper,
                anomalyMapper,
                new QualityProjectScopeSupport());

        when(reportMapper.selectById(1001L)).thenReturn(report());
        when(anomalyMapper.selectList(any())).thenReturn(List.of(anomaly()));

        QualityReportLowSensitiveExportContent content = service.exportReport(
                1001L,
                new QualityProjectVisibility(null, null, List.of(101L), true),
                10);

        assertThat(content.fileName()).contains("quality-report-1001");
        assertThat(content.contentType()).isEqualTo("text/csv;charset=UTF-8");
        assertThat(content.body()).contains("LOW_SENSITIVE_ONLY");
        assertThat(content.body()).contains("NULL_VALUE");
        assertThat(content.body()).contains("customer.email");

        assertThat(content.body()).doesNotContain("pk-001");
        assertThat(content.body()).doesNotContain("secret@example.com");
        assertThat(content.body()).doesNotContain("{\"email\":\"secret@example.com\"}");
        assertThat(content.body()).doesNotContain("请回源修复 secret@example.com");
    }

    @Test
    void shouldRejectUnauthorizedProjectExport() {
        QualityCheckReportMapper reportMapper = mock(QualityCheckReportMapper.class);
        QualityAnomalyDetailMapper anomalyMapper = mock(QualityAnomalyDetailMapper.class);
        QualityReportLowSensitiveExportService service = new QualityReportLowSensitiveExportService(
                reportMapper,
                anomalyMapper,
                new QualityProjectScopeSupport());

        when(reportMapper.selectById(1001L)).thenReturn(report());

        assertThatThrownBy(() -> service.exportReport(
                1001L,
                new QualityProjectVisibility(null, null, List.of(202L), true),
                10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未授权项目");
    }

    private QualityCheckReport report() {
        QualityCheckReport report = new QualityCheckReport();
        report.setId(1001L);
        report.setTenantId(10L);
        report.setProjectId(101L);
        report.setWorkspaceId(301L);
        report.setRuleId(501L);
        report.setRuleVersion(3);
        report.setRuleName("客户邮箱完整性");
        report.setRuleType("COMPLETENESS");
        report.setTargetObject("ods.customer.email");
        report.setSeverity("HIGH");
        report.setMeasuredValue(new BigDecimal("0.9200"));
        report.setExpectedValue(new BigDecimal("0.9900"));
        report.setCheckStatus("FAILED");
        report.setSampleSize(1000);
        report.setExceptionCount(80);
        report.setPassRate(new BigDecimal("0.9200"));
        report.setTriggerType("TASK_TRIGGERED");
        report.setCreateTime(LocalDateTime.of(2026, 7, 1, 11, 12));
        return report;
    }

    private QualityAnomalyDetail anomaly() {
        QualityAnomalyDetail anomaly = new QualityAnomalyDetail();
        anomaly.setId(9001L);
        anomaly.setReportId(1001L);
        anomaly.setRuleId(501L);
        anomaly.setProjectId(101L);
        anomaly.setTargetObject("ods.customer.email");
        anomaly.setAnomalyType("NULL_VALUE");
        anomaly.setFieldName("customer.email");
        anomaly.setSeverity("HIGH");
        anomaly.setRecordIdentifier("pk-001");
        anomaly.setObservedValue("secret@example.com");
        anomaly.setSamplePayload("{\"email\":\"secret@example.com\"}");
        anomaly.setRecommendation("请回源修复 secret@example.com");
        return anomaly;
    }
}
