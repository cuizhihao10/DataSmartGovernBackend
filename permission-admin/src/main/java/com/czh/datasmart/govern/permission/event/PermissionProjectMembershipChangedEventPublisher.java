/**
 * @Author : Cui
 * @Date: 2026/05/23 16:50
 * @Description DataSmart Govern Backend - PermissionProjectMembershipChangedEventPublisher.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.event;

import com.czh.datasmart.govern.common.event.PermissionPolicyChangedEvent;
import com.czh.datasmart.govern.permission.config.PermissionPolicyEventProperties;
import com.czh.datasmart.govern.permission.controller.dto.PermissionActorContext;
import com.czh.datasmart.govern.permission.entity.PermissionEventOutbox;
import com.czh.datasmart.govern.permission.entity.PermissionProjectMembership;
import com.czh.datasmart.govern.permission.mapper.PermissionEventOutboxMapper;
import com.czh.datasmart.govern.permission.service.support.PermissionProjectMembershipAuditSupport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 项目成员授权变更事件发布器。
 *
 * <p>项目成员关系会直接影响 PROJECT 数据范围的授权结果。
 * 当成员被新增、更新、启停时，gateway 侧如果仍然缓存旧的授权项目集合，就会出现“成员关系已变，但可见项目没更新”的问题。
 * 因此这里和路由策略一样采用 outbox 模式，把项目成员授权变化转换成统一事件，交给后端投递器可靠发送。
 *
 * <p>为什么和路由策略共用同一条事件通道？
 * 因为两者本质上都属于“权限事实变化”，最终都会影响 gateway 的入口授权和业务服务的数据范围判断。
 * 共用同一条权限事件通道，可以减少网关侧 listener 数量，也便于后续统一做权限缓存失效。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PermissionProjectMembershipChangedEventPublisher {

    private final PermissionPolicyEventProperties eventProperties;
    private final PermissionEventOutboxMapper eventOutboxMapper;
    private final ObjectMapper objectMapper;

    /**
     * 发布项目成员授权变更事件。
     *
     * @param eventType 事件类型，例如 PROJECT_MEMBERSHIP_UPDATED。
     * @param membership 最新成员快照。
     * @param actorContext 操作者上下文。
     * @param summary 事件摘要。
     */
    public void publishProjectMembershipChanged(String eventType,
                                                PermissionProjectMembership membership,
                                                PermissionActorContext actorContext,
                                                String summary) {
        if (!eventProperties.isEnabled()) {
            return;
        }
        if (membership == null) {
            log.warn("跳过项目成员授权变更事件 outbox 写入，因为成员对象为空，eventType={}", eventType);
            return;
        }

        PermissionPolicyChangedEvent event = PermissionPolicyChangedEvent.projectMembershipChanged(
                eventType,
                membership.getTenantId(),
                membership.getId(),
                membership.getActorId(),
                membership.getProjectId(),
                membership.getWorkspaceId(),
                membership.getProjectRole(),
                membership.getGrantSource(),
                membership.getEnabled(),
                actorContext == null ? null : actorContext.actorId(),
                actorContext == null ? null : actorContext.actorRole(),
                actorContext == null ? null : actorContext.traceId(),
                summary
        );

        try {
            String payload = objectMapper.writeValueAsString(event);
            PermissionEventOutbox outbox = buildOutbox(event, membership, payload);
            eventOutboxMapper.insert(outbox);
            log.info("已写入项目成员授权变更 outbox，topic={}, eventId={}, eventType={}, tenantId={}, membershipId={}, actorId={}, projectId={}",
                    outbox.getTopic(), event.getEventId(), eventType, membership.getTenantId(), membership.getId(),
                    membership.getActorId(), membership.getProjectId());
        } catch (JsonProcessingException exception) {
            log.warn("项目成员授权变更事件序列化失败，eventType={}, membershipId={}, error={}",
                    eventType, membership.getId(), exception.getMessage());
        } catch (RuntimeException exception) {
            log.warn("项目成员授权变更 outbox 写入失败，eventType={}, membershipId={}, error={}",
                    eventType, membership.getId(), exception.getMessage());
            throw exception;
        }
    }

    /**
     * 构造 outbox 记录。
     *
     * <p>事件 key 继续使用 tenantId，方便 Kafka 在同一租户内尽量保持顺序。
     */
    private PermissionEventOutbox buildOutbox(PermissionPolicyChangedEvent event,
                                              PermissionProjectMembership membership,
                                              String payload) {
        LocalDateTime now = LocalDateTime.now();
        PermissionEventOutbox outbox = new PermissionEventOutbox();
        outbox.setEventId(event.getEventId());
        outbox.setEventType(event.getEventType());
        outbox.setTopic(eventProperties.getTopic());
        outbox.setEventKey(String.valueOf(membership.getTenantId()));
        outbox.setPayloadJson(payload);
        outbox.setStatus("PENDING");
        outbox.setAttemptCount(0);
        outbox.setMaxAttempts(eventProperties.getMaxAttempts());
        outbox.setNextRetryTime(now);
        outbox.setTenantId(membership.getTenantId());
        outbox.setResourceType(PermissionProjectMembershipAuditSupport.RESOURCE_TYPE_PROJECT_MEMBERSHIP);
        outbox.setResourceId("permission_project_membership:" + membership.getId());
        outbox.setTraceId(event.getTraceId());
        outbox.setCreateTime(now);
        outbox.setUpdateTime(now);
        return outbox;
    }
}
