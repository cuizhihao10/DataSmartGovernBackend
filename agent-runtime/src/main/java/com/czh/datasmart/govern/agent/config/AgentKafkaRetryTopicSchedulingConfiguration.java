/**
 * @Author : Cui
 * @Date: 2026/08/11 21:08
 * @Description DataSmart Govern Backend - AgentKafkaRetryTopicSchedulingConfiguration.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 为 Spring Kafka 的非阻塞 retry topic 机制提供退避计时调度器。
 *
 * <p>Autopilot 恢复触发消费者使用 {@code @RetryableTopic}。消费失败时，Spring Kafka 会暂停 retry topic
 * 的对应分区，等退避时间到期后再恢复消费。这个“等待后恢复”的动作必须由 {@link TaskScheduler} 驱动；
 * 如果上下文里没有该 Bean，应用会在创建 Kafka 监听器时启动失败，而不是等到第一条失败消息才报错。</p>
 *
 * <p>该配置与 Agent 的业务定时任务相互独立。业务 outbox 是否轮询仍由各自的条件化
 * {@code @EnableScheduling} 配置控制；这里仅保证 Kafka retry infrastructure 在消费者关闭或开启时都能
 * 完成上下文初始化。若未来平台统一提供了更合适的 {@link TaskScheduler}，
 * {@link ConditionalOnMissingBean} 会让本地默认实现自动退让，避免出现多个候选 Bean。</p>
 */
@Configuration(proxyBeanMethods = false)
public class AgentKafkaRetryTopicSchedulingConfiguration {

    /**
     * 创建一个轻量、可识别且能优雅关闭的 Kafka retry topic 调度器。
     *
     * <p>这里只配置一个线程，因为线程只负责退避到期通知，不执行 Python 诊断、RAG 检索、审批或同步重试
     * 等耗时业务。业务仍由 Kafka listener 线程和下游服务执行。取消任务会立即从队列移除，应用关闭时则
     * 最多等待十秒让已经触发的短任务结束，减少容器滚动发布期间留下悬挂计时任务的概率。</p>
     *
     * @return 可被 Spring Kafka 按 {@link TaskScheduler} 类型发现的线程池调度器
     */
    @Bean(name = "agentKafkaRetryTopicTaskScheduler")
    @ConditionalOnMissingBean(TaskScheduler.class)
    public ThreadPoolTaskScheduler agentKafkaRetryTopicTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("agent-kafka-retry-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        return scheduler;
    }
}
