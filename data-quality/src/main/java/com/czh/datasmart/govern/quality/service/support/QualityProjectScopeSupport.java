/**
 * @Author : Cui
 * @Date: 2026/05/10 14:40
 * @Description DataSmart Govern Backend - QualityProjectScopeSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.service.support;

import com.czh.datasmart.govern.common.context.PlatformAuthorizedProjectHeaderSupport;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * data-quality 项目级数据范围支撑组件。
 *
 * <p>该组件集中处理 `X-DataSmart-Data-Scope-Level` 和
 * `X-DataSmart-Authorized-Project-Ids` 的解释规则，避免每个 Controller 自己 split Header。
 * 权限边界代码最怕“每个接口理解一点点”，因为一旦某个接口把空授权集合误解成“不过滤”，
 * 就会导致 PROJECT 范围退化为租户全量可见。</p>
 *
 * <p>当前实现先对齐 datasource-management/data-sync 的 PROJECT 范围语义：</p>
 * <p>1. 只有 Header 明确声明 PROJECT 时才强制使用授权项目集合；</p>
 * <p>2. PROJECT 范围下如果授权集合为空，列表接口应返回空结果，详情接口应拒绝访问；</p>
 * <p>3. 调用方主动传入 projectId 时，该 projectId 必须属于授权集合；</p>
 * <p>4. 非 PROJECT 范围暂不在本组件做租户/平台级判断，后续会继续由 permission-admin 统一收敛。</p>
 */
@Component
public class QualityProjectScopeSupport {

    private static final String PROJECT_SCOPE = "PROJECT";

    /**
     * 将请求参数与 Header 解析成统一的项目可见范围。
     *
     * @param requestedProjectId 查询参数或请求体中的项目 ID
     * @param requestedWorkspaceId 查询参数或请求体中的工作空间 ID
     * @param dataScopeLevel gateway 透传的数据范围级别
     * @param authorizedProjectIdsHeader gateway 透传的项目授权集合 Header
     * @return 当前请求可复用的质量数据可见范围
     */
    public QualityProjectVisibility resolveVisibility(Long requestedProjectId,
                                                      Long requestedWorkspaceId,
                                                      String dataScopeLevel,
                                                      String authorizedProjectIdsHeader) {
        boolean projectScopeEnforced = PROJECT_SCOPE.equalsIgnoreCase(trimToEmpty(dataScopeLevel));
        List<Long> authorizedProjectIds = projectScopeEnforced
                ? PlatformAuthorizedProjectHeaderSupport.parse(authorizedProjectIdsHeader)
                : List.of();
        if (projectScopeEnforced && requestedProjectId != null && !authorizedProjectIds.contains(requestedProjectId)) {
            throw new IllegalArgumentException("当前身份不能访问未授权项目的数据质量资源，requestedProjectId=" + requestedProjectId);
        }
        return new QualityProjectVisibility(
                requestedProjectId,
                requestedWorkspaceId,
                authorizedProjectIds,
                projectScopeEnforced
        );
    }

    /**
     * 校验单条质量资源是否处于当前 PROJECT 授权范围内。
     *
     * <p>列表接口可以通过 `project_id IN (...)` 在数据库层过滤；但详情、更新、启停、运行检查这类接口
     * 通常只接收资源 ID。如果读取资源后不再校验 projectId，用户就可能通过猜测 ID 访问其他项目的规则、
     * 报告或异常样本。因此所有基于 ID 的入口都应该调用该方法。</p>
     *
     * @param resourceProjectId 资源自身归属的项目 ID
     * @param visibility 当前请求解析出的可见范围
     * @param resourceName 用于错误提示的人类可读资源名称
     */
    public void validateProjectReadable(Long resourceProjectId,
                                        QualityProjectVisibility visibility,
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
