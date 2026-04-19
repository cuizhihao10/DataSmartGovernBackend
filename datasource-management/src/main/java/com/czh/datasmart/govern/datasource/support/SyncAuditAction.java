package com.czh.datasmart.govern.datasource.support;

/**
 * @Author : Cui
 * @Date: 2026/4/19 20:58
 * @Description DataSmart Govern Backend - SyncAuditAction.java
 * @Version:1.0.0
 *
 * 同步领域审计动作枚举。
 * 审计动作单独建模的价值在于：
 * 1. 后续审计中心可以按动作类型聚合分析，而不需要去解析自由文本；
 * 2. 普通用户动作、审批动作、管理员强制动作、执行器回调动作可以被明确区分；
 * 3. 新增了队列健康查看和队列老化巡检后，平台治理类动作也能进入统一审计分类。
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
    QUEUE_TASK,
    CLAIM_TASK,
    RUN_TASK,
    RECOVER_EXPIRED_LEASE,
    PAUSE_TASK,
    RESUME_TASK,
    RETRY_TASK,
    CANCEL_TASK,
    FORCE_RETRY_TASK,
    FORCE_CANCEL_TASK,
    OVERRIDE_PRIORITY,
    OVERRIDE_TIMEOUT,
    INSPECT_QUEUE_HEALTH,
    SCAN_QUEUE_AGING,
    UPDATE_PROGRESS,
    SAVE_CHECKPOINT,
    COMPLETE_EXECUTION,
    FAIL_EXECUTION,
    ARCHIVE_TASK
}
