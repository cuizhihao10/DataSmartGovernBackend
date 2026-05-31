/**
 * @Author : Cui
 * @Date: 2026/05/31 23:54
 * @Description DataSmart Govern Backend - AgentAsyncTaskCommandKafkaMetricsServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.event;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Agent 异步命令 Kafka 指标服务测试。
 *
 * <p>指标测试只验证低基数标签和计数是否写入，不把 commandId、traceId、offset 这类高基数字段作为标签。
 * 这是生产可观测性的基本原则：单条消息定位交给日志/诊断快照，指标负责聚合趋势和告警。</p>
 */
class AgentAsyncTaskCommandKafkaMetricsServiceTest {

    @Test
    void rejectedMessageShouldIncreaseHandledAndDlqCandidateCounters() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AgentAsyncTaskCommandKafkaMetricsService service = new AgentAsyncTaskCommandKafkaMetricsService(meterRegistry);
        AgentAsyncTaskCommandKafkaRecordMetadata metadata = new AgentAsyncTaskCommandKafkaRecordMetadata(
                "datasmart.agent.tool.async.commands",
                0,
                10L,
                "keyhash",
                1000L,
                "CREATE_TIME",
                "trace-001"
        );

        service.recordRejected(AgentAsyncTaskCommandKafkaFailureType.INVALID_JSON,
                metadata,
                Duration.ofMillis(12),
                true);

        assertEquals(1.0, meterRegistry.counter("datasmart_task_agent_async_command_kafka_handled_total",
                "result", "REJECTED",
                "failureType", "INVALID_JSON",
                "duplicate", "false",
                "taskCreated", "false",
                "topic", "datasmart.agent.tool.async.commands").count());
        assertEquals(1.0, meterRegistry.counter("datasmart_task_agent_async_command_kafka_dlq_candidate_total",
                "failureType", "INVALID_JSON",
                "topic", "datasmart.agent.tool.async.commands").count());
        assertEquals(1, meterRegistry.find("datasmart_task_agent_async_command_kafka_handle_duration").timers().size());
    }
}
