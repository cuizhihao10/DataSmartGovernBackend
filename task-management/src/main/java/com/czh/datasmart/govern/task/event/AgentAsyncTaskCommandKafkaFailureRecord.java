/**
 * @Author : Cui
 * @Date: 2026/05/31 23:11
 * @Description DataSmart Govern Backend - AgentAsyncTaskCommandKafkaFailureRecord.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.event;

import java.time.LocalDateTime;

/**
 * Agent 异步工具命令 Kafka 消费失败样本。
 *
 * <p>该对象刻意不保存原始 payload。原因是 command 虽然设计上只携带引用和治理元数据，
 * 但真实生产环境里仍可能被错误生产者写入 SQL、字段名、样本值、对象存储路径甚至密钥引用。
 * 诊断快照应该帮助定位问题，而不是成为另一个敏感数据扩散面。</p>
 *
 * @param failureId 单进程内生成的失败样本 ID，用于日志、诊断快照和后续人工排障关联。
 * @param type 稳定失败分类，供指标、告警、DLQ 分类和运维台筛选使用。
 * @param reason 面向开发者的失败说明，应保持可读，但不要依赖它做程序判断。
 * @param payloadBytes payload 的 UTF-8 字节数，用于判断是否因为大消息触发保护。
 * @param dlqCandidate 当前配置下是否应作为死信候选。当前阶段仅记录，不真实写 DLQ topic。
 * @param occurredAt 失败发生时间，使用服务本地时间即可满足本地学习和单服务诊断；跨服务追踪仍应依赖 traceId。
 */
public record AgentAsyncTaskCommandKafkaFailureRecord(
        String failureId,
        AgentAsyncTaskCommandKafkaFailureType type,
        String reason,
        int payloadBytes,
        boolean dlqCandidate,
        LocalDateTime occurredAt
) {
}
