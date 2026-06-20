/**
 * @Author : Cui
 * @Date: 2026/06/20 16:51
 * @Description DataSmart Govern Backend - DataSyncWorkerOutboxClaimRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.datasync;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DataSync worker outbox 领取请求。
 *
 * <p>这个请求不是普通用户侧 API 的业务表单，而是内部调度器、补偿器或运维控制面发起的“领取待投递命令”请求。
 * 它解决的问题是：task-management 已经把跨服务命令可靠写入 outbox，但后续需要一个稳定的入口让 dispatcher
 * 按租户、项目和重试时间扫描 PENDING/DEFERRED 命令，并把它们标记为 DISPATCHING。</p>
 *
 * <p>商业化场景里，未来可能会有多个 dispatcher 实例并发领取。当前实现会通过“状态条件更新”避免同一条命令被两个实例
 * 同时领取成功；如果后续要支撑更高吞吐，可以把查询升级为数据库层面的 {@code FOR UPDATE SKIP LOCKED}
 * 或者带版本号的乐观锁批量 claim。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DataSyncWorkerOutboxClaimRequest {

    /**
     * 执行领取动作的 dispatcher/worker 标识。
     *
     * <p>该字段不会写入当前 outbox 表，因为表结构还没有 executor_owner 字段；它主要用于日志、响应和后续审计扩展。
     * 后续如果引入租约续期、worker 心跳和抢占恢复，应把 executorId 持久化到单独的 lease 字段中。</p>
     */
    private String executorId;

    /**
     * 可选租户过滤条件。
     *
     * <p>生产环境中推荐 dispatcher 按租户或租户分片领取，避免某个大租户的积压命令长期挤占其他租户的执行机会。</p>
     */
    private Long tenantId;

    /**
     * 可选项目过滤条件。
     *
     * <p>当项目级资源配额、并发上限或优先级不同的时候，dispatcher 可以按 projectId 分批领取，保证项目隔离。</p>
     */
    private Long projectId;

    /**
     * 本次最多领取多少条命令。
     *
     * <p>服务层会把该值限制在安全范围内，避免一次 claim 把大量命令全部标记为 DISPATCHING，导致 worker 宕机后恢复成本过高。</p>
     */
    private Integer limit;

    /**
     * 是否允许领取已经到达 nextRetryAt 的 DEFERRED 命令。
     *
     * <p>null 默认视为 true。调度器在高峰期可以临时只领取 PENDING，低峰期再处理 DEFERRED 重试队列。</p>
     */
    private Boolean includeDeferred;

    /**
     * 判断是否包含可重试队列。
     *
     * @return true 表示 PENDING 和到期 DEFERRED 都可以领取；false 表示只领取首次待投递命令。
     */
    public boolean includeDeferredCommands() {
        return includeDeferred == null || includeDeferred;
    }
}
