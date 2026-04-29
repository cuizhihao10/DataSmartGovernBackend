package com.czh.datasmart.govern.datasource.support;

/**
 * @Author : Cui
 * @Date: 2026/4/24 23:18
 * @Description DataSmart Govern Backend - SyncAuditAction.java
 * @Version:1.0.0
 *
 * 同步治理域审计动作枚举。
 * 这一层的作用是把“发生了什么治理动作”沉淀成稳定枚举，而不是散落在字符串日志里。
 *
 * 当前重点覆盖：
 * 1. 模板、任务、执行器、队列、告警这些同步控制面动作；
 * 2. 权限绑定、权限变更申请、审批委托这些权限治理动作；
 * 3. 方便后续做审计报表、行为检索、异常排障和风险复盘。
 */
public enum SyncAuditAction {
    SUBMIT_PERMISSION_POLICY_CHANGE_REQUEST,
    APPROVE_PERMISSION_POLICY_CHANGE_REQUEST,
    REJECT_PERMISSION_POLICY_CHANGE_REQUEST,
    REPLACE_PERMISSION_POLICY_BINDINGS,
    CREATE_PERMISSION_APPROVAL_DELEGATE_RULE,
    DISABLE_PERMISSION_APPROVAL_DELEGATE_RULE,
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
