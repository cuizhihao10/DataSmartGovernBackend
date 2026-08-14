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
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
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

    /** 字段名仅大小写漂移时，应采用实时元数据中的准确名称并强制绕过缓存。 */
    @Test
    void fieldMappingRepairShouldNormalizeMetadataProvenColumnNames() throws Exception {
        DatasourceMetadataDiscoveryClient metadataClient = mock(DatasourceMetadataDiscoveryClient.class);
        SyncTaskDefinitionMetadataAwarePrecheckSupport support = support(metadataClient, emptyTargetProbe());
        when(metadataClient.discover(eq(23L), any(), any())).thenReturn(responseWithTable(null, "task",
                column("ID", "BIGINT", true)));
        when(metadataClient.discover(eq(24L), any(), any())).thenReturn(responseWithTable(
                "target_schema", "target_task", column("ID", "BIGINT", true)));
        SyncTaskDefinition definition = definitionWithCustomTarget("target_schema", "target_task");

        SyncTaskDefinitionMetadataAwarePrecheckSupport.MetadataFieldMappingRepairResult result =
                support.repairFieldMappings(definition, actor());

        var row = new ObjectMapper().readTree(result.fieldMappingConfig())
                .path("objectMappings").get(0).path("mappings").get(0);
        assertThat(result.changedCount()).isEqualTo(1);
        assertThat(row.path("sourceField").asText()).isEqualTo("ID");
        assertThat(row.path("targetField").asText()).isEqualTo("ID");
        ArgumentCaptor<com.czh.datasmart.govern.datasync.integration.datasource.metadata.DatasourceMetadataDiscoveryRequest>
                request = ArgumentCaptor.forClass(
                com.czh.datasmart.govern.datasync.integration.datasource.metadata.DatasourceMetadataDiscoveryRequest.class);
        verify(metadataClient, atLeastOnce()).discover(any(), request.capture(), any());
        assertThat(request.getAllValues()).allMatch(value -> Boolean.TRUE.equals(value.getForceRefresh()));
    }

    /**
     * 目标列发生真实改名时，只有唯一的未占用候选满足类型、主键属性和元数据序号约束才允许自动修复。
     *
     * <p>该场景模拟 {@code customer_name} 被数据库管理员改名为 {@code name}。其它三列仍被现有映射占用，
     * 因而 {@code name} 是唯一可证明候选。修复只改变已有映射的目标字段，不新增列、不改 DDL、不扩大
     * 同步行列范围；调用方还会在持久化前再次运行完整预检。</p>
     */
    @Test
    void fieldMappingRepairShouldRetargetUniquelyProvenRenamedColumn() throws Exception {
        DatasourceMetadataDiscoveryClient metadataClient = mock(DatasourceMetadataDiscoveryClient.class);
        SyncTaskDefinitionMetadataAwarePrecheckSupport support = support(metadataClient, emptyTargetProbe());
        when(metadataClient.discover(eq(23L), any(), any())).thenReturn(responseWithTable(null, "task",
                columnAt("id", "BIGINT", true, 1),
                columnAt("customer_name", "VARCHAR", false, 2),
                columnAt("amount", "DECIMAL", false, 3),
                columnAt("region", "VARCHAR", false, 4)));
        DatasourceMetadataDiscoveryResponse.ColumnSummary renamed =
                columnAt("name", "VARCHAR", false, 2);
        renamed.setNullable(false);
        when(metadataClient.discover(eq(24L), any(), any())).thenReturn(responseWithTable(
                "target_schema", "target_task",
                columnAt("id", "BIGINT", true, 1),
                renamed,
                columnAt("amount", "NUMERIC", false, 3),
                columnAt("region", "VARCHAR", false, 4)));
        SyncTaskDefinition definition = definitionWithCustomTarget("target_schema", "target_task");
        definition.setFieldMappingConfig("""
                {"version":"datasmart.sync.field-mapping.v2","objectMappings":[{
                  "sourceObjectName":"task","targetSchema":"target_schema","targetObjectName":"target_task",
                  "mappings":[
                    {"sourceField":"id","targetField":"id","syncEnabled":true},
                    {"sourceField":"customer_name","targetField":"customer_name","syncEnabled":true},
                    {"sourceField":"amount","targetField":"amount","syncEnabled":true},
                    {"sourceField":"region","targetField":"region","syncEnabled":true}
                  ]
                }]}
                """);

        SyncTaskDefinitionMetadataAwarePrecheckSupport.MetadataFieldMappingRepairResult result =
                support.repairFieldMappings(definition, actor());

        var rows = new ObjectMapper().readTree(result.fieldMappingConfig())
                .path("objectMappings").get(0).path("mappings");
        assertThat(result.changedCount()).isEqualTo(1);
        assertThat(result.issueCodes()).isEmpty();
        assertThat(rows.get(1).path("sourceField").asText()).isEqualTo("customer_name");
        assertThat(rows.get(1).path("targetField").asText()).isEqualTo("name");
    }

    /**
     * 多个未占用目标列都与失效映射兼容时必须停止自动修复，不能按名称相似度或模型猜测任选一个。
     */
    @Test
    void fieldMappingRepairShouldRejectAmbiguousRenamedColumnCandidates() {
        DatasourceMetadataDiscoveryClient metadataClient = mock(DatasourceMetadataDiscoveryClient.class);
        SyncTaskDefinitionMetadataAwarePrecheckSupport support = support(metadataClient, emptyTargetProbe());
        when(metadataClient.discover(eq(23L), any(), any())).thenReturn(responseWithTable(null, "task",
                column("id", "BIGINT", true), column("customer_name", "VARCHAR", false)));
        when(metadataClient.discover(eq(24L), any(), any())).thenReturn(responseWithTable(
                "target_schema", "target_task",
                column("id", "BIGINT", true),
                column("name", "VARCHAR", false),
                column("display_name", "VARCHAR", false)));
        SyncTaskDefinition definition = definitionWithCustomTarget("target_schema", "target_task");
        definition.setFieldMappingConfig("""
                {"version":"datasmart.sync.field-mapping.v2","objectMappings":[{
                  "sourceObjectName":"task","targetSchema":"target_schema","targetObjectName":"target_task",
                  "mappings":[
                    {"sourceField":"id","targetField":"id","syncEnabled":true},
                    {"sourceField":"customer_name","targetField":"customer_name","syncEnabled":true}
                  ]
                }]}
                """);

        SyncTaskDefinitionMetadataAwarePrecheckSupport.MetadataFieldMappingRepairResult result =
                support.repairFieldMappings(definition, actor());

        assertThat(result.changedCount()).isZero();
        assertThat(result.issueCodes()).contains("AUTOPILOT_TARGET_FIELD_REPAIR_NOT_DETERMINISTIC");
    }

    /** 源字段已删除时，只有目标列本身可空或有数据库生成语义才允许停用该映射。 */
    @Test
    void fieldMappingRepairShouldOmitStaleSourceOnlyForNullableTarget() throws Exception {
        DatasourceMetadataDiscoveryClient metadataClient = mock(DatasourceMetadataDiscoveryClient.class);
        SyncTaskDefinitionMetadataAwarePrecheckSupport support = support(metadataClient, emptyTargetProbe());
        when(metadataClient.discover(eq(23L), any(), any())).thenReturn(responseWithTable(null, "task",
                column("id", "BIGINT", true)));
        when(metadataClient.discover(eq(24L), any(), any())).thenReturn(responseWithTable(
                "target_schema", "target_task",
                column("id", "BIGINT", true), column("optional_note", "VARCHAR", false)));
        SyncTaskDefinition definition = definitionWithCustomTarget("target_schema", "target_task");
        definition.setFieldMappingConfig("""
                {"version":"datasmart.sync-field-mapping.v2","objectMappings":[{
                  "sourceObjectName":"task","targetSchema":"target_schema","targetObjectName":"target_task",
                  "mappings":[{"sourceField":"legacy_note","targetField":"optional_note","syncEnabled":true}]
                }]}
                """);

        SyncTaskDefinitionMetadataAwarePrecheckSupport.MetadataFieldMappingRepairResult result =
                support.repairFieldMappings(definition, actor());

        var row = new ObjectMapper().readTree(result.fieldMappingConfig())
                .path("objectMappings").get(0).path("mappings").get(0);
        assertThat(result.changedCount()).isEqualTo(1);
        assertThat(row.path("syncEnabled").asBoolean()).isFalse();
        assertThat(result.issueCodes()).isEmpty();
    }

    /** 源字段已删除但目标已有数据库默认值时，可停用旧映射并让数据库使用既有默认语义。 */
    @Test
    void fieldMappingRepairShouldOmitStaleSourceWhenTargetHasDatabaseDefault() throws Exception {
        DatasourceMetadataDiscoveryClient metadataClient = mock(DatasourceMetadataDiscoveryClient.class);
        SyncTaskDefinitionMetadataAwarePrecheckSupport support = support(metadataClient, emptyTargetProbe());
        when(metadataClient.discover(eq(23L), any(), any())).thenReturn(responseWithTable(null, "task",
                column("id", "BIGINT", true)));
        DatasourceMetadataDiscoveryResponse.ColumnSummary defaulted = column("status", "VARCHAR", false);
        defaulted.setNullable(false);
        defaulted.setDefaultValue("ACTIVE");
        when(metadataClient.discover(eq(24L), any(), any())).thenReturn(responseWithTable(
                "target_schema", "target_task", column("id", "BIGINT", true), defaulted));
        SyncTaskDefinition definition = definitionWithCustomTarget("target_schema", "target_task");
        definition.setFieldMappingConfig("""
                {"version":"datasmart.sync-field-mapping.v2","objectMappings":[{
                  "sourceObjectName":"task","targetSchema":"target_schema","targetObjectName":"target_task",
                  "mappings":[
                    {"sourceField":"id","targetField":"id","syncEnabled":true},
                    {"sourceField":"legacy_status","targetField":"status","syncEnabled":true}
                  ]
                }]}
                """);

        SyncTaskDefinitionMetadataAwarePrecheckSupport.MetadataFieldMappingRepairResult result =
                support.repairFieldMappings(definition, actor());

        var rows = new ObjectMapper().readTree(result.fieldMappingConfig())
                .path("objectMappings").get(0).path("mappings");
        assertThat(result.changedCount()).isEqualTo(1);
        assertThat(rows.get(1).path("syncEnabled").asBoolean()).isFalse();
        assertThat(result.issueCodes()).isEmpty();
    }

    /** 必填且无默认值的目标列未映射时，预检必须在写入前阻断。 */
    @Test
    void precheckShouldRejectUnmappedRequiredTargetColumn() {
        DatasourceMetadataDiscoveryClient metadataClient = mock(DatasourceMetadataDiscoveryClient.class);
        SyncTaskDefinitionMetadataAwarePrecheckSupport support = support(metadataClient, emptyTargetProbe());
        when(metadataClient.discover(eq(23L), any(), any())).thenReturn(responseWithTable(null, "task",
                column("id", "BIGINT", true)));
        DatasourceMetadataDiscoveryResponse.ColumnSummary required = column("required_code", "VARCHAR", false);
        required.setNullable(false);
        when(metadataClient.discover(eq(24L), any(), any())).thenReturn(responseWithTable(
                "target_schema", "target_task", column("id", "BIGINT", true), required));

        SyncTaskDefinitionMetadataAwarePrecheckSupport.MetadataAwarePrecheckResult result =
                support.evaluate(definitionWithCustomTarget("target_schema", "target_task"), actor());

        assertThat(result.issueCodes()).contains("METADATA_REQUIRED_TARGET_FIELD_NOT_MAPPED");
        assertThat(result.recommendedActions()).anyMatch(action -> action.contains("必填列"));
    }

    /** 必填且无默认值的目标列不能通过停用映射自动绕过。 */
    @Test
    void fieldMappingRepairShouldRejectRequiredTargetWithoutDefault() throws Exception {
        DatasourceMetadataDiscoveryClient metadataClient = mock(DatasourceMetadataDiscoveryClient.class);
        SyncTaskDefinitionMetadataAwarePrecheckSupport support = support(metadataClient, emptyTargetProbe());
        when(metadataClient.discover(eq(23L), any(), any())).thenReturn(responseWithTable(null, "task",
                column("id", "BIGINT", true)));
        DatasourceMetadataDiscoveryResponse.ColumnSummary required = column("required_code", "VARCHAR", false);
        required.setNullable(false);
        when(metadataClient.discover(eq(24L), any(), any())).thenReturn(responseWithTable(
                "target_schema", "target_task", column("id", "BIGINT", true), required));
        SyncTaskDefinition definition = definitionWithCustomTarget("target_schema", "target_task");
        definition.setFieldMappingConfig("""
                {"version":"datasmart.sync-field-mapping.v2","objectMappings":[{
                  "sourceObjectName":"task","targetSchema":"target_schema","targetObjectName":"target_task",
                  "mappings":[{"sourceField":"legacy_code","targetField":"required_code","syncEnabled":true}]
                }]}
                """);

        SyncTaskDefinitionMetadataAwarePrecheckSupport.MetadataFieldMappingRepairResult result =
                support.repairFieldMappings(definition, actor());

        var row = new ObjectMapper().readTree(result.fieldMappingConfig())
                .path("objectMappings").get(0).path("mappings").get(0);
        assertThat(result.changedCount()).isZero();
        assertThat(row.path("syncEnabled").asBoolean()).isTrue();
        assertThat(result.issueCodes()).contains("AUTOPILOT_SOURCE_FIELD_REPAIR_NOT_DETERMINISTIC");
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

    /** 创建带 JDBC 元数据序号的字段摘要，用于证明列改名没有改变表内结构位置。 */
    private DatasourceMetadataDiscoveryResponse.ColumnSummary columnAt(
            String name,
            String type,
            boolean primaryKey,
            int ordinalPosition) {
        DatasourceMetadataDiscoveryResponse.ColumnSummary column = column(name, type, primaryKey);
        column.setOrdinalPosition(ordinalPosition);
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
