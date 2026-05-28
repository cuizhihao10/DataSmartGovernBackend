/**
 * @Author : Cui
 * @Date: 2026/05/05 23:40
 * @Description DataSmart Govern Backend - SyncGovernanceAlertServiceImpl.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.czh.datasmart.govern.datasource.controller.dto.SyncActionRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncAlertDispatchBatchResult;
import com.czh.datasmart.govern.datasource.controller.dto.SyncAlertOutboxHealthSnapshot;
import com.czh.datasmart.govern.datasource.controller.dto.SyncAlertOutboxLeaseRecoveryResult;
import com.czh.datasmart.govern.datasource.entity.SyncAlertDeliveryRecord;
import com.czh.datasmart.govern.datasource.entity.SyncGovernanceAlert;
import com.czh.datasmart.govern.datasource.mapper.SyncGovernanceAlertMapper;
import com.czh.datasmart.govern.datasource.service.SyncGovernanceAlertService;
import com.czh.datasmart.govern.datasource.service.support.SyncAlertDeliverySupport;
import com.czh.datasmart.govern.datasource.service.support.SyncAlertLifecycleSupport;
import com.czh.datasmart.govern.datasource.service.support.SyncAlertOutboxSupport;
import com.czh.datasmart.govern.datasource.service.support.SyncAlertPermissionSupport;
import com.czh.datasmart.govern.datasource.support.SyncPermissionAction;
import com.czh.datasmart.govern.datasource.support.SyncPermissionResource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 同步治理告警服务实现。
 *
 * <p>同步治理告警是 datasource-management 面向生产运营的风险对象：
 * 它既要表达“哪个租户、哪个同步任务、哪类风险正在发生”，也要承担 outbox 投递状态，
 * 让告警可以被 webhook、飞书、企业微信、内部日志或未来统一通知中心消费。</p>
 *
 * <p>本轮重构后，该类不再直接承载所有细节，而是回到应用服务门面：
 * 1. 对外实现 `SyncGovernanceAlertService` 接口；
 * 2. 保留 Spring 事务边界；
 * 3. 组织权限、生命周期、outbox 和投递组件协作；
 * 4. 不再直接拼装租约 SQL、webhook 请求、投递记录或状态流转细节。</p>
 *
 * <p>这种结构让告警域更接近真实商业产品的可演进形态：
 * 权限边界、告警状态、投递 outbox、通道链、健康巡检可以分别增强，
 * 后续接入 observability、统一通知中心、告警抑制、告警升级和值班策略时，
 * 不需要继续把所有逻辑塞进一个 Impl 文件。</p>
 */
@Service
@RequiredArgsConstructor
public class SyncGovernanceAlertServiceImpl extends ServiceImpl<SyncGovernanceAlertMapper, SyncGovernanceAlert>
        implements SyncGovernanceAlertService {

    /**
     * 告警生命周期组件，负责打开、刷新、确认、解决、死信重入队和列表查询。
     */
    private final SyncAlertLifecycleSupport syncAlertLifecycleSupport;

    /**
     * 告警权限组件，负责租户查询范围和单条告警动作权限。
     */
    private final SyncAlertPermissionSupport syncAlertPermissionSupport;

    /**
     * 告警 outbox 组件，负责批量认领、租约释放、租约恢复和健康快照。
     */
    private final SyncAlertOutboxSupport syncAlertOutboxSupport;

    /**
     * 告警投递组件，负责通道链、webhook、内部日志和投递记录。
     */
    private final SyncAlertDeliverySupport syncAlertDeliverySupport;

    /**
     * 打开或刷新治理告警。
     *
     * <p>该入口通常由同步任务、队列巡检、数据源健康检查或质量执行器调用。
     * 它不要求调用方自己处理去重和投递，避免各业务点重复实现告警风暴控制。</p>
     */
    @Override
    @Transactional
    public SyncGovernanceAlert openOrRefreshAlert(Long tenantId,
                                                  Long syncTaskId,
                                                  String alertType,
                                                  String severity,
                                                  String alertKey,
                                                  String summary,
                                                  String detail,
                                                  String sourceResource,
                                                  String triggeredByAction,
                                                  Long actorId,
                                                  String actorRole) {
        SyncGovernanceAlert alert = syncAlertLifecycleSupport.openOrRefreshAlert(
                tenantId, syncTaskId, alertType, severity, alertKey, summary, detail, sourceResource, triggeredByAction);
        if (syncAlertDeliverySupport.autoDeliverOnOpen()) {
            syncAlertDeliverySupport.dispatchInternal(alert, actorId, actorRole, false);
        }
        return syncAlertLifecycleSupport.getRequiredAlert(alert.getId());
    }

    /**
     * 分页查询治理告警。
     */
    @Override
    @Transactional(readOnly = true)
    public IPage<SyncGovernanceAlert> pageAlerts(Page<SyncGovernanceAlert> page,
                                                 Long actorId,
                                                 String actorRole,
                                                 Long actorTenantId,
                                                 Long tenantId,
                                                 String alertType,
                                                 String severity,
                                                 String alertStatus,
                                                 String deliveryStatus) {
        Long resolvedTenantId = syncAlertPermissionSupport.resolveTenantQueryScope(actorRole, actorTenantId, tenantId);
        syncAlertPermissionSupport.assertTenantAlertPermission(actorId, actorRole, actorTenantId,
                resolvedTenantId, SyncPermissionAction.VIEW_ALERT);
        return syncAlertLifecycleSupport.pageAlerts(page, resolvedTenantId, alertType, severity, alertStatus, deliveryStatus);
    }

    /**
     * 分页查询某条告警的投递记录。
     */
    @Override
    @Transactional(readOnly = true)
    public IPage<SyncAlertDeliveryRecord> pageDeliveryRecords(Long alertId,
                                                              Page<SyncAlertDeliveryRecord> page,
                                                              Long actorId,
                                                              String actorRole,
                                                              Long actorTenantId) {
        SyncGovernanceAlert alert = syncAlertLifecycleSupport.getRequiredAlert(alertId);
        syncAlertPermissionSupport.assertAlertPermission(alert, actorId, actorRole, actorTenantId,
                SyncPermissionResource.SYNC_ALERT_DELIVERY, SyncPermissionAction.VIEW_ALERT);
        return syncAlertDeliverySupport.pageDeliveryRecords(alertId, page);
    }

    /**
     * 确认告警。
     */
    @Override
    @Transactional
    public SyncGovernanceAlert acknowledgeAlert(Long id, SyncActionRequest request) {
        SyncGovernanceAlert alert = syncAlertLifecycleSupport.getRequiredAlert(id);
        syncAlertPermissionSupport.assertAlertPermission(alert, request,
                SyncPermissionResource.SYNC_ALERT, SyncPermissionAction.ACKNOWLEDGE_ALERT);
        return syncAlertLifecycleSupport.acknowledgeAlert(alert, request);
    }

    /**
     * 解决告警。
     */
    @Override
    @Transactional
    public SyncGovernanceAlert resolveAlert(Long id, SyncActionRequest request) {
        SyncGovernanceAlert alert = syncAlertLifecycleSupport.getRequiredAlert(id);
        syncAlertPermissionSupport.assertAlertPermission(alert, request,
                SyncPermissionResource.SYNC_ALERT, SyncPermissionAction.RESOLVE_ALERT);
        return syncAlertLifecycleSupport.resolveAlert(alert, request);
    }

    /**
     * 手动投递单条告警。
     */
    @Override
    @Transactional
    public SyncGovernanceAlert dispatchAlert(Long id, SyncActionRequest request) {
        SyncGovernanceAlert alert = syncAlertLifecycleSupport.getRequiredAlert(id);
        syncAlertPermissionSupport.assertAlertPermission(alert, request,
                SyncPermissionResource.SYNC_ALERT, SyncPermissionAction.DISPATCH_ALERT);
        return syncAlertOutboxSupport.dispatchSingleAlert(id, request);
    }

    /**
     * 手动批量补投可重试告警。
     */
    @Override
    @Transactional
    public SyncAlertDispatchBatchResult dispatchRetryableAlerts(SyncActionRequest request) {
        return claimAndDispatchRetryableAlerts(request,
                "MANUAL-BATCH-" + request.getActorId(),
                syncAlertDeliverySupport.resolveRetryDispatchBatchLimit(),
                120);
    }

    /**
     * 由调度器或运维接口批量认领并补投到期告警。
     */
    @Override
    @Transactional
    public SyncAlertDispatchBatchResult claimAndDispatchRetryableAlerts(SyncActionRequest request,
                                                                        String dispatcherInstanceId,
                                                                        Integer claimBatchSize,
                                                                        Integer leaseSeconds) {
        Long resolvedTenantId = syncAlertPermissionSupport.resolveTenantQueryScope(
                request.getActorRole(), request.getActorTenantId(), null);
        syncAlertPermissionSupport.assertTenantAlertPermission(request.getActorId(), request.getActorRole(),
                request.getActorTenantId(), resolvedTenantId, SyncPermissionAction.DISPATCH_ALERT);
        return syncAlertOutboxSupport.claimAndDispatchRetryableAlerts(
                request, dispatcherInstanceId, claimBatchSize, leaseSeconds, resolvedTenantId);
    }

    /**
     * 将死信告警重新放回待投递队列。
     */
    @Override
    @Transactional
    public SyncGovernanceAlert requeueDeadLetterAlert(Long id, SyncActionRequest request) {
        SyncGovernanceAlert alert = syncAlertLifecycleSupport.getRequiredAlert(id);
        syncAlertPermissionSupport.assertAlertPermission(alert, request,
                SyncPermissionResource.SYNC_ALERT, SyncPermissionAction.DISPATCH_ALERT);
        return syncAlertLifecycleSupport.requeueDeadLetterAlert(alert);
    }

    /**
     * 查看告警 outbox 健康快照。
     */
    @Override
    @Transactional(readOnly = true)
    public SyncAlertOutboxHealthSnapshot inspectOutboxHealth(Long actorId,
                                                             String actorRole,
                                                             Long actorTenantId,
                                                             Long tenantId) {
        Long resolvedTenantId = syncAlertPermissionSupport.resolveTenantQueryScope(actorRole, actorTenantId, tenantId);
        syncAlertPermissionSupport.assertTenantAlertPermission(actorId, actorRole, actorTenantId,
                resolvedTenantId, SyncPermissionAction.VIEW_ALERT);
        return syncAlertOutboxSupport.inspectOutboxHealth(resolvedTenantId);
    }

    /**
     * 恢复过期投递租约。
     */
    @Override
    @Transactional
    public SyncAlertOutboxLeaseRecoveryResult recoverExpiredDispatchLeases(SyncActionRequest request, Integer batchSize) {
        Long resolvedTenantId = syncAlertPermissionSupport.resolveTenantQueryScope(
                request.getActorRole(), request.getActorTenantId(), null);
        syncAlertPermissionSupport.assertTenantAlertPermission(request.getActorId(), request.getActorRole(),
                request.getActorTenantId(), resolvedTenantId, SyncPermissionAction.DISPATCH_ALERT);
        return syncAlertOutboxSupport.recoverExpiredDispatchLeases(resolvedTenantId, batchSize);
    }
}
