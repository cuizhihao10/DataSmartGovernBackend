package com.czh.datasmart.govern.datasource.controller.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * @Author : Cui
 * @Date: 2026/7/9 00:00
 * @Description DataSmart Govern Backend - TestDataSourceConnectionRequest.java
 * @Version:1.0.0
 *
 * 新建数据源页面的临时连接测试请求。
 *
 * <p>这个请求只表达“用这组连接参数试连一次外部数据库”，不会创建数据源主记录，也不会修改数据库用户密码。
 * password 字段代表数据库账号的连接密码，后端只在本次 JDBC 连接中使用，不把这次临时测试请求落库。</p>
 */
@Data
public class TestDataSourceConnectionRequest {

    /**
     * 数据源类型，例如 MYSQL、POSTGRESQL、SQLSERVER。
     */
    @NotBlank(message = "数据源类型不能为空")
    private String type;

    /**
     * JDBC 连接地址。
     */
    @NotBlank(message = "JDBC 连接地址不能为空")
    private String jdbcUrl;

    /**
     * 连接用户名。
     */
    @NotBlank(message = "用户名不能为空")
    private String username;

    /**
     * 数据库账号的连接密码。
     */
    @NotBlank(message = "连接密码不能为空")
    private String password;
}
