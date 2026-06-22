/**
 * @Author : Cui
 * @Date: 2026/06/22 20:19
 * @Description DataSmart Govern Backend - QualityExecutionDiagnosticsServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.service;

import com.czh.datasmart.govern.quality.config.TaskManagementIntegrationProperties;
import com.czh.datasmart.govern.quality.controller.dto.QualityExecutionDiagnosticsExecutionView;
import com.czh.datasmart.govern.quality.controller.dto.QualityExecutionDiagnosticsResponse;
import com.czh.datasmart.govern.quality.entity.QualityCheckExecution;
import com.czh.datasmart.govern.quality.mapper.QualityAnomalyDetailMapper;
import com.czh.datasmart.govern.quality.mapper.QualityCheckExecutionMapper;
import com.czh.datasmart.govern.quality.mapper.QualityCheckReportMapper;
import com.czh.datasmart.govern.quality.service.support.QualityProjectVisibility;
import com.czh.datasmart.govern.quality.support.QualityCheckExecutionState;
import com.czh.datasmart.govern.quality.support.QualityCheckStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 质量执行诊断服务测试。
 *
 * <p>这组测试固定的是“运营可见但正文低敏”的诊断契约。真实商业化产品中，诊断接口经常会被接入
 * 管理后台首页、告警机器人、自动巡检任务或客户支持工具。如果它不小心返回 scanPlanSnapshot、
 * message 正文、SQL、连接串或样本载荷，就会把排障接口变成新的敏感数据出口。</p>
 */
class QualityExecutionDiagnosticsServiceTest {

    @Test
    void diagnoseShouldAggregateCountsAndHideSensitiveExecutionBody() {
        QualityCheckExecutionMapper executionMapper = mock(QualityCheckExecutionMapper.class);
        QualityCheckReportMapper reportMapper = mock(QualityCheckReportMapper.class);
        QualityAnomalyDetailMapper anomalyDetailMapper = mock(QualityAnomalyDetailMapper.class);
        QualityExecutionDiagnosticsService service = new QualityExecutionDiagnosticsService(
                executionMapper,
                reportMapper,
                anomalyDetailMapper,
                properties());

        when(executionMapper.selectCount(any()))
                .thenReturn(2L, 7L, 1L);
        when(reportMapper.selectCount(any()))
                .thenReturn(5L, 3L, 2L, 4L, 0L);
        when(anomalyDetailMapper.selectCount(any()))
                .thenReturn(6L);
        when(executionMapper.selectList(any()))
                .thenReturn(List.of(recentExecution()));

        QualityExecutionDiagnosticsResponse response = service.diagnose(
                10L,
                1001L,
                500,
                new QualityProjectVisibility(null, null, List.of(101L, 102L), true)
        );

        assertThat(response.getRecentExecutionLimit()).isEqualTo(100);
        assertThat(response.getProjectScopeEnforced()).isTrue();
        assertThat(response.getExecutionStateCounts())
                .containsEntry(QualityCheckExecutionState.RUNNING, 2L)
                .containsEntry(QualityCheckExecutionState.SUCCESS, 7L)
                .containsEntry(QualityCheckExecutionState.FAILED, 1L);
        assertThat(response.getReportStatusCounts())
                .containsEntry(QualityCheckStatus.PASSED, 5L)
                .containsEntry(QualityCheckStatus.FAILED, 3L);
        assertThat(response.getSeverityCounts())
                .containsEntry("HIGH", 2L)
                .containsEntry("MEDIUM", 4L)
                .containsEntry("LOW", 0L);
        assertThat(response.getAnomalyCount()).isEqualTo(6L);
        assertThat(response.getRuntime().getExecutorCoordinatorEnabled()).isTrue();
        assertThat(response.getWarnings()).anyMatch(item -> item.contains("FAILED"));

        QualityExecutionDiagnosticsExecutionView executionView = response.getRecentExecutions().getFirst();
        assertThat(executionView.getScanPlanSnapshotAvailable()).isTrue();
        assertThat(executionView.getMessageAvailable()).isTrue();

        /*
         * 诊断响应可以告诉调用方“存在正文”，但不能返回正文内容。
         * 这里故意在实体里放入连接串、密码、SQL 和内部 endpoint，确保序列化视图不会把它们带出来。
         */
        String serializedView = response.toString();
        assertThat(serializedView)
                .doesNotContain("jdbc:mysql")
                .doesNotContain("password=secret")
                .doesNotContain("SELECT * FROM ods_order")
                .doesNotContain("http://internal-task-service");
    }

    @Test
    void diagnoseShouldReturnEmptyViewWithoutDatabaseQueryWhenProjectScopeHasNoAuthorizedProjects() {
        QualityCheckExecutionMapper executionMapper = mock(QualityCheckExecutionMapper.class);
        QualityCheckReportMapper reportMapper = mock(QualityCheckReportMapper.class);
        QualityAnomalyDetailMapper anomalyDetailMapper = mock(QualityAnomalyDetailMapper.class);
        QualityExecutionDiagnosticsService service = new QualityExecutionDiagnosticsService(
                executionMapper,
                reportMapper,
                anomalyDetailMapper,
                properties());

        QualityExecutionDiagnosticsResponse response = service.diagnose(
                null,
                null,
                null,
                new QualityProjectVisibility(null, null, List.of(), true)
        );

        assertThat(response.getHasVisibleProjects()).isFalse();
        assertThat(response.getExecutionStateCounts().values()).containsOnly(0L);
        assertThat(response.getReportStatusCounts().values()).containsOnly(0L);
        assertThat(response.getSeverityCounts().values()).containsOnly(0L);
        assertThat(response.getAnomalyCount()).isZero();
        assertThat(response.getRecentExecutions()).isEmpty();
        assertThat(response.getWarnings()).singleElement().asString().contains("授权项目");
        verify(executionMapper, never()).selectCount(any());
        verify(reportMapper, never()).selectCount(any());
        verify(anomalyDetailMapper, never()).selectCount(any());
        verify(executionMapper, never()).selectList(any());
    }

    /**
     * 构造测试用执行器配置。
     */
    private TaskManagementIntegrationProperties properties() {
        TaskManagementIntegrationProperties properties = new TaskManagementIntegrationProperties();
        properties.setEnabled(true);
        properties.setExecutorCoordinatorEnabled(true);
        properties.setExecutorSchedulerEnabled(false);
        properties.setExecutorConcurrencyGuardEnabled(true);
        properties.setExecutorId("quality-worker-test");
        properties.setTaskType("DATA_QUALITY_SCAN");
        properties.setExecutorSchedulerInitialDelaySeconds(5);
        properties.setExecutorSchedulerFixedDelaySeconds(10);
        properties.setExecutorSchedulerMaxRunsPerTick(3);
        properties.setExecutorMaxConcurrentRunsGlobal(4);
        properties.setExecutorMaxConcurrentRunsPerTenant(2);
        properties.setExecutorMaxConcurrentRunsPerDatasource(1);
        properties.setExecutorThrottleDeferSeconds(45);
        properties.setExecutorLeaseSeconds(120L);
        properties.setFailOpen(false);
        properties.setSourceService("data-quality-test");
        return properties;
    }

    /**
     * 构造一条包含敏感正文的执行记录。
     */
    private QualityCheckExecution recentExecution() {
        QualityCheckExecution execution = new QualityCheckExecution();
        execution.setId(9001L);
        execution.setTenantId(10L);
        execution.setProjectId(101L);
        execution.setWorkspaceId(201L);
        execution.setRuleId(1001L);
        execution.setExecutionNo(6L);
        execution.setTriggerType("TASK_TRIGGERED");
        execution.setExecutionState(QualityCheckExecutionState.FAILED);
        execution.setOperator("quality-worker-test");
        execution.setTaskId(3001L);
        execution.setTaskRunId(4001L);
        execution.setExecutorId("quality-worker-test");
        execution.setStartedAt(LocalDateTime.of(2026, 6, 22, 20, 0));
        execution.setFinishedAt(LocalDateTime.of(2026, 6, 22, 20, 1));
        execution.setDurationMs(60_000L);
        execution.setReportId(5001L);
        execution.setScanPlanSnapshot("{\"jdbc\":\"jdbc:mysql://localhost:3306/order\",\"password\":\"secret\"}");
        execution.setMessage("SELECT * FROM ods_order; password=secret; http://internal-task-service");
        return execution;
    }
}
