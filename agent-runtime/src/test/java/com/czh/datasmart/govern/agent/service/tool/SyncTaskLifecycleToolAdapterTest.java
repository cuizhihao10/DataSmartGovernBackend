/**
 * @Author : Cui
 * @Date: 2026/07/26 18:40
 * @Description DataSmart Govern Backend - SyncTaskLifecycleToolAdapterTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 保护 Agent 创建任务与人工创建向导共用的对象、字段和模式语义。
 *
 * <p>测试刻意使用不同的源表、目标表和字段名。这样只要实现再次把映射错误地重建为
 * “源端到源端”或“目标端到目标端”，断言就会立即失败。</p>
 */
class SyncTaskLifecycleToolAdapterTest {

    private final SyncTaskLifecycleToolAdapter adapter =
            new SyncTaskLifecycleToolAdapter(null, null, null, new ObjectMapper());

    @Test
    void shouldPreserveExplicitSourceToTargetTableAndFieldMapping() throws Exception {
        Map<String, Object> rawMapping = new LinkedHashMap<>();
        rawMapping.put("objectKey", "customer-transfer");
        rawMapping.put("sourceObjectName", "source_customer");
        rawMapping.put("targetSchemaName", "public");
        rawMapping.put("targetObjectName", "target_customer");
        rawMapping.put("fieldMappings", List.of(Map.of(
                "sourceField", "id",
                "targetField", "customer_id",
                "syncEnabled", true
        )));

        List<?> resolvedMappings = resolveMappings(List.of(rawMapping), false);
        Map<String, Object> config = buildFieldMappingConfig(
                resolvedMappings,
                metadata(table(null, "source_customer", column("id", "BIGINT"))),
                metadata(table("public", "target_customer", column("customer_id", "BIGINT"))),
                false
        );

        Map<String, Object> objectMapping = firstMap(config.get("objectMappings"));
        Map<String, Object> fieldMapping = firstMap(objectMapping.get("mappings"));
        assertEquals("source_customer", objectMapping.get("sourceObjectName"));
        assertEquals("target_customer", objectMapping.get("targetObjectName"));
        assertEquals("id", fieldMapping.get("sourceField"));
        assertEquals("customer_id", fieldMapping.get("targetField"));
    }

    @Test
    void shouldNormalizeLegacyRealtimeModeToProductContract() {
        assertEquals("CDC_STREAMING", adapter.normalizeSyncMode("REAL_TIME"));
        assertEquals("CDC_STREAMING", adapter.normalizeSyncMode("CDC_STREAMING"));
    }

    private List<?> resolveMappings(Object rawMappings, boolean customSqlMode) throws Exception {
        Method method = SyncTaskLifecycleToolAdapter.class
                .getDeclaredMethod("resolveObjectMappings", Object.class, boolean.class);
        method.setAccessible(true);
        return (List<?>) method.invoke(adapter, rawMappings, customSqlMode);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildFieldMappingConfig(
            List<?> mappings,
            Map<String, Object> sourceMetadata,
            Map<String, Object> targetMetadata,
            boolean customSqlMode) throws Exception {
        Method method = SyncTaskLifecycleToolAdapter.class.getDeclaredMethod(
                "buildFieldMappingConfig", List.class, Map.class, Map.class, boolean.class);
        method.setAccessible(true);
        return (Map<String, Object>) method.invoke(
                adapter, mappings, sourceMetadata, targetMetadata, customSqlMode);
    }

    private Map<String, Object> metadata(Map<String, Object> table) {
        return Map.of("tables", List.of(table));
    }

    private Map<String, Object> table(String schema, String name, Map<String, Object> column) {
        Map<String, Object> table = new LinkedHashMap<>();
        if (schema != null) {
            table.put("schemaName", schema);
        }
        table.put("tableName", name);
        table.put("columns", List.of(column));
        return table;
    }

    private Map<String, Object> column(String name, String type) {
        return Map.of(
                "columnName", name,
                "dataTypeName", type,
                "nullable", false,
                "primaryKey", true
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstMap(Object value) {
        return (Map<String, Object>) ((List<?>) value).getFirst();
    }
}
