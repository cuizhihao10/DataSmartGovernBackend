/**
 * @Author : Cui
 * @Date: 2026/08/11 18:55
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryTriggerOutboxServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.config.SyncAutopilotRecoveryTriggerProperties;
import com.czh.datasmart.govern.datasync.entity.SyncAutopilotRecoveryTriggerOutbox;
import com.czh.datasmart.govern.datasync.mapper.SyncAutopilotRecoveryTriggerOutboxMapper;
import com.czh.datasmart.govern.datasync.support.SyncAutopilotRecoveryTriggerOutboxState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 验证 trigger outbox 的幂等入库、原子认领和有界失败策略。
 */
class SyncAutopilotRecoveryTriggerOutboxServiceTest {

    /**
     * 首次事件必须先 insert，再认领并标记 DELIVERED；不能绕过数据库直接发 Kafka。
     */
    @Test
    void shouldPersistBeforeImmediateKafkaDispatch() throws Exception {
        Fixture fixture = fixture();
        SyncAutopilotRecoveryTriggerOutbox persisted = outbox(71L, 0, 3);
        when(fixture.mapper().selectByEventId("event-1")).thenReturn(null, persisted);
        when(fixture.mapper().insertIfAbsent(any())).thenReturn(1);
        when(fixture.mapper().markDispatching(71L, 300L)).thenReturn(1);
        when(fixture.mapper().markDelivered(71L)).thenReturn(1);

        fixture.service().enqueueAndDispatch(event());

        ArgumentCaptor<SyncAutopilotRecoveryTriggerOutbox> inserted =
                ArgumentCaptor.forClass(SyncAutopilotRecoveryTriggerOutbox.class);
        verify(fixture.mapper()).insertIfAbsent(inserted.capture());
        assertThat(inserted.getValue().getOutboxState())
                .isEqualTo(SyncAutopilotRecoveryTriggerOutboxState.PENDING.name());
        assertThat(inserted.getValue().getPayloadJson()).contains("\"rootSessionId\":\"session-1\"");
        verify(fixture.dispatcher()).dispatch(persisted);
        verify(fixture.mapper()).markDelivered(71L);
    }

    /**
     * 业务事务尚未提交时只能登记 afterCommit 回调，不能提前把事件交给 Kafka。
     *
     * <p>测试直接控制 Spring 的线程级事务同步器：先确认 enqueue 阶段没有调用 dispatcher，
     * 再手动触发 afterCommit，证明即时投递发生在数据库提交之后。</p>
     */
    @Test
    void shouldDelayImmediateKafkaDispatchUntilTransactionCommit() throws Exception {
        Fixture fixture = fixture();
        SyncAutopilotRecoveryTriggerOutbox persisted = outbox(74L, 0, 3);
        when(fixture.mapper().selectByEventId("event-1")).thenReturn(null, persisted);
        when(fixture.mapper().insertIfAbsent(any())).thenReturn(1);
        when(fixture.mapper().markDispatching(74L, 300L)).thenReturn(1);
        when(fixture.mapper().markDelivered(74L)).thenReturn(1);

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            fixture.service().enqueueAndDispatch(event());

            verifyNoInteractions(fixture.dispatcher());
            List<TransactionSynchronization> synchronizations =
                    TransactionSynchronizationManager.getSynchronizations();
            assertThat(synchronizations).hasSize(1);
            synchronizations.getFirst().afterCommit();

            verify(fixture.dispatcher()).dispatch(persisted);
            verify(fixture.mapper()).markDelivered(74L);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    /**
     * Kafka 短时故障应写 RETRY_WAIT 和 nextRetryAt，而不是丢记录或抛回同步失败主链。
     */
    @Test
    void shouldBackoffAfterDispatchFailure() throws Exception {
        Fixture fixture = fixture();
        SyncAutopilotRecoveryTriggerOutbox persisted = outbox(72L, 0, 3);
        when(fixture.mapper().selectByEventId("event-1")).thenReturn(persisted);
        when(fixture.mapper().markDispatching(72L, 300L)).thenReturn(1);
        doThrow(new IllegalStateException("broker unavailable"))
                .when(fixture.dispatcher()).dispatch(persisted);

        fixture.service().enqueueAndDispatch(event());

        verify(fixture.mapper()).markFailure(
                eq(72L),
                eq(SyncAutopilotRecoveryTriggerOutboxState.RETRY_WAIT.name()),
                any(),
                isNull(),
                eq("AUTOPILOT_TRIGGER_KAFKA_DISPATCH_FAILED"),
                eq("Autopilot recovery trigger could not be delivered"));
        verifyNoInteractions(fixture.deadLetterService());
    }

    /**
     * 达到尝试预算后必须通过 producer 收敛事务进入 DEAD_LETTER，防止 broker 长期故障造成无限循环。
     *
     * <p>The outbox service itself must not write a consumer result here: a broker acknowledgement failure does not
     * prove that Agent Runtime received the event. The mocked producer convergence service owns the atomic local
     * terminal outbox/case update and is the only collaboration expected for this final attempt.</p>
     */
    @Test
    void shouldDeadLetterAfterMaxAttempts() throws Exception {
        Fixture fixture = fixture();
        SyncAutopilotRecoveryTriggerOutbox persisted = outbox(73L, 2, 3);
        when(fixture.mapper().selectByEventId("event-1")).thenReturn(persisted);
        when(fixture.mapper().markDispatching(73L, 300L)).thenReturn(1);
        doThrow(new IllegalStateException("broker unavailable"))
                .when(fixture.dispatcher()).dispatch(persisted);

        fixture.service().enqueueAndDispatch(event());

        verify(fixture.deadLetterService()).recordProducerDeadLettered(persisted);
        verify(fixture.mapper(), never()).markFailure(
                eq(73L),
                eq(SyncAutopilotRecoveryTriggerOutboxState.DEAD_LETTER.name()),
                isNull(),
                any(),
                eq("AUTOPILOT_TRIGGER_KAFKA_DISPATCH_FAILED"),
                eq("Autopilot recovery trigger could not be delivered"));
    }

    private Fixture fixture() {
        SyncAutopilotRecoveryTriggerOutboxMapper mapper =
                mock(SyncAutopilotRecoveryTriggerOutboxMapper.class);
        SyncAutopilotRecoveryTriggerKafkaDispatcher dispatcher =
                mock(SyncAutopilotRecoveryTriggerKafkaDispatcher.class);
        SyncAutopilotRecoveryDeadLetterService deadLetterService =
                mock(SyncAutopilotRecoveryDeadLetterService.class);
        SyncAutopilotRecoveryTriggerProperties properties = new SyncAutopilotRecoveryTriggerProperties();
        properties.setImmediateDispatchEnabled(true);
        properties.setMaxAttempts(3);
        return new Fixture(
                new SyncAutopilotRecoveryTriggerOutboxService(
                        mapper, dispatcher, properties, new ObjectMapper(), deadLetterService),
                mapper,
                dispatcher,
                deadLetterService);
    }

    private SyncAutopilotRecoveryTriggerOutbox outbox(Long id, int attempts, int maxAttempts) {
        SyncAutopilotRecoveryTriggerOutbox outbox = new SyncAutopilotRecoveryTriggerOutbox();
        outbox.setId(id);
        outbox.setEventId("event-1");
        outbox.setTenantId(10L);
        outbox.setProjectId(20L);
        outbox.setSyncTaskId(31L);
        outbox.setRootExecutionId(1001L);
        outbox.setCurrentExecutionId(1001L);
        outbox.setCycle(1);
        outbox.setOutboxState(SyncAutopilotRecoveryTriggerOutboxState.PENDING.name());
        outbox.setAttemptCount(attempts);
        outbox.setMaxAttemptCount(maxAttempts);
        outbox.setPayloadJson("{}");
        return outbox;
    }

    private SyncAutopilotRecoveryTriggerEvent event() {
        return new SyncAutopilotRecoveryTriggerEvent(
                "datasmart.autopilot.recovery-trigger.v1",
                "event-1",
                "session-1",
                "run-1",
                10L,
                100L,
                20L,
                "9001",
                "9001",
                "OPENCLAW",
                "delegation-1",
                31L,
                1001L,
                1001L,
                1,
                5,
                "2099-01-01T00:00:00Z",
                "a".repeat(64),
                0,
                null,
                List.of("TARGET_TIMEOUT"),
                Map.of("policyId", "policy-1"),
                "sha256:" + "b".repeat(64),
                "2026-08-11T00:00:00Z");
    }

    private record Fixture(
            SyncAutopilotRecoveryTriggerOutboxService service,
            SyncAutopilotRecoveryTriggerOutboxMapper mapper,
            SyncAutopilotRecoveryTriggerKafkaDispatcher dispatcher,
            SyncAutopilotRecoveryDeadLetterService deadLetterService) {
    }
}
