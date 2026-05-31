/**
 * @Author : Cui
 * @Date: 2026/05/31 23:45
 * @Description DataSmart Govern Backend - AgentAsyncTaskCommandKafkaMetricsService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.event;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Agent 异步工具命令 Kafka 消费指标组件。
 *
 * <p>该组件把 Micrometer 指标写入逻辑从 listener/handler 中拆出来。
 * 对商业化 Agent 平台而言，Kafka 命令入口是 durable action pipeline 的关键入口：
 * 如果 accepted/rejected、失败类型、DLQ 候选、处理耗时不可见，后台 worker 打开后很难判断系统是健康运行、
 * 被坏消息卡住、被上游错误 payload 打爆，还是业务消费服务出现系统性异常。</p>
 *
 * <p>标签设计原则：
 * 只使用 result、failureType、duplicate、taskCreated、topic 这类低基数字段。
 * 不把 commandId、taskId、traceId、offset 放进指标标签，因为这些值几乎每条消息都不同，会造成高基数时序，
 * 让 Prometheus/Grafana 存储和查询压力暴涨。需要精确定位单条消息时，应使用诊断快照和日志。</p>
 */
@Component
@RequiredArgsConstructor
public class AgentAsyncTaskCommandKafkaMetricsService {

    private static final String METRIC_PREFIX = "datasmart_task_agent_async_command_kafka";
    private static final String UNKNOWN = "UNKNOWN";

    private final MeterRegistry meterRegistry;

    /**
     * 记录成功消费的命令。
     *
     * @param result handler 返回的处理摘要。
     * @param metadata Kafka record 低敏元数据。
     * @param duration handler 从开始解析到业务消费完成的耗时。
     */
    public void recordAccepted(AgentAsyncTaskCommandKafkaMessageHandler.AgentAsyncTaskCommandKafkaHandleResult result,
                               AgentAsyncTaskCommandKafkaRecordMetadata metadata,
                               Duration duration) {
        Counter.builder(METRIC_PREFIX + "_handled_total")
                .description("task-management Agent 异步命令 Kafka 消费处理次数")
                .tag("result", "ACCEPTED")
                .tag("failureType", "NONE")
                .tag("duplicate", String.valueOf(result != null && result.duplicate()))
                .tag("taskCreated", String.valueOf(result != null && result.taskCreated()))
                .tag("topic", topic(metadata))
                .register(meterRegistry)
                .increment();
        recordDuration("ACCEPTED", "NONE", metadata, duration);
    }

    /**
     * 记录被拒绝或处理异常的命令。
     *
     * @param failureType 稳定失败类型。
     * @param metadata Kafka record 低敏元数据。
     * @param duration handler 从开始解析到失败分类完成的耗时。
     * @param dlqCandidate 当前配置下是否应进入 DLQ 候选。
     */
    public void recordRejected(AgentAsyncTaskCommandKafkaFailureType failureType,
                               AgentAsyncTaskCommandKafkaRecordMetadata metadata,
                               Duration duration,
                               boolean dlqCandidate) {
        String normalizedFailureType = failureType == null ? UNKNOWN : failureType.name();
        Counter.builder(METRIC_PREFIX + "_handled_total")
                .description("task-management Agent 异步命令 Kafka 消费处理次数")
                .tag("result", "REJECTED")
                .tag("failureType", normalizedFailureType)
                .tag("duplicate", "false")
                .tag("taskCreated", "false")
                .tag("topic", topic(metadata))
                .register(meterRegistry)
                .increment();
        if (dlqCandidate) {
            Counter.builder(METRIC_PREFIX + "_dlq_candidate_total")
                    .description("task-management Agent 异步命令 Kafka 死信候选次数")
                    .tag("failureType", normalizedFailureType)
                    .tag("topic", topic(metadata))
                    .register(meterRegistry)
                    .increment();
        }
        recordDuration("REJECTED", normalizedFailureType, metadata, duration);
    }

    private void recordDuration(String result,
                                String failureType,
                                AgentAsyncTaskCommandKafkaRecordMetadata metadata,
                                Duration duration) {
        if (duration == null || duration.isNegative()) {
            return;
        }
        Timer.builder(METRIC_PREFIX + "_handle_duration")
                .description("task-management Agent 异步命令 Kafka 单条处理耗时")
                .tag("result", result)
                .tag("failureType", failureType)
                .tag("topic", topic(metadata))
                .register(meterRegistry)
                .record(duration);
    }

    private String topic(AgentAsyncTaskCommandKafkaRecordMetadata metadata) {
        if (metadata == null || metadata.topic() == null || metadata.topic().isBlank()) {
            return UNKNOWN;
        }
        return metadata.topic();
    }
}
