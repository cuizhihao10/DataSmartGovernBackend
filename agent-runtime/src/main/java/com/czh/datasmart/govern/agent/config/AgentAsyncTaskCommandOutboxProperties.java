/**
 * @Author : Cui
 * @Date: 2026/05/31 17:05
 * @Description DataSmart Govern Backend - AgentAsyncTaskCommandOutboxProperties.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent ASYNC_TASK 命令 outbox 配置。
 *
 * <p>该配置不同于工具状态事件 outbox。工具状态事件 outbox 投递的是“某个事实已经发生”，
 * 而异步命令 outbox 投递的是“请 task-management 创建并执行一个任务”的动作请求。
 * 后者会触发下游副作用，因此必须更强调幂等、失败重试、死信、人工补偿和目标服务安全。</p>
 *
 * <p>当前已经支持 memory 与 MySQL 两种仓储。memory 用于本地学习、单元测试和早期联调；
 * MySQL 用于集成环境和生产环境，使待投递 command 能在服务重启、多实例部署和 dispatcher 崩溃后恢复。
 * 生产级路线应继续演进为：
 * 1. MySQL command outbox 与工具审计状态同事务提交；
 * 2. 后台 dispatcher 从 outbox 条件领取记录；
 * 3. dispatcher 投递到 Kafka 或内部 task-management consume endpoint；
 * 4. task-management Inbox 用 commandId/idempotencyKey 再次去重；
 * 5. 任务状态再回写 agent-runtime 工具审计。</p>
 */
@Data
@ConfigurationProperties(prefix = "datasmart.agent-runtime.async-task-commands.outbox")
public class AgentAsyncTaskCommandOutboxProperties {

    /**
     * 是否启用 Agent 异步命令 outbox。
     */
    private boolean enabled = true;

    /**
     * 仓储类型。
     *
     * <p>可选值：</p>
     * <p>1. memory：默认值，数据只保存在当前 JVM，适合单测、本地学习和无数据库联调；</p>
     * <p>2. mysql：写入 {@code agent_async_task_command_outbox} 表，适合集成环境、生产环境和多实例恢复。</p>
     *
     * <p>启用 mysql 时还必须设置 {@code datasmart.agent-runtime.persistence.database-enabled=true}，
     * 并正确配置 {@code datasmart.agent-runtime.persistence.jdbc.*}。这样做是为了避免只改一个字符串就让本地启动
     * 突然强依赖 MySQL。</p>
     */
    private String store = "memory";

    /**
     * command payload 协议版本，需与 task-management 消费侧保持一致。
     */
    private String schemaVersion = "datasmart.agent.async-task-command.v1";

    /**
     * 单条 command payload 最大字节数。
     *
     * <p>payload 只包含引用和治理元数据，理论上应很小。设置硬上限是为了防止未来误把 planArguments、
     * SQL、样本数据或文件清单写入命令消息。</p>
     */
    private int maxPayloadBytes = 64 * 1024;

    /**
     * 单个 run 在内存 outbox 中最多保留的命令记录。
     */
    private int maxCommandsPerRun = 500;

    /**
     * 当前 JVM 内 command outbox 总记录上限。
     */
    private int maxTotalRecords = 5000;

    /**
     * 是否启用入箱前容量保护。
     *
     * <p>outbox 的容量上限解决的是“仓储最多保留多少记录”，但它不等于执行保护。
     * 如果一个租户已经堆积大量 PENDING/FAILED/PUBLISHING command，继续允许 Agent 批量确认入箱，
     * 会把压力继续转移给 dispatcher、Kafka、task-management 和下游数据治理服务。
     * 因此这里单独提供入箱前保护开关，用于在 command 形成之前就阻断明显会扩大积压的请求。</p>
     */
    private boolean capacityProtectionEnabled = true;

    /**
     * 单次 enqueue 最多允许写入的 dispatchable command 数。
     *
     * <p>该值保护的是“一次用户确认或一次兼容批量入口”带来的瞬时放大。
     * 即使单个 run 总体允许保留 500 条记录，也不代表一次 HTTP 请求应该推进 500 个后台任务。</p>
     */
    private int maxCommandsPerEnqueue = 20;

    /**
     * 单个 run 允许存在的活跃 command 积压上限。
     *
     * <p>活跃积压包括 PENDING、PUBLISHING 和 FAILED：它们要么还没投递，要么正在投递，要么等待重试。
     * PUBLISHED 表示已交给下游，BLOCKED/IGNORED 则进入人工治理，不计入自动执行压力。</p>
     */
    private int maxActiveCommandsPerRun = 100;

    /**
     * 单个租户允许存在的活跃 command 积压上限。
     *
     * <p>这是商业化多租户场景里的第一层公平性保护：一个大租户或异常 Agent loop 不应该把全平台 outbox、
     * dispatcher 线程、Kafka 分区或 task-management worker 全部占满。后续可以把它升级为套餐级配额、Redis
     * 分布式计数器和令牌桶；当前先在本服务内基于 outbox store 做可测试的保守保护。</p>
     */
    private int maxActiveCommandsPerTenant = 1000;

    /**
     * 自动生成 task-management 任务时使用的默认优先级。
     */
    private String defaultPriority = "MEDIUM";

    /**
     * 交给 task-management 创建任务时的默认最大重试次数。
     */
    private int defaultMaxRetryCount = 3;

    /**
     * 交给 task-management 创建任务时的默认最大连续退避次数。
     */
    private int defaultMaxDeferCount = 20;

    /**
     * 是否启用后台 dispatcher。
     *
     * <p>默认关闭，避免本地学习环境没有 task-management 或 Kafka 时后台线程反复失败。
     * 可以先通过手动 dispatch 接口验证，再在集成环境打开调度。</p>
     */
    private boolean dispatcherEnabled = false;

    /**
     * dispatcher 初始延迟，单位毫秒。
     */
    private long dispatcherInitialDelayMs = 10000;

    /**
     * dispatcher 固定延迟，单位毫秒。
     */
    private long dispatcherFixedDelayMs = 5000;

    /**
     * dispatcher 单轮最多领取记录数。
     */
    private int dispatcherBatchSize = 20;

    /**
     * 单条 command 最大自动投递尝试次数。
     */
    private int dispatcherMaxAttempts = 8;

    /**
     * 基础重试退避秒数。
     */
    private long retryBackoffSeconds = 30;

    /**
     * 最大退避秒数。
     */
    private long dispatcherMaxRetryBackoffSeconds = 300;

    /**
     * 是否恢复长时间停留在 PUBLISHING 的记录。
     */
    private boolean dispatcherRecoverStalePublishingEnabled = true;

    /**
     * PUBLISHING 超时秒数。
     */
    private long dispatcherPublishingTimeoutSeconds = 300;

    /**
     * 没有任何投递目标时是否允许直接标记为 PUBLISHED。
     *
     * <p>生产环境必须保持 false。否则 command 会从待投递列表消失，但 task-management 实际没有收到。</p>
     */
    private boolean dispatcherAllowNoTargetsAsPublished = false;

    /**
     * dispatcher 投递前是否启用 Agent worker pre-check。
     *
     * <p>command outbox 只能证明“命令曾经被确认入箱”，不能证明 dispatcher 领取时仍然允许执行。
     * 开启该开关后，dispatcher 会在真正投递给 Kafka/HTTP target 前调用
     * {@code AgentAsyncTaskCommandPreCheckService}，重新复核 selected-node confirmation、当前 execution-policy、
     * sandbox verdict、runtime-protection verdict 和 policyVersion 证据。</p>
     *
     * <p>默认关闭是为了兼容本地学习环境和历史 Run 级 command：这些 command 可能还没有 confirmationId。
     * 集成环境或生产环境一旦完成 selected-node confirmation 链路接入，应逐步打开该开关，让缺确认、确认过期、
     * 沙箱拒绝等问题 fail-closed，让容量/熔断问题进入退避重试。</p>
     */
    private boolean dispatcherPreCheckEnabled = false;

    /**
     * 是否启用 Kafka 投递目标。
     *
     * <p>这是面向生产主路径的投递方式：dispatcher 从 command outbox 领取记录后，
     * 将 payloadJson 写入记录自身声明的 commandTopic，由 task-management Kafka listener 消费。
     * 默认关闭，避免本地没有 Kafka 时误触发跨服务副作用。</p>
     *
     * <p>注意：如果同时打开 HTTP target 和 Kafka target，同一 command 会被投递两次。
     * task-management Inbox 依赖 commandId/idempotencyKey 能去重，但生产灰度时仍建议一次只启用一种主通道，
     * 除非正在有意验证双通道幂等和补偿能力。</p>
     */
    private boolean dispatcherKafkaEnabled = false;

    /**
     * Kafka broker ack 等待超时，单位毫秒。
     *
     * <p>command 会触发下游创建任务，不能像普通日志一样 fire-and-forget。
     * 这里等待 broker ack，超时或失败会抛出异常，由 dispatcher 写回 FAILED 并进入退避重试。</p>
     */
    private long dispatcherKafkaSendTimeoutMs = 3000;

    /**
     * 是否启用 HTTP 投递目标。
     *
     * <p>这是 Kafka producer 之前的联调路径。后续如果改成 Kafka，HTTP target 可保留为运维补偿或本地调试通道。</p>
     */
    private boolean dispatcherHttpEnabled = false;

    /**
     * task-management command consume 地址。
     */
    private String taskManagementConsumeUrl = "http://localhost:8081/internal/agent-async-task-commands/consume";

    /**
     * HTTP 投递等待超时，单位毫秒。
     */
    private long dispatcherHttpTimeoutMs = 3000;
}
