/**
 * @Author : Cui
 * @Date: 2026/05/31 23:12
 * @Description DataSmart Govern Backend - AgentAsyncTaskCommandKafkaDiagnosticsSnapshot.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.event;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Agent 异步工具命令 Kafka 消费诊断快照。
 *
 * <p>该快照是给内部运维接口、测试和后续指标导出使用的只读视图。
 * 它不代表持久化审计事实，也不保证跨进程共享；如果 task-management 多实例部署，
 * 每个实例只能看到自己进程内最近处理过的坏消息。后续商业化版本可以把同样的字段写入
 * MySQL 诊断表、日志平台或真实 DLQ topic，再由统一运维台聚合。</p>
 *
 * @param enabled 是否启用诊断记录。
 * @param dlqEnabled 是否启用死信候选标记。
 * @param dlqTopic 未来真实 DLQ Producer 使用的 topic。
 * @param maxRecentFailures 当前进程最多保留的最近失败样本数量。
 * @param totalFailures 当前进程启动后累计记录的失败次数。
 * @param dlqCandidateFailures 当前进程启动后累计被标记为死信候选的失败次数。
 * @param failuresByType 按稳定失败类型聚合的计数。
 * @param recentFailures 最近失败样本，按发生时间从旧到新排列，方便人眼阅读。
 * @param snapshotAt 生成快照的时间。
 */
public record AgentAsyncTaskCommandKafkaDiagnosticsSnapshot(
        boolean enabled,
        boolean dlqEnabled,
        String dlqTopic,
        int maxRecentFailures,
        long totalFailures,
        long dlqCandidateFailures,
        Map<AgentAsyncTaskCommandKafkaFailureType, Long> failuresByType,
        List<AgentAsyncTaskCommandKafkaFailureRecord> recentFailures,
        LocalDateTime snapshotAt
) {
}
