/**
 * @Author : Cui
 * @Date: 2026/05/31 17:18
 * @Description DataSmart Govern Backend - AgentAsyncTaskCommandKafkaProperties.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 异步工具命令 Kafka 消费配置。
 *
 * <p>这组配置只描述 task-management 如何从 Kafka 接收 agent-runtime 投递的异步工具命令。
 * 真正的协议校验、Inbox 去重和任务创建仍在 {@code AgentAsyncTaskCommandConsumerService} 中完成。
 * 这样 HTTP 联调入口、Kafka listener、未来死信重放入口都能复用同一套业务语义。</p>
 *
 * <p>默认 {@code enabled=false} 是刻意的本地开发保护。很多学习环境只启动 MySQL，
 * 没有启动 Kafka。如果 listener 默认启动，应用会不断尝试连接 broker，既影响日志可读性，
 * 也会让初学者误以为任务模块本身启动失败。生产或联调环境准备好 topic、消费者组、告警和重放策略后，
 * 再通过环境变量显式打开。</p>
 */
@Data
@ConfigurationProperties(prefix = "datasmart.task-management.agent-async-commands.kafka")
public class AgentAsyncTaskCommandKafkaProperties {

    /**
     * 是否启动 Kafka listener。
     *
     * <p>该字段会绑定到 {@code @KafkaListener(autoStartup=...)}。
     * 关闭时 Spring 不会启动监听容器，从源头避免本地没有 Kafka 时的连接噪声。</p>
     */
    private boolean enabled = false;

    /**
     * Agent Runtime command outbox 投递的 Kafka topic。
     *
     * <p>默认值需要与 agent-runtime command plan/outbox 中的 commandTopic 保持一致。
     * 后续如果将高风险工具、批量导出、质量扫描拆成不同 topic，可在这里按环境覆盖。</p>
     */
    private String topic = "datasmart.agent.tool.async.commands";

    /**
     * task-management 消费者组。
     *
     * <p>同一 groupId 下的多个 task-management 实例会分摊分区消费，适合“每条 command 只被一个任务中心实例处理”
     * 的场景。审计中心或观测服务如果也要完整读取同一 topic，应使用不同 groupId。</p>
     */
    private String groupId = "datasmart-task-management-agent-command-consumer";

    /**
     * 单条 Kafka payload 的最大字节数。
     *
     * <p>正常 command payload 只包含引用和治理元数据，不应很大。这里设置上限是为了防止错误生产者把完整工具参数、
     * SQL、样本数据或文件清单写入消息，导致消费者内存被异常消息拖垮。</p>
     */
    private int maxPayloadBytes = 64 * 1024;

    /**
     * 遇到非法消息时是否抛出异常，让 Kafka 容器保留 offset 并按错误处理器重试。
     *
     * <p>当前还没有专用 DLQ，所以生产默认建议 fail-fast：非法消息会阻塞当前分区，等待运维处理或后续接入死信。
     * 如果本地联调希望跳过坏消息，可显式设置为 false；但这意味着该消息会被消费确认，后续不再自动重放。</p>
     */
    private boolean failOnRejectedMessage = true;

    /**
     * 发生解析或消费错误时是否把原始 payload 写入日志。
     *
     * <p>默认关闭。command 理论上不包含参数值，但仍可能携带业务标识和引用，生产日志中不应默认复制完整消息。</p>
     */
    private boolean logPayloadOnError = false;
}
