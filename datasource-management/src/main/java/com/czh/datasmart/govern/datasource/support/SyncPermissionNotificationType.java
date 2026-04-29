package com.czh.datasmart.govern.datasource.support;

/**
 * @Author : Cui
 * @Date: 2026/4/24 22:40
 * @Description DataSmart Govern Backend - SyncPermissionNotificationType.java
 * @Version:1.0.0
 *
 * 权限治理通知类型枚举。
 * 当前先聚焦在权限变更申请这条审批链路上，而不是把整个系统所有通知都塞进一个超大枚举里。
 *
 * 第一版优先覆盖两个最重要的通知节点：
 * 1. 提交申请后，提醒审批角色有新的待办；
 * 2. 审批完成后，通知申请人最终结果。
 */
public enum SyncPermissionNotificationType {
    APPROVAL_PENDING,
    APPROVAL_REMINDER,
    APPROVAL_ESCALATED,
    APPROVAL_APPROVED,
    APPROVAL_REJECTED
}
