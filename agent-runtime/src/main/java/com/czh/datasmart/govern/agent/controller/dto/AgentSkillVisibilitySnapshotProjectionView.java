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

        /**
         * 本轮会话与 Skill Publication Manifest 的绑定状态。
         *
         * <p>典型值包括：
         * `BOUND_REMOTE_MANIFEST` 表示已经拿到远端 Java 发布目录且存在 contentFingerprint；
         * `LOCAL_DEFAULT_OR_FALLBACK` 表示 Python Runtime 使用本地默认 Skill 或远端不可用回退；
         * `REMOTE_READY_WITHOUT_FINGERPRINT` 表示远端可用但缺少版本指纹，需要补齐发布契约。</p>
         *
         * <p>这个字段是后续灰度、缓存排障和审计回放的关键维度：同一段时间内如果不同 Python Runtime
         * 绑定了不同 Manifest，运营台可以按该字段快速定位能力暴露差异。</p>
         */
        String manifestBindingStatus,

        /**
         * Python 诊断服务看到的 Manifest 健康状态，例如 REMOTE_READY、REMOTE_NOT_REFRESHED。
         */
        String manifestStatus,

        /**
         * Manifest 事实来源，例如 java-agent-runtime、local-default 或 diagnostics-service。
         */
        String manifestSource,

        /**
         * Manifest 内容指纹。它不是密钥，而是能力发布目录的版本证据，可用于灰度比对和审计定位。
         */
        String manifestFingerprint,

        /**
         * Manifest schema 版本，便于 Java 控制面兼容未来发布目录结构升级。
         */
        String manifestSchemaVersion,

        /**
         * 当前 Manifest 中的 Skill 总量。远端未绑定时通常为 0 或本地回退数量。
         */
        Integer manifestSkillCount,

        /**
         * 当前 Manifest 中 READY Skill 数量。后续 Marketplace 可据此判断可用能力池规模。
         */
        Integer manifestReadySkillCount,

        /**
         * 当前 Manifest 中非 READY Skill 数量，用于提示能力发布治理仍有阻塞项。
         */
        Integer manifestNonReadySkillCount,

        /**
         * 是否处于本地默认或远端不可用回退路径。生产环境如果长期为 true，应触发运维排查。
         */
        Boolean manifestFallback,

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
