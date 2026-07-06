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
 * <p>当前版本先闭环显式 ID_RANGE 分片，不做动态 min/max 探测，也不做 hash 分桶自动均衡。原因是动态探测
 * 需要 datasource-management 暴露元数据/统计信息执行接口，hash 分桶还涉及数据库函数方言差异。先支持显式
 * range，可以让用户在创建任务阶段或 Agent 规划阶段明确声明业务可接受的分片边界，并快速获得“成功分片跳过、
 * 失败分片可重传”的生产闭环。</p>
 *
 * @param declared 是否声明了 partitionConfig。
 * @param parseable JSON 是否可以解析成受支持的结构。
 * @param executableByPartitionFanOut 是否可由当前分片 fan-out 调度器真实执行。
 * @param partitionStrategy 分片策略，当前主要为 ID_RANGE。
 * @param partitionField 分片字段。
 * @param maxParallelism 当前 execution 允许的最大并行分片数。
 * @param maxAttemptCount 每个分片允许的最大尝试次数。
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
        int maxParallelism,
        int maxAttemptCount,
        List<SyncPartitionShardExecutionItem> shards,
        List<String> issueCodes,
        List<String> warnings,
        String payloadPolicy
) {

    public static final String PAYLOAD_POLICY =
            "INTERNAL_PARTITION_SHARD_CONTRACT_NO_RAW_SQL_NO_BOUNDARY_VALUES_IN_PUBLIC_EVENTS";

    public SyncPartitionShardExecutionContract {
        maxParallelism = Math.max(1, maxParallelism);
        maxAttemptCount = Math.max(1, maxAttemptCount);
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
