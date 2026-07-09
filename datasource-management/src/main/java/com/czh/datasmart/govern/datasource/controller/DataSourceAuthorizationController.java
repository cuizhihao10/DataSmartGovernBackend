/**
 * @Author : Cui
 * @Date: 2026/07/09 01:07
 * @Description DataSmart Govern Backend - DataSourceAuthorizationController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.datasource.common.ApiResponse;
import com.czh.datasmart.govern.datasource.controller.dto.DataSourceAuthorizationView;
import com.czh.datasmart.govern.datasource.controller.dto.GrantDataSourceAuthorizationRequest;
import com.czh.datasmart.govern.datasource.controller.dto.RevokeDataSourceAuthorizationRequest;
import com.czh.datasmart.govern.datasource.entity.DataSourceConfig;
import com.czh.datasmart.govern.datasource.service.DataSourceAuthorizationService;
import com.czh.datasmart.govern.datasource.service.DataSourceManagementService;
import com.czh.datasmart.govern.datasource.service.support.DatasourceAuthorizationActorContext;
import com.czh.datasmart.govern.datasource.service.support.DatasourceProjectScopeSupport;
import com.czh.datasmart.govern.datasource.service.support.DatasourceProjectVisibility;
import com.czh.datasmart.govern.datasource.support.DataSourceAuthorizationAction;
import com.czh.datasmart.govern.datasource.support.DataSourceStatus;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.NoSuchElementException;

/**
 * 数据源授权管理控制器。
 *
 * <p>该控制器负责数据源列表中“授权给其他用户”的后端能力，包括查看授权清单、授予授权、撤销授权。
 * 它与 {@link DataSourceManagementController} 分开，是为了避免数据源主生命周期接口继续膨胀，也让前端可以清楚地区分：
 * 编辑/删除是数据源主记录管理，授权是资源实例 ACL 管理。</p>
 */
@RestController
@RequestMapping("/datasources/{datasourceId}/authorizations")
@RequiredArgsConstructor
public class DataSourceAuthorizationController {

    private final DataSourceManagementService dataSourceManagementService;
    private final DataSourceAuthorizationService dataSourceAuthorizationService;
    private final DatasourceProjectScopeSupport datasourceProjectScopeSupport;

    /**
     * 分页查看某个数据源的授权清单。
     *
     * <p>授权清单本身会暴露“哪些用户/角色可以访问该数据源”，属于治理信息，因此需要管理权限。
     * 当前规则是：项目 MANAGER/OWNER 可以查看；如果项目角色不足，但该 actor 具有该数据源 MANAGE 实例级授权，也可以查看。
     * 只读 READER 不应看到授权账本，避免把协作者名单、服务账号授权和治理职责暴露给无管理职责的人。</p>
     */
    @GetMapping
    public ResponseEntity<ApiResponse<IPage<DataSourceAuthorizationView>>> pageAuthorizations(
            @PathVariable Long datasourceId,
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) String subjectType,
            @RequestParam(required = false) String status,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) String actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_TYPE, required = false) String actorType,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_ROLES, required = false) String authorizedProjectRoles) {
        DatasourceAuthorizationActorContext actorContext = new DatasourceAuthorizationActorContext(actorId, actorRole, actorType);
        DataSourceConfig datasource = getManageableDataSource(
                datasourceId, dataScopeLevel, authorizedProjectIds, authorizedProjectRoles, actorContext);
        return ResponseEntity.ok(ApiResponse.success("数据源授权清单查询成功",
                dataSourceAuthorizationService.pageAuthorizations(
                        datasource, new Page<>(current, size), subjectType, status)));
    }

    /**
     * 授予或更新数据源授权。
     *
     * <p>重复授权同一主体会更新原有 ACTIVE 授权，而不是创建重复记录。
     * 这对前端很重要：用户在弹窗里修改动作集合后可以直接再次提交，不必先删除旧授权。</p>
     */
    @PostMapping
    public ResponseEntity<ApiResponse<DataSourceAuthorizationView>> grantAuthorization(
            @PathVariable Long datasourceId,
            @Valid @RequestBody GrantDataSourceAuthorizationRequest request,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) String actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_TYPE, required = false) String actorType,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_ROLES, required = false) String authorizedProjectRoles) {
        DatasourceAuthorizationActorContext actorContext = new DatasourceAuthorizationActorContext(actorId, actorRole, actorType);
        DataSourceConfig datasource = getManageableDataSource(
                datasourceId, dataScopeLevel, authorizedProjectIds, authorizedProjectRoles, actorContext);
        return ResponseEntity.ok(ApiResponse.success("数据源授权保存成功",
                dataSourceAuthorizationService.grantAuthorization(datasource, request, actorContext)));
    }

    /**
     * 撤销数据源授权。
     *
     * <p>这里使用 DELETE 语义是为了贴合前端“移除授权”的操作习惯，但服务端实际执行的是逻辑撤销。
     * 历史授权记录仍会保留，用于审计和问题复盘。</p>
     */
    @DeleteMapping("/{authorizationId}")
    public ResponseEntity<ApiResponse<DataSourceAuthorizationView>> revokeAuthorization(
            @PathVariable Long datasourceId,
            @PathVariable Long authorizationId,
            @RequestBody(required = false) RevokeDataSourceAuthorizationRequest request,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) String actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_TYPE, required = false) String actorType,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_ROLES, required = false) String authorizedProjectRoles) {
        DatasourceAuthorizationActorContext actorContext = new DatasourceAuthorizationActorContext(actorId, actorRole, actorType);
        DataSourceConfig datasource = getManageableDataSource(
                datasourceId, dataScopeLevel, authorizedProjectIds, authorizedProjectRoles, actorContext);
        return ResponseEntity.ok(ApiResponse.success("数据源授权已撤销",
                dataSourceAuthorizationService.revokeAuthorization(datasource, authorizationId, request, actorContext)));
    }

    /**
     * 读取并校验当前 actor 是否可以管理该数据源授权。
     *
     * <p>这里故意调用 validateProjectManageable，而不是 validateProjectReadable。
     * 因为“授权给其他用户”本质上是改变资源访问边界，比普通详情查看更敏感；
     * 即便前端隐藏授权按钮，后端也必须在直接调接口时拒绝 READER 越权。</p>
     */
    private DataSourceConfig getManageableDataSource(Long datasourceId,
                                                     String dataScopeLevel,
                                                     String authorizedProjectIds,
                                                     String authorizedProjectRoles,
                                                     DatasourceAuthorizationActorContext actorContext) {
        DataSourceConfig datasource = dataSourceManagementService.getById(datasourceId);
        if (datasource == null || DataSourceStatus.DELETED.equals(datasource.getStatus())) {
            throw new NoSuchElementException("数据源不存在或已删除: " + datasourceId);
        }
        DatasourceProjectVisibility visibility = datasourceProjectScopeSupport.resolveVisibility(
                null, null, dataScopeLevel, authorizedProjectIds, authorizedProjectRoles);
        try {
            datasourceProjectScopeSupport.validateProjectManageable(datasource.getProjectId(), visibility, "数据源授权");
            return datasource;
        } catch (IllegalArgumentException exception) {
            if (!visibility.canReachProject(datasource.getProjectId())) {
                throw exception;
            }
            if (isDatasourceOwner(datasource, actorContext)) {
                return datasource;
            }
            throw exception;
        }
    }

    private boolean isDatasourceOwner(DataSourceConfig datasource, DatasourceAuthorizationActorContext actorContext) {
        Long actorId = parseActorId(actorContext == null ? null : actorContext.actorId());
        return actorId != null && actorId.equals(datasource.getOwnerId());
    }

    private Long parseActorId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
