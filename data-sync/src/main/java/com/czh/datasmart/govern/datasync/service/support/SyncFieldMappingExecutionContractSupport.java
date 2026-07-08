/**
 * @Author : Cui
 * @Date: 2026/06/29 23:43
 * @Description DataSmart Govern Backend - SyncFieldMappingExecutionContractSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 字段映射执行契约解析器。
 *
 * <p>本组件只做 data-sync 执行闭环所需的最小字段映射解析，不做真实元数据校验、不读取源库/目标库、
 * 不访问 datasource-management 的连接配置，也不执行 SQL。它的职责边界非常明确：把模板中的
 * {@code fieldMappingConfig} 原始 JSON 转换为受控 batch runner bridge 能消费的字段列表和低敏问题码。</p>
 *
 * <p>为什么 data-sync 需要自己的轻量解析器，而不是直接复用 datasource-management 的字段映射校验：</p>
 * <p>1. datasource-management 的校验会结合表元数据、字段类型、长度、必填列和自动映射建议，属于数据源/元数据域；</p>
 * <p>2. data-sync 当前只需要判断“最小执行器能否构建 readColumns/writeColumns/primaryKeyColumns”，不应为了这个动作依赖元数据实体；</p>
 * <p>3. 保持本地轻量契约可以让后续 connector runtime 作为独立服务演进，避免一个模块内部类变成跨模块事实标准。</p>
 *
 * <p>安全策略：</p>
 * <p>解析失败时只返回 issueCode，不返回 Jackson 异常原文和原始 JSON；字段名会进入内部契约对象，
 * 但该对象不作为 controller DTO 暴露，也不应被日志打印。</p>
 */
@Component
@RequiredArgsConstructor
public class SyncFieldMappingExecutionContractSupport {

    /**
     * 执行器第一阶段只接受保守安全标识符。
     *
     * <p>这里暂不支持带点号、反引号、双引号、空格、函数表达式或 JSONPath 的字段名，是为了避免最小 runner
     * 在还没有方言级转义策略和字段白名单校验时误把表达式当字段执行。后续如果要支持复杂字段路径，
     * 应在 connector dialect 中新增明确的 escape/validate 机制，而不是放宽这个通用规则。</p>
     */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");

    /**
     * Jackson JSON 解析器。
     *
     * <p>使用 ObjectMapper 是为了兼容未来字段映射 JSON 增加 transform、defaultValue、masking、required 等扩展字段；
     * 当前只读取 source/target 两类字段，不会把其他扩展配置写入执行契约。</p>
     */
    private final ObjectMapper objectMapper;

    /**
     * 解析字段映射 JSON，生成内部执行契约。
     *
     * @param fieldMappingConfig 模板中保存的字段映射 JSON。允许为空，但为空会返回 FIELD_MAPPING_NOT_DECLARED。
     * @param primaryKeyField 模板声明的主键/冲突字段。允许为空；冲突写入是否必须声明主键由 workerPlan/bridge 再判断。
     * @return 内部字段映射执行契约。该对象可能包含字段名，调用方不能直接返回给外部 API。
     */
    public SyncFieldMappingExecutionContract parse(String fieldMappingConfig, String primaryKeyField) {
        List<String> issueCodes = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (!hasText(fieldMappingConfig)) {
            issueCodes.add("FIELD_MAPPING_NOT_DECLARED");
            return contract(false, false, 0, List.of(), List.of(), List.of(), false, issueCodes, warnings);
        }

        JsonNode mappingsNode = readMappingsNode(fieldMappingConfig, issueCodes);
        if (mappingsNode == null) {
            return contract(false, false, 0, List.of(), List.of(), List.of(), false, issueCodes, warnings);
        }
        if (!mappingsNode.isArray()) {
            issueCodes.add("FIELD_MAPPING_SCHEMA_UNSUPPORTED");
            return contract(false, false, 0, List.of(), List.of(), List.of(), false, issueCodes, warnings);
        }
        if (mappingsNode.isEmpty()) {
            issueCodes.add("FIELD_MAPPING_EMPTY");
            return contract(true, false, 0, List.of(), List.of(), List.of(), false, issueCodes, warnings);
        }

        Set<String> selectedColumns = new LinkedHashSet<>();
        Set<String> writeColumns = new LinkedHashSet<>();
        boolean requiresFieldRenameTransform = false;
        int mappingCount = 0;

        for (JsonNode mappingNode : mappingsNode) {
            /*
             * 新版创建向导会把“源端独有字段”和“目标端独有字段”也写入 fieldMappingConfig，
             * 目的是让用户完整看到两端表结构差异。此类行通常 syncEnabled=false，表示仅用于展示，
             * 不参与真实 reader -> writer 搬运；旧执行合同必须跳过它们，避免把空 sourceField/targetField
             * 误判成字段映射错误。
             */
            if (!mappingSyncEnabled(mappingNode)) {
                continue;
            }
            FieldMappingPair pair = readPair(mappingNode);
            if (!hasText(pair.sourceField()) || !hasText(pair.targetField())) {
                issueCodes.add("FIELD_MAPPING_PAIR_INCOMPLETE");
                continue;
            }
            if (!safeIdentifier(pair.sourceField()) || !safeIdentifier(pair.targetField())) {
                issueCodes.add("FIELD_MAPPING_IDENTIFIER_UNSAFE");
                continue;
            }
            mappingCount++;
            selectedColumns.add(pair.sourceField());
            writeColumns.add(pair.targetField());
            if (!normalize(pair.sourceField()).equals(normalize(pair.targetField()))) {
                requiresFieldRenameTransform = true;
            }
        }

        if (selectedColumns.isEmpty()) {
            issueCodes.add("SELECTED_COLUMNS_NOT_RESOLVED");
        }
        if (writeColumns.isEmpty()) {
            issueCodes.add("WRITE_COLUMNS_NOT_RESOLVED");
        }

        List<String> primaryKeyColumns = resolvePrimaryKeyColumns(primaryKeyField, writeColumns, issueCodes);
        if (requiresFieldRenameTransform) {
            warnings.add("FIELD_RENAME_TRANSFORM_REQUIRED");
        }

        return contract(true, mappingCount > 0, mappingCount,
                selectedColumns.stream().toList(),
                writeColumns.stream().toList(),
                primaryKeyColumns,
                requiresFieldRenameTransform,
                distinct(issueCodes),
                distinct(warnings));
    }

    /**
     * 读取映射数组节点。
     *
     * <p>当前兼容两种配置形态：</p>
     * <p>1. 直接数组：{@code [{"sourceField":"id","targetField":"id"}]}；</p>
     * <p>2. 包装对象：{@code {"mappings":[...]}}。</p>
     *
     * <p>解析异常只转换为 FIELD_MAPPING_PARSE_FAILED，避免把异常 message 中可能带出的原始 JSON 片段传给上层。</p>
     */
    private JsonNode readMappingsNode(String fieldMappingConfig, List<String> issueCodes) {
        try {
            JsonNode rootNode = objectMapper.readTree(fieldMappingConfig);
            if (rootNode.isArray()) {
                return rootNode;
            }
            JsonNode topLevelMappings = firstArray(rootNode, "mappings", "fieldMappings");
            if (topLevelMappings != null) {
                return topLevelMappings;
            }
            /*
             * 创建向导 v2 的字段映射是“对象级”的：每一张源表到目标表的映射都有自己的 mappings。
             * 旧的最小执行合同只需要知道“是否存在可执行字段映射”，因此这里构造一个扁平化视图。
             * 真正的逐对象校验，例如目标表是否存在、源字段/目标字段是否存在、类型是否兼容，
             * 由 SyncTemplateMetadataAwarePrecheckSupport 读取两端真实元数据后完成。
             */
            JsonNode objectMappings = rootNode.path("objectMappings");
            if (objectMappings.isArray()) {
                ArrayNode flattenedMappings = objectMapper.createArrayNode();
                for (JsonNode objectMapping : objectMappings) {
                    JsonNode objectLevelMappings = firstArray(objectMapping, "mappings", "fieldMappings");
                    if (objectLevelMappings == null) {
                        continue;
                    }
                    objectLevelMappings.forEach(flattenedMappings::add);
                }
                return flattenedMappings;
            }
            return rootNode.path("mappings");
        } catch (Exception exception) {
            issueCodes.add("FIELD_MAPPING_PARSE_FAILED");
            return null;
        }
    }

    /**
     * 从单条映射配置中读取源字段和目标字段。
     *
     * <p>这里支持多组别名，是因为真实产品里字段映射可能来自前端表单、Agent 生成、旧版本配置迁移或外部导入。
     * 解析器尽量包容命名差异，但仍要求最终字段名满足安全标识符规则。</p>
     */
    private FieldMappingPair readPair(JsonNode mappingNode) {
        if (mappingNode == null || !mappingNode.isObject()) {
            return new FieldMappingPair(null, null);
        }
        return new FieldMappingPair(
                firstText(mappingNode, "sourceField", "source", "from", "sourceColumn"),
                firstText(mappingNode, "targetField", "target", "to", "targetColumn")
        );
    }

    /**
     * 判断字段映射行是否真的参与同步。
     *
     * <p>历史字段映射没有 {@code syncEnabled} 字段，默认视为参与同步，保证旧模板不需要迁移；
     * 新版创建向导会把源端独有、目标端独有字段以 {@code syncEnabled=false} 保存，用于字段映射页展示两端差异，
     * 这些行不应进入最小执行合同。</p>
     */
    private boolean mappingSyncEnabled(JsonNode mappingNode) {
        if (mappingNode == null || !mappingNode.isObject()) {
            return true;
        }
        JsonNode syncEnabledNode = mappingNode.get("syncEnabled");
        if (syncEnabledNode == null || syncEnabledNode.isNull()) {
            return true;
        }
        return syncEnabledNode.asBoolean(true);
    }

    /**
     * 根据模板主键字段生成目标端冲突键列表。
     *
     * <p>如果主键字段不在 writeColumns 中，最小写入器无法在目标端生成稳定冲突条件，因此返回问题码。
     * 注意这里不主动要求必须有主键，因为 APPEND/OVERWRITE 等策略不一定需要冲突键；
     * 是否必须声明由写入策略层判断。</p>
     */
    private List<String> resolvePrimaryKeyColumns(String primaryKeyField,
                                                  Set<String> writeColumns,
                                                  List<String> issueCodes) {
        if (!hasText(primaryKeyField)) {
            return List.of();
        }
        String trimmedPrimaryKey = primaryKeyField.trim();
        if (!safeIdentifier(trimmedPrimaryKey)) {
            issueCodes.add("PRIMARY_KEY_IDENTIFIER_UNSAFE");
            return List.of();
        }
        boolean present = writeColumns.stream()
                .anyMatch(column -> normalize(column).equals(normalize(trimmedPrimaryKey)));
        if (!present) {
            issueCodes.add("PRIMARY_KEY_NOT_PRESENT_IN_FIELD_MAPPING");
            return List.of();
        }
        return List.of(trimmedPrimaryKey);
    }

    /**
     * 按多个候选字段名读取第一个有文本的值。
     */
    private String firstText(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode fieldNode = node.get(fieldName);
            if (fieldNode != null && !fieldNode.isNull() && hasText(fieldNode.asText())) {
                return fieldNode.asText().trim();
            }
        }
        return null;
    }

    /**
     * 从若干候选字段名中读取第一个数组节点。
     *
     * <p>创建向导、导入文件和历史模板可能分别使用 {@code mappings} 或 {@code fieldMappings}。
     * 在这里集中做兼容，可以避免同一种字段映射语义散落到多个分支里。</p>
     */
    private JsonNode firstArray(JsonNode node, String... fieldNames) {
        if (node == null || !node.isObject()) {
            return null;
        }
        for (String fieldName : fieldNames) {
            JsonNode candidate = node.get(fieldName);
            if (candidate != null && candidate.isArray()) {
                return candidate;
            }
        }
        return null;
    }

    private SyncFieldMappingExecutionContract contract(boolean parseable,
                                                       boolean hasMappings,
                                                       int mappingCount,
                                                       List<String> selectedColumns,
                                                       List<String> writeColumns,
                                                       List<String> primaryKeyColumns,
                                                       boolean requiresFieldRenameTransform,
                                                       List<String> issueCodes,
                                                       List<String> warnings) {
        return new SyncFieldMappingExecutionContract(
                parseable,
                hasMappings,
                mappingCount,
                selectedColumns,
                writeColumns,
                primaryKeyColumns,
                requiresFieldRenameTransform,
                issueCodes,
                warnings);
    }

    private List<String> distinct(List<String> values) {
        return new ArrayList<>(new LinkedHashSet<>(values));
    }

    private boolean safeIdentifier(String value) {
        return hasText(value) && SAFE_IDENTIFIER.matcher(value.trim()).matches();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private record FieldMappingPair(String sourceField, String targetField) {
    }
}
