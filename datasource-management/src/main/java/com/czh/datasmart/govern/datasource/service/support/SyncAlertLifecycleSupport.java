/**
 * @Author : Cui
 * @Date: 2026/05/05 23:40
 * @Description DataSmart Govern Backend - SyncAlertLifecycleSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.czh.datasmart.govern.datasource.config.SyncAlertProperties;
import com.czh.datasmart.govern.datasource.controller.dto.SyncActionRequest;
import com.czh.datasmart.govern.datasource.entity.SyncGovernanceAlert;
import com.czh.datasmart.govern.datasource.mapper.SyncGovernanceAlertMapper;
import com.czh.datasmart.govern.datasource.support.SyncAlertChannel;
import com.czh.datasmart.govern.datasource.support.SyncAlertDeliveryStatus;
import com.czh.datasmart.govern.datasource.support.SyncAlertSeverity;
import com.czh.datasmart.govern.datasource.support.SyncAlertStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.NoSuchElementException;

/**
 * 同步治理告警生命周期支持组件。
 *
 * <p>该组件负责“告警作为一个治理对象”的生命周期，而不负责外部投递：
 * 打开、刷新、去重、确认、解决、死信重入队、列表查询都属于告警对象本身的状态管理。</p>
 *
 * <p>这样拆分后，告警生命周期与 outbox 投递可以分别演进：
 * 生命周期侧未来可以增加告警归并、抑制窗口、负责人、SLA、升级策略、关闭原因和审批；
 * 投递侧可以独立升级为多通道、模板化消息、重试退避、签名校验和外部通知中心。</p>
 */
@Component
@RequiredArgsConstructor
public class SyncAlertLifecycleSupport {

    /**
     * 告警主表 Mapper。
     */
    private final SyncGovernanceAlertMapper syncGovernanceAlertMapper;

    /**
     * 告警配置。
     *
     * <p>生命周期侧主要使用去重窗口、默认通道和自动投递开关的初始状态语义。</p>
     */
    private final SyncAlertProperties syncAlertProperties;

    /**
     * 打开或刷新一条治理告警。
     *
     * <p>相同 alertKey 在去重窗口内重复触发时，优先刷新已有未解决告警，
     * 而不是无限新增记录。这样可以减少告警风暴，也让运维人员看到“同类问题发生了多少次”。</p>
     */
    public SyncGovernanceAlert openOrRefreshAlert(Long tenantId,
                                                  Long syncTaskId,
                                                  String alertType,
                                                  String severity,
                                                  String alertKey,
                                                  String summary,
                                                  String detail,
                                                  String sourceResource,
                                                  String triggeredByAction) {
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
            alert.setDeliveryStatus(SyncAlertDeliveryStatus.PENDING.name());
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
            syncGovernanceAlertMapper.insert(alert);
            return alert;
        }

        alert.setTenantId(tenantId == null ? alert.getTenantId() : tenantId);
        alert.setSyncTaskId(syncTaskId == null ? alert.getSyncTaskId() : syncTaskId);
        alert.setSeverity(escalateSeverity(alert.getSeverity(), severity));
        alert.setSummary(truncate(summary, 512));
        alert.setDetail(truncate(detail, 4000));
        alert.setSourceResource(sourceResource);
        alert.setTriggeredByAction(triggeredByAction);
        alert.setLastOccurredAt(now);
        alert.setOccurrenceCount((alert.getOccurrenceCount() == null ? 0 : alert.getOccurrenceCount()) + 1);
        reopenIfAlreadyResolved(alert);
        requeueIfDeadLetter(alert);
        alert.setNextDeliveryAttemptAt(Boolean.TRUE.equals(syncAlertProperties.getAutoDeliverOnOpen())
                ? now : alert.getNextDeliveryAttemptAt());
        syncGovernanceAlertMapper.updateById(alert);
        return alert;
    }

    /**
     * 分页查询治理告警。
     */
    public IPage<SyncGovernanceAlert> pageAlerts(Page<SyncGovernanceAlert> page,
                                                 Long resolvedTenantId,
                                                 String alertType,
                                                 String severity,
                                                 String alertStatus,
                                                 String deliveryStatus) {
        LambdaQueryWrapper<SyncGovernanceAlert> wrapper = new LambdaQueryWrapper<SyncGovernanceAlert>()
                .eq(resolvedTenantId != null, SyncGovernanceAlert::getTenantId, resolvedTenantId)
                .eq(alertType != null && !alertType.isBlank(), SyncGovernanceAlert::getAlertType, normalize(alertType))
                .eq(severity != null && !severity.isBlank(), SyncGovernanceAlert::getSeverity, normalize(severity))
                .eq(alertStatus != null && !alertStatus.isBlank(), SyncGovernanceAlert::getAlertStatus, normalize(alertStatus))
                .eq(deliveryStatus != null && !deliveryStatus.isBlank(), SyncGovernanceAlert::getDeliveryStatus, normalize(deliveryStatus))
                .orderByDesc(SyncGovernanceAlert::getLastOccurredAt)
                .orderByDesc(SyncGovernanceAlert::getCreateTime);
        return syncGovernanceAlertMapper.selectPage(page, wrapper);
    }

    /**
     * 确认告警。
     *
     * <p>确认表示“有人已经看到并接手”，不代表问题已经消失。
     * 如果告警已经解决，则不再回退状态，只记录确认人和确认时间。</p>
     */
    public SyncGovernanceAlert acknowledgeAlert(SyncGovernanceAlert alert, SyncActionRequest request) {
        if (!SyncAlertStatus.RESOLVED.name().equals(alert.getAlertStatus())) {
            alert.setAlertStatus(SyncAlertStatus.ACKNOWLEDGED.name());
        }
        alert.setAcknowledgedBy(request.getActorId());
        alert.setAcknowledgedAt(LocalDateTime.now());
        syncGovernanceAlertMapper.updateById(alert);
        return alert;
    }

    /**
     * 解决告警。
     *
     * <p>解决表示运维侧认为问题已经处理完成。后续如果同一 alertKey 在去重窗口内再次触发，
     * `openOrRefreshAlert` 会把它重新打开，以避免已解决告警掩盖新的故障。</p>
     */
    public SyncGovernanceAlert resolveAlert(SyncGovernanceAlert alert, SyncActionRequest request) {
        alert.setAlertStatus(SyncAlertStatus.RESOLVED.name());
        alert.setResolvedBy(request.getActorId());
        alert.setResolvedAt(LocalDateTime.now());
        syncGovernanceAlertMapper.updateById(alert);
        return alert;
    }

    /**
     * 将死信告警重新放回待投递队列。
     */
    public SyncGovernanceAlert requeueDeadLetterAlert(SyncGovernanceAlert alert) {
        alert.setDeliveryStatus(SyncAlertDeliveryStatus.PENDING.name());
        alert.setNextDeliveryAttemptAt(LocalDateTime.now());
        alert.setDeadLetteredAt(null);
        alert.setDeadLetterReason(null);
        alert.setDispatchLeaseOwner(null);
        alert.setDispatchLeaseExpireAt(null);
        syncGovernanceAlertMapper.updateById(alert);
        return alert;
    }

    public SyncGovernanceAlert getRequiredAlert(Long id) {
        SyncGovernanceAlert alert = syncGovernanceAlertMapper.selectById(id);
        if (alert == null) {
            throw new NoSuchElementException("同步治理告警不存在: " + id);
        }
        return alert;
    }

    private SyncGovernanceAlert findDeduplicatedAlert(String alertKey, LocalDateTime now) {
        int dedupWindowSeconds = syncAlertProperties.getDedupWindowSeconds() == null
                ? 600
                : Math.max(60, syncAlertProperties.getDedupWindowSeconds());
        return syncGovernanceAlertMapper.selectOne(new LambdaQueryWrapper<SyncGovernanceAlert>()
                .eq(SyncGovernanceAlert::getAlertKey, alertKey)
                .ne(SyncGovernanceAlert::getAlertStatus, SyncAlertStatus.RESOLVED.name())
                .ge(SyncGovernanceAlert::getLastOccurredAt, now.minusSeconds(dedupWindowSeconds))
                .orderByDesc(SyncGovernanceAlert::getLastOccurredAt)
                .last("LIMIT 1"));
    }

    private void reopenIfAlreadyResolved(SyncGovernanceAlert alert) {
        if (SyncAlertStatus.RESOLVED.name().equals(alert.getAlertStatus())) {
            alert.setAlertStatus(SyncAlertStatus.OPEN.name());
            alert.setResolvedBy(null);
            alert.setResolvedAt(null);
        }
    }

    private void requeueIfDeadLetter(SyncGovernanceAlert alert) {
        if (SyncAlertDeliveryStatus.DEAD_LETTER.name().equals(alert.getDeliveryStatus())) {
            alert.setDeliveryStatus(SyncAlertDeliveryStatus.PENDING.name());
            alert.setDeadLetteredAt(null);
            alert.setDeadLetterReason(null);
        }
    }

    private SyncAlertChannel resolveDefaultChannel() {
        if (syncAlertProperties.getDefaultChannel() == null || syncAlertProperties.getDefaultChannel().isBlank()) {
            return SyncAlertChannel.NONE;
        }
        return SyncAlertChannel.valueOf(syncAlertProperties.getDefaultChannel().toUpperCase());
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

    private String normalize(String value) {
        return value.toUpperCase();
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }
}
