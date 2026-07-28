/**
 * @Author : Cui
 * @Date: 2026/06/29 03:18
 * @Description DataSmart Govern Backend - SyncWorkerExecutionPlanSupportTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.controller.dto.SyncWorkerExecutionPlanView;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.entity.SyncTaskDefinition;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskDefinitionMapper;
import com.czh.datasmart.govern.datasync.support.SyncExecutionState;
import com.czh.datasmart.govern.datasync.support.SyncTriggerType;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * worker 执行计划生成器测试。
 *
 * <p>这组测试不是验证真实数据库读写，而是验证 claim 后交给 worker 的“控制面执行计划”是否足够安全和可解释。
 * 如果这个契约设计不好，后续 JDBC runner 很容易为了拿配置而把字段映射、SQL、过滤条件、连接信息直接塞进 claim 响应，
 * 这会破坏数据同步产品的安全边界。</p>
 */
class SyncWorkerExecutionPlanSupportTest {

    @Test
    void readyDefinitionShouldProduceLowSensitiveWorkerPlan() {
        Fixture fixture = fixture(definition("FULL", "MYSQL", "POSTGRESQL")
                .setFieldMappingConfigForTest("{\"secret\":\"不要返回字段映射正文\"}")
                .setFilterConfigForTest("where token = 'secret'")
                .setPartitionConfigForTest("{\"partitionKey\":\"customer_id\"}")
                .setRetryPolicyForTest("{\"maxRetries\":3}")
                .setTimeoutPolicyForTest("{\"readTimeoutSeconds\":30}"));

        SyncWorkerExecutionPlanView plan = fixture.support().buildPlan(execution(), task());

        assertThat(plan.available()).isTrue();
        assertThat(plan.planStatus()).isEqualTo("READY_TO_RUN");
        assertThat(plan.transferChannel()).isEqualTo("OFFLINE");
        assertThat(plan.referenceRuntime()).isEqualTo("DATAX_STYLE_OFFLINE_READER_WRITER_RUNNER");
        assertThat(plan.connectorCompatibilitySupported()).isTrue();
        assertThat(plan.checkpointRequired()).isFalse();
        assertThat(plan.sourceObjectDeclared()).isTrue();
        assertThat(plan.targetObjectDeclared()).isTrue();
        assertThat(plan.writeStrategy()).isEqualTo("APPEND");
        assertThat(plan.workerActions()).contains("CLAIM_ALREADY_MARKED_RUNNING_DO_NOT_CALL_START");
        assertThat(plan.toString())
                .doesNotContain("不要返回字段映射正文")
                .doesNotContain("token = 'secret'")
                .doesNotContain("customer_id")
                .doesNotContain("readTimeoutSeconds");
    }

    @Test
    void incrementalDefinitionWithoutBoundaryShouldWarnWorker() {
        Fixture fixture = fixture(definition("INCREMENTAL_TIME", "MYSQL", "POSTGRESQL")
                .setIncrementalFieldForTest("updated_at")
                .setFieldMappingConfigForTest("{}")
                .setRetryPolicyForTest("{}")
                .setTimeoutPolicyForTest("{}"));

        SyncWorkerExecutionPlanView plan = fixture.support().buildPlan(execution(), task());

        assertThat(plan.planStatus()).isEqualTo("READY_WITH_WARNINGS");
        assertThat(plan.transferChannel()).isEqualTo("OFFLINE");
        assertThat(plan.checkpointRequired()).isTrue();
        assertThat(plan.issueCodes()).contains("CHECKPOINT_BOUNDARY_NOT_DECLARED");
        assertThat(plan.workerActions()).contains("WRITE_CHECKPOINT_AFTER_EACH_SAFE_BATCH");
    }

    @Test
    void missingObjectBindingShouldBlockWorkerBeforeReadOrWrite() {
        Fixture fixture = fixture(definition("FULL", "MYSQL", "POSTGRESQL")
                .setSourceObjectNameForTest(null)
                .setTargetObjectNameForTest(null)
                .setFieldMappingConfigForTest("{}")
                .setRetryPolicyForTest("{}")
                .setTimeoutPolicyForTest("{}"));

        SyncWorkerExecutionPlanView plan = fixture.support().buildPlan(execution(), task());

        assertThat(plan.planStatus()).isEqualTo("BLOCKED");
        assertThat(plan.sourceObjectDeclared()).isFalse();
        assertThat(plan.targetObjectDeclared()).isFalse();
        assertThat(plan.issueCodes()).contains("SOURCE_OBJECT_NOT_DECLARED", "TARGET_OBJECT_NOT_DECLARED");
        assertThat(plan.workerActions()).contains("DO_NOT_READ_OR_WRITE_DATA");
    }

    @Test
    void conflictWriteWithoutPrimaryKeyShouldBlockWorker() {
        Fixture fixture = fixture(definition("FULL", "MYSQL", "POSTGRESQL")
                .setWriteStrategyForTest("UPSERT")
                .setFieldMappingConfigForTest("{}")
                .setRetryPolicyForTest("{}")
                .setTimeoutPolicyForTest("{}"));

        SyncWorkerExecutionPlanView plan = fixture.support().buildPlan(execution(), task());

        assertThat(plan.planStatus()).isEqualTo("BLOCKED");
        assertThat(plan.writeStrategyRequiresConflictKey()).isTrue();
        assertThat(plan.primaryKeyDeclared()).isFalse();
        assertThat(plan.issueCodes()).contains("PRIMARY_KEY_NOT_DECLARED_FOR_CONFLICT_WRITE");
    }

    @Test
    void unsupportedConnectorModeShouldBlockWorker() {
        Fixture fixture = fixture(definition("FULL", "KAFKA", "POSTGRESQL")
                .setFieldMappingConfigForTest("{}")
                .setRetryPolicyForTest("{}")
                .setTimeoutPolicyForTest("{}"));

        SyncWorkerExecutionPlanView plan = fixture.support().buildPlan(execution(), task());

        assertThat(plan.planStatus()).isEqualTo("BLOCKED");
        assertThat(plan.connectorCompatibilitySupported()).isFalse();
        assertThat(plan.issueCodes()).contains("CONNECTOR_COMPATIBILITY_UNSUPPORTED");
        assertThat(plan.workerActions()).contains("DO_NOT_READ_OR_WRITE_DATA");
    }

    @Test
    void missingDefinitionShouldReturnUnavailableBlockedPlan() {
        SyncTaskDefinitionMapper taskDefinitionMapper = mock(SyncTaskDefinitionMapper.class);
        when(taskDefinitionMapper.selectById(11L)).thenReturn(null);
        SyncWorkerExecutionPlanSupport support = new SyncWorkerExecutionPlanSupport(
                taskDefinitionMapper, new SyncConnectorCapabilityRegistry());

        SyncWorkerExecutionPlanView plan = support.buildPlan(execution(), task());

        assertThat(plan.available()).isFalse();
        assertThat(plan.planStatus()).isEqualTo("BLOCKED");
        assertThat(plan.issueCodes()).contains("TASK_DEFINITION_NOT_FOUND");
        assertThat(plan.workerActions()).contains("DO_NOT_READ_OR_WRITE_DATA");
    }

    private Fixture fixture(SyncTaskDefinition definition) {
        SyncTaskDefinitionMapper taskDefinitionMapper = mock(SyncTaskDefinitionMapper.class);
        when(taskDefinitionMapper.selectById(11L)).thenReturn(definition);
        return new Fixture(new SyncWorkerExecutionPlanSupport(taskDefinitionMapper, new SyncConnectorCapabilityRegistry()));
    }

    private SyncExecution execution() {
        SyncExecution execution = new SyncExecution();
        execution.setId(88L);
        execution.setTenantId(7L);
        execution.setProjectId(101L);
        execution.setWorkspaceId(301L);
        execution.setSyncTaskId(11L);
        execution.setExecutionNo(3L);
        execution.setExecutionState(SyncExecutionState.RUNNING.name());
        execution.setTriggerType(SyncTriggerType.MANUAL.name());
        execution.setExecutorId("worker-1");
        execution.setLeaseExpireTime(LocalDateTime.now().plusMinutes(2));
        return execution;
    }

    private SyncTask task() {
        SyncTask task = new SyncTask();
        task.setId(11L);
        task.setTenantId(7L);
        task.setProjectId(101L);
        task.setWorkspaceId(301L);
        return task;
    }

    private TestDefinition definition(String syncMode, String sourceConnectorType, String targetConnectorType) {
        TestDefinition definition = new TestDefinition();
        definition.setId(11L);
        definition.setTenantId(7L);
        definition.setProjectId(101L);
        definition.setWorkspaceId(301L);
        definition.setSourceDatasourceId(10001L);
        definition.setTargetDatasourceId(10002L);
        definition.setSourceSchemaName("ods");
        definition.setSourceObjectName("customer");
        definition.setTargetSchemaName("dwd");
        definition.setTargetObjectName("customer");
        definition.setSourceConnectorType(sourceConnectorType);
        definition.setTargetConnectorType(targetConnectorType);
        definition.setSyncMode(syncMode);
        definition.setWriteStrategy("APPEND");
        definition.setEnabled(true);
        return definition;
    }

    private record Fixture(SyncWorkerExecutionPlanSupport support) {
    }

    /**
     * 测试专用模板子类。
     *
     * <p>这里使用链式方法只是为了让测试用例更聚焦业务差异，例如“缺少增量边界”或“配置正文不能外泄”。
     * 生产代码仍使用普通 {@link SyncTaskDefinition} 实体。</p>
     */
    private static class TestDefinition extends SyncTaskDefinition {

        private TestDefinition setFieldMappingConfigForTest(String value) {
            setFieldMappingConfig(value);
            return this;
        }

        private TestDefinition setFilterConfigForTest(String value) {
            setFilterConfig(value);
            return this;
        }

        private TestDefinition setPartitionConfigForTest(String value) {
            setPartitionConfig(value);
            return this;
        }

        private TestDefinition setRetryPolicyForTest(String value) {
            setRetryPolicy(value);
            return this;
        }

        private TestDefinition setTimeoutPolicyForTest(String value) {
            setTimeoutPolicy(value);
            return this;
        }

        private TestDefinition setSourceObjectNameForTest(String value) {
            setSourceObjectName(value);
            return this;
        }

        private TestDefinition setTargetObjectNameForTest(String value) {
            setTargetObjectName(value);
            return this;
        }

        private TestDefinition setWriteStrategyForTest(String value) {
            setWriteStrategy(value);
            return this;
        }

        private TestDefinition setIncrementalFieldForTest(String value) {
            setIncrementalField(value);
            return this;
        }
    }
}
