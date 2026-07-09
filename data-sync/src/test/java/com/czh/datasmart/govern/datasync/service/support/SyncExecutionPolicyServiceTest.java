/**
 * @Author : Cui
 * @Date: 2026/07/09 23:31
 * @Description DataSmart Govern Backend - SyncExecutionPolicyServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.entity.SyncExecutionPolicy;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.entity.SyncTemplate;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionPolicyMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionPolicySnapshotMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncTemplateMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 执行策略分层解析测试。
 *
 * <p>策略允许只覆盖少量字段，因此不能简单选择“最具体的一条策略”。正确做法是从系统默认开始逐层合并：
 * SYSTEM -> CONNECTOR -> DATASOURCE -> PROJECT -> TASK，后层字段只覆盖自身非空值。
 * 这样既能统一治理，又能对单项目或单任务做例外调整。</p>
 */
class SyncExecutionPolicyServiceTest {

    @Test
    void taskPolicyShouldOverrideProjectDatasourceConnectorAndSystemPoliciesFieldByField() {
        SyncExecutionPolicyMapper policyMapper = mock(SyncExecutionPolicyMapper.class);
        SyncExecutionPolicyService service = new SyncExecutionPolicyService(
                policyMapper,
                mock(SyncExecutionPolicySnapshotMapper.class),
                mock(SyncTaskMapper.class),
                mock(SyncTemplateMapper.class),
                mock(SyncExecutionMapper.class),
                mock(SyncDataScopeSupport.class),
                new ObjectMapper()
        );

        when(policyMapper.selectList(any())).thenReturn(List.of(
                policy(1L, "TASK", "TASK_OVERRIDE", 10L, 101L, null, null, null, 77L,
                        null, 2, null, null, 900),
                policy(2L, "PROJECT", "PROJECT_DEFAULT", 10L, 101L, null, null, null, null,
                        null, 4, null, 750, null),
                policy(3L, "DATASOURCE", "SOURCE_11_LIMIT", 10L, null, 11L, null, "SOURCE", null,
                        null, 5, null, null, null),
                policy(4L, "CONNECTOR", "DEFAULT_SOURCE_READ", 0L, null, null, null, "SOURCE", null,
                        null, 6, 1000, null, null),
                policy(5L, "CONNECTOR", "DEFAULT_TARGET_WRITE", 0L, null, null, null, "TARGET", null,
                        null, 7, null, 900, null),
                policy(6L, "SYSTEM", "SYSTEM_DEFAULT", 0L, null, null, null, "ANY", null,
                        500000L, 8, null, null, 600)
        ));

        SyncTask task = new SyncTask();
        task.setId(77L);
        task.setTenantId(10L);
        task.setProjectId(101L);

        SyncTemplate template = new SyncTemplate();
        template.setSourceDatasourceId(11L);
        template.setTargetDatasourceId(22L);
        template.setSourceConnectorType("MYSQL");
        template.setTargetConnectorType("POSTGRESQL");

        SyncEffectiveExecutionPolicy effective = service.resolveEffectivePolicy(
                task,
                template,
                new SyncActorContext(10L, 9001L, "PLATFORM_ADMINISTRATOR", "trace-policy-test")
        );

        assertThat(effective.matchedPolicyCodes()).containsExactly(
                "BUILTIN_DEFAULT",
                "SYSTEM:SYSTEM_DEFAULT",
                "CONNECTOR:DEFAULT_SOURCE_READ",
                "CONNECTOR:DEFAULT_TARGET_WRITE",
                "DATASOURCE:SOURCE_11_LIMIT",
                "PROJECT:PROJECT_DEFAULT",
                "TASK:TASK_OVERRIDE"
        );
        assertThat(effective.targetRowsPerShard()).isEqualTo(500000L);
        assertThat(effective.readBatchSize()).isEqualTo(1000);
        assertThat(effective.writeBatchSize()).isEqualTo(750);
        assertThat(effective.maxChannel()).isEqualTo(2);
        assertThat(effective.timeoutSeconds()).isEqualTo(900);
    }

    @Test
    void genericSourceAndTargetPoliciesShouldMatchAnyConnectorFamily() {
        SyncExecutionPolicyMapper policyMapper = mock(SyncExecutionPolicyMapper.class);
        SyncExecutionPolicyService service = new SyncExecutionPolicyService(
                policyMapper,
                mock(SyncExecutionPolicySnapshotMapper.class),
                mock(SyncTaskMapper.class),
                mock(SyncTemplateMapper.class),
                mock(SyncExecutionMapper.class),
                mock(SyncDataScopeSupport.class),
                new ObjectMapper()
        );

        when(policyMapper.selectList(any())).thenReturn(List.of(
                policy(1L, "SYSTEM", "SYSTEM_DEFAULT", 0L, null, null, null, "ANY", null,
                        200000L, 2, 128, 128, 600),
                policy(2L, "CONNECTOR", "DEFAULT_SOURCE_READ", 0L, null, null, null, "SOURCE", null,
                        null, 3, 2048, null, null),
                policy(3L, "CONNECTOR", "DEFAULT_TARGET_WRITE", 0L, null, null, null, "TARGET", null,
                        null, 4, null, 1024, null)
        ));

        SyncTask task = new SyncTask();
        task.setId(88L);
        task.setTenantId(10L);
        task.setProjectId(101L);

        SyncTemplate template = new SyncTemplate();
        template.setSourceDatasourceId(31L);
        template.setTargetDatasourceId(32L);
        template.setSourceConnectorType("ORACLE");
        template.setTargetConnectorType("SQLSERVER");

        SyncEffectiveExecutionPolicy effective = service.resolveEffectivePolicy(
                task,
                template,
                new SyncActorContext(10L, 9001L, "PLATFORM_ADMINISTRATOR", "trace-generic-policy-test")
        );

        /*
         * 这个断言保护的是产品语义，而不是单纯的代码分支：
         * 默认读取/默认写入策略必须跨连接器生效，不能因为当前测试任务不是 MySQL -> PostgreSQL 就退回内置默认值。
         */
        assertThat(effective.matchedPolicyCodes()).containsExactly(
                "BUILTIN_DEFAULT",
                "SYSTEM:SYSTEM_DEFAULT",
                "CONNECTOR:DEFAULT_SOURCE_READ",
                "CONNECTOR:DEFAULT_TARGET_WRITE"
        );
        assertThat(effective.readBatchSize()).isEqualTo(2048);
        assertThat(effective.writeBatchSize()).isEqualTo(1024);
    }

    private SyncExecutionPolicy policy(Long id,
                                       String scopeType,
                                       String policyCode,
                                       Long tenantId,
                                       Long projectId,
                                       Long datasourceId,
                                       String connectorType,
                                       String connectorRole,
                                       Long syncTaskId,
                                       Long targetRowsPerShard,
                                       Integer maxChannel,
                                       Integer readBatchSize,
                                       Integer writeBatchSize,
                                       Integer timeoutSeconds) {
        SyncExecutionPolicy policy = new SyncExecutionPolicy();
        policy.setId(id);
        policy.setTenantId(tenantId);
        policy.setProjectId(projectId);
        policy.setScopeType(scopeType);
        policy.setScopeKey(scopeType + ":" + id);
        policy.setPolicyCode(policyCode);
        policy.setPolicyName(policyCode);
        policy.setEnabled(Boolean.TRUE);
        policy.setDatasourceId(datasourceId);
        policy.setConnectorType(connectorType);
        policy.setConnectorRole(connectorRole);
        policy.setSyncTaskId(syncTaskId);
        policy.setTargetRowsPerShard(targetRowsPerShard);
        policy.setMaxChannel(maxChannel);
        policy.setReadBatchSize(readBatchSize);
        policy.setWriteBatchSize(writeBatchSize);
        policy.setTimeoutSeconds(timeoutSeconds);
        policy.setPriority(100);
        return policy;
    }
}
