/**
 * @Author : Cui
 * @Date: 2026/07/07 23:21
 * @Description DataSmart Govern Backend - SyncPartitionShardExecutionContract.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import java.util.List;

/**
 * 单表分片执行合同。
 *
 * <p>该合同由 data-sync 控制面从模板的 {@code partitionConfig} 解析得到，职责是回答三个问题：</p>
 * <p>1. 当前配置是否能被安全解析；</p>
 * <p>2. 当前配置是否足以生成真实可执行的分片 read/write 请求；</p>
 * <p>3. 每个分片的低敏标识、结构化过滤条件、并发度和重试次数分别是什么。</p>
 *
 * <p>设计边界说明：</p>
 * <p>当前版本支持两类 DataX-style 范围切分：显式 ID_RANGE，以及通过 datasource-management range-probe
 * 探测 min/max 后生成的 AUTO_SPLIT_PK。hash bucket、时间窗口和文件 chunk 暂不混入本合同，避免一个字段同时表达
 * 多种一致性语义。</p>
 *
 * @param declared 是否声明了 partitionConfig。
 * @param parseable JSON 是否可以解析成受支持的结构。
 * @param executableByPartitionFanOut 是否可由当前分片 fan-out 调度器真实执行。
 * @param partitionStrategy 分片策略，当前主要为 ID_RANGE。
 * @param partitionField 分片字段。
 * @param requestedShardCount AUTO_SPLIT_PK 期望生成的分片数量；显式 ID_RANGE 时通常等于 ranges 数量。
 * @param maxParallelism 当前 execution 允许的最大并行分片数。
 * @param taskGroupSize 每个 TaskGroup 包含的分片数量，用于把大量 shard 分成更可观测的小组。
 * @param maxAttemptCount 每个分片允许的最大尝试次数。
 * @param autoRangeProbeRequired 当前合同是否需要先调用 datasource-management 探测 min/max。
 * @param maxDirtyRecordCount DataX-style 脏数据数量阈值，超过后应 fail-closed 或转人工。
 * @param maxDirtyRecordRatio DataX-style 脏数据比例阈值，超过后应 fail-closed 或转人工。
 * @param shards 可执行分片清单。
 * @param issueCodes 阻断或风险问题码。
 * @param warnings 非阻断提示。
 * @param payloadPolicy 载荷安全边界说明。
 */
public record SyncPartitionShardExecutionContract(
        boolean declared,
        boolean parseable,
        boolean executableByPartitionFanOut,
        String partitionStrategy,
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
        List<String> warnings,
        String payloadPolicy
) {

    public static final String PAYLOAD_POLICY =
            "INTERNAL_PARTITION_SHARD_CONTRACT_NO_RAW_SQL_NO_BOUNDARY_VALUES_IN_PUBLIC_EVENTS";

    public SyncPartitionShardExecutionContract {
        requestedShardCount = Math.max(0, requestedShardCount);
        maxParallelism = Math.max(1, maxParallelism);
        taskGroupSize = Math.max(1, taskGroupSize);
        maxAttemptCount = Math.max(1, maxAttemptCount);
        maxDirtyRecordCount = Math.max(0L, maxDirtyRecordCount);
        maxDirtyRecordRatio = Math.max(0D, maxDirtyRecordRatio);
        shards = shards == null ? List.of() : List.copyOf(shards);
        issueCodes = issueCodes == null ? List.of() : List.copyOf(issueCodes);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        payloadPolicy = PAYLOAD_POLICY;
    }

    /**
     * 分片数量。
     *
     * <p>用方法而不是让调用方直接读取 {@code shards.size()}，是为了让业务语义更直观：这里统计的是可执行
     * 工作单元数量，而不是原始 JSON 中 ranges 数组的元素数量。</p>
     */
    public int shardCount() {
        return shards.size();
    }
}
