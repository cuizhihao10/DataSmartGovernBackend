/**
 * @Author : Cui
 * @Date: 2026/05/25 01:36
 * @Description DataSmart Govern Backend - GatewayAgentEventWebSocketProperties.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Agent 实时事件 WebSocket 入口治理配置。
 *
 * <p>这个配置类只服务 gateway 的 `/api/agent/events/ws` 入口，不直接关心 Python Runtime 内部的
 * subscribe、ack、heartbeat、reconnect 等协议细节。原因是两层职责不同：
 * 1. gateway 负责“谁能建立连接、最多能建立多少连接、握手格式是否正确”；
 * 2. Python Runtime 负责“连接建立后订阅哪个 session/run、如何 replay、如何处理 ack 和 live push”。
 *
 * <p>商业化场景里，WebSocket 与普通 HTTP 请求最大的差异是“生命周期长、资源占用持续”。如果不做入口治理，
 * 一个用户可以不断打开浏览器标签页或脚本化创建连接，导致 gateway 连接池、Python Runtime session、live outbox
 * 和下游事件存储都被拖垮。因此这里先提供本地实例级配额，后续再演进为 Redis/网关集群共享配额。
 */
@Data
@Component
@ConfigurationProperties(prefix = "datasmart.gateway.agent-events.websocket")
public class GatewayAgentEventWebSocketProperties {

    /**
     * 是否启用 Agent 实时事件 WebSocket 入口治理。
     *
     * <p>默认开启，因为它只影响 `/api/agent/events/ws` 这一条长连接入口，不会影响普通业务 REST API。
     * 如果本地调试时需要绕过配额或模拟旧行为，可以临时关闭。
     */
    private boolean enabled = true;

    /**
     * 需要治理的外部网关路径。
     *
     * <p>当前固定为 gateway 暴露给前端的统一入口。后续如果新增审计员专用事件流、运营大屏事件流或移动端轻量事件流，
     * 可以扩展成列表配置；当前先保持一个明确入口，降低误匹配风险。
     */
    private String path = "/api/agent/events/ws";

    /**
     * 是否要求请求必须携带 WebSocket Upgrade 握手头。
     *
     * <p>WebSocket 握手在 HTTP 层看起来像 GET，但必须包含 `Connection: Upgrade` 和 `Upgrade: websocket`。
     * 如果普通 HTTP GET 误打到该路径，继续转发只会得到下游难懂的错误；在 gateway 早期拦截能让问题更清晰。
     */
    private boolean requireUpgradeHeader = true;

    /**
     * 单个 gateway 实例允许的最大活跃 Agent 事件连接数。
     *
     * <p>这是实例级保护，不是最终的集群级容量控制。商业化生产环境如果有多个 gateway 实例，还需要结合 Redis、
     * 网关负载均衡策略或连接亲和性做全局配额。小于等于 0 表示不限制。
     */
    private int maxActiveConnections = 500;

    /**
     * 单个租户在当前 gateway 实例上的最大活跃连接数。
     *
     * <p>租户级配额用于防止一个大客户或异常租户把共享入口占满。默认值偏保守，后续可以按租户套餐、项目规模或
     * 管理员配置动态调整。小于等于 0 表示不限制。
     */
    private int maxConnectionsPerTenant = 100;

    /**
     * 单个操作者在当前 gateway 实例上的最大活跃连接数。
     *
     * <p>用户级配额主要防浏览器多标签页、脚本重连风暴或前端 bug。真实产品里还可以区分普通用户、审计员、运维员、
     * 服务账号等角色，给不同角色配置不同阈值。小于等于 0 表示不限制。
     */
    private int maxConnectionsPerActor = 8;

    /**
     * 触发连接配额时返回给客户端的 Retry-After 秒数。
     *
     * <p>WebSocket 客户端通常会自动重连。如果没有 Retry-After，前端可能立刻重试，形成更严重的重连风暴。
     */
    private long retryAfterSeconds = 5;
}
