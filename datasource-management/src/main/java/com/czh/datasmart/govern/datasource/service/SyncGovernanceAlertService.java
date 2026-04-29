package com.czh.datasmart.govern.datasource.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.czh.datasmart.govern.datasource.controller.dto.SyncAlertDispatchBatchResult;
import com.czh.datasmart.govern.datasource.controller.dto.SyncAlertOutboxHealthSnapshot;
import com.czh.datasmart.govern.datasource.controller.dto.SyncAlertOutboxLeaseRecoveryResult;
import com.czh.datasmart.govern.datasource.controller.dto.SyncActionRequest;
import com.czh.datasmart.govern.datasource.entity.SyncAlertDeliveryRecord;
import com.czh.datasmart.govern.datasource.entity.SyncGovernanceAlert;

/**
 * @Author : Cui
 * @Date: 2026/4/20 09:18
 * @Description DataSmart Govern Backend - SyncGovernanceAlertService.java
 * @Version:1.0.0
 *
 * 同步治理告警服务接口。
 * 这个服务把“告警是一条可治理对象”这件事显式建模出来，而不是只把风险打印进日志。
 */
public interface SyncGovernanceAlertService extends IService<SyncGovernanceAlert> {

    SyncGovernanceAlert openOrRefreshAlert(Long tenantId,
                                           Long syncTaskId,
                                           String alertType,
                                           String severity,
                                           String alertKey,
                                           String summary,
                                           String detail,
                                           String sourceResource,
                                           String triggeredByAction,
                                           Long actorId,
                                           String actorRole);

    IPage<SyncGovernanceAlert> pageAlerts(Page<SyncGovernanceAlert> page,
                                          Long actorId,
                                          String actorRole,
                                          Long actorTenantId,
                                          Long tenantId,
                                          String alertType,
                                          String severity,
                                          String alertStatus,
                                          String deliveryStatus);

    SyncGovernanceAlert acknowledgeAlert(Long id, SyncActionRequest request);

    SyncGovernanceAlert resolveAlert(Long id, SyncActionRequest request);

    SyncGovernanceAlert dispatchAlert(Long id, SyncActionRequest request);

    SyncAlertDispatchBatchResult dispatchRetryableAlerts(SyncActionRequest request);

    SyncAlertDispatchBatchResult claimAndDispatchRetryableAlerts(SyncActionRequest request,
                                                                 String dispatcherInstanceId,
                                                                 Integer claimBatchSize,
                                                                 Integer leaseSeconds);

    SyncGovernanceAlert requeueDeadLetterAlert(Long id, SyncActionRequest request);

    /**
     * 查看治理告警 outbox 的运行健康快照。
     *
     * @param actorId 当前查看人 ID。
     * @param actorRole 当前查看人角色。
     * @param actorTenantId 当前查看人所属租户。
     * @param tenantId 希望查看的目标租户；平台管理员可为空表示平台聚合视图。
     * @return outbox 积压、失败、死信和租约状态快照。
     */
    SyncAlertOutboxHealthSnapshot inspectOutboxHealth(Long actorId,
                                                      String actorRole,
                                                      Long actorTenantId,
                                                      Long tenantId);

    /**
     * 释放已经过期的 outbox 投递租约。
     *
     * @param request 当前操作人或系统调度身份。
     * @param batchSize 单次最多恢复多少条，避免一次操作扫描过大。
     * @return 本轮恢复结果。
     */
    SyncAlertOutboxLeaseRecoveryResult recoverExpiredDispatchLeases(SyncActionRequest request, Integer batchSize);

    IPage<SyncAlertDeliveryRecord> pageDeliveryRecords(Long alertId,
                                                       Page<SyncAlertDeliveryRecord> page,
                                                       Long actorId,
                                                       String actorRole,
                                                       Long actorTenantId);
}
