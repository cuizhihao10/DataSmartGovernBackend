package com.czh.datasmart.govern.datasource.controller.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * @Author : Cui
 * @Date: 2026/4/19 20:58
 * @Description DataSmart Govern Backend - SyncQueueHealthSnapshot.java
 * @Version:1.0.0
 *
 * 同步队列健康快照。
 * 这个对象服务的不是单个任务，而是“当前平台排队面”的运营观察：
 * - 队列总长度是否接近保护阈值；
 * - 是否有单个租户积压过多；
 * - 是否已经出现排队老化；
 * - 当前更像是健康、预警还是饱和状态。
 *
 * 之所以单独做成 DTO，而不是直接返回一堆散字段，是为了方便后续：
 * 1. 前端健康看板直接渲染；
 * 2. 告警规则基于统一结构判断；
 * 3. 未来演进到 observability 模块时可以平滑复用。
 */
@Data
public class SyncQueueHealthSnapshot {

    /**
     * 当前全局待执行任务数。
     */
    private Long globalQueuedCount;

    /**
     * 全局待执行任务上限。
     */
    private Integer maxQueuedTasksGlobal;

    /**
     * 用于触发全局预警的阈值。
     * 它通常比“硬上限”更小，目的是让运营在真正打满前就看到风险。
     */
    private Integer queueAlertThresholdGlobal;

    /**
     * 当前积压最多的租户 ID。
     * 如果没有租户维度信息，这里允许为空。
     */
    private Long highestBacklogTenantId;

    /**
     * 当前积压最多租户的排队任务数。
     */
    private Long highestBacklogTenantQueuedCount;

    /**
     * 单租户排队上限。
     */
    private Integer maxQueuedTasksPerTenant;

    /**
     * 单租户预警阈值。
     */
    private Integer queueAlertThresholdPerTenant;

    /**
     * 已经超过老化阈值的待执行任务数。
     */
    private Long agedQueuedTaskCount;

    /**
     * 当前队列中最老任务的 ID。
     */
    private Long oldestQueuedTaskId;

    /**
     * 当前最老任务的入队时间。
     */
    private LocalDateTime oldestQueuedAt;

    /**
     * 当前最老任务已经排队了多少秒。
     */
    private Long oldestQueuedDurationSeconds;

    /**
     * 当前队列压力等级。
     * 先用字符串表达，便于后续前后端和告警模块按文本常量对齐。
     */
    private String pressureLevel;

    /**
     * 是否已经需要人工关注。
     * 一旦达到预警阈值、单租户积压过高或出现老化任务，就会置为 true。
     */
    private Boolean attentionRequired;

    /**
     * 给运营人员的建议动作摘要。
     */
    private String recommendation;
}
