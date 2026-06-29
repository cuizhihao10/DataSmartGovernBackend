/**
 * @Author : Cui
 * @Date: 2026/06/29 23:59
 * @Description DataSmart Govern Backend - GatewayAuthenticationAuditEvent.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.authentication;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 网关认证审计事件。
 *
 * <p>这个 record 表达的是“gateway 已经如何解释当前请求身份”这一类低敏审计事实。
 * 它和 permission-admin 的授权审计不同：认证审计回答“你是谁、身份 claim 是否完整、gateway 是否接受该身份”，
 * 授权审计回答“这个身份能不能访问某个业务资源”。把二者拆开可以避免认证中心、网关和权限中心职责混在一起。</p>
 *
 * <p>安全边界非常重要：本事件不允许保存 access token、refresh token、完整 JWT claim、邮箱、手机号、
 * 密码、client secret、证书、prompt、SQL、工具参数、样本数据、模型输出或内部 endpoint。
 * 事件只保留租户、actor、角色、actorType、workspace、路由路径和 issueCode 等低敏控制面摘要。</p>
 *
 * @param eventId 审计事件 ID，用于日志、指标和未来审计中心落库时去重或排障。
 * @param traceId 网关链路追踪 ID，来自 X-DataSmart-Trace-Id。
 * @param outcome 认证链路结果，例如 RESOLVED、REJECTED、UNSUPPORTED_PRINCIPAL。
 * @param authenticationType 身份来源类型，例如 OIDC_JWT、TRUSTED_PLATFORM_CONTEXT、ANONYMOUS。
 * @param tenantId 租户 ID。为空表示当前请求未解析出可用租户，不能被解释为平台默认租户。
 * @param actorId 操作人 ID。为空表示当前请求未解析出可用 actor，不能被解释为匿名自动放行。
 * @param actorRole 平台角色，例如 PROJECT_OWNER、OPERATOR、AUDITOR。
 * @param actorType 操作主体类型，例如 USER、SERVICE_ACCOUNT、AGENT。
 * @param workspaceId Agent 工作区或业务隔离工作区标识，只保存低敏编码。
 * @param requestPath 当前请求路径，不包含 query string，避免把查询参数中的业务条件或敏感字段写入审计。
 * @param requestSource 请求来源，例如 OPEN_API、WEB_UI、AGENT_TOOL_CALL。
 * @param issueCodes 认证链路 issueCode 列表，用于解释为什么解析成功、失败或降级。
 * @param payloadPolicy 事件载荷策略，显式告诉消费方该事件只允许承载低敏认证摘要。
 * @param createdAt 事件生成时间。使用 OffsetDateTime 便于未来跨时区审计归档。
 */
public record GatewayAuthenticationAuditEvent(
        String eventId,
        String traceId,
        String outcome,
        String authenticationType,
        Long tenantId,
        Long actorId,
        String actorRole,
        String actorType,
        String workspaceId,
        String requestPath,
        String requestSource,
        List<String> issueCodes,
        String payloadPolicy,
        OffsetDateTime createdAt
) {
}
