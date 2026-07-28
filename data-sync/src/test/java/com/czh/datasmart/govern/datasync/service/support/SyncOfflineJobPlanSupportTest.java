/**
 * @Author : Cui
 * @Date: 2026/07/05 14:07
 * @Description DataSmart Govern Backend - SyncOfflineJobPlanSupportTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.controller.dto.SyncOfflineJobPlanResponse;
import com.czh.datasmart.govern.datasync.entity.SyncTaskDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 离线作业计划测试。
 *
 * <p>这组测试关注“控制面规划是否诚实”。所谓诚实，指的是：</p>
 * <p>1. FULL 单对象可以形成 DataX-style 手工/一次性全量扫描计划，并且当前最小 bridge 可执行；</p>
 * <p>2. SCHEDULED_FULL 是用户显式选择的定期全量，必须由任务层 scheduleConfig 驱动；</p>
 * <p>3. SCHEDULED_BATCH 属于离线通道，但必须由任务层调度配置和批处理窗口驱动，不能被误称为普通全量；</p>
 * <p>3. CUSTOM_SQL_QUERY 可以形成离线查询结果集计划，审批通过后可复用最小 bridge，但不能泄露 SQL 正文；</p>
 * <p>4. CDC_STREAMING 是实时通道，不应被塞进离线 runner；</p>
 * <p>5. 多对象同步可以建模和规划，但当前最小 run-once bridge 必须 fail-closed。</p>
 */
class SyncOfflineJobPlanSupportTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SyncOfflineJobPlanSupport support = new SyncOfflineJobPlanSupport(
            new SyncConnectorCapabilityRegistry(),
            new SyncTaskDefinitionScopeContractSupport(objectMapper),
            new SyncFieldMappingExecutionContractSupport(objectMapper),
            objectMapper
    );

    @Test
    void fullSingleObjectShouldBuildReadyOfflinePlanForMinimalBridge() {
        SyncOfflineJobPlanResponse response = support.buildPlan(executableSingleObjectDefinition("FULL"));

        assertThat(response.planStatus()).isEqualTo(SyncOfflineJobPlanSupport.PLAN_READY);
        assertThat(response.transferChannel()).isEqualTo("OFFLINE");
        assertThat(response.referenceRuntime()).isEqualTo("DATAX_STYLE_OFFLINE_READER_WRITER_RUNNER");
        assertThat(response.readerFamily()).isEqualTo("JDBC_READER");
        assertThat(response.writerFamily()).isEqualTo("JDBC_WRITER");
        assertThat(response.modeFamily()).isEqualTo("FULL_OBJECT_SCAN");
        assertThat(response.scheduleSemantics()).isEqualTo("MANUAL_FULL");
        assertThat(response.executableByMinimalBridge()).isTrue();
        assertThat(response.dedicatedOfflineRunnerRequired()).isFalse();
        assertThat(response.planReady()).isTrue();
    }

    @Test
    void scheduledFullShouldRequireTaskLevelScheduleAndReuseFullScanRunner() {
        SyncOfflineJobPlanResponse response = support.buildPlan(executableSingleObjectDefinition("SCHEDULED_FULL"));

        assertThat(response.planStatus()).isEqualTo(SyncOfflineJobPlanSupport.PLAN_READY);
        assertThat(response.transferChannel()).isEqualTo("OFFLINE");
        assertThat(response.modeFamily()).isEqualTo("SCHEDULED_FULL_OBJECT_SCAN");
        assertThat(response.shardStrategy()).isEqualTo("SCHEDULED_FULL_PAGE_OR_PK_RANGE_SHARD");
        assertThat(response.taskLevelScheduleRequired()).isTrue();
        assertThat(response.scheduleSemantics()).isEqualTo("TASK_LEVEL_SCHEDULE_REQUIRED_FOR_FULL_SCAN");
        assertThat(response.executableByMinimalBridge()).isTrue();
        assertThat(response.dedicatedOfflineRunnerRequired()).isFalse();
        assertThat(response.recommendedActions())
                .anyMatch(action -> action.contains("SCHEDULED_FULL 必须在创建任务时提供 scheduleConfig"));
    }

    @Test
    void scheduledBatchShouldRequireTaskLevelScheduleAndUseMinimalBridge() {
        SyncOfflineJobPlanResponse response = support.buildPlan(executableSingleObjectDefinition("SCHEDULED_BATCH"));

        assertThat(response.planStatus()).isEqualTo(SyncOfflineJobPlanSupport.PLAN_READY);
        assertThat(response.transferChannel()).isEqualTo("OFFLINE");
        assertThat(response.modeFamily()).isEqualTo("SCHEDULED_BATCH_WINDOW");
        assertThat(response.taskLevelScheduleRequired()).isTrue();
        assertThat(response.scheduleSemantics()).isEqualTo("TASK_LEVEL_SCHEDULE_REQUIRED_FOR_BATCH_WINDOW");
        assertThat(response.checkpointRequired()).isFalse();
        assertThat(response.executableByMinimalBridge()).isTrue();
        assertThat(response.dedicatedOfflineRunnerRequired()).isFalse();
        assertThat(response.failClosedReasons()).doesNotContain(
                "DEDICATED_OFFLINE_RUNNER_REQUIRED_FOR_MODE",
                "CHECKPOINT_HANDOFF_REQUIRED_FOR_OFFLINE_RUNNER"
        );
    }

    @Test
    void customSqlShouldRequireApprovalAndHideSqlBody() {
        SyncTaskDefinition definition = executableSingleObjectDefinition("CUSTOM_SQL_QUERY");
        definition.setSyncScopeType("CUSTOM_SQL_QUERY");
        definition.setCustomSqlConfig("""
                {
                  "statementRef": "managed-sql.customer-active",
                  "sql": "select id, name from customer where status = :status"
                }
                """);

        SyncOfflineJobPlanResponse response = support.buildPlan(definition);

        assertThat(response.planStatus()).isEqualTo(SyncOfflineJobPlanSupport.PLAN_READY_REQUIRES_APPROVAL);
        assertThat(response.modeFamily()).isEqualTo("CUSTOM_SQL_RESULT_SET");
        assertThat(response.approvalRequired()).isTrue();
        assertThat(response.customSqlStatementRefDeclared()).isTrue();
        assertThat(response.customSqlInlineSqlDeclared()).isTrue();
        assertThat(response.sqlStatementPolicy()).isEqualTo("STATEMENT_REF_DECLARED_LOW_SENSITIVE");
        assertThat(response.executableByMinimalBridge()).isTrue();
        assertThat(response.dedicatedOfflineRunnerRequired()).isFalse();
        assertThat(response.failClosedReasons()).doesNotContain("DEDICATED_OFFLINE_RUNNER_REQUIRED_FOR_SCOPE");
        assertThat(response.toString())
                .doesNotContain("select id")
                .doesNotContain("customer-active")
                .doesNotContain("status = :status");
    }

    @Test
    void cdcStreamingShouldBeRejectedByOfflinePlan() {
        SyncOfflineJobPlanResponse response = support.buildPlan(executableSingleObjectDefinition("CDC_STREAMING"));

        assertThat(response.planStatus()).isEqualTo(SyncOfflineJobPlanSupport.NOT_OFFLINE_CHANNEL);
        assertThat(response.transferChannel()).isEqualTo("REALTIME");
        assertThat(response.offlineChannel()).isFalse();
        assertThat(response.planReady()).isFalse();
        assertThat(response.runnerBoundary()).isEqualTo("NOT_OFFLINE_USE_REALTIME_CDC_PIPELINE");
        assertThat(response.failClosedReasons()).contains("REALTIME_CHANNEL_NOT_ACCEPTED_BY_OFFLINE_JOB_PLAN");
    }

    @Test
    void objectListShouldBePlanReadyButFailClosedForMinimalBridge() {
        SyncTaskDefinition definition = executableSingleObjectDefinition("FULL");
        definition.setSyncScopeType("OBJECT_LIST");
        definition.setObjectMappingConfig("""
                {
                  "mappings": [
                    {"sourceObject": "customer", "targetObject": "customer"},
                    {"sourceObject": "orders", "targetObject": "orders"}
                  ]
                }
                """);

        SyncOfflineJobPlanResponse response = support.buildPlan(definition);

        assertThat(response.planStatus()).isEqualTo(SyncOfflineJobPlanSupport.PLAN_READY_DEDICATED_RUNNER_REQUIRED);
        assertThat(response.planReady()).isTrue();
        assertThat(response.selectedObjectCount()).isEqualTo(2);
        assertThat(response.objectMappingDeclared()).isTrue();
        assertThat(response.shardStrategy()).isEqualTo("OBJECT_LEVEL_FAN_OUT_EXPLICIT_MAPPINGS");
        assertThat(response.executableByMinimalBridge()).isFalse();
        assertThat(response.dedicatedOfflineRunnerRequired()).isTrue();
        assertThat(response.failClosedReasons()).contains("DEDICATED_OFFLINE_RUNNER_REQUIRED_FOR_SCOPE");
    }

    private SyncTaskDefinition executableSingleObjectDefinition(String syncMode) {
        SyncTaskDefinition definition = new SyncTaskDefinition();
        definition.setId(22L);
        definition.setTenantId(7L);
        definition.setProjectId(101L);
        definition.setWorkspaceId(301L);
        definition.setSourceDatasourceId(10001L);
        definition.setTargetDatasourceId(10002L);
        definition.setSourceSchemaName("ods");
        definition.setSourceObjectName("customer");
        definition.setTargetSchemaName("dwd");
        definition.setTargetObjectName("customer");
        definition.setSourceConnectorType("MYSQL");
        definition.setTargetConnectorType("POSTGRESQL");
        definition.setSyncMode(syncMode);
        definition.setSyncScopeType("SINGLE_OBJECT");
        definition.setWriteStrategy("APPEND");
        definition.setFieldMappingConfig("""
                [
                  {"sourceField": "id", "targetField": "id"},
                  {"sourceField": "name", "targetField": "name"}
                ]
                """);
        return definition;
    }
}
