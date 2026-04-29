package com.czh.datasmart.govern.datasource.support;

/**
 * @Author : Cui
 * @Date: 2026/4/24 22:40
 * @Description DataSmart Govern Backend - SyncPermissionNotificationStatus.java
 * @Version:1.0.0
 *
 * 权限治理通知状态枚举。
 * 这里显式区分“尚未投递”“已投递”“已读”“投递失败”“已跳过”，
 * 是为了让通知对象既能服务于后台待办，也能服务于后续真正的外部消息通道。
 */
public enum SyncPermissionNotificationStatus {
    PENDING_DISPATCH,
    SENT,
    READ,
    FAILED,
    SKIPPED
}
