package com.czh.datasmart.govern.datasource.support;

/**
 * @Author : Cui
 * @Date: 2026/4/24 22:40
 * @Description DataSmart Govern Backend - SyncPermissionNotificationChannel.java
 * @Version:1.0.0
 *
 * 权限治理通知通道枚举。
 * 当前先实现最小可用的 INTERNAL_LOG 与 NONE，
 * 为后续扩展到站内消息、邮件、企业微信、飞书等通道保留稳定抽象。
 */
public enum SyncPermissionNotificationChannel {
    NONE,
    INTERNAL_LOG
}
