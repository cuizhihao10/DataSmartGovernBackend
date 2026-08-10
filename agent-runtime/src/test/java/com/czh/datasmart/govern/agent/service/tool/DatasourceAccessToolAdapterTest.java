/**
 * @Author : Cui
 * @Date: 2026-07-11 04:50
 * @Description DataSmart Govern Backend - DatasourceAccessToolAdapterTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.tool;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatasourceAccessToolAdapterTest {

    @Test
    void currentDatasourceContractUsesSuccessTestStatus() {
        assertTrue(DatasourceAccessToolAdapter.isSuccessfulConnectionTest(Map.of(
                "testStatus", "SUCCESS",
                "message", "Connection succeeded and metadata is discoverable."
        )));
        assertFalse(DatasourceAccessToolAdapter.isSuccessfulConnectionTest(Map.of(
                "testStatus", "FAILED"
        )));
    }

    @Test
    void legacySuccessBooleanRemainsCompatibleDuringRollingUpgrade() {
        assertTrue(DatasourceAccessToolAdapter.isSuccessfulConnectionTest(Map.of("success", true)));
        assertFalse(DatasourceAccessToolAdapter.isSuccessfulConnectionTest(Map.of("success", false)));
    }

    @Test
    void catalogSearchOnlyResolvesOneExactAuthorizedName() {
        Map<String, Object> result = DatasourceAccessToolAdapter.buildCatalogSearchOutput(
                "mysql-orders-source",
                "MYSQL",
                "SOURCE",
                List.of(
                        Map.of(
                                "id", 27,
                                "name", "mysql-orders-source",
                                "type", "MYSQL",
                                "usagePurpose", "SOURCE",
                                "status", "ENABLED"
                        ),
                        Map.of(
                                "id", 29,
                                "name", "mysql-orders-source-backup",
                                "type", "MYSQL",
                                "usagePurpose", "SOURCE",
                                "status", "ENABLED"
                        )
                )
        );

        assertEquals("EXACT", result.get("matchStatus"));
        assertEquals(27, result.get("resolvedDatasourceId"));
        assertEquals(false, result.get("requiresUserChoice"));
    }

    @Test
    void catalogSearchDoesNotAutoSelectOneFuzzyCandidate() {
        Map<String, Object> result = DatasourceAccessToolAdapter.buildCatalogSearchOutput(
                "orders",
                "POSTGRESQL",
                "TARGET",
                List.of(Map.of(
                        "id", 28,
                        "name", "postgres-orders-target",
                        "type", "POSTGRESQL",
                        "usagePurpose", "TARGET",
                        "status", "ENABLED"
                ))
        );

        assertEquals("AMBIGUOUS", result.get("matchStatus"));
        assertEquals(true, result.get("requiresUserChoice"));
        assertFalse(result.containsKey("resolvedDatasourceId"));
    }

    @Test
    void connectorTypeOnlyReturnsCandidatesWithoutAutoSelectingOneInstance() {
        Map<String, Object> result = DatasourceAccessToolAdapter.buildCatalogSearchOutput(
                null,
                "MYSQL",
                "SOURCE",
                List.of(Map.of(
                        "id", 27,
                        "name", "production-orders-source",
                        "type", "MYSQL",
                        "usagePurpose", "SOURCE",
                        "status", "ENABLED"
                ))
        );

        assertEquals("TYPE_CANDIDATES", result.get("matchStatus"));
        assertEquals("CONNECTOR_TYPE_ONLY", result.get("matchBasis"));
        assertEquals("MYSQL", result.get("requestedDatasourceType"));
        assertEquals(true, result.get("requiresUserChoice"));
        assertFalse(result.containsKey("resolvedDatasourceId"));
    }

    @Test
    void trustedUpstreamDatasourceReferenceOverridesModelSuppliedId() {
        assertEquals(
                28L,
                DatasourceAccessToolAdapter.selectTrustedDatasourceId(27L, 28L),
                "目标端元数据读取必须使用目标端连接测试返回的 ID，不能相信模型重复携带的源端 ID");
    }

    @Test
    void structuredWizardDatasourceIdRemainsCompatibleWithoutReference() {
        assertEquals(
                27L,
                DatasourceAccessToolAdapter.selectTrustedDatasourceId(27L, null),
                "结构化向导直接创建的同 Run DAG 在尚未带跨 Run 引用时仍应兼容显式数据源 ID");
    }

    @Test
    void exactTableNamesAreValidatedAndDeduplicated() {
        assertEquals(
                List.of("fs_test_customer_source", "fs_test_customer_target"),
                DatasourceAccessToolAdapter.normalizeExactTableNames(List.of(
                        "fs_test_customer_source",
                        "FS_TEST_CUSTOMER_SOURCE",
                        "fs_test_customer_target")));
    }

    @Test
    void exactMetadataResponsesAreMergedIntoOneTrustedSummaryInput() {
        Map<String, Object> firstData = new LinkedHashMap<>();
        firstData.put("datasourceName", "source");
        firstData.put("tables", List.of(Map.of(
                "schemaName", "",
                "tableName", "fs_test_customer_source",
                "columns", List.of())));
        firstData.put("warnings", List.of());
        Map<String, Object> secondData = new LinkedHashMap<>();
        secondData.put("datasourceName", "source");
        secondData.put("tables", List.of(Map.of(
                "schemaName", "",
                "tableName", "fs_test_customer_target",
                "columns", List.of())));
        secondData.put("warnings", List.of());

        Map<String, Object> merged = DatasourceAccessToolAdapter.mergeExactMetadataResponses(List.of(
                Map.of("code", 0, "message", "ok", "data", firstData),
                Map.of("code", 0, "message", "ok", "data", secondData)));
        Map<?, ?> data = (Map<?, ?>) merged.get("data");

        assertEquals(2, data.get("tableCount"));
        assertEquals(2, ((List<?>) data.get("tables")).size());
        assertEquals(100, data.get("appliedMaxTables"));
    }
}
