package com.czh.datasmart.govern.datasource.service.support;

import com.czh.datasmart.govern.datasource.entity.ConnectorCapabilityProfile;
import com.czh.datasmart.govern.datasource.entity.DataSourceConfig;
import com.czh.datasmart.govern.datasource.entity.SyncConnectorCapabilityAssessment;
import com.czh.datasmart.govern.datasource.support.SyncMode;
import com.czh.datasmart.govern.datasource.support.SyncWriteStrategy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @Author : Cui
 * @Date: 2026/06/20 02:00
 * @Description DataSmart Govern Backend - ConnectorCapabilityRegistryTest.java
 * @Version:1.0.0
 *
 * 连接器能力注册表单元测试。
 *
 * <p>这里不启动 Spring 容器，也不访问数据库或外部数据源。
 * 测试目标是验证“连接器能力规则本身”是否稳定：哪些同步模式可以被源端支持、哪些写入策略可以被目标端接受、
 * 哪些未来连接器只作为路线画像存在。</p>
 *
 * <p>这种测试很适合保护后续演进：
 * 当我们接入 Oracle、Kafka、对象存储或 REST API 时，可以先扩展注册表和测试，再让模板校验、前端向导和执行器共享同一份能力定义。</p>
 */
class ConnectorCapabilityRegistryTest {

    private final ConnectorCapabilityRegistry registry = new ConnectorCapabilityRegistry();

    @Test
    void getProfileShouldNormalizeSqlServerAliases() {
        ConnectorCapabilityProfile profile = registry.getProfile("sqlserver");

        assertEquals("SQL_SERVER", profile.getConnectorType());
        assertTrue(profile.getSupportedSyncModes().contains(SyncMode.FULL.name()));
        assertTrue(profile.getSupportedWriteStrategies().contains(SyncWriteStrategy.UPSERT.name()));
    }

    @Test
    void assessTemplateCompatibilityShouldAllowJdbcIncrementalUpsert() {
        SyncConnectorCapabilityAssessment assessment = registry.assessTemplateCompatibility(
                datasource("MYSQL"),
                datasource("POSTGRESQL"),
                SyncMode.INCREMENTAL_TIME,
                SyncWriteStrategy.UPSERT,
                "{\"field\":\"updated_at\"}"
        );

        assertTrue(assessment.isPassed());
        assertEquals("MYSQL", assessment.getSourceConnectorType());
        assertEquals("POSTGRESQL", assessment.getTargetConnectorType());
        assertTrue(assessment.getWarnings().stream().anyMatch(item -> item.contains("真实数据搬运执行器")));
    }

    @Test
    void assessTemplateCompatibilityShouldRejectStreamingModeForJdbcSource() {
        SyncConnectorCapabilityAssessment assessment = registry.assessTemplateCompatibility(
                datasource("MYSQL"),
                datasource("POSTGRESQL"),
                SyncMode.CDC,
                SyncWriteStrategy.APPEND,
                null
        );

        assertFalse(assessment.isPassed());
        assertTrue(assessment.getErrors().stream().anyMatch(item -> item.contains("不支持同步模式 CDC")));
    }

    @Test
    void assessTemplateCompatibilityShouldRejectUnsupportedTargetWriteStrategy() {
        SyncConnectorCapabilityAssessment assessment = registry.assessTemplateCompatibility(
                datasource("MYSQL"),
                datasource("POSTGRESQL"),
                SyncMode.FULL,
                SyncWriteStrategy.REPLACE,
                null
        );

        assertFalse(assessment.isPassed());
        assertTrue(assessment.getErrors().stream().anyMatch(item -> item.contains("不支持写入策略 REPLACE")));
    }

    @Test
    void listProfilesShouldExposeRoadmapConnectorsWithoutExecutionPromise() {
        List<ConnectorCapabilityProfile> profiles = registry.listProfiles();

        assertTrue(profiles.stream().anyMatch(item -> "KAFKA".equals(item.getConnectorType())));
        assertTrue(profiles.stream().anyMatch(item -> "OBJECT_STORAGE".equals(item.getConnectorType())));
        assertTrue(profiles.stream()
                .filter(item -> "KAFKA".equals(item.getConnectorType()))
                .findFirst()
                .orElseThrow()
                .getImplementationStage()
                .contains("ROADMAP"));
    }

    @Test
    void assertTemplateCompatibleShouldFailFastWhenAssessmentHasErrors() {
        assertThrows(IllegalArgumentException.class, () -> registry.assertTemplateCompatible(
                datasource("MYSQL"),
                datasource("POSTGRESQL"),
                SyncMode.CDC,
                SyncWriteStrategy.APPEND,
                null
        ));
    }

    private DataSourceConfig datasource(String type) {
        DataSourceConfig datasource = new DataSourceConfig();
        datasource.setType(type);
        return datasource;
    }
}
