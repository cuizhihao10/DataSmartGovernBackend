/**
 * @Author : Cui
 * @Date: 2026/06/20 21:43
 * @Description DataSmart Govern Backend - DataSyncWorkerOutboxDispatchBatchRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.datasync;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DataSync worker outbox 批量投递请求。
 *
 * <p>它比 {@link DataSyncWorkerOutboxClaimRequest} 多一步：不仅把 PENDING/DEFERRED 命令领取为
 * DISPATCHING，还会立即调用 datasource-management 的内部执行入口并记录 receipt。
 * 因此这个请求只能用于内部控制面、后台 dispatcher 或受控运维工具，不能暴露给普通用户。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DataSyncWorkerOutboxDispatchBatchRequest {

    /**
     * 执行本轮投递的 dispatcher/worker 标识。
     */
    private String executorId;

    /**
     * 可选租户过滤。
     *
     * <p>后续做租户级队列分片、配额和公平调度时，可以按 tenantId 分批投递，避免大租户挤占全部执行机会。</p>
     */
    private Long tenantId;

    /**
     * 可选项目过滤。
     */
    private Long projectId;

    /**
     * 本轮最多投递多少条命令。
     */
    private Integer limit;

    /**
     * 是否包含已经到达 nextRetryAt 的 DEFERRED 命令。
     */
    private Boolean includeDeferred;

    /**
     * 判断本轮是否允许处理重试队列。
     *
     * @return true 表示 PENDING 与到期 DEFERRED 都可以领取；false 表示只处理首次投递命令。
     */
    public boolean includeDeferredCommands() {
        return includeDeferred == null || includeDeferred;
    }
}
