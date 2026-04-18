package com.czh.datasmart.govern.datasource.support;

import java.util.Arrays;

/**
 * @Author : Cui
 * @Date: 2026/4/18 23:12
 * @Description DataSmart Govern Backend - ApprovalState.java
 * @Version:1.0.0
 *
 * 审批状态枚举。
 * 审批状态属于任务的辅助状态轴，用来表达“这个任务在治理流程上是否已经被授权执行”，
 * 而不是任务本身当前是否正在运行。
 */
public enum ApprovalState {
    NOT_REQUIRED,
    PENDING,
    APPROVED,
    REJECTED;

    public static ApprovalState fromValue(String value) {
        return Arrays.stream(values())
                .filter(item -> item.name().equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不支持的审批状态: " + value));
    }
}
