/**
 * @Author : Cui
 * @Date: 2026/06/29 00:09
 * @Description DataSmart Govern Backend - SyncTemplateConnectorFactResolverTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.datasync.config.DataSyncDatasourceCapabilityProperties;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.entity.SyncTemplate;
import com.czh.datasmart.govern.datasync.integration.datasource.DatasourceCapabilitySnapshotClient;
import com.czh.datasmart.govern.datasync.integration.datasource.DatasourceCapabilitySnapshotView;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * `SyncTemplateConnectorFactResolver` 单元测试。
 *
 * <p>这组测试重点保护 data-sync 与 datasource-management 的收敛契约：
 * 模板创建方可以只传 datasourceId，data-sync 会读取低敏能力快照自动补全 connectorType；
 * 但只要快照显示租户/项目不一致、不可规划、源端不可读或目标端不可写，就必须 fail-closed。</p>
 *
 * <p>测试不启动 HTTP 服务，而是用内存 fake client 模拟 datasource-management 响应。这样可以把“业务规则是否正确”
 * 和“HTTP 客户端是否能连通”分开验证，单测更快、更稳定，也不会依赖本机是否正在运行 datasource-management。</p>
 */
class SyncTemplateConnectorFactResolverTest {

    @Test
    void resolveConnectorFactsShouldFillMissingConnectorTypesFromCapabilitySnapshots() {
        FakeSnapshotClient client = new FakeSnapshotClient()
                .put(snapshot(10001L, "mysql", 7L, 101L, 301L, true, true, false))
                .put(snapshot(20001L, "postgresql", 7L, 101L, 301L, true, true, true));
        SyncTemplateConnectorFactResolver resolver = resolver(client, enabledProperties());
        SyncTemplate template = template(null, null);

        resolver.resolveConnectorFacts(template, actor());

        assertThat(template.getSourceConnectorType()).isEqualTo("MYSQL");
        assertThat(template.getTargetConnectorType()).isEqualTo("POSTGRESQL");
        assertThat(client.callCount()).isEqualTo(2);
    }

    @Test
    void resolveConnectorFactsShouldRejectSnapshotThatIsNotEligibleForTemplatePlanning() {
        FakeSnapshotClient client = new FakeSnapshotClient()
                .put(snapshot(10001L, "MYSQL", 7L, 101L, 301L, false, true, false)
                        .withIssueCodes(List.of("CONNECTOR_ROADMAP_RESERVED"))
                        .withRecommendedActions(List.of("先补齐真实执行器能力")))
                .put(snapshot(20001L, "POSTGRESQL", 7L, 101L, 301L, true, true, true));
        SyncTemplateConnectorFactResolver resolver = resolver(client, enabledProperties());

        PlatformBusinessException exception = assertThrows(PlatformBusinessException.class,
                () -> resolver.resolveConnectorFacts(template(null, null), actor()));

        assertThat(exception.getErrorCode()).isEqualTo(PlatformErrorCode.VALIDATION_ERROR);
        assertThat(exception.getMessage()).contains("不允许进入同步模板规划");
        assertThat(exception.getMessage()).contains("CONNECTOR_ROADMAP_RESERVED");
    }

    @Test
    void resolveConnectorFactsShouldRejectCrossTenantDatasourceReference() {
        FakeSnapshotClient client = new FakeSnapshotClient()
                .put(snapshot(10001L, "MYSQL", 8L, 101L, 301L, true, true, false));
        SyncTemplateConnectorFactResolver resolver = resolver(client, enabledProperties());

        PlatformBusinessException exception = assertThrows(PlatformBusinessException.class,
                () -> resolver.resolveConnectorFacts(template(null, "POSTGRESQL"), actor()));

        assertThat(exception.getErrorCode()).isEqualTo(PlatformErrorCode.TENANT_SCOPE_DENIED);
        assertThat(exception.getMessage()).contains("租户");
    }

    @Test
    void resolveConnectorFactsShouldRejectCrossProjectDatasourceReference() {
        FakeSnapshotClient client = new FakeSnapshotClient()
                .put(snapshot(10001L, "MYSQL", 7L, 999L, 301L, true, true, false));
        SyncTemplateConnectorFactResolver resolver = resolver(client, enabledProperties());

        PlatformBusinessException exception = assertThrows(PlatformBusinessException.class,
                () -> resolver.resolveConnectorFacts(template(null, "POSTGRESQL"), actor()));

        assertThat(exception.getErrorCode()).isEqualTo(PlatformErrorCode.TENANT_SCOPE_DENIED);
        assertThat(exception.getMessage()).contains("跨项目同步");
    }

    @Test
    void resolveConnectorFactsShouldRejectTargetWithoutWriteCapability() {
        FakeSnapshotClient client = new FakeSnapshotClient()
                .put(snapshot(20001L, "POSTGRESQL", 7L, 101L, 301L, true, true, false));
        SyncTemplateConnectorFactResolver resolver = resolver(client, enabledProperties());

        PlatformBusinessException exception = assertThrows(PlatformBusinessException.class,
                () -> resolver.resolveConnectorFacts(template("MYSQL", null), actor()));

        assertThat(exception.getErrorCode()).isEqualTo(PlatformErrorCode.VALIDATION_ERROR);
        assertThat(exception.getMessage()).contains("不具备目标端写入能力");
    }

    @Test
    void resolveConnectorFactsShouldKeepLegacyMissingFactsWhenRemoteSnapshotIsDisabled() {
        DataSyncDatasourceCapabilityProperties properties = enabledProperties();
        properties.setEnabled(false);
        FakeSnapshotClient client = new FakeSnapshotClient();
        SyncTemplateConnectorFactResolver resolver = resolver(client, properties);
        SyncTemplate template = template(null, null);

        resolver.resolveConnectorFacts(template, actor());

        assertThat(template.getSourceConnectorType()).isNull();
        assertThat(template.getTargetConnectorType()).isNull();
        assertThat(client.callCount()).isZero();
    }

    @Test
    void resolveConnectorFactsShouldRejectProvidedConnectorTypeWhenStrictValidationFindsMismatch() {
        DataSyncDatasourceCapabilityProperties properties = enabledProperties();
        properties.setValidateProvidedConnectorFacts(true);
        FakeSnapshotClient client = new FakeSnapshotClient()
                .put(snapshot(10001L, "MYSQL", 7L, 101L, 301L, true, true, false))
                .put(snapshot(20001L, "POSTGRESQL", 7L, 101L, 301L, true, true, true));
        SyncTemplateConnectorFactResolver resolver = resolver(client, properties);

        PlatformBusinessException exception = assertThrows(PlatformBusinessException.class,
                () -> resolver.resolveConnectorFacts(template("KAFKA", "POSTGRESQL"), actor()));

        assertThat(exception.getErrorCode()).isEqualTo(PlatformErrorCode.VALIDATION_ERROR);
        assertThat(exception.getMessage()).contains("不一致");
    }

    private SyncTemplateConnectorFactResolver resolver(FakeSnapshotClient client,
                                                       DataSyncDatasourceCapabilityProperties properties) {
        return new SyncTemplateConnectorFactResolver(client, properties, new SyncQuerySupport());
    }

    private DataSyncDatasourceCapabilityProperties enabledProperties() {
        DataSyncDatasourceCapabilityProperties properties = new DataSyncDatasourceCapabilityProperties();
        properties.setEnabled(true);
        return properties;
    }

    private SyncTemplate template(String sourceConnectorType, String targetConnectorType) {
        SyncTemplate template = new SyncTemplate();
        template.setTenantId(7L);
        template.setProjectId(101L);
        template.setWorkspaceId(301L);
        template.setSourceDatasourceId(10001L);
        template.setTargetDatasourceId(20001L);
        template.setSourceConnectorType(sourceConnectorType);
        template.setTargetConnectorType(targetConnectorType);
        template.setSyncMode("FULL");
        return template;
    }

    private SyncActorContext actor() {
        return new SyncActorContext(7L, 1001L, "PROJECT_OWNER", "trace-template-connector-fact");
    }

    private SnapshotBuilder snapshot(Long datasourceId,
                                     String connectorType,
                                     Long tenantId,
                                     Long projectId,
                                     Long workspaceId,
                                     boolean eligibleForTemplatePlanning,
                                     boolean canRead,
                                     boolean canWrite) {
        DatasourceCapabilitySnapshotView snapshot = new DatasourceCapabilitySnapshotView();
        snapshot.setSnapshotVersion("v1");
        snapshot.setPayloadPolicy("LOW_SENSITIVE_CAPABILITY_SNAPSHOT");
        snapshot.setDatasourceId(datasourceId);
        snapshot.setTenantId(tenantId);
        snapshot.setProjectId(projectId);
        snapshot.setWorkspaceId(workspaceId);
        snapshot.setDatasourceStatus("ACTIVE");
        snapshot.setConnectionTestStatus("SUCCESS");
        snapshot.setHealthStatus("CONNECTION_VERIFIED");
        snapshot.setConnectorType(connectorType);
        snapshot.setConnectorFamily("RELATIONAL_JDBC");
        snapshot.setImplementationStage("MVP_SUPPORTED");
        snapshot.setEligibleForTemplatePlanning(eligibleForTemplatePlanning);
        snapshot.setEligibleForExecutionPrecheck(true);
        snapshot.setCanRead(canRead);
        snapshot.setCanWrite(canWrite);
        snapshot.setSupportedSyncModes(List.of("FULL"));
        snapshot.setSupportedWriteStrategies(List.of("APPEND", "UPSERT"));
        snapshot.setIssueCodes(List.of());
        snapshot.setRecommendedActions(List.of());
        return new SnapshotBuilder(snapshot);
    }

    /**
     * 测试用快照构造器。
     *
     * <p>用一个小包装类而不是在每个测试里手写 setIssueCodes/setRecommendedActions，可以让测试关注业务差异，
     * 例如“不允许规划”或“跨租户”，而不是被大量样板字段淹没。</p>
     */
    private static class SnapshotBuilder {
        private final DatasourceCapabilitySnapshotView snapshot;

        private SnapshotBuilder(DatasourceCapabilitySnapshotView snapshot) {
            this.snapshot = snapshot;
        }

        private SnapshotBuilder withIssueCodes(List<String> issueCodes) {
            snapshot.setIssueCodes(issueCodes);
            return this;
        }

        private SnapshotBuilder withRecommendedActions(List<String> recommendedActions) {
            snapshot.setRecommendedActions(recommendedActions);
            return this;
        }
    }

    private static class FakeSnapshotClient implements DatasourceCapabilitySnapshotClient {
        private final Map<Long, DatasourceCapabilitySnapshotView> snapshots = new HashMap<>();
        private int callCount;

        private FakeSnapshotClient put(SnapshotBuilder builder) {
            snapshots.put(builder.snapshot.getDatasourceId(), builder.snapshot);
            return this;
        }

        @Override
        public DatasourceCapabilitySnapshotView getSnapshot(Long datasourceId, SyncActorContext actorContext) {
            callCount++;
            return snapshots.get(datasourceId);
        }

        private int callCount() {
            return callCount;
        }
    }
}
