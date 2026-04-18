package com.czh.datasmart.govern.datasource.controller.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * @Author : Cui
 * @Date: 2026/4/18 21:55
 * @Description DataSmart Govern Backend - UpdateDataSourceRequest.java
 * @Version:1.0.0
 *
 * 更新数据源请求体。
 * 当前更新接口允许调整连接地址、账号、密码和描述，
 * 但不允许直接修改 type，因为类型变化通常意味着这已经不是同一条连接定义。
 */
@Data
public class UpdateDataSourceRequest {

    /**
     * 数据源名称。
     */
    @NotBlank(message = "数据源名称不能为空")
    private String name;

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
