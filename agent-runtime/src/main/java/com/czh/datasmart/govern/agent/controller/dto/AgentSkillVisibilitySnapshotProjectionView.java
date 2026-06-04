/**
 * @Author : Cui
 * @Date: 2026/06/04 19:16
 * @Description DataSmart Govern Backend - AgentSkillVisibilitySnapshotProjectionView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Skill 可见性快照运行时事件的 Java 控制面视图。
 *
 * <p>Python Runtime 已经在 `SKILL_VISIBILITY_SNAPSHOT_RECORDED` 事件中写入会话级 Skill 可见性事实。
 * 该 DTO 的职责是把通用 runtime event attributes 解析为强类型视图，供前端治理卡片、审计台、
 * Java replay index 和后续 Skill Marketplace 使用统计消费。</p>
 *
 * <p>为什么不让前端直接解析通用 `AgentRuntimeEventProjectionView.attributes`：
 * 1. attributes 是自由 Map，不适合作为长期前端契约；
 * 2. Java 控制面需要对字段做低敏筛选、默认值兜底和类型转换；
 * 3. 后续如果事件 payloadVersion 升级，可以在服务层兼容，不需要所有调用方同时改造。</p>
 *
 * <p>安全边界：本视图仍然只承载低敏事实。它不会返回 prompt、SQL、工具参数、模型输出、完整权限清单、
 * 样本数据、密钥或长期记忆正文。Skill code 被视为能力目录标识，可用于治理和统计；如果未来某些
 * 客户把 Skill code 也视为敏感，应在 gateway/permission-admin 增加字段级策略。</p>
 */
public record AgentSkillVisibilitySnapshotProjectionView(
        /**
         * Java 投影层生成的幂等键。用于排障“这条事件是否已经被消费”，不作为业务主键暴露给模型。
         */
        String identityKey,

        /**
         * Python 事件契约版本，例如 `agent-runtime-event.v1`。
         */
        String schemaVersion,

        /**
         * 事件来源服务。当前通常是 `python-ai-runtime`，后续也可能来自独立 Agent host。
         */
        String source,

        /**
         * 固定为 `skill_visibility_snapshot_recorded`，用于调用方确认视图来源。
         */
        String eventType,

        String severity,
        String tenantId,
        String projectId,
        String actorId,
        String requestId,
        String runId,
        String sessionId,

        /**
         * Python 生产者原始 sequence。它只保证在生产方局部有序。
         */
        Long sequence,

        /**
         * Java 控制面分配的稳定 replay 游标。WebSocket 断线恢复和 Java replay API 应优先使用它。
         */
        Long replaySequence,

        Instant createdAt,
        Instant consumedAt,

        String snapshotType,
        String snapshotSource,
        Boolean available,
        Integer availableSkillCount,
        Integer visibleSkillCount,
        Integer hiddenSkillCount,
        Integer conditionalVisibleSkillCount,
        String permissionFactSource,
        String actorRoleSource,
        String actorRole,
        Integer grantedPermissionCount,
        Boolean tenantSkillEnabled,
        String workspaceRiskLevel,
        String tenantPlanCode,
        String policyVersion,
        Boolean legacyRequestVariablesDetected,
        Boolean modelGatewayAvailable,
        Boolean toolBudgetAllowed,
        List<String> visibleSkillCodes,
        Integer visibleSkillCodesTruncatedCount,
        List<String> hiddenSkillCodes,
        Integer hiddenSkillCodesTruncatedCount,
        Map<String, Integer> visibleRiskLevelCounts,
        Map<String, Integer> visibleDomainCounts,
        Map<String, Integer> hiddenAdmissionStatusCounts,
        String displaySummary,
        Integer recommendedActionCount
) {
}
