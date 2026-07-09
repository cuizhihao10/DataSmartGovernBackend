/**
 * @Author : Cui
 * @Date: 2026/07/05 16:19
 * @Description DataSmart Govern Backend - SyncObjectMappingExecutionContractSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.entity.SyncTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * objectMappingConfig 内部执行契约解析器。
 *
 * <p>本组件只负责把多对象配置解析成安全、可枚举的 fan-out 条目，不负责访问数据源、不做元数据发现、
 * 不读取字段类型，也不执行 SQL。真实表是否存在、字段是否兼容、目标表是否需要自动建表，应由预检查或
 * datasource-management 的元数据/connector runtime 继续承接。</p>
 *
 * <p>为什么这里仍然要校验对象名：多对象同步会把 sourceObject/targetObject 传给 Java Reader/Writer。
 * 如果控制面允许点号、引号、空格、函数表达式或 SQL 片段直接进入对象名，后续方言层很容易被迫处理“对象名和表达式混杂”的危险情况。
 * 当前最小闭环只放行普通安全标识符；schema 通过 sourceSchema/targetSchema 单独声明，复杂引用后续应由 connector dialect
 * 在明确白名单和转义规则下扩展，而不是在通用 JSON 解析层放宽。</p>
 */
@Component
public class SyncObjectMappingExecutionContractSupport {

    private static final int MAX_OBJECT_MAPPINGS = 1000;
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");

    /**
     * Jackson 解析器。
     *
     * <p>对象映射可能来自前端表单、Agent 规划、导入文件或旧版本迁移，使用 JsonNode 可以兼容未来扩展字段。
     * 本组件只读取执行 fan-out 必需字段，未知字段会被忽略，避免把扩展配置误当成执行指令。</p>
     */
    private final ObjectMapper objectMapper;

    public SyncObjectMappingExecutionContractSupport() {
        this(new ObjectMapper());
    }

    public SyncObjectMappingExecutionContractSupport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 解析模板中的 objectMappingConfig。
     *
     * @param template 同步模板。调用方应先完成租户、项目、审批和数据源权限校验。
     * @return 内部执行契约；当 executableBySerialFanOut=false 时，调用方不能触发真实读写。
     */
    public SyncObjectMappingExecutionContract parse(SyncTemplate template) {
        List<String> issueCodes = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (template == null) {
            issueCodes.add("OBJECT_MAPPING_TEMPLATE_MISSING");
            return contract(false, List.of(), issueCodes, warnings);
        }
        if (!hasText(template.getObjectMappingConfig())) {
            issueCodes.add("OBJECT_MAPPING_CONFIG_REQUIRED");
            return contract(false, List.of(), issueCodes, warnings);
        }

        JsonNode root = readJson(template.getObjectMappingConfig(), issueCodes);
        if (root == null) {
            return contract(false, List.of(), issueCodes, warnings);
        }
        JsonNode mappingsNode = root.path("mappings");
        if (!mappingsNode.isArray()) {
            issueCodes.add("OBJECT_MAPPING_SCHEMA_UNSUPPORTED");
            return contract(true, List.of(), issueCodes, warnings);
        }
        if (mappingsNode.isEmpty()) {
            issueCodes.add("OBJECT_MAPPING_EMPTY");
            return contract(true, List.of(), issueCodes, warnings);
        }
        if (mappingsNode.size() > MAX_OBJECT_MAPPINGS) {
            issueCodes.add("OBJECT_MAPPING_TOO_LARGE");
            return contract(true, List.of(), issueCodes, warnings);
        }

        List<SyncObjectMappingExecutionItem> mappings = new ArrayList<>();
        for (int index = 0; index < mappingsNode.size(); index++) {
            JsonNode mappingNode = mappingsNode.get(index);
            SyncObjectMappingExecutionItem item = parseItem(template, mappingNode, index, issueCodes, warnings);
            if (item != null) {
                mappings.add(item);
            }
        }
        if (mappings.isEmpty()) {
            issueCodes.add("OBJECT_MAPPING_EXECUTABLE_ITEMS_EMPTY");
        }
        if (hasText(jsonText(root, "targetNamingStrategy"))) {
            warnings.add("OBJECT_MAPPING_TARGET_NAMING_STRATEGY_DECLARED_BUT_SERIAL_FAN_OUT_USES_EXPLICIT_TARGET_OBJECT");
        }
        return contract(true, mappings, issueCodes, warnings);
    }

    /**
     * 解析单条映射。
     *
     * <p>这里支持多组字段别名，是为了兼容不同来源的配置：前端可能叫 sourceObject，Agent 可能输出 sourceTable，
     * 旧版本导入可能叫 from/to。解析器可以包容命名差异，但不会包容不安全标识符。</p>
     */
    private SyncObjectMappingExecutionItem parseItem(SyncTemplate template,
                                                     JsonNode mappingNode,
                                                     int ordinal,
                                                     List<String> issueCodes,
                                                     List<String> warnings) {
        if (mappingNode == null || !mappingNode.isObject()) {
            issueCodes.add("OBJECT_MAPPING_ITEM_SCHEMA_UNSUPPORTED");
            return null;
        }
        /*
         * 创建向导和导入导出合同已经逐步从早期的 sourceObject/targetObject 演进为
         * sourceObjectName/targetObjectName。执行解析器必须兼容这些字段名，否则会出现一个很隐蔽的生产问题：
         * 页面、预检查和任务详情都显示用户选的是 A -> B，但 worker 进入最小 run-once bridge 时仍然回退到
         * 模板顶层旧字段，最终把数据写到旧目标表。这里把两套命名都纳入解析，保证“用户保存的对象映射”优先成为执行事实。
         */
        String sourceObjectName = firstText(mappingNode,
                "sourceObjectName", "sourceObject", "sourceTableName", "sourceTable", "source", "from");
        String targetObjectName = firstText(mappingNode,
                "targetObjectName", "targetObject", "targetTableName", "targetTable", "target", "to");
        String sourceSchemaName = firstText(mappingNode,
                "sourceSchemaName", "sourceSchema", "sourceNamespace", "sourceCatalog");
        String targetSchemaName = firstText(mappingNode,
                "targetSchemaName", "targetSchema", "targetNamespace", "targetCatalog");
        /*
         * whereCondition 是当前创建向导里“每个对象一条过滤条件”的用户入口。
         * 它和历史模板级 filterConfig 不同：filterConfig 更像任务级兜底配置，而对象级 where 可以做到
         * “表 A 用 id > 100，表 B 用 biz_date >= '2026-07-01'”。这里只读取原文并保持低敏内部流转，
         * 真正的 SQL 安全校验和结构化转换放到 SyncFilterExecutionContractSupport 中完成，避免在对象映射解析层
         * 同时承担 JSON 解析、SQL 语法和执行安全三种职责。
         */
        String whereCondition = firstText(mappingNode,
                "whereCondition", "where", "filterCondition", "filterExpression");

        if (!safeIdentifier(sourceObjectName) || !safeIdentifier(targetObjectName)) {
            issueCodes.add("OBJECT_MAPPING_IDENTIFIER_UNSAFE");
            return null;
        }
        if (hasText(sourceSchemaName) && !safeIdentifier(sourceSchemaName)) {
            issueCodes.add("OBJECT_MAPPING_SOURCE_SCHEMA_UNSAFE");
            return null;
        }
        if (hasText(targetSchemaName) && !safeIdentifier(targetSchemaName)) {
            issueCodes.add("OBJECT_MAPPING_TARGET_SCHEMA_UNSAFE");
            return null;
        }

        String fieldMappingOverride = fieldMappingOverride(mappingNode, issueCodes);
        List<String> itemWarnings = new ArrayList<>();
        if (hasText(fieldMappingOverride)) {
            itemWarnings.add("OBJECT_LEVEL_FIELD_MAPPING_OVERRIDE_DECLARED");
        }
        String resolvedSourceSchema = firstText(sourceSchemaName, template.getSourceSchemaName());
        String resolvedTargetSchema = firstText(targetSchemaName, template.getTargetSchemaName());
        if (!hasText(resolvedSourceSchema)) {
            warnings.add("OBJECT_MAPPING_SOURCE_SCHEMA_FALLBACK_EMPTY");
        }
        if (!hasText(resolvedTargetSchema)) {
            warnings.add("OBJECT_MAPPING_TARGET_SCHEMA_FALLBACK_EMPTY");
        }

        return new SyncObjectMappingExecutionItem(
                ordinal,
                resolvedSourceSchema,
                sourceObjectName.trim(),
                resolvedTargetSchema,
                targetObjectName.trim(),
                fieldMappingOverride,
                hasText(fieldMappingOverride),
                trimToNull(whereCondition),
                itemWarnings
        );
    }

    /**
     * 读取对象级字段映射覆盖。
     *
     * <p>字段映射覆盖可能以字符串形式保存，也可能是 JSON 数组/对象。这里会把数组/对象重新序列化为 JSON 字符串，
     * 交给已有 {@link SyncFieldMappingExecutionContractSupport} 继续校验字段名、主键字段和映射完整性。
     * 解析失败时只返回 issueCode，不回显原始 JSON。</p>
     */
    private String fieldMappingOverride(JsonNode mappingNode, List<String> issueCodes) {
        JsonNode overrideNode = firstNode(mappingNode, "fieldMappingConfig", "fieldMappings", "fields");
        if (overrideNode == null || overrideNode.isNull()) {
            return null;
        }
        if (overrideNode.isTextual()) {
            String value = overrideNode.asText();
            return hasText(value) ? value.trim() : null;
        }
        if (overrideNode.isArray() || overrideNode.isObject()) {
            try {
                return objectMapper.writeValueAsString(overrideNode);
            } catch (Exception exception) {
                issueCodes.add("OBJECT_MAPPING_FIELD_MAPPING_OVERRIDE_UNREADABLE");
                return null;
            }
        }
        issueCodes.add("OBJECT_MAPPING_FIELD_MAPPING_OVERRIDE_UNSUPPORTED");
        return null;
    }

    private JsonNode readJson(String value, List<String> issueCodes) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception exception) {
            issueCodes.add("OBJECT_MAPPING_JSON_INVALID");
            return null;
        }
    }

    private SyncObjectMappingExecutionContract contract(boolean parseable,
                                                       List<SyncObjectMappingExecutionItem> mappings,
                                                       List<String> issueCodes,
                                                       List<String> warnings) {
        List<String> distinctIssues = distinct(issueCodes);
        return new SyncObjectMappingExecutionContract(
                parseable,
                parseable && distinctIssues.isEmpty() && !mappings.isEmpty(),
                mappings.size(),
                mappings,
                distinctIssues,
                distinct(warnings),
                SyncObjectMappingExecutionContract.PAYLOAD_POLICY
        );
    }

    private JsonNode firstNode(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode fieldNode = node.get(fieldName);
            if (fieldNode != null && !fieldNode.isNull()) {
                return fieldNode;
            }
        }
        return null;
    }

    private String firstText(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            String value = jsonText(node, fieldName);
            if (hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private String firstText(String value, String fallback) {
        return hasText(value) ? value.trim() : trimToNull(fallback);
    }

    private String jsonText(JsonNode node, String fieldName) {
        if (node == null || node.get(fieldName) == null || node.get(fieldName).isNull()) {
            return null;
        }
        String value = node.get(fieldName).asText();
        return hasText(value) ? value.trim() : null;
    }

    private boolean safeIdentifier(String value) {
        return hasText(value) && SAFE_IDENTIFIER.matcher(value.trim()).matches();
    }

    private List<String> distinct(List<String> values) {
        return new ArrayList<>(new LinkedHashSet<>(values == null ? List.of() : values));
    }

    private String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

}
