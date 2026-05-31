/**
 * @Author : Cui
 * @Date: 2026/05/31 23:59
 * @Description DataSmart Govern Backend - AgentToolServiceAuthorizationProperties.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.config;

import com.czh.datasmart.govern.agent.model.AgentToolServiceAuthorizationMode;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 工具服务间授权预检配置。
 *
 * <p>为什么要把这一组配置从 {@link AgentRuntimeProperties} 中拆出来？
 * AgentRuntimeProperties 已经承载模型路由、工具目录、同步/异步执行开关等多类配置，
 * 如果继续把 permission-admin 调用、服务账号、fail-closed 策略都塞进去，后续很容易形成新的“大配置类”。
 * 独立配置类能让权限边界更清楚，也方便未来单独迁移到 Nacos、数据库或租户级策略表。</p>
 */
@Data
@ConfigurationProperties(prefix = "datasmart.agent-runtime.service-authorization")
public class AgentToolServiceAuthorizationProperties {

    /**
     * 是否启用 Agent 工具服务间授权预检。
     *
     * <p>默认关闭，是为了保证当前本地学习环境和现有测试不强依赖 permission-admin。
     * 关闭时 DAG execution preview 会返回 NOT_EVALUATED，明确告诉调用方“当前还没有权限依据”；
     * 它不会伪装成允许，避免未来真实 worker 误把 preview 当成授权结果。</p>
     */
    private Boolean enabled = false;

    /**
     * 授权预检模式。
     *
     * <p>LOCAL_PREVIEW 只检查上下文完整性；PERMISSION_ADMIN_EVALUATE 会调用 permission-admin。
     * 商业化生产环境最终应使用 PERMISSION_ADMIN_EVALUATE，并配合内网 mTLS、服务账号令牌、
     * 网关内部路由或服务网格策略，避免任意服务伪造 SERVICE_ACCOUNT。</p>
     */
    private AgentToolServiceAuthorizationMode mode = AgentToolServiceAuthorizationMode.LOCAL_PREVIEW;

    /**
     * 是否让 DAG preview 根据授权预检结果降级执行候选。
     *
     * <p>当前默认 false，原因是本阶段仍是只读预览：我们先把授权状态展示出来，不突然改变已有
     * sync/async 候选数量，降低兼容风险。等真实 DAG worker 接入前，应在生产配置中打开该开关，
     * 让未授权、未评估或权限中心不可用的工具不再展示为可真实推进。</p>
     */
    private Boolean enforceInPreview = false;

    /**
     * Agent Runtime 在 permission-admin 中对应的服务账号 actorId。
     *
     * <p>permission-admin 当前 evaluate 契约使用 Long actorId，因此这里先使用数字型服务账号。
     * 未来如果接入正式 IdP/OAuth2 client credentials，可以把它映射为服务主体 ID，而不是人类用户 ID。</p>
     */
    private Long serviceAccountActorId = 900001L;

    /**
     * 服务账号可读编码。
     *
     * <p>该字段主要用于审计、日志和前端解释，避免只展示一个数字 ID。
     * 例如后续审计台可以显示“datasmart-agent-runtime 代表 actor-preview 尝试执行 data-sync.execute”。</p>
     */
    private String serviceAccountCode = "datasmart-agent-runtime";

    /**
     * 服务账号角色编码。
     *
     * <p>permission-admin 的推荐角色集中已经包含 SERVICE_ACCOUNT。
     * 如果客户环境中服务账号角色需要细分，例如 AGENT_RUNTIME_SERVICE 或 TASK_WORKER_SERVICE，
     * 可以在权限中心扩展角色后通过配置覆盖。</p>
     */
    private String serviceAccountRole = "SERVICE_ACCOUNT";

    /**
     * permission-admin evaluate 接口地址。
     *
     * <p>默认指向本地 permission-admin；生产环境更推荐走内网服务发现、gateway 内部路由或服务网格。
     * 不建议把公网地址直接写入这里，因为服务间授权请求通常携带租户、项目、资源和动作上下文。</p>
     */
    private String permissionAdminEvaluateUrl = "http://localhost:8085/permissions/evaluate";

    /**
     * 调用 permission-admin 的连接与读取超时时间。
     *
     * <p>授权预检属于执行前关键路径，超时时间不应过长。真实 worker 中如果权限中心短暂不可用，
     * 更推荐快速失败并进入可重试/告警状态，而不是长时间占用执行线程。</p>
     */
    private Long timeoutMs = 1500L;

    /**
     * 远端权限中心不可用时是否按拒绝处理。
     *
     * <p>商业化生产环境应保持 true，即 fail-closed。当前 preview 即使 fail-closed 也只会返回预览结论；
     * 真正执行入口后续应复用同样语义，防止 permission-admin 故障期间 Agent 越权执行工具。</p>
     */
    private Boolean failClosedWhenRemoteUnavailable = true;
}
