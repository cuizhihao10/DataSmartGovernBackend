/**
 * @Author : Cui
 * @Date: 2026/04/25 23:00
 * @Description DataSmart Govern Backend - PermissionDecisionRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 权限判定请求。
 *
 * <p>这个 DTO 是 gateway 和业务服务未来调用 permission-admin 的核心契约雏形。
 * 它同时携带路由信息和业务语义信息，原因是成熟权限系统不能只靠 URL 判断权限：
 * 同一个路径下不同动作、不同资源、不同租户的数据范围可能完全不同。
 */
@Data
public class PermissionDecisionRequest {

    /**
     * 租户 ID。为空或 0 时表示使用平台默认策略。
     */
    private Long tenantId;

    /**
     * 操作者 ID，用于审计和未来数据范围表达式计算。
     */
    private Long actorId;

    /**
     * 操作者角色编码。
     */
    @NotBlank(message = "角色编码不能为空")
    private String actorRole;

    /**
     * HTTP 方法，例如 GET、POST、PUT、DELETE。
     */
    @NotBlank(message = "HTTP 方法不能为空")
    private String httpMethod;

    /**
     * 请求路径，例如 /api/datasource/sync-tasks。
     */
    @NotBlank(message = "请求路径不能为空")
    private String requestPath;

    /**
     * 业务资源类型，例如 DATASOURCE、SYNC_TASK、QUALITY_RULE。
     */
    private String resourceType;

    /**
     * 业务动作，例如 VIEW、CREATE、EXECUTE、APPROVE。
     */
    private String action;
}
