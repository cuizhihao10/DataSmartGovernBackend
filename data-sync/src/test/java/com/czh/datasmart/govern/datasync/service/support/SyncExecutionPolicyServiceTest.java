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
import com.czh.datasmart.govern.datasync.entity.SyncTaskDefinition;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionPolicyMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionPolicySnapshotMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskDefinitionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
                mock(SyncTaskDefinitionMapper.class),
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

        SyncTaskDefinition definition = new SyncTaskDefinition();
        definition.setSourceDatasourceId(11L);
        definition.setTargetDatasourceId(22L);
        definition.setSourceConnectorType("MYSQL");
        definition.setTargetConnectorType("POSTGRESQL");

        SyncEffectiveExecutionPolicy effective = service.resolveEffectivePolicy(
                task,
                definition,
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
                mock(SyncTaskDefinitionMapper.class),
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

        SyncTaskDefinition definition = new SyncTaskDefinition();
        definition.setSourceDatasourceId(31L);
        definition.setTargetDatasourceId(32L);
        definition.setSourceConnectorType("ORACLE");
        definition.setTargetConnectorType("SQLSERVER");

        SyncEffectiveExecutionPolicy effective = service.resolveEffectivePolicy(
                task,
                definition,
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

    /** 临时恢复覆盖只有在任务仍运行同一个 execution 且未超过授权截止时间时才能参与策略合并。 */
    @Test
    void autopilotOverrideShouldBeBoundToCurrentExecutionStateAndDeadline() {
        SyncExecutionPolicyMapper policyMapper = mock(SyncExecutionPolicyMapper.class);
        SyncExecutionPolicyService service = new SyncExecutionPolicyService(
                policyMapper,
                mock(SyncExecutionPolicySnapshotMapper.class),
                mock(SyncTaskMapper.class),
                mock(SyncTaskDefinitionMapper.class),
                mock(SyncExecutionMapper.class),
                mock(SyncDataScopeSupport.class),
                new ObjectMapper());
        SyncExecutionPolicy override = autopilotOverride(
                501L, LocalDateTime.now(ZoneOffset.UTC).plusMinutes(30));
        when(policyMapper.selectList(any())).thenReturn(List.of(override));

        SyncTask task = new SyncTask();
        task.setId(77L);
        task.setTenantId(10L);
        task.setProjectId(101L);
        task.setLastExecutionId(501L);
        task.setCurrentState("RETRYING");

        SyncEffectiveExecutionPolicy active = service.resolveEffectivePolicy(
                task, new SyncTaskDefinition(),
                new SyncActorContext(10L, 9001L, "PLATFORM_ADMINISTRATOR", "trace-active"));
        assertThat(active.matchedPolicyCodes()).contains("TASK:AUTOPILOT_RECOVERY_OVERRIDE");

        task.setLastExecutionId(502L);
        SyncEffectiveExecutionPolicy staleExecution = service.resolveEffectivePolicy(
                task, new SyncTaskDefinition(),
                new SyncActorContext(10L, 9001L, "PLATFORM_ADMINISTRATOR", "trace-stale"));
        assertThat(staleExecution.matchedPolicyCodes()).doesNotContain("TASK:AUTOPILOT_RECOVERY_OVERRIDE");

        task.setLastExecutionId(501L);
        task.setCurrentState("SUCCEEDED");
        SyncEffectiveExecutionPolicy terminalTask = service.resolveEffectivePolicy(
                task, new SyncTaskDefinition(),
                new SyncActorContext(10L, 9001L, "PLATFORM_ADMINISTRATOR", "trace-terminal"));
        assertThat(terminalTask.matchedPolicyCodes()).doesNotContain("TASK:AUTOPILOT_RECOVERY_OVERRIDE");
    }

    /** 过期或损坏的绑定材料必须 fail-closed，不能降级成普通的高优先级 TASK 策略。 */
    @Test
    void autopilotOverrideShouldIgnoreExpiredOrMalformedBinding() {
        SyncExecutionPolicyMapper policyMapper = mock(SyncExecutionPolicyMapper.class);
        SyncExecutionPolicyService service = new SyncExecutionPolicyService(
                policyMapper,
                mock(SyncExecutionPolicySnapshotMapper.class),
                mock(SyncTaskMapper.class),
                mock(SyncTaskDefinitionMapper.class),
                mock(SyncExecutionMapper.class),
                mock(SyncDataScopeSupport.class),
                new ObjectMapper());
        SyncExecutionPolicy expired = autopilotOverride(
                501L, LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1));
        when(policyMapper.selectList(any())).thenReturn(List.of(expired));

        SyncTask task = new SyncTask();
        task.setId(77L);
        task.setTenantId(10L);
        task.setProjectId(101L);
        task.setLastExecutionId(501L);
        task.setCurrentState("RUNNING");

        SyncEffectiveExecutionPolicy effective = service.resolveEffectivePolicy(
                task, new SyncTaskDefinition(),
                new SyncActorContext(10L, 9001L, "PLATFORM_ADMINISTRATOR", "trace-expired"));
        assertThat(effective.matchedPolicyCodes()).doesNotContain("TASK:AUTOPILOT_RECOVERY_OVERRIDE");

        expired.setDescription("不是结构化恢复绑定");
        SyncEffectiveExecutionPolicy malformed = service.resolveEffectivePolicy(
                task, new SyncTaskDefinition(),
                new SyncActorContext(10L, 9001L, "PLATFORM_ADMINISTRATOR", "trace-malformed"));
        assertThat(malformed.matchedPolicyCodes()).doesNotContain("TASK:AUTOPILOT_RECOVERY_OVERRIDE");
    }

    /** 构造仅供当前恢复 execution 使用的高优先级任务覆盖。 */
    private SyncExecutionPolicy autopilotOverride(Long executionId, LocalDateTime deadlineAt) {
        SyncExecutionPolicy policy = policy(
                99L, "TASK", "AUTOPILOT_RECOVERY_OVERRIDE", 10L, 101L,
                null, null, "ANY", 77L, null, 1, 128, 128, 900);
        policy.setScopeKey("TASK:77");
        policy.setPriority(10_000);
        policy.setDescription("""
                {"bindingType":"AUTOPILOT_RECOVERY_OVERRIDE","caseId":81,
                 "tenantId":10,"projectId":101,"taskId":77,"executionId":%d,
                 "authorizationDigest":"%s","policyDigest":"%s",
                 "deadlineAt":"%s","action":"TUNE_EXECUTION_POLICY"}
                """.formatted(executionId, "a".repeat(64), "b".repeat(64), deadlineAt));
        return policy;
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
