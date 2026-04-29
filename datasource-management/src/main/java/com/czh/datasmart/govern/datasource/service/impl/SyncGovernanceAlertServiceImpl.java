package com.czh.datasmart.govern.datasource.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.czh.datasmart.govern.datasource.config.SyncAlertProperties;
import com.czh.datasmart.govern.datasource.controller.dto.SyncAlertDispatchBatchResult;
import com.czh.datasmart.govern.datasource.controller.dto.SyncActionRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncAlertOutboxHealthSnapshot;
import com.czh.datasmart.govern.datasource.controller.dto.SyncAlertOutboxLeaseRecoveryResult;
import com.czh.datasmart.govern.datasource.entity.SyncAlertDeliveryRecord;
import com.czh.datasmart.govern.datasource.entity.SyncGovernanceAlert;
import com.czh.datasmart.govern.datasource.mapper.SyncAlertDeliveryRecordMapper;
import com.czh.datasmart.govern.datasource.mapper.SyncGovernanceAlertMapper;
import com.czh.datasmart.govern.datasource.service.SyncGovernanceAlertService;
import com.czh.datasmart.govern.datasource.support.ActorRole;
import com.czh.datasmart.govern.datasource.support.SyncAlertChannel;
import com.czh.datasmart.govern.datasource.support.SyncAlertDeliveryStatus;
import com.czh.datasmart.govern.datasource.support.SyncAlertSeverity;
import com.czh.datasmart.govern.datasource.support.SyncAlertStatus;
import com.czh.datasmart.govern.datasource.support.SyncPermissionAction;
import com.czh.datasmart.govern.datasource.support.SyncPermissionContext;
import com.czh.datasmart.govern.datasource.support.SyncPermissionEvaluator;
import com.czh.datasmart.govern.datasource.support.SyncPermissionResource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * @Author : Cui
 * @Date: 2026/4/20 22:23
 * @Description DataSmart Govern Backend - SyncGovernanceAlertServiceImpl.java
 * @Version:1.0.0
 *
 * 鍚屾娌荤悊鍛婅鏈嶅姟瀹炵幇銆? * 杩欏眰璐熻矗鎶娾€滄不鐞嗛闄┾€濅粠涓€鏉＄灛鏃舵棩蹇楋紝鍗囩骇鎴愪竴涓彲鎸佺画绠＄悊鐨勫憡璀﹀璞°€? *
 * 褰撳墠鐗堟湰閲嶇偣瑙ｅ喅浜旂被鐜板疄闂锛? * 1. 鍚岀被椋庨櫓閲嶅瑙﹀彂鏃讹紝濡備綍鎶樺彔鑰屼笉鏄埛灞忥紱
 * 2. 鍛婅鐢熸垚鍚庯紝濡備綍灏濊瘯澶栭€侊紱
 * 3. 澶栭€佸け璐ュ悗锛屽浣曞畨鎺掍笅娆￠噸璇曡€屼笉鏄潤榛樹涪澶憋紱
 * 4. 澶氭澶辫触鍚庯紝濡備綍杩涘叆姝讳俊鑰屼笉鏄棤闄愮洸鐩噸璇曪紱
 * 5. 澶栭儴 webhook 鏆備笉鍙敤鏃讹紝鏄惁瀛樺湪鍐呴儴鍏滃簳閫氶亾銆? *
 * 杩欓噷閲囩敤鐨勬槸鈥滄不鐞嗗璞¤惤搴撲紭鍏堬紝鎶曢€掑紓姝ヨ涔夊悗琛モ€濈殑鎬濊矾锛? * - 鍏堜繚璇侀闄╀笉浼氫涪锛? * - 鍐嶉€愭琛ヨ冻鍙戦€併€佽ˉ鎶曘€佹淇″拰澶栭儴鍛婅骞冲彴瀵规帴鑳藉姏銆? */
@Slf4j
@Service
@RequiredArgsConstructor
public class SyncGovernanceAlertServiceImpl extends ServiceImpl<SyncGovernanceAlertMapper, SyncGovernanceAlert>
        implements SyncGovernanceAlertService {

    private final SyncAlertProperties syncAlertProperties;
    private final SyncPermissionEvaluator syncPermissionEvaluator;
    private final SyncAlertDeliveryRecordMapper syncAlertDeliveryRecordMapper;

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
        LocalDateTime now = LocalDateTime.now();
        SyncGovernanceAlert alert = findDeduplicatedAlert(alertKey, now);

        if (alert == null) {
            alert = new SyncGovernanceAlert();
            alert.setTenantId(tenantId);
            alert.setSyncTaskId(syncTaskId);
            alert.setAlertType(alertType);
            alert.setSeverity(severity);
            alert.setAlertStatus(SyncAlertStatus.OPEN.name());
            alert.setDeliveryChannel(resolveDefaultChannel().name());
            alert.setDeliveryStatus(resolveInitialDeliveryStatus().name());
            alert.setAlertKey(truncate(alertKey, 128));
            alert.setSummary(truncate(summary, 512));
            alert.setDetail(truncate(detail, 4000));
            alert.setSourceResource(sourceResource);
            alert.setTriggeredByAction(triggeredByAction);
            alert.setFirstOccurredAt(now);
            alert.setLastOccurredAt(now);
            alert.setOccurrenceCount(1);
            alert.setDeliveryAttemptCount(0);
            alert.setNextDeliveryAttemptAt(Boolean.TRUE.equals(syncAlertProperties.getAutoDeliverOnOpen()) ? now : null);
            save(alert);
        } else {
            alert.setTenantId(tenantId == null ? alert.getTenantId() : tenantId);
            alert.setSyncTaskId(syncTaskId == null ? alert.getSyncTaskId() : syncTaskId);
            alert.setSeverity(escalateSeverity(alert.getSeverity(), severity));
            alert.setSummary(truncate(summary, 512));
            alert.setDetail(truncate(detail, 4000));
            alert.setSourceResource(sourceResource);
            alert.setTriggeredByAction(triggeredByAction);
            alert.setLastOccurredAt(now);
            alert.setOccurrenceCount((alert.getOccurrenceCount() == null ? 0 : alert.getOccurrenceCount()) + 1);
            if (SyncAlertStatus.RESOLVED.name().equals(alert.getAlertStatus())) {
                alert.setAlertStatus(SyncAlertStatus.OPEN.name());
                alert.setResolvedBy(null);
                alert.setResolvedAt(null);
            }
            if (SyncAlertDeliveryStatus.DEAD_LETTER.name().equals(alert.getDeliveryStatus())) {
                alert.setDeliveryStatus(SyncAlertDeliveryStatus.PENDING.name());
                alert.setDeadLetteredAt(null);
                alert.setDeadLetterReason(null);
            }
            alert.setNextDeliveryAttemptAt(Boolean.TRUE.equals(syncAlertProperties.getAutoDeliverOnOpen()) ? now : alert.getNextDeliveryAttemptAt());
            updateById(alert);
        }

        if (Boolean.TRUE.equals(syncAlertProperties.getAutoDeliverOnOpen())) {
            dispatchInternal(alert, actorId, actorRole, false);
        }
        return getRequiredAlert(alert.getId());
    }

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
        Long resolvedTenantId = resolveTenantQueryScope(actorRole, actorTenantId, tenantId);
        syncPermissionEvaluator.assertAllowed(SyncPermissionContext.builder()
                        .actorId(actorId)
                        .actorRole(actorRole)
                        .actorTenantId(actorTenantId)
                        .resourceTenantId(resolvedTenantId)
                        .build(),
                SyncPermissionResource.SYNC_ALERT, SyncPermissionAction.VIEW_ALERT);

        LambdaQueryWrapper<SyncGovernanceAlert> wrapper = new LambdaQueryWrapper<SyncGovernanceAlert>()
                .eq(resolvedTenantId != null, SyncGovernanceAlert::getTenantId, resolvedTenantId)
                .eq(alertType != null && !alertType.isBlank(), SyncGovernanceAlert::getAlertType, alertType.toUpperCase())
                .eq(severity != null && !severity.isBlank(), SyncGovernanceAlert::getSeverity, severity.toUpperCase())
                .eq(alertStatus != null && !alertStatus.isBlank(), SyncGovernanceAlert::getAlertStatus, alertStatus.toUpperCase())
                .eq(deliveryStatus != null && !deliveryStatus.isBlank(), SyncGovernanceAlert::getDeliveryStatus, deliveryStatus.toUpperCase())
                .orderByDesc(SyncGovernanceAlert::getLastOccurredAt)
                .orderByDesc(SyncGovernanceAlert::getCreateTime);
        return page(page, wrapper);
    }

    @Override
    @Transactional(readOnly = true)
    public IPage<SyncAlertDeliveryRecord> pageDeliveryRecords(Long alertId,
                                                              Page<SyncAlertDeliveryRecord> page,
                                                              Long actorId,
                                                              String actorRole,
                                                              Long actorTenantId) {
        SyncGovernanceAlert alert = getRequiredAlert(alertId);
        syncPermissionEvaluator.assertAllowed(buildAlertPermissionContext(alert, actorId, actorRole, actorTenantId),
                SyncPermissionResource.SYNC_ALERT_DELIVERY, SyncPermissionAction.VIEW_ALERT);

        LambdaQueryWrapper<SyncAlertDeliveryRecord> wrapper = new LambdaQueryWrapper<SyncAlertDeliveryRecord>()
                .eq(SyncAlertDeliveryRecord::getAlertId, alertId)
                .orderByDesc(SyncAlertDeliveryRecord::getCreateTime)
                .orderByDesc(SyncAlertDeliveryRecord::getAttemptNo);
        return syncAlertDeliveryRecordMapper.selectPage(page, wrapper);
    }

    @Override
    @Transactional
    public SyncGovernanceAlert acknowledgeAlert(Long id, SyncActionRequest request) {
        SyncGovernanceAlert alert = getRequiredAlert(id);
        syncPermissionEvaluator.assertAllowed(buildAlertPermissionContext(alert, request),
                SyncPermissionResource.SYNC_ALERT, SyncPermissionAction.ACKNOWLEDGE_ALERT);

        if (!SyncAlertStatus.RESOLVED.name().equals(alert.getAlertStatus())) {
            alert.setAlertStatus(SyncAlertStatus.ACKNOWLEDGED.name());
        }
        alert.setAcknowledgedBy(request.getActorId());
        alert.setAcknowledgedAt(LocalDateTime.now());
        updateById(alert);
        return alert;
    }

    @Override
    @Transactional
    public SyncGovernanceAlert resolveAlert(Long id, SyncActionRequest request) {
        SyncGovernanceAlert alert = getRequiredAlert(id);
        syncPermissionEvaluator.assertAllowed(buildAlertPermissionContext(alert, request),
                SyncPermissionResource.SYNC_ALERT, SyncPermissionAction.RESOLVE_ALERT);

        alert.setAlertStatus(SyncAlertStatus.RESOLVED.name());
        alert.setResolvedBy(request.getActorId());
        alert.setResolvedAt(LocalDateTime.now());
        updateById(alert);
        return alert;
    }

    @Override
    @Transactional
    public SyncGovernanceAlert dispatchAlert(Long id, SyncActionRequest request) {
        SyncGovernanceAlert alert = getRequiredAlert(id);
        syncPermissionEvaluator.assertAllowed(buildAlertPermissionContext(alert, request),
                SyncPermissionResource.SYNC_ALERT, SyncPermissionAction.DISPATCH_ALERT);
        String dispatchOwner = "MANUAL-" + request.getActorId();
        LocalDateTime leaseExpireAt = LocalDateTime.now().plusSeconds(120);
        if (!tryClaimAlert(alert.getId(), dispatchOwner, leaseExpireAt, false, LocalDateTime.now())) {
            throw new IllegalStateException("当前告警正在被其他调度实例处理，请稍后重试");
        }
        try {
            dispatchInternal(getRequiredAlert(id), request.getActorId(), request.getActorRole(), true);
        } finally {
            releaseDispatchLease(id, dispatchOwner);
        }
        return getRequiredAlert(id);
    }

    @Override
    @Transactional
    public SyncAlertDispatchBatchResult dispatchRetryableAlerts(SyncActionRequest request) {
        return claimAndDispatchRetryableAlerts(request,
                "MANUAL-BATCH-" + request.getActorId(),
                resolveRetryDispatchBatchLimit(),
                120);
    }

    @Override
    @Transactional
    public SyncAlertDispatchBatchResult claimAndDispatchRetryableAlerts(SyncActionRequest request,
                                                                        String dispatcherInstanceId,
                                                                        Integer claimBatchSize,
                                                                        Integer leaseSeconds) {
        Long resolvedTenantId = resolveTenantQueryScope(request.getActorRole(), request.getActorTenantId(), null);
        syncPermissionEvaluator.assertAllowed(SyncPermissionContext.builder()
                        .actorId(request.getActorId())
                        .actorRole(request.getActorRole())
                        .actorTenantId(request.getActorTenantId())
                        .resourceTenantId(resolvedTenantId)
                        .build(),
                SyncPermissionResource.SYNC_ALERT, SyncPermissionAction.DISPATCH_ALERT);

        LocalDateTime now = LocalDateTime.now();
        int resolvedClaimBatchSize = claimBatchSize == null ? resolveRetryDispatchBatchLimit() : Math.max(1, claimBatchSize);
        int resolvedLeaseSeconds = leaseSeconds == null ? 120 : Math.max(30, leaseSeconds);
        List<SyncGovernanceAlert> alerts = listDueDispatchCandidates(resolvedTenantId, now, resolvedClaimBatchSize);

        SyncAlertDispatchBatchResult result = new SyncAlertDispatchBatchResult();
        result.setCandidateCount(alerts.size());
        result.setClaimedCount(0);
        result.setAttemptedCount(0);
        result.setSentCount(0);
        result.setFailedCount(0);
        result.setDeadLetterCount(0);
        result.setProcessedAlertIds(new ArrayList<>());

        List<SyncGovernanceAlert> claimedAlerts = new ArrayList<>();
        LocalDateTime leaseExpireAt = now.plusSeconds(resolvedLeaseSeconds);
        for (SyncGovernanceAlert alert : alerts) {
            if (tryClaimAlert(alert.getId(), dispatcherInstanceId, leaseExpireAt, true, now)) {
                claimedAlerts.add(getRequiredAlert(alert.getId()));
            }
        }
        result.setClaimedCount(claimedAlerts.size());

        for (SyncGovernanceAlert alert : claimedAlerts) {
            result.setAttemptedCount(result.getAttemptedCount() + 1);
            try {
                dispatchInternal(alert, request.getActorId(), request.getActorRole(), true);
                SyncGovernanceAlert latest = getRequiredAlert(alert.getId());
                result.getProcessedAlertIds().add(latest.getId());
                if (SyncAlertDeliveryStatus.SENT.name().equals(latest.getDeliveryStatus())) {
                    result.setSentCount(result.getSentCount() + 1);
                } else if (SyncAlertDeliveryStatus.DEAD_LETTER.name().equals(latest.getDeliveryStatus())) {
                    result.setDeadLetterCount(result.getDeadLetterCount() + 1);
                } else if (SyncAlertDeliveryStatus.FAILED.name().equals(latest.getDeliveryStatus())) {
                    result.setFailedCount(result.getFailedCount() + 1);
                }
            } finally {
                releaseDispatchLease(alert.getId(), dispatcherInstanceId);
            }
        }
        return result;
    }

    @Override
    @Transactional
    public SyncGovernanceAlert requeueDeadLetterAlert(Long id, SyncActionRequest request) {
        SyncGovernanceAlert alert = getRequiredAlert(id);
        syncPermissionEvaluator.assertAllowed(buildAlertPermissionContext(alert, request),
                SyncPermissionResource.SYNC_ALERT, SyncPermissionAction.DISPATCH_ALERT);

        alert.setDeliveryStatus(SyncAlertDeliveryStatus.PENDING.name());
        alert.setNextDeliveryAttemptAt(LocalDateTime.now());
        alert.setDeadLetteredAt(null);
        alert.setDeadLetterReason(null);
        alert.setDispatchLeaseOwner(null);
        alert.setDispatchLeaseExpireAt(null);
        updateById(alert);
        return alert;
    }

    @Override
    @Transactional(readOnly = true)
    public SyncAlertOutboxHealthSnapshot inspectOutboxHealth(Long actorId,
                                                             String actorRole,
                                                             Long actorTenantId,
                                                             Long tenantId) {
        Long resolvedTenantId = resolveTenantQueryScope(actorRole, actorTenantId, tenantId);
        syncPermissionEvaluator.assertAllowed(SyncPermissionContext.builder()
                        .actorId(actorId)
                        .actorRole(actorRole)
                        .actorTenantId(actorTenantId)
                        .resourceTenantId(resolvedTenantId)
                        .build(),
                SyncPermissionResource.SYNC_ALERT, SyncPermissionAction.VIEW_ALERT);

        LocalDateTime now = LocalDateTime.now();
        SyncAlertOutboxHealthSnapshot snapshot = new SyncAlertOutboxHealthSnapshot();
        snapshot.setTenantId(resolvedTenantId);
        snapshot.setPendingCount(countByDeliveryStatus(resolvedTenantId, SyncAlertDeliveryStatus.PENDING));
        snapshot.setFailedCount(countByDeliveryStatus(resolvedTenantId, SyncAlertDeliveryStatus.FAILED));
        snapshot.setDeadLetterCount(countByDeliveryStatus(resolvedTenantId, SyncAlertDeliveryStatus.DEAD_LETTER));
        snapshot.setLeasedCount(count(new LambdaQueryWrapper<SyncGovernanceAlert>()
                .eq(resolvedTenantId != null, SyncGovernanceAlert::getTenantId, resolvedTenantId)
                .isNotNull(SyncGovernanceAlert::getDispatchLeaseOwner)
                .gt(SyncGovernanceAlert::getDispatchLeaseExpireAt, now)));
        snapshot.setExpiredLeaseCount(count(new LambdaQueryWrapper<SyncGovernanceAlert>()
                .eq(resolvedTenantId != null, SyncGovernanceAlert::getTenantId, resolvedTenantId)
                .isNotNull(SyncGovernanceAlert::getDispatchLeaseOwner)
                .le(SyncGovernanceAlert::getDispatchLeaseExpireAt, now)));
        snapshot.setDueRetryableCount(countDueRetryableAlerts(resolvedTenantId, now));
        snapshot.setOldestDueRetryAt(findOldestDueRetryAt(resolvedTenantId, now));
        snapshot.setGeneratedAt(now);
        return snapshot;
    }

    @Override
    @Transactional
    public SyncAlertOutboxLeaseRecoveryResult recoverExpiredDispatchLeases(SyncActionRequest request, Integer batchSize) {
        Long resolvedTenantId = resolveTenantQueryScope(request.getActorRole(), request.getActorTenantId(), null);
        syncPermissionEvaluator.assertAllowed(SyncPermissionContext.builder()
                        .actorId(request.getActorId())
                        .actorRole(request.getActorRole())
                        .actorTenantId(request.getActorTenantId())
                        .resourceTenantId(resolvedTenantId)
                        .build(),
                SyncPermissionResource.SYNC_ALERT, SyncPermissionAction.DISPATCH_ALERT);

        LocalDateTime now = LocalDateTime.now();
        int safeBatchSize = batchSize == null ? 100 : Math.max(1, batchSize);
        List<SyncGovernanceAlert> expiredLeases = list(new LambdaQueryWrapper<SyncGovernanceAlert>()
                .eq(resolvedTenantId != null, SyncGovernanceAlert::getTenantId, resolvedTenantId)
                .in(SyncGovernanceAlert::getDeliveryStatus,
                        List.of(SyncAlertDeliveryStatus.PENDING.name(), SyncAlertDeliveryStatus.FAILED.name()))
                .isNotNull(SyncGovernanceAlert::getDispatchLeaseOwner)
                .le(SyncGovernanceAlert::getDispatchLeaseExpireAt, now)
                .orderByAsc(SyncGovernanceAlert::getDispatchLeaseExpireAt)
                .last("LIMIT " + safeBatchSize));

        SyncAlertOutboxLeaseRecoveryResult result = new SyncAlertOutboxLeaseRecoveryResult();
        result.setTenantId(resolvedTenantId);
        result.setCandidateCount(expiredLeases.size());
        result.setRecoveredCount(0);
        result.setRecoveredAlertIds(new ArrayList<>());
        result.setRecoveredAt(now);

        for (SyncGovernanceAlert alert : expiredLeases) {
            /*
             * 恢复租约时仍然带上 owner 和过期时间条件。
             * 这样即使另一个实例刚好在我们扫描后重新认领了这条 outbox，本次恢复也不会误清理掉它的新租约。
             */
            boolean recovered = update(new LambdaUpdateWrapper<SyncGovernanceAlert>()
                    .eq(SyncGovernanceAlert::getId, alert.getId())
                    .eq(SyncGovernanceAlert::getDispatchLeaseOwner, alert.getDispatchLeaseOwner())
                    .eq(SyncGovernanceAlert::getDispatchLeaseExpireAt, alert.getDispatchLeaseExpireAt())
                    .set(SyncGovernanceAlert::getDispatchLeaseOwner, null)
                    .set(SyncGovernanceAlert::getDispatchLeaseExpireAt, null));
            if (recovered) {
                result.setRecoveredCount(result.getRecoveredCount() + 1);
                result.getRecoveredAlertIds().add(alert.getId());
            }
        }
        return result;
    }

    private List<SyncGovernanceAlert> listDueDispatchCandidates(Long resolvedTenantId, LocalDateTime now, int limit) {
        return list(new LambdaQueryWrapper<SyncGovernanceAlert>()
                .in(SyncGovernanceAlert::getDeliveryStatus,
                        List.of(SyncAlertDeliveryStatus.PENDING.name(), SyncAlertDeliveryStatus.FAILED.name()))
                .eq(resolvedTenantId != null, SyncGovernanceAlert::getTenantId, resolvedTenantId)
                .le(SyncGovernanceAlert::getNextDeliveryAttemptAt, now)
                .and(condition -> condition
                        .isNull(SyncGovernanceAlert::getDispatchLeaseExpireAt)
                        .or()
                        .lt(SyncGovernanceAlert::getDispatchLeaseExpireAt, now))
                .orderByAsc(SyncGovernanceAlert::getNextDeliveryAttemptAt)
                .last("LIMIT " + limit));
    }

    /**
     * 按投递状态统计 outbox 数量。
     * 这里抽出公共方法，是为了让“健康快照”代码更像产品指标定义，而不是散落一堆重复查询条件。
     */
    private long countByDeliveryStatus(Long resolvedTenantId, SyncAlertDeliveryStatus deliveryStatus) {
        return count(new LambdaQueryWrapper<SyncGovernanceAlert>()
                .eq(resolvedTenantId != null, SyncGovernanceAlert::getTenantId, resolvedTenantId)
                .eq(SyncGovernanceAlert::getDeliveryStatus, deliveryStatus.name()));
    }

    /**
     * 统计当前已经到期、且没有被有效租约占用的可补投告警数量。
     * 这个指标比单纯 pending/failed 更接近“调度器下一轮能处理多少活儿”。
     */
    private long countDueRetryableAlerts(Long resolvedTenantId, LocalDateTime now) {
        return count(new LambdaQueryWrapper<SyncGovernanceAlert>()
                .eq(resolvedTenantId != null, SyncGovernanceAlert::getTenantId, resolvedTenantId)
                .in(SyncGovernanceAlert::getDeliveryStatus,
                        List.of(SyncAlertDeliveryStatus.PENDING.name(), SyncAlertDeliveryStatus.FAILED.name()))
                .le(SyncGovernanceAlert::getNextDeliveryAttemptAt, now)
                .and(condition -> condition
                        .isNull(SyncGovernanceAlert::getDispatchLeaseExpireAt)
                        .or()
                        .lt(SyncGovernanceAlert::getDispatchLeaseExpireAt, now)));
    }

    /**
     * 查询最早一条到期 outbox 的时间。
     * 如果这个时间距离现在越来越久，说明外部通道或调度器可能已经出现积压，需要运维介入。
     */
    private LocalDateTime findOldestDueRetryAt(Long resolvedTenantId, LocalDateTime now) {
        List<SyncGovernanceAlert> oldest = list(new LambdaQueryWrapper<SyncGovernanceAlert>()
                .eq(resolvedTenantId != null, SyncGovernanceAlert::getTenantId, resolvedTenantId)
                .in(SyncGovernanceAlert::getDeliveryStatus,
                        List.of(SyncAlertDeliveryStatus.PENDING.name(), SyncAlertDeliveryStatus.FAILED.name()))
                .le(SyncGovernanceAlert::getNextDeliveryAttemptAt, now)
                .and(condition -> condition
                        .isNull(SyncGovernanceAlert::getDispatchLeaseExpireAt)
                        .or()
                        .lt(SyncGovernanceAlert::getDispatchLeaseExpireAt, now))
                .orderByAsc(SyncGovernanceAlert::getNextDeliveryAttemptAt)
                .last("LIMIT 1"));
        if (oldest.isEmpty()) {
            return null;
        }
        return oldest.get(0).getNextDeliveryAttemptAt();
    }

    private boolean tryClaimAlert(Long alertId,
                                  String dispatchOwner,
                                  LocalDateTime leaseExpireAt,
                                  boolean dueOnly,
                                  LocalDateTime now) {
        LambdaUpdateWrapper<SyncGovernanceAlert> updateWrapper = new LambdaUpdateWrapper<SyncGovernanceAlert>()
                .eq(SyncGovernanceAlert::getId, alertId)
                .and(condition -> condition
                        .isNull(SyncGovernanceAlert::getDispatchLeaseExpireAt)
                        .or()
                        .lt(SyncGovernanceAlert::getDispatchLeaseExpireAt, now))
                .set(SyncGovernanceAlert::getDispatchLeaseOwner, dispatchOwner)
                .set(SyncGovernanceAlert::getDispatchLeaseExpireAt, leaseExpireAt);
        if (dueOnly) {
            updateWrapper.in(SyncGovernanceAlert::getDeliveryStatus,
                            List.of(SyncAlertDeliveryStatus.PENDING.name(), SyncAlertDeliveryStatus.FAILED.name()))
                    .le(SyncGovernanceAlert::getNextDeliveryAttemptAt, now);
        }
        return update(updateWrapper);
    }

    private void releaseDispatchLease(Long alertId, String dispatchOwner) {
        update(new LambdaUpdateWrapper<SyncGovernanceAlert>()
                .eq(SyncGovernanceAlert::getId, alertId)
                .eq(SyncGovernanceAlert::getDispatchLeaseOwner, dispatchOwner)
                .set(SyncGovernanceAlert::getDispatchLeaseOwner, null)
                .set(SyncGovernanceAlert::getDispatchLeaseExpireAt, null));
    }

    private SyncGovernanceAlert findDeduplicatedAlert(String alertKey, LocalDateTime now) {
        int dedupWindowSeconds = syncAlertProperties.getDedupWindowSeconds() == null
                ? 600
                : Math.max(60, syncAlertProperties.getDedupWindowSeconds());
        return getOne(new LambdaQueryWrapper<SyncGovernanceAlert>()
                .eq(SyncGovernanceAlert::getAlertKey, alertKey)
                .ne(SyncGovernanceAlert::getAlertStatus, SyncAlertStatus.RESOLVED.name())
                .ge(SyncGovernanceAlert::getLastOccurredAt, now.minusSeconds(dedupWindowSeconds))
                .orderByDesc(SyncGovernanceAlert::getLastOccurredAt)
                .last("LIMIT 1"), false);
    }

    /**
     * 鍛婅鎶曢€掍富娴佺▼銆?     * 褰撳墠鍏堟敮鎸佷竴涓畝鍗曚絾寰堝疄鐢ㄧ殑閾惧紡鎶曢€掓ā鍨嬶細
     * 1. 鍏堟寜閰嶇疆鐨勯€氶亾閾句緷娆″皾璇曪紱
     * 2. 鏈変竴涓€氶亾鎴愬姛灏辫涓烘姇閫掓垚鍔燂紱
     * 3. 鍏ㄩ儴澶辫触鍒欒繘鍏?FAILED 鎴?DEAD_LETTER銆?     */
    private void dispatchInternal(SyncGovernanceAlert alert, Long actorId, String actorRole, boolean manualDispatch) {
        LocalDateTime now = LocalDateTime.now();
        int dispatchAttemptNo = (alert.getDeliveryAttemptCount() == null ? 0 : alert.getDeliveryAttemptCount()) + 1;
        alert.setDeliveryAttemptCount(dispatchAttemptNo);

        List<SyncAlertChannel> channelChain = resolveChannelChain();
        String lastError = null;
        boolean allSkipped = true;
        for (SyncAlertChannel channel : channelChain) {
            alert.setDeliveryChannel(channel.name());
            LocalDateTime startedAt = LocalDateTime.now();
            DeliveryAttemptResult attemptResult = attemptDeliveryByChannelV2(alert, channel, actorId, actorRole, manualDispatch);
            LocalDateTime finishedAt = LocalDateTime.now();
            recordDeliveryAttempt(alert, dispatchAttemptNo, channel, attemptResult, manualDispatch, actorId, actorRole, startedAt, finishedAt);
            if (attemptResult.deliveryStatus() != SyncAlertDeliveryStatus.SKIPPED) {
                allSkipped = false;
            }
            if (attemptResult.success()) {
                alert.setDeliveryStatus(SyncAlertDeliveryStatus.SENT.name());
                alert.setLastDeliveryAt(finishedAt);
                alert.setLastDeliveryError(null);
                alert.setNextDeliveryAttemptAt(null);
                alert.setDeadLetteredAt(null);
                alert.setDeadLetterReason(null);
                updateById(alert);
                return;
            }
            lastError = attemptResult.errorMessage();
        }

        alert.setLastDeliveryAt(now);
        alert.setLastDeliveryError(truncate(lastError, 1000));
        if (allSkipped) {
            alert.setDeliveryStatus(SyncAlertDeliveryStatus.SKIPPED.name());
            alert.setNextDeliveryAttemptAt(null);
            alert.setDeadLetteredAt(null);
            alert.setDeadLetterReason(null);
        } else if (reachesDeadLetterThreshold(alert)) {
            alert.setDeliveryStatus(SyncAlertDeliveryStatus.DEAD_LETTER.name());
            alert.setDeadLetteredAt(now);
            alert.setDeadLetterReason(truncate(lastError, 1000));
            alert.setNextDeliveryAttemptAt(null);
        } else {
            alert.setDeliveryStatus(SyncAlertDeliveryStatus.FAILED.name());
            alert.setNextDeliveryAttemptAt(now.plusSeconds(resolveRetryBackoffSeconds()));
        }
        updateById(alert);
    }

    private DeliveryAttemptResult attemptDeliveryByChannel(SyncGovernanceAlert alert,
                                                           SyncAlertChannel channel,
                                                           Long actorId,
                                                           String actorRole,
                                                           boolean manualDispatch) {
        return switch (channel) {
            case NONE -> DeliveryAttemptResult.failedResult(manualDispatch
                    ? "当前未配置可用的告警投递通道，已跳过外部投递"
                    : "当前环境未启用外部告警通道");
            case INTERNAL_LOG -> {
                log.warn("同步治理告警进入内部日志通道: alertId={}, tenantId={}, taskId={}, type={}, severity={}, summary={}",
                        alert.getId(), alert.getTenantId(), alert.getSyncTaskId(), alert.getAlertType(), alert.getSeverity(), alert.getSummary());
                yield DeliveryAttemptResult.successResult();
            }
            case WEBHOOK, FEISHU_WEBHOOK, WECOM_WEBHOOK -> attemptWebhookDelivery(alert, actorId, actorRole, manualDispatch);
        };
    }

    private DeliveryAttemptResult attemptWebhookDelivery(SyncGovernanceAlert alert,
                                                         Long actorId,
                                                         String actorRole,
                                                         boolean manualDispatch) {
        if (!Boolean.TRUE.equals(syncAlertProperties.getWebhookEnabled()) || isBlank(syncAlertProperties.getWebhookUrl())) {
            return DeliveryAttemptResult.failedResult(manualDispatch
                    ? "当前未配置可用 webhook 通道，已跳过外部投递"
                    : "当前环境未启用 webhook 告警通道");
        }

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(resolveConnectTimeoutSeconds()))
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(syncAlertProperties.getWebhookUrl()))
                    .timeout(Duration.ofSeconds(resolveReadTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(buildWebhookPayload(alert, actorId, actorRole)))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return DeliveryAttemptResult.successResult();
            }
            return DeliveryAttemptResult.failedResult("HTTP " + response.statusCode() + ": " + response.body());
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return DeliveryAttemptResult.failedResult(exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
    }

    /**
     * 第二版通道投递实现。
     * 保留旧方法是为了降低这次重构的冲击面，而 dispatchInternal 已切到这一版。
     */
    private DeliveryAttemptResult attemptDeliveryByChannelV2(SyncGovernanceAlert alert,
                                                             SyncAlertChannel channel,
                                                             Long actorId,
                                                             String actorRole,
                                                             boolean manualDispatch) {
        return switch (channel) {
            case NONE -> DeliveryAttemptResult.skippedResult("当前未配置可用的告警投递通道，已跳过外部投递", "none");
            case INTERNAL_LOG -> {
                log.warn("同步治理告警进入内部日志通道: alertId={}, tenantId={}, taskId={}, type={}, severity={}, summary={}",
                        alert.getId(), alert.getTenantId(), alert.getSyncTaskId(), alert.getAlertType(), alert.getSeverity(), alert.getSummary());
                yield DeliveryAttemptResult.successResult("内部日志通道已记录告警", "internal-log");
            }
            case WEBHOOK -> attemptWebhookDeliveryV2(alert, actorId, actorRole, syncAlertProperties.getWebhookUrl(),
                    "通用 Webhook", manualDispatch);
            case FEISHU_WEBHOOK -> attemptWebhookDeliveryV2(alert, actorId, actorRole, syncAlertProperties.getFeishuWebhookUrl(),
                    "飞书机器人", manualDispatch);
            case WECOM_WEBHOOK -> attemptWebhookDeliveryV2(alert, actorId, actorRole, syncAlertProperties.getWecomWebhookUrl(),
                    "企业微信机器人", manualDispatch);
        };
    }

    /**
     * 第二版通用 webhook 投递实现。
     * 通过“目标地址 + 通道标签”的方式统一承载多类 webhook 通道。
     */
    private DeliveryAttemptResult attemptWebhookDeliveryV2(SyncGovernanceAlert alert,
                                                           Long actorId,
                                                           String actorRole,
                                                           String webhookUrl,
                                                           String channelLabel,
                                                           boolean manualDispatch) {
        if (!Boolean.TRUE.equals(syncAlertProperties.getWebhookEnabled())) {
            return DeliveryAttemptResult.skippedResult(channelLabel + " 当前未启用", channelLabel);
        }
        if (isBlank(webhookUrl)) {
            return manualDispatch
                    ? DeliveryAttemptResult.skippedResult(channelLabel + " 未配置可用地址，已跳过外部投递", channelLabel)
                    : DeliveryAttemptResult.skippedResult(channelLabel + " 当前未配置地址", channelLabel);
        }

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(resolveConnectTimeoutSeconds()))
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(webhookUrl))
                    .timeout(Duration.ofSeconds(resolveReadTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(buildWebhookPayload(alert, actorId, actorRole)))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            String responseSummary = "HTTP " + response.statusCode();
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return DeliveryAttemptResult.successResult(responseSummary, truncate(webhookUrl, 256));
            }
            return DeliveryAttemptResult.failedResult(responseSummary + ": " + response.body(), truncate(webhookUrl, 256));
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return DeliveryAttemptResult.failedResult(exception.getClass().getSimpleName() + ": " + exception.getMessage(),
                    truncate(webhookUrl, 256));
        }
    }

    private List<SyncAlertChannel> resolveChannelChain() {
        String channelChain = syncAlertProperties.getChannelChain();
        if (isBlank(channelChain)) {
            return List.of(resolveDefaultChannel());
        }
        return Arrays.stream(channelChain.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .map(item -> SyncAlertChannel.valueOf(item.toUpperCase()))
                .toList();
    }

    private SyncAlertChannel resolveDefaultChannel() {
        if (isBlank(syncAlertProperties.getDefaultChannel())) {
            return SyncAlertChannel.NONE;
        }
        return SyncAlertChannel.valueOf(syncAlertProperties.getDefaultChannel().toUpperCase());
    }

    private SyncAlertDeliveryStatus resolveInitialDeliveryStatus() {
        if (!Boolean.TRUE.equals(syncAlertProperties.getAutoDeliverOnOpen())) {
            return SyncAlertDeliveryStatus.PENDING;
        }
        return SyncAlertDeliveryStatus.PENDING;
    }

    private int resolveConnectTimeoutSeconds() {
        return syncAlertProperties.getConnectTimeoutSeconds() == null
                ? 3
                : Math.max(1, syncAlertProperties.getConnectTimeoutSeconds());
    }

    private int resolveReadTimeoutSeconds() {
        return syncAlertProperties.getReadTimeoutSeconds() == null
                ? 5
                : Math.max(1, syncAlertProperties.getReadTimeoutSeconds());
    }

    private int resolveRetryBackoffSeconds() {
        return syncAlertProperties.getRetryBackoffSeconds() == null
                ? 300
                : Math.max(30, syncAlertProperties.getRetryBackoffSeconds());
    }

    /**
     * 单次批量补投上限。
     */
    private int resolveRetryDispatchBatchLimit() {
        return syncAlertProperties.getRetryDispatchBatchLimit() == null
                ? 100
                : Math.max(1, syncAlertProperties.getRetryDispatchBatchLimit());
    }

    private boolean reachesDeadLetterThreshold(SyncGovernanceAlert alert) {
        int maxRetryCount = syncAlertProperties.getMaxDeliveryRetryCount() == null
                ? 3
                : Math.max(1, syncAlertProperties.getMaxDeliveryRetryCount());
        return alert.getDeliveryAttemptCount() != null && alert.getDeliveryAttemptCount() >= maxRetryCount;
    }

    /**
     * 落一条投递审计记录。
     * 当前每走一次通道都会记录一行，便于后续查看“同一条告警到底尝试过哪些通道”。
     */
    private void recordDeliveryAttempt(SyncGovernanceAlert alert,
                                       int attemptNo,
                                       SyncAlertChannel channel,
                                       DeliveryAttemptResult attemptResult,
                                       boolean manualDispatch,
                                       Long actorId,
                                       String actorRole,
                                       LocalDateTime startedAt,
                                       LocalDateTime finishedAt) {
        SyncAlertDeliveryRecord record = new SyncAlertDeliveryRecord();
        record.setTenantId(alert.getTenantId());
        record.setAlertId(alert.getId());
        record.setSyncTaskId(alert.getSyncTaskId());
        record.setAttemptNo(attemptNo);
        record.setChannel(channel.name());
        record.setDeliveryStatus(attemptResult.deliveryStatus().name());
        record.setTargetEndpoint(truncate(attemptResult.targetEndpoint(), 256));
        record.setManualDispatch(manualDispatch);
        record.setOperatorId(actorId);
        record.setOperatorRole(actorRole);
        record.setResponseSummary(truncate(attemptResult.responseSummary(), 1000));
        record.setErrorSummary(truncate(attemptResult.errorMessage(), 1000));
        record.setStartedAt(startedAt);
        record.setFinishedAt(finishedAt);
        syncAlertDeliveryRecordMapper.insert(record);
    }

    private String buildWebhookPayload(SyncGovernanceAlert alert, Long actorId, String actorRole) {
        StringBuilder builder = new StringBuilder("{");
        appendJsonField(builder, "alertId", alert.getId());
        appendJsonField(builder, "tenantId", alert.getTenantId());
        appendJsonField(builder, "syncTaskId", alert.getSyncTaskId());
        appendJsonField(builder, "alertType", alert.getAlertType());
        appendJsonField(builder, "severity", alert.getSeverity());
        appendJsonField(builder, "alertStatus", alert.getAlertStatus());
        appendJsonField(builder, "summary", alert.getSummary());
        appendJsonField(builder, "detail", alert.getDetail());
        appendJsonField(builder, "sourceResource", alert.getSourceResource());
        appendJsonField(builder, "triggeredByAction", alert.getTriggeredByAction());
        appendJsonField(builder, "occurrenceCount", alert.getOccurrenceCount());
        appendJsonField(builder, "actorId", actorId);
        appendJsonField(builder, "actorRole", actorRole);
        appendJsonField(builder, "sentAt", LocalDateTime.now());
        if (builder.charAt(builder.length() - 1) == ',') {
            builder.deleteCharAt(builder.length() - 1);
        }
        builder.append("}");
        return builder.toString();
    }

    private Long resolveTenantQueryScope(String actorRole, Long actorTenantId, Long requestedTenantId) {
        ActorRole role = ActorRole.fromValue(actorRole);
        if (role == ActorRole.PLATFORM_ADMINISTRATOR) {
            return requestedTenantId;
        }
        if (requestedTenantId == null) {
            return actorTenantId;
        }
        if (actorTenantId != null && !actorTenantId.equals(requestedTenantId)) {
            throw new IllegalStateException("褰撳墠瑙掕壊涓嶅厑璁歌法绉熸埛鏌ョ湅娌荤悊鍛婅");
        }
        return requestedTenantId;
    }

    private SyncPermissionContext buildAlertPermissionContext(SyncGovernanceAlert alert, SyncActionRequest request) {
        return buildAlertPermissionContext(alert, request.getActorId(), request.getActorRole(), request.getActorTenantId());
    }

    private SyncPermissionContext buildAlertPermissionContext(SyncGovernanceAlert alert,
                                                              Long actorId,
                                                              String actorRole,
                                                              Long actorTenantId) {
        return SyncPermissionContext.builder()
                .actorId(actorId)
                .actorRole(actorRole)
                .actorTenantId(actorTenantId)
                .resourceTenantId(alert.getTenantId())
                .build();
    }

    private void appendJsonField(StringBuilder builder, String key, Object value) {
        builder.append("\"").append(escape(key)).append("\":");
        if (value == null) {
            builder.append("null,");
            return;
        }
        if (value instanceof Number || value instanceof Boolean) {
            builder.append(value).append(",");
            return;
        }
        builder.append("\"").append(escape(String.valueOf(value))).append("\",");
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String escalateSeverity(String currentSeverity, String candidateSeverity) {
        SyncAlertSeverity current = currentSeverity == null
                ? SyncAlertSeverity.INFO
                : SyncAlertSeverity.valueOf(currentSeverity.toUpperCase());
        SyncAlertSeverity candidate = candidateSeverity == null
                ? SyncAlertSeverity.INFO
                : SyncAlertSeverity.valueOf(candidateSeverity.toUpperCase());
        return current.ordinal() >= candidate.ordinal() ? current.name() : candidate.name();
    }

    private SyncGovernanceAlert getRequiredAlert(Long id) {
        SyncGovernanceAlert alert = getById(id);
        if (alert == null) {
            throw new NoSuchElementException("鍚屾娌荤悊鍛婅涓嶅瓨鍦? " + id);
        }
        return alert;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }

    /**
     * 鍗曢€氶亾鎶曢€掑皾璇曠粨鏋溿€?     * 鍏堢敤鏈湴 record 鎵胯浇锛屼究浜庢妸鈥滄姇閫掓槸鍚︽垚鍔熲€濆拰鈥滃け璐ュ師鍥犫€濅綔涓轰竴涓揣鍑戣繑鍥炲€间紶閫掋€?     */
    private record DeliveryAttemptResult(boolean success,
                                         SyncAlertDeliveryStatus deliveryStatus,
                                         String errorMessage,
                                         String responseSummary,
                                         String targetEndpoint) {

        private static DeliveryAttemptResult successResult(String responseSummary, String targetEndpoint) {
            return new DeliveryAttemptResult(true, SyncAlertDeliveryStatus.SENT, null, responseSummary, targetEndpoint);
        }

        private static DeliveryAttemptResult successResult() {
            return successResult(null, null);
        }

        private static DeliveryAttemptResult failedResult(String errorMessage, String targetEndpoint) {
            return new DeliveryAttemptResult(false, SyncAlertDeliveryStatus.FAILED, errorMessage, null, targetEndpoint);
        }

        private static DeliveryAttemptResult failedResult(String errorMessage) {
            return failedResult(errorMessage, null);
        }

        private static DeliveryAttemptResult skippedResult(String responseSummary, String targetEndpoint) {
            return new DeliveryAttemptResult(false, SyncAlertDeliveryStatus.SKIPPED, null, responseSummary, targetEndpoint);
        }
    }
}
