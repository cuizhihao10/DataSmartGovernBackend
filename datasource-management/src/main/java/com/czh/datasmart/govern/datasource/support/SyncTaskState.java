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
 * 这里建模的是“运营视角下任务目前处于什么阶段”，它是任务看板、告警、审批、人工处置的核心字段。
 *
 * 重要设计说明：
 * 1. 这里故意不把审批、SLA、健康、是否需要人工关注全部挤进一个枚举；
 *    参考 PRD 的建议，这些内容应该拆分为辅助维度，避免一个状态承载过多语义。
 * 2. 当前仍保留完整主状态集合，是为了让后续调度器和执行器接入时不需要再调整表结构。
 * 3. 即使当前模块还没有真正的异步执行器，也先把状态语义建模正确，后续才能稳步产品化。
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

    private static final EnumSet<SyncTaskState> TERMINAL_STATES =
            EnumSet.of(SUCCEEDED, FAILED, CANCELLED, ARCHIVED);

    /**
     * 判断当前状态是否为终态。
     * 终态并不一定代表业务上“绝对结束”，但代表常规执行链路已经停住，
     * 后续如果还要继续，通常需要人工重试、强制干预或归档之外的新动作。
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
