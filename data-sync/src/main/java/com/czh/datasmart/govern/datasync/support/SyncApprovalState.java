/**
 * @Author : Cui
 * @Date: 2026/05/07 21:26
 * @Description DataSmart Govern Backend - SyncApprovalState.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.support;

/**
 * 同步任务审批状态。
 *
 * <p>审批状态独立于主状态，是为了避免把“是否允许执行”和“当前执行到哪里”混进一个字段。
 * 例如一个任务可以是 CONFIGURED 但 PENDING 审批，也可以是 FAILED 但审批仍然 APPROVED。
 */
public enum SyncApprovalState {
    NOT_REQUIRED,
    PENDING,
    APPROVED,
    REJECTED
}
