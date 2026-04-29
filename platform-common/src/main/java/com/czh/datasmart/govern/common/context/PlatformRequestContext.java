/**
 * @Author : Cui
 * @Date: 2026/04/25 22:30
 * @Description DataSmart Govern Backend - PlatformRequestContext.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.common.context;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 平台请求上下文。
 *
 * 这是跨微服务传递“谁在什么租户下、以什么身份、通过哪条链路发起请求”的标准载体。
 * 它不是认证结果本身，也不负责做权限判定；认证应由 gateway/IdP 完成，权限判定应由 permission-admin 或本地策略完成。
 *
 * 典型流转：
 * 1. gateway 从 token、服务账号凭证或 agent 调用凭证中解析身份；
 * 2. gateway 生成 PlatformRequestContext 对应的 Header；
 * 3. 领域服务从 Header 中还原上下文；
 * 4. 服务层把上下文用于权限判断、审计、日志和下游调用。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlatformRequestContext {

    /**
     * 链路追踪 ID。一次外部请求在所有微服务中应保持一致。
     */
    private String traceId;

    /**
     * 当前租户 ID。平台级请求可以为空，但领域数据操作通常必须具备租户语义。
     */
    private Long tenantId;

    /**
     * 操作者 ID。人类用户、服务账号、Agent 都应有可审计的主体标识。
     */
    private Long actorId;

    /**
     * 操作者角色，例如 ORDINARY_USER、OPERATOR、TENANT_ADMINISTRATOR、PLATFORM_ADMINISTRATOR。
     */
    private String actorRole;

    /**
     * 操作者类型，用于区分人类用户、服务账号、Agent 和系统调度器。
     */
    private PlatformActorType actorType;

    /**
     * 来源服务名。服务间调用时用于审计调用链来源。
     */
    private String sourceService;

    /**
     * Agent 或任务工作区 ID。普通后台请求可以为空。
     */
    private String workspaceId;

    /**
     * 请求来源说明，例如 WEB_UI、OPEN_API、SCHEDULER、AGENT_TOOL_CALL。
     */
    private String requestSource;

    /**
     * 判断当前请求是否具备租户上下文。
     */
    public boolean hasTenant() {
        return tenantId != null;
    }

    /**
     * 判断当前请求是否来自机器身份。
     * 机器身份通常需要更严格的审计和更窄的权限边界，不能简单等同于平台管理员。
     */
    public boolean isMachineActor() {
        return actorType == PlatformActorType.SERVICE_ACCOUNT
                || actorType == PlatformActorType.AGENT
                || actorType == PlatformActorType.SYSTEM_SCHEDULER;
    }
}
