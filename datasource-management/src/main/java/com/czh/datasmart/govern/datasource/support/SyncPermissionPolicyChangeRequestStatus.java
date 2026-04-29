package com.czh.datasmart.govern.datasource.support;

import java.util.Arrays;

/**
 * @Author : Cui
 * @Date: 2026/4/24 22:03
 * @Description DataSmart Govern Backend - SyncPermissionPolicyChangeRequestStatus.java
 * @Version:1.0.0
 *
 * 权限绑定变更申请状态枚举。
 * 这一层的目标，是把“高风险权限变更是否已经经过人工审核”显式建模出来，
 * 避免所有权限策略修改都直接变成实时生效的写操作。
 *
 * 当前先保留最小闭环的三种状态：
 * 1. `PENDING_APPROVAL`：已提交，等待审批。
 * 2. `EXECUTED`：审批通过并已实际落库执行。
 * 3. `REJECTED`：审批未通过，不再执行。
 *
 * 之所以当前不单独拆 `APPROVED_PENDING_EXECUTION`，
 * 是因为第一版实现采用“审批通过后同事务内立即执行”的方式，先把主链路跑通。
 */
public enum SyncPermissionPolicyChangeRequestStatus {
    PENDING_APPROVAL,
    EXECUTED,
    REJECTED;

    public static SyncPermissionPolicyChangeRequestStatus fromValue(String value) {
        return Arrays.stream(values())
                .filter(item -> item.name().equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不支持的权限变更申请状态: " + value));
    }
}
