package com.czh.datasmart.govern.datasource.controller.dto;

import lombok.Data;

import java.util.List;

/**
 * @Author : Cui
 * @Date: 2026/4/19 19:19
 * @Description DataSmart Govern Backend - SyncLeaseRecoveryResult.java
 * @Version:1.0.0
 *
 * 过期租约恢复结果。
 * 这不是普通用户日常最常看的对象，但它对平台运维和调度恢复非常重要。
 *
 * 它主要回答三个问题：
 * 1. 本次一共扫描并恢复了多少条过期租约任务。
 * 2. 哪些任务被自动重新放回队列，哪些任务被直接标记失败等待人工处理。
 * 3. 具体涉及哪些任务和执行记录，便于后续联动审计、告警和事件复盘。
 */
@Data
public class SyncLeaseRecoveryResult {

    /**
     * 本次扫描到的过期任务数量。
     */
    private Integer expiredTaskCount;

    /**
     * 成功恢复的任务数量。
     */
    private Integer recoveredTaskCount;

    /**
     * 被重新放回队列的任务数量。
     */
    private Integer requeuedTaskCount;

    /**
     * 被直接标记失败的任务数量。
     */
    private Integer failedTaskCount;

    /**
     * 本次处理涉及的任务 ID 列表。
     */
    private List<Long> taskIds;

    /**
     * 本次处理涉及的执行记录 ID 列表。
     */
    private List<Long> executionIds;
}
