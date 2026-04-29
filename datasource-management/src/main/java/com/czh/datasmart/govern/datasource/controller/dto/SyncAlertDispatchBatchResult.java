package com.czh.datasmart.govern.datasource.controller.dto;

import lombok.Data;

import java.util.List;

/**
 * @Author : Cui
 * @Date: 2026/4/24 22:40
 * @Description DataSmart Govern Backend - SyncAlertDispatchBatchResult.java
 * @Version:1.0.0
 *
 * 告警批量投递结果。
 * 这个 DTO 面向的是运营和管理员视角，它回答的不是“某一条告警发没发成功”，
 * 而是“这一轮 outbox 投递总共扫描了多少、认领了多少、实际尝试了多少、成功多少、失败多少”。
 */
@Data
public class SyncAlertDispatchBatchResult {

    /**
     * 本轮扫描到的候选告警数量。
     */
    private Integer candidateCount;

    /**
     * 本轮成功被当前实例认领的告警数量。
     * 在分布式 outbox 模式下，候选数不一定等于认领数。
     */
    private Integer claimedCount;

    /**
     * 本轮实际尝试投递的告警数量。
     */
    private Integer attemptedCount;

    /**
     * 本轮最终成功发送的告警数量。
     */
    private Integer sentCount;

    /**
     * 本轮仍然失败的告警数量。
     */
    private Integer failedCount;

    /**
     * 本轮新进入死信的告警数量。
     */
    private Integer deadLetterCount;

    /**
     * 本轮处理的告警 ID 列表。
     */
    private List<Long> processedAlertIds;
}
