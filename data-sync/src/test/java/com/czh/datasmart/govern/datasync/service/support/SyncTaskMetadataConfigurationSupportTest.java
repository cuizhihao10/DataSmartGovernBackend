/**
 * @Author : Cui
 * @Date: 2026/07/08 19:00
 * @Description DataSmart Govern Backend - SyncTaskMetadataConfigurationSupportTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskMetadataDiscoveryRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskMetadataDiscoveryResponse;
import com.czh.datasmart.govern.datasync.integration.datasource.DatasourceCapabilitySnapshotClient;
import com.czh.datasmart.govern.datasync.integration.datasource.DatasourceCapabilitySnapshotView;
import com.czh.datasmart.govern.datasync.integration.datasource.metadata.DatasourceMetadataDiscoveryClient;
import com.czh.datasmart.govern.datasync.integration.datasource.metadata.DatasourceMetadataDiscoveryRequest;
import com.czh.datasmart.govern.datasync.integration.datasource.metadata.DatasourceMetadataDiscoveryResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 同步任务创建向导元数据配置测试。
 *
 * <p>这组测试保护的是 data-sync -> datasource-management 的跨服务请求合同。
 * 创建任务第一步进入“对象映射”时，data-sync 会同时向 datasource-management 申请源端和目标端低敏元数据。
 * 如果 data-sync 没有把 actorId、actorRole、actorTenantId 写进远端请求体，下游 DTO 校验会返回 400；
 * 如果 data-sync 在 includeSampleRows=false 时仍传 sampleRowLimit=0，下游的 {@code @Min(1)} 也会返回 400。
 * 一旦这些 400 没有被 data-sync 统一异常处理捕获，用户界面就会看到“HTTP 500”。</p>
 *
 * <p>因此这里不验证 JDBC 元数据读取本身，而是用 mock 客户端捕获 data-sync 发出的请求体，
 * 固定“业务操作者上下文必须透传、样本行限制必须为空、表数量上限必须与下游一致”这三个关键契约。</p>
 */
class SyncTaskMetadataConfigurationSupportTest {

    /**
     * 发现任务元数据时必须补齐下游 actor 字段，并且不能发送 sampleRowLimit=0。
     *
     * <p>业务意义：元数据发现虽然不读取样本数据，但它仍然会暴露 schema/table/field 结构信息，
     * 因此 datasource-management 需要知道是谁触发了这次发现，用于审计、权限和 trace 串联。
     * sampleRowLimit 只有在 includeSampleRows=true 时才有意义；不需要样本行时传 null，表达“不要读取样本值”。</p>
     */
    @Test
    void discoverTaskMetadataShouldForwardActorContextAndOmitSampleRowLimit() {
        DatasourceCapabilitySnapshotClient snapshotClient = mock(DatasourceCapabilitySnapshotClient.class);
        DatasourceMetadataDiscoveryClient metadataClient = mock(DatasourceMetadataDiscoveryClient.class);
        SyncAuditSupport auditSupport = mock(SyncAuditSupport.class);
        SyncTaskMetadataConfigurationSupport support = new SyncTaskMetadataConfigurationSupport(
                snapshotClient,
                metadataClient,
                new SyncDataScopeSupport(),
                auditSupport
        );
        when(snapshotClient.getSnapshot(eq(23L), eq(actor()))).thenReturn(mysqlSnapshot());
        when(metadataClient.discover(eq(23L), org.mockito.ArgumentMatchers.any(DatasourceMetadataDiscoveryRequest.class),
                eq(actor()))).thenReturn(discoveryResponse());

        SyncTaskMetadataDiscoveryResponse response = support.discoverTaskMetadata(discoveryRequest(), actor());

        ArgumentCaptor<DatasourceMetadataDiscoveryRequest> remoteRequestCaptor =
                ArgumentCaptor.forClass(DatasourceMetadataDiscoveryRequest.class);
        verify(metadataClient).discover(eq(23L), remoteRequestCaptor.capture(), eq(actor()));
        DatasourceMetadataDiscoveryRequest remoteRequest = remoteRequestCaptor.getValue();
        assertThat(remoteRequest.getActorId()).isEqualTo(1001L);
        assertThat(remoteRequest.getActorRole()).isEqualTo("PROJECT_OWNER");
        assertThat(remoteRequest.getActorTenantId()).isEqualTo(10L);
        assertThat(remoteRequest.getIncludeSampleRows()).isFalse();
        assertThat(remoteRequest.getSampleRowLimit()).isNull();
        assertThat(remoteRequest.getMaxTables()).isEqualTo(200);
        assertThat(response.getTables()).hasSize(1);
        assertThat(response.getTables().get(0).getFields())
                .extracting(SyncTaskMetadataDiscoveryResponse.FieldObject::getFieldName)
                .containsExactly("id");
    }

    private SyncTaskMetadataDiscoveryRequest discoveryRequest() {
        SyncTaskMetadataDiscoveryRequest request = new SyncTaskMetadataDiscoveryRequest();
        request.setDatasourceId(23L);
        request.setSide("SOURCE");
        request.setConnectorType("MYSQL");
        request.setFilterMode("TABLE");
        request.setIncludeColumns(true);
        request.setIncludeViews(true);
        /*
         * 前端可能传入比 datasource-management DTO 更大的值。
         * data-sync 应在适配层提前收口，避免让用户看到下游校验异常。
         */
        request.setMaxTables(500);
        request.setMaxColumnsPerTable(120);
        return request;
    }

    private DatasourceCapabilitySnapshotView mysqlSnapshot() {
        DatasourceCapabilitySnapshotView snapshot = new DatasourceCapabilitySnapshotView();
        snapshot.setDatasourceId(23L);
        snapshot.setTenantId(10L);
        snapshot.setProjectId(101L);
        snapshot.setConnectorType("MYSQL");
        return snapshot;
    }

    private DatasourceMetadataDiscoveryResponse discoveryResponse() {
        DatasourceMetadataDiscoveryResponse.ColumnSummary column = new DatasourceMetadataDiscoveryResponse.ColumnSummary();
        column.setColumnName("id");
        column.setDataTypeName("BIGINT");
        column.setNullable(false);
        column.setPrimaryKey(true);
        column.setOrdinalPosition(1);

        DatasourceMetadataDiscoveryResponse.TableSummary table = new DatasourceMetadataDiscoveryResponse.TableSummary();
        table.setCatalog("datasmart_govern");
        table.setTableName("demo_user");
        table.setTableType("TABLE");
        table.setPrimaryKeys(List.of("id"));
        table.setColumns(List.of(column));

        DatasourceMetadataDiscoveryResponse response = new DatasourceMetadataDiscoveryResponse();
        response.setDatasourceId(23L);
        response.setDatasourceType("MYSQL");
        response.setTableCount(1);
        response.setTables(List.of(table));
        response.setWarnings(List.of());
        return response;
    }

    private SyncActorContext actor() {
        return new SyncActorContext(
                10L,
                101L,
                null,
                1001L,
                "PROJECT_OWNER",
                "trace-metadata-configuration-test",
                "PROJECT",
                "project_id IN ${actorProjectIds}",
                List.of(101L),
                false
        );
    }
}
