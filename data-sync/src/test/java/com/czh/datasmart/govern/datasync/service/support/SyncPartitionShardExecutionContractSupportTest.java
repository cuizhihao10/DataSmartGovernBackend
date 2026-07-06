/**
 * @Author : Cui
 * @Date: 2026/07/07 23:58
 * @Description DataSmart Govern Backend - SyncPartitionShardExecutionContractSupportTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.entity.SyncTemplate;
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
        assertThat(contract.warnings()).contains("PARTITION_SUPPORTED_STRATEGY_ID_RANGE_ONLY");
    }
}
