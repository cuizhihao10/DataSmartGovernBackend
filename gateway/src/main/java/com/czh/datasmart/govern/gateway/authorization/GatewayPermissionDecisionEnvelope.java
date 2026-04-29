/**
 * @Author : Cui
 * @Date: 2026/04/25 23:20
 * @Description DataSmart Govern Backend - GatewayPermissionDecisionEnvelope.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.authorization;

import lombok.Data;

/**
 * permission-admin 返回给 gateway 的统一响应外壳。
 *
 * <p>permission-admin 使用 platform-common 的 PlatformApiResponse。
 * gateway 这里只定义一个轻量镜像对象来解析 HTTP 响应，避免为了反序列化而引入跨服务编译依赖。
 */
@Data
public class GatewayPermissionDecisionEnvelope {

    /**
     * 平台统一业务码，0 表示成功。
     */
    private Integer code;

    /**
     * 机器可读原因，例如 SUCCESS、FORBIDDEN。
     */
    private String reason;

    /**
     * 人类可读说明。
     */
    private String message;

    /**
     * 权限判定结果。
     */
    private GatewayPermissionDecisionResult data;

    /**
     * 链路 ID。
     */
    private String traceId;
}
