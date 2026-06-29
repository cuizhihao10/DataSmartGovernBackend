/**
 * @Author : Cui
 * @Date: 2026/06/29 13:18
 * @Description DataSmart Govern Backend - TaskManagementExecutionReceiptClient.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.integration.task.receipt;

import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;

/**
 * task-management execution receipt 客户端接口。
 *
 * <p>data-sync 通过该接口把低敏执行事实投递给 task-management。
 * 保留接口层的原因是后续可能从 HTTP 切换到 Kafka outbox、gRPC 或服务网格本地代理，
 * 派发服务不应该关心底层传输方式。</p>
 */
public interface TaskManagementExecutionReceiptClient {

    /**
     * 记录一条 DataSync worker execution receipt。
     *
     * @param request 低敏回执请求
     * @param actorContext 调用上下文，用于 Header 透传和链路追踪
     */
    void record(TaskManagementExecutionReceiptRequest request, SyncActorContext actorContext);
}
