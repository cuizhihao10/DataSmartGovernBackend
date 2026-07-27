/**
 * @Author : Cui
 * @Date: 2026/07/27 21:16
 * @Description DataSmart Govern Backend - SyncAutomaticPartitionConfigSupportTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.support.SyncMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SyncAutomaticPartitionConfigSupportTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SyncAutomaticPartitionConfigSupport support =
            new SyncAutomaticPartitionConfigSupport(objectMapper);

    @Test
    void shouldDeriveMinimalAutoSplitDeclarationFromSingleIntegralPrimaryKey() throws Exception {
        SyncAutomaticPartitionConfigSupport.AutomaticPartitionConfig result = support.resolve(
                SyncMode.FULL,
                "SINGLE_OBJECT",
                """
                        {"objectMappings":[{"mappings":[
                          {"sourceField":"id","targetField":"id","sourceType":"BIGINT","primaryKey":true,"syncEnabled":true},
                          {"sourceField":"name","targetField":"name","sourceType":"VARCHAR","primaryKey":false,"syncEnabled":true}
                        ]}]}
                        """,
                null
        );

        assertThat(result.systemManaged()).isTrue();
        assertThat(result.splitPk()).isEqualTo("id");
        assertThat(objectMapper.readTree(result.partitionConfig()).path("strategy").asText())
                .isEqualTo("AUTO_SPLIT_PK");
        assertThat(objectMapper.readTree(result.partitionConfig()).path("splitPk").asText())
                .isEqualTo("id");
        assertThat(result.partitionConfig()).doesNotContain("shardCount", "maxParallelism", "taskGroupSize");
    }

    @Test
    void shouldNotAutoSplitCompositeOrNonIntegralPrimaryKey() {
        String composite = """
                [{"sourceField":"tenant_id","sourceType":"BIGINT","primaryKey":true},
                 {"sourceField":"id","sourceType":"BIGINT","primaryKey":true}]
                """;
        String uuid = """
                [{"sourceField":"id","sourceType":"UUID","primaryKey":true}]
                """;

        assertThat(support.resolve(SyncMode.FULL, "SINGLE_OBJECT", composite, null).partitionConfig())
                .isNull();
        assertThat(support.resolve(SyncMode.FULL, "SINGLE_OBJECT", uuid, null).partitionConfig())
                .isNull();
    }

    @Test
    void shouldKeepExplicitInternalPartitionContractUntouched() {
        String explicit = "{\"strategy\":\"ID_RANGE\",\"partitionField\":\"id\",\"ranges\":[]}";

        SyncAutomaticPartitionConfigSupport.AutomaticPartitionConfig result = support.resolve(
                SyncMode.FULL, "SINGLE_OBJECT", null, explicit);

        assertThat(result.partitionConfig()).isEqualTo(explicit);
        assertThat(result.systemManaged()).isFalse();
    }
}
