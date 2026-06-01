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
     * 是否启用后台定时调度器。
     *
     * <p>`enabled` 表示 worker 能力总开关，`schedulerEnabled` 表示是否允许应用启动后由后台线程自动触发。
     * 两者分开是为了支持更安全的灰度路径：运维人员可以先打开 `enabled=true`，继续只通过内部 `dispatch-once`
     * 手动验证链路；等状态回写、幂等、权限和下游容量都稳定后，再打开 `schedulerEnabled=true` 进入自动调度。</p>
     */
    private boolean schedulerEnabled = false;

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
     * 后台调度器每轮固定延迟毫秒数。
     *
     * <p>这里使用 fixed-delay 语义，而不是 fixed-rate：
     * fixed-delay 会等上一轮处理结束后再等待指定时间，适合执行耗时不稳定的任务 worker；
     * fixed-rate 更适合轻量轮询，如果一轮耗时超过间隔，容易形成调度堆积。</p>
     */
    private long schedulerFixedDelayMs = 5000;

    /**
     * 后台调度器单轮最多处理的任务数。
     *
     * <p>这个上限是生产保护阀。即使队列里积压很多 Agent 工具任务，单个 task-management 实例也不应在一次调度中无限 claim，
     * 否则会挤占普通任务执行器、打爆 data-sync 下游、或导致同租户任务长期霸占 worker。</p>
     */
    private int maxDispatchesPerTick = 1;

    /**
     * 当一轮调度遇到 NO_TASK 时是否立刻停止本轮。
     *
     * <p>默认 true。队列没有任务时继续循环没有价值，只会增加数据库 claim 查询压力。
     * 后续如果支持多分片、多优先级队列或多个工具队列，可以把该策略演进为“当前分片无任务后切换下一分片”。</p>
     */
    private boolean stopBatchOnNoTask = true;

    /**
     * 是否启用 worker 本地容量保护。
     *
     * <p>该开关保护的是 task-management 当前实例，不是全局租户配额。默认开启，是因为 Agent 工具可能触发数据同步、
     * 元数据扫描、质量检测、导出等真实副作用；即使权限和确认都合法，也不能让一个实例无限并发地 claim 和执行任务。
     * 关闭该开关只建议用于非常受控的本地调试环境，生产环境应保持开启，并逐步演进到 Redis/数据库级全局配额。</p>
     */
    private boolean capacityGuardEnabled = true;

    /**
     * 单个 task-management 实例允许同时进入 Agent 异步工具执行链路的最大 dispatch 数。
     *
     * <p>这里的“本地并发”覆盖手动 dispatch-once、后台 scheduler 以及未来多线程 worker 在同一个 JVM 内的竞争。
     * 当前默认 1，偏保守：先保证副作用链路可审计、可回滚、可排障，再逐步压测提高并发。
     * 多实例部署时，每个实例都会有自己的本地上限；全局并发需要后续通过 Redis/DB 租约继续补齐。</p>
     */
    private int maxLocalConcurrentExecutions = 1;

    /**
     * 两次进入 claim 阶段之间的最小本地间隔，单位毫秒。
     *
     * <p>默认 0 表示不额外节流，保持当前测试和本地调试体验。生产环境如果发现 worker 轮询过密、
     * permission-admin evaluate 压力过大、或队列为空时仍频繁打数据库，可以配置为 100-1000ms 作为保护阀。
     * 该值是实例级节流，不替代租户级/工具级限流。</p>
     */
    private long minDispatchIntervalMs = 0L;

    /**
     * 解析后的参数载荷最大字节数。
     *
     * <p>该限制保护 task-management worker 内存、日志、审计和下游工具适配器。
     * Agent 工具参数应是结构化配置和引用，不应直接携带大文件、样本数据或海量 SQL 片段。</p>
     */
    private int maxResolvedPayloadBytes = 65536;

    /**
     * 是否在 worker 执行前回查 agent-runtime 的 DAG selected-node 确认记录。
     *
     * <p>默认 true，因为真实商业化 Agent 平台不能只相信 task-management 本地任务字段。
     * selected-node 入箱时，agent-runtime 已经记录了用户确认、策略版本、选中 auditId、commandId 等证据；
     * worker 在调用真实工具前再次回查，可以防止消息被篡改、旧任务重放、错误 command 绑定到错误 audit 节点等风险。</p>
     *
     * <p>如果本地学习环境只想验证 payload 解析而没有启动 agent-runtime，可以临时关闭该开关。
     * 但生产环境建议保持开启，并配合服务账号签名、内网 ACL、超时、熔断和监控告警。</p>
     */
    private boolean confirmationCheckEnabled = true;

    /**
     * confirmation 回查失败时是否临时放行。
     *
     * <p>默认 false，表示 fail-closed：当 agent-runtime 不可达、响应异常或证据无法读取时，
     * worker 不会调用真实工具。为了避免把暂时不可用误判成永久失败，调度层会把任务 defer 回队列，
     * 等依赖恢复后重新复核。</p>
     *
     * <p>只有在非常受控的灰度环境、确认其他安全门足够兜底、且下游工具副作用风险很低时，才建议短暂设为 true。
     * 一旦设为 true，系统应通过日志和指标明确暴露“确认回查失败但放行”的次数。</p>
     */
    private boolean confirmationCheckFailOpenOnError = false;

    /**
     * 执行前复核依赖暂时不可用时的退避秒数。
     *
     * <p>该值用于 agent-runtime confirmation 查询失败、后续 permission-admin evaluate 暂时不可用、
     * 配额/限流服务短暂故障等场景。它和任务业务失败不同：任务不是不合法，而是当前控制面无法给出安全结论，
     * 所以 worker 应先释放租约、延迟重试，而不是直接失败或继续执行。</p>
     */
    private int preCheckUnavailableDeferSeconds = 30;

    /**
     * 是否在 worker 执行前调用 permission-admin 做实时授权复核。
     *
     * <p>confirmation 回查证明“这个任务来自被确认的 DAG 节点”，但它不能证明“执行时权限仍然有效”。
     * 权限策略、项目成员关系、租户状态、审批要求都可能在入箱和执行之间变化。因此真实商业化 worker
     * 需要在副作用发生前重新 evaluate，而不是只依赖入箱时的策略快照。</p>
     */
    private boolean permissionCheckEnabled = true;

    /**
     * permission-admin 不可用时是否临时放行。
     *
     * <p>默认 false，保持 fail-closed。权限中心不可用时继续执行工具，等同于在最需要安全门的时候绕过安全门。
     * 因此默认策略是 defer 当前任务，等待权限中心恢复后重试。只有低风险灰度环境才建议临时开启 fail-open。</p>
     */
    private boolean permissionCheckFailOpenOnError = false;

    /**
     * permission-admin evaluate 接口完整地址。
     *
     * <p>当前使用完整 URL 是为了本地开发简单直连；生产环境可改为内部 gateway、服务发现域名、
     * 服务网格地址或后续封装出的 OpenFeign/gRPC 客户端。该地址只用于服务间授权复核，不应暴露给外部用户。</p>
     */
    private String permissionAdminEvaluateUrl = "http://localhost:8085/permissions/evaluate";

    /**
     * 调用 permission-admin 的超时时间。
     *
     * <p>授权复核位于 worker 执行前关键路径。超时不能太长，否则一个慢权限中心会占住 worker 线程；
     * 也不能太短，否则在网络稍有抖动时产生大量 defer。当前默认 1500ms 是本地与小规模部署的折中值。</p>
     */
    private long permissionAdminTimeoutMs = 1500L;

    /**
     * task-management Agent worker 在 permission-admin 中对应的服务账号 actorId。
     *
     * <p>这里与 agent-runtime 的服务账号分开，是为了审计上能区分：
     * agent-runtime 负责计划、确认和入箱；task-management worker 负责最终执行副作用。
     * 未来接入正式 IdP 后，应把该值替换成真实服务主体 ID。</p>
     */
    private Long permissionCheckServiceAccountActorId = 900002L;

    /**
     * task-management Agent worker 的服务账号编码。
     *
     * <p>该编码会进入 permission-admin 的 delegationEvidence，便于审计台显示“哪个机器主体代表哪个用户执行了工具”。</p>
     */
    private String permissionCheckServiceAccountCode = "datasmart-task-management-agent-worker";

    /**
     * task-management Agent worker 使用的权限角色。
     *
     * <p>默认使用 SERVICE_ACCOUNT，表示这是受控机器身份。不要把它配置成 OPERATOR 或 PLATFORM_ADMINISTRATOR
     * 来绕过策略，因为那会破坏“服务账号最小权限 + 委托证据”的商业化安全模型。</p>
     */
    private String permissionCheckServiceAccountRole = "SERVICE_ACCOUNT";

    /**
     * 是否仅做 dry-run 预检。
     *
     * <p>当前默认 true。返回结果会明确告诉调用方“参数已解析但不会执行”。
     * 真正执行需要后续补工具适配器白名单、权限策略、状态回写和结果回填。</p>
     */
    private boolean dryRunOnly = true;
}
