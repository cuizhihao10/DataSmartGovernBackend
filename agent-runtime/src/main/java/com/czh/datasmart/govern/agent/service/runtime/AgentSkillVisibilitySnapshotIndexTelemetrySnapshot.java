/**
 * @Author : Cui
 * @Date: 2026/06/05 00:08
 * @Description DataSmart Govern Backend - AgentSkillVisibilitySnapshotIndexTelemetrySnapshot.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import java.time.Instant;
import java.util.Map;

/**
 * Skill 可见性快照专用索引遥测快照。
 *
 * <p>该 record 是 {@link AgentSkillVisibilitySnapshotIndexTelemetry} 的只读导出模型。
 * 之所以单独定义快照，而不是让诊断接口直接读取 AtomicLong，是为了把“指标采集内部实现”和“管理 API 契约”
 * 分开：内部可以继续从 AtomicLong 演进到 Micrometer gauge、Redis 计数或数据库物化统计；外部诊断 DTO
 * 仍然消费稳定字段。</p>
 *
 * <p>字段只描述低敏运行健康情况，不包含 runId、sessionId、requestId、traceId、tenantId、projectId
 * 或 manifestFingerprint。原因是这些标识会造成 Prometheus 高基数，也可能把客户排障上下文泄漏到运维指标。</p>
 */
public record AgentSkillVisibilitySnapshotIndexTelemetrySnapshot(
        long materializedCount,
        long duplicateMaterializationCount,
        long skippedMaterializationCount,
        long failedMaterializationCount,
        long dedicatedQueryCount,
        long fallbackQueryCount,
        long failedQueryCount,
        long dedicatedQueryResultCount,
        long fallbackQueryResultCount,
        Map<String, Long> manifestBindingStatusCounts,
        Instant lastMaterializedAt,
        Instant lastDuplicateMaterializationAt,
        Instant lastSkippedMaterializationAt,
        Instant lastFailedMaterializationAt,
        Instant lastDedicatedQueryAt,
        Instant lastFallbackQueryAt,
        Instant lastQueryFailedAt,
        String lastFailureStage,
        String lastFailureReason,
        String lastSkippedReason
) {
}
