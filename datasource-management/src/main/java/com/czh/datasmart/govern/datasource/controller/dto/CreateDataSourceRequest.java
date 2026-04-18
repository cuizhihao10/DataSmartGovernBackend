package com.czh.datasmart.govern.datasource.controller.dto;

import jakarta.validation.constraints.NotBlank;
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
