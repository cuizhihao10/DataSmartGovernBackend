package com.czh.datasmart.govern.datasource.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.czh.datasmart.govern.datasource.controller.dto.SyncActionRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncPermissionGovernanceNotificationView;
import com.czh.datasmart.govern.datasource.controller.dto.SyncPermissionReminderScanResult;
import com.czh.datasmart.govern.datasource.entity.SyncPermissionPolicyChangeRequest;

import java.util.List;

/**
 * @Author : Cui
 * @Date: 2026/4/24 22:40
 * @Description DataSmart Govern Backend - SyncPermissionNotificationService.java
 * @Version:1.0.0
 *
 * 权限治理通知服务。
 * 负责权限审批链路中的待办提醒、结果通知、通知查询和已读确认。
 */
public interface SyncPermissionNotificationService {

    void createPendingApprovalNotifications(SyncPermissionPolicyChangeRequest entity, List<String> approverRoles);

    void createDecisionNotification(SyncPermissionPolicyChangeRequest entity);

    IPage<SyncPermissionGovernanceNotificationView> pageNotifications(Page<?> page,
                                                                      Long actorId,
                                                                      String actorRole,
                                                                      Long actorTenantId,
                                                                      Long targetTenantId,
                                                                      String notificationType,
                                                                      String notificationStatus,
                                                                      Boolean unreadOnly);

    SyncPermissionGovernanceNotificationView markAsRead(Long id, SyncActionRequest request);

    /**
     * 扫描已经超出审批 SLA 的待审批申请单，并生成普通提醒或升级提醒通知。
     *
     * 这个方法会修改通知表，但不会修改审批单本身，所以它属于“治理提醒”而不是“审批状态流转”。
     * 调用者既可以是人工管理员，也可以是后台调度器；两种入口都复用同一套权限校验、去重和通知创建规则。
     *
     * @param request 当前操作人或系统调度身份，用于权限判断、租户范围判断和审计说明。
     * @return 本轮扫描和创建通知的统计结果。
     */
    SyncPermissionReminderScanResult scanApprovalReminders(SyncActionRequest request);
}
