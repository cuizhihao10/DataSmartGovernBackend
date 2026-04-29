/**
 * @Author : Cui
 * @Date: 2026/04/27 22:20
 * @Description DataSmart Govern Backend - TaskManagementIntegrationProperties.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * task-management 集成配置。
 *
 * <p>数据质量模块负责规则、报告和异常明细；任务模块负责排队、认领、心跳、超时恢复和重试。
 * 两者组合后，质量检测才能从“同步接口立即执行”演进为“可调度、可恢复、可运营”的后台任务。
 *
 * <p>当前先使用 HTTP 契约创建任务，而不是引入编译期 Java 依赖：
 * 1. 保持微服务边界清晰；
 * 2. 避免 data-quality 直接依赖 task-management 内部包结构；
 * 3. 后续可以自然切换为网关地址、服务发现、Kafka 命令事件或 OpenFeign。
 */
@Data
@Component
@ConfigurationProperties(prefix = "datasmart.quality.task-management")
public class TaskManagementIntegrationProperties {

    /**
     * 是否启用真实任务提交。
     *
     * <p>开发环境如果只想预览扫描计划，可以设为 false；
     * 生产环境应设为 true，让质量检测进入任务中心统一调度。
     */
    private boolean enabled = true;

    /**
     * task-management 服务基础地址。
     */
    private String baseUrl = "http://localhost:8081";

    /**
     * 质量检测任务类型。
     *
     * <p>执行器后续可以按这个类型认领质量检测任务，例如 claim 时传入 taskType=DATA_QUALITY_SCAN。
     */
    private String taskType = "DATA_QUALITY_SCAN";

    /**
     * 默认优先级。
     */
    private String defaultPriority = "MEDIUM";

    /**
     * 默认最大重试次数。
     */
    private Integer defaultMaxRetryCount = 3;

    /**
     * data-quality 本地质量执行器默认实例 ID。
     *
     * <p>后续质量执行器 coordinator 认领任务时会作为 executorId 使用。
     * 生产环境建议配置为 Pod 名、机器名或实例编号，便于按实例排查失败率、耗时和资源热点。
     */
    private String executorId = "data-quality-executor-local";

    /**
     * 是否启用 data-quality 内置质量执行器 coordinator。
     *
     * <p>默认关闭是一个刻意的生产安全设计：
     * 1. 当前还没有真实源数据扫描器，不能让服务启动后自动消费任务并伪造成功；
     * 2. 本地联调时可以手动打开并调用 run-once 接口，观察认领、心跳、失败闭环；
     * 3. 未来真实扫描器稳定后，再考虑定时轮询、线程池并发、租户公平调度和自动扩缩容。
     */
    private boolean executorCoordinatorEnabled = false;

    /**
     * 是否启用 data-quality 内置质量执行器后台调度器。
     *
     * <p>它与 executorCoordinatorEnabled 是两层不同的开关：
     * - executorCoordinatorEnabled 表示“执行器协调能力是否允许工作”；
     * - executorSchedulerEnabled 表示“是否由后台定时器自动触发执行器”。
     *
     * <p>这样拆分的原因是：本地联调时可以只开启 coordinator，然后通过接口手动 run-once；
     * 真正希望服务自动消费 task-management 队列时，再额外开启 scheduler。
     * 商业产品里，自动消费任务会影响源库负载、队列积压、告警和租户配额，因此不能只靠一个粗粒度开关控制。
     */
    private boolean executorSchedulerEnabled = false;

    /**
     * 后台调度器首次触发前等待秒数。
     *
     * <p>服务刚启动时，MySQL、Nacos、task-management、datasource-management 等依赖可能仍在恢复。
     * 适当延迟第一次扫描，可以减少启动期间的无效失败日志，也给服务注册和连接池预热留出时间。
     */
    private Integer executorSchedulerInitialDelaySeconds = 30;

    /**
     * 后台调度器两轮扫描之间的固定延迟秒数。
     *
     * <p>这里采用 fixedDelay 语义：上一轮执行完全结束后，再等待指定时间启动下一轮。
     * 它比 fixedRate 更适合质量扫描，因为每轮耗时可能受源库大小、网络、SQL 超时影响；
     * 如果使用固定频率，上一轮没结束下一轮又开始，容易造成执行器重叠消费和源库压力放大。
     */
    private Integer executorSchedulerFixedDelaySeconds = 30;

    /**
     * 后台调度器单轮最多处理多少条任务。
     *
     * <p>当前默认 1，是刻意保守的商业化演进策略：
     * - 先证明 claim -> heartbeat -> scan -> report -> complete/fail 闭环稳定；
     * - 再逐步提高批量或引入线程池；
     * - 避免一个错误配置瞬间消费大量任务并对多个客户源库产生压力。
     */
    private Integer executorSchedulerMaxRunsPerTick = 1;

    /**
     * 是否启用执行器本实例并发护栏。
     *
     * <p>该护栏用于限制当前 data-quality 实例内同时运行的质量扫描数量。
     * 它不是分布式配额，也不替代 task-management 的队列认领和租约机制；
     * 它解决的是另一个问题：当单个 data-quality 实例未来开启 runBatch、多线程池或更高调度频率时，
     * 不要在本实例内同时压住过多源库连接、HTTP 线程和 JDBC 查询。
     */
    private boolean executorConcurrencyGuardEnabled = true;

    /**
     * 当前 data-quality 实例允许同时运行的质量扫描总数。
     *
     * <p>默认 2 是保守值。后续如果引入线程池、连接池隔离、源库熔断和 Prometheus 告警，
     * 可以根据机器规格和客户源库承受能力逐步提高。
     */
    private Integer executorMaxConcurrentRunsGlobal = 2;

    /**
     * 当前 data-quality 实例内，单个租户允许同时运行的质量扫描数量。
     *
     * <p>默认 1 是为了避免某个租户在高频规则或人工批量触发时占满整个执行器。
     * 当前任务 payload 里的 tenantId 还处于过渡设计，未来应由 gateway/permission-admin 的可信租户上下文写入。
     */
    private Integer executorMaxConcurrentRunsPerTenant = 1;

    /**
     * 当前 data-quality 实例内，单个数据源允许同时运行的质量扫描数量。
     *
     * <p>默认 1 是为了保护客户源库。即使多个规则都指向同一个 datasource，也不应该在没有连接池隔离、
     * 慢查询熔断和源库配额前并发打上去。
     */
    private Integer executorMaxConcurrentRunsPerDatasource = 1;

    /**
     * 执行器因本实例并发护栏拒绝任务时，回写 task-management 的延迟秒数。
     *
     * <p>该配置只处理“容量/配额暂时不足”的场景，不处理业务失败。
     * 如果当前 data-quality 实例发现全局、租户或数据源并发已满，会把已认领任务 defer 回任务中心，
     * task-management 会在 queued_time 到期后允许它再次被认领。
     *
     * <p>默认 30 秒是保守退避值：
     * - 太短会导致任务被频繁认领又退避，增加 task-management 和执行器压力；
     * - 太长会让用户感知任务长时间无进展；
     * - 后续可以升级为指数退避、按租户等级退避、按数据源健康度退避。
     */
    private Integer executorThrottleDeferSeconds = 30;

    /**
     * 执行器认领和心跳默认租约秒数。
     *
     * <p>租约越短，执行器失联后恢复越快，但心跳请求越频繁；
     * 租约越长，网络抖动容忍度更高，但故障恢复更慢。
     */
    private Long executorLeaseSeconds = 60L;

    /**
     * 调用 task-management 执行器接口时透传的平台租户 ID。
     *
     * <p>当前本地联调使用 0 表示平台级系统调用。
     * 多租户生产环境中，应由服务账号或调度器上下文提供真实租户范围。
     */
    private Long executorActorTenantId = 0L;

    /**
     * 调用 task-management 执行器接口时透传的平台操作者 ID。
     *
     * <p>当前本地联调用 0 表示系统服务账号。
     */
    private Long executorActorId = 0L;

    /**
     * 调用 task-management 执行器接口时透传的平台操作者角色。
     *
     * <p>task-management 服务层当前允许 SERVICE_ACCOUNT、OPERATOR、PLATFORM_ADMINISTRATOR 认领和心跳。
     * 质量执行器属于机器身份，因此默认使用 SERVICE_ACCOUNT。
     */
    private String executorActorRole = "SERVICE_ACCOUNT";

    /**
     * 服务间调用来源标识。
     *
     * <p>该值会写入 `X-DataSmart-Source-Service`，方便 gateway、日志和审计系统识别调用来源。
     */
    private String sourceService = "data-quality";

    /**
     * task-management 调用失败时是否返回非提交结果而不是抛异常。
     *
     * <p>开发联调期可以设置 true，方便只验证 data-quality 侧计划生成；
     * 生产环境建议 false，避免调用方误以为任务已经进入队列。
     */
    private boolean failOpen = false;

    /**
     * 返回 Spring @Scheduled initialDelayString 可直接使用的毫秒值。
     *
     * <p>配置面向用户使用秒，是因为秒更容易理解；Spring 定时注解使用毫秒，
     * 所以在配置类里统一完成换算，避免调度器代码到处散落单位转换。
     */
    public Long getExecutorSchedulerInitialDelayMillis() {
        return Math.max(0, nullToDefault(executorSchedulerInitialDelaySeconds, 30)) * 1000L;
    }

    /**
     * 返回 Spring @Scheduled fixedDelayString 可直接使用的毫秒值。
     *
     * <p>最小固定延迟压到 1 秒，避免误配置为 0 或负数后形成疯狂轮询。
     */
    public Long getExecutorSchedulerFixedDelayMillis() {
        return Math.max(1, nullToDefault(executorSchedulerFixedDelaySeconds, 30)) * 1000L;
    }

    /**
     * 返回调度器单轮安全批量。
     *
     * <p>上限暂时限制为 20。后续如果要处理更高吞吐，应该引入线程池、租户配额、数据源配额和指标，
     * 而不是简单把单轮批量调得很大。
     */
    public Integer getSafeExecutorSchedulerMaxRunsPerTick() {
        return Math.max(1, Math.min(nullToDefault(executorSchedulerMaxRunsPerTick, 1), 20));
    }

    /**
     * 返回本实例全局并发安全上限。
     */
    public Integer getSafeExecutorMaxConcurrentRunsGlobal() {
        return Math.max(1, Math.min(nullToDefault(executorMaxConcurrentRunsGlobal, 2), 100));
    }

    /**
     * 返回本实例单租户并发安全上限。
     */
    public Integer getSafeExecutorMaxConcurrentRunsPerTenant() {
        return Math.max(1, Math.min(nullToDefault(executorMaxConcurrentRunsPerTenant, 1), 100));
    }

    /**
     * 返回本实例单数据源并发安全上限。
     */
    public Integer getSafeExecutorMaxConcurrentRunsPerDatasource() {
        return Math.max(1, Math.min(nullToDefault(executorMaxConcurrentRunsPerDatasource, 1), 100));
    }

    /**
     * 返回执行器容量退避的安全延迟秒数。
     *
     * <p>限制在 1 到 3600 秒之间，与 task-management 的接口校验保持一致。
     * 这样即使配置写错，也不会出现 0 秒疯狂重试或超长时间隐藏任务的问题。
     */
    public Integer getSafeExecutorThrottleDeferSeconds() {
        return Math.max(1, Math.min(nullToDefault(executorThrottleDeferSeconds, 30), 3600));
    }

    /**
     * 处理可空整数默认值。
     */
    private Integer nullToDefault(Integer value, Integer defaultValue) {
        return value == null ? defaultValue : value;
    }
}
