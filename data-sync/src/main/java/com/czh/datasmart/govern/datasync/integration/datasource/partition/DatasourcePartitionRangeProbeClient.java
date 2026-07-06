/**
 * @Author : Cui
 * @Date: 2026/07/07 23:34
 * @Description DataSmart Govern Backend - DatasourcePartitionRangeProbeClient.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.integration.datasource.partition;

import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;

/**
 * 分片范围探测客户端抽象。
 *
 * <p>该接口隔离 data-sync 控制面与 datasource-management 探测实现。后续如果 range probe 从 HTTP
 * 切换为 gRPC、Kafka command 或本地 sidecar，只需要替换实现，不影响分片合同解析和 fan-out 调度。</p>
 */
public interface DatasourcePartitionRangeProbeClient {

    /**
     * 探测源端 splitPk 的 min/max。
     *
     * @param request 探测请求。
     * @param actorContext 服务账号上下文。
     * @return 低敏探测结果。
     */
    DatasourcePartitionRangeProbeResponse probeRange(DatasourcePartitionRangeProbeRequest request,
                                                     SyncActorContext actorContext);
}
