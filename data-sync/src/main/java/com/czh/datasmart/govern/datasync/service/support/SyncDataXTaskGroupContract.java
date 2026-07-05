/**
 * @Author : Cui
 * @Date: 2026/07/05 15:58
 * @Description DataSmart Govern Backend - SyncDataXTaskGroupContract.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import java.util.List;

/**
 * DataX-style TaskGroup 低敏执行合同。
 *
 * <p>TaskGroup 是 Job 拆分后的调度容器。它让系统能把“一个同步任务”拆成多个可调度、可重试、可恢复的工作单元。
 * 单表全量时可以只有一个 TaskGroup；多表 fan-out、整库迁移、按分区补数、按窗口回放时，TaskGroup 的数量和内容
 * 应由专用 Runner 在受控环境中展开。</p>
 *
 * <p>这里保留的是“代表性 TaskGroup 摘要”，不是完整任务清单。这样前端、Agent 和运维台可以理解拓扑，
 * 但不会看到具体表名、字段、SQL、分区边界或 checkpoint 原始水位。</p>
 *
 * @param taskGroupId 低敏 TaskGroup 编号。
 * @param taskGroupKind TaskGroup 类型，例如 SINGLE_OBJECT_GROUP、OBJECT_FAN_OUT_GROUP、RUNTIME_DISCOVERY_GROUP。
 * @param objectScopeKind 对象范围类型，例如 SINGLE_OBJECT、OBJECT_LIST、SCHEMA_FULL。
 * @param estimatedTaskCount 当前 TaskGroup 预计任务数。-1 表示需要运行时发现。
 * @param schedulingPolicy 调度策略，例如最小 bridge 串行、专用 Runner 按资源组并发、定时窗口调度。
 * @param retryPolicy 重试策略。TaskGroup 级别需要说明失败后按分片、窗口还是整个 Job 重试。
 * @param checkpointPolicy checkpoint 策略。需要恢复能力的任务必须在 TaskGroup 或 Channel 级别维护水位引用。
 * @param resourceGroupPolicy 资源组策略。生产环境需要按租户、项目、连接器和任务优先级控制并发。
 * @param channels 代表性 Channel 合同列表。当前最小闭环通常只有一个逻辑 Channel。
 * @param payloadPolicy 当前对象低敏载荷策略。
 */
public record SyncDataXTaskGroupContract(
        String taskGroupId,
        String taskGroupKind,
        String objectScopeKind,
        int estimatedTaskCount,
        String schedulingPolicy,
        String retryPolicy,
        String checkpointPolicy,
        String resourceGroupPolicy,
        List<SyncDataXChannelContract> channels,
        String payloadPolicy
) {
}
