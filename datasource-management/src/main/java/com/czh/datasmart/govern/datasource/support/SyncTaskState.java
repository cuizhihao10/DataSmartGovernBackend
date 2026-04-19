package com.czh.datasmart.govern.datasource.support;

import java.util.Arrays;
import java.util.EnumSet;

/**
 * @Author : Cui
 * @Date: 2026/4/18 23:12
 * @Description DataSmart Govern Backend - SyncTaskState.java
 * @Version:1.0.0
 *
 * 数据同步任务主状态枚举。
 * 这里建模的是“从运营视角看，任务当前处于哪个生命周期阶段”。
 *
 * 设计上故意不把审批、SLA、健康状态、是否需要人工关注等全部塞进一个状态枚举，
 * 因为那样会让一个状态字段承载过多语义，后续很难扩展。
 *
 * 当前保留完整的主状态集合，是为了让后续调度器、执行器和审批中心接入时，
 * 不需要再次推翻任务主状态模型。
 */
public enum SyncTaskState {
    DRAFT,
    CONFIGURED,
    PENDING_APPROVAL,
    SCHEDULED,
    QUEUED,
    RUNNING,
    PAUSED,
    RETRYING,
    PARTIALLY_SUCCEEDED,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    ARCHIVED;

    /**
     * 终态集合。
     * 终态意味着常规执行链路已经结束，若还要继续推进，通常需要重试、强制干预或归档之外的新动作。
     */
    private static final EnumSet<SyncTaskState> TERMINAL_STATES =
            EnumSet.of(SUCCEEDED, FAILED, CANCELLED, ARCHIVED);

    /**
     * 判断当前状态是否为终态。
     */
    public boolean isTerminal() {
        return TERMINAL_STATES.contains(this);
    }

    /**
     * 大小写不敏感解析。
     */
    public static SyncTaskState fromValue(String value) {
        return Arrays.stream(values())
                .filter(item -> item.name().equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不支持的同步任务状态: " + value));
    }
}
