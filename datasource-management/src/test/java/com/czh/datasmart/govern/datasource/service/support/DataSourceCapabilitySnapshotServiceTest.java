/**
 * @Author : Cui
 * @Date: 2026/06/28 23:52
 * @Description DataSmart Govern Backend - DataSourceCapabilitySnapshotServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.support;

import com.czh.datasmart.govern.datasource.controller.dto.DataSourceCapabilitySnapshotView;
import com.czh.datasmart.govern.datasource.entity.DataSourceConfig;
import com.czh.datasmart.govern.datasource.support.ConnectionTestStatus;
import com.czh.datasmart.govern.datasource.support.DataSourceStatus;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据源能力快照构建服务单元测试。
 *
 * <p>这组测试不启动 Spring 容器，也不连接真实数据库。
 * 测试目标是保护低敏事实契约：只要有人误把 jdbcUrl、username、password、lastTestMessage 等字段放进快照，
 * 测试就应该立刻失败。</p>
 */
class DataSourceCapabilitySnapshotServiceTest {

    private final DataSourceCapabilitySnapshotService snapshotService =
            new DataSourceCapabilitySnapshotService(new ConnectorCapabilityRegistry());

    @Test
    void buildSnapshotShouldExposeLowSensitiveMysqlCapabilityFacts() {
        DataSourceCapabilitySnapshotView snapshot = snapshotService.buildSnapshot(activeDatasource("MYSQL"));

        assertEquals("datasmart.datasource.capability-snapshot.v1", snapshot.getSnapshotVersion());
        assertEquals("LOW_SENSITIVE_CAPABILITY_ONLY", snapshot.getPayloadPolicy());
        assertEquals("MYSQL", snapshot.getConnectorType());
        assertEquals("RELATIONAL_JDBC", snapshot.getConnectorFamily());
        assertEquals("CONNECTION_VERIFIED", snapshot.getHealthStatus());
        assertTrue(snapshot.isEligibleForTemplatePlanning());
        assertTrue(snapshot.isEligibleForExecutionPrecheck());
        assertTrue(snapshot.isCanRead());
        assertTrue(snapshot.isCanWrite());
        assertTrue(snapshot.isSupportsFullSync());
        assertTrue(snapshot.isSupportsIncrementalSync());
        assertTrue(snapshot.getSupportedSyncModes().contains("FULL"));
        assertTrue(snapshot.getIssueCodes().contains("EXECUTOR_STAGE_PENDING"));
    }

    @Test
    void buildSnapshotShouldBlockExecutionPrecheckWhenConnectionWasNotVerified() {
        DataSourceConfig datasource = activeDatasource("POSTGRESQL");
        datasource.setLastTestStatus(ConnectionTestStatus.UNKNOWN);
        datasource.setLastTestTime(null);

        DataSourceCapabilitySnapshotView snapshot = snapshotService.buildSnapshot(datasource);

        assertEquals("CONNECTION_NOT_TESTED", snapshot.getHealthStatus());
        assertTrue(snapshot.isEligibleForTemplatePlanning());
        assertFalse(snapshot.isEligibleForExecutionPrecheck());
        assertTrue(snapshot.getIssueCodes().contains("CONNECTION_NOT_VERIFIED"));
        assertTrue(snapshot.getRecommendedActions().stream().anyMatch(item -> item.contains("连接测试")));
    }

    @Test
    void buildSnapshotShouldExposeInactiveAndFailedConnectionAsIssueCodesWithoutFailureMessage() {
        DataSourceConfig datasource = activeDatasource("SQLSERVER");
        datasource.setStatus(DataSourceStatus.INACTIVE);
        datasource.setLastTestStatus(ConnectionTestStatus.FAILED);
        datasource.setLastTestMessage("jdbc:mysql://secret-host:3306/prod password=secret");

        DataSourceCapabilitySnapshotView snapshot = snapshotService.buildSnapshot(datasource);

        assertEquals("DATASOURCE_DISABLED", snapshot.getHealthStatus());
        assertFalse(snapshot.isEligibleForTemplatePlanning());
        assertFalse(snapshot.isEligibleForExecutionPrecheck());
        assertTrue(snapshot.getIssueCodes().contains("DATASOURCE_DISABLED"));
        assertTrue(snapshot.getIssueCodes().contains("CONNECTION_LAST_FAILED"));
        assertFalse(snapshot.getRecommendedActions().toString().contains("secret-host"));
        assertFalse(snapshot.getRecommendedActions().toString().contains("password"));
    }

    @Test
    void buildSnapshotShouldMarkRoadmapConnectorAsNotReadyForTemplatePlanning() {
        DataSourceConfig datasource = activeDatasource("KAFKA");

        DataSourceCapabilitySnapshotView snapshot = snapshotService.buildSnapshot(datasource);

        assertEquals("KAFKA", snapshot.getConnectorType());
        assertFalse(snapshot.isEligibleForTemplatePlanning());
        assertFalse(snapshot.isEligibleForExecutionPrecheck());
        assertTrue(snapshot.getIssueCodes().contains("CONNECTOR_ROADMAP_RESERVED"));
        assertTrue(snapshot.getRecommendedActions().stream().anyMatch(item -> item.contains("路线图")));
    }

    @Test
    void snapshotViewShouldNotDefineSensitiveConnectionFields() {
        Set<String> fieldNames = Arrays.stream(DataSourceCapabilitySnapshotView.class.getDeclaredFields())
                .map(Field::getName)
                .collect(Collectors.toSet());

        assertFalse(fieldNames.contains("jdbcUrl"));
        assertFalse(fieldNames.contains("username"));
        assertFalse(fieldNames.contains("password"));
        assertFalse(fieldNames.contains("lastTestMessage"));
        assertFalse(fieldNames.contains("endpoint"));
        assertFalse(fieldNames.contains("host"));
        assertFalse(fieldNames.contains("database"));
    }

    private DataSourceConfig activeDatasource(String connectorType) {
        DataSourceConfig datasource = new DataSourceConfig();
        datasource.setId(1001L);
        datasource.setTenantId(10L);
        datasource.setProjectId(20L);
        datasource.setWorkspaceId(30L);
        datasource.setName("不应进入快照的内部展示名");
        datasource.setType(connectorType);
        datasource.setJdbcUrl("jdbc:mysql://secret-host:3306/prod");
        datasource.setUsername("secret-user");
        datasource.setPassword("secret-password");
        datasource.setStatus(DataSourceStatus.ACTIVE);
        datasource.setLastTestStatus(ConnectionTestStatus.SUCCESS);
        datasource.setLastTestMessage("不应进入快照的连接测试详情");
        datasource.setLastTestTime(LocalDateTime.now().minusMinutes(5));
        return datasource;
    }
}
