package com.czh.datasmart.govern.datasource.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.czh.datasmart.govern.datasource.common.ApiResponse;
import com.czh.datasmart.govern.datasource.controller.dto.CreateDataSourceRequest;
import com.czh.datasmart.govern.datasource.controller.dto.UpdateDataSourceRequest;
import com.czh.datasmart.govern.datasource.entity.DataSourceConfig;
import com.czh.datasmart.govern.datasource.entity.DataSourceConnectionTestResult;
import com.czh.datasmart.govern.datasource.service.DataSourceManagementService;
import com.czh.datasmart.govern.datasource.support.DataSourceStatus;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.NoSuchElementException;

/**
 * 数据源管理控制器。
 * <p>
 * 这个模块对外暴露的是“管理型接口”，而不是直接执行数据采集。
 * 因此接口设计重点放在：
 * 1. 数据源配置的增删改查。
 * 2. 启停管理。
 * 3. 连接测试。
 * <p>
 * 你可以把它理解为后续更多数据治理能力的“配置入口”。
 */
@RestController
@RequestMapping("/datasources")
@RequiredArgsConstructor
public class DataSourceManagementController {

    private final DataSourceManagementService dataSourceManagementService;

    /**
     * 创建数据源配置。
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
        return ResponseEntity.ok(ApiResponse.success("datasource created", config));
    }

    /**
     * 分页查询数据源。
     * <p>
     * 默认不返回已删除数据源，这样更符合日常管理视角。
     */
    @GetMapping
    public ResponseEntity<ApiResponse<IPage<DataSourceConfig>>> listDataSources(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status) {
        LambdaQueryWrapper<DataSourceConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.ne(DataSourceConfig::getStatus, DataSourceStatus.DELETED);
        if (type != null) {
            wrapper.eq(DataSourceConfig::getType, type.toUpperCase());
        }
        if (status != null) {
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
            throw new NoSuchElementException("Datasource not found: " + id);
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
        return ResponseEntity.ok(ApiResponse.success("datasource updated", config));
    }

    /**
     * 启用数据源。
     */
    @PostMapping("/{id}/enable")
    public ResponseEntity<ApiResponse<DataSourceConfig>> enableDataSource(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("datasource enabled",
                dataSourceManagementService.enableDataSource(id)));
    }

    /**
     * 停用数据源。
     */
    @PostMapping("/{id}/disable")
    public ResponseEntity<ApiResponse<DataSourceConfig>> disableDataSource(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("datasource disabled",
                dataSourceManagementService.disableDataSource(id)));
    }

    /**
     * 测试连接。
     * <p>
     * 这是这个模块目前最核心的“动作接口”，因为它直接验证一条配置是否真的可用。
     */
    @PostMapping("/{id}/test-connection")
    public ResponseEntity<ApiResponse<DataSourceConnectionTestResult>> testConnection(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("datasource connection tested",
                dataSourceManagementService.testConnection(id)));
    }

    /**
     * 删除数据源。
     * <p>
     * 当前实现为逻辑删除，便于审计和恢复。
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<DataSourceConfig>> deleteDataSource(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("datasource deleted",
                dataSourceManagementService.deleteDataSource(id)));
    }
}
