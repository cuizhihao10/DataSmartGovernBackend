/**
 * @Author : Cui
 * @Date: 2026/04/27 20:12
 * @Description DataSmart Govern Backend - PermissionPolicyChangedEventPublisher.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.event;

import com.czh.datasmart.govern.common.event.PermissionPolicyChangedEvent;
import com.czh.datasmart.govern.permission.config.PermissionPolicyEventProperties;
import com.czh.datasmart.govern.permission.controller.dto.PermissionActorContext;
import com.czh.datasmart.govern.permission.entity.PermissionEventOutbox;
import com.czh.datasmart.govern.permission.entity.PermissionRoutePolicy;
import com.czh.datasmart.govern.permission.mapper.PermissionEventOutboxMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 权限策略变更事件发布器。
 *
 * <p>当前发布器负责把路由策略变更转换为平台统一事件，并写入 outbox 表。
 * 它不直接调用 Kafka，而是让 {@link PermissionPolicyOutboxDispatcher} 后台投递。
 *
 * <p>这样做的关键价值是“事务一致性”：
 * 业务 Service 方法本身有 @Transactional，策略变更、审计记录、outbox 记录会一起提交或一起回滚。
 * 如果事务回滚，outbox 不会出现幽灵事件；如果 Kafka 暂时不可用，事件仍保存在数据库里等待重试。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PermissionPolicyChangedEventPublisher {

    private final PermissionPolicyEventProperties eventProperties;
    private final PermissionEventOutboxMapper eventOutboxMapper;
    private final ObjectMapper objectMapper;

    /**
     * 发布路由策略变更事件。
     *
     * <p>方法名仍然叫 publish，是因为从业务语义看 Service 确实是在“发布一个领域事实”。
     * 只是技术实现从“直接发 Kafka”升级为“写入 outbox，稍后可靠投递”。
     *
     * @param eventType 事件类型，例如 ROUTE_POLICY_CREATED。
     * @param policy 变更后的策略。
     * @param actorContext 操作者上下文。
     * @param summary 事件摘要。
     */
    public void publishRoutePolicyChanged(String eventType,
                                          PermissionRoutePolicy policy,
                                          PermissionActorContext actorContext,
                                          String summary) {
        if (!eventProperties.isEnabled()) {
            return;
        }
        if (policy == null) {
            log.warn("跳过权限策略变更事件 outbox 写入，因为策略对象为空，eventType={}", eventType);
            return;
        }

        PermissionPolicyChangedEvent event = PermissionPolicyChangedEvent.routePolicyChanged(
                eventType,
                policy.getTenantId(),
                policy.getId(),
                policy.getRoleCode(),
                policy.getHttpMethod(),
                policy.getPathPattern(),
                policy.getEffect(),
                actorContext == null ? null : actorContext.actorId(),
                actorContext == null ? null : actorContext.actorRole(),
                actorContext == null ? null : actorContext.traceId(),
                summary
        );

        try {
            String payload = objectMapper.writeValueAsString(event);
            PermissionEventOutbox outbox = buildOutbox(event, policy, payload);
            eventOutboxMapper.insert(outbox);
            log.info("已写入权限策略变更 outbox，topic={}, eventId={}, eventType={}, tenantId={}, policyId={}",
                    outbox.getTopic(), event.getEventId(), eventType, policy.getTenantId(), policy.getId());
        } catch (JsonProcessingException exception) {
            log.warn("权限策略变更事件序列化失败，eventType={}, policyId={}, error={}",
                    eventType, policy.getId(), exception.getMessage());
        } catch (RuntimeException exception) {
            log.warn("权限策略变更 outbox 写入失败，eventType={}, policyId={}, error={}",
                    eventType, policy.getId(), exception.getMessage());
            throw exception;
        }
    }

    /**
     * 构造 outbox 记录。
     *
     * <p>eventKey 使用 tenantId，便于 Kafka 层尽量保持同租户事件顺序。
     * resourceId 使用 permission_route_policy:{id}，便于后续 outbox 管理页或审计页定位事件来源。
     */
    private PermissionEventOutbox buildOutbox(PermissionPolicyChangedEvent event,
                                              PermissionRoutePolicy policy,
                                              String payload) {
        LocalDateTime now = LocalDateTime.now();
        PermissionEventOutbox outbox = new PermissionEventOutbox();
        outbox.setEventId(event.getEventId());
        outbox.setEventType(event.getEventType());
        outbox.setTopic(eventProperties.getTopic());
        outbox.setEventKey(String.valueOf(policy.getTenantId()));
        outbox.setPayloadJson(payload);
        outbox.setStatus("PENDING");
        outbox.setAttemptCount(0);
        outbox.setMaxAttempts(eventProperties.getMaxAttempts());
        outbox.setNextRetryTime(now);
        outbox.setTenantId(policy.getTenantId());
        outbox.setResourceType("PERMISSION_ROUTE_POLICY");
        outbox.setResourceId("permission_route_policy:" + policy.getId());
        outbox.setTraceId(event.getTraceId());
        outbox.setCreateTime(now);
        outbox.setUpdateTime(now);
        return outbox;
    }
}
