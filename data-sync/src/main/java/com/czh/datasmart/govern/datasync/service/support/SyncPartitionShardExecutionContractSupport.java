/**
 * @Author : Cui
 * @Date: 2026/07/07 23:22
 * @Description DataSmart Govern Backend - SyncPartitionShardExecutionContractSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.entity.SyncTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * partitionConfig 分片执行合同解析器。
 *
 * <p>本组件把模板里的 {@code partitionConfig} 从“性能建议 JSON”推进为“可执行分片合同”。用户前面问到：
 * 大数据量离线任务是否应该拆成多个小任务并行执行、失败小任务是否可以单独重传。答案在代码层面就落在这里：
 * 只有 partitionConfig 被解析成安全、明确、可审计的分片合同后，调度器才能把单张大表拆成多个
 * {@link SyncPartitionShardExecutionItem}。</p>
 *
 * <p>当前支持的最小生产闭环配置示例：</p>
 * <pre>
 * {
 *   "strategy": "ID_RANGE",
 *   "partitionField": "id",
 *   "maxParallelism": 4,
 *   "maxShardAttempts": 3,
 *   "ranges": [
 *     {"startInclusive": 1, "endExclusive": 100000},
 *     {"startInclusive": 100000, "endExclusive": 200000}
 *   ]
 * }
 * </pre>
 *
 * <p>重要安全原则：</p>
 * <p>1. {@code partitionField} 必须是安全字段名，不能是表达式、函数、带点号的限定名或 SQL 片段；</p>
 * <p>2. 边界值只进入 {@link SyncFilterExecutionCondition#value()}，最终通过 PreparedStatement 绑定；</p>
 * <p>3. 分片标识默认使用 {@code id-range-0000} 这类低敏编号，不把真实业务边界写进 public event；</p>
 * <p>4. 当前不做自动 min/max 探测，避免 data-sync 控制面跨边界读取源端统计数据。</p>
 */
@Component
@RequiredArgsConstructor
public class SyncPartitionShardExecutionContractSupport {

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");
    private static final Pattern SAFE_SHARD_LABEL = Pattern.compile("[A-Za-z0-9_\\-:.]{1,128}");
    private static final String STRATEGY_ID_RANGE = "ID_RANGE";
    private static final int DEFAULT_MAX_PARALLELISM = 2;
    private static final int MAX_PARALLELISM = 16;
    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final int MAX_ATTEMPTS = 10;
    private static final int MAX_SHARD_COUNT = 128;
    private static final int MAX_TEXT_BOUNDARY_LENGTH = 128;

    private final ObjectMapper objectMapper;

    public SyncPartitionShardExecutionContractSupport() {
        this(new ObjectMapper());
    }

    /**
     * 解析模板分片配置。
     *
     * @param template 同步模板；本方法只读取 partitionConfig，不读取数据源凭据或真实业务数据。
     * @return 分片执行合同。即使配置错误，也返回带 issueCodes 的合同，而不是抛异常中断上层状态回写。
     */
    public SyncPartitionShardExecutionContract parse(SyncTemplate template) {
        if (template == null || !hasText(template.getPartitionConfig())) {
            return contract(false, true, false, null, null, DEFAULT_MAX_PARALLELISM,
                    DEFAULT_MAX_ATTEMPTS, List.of(), List.of(), List.of());
        }

        List<String> issueCodes = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        JsonNode root = readRoot(template.getPartitionConfig(), issueCodes);
        if (root == null) {
            return contract(true, false, false, null, null, DEFAULT_MAX_PARALLELISM,
                    DEFAULT_MAX_ATTEMPTS, List.of(), issueCodes, warnings);
        }

        String strategy = normalize(firstText(root, "strategy", "type", "mode"));
        if (!STRATEGY_ID_RANGE.equals(strategy)) {
            issueCodes.add("PARTITION_STRATEGY_UNSUPPORTED");
            warnings.add("PARTITION_SUPPORTED_STRATEGY_ID_RANGE_ONLY");
            return contract(true, true, false, strategy, null, DEFAULT_MAX_PARALLELISM,
                    DEFAULT_MAX_ATTEMPTS, List.of(), issueCodes, warnings);
        }

        String partitionField = firstText(root, "partitionField", "partitionKey", "splitPk", "field", "column");
        if (!safeIdentifier(partitionField)) {
            issueCodes.add("PARTITION_FIELD_IDENTIFIER_UNSAFE");
        }

        JsonNode rangesNode = root.path("ranges");
        if (!rangesNode.isArray()) {
            issueCodes.add("PARTITION_RANGES_REQUIRED");
            return contract(true, true, false, strategy, partitionField, DEFAULT_MAX_PARALLELISM,
                    DEFAULT_MAX_ATTEMPTS, List.of(), issueCodes, warnings);
        }
        if (rangesNode.isEmpty()) {
            issueCodes.add("PARTITION_RANGES_EMPTY");
        }
        if (rangesNode.size() > MAX_SHARD_COUNT) {
            issueCodes.add("PARTITION_SHARD_COUNT_EXCEEDED");
        }

        int maxParallelism = clamp(firstPositiveInt(root, "maxParallelism", "parallelism", "concurrency"),
                DEFAULT_MAX_PARALLELISM, 1, MAX_PARALLELISM);
        int maxAttemptCount = clamp(firstPositiveInt(root, "maxShardAttempts", "maxShardRetries", "maxAttempts"),
                DEFAULT_MAX_ATTEMPTS, 1, MAX_ATTEMPTS);

        List<SyncPartitionShardExecutionItem> shards = new ArrayList<>();
        for (int index = 0; index < Math.min(rangesNode.size(), MAX_SHARD_COUNT); index++) {
            SyncPartitionShardExecutionItem item = parseRange(index, partitionField, rangesNode.get(index), issueCodes);
            if (item != null) {
                shards.add(item);
            }
        }

        boolean executable = issueCodes.isEmpty() && !shards.isEmpty();
        if (executable) {
            warnings.add("PARTITION_ID_RANGE_SHARDS_READY");
        }
        return contract(true, true, executable, strategy, partitionField, maxParallelism,
                maxAttemptCount, shards, issueCodes, warnings);
    }

    private SyncPartitionShardExecutionItem parseRange(int ordinal,
                                                       String partitionField,
                                                       JsonNode rangeNode,
                                                       List<String> issueCodes) {
        if (rangeNode == null || !rangeNode.isObject()) {
            issueCodes.add("PARTITION_RANGE_SCHEMA_UNSUPPORTED");
            return null;
        }
        Object lower = firstBoundaryValue(rangeNode,
                "startInclusive", "lowerInclusive", "start", "from", "gte", "min");
        Object upper = firstBoundaryValue(rangeNode,
                "endExclusive", "upperExclusive", "end", "to", "lt", "max");
        if (lower == null && upper == null) {
            issueCodes.add("PARTITION_RANGE_BOUNDARY_REQUIRED");
            return null;
        }

        List<SyncFilterExecutionCondition> conditions = new ArrayList<>();
        if (lower != null) {
            conditions.add(new SyncFilterExecutionCondition(partitionField, lowerInclusiveOperator(rangeNode), lower, true));
        }
        if (upper != null) {
            conditions.add(new SyncFilterExecutionCondition(partitionField, upperInclusiveOperator(rangeNode), upper, true));
        }
        return new SyncPartitionShardExecutionItem(
                ordinal,
                resolveShardLabel(rangeNode, ordinal),
                STRATEGY_ID_RANGE,
                partitionField,
                conditions,
                List.of("PARTITION_RANGE_VALUES_INTERNAL_ONLY")
        );
    }

    private String lowerInclusiveOperator(JsonNode rangeNode) {
        if (rangeNode.has("startExclusive") || rangeNode.has("lowerExclusive") || rangeNode.has("gt")) {
            return "GT";
        }
        return "GTE";
    }

    private String upperInclusiveOperator(JsonNode rangeNode) {
        if (rangeNode.has("endInclusive") || rangeNode.has("upperInclusive") || rangeNode.has("lte")) {
            return "LTE";
        }
        return "LT";
    }

    private String resolveShardLabel(JsonNode rangeNode, int ordinal) {
        String explicit = firstText(rangeNode, "shard", "shardId", "label", "name");
        if (hasText(explicit) && SAFE_SHARD_LABEL.matcher(explicit.trim()).matches()) {
            return explicit.trim();
        }
        return "id-range-" + String.format(Locale.ROOT, "%04d", ordinal);
    }

    private JsonNode readRoot(String partitionConfig, List<String> issueCodes) {
        try {
            JsonNode root = objectMapper.readTree(partitionConfig);
            if (root == null || !root.isObject()) {
                issueCodes.add("PARTITION_CONFIG_SCHEMA_UNSUPPORTED");
                return null;
            }
            return root;
        } catch (Exception exception) {
            issueCodes.add("PARTITION_CONFIG_PARSE_FAILED");
            return null;
        }
    }

    private Object firstBoundaryValue(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode valueNode = node.get(field);
            Object value = readBoundaryValue(valueNode);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private Object readBoundaryValue(JsonNode valueNode) {
        if (valueNode == null || valueNode.isMissingNode() || valueNode.isNull()) {
            return null;
        }
        if (valueNode.isIntegralNumber()) {
            return valueNode.longValue();
        }
        if (valueNode.isFloatingPointNumber() || valueNode.isBigDecimal()) {
            return new BigDecimal(valueNode.asText());
        }
        if (valueNode.isBoolean()) {
            return valueNode.booleanValue();
        }
        if (valueNode.isTextual()) {
            String value = valueNode.asText();
            if (!hasText(value)) {
                return null;
            }
            return value.length() > MAX_TEXT_BOUNDARY_LENGTH ? value.substring(0, MAX_TEXT_BOUNDARY_LENGTH) : value;
        }
        return null;
    }

    private Integer firstPositiveInt(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && value.canConvertToInt() && value.asInt() > 0) {
                return value.asInt();
            }
        }
        return null;
    }

    private SyncPartitionShardExecutionContract contract(boolean declared,
                                                         boolean parseable,
                                                         boolean executable,
                                                         String strategy,
                                                         String partitionField,
                                                         int maxParallelism,
                                                         int maxAttemptCount,
                                                         List<SyncPartitionShardExecutionItem> shards,
                                                         List<String> issueCodes,
                                                         List<String> warnings) {
        return new SyncPartitionShardExecutionContract(
                declared,
                parseable,
                executable,
                strategy,
                partitionField,
                maxParallelism,
                maxAttemptCount,
                shards,
                distinct(issueCodes),
                distinct(warnings),
                SyncPartitionShardExecutionContract.PAYLOAD_POLICY
        );
    }

    private int clamp(Integer value, int defaultValue, int minValue, int maxValue) {
        int safeValue = value == null ? defaultValue : value;
        return Math.max(minValue, Math.min(safeValue, maxValue));
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node == null ? null : node.get(field);
            if (value != null && value.isTextual() && hasText(value.asText())) {
                return value.asText().trim();
            }
        }
        return null;
    }

    private boolean safeIdentifier(String value) {
        return hasText(value) && SAFE_IDENTIFIER.matcher(value.trim()).matches();
    }

    private String normalize(String value) {
        return hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private List<String> distinct(List<String> values) {
        return new ArrayList<>(new LinkedHashSet<>(values == null ? List.of() : values));
    }
}
