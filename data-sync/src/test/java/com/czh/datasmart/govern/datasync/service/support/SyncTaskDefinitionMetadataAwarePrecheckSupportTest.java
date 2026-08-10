/**
 * @Author : Cui
 * @Date: 2026/07/08 22:18
 * @Description DataSmart Govern Backend - SyncTaskDefinitionMetadataAwarePrecheckSupportTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.entity.SyncTaskDefinition;
import com.czh.datasmart.govern.datasync.integration.datasource.metadata.DatasourceMetadataDiscoveryClient;
import com.czh.datasmart.govern.datasync.integration.datasource.metadata.DatasourceMetadataDiscoveryResponse;
import com.czh.datasmart.govern.datasync.integration.datasource.tableprobe.DatasourceTableRowCountProbeClient;
import com.czh.datasmart.govern.datasync.integration.datasource.tableprobe.DatasourceTableRowCountProbeResponse;
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
class SyncTaskDefinitionMetadataAwarePrecheckSupportTest {

    @Test
    void customTargetTableShouldBeRejectedByPrecheckWhenMetadataCannotFindIt() {
        DatasourceMetadataDiscoveryClient metadataClient = mock(DatasourceMetadataDiscoveryClient.class);
        SyncTaskDefinitionMetadataAwarePrecheckSupport support = support(metadataClient, emptyTargetProbe());
        when(metadataClient.discover(eq(23L), any(), any())).thenReturn(responseWithTable(null, "task",
                column("id", "BIGINT", true),
                column("task_name", "VARCHAR", false)));
        when(metadataClient.discover(eq(24L), any(), any())).thenReturn(emptyResponse());

        SyncTaskDefinitionMetadataAwarePrecheckSupport.MetadataAwarePrecheckResult result =
                support.evaluate(definitionWithCustomTarget("custom_schema", "custom_table"), actor());

        assertThat(result.issueCodes()).contains("METADATA_TARGET_OBJECT_NOT_FOUND");
        assertThat(result.recommendedActions()).anyMatch(action -> action.contains("custom_schema.custom_table"));
    }

    @Test
    void uncheckedSourceOnlyAndTargetOnlyFieldsShouldNotBlockMetadataPrecheck() {
        DatasourceMetadataDiscoveryClient metadataClient = mock(DatasourceMetadataDiscoveryClient.class);
        SyncTaskDefinitionMetadataAwarePrecheckSupport support = support(metadataClient, emptyTargetProbe());
        when(metadataClient.discover(eq(23L), any(), any())).thenReturn(responseWithTable(null, "task",
                column("id", "BIGINT", true),
                column("source_only", "VARCHAR", false)));
        when(metadataClient.discover(eq(24L), any(), any())).thenReturn(responseWithTable("target_schema", "target_task",
                column("id", "BIGINT", true),
                column("target_only", "TIMESTAMP", false)));

        SyncTaskDefinitionMetadataAwarePrecheckSupport.MetadataAwarePrecheckResult result =
                support.evaluate(definitionWithCustomTarget("target_schema", "target_task"), actor());

        assertThat(result.issueCodes()).doesNotContain(
                "METADATA_SOURCE_FIELD_NOT_FOUND",
                "METADATA_TARGET_FIELD_NOT_FOUND",
                "METADATA_FIELD_MAPPING_TYPE_INCOMPATIBLE");
        assertThat(result.safetyNotes()).anyMatch(note -> note.contains("未勾选同步的源字段"));
        assertThat(result.safetyNotes()).anyMatch(note -> note.contains("未由源端写入的目标字段"));
    }

    @Test
    void updateWriteStrategyShouldRequireTargetPrimaryKeyFromMetadata() {
        DatasourceMetadataDiscoveryClient metadataClient = mock(DatasourceMetadataDiscoveryClient.class);
        SyncTaskDefinitionMetadataAwarePrecheckSupport support = support(metadataClient, emptyTargetProbe());
        when(metadataClient.discover(eq(23L), any(), any())).thenReturn(responseWithTable(null, "task",
                column("id", "BIGINT", true),
                column("task_name", "VARCHAR", false)));
        when(metadataClient.discover(eq(24L), any(), any())).thenReturn(responseWithTableWithoutPrimaryKey(
                "target_schema",
                "target_task",
                column("id", "BIGINT", false),
                column("task_name", "VARCHAR", false)));

        SyncTaskDefinition definition = definitionWithCustomTarget("target_schema", "target_task");
        definition.setWriteStrategy("UPDATE");

        SyncTaskDefinitionMetadataAwarePrecheckSupport.MetadataAwarePrecheckResult result =
                support.evaluate(definition, actor());

        assertThat(result.issueCodes()).contains("METADATA_TARGET_PRIMARY_KEY_REQUIRED_FOR_UPDATE");
        assertThat(result.recommendedActions()).anyMatch(action -> action.contains("update/merge"));
    }

    @Test
    void updateWriteStrategyShouldRequireMappedTargetPrimaryKeyField() {
        DatasourceMetadataDiscoveryClient metadataClient = mock(DatasourceMetadataDiscoveryClient.class);
        SyncTaskDefinitionMetadataAwarePrecheckSupport support = support(metadataClient, emptyTargetProbe());
        when(metadataClient.discover(eq(23L), any(), any())).thenReturn(responseWithTable(null, "task",
                column("id", "BIGINT", true),
                column("task_name", "VARCHAR", false)));
        when(metadataClient.discover(eq(24L), any(), any())).thenReturn(responseWithTable("target_schema", "target_task",
                column("id", "BIGINT", true),
                column("task_name", "VARCHAR", false)));

        SyncTaskDefinition definition = definitionWithCustomTarget("target_schema", "target_task");
        definition.setWriteStrategy("UPDATE");
        definition.setFieldMappingConfig("""
                {
                  "version": "datasmart.sync-field-mapping.v2",
                  "objectMappings": [
                    {
                      "sourceObjectName": "task",
                      "targetSchema": "target_schema",
                      "targetObjectName": "target_task",
                      "mappings": [
                        {"sourceField": "task_name", "targetField": "task_name", "syncEnabled": true}
                      ]
                    }
                  ]
                }
                """);

        SyncTaskDefinitionMetadataAwarePrecheckSupport.MetadataAwarePrecheckResult result =
                support.evaluate(definition, actor());

        assertThat(result.issueCodes()).contains("METADATA_TARGET_PRIMARY_KEY_FIELD_NOT_MAPPED_FOR_UPDATE");
        assertThat(result.recommendedActions()).anyMatch(action -> action.contains("主键字段"));
    }

    /**
     * 验证旧版单对象任务直接把字段映射保存为 JSON 数组时，预检查与执行器使用完全一致的解析语义。
     *
     * <p>该格式仍可能来自历史草稿、编辑任务恢复以及本地 E2E 脚本。如果预检查只识别 v2 对象包装格式，
     * 即使数组中已经明确配置了目标主键 {@code id -> id}，也会错误地同时报告“未选择字段”和
     * “UPDATE 未映射主键”。测试使用顶层源/目标对象字段，特意不设置 objectMappingConfig，确保覆盖
     * 真实 SINGLE_OBJECT 兼容路径，而不是再次走新版对象映射结构。</p>
     */
    @Test
    void legacySingleObjectDirectFieldMappingArrayShouldMapTargetPrimaryKeyForUpdate() {
        DatasourceMetadataDiscoveryClient metadataClient = mock(DatasourceMetadataDiscoveryClient.class);
        SyncTaskDefinitionMetadataAwarePrecheckSupport support = support(metadataClient, emptyTargetProbe());
        when(metadataClient.discover(eq(23L), any(), any())).thenReturn(responseWithTable(
                null,
                "task",
                column("id", "BIGINT", true),
                column("task_name", "VARCHAR", false)));
        when(metadataClient.discover(eq(24L), any(), any())).thenReturn(responseWithTable(
                "target_schema",
                "target_task",
                column("id", "BIGINT", true),
                column("task_name", "VARCHAR", false)));

        SyncTaskDefinition definition = new SyncTaskDefinition();
        definition.setId(1003L);
        definition.setTenantId(10L);
        definition.setProjectId(101L);
        definition.setSourceDatasourceId(23L);
        definition.setTargetDatasourceId(24L);
        definition.setSourceConnectorType("MYSQL");
        definition.setTargetConnectorType("POSTGRESQL");
        definition.setSyncMode("FULL");
        definition.setWriteStrategy("UPDATE");
        definition.setSourceObjectName("task");
        definition.setTargetSchemaName("target_schema");
        definition.setTargetObjectName("target_task");
        definition.setFieldMappingConfig("""
                [
                  {"sourceField": "id", "targetField": "id", "syncEnabled": true},
                  {"sourceField": "task_name", "targetField": "task_name", "syncEnabled": true}
                ]
                """);

        SyncTaskDefinitionMetadataAwarePrecheckSupport.MetadataAwarePrecheckResult result =
                support.evaluate(definition, actor());

        assertThat(result.issueCodes()).doesNotContain(
                "METADATA_FIELD_MAPPING_SELECTED_EMPTY",
                "METADATA_TARGET_PRIMARY_KEY_FIELD_NOT_MAPPED_FOR_UPDATE");
    }

    /**
     * 验证发布阶段会把真实目标主键写成内部映射标记，使后续执行契约能够生成 primaryKeyColumns。
     *
     * <p>这里不通过用户填写 primaryKeyField 来让测试通过，而是沿用真实创建向导的行为：定义中的
     * primaryKeyField 保持为空，系统读取目标表元数据并在 id 映射行上增加 targetPrimaryKey。这样可以证明
     * “用户只选 UPDATE，系统自动识别冲突键”的产品语义真正进入了 worker 合同。</p>
     */
    @Test
    void executionPrimaryKeyEnrichmentShouldAnnotateMappedTargetPrimaryKey() throws Exception {
        DatasourceMetadataDiscoveryClient metadataClient = mock(DatasourceMetadataDiscoveryClient.class);
        SyncTaskDefinitionMetadataAwarePrecheckSupport support = support(metadataClient, emptyTargetProbe());
        when(metadataClient.discover(eq(24L), any(), any())).thenReturn(responseWithTable(
                "target_schema",
                "target_task",
                column("id", "BIGINT", true),
                column("task_name", "VARCHAR", false)));

        SyncTaskDefinition definition = new SyncTaskDefinition();
        definition.setTargetDatasourceId(24L);
        definition.setTargetConnectorType("POSTGRESQL");
        definition.setTargetSchemaName("target_schema");
        definition.setTargetObjectName("target_task");
        definition.setSourceObjectName("task");
        definition.setSyncMode("FULL");
        definition.setWriteStrategy("UPDATE");
        definition.setFieldMappingConfig("""
                [
                  {"sourceField": "id", "targetField": "id", "syncEnabled": true},
                  {"sourceField": "task_name", "targetField": "task_name", "syncEnabled": true}
                ]
                """);

        String enriched = support.enrichExecutionPrimaryKeyFacts(definition, actor());

        assertThat(new ObjectMapper().readTree(enriched).get(0).path("targetPrimaryKey").asBoolean()).isTrue();
        SyncFieldMappingExecutionContract contract =
                new SyncFieldMappingExecutionContractSupport(new ObjectMapper()).parse(enriched, null);
        assertThat(contract.getPrimaryKeyColumns()).containsExactly("id");
    }

    /**
     * 验证复合主键必须完整映射，防止只映射其中一列却生成不稳定的 merge 冲突边界。
     */
    @Test
    void updateShouldRequireEveryTargetCompositePrimaryKeyColumnToBeMapped() {
        DatasourceMetadataDiscoveryClient metadataClient = mock(DatasourceMetadataDiscoveryClient.class);
        SyncTaskDefinitionMetadataAwarePrecheckSupport support = support(metadataClient, emptyTargetProbe());
        when(metadataClient.discover(eq(23L), any(), any())).thenReturn(responseWithTable(null, "task",
                column("id", "BIGINT", true),
                column("task_name", "VARCHAR", false)));
        DatasourceMetadataDiscoveryResponse targetResponse = responseWithTable(
                "target_schema",
                "target_task",
                column("id", "BIGINT", true),
                column("tenant_id", "BIGINT", true),
                column("task_name", "VARCHAR", false));
        targetResponse.getTables().getFirst().setPrimaryKeys(List.of("tenant_id", "id"));
        when(metadataClient.discover(eq(24L), any(), any())).thenReturn(targetResponse);

        SyncTaskDefinition definition = definitionWithCustomTarget("target_schema", "target_task");
        definition.setWriteStrategy("UPDATE");

        SyncTaskDefinitionMetadataAwarePrecheckSupport.MetadataAwarePrecheckResult result =
                support.evaluate(definition, actor());

        assertThat(result.issueCodes()).contains("METADATA_TARGET_PRIMARY_KEY_FIELD_NOT_MAPPED_FOR_UPDATE");
        assertThat(result.recommendedActions()).anyMatch(action -> action.contains("tenant_id"));
    }

    @Test
    void customSqlModeShouldValidateTargetFieldWithoutRequiringSourceObject() {
        DatasourceMetadataDiscoveryClient metadataClient = mock(DatasourceMetadataDiscoveryClient.class);
        SyncTaskDefinitionMetadataAwarePrecheckSupport support = support(metadataClient, emptyTargetProbe());
        when(metadataClient.discover(eq(24L), any(), any())).thenReturn(responseWithTable("dwd", "sql_target",
                column("id", "BIGINT", true),
                column("member_name", "VARCHAR", false)));

        SyncTaskDefinitionMetadataAwarePrecheckSupport.MetadataAwarePrecheckResult result =
                support.evaluate(definitionWithCustomSqlTarget("dwd", "sql_target", "missing_column"), actor());

        assertThat(result.issueCodes()).contains("METADATA_TARGET_FIELD_NOT_FOUND");
        assertThat(result.issueCodes()).doesNotContain("METADATA_SOURCE_OBJECT_REQUIRED");
        assertThat(result.recommendedActions()).anyMatch(action -> action.contains("missing_column"));
    }

    @Test
    void customSqlModeShouldRejectMissingTargetObjectByMetadata() {
        DatasourceMetadataDiscoveryClient metadataClient = mock(DatasourceMetadataDiscoveryClient.class);
        SyncTaskDefinitionMetadataAwarePrecheckSupport support = support(metadataClient, emptyTargetProbe());
        when(metadataClient.discover(eq(24L), any(), any())).thenReturn(emptyResponse());

        SyncTaskDefinitionMetadataAwarePrecheckSupport.MetadataAwarePrecheckResult result =
                support.evaluate(definitionWithCustomSqlTarget("dwd", "sql_target", "member_name"), actor());

        assertThat(result.issueCodes()).contains("METADATA_TARGET_OBJECT_NOT_FOUND");
        assertThat(result.recommendedActions()).anyMatch(action -> action.contains("dwd.sql_target"));
    }

    @Test
    void fullInsertShouldRejectNonEmptyTargetTable() {
        DatasourceMetadataDiscoveryClient metadataClient = mock(DatasourceMetadataDiscoveryClient.class);
        SyncTaskDefinitionMetadataAwarePrecheckSupport support = support(metadataClient, targetProbe(12L));
        when(metadataClient.discover(eq(23L), any(), any())).thenReturn(responseWithTable(null, "task",
                column("id", "BIGINT", true),
                column("task_name", "VARCHAR", false)));
        when(metadataClient.discover(eq(24L), any(), any())).thenReturn(responseWithTable("target_schema", "target_task",
                column("id", "BIGINT", true),
                column("task_name", "VARCHAR", false)));

        SyncTaskDefinitionMetadataAwarePrecheckSupport.MetadataAwarePrecheckResult result =
                support.evaluate(definitionWithCustomTarget("target_schema", "target_task"), actor());

        assertThat(result.issueCodes()).contains("METADATA_TARGET_NOT_EMPTY_FOR_INSERT_FULL");
        assertThat(result.recommendedActions()).anyMatch(action -> action.contains("当前行数为 12"));
    }

    @Test
    void postgresqlTargetShouldRequireSchemaBeforeMetadataLookup() {
        DatasourceMetadataDiscoveryClient metadataClient = mock(DatasourceMetadataDiscoveryClient.class);
        SyncTaskDefinitionMetadataAwarePrecheckSupport support = support(metadataClient, emptyTargetProbe());
        when(metadataClient.discover(eq(23L), any(), any())).thenReturn(responseWithTable(null, "task",
                column("id", "BIGINT", true)));

        SyncTaskDefinitionMetadataAwarePrecheckSupport.MetadataAwarePrecheckResult result =
                support.evaluate(definitionWithCustomTarget("", "target_task"), actor());

        assertThat(result.issueCodes()).contains("METADATA_TARGET_SCHEMA_REQUIRED");
        assertThat(result.recommendedActions()).anyMatch(action -> action.contains("目标端连接器 POSTGRESQL"));
    }

    private SyncTaskDefinition definitionWithCustomTarget(String targetSchema, String targetTable) {
        SyncTaskDefinition definition = new SyncTaskDefinition();
        definition.setId(1001L);
        definition.setTenantId(10L);
        definition.setProjectId(101L);
        definition.setSourceDatasourceId(23L);
        definition.setTargetDatasourceId(24L);
        definition.setSourceConnectorType("MYSQL");
        definition.setTargetConnectorType("POSTGRESQL");
        definition.setSyncMode("FULL");
        definition.setObjectMappingConfig("""
                {
                  "version": "datasmart.sync-object-mapping.v1",
                  "mappings": [
                    {
                      "sourceObjectName": "task",
                      "targetSchema": "%s",
                      "targetObjectName": "%s"
                    }
                  ]
                }
                """.formatted(targetSchema, targetTable));
        definition.setFieldMappingConfig("""
                {
                  "version": "datasmart.sync-field-mapping.v2",
                  "objectMappings": [
                    {
                      "sourceObjectName": "task",
                      "targetSchema": "%s",
                      "targetObjectName": "%s",
                      "mappings": [
                        {"sourceField": "id", "targetField": "id", "syncEnabled": true},
                        {"sourceField": "source_only", "targetField": "", "syncEnabled": false},
                        {"sourceField": "", "targetField": "target_only", "syncEnabled": false}
                      ]
                    }
                  ]
                }
                """.formatted(targetSchema, targetTable));
        return definition;
    }

    private SyncTaskDefinition definitionWithCustomSqlTarget(String targetSchema, String targetTable, String targetField) {
        SyncTaskDefinition definition = new SyncTaskDefinition();
        definition.setId(1002L);
        definition.setTenantId(10L);
        definition.setProjectId(101L);
        definition.setSourceDatasourceId(23L);
        definition.setTargetDatasourceId(24L);
        definition.setSourceConnectorType("MYSQL");
        definition.setTargetConnectorType("POSTGRESQL");
        definition.setSyncMode("CUSTOM_SQL_QUERY");
        definition.setWriteStrategy("INSERT");
        definition.setTargetSchemaName(targetSchema);
        definition.setTargetObjectName(targetTable);
        definition.setFieldMappingConfig("""
                {
                  "version": "datasmart.sync-field-mapping.v2",
                  "mappings": [
                    {"sourceField": "member_name", "targetField": "%s", "syncEnabled": true}
                  ]
                }
                """.formatted(targetField));
        return definition;
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

    private DatasourceMetadataDiscoveryResponse responseWithTableWithoutPrimaryKey(
            String schema,
            String tableName,
            DatasourceMetadataDiscoveryResponse.ColumnSummary... columns) {
        DatasourceMetadataDiscoveryResponse response = responseWithTable(schema, tableName, columns);
        response.getTables().getFirst().setPrimaryKeys(List.of());
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

    private SyncTaskDefinitionMetadataAwarePrecheckSupport support(DatasourceMetadataDiscoveryClient metadataClient,
                                                            DatasourceTableRowCountProbeClient rowCountProbeClient) {
        return new SyncTaskDefinitionMetadataAwarePrecheckSupport(metadataClient, rowCountProbeClient, new ObjectMapper());
    }

    private DatasourceTableRowCountProbeClient emptyTargetProbe() {
        return targetProbe(0L);
    }

    private DatasourceTableRowCountProbeClient targetProbe(Long rowCount) {
        DatasourceTableRowCountProbeClient client = mock(DatasourceTableRowCountProbeClient.class);
        when(client.probeRowCount(any(), any())).thenReturn(rowCountResponse(rowCount));
        return client;
    }

    private DatasourceTableRowCountProbeResponse rowCountResponse(Long rowCount) {
        DatasourceTableRowCountProbeResponse response = new DatasourceTableRowCountProbeResponse();
        response.setProbeStatus("ROW_COUNT_PROBED");
        response.setRowCount(rowCount);
        response.setEmpty(rowCount == null ? null : rowCount <= 0L);
        response.setWarnings(List.of());
        return response;
    }
}
