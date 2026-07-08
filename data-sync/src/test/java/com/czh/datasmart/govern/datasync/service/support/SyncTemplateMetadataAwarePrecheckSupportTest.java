/**
 * @Author : Cui
 * @Date: 2026/07/08 22:18
 * @Description DataSmart Govern Backend - SyncTemplateMetadataAwarePrecheckSupportTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.entity.SyncTemplate;
import com.czh.datasmart.govern.datasync.integration.datasource.metadata.DatasourceMetadataDiscoveryClient;
import com.czh.datasmart.govern.datasync.integration.datasource.metadata.DatasourceMetadataDiscoveryResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 元数据感知预检查测试。
 *
 * <p>这组测试刻意聚焦用户创建同步任务向导里的关键体验：目标 schema/table 可以由用户自定义输入，
 * 后端不能在第二步强制同名映射或强制下拉选择，但第四步预检查必须基于真实目标端元数据判断该对象是否存在。
 * 这能避免“页面允许配置、执行时才炸”的产品体验。</p>
 */
class SyncTemplateMetadataAwarePrecheckSupportTest {

    @Test
    void customTargetTableShouldBeRejectedByPrecheckWhenMetadataCannotFindIt() {
        DatasourceMetadataDiscoveryClient metadataClient = mock(DatasourceMetadataDiscoveryClient.class);
        SyncTemplateMetadataAwarePrecheckSupport support =
                new SyncTemplateMetadataAwarePrecheckSupport(metadataClient, new ObjectMapper());
        when(metadataClient.discover(eq(23L), any(), any())).thenReturn(responseWithTable(null, "task",
                column("id", "BIGINT", true),
                column("task_name", "VARCHAR", false)));
        when(metadataClient.discover(eq(24L), any(), any())).thenReturn(emptyResponse());

        SyncTemplateMetadataAwarePrecheckSupport.MetadataAwarePrecheckResult result =
                support.evaluate(templateWithCustomTarget("custom_schema", "custom_table"), actor());

        assertThat(result.issueCodes()).contains("METADATA_TARGET_OBJECT_NOT_FOUND");
        assertThat(result.recommendedActions()).anyMatch(action -> action.contains("custom_schema.custom_table"));
    }

    @Test
    void uncheckedSourceOnlyAndTargetOnlyFieldsShouldNotBlockMetadataPrecheck() {
        DatasourceMetadataDiscoveryClient metadataClient = mock(DatasourceMetadataDiscoveryClient.class);
        SyncTemplateMetadataAwarePrecheckSupport support =
                new SyncTemplateMetadataAwarePrecheckSupport(metadataClient, new ObjectMapper());
        when(metadataClient.discover(eq(23L), any(), any())).thenReturn(responseWithTable(null, "task",
                column("id", "BIGINT", true),
                column("source_only", "VARCHAR", false)));
        when(metadataClient.discover(eq(24L), any(), any())).thenReturn(responseWithTable("target_schema", "target_task",
                column("id", "BIGINT", true),
                column("target_only", "TIMESTAMP", false)));

        SyncTemplateMetadataAwarePrecheckSupport.MetadataAwarePrecheckResult result =
                support.evaluate(templateWithCustomTarget("target_schema", "target_task"), actor());

        assertThat(result.issueCodes()).doesNotContain(
                "METADATA_SOURCE_FIELD_NOT_FOUND",
                "METADATA_TARGET_FIELD_NOT_FOUND",
                "METADATA_FIELD_MAPPING_TYPE_INCOMPATIBLE");
        assertThat(result.safetyNotes()).anyMatch(note -> note.contains("未勾选同步的源字段"));
        assertThat(result.safetyNotes()).anyMatch(note -> note.contains("未由源端写入的目标字段"));
    }

    private SyncTemplate templateWithCustomTarget(String targetSchema, String targetTable) {
        SyncTemplate template = new SyncTemplate();
        template.setId(1001L);
        template.setTenantId(10L);
        template.setProjectId(101L);
        template.setSourceDatasourceId(23L);
        template.setTargetDatasourceId(24L);
        template.setSourceConnectorType("MYSQL");
        template.setTargetConnectorType("POSTGRESQL");
        template.setSyncMode("FULL");
        template.setObjectMappingConfig("""
                {
                  "version": "datasmart.sync-object-mapping.v1",
                  "mappings": [
                    {
                      "sourceObject": "task",
                      "targetSchema": "%s",
                      "targetObject": "%s"
                    }
                  ]
                }
                """.formatted(targetSchema, targetTable));
        template.setFieldMappingConfig("""
                {
                  "version": "datasmart.sync-field-mapping.v2",
                  "objectMappings": [
                    {
                      "sourceObject": "task",
                      "targetSchema": "%s",
                      "targetObject": "%s",
                      "mappings": [
                        {"sourceField": "id", "targetField": "id", "syncEnabled": true},
                        {"sourceField": "source_only", "targetField": "", "syncEnabled": false},
                        {"sourceField": "", "targetField": "target_only", "syncEnabled": false}
                      ]
                    }
                  ]
                }
                """.formatted(targetSchema, targetTable));
        return template;
    }

    private SyncActorContext actor() {
        return new SyncActorContext(10L, 101L, null, 1001L,
                "PROJECT_OWNER", "trace-test", "PROJECT",
                "project_id IN ${actorProjectIds}", List.of(101L), false);
    }

    private DatasourceMetadataDiscoveryResponse emptyResponse() {
        DatasourceMetadataDiscoveryResponse response = new DatasourceMetadataDiscoveryResponse();
        response.setTables(List.of());
        return response;
    }

    private DatasourceMetadataDiscoveryResponse responseWithTable(String schema,
                                                                  String tableName,
                                                                  DatasourceMetadataDiscoveryResponse.ColumnSummary... columns) {
        DatasourceMetadataDiscoveryResponse response = new DatasourceMetadataDiscoveryResponse();
        DatasourceMetadataDiscoveryResponse.TableSummary table = new DatasourceMetadataDiscoveryResponse.TableSummary();
        table.setSchemaName(schema);
        table.setTableName(tableName);
        table.setTableType("TABLE");
        table.setPrimaryKeys(List.of("id"));
        table.setColumns(List.of(columns));
        response.setTables(List.of(table));
        return response;
    }

    private DatasourceMetadataDiscoveryResponse.ColumnSummary column(String name,
                                                                     String type,
                                                                     boolean primaryKey) {
        DatasourceMetadataDiscoveryResponse.ColumnSummary column =
                new DatasourceMetadataDiscoveryResponse.ColumnSummary();
        column.setColumnName(name);
        column.setDataTypeName(type);
        column.setPrimaryKey(primaryKey);
        column.setNullable(!primaryKey);
        return column;
    }
}
