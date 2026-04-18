package com.czh.datasmart.govern.datasource.support;

/**
 * @Author : Cui
 * @Date: 2026/4/18 23:12
 * @Description DataSmart Govern Backend - SyncAuditAction.java
 * @Version:1.0.0
 *
 * 同步领域审计动作枚举。
 * 审计动作单独建模的意义在于：
 * 1. 后续审计中心可以按动作类型聚合查询，而不是解析自由文本。
 * 2. 管理员强制干预、审批流、执行状态更新可以明确区分。
 * 3. 未来接入消息通知、告警或事件总线时，可以把动作类型直接作为事件分类依据。
 */
public enum SyncAuditAction {
    CREATE_TEMPLATE,
    UPDATE_TEMPLATE,
    VALIDATE_TEMPLATE,
    CREATE_TASK,
    UPDATE_TASK,
    SUBMIT_APPROVAL,
    APPROVE_TASK,
    REJECT_TASK,
    SCHEDULE_TASK,
    RUN_TASK,
    PAUSE_TASK,
    RESUME_TASK,
    RETRY_TASK,
    CANCEL_TASK,
    FORCE_RETRY_TASK,
    FORCE_CANCEL_TASK,
    OVERRIDE_PRIORITY,
    OVERRIDE_TIMEOUT,
    UPDATE_PROGRESS,
    SAVE_CHECKPOINT,
    COMPLETE_EXECUTION,
    FAIL_EXECUTION,
    ARCHIVE_TASK
}
