/**
 * @Author : Cui
 * @Date: 2026/04/26 20:50
 * @Description DataSmart Govern Backend - PermissionPolicyEventProperties.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 权限策略变更事件配置。
 *
 * <p>permission-admin 是权限事实的源头。
 * 当路由策略、数据范围、角色绑定等事实发生变化时，它需要通知 gateway 和其他消费者，
 * 否则 gateway 本地授权缓存可能继续使用旧策略，导致“数据库策略已更新，但入口授权仍未生效”的一致性问题。
 *
 * <p>当前第一版默认关闭事件发送，是为了保证本地没有 Kafka 时仍能完成接口联调。
 * 当 Kafka 环境稳定后，应开启该配置，并在 gateway 侧开启消费。
 */
@Data
@Component
@ConfigurationProperties(prefix = "datasmart.permission.policy-events")
public class PermissionPolicyEventProperties {

    /**
     * 是否发布权限策略变更事件。
     *
     * <p>默认 false。开启后，路由策略创建、更新、启停成功后会向 Kafka 发送事件。
     */
    private boolean enabled = false;

    /**
     * 权限策略变更事件 Topic。
     *
     * <p>该 Topic 应由所有 gateway 实例消费，用于清理授权判定缓存。
     */
    private String topic = "datasmart.permission.policy.changed";

    /**
     * 是否启用 outbox 投递器。
     *
     * <p>通常应与 enabled 同时开启。
     * 如果只开启 enabled 但关闭 dispatcher，事件会写入 outbox 但不会自动发送，适合排查或手工补偿场景。
     */
    private boolean dispatcherEnabled = false;

    /**
     * 单次扫描最多投递多少条事件。
     *
     * <p>批量过大会让一次调度占用太久；过小会降低补偿速度。
     * 当前默认 50 条，适合开发期和早期部署。
     */
    private int dispatchBatchSize = 50;

    /**
     * 投递器固定延迟，单位毫秒。
     *
     * <p>使用毫秒字段而不是 Duration，是为了直接适配 @Scheduled 的配置占位符。
     */
    private long dispatchFixedDelayMs = 5000L;

    /**
     * Kafka 发送等待超时。
     *
     * <p>投递器会等待 send future 完成，成功后才把 outbox 标记为 SENT。
     * 超时后会进入 FAILED/DEAD 重试流程。
     */
    private Duration sendTimeout = Duration.ofSeconds(3);

    /**
     * 发送失败后的重试间隔。
     */
    private Duration retryDelay = Duration.ofSeconds(30);

    /**
     * 单条事件最大尝试次数。
     *
     * <p>超过后进入 DEAD，避免坏消息无限重试。
     * DEAD 后需要后续管理 API 或运维脚本人工处理。
     */
    private int maxAttempts = 10;

    /**
     * SENDING 状态超时时间。
     *
     * <p>服务在发送过程中崩溃时，事件可能一直停在 SENDING。
     * 投递器每轮会把超时 SENDING 恢复为 FAILED，允许继续重试。
     */
    private Duration sendingTimeout = Duration.ofMinutes(5);
}
