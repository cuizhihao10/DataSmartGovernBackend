/**
 * @Author : Cui
 * @Date: 2026/05/31 23:59
 * @Description DataSmart Govern Backend - AgentToolServiceAuthorizationRemoteRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.authorization;

/**
 * Agent Runtime 调用 permission-admin evaluate 接口时使用的远端授权请求快照。
 *
 * <p>这里不直接复用 permission-admin 模块中的 DTO，是为了保持微服务模块边界：
 * agent-runtime 不应该在编译期依赖 permission-admin 的内部包，否则两个服务会被 Java 依赖关系耦合在一起。
 * 两边通过 HTTP JSON 契约对齐即可，未来如果接口升级为 OpenAPI/Feign/gRPC，也可以在这一层替换。</p>
 *
 * @param tenantId 授权判定的租户边界。
 * @param serviceAccountActorId 代表 Agent Runtime 的服务账号 actorId。
 * @param serviceAccountRole 服务账号角色。
 * @param httpMethod 权限中心用于匹配路由策略的 HTTP 方法。
 * @param requestPath 权限中心用于匹配路由策略的请求路径。
 * @param resourceType 业务资源类型，例如 DATASOURCE、SYNC_TASK、QUALITY_RULE。
 * @param action 需要判定的动作，例如 VIEW、CREATE、EXECUTE。
 * @param traceId 链路追踪 ID。
 */
public record AgentToolServiceAuthorizationRemoteRequest(
        Long tenantId,
        Long serviceAccountActorId,
        String serviceAccountRole,
        String httpMethod,
        String requestPath,
        String resourceType,
        String action,
        String traceId
) {
}
