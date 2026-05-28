/**
 * @Author : Cui
 * @Date: 2026/05/08 21:53
 * @Description DataSmart Govern Backend - DataSyncExecutorLeaseService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service;

import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExpiredLeaseRecoveryRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExpiredLeaseRecoveryResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionClaimRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionClaimResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionDeferRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionHeartbeatRequest;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;

/**
 * data-sync 执行器租约服务。
 *
 * <p>该服务专门面向 worker 协议，不承载用户侧模板/任务 CRUD。
 */
public interface DataSyncExecutorLeaseService {

    SyncExecutionClaimResult claimNext(SyncExecutionClaimRequest request, SyncActorContext actorContext);

    SyncExecution heartbeat(Long executionId, SyncExecutionHeartbeatRequest request, SyncActorContext actorContext);

    SyncExecution defer(Long executionId, SyncExecutionDeferRequest request, SyncActorContext actorContext);

    SyncExpiredLeaseRecoveryResult recoverExpiredLeases(SyncExpiredLeaseRecoveryRequest request, SyncActorContext actorContext);
}
