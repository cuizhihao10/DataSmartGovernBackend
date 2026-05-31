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
 * <p>当前阶段仍默认使用内存仓储与手动 dispatch。生产级路线应继续演进为：
 * 1. MySQL command outbox，与工具审计状态同事务提交；
 * 2. 后台 dispatcher 从 outbox 领取记录；
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
     * 仓储类型。当前支持 memory，mysql 为后续生产持久化预留。
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
