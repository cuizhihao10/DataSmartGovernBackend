package com.czh.datasmart.govern.datasource.controller.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * @Author : Cui
 * @Date: 2026/7/9 00:00
 * @Description DataSmart Govern Backend - TestExistingDataSourceConnectionRequest.java
 * @Version:1.0.0
 *
 * 编辑数据源页面的临时连接测试请求。
 *
 * <p>编辑时用户可以修改 JDBC URL 和用户名，并选择是否填写连接密码。password 为空表示沿用平台内已保存的
 * 数据库连接密码来测试当前表单，而不是把密码改为空，也不是修改数据库账号密码。</p>
 */
@Data
public class TestExistingDataSourceConnectionRequest {

    /**
     * 表单中的 JDBC 连接地址。
     */
    @NotBlank(message = "JDBC 连接地址不能为空")
    private String jdbcUrl;

    /**
     * 表单中的连接用户名。
     */
    @NotBlank(message = "用户名不能为空")
    private String username;

    /**
     * 可选连接密码。为空时沿用已保存的数据源连接密码。
     */
    private String password;
}
