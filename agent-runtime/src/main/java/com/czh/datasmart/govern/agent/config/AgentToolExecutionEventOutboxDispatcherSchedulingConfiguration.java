/**
 * @Author : Cui
 * @Date: 2026/05/28 20:10
 * @Description DataSmart Govern Backend - AgentToolExecutionEventOutboxDispatcherSchedulingConfiguration.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Agent 工具事件 outbox dispatcher 调度配置。
 *
 * <p>Spring 的 {@code @Scheduled} 需要启用调度处理器后才会真正运行。这里没有直接把
 * {@link EnableScheduling} 放到启动类上，而是单独做一个条件化配置类：
 * 只有 {@code datasmart.agent-runtime.tool-execution-events.outbox.dispatcher-enabled=true} 时，
 * 才启用 dispatcher 的周期轮询。</p>
 *
 * <p>这样设计有两个好处：
 * 1. 本地默认 memory/outbox 学习模式不会启动后台线程，日志更干净，也不会误改 outbox 状态；
 * 2. 生产环境打开 dispatcher 时，配置意图非常明确，便于灰度、回滚和排障。</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(
        prefix = "datasmart.agent-runtime.tool-execution-events.outbox",
        name = "dispatcher-enabled",
        havingValue = "true"
)
public class AgentToolExecutionEventOutboxDispatcherSchedulingConfiguration {
}
