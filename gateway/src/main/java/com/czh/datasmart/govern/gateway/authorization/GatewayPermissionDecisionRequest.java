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

    /**
     * 操作者类型。
     *
     * <p>该字段来自 gateway 已验证的 OIDC claim 或受信平台 Header，典型值包括 USER、SERVICE_ACCOUNT、
     * AGENT、SYSTEM_SCHEDULER。它不直接替代 actorRole，但能让 permission-admin 和审计系统区分
     * “同样是 PROJECT_OWNER 角色的人类用户”和“以 SERVICE_ACCOUNT 身份执行的机器请求”。</p>
     */
    private String actorType;

    /**
     * 工作区 ID。
     *
     * <p>Agent Runtime、长期记忆、工具执行和数据同步都需要 workspace 边界。把它放入授权请求，可以为后续
     * workspace 级风险、数据范围、租户隔离和服务账号策略预留统一入口。</p>
     */
    private String workspaceId;

    /**
     * 请求来源。
     *
     * <p>例如 WEB_UI、OPEN_API、AGENT_TOOL_CALL、SCHEDULER。它用于审计和未来策略差异化，
     * 但当前不作为单独放行依据，避免调用方仅靠伪造来源绕过角色/路由策略。</p>
     */
    private String requestSource;

    private String httpMethod;
    private String requestPath;
    private String resourceType;
    private String action;

    /**
     * 浏览器当前选择的项目 ID。
     *
     * <p>该值仍然只是待校验输入。permission-admin 会根据项目主数据、角色数据范围和成员关系确认它是否可用，
     * 平台管理员跨租户切换时还会据此返回可信的有效租户 ID。</p>
     */
    private Long requestedProjectId;

    /**
     * 服务账号 actorId。
     *
     * <p>当 actorType 或 actorRole 为 SERVICE_ACCOUNT 时，gateway 会把当前 actorId 同步写入该字段。
     * 如果受信上游显式传入了 `X-DataSmart-Service-Account-Actor-Id`，则优先使用上游值。</p>
     */
    private Long serviceAccountActorId;

    /**
     * 服务账号可读编码。
     *
     * <p>该字段优先来自 `X-DataSmart-Service-Account-Code`，没有显式值时 gateway 会使用 sourceService 或 workspace
     * 生成低敏默认值。它不会保存 token、secret、证书、内部 URL 或其他敏感凭据。</p>
     */
    private String serviceAccountCode;

    /**
     * 被服务账号代表的业务主体。
     *
     * <p>普通用户请求通常为空；服务账号代表用户、任务、审批单或事故工单执行动作时，可以写入低敏主体标识。</p>
     */
    private String representedActorId;

    /**
     * 委托类型。
     *
     * <p>该字段只用于审计和策略解释。真正是否允许仍由 permission-admin 的角色、路由策略、数据范围和审批要求决定。</p>
     */
    private String delegationType;

    /**
     * 委托原因摘要。
     *
     * <p>只允许低敏短文本，不允许 prompt、SQL、工具参数、样本数据、模型输出或客户业务正文进入该字段。</p>
     */
    private String delegationReason;

    /**
     * 调用方期望使用的权限策略版本。
     *
     * <p>为后续“预检版本与执行版本一致性校验”预留。当前 gateway 只透传该字段，permission-admin 返回实际命中的版本。</p>
     */
    private String requestedPolicyVersion;
}
