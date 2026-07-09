/**
 * @Author : Cui
 * @Date: 2026/05/10 14:05
 * @Description DataSmart Govern Backend - DatasourceProjectScopeSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.support;

import com.czh.datasmart.govern.common.context.PlatformAuthorizedProjectHeaderSupport;
import com.czh.datasmart.govern.common.context.PlatformAuthorizedProjectRole;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * datasource-management 项目级数据范围支撑组件。
 *
 * <p>本模块管理的是数据源连接、元数据发现、受控 SQL 访问和旧版同步控制面。真实商用场景中，
 * 数据源往往属于某个项目，例如“零售项目 A 的订单库”“风控项目的标签库”。
 * 如果只有 tenantId 而没有 projectId，项目负责人就可能看到同一租户内其他项目的数据源连接信息，
 * 甚至进一步触发元数据探查或只读 SQL，这在安全和合规上都不可接受。</p>
 *
 * <p>关于 workspace 的迁移说明：
 * 早期模型曾把数据源挂在 tenant/project/workspace 三层下，但当前产品已经决定只保留“租户 -> 项目”
 * 这一条用户可见业务层级。数据库列和部分历史审计仍保留 workspace_id 兼容旧数据；本组件从当前版本开始
 * 不再把请求里的 workspaceId 传播到查询条件，避免用户切到某个项目后因为不可见 workspace 过滤而看不到资源。</p>
 *
 * <p>当前组件遵循和 data-sync 一致的 PROJECT 范围语义：</p>
 * <p>1. 只有当 gateway 明确透传 `dataScopeLevel=PROJECT` 时，才强制使用授权项目集合过滤；</p>
 * <p>2. 授权项目集合为空时表示“没有任何项目可见”，列表应返回空结果，而不是退回租户范围；</p>
 * <p>3. 如果调用方主动传入 projectId，该 projectId 必须在授权集合中，否则直接拒绝；</p>
 * <p>4. 非 PROJECT 范围暂时不在这里做租户判断，租户边界由已有 tenantId 查询参数和后续 permission-admin
 * 统一策略继续收敛。</p>
 */
@Component
public class DatasourceProjectScopeSupport {

    private static final String PROJECT_SCOPE = "PROJECT";

    /**
     * 把 HTTP Header 和查询参数解析成模块内部可使用的可见范围。
     *
     * @param requestedProjectId 查询参数中的项目 ID，可为空
     * @param requestedWorkspaceId 历史兼容工作空间 ID。当前正式产品链路忽略该值，不再按 workspace 过滤。
     * @param dataScopeLevel gateway 透传的数据范围级别，例如 SELF、PROJECT、TENANT、PLATFORM
     * @param authorizedProjectIdsHeader gateway 透传的授权项目集合 Header
     * @return 可供 Controller 追加查询条件或详情校验使用的范围对象
     */
    public DatasourceProjectVisibility resolveVisibility(Long requestedProjectId,
                                                        Long requestedWorkspaceId,
                                                        String dataScopeLevel,
                                                        String authorizedProjectIdsHeader) {
        return resolveVisibility(requestedProjectId, requestedWorkspaceId, dataScopeLevel,
                authorizedProjectIdsHeader, null);
    }

    /**
     * 把 HTTP Header 和查询参数解析成包含项目角色的可见范围。
     *
     * <p>相比旧版方法，本重载会同时解析 `X-DataSmart-Authorized-Project-Roles`。
     * 列表查询仍主要依赖 projectId 集合；创建、编辑、删除、授权、连接测试和元数据发现等动作会继续检查项目角色，
     * 从而实现“前端隐藏按钮 + 后端拒绝越权接口”的闭环。</p>
     */
    public DatasourceProjectVisibility resolveVisibility(Long requestedProjectId,
                                                         Long requestedWorkspaceId,
                                                         String dataScopeLevel,
                                                         String authorizedProjectIdsHeader,
                                                         String authorizedProjectRolesHeader) {
        boolean projectScopeEnforced = PROJECT_SCOPE.equalsIgnoreCase(trimToEmpty(dataScopeLevel));
        List<Long> authorizedProjectIds = projectScopeEnforced
                ? PlatformAuthorizedProjectHeaderSupport.parse(authorizedProjectIdsHeader)
                : List.of();
        List<PlatformAuthorizedProjectRole> authorizedProjectRoles = projectScopeEnforced
                ? PlatformAuthorizedProjectHeaderSupport.parseRoles(authorizedProjectRolesHeader)
                : List.of();
        if (authorizedProjectIds.isEmpty() && !authorizedProjectRoles.isEmpty()) {
            /*
             * 项目角色 Header 的每个片段都包含 projectId，例如 `101:MANAGER`。
             * 正常情况下 gateway 会同时下发“授权项目 ID 集合”和“项目内角色集合”，但灰度发布或测试构造请求时
             * 可能只携带后者。由于角色 Header 仍然由 gateway/permission-admin 重建，属于可信控制面事实，
             * 这里可以安全地从角色集合推导 projectId，避免数据源列表、详情和创建入口被误判为无项目授权。
             */
            authorizedProjectIds = authorizedProjectRoles.stream()
                    .map(PlatformAuthorizedProjectRole::projectId)
                    .filter(projectId -> projectId != null && projectId > 0)
                    .distinct()
                    .toList();
        }
        if (projectScopeEnforced && requestedProjectId != null && !authorizedProjectIds.contains(requestedProjectId)) {
            throw new IllegalArgumentException("当前身份不能访问未授权项目的数据源资源，requestedProjectId=" + requestedProjectId);
        }
        return new DatasourceProjectVisibility(
                requestedProjectId,
                null,
                authorizedProjectIds,
                authorizedProjectRoles,
                projectScopeEnforced
        );
    }

    /**
     * 校验某条详情资源是否处于当前 PROJECT 授权范围内。
     *
     * <p>列表接口可以通过 `project_id IN (...)` 过滤，但详情、更新、启停、连接测试、元数据发现这类接口
     * 通常只接收资源 ID。如果不在资源读取后再次校验 projectId，用户就可能通过猜测 ID 访问其他项目的资源。
     * 因此所有基于 ID 的敏感入口都应该调用该方法。</p>
     *
     * @param resourceProjectId 资源自身归属的项目 ID
     * @param visibility 当前请求解析出的项目可见范围
     * @param resourceName 用于错误提示的人类可读资源名称
     */
    public void validateProjectReadable(Long resourceProjectId,
                                        DatasourceProjectVisibility visibility,
                                        String resourceName) {
        if (visibility == null || !visibility.projectScopeEnforced()) {
            return;
        }
        if (resourceProjectId == null || !visibility.authorizedProjectIds().contains(resourceProjectId)) {
            throw new IllegalArgumentException("当前身份不能访问未授权项目的" + resourceName
                    + "，resourceProjectId=" + resourceProjectId);
        }
    }

    /**
     * 校验当前 actor 是否可以使用某个项目内的数据源。
     *
     * <p>“使用”比“查看”更敏感。只读用户可以看到数据源名称、类型、描述等低敏配置，但不应该直接拿平台保存的凭据去测试连接、
     * 发现元数据或作为同步任务的源端/目标端。MANAGER/OWNER 可以使用，SERVICE 用于受控机器执行链路。</p>
     */
    public void validateProjectUsable(Long resourceProjectId,
                                      DatasourceProjectVisibility visibility,
                                      String resourceName) {
        validateProjectReadable(resourceProjectId, visibility, resourceName);
        if (visibility == null || !visibility.projectScopeEnforced()) {
            return;
        }
        if (!visibility.canUseProject(resourceProjectId)) {
            throw new IllegalArgumentException("当前身份在项目 " + resourceProjectId + " 下没有使用" + resourceName
                    + "的权限，请联系项目管理员授予 MANAGER/OWNER 角色，或通过数据源实例授权获得 USE 权限");
        }
    }

    /**
     * 校验当前 actor 是否可以管理某个项目内的数据源。
     *
     * <p>管理动作包括创建、编辑、启停、删除和授权。这里要求项目角色为 MANAGER 或 OWNER。
     * 这一步不是为了替代 permission-admin 的路由授权，而是在资源 ID 已经落到具体项目后做最后一公里校验，
     * 防止用户绕过前端按钮直接调用写接口。</p>
     */
    public void validateProjectManageable(Long resourceProjectId,
                                          DatasourceProjectVisibility visibility,
                                          String resourceName) {
        validateProjectReadable(resourceProjectId, visibility, resourceName);
        if (visibility == null || !visibility.projectScopeEnforced()) {
            return;
        }
        if (!visibility.canManageProject(resourceProjectId)) {
            throw new IllegalArgumentException("当前身份在项目 " + resourceProjectId + " 下没有管理" + resourceName
                    + "的权限，只有 MANAGER 或 OWNER 可以执行该操作");
        }
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
