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
import com.czh.datasmart.govern.datasource.controller.dto.UpdateDataSourceRequest;
import com.czh.datasmart.govern.datasource.entity.DataSourceCapabilityProfile;
import com.czh.datasmart.govern.datasource.entity.DataSourceConfig;
import com.czh.datasmart.govern.datasource.entity.DataSourceConnectionTestResult;
import com.czh.datasmart.govern.datasource.entity.DataSourceMetadataDiscoveryResult;
import com.czh.datasmart.govern.datasource.entity.DataSourceReadOnlySqlExecutionAudit;
import com.czh.datasmart.govern.datasource.service.DataSourceManagementService;
import com.czh.datasmart.govern.datasource.service.support.DatasourceProjectScopeSupport;
import com.czh.datasmart.govern.datasource.service.support.DatasourceProjectVisibility;
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
import java.util.NoSuchElementException;

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
 * <p>本次调整的核心原则是：租户、项目属于系统上下文，不能继续作为普通表单字段让用户手填。
 * 页面当前切换到哪个 FlashSync 项目，就应该由 gateway 或前端上下文把对应 Header 传给后端；
 * 后端优先使用可信 Header，只有本地调试或旧脚本缺少 Header 时才兼容请求体/查询参数。
 * 工作空间已经从产品可见层级退场，因此新建数据源不再写入默认 workspaceId。</p>
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
     * 数据源业务服务。
     *
     * <p>Controller 只负责 HTTP 路由、参数装配和入口级范围收口；真正的生命周期、连接测试、
     * 元数据发现和 SQL 审计逻辑仍然在 Service 层，避免把业务规则散落到路由方法里。</p>
     */
    private final DataSourceManagementService dataSourceManagementService;

    /**
     * 项目级数据范围支撑组件。
     *
     * <p>它负责把 gateway 透传的数据范围 Header 转换成 datasource-management 可落地的查询条件，
     * 并在详情类接口中校验资源 projectId，避免用户通过猜测 ID 越权读取其他项目的数据源。</p>
     */
    private final DatasourceProjectScopeSupport datasourceProjectScopeSupport;

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
            @RequestHeader(value = PlatformContextHeaders.PROJECT_ID, required = false) Long projectHeader) {
        Long tenantId = resolveScopeValue("tenantId", tenantHeader, request.getTenantId(), DEFAULT_FLASHSYNC_TENANT_ID);
        Long projectId = resolveScopeValue("projectId", projectHeader, request.getProjectId(), DEFAULT_FLASHSYNC_PROJECT_ID);
        DataSourceConfig config = dataSourceManagementService.createDataSource(
                tenantId,
                projectId,
                null,
                request.getName(),
                request.getType(),
                request.getJdbcUrl(),
                request.getUsername(),
                request.getPassword(),
                request.getDescription(),
                request.getUsagePurpose()
        );
        return ResponseEntity.ok(ApiResponse.success("数据源创建成功", config));
    }

    /**
     * 分页查询数据源。
     *
     * <p>前端新建同步任务时，可以通过 {@code usagePurpose=SOURCE} 获取源端候选，通过
     * {@code usagePurpose=TARGET} 获取目标端候选。后端会严格按 SOURCE/TARGET 精确过滤，不再把同一条连接
     * 同时允许作为源端和目标端。</p>
     */
    @GetMapping
    public ResponseEntity<ApiResponse<IPage<DataSourceConfig>>> listDataSources(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long workspaceId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String usagePurpose,
            @RequestParam(required = false) String status,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long tenantHeader,
            @RequestHeader(value = PlatformContextHeaders.PROJECT_ID, required = false) Long projectHeader,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds) {
        Long effectiveTenantId = resolveScopeValue("tenantId", tenantHeader, tenantId, DEFAULT_FLASHSYNC_TENANT_ID);
        Long effectiveProjectId = resolveScopeValue("projectId", projectHeader, projectId, DEFAULT_FLASHSYNC_PROJECT_ID);
        DatasourceProjectVisibility visibility = datasourceProjectScopeSupport.resolveVisibility(
                effectiveProjectId, workspaceId, dataScopeLevel, authorizedProjectIds);
        LambdaQueryWrapper<DataSourceConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.ne(DataSourceConfig::getStatus, DataSourceStatus.DELETED);
        applyDatasourceScope(wrapper, effectiveTenantId, visibility);
        if (hasText(type)) {
            wrapper.eq(DataSourceConfig::getType, type.toUpperCase());
        }
        applyUsagePurposeFilter(wrapper, usagePurpose);
        if (hasText(status)) {
            wrapper.eq(DataSourceConfig::getStatus, status.toUpperCase());
        }
        wrapper.orderByDesc(DataSourceConfig::getCreateTime);

        IPage<DataSourceConfig> result = dataSourceManagementService.page(new Page<>(current, size), wrapper);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /**
     * 查询单个数据源详情。
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<DataSourceConfig>> getDataSource(
            @PathVariable Long id,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds) {
        DataSourceConfig config = getRequiredVisibleDataSource(id, dataScopeLevel, authorizedProjectIds);
        return ResponseEntity.ok(ApiResponse.success(config));
    }

    /**
     * 更新数据源配置。
     *
     * <p>本接口不允许修改租户、项目和工作空间归属。资源归属迁移在商业化产品中通常需要审计，
     * 后续应由单独的“转移项目/空间”治理接口承载。</p>
     */
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<DataSourceConfig>> updateDataSource(
            @PathVariable Long id,
            @Valid @RequestBody UpdateDataSourceRequest request,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds) {
        getRequiredVisibleDataSource(id, dataScopeLevel, authorizedProjectIds);
        DataSourceConfig config = dataSourceManagementService.updateDataSource(
                id,
                request.getName(),
                request.getJdbcUrl(),
                request.getUsername(),
                request.getPassword(),
                request.getDescription(),
                request.getUsagePurpose()
        );
        return ResponseEntity.ok(ApiResponse.success("数据源更新成功", config));
    }

    /**
     * 启用数据源。
     */
    @PostMapping("/{id}/enable")
    public ResponseEntity<ApiResponse<DataSourceConfig>> enableDataSource(
            @PathVariable Long id,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds) {
        getRequiredVisibleDataSource(id, dataScopeLevel, authorizedProjectIds);
        return ResponseEntity.ok(ApiResponse.success("数据源已启用", dataSourceManagementService.enableDataSource(id)));
    }

    /**
     * 停用数据源。
     */
    @PostMapping("/{id}/disable")
    public ResponseEntity<ApiResponse<DataSourceConfig>> disableDataSource(
            @PathVariable Long id,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds) {
        getRequiredVisibleDataSource(id, dataScopeLevel, authorizedProjectIds);
        return ResponseEntity.ok(ApiResponse.success("数据源已停用", dataSourceManagementService.disableDataSource(id)));
    }

    /**
     * 逻辑删除数据源。
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<DataSourceConfig>> deleteDataSource(
            @PathVariable Long id,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds) {
        getRequiredVisibleDataSource(id, dataScopeLevel, authorizedProjectIds);
        return ResponseEntity.ok(ApiResponse.success("数据源已删除", dataSourceManagementService.deleteDataSource(id)));
    }

    /**
     * 执行数据源连接测试。
     */
    @PostMapping("/{id}/test")
    public ResponseEntity<ApiResponse<DataSourceConnectionTestResult>> testConnection(
            @PathVariable Long id,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds) {
        getRequiredVisibleDataSource(id, dataScopeLevel, authorizedProjectIds);
        return ResponseEntity.ok(ApiResponse.success("数据源连接测试完成", dataSourceManagementService.testConnection(id)));
    }

    /**
     * 查询数据源能力画像。
     */
    @GetMapping("/{id}/capabilities")
    public ResponseEntity<ApiResponse<DataSourceCapabilityProfile>> getCapabilities(
            @PathVariable Long id,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds) {
        getRequiredVisibleDataSource(id, dataScopeLevel, authorizedProjectIds);
        return ResponseEntity.ok(ApiResponse.success("数据源能力画像获取成功", dataSourceManagementService.getCapabilityProfile(id)));
    }

    /**
     * 发现数据源元数据。
     */
    @PostMapping("/{id}/metadata/discover")
    public ResponseEntity<ApiResponse<DataSourceMetadataDiscoveryResult>> discoverMetadata(
            @PathVariable Long id,
            @Valid @RequestBody MetadataDiscoveryRequest request,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds) {
        getRequiredVisibleDataSource(id, dataScopeLevel, authorizedProjectIds);
        return ResponseEntity.ok(ApiResponse.success("数据源元数据发现完成",
                dataSourceManagementService.discoverMetadata(id, request)));
    }

    /**
     * 执行受控只读 SQL。
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
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds) {
        getRequiredVisibleDataSource(id, dataScopeLevel, authorizedProjectIds);
        applyTrustedPlatformContext(request, tenantId, actorId, actorRole, actorType, sourceService, traceId);
        return ResponseEntity.ok(ApiResponse.success("受控只读 SQL 执行完成",
                dataSourceManagementService.executeReadOnlySql(id, request)));
    }

    /**
     * 分页查询受控只读 SQL 执行审计。
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
            @RequestParam(required = false) Long workspaceId,
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
                projectId, workspaceId, dataScopeLevel, authorizedProjectIds);
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

    private void applyDatasourceScope(LambdaQueryWrapper<DataSourceConfig> wrapper,
                                      Long tenantId,
                                      DatasourceProjectVisibility visibility) {
        wrapper.eq(tenantId != null, DataSourceConfig::getTenantId, tenantId)
                .eq(visibility.requestedProjectId() != null, DataSourceConfig::getProjectId, visibility.requestedProjectId())
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

    private DataSourceConfig getRequiredVisibleDataSource(Long id, String dataScopeLevel, String authorizedProjectIds) {
        DataSourceConfig config = dataSourceManagementService.getById(id);
        if (config == null || DataSourceStatus.DELETED.equals(config.getStatus())) {
            throw new NoSuchElementException("数据源不存在: " + id);
        }
        DatasourceProjectVisibility visibility = datasourceProjectScopeSupport.resolveVisibility(
                null, null, dataScopeLevel, authorizedProjectIds);
        datasourceProjectScopeSupport.validateProjectReadable(config.getProjectId(), visibility, "数据源");
        return config;
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
     * 解析租户、项目、工作空间这类“系统上下文字段”。
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

    private Long parseLongHeader(String headerName, String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(headerName + " 必须是数字: " + value);
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
