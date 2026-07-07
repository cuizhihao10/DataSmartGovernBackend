/**
 * @Author : Cui
 * @Date: 2026/05/23 20:16
 * @Description DataSmart Govern Backend - DataSyncServiceImplProjectScopeTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.impl;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.datasync.controller.dto.CreateSyncTaskRequest;
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
import com.czh.datasmart.govern.datasync.service.support.SyncDirtyRecordReplaySupport;
import com.czh.datasmart.govern.datasync.service.support.SyncExecutionCreationSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncExecutionLifecycleSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncObjectExecutionOperationSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncOfflineJobPlanSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncQuerySupport;
import com.czh.datasmart.govern.datasync.service.support.SyncTaskLifecycleOperationSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncTaskRecoveryOperationSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncTaskScheduleConfigSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncTaskStateMachineSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncConnectorCapabilityRegistry;
import com.czh.datasmart.govern.datasync.service.support.SyncFieldMappingExecutionContractSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncFilterExecutionContractSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncTemplateConnectorFactResolver;
import com.czh.datasmart.govern.datasync.service.support.SyncTemplateCreationSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncTemplateExecutionPrecheckSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncTemplatePlanningPreviewSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncTemplateScopeContractSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncTemplateValidationSupport;
import com.czh.datasmart.govern.datasync.support.SyncApprovalState;
import com.czh.datasmart.govern.datasync.support.SyncTaskState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
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
     * 普通项目角色不能仅靠请求体里的 approvalConfirmed=true 绕过高风险审批。
     *
     * <p>这个用例固定的是商业化系统里非常重要的一条边界：自定义 SQL、全库迁移等高风险任务可以先创建草稿，
     * 但“审批已完成”必须来自可信角色或后续正式审批中心。否则攻击者只要构造一个布尔字段，就能把任意只读 SQL
     * 结果集搬到目标端，审批流就形同虚设。</p>
     */
    @Test
    void createTaskShouldRejectApprovalConfirmationFromUntrustedRole() {
        SyncTemplateMapper templateMapper = mock(SyncTemplateMapper.class);
        SyncTaskMapper taskMapper = mock(SyncTaskMapper.class);
        SyncAuditSupport auditSupport = mock(SyncAuditSupport.class);
        DataSyncServiceImpl service = service(templateMapper, taskMapper,
                mock(SyncExecutionMapper.class),
                mock(SyncCheckpointMapper.class),
                mock(SyncErrorSampleMapper.class),
                mock(SyncAuditRecordMapper.class),
                auditSupport);
        when(templateMapper.selectById(7001L)).thenReturn(highRiskCustomSqlTemplate());

        CreateSyncTaskRequest request = createTaskRequest(true, "approval:test-001");

        assertThrows(PlatformBusinessException.class,
                () -> service.createTask(request, projectScopedActor(List.of(101L, 102L))));

        verify(taskMapper, never()).insert(any(SyncTask.class));
        verify(auditSupport, never()).saveAudit(any(), any(), any(), any(), any(), any());
    }

    /**
     * 高风险任务未提交审批事实时，应允许创建但停留在 PENDING_APPROVAL。
     *
     * <p>这样前端或运营台可以看到“配置已经保存、但不能执行”的明确状态，而不是让用户反复创建失败。
     * 真正运行仍会被 runTask 的预检与状态机拦住，直到审批中心或可信服务账号写入 APPROVED 事实。</p>
     */
    @Test
    void createTaskShouldParkHighRiskTaskInPendingApprovalWhenApprovalMissing() {
        SyncTemplateMapper templateMapper = mock(SyncTemplateMapper.class);
        SyncTaskMapper taskMapper = mock(SyncTaskMapper.class);
        SyncAuditSupport auditSupport = mock(SyncAuditSupport.class);
        DataSyncServiceImpl service = service(templateMapper, taskMapper,
                mock(SyncExecutionMapper.class),
                mock(SyncCheckpointMapper.class),
                mock(SyncErrorSampleMapper.class),
                mock(SyncAuditRecordMapper.class),
                auditSupport);
        when(templateMapper.selectById(7001L)).thenReturn(highRiskCustomSqlTemplate());
        when(taskMapper.insert(any(SyncTask.class))).thenAnswer(invocation -> {
            SyncTask task = invocation.getArgument(0);
            task.setId(8101L);
            return 1;
        });

        SyncTask task = service.createTask(createTaskRequest(false, null), projectScopedActor(List.of(101L, 102L)));

        assertThat(task.getCurrentState()).isEqualTo(SyncTaskState.PENDING_APPROVAL.name());
        assertThat(task.getApprovalState()).isEqualTo(SyncApprovalState.PENDING.name());
        verify(taskMapper).insert(any(SyncTask.class));
        verify(auditSupport).saveAudit(any(), any(), any(), any(), any(), contains("approvalRequired=true"));
    }

    /**
     * 可信服务账号提交低敏审批事实后，高风险任务才允许进入 CONFIGURED。
     *
     * <p>这里同时验证审计摘要只记录 approvalFactId 这类低敏引用。SQL 正文、字段映射和连接信息仍留在受控配置或
     * datasource-management 执行面，不能被普通审计 payload 扩散。</p>
     */
    @Test
    void createTaskShouldAcceptTrustedApprovalFactAndWriteLowSensitiveAudit() {
        SyncTemplateMapper templateMapper = mock(SyncTemplateMapper.class);
        SyncTaskMapper taskMapper = mock(SyncTaskMapper.class);
        SyncAuditSupport auditSupport = mock(SyncAuditSupport.class);
        DataSyncServiceImpl service = service(templateMapper, taskMapper,
                mock(SyncExecutionMapper.class),
                mock(SyncCheckpointMapper.class),
                mock(SyncErrorSampleMapper.class),
                mock(SyncAuditRecordMapper.class),
                auditSupport);
        when(templateMapper.selectById(7001L)).thenReturn(highRiskCustomSqlTemplate());
        when(taskMapper.insert(any(SyncTask.class))).thenAnswer(invocation -> {
            SyncTask task = invocation.getArgument(0);
            task.setId(8102L);
            return 1;
        });

        SyncTask task = service.createTask(createTaskRequest(true, "approval:test-002"), trustedApprovalActor());

        assertThat(task.getCurrentState()).isEqualTo(SyncTaskState.CONFIGURED.name());
        assertThat(task.getApprovalState()).isEqualTo(SyncApprovalState.APPROVED.name());
        verify(taskMapper).insert(any(SyncTask.class));
        verify(auditSupport).saveAudit(any(), any(), any(), any(), any(), contains("approvalFactId=approval:test-002"));
    }

    /**
     * FULL + scheduleConfig 应创建为定期全量任务。
     *
     * <p>这个用例固定本轮新增的核心产品语义：FULL 本身仍然表示全量同步，
     * 但当任务级 scheduleConfig 存在时，它就从“手动全量”变成“定期全量”。
     * 创建后的任务应停在 SCHEDULED，写入 nextFireTime，并等待后台 task scheduler 到点生成 SCHEDULED execution。</p>
     */
    @Test
    void createTaskShouldCreateScheduledFullTaskWhenScheduleConfigProvided() {
        SyncTemplateMapper templateMapper = mock(SyncTemplateMapper.class);
        SyncTaskMapper taskMapper = mock(SyncTaskMapper.class);
        SyncAuditSupport auditSupport = mock(SyncAuditSupport.class);
        DataSyncServiceImpl service = service(templateMapper, taskMapper,
                mock(SyncExecutionMapper.class),
                mock(SyncCheckpointMapper.class),
                mock(SyncErrorSampleMapper.class),
                mock(SyncAuditRecordMapper.class),
                auditSupport);
        when(templateMapper.selectById(7201L)).thenReturn(scheduledFullTemplate());
        when(taskMapper.insert(any(SyncTask.class))).thenAnswer(invocation -> {
            SyncTask task = invocation.getArgument(0);
            task.setId(8201L);
            return 1;
        });

        CreateSyncTaskRequest request = new CreateSyncTaskRequest();
        request.setTemplateId(7201L);
        request.setTenantId(7L);
        request.setProjectId(101L);
        request.setWorkspaceId(301L);
        request.setName("daily full sync task");
        request.setRunMode("SCHEDULED");
        request.setScheduleConfig("""
                {"type":"FIXED_RATE","intervalSeconds":3600,"misfirePolicy":"FIRE_ONCE","timezone":"Asia/Shanghai"}
                """);

        SyncTask task = service.createTask(request, projectScopedActor(List.of(101L, 102L)));

        assertThat(task.getCurrentState()).isEqualTo(SyncTaskState.SCHEDULED.name());
        assertThat(task.getScheduleEnabled()).isTrue();
        assertThat(task.getNextFireTime()).isNotNull();
        assertThat(task.getScheduleVersion()).isZero();
        assertThat(task.getScheduleDispatchCount()).isZero();
        assertThat(task.getTriggerType()).isEqualTo("SCHEDULED");
        verify(auditSupport).saveAudit(any(), any(), any(), any(), any(), contains("scheduleEnabled=true"));
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
                new SyncTemplatePlanningPreviewSupport(new SyncConnectorCapabilityRegistry()),
                templateExecutionPrecheckSupport(),
                offlineJobPlanSupport(),
                mock(SyncObjectExecutionOperationSupport.class),
                mock(SyncDirtyRecordReplaySupport.class),
                new SyncTaskScheduleConfigSupport(new ObjectMapper())
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
                new SyncTemplatePlanningPreviewSupport(new SyncConnectorCapabilityRegistry()),
                templateExecutionPrecheckSupport(),
                offlineJobPlanSupport(),
                mock(SyncObjectExecutionOperationSupport.class),
                mock(SyncDirtyRecordReplaySupport.class),
                new SyncTaskScheduleConfigSupport(new ObjectMapper())
        );
    }

    private SyncTemplateExecutionPrecheckSupport templateExecutionPrecheckSupport() {
        ObjectMapper objectMapper = new ObjectMapper();
        return new SyncTemplateExecutionPrecheckSupport(
                new SyncConnectorCapabilityRegistry(),
                new SyncTemplateScopeContractSupport(objectMapper),
                new SyncFieldMappingExecutionContractSupport(objectMapper),
                new SyncFilterExecutionContractSupport(objectMapper)
        );
    }

    private SyncOfflineJobPlanSupport offlineJobPlanSupport() {
        ObjectMapper objectMapper = new ObjectMapper();
        return new SyncOfflineJobPlanSupport(
                new SyncConnectorCapabilityRegistry(),
                new SyncTemplateScopeContractSupport(objectMapper),
                new SyncFieldMappingExecutionContractSupport(objectMapper),
                objectMapper
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

    private CreateSyncTaskRequest createTaskRequest(boolean approvalConfirmed, String approvalFactId) {
        CreateSyncTaskRequest request = new CreateSyncTaskRequest();
        request.setTemplateId(7001L);
        request.setTenantId(7L);
        request.setProjectId(101L);
        request.setWorkspaceId(301L);
        request.setName("high risk custom sql task");
        request.setDescription("task used to verify approval gate");
        request.setPriority("HIGH");
        request.setRunMode("MANUAL");
        request.setOwnerId(1001L);
        request.setApprovalConfirmed(approvalConfirmed);
        request.setApprovalFactId(approvalFactId);
        return request;
    }

    private SyncTemplate highRiskCustomSqlTemplate() {
        SyncTemplate template = new SyncTemplate();
        template.setId(7001L);
        template.setTenantId(7L);
        template.setProjectId(101L);
        template.setWorkspaceId(301L);
        template.setName("high risk custom sql template");
        template.setDescription("custom sql approval test template");
        template.setSourceDatasourceId(10001L);
        template.setTargetDatasourceId(20001L);
        template.setSourceSchemaName("ods");
        template.setTargetSchemaName("dwd");
        template.setTargetObjectName("customer_clean");
        template.setSourceConnectorType("MYSQL");
        template.setTargetConnectorType("POSTGRESQL");
        template.setSyncMode("CUSTOM_SQL_QUERY");
        template.setSyncScopeType("CUSTOM_SQL_QUERY");
        template.setWriteStrategy("UPSERT");
        template.setPrimaryKeyField("id");
        template.setCustomSqlConfig("""
                {"sql":"select id, name from customer where status = 'ACTIVE'","statementRef":"approval-test.sql"}
                """);
        template.setFieldMappingConfig("""
                [
                  {"sourceField":"id","targetField":"id"},
                  {"sourceField":"name","targetField":"name"}
                ]
                """);
        template.setEnabled(true);
        return template;
    }

    private SyncTemplate scheduledFullTemplate() {
        SyncTemplate template = new SyncTemplate();
        template.setId(7201L);
        template.setTenantId(7L);
        template.setProjectId(101L);
        template.setWorkspaceId(301L);
        template.setName("scheduled full template");
        template.setDescription("scheduled full sync template");
        template.setSourceDatasourceId(10001L);
        template.setTargetDatasourceId(20001L);
        template.setSourceSchemaName("ods");
        template.setSourceObjectName("orders");
        template.setTargetSchemaName("dwd");
        template.setTargetObjectName("orders");
        template.setSourceConnectorType("MYSQL");
        template.setTargetConnectorType("POSTGRESQL");
        template.setSyncMode("FULL");
        template.setWriteStrategy("APPEND");
        template.setEnabled(true);
        return template;
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

    private SyncActorContext trustedApprovalActor() {
        return new SyncActorContext(
                7L,
                9001L,
                "SERVICE_ACCOUNT",
                "trace-sync-approval",
                "PLATFORM",
                "all",
                List.of(),
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
