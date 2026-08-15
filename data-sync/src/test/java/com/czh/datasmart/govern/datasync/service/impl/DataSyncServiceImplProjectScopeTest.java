package com.czh.datasmart.govern.datasync.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.czh.datasmart.govern.common.context.PlatformAuthorizedProjectRole;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncAuditQueryCriteria;
import com.czh.datasmart.govern.datasync.controller.dto.SyncCheckpointQueryCriteria;
import com.czh.datasmart.govern.datasync.controller.dto.SyncErrorSampleQueryCriteria;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionLogQueryCriteria;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionQueryCriteria;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.mapper.SyncAuditRecordMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncCheckpointMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncErrorSampleMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionLogMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskDefinitionMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskMapper;
import com.czh.datasmart.govern.datasync.service.support.SyncAgentExecutionDiagnosisSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncAgentExecutionCorrelationSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncAgentInvocationAuthoritySupport;
import com.czh.datasmart.govern.datasync.service.support.SyncAuditSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncDataScopeSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncDirtyRecordQuarantineSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncDirtyRecordReplaySupport;
import com.czh.datasmart.govern.datasync.service.support.SyncExecutionCreationSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncExecutionLifecycleSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncObjectExecutionOperationSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncQuerySupport;
import com.czh.datasmart.govern.datasync.service.support.SyncTaskBatchOperationSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncTaskCreateWizardDraftSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncTaskDefinitionExchangeSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncTaskDefinitionExecutionPrecheckSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncTaskDefinitionOperationSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncTaskGroupOperationSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncTaskLifecycleOperationSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncTaskManagementOperationSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncTaskMetadataConfigurationSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncTaskRecoveryOperationSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncTaskStateMachineSupport;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DataSyncServiceImpl 项目数据范围测试。
 *
 * <p>任务定义已经成为任务聚合的一部分，因此这里不再测试独立模板创建入口，只验证任务列表口径和
 * 任务执行事实的项目隔离。</p>
 */
class DataSyncServiceImplProjectScopeTest {

    @Test
    void defaultGroupFilterShouldIncludeLegacyEmptyAndNullGroupCodes() throws Exception {
        DataSyncServiceImpl service = service(mock(SyncTaskMapper.class), mock(SyncExecutionMapper.class),
                mock(SyncExecutionLogMapper.class), mock(SyncCheckpointMapper.class),
                mock(SyncErrorSampleMapper.class), mock(SyncAuditRecordMapper.class));
        LambdaQueryWrapper<SyncTask> wrapper = new LambdaQueryWrapper<>();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), SyncTask.class);
        Method method = DataSyncServiceImpl.class.getDeclaredMethod(
                "applyTaskGroupFilter", LambdaQueryWrapper.class, String.class);
        method.setAccessible(true);

        method.invoke(service, wrapper, SyncTaskGroupOperationSupport.DEFAULT_GROUP_CODE);

        assertThat(wrapper.getCustomSqlSegment()).contains("group_code", "COALESCE", "NULLIF");
        assertThat(wrapper.getParamNameValuePairs())
                .containsValue(SyncTaskGroupOperationSupport.DEFAULT_GROUP_CODE);
    }

    @Test
    void pageExecutionsShouldRejectUnauthorizedTaskProjectBeforeQueryChildTable() {
        ServiceFixture fixture = fixture();
        assertThrows(PlatformBusinessException.class, () -> fixture.service().pageExecutions(
                new SyncExecutionQueryCriteria(9001L, null, null, 1L, 20L), actor()));
        verify(fixture.executionMapper(), never()).selectPage(any(), any());
    }

    @Test
    void pageExecutionLogsShouldRejectUnauthorizedTaskProjectBeforeQueryChildTable() {
        ServiceFixture fixture = fixture();
        assertThrows(PlatformBusinessException.class, () -> fixture.service().pageExecutionLogs(
                new SyncExecutionLogQueryCriteria(9001L, 9101L, null, null, 1L, 20L), actor()));
        verify(fixture.executionLogMapper(), never()).selectPage(any(), any());
    }

    @Test
    void pageCheckpointsShouldRejectUnauthorizedTaskProjectBeforeQueryChildTable() {
        ServiceFixture fixture = fixture();
        assertThrows(PlatformBusinessException.class, () -> fixture.service().pageCheckpoints(
                new SyncCheckpointQueryCriteria(9001L, null, null, 1L, 20L), actor()));
        verify(fixture.checkpointMapper(), never()).selectPage(any(), any());
    }

    @Test
    void pageErrorSamplesShouldRejectUnauthorizedTaskProjectBeforeQueryChildTable() {
        ServiceFixture fixture = fixture();
        assertThrows(PlatformBusinessException.class, () -> fixture.service().pageErrorSamples(
                new SyncErrorSampleQueryCriteria(9001L, null, null, null, 1L, 20L), actor()));
        verify(fixture.errorSampleMapper(), never()).selectPage(any(), any());
    }

    @Test
    void pageAuditRecordsShouldRejectUnauthorizedTaskProjectBeforeQueryChildTable() {
        ServiceFixture fixture = fixture();
        assertThrows(PlatformBusinessException.class, () -> fixture.service().pageAuditRecords(
                new SyncAuditQueryCriteria(9001L, null, null, null, 1L, 20L), actor()));
        verify(fixture.auditRecordMapper(), never()).selectPage(any(), any());
    }

    private ServiceFixture fixture() {
        SyncTaskMapper taskMapper = mock(SyncTaskMapper.class);
        SyncExecutionMapper executionMapper = mock(SyncExecutionMapper.class);
        SyncExecutionLogMapper executionLogMapper = mock(SyncExecutionLogMapper.class);
        SyncCheckpointMapper checkpointMapper = mock(SyncCheckpointMapper.class);
        SyncErrorSampleMapper errorSampleMapper = mock(SyncErrorSampleMapper.class);
        SyncAuditRecordMapper auditRecordMapper = mock(SyncAuditRecordMapper.class);
        SyncTask task = new SyncTask();
        task.setId(9001L);
        task.setTenantId(7L);
        task.setProjectId(999L);
        task.setOwnerId(2002L);
        when(taskMapper.selectById(9001L)).thenReturn(task);
        return new ServiceFixture(
                service(taskMapper, executionMapper, executionLogMapper, checkpointMapper,
                        errorSampleMapper, auditRecordMapper),
                executionMapper, executionLogMapper, checkpointMapper, errorSampleMapper, auditRecordMapper);
    }

    private DataSyncServiceImpl service(SyncTaskMapper taskMapper,
                                        SyncExecutionMapper executionMapper,
                                        SyncExecutionLogMapper executionLogMapper,
                                        SyncCheckpointMapper checkpointMapper,
                                        SyncErrorSampleMapper errorSampleMapper,
                                        SyncAuditRecordMapper auditRecordMapper) {
        return new DataSyncServiceImpl(
                mock(SyncTaskDefinitionMapper.class),
                taskMapper,
                executionMapper,
                executionLogMapper,
                checkpointMapper,
                errorSampleMapper,
                auditRecordMapper,
                new SyncDataScopeSupport(),
                new SyncQuerySupport(),
                mock(SyncTaskStateMachineSupport.class),
                mock(SyncAuditSupport.class),
                mock(SyncExecutionLifecycleSupport.class),
                mock(SyncExecutionCreationSupport.class),
                mock(SyncTaskBatchOperationSupport.class),
                mock(SyncTaskDefinitionOperationSupport.class),
                mock(SyncTaskDefinitionExchangeSupport.class),
                mock(SyncTaskLifecycleOperationSupport.class),
                defaultTaskGroupOperationSupport(),
                mock(SyncTaskManagementOperationSupport.class),
                mock(SyncTaskMetadataConfigurationSupport.class),
                mock(SyncTaskRecoveryOperationSupport.class),
                mock(SyncTaskDefinitionExecutionPrecheckSupport.class),
                mock(SyncObjectExecutionOperationSupport.class),
                mock(SyncDirtyRecordReplaySupport.class),
                mock(SyncDirtyRecordQuarantineSupport.class),
                mock(SyncAgentExecutionDiagnosisSupport.class),
                mock(SyncTaskCreateWizardDraftSupport.class),
                mock(SyncAgentExecutionCorrelationSupport.class),
                mock(SyncAgentInvocationAuthoritySupport.class)
        );
    }

    private SyncTaskGroupOperationSupport defaultTaskGroupOperationSupport() {
        SyncTaskGroupOperationSupport support = mock(SyncTaskGroupOperationSupport.class);
        when(support.normalizeGroupCodeForFilter(any())).thenAnswer(invocation -> {
            String groupCode = invocation.getArgument(0);
            return groupCode == null || groupCode.isBlank() ? null : groupCode.trim().toUpperCase();
        });
        return support;
    }

    private SyncActorContext actor() {
        List<Long> projectIds = List.of(101L, 102L);
        List<PlatformAuthorizedProjectRole> roles = projectIds.stream()
                .map(projectId -> new PlatformAuthorizedProjectRole(projectId, "MANAGER"))
                .toList();
        return new SyncActorContext(
                7L, null, null, 1001L, "PROJECT_OWNER", "trace-sync-project",
                "PROJECT", "project_id IN ${actorProjectIds}", projectIds, roles, false);
    }

    private record ServiceFixture(DataSyncServiceImpl service,
                                  SyncExecutionMapper executionMapper,
                                  SyncExecutionLogMapper executionLogMapper,
                                  SyncCheckpointMapper checkpointMapper,
                                  SyncErrorSampleMapper errorSampleMapper,
                                  SyncAuditRecordMapper auditRecordMapper) {
    }
}
