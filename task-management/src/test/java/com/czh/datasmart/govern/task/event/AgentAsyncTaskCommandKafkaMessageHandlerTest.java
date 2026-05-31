/**
 * @Author : Cui
 * @Date: 2026/05/31 17:25
 * @Description DataSmart Govern Backend - AgentAsyncTaskCommandKafkaMessageHandlerTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.event;

import com.czh.datasmart.govern.task.config.AgentAsyncTaskCommandKafkaProperties;
import com.czh.datasmart.govern.task.controller.dto.AgentAsyncTaskCommandConsumeResponse;
import com.czh.datasmart.govern.task.controller.dto.AgentAsyncTaskCommandRequest;
import com.czh.datasmart.govern.task.service.AgentAsyncTaskCommandConsumerService;
import com.czh.datasmart.govern.task.support.AgentAsyncTaskCommandState;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Agent 异步命令 Kafka 消息处理器测试。
 *
 * <p>测试不启动 Kafka broker，只验证传输适配层的核心语义：
 * 1. 合法 JSON 会被解析为共享 DTO，并交给 ConsumerService；
 * 2. 超过大小上限的消息会被拒绝，避免异常 payload 拖垮消费者；
 * 3. 本地联调可选择跳过非法消息，但生产默认应抛异常等待重试或后续 DLQ。</p>
 */
class AgentAsyncTaskCommandKafkaMessageHandlerTest {

    private AgentAsyncTaskCommandKafkaProperties properties;
    private AgentAsyncTaskCommandConsumerService consumerService;
    private ObjectMapper objectMapper;
    private AgentAsyncTaskCommandKafkaDiagnosticsService diagnosticsService;
    private SimpleMeterRegistry meterRegistry;
    private AgentAsyncTaskCommandKafkaMetricsService metricsService;
    private AgentAsyncTaskCommandKafkaMessageHandler handler;

    @BeforeEach
    void setUp() {
        properties = new AgentAsyncTaskCommandKafkaProperties();
        consumerService = mock(AgentAsyncTaskCommandConsumerService.class);
        objectMapper = new ObjectMapper();
        diagnosticsService = new AgentAsyncTaskCommandKafkaDiagnosticsService(properties);
        meterRegistry = new SimpleMeterRegistry();
        metricsService = new AgentAsyncTaskCommandKafkaMetricsService(meterRegistry);
        handler = new AgentAsyncTaskCommandKafkaMessageHandler(properties, consumerService, objectMapper,
                diagnosticsService, metricsService);
    }

    @Test
    void validPayloadShouldBeParsedAndDelegatedToConsumerService() throws JsonProcessingException {
        when(consumerService.consume(any(AgentAsyncTaskCommandRequest.class))).thenReturn(new AgentAsyncTaskCommandConsumeResponse(
                "aatc-kafka-001",
                "agent-tool-async:session-kafka:run-kafka:audit-kafka",
                AgentAsyncTaskCommandState.TASK_CREATED,
                false,
                true,
                9100L,
                "Kafka command 已创建任务"
        ));

        AgentAsyncTaskCommandKafkaMessageHandler.AgentAsyncTaskCommandKafkaHandleResult result =
                handler.handle(objectMapper.writeValueAsString(validRequest()));

        assertTrue(result.accepted());
        assertFalse(result.duplicate());
        assertEquals("aatc-kafka-001", result.commandId());
        assertEquals(9100L, result.taskId());
        assertEquals(1.0, meterRegistry.counter("datasmart_task_agent_async_command_kafka_handled_total",
                "result", "ACCEPTED",
                "failureType", "NONE",
                "duplicate", "false",
                "taskCreated", "true",
                "topic", "UNKNOWN").count());
        verify(consumerService).consume(any(AgentAsyncTaskCommandRequest.class));
    }

    @Test
    void oversizedPayloadShouldThrowAndNotCallConsumerService() {
        properties.setMaxPayloadBytes(8);

        assertThrows(IllegalArgumentException.class, () -> handler.handle("{\"too\":\"large\"}"));

        verifyNoInteractions(consumerService);
    }

    @Test
    void invalidJsonCanBeSkippedWhenLocalDebugModeDisablesFailFast() {
        properties.setFailOnRejectedMessage(false);

        AgentAsyncTaskCommandKafkaMessageHandler.AgentAsyncTaskCommandKafkaHandleResult result =
                handler.handle("{not-json");

        assertFalse(result.accepted());
        assertEquals(AgentAsyncTaskCommandKafkaFailureType.INVALID_JSON, result.failureType());
        assertTrue(result.reason().contains("不是合法 JSON"));
        AgentAsyncTaskCommandKafkaDiagnosticsSnapshot snapshot = diagnosticsService.snapshot();
        assertEquals(1L, snapshot.totalFailures());
        assertEquals(1L, snapshot.failuresByType().get(AgentAsyncTaskCommandKafkaFailureType.INVALID_JSON));
        assertEquals(1.0, meterRegistry.counter("datasmart_task_agent_async_command_kafka_handled_total",
                "result", "REJECTED",
                "failureType", "INVALID_JSON",
                "duplicate", "false",
                "taskCreated", "false",
                "topic", "UNKNOWN").count());
        verifyNoInteractions(consumerService);
    }

    @Test
    void oversizedPayloadShouldBeRecordedAsDiagnosticsWhenFailFastIsDisabled() {
        properties.setFailOnRejectedMessage(false);
        properties.setMaxPayloadBytes(8);

        AgentAsyncTaskCommandKafkaMessageHandler.AgentAsyncTaskCommandKafkaHandleResult result =
                handler.handle("{\"too\":\"large\"}");

        assertFalse(result.accepted());
        assertEquals(AgentAsyncTaskCommandKafkaFailureType.PAYLOAD_TOO_LARGE, result.failureType());
        AgentAsyncTaskCommandKafkaDiagnosticsSnapshot snapshot = diagnosticsService.snapshot();
        assertEquals(1L, snapshot.totalFailures());
        assertEquals(1, snapshot.recentFailures().size());
        assertEquals(AgentAsyncTaskCommandKafkaFailureType.PAYLOAD_TOO_LARGE, snapshot.recentFailures().get(0).type());
        assertEquals("UNKNOWN", snapshot.recentFailures().get(0).recordMetadata().topic());
        verifyNoInteractions(consumerService);
    }

    @Test
    void consumerValidationErrorShouldBeClassifiedAsRejectedMessage() throws JsonProcessingException {
        properties.setFailOnRejectedMessage(false);
        when(consumerService.consume(any(AgentAsyncTaskCommandRequest.class)))
                .thenThrow(new IllegalArgumentException("schemaVersion 不支持"));

        AgentAsyncTaskCommandKafkaMessageHandler.AgentAsyncTaskCommandKafkaHandleResult result =
                handler.handle(objectMapper.writeValueAsString(validRequest()));

        assertFalse(result.accepted());
        assertEquals(AgentAsyncTaskCommandKafkaFailureType.CONSUMER_REJECTED, result.failureType());
        AgentAsyncTaskCommandKafkaDiagnosticsSnapshot snapshot = diagnosticsService.snapshot();
        assertEquals(1L, snapshot.failuresByType().get(AgentAsyncTaskCommandKafkaFailureType.CONSUMER_REJECTED));
        verify(consumerService).consume(any(AgentAsyncTaskCommandRequest.class));
    }

    private AgentAsyncTaskCommandRequest validRequest() {
        AgentAsyncTaskCommandRequest request = new AgentAsyncTaskCommandRequest();
        request.setSchemaVersion(AgentAsyncTaskCommandConsumerService.SUPPORTED_SCHEMA_VERSION);
        request.setCommandId("aatc-kafka-001");
        request.setIdempotencyKey("agent-tool-async:session-kafka:run-kafka:audit-kafka");
        request.setCommandType(AgentAsyncTaskCommandConsumerService.SUPPORTED_COMMAND_TYPE);
        request.setAuditId("audit-kafka");
        request.setSessionId("session-kafka");
        request.setRunId("run-kafka");
        request.setToolCode("data-sync.execute");
        request.setTargetService("data-sync");
        request.setTargetEndpoint("/sync-tasks");
        request.setTenantId(10L);
        request.setProjectId(20L);
        request.setWorkspaceId(30L);
        request.setActorId("actor-kafka");
        request.setTraceId("trace-kafka");
        request.setPayloadReference("agent-tool-audit://session-kafka/run-kafka/audit-kafka/plan-arguments");
        request.setArgumentNames(List.of("datasourceId", "credentialRef"));
        request.setSensitiveArgumentNames(List.of("credentialRef"));
        request.setPriority("MEDIUM");
        request.setMaxRetryCount(3);
        request.setMaxDeferCount(20);
        return request;
    }
}
