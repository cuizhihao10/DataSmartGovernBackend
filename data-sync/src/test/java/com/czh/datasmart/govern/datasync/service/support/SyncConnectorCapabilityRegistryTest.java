/**
 * @Author : Cui
 * @Date: 2026/06/28 23:28
 * @Description DataSmart Govern Backend - SyncConnectorCapabilityRegistryTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.datasync.controller.dto.SyncConnectorCapabilityView;
import com.czh.datasmart.govern.datasync.controller.dto.SyncConnectorCompatibilityView;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 连接器能力注册表测试。
 *
 * <p>这组测试保护的是 data-sync 后续泛化能力，而不是某个具体数据库驱动：
 * 1. MySQL/PostgreSQL 必须被同级建模，避免产品长期只围绕单一 MySQL 场景；
 * 2. Kafka/File/ObjectStorage/REST API 的模式语义不能被强行塞进传统表同步；
 * 3. 兼容性诊断只能返回低敏能力元数据，不能暴露连接串、SQL、样本数据或密钥。</p>
 */
class SyncConnectorCapabilityRegistryTest {

    private final SyncConnectorCapabilityRegistry registry = new SyncConnectorCapabilityRegistry();

    /**
     * 默认能力矩阵应覆盖产品文档中的主流连接器家族。
     */
    @Test
    void listCapabilitiesShouldCoverCommercialConnectorFamilies() {
        Map<String, SyncConnectorCapabilityView> capabilities = registry.listCapabilities().stream()
                .collect(Collectors.toMap(SyncConnectorCapabilityView::connectorType, item -> item));

        assertThat(capabilities).containsKeys(
                "MYSQL", "POSTGRESQL", "SQL_SERVER", "ORACLE", "MONGODB", "KAFKA",
                "HIVE", "CLICKHOUSE", "FILE", "OBJECT_STORAGE", "REST_API"
        );
        assertThat(capabilities.get("MYSQL").supportedModes())
                .contains("FULL", "SCHEDULED_FULL", "SCHEDULED_BATCH", "CUSTOM_SQL_QUERY", "CDC_STREAMING")
                .doesNotContain("INCREMENTAL_TIME", "INCREMENTAL_ID", "REPLAY", "BACKFILL");
        assertThat(capabilities.get("POSTGRESQL").supportedModes())
                .contains("FULL", "SCHEDULED_FULL", "SCHEDULED_BATCH", "CUSTOM_SQL_QUERY", "CDC_STREAMING")
                .doesNotContain("INCREMENTAL_TIME", "INCREMENTAL_ID");
        assertThat(capabilities.get("KAFKA").supportedModes()).doesNotContain("FULL");
        assertThat(capabilities.get("OBJECT_STORAGE").supportedModes())
                .contains("FULL", "SCHEDULED_FULL", "SCHEDULED_BATCH")
                .doesNotContain("OFFLINE_IMPORT", "OFFLINE_EXPORT", "REPLAY", "BACKFILL");
    }

    /**
     * 关系型数据库之间的全量同步应通过能力预检。
     */
    @Test
    void relationalFullSyncShouldBeSupported() {
        SyncConnectorCompatibilityView result =
                registry.checkCompatibility("mysql", "postgresql", "full");

        assertThat(result.supported()).isTrue();
        assertThat(result.transferChannel()).isEqualTo("OFFLINE");
        assertThat(result.referenceRuntime()).isEqualTo("DATAX_STYLE_OFFLINE_READER_WRITER_RUNNER");
        assertThat(result.consistencyGoal()).isEqualTo("SNAPSHOT_BOUNDED");
        assertThat(result.issueCodes()).isEmpty();
        assertThat(result.recommendedActions()).anyMatch(action -> action.contains("连接测试"));
    }

    /**
     * 传输通道是执行路线的顶层分类：非 CDC 的有界同步作业都走 OFFLINE，CDC 才走 REALTIME。
     *
     * <p>这里特别覆盖 SCHEDULED_BATCH 和 CUSTOM_SQL_QUERY，是为了防止后续把“定时批量”和“SQL 自定义传输”
     * 从 DataX-style 离线通道里漏掉。注意：OFFLINE 不等于所有模式都叫“批量传输”，真正的批量语义仍由
     * SCHEDULED_BATCH 表达。</p>
     */
    @Test
    void transferChannelShouldSeparateBoundedOfflineJobsFromCdcStreaming() {
        assertThat(registry.checkCompatibility("mysql", "postgresql", "full").transferChannel())
                .isEqualTo("OFFLINE");
        assertThat(registry.checkCompatibility("mysql", "postgresql", "scheduled_full").transferChannel())
                .isEqualTo("OFFLINE");
        assertThat(registry.checkCompatibility("mysql", "postgresql", "scheduled_batch").transferChannel())
                .isEqualTo("OFFLINE");
        assertThat(registry.checkCompatibility("mysql", "postgresql", "custom_sql_query").transferChannel())
                .isEqualTo("OFFLINE");
        assertThat(registry.checkCompatibility("mysql", "postgresql", "cdc_streaming").transferChannel())
                .isEqualTo("REALTIME");
        assertThat(registry.checkCompatibility("mysql", "postgresql", "cdc_streaming").referenceRuntime())
                .isEqualTo("DEBEZIUM_KAFKA_CONNECT_CDC_PIPELINE");
    }

    /**
     * Kafka 不应被误判为传统全量表同步连接器。
     */
    @Test
    void kafkaFullSyncShouldBeRejected() {
        SyncConnectorCompatibilityView result =
                registry.checkCompatibility("kafka", "mysql", "full");

        assertThat(result.supported()).isFalse();
        assertThat(result.issueCodes()).contains("SOURCE_MODE_UNSUPPORTED");
        assertThat(result.performanceNotes()).anyMatch(note -> note.contains("offset"));
    }

    /**
     * CDC/流式模式必须要求源端和目标端都具备流式能力。
     */
    @Test
    void cdcShouldRequireStreamingCapabilityOnBothSides() {
        SyncConnectorCompatibilityView result =
                registry.checkCompatibility("mysql", "file", "cdc_streaming");

        assertThat(result.supported()).isFalse();
        assertThat(result.issueCodes()).contains("TARGET_MODE_UNSUPPORTED", "STREAMING_CAPABILITY_REQUIRED");
        assertThat(result.checkpointRequired()).isTrue();
        assertThat(result.retryPattern()).isEqualTo("OFFSET_RECOVERY");
    }

    /**
     * 能力诊断必须保持低敏，不允许把连接细节、SQL、样本或密钥放进响应。
     */
    @Test
    void compatibilityPayloadShouldRemainLowSensitive() {
        SyncConnectorCompatibilityView result =
                registry.checkCompatibility("rest_api", "object_storage", "scheduled_batch");
        String serialized = result.toString().toLowerCase();

        assertThat(result.payloadPolicy()).isEqualTo("LOW_SENSITIVE_CONNECTOR_CAPABILITY_METADATA_ONLY");
        assertThat(serialized).doesNotContain("jdbc:");
        assertThat(serialized).doesNotContain("select * from");
        assertThat(serialized).doesNotContain("password");
        assertThat(serialized).doesNotContain("api_key");
        assertThat(serialized).doesNotContain("sample payload");
        assertThat(serialized).doesNotContain("signed url");
    }

    /**
     * 未知连接器必须 fail-closed，不能悄悄按通用连接器放行。
     */
    @Test
    void unknownConnectorShouldFailClosed() {
        assertThrows(PlatformBusinessException.class,
                () -> registry.checkCompatibility("unknown-db", "mysql", "full"));
    }
}
