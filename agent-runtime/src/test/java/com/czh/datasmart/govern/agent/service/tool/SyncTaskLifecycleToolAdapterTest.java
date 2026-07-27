/**
 * @Author : Cui
 * @Date: 2026/07/26 18:40
 * @Description DataSmart Govern Backend - SyncTaskLifecycleToolAdapterTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.tool;

import com.czh.datasmart.govern.agent.model.AgentRunState;
import com.czh.datasmart.govern.agent.model.AgentToolExecutionState;
import com.czh.datasmart.govern.agent.model.WorkspaceIsolationLevel;
import com.czh.datasmart.govern.agent.service.audit.AgentToolExecutionAuditRecord;
import com.czh.datasmart.govern.agent.service.session.AgentRunRecord;
import com.czh.datasmart.govern.agent.service.session.AgentSessionRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void shouldRejectRealtimeDraftWithoutCdcReadinessEvidence() {
        SyncTaskLifecycleToolAdapter guardedAdapter = new SyncTaskLifecycleToolAdapter(
                null,
                null,
                new AgentToolOutputReferenceResolver(new AgentToolExecutionOutputStore()),
                new ObjectMapper());

        AgentToolExecutionOutcome outcome = guardedAdapter.execute(context(Map.of(
                "syncMode", "CDC_STREAMING"
        )));

        assertFalse(outcome.success());
        assertEquals("SYNC_TOOL_VALIDATION_FAILED", outcome.errorCode());
        assertTrue(outcome.message().contains("CDC"));
    }

    @Test
    void shouldRejectRealtimeDraftWhenCdcReadinessHasBlockers() {
        AgentToolExecutionOutputStore store = new AgentToolExecutionOutputStore();
        store.save(
                new AgentToolExecutionOutputStore.AgentToolExecutionAuditSnapshot(
                        "session-sync", "run-sync", "audit-cdc", CdcReadinessToolAdapter.TOOL_CODE),
                Map.of("ready", false, "decision", "BLOCKED")
        );
        SyncTaskLifecycleToolAdapter guardedAdapter = new SyncTaskLifecycleToolAdapter(
                null,
                null,
                new AgentToolOutputReferenceResolver(store),
                new ObjectMapper());

        AgentToolExecutionOutcome outcome = guardedAdapter.execute(context(Map.of(
                "syncMode", "CDC_STREAMING",
                "cdcReadinessRef", Map.of(
                        "fromTool", CdcReadinessToolAdapter.TOOL_CODE,
                        "fromAuditId", "audit-cdc"
                )
        )));

        assertFalse(outcome.success());
        assertEquals("SYNC_TOOL_VALIDATION_FAILED", outcome.errorCode());
        assertTrue(outcome.message().contains("阻断"));
    }

    @Test
    void shouldMergeEveryNarrowMetadataResultWithoutDuplicatingTables() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("datasourceType", "MYSQL");
        first.put("tables", List.of(
                table(null, "source_customer", column("id", "BIGINT"))
        ));
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("datasourceType", "MYSQL");
        second.put("tables", List.of(
                table(null, "source_order", column("id", "BIGINT")),
                table(null, "SOURCE_CUSTOMER", column("id", "BIGINT"))
        ));

        Map<String, Object> merged = SyncTaskLifecycleToolAdapter.mergeMetadata(List.of(first, second));

        assertEquals("MYSQL", merged.get("datasourceType"));
        assertEquals(2, merged.get("tableCount"));
        assertEquals(
                List.of("source_customer", "source_order"),
                ((List<?>) merged.get("tables")).stream()
                        .map(value -> String.valueOf(((Map<?, ?>) value).get("tableName")))
                        .toList()
        );
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

    private AgentToolExecutionContext context(Map<String, Object> arguments) {
        AgentSessionRecord session = new AgentSessionRecord(
                "session-sync", 10L, 101L, null, "1001", "PROJECT_OWNER", "USER", "101:OWNER",
                "WEB", "create realtime sync", WorkspaceIsolationLevel.PROJECT,
                "tenant:10:project:101", LocalDateTime.now());
        AgentRunRecord run = new AgentRunRecord(
                "run-sync", "session-sync", AgentRunState.PLANNING, "AGENT_REASONING",
                "validate CDC readiness", true, false, List.of(), Map.of(), LocalDateTime.now(),
                "CDC draft gate test");
        AgentToolExecutionAuditRecord audit = new AgentToolExecutionAuditRecord(
                "audit-draft", "session-sync", "run-sync", "binding-draft",
                SyncTaskLifecycleToolAdapter.DRAFT_SAVE, "DATA_SYNC", "data-sync", "/sync-tasks", null,
                10L, 101L, null, "1001", "MEDIUM", "SYNC", false,
                true, true, List.of("CREATE"), "save sync draft", arguments,
                Map.of("projectScoped", true), Map.of("missingFields", List.of()),
                AgentToolExecutionState.PLANNED, "trace-sync", "save sync draft", LocalDateTime.now());
        return new AgentToolExecutionContext(session, run, audit, Map.of(), "trace-sync");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstMap(Object value) {
        return (Map<String, Object>) ((List<?>) value).getFirst();
    }
}
