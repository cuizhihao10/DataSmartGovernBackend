/**
 * @Author : Cui
 * @Date: 2026/04/25 23:00
 * @Description DataSmart Govern Backend - PermissionDecisionRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 权限判定请求。
 *
 * <p>这个 DTO 是 gateway 和业务服务未来调用 permission-admin 的核心契约雏形。
 * 它同时携带路由信息和业务语义信息，原因是成熟权限系统不能只靠 URL 判断权限：
 * 同一个路径下不同动作、不同资源、不同租户的数据范围可能完全不同。
 */
@Data
public class PermissionDecisionRequest {

    /**
     * 租户 ID。为空或 0 时表示使用平台默认策略。
     */
    private Long tenantId;

    /**
     * 操作者 ID，用于审计和未来数据范围表达式计算。
     */
    private Long actorId;

    /**
     * 操作者角色编码。
     */
    @NotBlank(message = "角色编码不能为空")
    private String actorRole;

    /**
     * 操作者类型。
     *
     * <p>典型值包括 USER、SERVICE_ACCOUNT、AGENT、SYSTEM_SCHEDULER。当前授权仍以角色和路由策略为准，
     * 但 actorType 能帮助审计和后续策略区分“人类访问”“机器访问”“Agent 访问”。</p>
     */
    private String actorType;

    /**
     * 工作区 ID。
     *
     * <p>工作区是 Agent、工具执行、长期记忆和数据治理任务的隔离边界。当前 permission-admin 先接收并审计该字段，
     * 后续可扩展为 workspace 级策略、风险等级和数据范围过滤。</p>
     */
    private String workspaceId;

    /**
     * 请求来源。
     *
     * <p>例如 WEB_UI、OPEN_API、AGENT_TOOL_CALL、SCHEDULER。该字段用于审计和未来策略分层，不应单独成为放行依据。</p>
     */
    private String requestSource;

    /**
     * HTTP 方法，例如 GET、POST、PUT、DELETE。
     */
    @NotBlank(message = "HTTP 方法不能为空")
    private String httpMethod;

    /**
     * 请求路径，例如 /api/datasource/sync-tasks。
     */
    @NotBlank(message = "请求路径不能为空")
    private String requestPath;

    /**
     * 业务资源类型，例如 DATASOURCE、SYNC_TASK、QUALITY_RULE。
     */
    private String resourceType;

    /**
     * 业务动作，例如 VIEW、CREATE、EXECUTE、APPROVE。
     */
    private String action;

    /**
     * 调用方当前选择的项目 ID。
     *
     * <p>该字段来自 gateway 保存并清洗后的项目选择，仍不能直接信任。权限中心会读取 permission_project，
     * 校验项目状态、租户归属和当前数据范围，并为平台管理员解析跨租户操作的有效租户。</p>
     */
    private Long requestedProjectId;

    /**
     * 发起服务间调用的服务账号 actorId。
     *
     * <p>普通网关请求通常只需要 {@link #actorId}，但 Agent Runtime、Task Worker、异步调度器这类
     * 机器身份经常会“代表某个人类用户或上游系统”执行动作。单独保留 serviceAccountActorId，可以让
     * 审计中心区分“真正发起 HTTP 调用的机器主体”和“被代表的业务主体”，避免把所有责任都压到一个
     * SERVICE_ACCOUNT 角色上。</p>
     */
    private Long serviceAccountActorId;

    /**
     * 服务账号可读编码，例如 datasmart-agent-runtime。
     *
     * <p>actorId 适合做数据库关联，但排障和审计复盘时更需要稳定、可读、跨环境可识别的主体编码。
     * 后续如果接入 OAuth2 Client Credentials、mTLS SPIFFE ID 或服务网格身份，也可以把对应 clientId
     * 映射到该字段。</p>
     */
    private String serviceAccountCode;

    /**
     * 被服务账号代表的上游主体。
     *
     * <p>Agent 工具执行中它通常是人类用户 actorId；批处理或系统补偿中也可能是上游任务、审批单或
     * 事故工单编号。当前使用 String 是为了兼容 agent-runtime 里已有的 actorId 形态，避免过早强制
     * 所有上游主体都必须转换为 Long。</p>
     */
    private String representedActorId;

    /**
     * 委托类型。
     *
     * <p>建议值示例：SERVICE_ACCOUNT_ON_BEHALF_OF_ACTOR、SYSTEM_COMPENSATION、SCHEDULED_JOB。
     * permission-admin 当前不直接根据该字段放行，而是把它纳入审计证据；真正是否允许仍由角色、路由策略、
     * 数据范围和审批要求决定。</p>
     */
    private String delegationType;

    /**
     * 委托原因。
     *
     * <p>该字段用于解释“为什么机器身份需要代表上游主体执行动作”，例如 DAG 工具预检、异步命令入箱、
     * 任务补偿或事件回放。它会进入审计 detailJson，便于未来在审计中心按场景筛选高风险委托行为。</p>
     */
    private String delegationReason;

    /**
     * 调用方期望使用的策略版本，可为空。
     *
     * <p>当前阶段 permission-admin 会返回服务端实际命中的 policyVersion。未来如果要做“预检时命中 A 版本，
     * 执行时必须仍是 A 版本”的强一致校验，可以让调用方把上一次拿到的版本回传到该字段，再由权限中心
     * 判断是否过期。</p>
     */
    private String requestedPolicyVersion;
}
