/**
 * @Author : Cui
 * @Date: 2026/06/29 02:40
 * @Description DataSmart Govern Backend - SyncTemplatePlanningPreviewSupportTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.controller.dto.SyncTemplatePlanningPreviewResponse;
import com.czh.datasmart.govern.datasync.entity.SyncTemplate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 同步模板规划预览测试。
 *
 * <p>预览接口不是执行器，也不是 SQL/样本预览。它只检查低敏配置事实是否足够清晰，
 * 并输出状态、问题码和建议动作。本测试覆盖 READY、NEEDS_REVIEW、BLOCKED 三类结果，
 * 防止后续把“缺少建议性配置”误判为硬阻断，或把“连接器能力不兼容”误判为可继续。</p>
 */
class SyncTemplatePlanningPreviewSupportTest {

    private final SyncTemplatePlanningPreviewSupport support =
            new SyncTemplatePlanningPreviewSupport(new SyncConnectorCapabilityRegistry());

    @Test
    void previewShouldReturnReadyWhenRelationalFullTemplateHasCorePolicies() {
        SyncTemplatePlanningPreviewResponse response = support.preview(template("MYSQL", "POSTGRESQL", "FULL")
                .fieldMappingConfig("{}")
                .retryPolicy("{}")
                .timeoutPolicy("{}")
                .build());

        assertThat(response.previewStatus()).isEqualTo("READY");
        assertThat(response.transferChannel()).isEqualTo("OFFLINE");
        assertThat(response.referenceRuntime()).isEqualTo("DATAX_STYLE_OFFLINE_READER_WRITER_RUNNER");
        assertThat(response.canProceedToTaskDraft()).isTrue();
        assertThat(response.executionPrecheckReady()).isTrue();
        assertThat(response.connectorCompatibilitySupported()).isTrue();
        assertThat(response.issueCodes()).isEmpty();
        assertThat(response.payloadPolicy()).isEqualTo("LOW_SENSITIVE_TEMPLATE_PLANNING_PREVIEW");
    }

    @Test
    void previewShouldExposeOfflineChannelForScheduledBatchAndCustomSql() {
        SyncTemplatePlanningPreviewResponse scheduledBatch = support.preview(template("MYSQL", "POSTGRESQL", "SCHEDULED_BATCH")
                .fieldMappingConfig("{}")
                .retryPolicy("{}")
                .timeoutPolicy("{}")
                .build());
        SyncTemplatePlanningPreviewResponse customSql = support.preview(template("MYSQL", "POSTGRESQL", "CUSTOM_SQL_QUERY")
                .syncScopeType("CUSTOM_SQL_QUERY")
                .fieldMappingConfig("{}")
                .customSqlConfig("{\"sql\":\"select id, name from customer where status = :status\"}")
                .retryPolicy("{}")
                .timeoutPolicy("{}")
                .build());

        assertThat(scheduledBatch.transferChannel()).isEqualTo("OFFLINE");
        assertThat(customSql.transferChannel()).isEqualTo("OFFLINE");
        assertThat(customSql.referenceRuntime()).isEqualTo("DATAX_STYLE_OFFLINE_READER_WRITER_RUNNER");
        assertThat(customSql.requiresApproval()).isTrue();
        assertThat(customSql.performanceNotes()).anyMatch(note -> note.contains("SQL 自定义传输"));
    }

    @Test
    void previewShouldExposeRealtimeChannelOnlyForCdcStreaming() {
        SyncTemplatePlanningPreviewResponse response = support.preview(template("MYSQL", "POSTGRESQL", "CDC_STREAMING")
                .writeStrategy(null)
                .fieldMappingConfig("{}")
                .retryPolicy("{}")
                .timeoutPolicy("{}")
                .build());

        assertThat(response.transferChannel()).isEqualTo("REALTIME");
        assertThat(response.referenceRuntime()).isEqualTo("DEBEZIUM_KAFKA_CONNECT_CDC_PIPELINE");
        assertThat(response.writeStrategy()).isEqualTo("UPDATE");
        assertThat(response.issueCodes()).doesNotContain("REALTIME_WRITE_STRATEGY_MUST_BE_MERGE");
    }

    @Test
    void previewShouldBlockHistoricalIncrementalModeBecauseItIsNoLongerUserSelectable() {
        SyncTemplatePlanningPreviewResponse response = support.preview(template("MYSQL", "POSTGRESQL", "INCREMENTAL_TIME")
                .incrementalField("updated_at")
                .fieldMappingConfig("{}")
                .build());

        assertThat(response.previewStatus()).isEqualTo("BLOCKED");
        assertThat(response.canProceedToTaskDraft()).isFalse();
        assertThat(response.executionPrecheckReady()).isFalse();
        assertThat(response.issueCodes()).contains(
                "SYNC_MODE_NOT_USER_SELECTABLE_TRANSFER_MODE",
                "RETRY_POLICY_NOT_DECLARED",
                "TIMEOUT_POLICY_NOT_DECLARED"
        );
        assertThat(response.recommendedActions()).anyMatch(action -> action.contains("内部/历史能力"));
    }

    @Test
    void previewShouldBlockWhenExecutableObjectBindingIsMissing() {
        SyncTemplatePlanningPreviewResponse response = support.preview(template("MYSQL", "POSTGRESQL", "FULL")
                .withoutObjects()
                .fieldMappingConfig("{}")
                .retryPolicy("{}")
                .timeoutPolicy("{}")
                .build());

        assertThat(response.previewStatus()).isEqualTo("BLOCKED");
        assertThat(response.sourceObjectDeclared()).isFalse();
        assertThat(response.targetObjectDeclared()).isFalse();
        assertThat(response.issueCodes()).contains("SOURCE_OBJECT_NOT_DECLARED", "TARGET_OBJECT_NOT_DECLARED");
    }

    @Test
    void previewShouldBlockConflictWriteWithoutPrimaryKey() {
        SyncTemplatePlanningPreviewResponse response = support.preview(template("MYSQL", "POSTGRESQL", "FULL")
                .writeStrategy("UPSERT")
                .fieldMappingConfig("{}")
                .retryPolicy("{}")
                .timeoutPolicy("{}")
                .build());

        assertThat(response.previewStatus()).isEqualTo("BLOCKED");
        assertThat(response.writeStrategy()).isEqualTo("UPSERT");
        assertThat(response.writeStrategyRequiresConflictKey()).isTrue();
        assertThat(response.primaryKeyDeclared()).isFalse();
        assertThat(response.issueCodes()).contains("PRIMARY_KEY_NOT_DECLARED_FOR_CONFLICT_WRITE");
    }

    @Test
    void previewShouldReturnBlockedWhenConnectorCombinationIsUnsupported() {
        SyncTemplatePlanningPreviewResponse response = support.preview(template("KAFKA", "POSTGRESQL", "FULL")
                .fieldMappingConfig("{}")
                .retryPolicy("{}")
                .timeoutPolicy("{}")
                .build());

        assertThat(response.previewStatus()).isEqualTo("BLOCKED");
        assertThat(response.canProceedToTaskDraft()).isFalse();
        assertThat(response.connectorCompatibilitySupported()).isFalse();
        assertThat(response.issueCodes()).contains("SOURCE_MODE_UNSUPPORTED");
        assertThat(response.recommendedActions()).isNotEmpty();
    }

    @Test
    void previewShouldNotExposeConfigBodyWhenTemplateHasSensitiveLookingConfigText() {
        SyncTemplatePlanningPreviewResponse response = support.preview(template("MYSQL", "POSTGRESQL", "FULL")
                .fieldMappingConfig("{\"password\":\"should-not-return\"}")
                .retryPolicy("{\"token\":\"should-not-return\"}")
                .timeoutPolicy("{\"jdbcUrl\":\"should-not-return\"}")
                .build());

        String serializedSummary = response.toString();
        assertThat(serializedSummary)
                .doesNotContain("should-not-return")
                .doesNotContain("password")
                .doesNotContain("token")
                .doesNotContain("jdbcUrl");
    }

    @Test
    void previewShouldReturnNeedsReviewWhenConnectorFactsAreIncompleteForLegacyTemplate() {
        SyncTemplatePlanningPreviewResponse response = support.preview(template(null, null, "FULL")
                .timeoutPolicy("{}")
                .build());

        assertThat(response.previewStatus()).isEqualTo("NEEDS_REVIEW");
        assertThat(response.canProceedToTaskDraft()).isTrue();
        assertThat(response.connectorCompatibilitySupported()).isFalse();
        assertThat(response.issueCodes()).contains("CONNECTOR_FACTS_INCOMPLETE");
    }

    private TemplateBuilder template(String sourceConnectorType, String targetConnectorType, String syncMode) {
        return new TemplateBuilder(sourceConnectorType, targetConnectorType, syncMode);
    }

    /**
     * 测试用模板构造器。
     *
     * <p>预览测试关心的是“配置块是否存在”，不关心配置块正文。因此构造器只暴露少量声明方法，
     * 让每个测试用例能快速表达自己想模拟的配置完整度。</p>
     */
    private static class TemplateBuilder {
        private final SyncTemplate template = new SyncTemplate();

        private TemplateBuilder(String sourceConnectorType, String targetConnectorType, String syncMode) {
            template.setId(1001L);
            template.setTenantId(7L);
            template.setProjectId(101L);
            template.setWorkspaceId(301L);
            template.setSourceDatasourceId(10001L);
            template.setTargetDatasourceId(20001L);
            template.setSourceSchemaName("ods");
            template.setSourceObjectName("customer");
            template.setTargetSchemaName("dwd");
            template.setTargetObjectName("customer");
            template.setSourceConnectorType(sourceConnectorType);
            template.setTargetConnectorType(targetConnectorType);
            template.setSyncMode(syncMode);
            template.setWriteStrategy("APPEND");
        }

        private TemplateBuilder withoutObjects() {
            template.setSourceObjectName(null);
            template.setTargetObjectName(null);
            return this;
        }

        private TemplateBuilder writeStrategy(String value) {
            template.setWriteStrategy(value);
            return this;
        }

        private TemplateBuilder incrementalField(String value) {
            template.setIncrementalField(value);
            return this;
        }

        private TemplateBuilder fieldMappingConfig(String value) {
            template.setFieldMappingConfig(value);
            return this;
        }

        private TemplateBuilder syncScopeType(String value) {
            template.setSyncScopeType(value);
            return this;
        }

        private TemplateBuilder customSqlConfig(String value) {
            template.setCustomSqlConfig(value);
            return this;
        }

        private TemplateBuilder retryPolicy(String value) {
            template.setRetryPolicy(value);
            return this;
        }

        private TemplateBuilder timeoutPolicy(String value) {
            template.setTimeoutPolicy(value);
            return this;
        }

        private SyncTemplate build() {
            return template;
        }
    }
}
