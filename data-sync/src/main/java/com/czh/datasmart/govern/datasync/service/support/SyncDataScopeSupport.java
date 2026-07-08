/**
 * @Author : Cui
 * @Date: 2026/05/07 21:30
 * @Description DataSmart Govern Backend - SyncDataScopeSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 数据同步数据范围支撑组件。
 *
 * <p>data-sync 是高风险模块：它不仅能读数据，还可能把数据写入目标端或导出到文件。
 * 如果没有租户范围控制，普通用户可能通过构造 tenantId 查询或创建其他租户的同步模板/任务。
 *
 * <p>当前项目还没有把 permission-admin 的数据范围策略远程下沉到 data-sync，
 * 所以这里先实现两层规则：
 * 1. 如果请求经过 gateway 且 gateway 已经透传 permission-admin 的 dataScopeLevel，则优先使用该范围；
 * 2. 如果没有范围 Header，说明请求可能来自本地调试、内部调度或迁移期环境，则根据 actorRole 做本地兜底推断。
 *
 * <p>本地兜底规则：
 * 1. 平台管理员和服务账号可以跨租户，用于平台运维和内部自动化；
 * 2. 租户管理员、运营、审计和普通用户默认只能访问自身租户；
 * 3. 请求中的 tenantId 只能缩小范围，不能扩大权限。
 *
 * <p>PROJECT 范围需要“当前用户可访问项目集合”才能做到完全自动收敛。
 * permission-admin 会把 `${actorProjectIds}` 物化为 Header，gateway 可信透传，data-sync 再把集合转换为 `project_id IN (...)`。
 * 如果 permission-admin 明确返回 PROJECT 范围但集合为空，data-sync 会返回空可见集合，而不是退回租户范围。
 */
@Component
public class SyncDataScopeSupport {

    private static final long PLATFORM_TENANT_ID = 0L;
    private static final long DEFAULT_FLASHSYNC_TENANT_ID = 10L;
    private static final long DEFAULT_FLASHSYNC_PROJECT_ID = 101L;
    private static final Set<String> CROSS_TENANT_ROLES = Set.of(
            "PLATFORM_ADMINISTRATOR",
            "SERVICE_ACCOUNT"
    );

    private static final Map<String, String> FALLBACK_SCOPE_BY_ROLE = Map.of(
            "ORDINARY_USER", "SELF",
            "PROJECT_OWNER", "PROJECT",
            "OPERATOR", "TENANT",
            "AUDITOR", "TENANT",
            "TENANT_ADMINISTRATOR", "TENANT",
            "PLATFORM_ADMINISTRATOR", "PLATFORM",
            "SERVICE_ACCOUNT", "PLATFORM"
    );

    /**
     * 解析创建对象时应使用的租户 ID。
     *
     * @param requestedTenantId 请求体中显式声明的租户
     * @param actorContext 当前操作者上下文
     * @return 最终可信租户 ID
     */
    public Long resolveTenantForCreate(Long requestedTenantId, SyncActorContext actorContext) {
        String role = normalizeRole(actorContext);
        if (CROSS_TENANT_ROLES.contains(role)) {
            Long actorTenantId = actorContext == null ? null : actorContext.tenantId();
            if (requestedTenantId == null && actorTenantId == null) {
                return DEFAULT_FLASHSYNC_TENANT_ID;
            }
            return requestedTenantId == null ? normalizeTenant(actorTenantId) : requestedTenantId;
        }
        Long actorTenantId = actorContext == null || actorContext.tenantId() == null
                ? DEFAULT_FLASHSYNC_TENANT_ID
                : normalizeTenant(actorContext.tenantId());
        if (requestedTenantId != null && !actorTenantId.equals(requestedTenantId)) {
            throw new PlatformBusinessException(PlatformErrorCode.TENANT_SCOPE_DENIED,
                    "当前身份不能为其他租户创建同步对象，actorTenantId=" + actorTenantId + ", requestedTenantId=" + requestedTenantId);
        }
        return actorTenantId;
    }

    /**
     * 解析创建模板/任务时应写入的项目 ID。
     *
     * <p>产品交互上，项目应来自“当前项目切换器”和登录上下文，而不是让用户在表单里填写一个数字 ID。这里的优先级是：</p>
     * <p>1. 网关或受信服务间调用注入的 {@code X-DataSmart-Project-Id}；</p>
     * <p>2. 旧接口 request body 中的 projectId，作为历史兼容入口；</p>
     * <p>3. 如果权限中心只给了一个授权项目，则自动落到该项目；</p>
     * <p>4. 本地 FlashSync 默认开租项目 {@code 101}。</p>
     *
     * <p>如果 Header 和 request body 同时存在但不一致，必须拒绝。原因是 request body 来自浏览器或脚本，不应该覆盖可信上下文；
     * 否则用户切换在 A 项目，却通过接口参数把资源写到 B 项目，会破坏项目级隔离和后续审计。</p>
     */
    public Long resolveProjectForCreate(Long requestedProjectId, SyncActorContext actorContext) {
        Long contextProjectId = actorContext == null ? null : actorContext.projectId();
        if (contextProjectId != null) {
            if (requestedProjectId != null && !contextProjectId.equals(requestedProjectId)) {
                throw new PlatformBusinessException(PlatformErrorCode.TENANT_SCOPE_DENIED,
                        "请求项目与当前上下文项目不一致，contextProjectId=" + contextProjectId
                                + ", requestedProjectId=" + requestedProjectId);
            }
            return contextProjectId;
        }
        if (requestedProjectId != null) {
            validateRequestedProjectInAuthorizedSet(requestedProjectId, actorContext);
            return requestedProjectId;
        }
        List<Long> authorizedProjectIds = actorContext == null || actorContext.authorizedProjectIds() == null
                ? List.of()
                : actorContext.authorizedProjectIds().stream()
                .filter(id -> id != null && id > 0)
                .distinct()
                .toList();
        if (authorizedProjectIds.size() == 1) {
            return authorizedProjectIds.get(0);
        }
        return DEFAULT_FLASHSYNC_PROJECT_ID;
    }

    /**
     * 解析创建模板/任务时的历史工作空间字段。
     *
     * <p>当前产品层级已经收敛为“租户 -> 项目 -> 数据源/同步任务”，工作空间不再作为用户创建资源时需要感知、
     * 选择或填写的业务层级。因此新建资源不再写入默认 {@code workspaceId=10001}，而是统一返回 {@code null}。
     * 数据库表中的 {@code workspace_id} 暂时保留，是为了兼容历史执行记录、审计表、迁移脚本和部分 Agent 内部
     * workspace key 语义；但它已经不是新建数据同步资源的产品归属维度。</p>
     *
     * <p>这里采用“读兼容、写收敛”的迁移策略：旧请求体或旧 Header 即使仍携带 workspaceId，也不会继续影响
     * 新创建的模板和任务。这样可以避免页面已经隐藏工作空间后，后端仍然把资源写入某个不可见空间，最终造成
     * “我切到项目里却看不到自己刚创建的数据源/任务”的体验问题。</p>
     */
    public Long resolveWorkspaceForCreate(Long requestedWorkspaceId, SyncActorContext actorContext) {
        return null;
    }

    /**
     * 解析查询租户范围。
     *
     * <p>返回 null 表示允许查询全平台，仅平台管理员或服务账号可以得到 null。
     */
    public Long resolveTenantForQuery(Long requestedTenantId, SyncActorContext actorContext) {
        SyncDataVisibility visibility = resolveVisibility(requestedTenantId, actorContext);
        return visibility.tenantId();
    }

    /**
     * 解析列表查询可见范围。
     *
     * <p>相比 resolveTenantForQuery 只返回 tenantId，本方法会额外告诉调用方是否需要限制为“本人相关”。
     * 例如普通用户查看同步任务列表时，只能看到 ownerId=actorId 的任务；
     * 审计员查看同一接口时，可以看到租户内全部任务，但只能通过 GET 路由访问，不能执行写操作。
     */
    public SyncDataVisibility resolveVisibility(Long requestedTenantId, SyncActorContext actorContext) {
        return resolveVisibility(requestedTenantId, null, null, actorContext);
    }

    /**
     * 解析带项目/工作空间条件的列表查询可见范围。
     */
    public SyncDataVisibility resolveVisibility(Long requestedTenantId,
                                                Long requestedProjectId,
                                                Long requestedWorkspaceId,
                                                SyncActorContext actorContext) {
        String scopeLevel = resolveScopeLevel(actorContext);
        boolean approvalRequired = Boolean.TRUE.equals(actorContext == null ? null : actorContext.approvalRequired());
        String scopeExpression = actorContext == null ? null : actorContext.dataScopeExpression();

        if ("PLATFORM".equals(scopeLevel)) {
            return new SyncDataVisibility(requestedTenantId, requestedProjectId, List.of(), null,
                    false, false, scopeLevel, scopeExpression, approvalRequired);
        }

        Long actorTenantId = normalizeTenant(actorContext == null ? null : actorContext.tenantId());
        if (requestedTenantId != null && !actorTenantId.equals(requestedTenantId)) {
            throw new PlatformBusinessException(PlatformErrorCode.TENANT_SCOPE_DENIED,
                    "当前身份不能查询其他租户的同步数据，actorTenantId=" + actorTenantId + ", requestedTenantId=" + requestedTenantId);
        }
        boolean selfOnly = "SELF".equals(scopeLevel);
        boolean projectScopeEnforced = isExplicitGatewayProjectScope(actorContext);
        List<Long> authorizedProjectIds = resolveAuthorizedProjectIds(scopeLevel, requestedProjectId, actorContext);
        return new SyncDataVisibility(actorTenantId, requestedProjectId, authorizedProjectIds, null,
                projectScopeEnforced, selfOnly, scopeLevel, scopeExpression, approvalRequired);
    }

    /**
     * 解析 PROJECT 范围下真正可落地的项目集合。
     *
     * <p>这里区分两种场景：
     * 1. gateway 明确透传了 `dataScopeLevel=PROJECT`，说明 permission-admin 已参与判定，此时必须使用授权项目集合；
     * 2. 本地开发或内部调用没有 dataScopeLevel，只是通过角色兜底推断为 PROJECT，此时不强制空集合过滤，避免迁移期调用全部变空。
     *
     * <p>如果请求主动传入 projectId，则必须属于授权集合；否则说明调用方试图查询未授权项目，应直接拒绝。
     * 如果没有传 projectId，则返回完整授权集合，让 Service 层追加 `project_id IN (...)`。
     */
    public List<Long> resolveAuthorizedProjectIds(String scopeLevel,
                                                  Long requestedProjectId,
                                                  SyncActorContext actorContext) {
        if (!"PROJECT".equals(scopeLevel)) {
            return List.of();
        }

        boolean explicitGatewayScope = isExplicitGatewayProjectScope(actorContext);
        List<Long> authorizedProjectIds = actorContext == null || actorContext.authorizedProjectIds() == null
                ? List.of()
                : actorContext.authorizedProjectIds().stream()
                .filter(id -> id != null && id > 0)
                .distinct()
                .toList();

        if (!explicitGatewayScope) {
            return List.of();
        }
        if (requestedProjectId != null && !authorizedProjectIds.contains(requestedProjectId)) {
            throw new PlatformBusinessException(PlatformErrorCode.TENANT_SCOPE_DENIED,
                    "当前身份不能访问未授权项目的同步数据，requestedProjectId=" + requestedProjectId);
        }
        if (requestedProjectId != null) {
            return List.of();
        }
        return authorizedProjectIds;
    }

    /**
     * 校验写入类动作是否可以落到指定项目。
     *
     * <p>列表查询和详情查询只解决“能不能看”的问题，创建模板、创建任务、生成事故这类动作还要解决“能不能写”的问题。
     * 对 data-sync 来说，写入风险更高：一个错误创建的同步模板可能后续被调度、审批或执行器消费，
     * 最终触发跨项目的数据读取、数据写入、离线导出或历史回放。
     *
     * <p>因此当 gateway 明确透传 `dataScopeLevel=PROJECT` 时，写操作必须满足两个条件：
     * 1. 请求必须明确落到一个 projectId，不能用 null 表示“租户级模板”来绕过项目授权；
     * 2. projectId 必须位于 permission-admin 物化出的 authorizedProjectIds 中。
     *
     * <p>如果当前不是显式 PROJECT 范围，例如平台管理员、租户管理员或内部迁移任务，则不在这里强行要求 projectId。
     * 这样既保护普通项目角色，又保留平台运维和迁移期的必要弹性。
     *
     * @param requestedTenantId 请求希望写入的租户
     * @param requestedProjectId 请求希望写入的项目
     * @param requestedWorkspaceId 请求希望写入的工作空间，可为空
     * @param actorContext 当前操作者上下文，通常来自 gateway 可信 Header
     * @param resourceName 资源名称，用于错误信息中说明被保护的业务对象
     */
    public void validateProjectWritable(Long requestedTenantId,
                                        Long requestedProjectId,
                                        Long requestedWorkspaceId,
                                        SyncActorContext actorContext,
                                        String resourceName) {
        SyncDataVisibility visibility = resolveVisibility(
                requestedTenantId, requestedProjectId, requestedWorkspaceId, actorContext);
        if (!visibility.projectScopeEnforced()) {
            return;
        }
        if (requestedProjectId == null) {
            throw new PlatformBusinessException(PlatformErrorCode.TENANT_SCOPE_DENIED,
                    "PROJECT 范围下创建" + resourceName + "必须明确指定授权项目，不能写入无项目归属资源");
        }
    }

    /**
     * 校验某条资源是否对当前操作者可见。
     */
    public void validateTenantReadable(Long resourceTenantId, SyncActorContext actorContext) {
        SyncDataVisibility visibility = resolveVisibility(resourceTenantId, actorContext);
        if (visibility.tenantId() == null) {
            return;
        }
        Long normalizedResourceTenantId = normalizeTenant(resourceTenantId);
        if (!visibility.tenantId().equals(normalizedResourceTenantId)) {
            throw new PlatformBusinessException(PlatformErrorCode.TENANT_SCOPE_DENIED,
                    "当前身份不能访问其他租户的同步数据，actorTenantId=" + visibility.tenantId() + ", resourceTenantId=" + normalizedResourceTenantId);
        }
    }

    /**
     * 校验带负责人/创建人语义的资源是否可读。
     *
     * <p>普通租户隔离只能挡住跨租户访问，挡不住“同租户内 A 用户查看 B 用户任务”的场景。
     * SELF 范围就是为这个问题准备的：当 permission-admin 返回 SELF，业务服务必须继续检查 ownerId、createdBy、
     * assignedOperatorId 等领域字段，而不能只检查 tenantId。
     */
    public void validateOwnedReadable(Long resourceTenantId,
                                      Long ownerLikeActorId,
                                      SyncActorContext actorContext,
                                      String resourceName) {
        validateOwnedReadable(resourceTenantId, null, ownerLikeActorId, actorContext, resourceName);
    }

    /**
     * 校验带项目字段的资源是否可读。
     *
     * <p>列表查询可以通过 `project_id IN (...)` 收敛，但详情接口通常只传资源 ID。
     * 如果这里不继续校验 resourceProjectId，项目负责人可能通过猜测 ID 读取同租户其他项目的详情。
     */
    public void validateOwnedReadable(Long resourceTenantId,
                                      Long resourceProjectId,
                                      Long ownerLikeActorId,
                                      SyncActorContext actorContext,
                                      String resourceName) {
        SyncDataVisibility visibility = resolveVisibility(resourceTenantId, actorContext);
        if (visibility.tenantId() != null && !visibility.tenantId().equals(normalizeTenant(resourceTenantId))) {
            throw new PlatformBusinessException(PlatformErrorCode.TENANT_SCOPE_DENIED,
                    "当前身份不能访问其他租户的" + resourceName + "，actorTenantId=" + visibility.tenantId()
                            + ", resourceTenantId=" + normalizeTenant(resourceTenantId));
        }
        validateProjectReadable(visibility, resourceProjectId, resourceName);
        if (visibility.selfOnly() && !sameActor(ownerLikeActorId, actorContext)) {
            throw new PlatformBusinessException(PlatformErrorCode.TENANT_SCOPE_DENIED,
                    "当前身份只能访问本人相关的" + resourceName + "，actorId="
                            + (actorContext == null ? null : actorContext.actorId()) + ", ownerId=" + ownerLikeActorId);
        }
    }

    /**
     * 校验“多个责任人字段”中的任意一个是否匹配当前操作者。
     *
     * <p>事故记录这类运营对象通常不只有一个 owner：
     * operatorId 表示创建事故的人，assignedOperatorId 表示当前负责人。
     * SELF 范围下，只要当前用户是创建人或负责人之一，就可以读取该事故；否则即使同租户也不能看到。
     */
    public void validateAnyActorReadable(Long resourceTenantId,
                                         SyncActorContext actorContext,
                                         String resourceName,
                                         Long... actorLikeIds) {
        validateAnyActorReadable(resourceTenantId, null, actorContext, resourceName, actorLikeIds);
    }

    /**
     * 校验带项目字段、且有多个责任人字段的资源是否可读。
     */
    public void validateAnyActorReadable(Long resourceTenantId,
                                         Long resourceProjectId,
                                         SyncActorContext actorContext,
                                         String resourceName,
                                         Long... actorLikeIds) {
        SyncDataVisibility visibility = resolveVisibility(resourceTenantId, actorContext);
        if (visibility.tenantId() != null && !visibility.tenantId().equals(normalizeTenant(resourceTenantId))) {
            throw new PlatformBusinessException(PlatformErrorCode.TENANT_SCOPE_DENIED,
                    "当前身份不能访问其他租户的" + resourceName + "，actorTenantId=" + visibility.tenantId()
                            + ", resourceTenantId=" + normalizeTenant(resourceTenantId));
        }
        validateProjectReadable(visibility, resourceProjectId, resourceName);
        if (!visibility.selfOnly()) {
            return;
        }
        for (Long actorLikeId : actorLikeIds) {
            if (sameActor(actorLikeId, actorContext)) {
                return;
            }
        }
        throw new PlatformBusinessException(PlatformErrorCode.TENANT_SCOPE_DENIED,
                "当前身份只能访问本人相关的" + resourceName + "，actorId="
                        + (actorContext == null ? null : actorContext.actorId()));
    }

    /**
     * 校验旧 request body 中显式传入的 projectId 是否落在权限中心授权项目集合内。
     *
     * <p>只有当 gateway 明确声明当前是 PROJECT 范围时才强制校验授权集合；本地开发、服务账号或迁移脚本没有注入 dataScopeLevel 时，
     * 仍允许按默认开租上下文继续运行，避免把本地闭环调试误判成权限异常。</p>
     */
    private void validateRequestedProjectInAuthorizedSet(Long requestedProjectId, SyncActorContext actorContext) {
        if (!isExplicitGatewayProjectScope(actorContext)) {
            return;
        }
        List<Long> authorizedProjectIds = actorContext == null || actorContext.authorizedProjectIds() == null
                ? List.of()
                : actorContext.authorizedProjectIds();
        if (!authorizedProjectIds.contains(requestedProjectId)) {
            throw new PlatformBusinessException(PlatformErrorCode.TENANT_SCOPE_DENIED,
                    "当前身份不能在未授权项目下创建同步对象，requestedProjectId=" + requestedProjectId);
        }
    }

    private boolean isExplicitGatewayProjectScope(SyncActorContext actorContext) {
        return actorContext != null
                && actorContext.dataScopeLevel() != null
                && "PROJECT".equalsIgnoreCase(actorContext.dataScopeLevel());
    }

    private void validateProjectReadable(SyncDataVisibility visibility, Long resourceProjectId, String resourceName) {
        if (!visibility.projectScopeEnforced()) {
            return;
        }
        if (resourceProjectId == null || !visibility.authorizedProjectIds().contains(resourceProjectId)) {
            throw new PlatformBusinessException(PlatformErrorCode.TENANT_SCOPE_DENIED,
                    "当前身份不能访问未授权项目的" + resourceName + "，resourceProjectId=" + resourceProjectId);
        }
    }

    private String resolveScopeLevel(SyncActorContext actorContext) {
        String scopeLevel = actorContext == null ? null : actorContext.dataScopeLevel();
        if (scopeLevel != null && !scopeLevel.isBlank()) {
            return scopeLevel.trim().toUpperCase(Locale.ROOT);
        }
        return FALLBACK_SCOPE_BY_ROLE.getOrDefault(normalizeRole(actorContext), "TENANT");
    }

    private String normalizeRole(SyncActorContext actorContext) {
        String role = actorContext == null ? null : actorContext.actorRole();
        return role == null || role.isBlank() ? "USER" : role.trim().toUpperCase(Locale.ROOT);
    }

    private Long normalizeTenant(Long tenantId) {
        return tenantId == null ? PLATFORM_TENANT_ID : tenantId;
    }

    private boolean sameActor(Long ownerLikeActorId, SyncActorContext actorContext) {
        Long actorId = actorContext == null ? null : actorContext.actorId();
        return actorId != null && actorId.equals(ownerLikeActorId);
    }

    /**
     * 将授权项目集合稳定地落到查询条件里。
     *
     * <p>这段逻辑是 data-sync 里最常见的项目范围过滤：
     * - 如果 gateway 已经明确告诉我们当前是 PROJECT 范围，就必须继续做后端收口；
     * - 如果请求本身已经显式指定了 projectId，就不再重复拼 `IN (...)`；
     * - 如果授权项目集合为空，就直接返回 `1 = 0`，避免“没有授权”误退化成“全量可见”。
     *
     * <p>把它放在 data-scope 支持组件里，后续模板、任务、执行、事故、审计等列表查询都能复用同一语义。
     *
     * @param wrapper MyBatis 查询包装器
     * @param projectColumn 目标项目列
     * @param visibility 由 permission-admin 语义翻译得到的可见范围
     * @param <T> 实体类型
     */
    public <T> void applyAuthorizedProjectScope(LambdaQueryWrapper<T> wrapper,
                                                 SFunction<T, Long> projectColumn,
                                                 SyncDataVisibility visibility) {
        if (wrapper == null || projectColumn == null || visibility == null) {
            return;
        }
        if (!visibility.projectScopeEnforced() || visibility.projectId() != null) {
            return;
        }
        List<Long> projectIds = visibility.authorizedProjectIds();
        if (projectIds == null || projectIds.isEmpty()) {
            wrapper.apply("1 = 0");
            return;
        }
        wrapper.in(projectColumn, projectIds);
    }
}
