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
     * 变更后的策略效果。
     */
    private String effect;

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
                effect,
                actorId,
                actorRole,
                traceId,
                LocalDateTime.now(),
                summary
        );
    }
}
