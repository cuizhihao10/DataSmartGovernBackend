package com.czh.datasmart.govern.datasource.controller.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * @Author : Cui
 * @Date: 2026/4/19 20:58
 * @Description DataSmart Govern Backend - SyncQueueAgingScanResult.java
 * @Version:1.0.0
 *
 * 队列老化巡检结果。
 * 巡检不是简单“查出来多少老任务”，它还承担治理语义：
 * - 找出排队时间过长的任务；
 * - 把这些任务标记为需要人工关注；
 * - 为运营或管理员提供需要优先处理的任务列表。
 */
@Data
public class SyncQueueAgingScanResult {

    /**
     * 当前全局队列任务总数。
     */
    private Long queuedTaskCount;

    /**
     * 本次巡检最多扫描多少条候选任务。
     */
    private Integer scanLimit;

    /**
     * 本次实际命中的老化任务数。
     */
    private Integer agedQueuedTaskCount;

    /**
     * 本次被标记为“需要人工关注”的任务数。
     * 如果任务原先就已经处于关注态，这里的数量不会重复累加。
     */
    private Integer markedAttentionTaskCount;

    /**
     * 判定老化的阈值秒数。
     */
    private Integer thresholdSeconds;

    /**
     * 当前命中的最老老化任务的入队时间。
     */
    private LocalDateTime oldestAgedQueuedAt;

    /**
     * 命中的任务 ID 列表。
     */
    private List<Long> taskIds;

    /**
     * 是否建议运营进一步升级处理。
     * 例如本次一下命中很多老化任务，往往意味着执行器容量或上游提交策略需要被复查。
     */
    private Boolean alertSuggested;
}
