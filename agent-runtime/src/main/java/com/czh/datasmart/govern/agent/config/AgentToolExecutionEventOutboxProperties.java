/**
 * @Author : Cui
 * @Date: 2026/05/28 18:00
 * @Description DataSmart Govern Backend - AgentToolExecutionEventOutboxProperties.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 工具执行事件 outbox 配置。
 *
 * <p>outbox 的业务目标，是把“工具状态已经变化”这个事实先写进一个可查询、可重放、可补偿的本地事件箱，
 * 再由后续投递器异步发布到 Kafka、WebSocket、审计中心或长期事件库。这样可以避免典型的“双写问题”：
 * 业务状态已经从 EXECUTING 变成 SUCCEEDED，但 Kafka 投递刚好失败，导致 Python Runtime、前端和审计中心看不到结果。</p>
 *
 * <p>当前实现仍是内存版 store，原因是 agent-runtime 还没有接入 MyBatis/JDBC 持久化链路。
 * 但配置结构、状态枚举、查询入口和 MySQL 表迁移脚本已经按生产 outbox 的形态预留，后续替换成数据库实现时，
 * 只需要新增一个持久化 store，而不需要推翻工具状态事件契约或业务状态机。</p>
 */
@Data
@ConfigurationProperties(prefix = "datasmart.agent-runtime.tool-execution-events.outbox")
public class AgentToolExecutionEventOutboxProperties {

    /**
     * 是否启用 outbox sink。
     *
     * <p>默认启用，是因为 outbox 是控制面可靠性的基础能力，不依赖 Kafka broker。
     * 本地开发即使没有 Kafka，也可以通过 outbox 查询接口看到工具状态事件是否已经被 Java 控制面捕获。
     * 如果未来压测时需要暂时关闭该热窗口，可以显式设置为 false；关闭后不会影响 Kafka/projection 等其他 sink。</p>
     */
    private boolean enabled = true;

    /**
     * 单个 run 在内存 outbox 中最多保留的事件数。
     *
     * <p>工具状态事件理论上不会像 token streaming 一样高频，但真实商业环境仍可能出现大量工具并行、失败重试、
     * 审批反复或批量补偿。该上限用于防止某一个 run 的异常风暴挤占整个 JVM 内存。</p>
     */
    private int maxEventsPerRun = 1000;

    /**
     * 当前 JVM 内 outbox 记录总上限。
     *
     * <p>内存实现只能作为热窗口，不能无限增长。超过该上限时会裁剪最早写入的记录。
     * 生产级持久化实现应改为依赖数据库保留策略、分区归档和后台清理任务，而不是 JVM 裁剪。</p>
     */
    private int maxTotalRecords = 10000;

    /**
     * 单条 outbox payload 的最大字节数。
     *
     * <p>工具状态事件契约本身已经避免写入完整工具入参和完整输出，但仍需要设置硬上限，防止后续扩展字段误把大对象、
     * SQL 明细、样本数据或错误堆栈写入通用事件箱。超过上限的事件会被标记为 BLOCKED，提醒后续人工或投递器处理。</p>
     */
    private int maxPayloadBytes = 256 * 1024;

    /**
     * outbox 写入失败时是否必须阻断工具状态提交。
     *
     * <p>默认保持 false，是为了兼容当前 memory 学习模式和早期联调模式：
     * 在没有数据库事务兜底时，强行让 outbox 失败抛出异常并不能真正回滚内存审计状态，反而可能让调用方看到失败而内存状态已改变。
     * 当 {@code audit-store=mysql + outbox-store=mysql + database-enabled=true} 同时满足时，
     * outbox sink 会自动升级为必达 sink；此配置主要用于未来灰度、压测或特殊环境中手动强制开启。</p>
     */
    private boolean requiredForStateCommit = false;

    /**
     * 默认重试延迟秒数。
     *
     * <p>当前阶段还没有实现后台 outbox dispatcher，但先把该配置暴露出来，可以让后续投递器按同一套配置决定失败后多久重试。
     * 真实生产中还应扩展为指数退避、最大重试次数、死信状态和人工补偿入口。</p>
     */
    private long retryBackoffSeconds = 30;

    /**
     * 是否启用 outbox 后台投递器。
     *
     * <p>默认关闭，是为了避免本地学习环境在没有 Kafka、审计中心或 WebSocket 推送目标时不断扫描 outbox。
     * 生产环境建议在 MySQL outbox store、监控告警和至少一个投递目标准备好后再开启。
     * 开启后，dispatcher 会周期性领取 PENDING/FAILED 记录，投递成功后标记 PUBLISHED，失败后写回 FAILED 和下一次重试时间。</p>
     */
    private boolean dispatcherEnabled = false;

    /**
     * dispatcher 首次启动延迟，单位毫秒。
     *
     * <p>给应用启动、Nacos 注册、Kafka producer 初始化和数据库连接池预热留出缓冲时间，避免服务刚启动时就抢占 outbox 记录。</p>
     */
    private long dispatcherInitialDelayMs = 10000;

    /**
     * dispatcher 固定延迟轮询间隔，单位毫秒。
     *
     * <p>这里使用 fixedDelay 语义：上一轮投递完成后再等待该间隔启动下一轮。
     * 这样可以避免某一轮投递耗时较长时，下一轮重叠执行造成同一 JVM 内重复领取。</p>
     */
    private long dispatcherFixedDelayMs = 5000;

    /**
     * 单轮最多领取的 outbox 记录数。
     *
     * <p>该值控制 dispatcher 的单轮工作量。过大会让一次调度长时间占用线程，过小会降低积压恢复速度。
     * 后续压测时应结合 Kafka 吞吐、MySQL 更新成本、事件积压量和业务峰值共同调整。</p>
     */
    private int dispatcherBatchSize = 50;

    /**
     * 单条 outbox 记录最大投递尝试次数。
     *
     * <p>达到该上限后，dispatcher 会把记录转入 BLOCKED，停止自动重试。这样可以避免某条坏消息因为契约错误、
     * 权限上下文缺失或下游配置长期错误而不断消耗数据库、Kafka 与调度线程资源。后续运维台可以围绕 BLOCKED
     * 记录提供重新入队、忽略、导出、人工修复等补偿入口。</p>
     */
    private int dispatcherMaxAttempts = 10;

    /**
     * dispatcher 最大重试退避秒数。
     *
     * <p>失败重试使用 retryBackoffSeconds 作为基础值，并按尝试次数指数退避，最后不超过该上限。
     * 这样可以避免下游故障时 dispatcher 高频打爆 Kafka、数据库或审计中心。</p>
     */
    private long dispatcherMaxRetryBackoffSeconds = 300;

    /**
     * 没有任何投递目标时是否允许直接标记 PUBLISHED。
     *
     * <p>默认 false。商业化系统里“没有目标还标记成功”非常危险，会让事件从待补偿队列中消失。
     * 该开关只建议在演示、单测或临时本地验证 outbox 状态机时打开。</p>
     */
    private boolean dispatcherAllowNoTargetsAsPublished = false;

    /**
     * dispatcher 是否把 outbox payload 投递到 Kafka。
     *
     * <p>该开关与 {@code datasmart.agent-runtime.tool-execution-events.enabled} 的直接 Kafka sink 不同：
     * 直接 sink 是状态变更时立即投递，dispatcher-kafka 是从 outbox 里补偿式投递。
     * 生产环境通常应优先使用 dispatcher-kafka，并保持直接 sink 关闭，避免重复事件；消费侧仍应基于 eventId 做幂等。</p>
     */
    private boolean dispatcherKafkaEnabled = false;

    /**
     * Kafka 发送确认等待超时，单位毫秒。
     *
     * <p>dispatcher 必须知道投递是否成功，才能决定 markPublished 还是 markFailed。
     * 因此 Kafka 目标会等待 broker ack，超时会按失败处理并进入下一次重试。</p>
     */
    private long dispatcherKafkaSendTimeoutMs = 3000;
}
