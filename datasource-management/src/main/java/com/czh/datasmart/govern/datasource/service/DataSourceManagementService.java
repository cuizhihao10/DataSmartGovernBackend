package com.czh.datasmart.govern.datasource.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.czh.datasmart.govern.datasource.controller.dto.MetadataDiscoveryRequest;
import com.czh.datasmart.govern.datasource.controller.dto.ReadOnlySqlExecutionRequest;
import com.czh.datasmart.govern.datasource.controller.dto.ReadOnlySqlExecutionResult;
import com.czh.datasmart.govern.datasource.entity.DataSourceCapabilityProfile;
import com.czh.datasmart.govern.datasource.entity.DataSourceConfig;
import com.czh.datasmart.govern.datasource.entity.DataSourceConnectionTestResult;
import com.czh.datasmart.govern.datasource.entity.DataSourceMetadataDiscoveryResult;
import com.czh.datasmart.govern.datasource.entity.DataSourceReadOnlySqlExecutionAudit;
import com.czh.datasmart.govern.datasource.service.support.DatasourceProjectVisibility;

import java.time.LocalDateTime;

/**
 * @Author : Cui
 * @Date: 2026/4/18 21:55
 * @Description DataSmart Govern Backend - DataSourceManagementService.java
 * @Version:1.0.0
 *
 * 数据源管理服务接口。
 * 这里定义的是模块对外提供的核心能力，而不是单纯围绕数据库操作命名。
 */
public interface DataSourceManagementService extends IService<DataSourceConfig> {

    /**
     * 创建数据源。
     */
    DataSourceConfig createDataSource(Long tenantId, Long projectId, Long workspaceId,
                                      String name, String type, String jdbcUrl, String username,
                                      String password, String description, String usagePurpose);

    /**
     * 更新数据源。
     */
    DataSourceConfig updateDataSource(Long id, String name, String jdbcUrl, String username,
                                      String password, String description, String usagePurpose);

    /**
     * 启用数据源。
     */
    DataSourceConfig enableDataSource(Long id);

    /**
     * 停用数据源。
     */
    DataSourceConfig disableDataSource(Long id);

    /**
     * 逻辑删除数据源。
     */
    DataSourceConfig deleteDataSource(Long id);

    /**
     * 测试数据源连接。
     */
    DataSourceConnectionTestResult testConnection(Long id);

    /**
     * 获取数据源能力画像。
     * 这一步解决的是“这个数据源理论上支持哪些平台能力”，
     * 而不是去真实扫描外部库中有哪些表和字段。
     */
    DataSourceCapabilityProfile getCapabilityProfile(Long id);

    /**
     * 发现数据源元数据。
     * 当前主要面向关系型 JDBC 数据源，返回表和字段的即时探查结果。
     */
    DataSourceMetadataDiscoveryResult discoverMetadata(Long id, MetadataDiscoveryRequest request);

    /**
     * 执行受控只读 SQL。
     *
     * 这个能力是后续质量扫描、资产画像、异常样本定位的跨模块基础设施：
     * 调用方提交“只读查询意图”，datasource-management 负责统一校验权限、SQL 安全、行数上限和执行超时。
     */
    ReadOnlySqlExecutionResult executeReadOnlySql(Long id, ReadOnlySqlExecutionRequest request);

    /**
     * 分页查询受控只读 SQL 执行审计。
     *
     * 这是面向运营、审计和管理员的治理接口，用于检索“谁在什么时候因为什么目的访问了哪个数据源”。
     */
    IPage<DataSourceReadOnlySqlExecutionAudit> pageReadOnlySqlExecutionAudits(
            Integer current,
            Integer size,
            Long datasourceId,
            String purpose,
            String actorRole,
            Long actorTenantId,
            DatasourceProjectVisibility visibility,
            String executionStatus,
            String sqlFingerprint,
            LocalDateTime startTime,
            LocalDateTime endTime,
            String queryActorRole);
}
