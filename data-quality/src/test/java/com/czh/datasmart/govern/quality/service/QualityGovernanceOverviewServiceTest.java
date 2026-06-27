/**
 * @Author : Cui
 * @Date: 2026/06/27 21:02
 * @Description DataSmart Govern Backend - QualityGovernanceOverviewServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.service;

import com.czh.datasmart.govern.quality.controller.dto.QualityAnomalyAggregationItem;
import com.czh.datasmart.govern.quality.controller.dto.QualityGovernanceOverviewResponse;
import com.czh.datasmart.govern.quality.mapper.QualityAnomalyDetailMapper;
import com.czh.datasmart.govern.quality.mapper.QualityCheckExecutionMapper;
import com.czh.datasmart.govern.quality.mapper.QualityCheckReportMapper;
import com.czh.datasmart.govern.quality.mapper.QualityRuleMapper;
import com.czh.datasmart.govern.quality.service.support.QualityGovernanceOverviewCalculator;
import com.czh.datasmart.govern.quality.service.support.QualityProjectVisibility;
import com.czh.datasmart.govern.quality.support.QualityCheckExecutionState;
import com.czh.datasmart.govern.quality.support.QualityCheckStatus;
import com.czh.datasmart.govern.quality.support.QualityGovernanceRiskLevel;
import com.czh.datasmart.govern.quality.support.QualityRuleStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 数据质量治理总览服务测试。
 *
 * <p>这组测试固定的是商业化质量大盘最重要的两个约束：</p>
 *
 * <p>1. 在有权限范围内，服务能把规则、报告、执行和异常事实聚合成可解释的评分、风险等级和下一步建议；</p>
 * <p>2. 在 PROJECT 空授权场景下，服务必须直接返回空视图，不能为了“看起来有数据”而误查全库。</p>
 *
 * <p>测试同样关注低敏原则：总览只能暴露聚合值和建议，不能把 SQL、样本载荷、连接串、凭据或错误正文
 * 带到大盘响应里。</p>
 */
class QualityGovernanceOverviewServiceTest {

    @Test
    void overviewShouldAggregateGovernancePostureAndReturnLowSensitiveSuggestions() {
        QualityRuleMapper ruleMapper = mock(QualityRuleMapper.class);
        QualityCheckReportMapper reportMapper = mock(QualityCheckReportMapper.class);
        QualityCheckExecutionMapper executionMapper = mock(QualityCheckExecutionMapper.class);
        QualityAnomalyDetailMapper anomalyDetailMapper = mock(QualityAnomalyDetailMapper.class);
        QualityGovernanceOverviewService service = service(
                ruleMapper, reportMapper, executionMapper, anomalyDetailMapper);

        /*
         * 规则计数调用顺序：
         * 1. 生命周期 DRAFT/ACTIVE/INACTIVE/ARCHIVED；
         * 2. 规则类型 COMPLETENESS/UNIQUENESS/VALIDITY/CONSISTENCY/ACCURACY；
         * 3. 严重级别 HIGH/MEDIUM/LOW；
         * 4. 目标类型 GENERIC/RELATIONAL_TABLE/RELATIONAL_FIELD/KAFKA_TOPIC/FILE_OBJECT/API_ENDPOINT。
         */
        when(ruleMapper.selectCount(any()))
                .thenReturn(1L, 4L, 1L, 0L,
                        2L, 1L, 1L, 1L, 1L,
                        3L, 2L, 1L,
                        1L, 2L, 2L, 1L, 0L, 0L);
        when(reportMapper.selectCount(any()))
                .thenReturn(8L, 2L);
        when(executionMapper.selectCount(any()))
                .thenReturn(1L, 9L, 1L);
        when(anomalyDetailMapper.selectCount(any()))
                .thenReturn(6L);
        when(anomalyDetailMapper.aggregateAnomalies(
                anyString(), eq(10L), any(), any(), any(), any(), any(), any(),
                any(), any(), anyInt(), any(), any(), anyList(), anyBoolean()))
                .thenReturn(List.of(aggregation("email", 4L)), List.of(aggregation("NULL_VALUE", 5L)));

        QualityGovernanceOverviewResponse response = service.overview(
                10L,
                999,
                999,
                new QualityProjectVisibility(101L, 201L, List.of(101L), true)
        );

        assertThat(response.getWindowDays()).isEqualTo(365);
        assertThat(response.getTopLimit()).isEqualTo(50);
        assertThat(response.getProjectScopeEnforced()).isTrue();
        assertThat(response.getHasVisibleProjects()).isTrue();
        assertThat(response.getRuleStatusCounts()).containsEntry(QualityRuleStatus.ACTIVE, 4L);
        assertThat(response.getReportStatusCounts())
                .containsEntry(QualityCheckStatus.PASSED, 8L)
                .containsEntry(QualityCheckStatus.FAILED, 2L);
        assertThat(response.getExecutionStateCounts())
                .containsEntry(QualityCheckExecutionState.RUNNING, 1L)
                .containsEntry(QualityCheckExecutionState.FAILED, 1L);
        assertThat(response.getRecentReportCount()).isEqualTo(10L);
        assertThat(response.getFailedReportCount()).isEqualTo(2L);
        assertThat(response.getPassRate()).isEqualByComparingTo(new BigDecimal("0.8000"));
        assertThat(response.getAnomalyCount()).isEqualTo(6L);
        assertThat(response.getQualityScore()).isEqualTo(70);
        assertThat(response.getRiskLevel()).isEqualTo(QualityGovernanceRiskLevel.RISK);
        assertThat(response.getTopAnomalyFields()).singleElement()
                .extracting(QualityAnomalyAggregationItem::getAggregateKey)
                .isEqualTo("email");
        assertThat(response.getNextActions())
                .anyMatch(item -> item.contains("失败质量报告"))
                .anyMatch(item -> item.contains("异常样本"))
                .anyMatch(item -> item.contains("执行失败"));

        String serialized = response.toString();
        assertThat(serialized)
                .doesNotContain("SELECT *")
                .doesNotContain("jdbc:mysql")
                .doesNotContain("password")
                .doesNotContain("samplePayload")
                .doesNotContain("http://internal");
        verify(anomalyDetailMapper, times(2)).aggregateAnomalies(
                anyString(), eq(10L), any(), any(), any(), any(), any(), any(),
                any(), any(), anyInt(), any(), any(), anyList(), anyBoolean());
    }

    @Test
    void overviewShouldReturnEmptyViewWithoutDatabaseQueryWhenProjectScopeHasNoVisibleProjects() {
        QualityRuleMapper ruleMapper = mock(QualityRuleMapper.class);
        QualityCheckReportMapper reportMapper = mock(QualityCheckReportMapper.class);
        QualityCheckExecutionMapper executionMapper = mock(QualityCheckExecutionMapper.class);
        QualityAnomalyDetailMapper anomalyDetailMapper = mock(QualityAnomalyDetailMapper.class);
        QualityGovernanceOverviewService service = service(
                ruleMapper, reportMapper, executionMapper, anomalyDetailMapper);

        QualityGovernanceOverviewResponse response = service.overview(
                null,
                null,
                null,
                new QualityProjectVisibility(null, null, List.of(), true)
        );

        assertThat(response.getHasVisibleProjects()).isFalse();
        assertThat(response.getRiskLevel()).isEqualTo(QualityGovernanceRiskLevel.NO_VISIBLE_PROJECT);
        assertThat(response.getQualityScore()).isZero();
        assertThat(response.getRuleStatusCounts().values()).containsOnly(0L);
        assertThat(response.getReportStatusCounts().values()).containsOnly(0L);
        assertThat(response.getTopAnomalyFields()).isEmpty();
        assertThat(response.getNextActions()).singleElement().asString().contains("授权项目");
        verify(ruleMapper, never()).selectCount(any());
        verify(reportMapper, never()).selectCount(any());
        verify(executionMapper, never()).selectCount(any());
        verify(anomalyDetailMapper, never()).selectCount(any());
    }

    private QualityGovernanceOverviewService service(QualityRuleMapper ruleMapper,
                                                     QualityCheckReportMapper reportMapper,
                                                     QualityCheckExecutionMapper executionMapper,
                                                     QualityAnomalyDetailMapper anomalyDetailMapper) {
        return new QualityGovernanceOverviewService(
                ruleMapper,
                reportMapper,
                executionMapper,
                anomalyDetailMapper,
                new QualityGovernanceOverviewCalculator()
        );
    }

    private QualityAnomalyAggregationItem aggregation(String key, Long count) {
        QualityAnomalyAggregationItem item = new QualityAnomalyAggregationItem();
        item.setAggregateKey(key);
        item.setAnomalyCount(count);
        item.setLatestCreateTime(LocalDateTime.of(2026, 6, 27, 20, 0));
        return item;
    }
}
