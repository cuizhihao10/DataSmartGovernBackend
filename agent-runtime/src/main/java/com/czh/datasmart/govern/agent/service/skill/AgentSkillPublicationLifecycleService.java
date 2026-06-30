/**
 * @Author : Cui
 * @Date: 2026/06/30 23:19
 * @Description DataSmart Govern Backend - AgentSkillPublicationLifecycleService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.skill;

import com.czh.datasmart.govern.agent.config.AgentRuntimeProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentSkillPublicationDraftCreateRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentSkillPublicationLifecycleActionRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentSkillPublicationLifecycleQueryResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentSkillPublicationLifecycleView;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Agent Skill 创建、审核、发布和下线状态机服务。
 *
 * <p>该服务补齐的是 Skill Marketplace 的“写侧控制面”：
 * 以前 agent-runtime 已经能读取配置式 Skill 注册表、生成发布 Manifest、把可见性快照投影到 Java；
 * 但缺少把一个新 Skill 从草稿推进到 READY，再按治理原因下线的生命周期管理。
 * 这里先实现最小但完整的状态机，避免继续停留在只读目录和消费侧诊断。</p>
 *
 * <p>为什么状态机放在 Java agent-runtime，而不是 Python Runtime：
 * Skill 发布属于平台治理事实，涉及租户、项目、角色、权限、审计、发布目录和运行时准入。
 * Python Runtime 适合执行 Agent 推理、工具规划和上下文构建，不应该成为“谁能发布能力包”的事实源。
 * Java 控制面负责持久化和审计，Python 只消费 READY 的低敏发布事实。</p>
 *
 * <p>当前边界：
 * 1. 默认仓储是内存实现，用于本地闭环和单元测试；生产应替换为 MySQL/JDBC store；</p>
 * <p>2. 身份和角色仍来自 Header/请求体，后续应由 gateway + OIDC/Keycloak + permission-admin 注入可信主体；</p>
 * <p>3. 服务只保存低敏 Skill 元数据，不保存 prompt、SQL、工具参数、样本数据、模型输出或凭据。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentSkillPublicationLifecycleService {

    private static final String VIEW_SCHEMA_VERSION = "datasmart.agent.skill.publication-lifecycle.v1";
    private static final String QUERY_SCHEMA_VERSION = "datasmart.agent.skill.publication-lifecycle-query.v1";
    private static final String QUERY_TYPE = "AGENT_SKILL_PUBLICATION_LIFECYCLE_QUERY";
    private static final Pattern SKILL_CODE_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9_.-]{2,158}$");
    private static final List<String> SENSITIVE_TEXT_MARKERS = List.of(
            "api_key", "apikey", "access_key", "secret", "password", "passwd", "bearer ",
            "token=", "jdbc:", "http://", "https://", "select *", "insert into", "update ",
            "delete from", "drop table", "prompt:", "system prompt", "model output"
    );

    private final AgentRuntimeProperties runtimeProperties;
    private final AgentSkillPublicationLifecycleStore lifecycleStore;

    /**
     * 创建 Skill 发布草稿。
     *
     * <p>草稿创建只代表“某个能力包的低敏治理元数据被登记”，不会自动进入 Manifest，也不会被 Python Runtime
     * 默认加载。创建时会生成内容指纹，并校验 skillCode/version 的租户项目内唯一性，避免后续审计无法区分版本。</p>
     */
    public AgentSkillPublicationLifecycleView createDraft(AgentSkillPublicationDraftCreateRequest request,
                                                          String headerTenantId,
                                                          String headerProjectId,
                                                          String headerActorId) {
        ensureRuntimeEnabled();
        String tenantId = requireText(firstNonBlank(headerTenantId, request.tenantId()), "tenantId");
        String projectId = requireText(firstNonBlank(headerProjectId, request.projectId()), "projectId");
        String operatorId = requireText(firstNonBlank(headerActorId, request.operatorId()), "operatorId");
        String skillCode = requireText(request.skillCode(), "skillCode");
        String version = requireText(request.version(), "version");
        String displayName = requireText(request.displayName(), "displayName");
        validateSkillCode(skillCode);
        validateLowSensitiveText(displayName, "displayName");
        validateLowSensitiveText(request.description(), "description");

        lifecycleStore.findBySkillCodeAndVersion(tenantId, projectId, skillCode, version)
                .ifPresent(existing -> {
                    throw new PlatformBusinessException(PlatformErrorCode.DUPLICATE_OPERATION,
                            "同一租户项目内已存在相同 skillCode/version 的发布单，publicationId=" + existing.publicationId());
                });

        Instant now = Instant.now();
        List<String> requiredTools = normalizeList(request.requiredTools(), false);
        List<String> requiredPermissions = normalizeList(request.requiredPermissions(), false);
        List<String> memoryDependencies = normalizeList(request.memoryDependencies(), true);
        String domain = normalizeToken(request.domain(), "GENERAL_GOVERNANCE");
        String riskLevel = normalizeToken(request.riskLevel(), "LOW");
        String approvalPolicy = normalizeToken(request.approvalPolicy(), "NONE");
        AgentSkillPublicationRecord record = new AgentSkillPublicationRecord(
                "skill-pub-" + UUID.randomUUID(),
                tenantId,
                projectId,
                skillCode.trim(),
                version.trim(),
                displayName.trim(),
                blankToNull(request.description()),
                domain,
                riskLevel,
                approvalPolicy,
                request.auditRequired() == null || Boolean.TRUE.equals(request.auditRequired()),
                request.tenantScoped() == null || Boolean.TRUE.equals(request.tenantScoped()),
                request.projectScoped() == null || Boolean.TRUE.equals(request.projectScoped()),
                requiredTools,
                requiredPermissions,
                memoryDependencies,
                fingerprintDraft(tenantId, projectId, skillCode, version, displayName, request.description(),
                        domain, riskLevel, approvalPolicy, requiredTools, requiredPermissions, memoryDependencies),
                AgentSkillPublicationLifecycleStatus.DRAFT,
                operatorId,
                null,
                null,
                null,
                null,
                null,
                null,
                now,
                null,
                null,
                null,
                now
        );
        return toView(lifecycleStore.save(record));
    }

    /**
     * 提交发布审核。
     *
     * <p>提交审核时会执行发布策略预检。这样 DRAFT 可以允许用户逐步补全字段，但进入 IN_REVIEW 前必须满足
     * 商业化基础规则：审计、租户隔离、项目隔离、权限声明和高风险人工审批策略都齐备。</p>
     */
    public AgentSkillPublicationLifecycleView submitForReview(String publicationId,
                                                              AgentSkillPublicationLifecycleActionRequest request,
                                                              String headerActorId) {
        ensureRuntimeEnabled();
        AgentSkillPublicationRecord record = requireRecord(publicationId);
        ensureStatus(record, AgentSkillPublicationLifecycleStatus.DRAFT, "只有 DRAFT 发布单可以提交审核");
        ensureNoBlockingPolicyIssues(record);
        String operatorId = requireText(firstNonBlank(headerActorId, request.operatorId()), "operatorId");
        validateLowSensitiveText(request.comment(), "comment");
        return toView(lifecycleStore.save(record.submitForReview(operatorId, request.comment(), Instant.now())));
    }

    /**
     * 审核通过并发布 Skill。
     *
     * <p>当前没有接入真实审批流引擎，因此这里先使用 actorRole 做轻量保护：如果 Header 提供了角色，
     * 则必须是管理员类角色才能审批。后续接 Keycloak/permission-admin 后，应由统一权限中心返回可审批动作。</p>
     */
    public AgentSkillPublicationLifecycleView approve(String publicationId,
                                                      AgentSkillPublicationLifecycleActionRequest request,
                                                      String headerActorId,
                                                      String actorRole) {
        ensureRuntimeEnabled();
        ensureAdminIfRolePresent(actorRole, "审核通过 Skill 发布");
        AgentSkillPublicationRecord record = requireRecord(publicationId);
        ensureStatus(record, AgentSkillPublicationLifecycleStatus.IN_REVIEW, "只有 IN_REVIEW 发布单可以审核通过");
        ensureNoBlockingPolicyIssues(record);
        String operatorId = requireText(firstNonBlank(headerActorId, request.operatorId()), "operatorId");
        validateLowSensitiveText(request.comment(), "comment");
        validateLowSensitiveText(request.approvalTicketId(), "approvalTicketId");
        return toView(lifecycleStore.save(record.approve(operatorId, request.comment(), Instant.now())));
    }

    /**
     * 拒绝发布审核。
     *
     * <p>拒绝是稳定终态之一，不能直接删除记录。拒绝原因必须是低敏摘要，方便管理台展示和后续复盘。</p>
     */
    public AgentSkillPublicationLifecycleView reject(String publicationId,
                                                     AgentSkillPublicationLifecycleActionRequest request,
                                                     String headerActorId,
                                                     String actorRole) {
        ensureRuntimeEnabled();
        ensureAdminIfRolePresent(actorRole, "拒绝 Skill 发布");
        AgentSkillPublicationRecord record = requireRecord(publicationId);
        ensureStatus(record, AgentSkillPublicationLifecycleStatus.IN_REVIEW, "只有 IN_REVIEW 发布单可以被拒绝");
        String operatorId = requireText(firstNonBlank(headerActorId, request.operatorId()), "operatorId");
        String reason = requireText(request.comment(), "comment");
        validateLowSensitiveText(reason, "comment");
        return toView(lifecycleStore.save(record.reject(operatorId, reason, Instant.now())));
    }

    /**
     * 下线已发布 Skill。
     *
     * <p>只有 READY 能力可以被废弃。DRAFT/IN_REVIEW/REJECTED 本来就未进入运行时发布目录，不需要走下线流程。</p>
     */
    public AgentSkillPublicationLifecycleView deprecate(String publicationId,
                                                        AgentSkillPublicationLifecycleActionRequest request,
                                                        String headerActorId,
                                                        String actorRole) {
        ensureRuntimeEnabled();
        ensureAdminIfRolePresent(actorRole, "下线 Skill");
        AgentSkillPublicationRecord record = requireRecord(publicationId);
        ensureStatus(record, AgentSkillPublicationLifecycleStatus.READY, "只有 READY 发布单可以下线");
        String operatorId = requireText(firstNonBlank(headerActorId, request.operatorId()), "operatorId");
        String reason = requireText(request.comment(), "comment");
        validateLowSensitiveText(reason, "comment");
        return toView(lifecycleStore.save(record.deprecate(operatorId, reason, Instant.now())));
    }

    /**
     * 查询单个发布单。
     */
    public AgentSkillPublicationLifecycleView getPublication(String publicationId) {
        ensureRuntimeEnabled();
        return toView(requireRecord(publicationId));
    }

    /**
     * 查询发布单列表并输出状态聚合。
     *
     * <p>列表查询先保持低敏和轻量，后续接 MySQL 后可以扩展 page/pageSize、创建时间范围、审核人和租户套餐过滤。</p>
     */
    public AgentSkillPublicationLifecycleQueryResponse query(String tenantId,
                                                             String projectId,
                                                             String skillCode,
                                                             String domain,
                                                             String status,
                                                             Integer limit) {
        ensureRuntimeEnabled();
        AgentSkillPublicationLifecycleStatus parsedStatus = parseStatus(status);
        List<AgentSkillPublicationLifecycleView> views = lifecycleStore.query(
                        new AgentSkillPublicationLifecycleQuery(
                                blankToNull(tenantId),
                                blankToNull(projectId),
                                blankToNull(skillCode),
                                domain == null || domain.isBlank() ? null : normalizeToken(domain, null),
                                parsedStatus,
                                limit == null ? 50 : limit
                        )
                ).stream()
                .map(this::toView)
                .toList();
        Map<String, Long> statusCounts = new LinkedHashMap<>();
        for (AgentSkillPublicationLifecycleView view : views) {
            statusCounts.put(view.status(), statusCounts.getOrDefault(view.status(), 0L) + 1);
        }
        return new AgentSkillPublicationLifecycleQueryResponse(
                QUERY_SCHEMA_VERSION,
                QUERY_TYPE,
                views.size(),
                statusCounts,
                views,
                recommendedActions(statusCounts)
        );
    }

    private void ensureRuntimeEnabled() {
        if (!Boolean.TRUE.equals(runtimeProperties.getEnabled())) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT, "Agent Runtime 当前未启用");
        }
    }

    private AgentSkillPublicationRecord requireRecord(String publicationId) {
        String id = requireText(publicationId, "publicationId");
        return lifecycleStore.findById(id)
                .orElseThrow(() -> new PlatformBusinessException(PlatformErrorCode.NOT_FOUND,
                        "Skill 发布单不存在，publicationId=" + id));
    }

    private void ensureStatus(AgentSkillPublicationRecord record,
                              AgentSkillPublicationLifecycleStatus expected,
                              String message) {
        if (record.status() != expected) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    message + "，当前状态=" + record.status());
        }
    }

    private void ensureNoBlockingPolicyIssues(AgentSkillPublicationRecord record) {
        List<String> issues = policyIssues(record);
        List<String> blockingIssues = issues.stream()
                .filter(issue -> !"NO_TOOL_DEPENDENCY_DECLARED".equals(issue))
                .toList();
        if (!blockingIssues.isEmpty()) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "Skill 发布策略不完整，不能提交或发布：" + String.join(",", blockingIssues));
        }
    }

    private void ensureAdminIfRolePresent(String actorRole, String actionName) {
        if (actorRole == null || actorRole.isBlank()) {
            return;
        }
        String role = normalizeToken(actorRole, "");
        if (role.contains("ADMIN") || role.equals("PLATFORM_OWNER")) {
            return;
        }
        throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                "当前角色无权执行操作：" + actionName + "，actorRole=" + role);
    }

    private AgentSkillPublicationLifecycleView toView(AgentSkillPublicationRecord record) {
        return new AgentSkillPublicationLifecycleView(
                VIEW_SCHEMA_VERSION,
                record.publicationId(),
                record.tenantId(),
                record.projectId(),
                record.skillCode(),
                record.version(),
                record.displayName(),
                record.description(),
                record.domain(),
                record.riskLevel(),
                record.approvalPolicy(),
                record.auditRequired(),
                record.tenantScoped(),
                record.projectScoped(),
                record.requiredTools(),
                record.requiredPermissions(),
                record.memoryDependencies(),
                record.contentFingerprint(),
                record.status().name(),
                allowedNextActions(record.status()),
                policyIssues(record),
                record.createdBy(),
                record.submittedBy(),
                record.reviewedBy(),
                record.deprecatedBy(),
                record.createdAt(),
                record.submittedAt(),
                record.reviewedAt(),
                record.deprecatedAt(),
                record.updatedAt()
        );
    }

    private List<String> allowedNextActions(AgentSkillPublicationLifecycleStatus status) {
        return switch (status) {
            case DRAFT -> List.of("SUBMIT_REVIEW");
            case IN_REVIEW -> List.of("APPROVE", "REJECT");
            case READY -> List.of("DEPRECATE");
            case REJECTED, DEPRECATED -> List.of();
        };
    }

    private List<String> policyIssues(AgentSkillPublicationRecord record) {
        List<String> issues = new ArrayList<>();
        if (!SKILL_CODE_PATTERN.matcher(record.skillCode()).matches()) {
            issues.add("SKILL_CODE_FORMAT_INVALID");
        }
        if (!List.of("LOW", "MEDIUM", "HIGH", "CRITICAL").contains(record.riskLevel())) {
            issues.add("RISK_LEVEL_UNKNOWN");
        }
        if (!Boolean.TRUE.equals(record.auditRequired())) {
            issues.add("AUDIT_POLICY_MISSING");
        }
        if (!Boolean.TRUE.equals(record.tenantScoped())) {
            issues.add("TENANT_SCOPE_MISSING");
        }
        if (!Boolean.TRUE.equals(record.projectScoped())) {
            issues.add("PROJECT_SCOPE_MISSING");
        }
        if (record.requiredPermissions().isEmpty()) {
            issues.add("REQUIRED_PERMISSION_MISSING");
        }
        if (record.requiredTools().isEmpty()) {
            issues.add("NO_TOOL_DEPENDENCY_DECLARED");
        }
        if (List.of("HIGH", "CRITICAL").contains(record.riskLevel())
                && !"HUMAN_APPROVAL_REQUIRED".equals(record.approvalPolicy())) {
            issues.add("HUMAN_APPROVAL_REQUIRED_FOR_HIGH_RISK");
        }
        return issues;
    }

    private List<String> recommendedActions(Map<String, Long> statusCounts) {
        List<String> actions = new ArrayList<>();
        if (statusCounts.isEmpty()) {
            actions.add("当前没有 Skill 发布单；下一步应优先从核心治理域创建少量可审核 Skill，而不是继续扩散新能力。");
            return actions;
        }
        if (statusCounts.getOrDefault("DRAFT", 0L) > 0) {
            actions.add("存在草稿 Skill，应补齐审计、权限、隔离和审批策略后提交审核。");
        }
        if (statusCounts.getOrDefault("IN_REVIEW", 0L) > 0) {
            actions.add("存在审核中 Skill，应由管理员完成审核，避免能力市场长期积压。");
        }
        if (statusCounts.getOrDefault("READY", 0L) > 0) {
            actions.add("READY Skill 后续应绑定 Manifest、租户可见性和 Python Runtime 缓存指纹。");
        }
        if (statusCounts.getOrDefault("DEPRECATED", 0L) > 0) {
            actions.add("已下线 Skill 应保留审计记录，并在运行时目录中确保不再默认可见。");
        }
        return actions;
    }

    private AgentSkillPublicationLifecycleStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return AgentSkillPublicationLifecycleStatus.valueOf(normalizeToken(status, ""));
        } catch (IllegalArgumentException exception) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "不支持的 Skill 发布状态：" + status);
        }
    }

    private void validateSkillCode(String skillCode) {
        if (!SKILL_CODE_PATTERN.matcher(skillCode).matches()) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "skillCode 只能包含小写字母、数字、点、下划线和短横线，且长度需在 3-159 之间");
        }
    }

    private void validateLowSensitiveText(String text, String fieldName) {
        if (text == null || text.isBlank()) {
            return;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String marker : SENSITIVE_TEXT_MARKERS) {
            if (lower.contains(marker)) {
                throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                        fieldName + " 只能填写低敏摘要，不能包含 prompt、SQL、URL、凭据、工具参数或样本数据");
            }
        }
    }

    private String fingerprintDraft(String tenantId,
                                    String projectId,
                                    String skillCode,
                                    String version,
                                    String displayName,
                                    String description,
                                    String domain,
                                    String riskLevel,
                                    String approvalPolicy,
                                    List<String> requiredTools,
                                    List<String> requiredPermissions,
                                    List<String> memoryDependencies) {
        String source = String.join("|",
                VIEW_SCHEMA_VERSION,
                nullSafe(tenantId),
                nullSafe(projectId),
                nullSafe(skillCode),
                nullSafe(version),
                nullSafe(displayName),
                nullSafe(description),
                nullSafe(domain),
                nullSafe(riskLevel),
                nullSafe(approvalPolicy),
                String.join(",", requiredTools),
                String.join(",", requiredPermissions),
                String.join(",", memoryDependencies)
        );
        return sha256(source);
    }

    private String sha256(String source) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256，无法生成 Skill 发布内容指纹", exception);
        }
    }

    private List<String> normalizeList(List<String> values, boolean uppercase) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(value -> uppercase ? normalizeToken(value, value) : value)
                .distinct()
                .limit(50)
                .toList();
    }

    private String normalizeToken(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim().replace('-', '_').toUpperCase(Locale.ROOT);
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST, fieldName + " 不能为空");
        }
        return value.trim();
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String nullSafe(Object value) {
        return Objects.toString(value, "");
    }
}
