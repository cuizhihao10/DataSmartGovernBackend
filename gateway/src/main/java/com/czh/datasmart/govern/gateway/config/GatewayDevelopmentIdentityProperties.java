/**
 * @Author : Cui
 * @Date: 2026/04/25 23:41
 * @Description DataSmart Govern Backend - GatewayDevelopmentIdentityProperties.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 网关开发期身份注入配置。
 *
 * <p>这个配置类服务于一个非常现实的过渡阶段：平台已经开始建设统一权限中心，
 * 但完整的企业 IdP、OAuth2/JWT 签发、前端登录态、服务账号签名体系还没有全部落地。
 * 如果此时仍然让 gateway 长期使用默认角色去调用 permission-admin，会产生两个问题：
 * 1. 本地联调时无法方便模拟普通用户、租户管理员、平台管理员、服务账号等不同身份；
 * 2. 授权链路看似接通了，但没有真实身份上下文，无法提前暴露多租户和多角色策略问题。
 *
 * <p>因此这里提供一个默认关闭的开发期能力：允许开发者显式传入受控格式的身份令牌，
 * 由网关在清理外部 Header 之后，重新写入平台内部可信的 X-DataSmart-* 上下文。
 *
 * <p>为什么不直接让前端传 X-DataSmart-Actor-Role？
 * 因为这些 Header 会直接影响权限、审计、租户边界和数据范围。如果业务客户端可以随意伪造，
 * 等同于绕过认证系统。即使当前只是本地开发，也要从代码结构上培养正确的安全边界：
 * 外部输入先解析，经过网关策略校验后，再转换为内部上下文。
 */
@Data
@Component
@ConfigurationProperties(prefix = "datasmart.gateway.development-identity")
public class GatewayDevelopmentIdentityProperties {

    /**
     * 是否启用开发期身份注入。
     *
     * <p>默认必须是 false。它不是生产认证能力，而是联调工具。
     * 生产环境接入真实身份中心后，应由 JWT/OAuth2 解析过滤器或服务账号签名过滤器负责写入身份上下文。
     */
    private boolean enabled = false;

    /**
     * 是否允许从 Authorization: Bearer ... 中读取开发期令牌。
     *
     * <p>开启后可以使用：
     * Authorization: Bearer dev:1:1001:PLATFORM_ADMINISTRATOR:USER:workspace-a
     *
     * <p>这里刻意要求 tokenPrefix，避免把真实 JWT 或其他 Bearer Token 误当作开发身份解析。
     */
    private boolean allowAuthorizationBearerToken = true;

    /**
     * 可选的开发期身份 Header。
     *
     * <p>它的优先级高于 Authorization，适合 curl、Postman 或接口测试脚本快速切换身份。
     * 这个 Header 依然不是“可直接信任的业务上下文”，它只是开发期身份解析器的输入。
     */
    private String identityHeader = "X-DataSmart-Dev-Identity";

    /**
     * 开发令牌前缀。
     *
     * <p>令牌格式：
     * dev:{tenantId}:{actorId}:{actorRole}[:actorType][:workspaceId]
     *
     * <p>示例：
     * dev:1:1001:ORDINARY_USER
     * dev:1:9001:PLATFORM_ADMINISTRATOR:USER:global
     * dev:1:7001:SERVICE_ACCOUNT:SERVICE_ACCOUNT:sync-executor
     */
    private String tokenPrefix = "dev:";

    /**
     * 是否在发现格式错误的开发令牌时直接拒绝请求。
     *
     * <p>true 更适合排查问题：一旦令牌写错，网关会返回 401，避免请求继续以默认匿名身份进入授权链路。
     * false 更宽松：令牌错误时忽略身份注入，让后续授权过滤器按默认身份处理。
     */
    private boolean rejectMalformedIdentity = true;

    /**
     * 开发令牌没有显式传 actorType 时使用的默认操作者类型。
     *
     * <p>多数本地手工联调模拟的是“人类用户”，因此默认 USER。
     * 如果模拟调度器、执行器、Agent 工具调用，应在令牌中显式传 SERVICE_ACCOUNT、SYSTEM_SCHEDULER 或 AGENT。
     */
    private String defaultActorType = "USER";

    /**
     * 开发令牌没有显式传 workspaceId 时使用的默认工作区。
     *
     * <p>工作区不是所有接口都必须依赖，但它是未来 Agent Runtime、任务执行隔离、资产目录隔离的重要上下文。
     * 先在网关契约中预留，可以减少后续模块接入时的上下文迁移成本。
     */
    private String defaultWorkspaceId = "default";

    /**
     * 允许开发期模拟的角色编码。
     *
     * <p>这里不依赖 permission-admin 模块中的枚举，避免 gateway 对权限中心形成编译期业务耦合。
     * 角色编码保持字符串契约，由 permission-admin 的策略数据最终决定是否允许访问。
     */
    private List<String> allowedRoles = new ArrayList<>(List.of(
            "ORDINARY_USER",
            "PROJECT_OWNER",
            "OPERATOR",
            "AUDITOR",
            "TENANT_ADMINISTRATOR",
            "PLATFORM_ADMINISTRATOR",
            "SERVICE_ACCOUNT"
    ));
}
