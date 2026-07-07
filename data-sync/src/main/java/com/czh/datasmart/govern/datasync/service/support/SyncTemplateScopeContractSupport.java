/**
 * @Author : Cui
 * @Date: 2026/07/05 23:22
 * @Description DataSmart Govern Backend - SyncTemplateScopeContractSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.czh.datasmart.govern.datasync.entity.SyncTemplate;
import com.czh.datasmart.govern.datasync.support.SyncMode;
import com.czh.datasmart.govern.datasync.support.SyncScopeType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 同步范围配置解析与安全校验组件。
 *
 * <p>这个组件是 data-sync 从“单表模板”走向“真实 ETL/同步产品”的关键收口点。
 * 它不执行数据同步，也不访问源端或目标端，而是只解析模板中的低敏配置字段：</p>
 *
 * <p>1. syncScopeType：用户到底选择单表、多表、全 schema、全库还是自定义 SQL；</p>
 * <p>2. objectMappingConfig：多表/全库场景下的对象清单、包含/排除规则和目标命名策略；</p>
 * <p>3. customSqlConfig：自定义 SQL 查询传输场景的只读 SQL 或 statementRef 合同。</p>
 *
 * <p>为什么必须单独拆出来：</p>
 * <p>1. validate、preview、precheck、worker bridge 都需要同一套范围判断；</p>
 * <p>2. 自定义 SQL 不能被当成普通 where 字符串处理，否则很容易把 DDL/DML 或敏感业务 SQL
 * 混进普通过滤条件；</p>
 * <p>3. 多表/全库同步在执行器未完全实现前必须 fail-closed，但控制面仍应能保存可解释配置并告诉用户缺什么。</p>
 */
@Component
public class SyncTemplateScopeContractSupport {

    private static final int MAX_OBJECT_MAPPINGS = 1000;
    private static final int MAX_INLINE_SQL_LENGTH = 8000;
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");
    private static final Pattern SQL_DANGEROUS_TOKEN = Pattern.compile(
            "\\b(insert|update|delete|merge|drop|alter|create|truncate|grant|revoke|call|exec|execute|copy|load|replace)\\b",
            Pattern.CASE_INSENSITIVE);

    private final ObjectMapper objectMapper;

    public SyncTemplateScopeContractSupport() {
        this(new ObjectMapper());
    }

    public SyncTemplateScopeContractSupport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 解析模板范围配置并返回低敏合同。
     *
     * @param template 同步模板。调用方应先完成租户/项目权限校验，再调用本方法。
     * @return 低敏范围合同，不包含 objectMappingConfig/customSqlConfig 原文。
     */
    public SyncTemplateScopeContract evaluate(SyncTemplate template) {
        List<String> issueCodes = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> recommendedActions = new ArrayList<>();
        SyncScopeType scopeType = resolveScopeType(template.getSyncScopeType(), issueCodes, recommendedActions);
        SyncMode syncMode = resolveMode(template.getSyncMode());
        evaluateScopeAndModeConsistency(scopeType, syncMode, issueCodes, recommendedActions);

        int selectedObjectCount = 0;
        if (scopeType == SyncScopeType.SINGLE_OBJECT) {
            evaluateSingleObject(template, issueCodes, recommendedActions);
        } else if (scopeType == SyncScopeType.OBJECT_LIST) {
            selectedObjectCount = evaluateObjectMappings(template, true, issueCodes, warnings, recommendedActions);
        } else if (scopeType == SyncScopeType.SCHEMA_FULL) {
            evaluateSchemaFull(template, issueCodes, recommendedActions);
            selectedObjectCount = evaluateObjectMappings(template, false, issueCodes, warnings, recommendedActions);
        } else if (scopeType == SyncScopeType.DATABASE_FULL) {
            selectedObjectCount = evaluateDatabaseFull(template, issueCodes, warnings, recommendedActions);
        } else if (scopeType == SyncScopeType.CUSTOM_SQL_QUERY) {
            evaluateCustomSql(template, issueCodes, warnings, recommendedActions);
        }

        boolean multiObjectScope = scopeType == SyncScopeType.OBJECT_LIST
                || scopeType == SyncScopeType.SCHEMA_FULL
                || scopeType == SyncScopeType.DATABASE_FULL;
        boolean customSqlScope = scopeType == SyncScopeType.CUSTOM_SQL_QUERY;
        boolean requiresApproval = multiObjectScope || customSqlScope || "OVERWRITE".equals(normalize(template.getWriteStrategy()));
        /*
         * 最小 run-once bridge 的“可执行”边界已经从最早的单表 FULL 扩展到两类受控离线能力：
         * 1. SINGLE_OBJECT：按源对象/目标对象做普通 Reader -> Writer；
         * 2. CUSTOM_SQL_QUERY：不再要求 sourceObjectName，而是把只读 SQL 结果集当作 Reader 输出。
         *
         * 注意这里没有把 OBJECT_LIST/SCHEMA_FULL/DATABASE_FULL 也标为 true，因为它们不是“单次 run-once”
         * 能完成的范围；它们需要先由 data-sync 做对象级 fan-out，再把每个对象转换成 SINGLE_OBJECT 子计划。
         */
        boolean executableByMinimalBridge = scopeType == SyncScopeType.SINGLE_OBJECT
                || scopeType == SyncScopeType.CUSTOM_SQL_QUERY;
        if (!executableByMinimalBridge) {
            issueCodes.add("SCOPE_NOT_EXECUTABLE_BY_MINIMAL_RUN_ONCE_BRIDGE");
            recommendedActions.add("当前范围已经完成控制面建模，但最小 run-once 执行桥只支持 SINGLE_OBJECT；执行前需要专用多对象/自定义 SQL runner");
        }

        return new SyncTemplateScopeContract(
                scopeType.name(),
                scopeType == SyncScopeType.SINGLE_OBJECT,
                multiObjectScope,
                customSqlScope,
                selectedObjectCount,
                hasText(template.getObjectMappingConfig()),
                hasText(template.getCustomSqlConfig()),
                requiresApproval,
                executableByMinimalBridge,
                List.copyOf(issueCodes),
                List.copyOf(warnings),
                List.copyOf(recommendedActions)
        );
    }

    private SyncScopeType resolveScopeType(String value,
                                           List<String> issueCodes,
                                           List<String> recommendedActions) {
        if (!hasText(value)) {
            return SyncScopeType.SINGLE_OBJECT;
        }
        try {
            return SyncScopeType.valueOf(normalize(value));
        } catch (IllegalArgumentException exception) {
            issueCodes.add("SYNC_SCOPE_TYPE_UNSUPPORTED");
            recommendedActions.add("将 syncScopeType 调整为 SINGLE_OBJECT、OBJECT_LIST、SCHEMA_FULL、DATABASE_FULL 或 CUSTOM_SQL_QUERY");
            return SyncScopeType.SINGLE_OBJECT;
        }
    }

    private SyncMode resolveMode(String value) {
        if (!hasText(value)) {
            return null;
        }
        try {
            return SyncMode.valueOf(normalize(value));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private void evaluateScopeAndModeConsistency(SyncScopeType scopeType,
                                                 SyncMode syncMode,
                                                 List<String> issueCodes,
                                                 List<String> recommendedActions) {
        boolean customScope = scopeType == SyncScopeType.CUSTOM_SQL_QUERY;
        boolean customMode = syncMode == SyncMode.CUSTOM_SQL_QUERY;
        if (customScope != customMode) {
            issueCodes.add("SYNC_SCOPE_MODE_MISMATCH");
            recommendedActions.add("自定义 SQL 传输必须同时使用 syncScopeType=CUSTOM_SQL_QUERY 与 syncMode=CUSTOM_SQL_QUERY，避免普通过滤条件被误当成 SQL 执行");
        }
    }

    private void evaluateSingleObject(SyncTemplate template,
                                      List<String> issueCodes,
                                      List<String> recommendedActions) {
        if (!hasText(template.getSourceObjectName())) {
            issueCodes.add("SINGLE_OBJECT_SOURCE_NOT_DECLARED");
            recommendedActions.add("源端对象名称不能为空；单对象同步必须声明 sourceObjectName，执行器才知道应该读取哪一个表、视图、topic 或逻辑资源");
            recommendedActions.add("单对象同步必须声明 sourceObjectName，执行器需要明确读取哪个表、视图、topic 或逻辑资源");
        }
        if (!hasText(template.getTargetObjectName())) {
            issueCodes.add("SINGLE_OBJECT_TARGET_NOT_DECLARED");
            recommendedActions.add("目标端对象名称不能为空；单对象同步必须声明 targetObjectName，执行器才知道应该写入哪一个目标表、topic 或逻辑资源");
            recommendedActions.add("单对象同步必须声明 targetObjectName，执行器需要明确写入哪个目标对象");
        }
    }

    private void evaluateSchemaFull(SyncTemplate template,
                                    List<String> issueCodes,
                                    List<String> recommendedActions) {
        if (!hasText(template.getSourceSchemaName()) || !hasText(template.getTargetSchemaName())) {
            issueCodes.add("SCHEMA_FULL_REQUIRES_SCHEMA_PAIR");
            recommendedActions.add("整 schema 同步必须声明 sourceSchemaName 与 targetSchemaName，并在预检阶段做元数据发现、字段兼容和容量估算");
        }
    }

    private int evaluateDatabaseFull(SyncTemplate template,
                                     List<String> issueCodes,
                                     List<String> warnings,
                                     List<String> recommendedActions) {
        if (!hasText(template.getObjectMappingConfig())) {
            warnings.add("DATABASE_FULL_DISCOVERY_POLICY_DEFAULTED");
            recommendedActions.add("整库迁移未声明 objectMappingConfig 时会使用受控默认发现策略：只返回有限数量表、不采样业务数据、需要审批/服务账号上下文；生产建议补充 include/exclude 和 maxObjects");
            return 0;
        }
        warnings.add("DATABASE_FULL_SCOPE_REQUIRES_OPERATOR_APPROVAL");
        return evaluateObjectMappings(template, false, issueCodes, warnings, recommendedActions);
    }

    private int evaluateObjectMappings(SyncTemplate template,
                                       boolean mappingsRequired,
                                       List<String> issueCodes,
                                       List<String> warnings,
                                       List<String> recommendedActions) {
        if (!hasText(template.getObjectMappingConfig())) {
            if (mappingsRequired) {
                issueCodes.add("OBJECT_MAPPING_CONFIG_REQUIRED");
                recommendedActions.add("多对象同步必须声明 objectMappingConfig.mappings，用于表达用户选择了哪些表以及源表到目标表的映射关系");
            }
            return 0;
        }
        JsonNode root = readJson(template.getObjectMappingConfig(), "OBJECT_MAPPING_JSON_INVALID", issueCodes);
        if (root == null) {
            recommendedActions.add("修正 objectMappingConfig 为合法 JSON；普通响应不会回显配置正文，请在受控配置页查看");
            return 0;
        }
        JsonNode mappings = root.get("mappings");
        int mappingCount = mappings != null && mappings.isArray() ? mappings.size() : 0;
        if (mappingsRequired && mappingCount == 0) {
            issueCodes.add("OBJECT_MAPPING_EMPTY");
            recommendedActions.add("objectMappingConfig.mappings 至少需要包含一条 sourceObject -> targetObject 映射");
        }
        if (mappingCount > MAX_OBJECT_MAPPINGS) {
            issueCodes.add("OBJECT_MAPPING_TOO_LARGE");
            recommendedActions.add("单个同步模板最多声明 " + MAX_OBJECT_MAPPINGS + " 个对象映射；更大规模迁移应拆分为批次或使用迁移计划对象");
        }
        validateMappingIdentifiers(mappings, issueCodes, recommendedActions);
        if (mappingCount == 0 && !hasText(jsonText(root, "discoveryPolicy"))) {
            warnings.add("OBJECT_MAPPING_DISCOVERY_POLICY_NOT_DECLARED");
            recommendedActions.add("schema/full database 范围建议声明 discoveryPolicy、includePatterns 或 excludeObjects，便于预检阶段明确对象发现边界");
        }
        return mappingCount;
    }

    private void validateMappingIdentifiers(JsonNode mappings,
                                            List<String> issueCodes,
                                            List<String> recommendedActions) {
        if (mappings == null || !mappings.isArray()) {
            return;
        }
        for (JsonNode mapping : mappings) {
            String sourceObject = jsonText(mapping, "sourceObject");
            String targetObject = jsonText(mapping, "targetObject");
            if (!safeIdentifier(sourceObject) || !safeIdentifier(targetObject)) {
                issueCodes.add("OBJECT_MAPPING_IDENTIFIER_UNSAFE");
                recommendedActions.add("objectMappingConfig 中的 sourceObject/targetObject 只能使用字母、数字和下划线，并以字母或下划线开头；复杂引用后续应通过 connector dialect 做安全转义");
                return;
            }
        }
    }

    private void evaluateCustomSql(SyncTemplate template,
                                   List<String> issueCodes,
                                   List<String> warnings,
                                   List<String> recommendedActions) {
        if (!hasText(template.getCustomSqlConfig())) {
            issueCodes.add("CUSTOM_SQL_CONFIG_REQUIRED");
            recommendedActions.add("自定义 SQL 查询传输必须声明 customSqlConfig，并通过只读 SQL/statementRef、参数 schema、目标对象和字段映射共同完成预检");
            return;
        }
        JsonNode root = readJson(template.getCustomSqlConfig(), "CUSTOM_SQL_JSON_INVALID", issueCodes);
        if (root == null) {
            recommendedActions.add("修正 customSqlConfig 为合法 JSON；普通响应不会回显 SQL 正文");
            return;
        }
        String sql = jsonText(root, "sql");
        String statementRef = jsonText(root, "statementRef");
        if (!hasText(sql) && !hasText(statementRef)) {
            issueCodes.add("CUSTOM_SQL_QUERY_MISSING");
            recommendedActions.add("customSqlConfig 必须至少声明 sql 或 statementRef；生产推荐使用 statementRef 托管 SQL 正文");
        }
        if (hasText(sql) && !readOnlySql(sql)) {
            issueCodes.add("CUSTOM_SQL_RAW_SQL_UNSAFE");
            recommendedActions.add("customSqlConfig.sql 只允许单条 SELECT/WITH 只读查询，禁止 DDL/DML、多语句、注释逃逸和存储过程");
        }
        if (!hasText(template.getTargetObjectName())) {
            issueCodes.add("CUSTOM_SQL_TARGET_OBJECT_REQUIRED");
            recommendedActions.add("自定义 SQL 查询结果必须声明 targetObjectName，否则目标端无法确定写入对象");
        }
        if (!hasText(template.getFieldMappingConfig())) {
            issueCodes.add("CUSTOM_SQL_FIELD_MAPPING_REQUIRED");
            recommendedActions.add("自定义 SQL 查询结果必须声明 fieldMappingConfig，用于把查询别名映射到目标字段并做类型兼容预检");
        }
        warnings.add("CUSTOM_SQL_REQUIRES_APPROVAL_AND_AUDIT");
        recommendedActions.add("自定义 SQL 属于高风险能力，执行前应完成 explain/limit 预检、行数估算、超时策略、审批和审计留痕");
    }

    private JsonNode readJson(String value, String issueCode, List<String> issueCodes) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception exception) {
            issueCodes.add(issueCode);
            return null;
        }
    }

    private boolean readOnlySql(String sql) {
        String normalized = sql.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        if (sql.length() > MAX_INLINE_SQL_LENGTH) {
            return false;
        }
        if (normalized.contains(";") || normalized.contains("--") || normalized.contains("/*") || normalized.contains("*/")) {
            return false;
        }
        if (!normalized.startsWith("select ") && !normalized.startsWith("with ")) {
            return false;
        }
        return !SQL_DANGEROUS_TOKEN.matcher(normalized).find();
    }

    private boolean safeIdentifier(String value) {
        return hasText(value) && SAFE_IDENTIFIER.matcher(value.trim()).matches();
    }

    private String jsonText(JsonNode node, String fieldName) {
        if (node == null || node.get(fieldName) == null || node.get(fieldName).isNull()) {
            return null;
        }
        String text = node.get(fieldName).asText();
        return hasText(text) ? text.trim() : null;
    }

    private String normalize(String value) {
        return hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
