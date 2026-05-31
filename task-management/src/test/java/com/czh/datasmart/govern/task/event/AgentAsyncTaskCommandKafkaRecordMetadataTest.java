/**
 * @Author : Cui
 * @Date: 2026/05/31 23:53
 * @Description DataSmart Govern Backend - AgentAsyncTaskCommandKafkaRecordMetadataTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.event;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kafka record 元数据提取测试。
 *
 * <p>这里不启动 Kafka broker，只验证“从 ConsumerRecord 提取低敏定位字段”的本地语义。
 * 这样后续 listener 从 String payload 改为 ConsumerRecord 时，元数据提取规则可以稳定回归。</p>
 */
class AgentAsyncTaskCommandKafkaRecordMetadataTest {

    @Test
    void shouldExtractRecordLocationAndTraceHeaderWithoutKeepingRawKey() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "datasmart.agent.tool.async.commands",
                3,
                41L,
                "tenant-10-project-20-command-key",
                "{\"commandId\":\"aatc-001\"}"
        );
        record.headers().add("x-trace-id", "trace-kafka-001".getBytes(StandardCharsets.UTF_8));

        AgentAsyncTaskCommandKafkaRecordMetadata metadata = AgentAsyncTaskCommandKafkaRecordMetadata.from(record);

        assertEquals("datasmart.agent.tool.async.commands", metadata.topic());
        assertEquals(3, metadata.partition());
        assertEquals(41L, metadata.offset());
        assertEquals("trace-kafka-001", metadata.traceId());
        assertNotEquals("tenant-10-project-20-command-key", metadata.keyHash());
        assertEquals(16, metadata.keyHash().length());
        assertTrue(metadata.location().contains("partition=3"));
    }

    @Test
    void emptyMetadataShouldUseUnknownSentinelValues() {
        AgentAsyncTaskCommandKafkaRecordMetadata metadata = AgentAsyncTaskCommandKafkaRecordMetadata.empty();

        assertEquals("UNKNOWN", metadata.topic());
        assertEquals(-1, metadata.partition());
        assertEquals(-1L, metadata.offset());
        assertEquals("UNKNOWN", metadata.keyHash());
        assertEquals("UNKNOWN", metadata.traceId());
    }
}
