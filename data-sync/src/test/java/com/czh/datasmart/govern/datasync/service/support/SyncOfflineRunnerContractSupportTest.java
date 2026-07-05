/**
 * @Author : Cui
 * @Date: 2026/07/05 14:26
 * @Description DataSmart Govern Backend - SyncOfflineRunnerContractSupportTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.controller.dto.SyncOfflineJobPlanResponse;
import com.czh.datasmart.govern.datasync.entity.SyncTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 离线 Runner 合同测试。
 *
 * <p>这组测试证明新增合同不是“又一个 DTO”，而是把 DataX-style 离线执行器接入前必须明确的调度事实固定下来：</p>
 * <p>1. FULL 单对象可以被当前最小 run-once bridge 端到端处理；</p>
 * <p>2. SCHEDULED_BATCH 虽然属于 OFFLINE，但需要调度窗口与 checkpoint handoff，因此必须等待专用 Runner 或补齐交接机制；</p>
 * <p>3. CUSTOM_SQL_QUERY 必须保留低敏边界，合同中只出现策略，不出现 SQL 正文或 statementRef 值。</p>
 */
class SyncOfflineRunnerContractSupportTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SyncOfflineJobPlanSupport offlineJobPlanSupport = new SyncOfflineJobPlanSupport(
            new SyncConnectorCapabilityRegistry(),
            new SyncTemplateScopeContractSupport(objectMapper),
            new SyncFieldMappingExecutionContractSupport(objectMapper),
            objectMapper
    );
    private final SyncOfflineRunnerContractSupport contractSupport = new SyncOfflineRunnerContractSupport();

    @Test
    void fullSingleObjectPlanShouldBecomeMinimalBridgeContract() {
        SyncOfflineJobPlanResponse plan = offlineJobPlanSupport.buildPlan(template("FULL"));

        SyncOfflineRunnerJobContract contract = contractSupport.buildFromOfflinePlan(plan);

        assertThat(contract.contractVersion()).isEqualTo(SyncOfflineRunnerContractSupport.CONTRACT_VERSION);
        assertThat(contract.contractStatus()).isEqualTo("MINIMAL_BRIDGE_END_TO_END_SUPPORTED");
        assertThat(contract.transferChannel()).isEqualTo("OFFLINE");
        assertThat(contract.readerFamily()).isEqualTo("JDBC_READER");
        assertThat(contract.writerFamily()).isEqualTo("JDBC_WRITER");
        assertThat(contract.minimalBridgeDispatchable()).isTrue();
        assertThat(contract.minimalBridgeEndToEndSupported()).isTrue();
        assertThat(contract.shardPlan().shardKind()).isEqualTo("SINGLE_OBJECT");
        assertThat(contract.shardPlan().estimatedShardCount()).isEqualTo(1);
        assertThat(contract.reportContract().lowCardinalityMetricsRequired()).isTrue();
        assertThat(contract.dataXJobExecutionContract().topologyStatus())
                .isEqualTo("MINIMAL_SINGLE_CHANNEL_RUN_ONCE_TOPOLOGY");
        assertThat(contract.dataXJobExecutionContract().jobKind()).isEqualTo("SINGLE_OBJECT_JOB");
        assertThat(contract.dataXJobExecutionContract().estimatedTaskGroupCount()).isEqualTo(1);
        assertThat(contract.dataXJobExecutionContract().estimatedChannelCount()).isEqualTo(1);
        assertThat(contract.dataXJobExecutionContract().taskGroups()).hasSize(1);
        assertThat(contract.dataXJobExecutionContract().taskGroups().get(0).channels()).hasSize(1);
        assertThat(contract.dataXJobExecutionContract().taskGroups().get(0).channels().get(0)
                .readerContract().fetchPolicy())
                .isEqualTo("LIMIT_OFFSET_WITH_STABLE_ORDER_AND_MAX_BATCH_GUARD");
        assertThat(contract.dataXJobExecutionContract().runtimeSafetyPolicy().forbiddenPayloads())
                .contains("rawSql", "connectionUrl", "rowPayload");
    }

    @Test
    void scheduledBatchContractShouldRequireCheckpointAndScheduleAwareRunner() {
        SyncOfflineJobPlanResponse plan = offlineJobPlanSupport.buildPlan(template("SCHEDULED_BATCH"));

        SyncOfflineRunnerJobContract contract = contractSupport.buildFromOfflinePlan(plan);

        assertThat(contract.contractStatus()).isEqualTo("DEDICATED_OFFLINE_RUNNER_REQUIRED");
        assertThat(contract.checkpointRequired()).isTrue();
        assertThat(contract.taskLevelScheduleRequired()).isTrue();
        assertThat(contract.minimalBridgeEndToEndSupported()).isFalse();
        assertThat(contract.shardPlan().shardKind()).isEqualTo("SCHEDULED_WINDOW");
        assertThat(contract.shardPlan().requiredRunnerCapabilities())
                .contains("CHECKPOINT_HANDOFF", "TASK_LEVEL_SCHEDULE_WINDOW");
        assertThat(contract.reportContract().checkpointReportPolicy())
                .isEqualTo("CHECKPOINT_REF_OR_DIGEST_ONLY_NO_RAW_VALUE");
        assertThat(contract.dataXJobExecutionContract().topologyStatus())
                .isEqualTo("DATAX_TOPOLOGY_REQUIRES_CHECKPOINT_HANDOFF");
        assertThat(contract.dataXJobExecutionContract().requiredRunnerCapabilities())
                .contains("DURABLE_CHECKPOINT_HANDOFF", "DATAX_JOB_TASKGROUP_CHANNEL_TOPOLOGY");
        assertThat(contract.dataXJobExecutionContract().taskGroups().get(0).schedulingPolicy())
                .isEqualTo("TASK_LEVEL_SCHEDULE_WINDOW_REQUIRED");
    }

    @Test
    void customSqlContractShouldNotExposeSqlOrStatementReferenceValue() {
        SyncTemplate template = template("CUSTOM_SQL_QUERY");
        template.setSyncScopeType("CUSTOM_SQL_QUERY");
        template.setCustomSqlConfig("""
                {
                  "statementRef": "managed-sql.customer-active",
                  "sql": "select id, name from customer where status = :status"
                }
                """);
        SyncOfflineJobPlanResponse plan = offlineJobPlanSupport.buildPlan(template);

        SyncOfflineRunnerJobContract contract = contractSupport.buildFromOfflinePlan(plan);

        assertThat(contract.contractStatus()).isEqualTo("WAITING_APPROVAL_BEFORE_RUNNER_DISPATCH");
        assertThat(contract.approvalRequired()).isTrue();
        assertThat(contract.shardPlan().shardKind()).isEqualTo("CUSTOM_SQL_RESULT_SET");
        assertThat(contract.customSqlStatementPolicy()).isEqualTo("STATEMENT_REF_DECLARED_LOW_SENSITIVE");
        assertThat(contract.shardPlan().requiredRunnerCapabilities())
                .contains("MANAGED_STATEMENT_REF_OR_READ_ONLY_SQL_EXECUTION");
        assertThat(contract.dataXJobExecutionContract().jobKind()).isEqualTo("CUSTOM_SQL_RESULT_SET_JOB");
        assertThat(contract.dataXJobExecutionContract().taskGroups().get(0).channels().get(0)
                .readerContract().customSqlPolicy())
                .isEqualTo("STATEMENT_REF_DECLARED_LOW_SENSITIVE");
        assertThat(contract.toString())
                .doesNotContain("select id")
                .doesNotContain("customer-active")
                .doesNotContain("status = :status");
    }

    private SyncTemplate template(String syncMode) {
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
