/**
 * @Author : Cui
 * @Date: 2026/08/19 00:00
 * @Description DataSmart Govern Backend - AgentCommandTaskFinalStateCallbackWorkerSchedulingConfiguration.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 最终态 callback worker 的条件化调度开关。
 *
 * <p>默认不启用 {@code @EnableScheduling}，因此即使 receipt index 中已有终态事实，本地开发也不会自动写
 * task-management。只有部署环境显式设置 worker enabled=true，并成功装配 durable job store 后才会出现后台线程。</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(
        prefix = "datasmart.agent-runtime.async-task-final-state-callback-worker",
        name = "enabled",
        havingValue = "true"
)
public class AgentCommandTaskFinalStateCallbackWorkerSchedulingConfiguration {
}
