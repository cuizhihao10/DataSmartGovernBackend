/**
 * @Author : Cui
 * @Date: 2026/08/11 21:30
 * @Description DataSmart Govern Backend - AgentAutopilotRecoveryTriggerConsumerServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.autopilot;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 验证确定性拒绝与可重试下游故障对应的不同 Kafka 确认行为。
 *
 * <p>测试直接调用应用服务，不启动 Kafka broker。正常返回会让监听器确认 offset，未捕获异常则会启动
 * Spring Kafka 重试处理。因此，该聚焦边界测试不依赖基础设施也能明确证明期望的投递行为。</p>
 */
class AgentAutopilotRecoveryTriggerConsumerServiceTest {

    /**
     * Provider 超时已经由 Python 归类为瞬态技术故障时，必须抛到 Kafka 监听器触发有界重投。
     *
     * <p>这里不依赖异常原文，只读取版本化响应中的固定原因码和来源码。测试同时证明瞬态失败不会进入
     * Java 执行服务，也不会提前写回 data-sync；只有后续重投真正获得候选或耗尽重试后，控制面才会
     * 产生持久业务终态。</p>
     */
    @Test
    void shouldPropagateClassifiedTransientModelFailureForKafkaRetry() {
        Fixture fixture = fixture();
        AgentAutopilotVerifiedRecoveryTrigger verified = mock(AgentAutopilotVerifiedRecoveryTrigger.class);
        AgentAutopilotRecoveryPlanResponse response = mock(AgentAutopilotRecoveryPlanResponse.class);
        when(fixture.triggerVerifier.verify(any())).thenReturn(verified);
        when(fixture.pythonClient.plan(verified)).thenReturn(response);
        when(response.status()).thenReturn("FAILED");
        when(response.reasonCode()).thenReturn("RECOVERY_PLANNING_MODEL_FAILED");
        when(response.modelFailureReasonCode()).thenReturn("MODEL_TIMEOUT");
        when(response.modelFailureSource()).thenReturn("MODEL_PROVIDER_TRANSPORT");

        assertThatThrownBy(() -> fixture.service.consume(validPayload()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("PYTHON_AUTOPILOT_RECOVERY_PLANNER_TRANSIENT_FAILURE");

        verify(fixture.metrics).recordPlanningFailed();
        verify(fixture.executionService, never()).execute(any(), any());
        verifyNoInteractions(fixture.dataSyncClient);
    }

    /**
     * JSON 解析或模型响应契约失败属于确定性结果，不能靠 Kafka 反复询问模型来碰运气。
     *
     * <p>执行服务会把该失败投影为低敏终态，随后 data-sync 先持久接收结果，监听器才允许 ACK。这样既
     * 避免无意义循环，也让前端和审计记录看到明确失败原因。</p>
     */
    @Test
    void shouldDurablyRecordPermanentModelContractFailureWithoutKafkaRetry() {
        Fixture fixture = fixture();
        AgentAutopilotVerifiedRecoveryTrigger verified = mock(AgentAutopilotVerifiedRecoveryTrigger.class);
        AgentAutopilotRecoveryPlanResponse response = mock(AgentAutopilotRecoveryPlanResponse.class);
        AgentAutopilotRecoveryExecutionResult failed = new AgentAutopilotRecoveryExecutionResult(
                "event-1", "FAILED", "RECOVERY_PLANNING_MODEL_FAILED", null, 41L);
        when(fixture.triggerVerifier.verify(any())).thenReturn(verified);
        when(fixture.pythonClient.plan(verified)).thenReturn(response);
        when(response.status()).thenReturn("FAILED");
        when(response.reasonCode()).thenReturn("RECOVERY_PLANNING_MODEL_FAILED");
        when(response.modelFailureReasonCode()).thenReturn("MODEL_RESPONSE_INVALID_JSON");
        when(response.modelFailureSource()).thenReturn("MODEL_RESPONSE_PARSER");
        when(fixture.executionService.execute(verified, response)).thenReturn(failed);

        AgentAutopilotRecoveryExecutionResult result = fixture.service.consume(validPayload());

        assertThat(result).isEqualTo(failed);
        verify(fixture.dataSyncClient).recordTriggerResult(failed);
        verify(fixture.metrics).recordPlanningFailed();
    }

    /**
     * 格式损坏的 JSON 无法在后续投递中变成合法事件，因此属于确定性毒消息。
     *
     * <p>服务必须返回稳定拒绝，使监听器可以确认记录。校验器、Python 和 data-sync 都不能运行，因为任意
     * 无效字节绝不能产生外部副作用。</p>
     */
    @Test
    void shouldPermanentlyRejectMalformedJsonWithoutCallingDependencies() {
        Fixture fixture = fixture();

        AgentAutopilotRecoveryExecutionResult result = fixture.service.consume("{not-valid-json");

        assertThat(result.status()).isEqualTo("REJECTED");
        assertThat(result.reasonCode()).isEqualTo("AUTOPILOT_TRIGGER_JSON_INVALID");
        verifyNoInteractions(fixture.triggerVerifier, fixture.pythonClient, fixture.executionService,
                fixture.dataSyncClient);
    }

    /**
     * 校验器拒绝表示持久 session、run 或授权事实没有授予该事件执行权限。
     *
     * <p>这是确定性业务结果，不是可用性故障。测试证明服务返回 {@code REJECTED}，并且在调用 Python
     * 规划器或执行服务前停止。</p>
     */
    @Test
    void shouldRejectAuthorizationFailureWithoutCallingPython() {
        Fixture fixture = fixture();
        PlatformBusinessException rejection = new PlatformBusinessException(
                PlatformErrorCode.FORBIDDEN, "AUTOPILOT_DELEGATION_INACTIVE");
        when(fixture.triggerVerifier.verify(any())).thenThrow(rejection);

        AgentAutopilotRecoveryExecutionResult result = fixture.service.consume(validPayload());

        assertThat(result.status()).isEqualTo("REJECTED");
        assertThat(result.reasonCode()).isEqualTo("AUTOPILOT_DELEGATION_INACTIVE");
        verify(fixture.triggerVerifier).verify(any());
        verify(fixture.dataSyncClient).recordTriggerResult(result);
        verifyNoInteractions(fixture.pythonClient, fixture.executionService);
    }

    /**
     * 校验通过后的 Python 规划器故障表示持久处理尚未完成，Kafka 必须重试。
     *
     * <p>测试故意使用 {@link PlatformBusinessException}：旧的宽泛捕获曾把该下游错误错误地当成授权拒绝。
     * 断言同一个异常对象继续抛出，可以证明 consumer 服务不能过早确认消息。</p>
     */
    @Test
    void shouldPropagatePythonInfrastructureFailureForKafkaRetry() {
        Fixture fixture = fixture();
        AgentAutopilotVerifiedRecoveryTrigger verified = mock(AgentAutopilotVerifiedRecoveryTrigger.class);
        PlatformBusinessException failure = new PlatformBusinessException(
                PlatformErrorCode.BUSINESS_STATE_CONFLICT, "PYTHON_AUTOPILOT_PLANNER_UNAVAILABLE");
        when(fixture.triggerVerifier.verify(any())).thenReturn(verified);
        when(fixture.pythonClient.plan(verified)).thenThrow(failure);

        assertThatThrownBy(() -> fixture.service.consume(validPayload())).isSameAs(failure);

        verify(fixture.triggerVerifier).verify(any());
        verify(fixture.pythonClient).plan(verified);
        verify(fixture.executionService, never()).execute(any(), any());
        verifyNoInteractions(fixture.dataSyncClient);
        verify(fixture.metrics).recordPlanningFailed();
    }

    /**
     * 即使测试替身绕过 HTTP 客户端校验，Python 规划协议之外的状态仍属于可重试合同故障。
     *
     * <p>生产客户端会在返回前拒绝该状态，但 consumer 继续保留防御边界，防止未来客户端重构把未知规划
     * 状态静默计为成功。记录 {@code FAILED} 后抛出异常会让 Kafka offset 保持未确认，并阻止任意状态
     * 进入执行或持久结果回调。</p>
     */
    @Test
    void shouldPropagateUnknownPlannerStatusForKafkaRetryWithoutCountingSuccess() {
        Fixture fixture = fixture();
        AgentAutopilotVerifiedRecoveryTrigger verified = mock(AgentAutopilotVerifiedRecoveryTrigger.class);
        AgentAutopilotRecoveryPlanResponse response = mock(AgentAutopilotRecoveryPlanResponse.class);
        when(fixture.triggerVerifier.verify(any())).thenReturn(verified);
        when(fixture.pythonClient.plan(verified)).thenReturn(response);
        when(response.status()).thenReturn("UNRECOGNIZED");

        assertThatThrownBy(() -> fixture.service.consume(validPayload()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("PYTHON_AUTOPILOT_RECOVERY_PLANNER_STATUS_INVALID");

        verify(fixture.metrics).recordPlanningFailed();
        verify(fixture.metrics, never()).recordPlanningSucceeded();
        verify(fixture.executionService, never()).execute(any(), any());
        verifyNoInteractions(fixture.dataSyncClient);
    }

    /**
     * Python 返回计划后的 Java 或 data-sync 执行故障同样可重试，不属于确定性拒绝。
     *
     * <p>该阶段可能发生网络、持久化或下游响应故障。继续抛出异常可让 Spring Kafka 重投同一事件，下游
     * 回执和乐观锁设计则保证该重试幂等。</p>
     */
    @Test
    void shouldPropagateDownstreamInfrastructureFailureForKafkaRetry() {
        Fixture fixture = fixture();
        AgentAutopilotVerifiedRecoveryTrigger verified = mock(AgentAutopilotVerifiedRecoveryTrigger.class);
        AgentAutopilotRecoveryPlanResponse response = mock(AgentAutopilotRecoveryPlanResponse.class);
        PlatformBusinessException failure = new PlatformBusinessException(
                PlatformErrorCode.BUSINESS_STATE_CONFLICT, "DATA_SYNC_AUTOPILOT_UNAVAILABLE");
        when(fixture.triggerVerifier.verify(any())).thenReturn(verified);
        when(fixture.pythonClient.plan(verified)).thenReturn(response);
        when(response.status()).thenReturn("CANDIDATE_READY");
        when(fixture.executionService.execute(verified, response)).thenThrow(failure);

        assertThatThrownBy(() -> fixture.service.consume(validPayload())).isSameAs(failure);

        verify(fixture.triggerVerifier).verify(any());
        verify(fixture.pythonClient).plan(verified);
        verify(fixture.executionService).execute(verified, response);
        verifyNoInteractions(fixture.dataSyncClient);
    }

    /**
     * Python 人工关注响应属于可持久的处理终态，不是基础设施异常。
     *
     * <p>执行服务会把 Python 响应转换成低敏结果。consumer 必须在返回前写回该结果，使 data-sync 能够
     * 关闭触发回执，Kafka 随后才能安全确认消息。</p>
     */
    @Test
    void shouldRecordPythonAttentionResultBeforeAcknowledging() {
        Fixture fixture = fixture();
        AgentAutopilotVerifiedRecoveryTrigger verified = mock(AgentAutopilotVerifiedRecoveryTrigger.class);
        AgentAutopilotRecoveryPlanResponse response = mock(AgentAutopilotRecoveryPlanResponse.class);
        AgentAutopilotRecoveryExecutionResult attention = new AgentAutopilotRecoveryExecutionResult(
                "event-1", "ATTENTION_REQUIRED", "PYTHON_RECOVERY_REQUIRES_REVIEW", 71L, 41L);
        when(fixture.triggerVerifier.verify(any())).thenReturn(verified);
        when(fixture.pythonClient.plan(verified)).thenReturn(response);
        when(response.status()).thenReturn("ATTENTION_REQUIRED");
        when(fixture.executionService.execute(verified, response)).thenReturn(attention);

        AgentAutopilotRecoveryExecutionResult result = fixture.service.consume(validPayload());

        assertThat(result).isEqualTo(attention);
        verify(fixture.dataSyncClient).recordTriggerResult(attention);
    }

    /**
     * 确定性证据拒绝只有在 data-sync 持久记录固定结果后才能安全确认。
     *
     * <p>执行服务已经把证据不匹配归类为 {@code REJECTED}。本 consumer 测试验证边界的后一半：只有
     * 调用持久回调后才能正常返回，这才表示 Spring Kafka 可以提交当前记录，而不是送入重试或 DLT。</p>
     */
    @Test
    void shouldRecordEvidenceRejectionBeforeAcknowledging() {
        Fixture fixture = fixture();
        AgentAutopilotVerifiedRecoveryTrigger verified = mock(AgentAutopilotVerifiedRecoveryTrigger.class);
        AgentAutopilotRecoveryPlanResponse response = mock(AgentAutopilotRecoveryPlanResponse.class);
        AgentAutopilotRecoveryExecutionResult rejection = new AgentAutopilotRecoveryExecutionResult(
                "event-1", "REJECTED", "AUTOPILOT_EVIDENCE_DIGEST_MISMATCH", null, 41L);
        when(fixture.triggerVerifier.verify(any())).thenReturn(verified);
        when(fixture.pythonClient.plan(verified)).thenReturn(response);
        when(response.status()).thenReturn("ATTENTION_REQUIRED");
        when(fixture.executionService.execute(verified, response)).thenReturn(rejection);

        AgentAutopilotRecoveryExecutionResult result = fixture.service.consume(validPayload());

        assertThat(result).isEqualTo(rejection);
        verify(fixture.dataSyncClient).recordTriggerResult(rejection);
        verify(fixture.metrics).recordAttentionRequired();
    }

    /**
     * 结果回调属于持久处理的一部分，因此回调失败必须启动 Kafka 重试，不能 ACK。
     *
     * <p>测试使用 {@link IllegalStateException}，因为损坏的结果 envelope 和空 Python 响应属于技术合同
     * 故障。RestClient 失败也遵守同一规则：data-sync 接受结果回调前不允许正常返回。</p>
     */
    @Test
    void shouldPropagateResultWriteBackFailureForKafkaRetry() {
        Fixture fixture = fixture();
        AgentAutopilotVerifiedRecoveryTrigger verified = mock(AgentAutopilotVerifiedRecoveryTrigger.class);
        AgentAutopilotRecoveryPlanResponse response = mock(AgentAutopilotRecoveryPlanResponse.class);
        AgentAutopilotRecoveryExecutionResult result = new AgentAutopilotRecoveryExecutionResult(
                "event-1", "RECOVERY_STARTED", "AUTOPILOT_FAILED_OBJECTS_REQUEUED", 71L, 41L);
        IllegalStateException failure = new IllegalStateException("AUTOPILOT_DATA_SYNC_TRIGGER_RESULT_ENVELOPE_INVALID");
        when(fixture.triggerVerifier.verify(any())).thenReturn(verified);
        when(fixture.pythonClient.plan(verified)).thenReturn(response);
        when(response.status()).thenReturn("CANDIDATE_READY");
        when(fixture.executionService.execute(verified, response)).thenReturn(result);
        doThrow(failure).when(fixture.dataSyncClient).recordTriggerResult(result);

        assertThatThrownBy(() -> fixture.service.consume(validPayload())).isSameAs(failure);

        verify(fixture.dataSyncClient).recordTriggerResult(result);
    }

    /**
     * 提供 Jackson 能够反序列化成触发记录的最小 JSON 文档。
     *
     * <p>这些测试隔离的是 consumer 服务的重试边界，而不是校验器的详细合同检查。mock 校验器决定是否
     * 接受这份稀疏事件，使每个测试只聚焦一条规则。</p>
     */
    private String validPayload() {
        return "{\"eventId\":\"event-1\"}";
    }

    /**
     * 使用真实 JSON 解析和 mock 外部协作者构建隔离的 consumer 服务。
     *
     * <p>保留真实 {@link ObjectMapper} 用于验证损坏 JSON 分支；对授权校验、Python 规划和 Java/data-sync
     * 执行使用 mock，可以在不启动 Spring Context、HTTP 服务和 Kafka 的情况下，精确检查决定确认行为的
     * 异常传播。</p>
     */
    private Fixture fixture() {
        AgentAutopilotRecoveryTriggerVerifier triggerVerifier =
                mock(AgentAutopilotRecoveryTriggerVerifier.class);
        AgentAutopilotRecoveryPythonClient pythonClient = mock(AgentAutopilotRecoveryPythonClient.class);
        AgentAutopilotRecoveryExecutionService executionService =
                mock(AgentAutopilotRecoveryExecutionService.class);
        AgentAutopilotRecoveryDataSyncClient dataSyncClient =
                mock(AgentAutopilotRecoveryDataSyncClient.class);
        AgentAutopilotRecoveryMetrics metrics = mock(AgentAutopilotRecoveryMetrics.class);
        return new Fixture(
                new AgentAutopilotRecoveryTriggerConsumerService(
                        new ObjectMapper(), triggerVerifier, pythonClient, executionService, dataSyncClient, metrics),
                triggerVerifier,
                pythonClient,
                executionService,
                dataSyncClient,
                metrics);
    }

    private record Fixture(
            AgentAutopilotRecoveryTriggerConsumerService service,
            AgentAutopilotRecoveryTriggerVerifier triggerVerifier,
            AgentAutopilotRecoveryPythonClient pythonClient,
            AgentAutopilotRecoveryExecutionService executionService,
            AgentAutopilotRecoveryDataSyncClient dataSyncClient,
            AgentAutopilotRecoveryMetrics metrics) {
    }
}
