/**
 * @Author : Cui
 * @Date: 2026/05/23 20:16
 * @Description DataSmart Govern Backend - DataSyncServiceImplProjectScopeTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.impl;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.datasync.config.DataSyncDatasourceCapabilityProperties;
import com.czh.datasmart.govern.datasync.controller.dto.CreateSyncTemplateRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncAuditQueryCriteria;
import com.czh.datasmart.govern.datasync.controller.dto.SyncCheckpointQueryCriteria;
import com.czh.datasmart.govern.datasync.controller.dto.SyncErrorSampleQueryCriteria;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionQueryCriteria;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.entity.SyncTemplate;
import com.czh.datasmart.govern.datasync.mapper.SyncAuditRecordMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncCheckpointMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncErrorSampleMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncTemplateMapper;
import com.czh.datasmart.govern.datasync.service.support.SyncAuditSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncDataScopeSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncExecutionCreationSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncExecutionLifecycleSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncQuerySupport;
import com.czh.datasmart.govern.datasync.service.support.SyncTaskLifecycleOperationSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncTaskRecoveryOperationSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncTaskStateMachineSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncConnectorCapabilityRegistry;
import com.czh.datasmart.govern.datasync.service.support.SyncTemplateConnectorFactResolver;
import com.czh.datasmart.govern.datasync.service.support.SyncTemplateCreationSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncTemplatePlanningPreviewSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncTemplateValidationSupport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * `DataSyncServiceImpl` 在 PROJECT 数据范围下的写入安全测试。
 *
 * <p>前面 `SyncDataScopeSupportTest` 已经证明 support 组件能正确解释 gateway 透传的授权项目集合；
 * 这组测试再往上走一层，确认真正的业务入口 `createTemplate(...)` 已经在 insert 之前调用写入校验。
 *
 * <p>为什么要专门测 Service，而不只测 support：
 * 1. support 正确不代表 Service 一定使用了它；
 * 2. 创建同步模板属于高风险写入入口，后续可能触发真实数据同步、导出、回放或补数；
 * 3. 如果越权模板先写入数据库，再靠列表查询隐藏，风险已经产生，不能算安全闭环。
 */
class DataSyncServiceImplProjectScopeTest {

    /**
     * PROJECT 范围下，用户不能把同步模板写入未授权项目。
     *
     * <p>该用例重点验证“拒绝发生在 insert 之前”。
     * 这样即使后续审计、事件发布或异步调度扩展进来，也不会看到一条已经落库的越权模板。
     */
    @Test
    void createTemplateShouldRejectUnauthorizedProjectBeforeInsert() {
        SyncTemplateMapper templateMapper = mock(SyncTemplateMapper.class);
        SyncAuditSupport auditSupport = mock(SyncAuditSupport.class);
        DataSyncServiceImpl service = service(templateMapper, auditSupport);
        CreateSyncTemplateRequest request = templateRequest(999L);

        assertThrows(PlatformBusinessException.class,
                () -> service.createTemplate(request, projectScopedActor(List.of(101L, 102L))));

        verify(templateMapper, never()).insert(any(SyncTemplate.class));
        verify(auditSupport, never()).saveTemplateAudit(any(), any(), any(), any());
    }

    /**
     * PROJECT 范围下，用户把模板写入授权项目时应正常入库并写审计。
     *
     * <p>这个正向用例避免校验逻辑被写得过严，例如把所有 PROJECT 范围写入都拒绝。
     */
    @Test
    void createTemplateShouldAllowAuthorizedProjectAndWriteAudit() {
        SyncTemplateMapper templateMapper = mock(SyncTemplateMapper.class);
        SyncAuditSupport auditSupport = mock(SyncAuditSupport.class);
        DataSyncServiceImpl service = service(templateMapper, auditSupport);
        when(templateMapper.insert(any(SyncTemplate.class))).thenAnswer(invocation -> {
            SyncTemplate template = invocation.getArgument(0);
            template.setId(5001L);
            return 1;
        });

        SyncTemplate template = service.createTemplate(templateRequest(101L), projectScopedActor(List.of(101L, 102L)));

        assertThat(template.getId()).isEqualTo(5001L);
        assertThat(template.getProjectId()).isEqualTo(101L);
        verify(templateMapper).insert(any(SyncTemplate.class));
        verify(auditSupport).saveTemplateAudit(any(SyncTemplate.class), any(), any(), any());
    }

    /**
     * 执行历史列表如果指定了 taskId，必须先校验任务归属项目。
     *
     * <p>这个场景对应前端从“同步任务详情页”进入执行历史。
     * 虽然查询的是 execution 表，但真正的权限边界来自任务所属项目；如果任务不属于授权项目，
     * service 应该在读取任务后立即拒绝，不能继续查询 execution 子表。
     */
    @Test
    void pageExecutionsShouldRejectUnauthorizedTaskProjectBeforeQueryChildTable() {
        ServiceFixture fixture = serviceFixtureWithUnauthorizedTask();

        assertThrows(PlatformBusinessException.class,
                () -> fixture.service().pageExecutions(
                        new SyncExecutionQueryCriteria(9001L, null, null, 1L, 20L),
                        projectScopedActor(List.of(101L, 102L))));

        verify(fixture.executionMapper(), never()).selectPage(any(), any());
    }

    /**
     * checkpoint 列表同样必须先校验任务项目归属。
     *
     * <p>checkpoint 会暴露增量边界、offset、分区位置等恢复信息，属于执行链路里的敏感运维事实。
     * 未授权项目不能通过猜 taskId 间接读取这些恢复坐标。
     */
    @Test
    void pageCheckpointsShouldRejectUnauthorizedTaskProjectBeforeQueryChildTable() {
        ServiceFixture fixture = serviceFixtureWithUnauthorizedTask();

        assertThrows(PlatformBusinessException.class,
                () -> fixture.service().pageCheckpoints(
                        new SyncCheckpointQueryCriteria(9001L, null, null, 1L, 20L),
                        projectScopedActor(List.of(101L, 102L))));

        verify(fixture.checkpointMapper(), never()).selectPage(any(), any());
    }

    /**
     * 错误样本列表必须遵守任务项目边界。
     *
     * <p>错误样本可能包含字段值片段、失败主键、目标端响应摘要等信息。
     * 即使未来会继续做脱敏，也不能让未授权项目的用户进入这张表的查询路径。
     */
    @Test
    void pageErrorSamplesShouldRejectUnauthorizedTaskProjectBeforeQueryChildTable() {
        ServiceFixture fixture = serviceFixtureWithUnauthorizedTask();

        assertThrows(PlatformBusinessException.class,
                () -> fixture.service().pageErrorSamples(
                        new SyncErrorSampleQueryCriteria(9001L, null, null, null, 1L, 20L),
                        projectScopedActor(List.of(101L, 102L))));

        verify(fixture.errorSampleMapper(), never()).selectPage(any(), any());
    }

    /**
     * 审计流水查询也不能绕过任务项目边界。
     *
     * <p>审计数据常被误认为“只读所以不敏感”，但它会暴露谁操作了什么任务、失败原因、traceId 和配置摘要。
     * 因此审计列表同样需要先校验任务所属项目，再查询审计子表。
     */
    @Test
    void pageAuditRecordsShouldRejectUnauthorizedTaskProjectBeforeQueryChildTable() {
        ServiceFixture fixture = serviceFixtureWithUnauthorizedTask();

        assertThrows(PlatformBusinessException.class,
                () -> fixture.service().pageAuditRecords(
                        new SyncAuditQueryCriteria(9001L, null, null, null, 1L, 20L),
                        projectScopedActor(List.of(101L, 102L))));

        verify(fixture.auditRecordMapper(), never()).selectPage(any(), any());
    }

    private DataSyncServiceImpl service(SyncTemplateMapper templateMapper, SyncAuditSupport auditSupport) {
        SyncDataScopeSupport dataScopeSupport = new SyncDataScopeSupport();
        SyncQuerySupport querySupport = new SyncQuerySupport();
        SyncTemplateValidationSupport templateValidationSupport = new SyncTemplateValidationSupport();
        return new DataSyncServiceImpl(
                templateMapper,
                mock(SyncTaskMapper.class),
                mock(SyncExecutionMapper.class),
                mock(SyncCheckpointMapper.class),
                mock(SyncErrorSampleMapper.class),
                mock(SyncAuditRecordMapper.class),
                dataScopeSupport,
                querySupport,
                templateValidationSupport,
                mock(SyncTaskStateMachineSupport.class),
                auditSupport,
                mock(SyncExecutionLifecycleSupport.class),
                mock(SyncExecutionCreationSupport.class),
                mock(SyncTaskLifecycleOperationSupport.class),
                mock(SyncTaskRecoveryOperationSupport.class),
                templateCreationSupport(templateMapper, auditSupport, dataScopeSupport, querySupport, templateValidationSupport),
                new SyncTemplatePlanningPreviewSupport(new SyncConnectorCapabilityRegistry())
        );
    }

    private ServiceFixture serviceFixtureWithUnauthorizedTask() {
        SyncTemplateMapper templateMapper = mock(SyncTemplateMapper.class);
        SyncTaskMapper taskMapper = mock(SyncTaskMapper.class);
        SyncExecutionMapper executionMapper = mock(SyncExecutionMapper.class);
        SyncCheckpointMapper checkpointMapper = mock(SyncCheckpointMapper.class);
        SyncErrorSampleMapper errorSampleMapper = mock(SyncErrorSampleMapper.class);
        SyncAuditRecordMapper auditRecordMapper = mock(SyncAuditRecordMapper.class);
        SyncAuditSupport auditSupport = mock(SyncAuditSupport.class);
        DataSyncServiceImpl service = service(
                templateMapper,
                taskMapper,
                executionMapper,
                checkpointMapper,
                errorSampleMapper,
                auditRecordMapper,
                auditSupport
        );
        SyncTask task = new SyncTask();
        task.setId(9001L);
        task.setTenantId(7L);
        task.setProjectId(999L);
        task.setWorkspaceId(301L);
        task.setOwnerId(2002L);
        when(taskMapper.selectById(9001L)).thenReturn(task);
        return new ServiceFixture(service, executionMapper, checkpointMapper, errorSampleMapper, auditRecordMapper);
    }

    private DataSyncServiceImpl service(SyncTemplateMapper templateMapper,
                                        SyncTaskMapper taskMapper,
                                        SyncExecutionMapper executionMapper,
                                        SyncCheckpointMapper checkpointMapper,
                                        SyncErrorSampleMapper errorSampleMapper,
                                        SyncAuditRecordMapper auditRecordMapper,
                                        SyncAuditSupport auditSupport) {
        SyncDataScopeSupport dataScopeSupport = new SyncDataScopeSupport();
        SyncQuerySupport querySupport = new SyncQuerySupport();
        SyncTemplateValidationSupport templateValidationSupport = new SyncTemplateValidationSupport();
        return new DataSyncServiceImpl(
                templateMapper,
                taskMapper,
                executionMapper,
                checkpointMapper,
                errorSampleMapper,
                auditRecordMapper,
                dataScopeSupport,
                querySupport,
                templateValidationSupport,
                mock(SyncTaskStateMachineSupport.class),
                auditSupport,
                mock(SyncExecutionLifecycleSupport.class),
                mock(SyncExecutionCreationSupport.class),
                mock(SyncTaskLifecycleOperationSupport.class),
                mock(SyncTaskRecoveryOperationSupport.class),
                templateCreationSupport(templateMapper, auditSupport, dataScopeSupport, querySupport, templateValidationSupport),
                new SyncTemplatePlanningPreviewSupport(new SyncConnectorCapabilityRegistry())
        );
    }

    private SyncTemplateCreationSupport templateCreationSupport(SyncTemplateMapper templateMapper,
                                                               SyncAuditSupport auditSupport,
                                                               SyncDataScopeSupport dataScopeSupport,
                                                               SyncQuerySupport querySupport,
                                                               SyncTemplateValidationSupport templateValidationSupport) {
        return new SyncTemplateCreationSupport(
                templateMapper,
                dataScopeSupport,
                querySupport,
                disabledConnectorFactResolver(querySupport),
                templateValidationSupport,
                auditSupport
        );
    }

    private SyncTemplateConnectorFactResolver disabledConnectorFactResolver(SyncQuerySupport querySupport) {
        DataSyncDatasourceCapabilityProperties properties = new DataSyncDatasourceCapabilityProperties();
        properties.setEnabled(false);
        return new SyncTemplateConnectorFactResolver((datasourceId, actorContext) -> {
            throw new AssertionError("测试夹具已关闭 datasource-management 能力快照调用，不应触发远程读取");
        }, properties, querySupport);
    }

    private CreateSyncTemplateRequest templateRequest(Long projectId) {
        CreateSyncTemplateRequest request = new CreateSyncTemplateRequest();
        request.setTenantId(7L);
        request.setProjectId(projectId);
        request.setWorkspaceId(301L);
        request.setName("客户主数据同步模板");
        request.setSourceDatasourceId(10001L);
        request.setTargetDatasourceId(20001L);
        request.setSourceSchemaName("ods");
        request.setSourceObjectName("customer");
        request.setTargetSchemaName("dwd");
        request.setTargetObjectName("customer");
        request.setSyncMode("FULL");
        request.setWriteStrategy("APPEND");
        return request;
    }

    private SyncActorContext projectScopedActor(List<Long> projectIds) {
        return new SyncActorContext(
                7L,
                1001L,
                "PROJECT_OWNER",
                "trace-sync-project",
                "PROJECT",
                "project_id IN ${actorProjectIds}",
                projectIds,
                false
        );
    }

    /**
     * 测试夹具，把 service 和执行追踪子表 mapper 放在一起。
     *
     * <p>这样每个测试都可以清晰表达“应该拒绝访问，并且某个子表 mapper 不应该被调用”，
     * 不需要在测试方法里重复构造一长串 mock。
     */
    private record ServiceFixture(DataSyncServiceImpl service,
                                  SyncExecutionMapper executionMapper,
                                  SyncCheckpointMapper checkpointMapper,
                                  SyncErrorSampleMapper errorSampleMapper,
                                  SyncAuditRecordMapper auditRecordMapper) {
    }
}
