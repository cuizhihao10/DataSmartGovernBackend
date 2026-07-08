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
 *
 * <p>更新接口允许调整名称、连接地址、账号、密码、描述和用途，但不允许直接修改 tenant/project/workspace
 * 归属，也不允许修改 type。原因是归属迁移属于治理动作，通常需要审计；类型变化则意味着它已经不是同一条
 * 数据源定义，更合理的做法是新建一条数据源。</p>
 */
@Data
public class UpdateDataSourceRequest {

    /**
     * 数据源名称。
     */
    @NotBlank(message = "数据源名称不能为空")
    private String name;

    /**
     * 数据源用途：SOURCE、TARGET、BOTH。
     *
     * <p>用户可以在编辑数据源时把一个历史 BOTH 数据源收紧为 SOURCE 或 TARGET。这样新建同步任务时，
     * 前端就能只展示符合源端/目标端角色的数据源，减少误选。</p>
     */
    private String usagePurpose;

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
     * 连接密码。
     */
    @NotBlank(message = "密码不能为空")
    private String password;

    /**
     * 描述信息。
     */
    private String description;
}
