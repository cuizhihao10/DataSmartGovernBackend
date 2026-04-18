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
