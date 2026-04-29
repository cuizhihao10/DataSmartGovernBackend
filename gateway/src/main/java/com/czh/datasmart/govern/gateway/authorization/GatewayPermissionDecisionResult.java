/**
 * @Author : Cui
 * @Date: 2026/04/25 23:20
 * @Description DataSmart Govern Backend - GatewayPermissionDecisionResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.authorization;

import lombok.Data;

/**
 * 网关侧权限判定结果。
 *
 * <p>字段与 permission-admin 的响应 data 保持同名，方便 Jackson 反序列化。
 * 只保留 gateway 当前需要的字段，避免把权限中心内部模型完整泄露到网关模块。
 */
@Data
public class GatewayPermissionDecisionResult {

    /**
     * 是否允许访问。
     */
    private Boolean allowed;

    /**
     * 判定原因。
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
     * 数据范围级别。
     */
    private String dataScopeLevel;

    /**
     * 是否需要审批。
     */
    private Boolean approvalRequired;
}
