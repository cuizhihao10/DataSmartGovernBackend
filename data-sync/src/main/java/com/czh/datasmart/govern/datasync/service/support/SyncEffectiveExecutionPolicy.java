/**
 * @Author : Cui
 * @Date: 2026/07/09 22:38
 * @Description DataSmart Govern Backend - SyncEffectiveExecutionPolicy.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.entity.SyncExecutionPolicy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 运行时已经合并完成的执行策略。
 *
 * <p>数据库中的策略是“局部覆盖”：某条项目策略可能只配置 channel，不配置批大小。执行器不能直接拿单条策略运行，
 * 必须先从系统默认开始逐层合并，得到一个所有关键字段都有安全默认值的 effective policy。</p>
 *
 * <p>本类同时承载自动分片算法：当 AUTO_SPLIT_PK 已经探测到 rowCount 后，系统按
 * ceil(rowCount / targetRowsPerShard) 计算候选分片数，再按 minShardCount/maxShardCount 裁剪。
 * 这意味着普通用户不需要也不应该手工填写 shardCount，大表会随数据量自动增加分片，小表则保持少量分片。</p>
 */
public record SyncEffectiveExecutionPolicy(
        Long tenantId,
        Long projectId,
        Long syncTaskId,
        List<String> matchedPolicyCodes,
        Long targetRowsPerShard,
        Integer minShardCount,
        Integer maxShardCount,
        Integer maxChannel,
        Integer taskGroupSize,
        Integer readBatchSize,
        Integer writeBatchSize,
        Integer commitIntervalRecords,
        Integer timeoutSeconds,
        Integer maxRetryCount,
        Long maxDirtyRecordCount,
        BigDecimal maxDirtyRecordRatio,
        String resolutionOrder
) {

    /** 快照载荷的安全说明。 */
    public static final String SNAPSHOT_PAYLOAD_POLICY = "LOW_SENSITIVE_EXECUTION_POLICY_SNAPSHOT_NO_SQL_NO_CREDENTIALS";

    /**
     * 构造平台内置默认策略。
     *
     * <p>这些默认值与现有 run-once 配置保持相对兼容，同时新增自动分片目标行数。后续管理员可以通过
     * SYSTEM 作用域策略覆盖它们，而不是修改代码或 application.yml。</p>
     */
    public static SyncEffectiveExecutionPolicy defaults(Long tenantId, Long projectId, Long syncTaskId) {
        return new SyncEffectiveExecutionPolicy(
                tenantId,
                projectId,
                syncTaskId,
                List.of("BUILTIN_DEFAULT"),
                200000L,
                1,
                64,
                4,
                8,
                512,
                256,
                256,
                600,
                0,
                100L,
                BigDecimal.valueOf(0.01D),
                "TASK > PROJECT > DATASOURCE/CONNECTOR > SYSTEM"
        );
    }

    /**
     * 合并一条数据库策略。
     *
     * <p>字段为空表示“不覆盖”。例如连接器策略只配置 readBatchSize，那么 writeBatchSize 会继续沿用系统默认
     * 或目标端策略。这样管理员可以用很小的策略对象表达真实治理意图。</p>
     */
    public SyncEffectiveExecutionPolicy merge(SyncExecutionPolicy policy) {
        if (policy == null) {
            return this;
        }
        List<String> mergedCodes = new ArrayList<>(matchedPolicyCodes);
        mergedCodes.add(policy.getScopeType() + ":" + policy.getPolicyCode());
        return new SyncEffectiveExecutionPolicy(
                firstNonNull(policy.getTenantId(), tenantId),
                firstNonNull(policy.getProjectId(), projectId),
                firstNonNull(policy.getSyncTaskId(), syncTaskId),
                List.copyOf(mergedCodes),
                firstNonNull(policy.getTargetRowsPerShard(), targetRowsPerShard),
                firstNonNull(policy.getMinShardCount(), minShardCount),
                firstNonNull(policy.getMaxShardCount(), maxShardCount),
                firstNonNull(policy.getMaxChannel(), maxChannel),
                firstNonNull(policy.getTaskGroupSize(), taskGroupSize),
                firstNonNull(policy.getReadBatchSize(), readBatchSize),
                firstNonNull(policy.getWriteBatchSize(), writeBatchSize),
                firstNonNull(policy.getCommitIntervalRecords(), commitIntervalRecords),
                firstNonNull(policy.getTimeoutSeconds(), timeoutSeconds),
                firstNonNull(policy.getMaxRetryCount(), maxRetryCount),
                firstNonNull(policy.getMaxDirtyRecordCount(), maxDirtyRecordCount),
                firstNonNull(policy.getMaxDirtyRecordRatio(), maxDirtyRecordRatio),
                resolutionOrder
        );
    }

    /**
     * 根据探测到的行数自动计算 splitPk 分片数。
     *
     * @param rowCount range-probe 返回的源表行数；为空或小于等于 0 时无法按数据量推导，会回退到 fallbackShardCount。
     * @param fallbackShardCount 兼容旧模板或旧测试的兜底分片数。
     * @return 已按 min/max 裁剪后的安全分片数。
     */
    public int adaptiveShardCount(Long rowCount, int fallbackShardCount) {
        long targetRows = Math.max(1L, valueOrDefault(targetRowsPerShard, 200000L));
        int min = Math.max(1, valueOrDefault(minShardCount, 1));
        int max = Math.max(min, valueOrDefault(maxShardCount, 64));
        if (rowCount == null || rowCount <= 0) {
            return clamp(Math.max(1, fallbackShardCount), min, max);
        }
        long estimated = (rowCount + targetRows - 1L) / targetRows;
        if (estimated > Integer.MAX_VALUE) {
            estimated = Integer.MAX_VALUE;
        }
        return clamp((int) Math.max(1L, estimated), min, max);
    }

    /**
     * 根据实际工作单元数计算 channel。
     *
     * <p>channel 再大也不能超过工作单元数量，否则只会创建空闲线程；同时必须至少为 1，保证小任务也能运行。</p>
     */
    public int effectiveChannel(int workUnitCount) {
        int configured = Math.max(1, valueOrDefault(maxChannel, 1));
        return Math.max(1, Math.min(configured, Math.max(1, workUnitCount)));
    }

    public int effectiveTaskGroupSize(int workUnitCount) {
        int configured = Math.max(1, valueOrDefault(taskGroupSize, 8));
        return Math.max(1, Math.min(configured, Math.max(1, workUnitCount)));
    }

    public int effectiveReadBatchSize() {
        return Math.max(1, valueOrDefault(readBatchSize, 512));
    }

    public int effectiveWriteBatchSize() {
        return Math.max(1, valueOrDefault(writeBatchSize, 256));
    }

    public int effectiveCommitIntervalRecords() {
        return Math.max(1, valueOrDefault(commitIntervalRecords, effectiveWriteBatchSize()));
    }

    public int effectiveTimeoutSeconds() {
        return Math.max(1, valueOrDefault(timeoutSeconds, 600));
    }

    public int effectiveMaxRetryCount() {
        return Math.max(0, valueOrDefault(maxRetryCount, 0));
    }

    public long effectiveMaxDirtyRecordCount() {
        return Math.max(0L, valueOrDefault(maxDirtyRecordCount, 100L));
    }

    public double effectiveMaxDirtyRecordRatio() {
        BigDecimal value = maxDirtyRecordRatio == null ? BigDecimal.valueOf(0.01D) : maxDirtyRecordRatio;
        return Math.max(0D, value.doubleValue());
    }

    private static <T> T firstNonNull(T value, T fallback) {
        return value == null ? fallback : value;
    }

    private static int valueOrDefault(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static long valueOrDefault(Long value, long fallback) {
        return value == null ? fallback : value;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }
}
