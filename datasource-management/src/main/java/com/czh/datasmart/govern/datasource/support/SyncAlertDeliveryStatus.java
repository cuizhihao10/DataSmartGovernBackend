package com.czh.datasmart.govern.datasource.support;

/**
 * @Author : Cui
 * @Date: 2026/4/20 09:18
 * @Description DataSmart Govern Backend - SyncAlertDeliveryStatus.java
 * @Version:1.0.0
 *
 * 告警投递状态。
 * 当前主要面向 webhook/outbox 语义：
 * - PENDING：已生成但尚未投递；
 * - SENT：已成功发出；
 * - FAILED：已尝试但失败；
 * - SKIPPED：当前环境未配置真实外部投递。
 */
public enum SyncAlertDeliveryStatus {
    PENDING,
    SENT,
    FAILED,
    SKIPPED,
    DEAD_LETTER
}
