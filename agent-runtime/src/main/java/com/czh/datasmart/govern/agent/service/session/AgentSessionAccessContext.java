/**
 * @Author : Cui
 * @Date: 2026/08/04 00:00
 * @Description DataSmart Govern Backend - AgentSessionAccessContext.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.session;

import com.czh.datasmart.govern.common.context.PlatformAuthorizedProjectRole;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Gateway 清理客户端伪造 Header 后注入的当前用户访问上下文。
 *
 * <p>该对象描述“谁在访问”，不描述 Agent 被允许执行什么工具。工具权限必须继续与 session delegation、
 * 工具策略和资源授权求交集。把上下文封装为不可变 record，可以避免控制器在多层调用之间遗漏某个隔离维度。</p>
 *
 * @param tenantId 当前用户所属租户
 * @param applicationId 当前项目所属且已由权限中心解析的产品应用
 * @param projectId 当前页面选择且已经过授权的项目
 * @param actorId 当前登录用户 ID
 * @param actorRole 当前请求的主角色，用于判断平台/租户管理只读能力
 * @param dataScopeLevel permission-admin 针对本次路由返回的数据范围：SELF、PROJECT、TENANT 或 PLATFORM
 * @param authorizedProjectIds permission-admin 物化的项目授权集合
 * @param authorizedProjectRoles permission-admin 物化的项目角色集合，用于判断当前项目是否具有 OWNER/MANAGER 能力
 */
public record AgentSessionAccessContext(Long tenantId,
                                         Long applicationId,
                                         Long projectId,
                                        String actorId,
                                        String actorRole,
                                        String dataScopeLevel,
                                        List<Long> authorizedProjectIds,
                                        List<PlatformAuthorizedProjectRole> authorizedProjectRoles) {

    /**
     * 保留原有四参数构造，兼容 Agent Session、工具审计和异步命令等尚未接入本次专用范围快照的调用方。
     *
     * <p>兼容构造不会凭空制造权限：缺少显式 dataScope 时，专业事实 Service 只能按 actor 自身读取，
     * 不会因为旧调用方携带管理员角色就自动获得跨用户读取能力。</p>
     */
    public AgentSessionAccessContext(Long tenantId,
                                      Long projectId,
                                      String actorId,
                                      String actorRole) {
        this(tenantId, null, projectId, actorId, actorRole, null, List.of(), List.of());
    }

    /**
     * 保留原有七参数构造，避免非专业事实链路在逐步接入 applicationId 时被静默改写上下文。
     *
     * <p>该兼容构造明确把 applicationId 设为 {@code null}。专业事实 Service 会把 null 当成不可信上下文
     * 而拒绝查询；其它尚未需要应用边界的旧会话接口则维持原有行为，避免把安全加固误扩散成无关功能回归。</p>
     */
    public AgentSessionAccessContext(Long tenantId,
                                      Long projectId,
                                      String actorId,
                                      String actorRole,
                                      String dataScopeLevel,
                                      List<Long> authorizedProjectIds,
                                      List<PlatformAuthorizedProjectRole> authorizedProjectRoles) {
        this(tenantId, null, projectId, actorId, actorRole, dataScopeLevel,
                authorizedProjectIds, authorizedProjectRoles);
    }

    /**
     * 规范化 Gateway 传入的不可变权限快照。
     *
     * <p>Header 解析已经在 Controller 完成，但这里仍然复制并清理集合，避免上层把可变 List 传入后在
     * Service 查询期间改变授权判断。该 record 是跨层传递的安全边界，必须保持快照语义。</p>
     */
    public AgentSessionAccessContext {
        dataScopeLevel = normalizeOptional(dataScopeLevel);
        authorizedProjectIds = authorizedProjectIds == null
                ? List.of()
                : authorizedProjectIds.stream()
                .filter(id -> id != null && id > 0)
                .distinct()
                .toList();
        authorizedProjectRoles = authorizedProjectRoles == null
                ? List.of()
                : authorizedProjectRoles.stream()
                .filter(role -> role != null)
                .toList();
    }

    private static final Set<String> PRIVILEGED_READ_ROLES = Set.of(
            "PLATFORM_ADMIN", "PLATFORM_ADMINISTRATOR", "TENANT_ADMIN", "TENANT_ADMINISTRATOR",
            "OPERATOR", "AUDITOR");

    /**
     * 判断调用方是否具备跨租户、跨项目的只读平台审计能力。
     *
     * <p>这里只用于会话查询可见性，不授予会话修改或工具执行权限。即使平台管理员能够查看会话，
     * {@code AgentSessionService} 仍要求会话发起人在原租户/项目内才能继续对话。</p>
     *
     * @return 角色是 PLATFORM_ADMIN 或 PLATFORM_ADMINISTRATOR 时返回 true
     */
    public boolean platformAdministrator() {
        String role = normalizedActorRole();
        if (role.isBlank()) {
            return false;
        }
        return "PLATFORM_ADMIN".equals(role) || "PLATFORM_ADMINISTRATOR".equals(role);
    }

    /**
     * 判断调用方是否可以执行管理或审计目的的扩展读取。
     *
     * <p>平台管理员、租户管理员、运维和审计角色可以读取权限范围内的他人会话；普通用户始终只能读取自己。
     * 该方法故意不命名为 {@code canManage}，避免调用方误把只读能力用于写操作。</p>
     *
     * @return 当前角色属于受控只读角色集合时返回 true
     */
    public boolean privilegedRead() {
        return PRIVILEGED_READ_ROLES.contains(normalizedActorRole());
    }

    /**
     * 返回规范化后的主角色。
     *
     * <p>角色 Header 只作为权限中心结果的一个维度使用；本类不会把它单独当作跨用户授权凭据。
     * 专业事实读取必须进一步结合 {@link #dataScopeLevel} 和项目授权快照。</p>
     */
    public String normalizedActorRole() {
        return actorRole == null || actorRole.isBlank()
                ? ""
                : actorRole.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * 返回规范化后的数据范围等级。
     *
     * <p>未知、缺失或空白范围都返回空字符串。空范围不是“全量范围”，而是“没有可用于扩大读取的可信范围”。</p>
     */
    public String normalizedDataScopeLevel() {
        return dataScopeLevel == null || dataScopeLevel.isBlank()
                ? ""
                : dataScopeLevel.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * 判断当前项目是否出现在 permission-admin 的可信授权快照中。
     *
     * <p>PROJECT 范围下，空集合必须解释为“没有项目授权”，不能退化成“当前 projectId 默认可用”。
     * 项目角色 Header 是另一种等价的物化形式，因此两者任一命中即可证明当前项目在授权集合内。</p>
     */
    public boolean currentProjectIsAuthorized() {
        if (projectId == null || projectId <= 0) {
            return false;
        }
        return authorizedProjectIds.contains(projectId)
                || authorizedProjectRoles.stream()
                .anyMatch(role -> projectId.equals(role.projectId()));
    }

    /**
     * 判断当前项目角色是否具备查看项目内其他 actor 事实的管理能力。
     *
     * <p>READER 只能查看自己的事实；OWNER/MANAGER 才能在显式 PROJECT 数据范围下查看项目内其他用户。
     * 这是把“角色名称”和“项目级授权快照”求交集，而不是因为 actorRole 看起来像管理员就直接放大。</p>
     */
    public boolean currentProjectAllowsOtherActors() {
        return authorizedProjectRoles.stream()
                .filter(role -> projectId != null && projectId.equals(role.projectId()))
                .map(PlatformAuthorizedProjectRole::projectRole)
                .map(value -> value == null ? "" : value.trim().toUpperCase(Locale.ROOT))
                .anyMatch(Set.of("OWNER", "MANAGER")::contains);
    }

    /**
     * 根据“角色 + 数据范围 + 项目授权”三者交集判断是否可以读取当前项目内其他用户的事实。
     *
     * <p>这是专业 Agent turn 事实的专用安全合同：</p>
     * <ul>
     *     <li>SELF 永远只能读取当前 actor；</li>
     *     <li>PROJECT 必须命中当前项目授权集合，并且具有 OWNER/MANAGER 项目角色，或命中已存在的项目审计角色；</li>
     *     <li>TENANT 只允许租户/平台管理员、运维或审计角色在当前租户内跨 actor；</li>
     *     <li>PLATFORM 只允许平台管理员，且 Service 仍会精确比较事实 tenant/project，不会扫描全库。</li>
     * </ul>
     *
     * <p>方法只决定“是否能看同一当前项目内其他 actor”，不改变 tenant/project 边界，也不授予写权限。</p>
     */
    public boolean canReadOtherActorsForSpecialistFacts() {
        return switch (normalizedDataScopeLevel()) {
            case "PROJECT" -> currentProjectIsAuthorized()
                    && (currentProjectAllowsOtherActors() || privilegedRead());
            case "TENANT" -> tenantWideReadRole();
            case "PLATFORM" -> platformAdministrator();
            default -> false;
        };
    }

    /**
     * TENANT 范围的跨 actor 角色集合。
     *
     * <p>这里保留现有事实读取链路中的只读运维/审计角色，但仍要求 Gateway 显式返回 TENANT 范围；
     * 只有角色没有范围时不会通过本方法。</p>
     */
    private boolean tenantWideReadRole() {
        return Set.of(
                "TENANT_ADMIN", "TENANT_ADMINISTRATOR",
                "PLATFORM_ADMIN", "PLATFORM_ADMINISTRATOR",
                "OPERATOR", "AUDITOR"
        ).contains(normalizedActorRole());
    }

    /** 规范化可选字符串；不把缺失的安全事实转换成可用的默认权限。 */
    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }
}
