/**
 * @Author : Cui
 * @Date: 2026/06/27 01:24
 * @Description DataSmart Govern Backend - AgentCommandTaskFinalStateCallbackDispatchRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Agent command 最终态回调投递请求。
 *
 * <p>该请求不是普通用户“手动改任务状态”的入口，而是给智能网关、运维台或未来补偿 worker 使用的
 * 受控投递命令。它的业务语义是：先基于 commandId 重新执行一次低敏最终态对账，再把对账建议映射为
 * task-management 已有执行器回调协议。</p>
 *
 * <p>为什么请求里继续保留 tenantId/projectId/actorId/runId/sessionId 这些过滤条件：
 * 这些字段不会扩大权限，只会缩小 Header 中的可信数据范围。真实可见范围仍由 gateway/permission-admin
 * 透传的访问上下文决定。这样可以避免平台管理员误把同名 commandId 下的旧 run 或其他项目 receipt 当成当前任务结果。</p>
 */
@Data
public class AgentCommandTaskFinalStateCallbackDispatchRequest {

    /**
     * 必填 commandId。
     *
     * <p>commandId 是跨 agent-runtime outbox、task-management inbox、worker receipt 和最终态对账的主关联键。
     * 该接口禁止无 commandId 的全量扫描，避免补偿 worker 因配置错误一次性扫描或推进过多任务。</p>
     */
    @NotBlank(message = "commandId 不能为空")
    private String commandId;

    /**
     * 可选工具编码。
     *
     * <p>当调用方知道本次 command 应属于哪个工具时，建议传入 toolCode。
     * 服务端会把它作为 receipt 查询过滤条件，降低异常重放、历史迁移或测试数据导致的错配风险。</p>
     */
    private String toolCode;

    /**
     * 可选租户过滤条件，只能缩小可信 Header 范围。
     */
    private String tenantId;

    /**
     * 可选项目过滤条件，PROJECT 数据范围下必须落在授权项目集合内。
     */
    private String projectId;

    /**
     * 可选 actor 过滤条件，SELF 数据范围下会被强制收口到当前 actor。
     */
    private String actorId;

    /**
     * 可选 Agent Run 条件。
     *
     * <p>生产调用建议传入 runId，避免同一会话多轮执行时采信旧 run 的 worker receipt。</p>
     */
    private String runId;

    /**
     * 可选 Agent Session 条件，用于把回调限制在当前会话链路内。
     */
    private String sessionId;

    /**
     * 最多扫描多少条 receipt。
     *
     * <p>默认由服务层决定；这里限制最大 500，避免异常 command 重放导致控制面扫描过多记录。</p>
     */
    @Min(value = 1, message = "limit 不能小于 1")
    @Max(value = 500, message = "limit 不能大于 500")
    private Integer limit;

    /**
     * 是否只演练不投递。
     *
     * <p>默认 true 是刻意的安全设计。最终态回调会改变 task-management 任务状态，
     * 因此运维台或自动 worker 第一次接入时应先 dry-run，确认 reconciliation、taskId、taskRunId、
     * executorId 和幂等键都正确后，再显式传 false 执行真实投递。</p>
     */
    private Boolean dryRun = true;

    /**
     * 是否允许把 RUNNING 这类非终态建议映射为 progress 回调。
     *
     * <p>默认 false。原因是“最终态回调”最核心的价值是成功、失败和退避收口；
     * RUNNING 只是可见性刷新，如果配置不当可能产生额外进度噪声。需要会话恢复时刷新执行中状态的调用方，
     * 可以显式开启该开关。</p>
     */
    private Boolean includeNonTerminalProgressCallback = false;

    /**
     * DEFERRED 回调的退避秒数。
     *
     * <p>当 worker receipt 表示容量限制时，task-management 需要一个 delaySeconds 才能把任务放回延迟队列。
     * 默认 60 秒是保守值；生产环境后续可以按租户套餐、队列积压、下游 429 Retry-After 或 worker backlog 动态计算。</p>
     */
    @Min(value = 1, message = "deferDelaySeconds 不能小于 1")
    @Max(value = 3600, message = "deferDelaySeconds 不能大于 3600")
    private Integer deferDelaySeconds = 60;
}
