/**
 * @Author : Cui
 * @Date: 2026/05/31 23:19
 * @Description DataSmart Govern Backend - AgentAsyncTaskCommandKafkaDiagnosticsServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.event;

import com.czh.datasmart.govern.task.config.AgentAsyncTaskCommandKafkaProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent 异步工具命令 Kafka 诊断服务测试。
 *
 * <p>这里重点验证两个生产化语义：
 * 1. 最近失败样本必须有容量上限，避免坏消息风暴时内存无限增长；
 * 2. DLQ 开关当前只作为候选标记进入诊断快照，不会要求测试启动真实 Kafka Producer。</p>
 */
class AgentAsyncTaskCommandKafkaDiagnosticsServiceTest {

    @Test
    void recentFailuresShouldBeBoundedByConfiguredWindow() {
        AgentAsyncTaskCommandKafkaProperties properties = new AgentAsyncTaskCommandKafkaProperties();
        properties.setMaxRecentFailures(2);
        AgentAsyncTaskCommandKafkaDiagnosticsService service = new AgentAsyncTaskCommandKafkaDiagnosticsService(properties);

        service.recordFailure(AgentAsyncTaskCommandKafkaFailureType.INVALID_JSON, "first", 5);
        service.recordFailure(AgentAsyncTaskCommandKafkaFailureType.PAYLOAD_TOO_LARGE, "second", 99);
        service.recordFailure(AgentAsyncTaskCommandKafkaFailureType.CONSUMER_REJECTED, "third", 30);

        AgentAsyncTaskCommandKafkaDiagnosticsSnapshot snapshot = service.snapshot();

        assertEquals(3L, snapshot.totalFailures());
        assertEquals(2, snapshot.recentFailures().size());
        assertEquals(AgentAsyncTaskCommandKafkaFailureType.PAYLOAD_TOO_LARGE, snapshot.recentFailures().get(0).type());
        assertEquals(AgentAsyncTaskCommandKafkaFailureType.CONSUMER_REJECTED, snapshot.recentFailures().get(1).type());
        assertEquals(1L, snapshot.failuresByType().get(AgentAsyncTaskCommandKafkaFailureType.INVALID_JSON));
    }

    @Test
    void dlqEnabledShouldMarkFailureAsDlqCandidateWithoutWritingRealTopic() {
        AgentAsyncTaskCommandKafkaProperties properties = new AgentAsyncTaskCommandKafkaProperties();
        properties.setDlqEnabled(true);
        properties.setDlqTopic("custom.dlq.topic");
        AgentAsyncTaskCommandKafkaDiagnosticsService service = new AgentAsyncTaskCommandKafkaDiagnosticsService(properties);

        service.recordFailure(AgentAsyncTaskCommandKafkaFailureType.CONSUMER_EXCEPTION, "database timeout", 128);

        AgentAsyncTaskCommandKafkaDiagnosticsSnapshot snapshot = service.snapshot();

        assertTrue(snapshot.dlqEnabled());
        assertEquals("custom.dlq.topic", snapshot.dlqTopic());
        assertEquals(1L, snapshot.dlqCandidateFailures());
        assertTrue(snapshot.recentFailures().get(0).dlqCandidate());
    }
}
