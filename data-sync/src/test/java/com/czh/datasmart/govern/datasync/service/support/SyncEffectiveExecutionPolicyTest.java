/**
 * @Author : Cui
 * @Date: 2026/07/09 23:30
 * @Description DataSmart Govern Backend - SyncEffectiveExecutionPolicyTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.entity.SyncExecutionPolicy;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 有效执行策略中的自动分片算法测试。
 *
 * <p>普通用户不填写 shardCount。系统在 range-probe 得到源表行数后，按照
 * {@code ceil(rowCount / targetRowsPerShard)} 计算候选分片数，再用管理员配置的最小、最大分片数做裁剪。
 * 这组测试把算法本身固定下来，避免后续重构时退回“固定分片数”或错误地把 channel 当成分片数。</p>
 */
class SyncEffectiveExecutionPolicyTest {

    @Test
    void oneMillionRowsShouldProduceFiveShardsWhenTargetIsTwoHundredThousandRows() {
        SyncExecutionPolicy override = new SyncExecutionPolicy();
        override.setTargetRowsPerShard(200000L);
        override.setMinShardCount(1);
        override.setMaxShardCount(64);

        SyncEffectiveExecutionPolicy policy =
                SyncEffectiveExecutionPolicy.defaults(10L, 101L, 1001L).merge(override);

        assertThat(policy.adaptiveShardCount(1000000L, 1)).isEqualTo(5);
        assertThat(policy.effectiveChannel(5)).isEqualTo(4);
    }

    @Test
    void adaptiveShardCountShouldRespectConfiguredMinimumAndMaximum() {
        SyncExecutionPolicy override = new SyncExecutionPolicy();
        override.setTargetRowsPerShard(100000L);
        override.setMinShardCount(3);
        override.setMaxShardCount(12);

        SyncEffectiveExecutionPolicy policy =
                SyncEffectiveExecutionPolicy.defaults(10L, 101L, 1001L).merge(override);

        assertThat(policy.adaptiveShardCount(1L, 1))
                .as("极小表仍受管理员最小分片数约束")
                .isEqualTo(3);
        assertThat(policy.adaptiveShardCount(10000000L, 1))
                .as("超大表不能突破管理员最大分片数，避免生成过多账本和调度单元")
                .isEqualTo(12);
        assertThat(policy.adaptiveShardCount(null, 20))
                .as("探测不到行数时使用兼容兜底值，但仍应用 min/max 安全裁剪")
                .isEqualTo(12);
    }
}
