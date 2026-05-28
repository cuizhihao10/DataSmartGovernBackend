package com.czh.datasmart.govern.datasource.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * @Author : Cui
 * @Date: 2026/4/18 21:55
 * @Description DataSmart Govern Backend - CreateDataSourceRequest.java
 * @Version:1.0.0
 *
 * 创建数据源请求体。
 * 这个 DTO 的职责是描述调用方在登记数据源时允许提交哪些字段，
 * 而不是直接映射数据库表结构。
 */
@Data
public class CreateDataSourceRequest {

    /**
     * 租户 ID。
     * 新建数据源时必须声明租户归属，否则后续权限、审计、配额和成本统计都无法判断这条连接配置属于哪个客户。
     */
    @NotNull(message = "tenantId 不能为空")
    private Long tenantId;

    /**
     * 项目 ID。
     * 数据源连接配置属于高敏感资产，真实产品中不应该只挂在租户下，而应该进一步归属到具体项目。
     * 该字段会被 PROJECT 数据范围用于过滤列表、详情、元数据发现和只读 SQL 入口。
     */
    @NotNull(message = "projectId 不能为空")
    private Long projectId;

    /**
     * 工作空间 ID。
     * 工作空间用于在项目内部继续拆分协作边界，例如研发空间、生产空间、临时分析空间。
     * 当前允许为空，表示只落到项目层；后续如引入空间级权限，可继续强制该字段。
     */
    private Long workspaceId;

    /**
     * 数据源名称。
     */
    @NotBlank(message = "数据源名称不能为空")
    private String name;

    /**
     * 数据源类型。
     */
    @NotBlank(message = "数据源类型不能为空")
    private String type;

    /**
     * JDBC 连接地址。
     */
    @NotBlank(message = "JDBC 连接地址不能为空")
    private String jdbcUrl;

    /**
     * 用户名。
     */
    @NotBlank(message = "用户名不能为空")
    private String username;

    /**
     * 密码。
     */
    @NotBlank(message = "密码不能为空")
    private String password;

    /**
     * 描述信息。
     */
    private String description;
}
