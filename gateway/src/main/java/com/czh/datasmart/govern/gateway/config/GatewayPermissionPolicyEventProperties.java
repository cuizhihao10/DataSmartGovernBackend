/**
 * @Author : Cui
 * @Date: 2026/04/26 20:51
 * @Description DataSmart Govern Backend - GatewayPermissionPolicyEventProperties.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 网关权限策略事件消费配置。
 *
 * <p>gateway 本地授权缓存提升性能，但也带来一个新问题：
 * permission-admin 中的策略已经变化时，gateway 不能长期依赖旧缓存。
 * 因此需要消费权限策略变更事件，在策略变化后主动清理本地缓存。
 *
 * <p>当前第一版默认关闭消费，保证本地没有 Kafka 时 gateway 仍可启动。
 * 当 Kafka 环境稳定后，可以开启该配置，并与 permission-admin 的事件发布配置配套使用。
 */
@Data
@Component("gatewayPermissionPolicyEventProperties")
@ConfigurationProperties(prefix = "datasmart.gateway.authorization.policy-events")
public class GatewayPermissionPolicyEventProperties {

    /**
     * 是否启用权限策略事件消费。
     */
    private boolean enabled = false;

    /**
     * 权限策略变更事件 Topic。
     */
    private String topic = "datasmart.permission.policy.changed";

    /**
     * gateway 消费者组。
     *
     * <p>注意：如果多个 gateway 实例使用同一个消费者组，Kafka 默认只会让其中一个实例消费某个分区消息。
     * 但缓存失效需要所有 gateway 实例都收到事件。
     * 当前本地开发可以使用固定 groupId；生产环境更推荐：
     * 1. 每个 gateway 实例使用实例级 groupId；
     * 2. 或者改用广播机制；
     * 3. 或者使用 Redis/集中式缓存，让单个消费实例清理共享缓存即可。
     */
    private String groupId = "datasmart-gateway-permission-policy-cache-invalidator";
}
