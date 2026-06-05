/**
 * @Author : Cui
 * @Date: 2026/06/05 00:18
 * @Description DataSmart Govern Backend - AgentSkillVisibilitySnapshotIndexDiagnosticsView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.time.Instant;
import java.util.Map;

/**
 * Skill 可见性快照专用索引诊断视图。
 *
 * <p>该 DTO 面向平台管理员、运维和后续 observability 模块，用于回答几个生产排障问题：</p>
 * <p>1. 当前是否启用了专用索引，配置的是 memory 还是 mysql；</p>
 * <p>2. 查询服务当前实际走专用索引还是 fallback 到通用 runtime event projection；</p>
 * <p>3. 当前索引大小是否可探测，MySQL 不可用或表结构缺失时能否给出低敏错误；</p>
 * <p>4. 物化、重复、跳过、失败和查询来源计数是否正常；</p>
 * <p>5. Manifest 绑定状态分布是否出现异常，例如大量 fallback 或 diagnostics unavailable。</p>
 *
 * <p>安全边界：该视图不返回任何 runId、sessionId、requestId、traceId、tenantId、projectId、actorId、
 * manifestFingerprint、Skill code 明细、prompt、SQL 或工具参数。它只返回链路健康摘要，适合进入管理端和告警面板。</p>
 */
public record AgentSkillVisibilitySnapshotIndexDiagnosticsView(
        boolean enabled,
        String configuredStore,
        String activeIndexSource,
        String activeStore,
        int maxSnapshotsPerRun,
        int maxTotalSnapshots,
        int currentIndexSize,
        String currentIndexSizeStatus,
        String currentIndexSizeError,
        int fallbackProjectionSize,
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
