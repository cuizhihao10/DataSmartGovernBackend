/**
 * @Author : Cui
 * @Date: 2026/07/05 13:45
 * @Description DataSmart Govern Backend - SyncTemplateExecutionPrecheckSupportTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.controller.dto.SyncTemplateExecutionPrecheckResponse;
import com.czh.datasmart.govern.datasync.entity.SyncTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 同步模板执行预检查测试。
 *
 * <p>预检查是用户点击“运行任务”前的最后一道控制面闸门。它需要回答两个不同问题：
 * “这个模板配置是否合规”和“当前 runner 是否真的能执行”。这两个问题不能混为一谈，否则产品会出现最危险的状态：
 * 页面允许配置多表/整库/自定义 SQL，但底层仍用单表 run-once runner 悄悄执行。</p>
 */
class SyncTemplateExecutionPrecheckSupportTest {

    private final SyncTemplateExecutionPrecheckSupport support = new SyncTemplateExecutionPrecheckSupport(
            new SyncConnectorCapabilityRegistry(),
            new SyncTemplateScopeContractSupport(new ObjectMapper()),
            new SyncFieldMappingExecutionContractSupport(new ObjectMapper())
    );

    @Test
    void singleObjectFullSyncWithDirectFieldMappingShouldBeReadyToExecute() {
        SyncTemplate template = executableSingleObjectTemplate("FULL");

        SyncTemplateExecutionPrecheckResponse response = support.precheck(template);

        assertThat(response.precheckStatus()).isEqualTo(SyncTemplateExecutionPrecheckSupport.READY_TO_EXECUTE);
        assertThat(response.transferChannel()).isEqualTo("OFFLINE");
        assertThat(response.referenceRuntime()).isEqualTo("DATAX_STYLE_OFFLINE_READER_WRITER_RUNNER");
        assertThat(response.canStartExecution()).isTrue();
        assertThat(response.executableByCurrentRunner()).isTrue();
        assertThat(response.issueCodes()).doesNotContain("SCOPE_NOT_EXECUTABLE_BY_MINIMAL_RUN_ONCE_BRIDGE");
    }

    @Test
    void objectListCanBeDraftedButShouldNotStartUntilDedicatedRunnerExists() {
        SyncTemplate template = executableSingleObjectTemplate("FULL");
        template.setSyncScopeType("OBJECT_LIST");
        template.setObjectMappingConfig("""
                {
                  "mappings": [
                    {"sourceObject":"customer","targetObject":"customer"},
                    {"sourceObject":"orders","targetObject":"orders"}
                  ]
                }
                """);

        SyncTemplateExecutionPrecheckResponse response = support.precheck(template);

        assertThat(response.precheckStatus()).isEqualTo(SyncTemplateExecutionPrecheckSupport.NOT_SUPPORTED_BY_CURRENT_RUNNER);
        assertThat(response.canCreateTaskDraft()).isTrue();
        assertThat(response.canStartExecution()).isFalse();
        assertThat(response.approvalRequired()).isTrue();
        assertThat(response.issueCodes()).contains("SCOPE_NOT_EXECUTABLE_BY_MINIMAL_RUN_ONCE_BRIDGE");
    }

    @Test
    void incrementalModeShouldBeBlockedFromCurrentRunnerByCheckpointHandoff() {
        SyncTemplate template = executableSingleObjectTemplate("INCREMENTAL_TIME");
        template.setIncrementalField("updated_at");

        SyncTemplateExecutionPrecheckResponse response = support.precheck(template);

        assertThat(response.precheckStatus()).isEqualTo(SyncTemplateExecutionPrecheckSupport.NOT_SUPPORTED_BY_CURRENT_RUNNER);
        assertThat(response.transferChannel()).isEqualTo("OFFLINE");
        assertThat(response.checkpointRequired()).isTrue();
        assertThat(response.checkpointHandoffSupported()).isFalse();
        assertThat(response.issueCodes()).contains("CHECKPOINT_HANDOFF_NOT_IMPLEMENTED");
    }

    @Test
    void cdcStreamingShouldBeRealtimeChannelButNotExecutableByMinimalBridge() {
        SyncTemplate template = executableSingleObjectTemplate("CDC_STREAMING");

        SyncTemplateExecutionPrecheckResponse response = support.precheck(template);

        assertThat(response.transferChannel()).isEqualTo("REALTIME");
        assertThat(response.referenceRuntime()).isEqualTo("DEBEZIUM_KAFKA_CONNECT_CDC_PIPELINE");
        assertThat(response.precheckStatus()).isEqualTo(SyncTemplateExecutionPrecheckSupport.NOT_SUPPORTED_BY_CURRENT_RUNNER);
        assertThat(response.canStartExecution()).isFalse();
    }

    @Test
    void unsafeCustomSqlShouldBeBlockedBeforeTaskExecution() {
        SyncTemplate template = executableSingleObjectTemplate("CUSTOM_SQL_QUERY");
        template.setSyncScopeType("CUSTOM_SQL_QUERY");
        template.setCustomSqlConfig("""
                {"sql":"delete from customer where status = 'inactive'"}
                """);

        SyncTemplateExecutionPrecheckResponse response = support.precheck(template);

        assertThat(response.precheckStatus()).isEqualTo(SyncTemplateExecutionPrecheckSupport.BLOCKED);
        assertThat(response.transferChannel()).isEqualTo("OFFLINE");
        assertThat(response.canStartExecution()).isFalse();
        assertThat(response.customSqlSafetyPassed()).isFalse();
        assertThat(response.issueCodes()).contains("CUSTOM_SQL_RAW_SQL_UNSAFE");
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
                [{"sourceField":"id","targetField":"id"},{"sourceField":"name","targetField":"name"}]
                """);
        return template;
    }
}
