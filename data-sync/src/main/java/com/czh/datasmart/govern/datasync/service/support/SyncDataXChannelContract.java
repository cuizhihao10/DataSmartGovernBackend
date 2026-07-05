/**
 * @Author : Cui
 * @Date: 2026/07/05 15:58
 * @Description DataSmart Govern Backend - SyncDataXChannelContract.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

/**
 * DataX-style Channel 低敏执行合同。
 *
 * <p>Channel 可以理解为 Reader 与 Writer 之间的受控数据通道。它不只是“线程数”，还承载限速、脏数据、
 * 转换、提交、指标和错误处理策略。当前最小 run-once bridge 只有一个逻辑通道；后续专用 Runner 可以把一个 Job
 * 拆成多个 TaskGroup 和多个 Channel，并在通道级别做 backpressure、限速、失败隔离和进度上报。</p>
 *
 * <p>本对象只描述通道策略，不承载真实行数据，也不承载字段映射原文。</p>
 *
 * @param channelId 低敏通道编号。当前通常是 CHANNEL-0；真实 Runner 可在内部扩展更多通道。
 * @param channelKind 通道类型，例如 SINGLE_OBJECT_CHANNEL、OBJECT_FAN_OUT_CHANNEL、CUSTOM_SQL_CHANNEL。
 * @param executionBoundary 执行边界，说明当前通道由最小 bridge 处理还是必须交给专用 Runner。
 * @param readerContract Reader 低敏合同。
 * @param writerContract Writer 低敏合同。
 * @param transformerPolicy 转换策略。字段改名、类型转换、脱敏、默认值等必须显式声明能力，不允许 Runner 猜测。
 * @param speedLimitPolicy 限速策略。生产环境需要支持 bytes/s、records/s、租户配额和资源组隔离。
 * @param dirtyRecordPolicy 脏数据策略。失败样本只能以 digest 或受控引用形式进入报告。
 * @param commitPolicy 提交策略。说明批次成功、部分失败、最终完成时如何推进状态机。
 * @param observabilityPolicy 可观测策略。约束通道必须输出低基数指标和结构化报告。
 * @param payloadPolicy 当前对象低敏载荷策略。
 */
public record SyncDataXChannelContract(
        String channelId,
        String channelKind,
        String executionBoundary,
        SyncDataXReaderContract readerContract,
        SyncDataXWriterContract writerContract,
        String transformerPolicy,
        String speedLimitPolicy,
        String dirtyRecordPolicy,
        String commitPolicy,
        String observabilityPolicy,
        String payloadPolicy
) {
}
