/**
 * @Author : Cui
 * @Date: 2026/05/31 18:16
 * @Description DataSmart Govern Backend - AgentAsyncToolWorkerProperties.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 异步工具 worker 配置。
 *
 * <p>该配置不是 Kafka consumer 配置，而是“任务已进入 task-management 后，执行器如何准备执行”的配置。
 * 4.51 阶段只落地 payloadReference 解析和执行前预检，不直接调用 data-sync、datasource-management 等业务服务。
 * 这样可以先把安全、权限、参数和大小边界固定下来，再逐步接入真正工具适配器。</p>
 *
 * <p>为什么默认 dryRunOnly=true 且 enabled=false：</p>
 * <p>1. 当前 worker 还没有完整的工具适配器矩阵，不能把任意 targetEndpoint 当成可调用接口；</p>
 * <p>2. 异步工具可能产生数据同步、扫描、导出等副作用，必须先经过策略和人工可观测预检；</p>
 * <p>3. 本地学习环境不应因为启动 task-management 就自动认领和执行任务；</p>
 * <p>4. 后续开启真正 worker 时，应先小流量、单租户、有限工具白名单灰度。</p>
 */
@Data
@ConfigurationProperties(prefix = "datasmart.task-management.agent-async-worker")
public class AgentAsyncToolWorkerProperties {

    /**
     * 是否启用未来的后台 worker 调度。
     *
     * <p>当前 4.51 预检接口不依赖该开关，因为预检是只读动作；
     * 真正自动认领任务、调用工具适配器、回写执行结果时必须显式检查该开关。</p>
     */
    private boolean enabled = false;

    /**
     * Agent Runtime 内部服务地址。
     *
     * <p>worker 会通过它访问 `/internal/agent-runtime/.../plan-arguments`，按 payloadReference 回读受控参数快照。
     * 生产环境建议通过服务发现、网关内网地址或配置中心覆盖，而不是写死 localhost。</p>
     */
    private String agentRuntimeBaseUrl = "http://localhost:8090";

    /**
     * data-sync 内部服务地址。
     *
     * <p>当白名单适配器执行 `data-sync.execute` 时，worker 不会调用 task payload 里的任意 targetEndpoint，
     * 而是固定调用 data-sync 提供的内部幂等入口 `/internal/data-sync/agent/tasks/execute`。
     * 这个地址在本地默认指向 data-sync 服务端口；生产环境应通过 Nacos、网关内网域名或服务网格地址覆盖。</p>
     */
    private String dataSyncBaseUrl = "http://localhost:8086";

    /**
     * worker 身份标识。
     *
     * <p>后续接入真实执行器认领和心跳时，该值会进入 task.current_executor_id 和 task_execution_run.executor_id，
     * 用于租约校验、故障排查和多实例区分。</p>
     */
    private String executorId = "task-management-agent-async-worker-local";

    /**
     * 未来自动认领任务时的租约秒数。
     *
     * <p>本阶段只做预检，不使用该值；提前放入配置是为了让 worker 设计与现有 claim/heartbeat 机制对齐。</p>
     */
    private long claimLeaseSeconds = 60;

    /**
     * 解析后的参数载荷最大字节数。
     *
     * <p>该限制保护 task-management worker 内存、日志、审计和下游工具适配器。
     * Agent 工具参数应是结构化配置和引用，不应直接携带大文件、样本数据或海量 SQL 片段。</p>
     */
    private int maxResolvedPayloadBytes = 65536;

    /**
     * 是否仅做 dry-run 预检。
     *
     * <p>当前默认 true。返回结果会明确告诉调用方“参数已解析但不会执行”。
     * 真正执行需要后续补工具适配器白名单、权限策略、状态回写和结果回填。</p>
     */
    private boolean dryRunOnly = true;
}
