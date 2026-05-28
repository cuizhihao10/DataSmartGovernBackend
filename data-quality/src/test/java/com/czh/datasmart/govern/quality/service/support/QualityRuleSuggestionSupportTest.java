/**
 * @Author : Cui
 * @Date: 2026/05/24 22:25
 * @Description DataSmart Govern Backend - QualityRuleSuggestionSupportTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.service.support;

import com.czh.datasmart.govern.quality.controller.dto.QualityRuleSuggestionRequest;
import com.czh.datasmart.govern.quality.controller.dto.QualityRuleSuggestionResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 质量规则草案建议测试。
 *
 * <p>这里测试的是确定性元数据规则引擎，不涉及数据库、不启动 Spring 容器。
 * 这样可以快速固定第一版 Agent 草案生成语义：主键生成唯一性建议，关键字段生成完整性建议，
 * 金额字段生成有效性建议，且所有结果都保持“草案”而不是直接落库。</p>
 */
class QualityRuleSuggestionSupportTest {

    private final QualityRuleSuggestionSupport support = new QualityRuleSuggestionSupport();

    @Test
    void shouldSuggestBasicRulesFromRelationalMetadata() {
        QualityRuleSuggestionResponse response = support.suggest(request());

        assertEquals(4, response.getSuggestionCount());
        assertTrue(response.getSuggestions().stream().anyMatch(item ->
                "UNIQUENESS".equals(item.getRuleType()) && "order_id".equals(item.getFieldName())));
        assertTrue(response.getSuggestions().stream().anyMatch(item ->
                "COMPLETENESS".equals(item.getRuleType()) && "customer_phone".equals(item.getFieldName())));
        assertTrue(response.getSuggestions().stream().anyMatch(item ->
                "VALIDITY".equals(item.getRuleType()) && "order_amount".equals(item.getFieldName())));
        assertEquals("deterministic-metadata-rule-engine-v1", response.getGenerationStrategy());
    }

    private QualityRuleSuggestionRequest request() {
        QualityRuleSuggestionRequest request = new QualityRuleSuggestionRequest();
        request.setTenantId(10L);
        request.setProjectId(20L);
        request.setDatasourceId(1001L);
        request.setTableName("ods_order");
        request.setBusinessGoal("检查订单主键唯一性、手机号完整性和金额异常值");
        request.setMaxSuggestions(6);
        request.setMetadata(Map.of(
                "tables", List.of(Map.of(
                        "catalog", "order_db",
                        "schemaName", "public",
                        "tableName", "ods_order",
                        "columnsTruncated", false,
                        "primaryKeys", List.of("order_id"),
                        "columns", List.of(
                                Map.of("columnName", "order_id", "dataTypeName", "BIGINT",
                                        "nullable", false, "primaryKey", true),
                                Map.of("columnName", "customer_phone", "dataTypeName", "VARCHAR",
                                        "nullable", false, "primaryKey", false),
                                Map.of("columnName", "order_amount", "dataTypeName", "DECIMAL",
                                        "nullable", true, "primaryKey", false)
                        )
                ))
        ));
        return request;
    }
}
