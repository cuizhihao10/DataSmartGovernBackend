/**
 * @Author : Cui
 * @Date: 2026/07/07 23:22
 * @Description DataSmart Govern Backend - SyncPartitionShardExecutionContractSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.entity.SyncTemplate;
import com.czh.datasmart.govern.datasync.integration.datasource.partition.DatasourcePartitionRangeProbeResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * partitionConfig 分片执行合同解析器。
 *
 * <p>本组件把模板里的 {@code partitionConfig} 从“性能建议 JSON”推进为“可执行分片合同”。它对齐 DataX 的
 * splitPk 思路，但把 DataX 没有产品化完整覆盖的部分继续补齐：分片会进入账本、失败分片可以选择性重试，
 * channel 和 TaskGroup 会成为调度合同，脏数据阈值会进入执行策略。</p>
 *
 * <p>显式 ID_RANGE 配置示例：</p>
 * <pre>
 * {
 *   "strategy": "ID_RANGE",
 *   "partitionField": "id",
 *   "channel": 4,
 *   "taskGroupSize": 8,
 *   "maxShardAttempts": 3,
 *   "ranges": [
 *     {"startInclusive": 1, "endExclusive": 100000},
 *     {"startInclusive": 100000, "endExclusive": 200000}
 *   ]
 * }
 * </pre>
 *
 * <p>自动 splitPk 配置示例：</p>
 * <pre>
 * {
 *   "strategy": "AUTO_SPLIT_PK",
 *   "splitPk": "id",
 *   "shardCount": 32,
 *   "channel": 4,
 *   "taskGroupSize": 8,
 *   "maxDirtyRecordCount": 100,
 *   "maxDirtyRecordRatio": 0.01
 * }
 * </pre>
 *
 * <p>安全原则：</p>
 * <p>1. splitPk/partitionField 必须是安全字段名，不允许表达式、函数、点号限定名或 SQL 片段；</p>
 * <p>2. 分片边界值只进入结构化 {@link SyncFilterExecutionCondition}，最终由 datasource-management 方言层
 * 通过 PreparedStatement 参数绑定；</p>
 * <p>3. 分片标识使用 {@code id-range-0000} 或 {@code splitpk-range-0000} 这类低敏编号；</p>
 * <p>4. AUTO_SPLIT_PK 的 min/max 探测由 datasource-management internal range-probe 完成，data-sync 不直接连接源库。</p>
 */
@Component
@RequiredArgsConstructor
public class SyncPartitionShardExecutionContractSupport {

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");
    private static final Pattern SAFE_SHARD_LABEL = Pattern.compile("[A-Za-z0-9_\\-:.]{1,128}");
    private static final String STRATEGY_ID_RANGE = "ID_RANGE";
    private static final String STRATEGY_AUTO_SPLIT_PK = "AUTO_SPLIT_PK";
    private static final Set<String> AUTO_SPLIT_PK_ALIASES = Set.of("AUTO_SPLIT_PK", "SPLIT_PK", "AUTO_ID_RANGE");
    private static final int DEFAULT_MAX_PARALLELISM = 2;
    private static final int MAX_PARALLELISM = 16;
    private static final int DEFAULT_TASK_GROUP_SIZE = 8;
    private static final int MAX_TASK_GROUP_SIZE = 128;
    private static final int DEFAULT_AUTO_SHARD_COUNT = 8;
    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final int MAX_ATTEMPTS = 10;
    private static final int MAX_SHARD_COUNT = 128;
    private static final long DEFAULT_MAX_DIRTY_RECORD_COUNT = 100L;
    private static final double DEFAULT_MAX_DIRTY_RECORD_RATIO = 0.01D;
    private static final int MAX_TEXT_BOUNDARY_LENGTH = 128;

    private final ObjectMapper objectMapper;

    public SyncPartitionShardExecutionContractSupport() {
        this(new ObjectMapper());
    }

    /**
     * 解析模板分片配置。
     *
     * @param template 同步模板；本方法只读取 partitionConfig，不读取数据源凭据或真实业务数据。
     * @return 分片执行合同。配置错误时返回 issueCodes，避免上层无法回写失败状态。
     */
    public SyncPartitionShardExecutionContract parse(SyncTemplate template) {
        if (template == null || !hasText(template.getPartitionConfig())) {
            return contract(false, true, false, null, null, 0,
                    DEFAULT_MAX_PARALLELISM, DEFAULT_TASK_GROUP_SIZE, DEFAULT_MAX_ATTEMPTS,
                    false, DEFAULT_MAX_DIRTY_RECORD_COUNT, DEFAULT_MAX_DIRTY_RECORD_RATIO,
                    List.of(), List.of(), List.of());
        }
        List<String> issueCodes = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        JsonNode root = readRoot(template.getPartitionConfig(), issueCodes);
        if (root == null) {
            return contract(true, false, false, null, null, 0,
                    DEFAULT_MAX_PARALLELISM, DEFAULT_TASK_GROUP_SIZE, DEFAULT_MAX_ATTEMPTS,
                    false, DEFAULT_MAX_DIRTY_RECORD_COUNT, DEFAULT_MAX_DIRTY_RECORD_RATIO,
                    List.of(), issueCodes, warnings);
        }

        String strategy = normalize(firstText(root, "strategy", "type", "mode"));
        if (AUTO_SPLIT_PK_ALIASES.contains(strategy)) {
            return parseAutoSplitPk(root, issueCodes, warnings);
        }
        if (!STRATEGY_ID_RANGE.equals(strategy)) {
            issueCodes.add("PARTITION_STRATEGY_UNSUPPORTED");
            warnings.add("PARTITION_SUPPORTED_STRATEGY_ID_RANGE_OR_AUTO_SPLIT_PK_ONLY");
            return contract(true, true, false, strategy, null, 0,
                    DEFAULT_MAX_PARALLELISM, DEFAULT_TASK_GROUP_SIZE, DEFAULT_MAX_ATTEMPTS,
                    false, dirtyCount(root), dirtyRatio(root), List.of(), issueCodes, warnings);
        }
        return parseExplicitIdRange(root, issueCodes, warnings);
    }

    private SyncPartitionShardExecutionContract parseExplicitIdRange(JsonNode root,
                                                                     List<String> issueCodes,
                                                                     List<String> warnings) {
        String partitionField = firstText(root, "partitionField", "partitionKey", "splitPk", "field", "column");
        if (!safeIdentifier(partitionField)) {
            issueCodes.add("PARTITION_FIELD_IDENTIFIER_UNSAFE");
        }
        JsonNode rangesNode = root.path("ranges");
        if (!rangesNode.isArray()) {
            issueCodes.add("PARTITION_RANGES_REQUIRED");
            return contract(true, true, false, STRATEGY_ID_RANGE, partitionField, 0,
                    maxParallelism(root), taskGroupSize(root), maxAttemptCount(root),
                    false, dirtyCount(root), dirtyRatio(root), List.of(), issueCodes, warnings);
        }
        if (rangesNode.isEmpty()) {
            issueCodes.add("PARTITION_RANGES_EMPTY");
        }
        if (rangesNode.size() > MAX_SHARD_COUNT) {
            issueCodes.add("PARTITION_SHARD_COUNT_EXCEEDED");
        }

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
        return contract(true, true, executable, STRATEGY_ID_RANGE, partitionField, shards.size(),
                maxParallelism(root), taskGroupSize(root), maxAttemptCount(root),
                false, dirtyCount(root), dirtyRatio(root), shards, issueCodes, warnings);
    }

    private SyncPartitionShardExecutionContract parseAutoSplitPk(JsonNode root,
                                                                  List<String> issueCodes,
                                                                  List<String> warnings) {
        String splitPk = firstText(root, "splitPk", "partitionField", "partitionKey", "field", "column");
        if (!safeIdentifier(splitPk)) {
            issueCodes.add("PARTITION_FIELD_IDENTIFIER_UNSAFE");
        }
        int requestedShardCount = clamp(firstPositiveInt(root, "shardCount", "splitCount", "rangeCount", "taskCount"),
                DEFAULT_AUTO_SHARD_COUNT, 1, MAX_SHARD_COUNT);
        if (issueCodes.isEmpty()) {
            warnings.add("PARTITION_AUTO_SPLIT_PK_RANGE_PROBE_REQUIRED");
        }
        return contract(true, true, false, STRATEGY_AUTO_SPLIT_PK, splitPk, requestedShardCount,
                maxParallelism(root), taskGroupSize(root), maxAttemptCount(root), true,
                dirtyCount(root), dirtyRatio(root), List.of(), issueCodes, warnings);
    }

    /**
     * 根据 datasource-management range-probe 结果生成可执行 ID_RANGE 合同。
     *
     * <p>range-probe 只提供 min/max/count，真正的 shard 数量、channel、TaskGroup、重试次数和脏数据阈值
     * 仍来自用户模板合同。这样后续审计时可以区分“用户声明的策略”和“源库探测到的事实”。</p>
     */
    public SyncPartitionShardExecutionContract buildAutoRangeContract(SyncPartitionShardExecutionContract base,
                                                                       DatasourcePartitionRangeProbeResponse probe) {
        return buildAutoRangeContract(base, probe, null);
    }

    /**
     * 根据 datasource-management 的 range-probe 结果和管理员执行策略生成可执行分片合同。
     *
     * <p>这里是“自动分片”真正生效的地方：普通用户不需要在任务向导里填写 shardCount。系统会先让
     * datasource-management 对源表执行低敏 range-probe，拿到 splitPk 的 min/max/count；然后按照管理员策略中的
     * targetRowsPerShard、minShardCount、maxShardCount 计算本次 execution 的分片数量。</p>
     *
     * <p>这样设计的好处是：同一个任务在 1 万行、100 万行、1 亿行数据下可以自动调整分片数量；管理员只需要维护
     * “每片目标行数”和“上限/下限”，而不是让普通用户猜一个固定分片数。</p>
     */
    public SyncPartitionShardExecutionContract buildAutoRangeContract(SyncPartitionShardExecutionContract base,
                                                                       DatasourcePartitionRangeProbeResponse probe,
                                                                       SyncEffectiveExecutionPolicy policy) {
        List<String> issueCodes = new ArrayList<>(base == null ? List.of() : base.issueCodes());
        List<String> warnings = new ArrayList<>(base == null ? List.of() : base.warnings());
        SyncEffectiveExecutionPolicy safePolicy = policy == null
                ? SyncEffectiveExecutionPolicy.defaults(null, null, null)
                : policy;
        if (base == null || !base.autoRangeProbeRequired()) {
            issueCodes.add("PARTITION_AUTO_RANGE_BASE_CONTRACT_REQUIRED");
            return contract(true, true, false, null, null, 0,
                    safePolicy.effectiveChannel(1),
                    safePolicy.effectiveTaskGroupSize(1),
                    effectiveAttemptCount(safePolicy),
                    false,
                    safePolicy.effectiveMaxDirtyRecordCount(),
                    safePolicy.effectiveMaxDirtyRecordRatio(),
                    List.of(), issueCodes, warnings);
        }
        int adaptiveShardCount = safePolicy.adaptiveShardCount(probe == null ? null : probe.getRowCount(),
                base.requestedShardCount());
        if (probe == null || !"RANGE_PROBED".equals(normalize(probe.getProbeStatus()))
                || !Boolean.TRUE.equals(probe.getNumericRange())
                || probe.getMinValue() == null || probe.getMaxValue() == null) {
            issueCodes.add("PARTITION_AUTO_RANGE_PROBE_UNAVAILABLE");
            return contract(true, true, false, STRATEGY_AUTO_SPLIT_PK, base.partitionField(), adaptiveShardCount,
                    safePolicy.effectiveChannel(adaptiveShardCount),
                    safePolicy.effectiveTaskGroupSize(adaptiveShardCount),
                    effectiveAttemptCount(safePolicy),
                    true,
                    safePolicy.effectiveMaxDirtyRecordCount(),
                    safePolicy.effectiveMaxDirtyRecordRatio(),
                    List.of(), issueCodes, warnings);
        }
        if (probe.getMaxValue() < probe.getMinValue()) {
            issueCodes.add("PARTITION_AUTO_RANGE_PROBE_INVALID_BOUNDARY");
            return contract(true, true, false, STRATEGY_AUTO_SPLIT_PK, base.partitionField(), adaptiveShardCount,
                    safePolicy.effectiveChannel(adaptiveShardCount),
                    safePolicy.effectiveTaskGroupSize(adaptiveShardCount),
                    effectiveAttemptCount(safePolicy),
                    true,
                    safePolicy.effectiveMaxDirtyRecordCount(),
                    safePolicy.effectiveMaxDirtyRecordRatio(),
                    List.of(), issueCodes, warnings);
        }
        if (probe.getWarnings() != null) {
            warnings.addAll(probe.getWarnings());
        }
        warnings.add("PARTITION_AUTO_SPLIT_PK_SHARDS_READY");
        warnings.add("PARTITION_AUTO_SPLIT_PK_ADAPTIVE_SHARD_COUNT_APPLIED");
        List<SyncPartitionShardExecutionItem> shards = autoRangeShards(base, probe.getMinValue(), probe.getMaxValue(),
                adaptiveShardCount);
        return contract(true, true, !shards.isEmpty(), STRATEGY_ID_RANGE, base.partitionField(), shards.size(),
                safePolicy.effectiveChannel(shards.size()),
                safePolicy.effectiveTaskGroupSize(shards.size()),
                effectiveAttemptCount(safePolicy),
                false,
                safePolicy.effectiveMaxDirtyRecordCount(),
                safePolicy.effectiveMaxDirtyRecordRatio(),
                shards, issueCodes, warnings);
    }

    /**
     * 将管理员执行策略覆盖到已经解析出的显式分片合同上。
     *
     * <p>显式 ID_RANGE 场景下，分片边界来自模板或后续失败分片重试入口，不能由策略服务重新生成；但 channel、
     * TaskGroup、最大尝试次数和脏数据阈值属于运行治理参数，应该由统一执行策略接管。这样普通用户不需要理解
     * DataX channel、TaskGroup 或 dirty threshold 的底层概念，管理员仍能在统一页面控制生产资源消耗。</p>
     */
    public SyncPartitionShardExecutionContract applyExecutionPolicy(SyncPartitionShardExecutionContract base,
                                                                     SyncEffectiveExecutionPolicy policy) {
        if (base == null || policy == null) {
            return base;
        }
        int workUnitCount = base.shards() == null || base.shards().isEmpty()
                ? Math.max(1, base.requestedShardCount())
                : base.shards().size();
        List<String> warnings = new ArrayList<>(base.warnings());
        warnings.add("EXECUTION_POLICY_APPLIED");
        return contract(
                base.declared(),
                base.parseable(),
                base.executableByPartitionFanOut(),
                base.partitionStrategy(),
                base.partitionField(),
                base.requestedShardCount(),
                policy.effectiveChannel(workUnitCount),
                policy.effectiveTaskGroupSize(workUnitCount),
                effectiveAttemptCount(policy),
                base.autoRangeProbeRequired(),
                policy.effectiveMaxDirtyRecordCount(),
                policy.effectiveMaxDirtyRecordRatio(),
                base.shards(),
                base.issueCodes(),
                warnings
        );
    }

    private List<SyncPartitionShardExecutionItem> autoRangeShards(SyncPartitionShardExecutionContract base,
                                                                  long minValue,
                                                                  long maxValue,
                                                                  int requestedShardCount) {
        BigInteger start = BigInteger.valueOf(minValue);
        BigInteger inclusiveEnd = BigInteger.valueOf(maxValue);
        BigInteger total = inclusiveEnd.subtract(start).add(BigInteger.ONE);
        int requested = Math.max(1, Math.min(requestedShardCount, MAX_SHARD_COUNT));
        int shardCount = total.compareTo(BigInteger.valueOf(requested)) < 0 ? Math.max(1, total.intValue()) : requested;
        BigInteger shardSize = total.add(BigInteger.valueOf(shardCount - 1L)).divide(BigInteger.valueOf(shardCount));
        List<SyncPartitionShardExecutionItem> shards = new ArrayList<>();
        BigInteger lower = start;
        for (int index = 0; index < shardCount && lower.compareTo(inclusiveEnd) <= 0; index++) {
            BigInteger upperExclusive = lower.add(shardSize);
            BigInteger hardEndExclusive = inclusiveEnd.add(BigInteger.ONE);
            if (index == shardCount - 1 || upperExclusive.compareTo(hardEndExclusive) > 0) {
                upperExclusive = hardEndExclusive;
            }
            List<SyncFilterExecutionCondition> conditions = List.of(
                    new SyncFilterExecutionCondition(base.partitionField(), "GTE", lower.longValue(), true),
                    new SyncFilterExecutionCondition(base.partitionField(), "LT", upperExclusive.longValue(), true)
            );
            shards.add(new SyncPartitionShardExecutionItem(
                    index,
                    "splitpk-range-" + String.format(Locale.ROOT, "%04d", index),
                    STRATEGY_ID_RANGE,
                    base.partitionField(),
                    conditions,
                    List.of("PARTITION_RANGE_VALUES_INTERNAL_ONLY", "PARTITION_RANGE_AUTO_GENERATED_FROM_SPLIT_PK")
            ));
            lower = upperExclusive;
        }
        return shards;
    }

    private int effectiveAttemptCount(SyncEffectiveExecutionPolicy policy) {
        return clamp(policy == null ? null : policy.effectiveMaxRetryCount() + 1,
                DEFAULT_MAX_ATTEMPTS, 1, MAX_ATTEMPTS);
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

    private int maxParallelism(JsonNode root) {
        return clamp(firstPositiveInt(root, "channel", "channelCount", "maxParallelism", "parallelism", "concurrency"),
                DEFAULT_MAX_PARALLELISM, 1, MAX_PARALLELISM);
    }

    private int taskGroupSize(JsonNode root) {
        return clamp(firstPositiveInt(root, "taskGroupSize", "taskGroupShardCount", "tasksPerGroup"),
                DEFAULT_TASK_GROUP_SIZE, 1, MAX_TASK_GROUP_SIZE);
    }

    private int maxAttemptCount(JsonNode root) {
        return clamp(firstPositiveInt(root, "maxShardAttempts", "maxShardRetries", "maxAttempts"),
                DEFAULT_MAX_ATTEMPTS, 1, MAX_ATTEMPTS);
    }

    private long dirtyCount(JsonNode node) {
        JsonNode dirtyNode = node == null ? null : node.path("dirtyRecordThreshold");
        Integer nested = firstPositiveInt(dirtyNode, "maxCount", "maxDirtyRecordCount", "maxDirtyNumber");
        Integer direct = firstPositiveInt(node, "maxDirtyRecordCount", "maxDirtyNumber", "maxDirtyCount");
        return nested == null ? (direct == null ? DEFAULT_MAX_DIRTY_RECORD_COUNT : direct.longValue()) : nested.longValue();
    }

    private double dirtyRatio(JsonNode node) {
        Double nested = firstPositiveDouble(node == null ? null : node.path("dirtyRecordThreshold"),
                "maxRatio", "maxPercentage", "maxDirtyRecordRatio");
        Double direct = firstPositiveDouble(node, "maxDirtyRecordRatio", "maxDirtyPercentage");
        return nested == null ? (direct == null ? DEFAULT_MAX_DIRTY_RECORD_RATIO : direct) : nested;
    }

    private Integer firstPositiveInt(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node == null ? null : node.get(field);
            if (value != null && value.canConvertToInt() && value.asInt() > 0) {
                return value.asInt();
            }
        }
        return null;
    }

    private Double firstPositiveDouble(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node == null ? null : node.get(field);
            if (value != null && value.isNumber() && value.asDouble() >= 0D) {
                return value.asDouble();
            }
        }
        return null;
    }

    private SyncPartitionShardExecutionContract contract(boolean declared,
                                                         boolean parseable,
                                                         boolean executable,
                                                         String strategy,
                                                         String partitionField,
                                                         int requestedShardCount,
                                                         int maxParallelism,
                                                         int taskGroupSize,
                                                         int maxAttemptCount,
                                                         boolean autoRangeProbeRequired,
                                                         long maxDirtyRecordCount,
                                                         double maxDirtyRecordRatio,
                                                         List<SyncPartitionShardExecutionItem> shards,
                                                         List<String> issueCodes,
                                                         List<String> warnings) {
        return new SyncPartitionShardExecutionContract(
                declared,
                parseable,
                executable,
                strategy,
                partitionField,
                requestedShardCount,
                maxParallelism,
                taskGroupSize,
                maxAttemptCount,
                autoRangeProbeRequired,
                maxDirtyRecordCount,
                maxDirtyRecordRatio,
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
