/**
 * @Author : Cui
 * @Date: 2026/08/11 22:45
 * @Description DataSmart Govern Backend - AgentAutopilotRecoveryMetrics.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.autopilot;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Agent Runtime 侧 Autopilot Recovery 的低基数 Micrometer 指标入口。
 *
 * <p>“低基数”表示一个标签只能取少量、预先定义的值。本类因此只提供语义明确的方法，调用方不能传入
 * tenantId、taskId、executionId、eventId、异常消息或模型 reasonCode 作为标签。这样既避免把业务标识和
 * 敏感内容暴露给 Prometheus，也避免海量时间序列拖垮监控系统。</p>
 *
 * <p>这些 Counter 统计的是处理尝试而不是全局唯一事件。Kafka 至少一次投递可能使同一事件重复计数，
 * 精确的业务事实仍应查询 data-sync 的 recovery case、receipt 和 trigger outbox。指标用于发现趋势和告警，
 * 不能替代持久化审计。</p>
 */
@Component
public class AgentAutopilotRecoveryMetrics {

    private static final String METRIC_PREFIX = "datasmart_agent_autopilot_recovery";

    private final MeterRegistry meterRegistry;

    /**
     * 创建指标组件并绑定当前应用的 Micrometer 注册表。
     *
     * @param meterRegistry Spring Boot 管理的指标注册表；Prometheus registry 会从这里导出 Counter
     */
    public AgentAutopilotRecoveryMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /** 记录通过 JSON、会话、Run、授权范围和时限验证的 Kafka trigger。 */
    public void recordTriggerAccepted() {
        increment("trigger_total", "outcome", "ACCEPTED",
                "Agent Autopilot recovery trigger handling attempts");
    }

    /** 记录无效 JSON 或无法通过可信授权验证的 Kafka trigger。 */
    public void recordTriggerRejected() {
        increment("trigger_total", "outcome", "REJECTED",
                "Agent Autopilot recovery trigger handling attempts");
    }

    /** 记录 Python Recovery Specialist 已返回可供 Java 继续判断的结果。 */
    public void recordPlanningSucceeded() {
        increment("planning_total", "outcome", "SUCCEEDED",
                "Agent Autopilot recovery planning attempts");
    }

    /** 记录 Python 调用异常或 Specialist 明确返回 FAILED。 */
    public void recordPlanningFailed() {
        increment("planning_total", "outcome", "FAILED",
                "Agent Autopilot recovery planning attempts");
    }

    /** 记录 Java 已独立复算并接受候选证据。 */
    public void recordEvidenceAccepted() {
        increment("evidence_total", "outcome", "ACCEPTED",
                "Agent Autopilot recovery evidence verification attempts");
    }

    /** 记录候选证据因范围、摘要、来源或新鲜度问题被拒绝。 */
    public void recordEvidenceRejected() {
        increment("evidence_total", "outcome", "REJECTED",
                "Agent Autopilot recovery evidence verification attempts");
    }

    /** 记录已得到 durable callback 确认的无人值守恢复启动结果。 */
    public void recordAutomaticRecoveryStarted() {
        increment("execution_total", "outcome", "RECOVERY_STARTED",
                "Agent Autopilot recovery execution outcomes");
    }

    /** 记录已得到 durable callback 确认、需要等待审批或人工排查的结果。 */
    public void recordAttentionRequired() {
        increment("execution_total", "outcome", "ATTENTION_REQUIRED",
                "Agent Autopilot recovery execution outcomes");
    }

    /** 记录 consumer 重试预算耗尽并进入 DLT。 */
    public void recordDeadLettered() {
        increment("delivery_total", "outcome", "DEAD_LETTERED",
                "Agent Autopilot recovery Kafka delivery outcomes");
    }

    /**
     * 使用固定 metric、tag key 和 tag value 增加一个 Counter。
     *
     * <p>该方法保持 private，防止其他组件绕过上面的有限方法，把任意业务值拼成标签。Micrometer 对相同
     * 名称和标签组合会复用已注册 Counter，因此每次调用不会创建新的高基数对象。</p>
     *
     * @param metricSuffix 固定指标后缀，由本类源码控制
     * @param tagKey 固定标签名，由本类源码控制
     * @param tagValue 固定标签值，由本类源码控制
     * @param description 指标帮助文本
     */
    private void increment(String metricSuffix,
                           String tagKey,
                           String tagValue,
                           String description) {
        Counter.builder(METRIC_PREFIX + "_" + metricSuffix)
                .description(description)
                .tag(tagKey, tagValue)
                .register(meterRegistry)
                .increment();
    }
}
