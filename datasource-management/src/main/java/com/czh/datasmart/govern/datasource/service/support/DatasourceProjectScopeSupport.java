/**
 * @Author : Cui
 * @Date: 2026/05/10 14:05
 * @Description DataSmart Govern Backend - DatasourceProjectScopeSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.support;

import com.czh.datasmart.govern.common.context.PlatformAuthorizedProjectHeaderSupport;
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
        boolean projectScopeEnforced = PROJECT_SCOPE.equalsIgnoreCase(trimToEmpty(dataScopeLevel));
        List<Long> authorizedProjectIds = projectScopeEnforced
                ? PlatformAuthorizedProjectHeaderSupport.parse(authorizedProjectIdsHeader)
                : List.of();
        if (projectScopeEnforced && requestedProjectId != null && !authorizedProjectIds.contains(requestedProjectId)) {
            throw new IllegalArgumentException("当前身份不能访问未授权项目的数据源资源，requestedProjectId=" + requestedProjectId);
        }
        return new DatasourceProjectVisibility(
                requestedProjectId,
                null,
                authorizedProjectIds,
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

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
