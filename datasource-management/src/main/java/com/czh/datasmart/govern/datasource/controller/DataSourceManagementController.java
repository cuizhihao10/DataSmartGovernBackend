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
 * 它负责管理一个外部数据源如何被登记、查看、更新、启停、测试连接、发现元数据，以及在受控边界下执行只读 SQL。
 * 对商业化数据治理平台来说，数据源配置本身就是敏感资产：连接地址、用户名、元数据结构、样例数据访问能力都可能暴露企业数据边界。</p>
 *
 * <p>本轮新增项目/工作空间范围控制后，控制器额外承担一项入口职责：
 * 把 gateway 透传的 `X-DataSmart-Data-Scope-Level` 和 `X-DataSmart-Authorized-Project-Ids`
 * 翻译为 datasource-management 可落地的查询条件和详情校验。
 * 列表接口通过 `project_id IN (...)` 收敛结果；详情、更新、启停、连接测试、元数据发现和只读 SQL 等按 ID 访问的接口，
 * 必须在执行业务动作前校验资源 projectId，防止通过猜测 ID 越权访问其他项目的数据源。</p>
 */
@RestController
@RequestMapping("/datasources")
@RequiredArgsConstructor
public class DataSourceManagementController {

    /**
     * 数据源业务服务。
     * Controller 只声明 HTTP 路由、参数和入口级权限收敛；真正的生命周期、连接测试和 SQL 审计逻辑仍交给 Service。
     */
    private final DataSourceManagementService dataSourceManagementService;

    /**
     * 项目级数据范围支撑组件。
     * 该组件集中解释 PROJECT 范围、授权项目集合和详情资源校验，避免每个路由重复实现安全规则。
     */
    private final DatasourceProjectScopeSupport datasourceProjectScopeSupport;

    /**
     * 创建数据源登记记录。
     *
     * <p>新建数据源时要求请求体显式携带 tenantId、projectId 和可选 workspaceId。
     * 这样数据源从落库第一天起就具备租户内项目隔离能力，而不是等权限系统上线后再大规模补数。</p>
     */
    @PostMapping
    public ResponseEntity<ApiResponse<DataSourceConfig>> createDataSource(
            @Valid @RequestBody CreateDataSourceRequest request) {
        DataSourceConfig config = dataSourceManagementService.createDataSource(
                request.getTenantId(),
                request.getProjectId(),
                request.getWorkspaceId(),
                request.getName(),
                request.getType(),
                request.getJdbcUrl(),
                request.getUsername(),
                request.getPassword(),
                request.getDescription()
        );
        return ResponseEntity.ok(ApiResponse.success("数据源创建成功", config));
    }

    /**
     * 分页查询数据源。
     *
     * <p>常规筛选维度包括 tenantId、projectId、workspaceId、type、status。
     * 当 gateway 明确声明当前身份是 PROJECT 数据范围时，即使调用方不传 projectId，后端也会自动追加授权项目集合过滤。
     * 如果授权项目集合为空，查询会追加 `1 = 0`，让结果安全地收敛为空，而不是退回整个租户。</p>
     */
    @GetMapping
    public ResponseEntity<ApiResponse<IPage<DataSourceConfig>>> listDataSources(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long workspaceId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds) {
        DatasourceProjectVisibility visibility = datasourceProjectScopeSupport.resolveVisibility(
                projectId, workspaceId, dataScopeLevel, authorizedProjectIds);
        LambdaQueryWrapper<DataSourceConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.ne(DataSourceConfig::getStatus, DataSourceStatus.DELETED);
        applyDatasourceScope(wrapper, tenantId, visibility);
        if (hasText(type)) {
            wrapper.eq(DataSourceConfig::getType, type.toUpperCase());
        }
        if (hasText(status)) {
            wrapper.eq(DataSourceConfig::getStatus, status.toUpperCase());
        }
        wrapper.orderByDesc(DataSourceConfig::getCreateTime);

        IPage<DataSourceConfig> result = dataSourceManagementService.page(new Page<>(current, size), wrapper);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /**
     * 查询单个数据源详情。
     *
     * <p>详情接口只传资源 ID，不能依赖列表页已经过滤过。这里会在返回前再次校验资源 projectId，
     * 防止项目负责人通过猜测 ID 读取其他项目的数据源连接配置。</p>
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
     * <p>本接口暂不允许修改 tenant/project/workspace 归属。
     * 资源归属迁移在商业化产品中通常需要审批和审计，后续应单独提供“转移项目/空间”治理接口，而不是混入普通编辑。</p>
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
                request.getDescription()
        );
        return ResponseEntity.ok(ApiResponse.success("数据源更新成功", config));
    }

    /**
     * 启用数据源。
     * 启用后，元数据发现、模板创建和只读 SQL 等能力才应该继续使用该数据源。
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
     * 停用不会删除历史配置，而是阻止后续业务继续把它作为可用连接使用。
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
     * 使用逻辑删除是为了保留历史配置、审计记录和同步任务引用，不让历史证据因为物理删除而断链。
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
     * 连接测试会真实触达外部系统，因此也必须先校验项目可见性，避免用户测试未授权项目的数据源。
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
     * 能力画像用于告诉前端和模板校验层该连接器理论上支持哪些治理能力，例如全量同步、增量同步、元数据发现和采样预览。
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
     * 元数据结构本身可能暴露业务表名、字段名、索引和样例结构，因此进入发现流程前也要做项目级详情校验。
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
     *
     * <p>这是 datasource-management 从“连接配置管理”走向“安全数据访问代理”的关键入口。
     * 因为该接口会真实读取外部数据，所以在执行 SQL 安全校验、行数限制和审计写入之前，必须先确认当前身份可以访问该数据源所属项目。</p>
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
     * 当前审计表仍按访问人租户、角色、数据源和 SQL 指纹查询；后续应继续补 datasource projectId 快照，支持项目级审计过滤。
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
     * 把可信平台 Header 合并到请求体。
     * gateway 注入的身份上下文优先级高于请求体字段，用于逐步从“调用方自带身份”迁移到“网关注入身份”。
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
