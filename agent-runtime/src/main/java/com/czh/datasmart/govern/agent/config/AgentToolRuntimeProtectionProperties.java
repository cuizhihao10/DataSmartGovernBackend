/**
 * @Author : Cui
 * @Date: 2026/06/03 23:58
 * @Description DataSmart Govern Backend - AgentToolRuntimeProtectionProperties.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 工具运行时保护配置。
 *
 * <p>本配置和 {@link AgentRuntimeProperties.ToolSandboxProperties} 的职责不同：
 * sandbox 关注“工具计划是否安全、是否越权、是否符合目录和审批规则”；
 * runtime-protection 关注“工具虽然安全，但当前是否有足够运行容量、目标服务是否处于熔断冷却期”。</p>
 *
 * <p>为什么独立成配置类：
 * 1. `AgentRuntimeProperties` 已经接近 500 行，继续堆叠配置会降低可读性；
 * 2. 运行时保护未来会扩展为租户套餐、项目配额、服务健康、Redis 分布式计数、服务网格熔断等能力；
 * 3. 独立配置类能让后续拆成 quota-center 或 tool-execution-control-plane 时更平滑。</p>
 *
 * <p>当前阶段先实现 JVM 内存级保护，适合本地学习、单实例联调和第一阶段商业化原型。
 * 多实例生产环境必须把 in-flight 计数、目标服务健康和熔断状态升级为 Redis/DB/服务网格级共享状态，
 * 否则每个 agent-runtime 实例只能看到自己的本地压力，无法形成全局限流。</p>
 */
@Data
@ConfigurationProperties(prefix = "datasmart.agent-runtime.tool-runtime-protection")
public class AgentToolRuntimeProtectionProperties {

    /**
     * 是否启用工具运行时保护。
     *
     * <p>关闭后不会执行并发限制和熔断检查，但仍会返回可解释 verdict，明确告诉调用方当前保护被关闭。
     * 生产环境建议保持开启，因为 Agent 工具调用可能被模型循环、前端重复点击、自动重试或批量 DAG 推进放大。</p>
     */
    private Boolean enabled = true;

    /**
     * 当前 JVM 内所有工具执行的最大并发数。
     *
     * <p>这是最后一道本地总闸门，用于保护 agent-runtime 自身线程、HTTP 连接池、内存和下游适配器。
     * 后续进入多实例生产后，应替换为“实例级限流 + 全局租户配额”的组合，而不是只依赖本地计数。</p>
     */
    private Integer maxGlobalInFlight = 50;

    /**
     * 单租户在当前 JVM 内允许的最大工具并发数。
     *
     * <p>该值用于避免某个大租户或异常 Agent loop 独占执行资源。
     * 当前按 audit.tenantId 计数；如果旧数据缺少 tenantId，则无法应用租户级保护，只能落到全局和目标服务级保护。</p>
     */
    private Integer maxTenantInFlight = 20;

    /**
     * 单目标服务在当前 JVM 内允许的最大工具并发数。
     *
     * <p>例如 datasource-management、data-quality、task-management 分别可能有自己的连接池和吞吐能力。
     * 将并发按 targetService 隔离，可以避免质量规则生成高峰把数据源管理服务或任务中心也拖垮。</p>
     */
    private Integer maxTargetServiceInFlight = 10;

    /**
     * 是否启用目标服务连续失败熔断。
     *
     * <p>熔断解决的是“下游已经明显不健康，还继续让 Agent 自动调用”的问题。
     * 如果不熔断，模型可能因为工具失败而不断重试、改写参数、再次调用，最终形成故障放大器。</p>
     */
    private Boolean circuitBreakerEnabled = true;

    /**
     * 同一 targetService 连续失败多少次后打开熔断。
     *
     * <p>第一阶段按目标服务聚合，而不是按 toolCode 聚合，是因为多数工具最终共享同一业务微服务。
     * 后续如果某个服务下工具差异很大，可以升级为 targetService + toolCode 双维度熔断。</p>
     */
    private Integer consecutiveFailureThreshold = 3;

    /**
     * 熔断打开后的冷却时间，单位秒。
     *
     * <p>冷却期内新的工具调用会被直接拒绝，让下游服务有恢复窗口。
     * 后续可接入健康探针或半开状态，而不是简单等待固定时间。</p>
     */
    private Long circuitOpenSeconds = 60L;

    /**
     * 这些错误码不计入熔断连续失败。
     *
     * <p>不是所有失败都代表下游服务不健康。比如用户参数不合法、业务状态冲突、人工拒绝这类失败，
     * 应反馈给 Agent 或用户修正，而不是把整个 targetService 熔断。</p>
     */
    private List<String> ignoredCircuitFailureCodes = new ArrayList<>(List.of(
            "BAD_REQUEST",
            "VALIDATION_ERROR",
            "MISSING_PARAMETERS",
            "USER_REJECTED",
            "BUSINESS_STATE_CONFLICT"
    ));
}
