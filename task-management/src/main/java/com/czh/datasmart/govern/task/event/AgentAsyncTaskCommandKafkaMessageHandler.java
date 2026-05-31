/**
 * @Author : Cui
 * @Date: 2026/05/31 17:20
 * @Description DataSmart Govern Backend - AgentAsyncTaskCommandKafkaMessageHandler.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.event;

import com.czh.datasmart.govern.task.config.AgentAsyncTaskCommandKafkaProperties;
import com.czh.datasmart.govern.task.controller.dto.AgentAsyncTaskCommandConsumeResponse;
import com.czh.datasmart.govern.task.controller.dto.AgentAsyncTaskCommandRequest;
import com.czh.datasmart.govern.task.service.AgentAsyncTaskCommandConsumerService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Agent 异步工具命令 Kafka 消息处理器。
 *
 * <p>该组件位于 Kafka listener 与业务消费服务之间，专门处理“传输层消息”到“业务命令 DTO”的转换：
 * 1. 控制单条 payload 大小；
 * 2. 将 JSON 字符串解析为 {@link AgentAsyncTaskCommandRequest}；
 * 3. 委托 {@link AgentAsyncTaskCommandConsumerService} 做协议校验、Inbox 去重和任务创建；
 * 4. 根据配置决定非法消息是抛出异常等待 Kafka 重试，还是在本地联调时记录后跳过。</p>
 *
 * <p>为什么不把这些逻辑直接写进 listener：
 * listener 的职责应该非常薄，只负责 Spring Kafka 接入。解析、错误策略和业务调用拆出来后，
 * 单元测试不需要启动 Kafka 容器，未来接入 DLQ、指标、手动 replay 或批量消费时也能复用这里的处理语义。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentAsyncTaskCommandKafkaMessageHandler {

    private final AgentAsyncTaskCommandKafkaProperties properties;
    private final AgentAsyncTaskCommandConsumerService consumerService;
    private final ObjectMapper objectMapper;
    private final AgentAsyncTaskCommandKafkaDiagnosticsService diagnosticsService;

    /**
     * 处理一条 Kafka payload。
     *
     * @param payload Kafka value，约定为 UTF-8 JSON 字符串。
     * @return 处理摘要，供 listener 记录审计友好的日志。
     */
    public AgentAsyncTaskCommandKafkaHandleResult handle(String payload) {
        try {
            ensurePayloadAllowed(payload);
            AgentAsyncTaskCommandRequest request = objectMapper.readValue(payload, AgentAsyncTaskCommandRequest.class);
            AgentAsyncTaskCommandConsumeResponse response = consumerService.consume(request);
            return AgentAsyncTaskCommandKafkaHandleResult.accepted(response);
        } catch (KafkaCommandPayloadRejectedException exception) {
            return handleRejected(exception.failureType(), exception.getMessage(), payload, exception);
        } catch (JsonProcessingException exception) {
            return handleRejected(
                    AgentAsyncTaskCommandKafkaFailureType.INVALID_JSON,
                    "Agent 异步命令 Kafka payload 不是合法 JSON",
                    payload,
                    new IllegalArgumentException("Agent 异步命令 Kafka payload 不是合法 JSON", exception)
            );
        } catch (IllegalArgumentException exception) {
            return handleRejected(
                    AgentAsyncTaskCommandKafkaFailureType.CONSUMER_REJECTED,
                    exception.getMessage(),
                    payload,
                    exception
            );
        } catch (RuntimeException exception) {
            return handleRejected(
                    AgentAsyncTaskCommandKafkaFailureType.CONSUMER_EXCEPTION,
                    exception.getMessage(),
                    payload,
                    exception
            );
        }
    }

    /**
     * 校验 payload 大小。
     *
     * <p>这里按 UTF-8 字节数判断，而不是 Java 字符数。Kafka、网络传输和数据库 payload 限制通常都按字节计算，
     * 中文字符、emoji 或转义内容都可能让字符数与真实传输大小不一致。</p>
     */
    private void ensurePayloadAllowed(String payload) {
        if (payload == null || payload.isBlank()) {
            throw new KafkaCommandPayloadRejectedException(
                    AgentAsyncTaskCommandKafkaFailureType.EMPTY_PAYLOAD,
                    "Agent 异步命令 Kafka payload 不能为空"
            );
        }
        int payloadBytes = payload.getBytes(StandardCharsets.UTF_8).length;
        if (payloadBytes > Math.max(1, properties.getMaxPayloadBytes())) {
            throw new KafkaCommandPayloadRejectedException(
                    AgentAsyncTaskCommandKafkaFailureType.PAYLOAD_TOO_LARGE,
                    "Agent 异步命令 Kafka payload 超过最大字节数限制: " + payloadBytes
            );
        }
    }

    /**
     * 处理被拒绝的消息。
     *
     * <p>在生产环境，默认重新抛出异常，让 Kafka listener 容器不要提交 offset，等待错误处理器、运维或后续 DLQ 处理。
     * 在本地联调或临时灰度环境，也可以关闭 failOnRejectedMessage，让坏消息被记录后跳过，避免单条测试消息卡住分区。</p>
     */
    private AgentAsyncTaskCommandKafkaHandleResult handleRejected(AgentAsyncTaskCommandKafkaFailureType failureType,
                                                                  String reason,
                                                                  String payload,
                                                                  RuntimeException exception) {
        diagnosticsService.recordFailure(failureType, reason, payloadBytes(payload));
        if (properties.isLogPayloadOnError()) {
            log.warn("Agent 异步命令 Kafka 消息被拒绝，type={}, reason={}, payload={}", failureType, reason, payload);
        } else {
            log.warn("Agent 异步命令 Kafka 消息被拒绝，type={}, reason={}", failureType, reason);
        }
        if (properties.isFailOnRejectedMessage()) {
            throw exception;
        }
        return AgentAsyncTaskCommandKafkaHandleResult.rejected(failureType, reason);
    }

    private int payloadBytes(String payload) {
        if (payload == null) {
            return 0;
        }
        return payload.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * Kafka 消息处理结果摘要。
     *
     * <p>该对象只用于日志、测试和未来指标，不进入业务表。它不包含原始 payload，避免日志或指标系统扩散消息正文。</p>
     */
    public record AgentAsyncTaskCommandKafkaHandleResult(
            boolean accepted,
            boolean duplicate,
            boolean taskCreated,
            String commandId,
            Long taskId,
            AgentAsyncTaskCommandKafkaFailureType failureType,
            String reason
    ) {

        private static AgentAsyncTaskCommandKafkaHandleResult accepted(AgentAsyncTaskCommandConsumeResponse response) {
            return new AgentAsyncTaskCommandKafkaHandleResult(
                    true,
                    response.duplicate(),
                    response.taskCreated(),
                    response.commandId(),
                    response.taskId(),
                    null,
                    response.message()
            );
        }

        private static AgentAsyncTaskCommandKafkaHandleResult rejected(AgentAsyncTaskCommandKafkaFailureType failureType,
                                                                      String reason) {
            return new AgentAsyncTaskCommandKafkaHandleResult(false, false, false, null, null, failureType, reason);
        }
    }

    /**
     * payload 在进入 JSON 解析之前就被传输层规则拒绝时使用的内部异常。
     *
     * <p>单独定义异常是为了携带稳定 failureType。否则上层只能通过 message 文案猜测是空消息还是超大消息，
     * 后续做指标和 DLQ 分类时会非常脆弱。</p>
     */
    private static class KafkaCommandPayloadRejectedException extends IllegalArgumentException {

        private final AgentAsyncTaskCommandKafkaFailureType failureType;

        private KafkaCommandPayloadRejectedException(AgentAsyncTaskCommandKafkaFailureType failureType,
                                                     String message) {
            super(message == null || message.isBlank()
                    ? "Agent 异步命令 Kafka payload 被拒绝"
                    : message);
            this.failureType = failureType;
        }

        private AgentAsyncTaskCommandKafkaFailureType failureType() {
            return failureType;
        }
    }
}
