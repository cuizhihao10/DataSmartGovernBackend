/**
 * @Author : Cui
 * @Date: 2026/05/08 22:43
 * @Description DataSmart Govern Backend - DataSyncIncidentService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service;

import com.czh.datasmart.govern.common.api.PlatformPageResponse;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncIncidentOperationRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncIncidentOperationResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncIncidentQueryCriteria;
import com.czh.datasmart.govern.datasync.entity.SyncIncidentRecord;

/**
 * data-sync 事故记录服务。
 *
 * <p>该服务负责事故查询与生命周期流转，和人工介入任务处理服务分开。
 * 原因是一个任务可以关联多个事故，一个事故也可能跨越确认、分派、解决、关闭多个阶段；
 * 如果把事故逻辑继续堆在人工介入服务里，后续接入 SLA、通知和工单会很快失控。
 */
public interface DataSyncIncidentService {

    PlatformPageResponse<SyncIncidentRecord> pageIncidents(SyncIncidentQueryCriteria criteria,
                                                           SyncActorContext actorContext);

    SyncIncidentRecord getIncident(Long incidentId, SyncActorContext actorContext);

    SyncIncidentOperationResult acknowledge(Long incidentId,
                                            SyncIncidentOperationRequest request,
                                            SyncActorContext actorContext);

    SyncIncidentOperationResult assign(Long incidentId,
                                       SyncIncidentOperationRequest request,
                                       SyncActorContext actorContext);

    SyncIncidentOperationResult resolve(Long incidentId,
                                        SyncIncidentOperationRequest request,
                                        SyncActorContext actorContext);

    SyncIncidentOperationResult close(Long incidentId,
                                      SyncIncidentOperationRequest request,
                                      SyncActorContext actorContext);
}
