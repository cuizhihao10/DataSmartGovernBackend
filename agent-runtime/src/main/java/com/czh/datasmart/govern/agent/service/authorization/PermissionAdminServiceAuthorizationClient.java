/**
 * @Author : Cui
 * @Date: 2026/05/31 23:59
 * @Description DataSmart Govern Backend - PermissionAdminServiceAuthorizationClient.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.authorization;

/**
 * permission-admin 服务间授权客户端抽象。
 *
 * <p>Agent Runtime 的执行预览服务只关心“某个服务账号动作是否允许”，不应该关心 HTTP、
 * RestClient、超时、响应包裹结构等基础设施细节。因此用一个很小的接口把远端调用包起来。
 * 后续如果我们把 permission-admin 调用切换成 OpenFeign、gRPC、服务网格授权或本地缓存，
 * 上层授权预览服务无需改动。</p>
 */
public interface PermissionAdminServiceAuthorizationClient {

    /**
     * 调用 permission-admin 进行一次动作级授权判定。
     *
     * @param request 授权请求快照，包含租户、服务账号、路由路径、资源类型和动作。
     * @return 授权结果快照。
     */
    AgentToolServiceAuthorizationRemoteResult evaluate(AgentToolServiceAuthorizationRemoteRequest request);
}
