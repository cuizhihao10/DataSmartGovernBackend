/**
 * @Author : Cui
 * @Date: 2026/05/07 21:26
 * @Description DataSmart Govern Backend - SyncTriggerType.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.support;

/**
 * 同步任务触发方式。
 */
public enum SyncTriggerType {
    MANUAL,
    SCHEDULED,
    API,
    SYSTEM,
    BACKFILL,
    REPLAY
}
