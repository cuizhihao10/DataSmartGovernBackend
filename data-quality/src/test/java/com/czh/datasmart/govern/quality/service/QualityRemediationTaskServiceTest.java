/**
 * @Author : Cui
 * @Date: 2026/06/28 15:20
 * @Description DataSmart Govern Backend - QualityRemediationTaskServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.service;

import com.czh.datasmart.govern.quality.config.TaskManagementIntegrationProperties;
import com.czh.datasmart.govern.quality.controller.dto.QualityAnomalyAggregationItem;
import com.czh.datasmart.govern.quality.controller.dto.QualityRemediationTaskRequest;
import com.czh.datasmart.govern.quality.controller.dto.QualityRemediationTaskResponse;
import com.czh.datasmart.govern.quality.entity.QualityCheckReport;
import com.czh.datasmart.govern.quality.integration.task.TaskCreateRequest;
import com.czh.datasmart.govern.quality.integration.task.TaskCreateResponse;
import com.czh.datasmart.govern.quality.integration.task.TaskManagementClient;
import com.czh.datasmart.govern.quality.mapper.QualityAnomalyDetailMapper;
import com.czh.datasmart.govern.quality.mapper.QualityCheckReportMapper;
import com.czh.datasmart.govern.quality.service.support.QualityProjectScopeSupport;
import com.czh.datasmart.govern.quality.service.support.QualityProjectVisibility;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 质量异常治理任务服务测试。
 *
 * <p>这组测试保护的是 data-quality 新增闭环中最容易出错的几条业务边界：</p>
 *
 * <p>1. dry-run 只能生成低敏 payload 预览，不能真实调用 task-management；</p>
 * <p>2. PROJECT 空授权必须短路，不能为了判断 reportId 或异常数量而查询数据库；</p>
 * <p>3. reportId 入口必须校验报告项目归属，防止猜测 ID 后跨项目创建任务；</p>
 * <p>4. 真实提交任务时必须使用 DATA_QUALITY_REMEDIATION，而不是误用 DATA_QUALITY_SCAN。</p>
 */
class QualityRemediationTaskServiceTest {

    @Test
    void dryRunShouldBuildLowSensitivePayloadWithoutSubmittingTask() {
        QualityCheckReportMapper reportMapper = mock(QualityCheckReportMapper.class);
        QualityAnomalyDetailMapper anomalyMapper = mock(QualityAnomalyDetailMapper.class);
        TaskManagementClient taskClient = mock(TaskManagementClient.class);
        QualityRemediationTaskService service = service(reportMapper, anomalyMapper, taskClient, properties(true));

        when(reportMapper.selectById(77L)).thenReturn(report());
        when(anomalyMapper.selectCount(any())).thenReturn(3L);
        when(anomalyMapper.aggregateAnomalies(anyString(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), anyInt(), any(), any(), anyList(), anyBoolean()))
                .thenReturn(List.of(aggregation("email", 2L)));

        QualityRemediationTaskRequest request = request();
        request.setDryRun(true);
        request.setRecommendation("SELECT * FROM customer WHERE password='secret'");

        QualityRemediationTaskResponse response = service.createRemediationTask(
                request,
                new QualityProjectVisibility(101L, 201L, List.of(101L), true),
                10L,
                1001L
        );

        assertThat(response.isSubmitted()).isFalse();
        assertThat(response.isDryRun()).isTrue();
        assertThat(response.getTaskType()).isEqualTo("DATA_QUALITY_REMEDIATION");
        assertThat(response.getAnomalyCount()).isEqualTo(3L);
        assertThat(response.getPayloadPreview().getProjectId()).isEqualTo(101L);
        assertThat(response.getPayloadPreview().getRecommendation()).contains("已按低敏策略隐藏");
        assertThat(response.getPayloadPreview().getTopFields()).singleElement()
                .extracting(QualityAnomalyAggregationItem::getAggregateKey)
                .isEqualTo("email");

        String serialized = response.toString();
        assertThat(serialized)
                .doesNotContain("SELECT *")
                .doesNotContain("password")
                .doesNotContain("secret")
                .doesNotContain("observedValue")
                .doesNotContain("samplePayload")
                .doesNotContain("recordIdentifier")
                .doesNotContain("jdbc:mysql");
        verify(taskClient, never()).createTask(any());
    }

    @Test
    void projectScopeWithoutVisibleProjectsShouldReturnNotSubmittedWithoutDatabaseQuery() {
        QualityCheckReportMapper reportMapper = mock(QualityCheckReportMapper.class);
        QualityAnomalyDetailMapper anomalyMapper = mock(QualityAnomalyDetailMapper.class);
        TaskManagementClient taskClient = mock(TaskManagementClient.class);
        QualityRemediationTaskService service = service(reportMapper, anomalyMapper, taskClient, properties(true));

        QualityRemediationTaskResponse response = service.createRemediationTask(
                request(),
                new QualityProjectVisibility(null, null, List.of(), true),
                10L,
                1001L
        );

        assertThat(response.isSubmitted()).isFalse();
        assertThat(response.getWarnings()).contains("PROJECT_SCOPE_EMPTY");
        verify(reportMapper, never()).selectById(any());
        verify(anomalyMapper, never()).selectCount(any());
        verify(taskClient, never()).createTask(any());
    }

    @Test
    void reportOutsideProjectScopeShouldBeRejectedBeforeTaskSubmission() {
        QualityCheckReportMapper reportMapper = mock(QualityCheckReportMapper.class);
        QualityAnomalyDetailMapper anomalyMapper = mock(QualityAnomalyDetailMapper.class);
        TaskManagementClient taskClient = mock(TaskManagementClient.class);
        QualityRemediationTaskService service = service(reportMapper, anomalyMapper, taskClient, properties(true));

        when(reportMapper.selectById(77L)).thenReturn(report());

        assertThatThrownBy(() -> service.createRemediationTask(
                request(),
                new QualityProjectVisibility(999L, 201L, List.of(999L), true),
                10L,
                1001L
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("质量报告");

        verify(anomalyMapper, never()).selectCount(any());
        verify(taskClient, never()).createTask(any());
    }

    @Test
    void successfulSubmissionShouldUseRemediationTaskTypeAndLowSensitiveParams() {
        QualityCheckReportMapper reportMapper = mock(QualityCheckReportMapper.class);
        QualityAnomalyDetailMapper anomalyMapper = mock(QualityAnomalyDetailMapper.class);
        TaskManagementClient taskClient = mock(TaskManagementClient.class);
        QualityRemediationTaskService service = service(reportMapper, anomalyMapper, taskClient, properties(true));

        when(reportMapper.selectById(77L)).thenReturn(report());
        when(anomalyMapper.selectCount(any())).thenReturn(5L);
        when(anomalyMapper.aggregateAnomalies(anyString(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), anyInt(), any(), any(), anyList(), anyBoolean()))
                .thenReturn(List.of(aggregation("HIGH", 5L)));
        when(taskClient.createTask(any())).thenReturn(taskResponse());

        QualityRemediationTaskResponse response = service.createRemediationTask(
                request(),
                new QualityProjectVisibility(101L, 201L, List.of(101L), true),
                10L,
                1001L
        );

        ArgumentCaptor<TaskCreateRequest> captor = ArgumentCaptor.forClass(TaskCreateRequest.class);
        verify(taskClient).createTask(captor.capture());
        TaskCreateRequest taskRequest = captor.getValue();

        assertThat(response.isSubmitted()).isTrue();
        assertThat(response.getTaskId()).isEqualTo(9001L);
        assertThat(taskRequest.getType()).isEqualTo("DATA_QUALITY_REMEDIATION");
        assertThat(taskRequest.getTenantId()).isEqualTo(10L);
        assertThat(taskRequest.getProjectId()).isEqualTo(101L);
        assertThat(taskRequest.getOwnerId()).isEqualTo(1001L);
        assertThat(taskRequest.getParams())
                .contains("DATA_QUALITY_REMEDIATION_TASK_V1")
                .contains("LOW_SENSITIVE_AGGREGATION_ONLY")
                .doesNotContain("observedValue")
                .doesNotContain("samplePayload")
                .doesNotContain("recordIdentifier")
                .doesNotContain("jdbc:mysql")
                .doesNotContain("SELECT *");
    }

    private QualityRemediationTaskService service(QualityCheckReportMapper reportMapper,
                                                  QualityAnomalyDetailMapper anomalyMapper,
                                                  TaskManagementClient taskClient,
                                                  TaskManagementIntegrationProperties properties) {
        return new QualityRemediationTaskService(
                reportMapper,
                anomalyMapper,
                new QualityProjectScopeSupport(),
                properties,
                taskClient,
                objectMapper()
        );
    }

    private ObjectMapper objectMapper() {
        return JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .build();
    }

    private TaskManagementIntegrationProperties properties(boolean enabled) {
        TaskManagementIntegrationProperties properties = new TaskManagementIntegrationProperties();
        properties.setEnabled(enabled);
        properties.setDefaultPriority("MEDIUM");
        properties.setDefaultMaxRetryCount(3);
        properties.setExecutorActorId(0L);
        properties.setRemediationTaskType("DATA_QUALITY_REMEDIATION");
        return properties;
    }

    private QualityRemediationTaskRequest request() {
        QualityRemediationTaskRequest request = new QualityRemediationTaskRequest();
        request.setReportId(77L);
        request.setProjectId(101L);
        request.setWorkspaceId(201L);
        request.setSeverity("HIGH");
        request.setAnomalyType("NULL_VALUE");
        request.setFieldName("email");
        request.setRemediationType("MANUAL_REVIEW");
        request.setReason("核心客户字段空值异常，需要项目负责人复核。");
        request.setStartTime(LocalDateTime.of(2026, 6, 1, 0, 0));
        request.setEndTime(LocalDateTime.of(2026, 6, 28, 23, 59));
        return request;
    }

    private QualityCheckReport report() {
        QualityCheckReport report = new QualityCheckReport();
        report.setId(77L);
        report.setTenantId(10L);
        report.setProjectId(101L);
        report.setWorkspaceId(201L);
        report.setRuleId(3001L);
        report.setRuleName("客户邮箱完整性规则");
        report.setRuleType("COMPLETENESS");
        report.setSeverity("HIGH");
        report.setTargetObject("ods.customer.email");
        report.setExceptionCount(5);
        return report;
    }

    private QualityAnomalyAggregationItem aggregation(String key, Long count) {
        QualityAnomalyAggregationItem item = new QualityAnomalyAggregationItem();
        item.setAggregateKey(key);
        item.setAnomalyCount(count);
        item.setLatestCreateTime(LocalDateTime.of(2026, 6, 28, 12, 0));
        return item;
    }

    private TaskCreateResponse taskResponse() {
        TaskCreateResponse response = new TaskCreateResponse();
        response.setId(9001L);
        response.setType("DATA_QUALITY_REMEDIATION");
        response.setStatus("PENDING");
        response.setPriority("MEDIUM");
        return response;
    }
}
