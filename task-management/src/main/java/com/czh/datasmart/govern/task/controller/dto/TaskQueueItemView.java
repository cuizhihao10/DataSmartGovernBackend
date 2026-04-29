/**
 * @Author : Cui
 * @Date: 2026/04/30 00:31
 * @Description DataSmart Govern Backend - TaskQueueItemView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.controller.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 任务队列运营项视图。
 *
 * <p>该 DTO 服务于 `GET /tasks/operations/queue/items`。
 * 它不是数据库实体，也不是普通任务详情，而是面向运营工作台的“可解释列表项”。
 *
 * <p>为什么不直接把 Task 暴露给运营前端：
 * 1. Task 保存的是当前快照字段，前端还需要自己推导排队时长、租约剩余时间、风险原因；
 * 2. 不同前端如果各自推导，很容易出现展示口径不一致；
 * 3. 风险等级和推荐动作属于任务中心的业务知识，应该尽量由后端统一计算；
 * 4. 后续接入告警、SLA、容量规划时，也可以复用同一套风险解释逻辑。
 */
@Data
public class TaskQueueItemView {

    /**
     * 任务 ID。
     */
    private Long id;

    /**
     * 任务名称。
     */
    private String name;

    /**
     * 任务类型。
     */
    private String type;

    /**
     * 当前任务状态。
     */
    private String status;

    /**
     * 任务优先级。
     */
    private String priority;

    /**
     * 当前进度。
     */
    private Integer progress;

    /**
     * 当前执行器 ID。
     */
    private String currentExecutorId;

    /**
     * 当前执行记录 ID。
     */
    private Long currentExecutionRunId;

    /**
     * 入队时间。
     */
    private LocalDateTime queuedTime;

    /**
     * 已排队秒数。
     *
     * <p>如果 queuedTime 在未来，例如 DEFERRED 延迟尚未到期，则该值为 0，
     * 未来剩余时间由 queuedDelayRemainingSeconds 表达。
     */
    private Long queueAgeSeconds;

    /**
     * 距离延迟队列到期还剩多少秒。
     *
     * <p>主要用于 DEFERRED 任务。大于 0 表示任务还不会被执行器认领；
     * 等于 0 表示已经到期或不是延迟任务。
     */
    private Long queuedDelayRemainingSeconds;

    /**
     * 最近心跳时间。
     */
    private LocalDateTime heartbeatTime;

    /**
     * 距离最近心跳已经过去多少秒。
     */
    private Long heartbeatAgeSeconds;

    /**
     * 租约过期时间。
     */
    private LocalDateTime leaseExpireTime;

    /**
     * 租约剩余秒数。
     *
     * <p>负数表示租约已经过期，通常需要触发超时恢复或人工排查执行器状态。
     */
    private Long leaseRemainingSeconds;

    /**
     * 是否需要人工关注。
     */
    private Boolean attentionRequired;

    /**
     * 当前连续退避次数。
     */
    private Integer deferCount;

    /**
     * 最大连续退避次数。
     */
    private Integer maxDeferCount;

    /**
     * 风险等级。
     *
     * <p>当前使用 NORMAL、INFO、WARNING、CRITICAL 四档。
     * 后续可以结合租户 SLA、任务优先级、排队时长分位数进一步细化。
     */
    private String riskLevel;

    /**
     * 风险原因。
     *
     * <p>面向运营人员阅读，解释为什么该任务值得关注。
     */
    private String riskReason;

    /**
     * 推荐处置动作。
     *
     * <p>这里只给出第一版建议，不直接执行动作。
     * 真正的恢复、暂停、取消、扩容仍应由受控接口和权限策略保护。
     */
    private String recommendedAction;

    /**
     * 最新结果摘要或失败原因。
     */
    private String result;
}
