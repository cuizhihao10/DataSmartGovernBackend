package com.czh.datasmart.govern.datasource.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.datasource.common.ApiResponse;
import com.czh.datasmart.govern.datasource.controller.dto.CreateDataSourceRequest;
import com.czh.datasmart.govern.datasource.controller.dto.MetadataDiscoveryRequest;
import com.czh.datasmart.govern.datasource.controller.dto.ReadOnlySqlExecutionRequest;
import com.czh.datasmart.govern.datasource.controller.dto.ReadOnlySqlExecutionResult;
import com.czh.datasmart.govern.datasource.controller.dto.TestDataSourceConnectionRequest;
import com.czh.datasmart.govern.datasource.controller.dto.TestExistingDataSourceConnectionRequest;
import com.czh.datasmart.govern.datasource.controller.dto.UpdateDataSourceRequest;
import com.czh.datasmart.govern.datasource.entity.DataSourceCapabilityProfile;
import com.czh.datasmart.govern.datasource.entity.DataSourceConfig;
import com.czh.datasmart.govern.datasource.entity.DataSourceConnectionTestResult;
import com.czh.datasmart.govern.datasource.entity.DataSourceMetadataDiscoveryResult;
import com.czh.datasmart.govern.datasource.entity.DataSourceReadOnlySqlExecutionAudit;
import com.czh.datasmart.govern.datasource.service.DataSourceAuthorizationService;
import com.czh.datasmart.govern.datasource.service.DataSourceManagementService;
import com.czh.datasmart.govern.datasource.service.support.DataSourceCredentialCipherSupport;
import com.czh.datasmart.govern.datasource.service.support.DatasourceAuthorizationActorContext;
import com.czh.datasmart.govern.datasource.service.support.DatasourceProjectScopeSupport;
import com.czh.datasmart.govern.datasource.service.support.DatasourceProjectVisibility;
import com.czh.datasmart.govern.datasource.support.DataSourceAuthorizationAction;
import com.czh.datasmart.govern.datasource.support.DataSourceStatus;
import com.czh.datasmart.govern.datasource.support.DataSourceUsagePurpose;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * @Author : Cui
 * @Date: 2026/4/18 22:00
 * @Description DataSmart Govern Backend - DataSourceManagementController.java
 * @Version:1.0.0
 *
 * 数据源管理控制器。
 *
 * <p>这个控制器暴露的是“连接配置治理接口”，不是直接读取外部业务库的普通业务接口。
 * 它负责管理外部数据源如何被登记、查看、更新、启停、测试连接、发现元数据，以及在受控边界下执行只读 SQL。</p>
 *
 * <p>本控制器同时处理两类权限边界：</p>
 * <p>1. 项目范围权限：由 gateway/permission-admin 计算后通过数据范围 Header 透传，回答“当前 actor 能不能访问这个项目里的资源”。</p>
 * <p>2. 数据源实例授权：由 datasource-management 自己维护 ACL 账本，回答“即使 actor 不是项目负责人，是否被单独授权使用某条数据源”。</p>
 *
 * <p>两者不能互相替代：项目范围适合管理者和项目成员的天然可见性，实例授权适合把某条源端/目标端连接临时共享给其他用户、
 * 服务账号或角色。列表接口会把二者做并集；详情、连接测试、元数据发现和只读 SQL 等按 ID 操作的接口会先检查项目范围，
 * 项目范围不通过时再检查实例级授权，避免用户猜 ID 越权。</p>
 */
@RestController
@RequestMapping("/datasources")
@RequiredArgsConstructor
public class DataSourceManagementController {

    /**
     * FlashSync 本地开租默认租户。
     *
     * <p>正式生产环境里，tenant/project 应由 gateway 根据登录态和项目切换上下文注入。
     * 这里保留默认值，是为了本地开发、接口调试和没有完整登录态的 E2E 能继续落在用户已经初始化好的
     * FlashSync 租户空间，而不是要求页面暴露内部 ID。</p>
     */
    private static final Long DEFAULT_FLASHSYNC_TENANT_ID = 10L;

    /**
     * FlashSync 默认项目 ID，对应 permission-admin 初始化脚本里的 FLASHSYNC_DEFAULT。
     */
    private static final Long DEFAULT_FLASHSYNC_PROJECT_ID = 101L;

    /**
     * 本地开发兜底 actor。
     *
     * <p>生产链路中 ownerId/createdBy 应由 gateway 从 Keycloak/企业 IdP 解析后写入
     * {@code X-DataSmart-Actor-Id}。保留这个兜底值只是为了本地直连接口、历史 smoke 和未登录调试
     * 不会创建出 ownerId 为空的资源；它不代表生产环境允许匿名创建数据源。</p>
     */
    private static final Long LOCAL_DEVELOPMENT_ACTOR_ID = 1001L;

    /**
     * 数据源业务服务。
     *
     * <p>Controller 只负责 HTTP 路由、参数装配和入口级范围收口；真正的生命周期、连接测试、
     * 元数据发现和 SQL 审计逻辑仍然在 Service 层，避免把业务规则散落到路由方法里。</p>
     */
    private final DataSourceManagementService dataSourceManagementService;

    /**
     * 数据源实例级授权服务。
     *
     * <p>它维护“某个用户/角色/服务账号被授予某条数据源的 VIEW、USE、MANAGE 能力”这一类 ACL 事实。
     * 这里之所以放在 datasource-management 内部，而不是直接写到 permission-admin 的路由策略里，是因为路由策略只能描述
     * “谁能访问某类 API”，不能高效表达“谁能访问第 123 条数据源”。实例级授权必须靠业务表保存和查询。</p>
     */
    private final DataSourceAuthorizationService dataSourceAuthorizationService;

    /**
     * 项目级数据范围支撑组件。
     *
     * <p>它负责把 gateway 透传的数据范围 Header 转换成 datasource-management 可落地的查询条件，
     * 并在详情类接口中校验资源 projectId，避免用户通过猜测 ID 越权读取其他项目的数据源。</p>
     */
    private final DatasourceProjectScopeSupport datasourceProjectScopeSupport;

    /**
     * 数据源凭据脱敏支撑组件。
     *
     * <p>Controller 是 HTTP 响应离开服务前的最后一道安全边界。即使 Service 层内部实体包含可用于 JDBC 的存储密码，
     * 对外接口也只能返回低敏副本。这里统一把 password 清空，而不是返回 {@code ******}，避免前端编辑保存时
     * 把星号误当成真实密码回写，造成数据源凭据被破坏。</p>
     */
    private final DataSourceCredentialCipherSupport dataSourceCredentialCipherSupport;

    /**
     * 创建数据源登记记录。
     *
     * <p>新前端不需要提交 tenantId/projectId，更不需要提交 workspaceId。服务端会优先使用 Header 中的当前租户/项目上下文，
     * 如果 Header 缺失才读取请求体里的兼容字段；如果两者同时存在但不一致，会直接拒绝请求。
     * workspaceId 已降级为历史兼容字段，新建数据源统一写入 {@code null}，让资源直接归属项目。</p>
     */
    @PostMapping
    public ResponseEntity<ApiResponse<DataSourceConfig>> createDataSource(
            @Valid @RequestBody CreateDataSourceRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantHeader,
            @RequestHeader(value = PlatformContextHeaders.PROJECT_ID, required = false) Long projectHeader,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) String actorId,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_ROLES, required = false) String authorizedProjectRoles) {
        Long tenantId = resolveScopeValue("tenantId", tenantHeader, request.getTenantId(), DEFAULT_FLASHSYNC_TENANT_ID);
        Long projectId = resolveScopeValue("projectId", projectHeader, request.getProjectId(), DEFAULT_FLASHSYNC_PROJECT_ID);
        Long ownerId = parseActorIdOrDefault(actorId);
        DatasourceProjectVisibility visibility = datasourceProjectScopeSupport.resolveVisibility(
                projectId, null, dataScopeLevel, authorizedProjectIds, authorizedProjectRoles);
        datasourceProjectScopeSupport.validateProjectManageable(projectId, visibility, "数据源");
        DataSourceConfig config = dataSourceManagementService.createDataSource(
                tenantId,
                projectId,
                null,
                ownerId,
                ownerId,
                request.getName(),
                request.getType(),
                request.getJdbcUrl(),
                request.getUsername(),
                request.getPassword(),
                request.getDescription(),
                request.getUsagePurpose()
        );
        return ResponseEntity.ok(ApiResponse.success("数据源创建成功",
                dataSourceCredentialCipherSupport.sanitizeForApi(config)));
    }

    /**
     * 测试一组尚未保存的数据源连接参数。
     *
     * <p>新建数据源页面使用该接口先验证 JDBC URL、用户名和连接密码是否可用。
     * 这不是修改数据库账号密码，也不会创建或更新平台内的数据源记录；只有用户点击保存时才会持久化连接配置。</p>
     */
    @PostMapping("/connection-test")
    public ResponseEntity<ApiResponse<DataSourceConnectionTestResult>> testConnectionBeforeCreate(
            @Valid @RequestBody TestDataSourceConnectionRequest request) {
        DataSourceConnectionTestResult result = dataSourceManagementService.testConnection(
                request.getType(),
                request.getJdbcUrl(),
                request.getUsername(),
                request.getPassword()
        );
        return ResponseEntity.ok(ApiResponse.success("数据源连接测试完成", result));
    }

    /**
     * 分页查询数据源。
     *
     * <p>前端新建同步任务时，可以通过 {@code usagePurpose=SOURCE} 获取源端候选，通过
     * {@code usagePurpose=TARGET} 获取目标端候选。后端会严格按 SOURCE/TARGET 精确过滤，不再把同一条连接
     * 同时允许作为源端和目标端。</p>
     *
     * <p>可见性口径先看项目成员资格：数据源列表只展示当前项目可达范围内的数据源。
     * 实例级 VIEW/USE/MANAGE 授权不会绕过项目隔离，它只在详情、编辑、元数据发现、创建同步任务引用等具体动作中，
     * 让项目内协作者获得更高的数据源使用能力。</p>
     */
    @GetMapping
    public ResponseEntity<ApiResponse<IPage<DataSourceConfig>>> listDataSources(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String usagePurpose,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantHeader,
            @RequestHeader(value = PlatformContextHeaders.PROJECT_ID, required = false) Long projectHeader,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) String actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_TYPE, required = false) String actorType,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_ROLES, required = false) String authorizedProjectRoles) {
        Long effectiveTenantId = resolveScopeValue("tenantId", tenantHeader, tenantId, DEFAULT_FLASHSYNC_TENANT_ID);
        Long effectiveProjectId = resolveScopeValue("projectId", projectHeader, projectId, DEFAULT_FLASHSYNC_PROJECT_ID);
        DatasourceAuthorizationActorContext actorContext = resolveActorContext(actorId, actorRole, actorType);
        /*
         * 数据源管理页已经收敛为项目级作用域。
         *
         * 数据库字段 workspace_id 暂时保留给历史数据、审计和 Agent 内部语义，但普通数据源列表不再接受 workspace 过滤。
         * 如果继续把 workspaceId 作为查询条件，用户在项目 101 下创建的数据源可能因为不可见 workspace 维度而消失。
         */
        DatasourceProjectVisibility visibility = datasourceProjectScopeSupport.resolveVisibility(
                effectiveProjectId, null, dataScopeLevel, authorizedProjectIds, authorizedProjectRoles);
        LambdaQueryWrapper<DataSourceConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.ne(DataSourceConfig::getStatus, DataSourceStatus.DELETED);
        applyDatasourceScope(wrapper, effectiveTenantId, visibility, actorContext);
        if (hasText(type)) {
            wrapper.eq(DataSourceConfig::getType, type.toUpperCase());
        }
        applyUsagePurposeFilter(wrapper, usagePurpose);
        if (hasText(status)) {
            wrapper.eq(DataSourceConfig::getStatus, DataSourceStatus.normalizeQueryValue(status));
        }
        if (hasText(keyword)) {
            String likeKeyword = keyword.trim();
            wrapper.and(nested -> nested
                    .like(DataSourceConfig::getName, likeKeyword)
                    .or()
                    .like(DataSourceConfig::getType, likeKeyword)
                    .or()
                    .like(DataSourceConfig::getUsername, likeKeyword)
                    .or()
                    .like(DataSourceConfig::getDescription, likeKeyword)
                    .or()
                    .like(DataSourceConfig::getJdbcUrl, likeKeyword));
        }
        wrapper.orderByDesc(DataSourceConfig::getId);

        IPage<DataSourceConfig> result = dataSourceManagementService.page(new Page<>(safeCurrent(current), safeSize(size)), wrapper);
        result.setRecords(sanitizeForApiWithEffectiveActions(result.getRecords(), visibility, actorContext));
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /**
     * 查询单个数据源详情。
     *
     * <p>详情页只展示低敏配置字段，但仍然是按 ID 访问，所以必须二次校验项目范围；
     * 实例 VIEW 授权只在 actor 已经属于该数据源项目时生效。</p>
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<DataSourceConfig>> getDataSource(
            @PathVariable Long id,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) String actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_TYPE, required = false) String actorType,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_ROLES, required = false) String authorizedProjectRoles) {
        DatasourceAuthorizationActorContext actorContext = resolveActorContext(actorId, actorRole, actorType);
        DataSourceConfig config = getRequiredVisibleDataSource(
                id,
                dataScopeLevel,
                authorizedProjectIds,
                authorizedProjectRoles,
                actorContext,
                DataSourceAuthorizationAction.VIEW);
        DatasourceProjectVisibility visibility = datasourceProjectScopeSupport.resolveVisibility(
                null, null, dataScopeLevel, authorizedProjectIds, authorizedProjectRoles);
        return ResponseEntity.ok(ApiResponse.success(
                sanitizeForApiWithEffectiveActions(config, visibility, actorContext)));
    }

    /**
     * 更新数据源配置。
     *
     * <p>本接口不允许修改租户、项目和工作空间归属。资源归属迁移在商业化产品中通常需要审计，
     * 后续应由单独的“转移项目”治理接口承载。更新连接信息属于高风险管理动作，因此要求项目范围通过或具备实例 MANAGE 授权。</p>
     */
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<DataSourceConfig>> updateDataSource(
            @PathVariable Long id,
            @Valid @RequestBody UpdateDataSourceRequest request,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) String actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_TYPE, required = false) String actorType,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_ROLES, required = false) String authorizedProjectRoles) {
        getRequiredVisibleDataSource(
                id,
                dataScopeLevel,
                authorizedProjectIds,
                authorizedProjectRoles,
                resolveActorContext(actorId, actorRole, actorType),
                DataSourceAuthorizationAction.MANAGE);
        DataSourceConfig config = dataSourceManagementService.updateDataSource(
                id,
                request.getName(),
                request.getJdbcUrl(),
                request.getUsername(),
                request.getPassword(),
                request.getDescription(),
                request.getUsagePurpose()
        );
        return ResponseEntity.ok(ApiResponse.success("数据源更新成功",
                dataSourceCredentialCipherSupport.sanitizeForApi(config)));
    }

    /**
     * 测试编辑表单中的数据源连接参数。
     *
     * <p>本接口用于“保存前测试”：它会读取已保存的数据源类型和旧连接密码，再叠加当前表单里的 JDBC URL、用户名和可选密码。
     * 因为密码留空时会复用已保存凭据，所以这里和更新接口一样要求 MANAGE 权限，避免只有使用权的用户拿平台保存的凭据去探测任意地址。</p>
     */
    @PostMapping("/{id}/connection-test")
    public ResponseEntity<ApiResponse<DataSourceConnectionTestResult>> testConnectionBeforeUpdate(
            @PathVariable Long id,
            @Valid @RequestBody TestExistingDataSourceConnectionRequest request,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) String actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_TYPE, required = false) String actorType,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_ROLES, required = false) String authorizedProjectRoles) {
        getRequiredVisibleDataSource(
                id,
                dataScopeLevel,
                authorizedProjectIds,
                authorizedProjectRoles,
                resolveActorContext(actorId, actorRole, actorType),
                DataSourceAuthorizationAction.MANAGE);
        DataSourceConnectionTestResult result = dataSourceManagementService.testConnection(
                id,
                request.getJdbcUrl(),
                request.getUsername(),
                request.getPassword()
        );
        return ResponseEntity.ok(ApiResponse.success("数据源连接测试完成", result));
    }

    /**
     * 启用数据源。
     *
     * <p>启用会让同步任务、质量扫描、元数据发现重新具备调用该连接的可能性，因此按 MANAGE 动作校验。</p>
     */
    @PostMapping("/{id}/enable")
    public ResponseEntity<ApiResponse<DataSourceConfig>> enableDataSource(
            @PathVariable Long id,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) String actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_TYPE, required = false) String actorType,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_ROLES, required = false) String authorizedProjectRoles) {
        getRequiredVisibleDataSource(
                id,
                dataScopeLevel,
                authorizedProjectIds,
                authorizedProjectRoles,
                resolveActorContext(actorId, actorRole, actorType),
                DataSourceAuthorizationAction.MANAGE);
        return ResponseEntity.ok(ApiResponse.success("数据源已启用",
                dataSourceCredentialCipherSupport.sanitizeForApi(dataSourceManagementService.enableDataSource(id))));
    }

    /**
     * 停用数据源。
     *
     * <p>停用会影响后续同步任务能否继续使用该连接，属于管理动作，因此同样按 MANAGE 校验。</p>
     */
    @PostMapping("/{id}/disable")
    public ResponseEntity<ApiResponse<DataSourceConfig>> disableDataSource(
            @PathVariable Long id,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) String actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_TYPE, required = false) String actorType,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_ROLES, required = false) String authorizedProjectRoles) {
        getRequiredVisibleDataSource(
                id,
                dataScopeLevel,
                authorizedProjectIds,
                authorizedProjectRoles,
                resolveActorContext(actorId, actorRole, actorType),
                DataSourceAuthorizationAction.MANAGE);
        return ResponseEntity.ok(ApiResponse.success("数据源已停用",
                dataSourceCredentialCipherSupport.sanitizeForApi(dataSourceManagementService.disableDataSource(id))));
    }

    /**
     * 逻辑删除数据源。
     *
     * <p>删除采用逻辑删除，历史任务、审计和授权事实仍可追溯。删除比普通编辑更敏感：
     * 数据源实例级 MANAGE 授权只能让协作者维护连接配置，不能扩大成删除权。</p>
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<DataSourceConfig>> deleteDataSource(
            @PathVariable Long id,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) String actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_TYPE, required = false) String actorType,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_ROLES, required = false) String authorizedProjectRoles) {
        getRequiredDeletableDataSource(
                id,
                dataScopeLevel,
                authorizedProjectIds,
                authorizedProjectRoles,
                resolveActorContext(actorId, actorRole, actorType));
        return ResponseEntity.ok(ApiResponse.success("数据源已删除",
                dataSourceCredentialCipherSupport.sanitizeForApi(dataSourceManagementService.deleteDataSource(id))));
    }

    /**
     * 执行数据源连接测试。
     *
     * <p>连接测试会真实触达外部数据库或中间件，所以不能只要求 VIEW；用户必须在项目范围内可使用该数据源，或具备实例 USE/MANAGE 授权。</p>
     */
    @PostMapping("/{id}/test")
    public ResponseEntity<ApiResponse<DataSourceConnectionTestResult>> testConnection(
            @PathVariable Long id,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) String actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_TYPE, required = false) String actorType,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_ROLES, required = false) String authorizedProjectRoles) {
        getRequiredVisibleDataSource(
                id,
                dataScopeLevel,
                authorizedProjectIds,
                authorizedProjectRoles,
                resolveActorContext(actorId, actorRole, actorType),
                DataSourceAuthorizationAction.USE);
        return ResponseEntity.ok(ApiResponse.success("数据源连接测试完成", dataSourceManagementService.testConnection(id)));
    }

    /**
     * 查询数据源能力画像。
     *
     * <p>能力画像是低敏元数据，查看详情即可读取，因此按 VIEW 校验。</p>
     */
    @GetMapping("/{id}/capabilities")
    public ResponseEntity<ApiResponse<DataSourceCapabilityProfile>> getCapabilities(
            @PathVariable Long id,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) String actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_TYPE, required = false) String actorType,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_ROLES, required = false) String authorizedProjectRoles) {
        getRequiredVisibleDataSource(
                id,
                dataScopeLevel,
                authorizedProjectIds,
                authorizedProjectRoles,
                resolveActorContext(actorId, actorRole, actorType),
                DataSourceAuthorizationAction.VIEW);
        return ResponseEntity.ok(ApiResponse.success("数据源能力画像获取成功", dataSourceManagementService.getCapabilityProfile(id)));
    }

    /**
     * 发现数据源元数据。
     *
     * <p>元数据发现会连接源端系统并读取库表结构，虽然不读取业务行数据，但仍属于“使用数据源”的动作，因此按 USE 校验。</p>
     */
    @PostMapping("/{id}/metadata/discover")
    public ResponseEntity<ApiResponse<DataSourceMetadataDiscoveryResult>> discoverMetadata(
            @PathVariable Long id,
            @Valid @RequestBody MetadataDiscoveryRequest request,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) String actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_TYPE, required = false) String actorType,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_ROLES, required = false) String authorizedProjectRoles) {
        getRequiredVisibleDataSource(
                id,
                dataScopeLevel,
                authorizedProjectIds,
                authorizedProjectRoles,
                resolveActorContext(actorId, actorRole, actorType),
                DataSourceAuthorizationAction.USE);
        return ResponseEntity.ok(ApiResponse.success("数据源元数据发现完成",
                dataSourceManagementService.discoverMetadata(id, request)));
    }

    /**
     * 执行受控只读 SQL。
     *
     * <p>只读 SQL 是最敏感的数据源使用入口之一：它必须经过 SQL 安全解析、审计落库和数据源 USE 授权三层约束。
     * Controller 在这里负责补齐可信平台 Header，Service 层负责只读 SQL 的语法与风险控制。</p>
     */
    @PostMapping("/{id}/sql/read-only/execute")
    public ResponseEntity<ApiResponse<ReadOnlySqlExecutionResult>> executeReadOnlySql(
            @PathVariable Long id,
            @Valid @RequestBody ReadOnlySqlExecutionRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) String tenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) String actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_TYPE, required = false) String actorType,
            @RequestHeader(value = PlatformContextHeaders.SOURCE_SERVICE, required = false) String sourceService,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_ROLES, required = false) String authorizedProjectRoles) {
        getRequiredVisibleDataSource(
                id,
                dataScopeLevel,
                authorizedProjectIds,
                authorizedProjectRoles,
                resolveActorContext(actorId, actorRole, actorType),
                DataSourceAuthorizationAction.USE);
        applyTrustedPlatformContext(request, tenantId, actorId, actorRole, actorType, sourceService, traceId);
        return ResponseEntity.ok(ApiResponse.success("受控只读 SQL 执行完成",
                dataSourceManagementService.executeReadOnlySql(id, request)));
    }

    /**
     * 分页查询受控只读 SQL 执行审计。
     *
     * <p>该接口主要给管理员、运维和审计员排查使用，继续沿用项目范围可见性，而不是实例授权。
     * 原因是审计视图关注“谁做过什么”，不是“当前用户能不能复用这条数据源”。</p>
     */
    @GetMapping("/sql/read-only/audits")
    public ResponseEntity<ApiResponse<IPage<DataSourceReadOnlySqlExecutionAudit>>> pageReadOnlySqlExecutionAudits(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) Long datasourceId,
            @RequestParam(required = false) String purpose,
            @RequestParam(required = false) String actorRole,
            @RequestParam(required = false) Long actorTenantId,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String executionStatus,
            @RequestParam(required = false) String sqlFingerprint,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
            @RequestParam(required = false) String queryActorRole,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String headerActorRole,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds) {
        String effectiveQueryActorRole = hasText(headerActorRole) ? headerActorRole : queryActorRole;
        DatasourceProjectVisibility visibility = datasourceProjectScopeSupport.resolveVisibility(
                projectId, null, dataScopeLevel, authorizedProjectIds);
        return ResponseEntity.ok(ApiResponse.success("受控只读 SQL 执行审计查询成功",
                dataSourceManagementService.pageReadOnlySqlExecutionAudits(
                        current,
                        size,
                        datasourceId,
                        purpose,
                        actorRole,
                        actorTenantId,
                        visibility,
                        executionStatus,
                        sqlFingerprint,
                        startTime,
                        endTime,
                        effectiveQueryActorRole)));
    }

    /**
     * 给数据源列表追加租户与项目范围过滤。
     *
     * <p>当 permission-admin 明确要求 PROJECT 范围时，列表必须限制在授权项目集合内；
     * 实例级数据源授权必须建立在项目成员资格之上，不能把其他项目的数据源并入当前列表。
     * 当不是 PROJECT 强制范围时，仍然尊重显式 projectId 过滤，避免平台管理员/租户管理员视图被误裁剪。</p>
     */
    private void applyDatasourceScope(LambdaQueryWrapper<DataSourceConfig> wrapper,
                                      Long tenantId,
                                      DatasourceProjectVisibility visibility,
                                      DatasourceAuthorizationActorContext actorContext) {
        wrapper.eq(tenantId != null, DataSourceConfig::getTenantId, tenantId);
        applyProjectVisibilityOnly(wrapper, visibility);
        applyDatasourceInstanceVisibility(wrapper, tenantId, visibility, actorContext);
    }

    /**
     * Apply datasource ownership and instance ACL filtering for SELF-scoped actors.
     *
     * <p>Project membership only establishes the isolation boundary. It must not make every personal connection in
     * the project visible to every member. A SELF-scoped actor can see a datasource only when the actor owns it or
     * receives an explicit VIEW/USE/MANAGE datasource authorization. Project OWNER/MANAGER roles are the deliberate
     * administrative exception and can inspect all datasource records in the managed project.</p>
     */
    private void applyDatasourceInstanceVisibility(LambdaQueryWrapper<DataSourceConfig> wrapper,
                                                   Long tenantId,
                                                   DatasourceProjectVisibility visibility,
                                                   DatasourceAuthorizationActorContext actorContext) {
        if (visibility == null || !visibility.selfOnly()) {
            return;
        }
        Long actorId = parseActorId(actorContext == null ? null : actorContext.actorId());
        List<Long> authorizedDatasourceIds = findVisibleDatasourceIds(tenantId, visibility, actorContext);
        List<Long> manageableProjectIds = visibility.authorizedProjectRoles().stream()
                .filter(role -> role != null && role.projectId() != null)
                .filter(role -> "OWNER".equalsIgnoreCase(role.projectRole())
                        || "MANAGER".equalsIgnoreCase(role.projectRole()))
                .map(role -> role.projectId())
                .distinct()
                .toList();
        if (actorId == null && authorizedDatasourceIds.isEmpty() && manageableProjectIds.isEmpty()) {
            wrapper.apply("1 = 0");
            return;
        }
        wrapper.and(visible -> {
            boolean hasCondition = false;
            if (!manageableProjectIds.isEmpty()) {
                visible.in(DataSourceConfig::getProjectId, manageableProjectIds);
                hasCondition = true;
            }
            if (actorId != null) {
                if (hasCondition) {
                    visible.or();
                }
                visible.eq(DataSourceConfig::getOwnerId, actorId);
                hasCondition = true;
            }
            if (!authorizedDatasourceIds.isEmpty()) {
                if (hasCondition) {
                    visible.or();
                }
                visible.in(DataSourceConfig::getId, authorizedDatasourceIds);
            }
        });
    }

    private List<Long> findVisibleDatasourceIds(Long tenantId,
                                                DatasourceProjectVisibility visibility,
                                                DatasourceAuthorizationActorContext actorContext) {
        if (visibility.requestedProjectId() != null) {
            return dataSourceAuthorizationService.findAuthorizedDatasourceIds(
                    tenantId, visibility.requestedProjectId(), actorContext, DataSourceAuthorizationAction.VIEW);
        }
        return visibility.authorizedProjectIds().stream()
                .flatMap(projectId -> dataSourceAuthorizationService.findAuthorizedDatasourceIds(
                        tenantId, projectId, actorContext, DataSourceAuthorizationAction.VIEW).stream())
                .distinct()
                .toList();
    }

    /**
     * 仅应用项目范围，不混入实例级授权。
     */
    private void applyProjectVisibilityOnly(LambdaQueryWrapper<DataSourceConfig> wrapper,
                                            DatasourceProjectVisibility visibility) {
        if (visibility == null) {
            return;
        }
        wrapper.eq(visibility.requestedProjectId() != null, DataSourceConfig::getProjectId, visibility.requestedProjectId())
                .eq(visibility.requestedWorkspaceId() != null, DataSourceConfig::getWorkspaceId, visibility.requestedWorkspaceId());
        if (!visibility.projectScopeEnforced()) {
            return;
        }
        if (visibility.authorizedProjectIds().isEmpty()) {
            wrapper.apply("1 = 0");
            return;
        }
        if (visibility.requestedProjectId() == null) {
            wrapper.in(DataSourceConfig::getProjectId, visibility.authorizedProjectIds());
        }
    }

    /**
     * 按源端/目标端用途过滤数据源候选列表。
     *
     * <p>当前产品只允许数据源被登记为 SOURCE 或 TARGET，不再支持 BOTH。因此这里使用精确相等过滤：
     * 源端选择器不会再混入目标端数据源，目标端选择器也不会再混入源端数据源。</p>
     */
    private void applyUsagePurposeFilter(LambdaQueryWrapper<DataSourceConfig> wrapper, String usagePurpose) {
        if (!hasText(usagePurpose)) {
            return;
        }
        DataSourceUsagePurpose purpose = DataSourceUsagePurpose.fromValue(usagePurpose);
        wrapper.eq(DataSourceConfig::getUsagePurpose, purpose.name());
    }

    /**
     * 读取并校验某条数据源是否对当前 actor 可见或可用。
     *
     * <p>校验顺序故意设计为“项目范围优先，实例授权兜底”：
     * 项目负责人、租户管理员、平台管理员通常不需要每条数据源都有 ACL 记录；
     * 普通用户或服务账号如果不在项目范围内，则可以依赖明确授权进入 VIEW/USE/MANAGE 能力边界。</p>
     */
    private DataSourceConfig getRequiredVisibleDataSource(Long id,
                                                          String dataScopeLevel,
                                                          String authorizedProjectIds,
                                                          String authorizedProjectRoles,
                                                          DatasourceAuthorizationActorContext actorContext,
                                                          DataSourceAuthorizationAction requiredAction) {
        DataSourceConfig config = dataSourceManagementService.getById(id);
        if (config == null || DataSourceStatus.DELETED.equals(config.getStatus())) {
            throw new NoSuchElementException("数据源不存在或已删除: " + id);
        }
        DatasourceProjectVisibility visibility = datasourceProjectScopeSupport.resolveVisibility(
                null, null, dataScopeLevel, authorizedProjectIds, authorizedProjectRoles);
        if (!visibility.projectScopeEnforced()) {
            return config;
        }
        datasourceProjectScopeSupport.validateProjectReadable(config.getProjectId(), visibility, "数据源");
        if (visibility.canManageProject(config.getProjectId()) || isDatasourceOwner(config, actorContext)) {
            return config;
        }
        if (dataSourceAuthorizationService.hasActiveAuthorization(config.getId(), actorContext, requiredAction)) {
            return config;
        }
        throw new IllegalArgumentException("当前账号没有访问该数据源的实例授权，datasourceId=" + id
                + "。请由数据源所有者授予 VIEW/USE/MANAGE 权限，或联系项目 OWNER/MANAGER 处理。");
    }

    /**
     * Read and validate whether the current actor can delete a datasource.
     *
     * <p>Delete is intentionally stricter than edit. Instance MANAGE authorization lets a collaborator
     * maintain connection settings, but it must not become a hidden delete permission. Deletion is limited
     * to the datasource owner or project MANAGER/OWNER.</p>
     */
    private DataSourceConfig getRequiredDeletableDataSource(Long id,
                                                            String dataScopeLevel,
                                                            String authorizedProjectIds,
                                                            String authorizedProjectRoles,
                                                            DatasourceAuthorizationActorContext actorContext) {
        DataSourceConfig config = dataSourceManagementService.getById(id);
        if (config == null || DataSourceStatus.DELETED.equals(config.getStatus())) {
            throw new NoSuchElementException("数据源不存在或已删除: " + id);
        }
        DatasourceProjectVisibility visibility = datasourceProjectScopeSupport.resolveVisibility(
                null, null, dataScopeLevel, authorizedProjectIds, authorizedProjectRoles);
        try {
            datasourceProjectScopeSupport.validateProjectManageable(config.getProjectId(), visibility, "数据源");
            return config;
        } catch (IllegalArgumentException exception) {
            if (!visibility.canReachProject(config.getProjectId())) {
                throw exception;
            }
            if (isDatasourceOwner(config, actorContext)) {
                return config;
            }
            throw exception;
        }
    }

    /**
     * 根据接口所需动作执行项目角色校验。
     *
     * <p>项目角色与实例授权是两个层级：
     * 项目 MANAGER/OWNER 对项目内所有数据源天然具备管理能力；
     * 项目 READER 只能查看低敏信息；
     * 如果 READER 或其他用户被单独授予某条数据源的 USE/MANAGE 实例授权，外层 catch 分支仍允许其通过。
     * 这种设计既保留“项目内角色”的主边界，又支持用户把自己创建的数据源授权给其他协作者使用。</p>
     */
    private void validateDatasourceProjectAction(Long projectId,
                                                 DatasourceProjectVisibility visibility,
                                                 DataSourceAuthorizationAction requiredAction) {
        if (requiredAction == DataSourceAuthorizationAction.MANAGE) {
            datasourceProjectScopeSupport.validateProjectManageable(projectId, visibility, "数据源");
            return;
        }
        if (requiredAction == DataSourceAuthorizationAction.USE) {
            datasourceProjectScopeSupport.validateProjectUsable(projectId, visibility, "数据源");
            return;
        }
        datasourceProjectScopeSupport.validateProjectReadable(projectId, visibility, "数据源");
    }

    /**
     * 把 gateway 透传的 actor Header 收敛为授权服务可识别的上下文对象。
     */
    private DatasourceAuthorizationActorContext resolveActorContext(String actorId, String actorRole, String actorType) {
        return new DatasourceAuthorizationActorContext(actorId, actorRole, actorType);
    }

    private boolean isDatasourceOwner(DataSourceConfig config, DatasourceAuthorizationActorContext actorContext) {
        Long actorId = parseActorId(actorContext == null ? null : actorContext.actorId());
        return actorId != null && actorId.equals(config.getOwnerId());
    }

    /**
     * Build API-safe datasource records and attach the current actor's effective datasource actions.
     *
     * <p>The action snapshot is a UI convenience contract, not the security boundary. Each mutating or sensitive
     * endpoint still repeats its own authorization check. Keeping the snapshot here lets datasource list, task wizard
     * datasource selectors and detail buttons all reason from the same backend-computed permissions.</p>
     */
    private List<DataSourceConfig> sanitizeForApiWithEffectiveActions(List<DataSourceConfig> sources,
                                                                      DatasourceProjectVisibility visibility,
                                                                      DatasourceAuthorizationActorContext actorContext) {
        if (sources == null || sources.isEmpty()) {
            return List.of();
        }
        return sources.stream()
                .map(source -> sanitizeForApiWithEffectiveActions(source, visibility, actorContext))
                .toList();
    }

    private DataSourceConfig sanitizeForApiWithEffectiveActions(DataSourceConfig source,
                                                                DatasourceProjectVisibility visibility,
                                                                DatasourceAuthorizationActorContext actorContext) {
        DataSourceConfig sanitized = dataSourceCredentialCipherSupport.sanitizeForApi(source);
        if (sanitized != null) {
            sanitized.setEffectiveActions(resolveEffectiveActions(source, visibility, actorContext));
        }
        return sanitized;
    }

    /**
     * Resolve VIEW/USE/MANAGE for one datasource under the already trusted project scope.
     *
     * <p>Instance authorization only becomes effective after the user has joined the datasource project. That product
     * rule prevents a leaked or stale datasource ACL row from becoming a cross-project data access channel.</p>
     */
    private List<String> resolveEffectiveActions(DataSourceConfig source,
                                                 DatasourceProjectVisibility visibility,
                                                 DatasourceAuthorizationActorContext actorContext) {
        if (source == null) {
            return List.of();
        }
        Set<String> actions = new LinkedHashSet<>();
        if (visibility == null || !visibility.projectScopeEnforced()) {
            actions.add(DataSourceAuthorizationAction.VIEW.name());
            actions.add(DataSourceAuthorizationAction.USE.name());
            actions.add(DataSourceAuthorizationAction.MANAGE.name());
            return List.copyOf(actions);
        }
        Long projectId = source.getProjectId();
        if (!visibility.canReachProject(projectId)) {
            return List.of();
        }

        boolean owner = isDatasourceOwner(source, actorContext);
        boolean projectManageable = visibility.canManageProject(projectId);
        boolean instanceManage = dataSourceAuthorizationService.hasActiveAuthorization(
                source.getId(), actorContext, DataSourceAuthorizationAction.MANAGE);
        boolean instanceUse = instanceManage || dataSourceAuthorizationService.hasActiveAuthorization(
                source.getId(), actorContext, DataSourceAuthorizationAction.USE);
        boolean instanceView = instanceUse || dataSourceAuthorizationService.hasActiveAuthorization(
                source.getId(), actorContext, DataSourceAuthorizationAction.VIEW);

        if (projectManageable || owner || instanceView) {
            actions.add(DataSourceAuthorizationAction.VIEW.name());
        }
        if (projectManageable || owner || instanceUse) {
            actions.add(DataSourceAuthorizationAction.USE.name());
        }
        if (projectManageable || owner || instanceManage) {
            actions.add(DataSourceAuthorizationAction.MANAGE.name());
        }
        return List.copyOf(actions);
    }

    /**
     * 把可信平台 Header 合并到只读 SQL 请求体。
     */
    private void applyTrustedPlatformContext(ReadOnlySqlExecutionRequest request,
                                             String tenantId,
                                             String actorId,
                                             String actorRole,
                                             String actorType,
                                             String sourceService,
                                             String traceId) {
        if (hasText(tenantId)) {
            request.setActorTenantId(parseLongHeader(PlatformContextHeaders.TENANT_ID, tenantId));
        }
        if (hasText(actorId)) {
            request.setActorId(parseLongHeader(PlatformContextHeaders.ACTOR_ID, actorId));
        }
        if (hasText(actorRole)) {
            request.setActorRole(actorRole);
        }
        if (hasText(actorType)) {
            request.setActorType(actorType);
        }
        if (hasText(sourceService)) {
            request.setSourceService(sourceService);
        }
        if (hasText(traceId)) {
            request.setTraceId(traceId);
        }
    }

    /**
     * 解析租户、项目这类“系统上下文字段”。
     *
     * <p>可信 Header 优先级最高，请求体/查询参数只是兼容入口。如果 Header 和请求体同时存在但值不同，
     * 说明调用方试图把当前页面上下文和写入归属拆开，这在多租户产品里属于高风险行为，因此直接拒绝。
     * 当两者都不存在时，本地开发默认落到 FlashSync 初始化上下文。</p>
     */
    private Long resolveScopeValue(String fieldName, Long headerValue, Long requestValue, Long defaultValue) {
        if (headerValue != null && requestValue != null && !headerValue.equals(requestValue)) {
            throw new IllegalArgumentException(fieldName + " 与当前系统上下文不一致，headerValue="
                    + headerValue + ", requestValue=" + requestValue);
        }
        if (headerValue != null) {
            return headerValue;
        }
        if (requestValue != null) {
            return requestValue;
        }
        return defaultValue;
    }

    /**
     * 解析需要写入审计字段的数字型 Header。
     */
    private Long parseLongHeader(String headerName, String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(headerName + " 必须是数字: " + value);
        }
    }

    /**
     * 规整用户侧列表页码。
     *
     * <p>数据源会随着项目使用持续增长，列表接口必须由服务端兜底分页边界：
     * 页码小于 1 时统一回到第 1 页，避免前端缓存、手工拼 URL 或自动化脚本传入非法页码后得到不可解释的结果。</p>
     */
    private Long parseActorIdOrDefault(String value) {
        Long parsed = parseActorId(value);
        return parsed == null ? LOCAL_DEVELOPMENT_ACTOR_ID : parsed;
    }

    private Long parseActorId(String value) {
        if (!hasText(value)) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private long safeCurrent(Integer current) {
        return current == null || current <= 0 ? 1L : current.longValue();
    }

    /**
     * 规整用户侧列表每页数量。
     *
     * <p>前端最多展示 100 条，后端也同步限制到 100 条，避免“前端看起来限制了，
     * 但直接调接口仍能一次拉取大量数据源连接低敏信息”的体验和安全不一致问题。</p>
     */
    private long safeSize(Integer size) {
        if (size == null || size <= 0) {
            return 10L;
        }
        return Math.min(size.longValue(), 100L);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
