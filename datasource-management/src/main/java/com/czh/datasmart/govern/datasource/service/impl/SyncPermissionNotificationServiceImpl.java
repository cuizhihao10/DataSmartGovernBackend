package com.czh.datasmart.govern.datasource.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.czh.datasmart.govern.datasource.config.SyncPermissionNotificationProperties;
import com.czh.datasmart.govern.datasource.controller.dto.SyncActionRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncPermissionGovernanceNotificationView;
import com.czh.datasmart.govern.datasource.controller.dto.SyncPermissionReminderScanResult;
import com.czh.datasmart.govern.datasource.entity.SyncPermissionGovernanceNotification;
import com.czh.datasmart.govern.datasource.entity.SyncPermissionPolicyChangeRequest;
import com.czh.datasmart.govern.datasource.mapper.SyncPermissionGovernanceNotificationMapper;
import com.czh.datasmart.govern.datasource.mapper.SyncPermissionPolicyChangeRequestMapper;
import com.czh.datasmart.govern.datasource.service.SyncPermissionNotificationService;
import com.czh.datasmart.govern.datasource.support.ActorRole;
import com.czh.datasmart.govern.datasource.support.SyncPermissionAction;
import com.czh.datasmart.govern.datasource.support.SyncPermissionContext;
import com.czh.datasmart.govern.datasource.support.SyncPermissionEvaluator;
import com.czh.datasmart.govern.datasource.support.SyncPermissionNotificationChannel;
import com.czh.datasmart.govern.datasource.support.SyncPermissionNotificationStatus;
import com.czh.datasmart.govern.datasource.support.SyncPermissionNotificationType;
import com.czh.datasmart.govern.datasource.support.SyncPermissionResource;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * @Author : Cui
 * @Date: 2026/04/25 00:00
 * @Description DataSmart Govern Backend - SyncPermissionNotificationServiceImpl.java
 * @Version:1.0.0
 *
 * 权限治理通知服务实现。
 *
 * 这一层把“审批链路里的提醒”从临时日志提升为可查询、可阅读、可投递、可巡检的持久化治理对象。
 * 在真实商业后台中，权限审批通常会涉及多类角色：申请人、审批人、租户管理员、平台管理员、审计员和值班运营。
 * 如果系统只提供审批单列表，那么审批人很容易漏处理，申请人也只能反复刷新页面。
 * 因此本服务负责把审批生命周期上的关键节点转化为通知：
 * 1. 提交申请后，给审批角色创建待办通知；
 * 2. 审批完成后，给申请人创建结果通知；
 * 3. 待审批超时后，创建普通提醒通知；
 * 4. 超时更久后，创建升级提醒通知，推动更高治理角色介入。
 *
 * 当前投递通道仍以 INTERNAL_LOG 为主，这是一个有意的分阶段设计：
 * 先把通知对象、去重、状态、API 和调度闭环做好；后续再把同一个通知模型接入站内消息、邮件、IM 或统一消息中心。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SyncPermissionNotificationServiceImpl implements SyncPermissionNotificationService {

    /**
     * 数据库中用 0 表示平台全局作用域。
     * 业务代码对外仍使用 null 表达“平台全局”，这样 API 语义更接近“没有特定租户限制”。
     */
    private static final long PLATFORM_SCOPE_TENANT_ID = 0L;

    /**
     * 权限变更申请单的待审批状态。
     * 这里先使用字符串常量，是为了贴合现有实体设计；后续可以继续收敛成枚举。
     */
    private static final String CHANGE_REQUEST_PENDING_APPROVAL = "PENDING_APPROVAL";

    private final SyncPermissionGovernanceNotificationMapper notificationMapper;
    private final SyncPermissionPolicyChangeRequestMapper policyChangeRequestMapper;
    private final SyncPermissionNotificationProperties notificationProperties;
    private final SyncPermissionEvaluator syncPermissionEvaluator;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public void createPendingApprovalNotifications(SyncPermissionPolicyChangeRequest entity, List<String> approverRoles) {
        if (!Boolean.TRUE.equals(notificationProperties.getNotifyApproverRolesOnSubmit())
                || approverRoles == null
                || approverRoles.isEmpty()) {
            return;
        }
        for (String approverRole : approverRoles) {
            createRoleNotification(entity,
                    SyncPermissionNotificationType.APPROVAL_PENDING,
                    ActorRole.fromValue(approverRole).name(),
                    "权限变更申请待审批 #" + entity.getId(),
                    buildPendingApprovalDetail(entity, approverRole));
        }
    }

    @Override
    @Transactional
    public void createDecisionNotification(SyncPermissionPolicyChangeRequest entity) {
        if (!Boolean.TRUE.equals(notificationProperties.getNotifyRequesterOnDecision())) {
            return;
        }
        SyncPermissionGovernanceNotification notification = buildBaseNotification(entity, resolveDecisionType(entity));
        notification.setRecipientActorId(entity.getRequesterId());
        notification.setRecipientActorRole(entity.getRequesterRole());
        notification.setSummary(buildDecisionSummary(entity));
        notification.setDetail(buildDecisionDetail(entity));
        notificationMapper.insert(notification);
        dispatchIfNeeded(notification);
    }

    @Override
    @Transactional(readOnly = true)
    public IPage<SyncPermissionGovernanceNotificationView> pageNotifications(Page<?> page,
                                                                             Long actorId,
                                                                             String actorRole,
                                                                             Long actorTenantId,
                                                                             Long targetTenantId,
                                                                             String notificationType,
                                                                             String notificationStatus,
                                                                             Boolean unreadOnly) {
        Long resolvedTargetTenantId = resolveReadableTargetTenantId(actorRole, actorTenantId, targetTenantId);
        SyncPermissionContext context = SyncPermissionContext.builder()
                .actorId(actorId)
                .actorRole(actorRole)
                .actorTenantId(actorTenantId)
                .resourceTenantId(resolvedTargetTenantId)
                .build();
        syncPermissionEvaluator.assertAllowed(context,
                SyncPermissionResource.SYNC_PERMISSION_POLICY, SyncPermissionAction.VIEW_POLICY);

        LambdaQueryWrapper<SyncPermissionGovernanceNotification> wrapper =
                new LambdaQueryWrapper<SyncPermissionGovernanceNotification>()
                        .eq(resolvedTargetTenantId == null, SyncPermissionGovernanceNotification::getTenantId, PLATFORM_SCOPE_TENANT_ID)
                        .eq(resolvedTargetTenantId != null, SyncPermissionGovernanceNotification::getTenantId, resolvedTargetTenantId)
                        .eq(notificationType != null && !notificationType.isBlank(),
                                SyncPermissionGovernanceNotification::getNotificationType,
                                SyncPermissionNotificationType.valueOf(notificationType.toUpperCase()).name())
                        .eq(notificationStatus != null && !notificationStatus.isBlank(),
                                SyncPermissionGovernanceNotification::getNotificationStatus,
                                SyncPermissionNotificationStatus.valueOf(notificationStatus.toUpperCase()).name())
                        .orderByDesc(SyncPermissionGovernanceNotification::getCreateTime)
                        .orderByDesc(SyncPermissionGovernanceNotification::getId);

        /*
         * 通知接收模型有两种：
         * 1. recipientActorId 有值：点对点通知，例如审批结果通知给申请人；
         * 2. recipientActorId 为空但 recipientActorRole 有值：角色待办，例如给所有租户管理员展示待审批提醒。
         * 因此前端查询“我的通知”时，需要同时命中“发给我本人”和“发给我当前角色”的通知。
         */
        if (actorId != null) {
            wrapper.and(condition -> condition
                    .eq(SyncPermissionGovernanceNotification::getRecipientActorId, actorId)
                    .or()
                    .and(inner -> inner
                            .isNull(SyncPermissionGovernanceNotification::getRecipientActorId)
                            .eq(SyncPermissionGovernanceNotification::getRecipientActorRole, ActorRole.fromValue(actorRole).name())));
        } else {
            wrapper.eq(SyncPermissionGovernanceNotification::getRecipientActorRole, ActorRole.fromValue(actorRole).name());
        }
        if (Boolean.TRUE.equals(unreadOnly)) {
            wrapper.ne(SyncPermissionGovernanceNotification::getNotificationStatus, SyncPermissionNotificationStatus.READ.name());
        }

        Page<SyncPermissionGovernanceNotification> entityPage =
                notificationMapper.selectPage(new Page<>(page.getCurrent(), page.getSize()), wrapper);
        Page<SyncPermissionGovernanceNotificationView> viewPage =
                new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        viewPage.setRecords(entityPage.getRecords().stream().map(this::toView).toList());
        return viewPage;
    }

    @Override
    @Transactional
    public SyncPermissionGovernanceNotificationView markAsRead(Long id, SyncActionRequest request) {
        SyncPermissionGovernanceNotification entity = getRequiredNotification(id);
        Long targetTenantId = fromStoredTenantId(entity.getTenantId());
        SyncPermissionContext context = SyncPermissionContext.builder()
                .actorId(request.getActorId())
                .actorRole(request.getActorRole())
                .actorTenantId(request.getActorTenantId())
                .resourceTenantId(targetTenantId)
                .build();
        syncPermissionEvaluator.assertAllowed(context,
                SyncPermissionResource.SYNC_PERMISSION_POLICY, SyncPermissionAction.VIEW_POLICY);

        if (!canReadNotification(entity, request)) {
            throw new IllegalStateException("当前操作人不能将不属于自己的权限治理通知标记为已读");
        }

        entity.setNotificationStatus(SyncPermissionNotificationStatus.READ.name());
        entity.setReadBy(request.getActorId());
        entity.setReadAt(LocalDateTime.now());
        notificationMapper.updateById(entity);
        return toView(entity);
    }

    @Override
    @Transactional
    public SyncPermissionReminderScanResult scanApprovalReminders(SyncActionRequest request) {
        SyncPermissionReminderScanResult result = buildEmptyReminderResult();
        if (!Boolean.TRUE.equals(notificationProperties.getApprovalReminderEnabled())) {
            return result;
        }

        Long resolvedTenantId = resolveReminderScanTenantScope(request);
        syncPermissionEvaluator.assertAllowed(SyncPermissionContext.builder()
                        .actorId(request.getActorId())
                        .actorRole(request.getActorRole())
                        .actorTenantId(request.getActorTenantId())
                        .resourceTenantId(resolvedTenantId)
                        .build(),
                SyncPermissionResource.SYNC_PERMISSION_POLICY, SyncPermissionAction.MANAGE_POLICY);

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime reminderCutoff = now.minusSeconds(notificationProperties.getSafeApprovalReminderAfterSeconds());
        LocalDateTime escalationCutoff = now.minusSeconds(notificationProperties.getSafeApprovalEscalationAfterSeconds());
        List<SyncPermissionPolicyChangeRequest> candidates = policyChangeRequestMapper.selectList(
                new LambdaQueryWrapper<SyncPermissionPolicyChangeRequest>()
                        .eq(SyncPermissionPolicyChangeRequest::getRequestStatus, CHANGE_REQUEST_PENDING_APPROVAL)
                        .eq(resolvedTenantId != null, SyncPermissionPolicyChangeRequest::getTargetTenantId, toStoredTenantId(resolvedTenantId))
                        .le(SyncPermissionPolicyChangeRequest::getCreateTime, reminderCutoff)
                        .orderByAsc(SyncPermissionPolicyChangeRequest::getCreateTime)
                        .last("LIMIT " + notificationProperties.getSafeApprovalReminderScanLimit()));

        result.setCandidateCount(candidates.size());
        for (SyncPermissionPolicyChangeRequest candidate : candidates) {
            result.getScannedChangeRequestIds().add(candidate.getId());

            /*
             * 普通提醒仍然发送给原审批角色。
             * 这样可以最大限度尊重申请单提交时保存的 requiredApproverRolesJson 快照，避免后续审批策略调整导致历史申请单解释不清。
             */
            List<String> approverRoles = parseApproverRoles(candidate.getRequiredApproverRolesJson());
            for (String approverRole : approverRoles) {
                if (existsNotification(candidate.getId(), SyncPermissionNotificationType.APPROVAL_REMINDER, null, approverRole)) {
                    result.setSkippedDuplicateCount(result.getSkippedDuplicateCount() + 1);
                    continue;
                }
                SyncPermissionGovernanceNotification notification = createRoleNotification(candidate,
                        SyncPermissionNotificationType.APPROVAL_REMINDER,
                        approverRole,
                        "权限变更申请审批提醒 #" + candidate.getId(),
                        buildReminderDetail(candidate, approverRole, notificationProperties.getSafeApprovalReminderAfterSeconds(), false));
                result.setReminderCreatedCount(result.getReminderCreatedCount() + 1);
                result.getReminderNotificationIds().add(notification.getId());
            }

            /*
             * 升级提醒只在更长 SLA 超时后创建。
             * 它不是替代普通提醒，而是给更高治理角色一个“需要介入”的信号。
             */
            if (candidate.getCreateTime() != null && !candidate.getCreateTime().isAfter(escalationCutoff)) {
                String escalationRole = ActorRole.fromValue(notificationProperties.getApprovalEscalationRecipientRole()).name();
                if (existsNotification(candidate.getId(), SyncPermissionNotificationType.APPROVAL_ESCALATED, null, escalationRole)) {
                    result.setSkippedDuplicateCount(result.getSkippedDuplicateCount() + 1);
                    continue;
                }
                SyncPermissionGovernanceNotification notification = createRoleNotification(candidate,
                        SyncPermissionNotificationType.APPROVAL_ESCALATED,
                        escalationRole,
                        "权限变更申请审批已超时升级 #" + candidate.getId(),
                        buildReminderDetail(candidate, escalationRole, notificationProperties.getSafeApprovalEscalationAfterSeconds(), true));
                result.setEscalationCreatedCount(result.getEscalationCreatedCount() + 1);
                result.getEscalationNotificationIds().add(notification.getId());
            }
        }
        result.setGeneratedAt(LocalDateTime.now());
        return result;
    }

    private SyncPermissionGovernanceNotification createRoleNotification(SyncPermissionPolicyChangeRequest entity,
                                                                        SyncPermissionNotificationType type,
                                                                        String recipientRole,
                                                                        String summary,
                                                                        String detail) {
        SyncPermissionGovernanceNotification notification = buildBaseNotification(entity, type);
        notification.setRecipientActorRole(ActorRole.fromValue(recipientRole).name());
        notification.setSummary(summary);
        notification.setDetail(detail);
        notificationMapper.insert(notification);
        dispatchIfNeeded(notification);
        return notification;
    }

    private SyncPermissionGovernanceNotification buildBaseNotification(SyncPermissionPolicyChangeRequest entity,
                                                                       SyncPermissionNotificationType type) {
        SyncPermissionGovernanceNotification notification = new SyncPermissionGovernanceNotification();
        notification.setTenantId(toStoredTenantId(fromStoredTenantId(entity.getTargetTenantId())));
        notification.setChangeRequestId(entity.getId());
        notification.setNotificationType(type.name());
        notification.setNotificationChannel(resolveDefaultChannel().name());
        notification.setNotificationStatus(SyncPermissionNotificationStatus.PENDING_DISPATCH.name());
        notification.setNextDispatchAt(LocalDateTime.now());
        notification.setDispatchAttemptCount(0);
        return notification;
    }

    private void dispatchIfNeeded(SyncPermissionGovernanceNotification notification) {
        if (!Boolean.TRUE.equals(notificationProperties.getAutoDispatchOnCreate())) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        notification.setDispatchAttemptCount((notification.getDispatchAttemptCount() == null ? 0 : notification.getDispatchAttemptCount()) + 1);
        notification.setDispatchedAt(now);
        switch (resolveDefaultChannel()) {
            case NONE -> {
                notification.setNotificationStatus(SyncPermissionNotificationStatus.SKIPPED.name());
                notification.setLastDispatchError(null);
                notification.setNextDispatchAt(null);
            }
            case INTERNAL_LOG -> {
                log.info("权限治理通知已投递到内部日志通道: notificationId={}, tenantId={}, changeRequestId={}, type={}, recipientActorId={}, recipientActorRole={}, summary={}",
                        notification.getId(),
                        fromStoredTenantId(notification.getTenantId()),
                        notification.getChangeRequestId(),
                        notification.getNotificationType(),
                        notification.getRecipientActorId(),
                        notification.getRecipientActorRole(),
                        notification.getSummary());
                notification.setNotificationStatus(SyncPermissionNotificationStatus.SENT.name());
                notification.setLastDispatchError(null);
                notification.setNextDispatchAt(null);
            }
        }
        notificationMapper.updateById(notification);
    }

    private boolean existsNotification(Long changeRequestId,
                                       SyncPermissionNotificationType type,
                                       Long recipientActorId,
                                       String recipientActorRole) {
        Long count = notificationMapper.selectCount(new LambdaQueryWrapper<SyncPermissionGovernanceNotification>()
                .eq(SyncPermissionGovernanceNotification::getChangeRequestId, changeRequestId)
                .eq(SyncPermissionGovernanceNotification::getNotificationType, type.name())
                .eq(recipientActorId != null, SyncPermissionGovernanceNotification::getRecipientActorId, recipientActorId)
                .isNull(recipientActorId == null, SyncPermissionGovernanceNotification::getRecipientActorId)
                .eq(recipientActorRole != null && !recipientActorRole.isBlank(),
                        SyncPermissionGovernanceNotification::getRecipientActorRole,
                        ActorRole.fromValue(recipientActorRole).name()));
        return count != null && count > 0;
    }

    private SyncPermissionNotificationType resolveDecisionType(SyncPermissionPolicyChangeRequest entity) {
        return switch (entity.getRequestStatus()) {
            case "EXECUTED" -> SyncPermissionNotificationType.APPROVAL_APPROVED;
            case "REJECTED" -> SyncPermissionNotificationType.APPROVAL_REJECTED;
            default -> throw new IllegalStateException("当前申请状态不适合生成审批结果通知: " + entity.getRequestStatus());
        };
    }

    private String buildPendingApprovalDetail(SyncPermissionPolicyChangeRequest entity, String approverRole) {
        return "目标角色=" + entity.getTargetRole()
                + "，绑定类型=" + entity.getBindingType()
                + "，申请原因=" + entity.getRequestReason()
                + "，当前待审批角色=" + ActorRole.fromValue(approverRole).name();
    }

    private String buildReminderDetail(SyncPermissionPolicyChangeRequest entity,
                                       String recipientRole,
                                       int thresholdSeconds,
                                       boolean escalated) {
        return "提醒级别=" + (escalated ? "升级提醒" : "普通提醒")
                + "，超时阈值秒数=" + thresholdSeconds
                + "，申请创建时间=" + entity.getCreateTime()
                + "，目标租户=" + fromStoredTenantId(entity.getTargetTenantId())
                + "，目标角色=" + entity.getTargetRole()
                + "，绑定类型=" + entity.getBindingType()
                + "，接收角色=" + ActorRole.fromValue(recipientRole).name()
                + "，申请原因=" + entity.getRequestReason();
    }

    private String buildDecisionSummary(SyncPermissionPolicyChangeRequest entity) {
        return switch (entity.getRequestStatus()) {
            case "EXECUTED" -> "权限变更申请已批准 #" + entity.getId();
            case "REJECTED" -> "权限变更申请已驳回 #" + entity.getId();
            default -> "权限变更申请状态更新 #" + entity.getId();
        };
    }

    private String buildDecisionDetail(SyncPermissionPolicyChangeRequest entity) {
        return "目标角色=" + entity.getTargetRole()
                + "，绑定类型=" + entity.getBindingType()
                + "，审批模式=" + entity.getApprovalMode()
                + "，执行摘要=" + entity.getExecutionSummary();
    }

    private List<String> parseApproverRoles(String requiredApproverRolesJson) {
        if (requiredApproverRolesJson == null || requiredApproverRolesJson.isBlank()) {
            return List.of();
        }
        try {
            List<String> roles = objectMapper.readValue(requiredApproverRolesJson, new TypeReference<List<String>>() {
            });
            return roles.stream()
                    .filter(Objects::nonNull)
                    .map(item -> ActorRole.fromValue(item).name())
                    .distinct()
                    .toList();
        } catch (Exception exception) {
            throw new IllegalStateException("权限审批角色快照 JSON 无法解析: " + requiredApproverRolesJson, exception);
        }
    }

    private boolean canReadNotification(SyncPermissionGovernanceNotification entity, SyncActionRequest request) {
        if (ActorRole.fromValue(request.getActorRole()) == ActorRole.PLATFORM_ADMINISTRATOR) {
            return true;
        }
        if (entity.getRecipientActorId() != null) {
            return Objects.equals(entity.getRecipientActorId(), request.getActorId());
        }
        return ActorRole.fromValue(request.getActorRole()).name().equals(entity.getRecipientActorRole());
    }

    private SyncPermissionGovernanceNotification getRequiredNotification(Long id) {
        SyncPermissionGovernanceNotification entity = notificationMapper.selectById(id);
        if (entity == null) {
            throw new IllegalStateException("权限治理通知不存在: " + id);
        }
        return entity;
    }

    private Long resolveReadableTargetTenantId(String actorRole, Long actorTenantId, Long targetTenantId) {
        ActorRole role = ActorRole.fromValue(actorRole);
        if (role == ActorRole.PLATFORM_ADMINISTRATOR) {
            return targetTenantId;
        }
        if (targetTenantId == null) {
            return actorTenantId;
        }
        if (!Objects.equals(actorTenantId, targetTenantId)) {
            throw new IllegalStateException("当前角色不能跨租户查看权限治理通知");
        }
        return targetTenantId;
    }

    private Long resolveReminderScanTenantScope(SyncActionRequest request) {
        ActorRole role = ActorRole.fromValue(request.getActorRole());
        if (role == ActorRole.PLATFORM_ADMINISTRATOR) {
            return null;
        }
        if (request.getActorTenantId() == null) {
            throw new IllegalStateException("非平台管理员执行审批提醒扫描时必须携带 actorTenantId");
        }
        return request.getActorTenantId();
    }

    private SyncPermissionNotificationChannel resolveDefaultChannel() {
        String configured = notificationProperties.getDefaultChannel();
        if (configured == null || configured.isBlank()) {
            return SyncPermissionNotificationChannel.INTERNAL_LOG;
        }
        return SyncPermissionNotificationChannel.valueOf(configured.trim().toUpperCase());
    }

    private SyncPermissionReminderScanResult buildEmptyReminderResult() {
        SyncPermissionReminderScanResult result = new SyncPermissionReminderScanResult();
        result.setCandidateCount(0);
        result.setReminderCreatedCount(0);
        result.setEscalationCreatedCount(0);
        result.setSkippedDuplicateCount(0);
        result.setScannedChangeRequestIds(new ArrayList<>());
        result.setReminderNotificationIds(new ArrayList<>());
        result.setEscalationNotificationIds(new ArrayList<>());
        result.setGeneratedAt(LocalDateTime.now());
        return result;
    }

    private SyncPermissionGovernanceNotificationView toView(SyncPermissionGovernanceNotification entity) {
        SyncPermissionGovernanceNotificationView view = new SyncPermissionGovernanceNotificationView();
        view.setId(entity.getId());
        view.setTenantId(fromStoredTenantId(entity.getTenantId()));
        view.setTenantScopeType(Objects.equals(entity.getTenantId(), PLATFORM_SCOPE_TENANT_ID)
                ? "PLATFORM_GLOBAL" : "TENANT_OVERRIDE");
        view.setChangeRequestId(entity.getChangeRequestId());
        view.setNotificationType(entity.getNotificationType());
        view.setRecipientActorId(entity.getRecipientActorId());
        view.setRecipientActorRole(entity.getRecipientActorRole());
        view.setNotificationChannel(entity.getNotificationChannel());
        view.setNotificationStatus(entity.getNotificationStatus());
        view.setSummary(entity.getSummary());
        view.setDetail(entity.getDetail());
        view.setNextDispatchAt(entity.getNextDispatchAt());
        view.setDispatchAttemptCount(entity.getDispatchAttemptCount());
        view.setDispatchedAt(entity.getDispatchedAt());
        view.setLastDispatchError(entity.getLastDispatchError());
        view.setReadBy(entity.getReadBy());
        view.setReadAt(entity.getReadAt());
        view.setCreateTime(entity.getCreateTime());
        view.setUpdateTime(entity.getUpdateTime());
        return view;
    }

    private Long toStoredTenantId(Long tenantId) {
        return tenantId == null ? PLATFORM_SCOPE_TENANT_ID : tenantId;
    }

    private Long fromStoredTenantId(Long tenantId) {
        return Objects.equals(tenantId, PLATFORM_SCOPE_TENANT_ID) ? null : tenantId;
    }
}
