package com.czh.datasmart.govern.datasource.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据源配置主表实体。
 * <p>
 * 这张表对应的是“平台里登记过哪些外部数据源”。
 * 当前模块阶段重点不是做真实的数据读取，而是先把“配置登记、可用性验证、启停管理”打通。
 * <p>
 * 对学习来说，这张表也能很好地帮助理解一个典型管理型模块的建模思路：
 * 1. 业务主键和展示字段。
 * 2. 技术连接信息。
 * 3. 运行状态。
 * 4. 最近一次探测结果。
 */
@Data
@TableName("datasource_config")
public class DataSourceConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 数据源名称。
     * 用于前台展示和人工识别。
     */
    private String name;

    /**
     * 数据源类型，例如 MYSQL、POSTGRESQL。
     */
    private String type;

    /**
     * 连接 URL。
     * 当前直接存 JDBC URL，后续若引入更细粒度配置可以再拆分。
     */
    private String jdbcUrl;

    /**
     * 用户名。
     */
    private String username;

    /**
     * 密码。
     * 当前为开发阶段明文存储，后续生产化时应升级为加密或密钥管理方案。
     */
    private String password;

    /**
     * 驱动类名。
     * 该字段由 type 推导得到，保存后便于后续连接逻辑直接使用。
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

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
