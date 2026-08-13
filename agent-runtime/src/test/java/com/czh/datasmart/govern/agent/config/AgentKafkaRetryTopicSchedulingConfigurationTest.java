/**
 * @Author : Cui
 * @Date: 2026/08/11 21:05
 * @Description DataSmart Govern Backend - AgentKafkaRetryTopicSchedulingConfigurationTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 Kafka 非阻塞重试基础设施所依赖的调度器可以在最小 Spring 上下文中创建。
 *
 * <p>{@code @RetryableTopic} 不只是在消息处理失败后立即再次调用监听器。Spring Kafka 会先把消息转入
 * retry topic，再由一个 {@link TaskScheduler} 等待退避时间到期后恢复对应分区的消费。如果应用上下文里
 * 没有调度器，服务会在启动阶段直接失败，甚至还没有机会连接 Kafka。因此这里使用最小上下文测试守住
 * “调度器 Bean 必须存在”这个启动前提，而不依赖外部 Kafka 或 PostgreSQL。</p>
 */
class AgentKafkaRetryTopicSchedulingConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AgentKafkaRetryTopicSchedulingConfiguration.class);

    /**
     * 确认配置只提供一个可被 Spring Kafka 按类型发现的任务调度器，并约束其线程模型。
     *
     * <p>Autopilot 当前只有一条恢复触发监听链，一条专用线程足以负责短暂的退避计时；真正的业务处理
     * 仍在 Kafka listener 线程中完成。固定线程名前缀可让初学者从线程转储和日志中快速识别该线程，
     * 也避免未来误把它当成同步任务 worker。</p>
     */
    @Test
    void shouldProvideSingleSchedulerRequiredByKafkaRetryTopics() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(TaskScheduler.class);

            ThreadPoolTaskScheduler scheduler = context.getBean(ThreadPoolTaskScheduler.class);
            // getPoolSize() 表示当前已经按需创建的线程数；空闲测试上下文中它可以为 0。
            // corePoolSize 才是配置的容量约束，也是本测试真正需要守住的线程模型。
            assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(1);
            assertThat(scheduler.getThreadNamePrefix()).isEqualTo("agent-kafka-retry-");
        });
    }
}
