/**
 * @Author : Cui
 * @Date: 2026/05/07 21:38
 * @Description DataSmart Govern Backend - SyncAuditActionType.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.support;

/**
 * 数据同步审计动作类型。
 *
 * <p>审计动作使用枚举集中维护，可以避免每个 Service 方法手写字符串导致统计口径不一致。
 */
public enum SyncAuditActionType {
    CREATE_TEMPLATE,
    VALIDATE_TEMPLATE,
    CREATE_TASK,
    RUN_TASK,
    CREATE_EXECUTION,
    UPDATE_CHECKPOINT,
    RECORD_ERROR_SAMPLE,
    ACKNOWLEDGE_ATTENTION,
    RESOLVE_ATTENTION,
    RERUN_ATTENTION_TASK,
    CANCEL_ATTENTION_TASK,
    ARCHIVE_ATTENTION_TASK,
    CREATE_INCIDENT,
    ACKNOWLEDGE_INCIDENT,
    ASSIGN_INCIDENT,
    RESOLVE_INCIDENT,
    CLOSE_INCIDENT
}
