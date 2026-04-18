package com.czh.datasmart.govern.datasource.controller.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 创建数据源请求。
 */
@Data
public class CreateDataSourceRequest {

    @NotBlank(message = "datasource name must not be blank")
    private String name;

    @NotBlank(message = "datasource type must not be blank")
    private String type;

    @NotBlank(message = "jdbc url must not be blank")
    private String jdbcUrl;

    @NotBlank(message = "username must not be blank")
    private String username;

    @NotBlank(message = "password must not be blank")
    private String password;

    private String description;
}
