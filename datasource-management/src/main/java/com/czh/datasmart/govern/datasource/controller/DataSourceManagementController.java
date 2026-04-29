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
import com.czh.datasmart.govern.datasource.support.DataSourceStatus;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
 * 这个控制器暴露的是“管理型接口”，而不是直接读取外部业务数据的接口。
 * 它负责管理连接元数据，也就是一个数据源应该如何被登记、校验、启停和查看。
 *
 * 这样设计的意义在于把“怎么连接外部系统”和“连上之后做什么”拆开：
 * - 前者由 datasource-management 模块负责。
 * - 后者由后续采集、扫描、画像、质量检测等执行型模块负责。
 */
@RestController
@RequestMapping("/datasources")
@RequiredArgsConstructor
public class DataSourceManagementController {

    /**
     * 控制器只依赖服务接口，保持接口层和业务层边界清晰。
     */
    private final DataSourceManagementService dataSourceManagementService;

    /**
     * 创建数据源登记记录。
     * 参数格式校验由 Controller 完成，真正的业务规则在 Service 层处理。
     */
    @PostMapping
    public ResponseEntity<ApiResponse<DataSourceConfig>> createDataSource(
            @Valid @RequestBody CreateDataSourceRequest request) {
        DataSourceConfig config = dataSourceManagementService.createDataSource(
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
     * 默认不返回已逻辑删除的数据源，这样更符合日常管理视角。
     */
    @GetMapping
    public ResponseEntity<ApiResponse<IPage<DataSourceConfig>>> listDataSources(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status) {
        LambdaQueryWrapper<DataSourceConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.ne(DataSourceConfig::getStatus, DataSourceStatus.DELETED);
        if (type != null && !type.isBlank()) {
            wrapper.eq(DataSourceConfig::getType, type.toUpperCase());
        }
        if (status != null && !status.isBlank()) {
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
    public ResponseEntity<ApiResponse<DataSourceConfig>> getDataSource(@PathVariable Long id) {
        DataSourceConfig config = dataSourceManagementService.getById(id);
        if (config == null || DataSourceStatus.DELETED.equals(config.getStatus())) {
            throw new NoSuchElementException("数据源不存在: " + id);
        }
        return ResponseEntity.ok(ApiResponse.success(config));
    }

    /**
     * 更新数据源配置。
     */
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<DataSourceConfig>> updateDataSource(
            @PathVariable Long id,
            @Valid @RequestBody UpdateDataSourceRequest request) {
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
     */
    @PostMapping("/{id}/enable")
    public ResponseEntity<ApiResponse<DataSourceConfig>> enableDataSource(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(
                "数据源已启用",
                dataSourceManagementService.enableDataSource(id))
        );
    }

    /**
     * 停用数据源。
     */
    @PostMapping("/{id}/disable")
    public ResponseEntity<ApiResponse<DataSourceConfig>> disableDataSource(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(
                "数据源已停用",
                dataSourceManagementService.disableDataSource(id))
        );
    }

    /**
     * 逻辑删除数据源。
     * 使用逻辑删除是为了保留历史配置，便于后续审计和问题追踪。
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<DataSourceConfig>> deleteDataSource(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(
                "数据源已删除",
                dataSourceManagementService.deleteDataSource(id))
        );
    }

    /**
     * 执行数据源连接测试。
     * 这个接口很重要，因为它验证的不是“业务是否正确”，而是“配置是否可连通”。
     */
    @PostMapping("/{id}/test")
    public ResponseEntity<ApiResponse<DataSourceConnectionTestResult>> testConnection(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(
                "数据源连接测试完成",
                dataSourceManagementService.testConnection(id))
        );
    }

    /**
     * 查询数据源能力画像。
     * 这个接口回答的是“当前这种数据源连接器理论上支持什么平台能力”，
     * 例如是否支持全量同步、增量同步、元数据发现、字段映射、检查点恢复等。
     *
     * 它的作用不是替代真实探查，而是给前端配置页、模板校验和后续同步模式约束提供统一依据。
     */
    @GetMapping("/{id}/capabilities")
    public ResponseEntity<ApiResponse<DataSourceCapabilityProfile>> getCapabilities(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(
                "数据源能力画像获取成功",
                dataSourceManagementService.getCapabilityProfile(id))
        );
    }

    /**
     * 发现数据源元数据。
     * 当前阶段主要面向关系型 JDBC 数据源，用于读取表和字段结构，
     * 为后续模板配置、字段映射、同步校验、数据资产接入提供第一版基础能力。
     */
    @PostMapping("/{id}/metadata/discover")
    public ResponseEntity<ApiResponse<DataSourceMetadataDiscoveryResult>> discoverMetadata(
            @PathVariable Long id,
            @Valid @RequestBody MetadataDiscoveryRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "数据源元数据发现完成",
                dataSourceManagementService.discoverMetadata(id, request))
        );
    }

    /**
     * 执行受控只读 SQL。
     *
     * 路由语义说明：
     * - `/datasources/{id}` 表示查询发生在哪个已登记数据源上；
     * - `/sql/read-only` 明确声明该接口只接受只读查询，不接受写入、DDL、存储过程或多语句；
     * - `/execute` 表示这是一次真实执行，而不是 SQL 生成、预检或计划解释。
     *
     * 为什么要把这个能力放在 datasource-management：
     * - 数据源凭据由本模块管理，其他模块不应复制密码；
     * - 权限、限流、SQL 安全校验也应集中在本模块，否则每个业务模块都会重复实现且容易不一致；
     * - 后续接入审计、脱敏、连接池、租户配额时，也可以在同一入口统一增强。
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
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        applyTrustedPlatformContext(request, tenantId, actorId, actorRole, actorType, sourceService, traceId);
        return ResponseEntity.ok(ApiResponse.success(
                "受控只读 SQL 执行完成",
                dataSourceManagementService.executeReadOnlySql(id, request))
        );
    }

    /**
     * 分页查询受控只读 SQL 执行审计。
     *
     * 这个接口面向运营后台和审计后台，不读取源库业务数据，只读取平台审计表。
     * 常见使用方式：
     * - 按 datasourceId 查看某个源库近期被哪些模块访问；
     * - 按 purpose 查看质量扫描、异常样本、字段画像的访问量；
     * - 按 executionStatus 快速定位失败访问；
     * - 按 sqlFingerprint 聚合同一类 SQL 的重复执行情况；
     * - 按时间窗口生成合规审计报告。
     *
     * 当前用 queryActorRole 做本地权限校验，后续应统一切换到 gateway 可信 Header。
     */
    @GetMapping("/sql/read-only/audits")
    public ResponseEntity<ApiResponse<IPage<DataSourceReadOnlySqlExecutionAudit>>> pageReadOnlySqlExecutionAudits(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) Long datasourceId,
            @RequestParam(required = false) String purpose,
            @RequestParam(required = false) String actorRole,
            @RequestParam(required = false) Long actorTenantId,
            @RequestParam(required = false) String executionStatus,
            @RequestParam(required = false) String sqlFingerprint,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
            @RequestParam(required = false) String queryActorRole,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String headerActorRole) {
        String effectiveQueryActorRole = hasText(headerActorRole) ? headerActorRole : queryActorRole;
        return ResponseEntity.ok(ApiResponse.success(
                "受控只读 SQL 执行审计查询成功",
                dataSourceManagementService.pageReadOnlySqlExecutionAudits(
                        current,
                        size,
                        datasourceId,
                        purpose,
                        actorRole,
                        actorTenantId,
                        executionStatus,
                        sqlFingerprint,
                        startTime,
                        endTime,
                        effectiveQueryActorRole))
        );
    }

    /**
     * 把可信平台 Header 合并到请求体。
     *
     * 当前项目处于从“请求体自带身份”向“gateway 统一注入身份”过渡的阶段。
     * 因此这里采用兼容策略：
     * - 如果 Header 存在，就优先使用 Header；
     * - 如果 Header 不存在，保留请求体里的旧字段，保证 data-quality 现有服务间调用不被打断。
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
     * 解析数字型平台 Header。
     */
    private Long parseLongHeader(String headerName, String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(headerName + " 必须是数字: " + value);
        }
    }

    /**
     * 判断字符串是否有真实内容。
     */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
