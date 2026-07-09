/**
 * @Author : Cui
 * @Date: 2026/05/10 14:05
 * @Description DataSmart Govern Backend - DatasourceProjectVisibility.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.support;

import com.czh.datasmart.govern.common.context.PlatformAuthorizedProjectHeaderSupport;
import com.czh.datasmart.govern.common.context.PlatformAuthorizedProjectRole;

import java.util.List;
import java.util.Optional;

/**
 * datasource-management 模块内的项目级可见范围描述。
 *
 * <p>这个 record 不直接对应数据库表，它是 Controller 把 gateway/permission-admin 透传的权限上下文
 * 翻译成 MyBatis 查询条件时使用的中间模型。之所以不让 Controller 直接解析 Header，是因为项目级数据范围
 * 属于安全边界：如果不同接口各自 split 字符串、各自理解空集合语义，很容易出现某个接口在
 * PROJECT 范围下“空授权集合退化成全租户可见”的严重越权问题。</p>
 *
 * @param requestedProjectId 调用方主动传入的 projectId；为空表示列表接口希望查看当前身份可见的所有项目
 * @param requestedWorkspaceId 历史兼容字段。当前产品层级已经收敛为租户 -> 项目 -> 数据源/同步任务，
 *                             因此正式页面不再用 workspaceId 过滤；该值默认保持为空，仅为旧脚本或历史审计保留扩展位。
 * @param authorizedProjectIds gateway 从 permission-admin 透传的授权项目集合
 * @param authorizedProjectRoles gateway 从 permission-admin 透传的项目角色集合。该字段用于判断 READER 是否只能查看、
 *                               MANAGER/OWNER 是否可以创建和管理数据源、SERVICE 是否可以执行受控机器协议。
 * @param projectScopeEnforced 是否必须按 authorizedProjectIds 强制过滤；只有 Header 明确声明 PROJECT 时才为 true
 */
public record DatasourceProjectVisibility(Long requestedProjectId,
                                          Long requestedWorkspaceId,
                                          List<Long> authorizedProjectIds,
                                          List<PlatformAuthorizedProjectRole> authorizedProjectRoles,
                                          boolean projectScopeEnforced) {

    /**
     * 查询某个项目在当前请求中的项目内角色。
     *
     * <p>这里不直接返回 Optional<PlatformAuthorizedProjectRole>，而是返回角色字符串。
     * 原因是业务服务只关心当前动作所需的角色强度，不应该继续依赖权限中心成员表的内部字段。</p>
     */
    public Optional<String> projectRole(Long projectId) {
        if (projectId == null || authorizedProjectRoles == null || authorizedProjectRoles.isEmpty()) {
            return Optional.empty();
        }
        return authorizedProjectRoles.stream()
                .filter(projectRole -> projectId.equals(projectRole.projectId()))
                .map(PlatformAuthorizedProjectRole::projectRole)
                .map(PlatformAuthorizedProjectHeaderSupport::normalizeProjectRole)
                .findFirst();
    }

    /**
     * 判断当前请求是否已经处在某个项目成员范围内。
     *
     * <p>实例级数据源授权不能绕过项目成员资格：用户即使被授予了某条数据源的 VIEW/USE/MANAGE，
     * 也必须先加入该数据源所在项目，授权才会生效。这个方法把“是否属于项目”的判断集中起来，
     * 避免列表、详情、编辑、元数据发现等入口各自解释项目范围。</p>
     */
    public boolean canReachProject(Long projectId) {
        if (!projectScopeEnforced) {
            return true;
        }
        return projectId != null
                && authorizedProjectIds != null
                && authorizedProjectIds.contains(projectId);
    }

    /**
     * 判断当前 actor 是否拥有项目内管理能力。
     *
     * <p>MANAGER 和 OWNER 可以创建、编辑、删除和授权数据源；READER 只能查看。
     * 该判断只代表项目内应用角色，不代表平台管理员或租户管理员的更高层数据范围。</p>
     */
    public boolean canManageProject(Long projectId) {
        return projectRole(projectId)
                .map(role -> "OWNER".equals(role) || "MANAGER".equals(role))
                .orElse(false);
    }

    /**
     * 判断当前 actor 是否可以使用该项目内的数据源。
     *
     * <p>使用数据源包括连接测试、元数据发现、只读 SQL 和创建同步任务时引用连接。
     * 这比单纯查看详情更敏感，因此 READER 默认不能使用；SERVICE 允许用于受控 worker/Agent 内部链路。</p>
     */
    public boolean canUseProject(Long projectId) {
        return projectRole(projectId)
                .map(role -> "OWNER".equals(role) || "MANAGER".equals(role) || "SERVICE".equals(role))
                .orElse(false);
    }
}
