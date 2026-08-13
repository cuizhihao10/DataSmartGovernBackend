/**
 * @Author : Cui
 * @Date: 2026/08/11 22:20
 * @Description DataSmart Govern Backend - AgentAutopilotRecoveryTriggerKafkaConsumerTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.event;

import com.czh.datasmart.govern.agent.AgentRuntimeApplication;
import com.czh.datasmart.govern.agent.service.autopilot.AgentAutopilotRecoveryDataSyncClient;
import com.czh.datasmart.govern.agent.service.autopilot.AgentAutopilotRecoveryMetrics;
import com.czh.datasmart.govern.agent.service.autopilot.AgentAutopilotRecoveryTriggerConsumerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.EnableKafkaRetryTopic;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Verifies that bounded retry and DLT behavior belongs only to the Autopilot trigger listener.
 *
 * <p>Reflection is sufficient here because the production behavior is declared by Spring Kafka annotations. The
 * test avoids starting a broker while still guarding the two important configuration promises: a finite retry path
 * with a DLT handler, and no accidental retry-topic policy added to the unrelated runtime-event listener.</p>
 */
class AgentAutopilotRecoveryTriggerKafkaConsumerTest {

    /**
     * The Autopilot listener must own its finite retry topic and low-sensitive DLT endpoint.
     *
     * <p>The retry count includes the first attempt, topic creation remains an operations responsibility, and DLT
     * handler failures are not retried indefinitely. The separate assertion on the runtime-event listener prevents
     * this recovery-specific policy from changing any other consumer's delivery behavior.</p>
     */
    @Test
    void shouldConfigureBoundedRetryAndDltOnlyForAutopilotTriggerListener() throws NoSuchMethodException {
        Method triggerListener = AgentAutopilotRecoveryTriggerKafkaConsumer.class
                .getMethod("onRecoveryTrigger", String.class);
        RetryableTopic retryableTopic = triggerListener.getAnnotation(RetryableTopic.class);
        Method dltHandler = AgentAutopilotRecoveryTriggerKafkaConsumer.class
                .getMethod("onRecoveryTriggerDlt", ConsumerRecord.class);
        Method runtimeEventListener = AgentRuntimeEventKafkaConsumer.class
                .getMethod("onAgentRuntimeEvent", String.class);

        assertThat(retryableTopic).isNotNull();
        assertThat(retryableTopic.attempts())
                .isEqualTo("${datasmart.agent-runtime.autopilot-recovery.kafka.retry-attempts:3}");
        assertThat(retryableTopic.autoCreateTopics()).isEqualTo("false");
        assertThat(retryableTopic.retryTopicSuffix()).isEqualTo("-autopilot-recovery-retry");
        assertThat(retryableTopic.dltTopicSuffix()).isEqualTo("-autopilot-recovery-dlt");
        assertThat(retryableTopic.dltStrategy()).isEqualTo(DltStrategy.FAIL_ON_ERROR);
        assertThat(dltHandler.isAnnotationPresent(DltHandler.class)).isTrue();
        assertThat(AgentRuntimeApplication.class.isAnnotationPresent(EnableKafkaRetryTopic.class)).isTrue();
        assertThat(runtimeEventListener.isAnnotationPresent(RetryableTopic.class)).isFalse();
    }

    /**
     * A valid DLT record must be represented in data-sync before the handler reports delivery completion.
     *
     * <p>The test uses the real JSON mapper so it covers the same identity extraction as production. Verifying the
     * fixed client call and metric proves that the handler does not merely log broker metadata and leave a started
     * case invisible to the control plane.</p>
     */
    @Test
    void shouldConvergeDeadLetteredTriggerBeforeRecordingMetric() {
        AgentAutopilotRecoveryDataSyncClient dataSyncClient =
                mock(AgentAutopilotRecoveryDataSyncClient.class);
        AgentAutopilotRecoveryMetrics metrics = mock(AgentAutopilotRecoveryMetrics.class);
        AgentAutopilotRecoveryTriggerKafkaConsumer consumer = new AgentAutopilotRecoveryTriggerKafkaConsumer(
                mock(AgentAutopilotRecoveryTriggerConsumerService.class),
                dataSyncClient,
                new ObjectMapper(),
                metrics);
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "datasmart.agent.autopilot-recovery-trigger.v1-autopilot-recovery-dlt",
                2,
                9L,
                "event-key",
                triggerPayload());

        consumer.onRecoveryTriggerDlt(record);

        verify(dataSyncClient).recordTriggerDeadLettered("event-1", 41L);
        verify(metrics).recordDeadLettered();
    }

    /**
     * A failed data-sync convergence must escape the DLT handler and must not be counted as completed delivery.
     *
     * <p>This preserves {@link DltStrategy#FAIL_ON_ERROR}: Spring Kafka can retry the DLT handler instead of
     * committing a record whose recovery case still lacks a durable bounded outcome.</p>
     */
    @Test
    void shouldFailDltHandlingWhenDurableConvergenceFails() {
        AgentAutopilotRecoveryDataSyncClient dataSyncClient =
                mock(AgentAutopilotRecoveryDataSyncClient.class);
        AgentAutopilotRecoveryMetrics metrics = mock(AgentAutopilotRecoveryMetrics.class);
        doThrow(new IllegalStateException("AUTOPILOT_DATA_SYNC_DEAD_LETTER_ENVELOPE_INVALID"))
                .when(dataSyncClient).recordTriggerDeadLettered("event-1", 41L);
        AgentAutopilotRecoveryTriggerKafkaConsumer consumer = new AgentAutopilotRecoveryTriggerKafkaConsumer(
                mock(AgentAutopilotRecoveryTriggerConsumerService.class),
                dataSyncClient,
                new ObjectMapper(),
                metrics);
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "dlt-topic", 0, 1L, null, triggerPayload());

        assertThatThrownBy(() -> consumer.onRecoveryTriggerDlt(record))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("AUTOPILOT_DATA_SYNC_DEAD_LETTER_ENVELOPE_INVALID");
        verify(metrics, never()).recordDeadLettered();
    }

    /** Creates the smallest structurally valid event required by DLT identity extraction. */
    private String triggerPayload() {
        return """
                {
                  "schemaVersion":"datasmart.autopilot.recovery-trigger.v1",
                  "eventId":"event-1",
                  "currentExecutionId":41,
                  "cycle":1,
                  "maxRecoveryCycles":3,
                  "issueCodes":[],
                  "authorizationSnapshot":{}
                }
                """;
    }
}
