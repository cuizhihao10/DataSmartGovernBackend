/**
 * @Author : Cui
 * @Date: 2026/04/26 20:50
 * @Description DataSmart Govern Backend - PermissionPolicyChangedEvent.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.common.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 权限策略变更事件。
 *
 * <p>这是 permission-admin 与 gateway 之间的跨服务事件契约。
 * 当角色、路由策略、数据范围、菜单绑定等权限事实发生变化时，权限中心应该发布事件；
 * gateway、业务服务或 observability 可以消费事件，完成缓存失效、审计聚合、告警或策略快照刷新。
 *
 * <p>为什么放在 platform-common？
 * 因为这个事件不是 permission-admin 内部对象，也不是 gateway 内部对象，而是平台级消息契约。
 * 如果两个微服务各自复制字段，很容易在后续演进中出现字段名不一致、事件类型不一致、消费者解析失败等问题。
 *
 * <p>当前第一版主要服务 gateway 授权缓存失效。
 * 后续可以继续扩展 policyVersion、changeRiskLevel、approvalId、beforeHash、afterHash 等字段，
 * 支撑审批流、策略版本回滚和更精细的增量失效。
 *
 * <p>从 2026-05-23 起，这个事件契约也开始承载项目成员授权变更。
 * 原因是 gateway 的 PROJECT 数据范围既依赖路由策略，也依赖项目成员集合。
 * 如果项目成员关系变化后不通知 gateway，就会出现“成员已变更，但授权快照仍旧过期”的问题。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PermissionPolicyChangedEvent {

    /**
     * 事件 ID。
     *
     * <p>每一次策略变更都应有独立事件 ID，便于消费者做幂等、日志追踪和问题排查。
     */
    private String eventId;

    /**
     * 事件类型。
     *
     * <p>示例：ROUTE_POLICY_CREATED、ROUTE_POLICY_UPDATED、ROUTE_POLICY_ENABLED、ROUTE_POLICY_DISABLED。
     */
    private String eventType;

    /**
     * 租户 ID。
     *
     * <p>gateway 当前可以根据 tenantId 清理本地授权缓存。
     * 如果 tenantId=0，代表平台全局策略变化，消费者应采用更保守的全量失效。
     */
    private Long tenantId;

    /**
     * 发生变更的策略 ID。
     */
    private Long policyId;

    /**
     * 受影响角色。
     */
    private String roleCode;

    /**
     * 受影响 HTTP 方法。
     */
    private String httpMethod;

    /**
     * 受影响路径模式。
     */
    private String pathPattern;

    /**
     * 受影响资源类型。
     *
     * <p>为空表示这是一条旧式路径级策略，不按资源类型区分。
     * 非空时，gateway 或后续业务服务可以更精准地失效某类资源的授权缓存。
     */
    private String resourceType;

    /**
     * 受影响业务动作。
     *
     * <p>为空表示这是一条旧式路径级策略，不按动作区分。
     * 非空时，可以表达 CALLBACK、RECOVER、ACKNOWLEDGE 等按钮级或协议级动作。
     */
    private String action;

    /**
     * 变更后的策略效果。
     */
    private String effect;

    /**
     * 项目成员授权 ID。
     *
     * <p>当事件类型是 PROJECT_MEMBERSHIP_* 时使用。
     * 路由策略事件通常为空。
     */
    private Long membershipId;

    /**
     * 项目成员对应的主体操作者 ID。
     *
     * <p>gateway 在收到项目成员变更事件后，可以按 tenantId + memberActorId 做更精细的本地缓存失效，
     * 避免同租户其他用户的授权缓存也被一并清空。
     */
    private Long memberActorId;

    /**
     * 项目 ID。
     *
     * <p>对于 PROJECT 范围授权来说，这是最直接的失效语义之一。
     */
    private Long projectId;

    /**
     * 工作区 ID。
     *
     * <p>保留该字段是为了兼容未来更细粒度的项目/工作区层级授权。
     */
    private Long workspaceId;

    /**
     * 项目角色。
     *
     * <p>例如 OWNER、MEMBER、MAINTAINER。
     */
    private String projectRole;

    /**
     * 授权来源。
     *
     * <p>例如 MANUAL、SYNC、IMPORT、APPROVAL。
     */
    private String grantSource;

    /**
     * 项目成员关系的最新启用状态。
     *
     * <p>启用、禁用、创建、更新都属于项目成员关系变化，因此 gateway 只要看到这类事件就应当重新计算缓存。
     */
    private Boolean membershipEnabled;

    /**
     * 操作者 ID。
     */
    private Long actorId;

    /**
     * 操作者角色。
     */
    private String actorRole;

    /**
     * 链路追踪 ID。
     */
    private String traceId;

    /**
     * 事件发生时间。
     */
    private LocalDateTime occurredAt;

    /**
     * 变更摘要。
     */
    private String summary;

    /**
     * 创建路由策略变更事件。
     */
    public static PermissionPolicyChangedEvent routePolicyChanged(String eventType,
                                                                  Long tenantId,
                                                                  Long policyId,
                                                                  String roleCode,
                                                                  String httpMethod,
                                                                  String pathPattern,
                                                                  String resourceType,
                                                                  String action,
                                                                  String effect,
                                                                  Long actorId,
                                                                  String actorRole,
                                                                  String traceId,
                                                                  String summary) {
        return new PermissionPolicyChangedEvent(
                UUID.randomUUID().toString(),
                eventType,
                tenantId,
                policyId,
                roleCode,
                httpMethod,
                pathPattern,
                resourceType,
                action,
                effect,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                actorId,
                actorRole,
                traceId,
                LocalDateTime.now(),
                summary
        );
    }

    /**
     * 创建项目成员授权变更事件。
     *
     * <p>虽然类名仍然保留 PermissionPolicyChangedEvent，但从商业化治理视角看，
     * 路由策略和项目成员授权都属于权限事实变更，因此完全可以复用同一条事件契约和同一条缓存失效链路。
     *
     * @param eventType 事件类型，例如 PROJECT_MEMBERSHIP_UPDATED。
     * @param tenantId 租户 ID。
     * @param membershipId 项目成员授权主键。
     * @param memberActorId 被授权的主体操作者 ID。
     * @param projectId 项目 ID。
     * @param workspaceId 工作区 ID。
     * @param projectRole 项目角色。
     * @param grantSource 授权来源。
     * @param membershipEnabled 最新启用状态。
     * @param actorId 操作者 ID。
     * @param actorRole 操作者角色。
     * @param traceId 链路追踪 ID。
     * @param summary 事件摘要。
     * @return 项目成员授权变更事件。
     */
    public static PermissionPolicyChangedEvent projectMembershipChanged(String eventType,
                                                                        Long tenantId,
                                                                        Long membershipId,
                                                                        Long memberActorId,
                                                                        Long projectId,
                                                                        Long workspaceId,
                                                                        String projectRole,
                                                                        String grantSource,
                                                                        Boolean membershipEnabled,
                                                                        Long actorId,
                                                                        String actorRole,
                                                                        String traceId,
                                                                        String summary) {
        return new PermissionPolicyChangedEvent(
                UUID.randomUUID().toString(),
                eventType,
                tenantId,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                membershipId,
                memberActorId,
                projectId,
                workspaceId,
                projectRole,
                grantSource,
                membershipEnabled,
                actorId,
                actorRole,
                traceId,
                LocalDateTime.now(),
                summary
        );
    }
}
