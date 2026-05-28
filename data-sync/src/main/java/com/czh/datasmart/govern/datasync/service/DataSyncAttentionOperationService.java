/**
 * @Author : Cui
 * @Date: 2026/05/08 22:27
 * @Description DataSmart Govern Backend - DataSyncAttentionOperationService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service;

import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncAttentionOperationRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncAttentionOperationResult;

/**
 * data-sync 人工介入任务运营服务。
 *
 * <p>该服务专门处理 `AWAITING_OPERATOR_ACTION` 状态的同步任务。
 * 这样可以避免把运营处理逻辑塞进 `DataSyncServiceImpl`，也让后续接入告警、工单、审批和权限中心时有独立扩展点。
 */
public interface DataSyncAttentionOperationService {

    SyncAttentionOperationResult acknowledge(Long taskId,
                                             SyncAttentionOperationRequest request,
                                             SyncActorContext actorContext);

    SyncAttentionOperationResult resolve(Long taskId,
                                         SyncAttentionOperationRequest request,
                                         SyncActorContext actorContext);

    SyncAttentionOperationResult rerun(Long taskId,
                                       SyncAttentionOperationRequest request,
                                       SyncActorContext actorContext);

    SyncAttentionOperationResult cancel(Long taskId,
                                        SyncAttentionOperationRequest request,
                                        SyncActorContext actorContext);

    SyncAttentionOperationResult archive(Long taskId,
                                         SyncAttentionOperationRequest request,
                                         SyncActorContext actorContext);

    SyncAttentionOperationResult createIncident(Long taskId,
                                                SyncAttentionOperationRequest request,
                                                SyncActorContext actorContext);
}
