/**
 * @Author : Cui
 * @Date: 2026/05/24 02:39
 * @Description DataSmart Govern Backend - InternalServiceEndpointProperties.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.config;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 网关内部服务端点保护规则。
 *
 * <p>这些规则表达“某条路由即使命中 permission-admin，也必须先满足网关本地服务间调用约束”。
 * 它适合保护机器身份入口、回调入口、计划接入口、usage 写回入口等高频内部协议。
 *
 * <p>之所以独立成类，而不是继续作为 `GatewayAuthorizationProperties` 的内部类，是为了控制主配置类规模。
 * 内部端点保护本身会继续扩展 token、mTLS、签名 Header、分布式限流、服务账号白名单等能力，
 * 如果全部塞回主配置类，`GatewayAuthorizationProperties` 会再次变成“所有授权细节的大杂烩”。
 */
@Data
public class InternalServiceEndpointProperties {

    /**
     * 规则名称，用于日志、指标和限流 key。
     */
    private String name;

    /**
     * 路径模式，例如 `/api/agent/plan-ingestions`。
     */
    private String pathPattern;

    /**
     * 允许调用该内部端点的角色。
     *
     * <p>默认只允许 SERVICE_ACCOUNT。平台管理员如果需要应急调用，建议使用专门的 break-glass 策略，
     * 而不是把所有管理员都长期加入内部机器接口白名单。
     */
    private List<String> allowedActorRoles = new ArrayList<>(List.of("SERVICE_ACCOUNT"));

    /**
     * 允许调用该内部端点的主体类型。
     *
     * <p>这里和 {@link #allowedActorRoles} 分开配置，是为了贴近 OIDC/Keycloak 或企业 IdP 的真实身份模型：
     * role 表示“这个主体被授予了哪些权限集合”，actorType 表示“这个主体本身是什么类型”。
     * 在生产环境里，普通用户、管理员、Agent、服务账号都可能拥有不同角色，但只有 `SERVICE_ACCOUNT`
     * 这类机器主体才应该直接调用 worker 回执、plan ingestion、内部补偿等机器协议入口。
     *
     * <p>如果只校验角色，不校验主体类型，未来一旦某个用户被临时授予 SERVICE_ACCOUNT 风格的管理角色，
     * 就可能绕过正常的人机交互入口，直接构造内部控制面请求。默认值要求 actorType 也是 SERVICE_ACCOUNT，
     * 让“服务账号角色 + 服务账号主体类型”共同构成内部端点的第一道身份门槛。
     */
    private List<String> allowedActorTypes = new ArrayList<>(List.of("SERVICE_ACCOUNT"));

    /**
     * 可选内部 Token Header 名称。
     *
     * <p>如果 internalToken 为空，则不校验 token，只校验角色与限流。
     * 后续生产环境可以把 token 换成 mTLS、JWT client credential 或签名 Header。
     */
    private String tokenHeaderName = "X-DataSmart-Internal-Token";

    /**
     * 可选内部 Token。
     */
    private String internalToken = "";

    /**
     * 是否启用本地限流。
     */
    private boolean rateLimitEnabled = true;

    /**
     * 每分钟最大请求数。
     *
     * <p>当前是单网关实例内的固定窗口限流。生产多实例要继续演进为 Redis 或网关级分布式限流。
     */
    private int maxRequestsPerMinute = 120;

    /**
     * 规则说明。
     */
    private String description;
}
