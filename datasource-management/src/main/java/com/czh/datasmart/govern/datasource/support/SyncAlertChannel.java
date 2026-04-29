package com.czh.datasmart.govern.datasource.support;

/**
 * @Author : Cui
 * @Date: 2026/4/20 09:18
 * @Description DataSmart Govern Backend - SyncAlertChannel.java
 * @Version:1.0.0
 *
 * 告警投递通道。
 * 当前先保留一个足够简单但可扩展的通道枚举，便于后续增加短信、邮件、企业 IM、事件总线等能力。
 */
public enum SyncAlertChannel {
    WEBHOOK,
    FEISHU_WEBHOOK,
    WECOM_WEBHOOK,
    INTERNAL_LOG,
    NONE
}
