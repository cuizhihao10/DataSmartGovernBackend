/**
 * @Author : Cui
 * @Date: 2026/05/27 00:00
 * @Description DataSmart Govern Backend - AgentRuntimeEventKafkaConsumer.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.event;

import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventConsumeResult;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventConsumerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Python AI Runtime 运行时事件 Kafka 消费者。
 *
 * <p>该消费者是 Java 控制面与 Python AI Runtime 实时事件链路的第一条异步闭环：
 * Python 在 `/agent/plans` 或后续真实 Agent 执行过程中产生结构化事件，并发布到 Kafka；
 * Java agent-runtime 消费这些事件，形成控制面投影，为运行详情页、审计、告警和状态同步打基础。</p>
 *
 * <p>职责边界：
 * - 本类只负责 Spring Kafka 接入和日志；
 * - 反序列化、校验、幂等、投影写入全部委托给 `AgentRuntimeEventConsumerService`；
 * - 当前默认关闭监听器，避免本地没有 Kafka 时启动失败或产生噪声。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentRuntimeEventKafkaConsumer {

    private final AgentRuntimeEventConsumerService consumerService;

    /**
     * 消费 Python AI Runtime 发布的 Agent runtime event。
     *
     * <p>`topics/groupId/autoStartup` 都通过 SpEL 读取配置 bean：
     * - topic 与 Python publisher 默认值保持一致；
     * - groupId 表达“Java 控制面投影消费者组”；
     * - autoStartup 绑定 enabled 开关，生产环境准备好 Kafka 后再打开。</p>
     */
    @KafkaListener(
            /*
             * 使用配置占位符读取 Kafka listener 参数，而不是通过 "#{@xxxProperties}" 读取短 Bean 名。
             * @EnableConfigurationProperties 注册出的配置 Bean 名称不一定等于类名首字母小写，
             * 如果依赖短名，应用可能在 SpEL 解析阶段启动失败。占位符方式直接读取 Environment，
             * 同时支持 application.yml、Nacos 配置中心和环境变量覆盖，更适合真实部署。
             */
            topics = "${datasmart.agent-runtime.runtime-events.kafka.topic:datasmart.agent-runtime.events}",
            groupId = "${datasmart.agent-runtime.runtime-events.kafka.group-id:datasmart-agent-runtime-control-plane}",
            autoStartup = "${datasmart.agent-runtime.runtime-events.kafka.enabled:false}"
    )
    public void onAgentRuntimeEvent(String payload) {
        AgentRuntimeEventConsumeResult result = consumerService.consume(payload);
        if (result.accepted()) {
            log.info("已接收 Agent runtime event，identityKey={}", result.identityKey());
            return;
        }
        if (result.duplicate()) {
            log.debug("跳过重复 Agent runtime event，identityKey={}", result.identityKey());
            return;
        }
        log.warn("拒绝 Agent runtime event，reason={}", result.reason());
    }
}
