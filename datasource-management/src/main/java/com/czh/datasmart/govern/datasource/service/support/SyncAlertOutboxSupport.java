/**
 * @Author : Cui
 * @Date: 2026/05/05 23:40
 * @Description DataSmart Govern Backend - SyncAlertOutboxSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.czh.datasmart.govern.datasource.controller.dto.SyncAlertDispatchBatchResult;
import com.czh.datasmart.govern.datasource.controller.dto.SyncAlertOutboxHealthSnapshot;
import com.czh.datasmart.govern.datasource.controller.dto.SyncAlertOutboxLeaseRecoveryResult;
import com.czh.datasmart.govern.datasource.controller.dto.SyncActionRequest;
import com.czh.datasmart.govern.datasource.entity.SyncGovernanceAlert;
import com.czh.datasmart.govern.datasource.mapper.SyncGovernanceAlertMapper;
import com.czh.datasmart.govern.datasource.support.SyncAlertDeliveryStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * 同步治理告警 outbox 支持组件。
 *
 * <p>告警主表同时承载投递 outbox 语义：PENDING/FAILED 表示可被调度器补投，
 * dispatchLeaseOwner/dispatchLeaseExpireAt 表示某个调度实例已经临时认领，DEAD_LETTER 表示重试耗尽。</p>
 *
 * <p>该组件集中处理认领、释放租约、批量补投、租约恢复和健康快照。
 * 这样未来如果升级为独立 outbox 表、Redis 分布式锁、Kafka 延迟队列或 observability 指标，
 * 可以优先替换这里，而不是改动告警主服务的每个接口。</p>
 */
@Component
@RequiredArgsConstructor
public class SyncAlertOutboxSupport {

    private final SyncGovernanceAlertMapper syncGovernanceAlertMapper;
    private final SyncAlertDeliverySupport syncAlertDeliverySupport;

    /**
     * 手动投递单条告警。
     */
    public SyncGovernanceAlert dispatchSingleAlert(Long alertId, SyncActionRequest request) {
        String dispatchOwner = "MANUAL-" + request.getActorId();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime leaseExpireAt = now.plusSeconds(120);
        if (!tryClaimAlert(alertId, dispatchOwner, leaseExpireAt, false, now)) {
            throw new IllegalStateException("当前告警正在被其他调度实例处理，请稍后重试");
        }
        try {
            syncAlertDeliverySupport.dispatchInternal(getRequiredAlert(alertId), request.getActorId(), request.getActorRole(), true);
        } finally {
            releaseDispatchLease(alertId, dispatchOwner);
        }
        return getRequiredAlert(alertId);
    }

    /**
     * 批量认领并补投到期告警。
     */
    public SyncAlertDispatchBatchResult claimAndDispatchRetryableAlerts(SyncActionRequest request,
                                                                        String dispatcherInstanceId,
                                                                        Integer claimBatchSize,
                                                                        Integer leaseSeconds,
                                                                        Long resolvedTenantId) {
        LocalDateTime now = LocalDateTime.now();
        int resolvedClaimBatchSize = claimBatchSize == null
                ? syncAlertDeliverySupport.resolveRetryDispatchBatchLimit()
                : Math.max(1, claimBatchSize);
        int resolvedLeaseSeconds = leaseSeconds == null ? 120 : Math.max(30, leaseSeconds);
        List<SyncGovernanceAlert> alerts = listDueDispatchCandidates(resolvedTenantId, now, resolvedClaimBatchSize);
        SyncAlertDispatchBatchResult result = initialBatchResult(alerts.size());

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
                syncAlertDeliverySupport.dispatchInternal(alert, request.getActorId(), request.getActorRole(), true);
                collectDispatchResult(result, getRequiredAlert(alert.getId()));
            } finally {
                releaseDispatchLease(alert.getId(), dispatcherInstanceId);
            }
        }
        return result;
    }

    /**
     * 生成 outbox 健康快照。
     */
    public SyncAlertOutboxHealthSnapshot inspectOutboxHealth(Long resolvedTenantId) {
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

    /**
     * 恢复已经过期的投递租约。
     */
    public SyncAlertOutboxLeaseRecoveryResult recoverExpiredDispatchLeases(Long resolvedTenantId, Integer batchSize) {
        LocalDateTime now = LocalDateTime.now();
        int safeBatchSize = batchSize == null ? 100 : Math.max(1, batchSize);
        List<SyncGovernanceAlert> expiredLeases = syncGovernanceAlertMapper.selectList(new LambdaQueryWrapper<SyncGovernanceAlert>()
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
            boolean recovered = syncGovernanceAlertMapper.update(null, new LambdaUpdateWrapper<SyncGovernanceAlert>()
                    .eq(SyncGovernanceAlert::getId, alert.getId())
                    .eq(SyncGovernanceAlert::getDispatchLeaseOwner, alert.getDispatchLeaseOwner())
                    .eq(SyncGovernanceAlert::getDispatchLeaseExpireAt, alert.getDispatchLeaseExpireAt())
                    .set(SyncGovernanceAlert::getDispatchLeaseOwner, null)
                    .set(SyncGovernanceAlert::getDispatchLeaseExpireAt, null)) > 0;
            if (recovered) {
                result.setRecoveredCount(result.getRecoveredCount() + 1);
                result.getRecoveredAlertIds().add(alert.getId());
            }
        }
        return result;
    }

    private SyncAlertDispatchBatchResult initialBatchResult(int candidateCount) {
        SyncAlertDispatchBatchResult result = new SyncAlertDispatchBatchResult();
        result.setCandidateCount(candidateCount);
        result.setClaimedCount(0);
        result.setAttemptedCount(0);
        result.setSentCount(0);
        result.setFailedCount(0);
        result.setDeadLetterCount(0);
        result.setProcessedAlertIds(new ArrayList<>());
        return result;
    }

    private void collectDispatchResult(SyncAlertDispatchBatchResult result, SyncGovernanceAlert latest) {
        result.getProcessedAlertIds().add(latest.getId());
        if (SyncAlertDeliveryStatus.SENT.name().equals(latest.getDeliveryStatus())) {
            result.setSentCount(result.getSentCount() + 1);
        } else if (SyncAlertDeliveryStatus.DEAD_LETTER.name().equals(latest.getDeliveryStatus())) {
            result.setDeadLetterCount(result.getDeadLetterCount() + 1);
        } else if (SyncAlertDeliveryStatus.FAILED.name().equals(latest.getDeliveryStatus())) {
            result.setFailedCount(result.getFailedCount() + 1);
        }
    }

    private List<SyncGovernanceAlert> listDueDispatchCandidates(Long resolvedTenantId, LocalDateTime now, int limit) {
        return syncGovernanceAlertMapper.selectList(new LambdaQueryWrapper<SyncGovernanceAlert>()
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

    private boolean tryClaimAlert(Long alertId, String dispatchOwner, LocalDateTime leaseExpireAt,
                                  boolean dueOnly, LocalDateTime now) {
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
        return syncGovernanceAlertMapper.update(null, updateWrapper) > 0;
    }

    private void releaseDispatchLease(Long alertId, String dispatchOwner) {
        syncGovernanceAlertMapper.update(null, new LambdaUpdateWrapper<SyncGovernanceAlert>()
                .eq(SyncGovernanceAlert::getId, alertId)
                .eq(SyncGovernanceAlert::getDispatchLeaseOwner, dispatchOwner)
                .set(SyncGovernanceAlert::getDispatchLeaseOwner, null)
                .set(SyncGovernanceAlert::getDispatchLeaseExpireAt, null));
    }

    private long countByDeliveryStatus(Long resolvedTenantId, SyncAlertDeliveryStatus deliveryStatus) {
        return count(new LambdaQueryWrapper<SyncGovernanceAlert>()
                .eq(resolvedTenantId != null, SyncGovernanceAlert::getTenantId, resolvedTenantId)
                .eq(SyncGovernanceAlert::getDeliveryStatus, deliveryStatus.name()));
    }

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

    private LocalDateTime findOldestDueRetryAt(Long resolvedTenantId, LocalDateTime now) {
        List<SyncGovernanceAlert> oldest = syncGovernanceAlertMapper.selectList(new LambdaQueryWrapper<SyncGovernanceAlert>()
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
        return oldest.isEmpty() ? null : oldest.get(0).getNextDeliveryAttemptAt();
    }

    private long count(LambdaQueryWrapper<SyncGovernanceAlert> wrapper) {
        return syncGovernanceAlertMapper.selectCount(wrapper);
    }

    private SyncGovernanceAlert getRequiredAlert(Long id) {
        SyncGovernanceAlert alert = syncGovernanceAlertMapper.selectById(id);
        if (alert == null) {
            throw new NoSuchElementException("同步治理告警不存在: " + id);
        }
        return alert;
    }
}
