/**
 * @Author : Cui
 * @Date: 2026/06/27 16:20
 * @Description DataSmart Govern Backend - DataSyncRecoveryPlanWorkerService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service;

import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncRecoveryPlanWorkerRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncRecoveryPlanWorkerResult;

/**
 * worker 恢复计划消费服务。
 *
 * <p>该服务只处理“已经认领 execution 的 worker 如何读取并消费 replay/backfill 计划”的机器协议。
 * 它不负责创建 replay/backfill 计划，创建动作仍由任务控制面和 {@code SyncTaskRecoveryOperationSupport} 完成；
 * 它也不执行真实数据读取写入，真实 connector worker 在拿到低敏契约后继续走 checkpoint/complete/fail 回调链路。
 */
public interface DataSyncRecoveryPlanWorkerService {

    /**
     * worker 认领恢复计划。
     *
     * <p>业务含义：worker 已经通过租约拿到 execution，现在声明自己要读取与该 execution 绑定的恢复计划。
     * 服务端会把计划从 CREATED 原子推进到 CLAIMED；如果已经 CLAIMED 或 CONSUMED，则返回幂等结果。
     */
    SyncRecoveryPlanWorkerResult claimPlan(Long executionId,
                                           SyncRecoveryPlanWorkerRequest request,
                                           SyncActorContext actorContext);

    /**
     * worker 消费恢复计划。
     *
     * <p>业务含义：worker 已经把恢复计划加载为本地执行策略，后续进入普通同步执行生命周期。
     * 服务端会把计划从 CLAIMED 原子推进到 CONSUMED；如果已经 CONSUMED，则返回幂等结果。
     */
    SyncRecoveryPlanWorkerResult consumePlan(Long executionId,
                                             SyncRecoveryPlanWorkerRequest request,
                                             SyncActorContext actorContext);
}
