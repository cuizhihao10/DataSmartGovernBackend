/**
 * @Author : Cui
 * @Date: 2026/06/29 12:53
 * @Description DataSmart Govern Backend - DatasourceRunOnceClient.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.integration.datasource.runonce;

import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;

/**
 * datasource-management 受控单批执行入口客户端接口。
 *
 * <p>接口层只表达“data-sync 需要触发一次 connector runtime 单批 read/write”这个业务动作，
 * 不暴露 HTTP、URL、Header、超时、响应 envelope 或远端异常细节。这样做的价值是保持调度服务
 * {@code SyncBatchRunOnceDispatchService} 只关心同步执行状态机，而不是关心跨微服务通信技术。</p>
 *
 * <p>生产演进方向：当前实现是 HTTP RestClient；后续如果为了吞吐、链路治理或安全隔离改成 gRPC、
 * Kafka command、专用 runner SDK 或 service mesh 内部调用，只需要新增本接口实现，不需要重写
 * data-sync 的执行生命周期逻辑。</p>
 */
public interface DatasourceRunOnceClient {

    /**
     * 执行一次受控单批读写。
     *
     * @param request data-sync 根据 bridge plan 生成的内部执行请求。请求中可能包含对象定位、字段清单和 checkpoint 起点，
     *                只能发往 datasource-management internal 路由，不能写入普通日志、公开响应或运行时事件。
     * @param actorContext 当前服务调用上下文，主要用于透传 traceId、tenantId 和服务账号事实。
     * @return datasource-management 返回的低敏执行摘要，不包含行数据、SQL、连接凭据、字段值或 checkpoint 原始值。
     */
    DatasourceRunOnceResponse runOnce(DatasourceRunOnceRequest request, SyncActorContext actorContext);
}
