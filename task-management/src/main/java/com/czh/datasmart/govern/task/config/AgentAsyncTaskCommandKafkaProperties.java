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

    /**
     * 是否记录 Kafka 消费诊断快照。
     *
     * <p>该开关只控制 task-management 进程内的轻量诊断记录，不会改变 Kafka offset 提交策略。
     * 设计上先把“坏消息为什么失败、失败了多少次、最近有哪些失败类型”记录下来，
     * 让开发者和运维人员能在不翻大量日志的情况下判断问题是 JSON 格式、payload 过大、协议校验失败，
     * 还是业务消费服务抛出了未知异常。后续如果接入 Prometheus、真实 DLQ Producer 或运维台重放，
     * 可以复用同一套失败分类语义。</p>
     */
    private boolean diagnosticsEnabled = true;

    /**
     * 诊断快照中最多保留多少条最近失败样本。
     *
     * <p>这里使用有界内存窗口，而不是无限 List，是为了避免生产环境遇到持续坏消息时把 JVM 内存拖垮。
     * 该值不是“失败总数上限”，总数会通过计数器继续累计；它只影响 recentFailures 里保留的最近样本数量。
     * 生产环境如果需要长期留存，应把失败事件写入 MySQL、日志平台或专用 DLQ，而不是依赖进程内存。</p>
     */
    private int maxRecentFailures = 100;

    /**
     * 是否启用死信候选标记。
     *
     * <p>当前阶段先不直接生产真实 DLQ 消息，原因是 DLQ 一旦真实写出，就需要同时设计 topic 权限、
     * payload 脱敏、重放幂等、人工处理状态和告警策略。这里先通过配置和诊断快照标记“该失败是否应该进入 DLQ”，
     * 为后续接入真实 KafkaTemplate DLQ Producer 留出稳定配置面。</p>
     */
    private boolean dlqEnabled = false;

    /**
     * 未来真实 DLQ Producer 使用的 topic 名称。
     *
     * <p>默认与主 topic 分离，避免坏消息重放和正常命令消费互相污染。后续生产化时建议按环境和租户策略规划：
     * 例如高风险工具、批量导出、数据同步执行可以拆成不同 DLQ topic，以便设置不同保留期和告警等级。</p>
     */
    private String dlqTopic = "datasmart.agent.tool.async.commands.dlq";
}
