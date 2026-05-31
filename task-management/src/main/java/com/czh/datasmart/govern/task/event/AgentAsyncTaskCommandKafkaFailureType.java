/**
 * @Author : Cui
 * @Date: 2026/05/31 23:10
 * @Description DataSmart Govern Backend - AgentAsyncTaskCommandKafkaFailureType.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.event;

/**
 * Agent 异步工具命令 Kafka 消费失败类型。
 *
 * <p>为什么要单独定义枚举，而不是只保存异常 message：
 * 生产环境排障时，message 往往不稳定，可能随框架版本、校验规则或业务文案变化。
 * 枚举类型则可以作为稳定维度，用于日志聚合、指标标签、DLQ 分类、告警规则和后续人工重放台筛选。</p>
 */
public enum AgentAsyncTaskCommandKafkaFailureType {

    /**
     * payload 为空或全是空白字符。
     *
     * <p>这种错误通常来自错误的生产者代码、手工测试误发或 topic 被其他系统误写。
     * 它没有业务命令 ID，无法安全重放，后续进入 DLQ 时也应默认标记为不可自动重放。</p>
     */
    EMPTY_PAYLOAD,

    /**
     * payload 超过配置的最大字节数。
     *
     * <p>Agent command 正常只携带治理元数据和 payloadReference，不应携带完整工具参数、大 SQL、样本数据或文件清单。
     * 该错误往往说明上游把“引用式契约”误用成“内嵌大对象契约”，需要从生产端修正。</p>
     */
    PAYLOAD_TOO_LARGE,

    /**
     * payload 不是合法 JSON。
     *
     * <p>这种消息无法反序列化为命令 DTO，因此 ConsumerService 无法执行协议校验、Inbox 去重或任务创建。
     * 生产环境默认应 fail-fast，等待 Kafka 错误处理器或 DLQ 接管，而不是静默跳过。</p>
     */
    INVALID_JSON,

    /**
     * JSON 合法，但业务消费服务拒绝该命令。
     *
     * <p>典型原因包括 schemaVersion 不支持、commandType 不支持、幂等键缺失、toolCode 未授权、
     * payloadReference 格式不正确等。与 INVALID_JSON 不同，这类消息通常已经能解析出业务字段，
     * 后续 DLQ 或运维台可以尝试展示 commandId、auditId、toolCode 等上下文。</p>
     */
    CONSUMER_REJECTED,

    /**
     * 消费服务执行过程中出现非预期运行时异常。
     *
     * <p>它可能代表数据库暂时不可用、Mapper 异常、事务失败、未知空指针或其他系统性问题。
     * 与协议拒绝不同，这类失败更可能适合重试或告警，而不是简单判定为坏消息。</p>
     */
    CONSUMER_EXCEPTION
}
