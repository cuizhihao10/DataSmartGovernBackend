package com.czh.datasmart.govern.datasource.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * @Author : Cui
 * @Date: 2026/4/18 21:55
 * @Description DataSmart Govern Backend - DataSourceConfig.java
 * @Version:1.0.0
 *
 * 数据源配置主表实体。
 * 这张表对应的是“平台登记过哪些外部数据源”。
 * 当前阶段重点不是直接读取外部业务数据，而是先把“配置登记、可用性验证、启停管理”打通。
 *
 * 从学习角度看，这张表很适合理解一个典型管理型模块的建模方式：
 * 1. 展示与识别字段。
 * 2. 技术连接信息。
 * 3. 生命周期状态。
 * 4. 最近一次检测结果。
 */
@Data
@TableName("datasource_config")
public class DataSourceConfig {

    /**
     * 主键 ID。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 租户 ID。
     * 数据源属于高敏感治理资源，同一个部署实例可能服务多个企业租户，因此必须从数据模型层保留租户维度。
     * 后续权限中心、审计报表、资源配额和成本统计都会依赖该字段判断“这条连接配置属于哪个客户”。
     */
    private Long tenantId;

    /**
     * 项目 ID。
     * 租户内部往往会继续拆分为多个业务项目，例如零售订单项目、风控标签项目、财务对账项目。
     * PROJECT 数据范围最终会落到该字段，避免项目负责人看到同租户其他项目的数据源连接和元数据。
     */
    private Long projectId;

    /**
     * 工作空间 ID。
     * 工作空间通常比项目更细，适合表达“某个项目下的研发空间、生产空间、临时分析空间”等协作边界。
     * 当前先作为可选字段保留，后续可以继续扩展为空间级配额、空间级元数据缓存和空间级访问审计。
     */
    private Long workspaceId;

    /**
     * 数据源名称。
     * 用于前台展示和人工识别。
     */
    private String name;

    /**
     * 数据源类型，例如 MYSQL、POSTGRESQL、SQLSERVER。
     */
    private String type;

    /**
     * JDBC 连接地址。
     */
    private String jdbcUrl;

    /**
     * 用户名。
     */
    private String username;

    /**
     * 密码。
     * 当前开发阶段先明文保存，后续生产化应升级为加密或密钥管理方案。
     */
    private String password;

    /**
     * 驱动类名。
     * 该字段由 type 推导得到，保存后便于后续连接测试直接使用。
     */
    private String driverClassName;

    /**
     * 数据源描述。
     */
    private String description;

    /**
     * 业务状态：ACTIVE / INACTIVE / DELETED。
     */
    private String status;

    /**
     * 最近一次连接测试状态。
     */
    private String lastTestStatus;

    /**
     * 最近一次连接测试返回消息。
     */
    private String lastTestMessage;

    /**
     * 最近一次连接测试时间。
     */
    private LocalDateTime lastTestTime;

    /**
     * 创建时间。
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 更新时间。
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
