/**
 * @Author : Cui
 * @Date: 2026/08/11 20:45
 * @Description DataSmart Govern Backend - AgentAutopilotRecoveryTriggerConsumerService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.autopilot;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Applies a Kafka recovery trigger to the Autopilot planning and execution flow.
 *
 * <p>Invalid JSON and rejected authorization are permanent message outcomes. Python, HTTP, Kafka,
 * database, and other downstream infrastructure failures intentionally remain visible to Spring Kafka
 * so its retry and dead-letter policy can decide how to redeliver the message.</p>
 */
@Service
@RequiredArgsConstructor
public class AgentAutopilotRecoveryTriggerConsumerService {

    private final ObjectMapper objectMapper;
    private final AgentAutopilotRecoveryTriggerVerifier triggerVerifier;
    private final AgentAutopilotRecoveryPythonClient pythonClient;
    private final AgentAutopilotRecoveryExecutionService executionService;
    private final AgentAutopilotRecoveryDataSyncClient dataSyncClient;
    private final AgentAutopilotRecoveryMetrics metrics;

    /**
     * Consumes one data-sync durable-outbox recovery trigger and separates permanent rejection from a retryable delivery failure.
     *
     * <p>For a beginner, the key Kafka rule is that a listener normally commits its offset after this method returns,
     * while an exception reaches Spring Kafka's retry or dead-letter policy. Malformed JSON has no usable event ID, so
     * it is the only permanent poison message that returns without a data-sync callback. A parsed event that the
     * verifier permanently rejects, a Python attention outcome, or a normal execution outcome is first written back
     * through {@link AgentAutopilotRecoveryDataSyncClient#recordTriggerResult(AgentAutopilotRecoveryExecutionResult)}.
     * Only after that durable callback succeeds may this method return normally and allow the offset to be committed.</p>
     *
     * <p>Once verification succeeds, the Python planner and Java/data-sync execution path are external dependency
     * boundaries. Timeouts, unavailable services, invalid downstream responses, and persistence failures must not be
     * caught here. The same is true for a failed result callback. Letting those exceptions propagate tells the Kafka
     * listener that durable processing is incomplete and allows a later retry. The verifier call has its own narrow
     * {@code try/catch} for exactly this reason: do not accidentally classify a downstream
     * {@link PlatformBusinessException} as a permanent authorization rejection.</p>
     *
     * <p>The Python client validates the HTTP response before returning it, and this method repeats the finite-status
     * check before recording planning metrics. That defensive check ensures an accidental client bypass cannot count an
     * unknown status as planning success. An unsupported status is a technical contract failure, so it increments
     * planning failure and escapes to Kafka retry without invoking execution or the durable callback.</p>
     *
     * @param payload raw JSON text published by the data-sync durable outbox
     * @return a low-sensitivity terminal result for a permanent input or authorization rejection, or the execution result on success
     */
    public AgentAutopilotRecoveryExecutionResult consume(String payload) {
        AgentAutopilotRecoveryTriggerEvent event;
        try {
            event = objectMapper.readValue(payload, AgentAutopilotRecoveryTriggerEvent.class);
        } catch (JsonProcessingException exception) {
            metrics.recordTriggerRejected();
            return new AgentAutopilotRecoveryExecutionResult(
                    null, "REJECTED", "AUTOPILOT_TRIGGER_JSON_INVALID", null, null);
        }

        AgentAutopilotVerifiedRecoveryTrigger verified;
        try {
            verified = triggerVerifier.verify(event);
        } catch (PlatformBusinessException exception) {
            AgentAutopilotRecoveryExecutionResult result = new AgentAutopilotRecoveryExecutionResult(
                    event.eventId(), "REJECTED", exception.getMessage(), null, event.currentExecutionId());
            dataSyncClient.recordTriggerResult(result);
            metrics.recordTriggerRejected();
            return result;
        }

        metrics.recordTriggerAccepted();
        AgentAutopilotRecoveryPlanResponse response;
        try {
            response = pythonClient.plan(verified);
            recordPlanningOutcome(response);
        } catch (RuntimeException exception) {
            metrics.recordPlanningFailed();
            throw exception;
        }
        AgentAutopilotRecoveryExecutionResult result = executionService.execute(verified, response);
        dataSyncClient.recordTriggerResult(result);
        recordDurableExecutionOutcome(result.status());
        return result;
    }

    /**
     * Records the planner metric only for statuses that belong to the versioned Java/Python recovery protocol.
     *
     * <p>The input is the response returned by the Python client. {@code CANDIDATE_READY} and
     * {@code ATTENTION_REQUIRED} mean Python completed planning and are counted as successful planning attempts;
     * {@code FAILED} is a completed but unsuccessful planning attempt and is counted as failed. Any null response or
     * other status is an invalid technical contract, not a new business state. This method then throws a fixed
     * {@link IllegalStateException} so {@link #consume(String)} can leave the Kafka record unacknowledged for bounded
     * retry.</p>
     *
     * <p>This method has no durable side effect beyond its low-cardinality metric. It does not execute recovery or
     * call data-sync, which keeps a malformed planner outcome from creating a callback that would incorrectly close a
     * recoverable Kafka delivery.</p>
     *
     * @param response planner response received after the verified trigger was sent to Python
     * @throws IllegalStateException when the response is missing or its status is outside the finite protocol set
     */
    private void recordPlanningOutcome(AgentAutopilotRecoveryPlanResponse response) {
        String status = normalizeStatus(response == null ? null : response.status());
        if ("CANDIDATE_READY".equals(status) || "ATTENTION_REQUIRED".equals(status)) {
            metrics.recordPlanningSucceeded();
            return;
        }
        if ("FAILED".equals(status)) {
            metrics.recordPlanningFailed();
            return;
        }
        throw new IllegalStateException("PYTHON_AUTOPILOT_RECOVERY_PLANNER_STATUS_INVALID");
    }

    /**
     * 在 data-sync 已持久接受 callback 后记录当前自动执行结果。
     *
     * <p>只有 {@code RECOVERY_STARTED} 和需要关注的有限状态进入 Counter；其他状态仍保存在 durable outbox，
     * 不会被强行归类。把指标写入放在 callback 之后，可避免 HTTP 回写失败时提前报告一个尚未持久确认的启动结果。
     * 该方法只做低基数指标更新，不修改 recovery case 或 Kafka offset。</p>
     *
     * @param status 已由执行服务产生并被 data-sync 接受的有限状态
     */
    private void recordDurableExecutionOutcome(String status) {
        String normalized = normalizeStatus(status);
        if ("RECOVERY_STARTED".equals(normalized)) {
            metrics.recordAutomaticRecoveryStarted();
            return;
        }
        if ("ATTENTION_REQUIRED".equals(normalized)
                || "WAITING_APPROVAL".equals(normalized)
                || "REJECTED".equals(normalized)) {
            metrics.recordAttentionRequired();
        }
    }

    /**
     * 把跨服务状态文本转换成内部比较形式，不把原值作为指标标签。
     *
     * @param status 可为空的响应状态
     * @return 去空白、转大写并统一连字符后的状态；空值返回空字符串
     */
    private String normalizeStatus(String status) {
        return status == null ? "" : status.trim().toUpperCase().replace('-', '_');
    }
}
