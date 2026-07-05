/**
 * @Author : Cui
 * @Date: 2026/06/29 23:47
 * @Description DataSmart Govern Backend - SyncBatchRunnerBridgePlanSupportTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.controller.dto.SyncWorkerExecutionPlanView;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.entity.SyncTemplate;
import com.czh.datasmart.govern.datasync.support.SyncExecutionState;
import com.czh.datasmart.govern.datasync.support.SyncTriggerType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批量执行器桥接计划测试。
 *
 * <p>桥接计划是从“worker 已经能看到低敏计划”走向“runner 可以被受控派发”的关键中间层。
 * 测试重点不是验证 JDBC SQL，而是验证 data-sync 不会把不适合最小 runner 的配置误判为可执行。</p>
 */
class SyncBatchRunnerBridgePlanSupportTest {

    private final SyncBatchRunnerBridgePlanSupport support = new SyncBatchRunnerBridgePlanSupport(
            new SyncFieldMappingExecutionContractSupport(new ObjectMapper()));

    @Test
    void directRelationalTemplateShouldBeReadyToDispatch() {
        SyncTemplate template = template("FULL", "MYSQL", "POSTGRESQL")
                .fieldMapping("""
                        [
                          {"sourceField":"id","targetField":"id"},
                          {"sourceField":"name","targetField":"name"}
                        ]
                        """);

        SyncBatchRunnerBridgePlan plan = support.buildPlan(execution(), task(), template,
                workerPlan("READY_WITH_WARNINGS", List.of("RETRY_POLICY_NOT_DECLARED")));

        assertThat(plan.isDispatchable()).isTrue();
        assertThat(plan.getDispatchStatus()).isEqualTo("READY_TO_DISPATCH");
        assertThat(plan.getReadStrategy()).isEqualTo("FULL_OBJECT_SCAN");
        assertThat(plan.getCheckpointType()).isEqualTo("NONE_OR_FINAL_WATERMARK");
        assertThat(plan.getSourceObjectLocator()).isEqualTo("ods.customer");
        assertThat(plan.getTargetObjectLocator()).isEqualTo("dwd.customer");
        assertThat(plan.getFieldMappingContract().getSelectedColumns()).containsExactly("id", "name");
        assertThat(plan.getOfflineRunnerContract().contractStatus())
                .isEqualTo("MINIMAL_BRIDGE_END_TO_END_SUPPORTED");
        assertThat(plan.getOfflineRunnerContract().shardPlan().shardKind()).isEqualTo("SINGLE_OBJECT");
        assertThat(plan.getOfflineRunnerContract().dataXJobExecutionContract().topologyStatus())
                .isEqualTo("MINIMAL_SINGLE_CHANNEL_RUN_ONCE_TOPOLOGY");
        assertThat(plan.getOfflineRunnerContract().dataXJobExecutionContract().taskGroups().get(0)
                .channels().get(0).writerContract().writeStrategy())
                .isEqualTo("APPEND");
        assertThat(plan.getIssueCodes()).containsExactly("RETRY_POLICY_NOT_DECLARED");
        assertThat(plan.getNextActions()).contains("DISPATCH_TO_CONNECTOR_RUNTIME_RUN_ONCE");
    }

    @Test
    void fieldRenameShouldBeDispatchableBecauseRunOnceCanAlignRowKeys() {
        SyncTemplate template = template("FULL", "MYSQL", "POSTGRESQL")
                .fieldMapping("""
                        [
                          {"sourceField":"customer_id","targetField":"id"},
                          {"sourceField":"customer_name","targetField":"name"}
                        ]
                        """);

        SyncBatchRunnerBridgePlan plan = support.buildPlan(execution(), task(), template,
                workerPlan("READY_TO_RUN", List.of()));

        assertThat(plan.isDispatchable()).isTrue();
        assertThat(plan.getDispatchStatus()).isEqualTo("READY_TO_DISPATCH");
        assertThat(plan.getFieldMappingContract().isRequiresFieldRenameTransform()).isTrue();
        assertThat(plan.getFieldMappingContract().getSelectedColumns()).containsExactly("customer_id", "customer_name");
        assertThat(plan.getFieldMappingContract().getWriteColumns()).containsExactly("id", "name");
        assertThat(plan.getIssueCodes()).doesNotContain("FIELD_MAPPING_CONTRACT_NOT_RUNNABLE_BY_MINIMAL_BRIDGE");
    }

    @Test
    void safeFilterConfigShouldBeCarriedByBridgePlan() {
        SyncTemplate template = template("FULL", "MYSQL", "POSTGRESQL")
                .filter("""
                        {
                          "logic": "AND",
                          "conditions": [
                            {"field":"status","operator":"=","value":"ACTIVE"},
                            {"field":"biz_date","operator":">=","value":"2026-01-01"}
                          ]
                        }
                        """)
                .fieldMapping("""
                        [{"sourceField":"id","targetField":"id"}]
                        """);

        SyncBatchRunnerBridgePlan plan = support.buildPlan(execution(), task(), template,
                workerPlan("READY_TO_RUN", List.of()));

        assertThat(plan.isDispatchable()).isTrue();
        assertThat(plan.getFilterConditions()).hasSize(2);
        assertThat(plan.getFilterConditions().get(0).getColumn()).isEqualTo("status");
        assertThat(plan.getFilterConditions().get(0).getOperator()).isEqualTo("EQ");
        assertThat(plan.getFilterConditions().get(0).getValue()).isEqualTo("ACTIVE");
        assertThat(plan.getIssueCodes()).doesNotContain("FILTER_CONTRACT_NOT_RUNNABLE_BY_MINIMAL_BRIDGE");
    }

    @Test
    void unsafeFilterConfigShouldBlockBridgeBeforeRealRead() {
        SyncTemplate template = template("FULL", "MYSQL", "POSTGRESQL")
                .filter("""
                        [{"field":"status or 1=1","operator":"=","value":"ACTIVE"}]
                        """)
                .fieldMapping("""
                        [{"sourceField":"id","targetField":"id"}]
                        """);

        SyncBatchRunnerBridgePlan plan = support.buildPlan(execution(), task(), template,
                workerPlan("READY_TO_RUN", List.of()));

        assertThat(plan.isDispatchable()).isFalse();
        assertThat(plan.getIssueCodes()).contains("FILTER_COLUMN_IDENTIFIER_UNSAFE",
                "FILTER_CONTRACT_NOT_RUNNABLE_BY_MINIMAL_BRIDGE");
        assertThat(plan.getNextActions()).contains("DO_NOT_DISPATCH_BATCH_RUNNER");
    }

    @Test
    void nonJdbcConnectorShouldNotBeDispatchedToJdbcBatchBridge() {
        SyncTemplate template = template("CDC_STREAMING", "KAFKA", "POSTGRESQL")
                .fieldMapping("""
                        [{"sourceField":"id","targetField":"id"}]
                        """);

        SyncBatchRunnerBridgePlan plan = support.buildPlan(execution(), task(), template,
                workerPlan("BLOCKED", List.of("CONNECTOR_COMPATIBILITY_UNSUPPORTED")));

        assertThat(plan.isDispatchable()).isFalse();
        assertThat(plan.getIssueCodes())
                .contains("WORKER_PLAN_BLOCKED",
                        "MINIMAL_JDBC_BATCH_BRIDGE_CONNECTOR_UNSUPPORTED",
                        "MINIMAL_JDBC_BATCH_BRIDGE_MODE_UNSUPPORTED");
    }

    @Test
    void multiObjectScopeShouldBeBlockedEvenWhenWorkerPlanLooksReady() {
        SyncTemplate template = template("FULL", "MYSQL", "POSTGRESQL")
                .scope("OBJECT_LIST")
                .objectMapping("""
                        {
                          "mappings": [
                            {"sourceObject":"customer","targetObject":"customer"},
                            {"sourceObject":"orders","targetObject":"orders"}
                          ]
                        }
                        """)
                .fieldMapping("""
                        [{"sourceField":"id","targetField":"id"}]
                        """);

        SyncBatchRunnerBridgePlan plan = support.buildPlan(execution(), task(), template,
                workerPlan("READY_TO_RUN", List.of()));

        assertThat(plan.isDispatchable()).isFalse();
        assertThat(plan.getIssueCodes()).contains("SCOPE_NOT_EXECUTABLE_BY_MINIMAL_RUN_ONCE_BRIDGE");
        assertThat(plan.getOfflineRunnerContract().contractStatus())
                .isEqualTo("WAITING_APPROVAL_BEFORE_RUNNER_DISPATCH");
        assertThat(plan.getOfflineRunnerContract().shardPlan().shardKind()).isEqualTo("OBJECT_FAN_OUT_EXPLICIT");
        assertThat(plan.getOfflineRunnerContract().dataXJobExecutionContract().jobKind())
                .isEqualTo("MULTI_OBJECT_FAN_OUT_JOB");
        assertThat(plan.getOfflineRunnerContract().dataXJobExecutionContract().estimatedChannelCount())
                .isEqualTo(2);
        assertThat(plan.getNextActions()).contains("DO_NOT_DISPATCH_BATCH_RUNNER");
    }

    @Test
    void destructiveOverwriteShouldWaitForApprovedBridgePolicy() {
        SyncTemplate template = template("FULL", "MYSQL", "POSTGRESQL")
                .writeStrategy("OVERWRITE")
                .fieldMapping("""
                        [{"sourceField":"id","targetField":"id"}]
                        """);

        SyncBatchRunnerBridgePlan plan = support.buildPlan(execution(), task(), template,
                workerPlan("READY_WITH_WARNINGS", List.of("DESTRUCTIVE_WRITE_STRATEGY_REQUIRES_REVIEW")));

        assertThat(plan.isDispatchable()).isFalse();
        assertThat(plan.getIssueCodes()).contains("DESTRUCTIVE_WRITE_STRATEGY_REQUIRES_APPROVED_BRIDGE_POLICY");
        assertThat(plan.getNextActions()).contains("CALL_FAIL_EXECUTION_WITH_LOW_SENSITIVE_REASON_OR_DEFER_FOR_TEMPLATE_FIX");
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
        execution.setRecordsRead(10L);
        execution.setRecordsWritten(8L);
        execution.setFailedRecordCount(1L);
        return execution;
    }

    private SyncTask task() {
        SyncTask task = new SyncTask();
        task.setId(11L);
        task.setTenantId(7L);
        task.setProjectId(101L);
        task.setWorkspaceId(301L);
        task.setTemplateId(22L);
        return task;
    }

    private TestTemplate template(String syncMode, String sourceConnectorType, String targetConnectorType) {
        TestTemplate template = new TestTemplate();
        template.setId(22L);
        template.setTenantId(7L);
        template.setProjectId(101L);
        template.setWorkspaceId(301L);
        template.setSourceDatasourceId(10001L);
        template.setTargetDatasourceId(10002L);
        template.setSourceSchemaName("ods");
        template.setSourceObjectName("customer");
        template.setTargetSchemaName("dwd");
        template.setTargetObjectName("customer");
        template.setSourceConnectorType(sourceConnectorType);
        template.setTargetConnectorType(targetConnectorType);
        template.setSyncMode(syncMode);
        template.setWriteStrategy("APPEND");
        template.setPrimaryKeyField("id");
        template.setIncrementalField("updated_at");
        template.setEnabled(true);
        return template;
    }

    private SyncWorkerExecutionPlanView workerPlan(String planStatus, List<String> issueCodes) {
        return new SyncWorkerExecutionPlanView(
                true,
                planStatus,
                7L,
                101L,
                301L,
                11L,
                88L,
                3L,
                SyncExecutionState.RUNNING.name(),
                SyncTriggerType.MANUAL.name(),
                "worker-1",
                LocalDateTime.now().plusMinutes(2),
                22L,
                10001L,
                10002L,
                "MYSQL",
                "POSTGRESQL",
                "FULL",
                "OFFLINE",
                "DATAX_STYLE_OFFLINE_READER_WRITER_RUNNER",
                "SINGLE_OBJECT",
                true,
                false,
                false,
                1,
                false,
                true,
                true,
                true,
                "APPEND",
                false,
                true,
                true,
                true,
                "SNAPSHOT_BOUNDED",
                false,
                "SEGMENT_RETRY",
                true,
                false,
                false,
                false,
                false,
                false,
                false,
                issueCodes,
                List.of("CLAIM_ALREADY_MARKED_RUNNING_DO_NOT_CALL_START"),
                List.of(),
                List.of(),
                "LOW_SENSITIVE_WORKER_PLAN_METADATA_ONLY");
    }

    /**
     * 测试专用模板子类。
     *
     * <p>链式方法只用于减少测试样板代码，生产实体仍保持普通 setter 风格。</p>
     */
    private static class TestTemplate extends SyncTemplate {

        private TestTemplate fieldMapping(String value) {
            setFieldMappingConfig(value);
            return this;
        }

        private TestTemplate objectMapping(String value) {
            setObjectMappingConfig(value);
            return this;
        }

        private TestTemplate scope(String value) {
            setSyncScopeType(value);
            return this;
        }

        private TestTemplate writeStrategy(String value) {
            setWriteStrategy(value);
            return this;
        }

        private TestTemplate filter(String value) {
            setFilterConfig(value);
            return this;
        }
    }
}
