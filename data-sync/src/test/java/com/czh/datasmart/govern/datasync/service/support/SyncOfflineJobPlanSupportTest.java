/**
 * @Author : Cui
 * @Date: 2026/07/05 14:07
 * @Description DataSmart Govern Backend - SyncOfflineJobPlanSupportTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.controller.dto.SyncOfflineJobPlanResponse;
import com.czh.datasmart.govern.datasync.entity.SyncTemplate;
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
            new SyncTemplateScopeContractSupport(objectMapper),
            new SyncFieldMappingExecutionContractSupport(objectMapper),
            objectMapper
    );

    @Test
    void fullSingleObjectShouldBuildReadyOfflinePlanForMinimalBridge() {
        SyncOfflineJobPlanResponse response = support.buildPlan(executableSingleObjectTemplate("FULL"));

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
        SyncOfflineJobPlanResponse response = support.buildPlan(executableSingleObjectTemplate("SCHEDULED_FULL"));

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
        SyncOfflineJobPlanResponse response = support.buildPlan(executableSingleObjectTemplate("SCHEDULED_BATCH"));

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
        SyncTemplate template = executableSingleObjectTemplate("CUSTOM_SQL_QUERY");
        template.setSyncScopeType("CUSTOM_SQL_QUERY");
        template.setCustomSqlConfig("""
                {
                  "statementRef": "managed-sql.customer-active",
                  "sql": "select id, name from customer where status = :status"
                }
                """);

        SyncOfflineJobPlanResponse response = support.buildPlan(template);

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
        SyncOfflineJobPlanResponse response = support.buildPlan(executableSingleObjectTemplate("CDC_STREAMING"));

        assertThat(response.planStatus()).isEqualTo(SyncOfflineJobPlanSupport.NOT_OFFLINE_CHANNEL);
        assertThat(response.transferChannel()).isEqualTo("REALTIME");
        assertThat(response.offlineChannel()).isFalse();
        assertThat(response.planReady()).isFalse();
        assertThat(response.runnerBoundary()).isEqualTo("NOT_OFFLINE_USE_REALTIME_CDC_PIPELINE");
        assertThat(response.failClosedReasons()).contains("REALTIME_CHANNEL_NOT_ACCEPTED_BY_OFFLINE_JOB_PLAN");
    }

    @Test
    void objectListShouldBePlanReadyButFailClosedForMinimalBridge() {
        SyncTemplate template = executableSingleObjectTemplate("FULL");
        template.setSyncScopeType("OBJECT_LIST");
        template.setObjectMappingConfig("""
                {
                  "mappings": [
                    {"sourceObject": "customer", "targetObject": "customer"},
                    {"sourceObject": "orders", "targetObject": "orders"}
                  ]
                }
                """);

        SyncOfflineJobPlanResponse response = support.buildPlan(template);

        assertThat(response.planStatus()).isEqualTo(SyncOfflineJobPlanSupport.PLAN_READY_REQUIRES_APPROVAL);
        assertThat(response.planReady()).isTrue();
        assertThat(response.selectedObjectCount()).isEqualTo(2);
        assertThat(response.objectMappingDeclared()).isTrue();
        assertThat(response.shardStrategy()).isEqualTo("OBJECT_LEVEL_FAN_OUT_EXPLICIT_MAPPINGS");
        assertThat(response.executableByMinimalBridge()).isFalse();
        assertThat(response.dedicatedOfflineRunnerRequired()).isTrue();
        assertThat(response.failClosedReasons()).contains("DEDICATED_OFFLINE_RUNNER_REQUIRED_FOR_SCOPE");
    }

    private SyncTemplate executableSingleObjectTemplate(String syncMode) {
        SyncTemplate template = new SyncTemplate();
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
        template.setSourceConnectorType("MYSQL");
        template.setTargetConnectorType("POSTGRESQL");
        template.setSyncMode(syncMode);
        template.setSyncScopeType("SINGLE_OBJECT");
        template.setWriteStrategy("APPEND");
        template.setFieldMappingConfig("""
                [
                  {"sourceField": "id", "targetField": "id"},
                  {"sourceField": "name", "targetField": "name"}
                ]
                """);
        return template;
    }
}
