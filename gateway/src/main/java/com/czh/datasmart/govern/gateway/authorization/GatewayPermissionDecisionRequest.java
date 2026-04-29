/**
 * @Author : Cui
 * @Date: 2026/04/25 23:20
 * @Description DataSmart Govern Backend - GatewayPermissionDecisionRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.authorization;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 网关侧权限判定请求。
 *
 * <p>这里没有直接依赖 permission-admin 模块里的 DTO，是刻意的模块边界选择：
 * gateway 可以调用 permission-admin 的 HTTP 契约，但不应该在 Maven 层直接依赖 permission-admin，
 * 否则会把“服务间 API 契约”变成“编译期强耦合”，后续微服务独立演进会变困难。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GatewayPermissionDecisionRequest {

    private Long tenantId;
    private Long actorId;
    private String actorRole;
    private String httpMethod;
    private String requestPath;
    private String resourceType;
    private String action;
}
