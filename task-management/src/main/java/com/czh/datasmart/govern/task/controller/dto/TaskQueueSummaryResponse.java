/**
 * @Author : Cui
 * @Date: 2026/04/30 00:24
 * @Description DataSmart Govern Backend - TaskQueueSummaryResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.controller.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 任务队列运营汇总响应。
 *
 * <p>该 DTO 服务于 `GET /tasks/operations/queue/summary`。
 * 与列表接口不同，汇总接口回答的是“队列整体健康如何”：
 * - 当前共有多少运营相关任务；
 * - 各状态分别有多少；
 * - 是否存在需要人工关注、死信、延迟回队列等高风险任务；
 * - 最老的排队任务已经等了多久；
 * - 当前观察到的最大连续退避次数是多少。
 *
 * <p>这些字段看起来像统计报表，但它们对生产系统非常关键。
 * 运营人员通常不是先翻列表，而是先看汇总指标判断是否需要进入详情排障。
 * 后续 Grafana 面板、告警规则、SLA 看板也可以从这类汇总指标逐步演进。
 */
@Data
public class TaskQueueSummaryResponse {

    /**
     * 汇总生成时间。
     *
     * <p>运营页面经常自动刷新，带上 generatedAt 可以帮助前端和排障人员确认数据是否为最新快照。
     */
    private LocalDateTime generatedAt;

    /**
     * 当前过滤条件下的任务总数。
     *
     * <p>如果请求没有指定 includeTerminal=true，则该值默认只统计运营相关状态，
     * 即 PENDING、RUNNING、DEFERRED、DEAD_LETTER、FAILED、PAUSED。
     */
    private Long totalCount;

    /**
     * 按状态分组的任务数量。
     *
     * <p>使用 Map 是为了让状态扩展更灵活。后续新增 SCHEDULED、RETRYING、ARCHIVED 等状态时，
     * 不必立刻修改响应结构，也能在运营台上直接看到新状态分布。
     */
    private Map<String, Long> statusCounts = new LinkedHashMap<>();

    /**
     * 待认领任务数量。
     */
    private Long pendingCount;

    /**
     * 正在运行任务数量。
     */
    private Long runningCount;

    /**
     * 延迟回队列任务数量。
     */
    private Long deferredCount;

    /**
     * 死信任务数量。
     */
    private Long deadLetterCount;

    /**
     * 执行失败任务数量。
     */
    private Long failedCount;

    /**
     * 暂停任务数量。
     */
    private Long pausedCount;

    /**
     * 需要人工关注的任务数量。
     *
     * <p>该值不完全等同于 DEAD_LETTER。
     * 例如租约超时恢复为 FAILED、连续失败、人工标记等场景也可能设置 attentionRequired=true。
     */
    private Long attentionRequiredCount;

    /**
     * 当前过滤条件下最早的 queuedTime。
     *
     * <p>如果该值很早，说明队列里可能有长期积压或长期延迟的任务。
     */
    private LocalDateTime oldestQueuedTime;

    /**
     * 最老 queuedTime 距离当前的秒数。
     *
     * <p>前端可以直接用它显示“最老任务已等待 N 秒/分钟/小时”，也可以用于告警阈值判断。
     */
    private Long oldestQueuedAgeSeconds;

    /**
     * 当前过滤结果中观察到的最大连续退避次数。
     *
     * <p>如果该值持续接近任务的 maxDeferCount，说明容量、配额或下游限流问题正在积累，
     * 需要在进入 DEAD_LETTER 前提前介入。
     */
    private Integer maxObservedDeferCount;
}
