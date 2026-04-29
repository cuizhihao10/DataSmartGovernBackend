/**
 * @Author : Cui
 * @Date: 2026/04/26 20:51
 * @Description DataSmart Govern Backend - GatewayPermissionPolicyChangedEventConsumer.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.event;

import com.czh.datasmart.govern.common.event.PermissionPolicyChangedEvent;
import com.czh.datasmart.govern.gateway.authorization.GatewayAuthorizationDecisionCache;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 权限策略变更事件消费者。
 *
 * <p>它负责把 permission-admin 发布的策略变更事件转换为 gateway 本地授权缓存失效动作。
 * 这是权限闭环非常关键的一步：
 * 1. 管理员在 permission-admin 创建、更新、启停路由策略；
 * 2. permission-admin 发布 PermissionPolicyChangedEvent；
 * 3. gateway 收到事件后清理本地授权缓存；
 * 4. 后续请求会重新回源 permission-admin 获取最新判定。
 *
 * <p>当前失效粒度：
 * 1. tenantId=0 或空：平台全局策略变化，执行全量清理；
 * 2. tenantId 非 0：按租户清理。
 *
 * <p>为什么平台全局策略要全量清理？
 * 因为 permission-admin 判定时会同时读取“平台默认策略 + 当前租户策略”。
 * 只要平台默认策略变化，理论上所有租户的判定都可能受到影响，因此必须保守全量失效。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GatewayPermissionPolicyChangedEventConsumer {

    private static final long PLATFORM_TENANT_ID = 0L;

    private final GatewayAuthorizationDecisionCache authorizationDecisionCache;
    private final ObjectMapper objectMapper;

    /**
     * 消费权限策略变更事件。
     *
     * <p>autoStartup 绑定配置开关，默认不会启动 Kafka 监听容器。
     * 这样本地开发没有 Kafka 时，gateway 仍然可以正常启动。
     */
    @KafkaListener(
            topics = "#{@gatewayPermissionPolicyEventProperties.topic}",
            groupId = "#{@gatewayPermissionPolicyEventProperties.groupId}",
            autoStartup = "#{@gatewayPermissionPolicyEventProperties.enabled}"
    )
    public void onPermissionPolicyChanged(String payload) {
        try {
            PermissionPolicyChangedEvent event = objectMapper.readValue(payload, PermissionPolicyChangedEvent.class);
            invalidateCache(event);
        } catch (JsonProcessingException exception) {
            log.warn("权限策略变更事件反序列化失败，payload={}, error={}", payload, exception.getMessage());
        } catch (RuntimeException exception) {
            log.warn("处理权限策略变更事件失败，payload={}, error={}", payload, exception.getMessage());
        }
    }

    /**
     * 根据事件影响范围清理缓存。
     */
    private void invalidateCache(PermissionPolicyChangedEvent event) {
        String reason = "permission-policy-event:" + event.getEventType() + ":" + event.getEventId();
        Long tenantId = event.getTenantId();
        if (tenantId == null || PLATFORM_TENANT_ID == tenantId) {
            authorizationDecisionCache.evictAll(reason);
            log.info("已根据权限策略变更事件全量清理网关授权缓存，eventId={}, eventType={}, policyId={}",
                    event.getEventId(), event.getEventType(), event.getPolicyId());
            return;
        }

        authorizationDecisionCache.evictTenant(tenantId, reason);
        log.info("已根据权限策略变更事件按租户清理网关授权缓存，eventId={}, eventType={}, tenantId={}, policyId={}",
                event.getEventId(), event.getEventType(), tenantId, event.getPolicyId());
    }
}
