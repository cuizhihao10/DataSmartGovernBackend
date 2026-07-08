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
 *
 * <p>新建数据源时，前端页面不应该让用户手工填写 tenantId、projectId、workspaceId 这类内部治理 ID。
 * 真实产品里这些归属应来自“当前登录身份 + 当前切换的项目/工作空间上下文”，通常由 gateway 通过
 * {@code X-DataSmart-Tenant-Id}、{@code X-DataSmart-Project-Id}、{@code X-DataSmart-Workspace-Id}
 * 注入到后端。这里仍然保留这三个字段，是为了兼容历史调试脚本、E2E 脚本和还未完全切换到 Header
 * 上下文的调用方；服务端会优先使用可信 Header，只有 Header 缺失时才读取请求体字段。</p>
 */
@Data
public class CreateDataSourceRequest {

    /**
     * 兼容字段：租户 ID。
     *
     * <p>普通页面不需要填写。服务端优先读取 gateway 注入的租户 Header；如果 Header 和请求体同时存在且不一致，
     * 后端会拒绝请求，避免用户通过篡改请求体把数据源写入其他租户。</p>
     */
    private Long tenantId;

    /**
     * 兼容字段：项目 ID。
     *
     * <p>项目来自当前页面的项目切换上下文，而不是用户手输。保留本字段只是为了旧脚本可继续运行；
     * 新前端应通过 Header 或统一上下文接口传递当前项目。</p>
     */
    private Long projectId;

    /**
     * 兼容字段：工作空间 ID。
     *
     * <p>工作空间用于区分开发、测试、生产或团队协作空间。它同样应该由系统上下文自动填充，
     * 而不是让业务用户理解并输入数据库主键。</p>
     */
    private Long workspaceId;

    /**
     * 数据源名称。
     */
    @NotBlank(message = "数据源名称不能为空")
    private String name;

    /**
     * 数据源类型，例如 MYSQL、POSTGRESQL、SQLSERVER。
     */
    @NotBlank(message = "数据源类型不能为空")
    private String type;

    /**
     * 数据源用途：SOURCE、TARGET、BOTH。
     *
     * <p>该字段用于后端和前端共同过滤候选数据源：SOURCE 只出现在源端选择器，TARGET 只出现在目标端选择器，
     * BOTH 可以同时出现。空值默认 BOTH，以兼容历史数据源。</p>
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
