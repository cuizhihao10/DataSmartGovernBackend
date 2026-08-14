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

import java.util.Set;

/**
 * 将 Kafka 恢复触发事件接入 Autopilot 规划与执行流程。
 *
 * <p>无效 JSON 和授权拒绝属于确定性消息结果。Python、HTTP、Kafka、数据库以及其他下游基础设施
 * 故障会有意继续向 Spring Kafka 暴露，使其重试和死信策略决定如何重新投递消息。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentAutopilotRecoveryTriggerConsumerService {

    private static final Set<String> RETRYABLE_MODEL_FAILURES = Set.of(
            "MODEL_TIMEOUT|MODEL_PROVIDER_TRANSPORT",
            "MODEL_PROVIDER_ERROR|MODEL_PROVIDER_TRANSPORT");

    private final ObjectMapper objectMapper;
    private final AgentAutopilotRecoveryTriggerVerifier triggerVerifier;
    private final AgentAutopilotRecoveryPythonClient pythonClient;
    private final AgentAutopilotRecoveryExecutionService executionService;
    private final AgentAutopilotRecoveryDataSyncClient dataSyncClient;
    private final AgentAutopilotRecoveryMetrics metrics;

    /**
     * 消费一条 data-sync 持久 outbox 恢复触发事件，并区分确定性拒绝与可重试投递失败。
     *
     * <p>对初学者而言，Kafka 的关键规则是：监听方法正常返回后通常会提交 offset；如果抛出异常，消息
     * 才会进入 Spring Kafka 的重试或死信策略。格式损坏的 JSON 没有可用 event ID，因此它是唯一一种
     * 可以不调用 data-sync 回调就返回的确定性毒消息。对于解析成功但被校验器永久拒绝的事件、Python
     * 返回的人工关注结果或正常执行结果，必须先通过
     * {@link AgentAutopilotRecoveryDataSyncClient#recordTriggerResult(AgentAutopilotRecoveryExecutionResult)}
     * 写回持久控制面，只有回调成功后本方法才能正常返回并允许提交 offset。</p>
     *
     * <p>校验通过后，Python 规划器以及 Java/data-sync 执行链路属于外部依赖边界。超时、服务不可用、
     * 下游响应无效和持久化失败不能在这里吞掉，结果回调失败也同样不能吞掉。让这些异常继续抛出，
     * 才能告诉 Kafka 监听器持久处理尚未完成，并允许后续重试。校验器调用单独保留窄范围
     * {@code try/catch} 正是为了避免把下游的 {@link PlatformBusinessException} 误判为永久授权拒绝。</p>
     *
     * <p>Python 客户端在返回前已经校验 HTTP 响应，本方法在记录规划指标前还会重复检查有限状态集合。
     * 这道防御性校验可以避免客户端未来被错误绕过时把未知状态计为规划成功。未支持的状态属于技术
     * 合同故障，因此先增加规划失败指标，再抛出异常进入 Kafka 重试，不调用执行服务或持久回调。</p>
     *
     * @param payload data-sync 持久 outbox 发布的原始 JSON 文本
     * @return 确定性输入/授权拒绝时的低敏终态结果，或成功执行时的执行结果
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
     * 只为版本化 Java/Python 恢复协议中的状态记录规划指标。
     *
     * <p>输入是 Python 客户端返回的响应。{@code CANDIDATE_READY} 与 {@code ATTENTION_REQUIRED} 表示
     * Python 已经完成规划，计为规划尝试成功；{@code FAILED} 表示规划尝试已结束但失败，计为失败。空响应
     * 或其他状态属于技术合同无效，不是新的业务状态。本方法随后抛出固定的
     * {@link IllegalStateException}，使 {@link #consume(String)} 不确认 Kafka 记录并进入有界重试。</p>
     *
     * <p>除低基数指标外，本方法没有持久副作用。它不会执行恢复或调用 data-sync，因此格式错误的规划
     * 结果不会创建错误关闭可恢复 Kafka 投递的回调。</p>
     *
     * @param response 已校验触发事件发送到 Python 后收到的规划响应
     * @throws IllegalStateException 响应缺失或状态不在有限协议集合中时抛出
     */
    private void recordPlanningOutcome(AgentAutopilotRecoveryPlanResponse response) {
        String status = normalizeStatus(response == null ? null : response.status());
        if ("CANDIDATE_READY".equals(status) || "ATTENTION_REQUIRED".equals(status)) {
            metrics.recordPlanningSucceeded();
            return;
        }
        if ("FAILED".equals(status)) {
            if (retryableModelFailure(response)) {
                /*
                 * 不在这里写回 data-sync，也不把模型失败伪装成业务终态。固定异常继续到 Kafka 监听器后，
                 * Spring Kafka 才能按已配置次数重投同一 eventId；Python checkpoint 会保留失败尝试，
                 * 但允许下一次投递重新调用模型。
                 */
                throw new IllegalStateException("PYTHON_AUTOPILOT_RECOVERY_PLANNER_TRANSIENT_FAILURE");
            }
            metrics.recordPlanningFailed();
            return;
        }
        throw new IllegalStateException("PYTHON_AUTOPILOT_RECOVERY_PLANNER_STATUS_INVALID");
    }

    /**
     * 判断 Python 返回的模型故障是否属于允许 Kafka 有界重投的瞬态类别。
     *
     * <p>输入只包含 Python 适配器生成的固定低敏枚举，不包含异常消息、Provider URL 或响应正文。只有
     * 超时以及 Provider 传输层故障进入白名单；JSON 解析、响应契约、越权输出、结果读取和适配器错误
     * 都是确定性失败，再次投递不会自然修复，因此返回 {@code false}。</p>
     *
     * <p>是否可重试由 Java 平台目录重新计算，而不是采信 Python 布尔值或模型输出。这可以防止模型通过
     * 自行声明 retryable 扩大 Loop 次数；即使命中白名单，Spring Kafka 仍受固定 attempts、退避和 DLT
     * 约束，不会形成无限循环。</p>
     *
     * @param response 已通过 Python 客户端版本、范围和字段白名单校验的规划响应
     * @return 固定原因码与来源码组合命中瞬态白名单时返回 {@code true}
     */
    private boolean retryableModelFailure(AgentAutopilotRecoveryPlanResponse response) {
        if (response == null || !"RECOVERY_PLANNING_MODEL_FAILED".equals(response.reasonCode())) {
            return false;
        }
        String reasonCode = response.modelFailureReasonCode() == null
                ? "" : response.modelFailureReasonCode().trim().toUpperCase();
        String source = response.modelFailureSource() == null
                ? "" : response.modelFailureSource().trim().toUpperCase();
        return RETRYABLE_MODEL_FAILURES.contains(reasonCode + "|" + source);
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
