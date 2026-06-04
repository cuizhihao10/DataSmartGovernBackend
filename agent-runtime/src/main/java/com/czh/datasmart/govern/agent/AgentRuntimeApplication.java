/**
 * @Author : Cui
 * @Date: 2026/05/13 22:18
 * @Description DataSmart Govern Backend - AgentRuntimeApplication.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent;

import com.czh.datasmart.govern.agent.config.AgentRuntimeProperties;
import com.czh.datasmart.govern.agent.config.AgentAsyncTaskCommandOutboxProperties;
import com.czh.datasmart.govern.agent.config.AgentRuntimeEventConsumerProperties;
import com.czh.datasmart.govern.agent.config.AgentRuntimePersistenceProperties;
import com.czh.datasmart.govern.agent.config.AgentRunToolDagConfirmationProperties;
import com.czh.datasmart.govern.agent.config.AgentSkillVisibilitySnapshotIndexProperties;
import com.czh.datasmart.govern.agent.config.AgentSkillRegistryProperties;
import com.czh.datasmart.govern.agent.config.AgentToolServiceAuthorizationProperties;
import com.czh.datasmart.govern.agent.config.AgentToolExecutionEventProperties;
import com.czh.datasmart.govern.agent.config.AgentToolExecutionEventOutboxProperties;
import com.czh.datasmart.govern.agent.config.AgentToolRuntimeProtectionProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 智能体运行时控制面启动类。
 *
 * <p>该模块不是直接承载大模型推理的 Python/vLLM 服务，而是 Java 后端侧的“AI 控制面”：
 * 1. 对 gateway 暴露稳定的 Agent/模型调用 API；
 * 2. 根据任务类型选择不同模型能力，例如推理、代码、Embedding、Rerank、多模态；
 * 3. 后续负责把 Java 任务中心、数据源、质量规则、权限上下文转换成 Agent 可执行的工具契约；
 * 4. 与 Python AI 服务通过 HTTP/gRPC/Kafka 解耦，避免 Java 业务模块直接依赖某个模型框架。
 */
@SpringBootApplication
@EnableConfigurationProperties({
        AgentRuntimeProperties.class,
        AgentRuntimePersistenceProperties.class,
        AgentSkillRegistryProperties.class,
        AgentSkillVisibilitySnapshotIndexProperties.class,
        AgentRuntimeEventConsumerProperties.class,
        AgentToolServiceAuthorizationProperties.class,
        AgentAsyncTaskCommandOutboxProperties.class,
        AgentRunToolDagConfirmationProperties.class,
        AgentToolExecutionEventProperties.class,
        AgentToolExecutionEventOutboxProperties.class,
        AgentToolRuntimeProtectionProperties.class
})
public class AgentRuntimeApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentRuntimeApplication.class, args);
    }
}
