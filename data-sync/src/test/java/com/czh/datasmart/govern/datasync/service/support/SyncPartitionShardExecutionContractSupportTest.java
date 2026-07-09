/**
 * @Author : Cui
 * @Date: 2026/07/07 23:58
 * @Description DataSmart Govern Backend - SyncPartitionShardExecutionContractSupportTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.entity.SyncTemplate;
import com.czh.datasmart.govern.datasync.entity.SyncExecutionPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * partitionConfig 分片合同解析测试。
 *
 * <p>这组测试聚焦“能不能安全地把用户配置的分片计划变成可执行工作单元”。它不连接数据库，也不验证真实读写；
 * 真实读写由 datasource-management 的 JDBC E2E 覆盖。这里的核心价值是把分片策略、字段名、边界表达和低敏分片标识
 * 固定成控制面合同，避免后续 fan-out 调度器直接拼 SQL 或把边界值写入公开事件。</p>
 */
class SyncPartitionShardExecutionContractSupportTest {

    private final SyncPartitionShardExecutionContractSupport support =
            new SyncPartitionShardExecutionContractSupport(new ObjectMapper());

    @Test
    void idRangeConfigShouldProduceExecutableShardItems() {
        SyncTemplate template = new SyncTemplate();
        template.setPartitionConfig("""
                {
                  "strategy": "ID_RANGE",
                  "partitionField": "id",
                  "maxParallelism": 4,
                  "maxShardAttempts": 3,
                  "ranges": [
                    {"startInclusive": 1, "endExclusive": 100},
                    {"startInclusive": 100, "endExclusive": 200}
                  ]
                }
                """);

        SyncPartitionShardExecutionContract contract = support.parse(template);

        assertThat(contract.declared()).isTrue();
        assertThat(contract.parseable()).isTrue();
        assertThat(contract.executableByPartitionFanOut()).isTrue();
        assertThat(contract.partitionStrategy()).isEqualTo("ID_RANGE");
        assertThat(contract.partitionField()).isEqualTo("id");
        assertThat(contract.maxParallelism()).isEqualTo(4);
        assertThat(contract.maxAttemptCount()).isEqualTo(3);
        assertThat(contract.shardCount()).isEqualTo(2);
        assertThat(contract.shards())
                .extracting(SyncPartitionShardExecutionItem::shardOrPartition)
                .containsExactly("id-range-0000", "id-range-0001");
        assertThat(contract.shards().get(0).filterConditions())
                .extracting(SyncFilterExecutionCondition::getColumn)
                .containsExactly("id", "id");
        assertThat(contract.shards().get(0).filterConditions())
                .extracting(SyncFilterExecutionCondition::getOperator)
                .containsExactly("GTE", "LT");
        assertThat(contract.issueCodes()).isEmpty();
        assertThat(contract.payloadPolicy())
                .isEqualTo(SyncPartitionShardExecutionContract.PAYLOAD_POLICY);
    }

    @Test
    void unsafeFieldShouldBlockExecutableShardContract() {
        SyncTemplate template = new SyncTemplate();
        template.setPartitionConfig("""
                {
                  "strategy": "ID_RANGE",
                  "partitionField": "id or 1=1",
                  "ranges": [
                    {"startInclusive": 1, "endExclusive": 100}
                  ]
                }
                """);

        SyncPartitionShardExecutionContract contract = support.parse(template);

        assertThat(contract.declared()).isTrue();
        assertThat(contract.executableByPartitionFanOut()).isFalse();
        assertThat(contract.issueCodes()).contains("PARTITION_FIELD_IDENTIFIER_UNSAFE");
    }

    @Test
    void unsupportedStrategyShouldStayParseableButNotExecutable() {
        SyncTemplate template = new SyncTemplate();
        template.setPartitionConfig("""
                {
                  "strategy": "HASH_BUCKET",
                  "partitionField": "id",
                  "bucketCount": 8
                }
                """);

        SyncPartitionShardExecutionContract contract = support.parse(template);

        assertThat(contract.declared()).isTrue();
        assertThat(contract.parseable()).isTrue();
        assertThat(contract.executableByPartitionFanOut()).isFalse();
        assertThat(contract.issueCodes()).contains("PARTITION_STRATEGY_UNSUPPORTED");
        assertThat(contract.warnings()).contains("PARTITION_SUPPORTED_STRATEGY_ID_RANGE_OR_AUTO_SPLIT_PK_ONLY");
    }

    @Test
    void autoSplitPkShouldRequireProbeAndBuildIdRangeShardsAfterProbe() {
        SyncTemplate template = new SyncTemplate();
        template.setPartitionConfig("""
                {
                  "strategy": "AUTO_SPLIT_PK",
                  "splitPk": "id",
                  "shardCount": 4,
                  "channel": 2,
                  "taskGroupSize": 2,
                  "maxDirtyRecordCount": 10,
                  "maxDirtyRecordRatio": 0.05
                }
                """);

        SyncPartitionShardExecutionContract probeRequired = support.parse(template);

        assertThat(probeRequired.autoRangeProbeRequired()).isTrue();
        assertThat(probeRequired.executableByPartitionFanOut()).isFalse();
        assertThat(probeRequired.partitionField()).isEqualTo("id");
        assertThat(probeRequired.requestedShardCount()).isEqualTo(4);
        assertThat(probeRequired.maxParallelism()).isEqualTo(2);
        assertThat(probeRequired.taskGroupSize()).isEqualTo(2);
        assertThat(probeRequired.maxDirtyRecordCount()).isEqualTo(10L);
        assertThat(probeRequired.maxDirtyRecordRatio()).isEqualTo(0.05D);

        com.czh.datasmart.govern.datasync.integration.datasource.partition.DatasourcePartitionRangeProbeResponse probe =
                new com.czh.datasmart.govern.datasync.integration.datasource.partition.DatasourcePartitionRangeProbeResponse();
        probe.setProbeStatus("RANGE_PROBED");
        probe.setNumericRange(true);
        probe.setMinValue(1L);
        probe.setMaxValue(10L);
        probe.setRowCount(10L);
        probe.setWarnings(java.util.List.of("SPLIT_PK_MIN_MAX_PROBED_BY_DATASOURCE_MANAGEMENT"));

        SyncPartitionShardExecutionContract executable = support.buildAutoRangeContract(probeRequired, probe);

        assertThat(executable.executableByPartitionFanOut()).isTrue();
        assertThat(executable.autoRangeProbeRequired()).isFalse();
        assertThat(executable.partitionStrategy()).isEqualTo("ID_RANGE");
        assertThat(executable.shardCount())
                .as("旧模板中的 shardCount 只保留兼容解析；10 行小表按默认每片目标行数自动收敛为 1 片")
                .isEqualTo(1);
        assertThat(executable.shards())
                .extracting(SyncPartitionShardExecutionItem::shardOrPartition)
                .containsExactly("splitpk-range-0000");
    }

    @Test
    void autoSplitPkShouldUseRangeProbeRowCountAndAdministratorPolicyInsteadOfUserShardCount() {
        SyncTemplate template = new SyncTemplate();
        template.setPartitionConfig("""
                {
                  "strategy": "AUTO_SPLIT_PK",
                  "splitPk": "id",
                  "shardCount": 32
                }
                """);
        SyncPartitionShardExecutionContract probeRequired = support.parse(template);

        com.czh.datasmart.govern.datasync.integration.datasource.partition.DatasourcePartitionRangeProbeResponse probe =
                new com.czh.datasmart.govern.datasync.integration.datasource.partition.DatasourcePartitionRangeProbeResponse();
        probe.setProbeStatus("RANGE_PROBED");
        probe.setNumericRange(true);
        probe.setMinValue(1L);
        probe.setMaxValue(1000000L);
        probe.setRowCount(1000000L);

        SyncExecutionPolicy administratorOverride = new SyncExecutionPolicy();
        administratorOverride.setScopeType("PROJECT");
        administratorOverride.setPolicyCode("PROJECT_AUTO_SHARD");
        administratorOverride.setTargetRowsPerShard(200000L);
        administratorOverride.setMinShardCount(1);
        administratorOverride.setMaxShardCount(64);
        administratorOverride.setMaxChannel(3);
        SyncEffectiveExecutionPolicy effectivePolicy =
                SyncEffectiveExecutionPolicy.defaults(10L, 101L, 1001L).merge(administratorOverride);

        SyncPartitionShardExecutionContract executable =
                support.buildAutoRangeContract(probeRequired, probe, effectivePolicy);

        assertThat(executable.shardCount())
                .as("一百万行按每片二十万行自动计算为五片，而不是继续采用旧模板中的固定 32 片")
                .isEqualTo(5);
        assertThat(executable.maxParallelism())
                .as("channel 是并发上限，不等于分片数")
                .isEqualTo(3);
        assertThat(executable.warnings()).contains("PARTITION_AUTO_SPLIT_PK_ADAPTIVE_SHARD_COUNT_APPLIED");
    }
}
