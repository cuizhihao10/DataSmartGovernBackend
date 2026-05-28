/**
 * @Author : Cui
 * @Date: 2026/05/27 00:00
 * @Description DataSmart Govern Backend - AgentRuntimeEventConsumerProperties.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Python AI Runtime 运行时事件消费配置。
 *
 * <p>这个配置类专门描述 Java agent-runtime 如何消费 Python AI Runtime 发布到 Kafka 的
 * `AgentRuntimeEvent`。它没有放进 {@link AgentRuntimeProperties}，是为了降低主配置类继续膨胀的风险：
 * Agent Runtime 后续会同时管理模型路由、工具目录、Skill、会话、事件、记忆、工作区等能力，
 * 如果所有配置都塞进一个类，文件会越来越难读，也不符合当前“单文件尽量控制在 500 行内”的规范。</p>
 *
 * <p>默认 `enabled=false`，这是一个重要的本地开发保护：
 * 很多学习/开发环境没有启动 Kafka，如果监听器默认启动，应用会在本地不断重连 broker，影响调试体验。
 * 生产环境只有在 Kafka topic、消费者组、告警和运维策略准备好后，才应显式打开。</p>
 */
@Data
@ConfigurationProperties(prefix = "datasmart.agent-runtime.runtime-events.kafka")
public class AgentRuntimeEventConsumerProperties {

    /**
     * 是否启动 Kafka 监听器。
     *
     * <p>该字段会绑定到 `@KafkaListener(autoStartup=...)`，不是简单的业务 if 判断。
     * 关闭后 Spring 不会启动对应 listener container，从源头避免本地无 Kafka 时的连接噪声。</p>
     */
    private boolean enabled = false;

    /**
     * Python AI Runtime 发布 Agent runtime events 的 topic。
     *
     * <p>该默认值与 Python 侧 `DATASMART_AI_RUNTIME_EVENT_TOPIC` 默认值保持一致。
     * 后续如果要拆分高频 token stream、工具执行结果、审计事件和 UI 进度事件，应新增 topic，
     * 不建议把所有 Agent 事件永久压在同一个主题上。</p>
     */
    private String topic = "datasmart.agent-runtime.events";

    /**
     * 当前 Java agent-runtime 消费者组。
     *
     * <p>同一 groupId 下的多个实例会分摊分区消费，适合“状态投影只处理一次”的控制面场景。
     * 如果未来 observability、audit-center 也要消费同一 topic，它们应使用不同 groupId，
     * 这样才能各自完整收到事件。</p>
     */
    private String groupId = "datasmart-agent-runtime-control-plane";

    /**
     * 单个 run 在当前内存投影中最多保留多少条事件。
     *
     * <p>当前阶段还没有落 MySQL/ClickHouse/对象存储，所以必须给内存投影加上限。
     * 真实生产环境可以把该值理解为“在线详情页热窗口”，长期审计应由独立持久化承接。</p>
     */
    private int maxEventsPerRun = 1000;

    /**
     * 当前 JVM 内所有 Agent runtime event 投影的总上限。
     *
     * <p>这个限制防止 Kafka 历史重放、异常风暴或测试压测时把 agent-runtime JVM 内存打满。
     * 后续迁移到数据库后，仍建议保留本地热窗口上限，避免控制面把自己变成无限事件仓库。</p>
     */
    private int maxTotalEvents = 10000;
}
