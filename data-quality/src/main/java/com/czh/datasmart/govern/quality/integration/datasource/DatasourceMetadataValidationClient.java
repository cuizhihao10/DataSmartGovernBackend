/**
 * @Author : Cui
 * @Date: 2026/04/27 22:00
 * @Description DataSmart Govern Backend - DatasourceMetadataValidationClient.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.integration.datasource;

import com.czh.datasmart.govern.quality.config.DatasourceMetadataValidationProperties;
import com.czh.datasmart.govern.quality.entity.QualityRule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * datasource-management 元数据校验客户端。
 *
 * <p>这个客户端是 data-quality 与 datasource-management 之间的第一版契约桥。
 * 它只做一件事：针对关系型质量规则，调用数据源模块的元数据发现接口，
 * 判断目标表和字段是否真实存在于登记的数据源中。
 *
 * <p>为什么不直接在 data-quality 中连接源库？
 * 1. 数据源连接信息、密钥、启停状态和元数据发现能力已经属于 datasource-management；
 * 2. 质量模块直接连源库会复制连接逻辑，也会绕过数据源权限与审计边界；
 * 3. 通过模块接口调用可以保留服务边界，后续也更容易接入网关、鉴权和限流。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DatasourceMetadataValidationClient {

    private final DatasourceMetadataValidationProperties properties;

    private final RestClient.Builder restClientBuilder;

    /**
     * 校验关系型规则目标是否存在。
     *
     * <p>如果远程校验未启用，会返回 executed=false 且 valid=true，
     * 表示当前只完成结构性校验。生产环境可以开启 enabled 并根据 failOpen 决定远程失败是否阻断启用。
     */
    public RelationalMetadataValidationOutcome validateRelationalTarget(QualityRule rule) {
        if (!properties.isEnabled()) {
            RelationalMetadataValidationOutcome outcome = RelationalMetadataValidationOutcome.skipped(
                    "远程元数据校验未启用，当前仅完成关系型目标结构校验");
            outcome.getSuggestions().add("生产环境建议开启 datasmart.quality.datasource-metadata-validation.enabled，以确认表和字段真实存在。");
            return outcome;
        }

        try {
            DatasourceMetadataDiscoveryRequest request = buildRequest(rule);
            RemoteApiResponse<DatasourceMetadataDiscoveryResponse> response = restClientBuilder
                    .baseUrl(properties.getBaseUrl())
                    .build()
                    .post()
                    .uri("/datasources/{id}/metadata/discover", rule.getDataSourceId())
                    .body(request)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            return evaluateResponse(rule, response);
        } catch (Exception ex) {
            log.warn("调用 datasource-management 校验质量规则目标失败，ruleId={}, datasourceId={}, failOpen={}",
                    rule.getId(), rule.getDataSourceId(), properties.isFailOpen(), ex);
            RelationalMetadataValidationOutcome outcome = properties.isFailOpen()
                    ? RelationalMetadataValidationOutcome.passed("远程元数据服务不可用，但当前配置允许 fail-open 放行")
                    : RelationalMetadataValidationOutcome.failed("远程元数据服务不可用，且当前配置要求 fail-close");
            outcome.setFailOpen(properties.isFailOpen());
            outcome.getSuggestions().add("请检查 datasource-management 是否可访问、数据源是否启用、网络和鉴权配置是否正确。");
            return outcome;
        }
    }

    /**
     * 构造元数据发现请求。
     *
     * <p>这里把 tableName 作为 tableNamePattern 精确传入，并只返回目标表。
     * 字段级规则会开启 includeColumns，用于验证 fieldName 是否存在。
     */
    private DatasourceMetadataDiscoveryRequest buildRequest(QualityRule rule) {
        DatasourceMetadataDiscoveryRequest request = new DatasourceMetadataDiscoveryRequest();
        request.setActorId(properties.getActorId());
        request.setActorRole(properties.getActorRole());
        request.setActorTenantId(properties.getActorTenantId());
        request.setCatalog(rule.getDatabaseName());
        request.setSchemaPattern(rule.getSchemaName());
        request.setTableNamePattern(rule.getTableName());
        request.setMaxTables(properties.getMaxTables());
        request.setMaxColumnsPerTable(properties.getMaxColumnsPerTable());
        request.setIncludeColumns(true);
        request.setIncludeViews(true);
        request.setIncludePrimaryKeys(false);
        request.setIncludeIndexes(false);
        request.setIncludeSampleRows(false);
        return request;
    }

    /**
     * 解释远程响应并判断表字段是否存在。
     */
    private RelationalMetadataValidationOutcome evaluateResponse(QualityRule rule,
                                                                 RemoteApiResponse<DatasourceMetadataDiscoveryResponse> response) {
        if (response == null) {
            return RelationalMetadataValidationOutcome.failed("datasource-management 返回空响应");
        }
        if (!response.successful()) {
            return RelationalMetadataValidationOutcome.failed("datasource-management 返回失败: " + response.getMessage());
        }
        DatasourceMetadataDiscoveryResponse data = response.getData();
        if (data == null || data.getTables() == null || data.getTables().isEmpty()) {
            return RelationalMetadataValidationOutcome.failed("未在数据源元数据中发现目标表: " + rule.getTableName());
        }

        DatasourceMetadataDiscoveryResponse.TableSummary table = findTable(rule, data.getTables());
        if (table == null) {
            return RelationalMetadataValidationOutcome.failed("元数据发现返回了表清单，但未匹配到目标表: " + rule.getTableName());
        }
        if (rule.getFieldName() == null || rule.getFieldName().isBlank()) {
            RelationalMetadataValidationOutcome outcome = RelationalMetadataValidationOutcome.passed("远程元数据校验通过，目标表存在: " + rule.getTableName());
            appendWarnings(outcome, data.getWarnings());
            return outcome;
        }
        if (table.getColumns() == null || table.getColumns().isEmpty()) {
            return RelationalMetadataValidationOutcome.failed("目标表存在，但远程元数据未返回字段清单，无法确认字段: " + rule.getFieldName());
        }
        boolean columnExists = table.getColumns().stream()
                .anyMatch(column -> rule.getFieldName().equalsIgnoreCase(column.getColumnName()));
        if (!columnExists) {
            return RelationalMetadataValidationOutcome.failed("未在目标表中发现字段: " + rule.getFieldName());
        }
        RelationalMetadataValidationOutcome outcome = RelationalMetadataValidationOutcome.passed(
                "远程元数据校验通过，目标表和字段均存在: " + rule.getTableName() + "." + rule.getFieldName());
        appendWarnings(outcome, data.getWarnings());
        return outcome;
    }

    /**
     * 在远程表清单中匹配目标表。
     */
    private DatasourceMetadataDiscoveryResponse.TableSummary findTable(QualityRule rule,
                                                                       List<DatasourceMetadataDiscoveryResponse.TableSummary> tables) {
        return tables.stream()
                .filter(table -> rule.getTableName().equalsIgnoreCase(table.getTableName()))
                .filter(table -> rule.getSchemaName() == null
                        || rule.getSchemaName().isBlank()
                        || rule.getSchemaName().equalsIgnoreCase(table.getSchemaName()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 把远程元数据接口的 warnings 追加到建议中。
     */
    private void appendWarnings(RelationalMetadataValidationOutcome outcome, List<String> warnings) {
        if (warnings == null || warnings.isEmpty()) {
            return;
        }
        outcome.getSuggestions().addAll(warnings);
    }
}
