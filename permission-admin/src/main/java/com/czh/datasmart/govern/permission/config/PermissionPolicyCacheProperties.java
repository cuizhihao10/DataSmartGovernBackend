/**
 * @Author : Cui
 * @Date: 2026/05/06 21:36
 * @Description DataSmart Govern Backend - PermissionPolicyCacheProperties.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 权限事实缓存配置。
 *
 * <p>这里的“权限事实”指的是授权判定依赖的基础数据，例如：
 * 1. 某个租户、某个角色可用的路由策略；
 * 2. 某个租户、某个角色、某类资源对应的数据范围策略。
 *
 * <p>为什么 permission-admin 内部还需要缓存？
 * gateway 已经可以缓存最终授权判定结果，但 gateway 缓存未命中、缓存关闭、影子模式联调、
 * 或者多个业务服务直接调用 permission-admin 时，permission-admin 仍然会承受高频 evaluate 流量。
 * 如果每次 evaluate 都重新查询策略表，权限中心会很快成为全平台入口链路的数据库热点。
 *
 * <p>当前先使用进程内缓存，而不是一步到位 Redis：
 * 1. 本地缓存不需要新增序列化、网络、连接池和 Redis 高可用复杂度；
 * 2. 可以先沉淀正确的缓存键、TTL、容量、失效和运营查看模型；
 * 3. 后续迁移到 Redis 时，本配置仍然可以作为缓存策略契约，缓存实现可替换而业务代码不需要大改。
 */
@Data
@Component
@ConfigurationProperties(prefix = "datasmart.permission.policy-cache")
public class PermissionPolicyCacheProperties {

    /**
     * 是否启用 permission-admin 内部权限事实缓存。
     *
     * <p>默认开启，是因为当前缓存只服务于授权高频读取路径，且策略变更后会在本服务内做事务提交后失效。
     * 如果本地联调时怀疑缓存影响排障，可以临时关闭该配置，让每次 evaluate 都直查数据库。
     */
    private boolean enabled = true;

    /**
     * 路由策略缓存 TTL。
     *
     * <p>TTL 是兜底一致性策略，不替代主动失效。
     * 正常情况下，路由策略创建、更新、启停会主动清理对应租户缓存；
     * TTL 用来覆盖进程异常、人工改库、未来多实例事件消费延迟等非理想场景。
     */
    private Duration routePolicyTtl = Duration.ofSeconds(30);

    /**
     * 数据范围策略缓存 TTL。
     *
     * <p>数据范围策略决定用户能看哪些业务数据，比普通菜单可见性更敏感。
     * 因此默认 TTL 与路由策略保持较短，避免管理员调整数据范围后旧结果驻留过久。
     */
    private Duration dataScopePolicyTtl = Duration.ofSeconds(30);

    /**
     * 最大缓存条目数。
     *
     * <p>当前实现使用 ConcurrentHashMap，为了避免租户、角色、资源类型快速增长导致 JVM 内存不可控，
     * 超过该上限时会优先清理过期条目；如果仍然超限，则执行保守的全量清理。
     */
    private int maxEntries = 1000;
}
