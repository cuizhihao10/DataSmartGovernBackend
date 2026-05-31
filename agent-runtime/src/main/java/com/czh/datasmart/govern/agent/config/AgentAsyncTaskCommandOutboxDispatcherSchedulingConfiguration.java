/**
 * @Author : Cui
 * @Date: 2026/05/31 17:05
 * @Description DataSmart Govern Backend - AgentAsyncTaskCommandOutboxDispatcherSchedulingConfiguration.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Agent 异步命令 outbox dispatcher 调度开关。
 *
 * <p>调度能力单独条件化启用，是为了保护本地开发体验。默认情况下，开发者可以生成和查询 command outbox，
 * 但不会因为没有 task-management、Kafka 或内部网络而让后台线程持续失败。</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(
        prefix = "datasmart.agent-runtime.async-task-commands.outbox",
        name = "dispatcher-enabled",
        havingValue = "true"
)
public class AgentAsyncTaskCommandOutboxDispatcherSchedulingConfiguration {
}
