/**
 * @Author : Cui
 * @Date: 2026/07/05 13:45
 * @Description DataSmart Govern Backend - SyncTaskDefinitionExecutionPrecheckSupportTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskDefinitionExecutionPrecheckResponse;
import com.czh.datasmart.govern.datasync.entity.SyncTaskDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 同步任务定义执行预检查测试。
 *
 * <p>预检查是用户点击“运行任务”前的最后一道控制面闸门。它需要回答两个不同问题：
 * “这个模板配置是否合规”和“当前 runner 是否真的能执行”。这两个问题不能混为一谈，否则产品会出现最危险的状态：
 * 页面允许配置多表/整库/自定义 SQL，但底层仍用单表 run-once runner 悄悄执行。</p>
 */
class SyncTaskDefinitionExecutionPrecheckSupportTest {

    private final SyncTaskDefinitionExecutionPrecheckSupport support = new SyncTaskDefinitionExecutionPrecheckSupport(
            new SyncConnectorCapabilityRegistry(),
            new SyncTaskDefinitionScopeContractSupport(new ObjectMapper()),
            new SyncFieldMappingExecutionContractSupport(new ObjectMapper()),
            new SyncFilterExecutionContractSupport(new ObjectMapper()),
            null
    );

    @Test
    void singleObjectFullSyncWithDirectFieldMappingShouldBeReadyToExecute() {
        SyncTaskDefinition definition = executableSingleObjectDefinition("FULL");

        SyncTaskDefinitionExecutionPrecheckResponse response = support.precheck(definition);

        assertThat(response.precheckStatus()).isEqualTo(SyncTaskDefinitionExecutionPrecheckSupport.READY_TO_EXECUTE);
        assertThat(response.transferChannel()).isEqualTo("OFFLINE");
        assertThat(response.referenceRuntime()).isEqualTo("DATAX_STYLE_OFFLINE_READER_WRITER_RUNNER");
        assertThat(response.canStartExecution()).isTrue();
        assertThat(response.executableByCurrentRunner()).isTrue();
        assertThat(response.issueCodes()).doesNotContain("SCOPE_NOT_EXECUTABLE_BY_MINIMAL_RUN_ONCE_BRIDGE");
    }

    @Test
    void objectListShouldBeExecutableByFanOutWithoutTaskCreationApproval() {
        SyncTaskDefinition definition = executableSingleObjectDefinition("FULL");
        definition.setSyncScopeType("OBJECT_LIST");
        definition.setObjectMappingConfig("""
                {
                  "mappings": [
                    {"sourceObject":"customer","targetObject":"customer"},
                    {"sourceObject":"orders","targetObject":"orders"}
                  ]
                }
                """);

        SyncTaskDefinitionExecutionPrecheckResponse response = support.precheck(definition);

        assertThat(response.precheckStatus()).isEqualTo(SyncTaskDefinitionExecutionPrecheckSupport.READY_TO_EXECUTE);
        assertThat(response.canCreateTaskDraft()).isTrue();
        assertThat(response.canStartExecution()).isTrue();
        assertThat(response.executableByCurrentRunner()).isTrue();
        assertThat(response.approvalRequired()).isFalse();
        assertThat(response.issueCodes()).doesNotContain("SCOPE_NOT_EXECUTABLE_BY_MINIMAL_RUN_ONCE_BRIDGE");
    }

    @Test
    void incrementalModeShouldBeBlockedBecauseItIsNoLongerUserSelectableTransferMode() {
        SyncTaskDefinition definition = executableSingleObjectDefinition("INCREMENTAL_TIME");
        definition.setIncrementalField("updated_at");

        SyncTaskDefinitionExecutionPrecheckResponse response = support.precheck(definition);

        assertThat(response.precheckStatus()).isEqualTo(SyncTaskDefinitionExecutionPrecheckSupport.BLOCKED);
        assertThat(response.transferChannel()).isEqualTo("OFFLINE");
        assertThat(response.checkpointRequired()).isTrue();
        assertThat(response.checkpointHandoffSupported()).isFalse();
        assertThat(response.issueCodes()).contains(
                "SYNC_MODE_NOT_USER_SELECTABLE_TRANSFER_MODE",
                "CHECKPOINT_HANDOFF_NOT_IMPLEMENTED");
    }

    @Test
    void cdcStreamingShouldBeRealtimeChannelButNotExecutableByMinimalBridge() {
        SyncTaskDefinition definition = executableSingleObjectDefinition("CDC_STREAMING");
        definition.setWriteStrategy(null);

        SyncTaskDefinitionExecutionPrecheckResponse response = support.precheck(definition);

        assertThat(response.transferChannel()).isEqualTo("REALTIME");
        assertThat(response.referenceRuntime()).isEqualTo("DEBEZIUM_KAFKA_CONNECT_CDC_PIPELINE");
        assertThat(response.precheckStatus()).isEqualTo(SyncTaskDefinitionExecutionPrecheckSupport.NOT_SUPPORTED_BY_CURRENT_RUNNER);
        assertThat(response.canStartExecution()).isFalse();
        assertThat(response.issueCodes()).doesNotContain("REALTIME_WRITE_STRATEGY_MUST_BE_MERGE");
    }

    @Test
    void cdcStreamingShouldRejectExplicitInsertWriteStrategy() {
        SyncTaskDefinition definition = executableSingleObjectDefinition("CDC_STREAMING");
        definition.setWriteStrategy("INSERT");

        SyncTaskDefinitionExecutionPrecheckResponse response = support.precheck(definition);

        assertThat(response.precheckStatus()).isEqualTo(SyncTaskDefinitionExecutionPrecheckSupport.BLOCKED);
        assertThat(response.issueCodes()).contains("REALTIME_WRITE_STRATEGY_MUST_BE_MERGE");
        assertThat(response.recommendedActions()).anyMatch(action -> action.contains("实时同步模式不需要用户选择写入策略"));
    }

    @Test
    void scheduledBatchShouldRequireWindowBoundary() {
        SyncTaskDefinition definition = executableSingleObjectDefinition("SCHEDULED_BATCH");
        definition.setWriteStrategy("UPDATE");

        SyncTaskDefinitionExecutionPrecheckResponse response = support.precheck(definition);

        assertThat(response.precheckStatus()).isEqualTo(SyncTaskDefinitionExecutionPrecheckSupport.BLOCKED);
        assertThat(response.issueCodes()).contains("SCHEDULED_BATCH_WINDOW_NOT_DECLARED");
    }

    @Test
    void scheduledFullInsertShouldBeBlockedWhenTargetIsReusedWithoutSnapshotPolicy() {
        SyncTaskDefinition definition = executableSingleObjectDefinition("SCHEDULED_FULL");
        definition.setWriteStrategy("INSERT");

        SyncTaskDefinitionExecutionPrecheckResponse response = support.precheck(definition);

        assertThat(response.precheckStatus()).isEqualTo(SyncTaskDefinitionExecutionPrecheckSupport.BLOCKED);
        assertThat(response.issueCodes()).contains("SCHEDULED_FULL_INSERT_TARGET_REUSE_UNSAFE");
    }

    @Test
    void unsafeCustomSqlShouldBeBlockedBeforeTaskExecution() {
        SyncTaskDefinition definition = executableSingleObjectDefinition("CUSTOM_SQL_QUERY");
        definition.setSyncScopeType("CUSTOM_SQL_QUERY");
        definition.setCustomSqlConfig("""
                {"sql":"delete from customer where status = 'inactive'"}
                """);

        SyncTaskDefinitionExecutionPrecheckResponse response = support.precheck(definition);

        assertThat(response.precheckStatus()).isEqualTo(SyncTaskDefinitionExecutionPrecheckSupport.BLOCKED);
        assertThat(response.transferChannel()).isEqualTo("OFFLINE");
        assertThat(response.canStartExecution()).isFalse();
        assertThat(response.customSqlSafetyPassed()).isFalse();
        assertThat(response.issueCodes()).contains("CUSTOM_SQL_RAW_SQL_UNSAFE");
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
                [{"sourceField":"id","targetField":"id"},{"sourceField":"name","targetField":"name"}]
                """);
        return definition;
    }
}
