/**
 * @Author : Cui
 * @Date: 2026/06/29 13:04
 * @Description DataSmart Govern Backend - DataSyncWorkerLoopService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service;

import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncWorkerLoopRunRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncWorkerLoopRunResult;

/**
 * data-sync worker loop 服务接口。
 *
 * <p>该接口是“同步执行闭环”的稳定入口：无论调用方是 HTTP 运维接口、后台定时器、未来独立 worker 进程，
 * 还是 Agent 发起的内部诊断动作，都应该通过这里触发一轮 claim -> dispatch -> complete/fail。</p>
 */
public interface DataSyncWorkerLoopService {

    /**
     * 执行一轮 worker loop。
     *
     * @param request 本轮运行参数，可覆盖 executorId、tenantId、租约秒数和最大处理条数
     * @param actorContext 当前调用主体上下文，用于审计和下游服务账号上下文兜底
     * @return 本轮低敏执行摘要，不包含 SQL、字段值、样本、checkpoint 原始值或连接凭据
     */
    SyncWorkerLoopRunResult runOnce(SyncWorkerLoopRunRequest request, SyncActorContext actorContext);
}
