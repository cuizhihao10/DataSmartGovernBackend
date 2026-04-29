package com.czh.datasmart.govern.datasource.controller.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * @Author : Cui
 * @Date: 2026/4/24 22:40
 * @Description DataSmart Govern Backend - SyncPermissionGovernanceNotificationView.java
 * @Version:1.0.0
 *
 * 权限治理通知视图对象。
 * 这一层主要服务于治理后台和后续站内待办中心。
 */
@Data
public class SyncPermissionGovernanceNotificationView {

    private Long id;
    private Long tenantId;
    private String tenantScopeType;
    private Long changeRequestId;
    private String notificationType;
    private Long recipientActorId;
    private String recipientActorRole;
    private String notificationChannel;
    private String notificationStatus;
    private String summary;
    private String detail;
    private LocalDateTime nextDispatchAt;
    private Integer dispatchAttemptCount;
    private LocalDateTime dispatchedAt;
    private String lastDispatchError;
    private Long readBy;
    private LocalDateTime readAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
