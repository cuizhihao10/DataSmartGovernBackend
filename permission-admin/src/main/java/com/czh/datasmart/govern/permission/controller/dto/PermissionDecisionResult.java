/**
 * @Author : Cui
 * @Date: 2026/04/25 23:00
 * @Description DataSmart Govern Backend - PermissionDecisionResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 权限判定结果。
 *
 * <p>判定结果不仅返回 allowed，还返回命中的策略、数据范围和说明。
 * 这样网关或业务模块在拒绝访问时可以给出清晰原因，审计中心也能追踪“为什么允许或拒绝”。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PermissionDecisionResult {

    /**
     * 是否允许本次访问。
     */
    private Boolean allowed;

    /**
     * 判定原因，面向排障和审计。
     */
    private String reason;

    /**
     * 命中的路由策略 ID。
     */
    private Long matchedRoutePolicyId;

    /**
     * 命中的路由策略效果。
     */
    private String routeEffect;

    /**
     * 命中的数据范围级别。
     */
    private String dataScopeLevel;

    /**
     * 数据范围表达式。
     */
    private String dataScopeExpression;

    /**
     * 当前动作是否需要审批。
     */
    private Boolean approvalRequired;
}
